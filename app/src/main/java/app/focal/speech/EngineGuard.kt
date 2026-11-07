package app.focal.speech

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.UUID

/** Why transcription waits while the guard is blocked (a note's stored reason; the interface says spec 5.4's words). */
internal const val ENGINE_STOPPED = "The speech model could not load. Saved audio waits for Try again."

/** Where the speech work can take the whole process down with it: a native crash or an out-of-memory kill. */
enum class EngineStage {
    /** Copying the model out of the APK (first launch, or after an update that changed the model). */
    INSTALL,
    /** Creating Silero VAD, the first call into the native speech runtime when a note is segmented. */
    VAD_OPEN,
    /** Silero VAD deciding speech while a note's saved audio is planned into windows. */
    SEGMENT,
    /** Creating the recognizer: most of a gigabyte, and the first call into ONNX Runtime's transducer. */
    RECOGNIZER,
    /** Decoding one window and committing its words. */
    DECODE,
}

/** This process, as the guard's marker names it: [token] tells it from any earlier process with the same [pid]. */
data class ProcessIdentity(val token: String, val pid: Int) {
    companion object {
        fun current() = ProcessIdentity(UUID.randomUUID().toString(), Process.myPid())
    }
}

/** How a process ended, as Android recorded it ([ApplicationExitInfo], API 30+). */
data class ProcessExit(val reason: Int, val importance: Int) {
    /**
     * Whether the death counts against the speech work it interrupted. Not when the person or the system ended Focal
     * for its own reasons (Force stop, an update, a permission change, the process it depended on), nor when Android
     * reclaimed an idle cached process; a native crash, a Java crash, an out-of-memory kill of a working process, an
     * exit from inside the runtime and anything unknown do.
     */
    // The newer reasons are only ever reported by the Android versions that define them.
    @SuppressLint("InlinedApi")
    fun counts(): Boolean = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_DEPENDENCY_DIED,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        ApplicationExitInfo.REASON_FREEZER -> false
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_UNKNOWN -> importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        else -> true
    }
}

/**
 * A durable guard around the speech work that can kill the process: the model install, Silero VAD, the recognizer
 * and every decode. Without it a native crash or an out-of-memory kill inside the runtime comes back at every launch,
 * because a foreground start resumes waiting transcription at once.
 *
 * Before a guarded stage runs, the stage is written to a small file (with this process's [ProcessIdentity]); after it
 * returns or throws, it is cleared. A process that starts and finds a marker written by another process knows that
 * process died inside the stage, and counts a death against it (unless Android says the person or the system ended
 * it, [ProcessExit.counts]). A recognizer that fails to load counts too, as the loads that kept failing always did.
 *
 * At [MAX_DEATHS] counted deaths the guard is [blocked]: nothing starts the speech work by itself any more (no
 * automatic resume, no install, no VAD, no recognizer), and waiting notes show "Model couldn't load" with Try again
 * (spec 5.4). Capture never passes through the guard, so recording keeps working. The person's Try again [reset]s it
 * for one attempt: until a decode completes, a single death blocks it again. A completed decode proves the whole chain
 * and clears every count; a completed install clears the deaths counted at install. Nothing else clears them, so
 * stages that each succeed once cannot hide a later stage that keeps dying.
 *
 * The file is replaced by a rename and never synced: it must survive the process, not the device. Thread-safe.
 */
