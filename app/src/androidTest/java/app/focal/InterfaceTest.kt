package app.focal

import android.content.ClipboardManager
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.data.NoteState
import app.focal.recording.LiveSession
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The rebuilt interface end to end, through MainActivity and the real view models and store: a note opens from the
 * library and Back returns, Copy fills the clipboard, Edit saves through the store, search opens a note at its paragraph, Delete offers
 * Undo, Settings opens by its accessibility action and applies the theme at once, a recording shows its controls in
 * the dock, and a notification's intent opens its note. Notes are synthetic and removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var app: FocalApplication
    private var scenario: ActivityScenario<MainActivity>? = null
    private val created = mutableListOf<String>()
    private val tag = UUID.randomUUID().toString().take(6)
    private var theme = "system"

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking { app.storageReady.await() }
        assumeTrue("Needs an idle recorder", RecordingState.live.value.noteId == null)
        // No peek, and no transcription work started by the activity for notes other tests left waiting.
        app.preferences.settingsOpened = true
        app.autoResumeTranscription = false
        theme = app.preferences.settings.value.theme
    }

    @After fun cleanup() {
        scenario?.close()
        RecordingState.live.value = LiveSession()
        app.preferences.theme(theme)
        app.autoResumeTranscription = true
        created.forEach { app.store.delete(it) }
    }

    private fun note(title: String, paragraphs: List<String>): String =
        app.store.insertNoteForTests(paragraphs, title = title).also {
            created += it
            app.store.audioFile(it).writeBytes(ByteArray(paragraphs.size * BYTES_PER_SECOND))
        }

    private fun launch(intent: Intent = Intent(app, MainActivity::class.java)) {
        scenario = ActivityScenario.launch(intent)
    }

    private fun back() = scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun await(what: String, matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Not shown: $what", timeout)
        }
    }

    private fun awaitGone(what: String, matcher: SemanticsMatcher) {
        try {
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Still shown: $what", timeout)
        }
    }

    /**
     * The note titled [title] in the library: on the stage (the newest note, unless another one is still being
     * transcribed or waits for it, as notes other tests leave behind may) or as the first row of Today.
     */
    private fun inLibrary(title: String): SemanticsMatcher =
        hasContentDescription("Latest note. $title.", substring = true) or (hasText(title) and hasClickAction())

    private fun openFromLibrary(title: String, firstParagraph: String) {
        await("$title in the library", inLibrary(title))
        compose.onAllNodes(inLibrary(title))[0].performClick()
        await("the note page", hasText(firstParagraph))
    }

    @Test fun aNoteOpensFromTheLibraryAndBackReturns() {
        val title = "Library fixture $tag"
        val words = listOf("A synthetic first paragraph for the library.", "A synthetic second paragraph.")
        note(title, words)
        launch()
        openFromLibrary(title, words[0])
        compose.onNode(hasText(title) and isHeading()).assertIsDisplayed()
        compose.onNodeWithText(words[1]).assertIsDisplayed()
        back()
        await("the library again", inLibrary(title))
    }

    @Test fun copyPutsTheNoteOnTheClipboardAndConfirms() {
        val title = "Copy fixture $tag"
        val words = listOf("Copy reads the first synthetic paragraph.", "And the second one, after a blank line.")
        note(title, words)
        launch()
        openFromLibrary(title, words[0])
        compose.onNodeWithContentDescription("Copy").performClick()
        await("Copied", hasContentDescription("Copied"))
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        var text: String? = null
        scenario!!.onActivity { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
        assertEquals(words.joinToString("\n\n"), text)
    }

    @Test fun editSavesTheParagraphThroughTheStore() {
        val title = "Edit fixture $tag"
        val words = listOf("The paragraph before an edit.", "A paragraph that stays.")
        val id = note(title, words)
        launch()
        openFromLibrary(title, words[0])
        compose.onNodeWithContentDescription("Edit").performClick()
        await("the edit bar", hasContentDescription("Done"))
        compose.onNodeWithText(words[0]).performClick()
        await("the paragraph's field", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(REVISED)
        // While the text differs from the recognizer's, the field says so (spec 4.14).
        await("the provenance line", hasText("Edited"))
        compose.onNode(hasContentDescription("Done")).performClick()
        awaitGone("the field", hasSetTextAction())
        compose.waitUntil(TIMEOUT_MS) { app.store.paragraphs(id).firstOrNull()?.text == REVISED }
        assertEquals(listOf(REVISED, words[1]), app.store.paragraphs(id).filterNot { it.hidden }.map { it.text })
        await("the saved paragraph", hasText(REVISED))
        awaitGone("a badge outside Edit", hasText("Edited"))
    }

    @Test fun searchOpensANoteAtItsParagraph() {
        val title = "Search fixture $tag"
        val needle = "zebrafinch$tag"
        val words = listOf("Nothing to find in this first paragraph.", "The $needle sings in the second paragraph.")
        note(title, words)
        launch()
        await("Search", hasContentDescription("Search notes") and hasClickAction())
        compose.onNode(hasContentDescription("Search notes") and hasClickAction()).performClick()
        await("the search field", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(needle)
        val result = hasText(title, substring = true) and hasClickAction()
        await("the result", result)
        compose.onNode(result).performClick()
        await("the note at its paragraph", hasText(words[1]))
        compose.onNodeWithText(words[1]).assertIsDisplayed()
    }

    @Test fun deleteFromTheMenuOffersUndo() {
        val title = "Delete fixture $tag"
        val words = listOf("A synthetic paragraph to delete and keep.")
        val id = note(title, words)
        launch()
        openFromLibrary(title, words[0])
        compose.onNodeWithContentDescription("More options").performClick()
        await("the menu", hasText("Delete") and hasClickAction())
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        await("the feedback pill", hasText("Note deleted"))
        compose.waitUntil(TIMEOUT_MS) { app.store.note(id) == null }
        compose.onNode(hasText("Undo") and hasClickAction()).performClick()
        compose.waitUntil(TIMEOUT_MS) { app.store.note(id) != null }
        await("the note back in the library", inLibrary(title))
    }

    @Test fun settingsOpenByTheirActionAndApplyTheThemeAtOnce() {
        note("Settings fixture $tag", listOf("A note, so the library dock carries Record."))
        launch()
        await("Record", hasContentDescription("Record"))
        // TalkBack's "Open settings" on the dock's Record (spec 4.23.6).
        val actions = compose.onNode(hasContentDescription("Record")).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == "Open settings" }.action() }
        val heading = hasText("Settings") and isHeading()
        await("the settings page", heading)
        compose.onNode(heading).assertIsFocused()
        compose.onNodeWithText("Dark").performClick()
        compose.waitUntil(TIMEOUT_MS) { app.preferences.settings.value.theme == "dark" }
        compose.onNodeWithText("Light").performClick()
        compose.waitUntil(TIMEOUT_MS) { app.preferences.settings.value.theme == "light" }
        compose.onNodeWithText("Open-source licenses").performClick()
        await("the licences", hasText("Parakeet Ultra speech model"))
        back()
        await("settings again", heading)
        back()
        awaitGone("the settings page", heading)
        compose.onNode(hasContentDescription("Record")).assertIsDisplayed()
    }

    @Test fun aRecordingShowsItsControlsInTheDock() {
        val id = app.store.create().also { created += it }
        app.store.state(id, NoteState.PAUSED)
        // Synthetic presentation state; the microphone and the service are tested on their own.
        RecordingState.live.value = LiveSession(noteId = id, paused = true, duration = 73_000)
        launch()
        await("Resume", hasContentDescription("Resume"))
        compose.onNode(hasContentDescription("Finish")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Paused, 1 minute 13 seconds.", substring = true)).assertIsDisplayed()
        // A running wave draws every frame: step the clock by hand from here.
        compose.mainClock.autoAdvance = false
        try {
            RecordingState.live.value = RecordingState.live.value.copy(paused = false)
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            compose.onNode(hasContentDescription("Pause")).assertIsDisplayed()
            compose.onNode(hasContentDescription("Finish")).assertIsDisplayed()
            compose.onNode(hasContentDescription("Recording, 1 minute 13 seconds", substring = true)).assertIsDisplayed()
        } finally {
            RecordingState.live.value = LiveSession()
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            compose.mainClock.autoAdvance = true
        }
    }

    /** What TalkBack, Switch Access and Voice Access get: Android's accessibility tree, not Compose's semantics. */
    @Test fun accessibilityServicesSeeTheStageAndItsActions() {
        note("Accessibility fixture $tag", listOf("A note, so the stage and the dock carry their actions."))
        launch()
        await("the stage", hasContentDescription("Latest note.", substring = true))
        // The tree fills in once the automation's accessibility connection reaches the window.
        var nodes = emptyList<AccessibilityNodeInfo>()
        fun described(prefix: String) = nodes.filter { it.contentDescription?.startsWith(prefix) == true }
        try {
            compose.waitUntil(TIMEOUT_MS) { nodes = platformNodes(); listOf("Latest note.", "Copy note", "Record").all { described(it).isNotEmpty() } }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Not in the accessibility tree: ${nodes.mapNotNull { it.contentDescription ?: it.text }}", timeout)
        }
        // Compose gives a merged node's description its own child, so TalkBack focuses the actionable node above it.
        fun actionable(label: String): AccessibilityNodeInfo = described(label).first().let { node ->
            generateSequence(node) { it.parent }.take(PARENT_STEPS).firstOrNull { it.isClickable || it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_CLICK } } ?: node
        }
        fun AccessibilityNodeInfo.describe() =
            "$contentDescription visible=$isVisibleToUser bounds=${android.graphics.Rect().also(::getBoundsInScreen)} " +
                "actions=${actionList.map { it.label ?: it.id.toString() }}"
        for (label in listOf("Latest note.", "Copy note", "Record")) {
            val node = actionable(label)
            assertTrue("Visible: ${node.describe()}", node.isVisibleToUser)
            // A note with no words has nothing to copy: its Copy is disabled instead.
            assertTrue("Clickable: ${node.describe()}", !node.isEnabled || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        }
        for (label in listOf("Latest note.", "Record")) {
            val node = actionable(label)
            assertTrue("Open settings: ${node.describe()}", node.actionList.any { it.label == "Open settings" })
        }
        assertTrue("The stage offers the row menu", actionable("Latest note.").actionList.any { it.label == "Delete" })
    }

    private fun platformNodes(): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            all += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(root)
        return all
    }

    @Test fun aNotificationOpensItsNote() {
        val words = listOf("A synthetic paragraph a notification opens.")
        val id = note("Notification fixture $tag", words)
        launch(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(RecordingService.NOTE, id))
        await("the note", hasText(words[0]))
        assertNotNull(app.store.note(id))
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 1_000L
        const val BYTES_PER_SECOND = 32_000
        const val REVISED = "The paragraph after a synthetic edit."
        const val PARENT_STEPS = 3
    }
}
