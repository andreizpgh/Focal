package app.focal

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.Note
import app.focal.data.Passage
import app.focal.recording.LiveSession
import app.focal.ui.FocalTheme
import app.focal.ui.NoteScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LiveTranscriptTest {
    @get:Rule val compose = createComposeRule()
    private val note = Note("live-note", "Synthetic recording title", 0, "en", "RECORDING", 0, "")
    private val list get() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    private val latest get() = compose.onNodeWithContentDescription("Jump to latest transcript")

    @Test fun manualScrollAndRestorationHoldPositionUntilLatestIsTapped() {
        var passages by mutableStateOf((1..14).map(::passage))
        val restore = StateRestorationTester(compose)
        restore.setContent { LiveNote(note, passages, LiveSession(noteId = note.id)) }

        compose.onNodeWithText(passage(14).text).assertIsDisplayed()
        compose.onNodeWithText(note.title).assertDoesNotExist()
        compose.runOnIdle { passages = passages + passage(15) }
        compose.onNodeWithText(passage(15).text).assertIsDisplayed()
        list.performTouchInput { swipeDown(startY = height * .15f, endY = height * .9f, durationMillis = 600) }
        latest.assertIsDisplayed()
        val reading = (14 downTo 1).map { compose.onNodeWithText(passage(it).text) }.first { it.isDisplayed() }
        val readingTop = reading.getUnclippedBoundsInRoot().top.value

        compose.runOnIdle { passages = passages + passage(16) }
        assertFalse(compose.onNodeWithText(passage(16).text).isDisplayed())
        assertEquals(readingTop, reading.getUnclippedBoundsInRoot().top.value, 1f)
        restore.emulateSavedInstanceStateRestore()
        latest.assertIsDisplayed()
        reading.assertIsDisplayed()
        assertEquals(readingTop, reading.getUnclippedBoundsInRoot().top.value, 1f)
        capture("live-transcript-manual-position")

        latest.performClick()
        latest.assertDoesNotExist()
        compose.onNodeWithText(passage(16).text).assertIsDisplayed()
        compose.runOnIdle { passages = passages + passage(17) }
        compose.onNodeWithText(passage(17).text).assertIsDisplayed()
    }

    @Test fun resumesAtTheEndOfATallPassageAndFollowsLateFinishSnapshot() {
        var currentNote by mutableStateOf(note)
        var passages by mutableStateOf((1..12).map(::passage))
        var live by mutableStateOf<LiveSession?>(LiveSession(noteId = note.id))
        var editorOpen by mutableStateOf(false)
        val tallText = (1..28).joinToString("\n") { "Synthetic line $it" }
        compose.setContent { LiveNote(currentNote, passages, live, editorOpen) }
        val previous = compose.onNodeWithText(passage(12).text)
        previous.assertIsDisplayed()
        val previousTop = previous.getUnclippedBoundsInRoot().top.value

        compose.runOnIdle {
            editorOpen = true
            passages = passages + passage(13).copy(text = tallText)
        }
        assertEquals(previousTop, previous.getUnclippedBoundsInRoot().top.value, 1f)
        compose.runOnIdle { editorOpen = false }
        assertTrue("The last line of a tall passage must be visible", lastLineIsVisible(tallText))
        capture("live-transcript-long-tail")

        compose.runOnIdle {
            currentNote = currentNote.copy(state = "PROCESSING")
            live = live?.copy(processing = true, message = "Finishing transcript…")
            passages = passages + passage(14)
        }
        compose.onNodeWithText("Finishing transcript…").assertIsDisplayed()
        compose.onNodeWithText(passage(14).text).assertIsDisplayed()
        compose.runOnIdle { live = null }
        compose.onNodeWithText("Finishing transcript…").assertDoesNotExist()
        compose.runOnIdle {
            currentNote = currentNote.copy(state = "READY")
            passages = passages + passage(15)
        }
        compose.onNodeWithText(passage(15).text).assertIsDisplayed()
        latest.assertDoesNotExist()
    }

    @Test fun viewportResizeKeepsTheTailOnlyWhileFollowing() {
        var width by mutableStateOf(320.dp)
        var height by mutableStateOf(320.dp)
        val last = passage(14)
        val passages = (1..14).map(::passage)
        compose.setContent { LiveNote(note, passages, LiveSession(noteId = note.id), width = width, height = height) }
        assertTrue(lastLineIsVisible(last.text))

        compose.runOnIdle { width = 240.dp; height = 210.dp }
        assertTrue("The last line must stay visible after reflow", lastLineIsVisible(last.text))

        list.performTouchInput { swipeDown(startY = this.height * .1f, endY = this.height * .9f, durationMillis = 600) }
        latest.assertIsDisplayed()
        assertFalse(lastLineIsVisible(last.text))
        val reading = passages.map { compose.onNodeWithText(it.text) }.first { it.isDisplayed() }
        val readingTop = reading.getUnclippedBoundsInRoot().top.value

        compose.runOnIdle { height = 170.dp }
        latest.assertIsDisplayed()
        reading.assertIsDisplayed()
        assertEquals(readingTop, reading.getUnclippedBoundsInRoot().top.value, 1f)
        assertFalse(lastLineIsVisible(last.text))
    }

    private fun lastLineIsVisible(text: String): Boolean {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        if (!node.isDisplayed()) return false
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val lineBottom = node.getUnclippedBoundsInRoot().top + with(compose.density) { layout.getLineBottom(layout.lineCount - 1).toDp() }
        val viewport = list.getUnclippedBoundsInRoot()
        return lineBottom > viewport.top && lineBottom <= viewport.bottom
    }

    @Composable private fun LiveNote(
        note: Note, passages: List<Passage>, live: LiveSession?, editorOpen: Boolean = false,
        width: Dp = 360.dp, height: Dp = 320.dp,
    ) {
        FocalTheme("light") {
            Box(Modifier.size(width, height)) {
                NoteScreen(note, LibraryState(note = note, passages = passages, loaded = true), live,
                    reviewOnly = false, toggleReview = {}, edit = {}, retry = {}, playing = false,
                    audioAvailable = false, play = {}, rename = {}, followSuspended = editorOpen)
            }
        }
    }

    private fun passage(index: Int) = Passage(
        id = index.toLong(), noteId = note.id, chunkId = "fixture", start = (index - 1) * 1_000L, end = index * 1_000L,
        text = "Passage $index. These words make the recording long enough to scroll.", uncertain = false,
    )

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots/$name.png")
        output.parentFile!!.mkdirs()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
