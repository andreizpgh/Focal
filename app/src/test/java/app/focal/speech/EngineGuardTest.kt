package app.focal.speech

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The guard around the speech work that can kill the process (EngineGuard). A process death is simulated as Android
 * leaves it: the stage's marker is on disk and the next process, a new guard over the same file, finds it.
 */
class EngineGuardTest {
    @get:Rule val folder = TemporaryFolder()
    private val file: File by lazy { File(folder.root, "speech-guard") }
    /** What Android recorded about each dead process, by pid, and when; absent means no record (before API 30). */
    private val exits = HashMap<Int, Pair<ProcessExit, Long>>()
    private val pids = HashMap<EngineGuard, Int>()
    private var nextPid = 100
    private var now = 1_000L

    /** The guard of a newly started process. */
    private fun process(): EngineGuard {
        val pid = nextPid++
        return EngineGuard(file, ProcessIdentity("process-$pid", pid), { dead, since ->
            exits[dead]?.takeIf { (_, at) -> at >= since }?.first
        }, clock = { now }).also { pids[it] = pid }
    }

    /** This process dies inside [stage]: the marker is on disk and nothing clears it; Android records [exit]. */
    private fun EngineGuard.dieInside(stage: EngineStage, exit: ProcessExit? = null) {
        enter(stage)
        assertEquals(stage, markedOnDisk())
        now += 1_000
        exit?.let { exits[pids.getValue(this)] = it to now }
    }

    @Test fun aProcessThatDiedInsideAStageCountsOneDeathThere() {
        process().dieInside(EngineStage.DECODE)
        val next = process()
        assertEquals(mapOf(EngineStage.DECODE to 1), next.deaths())
        assertEquals(EngineStage.DECODE, next.lastDeath)
        assertTrue("One death leaves one more attempt", next.allows())
        // The marker belonged to the dead process: it is gone, and a third start counts nothing more.
        assertNull(next.markedOnDisk())
        assertEquals(mapOf(EngineStage.DECODE to 1), process().deaths())
    }

    @Test fun twoDeathsInARowBlockTheWorkUntilRetry() {
        process().dieInside(EngineStage.RECOGNIZER)
        process().dieInside(EngineStage.RECOGNIZER)
        val blocked = process()
        assertFalse(blocked.allows())
        assertTrue(blocked.blocked.value)
        // Still blocked at every later start, with nothing run in between.
        val later = process()
        assertFalse(later.allows())
        // The person's Retry forgets the deaths, on disk too.
        later.reset()
        assertTrue(later.allows())
        assertFalse(later.blocked.value)
        assertTrue(process().allows())
    }

    @Test fun tryAgainGivesOneAttemptUntilADecodeCompletes() {
        process().dieInside(EngineStage.VAD_OPEN)
        process().dieInside(EngineStage.VAD_OPEN)
        val retried = process().also { it.reset() }
        assertTrue(retried.allows())
        // The crash happens every time: the process Try again started dies, and the next launch is blocked at once
        // instead of resuming the work and dying a second time.
        retried.dieInside(EngineStage.VAD_OPEN)
        val next = process()
        assertEquals(mapOf(EngineStage.VAD_OPEN to 1), next.deaths())
        assertFalse(next.allows())
        assertFalse("Still blocked at a later start", process().allows())
        // A failed load right after Try again blocks at once too.
        val loading = process().also { it.reset() }
        loading.failed(EngineStage.RECOGNIZER)
        assertFalse(loading.allows())
        // Once a decode completes the chain is proven: one death leaves one more attempt again.
        val recovered = process().also { it.reset() }
        recovered.inside(EngineStage.DECODE) { }
        recovered.completed(EngineStage.DECODE)
        recovered.dieInside(EngineStage.DECODE)
        val after = process()
        assertEquals(mapOf(EngineStage.DECODE to 1), after.deaths())
        assertTrue(after.allows())
    }

