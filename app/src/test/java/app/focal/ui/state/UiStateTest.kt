package app.focal.ui.state

import app.focal.core.Word
import app.focal.data.CaptureReason
import app.focal.data.Note
import app.focal.data.NoteState
import app.focal.data.Paragraph
import app.focal.data.TranscriptJson
import app.focal.data.WaitCause
import app.focal.recording.LiveSession
import app.focal.recording.SpeechAvailability
import app.focal.speech.EngineFault
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

    @Test fun aTapSelectsTheWordWithoutThePunctuationThatHangsOnIt() {
        val ui = ParagraphUi.of(paragraph("Hello, wrold again.", words))
        // Anywhere in the word, its ends included: the letters, never the comma or the full stop.
        assertEquals(0 until 5, ui.wordAt(0))
        assertEquals(0 until 5, ui.wordAt(3))
        assertEquals(0 until 5, ui.wordAt(6))
        assertEquals(7 until 12, ui.wordAt(7))
        assertEquals(7 until 12, ui.wordAt(12))
        assertEquals(13 until 18, ui.wordAt(18))
        assertEquals("again", ui.text.substring(13, 18))
        // Past the text there is no word: the caret goes there.
        assertNull(ui.wordAt(40))
        // Punctuation alone is not a word to replace.
        val dash = ParagraphUi.of(paragraph("One – two", listOf(Word("One", 0, 100, 0.9, false, 0), Word("–", 100, 150, 0.9, true, 0), Word("two", 200, 300, 0.9, false, 0))))
        assertNull(dash.wordAt(4))
        assertEquals(6 until 9, dash.wordAt(7))
    }

    @Test fun listeningPlaysTheWordsAtTheCaretWithALeadAndNeverInventsATime() {
        val ui = ParagraphUi.of(paragraph("Hello, wrold again.", words))
        // A caret in "wrold": from its start to its end, with the lead either side.
        assertEquals(0L until 1_400L, ui.listenRange(9, 9, lead = 500))
        // A selection of the last two words: from the first one's start to the last one's end.
        assertEquals(300L until 1_600L, ui.listenRange(7, 19, lead = 200))
        // Never before the recording starts.
        assertEquals(0L until 600L, ui.listenRange(0, 3, lead = 200))
        // An edited word has no time: the range widens to its timed neighbours.
        val edited = ParagraphUi.of(paragraph("Hello, world again.", words, edited = true, source = "Hello, wrold again."))
        assertEquals(0L until 1_500L, edited.listenRange(8, 10, lead = 100))
        // Text without any word times plays its whole paragraph.
        val legacy = ParagraphUi.of(paragraph("Saved before word times.", null, source = null))
        assertEquals(0L until 1_300L, legacy.listenRange(3, 3, lead = 300))
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

    @Test fun aStoppedEngineIsTheReasonEveryWaitingNoteWaits() {
        val interrupted = note(NoteState.INTERRUPTED).copy(error = "Recording was interrupted. Saved audio can still be transcribed.")
        val failures = ModelState(SpeechAvailability.READY, stop = EngineFault.OTHER)
        val memory = ModelState(SpeechAvailability.READY, stop = EngineFault.MEMORY)
        // An interrupted note's own message is not a failure to show: it resumes at the next foreground start.
        assertNull(waitingReason(interrupted, NotePhase.WAITING))
        // Once the speech work killed the process twice, every waiting note says why and offers Try again (spec 5.4),
        // naming memory only when an out-of-memory was confirmed.
        assertEquals(WaitingReason.Failures, waitingReason(interrupted, NotePhase.WAITING, failures))
        assertEquals(WaitingReason.Failures, waitingReason(note(NoteState.PROCESSING), NotePhase.WAITING, failures))
        assertEquals(WaitingReason.Memory, waitingReason(interrupted, NotePhase.WAITING, memory))
        // Without audio there is nothing to transcribe again; a note that is not waiting has no reason.
        assertNull(waitingReason(interrupted.copy(audioPresent = false), NotePhase.WAITING, failures))
        assertNull(waitingReason(note(NoteState.READY), NotePhase.READY, failures))
        assertNull(waitingReason(note(NoteState.PROCESSING), NotePhase.TRANSCRIBING, memory))
    }

    @Test fun aWaitingNoteSaysWhyTheModelCouldNotBePrepared() {
        val interrupted = note(NoteState.INTERRUPTED)
        val short = WaitCause.ModelStorage(850_000_000)
        val failedStorage = ModelState(SpeechAvailability.UNAVAILABLE, failure = short)
        val failedInstall = ModelState(SpeechAvailability.UNAVAILABLE, failure = WaitCause.ModelInstall)
        // The install that fails now names the reason for every waiting note, even one stored as a plain interruption.
        assertEquals(WaitingReason.Storage(850_000_000), waitingReason(interrupted, NotePhase.WAITING, failedStorage))
        assertEquals(WaitingReason.Install, waitingReason(interrupted, NotePhase.WAITING, failedInstall))
        // A stopped engine comes first: nothing is installed while it is stopped.
        assertEquals(WaitingReason.Failures, waitingReason(interrupted, NotePhase.WAITING,
            ModelState(SpeechAvailability.UNAVAILABLE, stop = EngineFault.OTHER, failure = WaitCause.EngineStopped)))
        // The stored cause when nothing in this process has said otherwise.
        val stored = interrupted.copy(error = WaitCause.encode(short))
        assertEquals(WaitingReason.Storage(850_000_000), waitingReason(stored, NotePhase.WAITING, ModelState(SpeechAvailability.UNAVAILABLE)))
        assertEquals(WaitingReason.Install, waitingReason(interrupted.copy(error = WaitCause.encode(WaitCause.ModelInstall)),
            NotePhase.WAITING, ModelState(SpeechAvailability.UNAVAILABLE)))
        // Once the model is ready, or the guard no longer stops the work, the note resumes like any interrupted one.
        assertNull(waitingReason(stored, NotePhase.WAITING, ModelState(SpeechAvailability.READY)))
        assertNull(waitingReason(interrupted.copy(error = WaitCause.encode(WaitCause.EngineStopped)), NotePhase.WAITING,
            ModelState(SpeechAvailability.UNAVAILABLE)))
        // A note without audio has nothing to transcribe again.
        assertNull(waitingReason(interrupted.copy(audioPresent = false), NotePhase.WAITING, failedStorage))
        // A damaged APK copy says what helps, and offers no Try again: copying it again cannot.
        val damaged = ModelState(SpeechAvailability.UNAVAILABLE, failure = WaitCause.ModelDamaged)
        assertEquals(WaitingReason.Damaged, waitingReason(interrupted, NotePhase.WAITING, damaged))
        assertEquals(WaitingReason.Damaged, waitingReason(interrupted.copy(error = WaitCause.encode(WaitCause.ModelDamaged)),
            NotePhase.WAITING, ModelState(SpeechAvailability.UNAVAILABLE)))
        assertFalse(WaitingReason.Damaged.retries)
        assertTrue(WaitingReason.Install.retries && WaitingReason.Memory.retries)
    }

    /**
     * The recognizer let go with the model ready (one confirmed out-of-memory, or different windows failing in a row,
     * before the guard stops anything): the note says why and offers Try again at once, until it is tried again.
     */
    @Test fun aWaitingNoteSaysWhyTheRecognizerWasLetGoWithTheModelReady() {
        val ready = ModelState(SpeechAvailability.READY)
        val memory = note(NoteState.INTERRUPTED).copy(error = WaitCause.encode(WaitCause.OutOfMemory))
        val failed = note(NoteState.INTERRUPTED).copy(error = WaitCause.encode(WaitCause.EngineFailed))
        assertEquals(WaitingReason.Memory, waitingReason(memory, NotePhase.WAITING, ready))
        assertEquals(WaitingReason.Failures, waitingReason(failed, NotePhase.WAITING, ready))
        // A start clears the stored cause (the note is PROCESSING, its error empty): nothing is said then.
        assertNull(waitingReason(note(NoteState.PROCESSING), NotePhase.WAITING, ready))
        // The guard's stop and an install failing now come first.
        assertEquals(WaitingReason.Failures, waitingReason(memory, NotePhase.WAITING, ModelState(SpeechAvailability.READY, stop = EngineFault.OTHER)))
        assertEquals(WaitingReason.Install, waitingReason(memory, NotePhase.WAITING,
            ModelState(SpeechAvailability.UNAVAILABLE, failure = WaitCause.ModelInstall)))
    }

    @Test fun waitCausesRoundTripThroughTheStore() {
        for (cause in listOf(WaitCause.ModelStorage(0), WaitCause.ModelStorage(1_234_567_890_123), WaitCause.ModelInstall,
            WaitCause.ModelDamaged, WaitCause.EngineStopped, WaitCause.OutOfMemory, WaitCause.EngineFailed))
            assertEquals(cause, WaitCause.decode(WaitCause.encode(cause)))
        // What older builds stored, and damaged values, are plain interruptions.
        for (text in listOf(null, "", "Saved audio needs transcription. Open the note to continue.", "wait:model-storage:",
            "wait:model-storage:-5", "wait:model-storage:many", "wait:unknown"))
            assertNull(text, WaitCause.decode(text))
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
