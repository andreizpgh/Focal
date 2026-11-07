package app.focal

import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import app.focal.data.NoteState
import app.focal.data.NoteStore
import app.focal.data.StoredWindow
import app.focal.data.WaitCause
import app.focal.recording.LiveSession
import app.focal.recording.RecordingDecoder
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.ServiceNotifications
import app.focal.recording.SessionOperations
import app.focal.speech.EngineGuard
import app.focal.speech.EngineStage
import app.focal.speech.ModelFailure
import app.focal.speech.ModelStatus
import app.focal.speech.waitCause
import app.focal.ui.state.PlaybackViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * library and Back returns, Copy fills the clipboard, the note's keyboard shortcuts reach it with nothing focused (Space
 * plays and pauses, Ctrl+Z and Ctrl+Shift+Z undo and redo in Edit, Space in Find's query is text), Edit saves
 * through the store, search opens a note at its paragraph, Delete offers
 * Undo, Settings opens by its accessibility action and applies the theme at once, a recording shows its controls in
 * the dock, a notification's intent opens its note, and Edit, Find, an open scene and an open note (between one pane
 * and two, on a tablet-size display) survive turning the display (spec 5.14). Notes are synthetic and removed
 * afterwards.
 */
@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** Stands in for the device's folds and hinges (androidx.window): none, unless a test publishes one. */
    @get:Rule val folds = WindowLayoutInfoPublisherRule()

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
        rotationBefore?.let { InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(it) }
        // The key tests leave touch mode; the tests after them start in it.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
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

    /** A note whose silence lasts long enough to be still playing at the next Space. */
    private fun longNote(title: String, paragraphs: List<String>): String =
        app.store.insertNoteForTests(paragraphs, durationMs = LONG_AUDIO_SECONDS * 1_000L, title = title).also {
            created += it
            app.store.audioFile(it).writeBytes(ByteArray(LONG_AUDIO_SECONDS * BYTES_PER_SECOND))
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

    /** Sends [keyCode] through the input pipeline, as a hardware keyboard would. */
    private fun press(keyCode: Int, meta: Int = 0) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    @Test fun theNotesShortcutsWorkWithNothingFocused() {
        val title = "Keys fixture $tag"
        val words = listOf("A keyboard copies this synthetic paragraph.", "And this one, after a blank line.")
        note(title, words)
        launch()
        openFromLibrary(title, words[0])
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        // Nothing on the page has focus after a tap: the activity hears the keys and hands them to the page.
        press(KeyEvent.KEYCODE_C, ctrl)
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        var text: String? = null
        compose.waitUntil(TIMEOUT_MS) {
            scenario!!.onActivity { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
            text == words.joinToString("\n\n")
        }
        // It went through the dock's Copy, which confirms as a tap does.
        await("Copied", hasContentDescription("Copied"))
        press(KeyEvent.KEYCODE_F, ctrl)
        await("Find", hasSetTextAction() and hasContentDescription("Find in note"))
    }

    private fun ctrlKey(keyCode: Int, meta: Int = 0) =
        KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or meta)

    /** Whether the note's audio plays: the activity's player, read without waiting for Compose to be idle. */
    private fun playing(): Boolean {
        var playing = false
        scenario!!.onActivity { playing = ViewModelProvider(it)[PlaybackViewModel::class.java].session.value.playing }
        return playing
    }

    /** Waits for [condition] without syncing with Compose, which is never idle while the player strip moves. */
    private fun poll(what: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("Not reached: $what")
            Thread.sleep(POLL_MS)
        }
    }

    @Test fun spacePlaysAndPausesTheNoteBeingRead() {
        val title = "Space fixture $tag"
        val words = listOf("Space plays this synthetic paragraph.", "And this one, after a blank line.")
        longNote(title, words)
        launch()
        openFromLibrary(title, words[0])
        val play = hasContentDescription("Play") and hasClickAction()
        await("Play", play)
        // Nothing has focus after the tap: the activity hears Space (onKeyDown) and hands it to the page; a held
        // Space's repeats are not taken.
        var handled = false
        val repeat = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 1)
        scenario!!.onActivity { handled = it.onKeyDown(KeyEvent.KEYCODE_SPACE, repeat) }
        assertFalse("A repeat is not taken", handled)
        val space = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
        scenario!!.onActivity { handled = it.onKeyDown(KeyEvent.KEYCODE_SPACE, space) }
        assertTrue("The activity hands Space to the page", handled)
        poll("playback") { playing() }
        frames()
        scenario!!.onActivity { handled = it.onKeyDown(KeyEvent.KEYCODE_SPACE, space) }
        assertTrue("The activity hands the next Space to the page", handled)
        poll("the pause") { !playing() }
        frames()
        // Android spends the first key after a touch leaving touch mode, which in one pane puts focus on the top
        // bar's back button by itself; leave touch mode and focus it here directly (in two panes the list pane's first
        // control takes that focus). Space is still the page's: held down it plays once (its repeat goes with it), and
        // it never presses the focused button (Back).
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        val back = hasContentDescription("Back") and hasClickAction()
        frames { compose.onNode(back).performSemanticsAction(SemanticsActions.RequestFocus) }
        frames { compose.onNode(back and isFocused()).assertExists() }
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0))
        instrumentation.sendKeySync(KeyEvent(now, now + HELD_MS, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 1))
        instrumentation.sendKeySync(KeyEvent(now, now + HELD_MS, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0))
        poll("playback from the keyboard") { playing() }
        frames()
        assertTrue("Still playing on the note", playing())
        press(KeyEvent.KEYCODE_SPACE)
        poll("the pause from the keyboard") { !playing() }
        frames {
            compose.onNode(play).assertIsDisplayed()
            compose.onNode(hasText(title) and isHeading()).assertIsDisplayed()
        }
    }

    /**
     * A note with nothing to play (its audio removed, spec 5.9): Space is still the page's while it is read, so it never
     * presses the control the first key after a touch focused by itself (Back, in one pane); Enter still does.
     */
    @Test fun spaceOnANoteWithoutAudioPressesNoFocusedControl() {
        val title = "Text-only Space fixture $tag"
        val words = listOf("A synthetic paragraph whose audio was removed.", "And this one, after a blank line.")
        // A migrated note: its text needs no windows, so its audio can be removed at once.
        val id = app.store.insertNoteForTests(words, title = title, legacy = true).also { created += it }
        app.store.audioFile(id).writeBytes(ByteArray(words.size * BYTES_PER_SECOND))
        app.store.removeAudio(id)
        launch()
        openFromLibrary(title, words[0])
        val heading = hasText(title) and isHeading()
        await("the title", heading)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        val back = hasContentDescription("Back") and hasClickAction()
        compose.onNode(back).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(back and isFocused()).assertExists()
        press(KeyEvent.KEYCODE_SPACE)
        compose.waitForIdle()
        compose.onNode(heading).assertIsDisplayed()
        compose.onNode(back and isFocused()).assertExists()
        press(KeyEvent.KEYCODE_ENTER)
        awaitGone("the note page", heading)
        await("the library", inLibrary(title))
    }

    /**
     * Lets the page catch up with the player, as a person's pace would: the test drives Compose's clock, which moves
     * only while the test waits on Compose, and the player strip draws every frame, so Compose never idles. [block]
     * reads the page after the frames.
     */
    private fun frames(block: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            block()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun spaceTypedIntoFindStaysInTheQuery() {
        val title = "Space find fixture $tag"
        val words = listOf("The harbour opens at six.", "Boats leave the harbour late.")
        longNote(title, words)
        launch()
        openFromLibrary(title, words[0])
        press(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        val field = hasSetTextAction() and hasContentDescription("Find in note")
        await("Find", field)
        compose.onNode(field).performTextInput("harbour")
        await("the count", hasText("1 of 2"))
        // The focused query takes Space as text; playback does not start.
        press(KeyEvent.KEYCODE_SPACE)
        await("the space in the query", field and hasText("harbour "))
        back()
        awaitGone("Find", field)
        await("Play", hasContentDescription("Play") and hasClickAction())
        assertTrue("Not playing", compose.onAllNodes(hasContentDescription("Pause playback")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun undoAndRedoKeysReachEditFromTheFieldAndFromTheActivity() {
        val title = "Undo keys fixture $tag"
        val words = listOf("The paragraph before an edit.", "A paragraph that stays.")
        val id = note(title, words)
        launch()
        openFromLibrary(title, words[0])
        compose.onNodeWithContentDescription("Edit").performClick()
        await("the edit bar", hasContentDescription("Done"))
        compose.onNodeWithText(words[0]).performClick()
        await("the paragraph's field", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(REVISED)
        compose.waitUntil(TIMEOUT_MS) { app.store.paragraphs(id).firstOrNull()?.text == REVISED }
        fun stored() = app.store.paragraphs(id).firstOrNull()?.text
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        // The field has focus: the page hears Ctrl+Z before the field's own undo, and undoes the session's step.
        press(KeyEvent.KEYCODE_Z, ctrl)
        compose.waitUntil(TIMEOUT_MS) { stored() == words[0] }
        press(KeyEvent.KEYCODE_Z, ctrl or shift)
        compose.waitUntil(TIMEOUT_MS) { stored() == REVISED }
        // With no focused node to hear them the activity does (onKeyShortcut), and hands them to the page.
        var handled = false
        scenario!!.onActivity { handled = it.onKeyShortcut(KeyEvent.KEYCODE_Z, ctrlKey(KeyEvent.KEYCODE_Z)) }
        assertTrue("Ctrl+Z through the activity", handled)
        compose.waitUntil(TIMEOUT_MS) { stored() == words[0] }
        scenario!!.onActivity { handled = it.onKeyShortcut(KeyEvent.KEYCODE_Z, ctrlKey(KeyEvent.KEYCODE_Z, shift)) }
        assertTrue("Ctrl+Shift+Z through the activity", handled)
        compose.waitUntil(TIMEOUT_MS) { stored() == REVISED }
        // The field shows the redone text before Done reads it (a person cannot tap within that frame).
        await("the redone field", hasSetTextAction() and hasText(REVISED))
        // Out of Edit the page declines them, and the activity leaves them to the system.
        compose.onNode(hasContentDescription("Done")).performClick()
        awaitGone("the field", hasSetTextAction())
        scenario!!.onActivity { handled = it.onKeyShortcut(KeyEvent.KEYCODE_Z, ctrlKey(KeyEvent.KEYCODE_Z)) }
        assertFalse("Ctrl+Z while reading", handled)
        assertEquals(REVISED, stored())
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

    /**
     * Two panes (spec 5.14, 4.23.4): the licences cover the whole window with the library's pane composed beneath
     * them, and Back returns to settings, the next Back to the library. Skipped on a display that does not change
     * panes when turned (run it with `adb shell wm size 1600x2560`).
     */
    @Test fun theLicencesReturnToSettingsInTwoPanes() {
        assumeTurnChangesPanes()
        note("Two-pane settings fixture $tag", listOf("A note, so the library dock carries Record."))
        launch()
        turn(landscape = true)
        await("Record", hasContentDescription("Record"))
        press(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        val heading = hasText("Settings") and isHeading()
        await("the settings page", heading)
        compose.onNodeWithText("Open-source licenses").performClick()
        val licences = hasText("Parakeet Ultra speech model")
        await("the licences", licences)
        back()
        awaitGone("the licences after Back", licences)
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

    /** How the display was turned before a test turned it, to put it back afterwards (a UiAutomation constant). */
    private var rotationBefore: Int? = null

    /**
     * Turns the display as a person turning the phone would (spec 5.14: no orientation locks) and waits until the
     * activity, recreated, is laid out for it: landscape on a phone is a short window with the dock in the rail, on a
     * tablet two panes.
     */
    private fun turn(landscape: Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (rotationBefore == null) {
            val resolver = app.contentResolver
            val automatic = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            // The user rotation setting holds a Surface rotation, which is also the matching freeze constant.
            rotationBefore = if (automatic) UiAutomation.ROTATION_UNFREEZE else Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
        }
        automation.setRotation(if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
        val wanted = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.waitUntil(TIMEOUT_MS) {
            var orientation = Configuration.ORIENTATION_UNDEFINED
            scenario!!.onActivity { orientation = it.resources.configuration.orientation }
            orientation == wanted
        }
        compose.waitForIdle()
    }

    @Test fun editKeepsItsFieldAcrossARotation() {
        val title = "Rotate edit fixture $tag"
        val words = listOf("The paragraph a rotation interrupts.", "A paragraph that stays.")
        val id = note(title, words)
        launch()
        openFromLibrary(title, words[0])
        compose.onNodeWithContentDescription("Edit").performClick()
        await("the edit bar", hasContentDescription("Done"))
        compose.onNodeWithText(words[0]).performClick()
        await("the paragraph's field", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(REVISED)
        turn(landscape = true)
        // Still in Edit, the same paragraph's field holding the draft, its provenance shown (spec 4.14).
        await("the edit bar after the turn", hasContentDescription("Done"))
        await("the draft after the turn", hasSetTextAction() and hasText(REVISED))
        await("the provenance line", hasText("Edited"))
        turn(landscape = false)
        await("the draft turned back", hasSetTextAction() and hasText(REVISED))
        compose.onNode(hasContentDescription("Done")).performClick()
        awaitGone("the field", hasSetTextAction())
        compose.waitUntil(TIMEOUT_MS) { app.store.paragraphs(id).firstOrNull()?.text == REVISED }
    }

    @Test fun findKeepsItsQueryAcrossARotation() {
        val title = "Rotate find fixture $tag"
        val words = listOf("The harbour opens at six.", "Boats leave the harbour late, and the harbour master waits.")
        note(title, words)
        launch()
        openFromLibrary(title, words[0])
        press(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        val field = hasSetTextAction() and hasContentDescription("Find in note")
        await("Find", field)
        compose.onNode(field).performTextInput("harbour")
        await("the count", hasText("1 of 3"))
        turn(landscape = true)
        await("the query after the turn", field and hasText("harbour"))
        await("the count after the turn", hasText("of 3", substring = true))
        compose.onNode(hasContentDescription("Next match") and hasClickAction()).assertIsDisplayed()
    }

    /** A display that is one pane upright and two panes turned (a tablet, spec 5.14). */
    private fun assumeTurnChangesPanes() {
        val config = app.resources.configuration
        val short = minOf(config.screenWidthDp, config.screenHeightDp)
        val long = maxOf(config.screenWidthDp, config.screenHeightDp)
        assumeTrue("Needs a display that is one pane upright and two panes turned", short in 480 until 840 && long >= 840)
    }

    /**
     * A tablet (one pane upright, two turned, spec 5.14): the open note moves into the detail pane beside the library
     * and back, keeping what was being done on it (Edit with its draft and its undo log, Find with its query), and
     * Back from the detail pane leaves the library with Record. Skipped on a display that does not change panes when
     * turned (run it with `adb shell wm size 1600x2560`).
     */
    @Test fun aNoteMovesBetweenOneAndTwoPanesOnATurn() {
        assumeTurnChangesPanes()
        val title = "Panes fixture $tag"
        val words = listOf("A synthetic paragraph that moves between panes.", "The harbour opens at six, and the harbour master waits.")
        // The library's stage or row, never the page's own title (which also takes a tap, to rename).
        val listed = hasContentDescription("Latest note. $title.", substring = true) or (hasText(title) and hasClickAction() and !isHeading())
        val undo = hasContentDescription("Undo") and hasClickAction() and isEnabled()
        val id = note(title, words)
        launch()
        turn(landscape = false)
        openFromLibrary(title, words[0])
        // Edit: one saved step (Undo on), then a draft typed just before the turn.
        compose.onNodeWithContentDescription("Edit").performClick()
        await("the edit bar", hasContentDescription("Done"))
        compose.onNodeWithText(words[0]).performClick()
        await("the paragraph's field", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement(REVISED)
        compose.waitUntil(TIMEOUT_MS) { app.store.paragraphs(id).firstOrNull()?.text == REVISED }
        await("Undo", undo)
        compose.onNode(hasSetTextAction()).performTextReplacement(DRAFT)
        turn(landscape = true)
        // Side by side: the library's stage or row beside the note, still in Edit with the draft and its log.
        await("the library beside the note", listed)
        await("the edit bar in two panes", hasContentDescription("Done"))
        await("the draft in two panes", hasSetTextAction() and hasText(DRAFT))
        await("Undo in two panes", undo)
        turn(landscape = false)
        awaitGone("the library under the page", listed)
        await("the draft in one pane", hasSetTextAction() and hasText(DRAFT))
        // The log came through both turns whole: the draft, then the saved step, then the recognised text.
        compose.onNode(undo).performClick()
        compose.waitUntil(TIMEOUT_MS) { app.store.paragraphs(id).firstOrNull()?.text == REVISED }
        compose.onNode(undo).performClick()
        compose.waitUntil(TIMEOUT_MS) { app.store.paragraphs(id).firstOrNull()?.text == words[0] }
        compose.onNode(hasContentDescription("Done")).performClick()
        awaitGone("the field", hasSetTextAction())
        // Find: its query and count across both turns.
        press(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        val field = hasSetTextAction() and hasContentDescription("Find in note")
        await("Find", field)
        compose.onNode(field).performTextInput("harbour")
        await("the count", hasText("1 of 2"))
        turn(landscape = true)
        await("the note beside the library again", listed)
        await("the query in two panes", field and hasText("harbour"))
        await("the count in two panes", hasText("of 2", substring = true))
        turn(landscape = false)
        await("the query in one pane", field and hasText("harbour"))
        turn(landscape = true)
        await("the query turned again", field and hasText("harbour"))
        back()
        awaitGone("Find after Back", field)
        compose.onNode(hasText(title) and isHeading()).assertIsDisplayed()
        back()
        awaitGone("the detail pane's note", hasText(words[1]))
        await("Record in the list pane", hasContentDescription("Record") and hasClickAction())
    }

    /**
     * Two panes and a hardware keyboard (spec 5.14, 8.5): with focus in the library's pane, where the first key after a
     * touch puts it by itself, Space plays and pauses the open note (a held Space once) and Ctrl+F opens its Find; a
     * rename in the library's pane still takes Space as text. Skipped on a display that does not change panes when
     * turned (run it with `adb shell wm size 1600x2560`).
     */
    @Test fun theOpenNoteTakesItsShortcutsFromTheLibraryPane() {
        assumeTurnChangesPanes()
        val title = "Two-pane keys fixture $tag"
        val words = listOf("Space plays this synthetic paragraph beside the library.", "And this one, after a blank line.")
        longNote(title, words)
        launch()
        turn(landscape = true)
        // The library's stage or row, never the page's own title (which also takes a tap, to rename).
        val listed = hasContentDescription("Latest note. $title.", substring = true) or (hasText(title) and hasClickAction() and !isHeading())
        await("the note in the library", listed)
        compose.onAllNodes(listed)[0].performClick()
        await("the note beside the library", hasText(words[0]))
        val play = hasContentDescription("Play") and hasClickAction()
        await("Play", play)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        frames { compose.onAllNodes(listed)[0].performSemanticsAction(SemanticsActions.RequestFocus) }
        frames { compose.onAllNodes(listed and isFocused()).assertCountEquals(1) }
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0))
        instrumentation.sendKeySync(KeyEvent(now, now + HELD_MS, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 1))
        instrumentation.sendKeySync(KeyEvent(now, now + HELD_MS, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0))
        poll("playback from the library's pane") { playing() }
        frames()
        assertTrue("Still playing", playing())
        press(KeyEvent.KEYCODE_SPACE)
        poll("the pause from the library's pane") { !playing() }
        frames { compose.onAllNodes(listed and isFocused()).assertCountEquals(1) }
        press(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        val find = hasSetTextAction() and hasContentDescription("Find in note")
        await("Find in the note's pane", find)
        back()
        awaitGone("Find", find)
        // A rename in the library's pane types its Space.
        frames {
            val menu = compose.onAllNodes(listed)[0].fetchSemanticsNode().config[SemanticsActions.CustomActions]
            compose.runOnIdle { menu.first { it.label == "Rename" }.action() }
        }
        val rename = hasSetTextAction() and !hasContentDescription("Find in note")
        await("the rename field", rename and isFocused())
        press(KeyEvent.KEYCODE_SPACE)
        await("the space in the title", rename and hasText(" "))
        assertFalse("Not playing", playing())
        back()
        awaitGone("the rename field", rename)
    }

    /**
     * Two panes and a hardware keyboard (spec 8.5): a focused stage keeps the keys it answers itself while a note is
     * open beside the library. Space on the focused live stage expands it instead of going to the note. Skipped on a
     * display that does not change panes when turned (run it with `adb shell wm size 1600x2560`).
     */
    @Test fun spaceOnTheFocusedLiveStageExpandsItBesideAnOpenNote() {
        assumeTurnChangesPanes()
        val title = "Live stage keys fixture $tag"
        val words = listOf("A synthetic paragraph open beside a recording.")
        longNote(title, words)
        val id = app.store.create().also { created += it }
        app.store.state(id, NoteState.PAUSED)
        // Synthetic presentation state (paused, so nothing draws every frame); the service is tested on its own.
        RecordingState.live.value = LiveSession(noteId = id, paused = true, duration = 73_000)
        launch()
        turn(landscape = true)
        val row = hasText(title) and hasClickAction() and !isHeading()
        await("the note's row", row)
        compose.onNode(row).performClick()
        await("the note beside the library", hasText(words[0]))
        val stage = hasContentDescription("Paused, 1 minute 13 seconds.", substring = true) and hasClickAction()
        await("the live stage", stage)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        // Put there on purpose: leaving touch mode puts focus on the stage by itself, which leaves the note its keys.
        compose.onNode(row).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(stage).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(stage and isFocused()).assertIsDisplayed()
        press(KeyEvent.KEYCODE_SPACE)
        await("the scene", hasContentDescription("Collapse. Recording continues"))
        assertFalse("The open note does not play", playing())
    }

    /**
     * Two panes and a hardware keyboard (spec 8.5): Ctrl+C on a focused stage copies the stage's note and confirms on
     * its pill, while the note open beside the library is left alone. Skipped on a display that does not change panes
     * when turned (run it with `adb shell wm size 1600x2560`).
     */
    @Test fun ctrlCOnTheFocusedStageCopiesItsOwnNoteBesideAnOpenNote() {
        assumeTurnChangesPanes()
        val openTitle = "Open copy fixture $tag"
        val openWords = listOf("The open note's synthetic paragraph.")
        val stageTitle = "Stage copy fixture $tag"
        val stageWords = listOf("The stage's own synthetic paragraph.", "And its second one.")
        // The open note is a minute older, so the stage holds the other one.
        app.store.insertNoteForTests(openWords, title = openTitle, created = System.currentTimeMillis() - 60_000).also {
            created += it
            app.store.audioFile(it).writeBytes(ByteArray(BYTES_PER_SECOND))
        }
        note(stageTitle, stageWords)
        launch()
        turn(landscape = true)
        val row = hasText(openTitle) and hasClickAction() and !isHeading()
        await("the open note's row", row)
        compose.onNode(row).performClick()
        await("the note beside the library", hasText(openWords[0]))
        val stage = hasContentDescription("Latest note. $stageTitle.", substring = true)
        await("the stage", stage)
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        // Put there on purpose: leaving touch mode puts focus on the stage by itself, which leaves the note its keys.
        compose.onNode(row).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(stage).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(stage and isFocused()).assertIsDisplayed()
        press(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        var text: String? = null
        try {
            compose.waitUntil(TIMEOUT_MS) {
                scenario!!.onActivity { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
                text == stageWords.joinToString("\n\n")
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("The clipboard holds \"$text\", not the stage's note", timeout)
        }
        // Through the stage's pill, which confirms as a tap does; the open note's Copy did not run.
        await("Copied on the stage", hasContentDescription("Copied") and hasAnyAncestor(stage))
        compose.onAllNodes(hasContentDescription("Copied")).assertCountEquals(1)
    }

    /**
     * Two panes and a hardware keyboard (spec 8.5): the first key after a touch leaves touch mode and puts focus where
     * the framework chooses, by itself: the stage. A stage that gets it that way takes none of the open note's keys:
     * beside a stage holding another note Space plays and pauses the note opened from its row; beside a live stage
     * (the recording holds the audio, so nothing plays) Space does not expand the scene; and Ctrl+C copies the open
     * note. Skipped on a display that does not change panes when turned (run it with
     * `adb shell wm size 1600x2560`).
     */
    @Test fun keysAfterATouchGoToTheOpenNoteBesideAStage() {
        assumeTurnChangesPanes()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        for (live in listOf(false, true)) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
            val openTitle = "Touch keys fixture ${if (live) "live" else "note"} $tag"
            val openWords = listOf("The open note's synthetic paragraph, which Space plays.")
            // Older than the stage's note, so the stage holds that one.
            app.store.insertNoteForTests(openWords, durationMs = LONG_AUDIO_SECONDS * 1_000L, title = openTitle,
                created = System.currentTimeMillis() - 60_000).also {
                created += it
                app.store.audioFile(it).writeBytes(ByteArray(LONG_AUDIO_SECONDS * BYTES_PER_SECOND))
            }
            if (live) {
                val id = app.store.create().also { created += it }
                app.store.state(id, NoteState.PAUSED)
                RecordingState.live.value = LiveSession(noteId = id, paused = true, duration = 73_000)
            } else note("Stage beside it $tag", listOf("The stage's own synthetic paragraph."))
            launch()
            turn(landscape = true)
            val row = hasText(openTitle) and hasClickAction() and !isHeading()
            await("the open note's row", row)
            compose.onNode(row).performClick()
            await("the note beside the library", hasText(openWords[0]))
            // A recording holds the audio: the open note cannot play, and Space while reading does nothing then.
            if (!live) await("Play", hasContentDescription("Play") and hasClickAction())
            // Out of touch mode as the first key leaves it: the framework puts focus on the stage by itself.
            InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
            var focusedBefore = emptyList<String>()
            frames { focusedBefore = focusedNodes() }
            press(KeyEvent.KEYCODE_SPACE)
            if (!live) poll("the open note playing") { playing() }
            press(KeyEvent.KEYCODE_SPACE)
            if (!live) poll("the open note paused") { !playing() }
            // Without syncing with Compose, which is never idle while the player strip moves.
            frames { compose.onAllNodes(hasContentDescription("Collapse. Recording continues")).assertCountEquals(0) }
            press(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            var text: String? = null
            try {
                // Advancing Compose's clock, which runs the dock's copy.
                compose.waitUntil(TIMEOUT_MS) {
                    scenario!!.onActivity { text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
                    text == openWords[0]
                }
            } catch (missing: ComposeTimeoutException) {
                var focusedAfter = emptyList<String>()
                var copied = 0
                frames {
                    focusedAfter = focusedNodes()
                    copied = compose.onAllNodes(hasContentDescription("Copied")).fetchSemanticsNodes().size
                }
                throw AssertionError("The clipboard holds \"$text\", not the open note (live stage: $live; focused before the keys " +
                    "$focusedBefore, after $focusedAfter; Copied shown $copied)", missing)
            }
            scenario?.close()
            scenario = null
            RecordingState.live.value = LiveSession()
        }
    }

    /** What has focus, by its description or text, for a failure's message. */
    private fun focusedNodes(): List<String> = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "a node without a label"
    }

    /**
     * Two panes (spec 4.23.6, 5.14): Ctrl+, opens settings with a note open beside the library, as it does from the
     * library alone, and Back returns to the note. Skipped on a display that does not change panes when turned (run it
     * with `adb shell wm size 1600x2560`).
     */
    @Test fun ctrlCommaOpensSettingsBesideAnOpenNote() {
        assumeTurnChangesPanes()
        val title = "Two-pane settings key fixture $tag"
        val words = listOf("A synthetic paragraph open beside the library.")
        note(title, words)
        launch()
        turn(landscape = true)
        val listed = hasContentDescription("Latest note. $title.", substring = true) or (hasText(title) and hasClickAction() and !isHeading())
        await("the note in the library", listed)
        compose.onAllNodes(listed)[0].performClick()
        await("the note beside the library", hasText(words[0]))
        press(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        val heading = hasText("Settings") and isHeading()
        await("the settings page", heading)
        back()
        awaitGone("the settings page", heading)
        compose.onNode(hasText(words[0])).assertIsDisplayed()
    }

    /**
     * A foldable (spec 5.14): with a separating vertical hinge across a two-pane window, the library's pane ends at the
     * hinge and the note, its dock with it, starts after it; a horizontal fold (tabletop) leaves the panes as the
     * window's size has them. The hinge is simulated (androidx.window's testing publisher). Skipped on a display that
     * does not change panes when turned (run it with `adb shell wm size 1600x2560`).
     */
    @Test fun aSeparatingHingeSplitsTheTwoPanesAtItself() {
        assumeTurnChangesPanes()
        val title = "Hinge fixture $tag"
        val words = listOf("A synthetic paragraph beside a hinge.")
        note(title, words)
        launch()
        turn(landscape = true)
        val listed = hasContentDescription("Latest note. $title.", substring = true) or (hasText(title) and hasClickAction() and !isHeading())
        await("the note in the library", listed)
        compose.onAllNodes(listed)[0].performClick()
        val heading = hasText(title) and isHeading()
        await("the note beside the library", heading)
        val copy = hasContentDescription("Copy") and hasClickAction()
        await("the note's Copy", copy)
        // No fold: the list pane is the window's first 412 dp, the note after it.
        val unfolded = compose.onNode(heading).getBoundsInRoot().left
        assertTrue("The note starts after 412 dp", unfolded >= 412.dp)
        assertTrue("The library ends by 412 dp", compose.onAllNodes(listed)[0].getBoundsInRoot().right <= 412.dp)
        // A 32 dp hinge across the window's middle, as the activity's window would report it.
        var window = Rect()
        var hinge: FoldingFeature? = null
        scenario!!.onActivity { activity ->
            val size = (HINGE_DP * activity.resources.displayMetrics.density).toInt()
            hinge = FoldingFeature(activity, size = size, state = FoldingFeature.State.FLAT, orientation = FoldingFeature.Orientation.VERTICAL)
            window = Rect(0, 0, activity.window.decorView.width, activity.window.decorView.height)
        }
        val feature = hinge!!
        assertTrue("A hinge with a width separates", feature.isSeparating)
        val density = app.resources.displayMetrics.density
        val hingeStart = (feature.bounds.left / density).dp
        val hingeEnd = (feature.bounds.right / density).dp
        assumeTrue("Both sides of the hinge leave 360 dp", hingeStart >= 360.dp && (window.width() / density).dp - hingeEnd >= 360.dp)
        val split = TestWindowLayoutInfo(listOf(feature))
        compose.waitUntil(TIMEOUT_MS) {
            // The publisher keeps nothing for a collector that starts later: publish until the layout follows.
            folds.overrideWindowLayoutInfo(split)
            compose.onNode(heading).getBoundsInRoot().left >= hingeEnd
        }
        val library = compose.onAllNodes(listed)[0].getBoundsInRoot()
        assertTrue("The library ends before the hinge (${library.right} > $hingeStart)", library.right <= hingeStart)
        assertTrue("The library widens up to the hinge (${library.right})", library.right > 412.dp || hingeStart <= 412.dp + 24.dp)
        assertTrue("The note's dock starts after the hinge", compose.onNode(copy).getBoundsInRoot().left >= hingeEnd)
        // A fold across the window (tabletop) changes nothing: the panes are as the window's size has them.
        val tabletop = TestWindowLayoutInfo(
            listOf(FoldingFeature(window, state = FoldingFeature.State.HALF_OPENED, orientation = FoldingFeature.Orientation.HORIZONTAL)),
        )
        compose.waitUntil(TIMEOUT_MS) {
            folds.overrideWindowLayoutInfo(tabletop)
            compose.onNode(heading).getBoundsInRoot().left < hingeStart
        }
        assertEquals(unfolded.value, compose.onNode(heading).getBoundsInRoot().left.value, 0.5f)
    }

    /** A tablet turned with library search open (spec 5.2, 5.14): search stays open, its query and results with it. */
    @Test fun librarySearchStaysOpenAcrossATurnBetweenPanes() {
        assumeTurnChangesPanes()
        val title = "Panes search fixture $tag"
        val needle = "kingfisher$tag"
        note(title, listOf("The $needle waits on the reed."))
        launch()
        turn(landscape = false)
        await("Search", hasContentDescription("Search notes") and hasClickAction())
        compose.onNode(hasContentDescription("Search notes") and hasClickAction()).performClick()
        await("the search field", hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(needle)
        val result = hasText(title, substring = true) and hasClickAction()
        await("the result", result)
        turn(landscape = true)
        await("the query in two panes", hasSetTextAction() and hasText(needle))
        await("the result in two panes", result)
        turn(landscape = false)
        await("the query in one pane", hasSetTextAction() and hasText(needle))
        await("the result in one pane", result)
        back()
        awaitGone("the search field after Back", hasSetTextAction())
    }

    @Test fun anOpenSceneStaysOpenAcrossARotation() {
        val id = app.store.create().also { created += it }
        app.store.state(id, NoteState.PAUSED)
        // Synthetic presentation state (paused, so nothing draws every frame); the service is tested on its own.
        RecordingState.live.value = LiveSession(noteId = id, paused = true, duration = 73_000)
        launch()
        val stage = hasContentDescription("Paused, 1 minute 13 seconds.", substring = true) and hasClickAction()
        await("the live stage", stage)
        compose.onNode(stage).performClick()
        val chevron = hasContentDescription("Collapse. Recording continues")
        await("the scene", chevron)
        turn(landscape = true)
        // The scene is still open, now in two columns with Resume and Finish in the rail (spec 5.14).
        await("the scene after the turn", chevron)
        compose.onNode(hasContentDescription("Resume") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasContentDescription("Finish") and hasClickAction()).assertIsDisplayed()
        turn(landscape = false)
        await("the scene turned back", chevron)
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

    /**
     * Spec 5.4 "Model couldn't load": once the speech work killed the process or failed to load twice (the engine
     * guard), a waiting note says why (memory only when confirmed) and offers Try again, which forgets the failures and
     * transcribes it.
     */
    @Test fun aStoppedSpeechEngineOffersTryAgainOnAWaitingNote() {
        val id = app.store.create().also { created += it }
        // A recording a process death left waiting: saved near-silence (one step of noise, so it is kept), no text yet.
        app.store.audioFile(id).writeBytes(ByteArray(2 * BYTES_PER_SECOND) { if (it % 4 == 2) 1 else 0 })
        app.store.duration(id, 2_000)
        app.store.state(id, NoteState.INTERRUPTED)
        repeat(EngineGuard.MAX_DEATHS) { app.engineGuard.failed(EngineStage.RECOGNIZER) }
        // Silence needs no decode; this one only keeps the real recognizer from loading.
        RecordingService.decoderFactoryForTests = {
            object : RecordingDecoder {
                override suspend fun transcribe(store: NoteStore, window: StoredWindow, threads: Int) = error("Silence is never decoded")
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
        try {
            launch(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(RecordingService.NOTE, id))
            // Failed loads are not a confirmed out-of-memory: the neutral sentence, with the audio kept.
            val reason = hasText("Transcription stopped after repeated failures. Your audio is saved.")
            await("the reason", reason)
            val tryAgain = hasContentDescription("Try again") and hasClickAction()
            compose.onNode(tryAgain).assertIsDisplayed()
            compose.onNode(tryAgain).performClick()
            // Try again reaches the service, which forgets the failures before it transcribes.
            compose.waitUntil(TIMEOUT_MS) { app.engineGuard.allows() }
            await("the transcribed note", hasText("No speech was recognized."))
            awaitGone("the reason", reason)
            compose.waitUntil(TIMEOUT_MS) {
                app.store.note(id)?.state == NoteState.READY && !SessionOperations.matchesSession(false, id) &&
                    RecordingState.live.value.noteId == null
            }
        } finally {
            app.engineGuard.clearForTests()
            RecordingService.decoderFactoryForTests = null
            ServiceNotifications.cancelReady(app, id)
        }
    }

    /**
     * A model that could not be prepared leaves a waiting note that says why and offers Try again: what to free when
     * storage ran short, a neutral sentence when the copy failed otherwise. Try again prepares the model again (copied
     * from the APK unless it is installed already) and transcribes the note, which keeps its audio throughout. A damaged
     * copy in the APK says to reinstall Focal and offers no Try again, which could not mend it.
     */
    @Test fun aModelThatCouldNotBePreparedSaysWhyAndTryAgainTranscribes() {
        assumeTrue("The model is bundled", runBlocking { app.models.awaitFiles() } != null)
        RecordingService.decoderFactoryForTests = {
            object : RecordingDecoder {
                override suspend fun transcribe(store: NoteStore, window: StoredWindow, threads: Int) = error("Silence is never decoded")
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
        val cases = listOf(
            ModelStatus.Failed("Free up space to prepare the speech model.", failure = ModelFailure.STORAGE, shortBytes = 850_000_000) to
                "Free up about 850 MB to prepare the speech model. Your audio is saved.",
            ModelStatus.Failed("Could not prepare private storage for the speech model.", failure = ModelFailure.INSTALL) to
                "The speech model couldn’t be prepared. Your audio is saved.",
        )
        try {
            for ((failure, sentence) in cases) {
                val id = app.store.create().also { created += it }
                app.store.audioFile(id).writeBytes(ByteArray(2 * BYTES_PER_SECOND) { if (it % 4 == 2) 1 else 0 })
                app.store.duration(id, 2_000)
                app.store.state(id, NoteState.INTERRUPTED, WaitCause.encode(failure.waitCause()))
                launch(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(RecordingService.NOTE, id))
                // The foreground start checks a failed install again by itself; this one fails after that check.
                runBlocking { app.models.awaitFiles() }
                app.models.settleForTests(failure)
                val reason = hasText(sentence)
                await("the reason", reason)
                val tryAgain = hasContentDescription("Try again") and hasClickAction()
                compose.onNode(tryAgain).assertIsDisplayed()
                compose.onNode(tryAgain).performClick()
                await("the transcribed note", hasText("No speech was recognized."))
                awaitGone("the reason", reason)
                compose.waitUntil(TIMEOUT_MS) {
                    app.store.note(id)?.state == NoteState.READY && app.store.audioFile(id).length() == 2L * BYTES_PER_SECOND &&
                        !SessionOperations.matchesSession(false, id) && RecordingState.live.value.noteId == null
                }
                assertTrue(app.models.status.value is ModelStatus.Ready)
                scenario?.close()
                scenario = null
                ServiceNotifications.cancelReady(app, id)
            }
            val damaged = ModelStatus.Failed("The bundled speech model is damaged. Reinstall Focal.", permanent = true, failure = ModelFailure.DAMAGED)
            val id = app.store.create().also { created += it }
            app.store.audioFile(id).writeBytes(ByteArray(2 * BYTES_PER_SECOND) { if (it % 4 == 2) 1 else 0 })
            app.store.duration(id, 2_000)
            app.store.state(id, NoteState.INTERRUPTED, WaitCause.encode(damaged.waitCause()))
            launch(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(RecordingService.NOTE, id))
            runBlocking { app.models.awaitFiles() }
            app.models.settleForTests(damaged)
            await("the reason", hasText("The speech model is damaged: reinstall Focal. Your audio is saved."))
            compose.onNode(hasContentDescription("Try again") and hasClickAction()).assertDoesNotExist()
            assertTrue("Its audio is kept", app.store.audioFile(id).length() == 2L * BYTES_PER_SECOND)
        } finally {
            RecordingService.decoderFactoryForTests = null
            if (app.models.status.value is ModelStatus.Failed) app.models.retry()
        }
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
        const val LONG_AUDIO_SECONDS = 30
        const val POLL_MS = 50L
        const val HELD_MS = 500L
        const val REVISED = "The paragraph after a synthetic edit."
        const val DRAFT = "The paragraph after a synthetic edit, typed on."
        const val PARENT_STEPS = 3
        /** The simulated hinge's width. */
        const val HINGE_DP = 32
    }
}