    @Test fun deathsThatDoNotCountLeaveTryAgainsAttempt() {
        process().dieInside(EngineStage.RECOGNIZER)
        process().dieInside(EngineStage.RECOGNIZER)
        val retried = process().also { it.reset() }
        // The person used Force stop while the model loaded: not a death, so the attempt is still there.
        retried.dieInside(EngineStage.RECOGNIZER, ProcessExit(ApplicationExitInfo.REASON_USER_REQUESTED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        val next = process()
        assertTrue(next.allows())
        next.dieInside(EngineStage.RECOGNIZER)
        assertFalse(process().allows())
    }

    @Test fun deathsAtEveryStageAddUp() {
        // The native libraries load in VAD's creation; each stage that dies brings the block nearer.
        process().dieInside(EngineStage.VAD_OPEN)
        process().dieInside(EngineStage.SEGMENT)
        val guard = process()
        assertEquals(mapOf(EngineStage.VAD_OPEN to 1, EngineStage.SEGMENT to 1), guard.deaths())
        assertFalse(guard.allows())
    }

    @Test fun onlyADecodeThatCompletesClearsTheDeaths() {
        process().dieInside(EngineStage.DECODE)
        val guard = process()
        // Every stage before the one that keeps dying succeeds: none of them hides the death.
        for (stage in listOf(EngineStage.INSTALL, EngineStage.VAD_OPEN, EngineStage.SEGMENT, EngineStage.RECOGNIZER)) {
            guard.inside(stage) { }
            guard.completed(stage)
        }
        assertEquals(mapOf(EngineStage.DECODE to 1), guard.deaths())
        guard.dieInside(EngineStage.DECODE)
        assertFalse("A stage that succeeds each time cannot keep a dying decode looping", process().allows())

        // A decode that completes proves the whole chain: every count goes, and so does Try again's single attempt.
        val retried = process().also { it.reset() }
        retried.dieInside(EngineStage.DECODE)
        val recovered = process()
        assertFalse(recovered.allows())
        recovered.inside(EngineStage.DECODE) { }
        recovered.completed(EngineStage.DECODE)
        assertEquals(emptyMap<EngineStage, Int>(), recovered.deaths())
        assertTrue(recovered.allows())
        assertEquals(emptyMap<EngineStage, Int>(), process().deaths())
    }

    @Test fun aCompletedInstallClearsOnlyTheDeathsAtInstall() {
        process().dieInside(EngineStage.INSTALL)
        process().dieInside(EngineStage.DECODE)
        val guard = process()
        assertFalse(guard.allows())
        guard.completed(EngineStage.INSTALL)
        assertEquals(mapOf(EngineStage.DECODE to 1), guard.deaths())
        assertTrue(guard.allows())
    }

    @Test fun aRecognizerThatFailsToLoadCountsAsADeath() {
        val guard = process()
        guard.failed(EngineStage.RECOGNIZER)
        assertTrue(guard.allows())
        guard.failed(EngineStage.RECOGNIZER)
        assertFalse(guard.allows())
        // Counted on disk: the next process is blocked too.
        assertFalse(process().allows())
    }

    @Test fun theMarkerIsOnDiskOnlyWhileTheStageRuns() {
        val guard = process()
        assertNull(guard.markedOnDisk())
        var inside: EngineStage? = null
        guard.inside(EngineStage.DECODE) { inside = guard.markedOnDisk() }
        assertEquals(EngineStage.DECODE, inside)
        assertNull(guard.markedOnDisk())
        // A stage that throws is not a death: the marker goes with the exception.
        runCatching { guard.inside(EngineStage.RECOGNIZER) { error("Not enough memory") } }
        assertNull(guard.markedOnDisk())
        assertEquals(emptyMap<EngineStage, Int>(), process().deaths())
    }

    @Test fun overlappingStagesKeepTheMarkerUntilTheLastLeaves() {
        val guard = process()
        guard.enter(EngineStage.INSTALL)
        guard.enter(EngineStage.SEGMENT)
        assertEquals(EngineStage.SEGMENT, guard.markedOnDisk())
        guard.leave(EngineStage.SEGMENT)
        assertEquals(EngineStage.INSTALL, guard.markedOnDisk())
        // The same stage on two threads: marked until both return.
        guard.enter(EngineStage.INSTALL)
        guard.leave(EngineStage.INSTALL)
        assertEquals(EngineStage.INSTALL, guard.markedOnDisk())
        guard.leave(EngineStage.INSTALL)
        assertNull(guard.markedOnDisk())
        // Leaving a stage that is not running changes nothing.
        guard.leave(EngineStage.DECODE)
        assertNull(guard.markedOnDisk())
    }

    @Test fun deathsThePersonOrTheSystemCausedDoNotCount() {
        val ignored = listOf(
            ProcessExit(ApplicationExitInfo.REASON_USER_REQUESTED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
            ProcessExit(ApplicationExitInfo.REASON_PACKAGE_UPDATED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
            ProcessExit(ApplicationExitInfo.REASON_PERMISSION_CHANGE, RunningAppProcessInfo.IMPORTANCE_FOREGROUND),
            // An idle cached process reclaimed while it copied the model in the background.
            ProcessExit(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_CACHED),
            ProcessExit(ApplicationExitInfo.REASON_OTHER, RunningAppProcessInfo.IMPORTANCE_CACHED),
        )
        for (exit in ignored) {
            process().dieInside(EngineStage.INSTALL, exit)
            val next = process()
            assertEquals("$exit", emptyMap<EngineStage, Int>(), next.deaths())
            assertNull(next.lastDeath)
        }
        val counted = listOf(
            ProcessExit(ApplicationExitInfo.REASON_CRASH_NATIVE, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
            // The recognizer's gigabyte pushed the working process out.
            ProcessExit(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
            ProcessExit(ApplicationExitInfo.REASON_CRASH, RunningAppProcessInfo.IMPORTANCE_FOREGROUND),
            // The runtime exits the process on a configuration it cannot use.
            ProcessExit(ApplicationExitInfo.REASON_EXIT_SELF, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE),
        )
        for (exit in counted) assertTrue("$exit", exit.counts())
        for (exit in ignored) assertFalse("$exit", exit.counts())
    }

    @Test fun anExitRecordFromBeforeTheStageBelongsToAnEarlierProcess() {
        val guard = process()
        // An earlier process with the same pid was stopped by the person before this one entered the stage.
        exits[pids.getValue(guard)] = ProcessExit(ApplicationExitInfo.REASON_USER_REQUESTED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND) to now - 1
        guard.dieInside(EngineStage.DECODE)
        assertEquals(mapOf(EngineStage.DECODE to 1), process().deaths())
    }

    @Test fun aDamagedFileCountsNothing() {
        file.writeText("deaths.DECODE=many\ninside=NOT_A_STAGE\n\u0000\u0001")
        val guard = process()
        assertEquals(emptyMap<EngineStage, Int>(), guard.deaths())
        assertTrue(guard.allows())
        guard.dieInside(EngineStage.DECODE)
        assertEquals(mapOf(EngineStage.DECODE to 1), process().deaths())
    }

    @Test fun theGuardsOwnMarkerIsNotADeathWhenItStartsAgainInTheSameProcess() {
        val identity = ProcessIdentity("same", 42)
        val guard = EngineGuard(file, identity, { _, _ -> null })
        guard.enter(EngineStage.DECODE)
        val again = EngineGuard(file, identity, { _, _ -> null })
        assertEquals(emptyMap<EngineStage, Int>(), again.deaths())
        assertNull(again.markedOnDisk())
    }
}