class EngineGuard @VisibleForTesting internal constructor(
    private val file: File,
    private var process: ProcessIdentity,
    /** Android's record of how the process [pid] ended, at or after [since] (epoch ms); null when unknown. */
    private val exitOf: (pid: Int, since: Long) -> ProcessExit?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    /** The stages running in this process, in the order they began (a stage may run on two threads at once). */
    private val active = ArrayList<EngineStage>()
    private var since = 0L
    private val deaths = sortedMapOf<EngineStage, Int>()
    /** The person's Try again cleared the counts and no decode has completed since: one death blocks again. */
    private var retried = false
    private val blockedState = MutableStateFlow(false)

    /**
     * True once the speech work died or failed [MAX_DEATHS] times without a decode completing in between, or once
     * after the person's Try again.
     */
    val blocked: StateFlow<Boolean> = blockedState.asStateFlow()

    /** The stage the previous process died in, when that death was counted at this start; null otherwise. */
    @Volatile var lastDeath: EngineStage? = null
        private set

    init {
        start()
    }

    /** Reads the file and counts the previous process's death inside a stage. Runs once per process. */
    private fun start() = synchronized(lock) {
        val saved = read()
        deaths.clear()
        for (stage in EngineStage.entries) saved.getProperty(DEATHS + stage.name)?.toIntOrNull()?.takeIf { it > 0 }?.let { deaths[stage] = it }
        retried = saved.getProperty(RETRIED) == "true"
        val inside = saved.getProperty(INSIDE)?.let { name -> EngineStage.entries.firstOrNull { it.name == name } }
        lastDeath = null
        if (inside != null && saved.getProperty(PROCESS) != process.token) {
            val pid = saved.getProperty(PID)?.toIntOrNull()
            val at = saved.getProperty(SINCE)?.toLongOrNull() ?: 0L
            val exit = pid?.let { runCatching { exitOf(it, at) }.getOrNull() }
            if (exit?.counts() != false) {
                deaths[inside] = (deaths[inside] ?: 0) + 1
                lastDeath = inside
            }
            log("The previous process ended inside $inside (${exit?.let { "reason ${it.reason}, importance ${it.importance}" } ?: "no exit record"})")
            write()
        } else if (inside != null) {
            // Written by this very process: nothing to count (only a test restarts the guard in one process).
            write()
        }
        publish()
    }

    /** Whether the speech work may start by itself. */
    fun allows(): Boolean = !blockedState.value

    /** Runs [block] inside [stage]: a death before it returns or throws is counted at the next start. */
    inline fun <T> inside(stage: EngineStage, block: () -> T): T {
        enter(stage)
        try {
            return block()
        } finally {
            leave(stage)
        }
    }

    /** Marks [stage] as running in this process, on disk, before the call that may kill the process. */
    fun enter(stage: EngineStage) = synchronized(lock) {
        if (active.isEmpty()) since = clock()
        active += stage
        write()
    }

    /** [stage] returned or threw: it no longer runs here. */
    fun leave(stage: EngineStage) = synchronized(lock) {
        if (!active.remove(stage)) return@synchronized
        write()
    }

    /**
     * [stage] finished its work. A decode clears every count and ends a Try again's single attempt (each stage before
     * it worked); an install clears the deaths counted at install.
     */
    fun completed(stage: EngineStage) = synchronized(lock) {
        val changed = when (stage) {
            EngineStage.DECODE -> (deaths.isNotEmpty() || retried).also { deaths.clear(); retried = false }
            EngineStage.INSTALL -> deaths.remove(EngineStage.INSTALL) != null
            else -> false
        }
        if (changed) { write(); publish() }
    }

    /** [stage] failed without killing the process, in a way that counts like a death (a recognizer that cannot load). */
    fun failed(stage: EngineStage) = synchronized(lock) {
        deaths[stage] = (deaths[stage] ?: 0) + 1
        write()
        publish()
    }

    /**
     * The person's Try again: every count is forgotten and the speech work may start again, for one attempt. Until a
     * decode completes, one death or failed load blocks the guard again at once. A crash that happens every time then
     * costs one process per Try again: the next launch stays blocked instead of resuming the work and crashing again.
     */
    fun reset() = synchronized(lock) {
        if (deaths.isEmpty()) return@synchronized
        deaths.clear()
        retried = true
        write()
        publish()
    }

    /** Device tests: forgets every count and any Try again, as on a fresh install. */
    @VisibleForTesting
    fun clearForTests() = synchronized(lock) {
        deaths.clear()
        retried = false
        write()
        publish()
    }

    /** The deaths and failures counted at each stage. */
    fun deaths(): Map<EngineStage, Int> = synchronized(lock) { deaths.toMap() }

    /** The stage this process has marked on disk, read back from the file; null when none is. */
    @VisibleForTesting
    fun markedOnDisk(): EngineStage? = synchronized(lock) {
        read().getProperty(INSIDE)?.let { name -> EngineStage.entries.firstOrNull { it.name == name } }
    }

    /**
     * Device tests: this guard starts again as the next process would, as [identity], so a marker left on disk by this
     * one (restored from a copy taken inside a stage) is a death of "the previous process".
     */
    @VisibleForTesting
    fun restartForTests(identity: ProcessIdentity = ProcessIdentity(UUID.randomUUID().toString(), -1)) {
        synchronized(lock) {
            active.clear()
            process = identity
        }
        start()
    }

    private fun publish() {
        val counted = deaths.values.sum()
        blockedState.value = counted >= MAX_DEATHS || retried && counted > 0
    }

    private fun read(): Properties = Properties().also { saved ->
        try {
            if (file.isFile) file.inputStream().use { saved.load(it) }
        } catch (error: Exception) {
            // A damaged file counts nothing: at worst one more attempt runs before the guard blocks.
            log("Could not read the engine guard", error)
            saved.clear()
        }
    }

    /** Replaces the file by a rename, so a death mid-write leaves the previous state whole. */
    private fun write() {
        val state = Properties()
        deaths.forEach { (stage, count) -> state.setProperty(DEATHS + stage.name, count.toString()) }
        if (retried) state.setProperty(RETRIED, "true")
        active.lastOrNull()?.let { stage ->
            state.setProperty(INSIDE, stage.name)
            state.setProperty(PROCESS, process.token)
            state.setProperty(PID, process.pid.toString())
            state.setProperty(SINCE, since.toString())
        }
        try {
            val partial = File(file.path + ".partial")
            partial.outputStream().use { state.store(it, null) }
            Files.move(partial.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: IOException) {
            // The guard cannot protect this attempt (storage is failing); the speech work goes on as without it.
            log("Could not write the engine guard", error)
        }
    }

    private fun log(message: String, error: Throwable? = null) {
        runCatching { if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error) }
    }

    companion object {
        /** Deaths or failed loads, without a decode completing in between, after which the work waits for Retry. */
        const val MAX_DEATHS = 2
        private const val TAG = "FocalEngineGuard"
        private const val DEATHS = "deaths."
        private const val RETRIED = "retried"
        private const val INSIDE = "inside"
        private const val PROCESS = "process"
        private const val PID = "pid"
        private const val SINCE = "since"

        /** The app's guard, in no-backup storage: its counts belong to this device's runtime, not to a backup. */
        fun open(context: Context): EngineGuard =
            EngineGuard(File(context.noBackupFilesDir, "speech-guard"), ProcessIdentity.current(), exits(context))

        private fun exits(context: Context): (Int, Long) -> ProcessExit? = { pid, since ->
            if (Build.VERSION.SDK_INT >= 30) exitRecord(context, pid, since) else null
        }

        @RequiresApi(30)
        private fun exitRecord(context: Context, pid: Int, since: Long): ProcessExit? =
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, pid, 1)
                .firstOrNull { it.pid == pid && it.timestamp >= since }
                ?.let { ProcessExit(it.reason, it.importance) }
    }
}
