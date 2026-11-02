package app.focal

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.core.Transcript
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.data.Preferences
import app.focal.recording.LiveSession
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.ui.FocalApp
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class InterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun application(): FocalApplication = ApplicationProvider.getApplicationContext<FocalApplication>().also {
        kotlinx.coroutines.runBlocking { it.initialized.await() }
    }

    private fun readySession(
        store: NoteStore, title: String, texts: List<String>, language: String = "en",
        uncertain: Set<Int> = emptySet(), duration: Long = texts.size * 1_000L,
    ): String {
        val id = store.create(language)
        store.audioFile(id).writeBytes(ByteArray((duration * 32).toInt()))
        val interval = duration / texts.size
        require(interval in 1..30_000L) { "Fixture passages must fit the recording chunk limit." }
        texts.forEachIndexed { index, text ->
            val start = index * interval
            val end = if (index == texts.lastIndex) duration else (index + 1) * interval
            val chunk = store.beginChunk(id, start, false)
            store.finishChunk(chunk, end)
            store.complete(store.nextChunk(id)!!, listOf(Passage(noteId = id, chunkId = chunk,
                start = start, end = end, text = text, uncertain = index in uncertain)))
        }
        store.rename(id, title)
        store.duration(id, duration)
        store.state(id, "READY")
        return id
    }

    private fun waitForTitle(title: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForSessionReady(title: String, passage: String? = null, audioAvailable: Boolean = true) {
        // Selecting a session loads its audio and passages on IO; Compose idle alone does not await that load.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Session options").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() &&
                (!audioAvailable || compose.onAllNodesWithContentDescription("Audio position").fetchSemanticsNodes().isNotEmpty()) &&
                (passage == null || compose.onAllNodesWithText(passage).fetchSemanticsNodes().isNotEmpty())
        }
    }

    private fun waitForEditor() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun reviewFilter(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
    )

    private fun languageOption(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    )

    private fun audioPositionFraction(): Float {
        val progress = compose.onNodeWithContentDescription("Audio position").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        return (progress.current - progress.range.start) / (progress.range.endInclusive - progress.range.start)
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots/$name.png")
        output.parentFile!!.mkdirs()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun recordingAndPausedStatesKeepControlsVisible() {
        val app = application()
        val original = RecordingState.live.value
        org.junit.Assume.assumeTrue(original.noteId == null)
        val id = app.store.create("ru")
        try {
            // Synthetic presentation state; microphone and STT behavior are tested separately.
            RecordingState.live.value = LiveSession(noteId = id, duration = 73_000, level = .65f,
                message = "Text appears after each spoken phrase.")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Pause recording").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Pause recording").assertIsDisplayed()
            compose.onNodeWithContentDescription("Finish recording").assertIsDisplayed()
            compose.onNodeWithText("Finish").assertIsDisplayed()
            compose.onNodeWithText("focal", ignoreCase = true).assertDoesNotExist()
            compose.onNodeWithText("Text appears after each spoken phrase.").assertDoesNotExist()
            capture("recording-layout")
            RecordingState.live.value = RecordingState.live.value.copy(paused = true, level = 0f)
            compose.onNodeWithContentDescription("Resume recording").assertIsDisplayed()
            compose.onNodeWithText("Recording paused").assertIsDisplayed()
            compose.onNodeWithText("Resume when you're ready.").assertDoesNotExist()
        } finally {
            RecordingState.live.value = original
            app.store.delete(id)
        }
    }

    @Test fun everySpeechLanguageIsAccessibleBeforeRecording() {
        val app = application()
        val initial = app.preferences.settings.value.language
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Record").fetchSemanticsNodes().isNotEmpty() }
            capture("empty")
            Preferences.languages.forEach { (id, label) ->
                val current = Preferences.languages[app.preferences.settings.value.language]
                compose.onNodeWithContentDescription("Speech language: $current").performClick()
                languageOption(label).performScrollTo().performClick()
                compose.onNodeWithContentDescription("Speech language: $label").assertIsDisplayed()
                compose.onNodeWithText("Record").assertIsDisplayed()
                assertEquals(id, app.preferences.settings.value.language)
            }
        } finally { app.preferences.language(initial) }
    }

    @Test fun sessionsHeaderAndRecordingFooterStayCompact() {
        val app = application()
        val id = readySession(app.store, "Clean session layout", listOf("A synthetic note for the library layout."))
        try {
            waitForTitle("Clean session layout")
            compose.onNodeWithText("Sessions").assertIsDisplayed()
            compose.onNodeWithText("focal", ignoreCase = true).assertDoesNotExist()
            compose.onNodeWithText("Audio and words, kept together.").assertDoesNotExist()
            compose.onNodeWithText("Speech language:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Record a note").assertDoesNotExist()
            compose.onNodeWithText("All notes ·", substring = true).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Search sessions").assertIsDisplayed().performClick()
            compose.onNode(hasSetTextAction()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close search").performClick()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            val language = Preferences.languages[app.preferences.settings.value.language]
            compose.onNodeWithContentDescription("Speech language: $language").assertIsDisplayed()
            compose.onNodeWithText("Record").assertIsDisplayed()
            capture("sessions-clean")
        } finally { app.store.delete(id) }
    }

    @Test fun searchFindsMiddlePassagesInEnglishAndRussianAndSurvivesRotation() {
        val app = application()
        val store = app.store
        val ids = mutableListOf<String>()
        try {
            ids += readySession(store, "Transcript search fixture", listOf(
                "This opening passage is the preview, with no search markers.",
                "The middle passage mentions a durable marker for the draft.",
                "В середине записи есть полезная мысль о восстановлении.",
                "The closing passage has no search markers either.",
            ), language = "ru")
            ids += readySession(store, "Other session", listOf("An unrelated recorded idea."))
            waitForTitle("Other session")
            compose.onNodeWithContentDescription("Search sessions").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("durable marker")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Transcript search fixture").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Other session").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("Clear search").performClick()
            waitForTitle("Other session")
            // The match is outside both the title and first-passage preview; uppercase exercises Unicode matching.
            compose.onNode(hasSetTextAction()).performTextReplacement("ВОССТАНОВЛЕНИИ")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Other session").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Transcript search fixture").assertExists()
            compose.activityRule.scenario.recreate()
            compose.onNode(hasSetTextAction()).assertTextContains("ВОССТАНОВЛЕНИИ")
            waitForTitle("Transcript search fixture")
            compose.onNodeWithText("Other session").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).performTextReplacement("no-such-session-7492")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No matching sessions.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Close search").performClick()
            waitForTitle("Other session")
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
        } finally { ids.forEach(store::delete) }
    }

    @Test fun libraryReviewFilterOpensTheRightNote() {
        val app = application()
        val store = app.store
        val ids = mutableListOf<String>()
        val initialTheme = app.preferences.settings.value.theme
        try {
            ids += readySession(store, "Ideas for the weekend", listOf("A walk by the river, a book and a slow Sunday morning."))
            ids += readySession(store, "A thought worth keeping", listOf("Make more space for the projects that matter."), uncertain = setOf(0))
            waitForTitle("A thought worth keeping")
            app.preferences.theme("light"); capture("library-light")
            app.preferences.theme("dark"); capture("library-dark")
            app.preferences.theme("light")
            val count = store.notes().count { it.reviewCount > 0 || it.state in listOf("ERROR", "INTERRUPTED") }
            reviewFilter("$count to review").performClick()
            compose.onNodeWithText("Ideas for the weekend").assertDoesNotExist()
            reviewFilter("Show all").assertExists()
            compose.onNodeWithText("A thought worth keeping").performClick()
            waitForSessionReady("A thought worth keeping", "Make more space for the projects that matter.")
            compose.onNodeWithContentDescription("Back to sessions").assertIsDisplayed()
            compose.onNodeWithContentDescription("Session options").assertIsDisplayed()
            compose.onNodeWithContentDescription("Play original audio").assertIsDisplayed()
            compose.onNodeWithText("Make more space for the projects that matter.").assertExists()
            compose.onNodeWithText("Marked passages may need a closer listen.").assertDoesNotExist()
            capture("note-light")
            app.preferences.theme("dark"); capture("note-dark")
        } finally { ids.forEach(store::delete); app.preferences.theme(initialTheme) }
    }

    @Test fun timedPassageStartsOriginalAudioAndDockRemainsVisibleWhileScrolling() {
        val app = application()
        val parts = List(12) { index ->
            "Passage ${index + 1}. Keep the first version focused on capture, review and sharing. Keep the original recording with its text."
        }
        // Silent PCM makes this a real player/UI wiring check without a microphone or speech model.
        val id = readySession(app.store, "Timed playback session", parts, duration = 60_000)
        try {
            waitForTitle("Timed playback session")
            compose.onNodeWithText("Timed playback session").performClick()
            waitForSessionReady("Timed playback session")
            compose.onNodeWithContentDescription("Play original audio").assertIsDisplayed()
            compose.onNodeWithContentDescription("Audio position").assertIsDisplayed()
            val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            val timestamp = "Listen from ${Transcript.time(15_000)}"
            list.performScrollToNode(hasContentDescription(timestamp))
            compose.onNodeWithContentDescription(timestamp).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Pause original audio").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(10_000) { audioPositionFraction() >= .24f }
            compose.onNodeWithContentDescription("Pause original audio").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Play original audio").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Play original audio").assertIsDisplayed()
            list.performScrollToNode(hasText(parts.last()))
            compose.onNodeWithText(parts.last()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Play original audio").assertIsDisplayed()
            compose.onNodeWithContentDescription("Audio position").assertIsDisplayed()
            compose.onNodeWithText("Copy text").assertIsDisplayed()
            compose.onNodeWithText("Tap a passage to listen or edit.").assertDoesNotExist()
            capture("session-audio-dock")
            compose.onNodeWithContentDescription("Back to sessions").performClick()
        } finally { app.store.delete(id) }
    }

    @Test fun languageAndAppearanceCanBeChangedWithoutLeavingTheApp() {
        val app = application()
        val initial = app.preferences.settings.value
        try {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Speech language").performClick()
            languageOption("Русский").performScrollTo().performClick()
            compose.onNodeWithText("Dark").performClick()
            compose.onNodeWithText("Light").performClick()
            compose.onNodeWithContentDescription("Back to sessions").performClick()
            compose.onNodeWithContentDescription("Speech language: Русский").assertIsDisplayed()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Speech language").performClick()
            languageOption("English").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back to sessions").performClick()
            compose.onNodeWithContentDescription("Speech language: English").assertIsDisplayed()
        } finally {
            app.preferences.language(initial.language)
            app.preferences.theme(initial.theme)
        }
    }

    @Test fun activeSessionDraftSurvivesRotationAndSameSessionNotification() {
        val app = application()
        val originalLive = RecordingState.live.value
        org.junit.Assume.assumeTrue(originalLive.noteId == null)
        val originalText = "The original live phrase stays unchanged until saved."
        val draft = "An unsaved live draft must survive returning to this recording."
        val id = readySession(app.store, "Active draft regression", listOf(originalText))
        try {
            app.store.state(id, "PAUSED")
            // A synthetic active session isolates navigation/draft recovery from microphone and decoder behavior.
            RecordingState.live.value = LiveSession(noteId = id, duration = 1_000, paused = true)
            waitForSessionReady("Active draft regression", originalText, audioAvailable = false)
            compose.onNode(hasText(originalText) and hasClickAction()).performScrollTo().performClick()
            waitForEditor()
            compose.onNodeWithText("Audio unavailable during recording.").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextReplacement(draft)
            compose.activityRule.scenario.recreate()
            waitForEditor()
            compose.onNode(hasSetTextAction()).assertTextContains(draft)

            val token = System.nanoTime().toString()
            val resumedActivity = compose.activity
            compose.activityRule.scenario.onActivity { activity ->
                // ActivityScenario matches lifecycle callbacks by launch Intent identity. Preserve it when
                // onNewIntent replaces Activity.intent, but clear task-launch flags before the real delivery.
                activity.startActivity(Intent(activity.intent).setClass(activity, MainActivity::class.java)
                    .putExtra(RecordingService.NOTE, id).putExtra("interface-test-token", token)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            compose.waitUntil(10_000) { compose.activity.intent.getStringExtra("interface-test-token") == token }
            compose.waitForIdle()
            assertSame(resumedActivity, compose.activity)
            compose.onNode(hasSetTextAction()).assertTextContains(draft)
            compose.onNodeWithText("Leave this session?").assertDoesNotExist()

            compose.onNodeWithContentDescription("Close editor").performScrollTo().performClick()
            compose.onNodeWithText("Discard your changes?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains(draft)
            compose.onNodeWithContentDescription("Close editor").performScrollTo().performClick()
            compose.onNodeWithText("Discard", substring = false).performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            assertEquals(originalText, app.store.passages(id).single().text)
            compose.onNodeWithContentDescription("Resume recording").assertIsDisplayed()
        } finally {
            RecordingState.live.value = originalLive
            app.store.delete(id)
        }
    }

    @Test fun largeFontInSmallWindowKeepsCaptureAndSessionControlsReachable() {
        val app = application()
        val originalLive = RecordingState.live.value
        org.junit.Assume.assumeTrue(originalLive.noteId == null)
        val id = readySession(app.store, "Accessible session", listOf("Readable text with its original audio."))
        val model = ViewModelProvider(compose.activity)[FocalViewModel::class.java]
        val originalReady = model.modelReady.value
        val originalPreparing = model.preparing.value
        try {
            // Exercise Compose's actual responsive branches without changing the device's global font settings.
            val density = compose.activity.resources.displayMetrics.density
            val opened = MutableStateFlow<Pair<String?, Long>>(null to 0)
            compose.runOnUiThread {
                compose.activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                        Box(Modifier.requiredSize(320.dp, 560.dp)) { FocalApp(model, opened) }
                    }
                }
            }
            // Missing/preparing transcription must not block the independent audio capture affordance.
            model.modelReady.value = false
            model.preparing.value = true
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Record") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            val language = Preferences.languages[app.preferences.settings.value.language]
            compose.onNodeWithContentDescription("Speech language: $language").assertIsDisplayed()
            compose.onNodeWithText("Record").assertIsDisplayed().assertIsEnabled()
            waitForTitle("Accessible session")
            compose.onNodeWithText("Accessible session").performClick()
            waitForSessionReady("Accessible session")
            compose.onNodeWithContentDescription("Play original audio").assertIsDisplayed()
            compose.onNodeWithContentDescription("Audio position").assertIsDisplayed()
            compose.onNodeWithText("Copy text").assertIsDisplayed().assertIsEnabled()
            RecordingState.live.value = LiveSession(noteId = id, duration = 1_000)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Pause recording").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Pause recording").assertIsDisplayed()
            compose.onNodeWithContentDescription("Finish recording").assertIsDisplayed()
            RecordingState.live.value = RecordingState.live.value.copy(paused = true)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Resume recording").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Resume recording").assertIsDisplayed()
            compose.onNodeWithContentDescription("Finish recording").assertIsDisplayed()
            capture("small-window-large-font")
        } finally {
            model.modelReady.value = originalReady
            model.preparing.value = originalPreparing
            RecordingState.live.value = originalLive
            app.store.delete(id)
        }
    }

    @Test fun reviewAndCopyPreservesRussianEdits() {
        val app = application()
        val store = app.store
        val id = readySession(store, "Sample note", listOf("Это тестовая запись."), language = "ru", uncertain = setOf(0))
        try {
            waitForTitle("Sample note")
            compose.onNodeWithText("Sample note").performClick()
            waitForSessionReady("Sample note", "Это тестовая запись.")
            reviewFilter("1 to review").performClick()
            compose.onNode(hasText("Это тестовая запись.") and hasClickAction()).performScrollTo().performClick()
            waitForEditor()
            capture("review")
            compose.onNode(hasSetTextAction()).performTextReplacement("Это исправленная запись.")
            compose.activityRule.scenario.recreate()
            waitForEditor()
            compose.onNode(hasSetTextAction()).assertTextContains("Это исправленная запись.")
            // A real storage write failure must preserve the draft and the saved original text.
            store.writableDatabase.execSQL("PRAGMA query_only=ON")
            try {
                compose.onNodeWithText("Save changes").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Couldn't save. Your changes are still here. Try again.").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasSetTextAction()).assertTextContains("Это исправленная запись.")
                assertEquals("Это тестовая запись.", store.passages(id).single().text)
            } finally { store.writableDatabase.execSQL("PRAGMA query_only=OFF") }
            compose.onNodeWithText("Save changes").performScrollTo().performClick()
            compose.waitUntil(10_000) { store.passages(id).single().reviewed }
            reviewFilter("Show all").performClick()
            waitForSessionReady("Sample note", "Это исправленная запись.")
            compose.onNodeWithText("Это исправленная запись.").assertIsDisplayed()
            compose.onNodeWithText("Copy text").performClick()
            compose.runOnIdle {
                assertEquals("Это исправленная запись.", app.getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
            }
        } finally { store.delete(id) }
    }
}
