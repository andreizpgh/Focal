package app.focal

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.speech.EngineFault
import app.focal.speech.EngineGuard
import app.focal.speech.EngineStage
import app.focal.speech.GuardProbeService
import app.focal.speech.ProcessExit
import app.focal.speech.ProcessIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The engine guard against real process deaths. A probe process of the debug build (GuardProbeService, ":guardprobe")
 * opens a guard over a directory of this test's, enters a stage and then dies: killed from outside (SIGKILL from the
 * app's own uid, as `kill -9` would; or `am kill`), a native crash in the speech bridge (the debug crash hook) or an
 * uncaught OutOfMemoryError. A guard opened over the same directory afterwards judges the death as the app's next
 * process would: without exit records, as Android 8 to 10 make it (only a crash the process recorded counts, or the
 * second quick death in a row after an automatic resume), and from API 30 with Android's exit records too.
 */
@RunWith(AndroidJUnit4::class)
class EngineGuardProcessTest {
    private val app get() = ApplicationProvider.getApplicationContext<FocalApplication>()
    private lateinit var dir: File
    private lateinit var activity: ActivityScenario<ComponentActivity>

    @Before fun setup() {
        dir = File(app.cacheDir, "guard-probe-${UUID.randomUUID()}").apply { check(mkdirs()) }
        // A visible activity: the probe's service starts from the foreground.
        activity = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After fun cleanup() {
        // No restart of the probe stays scheduled for the tests after this one.
        app.stopService(Intent(app, GuardProbeService::class.java))
        probePid()?.let { Process.killProcess(it) }
        activity.close()
        dir.deleteRecursively()
    }

    @Test fun aKillFromOutsideInsideAStageDoesNotCountWithoutAnExitRecord() {
        // kill -9: SIGKILL, which no handler sees.
        val killed = probe(EngineStage.DECODE)
        Process.killProcess(killed)
        awaitDeath(killed)
        assertEquals("inside=DECODE", markerLine())
        assertEquals("No crash recorded", 1, crashRecord().size)
        val next = judge(exitRecords = false)
        assertEquals(emptyMap<EngineStage, Int>(), next.deaths())
        assertNull(next.lastDeath)
        assertNull("The marker is consumed", next.markedOnDisk())

        // am kill, as the system kills a background process.
        val background = probe(EngineStage.RECOGNIZER)
        shell("am kill ${app.packageName}")
        awaitDeath(background)
        val copy = copyOfDir()
        assertEquals(emptyMap<EngineStage, Int>(), judge(exitRecords = false).deaths())
        // From API 30 Android records why: the person's or the system's own kill does not count either.
        if (Build.VERSION.SDK_INT >= 30) {
            val exit = awaitExitRecord(background)
            assertEquals("$exit", ApplicationExitInfo.REASON_USER_REQUESTED, exit.reason)
            assertEquals(emptyMap<EngineStage, Int>(), judge(exitRecords = true, dir = copy).deaths())
        }
    }

    @Test fun aNativeCrashInsideAStageCounts() {
        val crashed = probe(EngineStage.DECODE, GuardProbeService.NATIVE_CRASH)
        awaitDeath(crashed)
        assertEquals("The signal handler recorded SIGSEGV", "native 11", crashRecord().getOrNull(1))
        val copy = copyOfDir()
        val next = judge(exitRecords = false)
        assertEquals(mapOf(EngineStage.DECODE to 1), next.deaths())
        assertEquals(EngineStage.DECODE to EngineFault.OTHER, next.lastFault())
        if (Build.VERSION.SDK_INT >= 30) {
            val exit = awaitExitRecord(crashed)
            assertEquals("$exit", ApplicationExitInfo.REASON_CRASH_NATIVE, exit.reason)
            assertEquals(mapOf(EngineStage.DECODE to 1), judge(exitRecords = true, dir = copy).deaths())
        }
        // An abort in native code (SIGABRT) counts too, and the second crash blocks the work.
        val aborted = probe(EngineStage.RECOGNIZER, GuardProbeService.NATIVE_ABORT)
        awaitDeath(aborted)
        assertEquals("native 06", crashRecord().getOrNull(1))
        val blocked = judge(exitRecords = false)
        assertEquals(mapOf(EngineStage.DECODE to 1, EngineStage.RECOGNIZER to 1), blocked.deaths())
        assertFalse(blocked.allows())
    }

    @Test fun anUncaughtOutOfMemoryErrorInsideAStageCountsAsMemory() {
        val crashed = probe(EngineStage.DECODE, GuardProbeService.OUT_OF_MEMORY)
        awaitDeath(crashed)
        assertEquals("oom", crashRecord().getOrNull(1))
        val copy = copyOfDir()
        assertEquals(EngineStage.DECODE to EngineFault.MEMORY, judge(exitRecords = false).lastFault())
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(ApplicationExitInfo.REASON_CRASH, awaitExitRecord(crashed).reason)
            assertEquals(EngineStage.DECODE to EngineFault.MEMORY, judge(exitRecords = true, dir = copy).lastFault())
        }
    }

