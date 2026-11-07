package app.focal.speech

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.io.File

/**
 * Debug builds only (device tests): a process of its own (":guardprobe", where the app itself starts nothing) that opens
 * an engine guard over a test's directory, as the app's process opens its own, enters a stage and then ends as told: a
 * native crash, an uncaught OutOfMemoryError, or nothing, to be killed from outside. The next guard over the same
 * directory judges the death as the app's next process would. Its guard never has exit records, so a probe that
 * starts after another one judges that one's death as Android before API 30 makes the app do.
 */
class GuardProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent
        val dir = command?.getStringExtra(DIR)?.let(::File)
        if (command == null || dir == null || !dir.isDirectory) {
            // A start Android delivers again for a test that has ended.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val guard = EngineGuard.opened(File(dir, GUARD), { _, _ -> null }, exitRecords = false)
        if (command.getBooleanExtra(AUTOMATIC, false)) {
            guard.resumedAutomatically()
            guard.working(automatic = true)
        }
        // A stage that runs native code arms the native crash handlers before it is marked on disk.
        guard.enter(EngineStage.valueOf(command.getStringExtra(STAGE) ?: EngineStage.DECODE.name))
        val ready = File(dir, "$READY.partial")
        ready.writeText(Process.myPid().toString())
        check(ready.renameTo(File(dir, READY))) { "Could not report the probe ready" }
        // Dies once this start has returned, so Android does not deliver it again to a new process.
        val death: Runnable? = when (command.getStringExtra(THEN)) {
            NATIVE_CRASH -> Runnable { NativeCrashHook.crash(NativeCrashHook.SEGFAULT) }
            NATIVE_ABORT -> Runnable { NativeCrashHook.crash(NativeCrashHook.ABORT) }
            OUT_OF_MEMORY -> Runnable { throw OutOfMemoryError("Device test: an uncaught OutOfMemoryError inside a guarded stage.") }
            else -> null // Waits inside the stage until it is killed.
        }
        death?.let { Handler(Looper.getMainLooper()).postDelayed(it, DIE_AFTER_MS) }
        return START_NOT_STICKY
    }

    companion object {
        const val DIR = "dir"
        const val STAGE = "stage"
        const val THEN = "then"
        const val AUTOMATIC = "automatic"
        const val GUARD = "speech-guard"
        const val READY = "ready"
        const val NATIVE_CRASH = "native-crash"
        const val NATIVE_ABORT = "native-abort"
        const val OUT_OF_MEMORY = "out-of-memory"
        private const val DIE_AFTER_MS = 200L
    }
}
