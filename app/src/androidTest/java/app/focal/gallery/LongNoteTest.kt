package app.focal.gallery

import android.os.Build
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.focal.data.SplitOrigin
import app.focal.data.TextMatch
import app.focal.ui.design.FocalTheme
import app.focal.ui.note.EditBackend
import app.focal.ui.note.NoteActions
import app.focal.ui.note.NotePage
import app.focal.ui.note.NotePlayback
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * A synthetic one-hour note (500 paragraphs, about 9,000 words) on the real note page: scrolls it end to end with
 * flings and edits a paragraph deep in it, recording every frame's work (FrameMetrics). Emulator numbers do not
 * stand for a phone; the test guards against regressions (a frame per item, recomposing the whole list per keystroke)
 * and logs the distribution under the tag "LongNote".
 */
@RunWith(AndroidJUnit4::class)
class LongNoteTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val frames = CopyOnWriteArrayList<Long>()
    private var recording = false

    @Before
    fun setUp() {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                rule.activity.window.addOnFrameMetricsAvailableListener(
                    Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                        // The frame's own work, UI thread and render thread: not the wait for its vsync, so a frame
                        // after an idle gap is not counted as slow.
                        if (recording) frames += WORK.sumOf { metrics.getMetric(it) }
                    },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
            }
        }
    }

    @Test
    fun anHourLongNoteScrollsAndEdits() {
        val store = MemoryNote(longNote())
        rule.setContent {
            FocalTheme(dark = false) {
                val shell = rememberShellState()
                val destination = remember { Destination.Note(store.note.noteId) }
                LaunchedEffect(shell) { shell.open(destination) }
                FocalShell(shell, destination = { current ->
                    if (current is Destination.Note) NotePage(
                        note = store.note, playback = Idle, actions = NoteActions(find = { store.find(it) }), edits = store,
                        destination = destination, dates = dates, today = today, captureElsewhere = false,
                        listState = rememberLazyListState(),
                    )
                })
            }
        }
        rule.waitForIdle()
        val words = store.note.paragraphs.sumOf { it.text.split(' ').size }
        assertTrue("about 9,000 words: $words", words in WORDS_MIN..WORDS_MAX)

        // Scroll: twenty flings down, ten back up.
        val list = rule.onNode(hasScrollAction())
        record {
            repeat(FLINGS) { list.performTouchInput { swipeUp(durationMillis = SWIPE_MS) }; rule.waitForIdle() }
            repeat(FLINGS / 2) { list.performTouchInput { swipeDown(durationMillis = SWIPE_MS) }; rule.waitForIdle() }
        }
        report("scroll")

        // Edit a paragraph three hundred in: the page enters Edit, the field takes the caret and the text changes.
        list.performScrollToIndex(DEEP_ITEM)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Edit").performClick()
        rule.waitForIdle()
        record {
            list.performTouchInput { click(center) }
            rule.waitForIdle()
            val field = rule.onNode(hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            field.performTextInputSelection(TextRange(0))
            field.performTextInput("Edited. ")
            rule.waitForIdle()
            Thread.sleep(AUTOSAVE_WAIT_MS)
            rule.waitForIdle()
        }
        report("edit")
        assertTrue("the autosave wrote the edit", store.edits > 0)
        assertEquals(PARAGRAPHS, store.note.paragraphs.size)
    }

    private fun record(block: () -> Unit) {
        frames.clear()
        recording = true
        try { block() } finally { recording = false }
    }

    private fun report(phase: String) {
        val ms = frames.map { it / NANOS_PER_MS }.sorted()
        if (ms.isEmpty()) return
        fun at(q: Double) = ms[((ms.size - 1) * q).toInt()]
        val janky = ms.count { it > FRAME_BUDGET_MS }
        Log.i(TAG, "$phase: ${ms.size} frames of work, p50 ${at(P50)} ms, p90 ${at(P90)} ms, p99 ${at(P99)} ms, max ${ms.last()} ms, over 16.7 ms: $janky")
        assertTrue("$phase: the median frame fits a 60 Hz budget (${at(P50)} ms)", at(P50) <= FRAME_BUDGET_MS)
    }

    private companion object {
        val WORK = listOf(
            FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION,
            FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION, FrameMetrics.COMMAND_ISSUE_DURATION,
        )
        const val TAG = "LongNote"
        const val PARAGRAPHS = 500
        const val WORDS_MIN = 8_500
        const val WORDS_MAX = 9_500
        const val FLINGS = 20
        const val SWIPE_MS = 120L
        const val DEEP_ITEM = 300
        const val AUTOSAVE_WAIT_MS = 900L
        const val NANOS_PER_MS = 1_000_000.0
        const val FRAME_BUDGET_MS = 16.7
        const val P50 = 0.5
        const val P90 = 0.9
        const val P99 = 0.99
    }
}

