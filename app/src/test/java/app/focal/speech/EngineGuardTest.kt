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

    /** The guard of a newly started process; before API 30 ([exitRecords] false) Android records no exits. */
    private fun process(exitRecords: Boolean = true): EngineGuard {
        val pid = nextPid++
        return EngineGuard(file, ProcessIdentity("process-$pid", pid), { dead, since ->
            exits[dead]?.takeIf { (_, at) -> at >= since }?.first
        }, clock = { now }, exitRecords = exitRecords).also { pids[it] = pid }
    }

    /** Before API 30. */
    private fun legacy() = process(exitRecords = false)

    private val crashRecord get() = File(file.path + ".crash")

    /**
     * This process dies inside [stage]: the marker is on disk and nothing clears it; Android records [exit], and the
     * process records [crash] as it dies (the native bridge's signal handler, or the Java uncaught exception handler).
     */
    private fun EngineGuard.dieInside(stage: EngineStage, exit: ProcessExit? = null, crash: Crash? = null) {
        enter(stage)
        assertEquals(stage, markedOnDisk())
        when (crash) {
            Crash.NATIVE -> crashRecord.appendText("native 11\n")
            Crash.JAVA -> crashed(IllegalStateException("A Java crash"))
            Crash.OUT_OF_MEMORY -> crashed(RuntimeException("Wrapped", OutOfMemoryError("Failed to allocate")))
            null -> Unit
        }
        now += 1_000
        exit?.let { exits[pids.getValue(this)] = it to now }
    }

    /** This process resumes waiting notes by itself (the foreground start's DRAIN) and dies inside a decode [after] it. */
    private fun EngineGuard.dieResumingAutomatically(after: Long = 5_000, crash: Crash? = null) {
        resumedAutomatically()
        working(automatic = true)
        now += after
        dieInside(EngineStage.DECODE, crash = crash)
    }

    /**
     * The crash record names an out-of-memory however deep in the causes it is, and stops at a cycle of causes: it
     * walks them in place (an ending process may have no memory left to allocate a sequence).
     */
    @Test fun theCrashRecordFindsAnOutOfMemoryAmongTheCausesWithoutLooping() {
        val first = legacy()
        val deep = (1..5).fold<Int, Throwable>(OutOfMemoryError("Failed to allocate")) { cause, level -> RuntimeException("Level $level", cause) }
        first.dieInside(EngineStage.SEGMENT)
        first.crashed(deep)
        assertEquals(EngineStage.SEGMENT to EngineFault.MEMORY, legacy().lastFault())
        val a = RuntimeException("A")
        val b = IllegalStateException("B", a)
        a.initCause(b)
        val second = legacy()
        second.dieInside(EngineStage.SEGMENT)
        second.crashed(a)
        assertEquals(EngineStage.SEGMENT to EngineFault.OTHER, legacy().lastFault())
    }

    /** A crash outside every stage is not the speech work's: nothing is recorded. */
    @Test fun aCrashOutsideEveryStageRecordsNothing() {
        val first = legacy()
        first.enter(EngineStage.DECODE)
        first.leave(EngineStage.DECODE)
        first.crashed(OutOfMemoryError("Elsewhere"))
        assertEquals(1, crashRecord.readLines().size)
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

    @Test fun deathsAtInstallAreCountedButNeverBlock() {
        // Two first-launch copies ended with no exit record (a swipe-away or a reboot before API 30).
        process().dieInside(EngineStage.INSTALL)
        process().dieInside(EngineStage.INSTALL)
        val installing = process()
        assertEquals(mapOf(EngineStage.INSTALL to 2), installing.deaths())
        assertEquals(EngineStage.INSTALL, installing.lastDeath)
        assertTrue("Deaths at install never block", installing.allows())
        // They do not bring a block nearer either, nor arm Try again's single attempt.
        installing.dieInside(EngineStage.DECODE)
        val guard = process()
        assertTrue(guard.allows())
        guard.reset()
        guard.dieInside(EngineStage.INSTALL)
        assertTrue(process().allows())
    }

    @Test fun aCompletedInstallClearsOnlyTheDeathsAtInstall() {
        process().dieInside(EngineStage.INSTALL)
        process().dieInside(EngineStage.DECODE)
        val guard = process()
        assertEquals(mapOf(EngineStage.INSTALL to 1, EngineStage.DECODE to 1), guard.deaths())
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

    @Test fun theLastCountedFaultSaysWhetherMemoryRanOut() {
        // A native crash: Android's record names it, but not as memory.
        process().dieInside(EngineStage.RECOGNIZER, ProcessExit(ApplicationExitInfo.REASON_CRASH_NATIVE, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        val crashed = process()
        assertEquals(EngineStage.RECOGNIZER to EngineFault.OTHER, crashed.lastFault())
        assertNull("One death does not block", crashed.stop.value)
        // A low-memory kill of the working process: confirmed memory, kept on disk with its stage.
        crashed.dieInside(EngineStage.DECODE, ProcessExit(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        val blocked = process()
        assertEquals(EngineStage.DECODE to EngineFault.MEMORY, blocked.lastFault())
        assertEquals(EngineFault.MEMORY, blocked.stop.value)
        assertEquals(EngineFault.MEMORY, process().stop.value)
        // Try again forgets it with the counts.
        blocked.reset()
        assertNull(blocked.lastFault())
        assertNull(blocked.stop.value)
        // A death with no record and a recognizer that failed to load are not memory.
        blocked.dieInside(EngineStage.VAD_OPEN)
        val unexplained = process()
        assertEquals(EngineFault.OTHER, unexplained.stop.value)
        unexplained.reset()
        unexplained.failed(EngineStage.RECOGNIZER)
        assertEquals(EngineFault.OTHER, unexplained.stop.value)
        // A confirmed OutOfMemoryError is.
        val oom = process().also { it.reset() }
        oom.failed(EngineStage.DECODE, EngineFault.MEMORY)
        assertEquals(EngineFault.MEMORY, oom.stop.value)
        // A completed decode forgets it.
        oom.reset()
        oom.inside(EngineStage.DECODE) { }
        oom.completed(EngineStage.DECODE)
        assertNull(oom.lastFault())
        assertNull(process().lastFault())
    }

    @Test fun deathsAtInstallAreNeverNamed() {
        process().dieInside(EngineStage.DECODE)
        process().dieInside(EngineStage.INSTALL, ProcessExit(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(EngineStage.DECODE to EngineFault.OTHER, process().lastFault())
    }

    @Test fun theDecisionTableBeforeApi30() {
        // A crash the process recorded counts, quick or not; a quick one carries the run of quick deaths on.
        assertEquals(LegacyDeaths.Verdict(true, 0), LegacyDeaths.judge(Crash.NATIVE, quick = false, quickInARow = 1))
        assertEquals(LegacyDeaths.Verdict(true, 1), LegacyDeaths.judge(Crash.OUT_OF_MEMORY, quick = true, quickInARow = 0))
        assertEquals(LegacyDeaths.Verdict(true, 3), LegacyDeaths.judge(Crash.JAVA, quick = true, quickInARow = 2))
        // Without a crash, a death that is not quick never counts and ends the run.
        assertEquals(LegacyDeaths.Verdict(false, 0), LegacyDeaths.judge(null, quick = false, quickInARow = 0))
        assertEquals(LegacyDeaths.Verdict(false, 0), LegacyDeaths.judge(null, quick = false, quickInARow = 5))
        // A quick one counts from the second in a row.
        assertEquals(LegacyDeaths.Verdict(false, 1), LegacyDeaths.judge(null, quick = true, quickInARow = 0))
        assertEquals(LegacyDeaths.Verdict(true, 2), LegacyDeaths.judge(null, quick = true, quickInARow = 1))
        assertEquals(LegacyDeaths.Verdict(true, 3), LegacyDeaths.judge(null, quick = true, quickInARow = 2))
    }

    @Test fun beforeApi30AKillFromOutsideDoesNotCount() {
        // Two swipe-aways from Recents (or Force stop, or a reboot) during a decode: Android kept no record of either.
        legacy().dieInside(EngineStage.DECODE)
        legacy().dieInside(EngineStage.DECODE)
        val next = legacy()
        assertEquals(emptyMap<EngineStage, Int>(), next.deaths())
        assertNull(next.lastDeath)
        assertTrue(next.allows())
        // The marker was the dead process's: it is consumed all the same.
        assertNull(next.markedOnDisk())
        // Also during the recognizer's load and the model install.
        next.dieInside(EngineStage.RECOGNIZER)
        legacy().dieInside(EngineStage.INSTALL)
        assertTrue(legacy().allows())
    }

    @Test fun beforeApi30ACrashTheProcessRecordedCounts() {
        legacy().dieInside(EngineStage.RECOGNIZER, crash = Crash.NATIVE)
        val crashed = legacy()
        assertEquals(mapOf(EngineStage.RECOGNIZER to 1), crashed.deaths())
        assertEquals(EngineStage.RECOGNIZER to EngineFault.OTHER, crashed.lastFault())
        crashed.dieInside(EngineStage.DECODE, crash = Crash.JAVA)
        val blocked = legacy()
        assertFalse(blocked.allows())
        assertEquals(EngineFault.OTHER, blocked.stop.value)
        // An uncaught OutOfMemoryError (here wrapped) is a confirmed out-of-memory.
        blocked.reset()
        blocked.dieInside(EngineStage.DECODE, crash = Crash.OUT_OF_MEMORY)
        assertEquals(EngineFault.MEMORY, legacy().stop.value)
    }

    @Test fun aCrashRecordedByAnotherProcessIsNotThisDeath() {
        // The previous process crashed natively, but outside any stage: no marker, nothing counted.
        val first = legacy()
        first.inside(EngineStage.DECODE) { }
        crashRecord.appendText("native 11\n")
        val second = legacy()
        assertEquals(emptyMap<EngineStage, Int>(), second.deaths())
        // This one is killed from outside inside a stage; the earlier crash in the record is not its own.
        second.dieInside(EngineStage.DECODE)
        assertEquals("The record restarts with the process that enters a stage", "process-${pids.getValue(second)}", crashRecord.readLines().first())
        val third = legacy()
        assertEquals(emptyMap<EngineStage, Int>(), third.deaths())
        // A record whose token is another process's names nothing.
        third.enter(EngineStage.DECODE)
        crashRecord.writeText("process-0\nnative 11\n")
        assertEquals(emptyMap<EngineStage, Int>(), legacy().deaths())
        // A Java crash while no stage runs is not recorded.
        val idle = legacy()
        idle.inside(EngineStage.DECODE) { }
        idle.crashed(IllegalStateException("Outside the speech work"))
        assertEquals(1, crashRecord.readLines().size)
    }

    @Test fun beforeApi30QuickDeathsAfterAnAutomaticResumeCountFromTheSecondInARow() {
        // A low-memory kill that comes back at every launch: each foreground start resumes the note and dies soon after.
        legacy().dieResumingAutomatically()
        val second = legacy()
        assertEquals("The first quick death does not count", emptyMap<EngineStage, Int>(), second.deaths())
        second.dieResumingAutomatically()
        val third = legacy()
        assertEquals(mapOf(EngineStage.DECODE to 1), third.deaths())
        assertEquals("Not confirmed as memory", EngineFault.OTHER, third.lastFault()?.second)
        third.dieResumingAutomatically()
        assertFalse("The next one blocks", legacy().allows())
    }

    @Test fun beforeApi30ARunOfQuickDeathsBreaks() {
        // Not quick: the stage began more than a minute after the automatic resume.
        legacy().dieResumingAutomatically()
        legacy().dieResumingAutomatically(after = LegacyDeaths.QUICK_MS + 1)
        legacy().dieResumingAutomatically()
        assertEquals(emptyMap<EngineStage, Int>(), legacy().deaths())
        // The person's own work (Record, Finish, Try again) is never quick, even right after a resume.
        legacy().dieResumingAutomatically()
        legacy().apply { resumedAutomatically(); working(automatic = false) }.dieInside(EngineStage.DECODE)
        legacy().dieResumingAutomatically()
        assertEquals(emptyMap<EngineStage, Int>(), legacy().deaths())
        // A process that ended outside the speech work between them.
        legacy().dieResumingAutomatically()
        legacy()
        legacy().dieResumingAutomatically()
        assertEquals(emptyMap<EngineStage, Int>(), legacy().deaths())
        // A decode that completed between them.
        legacy().dieResumingAutomatically()
        legacy().apply { inside(EngineStage.DECODE) { }; completed(EngineStage.DECODE) }.dieResumingAutomatically()
        assertEquals(emptyMap<EngineStage, Int>(), legacy().deaths())
    }

    @Test fun fromApi30TheExitRecordDecidesAndTheCrashRecordNamesMemory() {
        // A Java crash Android records as REASON_CRASH: memory only because the process recorded an OutOfMemoryError.
        process().dieInside(EngineStage.DECODE, ProcessExit(ApplicationExitInfo.REASON_CRASH, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE), Crash.OUT_OF_MEMORY)
        val next = process()
        assertEquals(EngineStage.DECODE to EngineFault.MEMORY, next.lastFault())
        // The person's Force stop does not count, crash record or not; nor do quick deaths without a record decide.
        next.dieInside(EngineStage.DECODE, ProcessExit(ApplicationExitInfo.REASON_USER_REQUESTED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE), Crash.NATIVE)
        val after = process()
        assertEquals(mapOf(EngineStage.DECODE to 1), after.deaths())
        // A death Android has no record of still counts from API 30, as before.
        after.dieInside(EngineStage.DECODE)
        assertEquals(mapOf(EngineStage.DECODE to 2), process().deaths())
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
