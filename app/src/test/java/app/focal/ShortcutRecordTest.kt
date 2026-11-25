package app.focal

import app.focal.ui.shell.RationaleMode
import app.focal.ui.shell.RecordFlowState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutRecordTest {
    @After
    fun tearDown() {
        ShortcutRecord.clearForTests()
    }

    @Test
    fun matchingNonceConsumesOnceWithinValidityWindow() {
        val nonce = ShortcutRecord.requestAt(1_000L)
        assertNotEquals(0L, nonce)

        assertTrue(ShortcutRecord.consumeAt(nonce, 1_500L))
        // Replaying the same nonce fails immediately.
        assertFalse(ShortcutRecord.consumeAt(nonce, 1_600L))
    }

    @Test
    fun wrongOrZeroNonceIsRejectedAndDoesNotConsumePendingValidRequest() {
        val nonce = ShortcutRecord.requestAt(2_000L)
        val wrong = if (nonce == 1L) 2L else 1L

        assertFalse(ShortcutRecord.consumeAt(0L, 2_500L))
        assertFalse(ShortcutRecord.consumeAt(wrong, 2_500L))
        // The real request from ShortcutActivity still succeeds.
        assertTrue(ShortcutRecord.consumeAt(nonce, 2_600L))
    }

    @Test
    fun expiredNonceFailsAtValidityBoundaryAndNeverReplaysLater() {
        val nonce = ShortcutRecord.requestAt(5_000L)
        assertFalse(ShortcutRecord.consumeAt(nonce, 5_000L + ShortcutRecord.VALID_MS))
        assertFalse(ShortcutRecord.consumeAt(nonce, 6_000L))
    }

    @Test
    fun newerRequestSupersedesOlderNonce() {
        val first = ShortcutRecord.requestAt(1_000L)
        val second = ShortcutRecord.requestAt(2_000L)
        assertNotEquals(first, second)

        assertFalse(ShortcutRecord.consumeAt(first, 2_100L))
        assertTrue(ShortcutRecord.consumeAt(second, 2_200L))
    }

    @Test
    fun permissionDialogContinuationConsumesOnceAndDoesNotLeakIntoNewState() {
        val state = RecordFlowState()
        state.armPermission("note-1", 4_200L)
        assertEquals(RecordFlowState.Target("note-1", 4_200L), state.consumePermission())
        assertNull(state.consumePermission())

        // A new RecordFlowState (task removed or process killed) starts clean even if the process survived.
        val recreatedAfterTaskDeath = RecordFlowState()
        assertNull(recreatedAfterTaskDeath.sheet)
        assertNull(recreatedAfterTaskDeath.consumePermission())
    }

    @Test
    fun rationaleSheetSurvivesWithinStateAndSettingsContinuationClearsOnDismissOrTaskDeath() {
        val state = RecordFlowState()
        state.armPermission("note-3", 1_500L)
        val deniedTarget = state.consumePermission()
        assertEquals(RecordFlowState.Target("note-3", 1_500L), deniedTarget)

        state.holdRationale(RationaleMode.OpenSettings, deniedTarget!!.appendTo, deniedTarget.fromMs)
        assertEquals(RationaleMode.OpenSettings, state.sheet)

        state.armSettingsFromHold()
        assertNull(state.sheet)
        assertEquals(RecordFlowState.Target("note-3", 1_500L), state.consumeSettings())
        assertNull(state.consumeSettings())

        state.holdRationale(RationaleMode.Allow, "note-4", 750L)
        assertEquals(RationaleMode.Allow, state.sheet)
        state.clear()
        assertNull(state.sheet)
        assertNull(state.consumePermission())
        assertNull(state.consumeSettings())

        state.holdRationale(RationaleMode.OpenSettings, "note-5", 300L)
        state.armSettingsFromHold()
        // New Activity/ViewModel after removing the task from Recents: no armed settings continuation.
        val freshActivityState = RecordFlowState()
        assertNull(freshActivityState.consumeSettings())
    }
}
