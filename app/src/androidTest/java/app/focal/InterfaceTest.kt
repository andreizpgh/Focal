package app.focal

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.focal.core.Transcript
import app.focal.data.Chunk
import app.focal.data.NoteStore
import app.focal.data.Passage
import app.focal.data.Preferences
import app.focal.recording.LiveSession
import app.focal.recording.RecordingDecoder
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import app.focal.ui.FocalApp
import app.focal.ui.fitsTextTransferBudget
import app.focal.ui.LARGE_PASSAGE_PART_CHARS
import app.focal.ui.passageTextPart
import app.focal.ui.passageTextPartCount
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: ComposeTimeoutException) {
            runCatching { capture("title-wait-timeout-${System.currentTimeMillis()}") }
            runCatching {
                val model = compose.runOnUiThread { ViewModelProvider(compose.activity)[FocalViewModel::class.java] }
                val state = model.library.value
                val nodes = compose.onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
                ).fetchSemanticsNodes()
                val scroll = compose.runOnUiThread {
                    nodes.map {
                        val range = it.config[SemanticsProperties.VerticalScrollAxisRange]
                        range.value() to range.maxValue()
                    }
                }
                val unmergedMatches = compose.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().size
                failure.addSuppressed(AssertionError(
                    "Title diagnostics: libraryLoaded=${state.loaded}, libraryError=${state.error != null}, " +
                        "notes=${state.notes.size}, publishedMatches=${state.notes.count { it.title == title }}, " +
                        "detailSelected=${state.selectedId != null}, liveActive=${model.live.value.noteId != null}, " +
                        "unmergedMatches=$unmergedMatches, scroll=$scroll"
                ))
            }
            throw failure
        }
    }

    private fun waitForSessionReady(title: String, passage: String? = null, audioAvailable: Boolean = true) {
        // Selecting a session loads its audio and passages on IO; Compose idle alone does not await that load.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Recording options").fetchSemanticsNodes().isNotEmpty() &&
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
            compose.onNodeWithContentDescription("Microphone level").assertRangeInfoEquals(ProgressBarRangeInfo(.65f, 0f..1f))
            capture("recording-layout")
            RecordingState.live.value = RecordingState.live.value.copy(paused = true, level = 0f)
            compose.onNodeWithContentDescription("Resume recording").assertIsDisplayed()
            compose.onNodeWithText("Recording paused").assertIsDisplayed()
            compose.onNodeWithText("Resume when you're ready.").assertDoesNotExist()
            compose.onNodeWithContentDescription("Microphone level").assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
            capture("recording-paused")
        } finally {
            RecordingState.live.value = original
            app.store.delete(id)
        }
    }

    @Test fun transcriptionProgressStaysVisibleWithLongTextAndStopsWhenFinished() {
        val app = application()
        val original = RecordingState.live.value
        org.junit.Assume.assumeTrue(original.noteId == null)
        val id = readySession(app.store, "Processing fixture", List(12) { index ->
            "Synthetic passage ${index + 1}. The original audio and the written words remain available while transcription continues."
        })
        val progress = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        try {
            RecordingState.live.value = LiveSession(noteId = id, processing = true, message = "Transcribing")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Transcribing").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(progress).assertIsDisplayed()
            compose.onNodeWithContentDescription("Pause recording").assertDoesNotExist()
            compose.onNodeWithContentDescription("Microphone level").assertDoesNotExist()
            val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            list.performScrollToNode(hasText("Synthetic passage 12.", substring = true))
            compose.onNode(progress).assertIsDisplayed()
            compose.onNodeWithText("Transcribing").assertIsDisplayed()
            capture("processing")
            RecordingState.live.value = LiveSession()
            compose.waitUntil(10_000) { compose.onAllNodes(progress).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Copy text").assertIsDisplayed()
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
            compose.onNodeWithContentDescription("Browse recording dates").assertIsDisplayed()
            compose.onNodeWithText("focal", ignoreCase = true).assertDoesNotExist()
            compose.onNodeWithText("Audio and words, kept together.").assertDoesNotExist()
            compose.onNodeWithText("Speech language:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Record a note").assertDoesNotExist()
            compose.onNodeWithText("All notes ·", substring = true).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Search recordings").assertIsDisplayed().performClick()
            compose.onNode(hasSetTextAction()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close search").performClick()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            val language = Preferences.languages[app.preferences.settings.value.language]
            compose.onNodeWithContentDescription("Speech language: $language").assertIsDisplayed()
            compose.onNodeWithText("Record").assertIsDisplayed()
            capture("sessions-clean")
        } finally { app.store.delete(id) }
    }

    @Test fun prependingASessionShowsItAtTheTopAndPreservesAReaderFurtherDown() {
        val store = application().store
        val model = compose.runOnUiThread { ViewModelProvider(compose.activity)[FocalViewModel::class.java] }
        val query = "Library prepend ${UUID.randomUUID().toString().take(8)}"
        val ids = mutableListOf<String>()
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterdayStart = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()

        fun addSession(index: Int, timestamp: Long): String {
            val db = store.writableDatabase
            db.beginTransactionNonExclusive()
            val id = try {
                val created = readySession(store, "$query $index", listOf("Synthetic library row.")).also(ids::add)
                // Publish each fixture at its assigned day and unique sort position in one transaction.
                db.execSQL("UPDATE notes SET created=? WHERE id=?", arrayOf(timestamp, created))
                db.setTransactionSuccessful()
                created
            } finally { db.endTransaction() }
            // Publish again after the entire fixture row is visible to the library's IO reader.
            store.state(id, "READY")
            return id
        }

        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction() and !hasSetTextAction())
        fun rowOffset(title: String): Float {
            val listNode = list.fetchSemanticsNode()
            val rowNode = row(title).fetchSemanticsNode()
            return compose.runOnUiThread { rowNode.boundsInRoot.top - listNode.boundsInRoot.top }
        }

        try {
            repeat(20) { addSession(it, yesterdayStart + it) }
            compose.waitUntil(10_000) { model.library.value.notes.any { it.id == ids.last() } }
            compose.onNodeWithContentDescription("Search recordings").performClick()
            val search = compose.onNode(hasSetTextAction())
            search.performTextReplacement(query)
            search.performImeAction()
            compose.waitUntil(10_000) { model.library.value.notes.map { it.id } == ids.asReversed() }
            compose.onNodeWithText("$query 19", useUnmergedTree = true).assertIsDisplayed()
            val listNode = list.fetchSemanticsNode()
            assertEquals("The library should start at the top without a scroll request", 0f,
                compose.runOnUiThread { listNode.config[SemanticsProperties.VerticalScrollAxisRange].value() }, .001f)
            compose.waitForIdle()

            addSession(20, todayStart)
            waitForTitle("$query 20")
            compose.onNodeWithText("$query 20", useUnmergedTree = true).assertIsDisplayed()

            val reader = "$query 8"
            list.performScrollToNode(hasText(reader))
            row(reader).assertIsDisplayed()
            row("$query 20").assertIsNotDisplayed()
            val offset = rowOffset(reader)
            val newest = addSession(21, todayStart + 1)
            compose.waitUntil(10_000) { model.library.value.notes.firstOrNull()?.id == newest }
            compose.waitForIdle()
            row(reader).assertIsDisplayed()
            row("$query 21").assertIsNotDisplayed()
            assertEquals("Prepending moved the reader's row within the library viewport", offset, rowOffset(reader), 1f)
        } catch (failure: Throwable) {
            if (failure !is ComposeTimeoutException && failure !is AssertionError) throw failure
            runCatching { capture("library-prepend-timeout") }
            val visibleFixtures = runCatching {
                compose.onRoot(useUnmergedTree = true).printToString().lineSequence()
                    .filter { query in it }.take(20).joinToString("\n")
            }.getOrDefault("")
            val state = model.library.value
            val published = state.notes.map { ids.indexOf(it.id) }
            val stored = runCatching { store.notes(query).map { ids.indexOf(it.id) } }.getOrNull()
            // Fixture ranks preserve ordering without exposing unrelated titles or IDs. -1 is an outside row.
            failure.addSuppressed(AssertionError(
                "Fixture rows=${ids.size}, loaded=${state.loaded}, error=${state.error != null}, " +
                    "publishedTotal=${published.size}, publishedRanks=${published.take(24)}, " +
                    "storedTotal=${stored?.size}, storedRanks=${stored?.take(24)}\n$visibleFixtures"
            ))
            throw failure
        } finally { ids.asReversed().forEach(store::delete) }
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
            compose.onNodeWithContentDescription("Search recordings").performClick()
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
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No matching recordings.").fetchSemanticsNodes().isNotEmpty() }
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
            compose.onNodeWithContentDescription("Back to recordings").assertIsDisplayed()
            compose.onNodeWithContentDescription("Recording options").assertIsDisplayed()
            compose.onNodeWithContentDescription("Play original audio").assertIsDisplayed()
            compose.onNodeWithText("Make more space for the projects that matter.").assertExists()
            compose.onNodeWithText("Marked passages may need a closer listen.").assertDoesNotExist()
            capture("note-light")
            app.preferences.theme("dark"); capture("note-dark")
        } finally { ids.forEach(store::delete); app.preferences.theme(initialTheme) }
    }

    @Test fun libraryReviewFilterAndScrollSurviveOpeningSessionAndRotation() {
        val app = application()
        val ids = mutableListOf<String>()
        try {
            repeat(16) { index ->
                ids += readySession(app.store, "Review context $index", listOf("Review fixture passage $index."), uncertain = setOf(0))
            }
            val filtered = app.store.notes().filter { it.id in ids }
            val target = filtered[filtered.size / 2]
            waitForTitle(filtered.first().title)
            val count = app.store.notes().count { it.reviewCount > 0 || it.state in listOf("ERROR", "INTERRUPTED") }
            reviewFilter("$count to review").performClick()
            val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            list.performScrollToNode(hasText(target.title))
            fun browsingPosition(): Pair<Float, Float> {
                val listNode = list.fetchSemanticsNode()
                val rowNode = compose.onNodeWithText(target.title).fetchSemanticsNode()
                // Read both coordinates in one frame, including while window insets settle.
                return compose.runOnUiThread {
                    listNode.config[SemanticsProperties.VerticalScrollAxisRange].value() to
                        (rowNode.boundsInRoot.top - listNode.boundsInRoot.top)
                }
            }
            val (scrollPosition, rowOffset) = browsingPosition()
            compose.onNodeWithText(target.title).performClick()
            waitForSessionReady(target.title, "Review fixture passage ${ids.indexOf(target.id)}.")
            // The library is absent during recreation; its browsing state must still be saved.
            compose.activityRule.scenario.recreate()
            waitForSessionReady(target.title)
            compose.onNodeWithContentDescription("Back to recordings").performClick()
            waitForTitle(target.title)
            compose.onNodeWithText(target.title).assertIsDisplayed()
            val (restoredPosition, restoredOffset) = browsingPosition()
            assertEquals("Library logical scroll position changed after navigation and recreation", scrollPosition,
                restoredPosition, .001f)
            assertEquals("Session moved within the library viewport after navigation and recreation", rowOffset,
                restoredOffset, 1f)
            reviewFilter("Show all").assertIsDisplayed().assertIsSelected()
        } finally { ids.forEach(app.store::delete) }
    }

    @Test fun searchOpensAtTopAndDoesNotRefocusAfterReturningFromSession() {
        val app = application()
        val ids = mutableListOf<String>()
        try {
            repeat(16) { index ->
                ids += readySession(app.store, "Search context [$index]", listOf("Search fixture passage $index."))
            }
            val sessions = app.store.notes().filter { it.id in ids }
            waitForTitle(sessions.first().title)
            val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            list.performScrollToNode(hasText(sessions.last().title))
            compose.onNodeWithContentDescription("Search recordings").performClick()
            compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
            val target = sessions.first()
            compose.onNode(hasSetTextAction()).performTextReplacement(target.title)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            waitForTitle(target.title)
            compose.onNode(hasText(target.title) and hasClickAction() and !hasSetTextAction()).performClick()
            waitForSessionReady(target.title)
            compose.onNodeWithContentDescription("Back to recordings").performClick()
            compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsNotFocused().assertTextContains(target.title)
        } finally { ids.forEach(app.store::delete) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun searchCaretSurvivesOpeningAResultAndReturning() {
        val app = application()
        val query = "Range fixture"
        val title = "$query target"
        val passage = "Search selection survives opening this result."
        val selection = androidx.compose.ui.text.TextRange(6)
        val id = readySession(app.store, title, listOf(passage))
        fun assertQueryAndSelection() {
            // Verify the saved query's characters and caret independently of text decoration.
            compose.onNode(hasSetTextAction()).assert(
                SemanticsMatcher("Search query matches exactly") {
                    it.config[SemanticsProperties.EditableText].text == query
                }
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
        }
        try {
            waitForTitle(title)
            compose.onNodeWithContentDescription("Search recordings").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput(query)
            compose.onNode(hasSetTextAction()).performTextInputSelection(selection)
            assertQueryAndSelection()
            compose.onNode(hasText(title) and hasClickAction() and !hasSetTextAction()).assertIsDisplayed().performClick()
            waitForSessionReady(title, passage)
            compose.onNodeWithContentDescription("Back to recordings").performClick()
            waitForEditor()
            val fieldView = checkNotNull(compose.onNode(hasSetTextAction()).fetchSemanticsNode().root as? android.view.View)
            compose.waitUntil(5_000) {
                compose.runOnUiThread {
                    ViewCompat.getRootWindowInsets(fieldView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
            }
            // Preserve the text state without reactivating input after returning to Sessions.
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            assertQueryAndSelection()
        } finally { app.store.delete(id) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun activeSearchKeepsSelectionAndKeyboardThroughRecreation() {
        val app = application()
        val prefix = "Search rotation"
        val suffix = " target"
        val id = readySession(app.store, prefix + suffix, listOf("The search cursor stays where typing stopped."))
        fun waitForKeyboard(visible: Boolean) {
            val fieldView = checkNotNull(compose.onNode(hasSetTextAction()).fetchSemanticsNode().root as? android.view.View)
            compose.waitUntil(5_000) {
                compose.runOnUiThread {
                    ViewCompat.getRootWindowInsets(fieldView)?.isVisible(WindowInsetsCompat.Type.ime()) == visible
                }
            }
        }
        fun assertSelectionAtEnd(text: String) {
            compose.onNode(hasSetTextAction()).assert(
                SemanticsMatcher("Editable text equals the expected draft") {
                    it.config.contains(SemanticsProperties.EditableText) &&
                        it.config[SemanticsProperties.EditableText].text == text
                }
            ).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(text.length))
            )
        }
        fun returnFromHome(keyboardVisible: Boolean, text: String) {
            val retainedActivity = compose.activity
            // Real task focus loss can restore the IME even when a lifecycle-only round trip does not.
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
                .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            compose.waitUntil(5_000) {
                retainedActivity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                retainedActivity.startActivity(Intent(retainedActivity.intent).setClass(retainedActivity, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            compose.waitUntil(5_000) {
                retainedActivity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED && retainedActivity.hasWindowFocus()
            }
            assertSame(retainedActivity, compose.activity)
            waitForEditor()
            waitForKeyboard(keyboardVisible)
            if (keyboardVisible) compose.onNode(hasSetTextAction()).assertIsFocused()
            else compose.onNode(hasSetTextAction()).assertIsNotFocused()
            assertSelectionAtEnd(text)
        }
        try {
            waitForTitle(prefix + suffix)
            compose.onNodeWithContentDescription("Search recordings").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput(prefix)
            compose.onNode(hasSetTextAction()).performTextInputSelection(androidx.compose.ui.text.TextRange(prefix.length))
            assertSelectionAtEnd(prefix)
            waitForKeyboard(true)
            returnFromHome(keyboardVisible = true, prefix)

            // Keep MainActivity's real composition and input connection across recreation.
            compose.activityRule.scenario.recreate()
            waitForEditor()
            assertSelectionAtEnd(prefix)
            waitForKeyboard(true)
            compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput(suffix)
            assertSelectionAtEnd(prefix + suffix)

            compose.onNode(hasSetTextAction()).performImeAction()
            waitForKeyboard(false)
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.activityRule.scenario.recreate()
            waitForEditor()
            waitForKeyboard(false)
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            assertSelectionAtEnd(prefix + suffix)
            compose.onNode(hasText(prefix + suffix) and hasClickAction() and !hasSetTextAction()).performClick()
            waitForSessionReady(prefix + suffix)
            compose.onNodeWithContentDescription("Back to recordings").performClick()
            waitForEditor()
            waitForKeyboard(false)
            compose.onNode(hasSetTextAction()).assertIsNotFocused().assertTextEquals(prefix + suffix)

            compose.onNode(hasSetTextAction()).performClick()
            waitForKeyboard(true)
            compose.onNode(hasSetTextAction()).performTextInputSelection(androidx.compose.ui.text.TextRange((prefix + suffix).length))
            assertSelectionAtEnd(prefix + suffix)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitForKeyboard(false)
            assertSelectionAtEnd(prefix + suffix)
            returnFromHome(keyboardVisible = false, prefix + suffix)
            val retainedActivity = compose.activity
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertSame(retainedActivity, compose.activity)
            waitForEditor()
            waitForKeyboard(false)
            assertSelectionAtEnd(prefix + suffix)
            compose.activityRule.scenario.recreate()
            waitForEditor()
            waitForKeyboard(false)
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            assertSelectionAtEnd(prefix + suffix)

            compose.onNode(hasSetTextAction()).performClick()
            waitForKeyboard(true)
            val bounded = "a".repeat(199)
            // The 200th UTF-16 unit would split this emoji; retain only the valid prefix.
            compose.onNode(hasSetTextAction()).performTextReplacement(bounded + "🙂".repeat(40_000))
            assertSelectionAtEnd(bounded)
            compose.activityRule.scenario.recreate()
            waitForEditor()
            waitForKeyboard(true)
            compose.onNode(hasSetTextAction()).assertIsFocused()
            assertSelectionAtEnd(bounded)
            compose.onNodeWithContentDescription("Clear search").performClick()
            assertSelectionAtEnd("")
            compose.onNode(hasSetTextAction()).performImeAction()
            waitForKeyboard(false)
        } finally { app.store.delete(id) }
    }

    @Test fun largeFontLandscapeSearchKeepsTheWholeFieldAboveTheKeyboard() {
        val app = application()
        val originalOrientation = compose.activity.requestedOrientation
        val id = readySession(app.store, "Accessible search target", listOf("Search remains readable while typing."), uncertain = setOf(0))
        var lastGeometry = "The search field has not been measured."
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) {
                compose.runOnUiThread {
                    compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        compose.activity.window.decorView.width > compose.activity.window.decorView.height
                }
            }
            val model = ViewModelProvider(compose.activity)[FocalViewModel::class.java]
            val density = compose.activity.resources.displayMetrics.density
            val opened = MutableStateFlow<Pair<String?, Long>>(null to 0)
            compose.runOnUiThread {
                compose.activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                        FocalApp(model, opened)
                    }
                }
            }
            compose.waitUntil(10_000) {
                model.library.value.loaded && model.library.value.notes.any { it.id == id }
            }
            compose.onNodeWithContentDescription("Search recordings").performClick()
            val field = compose.onNode(hasSetTextAction())
            fun wholeFieldAboveKeyboard(): Boolean {
                val node = field.fetchSemanticsNode()
                val full = field.getUnclippedBoundsInRoot()
                val view = checkNotNull(node.root as? android.view.View)
                return compose.runOnUiThread {
                    val insets = ViewCompat.getRootWindowInsets(view)
                    val location = IntArray(2).also(view::getLocationInWindow)
                    val top = location[1] + full.top.value * density
                    val bottom = location[1] + full.bottom.value * density
                    val height = (full.bottom.value - full.top.value) * density
                    val imeTop = view.rootView.height - (insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
                    val statusBottom = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                    lastGeometry = "field=$top..$bottom, fullHeight=$height, visibleHeight=${node.boundsInRoot.height}, IME top=$imeTop"
                    insets?.isVisible(WindowInsetsCompat.Type.ime()) == true && height > 0 &&
                        node.boundsInRoot.height + 1 >= height && top >= statusBottom - 1 && bottom <= imeTop + 1
                }
            }
            try {
                compose.waitUntil(5_000) { wholeFieldAboveKeyboard() }
            } catch (error: ComposeTimeoutException) {
                throw AssertionError("The whole search field must fit above the real keyboard: $lastGeometry", error)
            }
            field.assertIsFocused().performTextInput("Accessible search")
            field.assertTextContains("Accessible search")
            assertTrue(lastGeometry, wholeFieldAboveKeyboard())
            field.performImeAction()
            compose.waitUntil(5_000) {
                compose.runOnUiThread {
                    ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
            }
            field.assertIsNotFocused().assertTextContains("Accessible search")
            compose.onNodeWithContentDescription("Browse recording dates").assertIsDisplayed()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                .performScrollToNode(hasText("Accessible search target"))
            compose.onNodeWithText("Accessible search target").assertIsDisplayed()
        } finally {
            app.store.delete(id)
            compose.runOnUiThread { compose.activity.requestedOrientation = originalOrientation }
        }
    }

    @Test fun continueTranscriptionFindsAnUnpublishedTailAndPreservesManualEdits() {
        val app = application()
        val title = "Interrupted session with an unpublished tail"
        val correction = "Keep this manual correction."
        val recoveredText = "Recovered the saved tail."
        val decoded = CopyOnWriteArrayList<Chunk>()
        val microphoneOpens = AtomicInteger()
        val previousInputFactory = RecordingService.inputFactoryForTests
        val previousDecoderFactory = RecordingService.decoderFactoryForTests
        val id = readySession(app.store, title, listOf("Original text"), uncertain = setOf(0))
        try {
            assertTrue("The bundled model must be ready to offer direct recovery", app.models.ready())
            val original = ByteArray(2000 * 32) { (it % 101).toByte() }
            app.store.audioFile(id).writeBytes(original)
            app.store.edit(app.store.passages(id).single(), correction)
            val saved = app.store.passages(id).single()
            app.store.state(id, "INTERRUPTED", "Saved audio can still be transcribed.")
            assertEquals(0, app.store.pendingCount(id))

            RecordingService.inputFactoryForTests = {
                microphoneOpens.incrementAndGet()
                error("Recovery must not open the microphone")
            }
            RecordingService.decoderFactoryForTests = {
                object : RecordingDecoder {
                    override suspend fun transcribe(store: NoteStore, chunk: Chunk, language: String, threads: Int) {
                        decoded += chunk
                        store.complete(chunk, listOf(Passage(noteId = chunk.noteId, chunkId = chunk.id,
                            start = chunk.start, end = chunk.end, text = recoveredText, uncertain = false)))
                    }
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }

            waitForTitle(title)
            compose.onNodeWithText(title).performClick()
            waitForSessionReady(title)
            compose.onNodeWithText("Transcription interrupted").assertIsDisplayed()
            capture("interrupted-unpublished-tail")
            compose.onNodeWithText("Continue transcription").assertExists()
            compose.onNodeWithContentDescription("Recording options").performClick()
            compose.onNodeWithText("Remove audio").assertIsNotEnabled()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText("Continue transcription").performScrollTo().performClick()

            compose.waitUntil(10_000) {
                app.store.note(id)?.state == "READY" && RecordingState.live.value.noteId != id &&
                    !SessionOperations.matchesSession(capture = false, noteId = id)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(recoveredText).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Continue transcription").assertDoesNotExist()
            compose.onNodeWithContentDescription("Recording options").performClick()
            compose.onNodeWithText("Remove audio").assertIsEnabled()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertEquals(listOf(1000L to 2000L), decoded.map { it.start to it.end })
            assertEquals(0, microphoneOpens.get())
            assertEquals(0, app.store.pendingCount(id))
            assertEquals(saved, app.store.passages(id).first())
            assertArrayEquals(original, app.store.audioFile(id).readBytes())
        } finally {
            try {
                if (SessionOperations.matchesSession(capture = false, noteId = id)) {
                    app.stopService(Intent(app, RecordingService::class.java))
                    compose.waitUntil(10_000) { !SessionOperations.matchesSession(capture = false, noteId = id) }
                }
            } finally {
                RecordingService.inputFactoryForTests = previousInputFactory
                RecordingService.decoderFactoryForTests = previousDecoderFactory
                app.store.delete(id)
            }
        }
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
            // The real player keeps updating its position while playing. Advance Compose
            // explicitly instead of asking Espresso to wait for a continuously changing UI.
            val player = compose.runOnUiThread { ViewModelProvider(compose.activity)[FocalViewModel::class.java].player }
            val autoAdvance = compose.mainClock.autoAdvance
            try {
                compose.mainClock.autoAdvance = false
                compose.onNodeWithContentDescription(timestamp).performClick()
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithContentDescription("Pause original audio").fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeByFrame()
                    audioPositionFraction() >= .24f
                }
                compose.onNodeWithContentDescription("Pause original audio").performClick()
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithContentDescription("Play original audio").fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                // Restore idle even if an assertion failed before tapping Pause.
                player.pause()
                compose.mainClock.autoAdvance = autoAdvance
            }
            compose.onNodeWithContentDescription("Play original audio").assertIsDisplayed()
            list.performScrollToNode(hasText(parts.last()))
            compose.onNodeWithText(parts.last()).assertIsDisplayed()
            compose.onNodeWithContentDescription("Play original audio").assertIsDisplayed()
            compose.onNodeWithContentDescription("Audio position").assertIsDisplayed()
            compose.onNodeWithText("Copy text").assertIsDisplayed()
            compose.onNodeWithText("Tap a passage to listen or edit.").assertDoesNotExist()
            capture("session-audio-dock")
            compose.onNodeWithContentDescription("Back to recordings").performClick()
        } finally { app.store.delete(id) }
    }

    @Test fun languageAndAppearanceCanBeChangedWithoutLeavingTheApp() {
        val app = application()
        val initial = app.preferences.settings.value
        try {
            val currentLanguage = Preferences.languages[initial.language]
            compose.onNodeWithContentDescription("Speech language: $currentLanguage").performClick()
            languageOption("Русский").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Speech language").assertDoesNotExist()
            compose.onNodeWithText("Dark").performClick()
            compose.onNodeWithText("Light").performClick()
            capture("settings-brand")
            compose.onNodeWithContentDescription("Back to recordings").performClick()
            compose.onNodeWithContentDescription("Speech language: Русский").assertIsDisplayed().performClick()
            languageOption("English").performScrollTo().performClick()
            compose.activityRule.scenario.recreate()
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
            // The live screen intentionally omits the title; await its actual loaded content.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Recording options").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("Resume recording").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText(originalText).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Active draft regression").assertDoesNotExist()
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
            compose.onNodeWithText("Leave this recording?").assertDoesNotExist()

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

    @Test fun selectedLanguageIsVisibleWhenPickerOpensAtLargeFont() {
        val app = application()
        val originalLanguage = app.preferences.settings.value.language
        val model = ViewModelProvider(compose.activity)[FocalViewModel::class.java]
        try {
            app.preferences.language("pt")
            val density = compose.activity.resources.displayMetrics.density
            val opened = MutableStateFlow<Pair<String?, Long>>(null to 0)
            compose.runOnUiThread {
                compose.activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                        Box(Modifier.requiredSize(320.dp, 560.dp)) { FocalApp(model, opened) }
                    }
                }
            }
            compose.onNodeWithContentDescription("Speech language: Português").performClick()
            languageOption("Português").assertIsDisplayed().assertIsSelected()
        } finally {
            app.preferences.language(originalLanguage)
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
        val reviewHint = "Dots mark recognizer hints. You can check and edit any passage."
        try {
            waitForTitle("Sample note")
            compose.onNodeWithText("Sample note").performClick()
            waitForSessionReady("Sample note", "Это тестовая запись.")
            compose.onNodeWithText(reviewHint).assertDoesNotExist()
            reviewFilter("1 to review").performClick()
            compose.onNodeWithText(reviewHint).assertDoesNotExist()
            reviewFilter("Show all").assertIsSelected()
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
                compose.onNodeWithTag("editor-save").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Couldn't save. Your changes are still here. Try again.").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasSetTextAction()).assertTextContains("Это исправленная запись.")
                assertEquals("Это тестовая запись.", store.passages(id).single().text)
            } finally { store.writableDatabase.execSQL("PRAGMA query_only=OFF") }
            compose.onNodeWithTag("editor-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { store.passages(id).single().reviewed }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("No flagged passages.").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("No flagged passages.").assertIsDisplayed()
            compose.onNodeWithText("All checked.").assertDoesNotExist()
            reviewFilter("Show all").performClick()
            waitForSessionReady("Sample note", "Это исправленная запись.")
            compose.onNodeWithText(reviewHint).assertDoesNotExist()
            compose.onNodeWithText("Это исправленная запись.").assertIsDisplayed()
            compose.onNodeWithText("Copy text").performClick()
            compose.runOnIdle {
                assertEquals("Это исправленная запись.", app.getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
            }
        } finally { store.delete(id) }
    }

    @Test fun editorWindowSystemBarsFollowThemeWithoutLosingDraft() {
        // The platform insets controller identifies the actual dialog window on API 30+.
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 30)
        val app = application()
        val initialTheme = app.preferences.settings.value.theme
        val title = "Editor theme fixture"
        val original = "Keep the saved passage."
        val draft = "Keep the unsaved draft too."
        val id = readySession(app.store, title, listOf(original))
        val activityDecor = compose.activity.window.decorView
        val field = compose.onNode(hasSetTextAction())
        try {
            compose.runOnIdle { app.preferences.theme("light") }
            waitForTitle(title)
            compose.onNodeWithText(title).performClick()
            waitForSessionReady(title, original)
            compose.onNode(hasText(original) and hasClickAction()).performScrollTo().performClick()
            waitForEditor()
            field.performTextReplacement(draft)
            for (theme in listOf("dark", "light")) {
                compose.runOnIdle { app.preferences.theme(theme) }
                field.assertTextContains(draft)
                val editorView = checkNotNull(field.fetchSemanticsNode().root as? android.view.View)
                capture("editor-system-bars-$theme")
                compose.runOnIdle {
                    assertTrue("The editor must have its own window", editorView.rootView !== activityDecor)
                    val bars = checkNotNull(ViewCompat.getWindowInsetsController(editorView))
                    assertEquals("Editor status bar in $theme theme", theme == "light", bars.isAppearanceLightStatusBars)
                    assertEquals("Editor navigation bar in $theme theme", theme == "light", bars.isAppearanceLightNavigationBars)
                }
            }
            assertEquals(original, app.store.passages(id).single().text)
        } finally {
            compose.runOnUiThread { app.preferences.theme(initialTheme) }
            app.store.delete(id)
        }
    }

    @Test fun saveAndNextAdvancesReviewAndSaveAndCloseCopiesCommittedText() {
        val app = application()
        val originals = listOf("Первый фрагмент.", "Второй фрагмент.", "Третий фрагмент.")
        val edits = listOf("Первый исправленный фрагмент.", "Второй исправленный фрагмент.")
        val id = readySession(app.store, "Review sequence fixture", originals, language = "ru", uncertain = setOf(0, 1, 2))
        try {
            waitForTitle("Review sequence fixture")
            compose.onNodeWithText("Review sequence fixture").performClick()
            waitForSessionReady("Review sequence fixture", originals.first())
            compose.onNode(hasText(originals.first()) and hasClickAction()).performScrollTo().performClick()
            waitForEditor()
            compose.onNode(hasSetTextAction()).performTextReplacement(edits.first())
            // The editor lives in a dialog window, separate from the Activity's
            // window. Clear the actual text-field root focus and hide its IME.
            val editorView = checkNotNull(compose.onNode(hasSetTextAction()).fetchSemanticsNode().root as? android.view.View)
            compose.runOnUiThread {
                editorView.clearFocus()
                editorView.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(editorView.windowToken, 0)
            }
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.waitForIdle()
            compose.waitUntil(5_000) {
                compose.runOnUiThread {
                    ViewCompat.getRootWindowInsets(editorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) != true
                }
            }
            val saveNext = compose.onNodeWithText("Save & next").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.waitForIdle()
            val saveAction = checkNotNull(saveNext.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val sheet = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNode()
            val sheetTop = sheet.boundsInRoot.top
            fun editableText(node: SemanticsNode): String? =
                if (node.config.contains(SemanticsProperties.EditableText)) node.config[SemanticsProperties.EditableText].text
                else node.children.firstNotNullOfOrNull(::editableText)
            val autoAdvance = compose.mainClock.autoAdvance
            try {
                compose.mainClock.autoAdvance = false
                // Scroll and fetch while frames advance. Dispatch only the already
                // resolved semantic click while frozen; never synchronously await scrolling.
                compose.runOnUiThread { org.junit.Assert.assertTrue(saveAction()) }
                compose.waitUntil(10_000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnUiThread {
                        assertEquals("Save & next moved or hid the bottom sheet", sheetTop, sheet.boundsInRoot.top, 1f)
                        editableText(sheet) == originals[1]
                    }
                }
                // A new SheetState would replay its entrance after the next passage appears.
                repeat(24) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnUiThread {
                        assertEquals("The bottom sheet replayed its entrance after advancing review", sheetTop, sheet.boundsInRoot.top, 1f)
                    }
                }
            } finally {
                compose.mainClock.autoAdvance = autoAdvance
            }
            assertEquals(edits.first(), app.store.passages(id)[0].text)
            org.junit.Assert.assertTrue(app.store.passages(id)[0].reviewed)
            compose.onNode(hasSetTextAction()).performTextReplacement(edits[1])
            compose.onNodeWithTag("editor-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            val passages = app.store.passages(id)
            assertEquals(edits[1], passages[1].text)
            org.junit.Assert.assertTrue(passages[1].reviewed)
            org.junit.Assert.assertFalse(passages[2].reviewed)
            compose.onNodeWithText("Copy text").performClick()
            compose.runOnIdle {
                assertEquals((edits + originals.last()).joinToString("\n\n"), app.getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
            }
        } finally { app.store.delete(id) }
    }

    @Test fun backgroundSoundDecisionKeepsSourceAndCanBeRestoredAfterRecreation() {
        val app = application()
        val texts = listOf("Synthetic noise words.", "Keep these words.")
        val title = "Background sound fixture"
        val id = readySession(app.store, title, texts)
        val originalAudio = app.store.audioFile(id).readBytes()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        try {
            waitForTitle(title)
            compose.onNodeWithText(title).performClick()
            waitForSessionReady(title, texts.first())
            compose.onNodeWithContentDescription("Recording options").performClick()
            compose.onNodeWithText("Check background sound").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Exclude text").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(texts, app.store.passages(id).map { it.text })
            assertTrue(app.store.passages(id).none { it.excluded })
            capture("background-sound-proposal")
            compose.onNodeWithText("Exclude text").performScrollTo().performClick()
            compose.waitUntil(10_000) { app.store.passages(id).first().excluded }
            compose.onNodeWithText("Keep text").performScrollTo().performClick()
            compose.waitUntil(10_000) { app.store.passages(id).last().reviewed }
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.onNodeWithText("Copy text").performClick()
            compose.runOnIdle { assertEquals(texts.last(), clipboard.primaryClip!!.getItemAt(0).text.toString()) }

            compose.activityRule.scenario.recreate()
            waitForSessionReady(title, "Excluded background sound")
            compose.onNodeWithText("Excluded background sound").performClick()
            waitForEditor()
            compose.onNode(hasSetTextAction()).assertTextContains(texts.first())
            capture("background-sound-retained-text")
            // Merely viewing the retained text and pressing Done must not restore it.
            compose.onNodeWithTag("editor-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            assertTrue(app.store.passages(id).first().excluded)
            compose.onNodeWithText("Excluded background sound").performClick()
            waitForEditor()
            compose.onNodeWithText("Restore text").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Copy text").performClick()
            compose.runOnIdle { assertEquals(texts.joinToString("\n\n"), clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            assertEquals(texts, app.store.passages(id).map { it.originalTranscriptText })
            assertArrayEquals(originalAudio, app.store.audioFile(id).readBytes())
        } finally { app.store.delete(id) }
    }

    @Test fun textTransferBudgetAccountsForUtf8AndUtf16() {
        org.junit.Assert.assertTrue(fitsTextTransferBudget("a".repeat(131_072)))
        org.junit.Assert.assertFalse(fitsTextTransferBudget("a".repeat(131_073)))
        org.junit.Assert.assertTrue(fitsTextTransferBudget("Ж".repeat(131_072)))
        org.junit.Assert.assertFalse(fitsTextTransferBudget("€".repeat(90_000)))
        org.junit.Assert.assertTrue(fitsTextTransferBudget("🙂".repeat(65_536)))
    }

    @Test fun oversizedCopyAndShareKeepSavedTextAndExistingClipboard() {
        val app = application()
        val texts = List(32) { index -> "Passage $index. " + "Synthetic sample. ".repeat(260) }
        val id = readySession(app.store, "Large transfer fixture", texts)
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        try {
            waitForTitle("Large transfer fixture")
            compose.onNodeWithText("Large transfer fixture").performClick()
            waitForSessionReady("Large transfer fixture")
            compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("Test", "Existing clipboard")) }
            compose.onNodeWithText("Copy text").performClick()
            compose.onNodeWithText("This transcript is too long to copy at once. Copy a smaller selection from the passage editor.").assertIsDisplayed()
            compose.runOnIdle { assertEquals("Existing clipboard", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            compose.onNodeWithContentDescription("Recording options").performClick()
            compose.onNodeWithText("Share text").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("This transcript is too long to share at once. Copy a smaller selection from the passage editor.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle { assertEquals("Existing clipboard", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            assertEquals(texts, app.store.passages(id).map { it.text })
        } finally { app.store.delete(id) }
    }

    @Test fun renamedTitleDraftAndKeyboardSurviveRecreation() {
        val app = application()
        val originalTitle = "Rename recreation fixture"
        val originalText = "The recorded passage stays unchanged."
        val draft = "a".repeat(118) + "🙂"
        val id = readySession(app.store, originalTitle, listOf(originalText))
        fun waitForDialogKeyboard() {
            val field = compose.onNode(hasSetTextAction())
            val dialogView = checkNotNull(field.fetchSemanticsNode().root as? android.view.View)
            compose.waitUntil(5_000) {
                compose.runOnUiThread {
                    ViewCompat.getRootWindowInsets(dialogView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
            }
            field.assertIsFocused()
        }
        try {
            waitForTitle(originalTitle)
            compose.onNodeWithText(originalTitle).performClick()
            waitForSessionReady(originalTitle, originalText)
            compose.onNodeWithContentDescription("Recording options").performClick()
            compose.onNodeWithText("Rename").performClick()
            waitForEditor()
            waitForDialogKeyboard()
            compose.onNode(hasSetTextAction()).performTextReplacement(draft)
            assertEquals(originalTitle, app.store.note(id)?.title)
            compose.activityRule.scenario.recreate()
            waitForEditor()
            waitForDialogKeyboard()
            compose.onNode(hasSetTextAction()).assertTextContains(draft)
            compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            waitForSessionReady(draft, originalText)
            assertEquals(draft, app.store.note(id)?.title)
            assertEquals(originalText, app.store.passages(id).single().text)
        } finally { app.store.delete(id) }
    }

    @Test fun oversizedEditorInputKeepsTheValidDraftThroughRotation() {
        val app = application()
        val id = readySession(app.store, "Bounded draft fixture", listOf("Saved original."))
        val draft = "A valid manual edit stays here."
        fun assertWarningVisible(stage: String) {
            val message = "This text is too long for one passage. Paste a smaller selection."
            try {
                // Sheet and IME restoration can outlast the first editor node.
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onNodeWithText(message).isDisplayed()
                }
                compose.onNodeWithText(message).assertIsDisplayed()
            } catch (error: ComposeTimeoutException) {
                throw AssertionError("The rejected-input warning must be visible $stage", error)
            } catch (error: AssertionError) {
                throw AssertionError("The rejected-input warning must be visible $stage", error)
            }
        }
        try {
            waitForTitle("Bounded draft fixture")
            compose.onNodeWithText("Bounded draft fixture").performClick()
            waitForSessionReady("Bounded draft fixture", "Saved original.")
            compose.onNode(hasText("Saved original.") and hasClickAction()).performScrollTo().performClick()
            waitForEditor()
            compose.onNode(hasSetTextAction()).performTextReplacement(draft)
            // UTF-16 length fits, but UTF-8 bytes exceed the shared saved-passage budget.
            compose.onNode(hasSetTextAction()).performTextReplacement("🙂".repeat(40_000))
            assertWarningVisible("before rotation")
            compose.onNode(hasSetTextAction()).assertTextContains(draft)
            capture("oversized-editor-before-recreation")
            compose.activityRule.scenario.recreate()
            waitForEditor()
            compose.onNode(hasSetTextAction()).assertTextContains(draft)
            assertWarningVisible("after rotation")
            capture("oversized-editor-after-recreation")
            compose.onNodeWithTag("editor-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            assertEquals(draft, app.store.passages(id).single().text)
        } finally { app.store.delete(id) }
    }

    @Test fun rejectedEditorInputFeedbackClearsOnlyAfterAChangedValidDraft() {
        val app = application()
        val original = "Saved original."
        val id = readySession(app.store, "Rejected input feedback fixture", listOf(original))
        val draft = "A valid manual edit stays here."
        val changedDraft = "A changed valid manual edit can be saved."
        val warning = "This text is too long for one passage. Paste a smaller selection."
        try {
            waitForTitle("Rejected input feedback fixture")
            compose.onNodeWithText("Rejected input feedback fixture").performClick()
            waitForSessionReady("Rejected input feedback fixture", original)
            compose.onNode(hasText(original) and hasClickAction()).performScrollTo().performClick()
            waitForEditor()
            compose.onNode(hasSetTextAction()).performTextReplacement(draft)
            compose.onNode(hasSetTextAction()).performTextReplacement("🙂".repeat(40_000))
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onNodeWithText(warning).isDisplayed()
            }
            compose.onNodeWithText(warning).assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertTextContains(draft)
            // The input connection may repeat the accepted text after rejecting a paste.
            compose.onNode(hasSetTextAction()).performTextReplacement(draft)
            compose.onNodeWithText(warning).assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertTextContains(draft)
            assertEquals(original, app.store.passages(id).single().text)
            compose.onNode(hasSetTextAction()).performTextReplacement(changedDraft)
            compose.onNodeWithText(warning).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertTextContains(changedDraft)
            compose.onNodeWithTag("editor-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
            assertEquals(changedDraft, app.store.passages(id).single().text)
        } finally { app.store.delete(id) }
    }

    @Test fun largePassagePartsPreserveSurrogatesAndLineEndings() {
        val size = LARGE_PASSAGE_PART_CHARS
        val texts = listOf(
            "a".repeat(size - 1) + "🙂",
            "a".repeat(size - 1) + "\r\nb",
            "a".repeat(size - 1) + "🙂\r\n" + "б".repeat(size) + "\r\n🙂tail",
        )
        texts.forEach { text ->
            val parts = (0 until passageTextPartCount(text)).map { passageTextPart(text, it) }
            assertEquals(text, parts.joinToString(""))
            parts.forEach { part ->
                org.junit.Assert.assertTrue(part.isNotEmpty())
                org.junit.Assert.assertFalse(part.first().isLowSurrogate())
                org.junit.Assert.assertFalse(part.last().isHighSurrogate())
            }
            parts.zipWithNext().forEach { (left, right) ->
                org.junit.Assert.assertFalse(left.endsWith("\r") && right.startsWith("\n"))
            }
        }
    }

    @Test fun largeLegacyPassageUsesReadOnlyPagesAndSurvivesRotation() {
        val app = application()
        val id = readySession(app.store, "Large legacy fixture", listOf("Initial text."))
        val legacy = "Legacy start🙂\r\n" + "a".repeat(2_100_000) + "\r\nLegacy end🙂"
        try {
            val passage = app.store.passages(id).single()
            app.store.writableDatabase.execSQL("UPDATE passages SET text=? WHERE id=?", arrayOf(legacy, passage.id))
            app.store.rename(id, "Large legacy fixture")
            waitForTitle("Large legacy fixture")
            compose.onNodeWithText("Large legacy fixture").performClick()
            waitForSessionReady("Large legacy fixture")
            compose.onNodeWithText("Open full passage").performScrollTo().performClick()
            compose.onNodeWithText("Saved passage").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            val count = passageTextPartCount(legacy)
            compose.onNodeWithText("Part 1 of $count").assertIsDisplayed()
            compose.onNodeWithContentDescription("Next text part").performScrollTo().performClick()
            compose.onNodeWithText("Part 2 of $count").assertIsDisplayed()
            // Only the page index and small scroll state belong in saved instance state.
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Part 2 of $count").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Previous text part").performScrollTo().performClick()
            compose.onNodeWithText("Part 1 of $count").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close editor").performScrollTo().performClick()
            compose.onNodeWithText("Discard your changes?").assertDoesNotExist()
            assertEquals(legacy, app.store.passages(id).single().text)
        } finally { app.store.delete(id) }
    }
}
