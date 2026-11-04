package app.focal

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.focal.ui.CopyTranscriptButton
import app.focal.ui.FocalTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CopyFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatCopyRestartsFeedbackWithoutMovingTheButton() {
        var copies = 0
        compose.setContent {
            FocalTheme("light") {
                CopyTranscriptButton("note", "Saved transcript", enabled = true) { copies++; true }
            }
        }
        val copy = compose.onNodeWithText("Copy text")
        val copied = compose.onNodeWithText("Copied")
        val bounds = copy.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false

        copy.performClick()
        compose.mainClock.advanceTimeBy(900)
        copied.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertEquals(bounds, copied.getUnclippedBoundsInRoot())
        copied.performClick()
        compose.mainClock.advanceTimeBy(1_000)
        copied.assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, copies) }
        compose.mainClock.advanceTimeBy(1_000)
        copy.assertIsDisplayed()
        assertEquals(bounds, copy.getUnclippedBoundsInRoot())
    }

    @Test fun failedCopyAndChangedNoteOrTranscriptClearFeedback() {
        val note = mutableStateOf("first")
        val transcript = mutableStateOf("Saved transcript")
        var succeeds = false
        compose.setContent {
            FocalTheme("light") {
                CopyTranscriptButton(note.value, transcript.value, enabled = true) { succeeds }
            }
        }
        val copy = compose.onNodeWithText("Copy text")
        val copied = compose.onNodeWithText("Copied")
        copy.performClick()
        copy.assertIsDisplayed()
        copied.assertDoesNotExist()

        compose.runOnIdle { succeeds = true }
        copy.performClick()
        copied.assertIsDisplayed()
        compose.runOnIdle { transcript.value = "Updated transcript" }
        copy.assertIsDisplayed()
        copied.assertDoesNotExist()

        copy.performClick()
        copied.assertIsDisplayed()
        compose.runOnIdle { note.value = "second" }
        copy.assertIsDisplayed()
        copied.assertDoesNotExist()

        copy.performClick()
        copied.assertIsDisplayed()
        compose.runOnIdle { succeeds = false }
        copied.performClick()
        copy.assertIsDisplayed()
        copied.assertDoesNotExist()
    }
}
