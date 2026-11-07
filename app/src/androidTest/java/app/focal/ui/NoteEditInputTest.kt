package app.focal.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.gallery.dates
import app.focal.gallery.timedParagraphs
import app.focal.gallery.today
import app.focal.ui.design.FocalTheme
import app.focal.ui.note.EditBackend
import app.focal.ui.note.FieldFocus
import app.focal.ui.note.NoteActions
import app.focal.ui.note.NotePage
import app.focal.ui.note.NotePlayback
import app.focal.ui.note.rememberNotePageState
import app.focal.ui.shell.Destination
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.rememberShellState
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteUiState
import app.focal.ui.state.ParagraphUi
import app.focal.ui.state.PlaybackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Edit's input paths (spec 5.7): Backspace at a paragraph's start joins it to the one before, whether a soft keyboard
 * sends it through the input connection (`deleteSurroundingText`, which at offset 0 changes no text, also while the
 * keyboard composes the word after the caret) or a hardware key sends Backspace; a join the store refuses keeps both paragraphs and says so; and the session's undo log
 * survives the page being recreated. The note page in the real shell, over an in-memory note.
 */
@RunWith(AndroidJUnit4::class)
class NoteEditInputTest {
    @get:Rule val rule = createComposeRule()

    /** The note's paragraphs in memory, with the store's revision and join rules; the page reloads from it. */
    private class MemoryNote(texts: List<String>) : EditBackend {
        var paragraphs by mutableStateOf(timedParagraphs(texts, 60_000))
        @Volatile var refuseJoin = false
        val joins = AtomicInteger()
        val writes = AtomicInteger()

        fun text(id: Long): String? = paragraphs.firstOrNull { it.id == id }?.text

        override suspend fun edit(id: Long, revision: Long, text: String): EditOutcome {
            val current = paragraphs.firstOrNull { it.id == id } ?: return EditOutcome.Stale
            if (current.revision != revision) return EditOutcome.Stale
            writes.incrementAndGet()
            val saved = current.copy(text = text.trim(), revision = revision + 1, edited = true, words = emptyList())
            paragraphs = paragraphs.map { if (it.id == id) saved else it }
            return EditOutcome.Saved(listOf(saved))
        }

        override suspend fun split(id: Long, revision: Long, offset: Int) = EditOutcome.NotEditable

        override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditOutcome {
            if (refuseJoin) return EditOutcome.CannotMerge
            val first = paragraphs.first { it.id == firstId }
            val second = paragraphs.first { it.id == secondId }
            if (first.revision != firstRevision || second.revision != secondRevision) return EditOutcome.Stale
            joins.incrementAndGet()
            val joined = first.copy(text = "${first.text} ${second.text}", revision = first.revision + 1, words = emptyList())
            paragraphs = paragraphs.filter { it.id != secondId }.map { if (it.id == firstId) joined else it }
            return EditOutcome.Saved(listOf(joined))
        }

        override suspend fun restore(id: Long, revision: Long) = EditOutcome.NotEditable
    }

    private object Silent : NotePlayback {
        override val session = PlaybackSession()
        override fun positionMs() = 0L
        override fun playWord(paragraph: ParagraphUi, offset: Int) = Unit
        override fun playFrom(ms: Long) = Unit
        override fun playRange(startMs: Long, endMs: Long) = Unit
        override fun toggle() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
    }

    @Volatile private var view: View? = null

    /** The page in Edit with the caret in [fieldId] at [caret] (on its first composition; a restored page keeps its own). */
    @Composable
    private fun EditingNote(store: MemoryNote, fieldId: Long, caret: Int) {
        val current = LocalView.current
        SideEffect { view = current }
        val shell = rememberShellState()
        val destination = remember { Destination.Note(NOTE_ID) }
        LaunchedEffect(shell) { shell.open(destination) }
        FocalShell(shell, destination = { shown ->
            if (shown is Destination.Note) {
                val note = NoteUiState(
                    noteId = NOTE_ID, loaded = true, title = "Synthetic input fixture", titleIsCustom = true, created = 0,
                    durationMs = 60_000, phase = NotePhase.READY, audioPresent = true, canPlay = true, editable = true,
                    paragraphs = store.paragraphs,
                )
                NotePage(
                    note = note, playback = Silent, actions = remember { NoteActions() }, edits = store, destination = destination,
                    dates = dates, today = today, captureElsewhere = false,
                    page = rememberNotePageState(field = remember { FieldFocus(fieldId, store.text(fieldId)!!, caret) }),
                )
            }
        })
    }

    /** The focused field's input connection, as the keyboard gets it from the view. */
    private fun connection(): InputConnection {
        var connection: InputConnection? = null
        rule.waitUntil(TIMEOUT_MS) {
            connection = rule.runOnUiThread { view?.onCreateInputConnection(EditorInfo()) }
            connection != null
        }
        return connection!!
    }

