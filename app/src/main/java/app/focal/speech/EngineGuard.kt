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
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.UUID

/** Why transcription waits while the guard is blocked, for logs; notes store [app.focal.data.WaitCause.EngineStopped]. */
internal const val ENGINE_STOPPED = "The speech model could not load. Saved audio waits for Try again."

/** Where the speech work can take the whole process down with it: a native crash or an out-of-memory kill. */
enum class EngineStage {
    /**
     * Copying the model out of the APK (first launch, or after an update that changed the model). Its deaths are
     * logged but never block ([EngineGuard.blocked]).
     */
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

/**
 * What the guard's last counted death or failure was, as far as Android or the runtime said: the person is told
 * "Not enough free memory" only when it is known ([MEMORY]), never as a guess.
 */
enum class EngineFault {
    /** A confirmed OutOfMemoryError, or Android's record of a low-memory kill of the working process (API 30+). */
    MEMORY,
    /** Anything else: a native or Java crash, a recognizer that failed to load, a death Android did not explain. */
    OTHER,
}

/** What a dying process left in its crash record ([EngineGuard]): the crash that ended it, as it ended. */
enum class Crash(internal val line: String) {
    /** A fatal signal in native code (the bridge's handlers: SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT). */
    NATIVE("native"),
    /** An uncaught Java exception. */
    JAVA("java"),
    /** An uncaught OutOfMemoryError: a confirmed out-of-memory. */
    OUT_OF_MEMORY("oom"),
}

/**
 * How a death inside a stage counts before API 30, where Android keeps no exit record and a swipe-away from Recents,
 * Force stop or a reboot looks like any other death. A [Crash] the process recorded as it died counts. A death without
 * one counts only from the second in a row that is quick (its stage began within [QUICK_MS] of the foreground start's
 * automatic resume), the shape of a low-memory kill that comes back at every launch; the person rarely swipes Focal
 * away that fast that often.
 */
internal object LegacyDeaths {
    const val QUICK_MS = 60_000L
    const val QUICK_IN_A_ROW = 2

    /** Whether the death [counts], and the quick deaths in a row it leaves ([quickInARow]). */
    data class Verdict(val counts: Boolean, val quickInARow: Int)

    fun judge(crash: Crash?, quick: Boolean, quickInARow: Int): Verdict {
        val row = if (quick) quickInARow + 1 else 0
        return Verdict(crash != null || row >= QUICK_IN_A_ROW, row)
    }
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

