package app.focal.recording

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RecordingStateTest {
    @Before fun idle() {
        RecordingState.live.value = LiveSession()
    }

    /**
     * Android 15+ calls onTimeout when the processing quota runs out: the service stops, and its onDestroy
     * cleanup waits for the worker before clearing the session. A Record tap in the meantime starts a new
     * service instance, whose recording that late cleanup must not wipe.
     */
    @Test fun anOldServiceInstanceNeverChangesTheSessionOfTheOneThatReplacedIt() {
        val old = RecordingState.newGeneration()
        RecordingState.publish(old) { LiveSession("a", processing = true, progress = 0.4f) }
        val new = RecordingState.newGeneration()
        RecordingState.publish(new) { LiveSession("b") }

        // The old instance's teardown and any late progress of its worker.
        RecordingState.publish(old) { if (it.noteId != null) LiveSession() else it }
        RecordingState.publish(old) { if (it.capturing) it else LiveSession("a", processing = true) }
        assertEquals(LiveSession("b"), RecordingState.live.value)

        RecordingState.publish(new) { if (it.noteId == "b") it.copy(duration = 1_000) else it }
        assertEquals(1_000L, RecordingState.live.value.duration)
        RecordingState.publish(new) { if (it.noteId != null) LiveSession() else it }
        assertEquals(LiveSession(), RecordingState.live.value)
        // Once the newer instance has published, the old one stays out even while the session is idle.
        RecordingState.publish(old) { LiveSession("a", processing = true) }
        assertEquals(LiveSession(), RecordingState.live.value)
    }

    @Test fun anOldInstanceStillClearsItsOwnSessionUntilANewerOnePublishes() {
        val old = RecordingState.newGeneration()
        RecordingState.publish(old) { LiveSession("a", processing = true) }
        val new = RecordingState.newGeneration()
        // A newer instance that changes nothing (its note is not the one shown) takes nothing over.
        RecordingState.publish(new) { if (it.noteId == "c") it.copy(pending = 3) else it }
        RecordingState.publish(old) { if (it.noteId == "a") it.copy(progress = 0.5f) else it }
        assertEquals(0.5f, RecordingState.live.value.progress)
        RecordingState.publish(old) { if (it.noteId != null) LiveSession() else it }
        assertEquals(LiveSession(), RecordingState.live.value)
        // The newer instance publishes normally afterwards.
        RecordingState.publish(new) { LiveSession("c") }
        assertEquals("c", RecordingState.live.value.noteId)
    }
}
