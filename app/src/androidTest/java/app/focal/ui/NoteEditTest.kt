package app.focal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Edit's save failure (spec 4.1.3, 5.7): a failed autosave keeps the draft in the field, writes "Not saved" and turns
 * Done into Retry; Retry saves the same draft and leaves Edit; a second failure in a row also says so in the feedback
 * pill. The note page in the real shell, from fake state and a fake store.
 */
@RunWith(AndroidJUnit4::class)
class NoteEditTest {
    @get:Rule val rule = createComposeRule()

    private val note = NoteUiState(
        noteId = "edit", loaded = true, title = "Synthetic edit fixture", titleIsCustom = true, created = 0,
        durationMs = 60_000, phase = NotePhase.READY, audioPresent = true, canPlay = true, editable = true,
        paragraphs = timedParagraphs(listOf(FIRST, "A second synthetic paragraph stays as it is."), 60_000),
    )

    /** A store that fails the first [failures] writes, then saves. */
    private inner class FlakyStore(private var failures: Int) : EditBackend {
        val writes = CopyOnWriteArrayList<String>()
        override suspend fun edit(id: Long, revision: Long, text: String): EditOutcome {
            writes += text
            if (failures-- > 0) return EditOutcome.Failed("disk full")
            val paragraph = note.paragraphs.first { it.id == id }
            return EditOutcome.Saved(listOf(paragraph.copy(text = text, revision = revision + 1, edited = true, words = emptyList())))
        }
        override suspend fun split(id: Long, revision: Long, offset: Int) = EditOutcome.NotEditable
        override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long) = EditOutcome.NotEditable
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

    @Composable
    private fun EditingNote(store: EditBackend) {
        val shell = rememberShellState()
        val destination = remember { Destination.Note(note.noteId) }
        LaunchedEffect(shell) { shell.open(destination) }
        FocalShell(shell, destination = { current ->
            if (current is Destination.Note) {
                val first = note.paragraphs.first()
                NotePage(
                    note = note, playback = Silent, actions = remember { NoteActions() }, edits = store, destination = destination,
                    dates = dates, today = today, captureElsewhere = false,
                    page = rememberNotePageState(field = remember { FieldFocus(first.id, first.text, first.text.length) }),
                )
            }
        })
    }

    @Test fun aFailedSaveKeepsTheDraftUntilRetrySavesIt() {
        val store = FlakyStore(failures = 1)
        rule.setContent { FocalTheme(dark = false) { EditingNote(store) } }
        val field = rule.onNode(hasSetTextAction() and hasText(FIRST))
        field.performTextReplacement(REVISED)
        // The autosave fails: the words stay, "Not saved" is written and Done reads Retry.
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasContentDescription("Retry")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Not saved").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).assertTextContains(REVISED)
        rule.onNode(hasContentDescription("Retry")).assertIsDisplayed().performClick()
        // Retry stores the same draft and leaves Edit.
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Not saved").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(REVISED, REVISED), store.writes) }
    }

    @Test fun aSecondFailureInARowAlsoSaysSoInTheFeedbackPill() {
        val store = FlakyStore(failures = 2)
        rule.setContent { FocalTheme(dark = false) { EditingNote(store) } }
        rule.onNode(hasSetTextAction() and hasText(FIRST)).performTextReplacement(REVISED)
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasContentDescription("Retry")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("Retry")).performClick()
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodes(hasText("Couldn’t save your edit. Free up some space, then tap Retry.")).fetchSemanticsNodes().isNotEmpty()
        }
        // Nothing is discarded: still in Edit, with the draft.
        rule.onNode(hasSetTextAction()).assertTextContains(REVISED)
        rule.onNode(hasContentDescription("Retry")).assertIsDisplayed()
    }

    private companion object {
        const val FIRST = "The first synthetic paragraph, before any edit."
        const val REVISED = "Revised synthetic words."
        const val TIMEOUT_MS = 5_000L
    }
}