    /** Android ended the process to reclaim memory. */
    // Only ever reported by the Android versions that define it.
    @get:SuppressLint("InlinedApi")
    val lowMemory: Boolean get() = reason == ApplicationExitInfo.REASON_LOW_MEMORY
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
 * Deaths at [EngineStage.INSTALL] are counted and logged, but they never block. The install is Java I/O with little
 * memory and no native speech code, so a death there is almost always the person or the system: a swipe-away, Force
 * stop or reboot during the first launch's copy, which Android before API 30 keeps no exit record of. Blocking on them
 * would leave a fresh install without its model, and with no note yet to offer Try again.
 *
 * The guard keeps what the last counted death or failure was ([EngineFault], with its stage) while the counts last, so
 * the interface can say "Not enough free memory" for a confirmed out-of-memory and a neutral sentence otherwise.
 *
 * Each process also keeps a crash record next to the file, armed at its first stage: its token, then one line per crash
 * as it dies, from the native bridge's signal handlers ([SherpaBridge.installCrashHandlers]) and the Java uncaught
 * exception handler ([open] installs both). From API 30 Android's exit record decides whether a death counts and the
 * crash record can only add that it was an OutOfMemoryError; before API 30 there is no exit record, and
 * [LegacyDeaths] decides from the crash record and how soon the death came after an automatic resume.
 *
 * The file is replaced by a rename and never synced: it must survive the process, not the device. Thread-safe.
 */
class EngineGuard @VisibleForTesting internal constructor(
    private val file: File,
    private var process: ProcessIdentity,
    /** Android's record of how the process [pid] ended, at or after [since] (epoch ms); null when unknown. */
    private val exitOf: (pid: Int, since: Long) -> ProcessExit?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Android keeps exit records (API 30+): they decide. Before, [LegacyDeaths] does. */
    private val exitRecords: Boolean = true,
    /** Arms the native bridge's crash handlers on this record, at the first stage that runs native code. */
    private val nativeCrashes: ((record: File) -> Unit)? = null,
) {
    private val crashRecord = File(file.path + ".crash")
    private val lock = Any()
    /** The stages running in this process, in the order they began (a stage may run on two threads at once). */
    private val active = ArrayList<EngineStage>()
    private var since = 0L
    private val deaths = sortedMapOf<EngineStage, Int>()
    /** The person's Try again cleared the counts and no decode has completed since: one death blocks again. */
    private var retried = false
    /** The last counted death or failure, kept while the counts last. */
    private var fault: EngineFault? = null
    private var faultStage: EngineStage? = null
    /** Quick deaths without a crash in a row, before API 30 ([LegacyDeaths]). */
    private var quickInARow = 0
    /** When the foreground start's automatic resume began in this process (epoch ms), or 0. */
    private var resumedAt = 0L
    /** The work running now resumes waiting notes by itself, rather than for the person or a recording. */
    private var automaticWork = false
    /** This process's crash record, open for appending once its first stage armed it. */
    @Volatile private var crashOut: FileOutputStream? = null
    private var nativeArmed = false
    /** Some stage runs now: a Java crash is worth recording. */
    @Volatile private var marked = false
    private val blockedState = MutableStateFlow(false)
    private val stopState = MutableStateFlow<EngineFault?>(null)

    /**
     * True once the speech work died or failed [MAX_DEATHS] times without a decode completing in between, or once
     * after the person's Try again. Deaths at install do not count here.
     */
    val blocked: StateFlow<Boolean> = blockedState.asStateFlow()

    /** Why the guard is [blocked]: the last death or failure it counted; null while it allows the work. */
    val stop: StateFlow<EngineFault?> = stopState.asStateFlow()

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
        fault = saved.getProperty(FAULT)?.let { name -> EngineFault.entries.firstOrNull { it.name == name } }
        faultStage = saved.getProperty(FAULT_STAGE)?.let { name -> EngineStage.entries.firstOrNull { it.name == name } }
        quickInARow = saved.getProperty(QUICK)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val inside = saved.getProperty(INSIDE)?.let { name -> EngineStage.entries.firstOrNull { it.name == name } }
        val token = saved.getProperty(PROCESS)
        lastDeath = null
        if (inside != null && token != process.token) {
            val pid = saved.getProperty(PID)?.toIntOrNull()
            val at = saved.getProperty(SINCE)?.toLongOrNull() ?: 0L
            val crash = crashOf(token)
            val resumed = saved.getProperty(RESUMED)?.toLongOrNull()
            val quick = resumed != null && at - resumed in 0..LegacyDeaths.QUICK_MS
            val exit = if (exitRecords) pid?.let { runCatching { exitOf(it, at) }.getOrNull() } else null
            val counts = if (exitRecords) {
                exit?.counts() != false
            } else {
                val verdict = LegacyDeaths.judge(crash, quick, quickInARow)
                quickInARow = verdict.quickInARow
                verdict.counts
            }
            if (counts) {
                count(inside, if (exit?.lowMemory == true || crash == Crash.OUT_OF_MEMORY) EngineFault.MEMORY else EngineFault.OTHER)
                lastDeath = inside
            }
            val record = if (exitRecords) exit?.let { "reason ${it.reason}, importance ${it.importance}" } ?: "no exit record"
                else "before API 30, ${if (quick) "quick" else "not quick"}"
            log("The previous process ended inside $inside ($record, ${crash ?: "no crash recorded"}): ${if (counts) "counted" else "not counted"}")
            write()
        } else if (inside != null) {
            // Written by this very process: nothing to count (only a test restarts the guard in one process).
            write()
        } else if (quickInARow > 0) {
            // The previous process ended outside the speech work: the quick deaths were not in a row.
            quickInARow = 0
            write()
        }
        publish()
    }

    /** What the crash record says ended the process [token], or null when it recorded no crash (or is another's). */
    private fun crashOf(token: String?): Crash? {
        if (token == null) return null
        val lines = try {
            if (crashRecord.isFile && crashRecord.length() <= MAX_RECORD_BYTES) crashRecord.readLines() else return null
        } catch (error: Exception) {
            log("Could not read the crash record", error)
            return null
        }
        if (lines.firstOrNull() != token) return null
        val crashes = lines.drop(1).mapNotNull { line -> Crash.entries.firstOrNull { line.startsWith(it.line) } }.toSet()
        return when {
            Crash.OUT_OF_MEMORY in crashes -> Crash.OUT_OF_MEMORY
            Crash.JAVA in crashes -> Crash.JAVA
            Crash.NATIVE in crashes -> Crash.NATIVE
            else -> null
        }
    }

