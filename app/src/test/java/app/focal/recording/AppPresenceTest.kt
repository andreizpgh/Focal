package app.focal.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPresenceTest {
    private class StubActivity
    private class StubShortcut

    @Test
    fun untrackedTrampolineNeverMarksVisibleOrDropsRealActivity() {
        val tracker = PresenceTracker { it !is StubShortcut }
        val main = StubActivity()
        val shortcut = StubShortcut()

        assertFalse(tracker.visible)
        tracker.onStarted(shortcut)
        tracker.onResumed(shortcut, finishingOrDestroyed = false)
        assertFalse(tracker.visible)

        tracker.onStarted(main)
        assertTrue(tracker.visible)
        tracker.onStopped(shortcut)
        tracker.onDestroyed(shortcut)
        assertTrue("Shortcut teardown must not decrement visibility for MainActivity", tracker.visible)
    }

    @Test
    fun duplicateStopDoesNotHideAnotherStartedActivityAndRotationStaysVisible() {
        val tracker = PresenceTracker { it !is StubShortcut }
        val first = StubActivity()
        val second = StubActivity()

        tracker.onStarted(first)
        tracker.onStopped(first)
        assertFalse(tracker.visible)

        tracker.onStarted(second)
        assertTrue(tracker.visible)
        // Duplicate or late stop from first activity must not remove second.
        tracker.onStopped(first)
        tracker.onDestroyed(first)
        assertTrue(tracker.visible)

        // Configuration change / new instance starting before old stops stays visible throughout.
        val recreated = StubActivity()
        tracker.onStarted(recreated)
        tracker.onStopped(second)
        tracker.onDestroyed(second)
        assertTrue(tracker.visible)

        tracker.onStopped(recreated)
        assertFalse(tracker.visible)
    }

    @Test
    fun onResumedRecoversVisibilityUnlessFinishingOrDestroyed() {
        val tracker = PresenceTracker { it !is StubShortcut }
        val main = StubActivity()

        tracker.onResumed(main, finishingOrDestroyed = true)
        assertFalse(tracker.visible)

        tracker.onResumed(main, finishingOrDestroyed = false)
        assertTrue(tracker.visible)

        tracker.onDestroyed(main)
        assertFalse(tracker.visible)
    }
}