/** Synthetic speech: 500 paragraphs of 14-22 words over an hour, with word times. */
private fun longNote(): NoteUiState {
    val random = Random(SEED)
    val texts = List(500) {
        val count = 14 + random.nextInt(9)
        List(count) { VOCABULARY[random.nextInt(VOCABULARY.size)] }.joinToString(" ").replaceFirstChar { it.uppercase() } + "."
    }
    return NoteUiState(
        noteId = "long", loaded = true, title = "An hour of notes", created = System.currentTimeMillis(),
        durationMs = HOUR_MS, phase = NotePhase.READY, audioPresent = true, canPlay = true, editable = true,
        paragraphs = timedParagraphs(texts, HOUR_MS), canRemoveAudio = true,
    )
}

private const val SEED = 42
private const val HOUR_MS = 3_600_000L
private val VOCABULARY = (
    "the plan for the week is simple we meet at eight and walk to the lake then back by noon before the rain " +
        "starts so bring a coat and something warm because the wind on the ridge is strong in the afternoon and " +
        "nobody wants to wait at the station for an hour while the last train runs late again"
    ).split(' ')

/** No audio plays in this test. */
private object Idle : NotePlayback {
    override val session = PlaybackSession()
    override fun positionMs() = 0L
    override fun playWord(paragraph: ParagraphUi, offset: Int) = Unit
    override fun playFrom(ms: Long) = Unit
    override fun playRange(startMs: Long, endMs: Long) = Unit
    override fun toggle() = Unit
    override fun pause() = Unit
    override fun stop() = Unit
}

/** The note's store in memory, with the store's edit rules (revisions, trimming). */
private class MemoryNote(initial: NoteUiState) : EditBackend {
    var note by mutableStateOf(initial)
    var edits = 0

    fun find(query: String): List<TextMatch> = note.paragraphs.flatMap { p ->
        val at = p.text.indexOf(query, ignoreCase = true)
        if (at < 0) emptyList() else listOf(TextMatch(p.id, at, at + query.length))
    }

    override suspend fun edit(id: Long, revision: Long, text: String): EditOutcome {
        val current = note.paragraphs.firstOrNull { it.id == id } ?: return EditOutcome.Stale
        if (current.revision != revision) return EditOutcome.Stale
        val saved = current.copy(text = text.trim(), revision = revision + 1, edited = true, exactTimes = false)
        note = note.copy(paragraphs = note.paragraphs.map { if (it.id == id) saved else it })
        edits++
        return EditOutcome.Saved(listOf(saved))
    }

    override suspend fun split(id: Long, revision: Long, offset: Int) = EditOutcome.NotEditable
    override suspend fun unsplit(keepId: Long, keepRevision: Long, removeId: Long, removeRevision: Long, offset: Int, origin: SplitOrigin) =
        EditOutcome.NotEditable
    override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long) = EditOutcome.NotEditable
    override suspend fun restore(id: Long, revision: Long) = EditOutcome.NotEditable
}
