package app.focal

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import app.focal.data.AUTOMATIC_LANGUAGE
import app.focal.data.Passage
import app.focal.data.ULTRA_ENGINE
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
        val id = app.store.create(AUTOMATIC_LANGUAGE, ULTRA_ENGINE)
        try {
            app.store.audioFile(id).writeBytes(ByteArray(32_000))
            val chunk = app.store.beginChunk(id, 0, false)
            app.store.finishChunk(chunk, 1_000)
            app.store.complete(checkNotNull(app.store.nextChunk(id)), listOf(
                Passage(noteId = id, chunkId = chunk, start = 0, end = 1_000,
                    text = "Saved primary text.", uncertain = true),
            ))
            app.store.rename(id, title)
            app.store.duration(id, 1_000)
            app.store.state(id, "READY")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Speech language: Automatic").fetchSemanticsNodes().isNotEmpty() &&
                    model.library.value.notes.any { it.id == id }
            }
            compose.onNodeWithContentDescription("Speech language: Automatic").assertIsDisplayed().assert(!hasClickAction())
            compose.onNodeWithText("Record").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Speech model").performClick()
            compose.onNodeWithText("Ultra works offline and selects the language automatically.", substring = true).assertIsDisplayed()
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
