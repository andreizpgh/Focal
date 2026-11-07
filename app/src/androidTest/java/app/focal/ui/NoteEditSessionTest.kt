package app.focal.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.gallery.dates
import app.focal.gallery.today
import app.focal.ui.design.FocalTheme
import app.focal.ui.note.FieldFocus
import app.focal.ui.note.NoteActions
import app.focal.ui.note.NotePage
import app.focal.ui.note.NotePlayback
import app.focal.ui.note.rememberNotePageState
import app.focal.ui.shell.Destination
import app.focal.ui.shell.FocalShell
import app.focal.ui.shell.rememberShellState
import app.focal.ui.state.ParagraphUi
import app.focal.ui.state.PlaybackSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edit's writes in the order they happened (spec 5.7), on the note page in the real shell over an in-memory note whose
 * writes can be held, as a busy database holds them: words typed right after Enter land in the second paragraph; a
 * new paragraph's draft survives the page being recreated and Done stores it; the save as the activity stops never
 * writes back over an undo still writing; Undo of Enter inside a word gives the word back; a long undo and redo chain
 * keeps finding its paragraphs.
 */
@RunWith(AndroidJUnit4::class)
class NoteEditSessionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** The note's view model's scope: writes in it outlive the page. */
    private val lasting = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var view: View? = null

    @After fun cleanup() {
        lasting.cancel()
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

    /** The page over [store]: in Edit with the caret in [fieldId] at [caret] (on its first composition), or reading. */
    @Composable
    private fun Note(store: EditStore, fieldId: Long? = null, caret: Int = 0, actions: NoteActions = remember { NoteActions() }) {
        val current = LocalView.current
        SideEffect { view = current }
        val shell = rememberShellState()
        val destination = remember { Destination.Note(NOTE_ID) }
        LaunchedEffect(shell) { shell.open(destination) }
        FocalShell(shell, destination = { shown ->
            if (shown is Destination.Note) {
                NotePage(
                    note = store.state(NOTE_ID, "Synthetic session fixture"), playback = Silent, actions = actions, edits = store,
                    destination = destination, dates = dates, today = today, captureElsewhere = false,
                    page = rememberNotePageState(field = remember { fieldId?.let { FieldFocus(it, store.text(it)!!, caret) } }),
                )
            }
        })
    }

    /** The focused field's input connection, as the keyboard gets it from the view (once the field has focus). */
    private fun connection(): InputConnection {
        var connection: InputConnection? = null
        rule.waitUntil(TIMEOUT_MS) {
            connection = rule.runOnUiThread { view?.onCreateInputConnection(EditorInfo()) }
            connection != null
        }
        return connection!!
    }

    private fun field() = rule.onNode(hasSetTextAction())

    private fun awaitField(text: String) =
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitStore(store: EditStore, texts: List<String>) {
        rule.waitUntil(TIMEOUT_MS) { store.texts() == texts }
    }

    private fun press(description: String) {
        val button = hasContentDescription(description) and hasClickAction()
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(button and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(button).performClick()
    }

    private fun release(store: EditStore, gate: CompletableDeferred<Unit>) {
        store.hold = null
        gate.complete(Unit)
    }

    /** S1: Enter while the store is busy, then typing at once: the words go to the second paragraph and are stored. */
    @OptIn(ExperimentalTestApi::class)
    @Test fun wordsTypedRightAfterEnterGoToTheSecondParagraph() {
        val store = EditStore(listOf(FIRST, SECOND))
        rule.setContent { FocalTheme(dark = false) { Note(store, fieldId = 1, caret = FIRST.indexOf("paragraph")) } }
        connection()
        val gate = CompletableDeferred<Unit>()
        store.hold = gate
        field().performKeyInput { pressKey(Key.Enter) }
        field().performTextInput("Typed ")
        rule.waitUntil(TIMEOUT_MS) { store.held.get() >= 1 }
        // The page has split already; the field holds the second paragraph with the words in it.
        awaitField("Typed paragraph ends here.")
        rule.onNode(hasText("The first synthetic") and !hasSetTextAction()).assertExists()
        release(store, gate)
        awaitStore(store, listOf("The first synthetic", "Typed paragraph ends here.", SECOND))
    }

    /**
     * S1 through a soft keyboard's input connection: Enter and the next word in a row, as a keyboard sends them. It
     * finishes the word it marked as composing first (the emulator's keyboard marks the word at the caret, API 33),
     * all in one turn of the main thread, so that keyboard cannot mark it again in between.
     */
    @Test fun wordsCommittedRightAfterEnterByAKeyboardGoToTheSecondParagraph() {
        val store = EditStore(listOf(FIRST, SECOND))
        rule.setContent { FocalTheme(dark = false) { Note(store, fieldId = 1, caret = FIRST.indexOf("paragraph")) } }
        val input = connection()
        val gate = CompletableDeferred<Unit>()
        store.hold = gate
        assertTrue(rule.runOnUiThread { input.finishComposingText() && input.commitText("\n", 1) && input.commitText("Typed ", 1) })
        rule.waitUntil(TIMEOUT_MS) { store.held.get() >= 1 }
        awaitField("Typed paragraph ends here.")
        release(store, gate)
        awaitStore(store, listOf("The first synthetic", "Typed paragraph ends here.", SECOND))
    }

    /**
     * S2: Enter at the end makes an empty paragraph, which the note's state leaves out. Recreated (rotation, process
     * death) before its draft was stored, the page still shows the field with the draft, and Done stores it there.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test fun aNewParagraphsDraftSurvivesRecreationAndDoneStoresIt() {
        val store = EditStore(listOf(FIRST, SECOND))
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FocalTheme(dark = false) { Note(store, fieldId = 2, caret = SECOND.length) } }
        connection()
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitUntil(TIMEOUT_MS) { store.texts() == listOf(FIRST, SECOND, "") }
        // The store is busy from here on: the draft is not stored before the page goes.
        val gate = CompletableDeferred<Unit>()
        store.hold = gate
        field().performTextInput(ADDED)
        restoration.emulateSavedInstanceStateRestore()
        release(store, gate)
        awaitField(ADDED)
        press("Done")
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        awaitStore(store, listOf(FIRST, SECOND, ADDED))
    }

    /**
     * S3: Home while an undo is still writing. The draft's save as the activity stops waits for the undo and reads the
     * field the undo reseeded, so it writes nothing: the undo stays, and nothing writes the undone text back.
     */
    @Test fun goingHomeWhileAnUndoIsWritingKeepsTheUndo() {
        val store = EditStore(listOf(FIRST, SECOND))
        val actions = lastingWrites(lasting)
        rule.setContent { FocalTheme(dark = false) { Note(store, fieldId = 1, caret = FIRST.length, actions = actions) } }
        field().performTextReplacement(REVISED)
        rule.waitUntil(TIMEOUT_MS) { store.writes.get() == 1 }
        val gate = CompletableDeferred<Unit>()
        store.hold = gate
        press("Undo")
        rule.waitUntil(TIMEOUT_MS) { store.held.get() == 1 }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        release(store, gate)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // Every write asked for so far, the save as the activity stopped included, has run.
        rule.waitUntil(TIMEOUT_MS) { store.writes.get() >= 2 && lasting.coroutineContext.job.children.none { it.isActive } }
        rule.waitForIdle()
        assertEquals(FIRST, store.text(1))
        assertEquals("The typing and its undo, nothing after", 2, store.writes.get())
        awaitField(FIRST)
    }

    /** S4: Undo of Enter pressed inside a word gives the word back, not its two parts joined with a space. */
    @OptIn(ExperimentalTestApi::class)
    @Test fun undoingEnterInsideAWordGivesTheWordBack() {
        val store = EditStore(listOf(COUNTRY, SECOND))
        rule.setContent { FocalTheme(dark = false) { Note(store, fieldId = 1, caret = COUNTRY.indexOf("country") + "cou".length) } }
        connection()
        field().performKeyInput { pressKey(Key.Enter) }
        awaitStore(store, listOf("Ask what your cou", "ntry can do.", SECOND))
        press("Undo")
        awaitStore(store, listOf(COUNTRY, SECOND))
        awaitField(COUNTRY)
        press("Redo")
        awaitStore(store, listOf("Ask what your cou", "ntry can do.", SECOND))
    }

    /**
     * S5: a split undone and redone twice, with typing in both of its parts: each undo and redo brings the text before
     * the split back under a new id, and every step logged on an older one still finds it.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test fun aLongUndoRedoChainKeepsFindingItsParagraphs() {
        val store = EditStore(listOf(ALPHA))
        rule.setContent { FocalTheme(dark = false) { Note(store, fieldId = 1, caret = "Alpha ".length) } }
        connection()
        field().performKeyInput { pressKey(Key.Enter) }
        awaitStore(store, listOf("Alpha", "beta gamma."))
        press("Undo")
        awaitStore(store, listOf(ALPHA))
        press("Redo")
        awaitStore(store, listOf("Alpha", "beta gamma."))
        // Typing in each part, the caret put there by a tap.
        rule.onNode(hasText("Alpha") and !hasSetTextAction()).performClick()
        awaitField("Alpha")
        field().performTextReplacement("Alpha, typed")
        awaitStore(store, listOf("Alpha, typed", "beta gamma."))
        rule.onNode(hasText("beta gamma.") and !hasSetTextAction()).performClick()
        awaitField("beta gamma.")
        field().performTextReplacement("beta gamma, typed.")
        awaitStore(store, listOf("Alpha, typed", "beta gamma, typed."))
        repeat(2) {
            press("Undo")
            awaitStore(store, listOf("Alpha, typed", "beta gamma."))
            press("Undo")
            awaitStore(store, listOf("Alpha", "beta gamma."))
            press("Undo")
            awaitStore(store, listOf(ALPHA))
            press("Redo")
            awaitStore(store, listOf("Alpha", "beta gamma."))
            press("Redo")
            awaitStore(store, listOf("Alpha, typed", "beta gamma."))
            press("Redo")
            awaitStore(store, listOf("Alpha, typed", "beta gamma, typed."))
        }
        rule.onNode(hasText("Not saved")).assertDoesNotExist()
    }

    private companion object {
        const val NOTE_ID = "edit-session"
        const val FIRST = "The first synthetic paragraph ends here."
        const val SECOND = "Second synthetic paragraph."
        const val COUNTRY = "Ask what your country can do."
        const val ALPHA = "Alpha beta gamma."
        const val ADDED = "Added synthetic words."
        const val REVISED = "Revised synthetic words."
        const val TIMEOUT_MS = 5_000L
    }
}
