package app.focal.ui.state

import app.focal.core.Word
import app.focal.data.CaptureReason
import app.focal.data.Note
import app.focal.data.NoteState
import app.focal.data.Paragraph
import app.focal.data.TranscriptJson
import app.focal.recording.LiveSession
import app.focal.recording.SpeechAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphUiTest {
    private fun paragraph(text: String, words: List<Word>?, edited: Boolean = false, source: String? = text) = Paragraph(
        id = 7, noteId = "n", ord = 0, startMs = words?.first()?.startMs ?: 0, endMs = words?.last()?.endMs ?: 1_000,
        text = text, sourceText = source, wordsJson = words?.let(TranscriptJson::encodeWords), edited = edited,
        hidden = false, open = false, revision = 3,
    )

    private val words = listOf(
        Word("Hello,", 100, 400, 0.98, false, 0),
        Word("wrold", 500, 900, 0.21, false, 0),
        Word("again.", 1_000, 1_400, 0.9, false, 0),
    )

    @Test fun uneditedTextKeepsEveryTimeAndDoubt() {
        val ui = ParagraphUi.of(paragraph("Hello, wrold again.", words))
        assertTrue(ui.exactTimes)
        assertEquals(listOf(0 until 6, 7 until 12, 13 until 19), ui.words.map { it.start until it.end })
        assertEquals(listOf(100L, 500L, 1_000L), ui.words.map { it.startMs })
        assertEquals(listOf(false, true, false), ui.words.map { it.doubtful })
        assertEquals(500L, ui.seekMs(9))
        assertEquals(3L, ui.revision)
    }

    @Test fun anEditedWordLosesItsTimeAndDoubtButNeighboursKeepTheirs() {
        val ui = ParagraphUi.of(paragraph("Hello, world again.", words, edited = true, source = "Hello, wrold again."))
        assertFalse(ui.exactTimes)
        assertNull(ui.words[1].startMs)
        assertFalse(ui.words[1].doubtful)
        // A tap on the changed word plays from the nearest earlier timed word.
        assertEquals(100L, ui.seekMs(8))
        assertEquals(1_000L, ui.seekMs(14))
    }

    @Test fun legacyTextHasWordsButNoInventedTimes() {
        val ui = ParagraphUi.of(paragraph("Saved before word times.", null, source = null))
        assertEquals(4, ui.words.size)
        assertTrue(ui.words.all { it.startMs == null && !it.doubtful })
        assertFalse(ui.exactTimes)
        assertFalse(ui.canRestore)
        assertNull(ui.seekMs(3))
    }
}

class PlayingWordTest {
    private fun ui(start: Long, vararg times: Long?) = ParagraphUi(
        id = start, revision = 0, text = "", startMs = start, endMs = start + 1_000,
        words = times.mapIndexed { i, t -> WordUi(i * 2, i * 2 + 1, t, t?.plus(100), false) },
        exactTimes = times.all { it != null }, edited = false, canRestore = false, open = false,
    )

    @Test fun locatesTheParagraphAndTheWordBeingSpoken() {
        val paragraphs = listOf(ui(0, 0, 300, 600), ui(2_000, 2_000, 2_500), ui(4_000, 4_000, null))
        assertNull(PlayingWord.locate(paragraphs, -1))
        assertEquals(WordLocation(0, 1), PlayingWord.locate(paragraphs, 450))
        assertEquals(WordLocation(0, 2), PlayingWord.locate(paragraphs, 1_500))
        assertEquals(WordLocation(1, 0), PlayingWord.locate(paragraphs, 2_000))
        // Without exact times there is no word: the paragraph is focused, no lens.
        assertEquals(WordLocation(2, null), PlayingWord.locate(paragraphs, 4_200))
        assertNull(PlayingWord.locate(emptyList(), 10))
    }
}

class NotePhasesTest {
    private fun note(state: String, failed: Int = 0) =
        Note("n", "", 0, "auto", state, 10_000, "", failedWindows = failed)

    @Test fun phasesFollowTheServiceThenTheStore() {
        val ready = SpeechAvailability.READY
        assertEquals(NotePhase.RECORDING, NotePhases.of(note(NoteState.RECORDING), LiveSession("n"), ready))
        assertEquals(NotePhase.PAUSED, NotePhases.of(note(NoteState.PAUSED), LiveSession("n", paused = true), ready))
        assertEquals(NotePhase.TRANSCRIBING, NotePhases.of(note(NoteState.PROCESSING), LiveSession("n", processing = true), ready))
        assertEquals(NotePhase.PREPARING_MODEL, NotePhases.of(note(NoteState.PROCESSING),
            LiveSession("n", processing = true, speech = SpeechAvailability.PREPARING), ready))
        assertEquals(NotePhase.WAITING, NotePhases.of(note(NoteState.INTERRUPTED), LiveSession("other"), ready))
        assertEquals(NotePhase.PREPARING_MODEL, NotePhases.of(note(NoteState.INTERRUPTED), LiveSession(), SpeechAvailability.PREPARING))
        assertEquals(NotePhase.READY, NotePhases.of(note(NoteState.READY), LiveSession(), ready))
        assertEquals(NotePhase.PARTIAL_FAILURE, NotePhases.of(note(NoteState.READY, failed = 2), LiveSession(), ready))
        assertEquals(NotePhase.ERROR, NotePhases.of(note(NoteState.ERROR), LiveSession(), ready))
    }

    @Test fun reasonsMapFromTheStore() {
        assertEquals(PauseReason.CALL, PauseReason.of(CaptureReason.CALL))
        assertEquals(PauseReason.SILENCED, PauseReason.of(CaptureReason.MICROPHONE))
        assertEquals(PauseReason.STORAGE, PauseReason.of(CaptureReason.STORAGE))
        assertNull(PauseReason.of(null))
        assertEquals(EndReason.PERMISSION, EndReason.of(CaptureReason.PERMISSION))
        assertEquals(EndReason.PROCESS_ENDED, EndReason.of(CaptureReason.PROCESS_ENDED))
        assertNull(EndReason.of(CaptureReason.STORAGE))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of("unknown"))
        assertEquals(ThemeMode.DARK, ThemeMode.of("dark"))
    }
}
