package app.focal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.ui.components.Dock
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.NotePill
import app.focal.ui.components.PlayCircle
import app.focal.ui.components.ProgressPhase
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The note dock's Copy (spec 4.5.1, 4.5.3, 8.2): "Copied" for its hold, in place, announced politely; a refused copy
 * stays "Copy"; the progress pill is a disabled range announced in quarters that turns into Copy in the same place.
 */
@RunWith(AndroidJUnit4::class)
class CopyFeedbackTest {
    @get:Rule val rule = createComposeRule()

    private var pill by mutableStateOf<NotePill>(NotePill.Copy())

    private fun showDock(copy: suspend () -> Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FocalTheme(dark = false) {
                Box(Modifier.fillMaxWidth().padding(FocalGrid.x24)) {
                    Dock(DockState.Note(PlayCircle.Play, pill), DockActions(copy = copy))
                }
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
    }

    @Test fun copyConfirmsForItsHoldInPlaceThenOffersCopyAgain() {
        var copies = 0
        showDock { copies++; true }
        val copy = rule.onNodeWithContentDescription("Copy")
        val bounds = copy.getUnclippedBoundsInRoot()
        copy.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).performClick()
        rule.mainClock.advanceTimeBy(CONFIRMING_MS)
        val copied = rule.onNodeWithContentDescription("Copied")
        copied.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals("Copied takes Copy's place", bounds, copied.getUnclippedBoundsInRoot())
        // A tap while it confirms copies nothing again.
        copied.performClick()
        rule.mainClock.advanceTimeBy(FocalTiming.COPIED_HOLD_MS)
        rule.onNodeWithContentDescription("Copy").assertIsEnabled()
        rule.runOnIdle { assertEquals(1, copies) }
        assertEquals(bounds, rule.onNodeWithContentDescription("Copy").getUnclippedBoundsInRoot())

        rule.onNodeWithContentDescription("Copy").performClick()
        rule.mainClock.advanceTimeBy(CONFIRMING_MS)
        rule.onNodeWithContentDescription("Copied").assertExists()
        rule.runOnIdle { assertEquals(2, copies) }
    }

    @Test fun aRefusedCopyNeverSaysCopied() {
        var attempts = 0
        showDock { attempts++; false }
        rule.onNodeWithContentDescription("Copy").performClick()
        rule.mainClock.advanceTimeBy(CONFIRMING_MS)
        rule.onNodeWithContentDescription("Copied").assertDoesNotExist()
        rule.onNodeWithContentDescription("Copy").assertIsEnabled()
        rule.runOnIdle { assertEquals(1, attempts) }
    }

    @Test fun theProgressPillIsADisabledRangeThatBecomesCopyInPlace() {
        pill = NotePill.Progress(ProgressPhase.Transcribing, 0.64f)
        showDock { true }
        // Announced at 25 % steps: 64 % reads as 50.
        val progress = rule.onNodeWithContentDescription("Transcribing, 50 percent")
        progress.assertIsNotEnabled().assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f))
        val bounds = progress.getUnclippedBoundsInRoot()
        rule.runOnIdle { pill = NotePill.Copy() }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        val copy = rule.onNodeWithContentDescription("Copy").assertIsEnabled()
        assertEquals("Readiness is one morph of the same control", bounds, copy.getUnclippedBoundsInRoot())
    }

    private companion object {
        const val SETTLE_MS = 1_000L
        const val CONFIRMING_MS = 300L
    }
}
