package app.focal

import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.focal.data.NoteStore
import app.focal.data.Preferences
import app.focal.data.RetiredFiles
import app.focal.recording.AppPresence
import app.focal.recording.RecordingService
import app.focal.recording.ServiceNotifications
import app.focal.speech.EngineGuard
import app.focal.speech.SpeechModels
import kotlinx.coroutines.*
import java.io.File

class FocalApplication : Application() {
    lateinit var store: NoteStore
        private set
    lateinit var preferences: Preferences
        private set
    /**
     * Counts the processes that died inside the speech work (and recognizers that failed to load); once it blocks,
     * nothing starts that work by itself until the person's Retry.
     */
    lateinit var engineGuard: EngineGuard
        private set
    /** The bundled speech model, installed in the background from the first launch. */
    lateinit var models: SpeechModels
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Device tests that stage waiting notes turn the automatic foreground resume off (debug builds only). */
    @VisibleForTesting
    @Volatile internal var autoResumeTranscription = true
    @Volatile lateinit var storageReady: Deferred<Unit>
        private set
    override fun onCreate() {
        super.onCreate()
        // Debug builds' engine guard probe (device tests) runs in a process of its own: it must touch none of the app.
        if (BuildConfig.DEBUG && processName().endsWith(PROBE_PROCESS)) return
        store = NoteStore(this); preferences = Preferences(this)
        // First, before anything can enter the speech work: a marker left by the previous process is its death.
        engineGuard = EngineGuard.open(this)
        models = SpeechModels(this, scope, engineGuard)
        AppPresence.install(this)
        ServiceNotifications(this).createChannels()
        storageReady = scope.async { store.recover() }
        // After recovery, whatever its result: drop model files that Whisper-era builds left behind.
        scope.launch { storageReady.join(); RetiredFiles.remove(filesDir) }
        // Recording never waits for this copy; transcription catches up once it is done.
        models.ensureStarted()
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")

    /** Retry only a failed recovery; never run recovery over an active recording. */
    @Synchronized fun retryStorage() {
        val previous = storageReady
        storageReady = scope.async {
            try { previous.await() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                store.recover()
            }
        }
    }

    /**
     * Continues transcription of notes that wait for it (after process death, Android's background limit or
     * a preempting recording). Call when an activity resumes: the service may only start from the visible
     * app. Skipped while nothing can transcribe (no model in this build, a failed install, or speech work that
     * killed the process or failed to load twice in a row, until the user retries: [engineGuard]).
     */
    fun resumeWaitingTranscription(activity: Activity) {
        // A failed model install (usually storage) is retried at every foreground start, unless the APK's
        // own copy is damaged: that needs a reinstall, and only the user's Retry tries it again.
        models.retry(automatic = true)
        if (BuildConfig.DEBUG && !autoResumeTranscription) return
        scope.launch {
            try {
                storageReady.await()
                if (store.waitingNotes().isEmpty()) return@launch
                // The speech work died or failed twice in a row: it waits for the person's Try again.
                if (!engineGuard.allows()) return@launch
                models.awaitFiles() ?: return@launch
                withContext(Dispatchers.Main) {
                    val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
                    if (resumed && !activity.isFinishing) RecordingService.command(activity, RecordingService.DRAIN)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("Focal", "Could not resume waiting transcription", error)
            }
        }
    }

    private companion object {
        /** The debug manifest's process for the engine guard probe. */
        const val PROBE_PROCESS = ":guardprobe"
    }
}