    private fun fieldSelection(): TextRange =
        rule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    @Test fun aSoftKeyboardBackspaceAtTheStartJoinsTheParagraphBefore() {
        val store = MemoryNote(listOf(FIRST, SECOND))
        rule.setContent { FocalTheme(dark = false) { EditingNote(store, fieldId = 2, caret = 0) } }
        val input = connection()
        // What Gboard sends for Backspace: a delete before the caret, which at offset 0 deletes nothing.
        assertTrue(rule.runOnUiThread { input.deleteSurroundingText(1, 0) })
        rule.waitUntil(TIMEOUT_MS) { store.joins.get() == 1 }
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction() and hasText("$FIRST $SECOND")).fetchSemanticsNodes().isNotEmpty() }
        // The caret lands where the joined text begins.
        rule.waitUntil(TIMEOUT_MS) { fieldSelection() == TextRange(FIRST.length + 1) }
        assertEquals(listOf("$FIRST $SECOND"), store.paragraphs.map { it.text })
    }

    @Test fun aBackspaceJoinsWhileTheKeyboardComposesTheWordAfterTheCaret() {
        val store = MemoryNote(listOf(FIRST, SECOND))
        rule.setContent { FocalTheme(dark = false) { EditingNote(store, fieldId = 2, caret = 0) } }
        val input = connection()
        // Gboard marks the word the caret touches as composing (seen on API 33); the caret stays at 0.
        assertTrue(rule.runOnUiThread { input.setComposingRegion(0, SECOND.indexOf(' ')) })
        rule.waitUntil(TIMEOUT_MS) { fieldSelection() == TextRange.Zero }
        assertTrue(rule.runOnUiThread { input.deleteSurroundingText(1, 0) })
        rule.waitUntil(TIMEOUT_MS) { store.joins.get() == 1 }
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction() and hasText("$FIRST $SECOND")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aSoftKeyboardDeleteInsideTheTextStillDeletes() {
        val store = MemoryNote(listOf(FIRST, SECOND))
        rule.setContent { FocalTheme(dark = false) { EditingNote(store, fieldId = 2, caret = 3) } }
        val input = connection()
        assertTrue(rule.runOnUiThread { input.deleteSurroundingText(1, 0) })
        val expected = SECOND.removeRange(2, 3)
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction() and hasText(expected)).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { assertEquals(0, store.joins.get()) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aHardwareBackspaceAtTheStartJoinsTheParagraphBefore() {
        val store = MemoryNote(listOf(FIRST, SECOND))
        rule.setContent { FocalTheme(dark = false) { EditingNote(store, fieldId = 2, caret = 0) } }
        connection()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Backspace) }
        rule.waitUntil(TIMEOUT_MS) { store.joins.get() == 1 }
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction() and hasText("$FIRST $SECOND")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aRefusedJoinKeepsBothParagraphsAndSaysSo() {
        val store = MemoryNote(listOf(FIRST, SECOND)).apply { refuseJoin = true }
        rule.setContent { FocalTheme(dark = false) { EditingNote(store, fieldId = 2, caret = 0) } }
        val input = connection()
        assertTrue(rule.runOnUiThread { input.deleteSurroundingText(1, 0) })
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasText(CANNOT_JOIN)).fetchSemanticsNodes().isNotEmpty() }
        // Both stay; the caret stays at the start of the paragraph it was in.
        rule.onNode(hasSetTextAction()).assertTextEquals(SECOND)
        assertEquals(TextRange.Zero, fieldSelection())
        rule.runOnIdle { assertEquals(listOf(FIRST, SECOND), store.paragraphs.map { it.text }) }
    }

    @Test fun theUndoLogSurvivesThePageBeingRecreated() {
        val store = MemoryNote(listOf(FIRST, SECOND))
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FocalTheme(dark = false) { EditingNote(store, fieldId = 1, caret = FIRST.length) } }
        rule.onNode(hasSetTextAction() and hasText(FIRST)).performTextReplacement(REVISED)
        // The autosave writes the typing as one step.
        rule.waitUntil(TIMEOUT_MS) { store.writes.get() == 1 }
        rule.waitUntil(TIMEOUT_MS) { rule.onNode(hasContentDescription("Undo")).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not() }

        restoration.emulateSavedInstanceStateRestore()

        // The recreated page is still in Edit with the draft, and its Undo takes the typing back.
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction() and hasText(REVISED)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("Undo")).assertIsEnabled().performClick()
        rule.waitUntil(TIMEOUT_MS) { store.text(1) == FIRST }
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction() and hasText(FIRST)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("Redo")).assertIsEnabled()
    }

    private companion object {
        const val NOTE_ID = "edit-input"
        const val FIRST = "The first synthetic paragraph ends here."
        const val SECOND = "Second synthetic paragraph."
        const val REVISED = "Revised synthetic words."
        const val CANNOT_JOIN = "Can’t join these paragraphs."
        const val TIMEOUT_MS = 5_000L
    }
}
