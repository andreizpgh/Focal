package app.focal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.focal.data.Passage
import app.focal.ui.EditSheet
import app.focal.ui.FocalTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EditorFooterTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nextConfirmsAndFailedAdvanceKeepsDraftForSaveAndClose() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val passages = listOf(
            Passage(id = 1, noteId = "note", chunkId = "chunk", start = 0, end = 1000,
                text = "First hint", uncertain = true),
            Passage(id = 2, noteId = "note", chunkId = "chunk", start = 1000, end = 2000,
                text = "Second hint", uncertain = true),
        )
        compose.setContent {
            var selected by remember { mutableIntStateOf(0) }
            var visible by remember { mutableStateOf(true) }
            FocalTheme("light") {
                if (visible) EditSheet(
                    passage = passages[selected], playing = false, audio = false, play = {}, dismiss = { visible = false },
                    hasNextReview = true, audioUnavailableReason = "Audio unavailable",
                    save = { text, next ->
                        calls += text to next
                        when {
                            text == "Revised hint" && next -> false
                            next -> { selected = 1; true }
                            else -> { visible = false; true }
                        }
                    },
                )
            }
        }

        val next = compose.onNodeWithTag("editor-next")
        val save = compose.onNodeWithTag("editor-save")
        save.performScrollTo().assertTextEquals("Done")
        next.performScrollTo().assertTextEquals("Next hint").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Confirms this passage, then opens the next hint",
        )).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Second hint")
        compose.runOnIdle { assertEquals(listOf("First hint" to true), calls) }

        compose.onNode(hasSetTextAction()).performTextReplacement("Revised hint")
        next.performScrollTo().assertTextEquals("Save & next").performClick()
        compose.onNodeWithText("Couldn't save. Your changes are still here. Try again.")
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Revised hint")
        save.performScrollTo().assertTextEquals("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            assertEquals(listOf("First hint" to true, "Revised hint" to true, "Revised hint" to false), calls)
        }
    }
}
