package app.focal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import app.focal.ui.EditSheet
import app.focal.ui.FocalTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EditorFooterTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSaveKeepsTheDraftUntilASaveSucceeds() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var failures = 1
        val paragraph = testParagraph(1, "note", 0, 1000, "First words")
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            FocalTheme("light") {
                if (visible) EditSheet(
                    passage = paragraph, playing = false, audio = false, play = {}, dismiss = { visible = false },
                    hasNextReview = true, audioUnavailableReason = "Audio unavailable",
                    save = { text, next ->
                        calls += text to next
                        if (failures-- > 0) false else { visible = false; true }
                    },
                )
            }
        }

        val save = compose.onNodeWithTag("editor-save")
        save.performScrollTo().assertTextEquals("Done")
        // Schema 5 keeps no review hints, so the footer never offers "Next hint".
        compose.onNodeWithTag("editor-next").assertDoesNotExist()

        compose.onNode(hasSetTextAction()).performTextReplacement("Revised words")
        // The keyboard is opening over the footer; a semantic click does not depend on its animation.
        save.assertTextEquals("Save").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Couldn't save. Your changes are still here. Try again.")
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Revised words")
            .assertIsNotFocused()
        save.performScrollTo().assertTextEquals("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            assertEquals(listOf("Revised words" to false, "Revised words" to false), calls)
        }
    }
}
