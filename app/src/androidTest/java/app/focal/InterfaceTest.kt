package app.focal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.ClipboardManager
import app.focal.data.Passage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun languageAndAppearanceCanBeChangedWithoutLeavingTheApp() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Speech language").performClick()
        compose.onNodeWithText("Русский").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithContentDescription("Back to notes").performClick()
        compose.onNodeWithText("Русский  ·  On this device").assertExists()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Speech language").performClick()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithContentDescription("Back to notes").performClick()
    }
    @Test fun reviewAndCopyPreservesRussianEdits() {
        val app = ApplicationProvider.getApplicationContext<FocalApplication>()
        kotlinx.coroutines.runBlocking { app.initialized.await() }
        val store = app.store
        val id = store.create("ru")
        try {
            store.audioFile(id).writeBytes(ByteArray(32000))
            val chunk = store.beginChunk(id, 0, false); store.finishChunk(chunk, 1000)
            store.complete(store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = chunk, start = 0, end = 1000, text = "Это тестовая запись.", uncertain = true)))
            store.rename(id, "Sample note"); store.state(id, "READY")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Sample note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sample note").performClick()
            compose.onNodeWithText("1 to review").performClick()
            compose.onNodeWithText("Это тестовая запись.").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Это исправленная запись.")
            compose.onNodeWithText("Save & next").performClick()
            compose.waitUntil(10_000) { store.passages(id).single().reviewed }
            compose.onNodeWithText("Show all").performClick()
            compose.onNodeWithText("Это исправленная запись.").assertIsDisplayed()
            compose.onNodeWithText("Copy text").performClick()
            compose.runOnIdle {
                assertEquals("Это исправленная запись.", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            }
        } finally { store.delete(id) }
    }
}