    @Test fun quickKillsAfterAnAutomaticResumeCountFromTheSecondInARowWithoutAnExitRecord() {
        // A kill that comes back right after each automatic resume, as a low-memory kill loop does. The second probe's
        // own guard judges the first one's death (not counted, one quick death in a row) before it dies the same way.
        val first = probe(EngineStage.RECOGNIZER, automatic = true)
        Process.killProcess(first)
        awaitDeath(first)
        val second = probe(EngineStage.RECOGNIZER, automatic = true)
        Process.killProcess(second)
        awaitDeath(second)
        val next = judge(exitRecords = false)
        assertEquals(mapOf(EngineStage.RECOGNIZER to 1), next.deaths())
        assertEquals(EngineFault.OTHER, next.lastFault()?.second)
    }

    /** Starts a probe that enters [stage], then does [then]; returns its pid once the stage is marked on disk. */
    private fun probe(stage: EngineStage, then: String? = null, automatic: Boolean = false): Int {
        val ready = File(dir, GuardProbeService.READY)
        ready.delete()
        val intent = Intent(app, GuardProbeService::class.java)
            .putExtra(GuardProbeService.DIR, dir.path).putExtra(GuardProbeService.STAGE, stage.name)
            .putExtra(GuardProbeService.THEN, then).putExtra(GuardProbeService.AUTOMATIC, automatic)
        activity.onActivity { it.startService(intent) }
        await("The probe is inside $stage") { ready.isFile }
        return ready.readText().trim().toInt()
    }

    /** The guard of the process that starts next, as the app opens it: over the probe's directory (or [dir]). */
    private fun judge(exitRecords: Boolean, dir: File = this.dir): EngineGuard =
        EngineGuard(File(dir, GuardProbeService.GUARD), ProcessIdentity(UUID.randomUUID().toString(), Process.myPid()),
            if (exitRecords) EngineGuard.exitRecordsOf(app) else { _, _ -> null }, exitRecords = exitRecords)

    /** The probe's files as the death left them, for a second judge. */
    private fun copyOfDir(): File = File(app.cacheDir, "guard-probe-${UUID.randomUUID()}").also { copy ->
        dir.copyRecursively(copy)
        copy.deleteOnExit()
    }

    private fun markerLine(): String? = File(dir, GuardProbeService.GUARD).readLines().firstOrNull { it.startsWith("inside=") }

    private fun crashRecord(): List<String> = File(dir, GuardProbeService.GUARD + ".crash").readLines()

    private fun awaitDeath(pid: Int) = await("The probe $pid died", 60_000) {
        probePid() != pid && !File("/proc/$pid/cmdline").let { it.exists() && it.readText().contains("guardprobe") }
    }

    private fun awaitExitRecord(pid: Int): ProcessExit {
        var exit: ProcessExit? = null
        await("Android recorded how $pid ended", 30_000) {
            exit = EngineGuard.exitRecordsOf(app)(pid, 0)
            exit != null
        }
        return checkNotNull(exit)
    }

    private fun probePid(): Int? = app.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
        .firstOrNull { it.processName.endsWith(":guardprobe") }?.pid

    /** Runs [command] as the shell and waits for it to finish (its output is read to the end). */
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun await(description: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { description }
            SystemClock.sleep(50)
        }
    }
}
