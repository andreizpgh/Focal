package app.focal

import android.content.Intent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.data.EditResult
import app.focal.data.Paragraph
import app.focal.recording.LiveSession
import app.focal.recording.RecordingState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * "Replaced N · Undo" (spec 5.8) end to end, through MainActivity, the note's view model and the store: on a note
 * with many matches across many paragraphs, one of them edited before, Replace all writes every match and its Undo
 * puts back every paragraph's text and edited flag, whether Find was opened while reading or from Edit with a
 * paragraph's field holding the caret. Notes are synthetic and removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceAllUndoTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var app: FocalApplication
    private var scenario: ActivityScenario<MainActivity>? = null
    private val created = mutableListOf<String>()
    private val tag = UUID.randomUUID().toString().take(6)

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking { app.storageReady.await() }
        assumeTrue("Needs an idle recorder", RecordingState.live.value.noteId == null)
        app.preferences.settingsOpened = true
        app.autoResumeTranscription = false
    }

    @After fun cleanup() {
        scenario?.close()
        RecordingState.live.value = LiveSession()
        app.autoResumeTranscription = true
        created.forEach { app.store.delete(it) }
    }

    /** What Undo must give back: each visible paragraph's text and whether it reads as edited. */
    private data class Shown(val text: String, val edited: Boolean)

    private fun shown(id: String): List<Shown> = app.store.paragraphs(id).filter { it.visible }.map { Shown(it.text, it.edited) }

    /** A ready note of [PARAGRAPHS] paragraphs with three matches each; one paragraph edited by hand, with one. */
    private fun manyMatches(): Pair<String, List<Paragraph>> {
        val texts = (1..PARAGRAPHS).map { "Screen $it of $tag shows the screen and a screen saver." }
        val id = app.store.insertNoteForTests(texts, title = "Replace fixture $tag").also {
            created += it
            app.store.audioFile(it).writeBytes(ByteArray(texts.size * BYTES_PER_SECOND))
        }
        val edited = app.store.paragraphs(id)[EDITED_INDEX]
        assertTrue(app.store.editParagraph(edited.id, edited.revision, EDITED_TEXT) is EditResult.Saved)
        return id to app.store.paragraphs(id)
    }

    private fun await(what: String, matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Not shown: $what", timeout)
        }
    }

    private fun awaitStore(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(TIMEOUT_MS, condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("The store never had $what", timeout)
        }
    }

    private fun open(firstParagraph: String) {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        val row = hasContentDescription("Replace fixture $tag", substring = true) or (hasText("Replace fixture $tag") and hasClickAction())
        await("the note in the library", row)
        compose.onAllNodes(row)[0].performClick()
        await("the note page", hasText(firstParagraph))
    }

    /** Find's two fields, then Replace all; returns once "Replaced N" shows. */
    private fun replaceAll(count: Int) {
        await("Find", hasSetTextAction() and hasContentDescription("Find in note"))
        compose.onNode(hasSetTextAction() and hasContentDescription("Find in note")).performTextInput(QUERY)
        compose.onNode(hasSetTextAction() and hasContentDescription("Replace with")).performTextInput(REPLACEMENT)
        val button = hasContentDescription("Replace all, $count matches") and hasClickAction()
        await("Replace all ($count)", button)
        compose.onNode(button).performClick()
        await("the feedback pill", hasText("Replaced $count"))
    }

    private fun undoInThePill() {
        val undo = hasText("Undo") and hasClickAction()
        await("Undo in the pill", undo)
        compose.onNode(undo).performClick()
    }

    @Test fun undoPutsBackEveryParagraphReplacedWhileReading() {
        val (id, before) = manyMatches()
        val original = shown(id)
        open(before.first().text)
        compose.onNode(hasContentDescription("More options") and hasClickAction()).performClick()
        await("the menu", hasText("Find and replace") and hasClickAction())
        compose.onNode(hasText("Find and replace") and hasClickAction()).performClick()

        replaceAll(MATCHES)
        awaitStore("every match replaced") { shown(id).none { it.text.contains(QUERY, ignoreCase = true) } }
        assertEquals(original.map { it.text.replace(Regex(QUERY, RegexOption.IGNORE_CASE), REPLACEMENT) }, shown(id).map { it.text })

        undoInThePill()
        awaitStore("the texts from before") { shown(id) == original }
        await("the first paragraph as it was", hasText(before.first().text))
    }

    @Test fun undoPutsBackEveryParagraphReplacedFromEdit() {
        val (id, before) = manyMatches()
        val original = shown(id)
        open(before.first().text)
        compose.onNode(hasContentDescription("Edit") and hasClickAction()).performClick()
        await("the edit bar", hasContentDescription("Done"))
        // A paragraph's field holds the caret, so its draft is live while Find replaces under it.
        compose.onNode(hasText(before.first().text)).performClick()
        await("the paragraph's field", hasSetTextAction() and hasText(before.first().text))
        compose.onNode(hasContentDescription("More options") and hasClickAction()).performClick()
        await("the edit menu", hasText("Find and replace") and hasClickAction())
        compose.onNode(hasText("Find and replace") and hasClickAction()).performClick()

        replaceAll(MATCHES)
        awaitStore("every match replaced") { shown(id).none { it.text.contains(QUERY, ignoreCase = true) } }

        undoInThePill()
        awaitStore("the texts from before") { shown(id) == original }
        // The field's draft never writes the replaced words back: after Find closes and Edit is done, it holds.
        compose.onNode(hasContentDescription("Close find") and hasClickAction()).performClick()
        await("the field again", hasSetTextAction() and hasText(before.first().text))
        compose.onNode(hasContentDescription("Done") and hasClickAction()).performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        Thread.sleep(AUTOSAVE_MARGIN_MS)
        assertEquals(original, shown(id))
    }

    private companion object {
        const val PARAGRAPHS = 24
        const val EDITED_INDEX = 5
        const val EDITED_TEXT = "An edited paragraph about one screen."
        /** Three in each paragraph, one in the edited one. */
        const val MATCHES = (PARAGRAPHS - 1) * 3 + 1
        const val QUERY = "screen"
        const val REPLACEMENT = "page"
        const val BYTES_PER_SECOND = 32_000
        const val TIMEOUT_MS = 10_000L
        /** Longer than Edit's autosave (600 ms), so a stale draft would have been written by then. */
        const val AUTOSAVE_MARGIN_MS = 1_500L
    }
}