    /**
     * Starts this process's crash record (its token, so the next process knows whose it is) at the first stage, and arms
     * the native handlers on it at the first stage that may run native code. Nothing here may keep the stage from running.
     */
    private fun armCrashRecord(stage: EngineStage) {
        if (crashOut == null) {
            try {
                FileOutputStream(crashRecord).use { it.write("${process.token}\n".toByteArray()) }
                crashOut = FileOutputStream(crashRecord, true)
            } catch (error: IOException) {
                log("Could not start the crash record", error)
                return
            }
        }
        if (nativeArmed || stage == EngineStage.INSTALL) return
        nativeArmed = true
        val arm = nativeCrashes ?: return
        try {
            arm(crashRecord)
        } catch (error: LinkageError) {
            log("Native crashes are not recorded", error)
        } catch (error: RuntimeException) {
            log("Native crashes are not recorded", error)
        }
    }

    /**
     * An uncaught exception is ending this process (the default handler [open] installs): recorded while a stage runs,
     * with whether it was an OutOfMemoryError. Writes bytes allocated in advance to the record opened in advance.
     */
    internal fun crashed(error: Throwable) {
        val out = crashOut ?: return
        if (!marked) return
        val memory = generateSequence(error) { it.cause }.take(MAX_CAUSES).any { it is OutOfMemoryError }
        try {
            out.write(if (memory) OOM_LINE else JAVA_LINE)
        } catch (_: IOException) {
            // Nothing more can be done for a process that is ending.
        }
    }

    /** The foreground start's automatic resume of waiting notes began (DRAIN): a stage that dies soon after is quick. */
    fun resumedAutomatically() = synchronized(lock) { resumedAt = clock() }

    /** The work about to run resumes waiting notes by itself ([automatic]), or is the person's or a recording's. */
    fun working(automatic: Boolean) = synchronized(lock) {
        if (automaticWork == automatic) return@synchronized
        automaticWork = automatic
        if (active.isNotEmpty()) write()
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
        armCrashRecord(stage)
        if (active.isEmpty()) since = clock()
        active += stage
        marked = true
        write()
    }

    /** [stage] returned or threw: it no longer runs here. */
    fun leave(stage: EngineStage) = synchronized(lock) {
        if (!active.remove(stage)) return@synchronized
        marked = active.isNotEmpty()
        write()
    }

    /**
     * [stage] finished its work. A decode clears every count and ends a Try again's single attempt (each stage before
     * it worked); an install clears the deaths counted at install.
     */
    fun completed(stage: EngineStage) = synchronized(lock) {
        val changed = when (stage) {
            EngineStage.DECODE -> (deaths.isNotEmpty() || retried || quickInARow > 0).also {
                deaths.clear(); retried = false; quickInARow = 0; forgetFault()
            }
            EngineStage.INSTALL -> deaths.remove(EngineStage.INSTALL) != null
            else -> false
        }
        if (changed) { write(); publish() }
    }

    /**
     * [stage] failed without killing the process, in a way that counts like a death: a recognizer that cannot load, or
     * an OutOfMemoryError in the native work ([EngineFault.MEMORY]).
     */
    fun failed(stage: EngineStage, fault: EngineFault = EngineFault.OTHER) = synchronized(lock) {
        count(stage, fault)
        write()
        publish()
    }

    /** The last death or failure that counts, at [stage]: install's are counted but never named. */
    private fun count(stage: EngineStage, fault: EngineFault) {
        deaths[stage] = (deaths[stage] ?: 0) + 1
        if (stage != EngineStage.INSTALL) { this.fault = fault; faultStage = stage }
    }

    /** The last death or failure that blocks or brings a block nearer, and its stage; null once the counts are gone. */
    fun lastFault(): Pair<EngineStage, EngineFault>? = synchronized(lock) {
        val stage = faultStage ?: return@synchronized null
        fault?.let { stage to it }
    }

