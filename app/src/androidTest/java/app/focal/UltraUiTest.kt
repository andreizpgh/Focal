package app.focal

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class UltraUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun automaticLanguageNeedsNoChoiceAndRetiredActionsAreGone() {
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        runBlocking { withTimeout(15_000) { app.storageReady.await() } }
        val model = compose.runOnUiThread { ViewModelProvider(compose.activity)[FocalViewModel::class.java] }
        val title = "Ultra primary ${UUID.randomUUID()}"
        val id = app.store.insertNoteForTests(listOf("Saved primary text."), 1_000, title)
        try {
            app.store.audioFile(id).writeBytes(ByteArray(32_000))
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Speech language: Automatic").fetchSemanticsNodes().isNotEmpty() &&
                    model.library.value.notes.any { it.id == id }
            }
            compose.onNodeWithContentDescription("Speech language: Automatic").assertIsDisplayed().assert(!hasClickAction())
            compose.onNodeWithText("Record").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Speech model").performClick()
            // Debug builds may be made without the model; the dialog must say which build this is.
            compose.onNodeWithText(
                if (model.speechModelBundled) "Ultra works offline and selects the language automatically."
                else "This build was made without the speech model.",
                substring = true,
            ).assertIsDisplayed()
            compose.onNodeWithText("Choose Whisper model").assertDoesNotExist()
            compose.onNodeWithText("Choose model").assertDoesNotExist()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithContentDescription("Back to recordings").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                .performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Recording options").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Automatic").assertIsDisplayed()
            compose.onNodeWithContentDescription("Recording options").performClick()
            compose.onNodeWithText("Remove audio").assertExists()
            compose.onNodeWithText("Check background sound").assertDoesNotExist()
            compose.onNodeWithText("Transcribe with Ultra").assertDoesNotExist()
            assertEquals("ultra-v1", app.store.note(id)?.speechEngine)
        } finally {
            app.store.delete(id)
        }
    }
}