    /**
     * The person's Try again: every count is forgotten and the speech work may start again, for one attempt. Until a
     * decode completes, one death or failed load blocks the guard again at once. A crash that happens every time then
     * costs one process per Try again: the next launch stays blocked instead of resuming the work and crashing again.
     * True when the guard was blocked: the work it held back, every waiting note's, may go on.
     */
    fun reset(): Boolean = synchronized(lock) {
        val wasBlocked = blockedState.value
        if (deaths.isEmpty()) return@synchronized wasBlocked
        if (blocking() > 0) retried = true
        deaths.clear()
        forgetFault()
        write()
        publish()
        wasBlocked
    }

    /** Device tests: forgets every count and any Try again, as on a fresh install. */
    @VisibleForTesting
    fun clearForTests() = synchronized(lock) {
        deaths.clear()
        retried = false
        quickInARow = 0
        forgetFault()
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
     * one (restored from a copy taken inside a stage) is a death of "the previous process", which ended with [crash]
     * (as its crash record says: a native crash by default, so the death counts before API 30 as well).
     */
    @VisibleForTesting
    fun restartForTests(identity: ProcessIdentity = ProcessIdentity(UUID.randomUUID().toString(), -1), crash: Crash? = Crash.NATIVE) {
        synchronized(lock) {
            closeCrashRecord()
            crashRecord.writeText(process.token + "\n" + (crash?.let { it.line + "\n" } ?: ""))
            active.clear()
            marked = false
            nativeArmed = false
            automaticWork = false
            resumedAt = 0
            process = identity
        }
        start()
    }

    private fun closeCrashRecord() {
        runCatching { crashOut?.close() }
        crashOut = null
    }

    /** The deaths and failures that can block: every stage's but the install's. */
    private fun blocking(): Int = deaths.entries.sumOf { (stage, count) -> if (stage == EngineStage.INSTALL) 0 else count }

    private fun forgetFault() {
        fault = null
        faultStage = null
    }

    private fun publish() {
        val counted = blocking()
        val blocked = counted >= MAX_DEATHS || retried && counted > 0
        stopState.value = if (blocked) fault ?: EngineFault.OTHER else null
        blockedState.value = blocked
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
        fault?.let { state.setProperty(FAULT, it.name) }
        faultStage?.let { state.setProperty(FAULT_STAGE, it.name) }
        if (quickInARow > 0) state.setProperty(QUICK, quickInARow.toString())
        active.lastOrNull()?.let { stage ->
            state.setProperty(INSIDE, stage.name)
            state.setProperty(PROCESS, process.token)
            state.setProperty(PID, process.pid.toString())
            state.setProperty(SINCE, since.toString())
            if (automaticWork && resumedAt > 0) state.setProperty(RESUMED, resumedAt.toString())
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
        private const val FAULT = "fault"
        private const val FAULT_STAGE = "fault.stage"
        private const val QUICK = "quick"
        private const val RESUMED = "resumed"
        /** A crash record is a token and a few lines: anything larger is not one. */
        private const val MAX_RECORD_BYTES = 4_096L
        private const val MAX_CAUSES = 8
        private val JAVA_LINE = (Crash.JAVA.line + "\n").toByteArray()
        private val OOM_LINE = (Crash.OUT_OF_MEMORY.line + "\n").toByteArray()
        private const val INSIDE = "inside"
        private const val PROCESS = "process"
        private const val PID = "pid"
        private const val SINCE = "since"

        /** The app's guard, in no-backup storage: its counts belong to this device's runtime, not to a backup. */
        fun open(context: Context): EngineGuard =
            opened(File(context.noBackupFilesDir, "speech-guard"), exits(context), exitRecords = Build.VERSION.SDK_INT >= 30)

        /**
         * This process's guard over [file], recording the native and Java crashes that end the process. The app opens
         * one ([open]); a device test's probe process opens its own over a file of its own.
         */
        internal fun opened(file: File, exitOf: (pid: Int, since: Long) -> ProcessExit?, exitRecords: Boolean): EngineGuard =
            EngineGuard(file, ProcessIdentity.current(), exitOf, exitRecords = exitRecords,
                nativeCrashes = { record -> SherpaBridge.installCrashHandlers(record.path) }).also { guard ->
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                    try {
                        guard.crashed(error)
                    } catch (_: Throwable) {
                        // The process ends either way; the handler before this one must still run.
                    }
                    previous?.uncaughtException(thread, error)
                }
            }

        /** Android's exit records (API 30+) of the app's processes, as [EngineGuard] reads them. */
        fun exitRecordsOf(context: Context): (pid: Int, since: Long) -> ProcessExit? = exits(context)

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
