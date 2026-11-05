package app.focal.ui.state

import androidx.compose.runtime.Immutable
import app.focal.core.Alignment
import app.focal.core.Doubt
import app.focal.core.TextWords
import app.focal.data.CaptureReason
import app.focal.data.Note
import app.focal.data.NoteState
import app.focal.data.Paragraph
import app.focal.recording.LiveSession
import app.focal.recording.SpeechAvailability

/**
 * Where a note stands, as the UI names it. Derived from the stored note, the service's [LiveSession] and
 * the model install ([NotePhases.of]); never stored.
 */
enum class NotePhase {
    /** The microphone session is live. */
    RECORDING,
    /** The microphone session is paused (manually or automatically: see the recording's pause reason). */
    PAUSED,
    /** Its windows are being transcribed now (determinate progress). */
    TRANSCRIBING,
    /** Transcription is blocked until the speech model finishes installing (first launch). */
    PREPARING_MODEL,
    /** Saved audio waits for transcription: preempted by a recording, process death, Android's limit or a model failure. */
    WAITING,
    READY,
    /** Ready, but some windows could not be transcribed: "Part of this note couldn't be transcribed · Retry". */
    PARTIAL_FAILURE,
    /** The recording could not start, or a legacy note failed before schema 5. */
    ERROR,
}

/** Why capture paused on its own. Null on a recording means a manual pause. */
enum class PauseReason {
    /** "A call is using the microphone." */
    CALL,
    /** "Android or another app silenced the microphone." (also a microphone that stopped delivering audio) */
    SILENCED,
    /** "Storage is almost full." */
    STORAGE;

    companion object {
        fun of(reason: String?): PauseReason? = when (reason) {
            CaptureReason.CALL -> CALL
            CaptureReason.MICROPHONE -> SILENCED
            CaptureReason.STORAGE -> STORAGE
            else -> null
        }
    }
}

/** Why a recording ended other than by Finish; the note shows a notice with the time it stopped. */
enum class EndReason {
    /** "Recording stopped unexpectedly at 41:12. Everything before that was saved." */
    PROCESS_ENDED,
    /** "Recording stopped at 41:12 because microphone access was turned off. Everything before that was saved." */
    PERMISSION;

    companion object {
        fun of(reason: String?): EndReason? = when (reason) {
            CaptureReason.PROCESS_ENDED -> PROCESS_ENDED
            CaptureReason.PERMISSION -> PERMISSION
            else -> null
        }
    }
}

object NotePhases {
    fun of(note: Note, live: LiveSession, speech: SpeechAvailability): NotePhase = when {
        live.noteId == note.id && live.capturing -> if (live.paused) NotePhase.PAUSED else NotePhase.RECORDING
        live.noteId == note.id && live.processing ->
            if (live.speech == SpeechAvailability.PREPARING) NotePhase.PREPARING_MODEL else NotePhase.TRANSCRIBING
        note.state == NoteState.READY -> if (note.failedWindows > 0) NotePhase.PARTIAL_FAILURE else NotePhase.READY
        note.state == NoteState.ERROR -> NotePhase.ERROR
        // RECORDING, PAUSED, PROCESSING without a live session (a moment before recovery) and INTERRUPTED.
        speech == SpeechAvailability.PREPARING -> NotePhase.PREPARING_MODEL
        else -> NotePhase.WAITING
    }
}

/**
 * A library row. [title] is empty until the transcript has words: show the fallback "Note at 14:32" from
 * [created]. [preview] is the start of the first visible paragraph. [progress] (0..1) is set while
 * [phase] is [NotePhase.TRANSCRIBING]. [audioPresent] is already false while a removal waits for its Undo
 * snackbar. [canRemoveAudio] offers Remove audio (a ready note without failed parts).
 */
@Immutable
data class NoteRow(
    val id: String,
    val title: String,
    val titleIsCustom: Boolean,
    val created: Long,
    val durationMs: Long,
    val preview: String,
    val phase: NotePhase,
    val progress: Float?,
    val audioPresent: Boolean,
    val failedParts: Int,
    val canRemoveAudio: Boolean = false,
)

/** Rows of one local calendar day ([epochDay] in the device time zone), newest first. */
@Immutable
data class LibraryDay(val epochDay: Long, val rows: List<NoteRow>)

/**
 * The library. [days] hold every note except the live recording ([liveNoteId]), whose row the UI draws
 * from [RecordingUiState] first in Today. [empty] means no notes and no recording (the empty state).
 */
@Immutable
data class LibraryUiState(
    val loaded: Boolean = false,
    val error: String? = null,
    val days: List<LibraryDay> = emptyList(),
    val liveNoteId: String? = null,
    val empty: Boolean = false,
)

/**
 * A search result. [snippet] is about 40 characters either side of the first hit with [snippetHit] its
 * range (null when only the title matched). Open the note at [paragraphId] and highlight [hitStart] until
 * [hitEnd] in that paragraph's text.
 */
@Immutable
data class SearchRow(
    val row: NoteRow,
    val paragraphId: Long?,
    val hitStart: Int,
    val hitEnd: Int,
    val snippet: String,
    val snippetHit: IntRange?,
)

/** [query] as typed; [results] belong to [resultsQuery] (results lag the query by the debounce). */
@Immutable
data class SearchUiState(
    val query: String = "",
    val resultsQuery: String = "",
    val results: List<SearchRow> = emptyList(),
    val searching: Boolean = false,
)

enum class RecordingPhase {
    /** No microphone session. */
    IDLE,
    /** Record was accepted; the service is opening the microphone and the note. */
    STARTING,
    RECORDING,
    PAUSED,
    /** Finish was tapped and the audio is closing; the note continues as [NotePhase.TRANSCRIBING]. */
    FINISHING,
}

/** A paragraph of the live recording ([open] is still growing). */
@Immutable
data class LiveParagraph(val id: Long, val text: String, val open: Boolean)

/**
 * The recording screen. [elapsedSeconds] ticks once a second (the meter level is NOT here: read
 * [app.focal.recording.AudioLevel.current] inside a draw lambda). [pauseReason] is null for a manual pause.
 * [storageShortBytes] > 0 after Resume was refused for storage ("Still not enough space. Free up about X").
 * [speech] adds " · preparing speech model" (PREPARING) or " · text will catch up" (UNAVAILABLE) to the
 * state line. [paragraphs] is the live transcript in order (the peek shows the last 1-3 lines).
 */
@Immutable
data class RecordingUiState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val noteId: String? = null,
    val elapsedSeconds: Long = 0,
    val pauseReason: PauseReason? = null,
    val storageShortBytes: Long = 0,
    val speech: SpeechAvailability = SpeechAvailability.READY,
    val paragraphs: List<LiveParagraph> = emptyList(),
) {
    val live: Boolean get() = phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED
}

/**
 * One word of a paragraph: chars [start, end) of [ParagraphUi.text]. [startMs]/[endMs] are its recorded
 * times (null when the word was changed by an edit or the note predates word times: never invented).
 * [doubtful] marks a low-confidence word (dotted underline; cleared by editing the word).
 */
@Immutable
data class WordUi(val start: Int, val end: Int, val startMs: Long?, val endMs: Long?, val doubtful: Boolean)

/**
 * A visible paragraph of a note. [words] cover its text; [exactTimes] is true when every word kept its time
 * (the playing-word lens is allowed). Pass [revision] back to edits: a stale one is refused. [canRestore]
 * offers "Restore original" (the recognizer's text). [open] paragraphs still grow while transcribing.
 */
@Immutable
data class ParagraphUi(
    val id: Long,
    val revision: Long,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<WordUi>,
    val exactTimes: Boolean,
    val edited: Boolean,
    val canRestore: Boolean,
    val open: Boolean,
) {
    /** Where a tap at [charOffset] plays from: the word's start (null: play from the paragraph start). */
    fun seekMs(charOffset: Int): Long? {
        var tapped = words.indexOfLast { it.start <= charOffset }
        while (tapped >= 0) {
            words[tapped].startMs?.let { return it }
            tapped--
        }
        return null
    }

    companion object {
        /** Words with times and doubt flags via [Alignment] (identity for unedited text). Off the main thread. */
        fun of(paragraph: Paragraph): ParagraphUi {
            val source = paragraph.sourceWords()
            val words = if (source == null) {
                TextWords.ranges(paragraph.text).map { WordUi(it.first, it.last + 1, null, null, false) }
            } else if (!paragraph.edited && source.joinToString(" ") { it.text } == paragraph.text) {
                var at = 0
                source.map { word ->
                    val start = at
                    at += word.text.length + 1
                    WordUi(start, start + word.text.length, word.startMs, word.endMs, Doubt.isDoubtful(word))
                }
            } else {
                Alignment.align(source, paragraph.text).map { WordUi(it.start, it.end, it.startMs, it.endMs, it.doubtful) }
            }
            return ParagraphUi(
                id = paragraph.id, revision = paragraph.revision, text = paragraph.text,
                startMs = paragraph.startMs, endMs = paragraph.endMs, words = words,
                exactTimes = words.isNotEmpty() && words.all { it.startMs != null },
                edited = paragraph.edited, canRestore = paragraph.canRestore, open = paragraph.open,
            )
        }
    }
}

/** An untranscribed range of a partly failed note, drawn as a gap row after [afterParagraphId] (null: first). */
@Immutable
data class GapUi(val startMs: Long, val endMs: Long, val afterParagraphId: Long?)

/**
 * A note screen. [title] empty = fallback ("Note at 14:32" from [created], muted). [progress] (0..1) is the
 * transcribed share while not READY. [editable] gates Edit, Find and Replace (READY only). [canPlay] is false
 * without audio or while any recording is live (the speaker must not leak into it). [noSpeech]: a ready note
 * with sound but no recognized words ("No speech was recognized."). [canRemoveAudio]: Remove audio is offered
 * (ready, audio kept, no failed part that Retry still needs). [canRetry]: Retry / Try again can work (the
 * audio is still there); without audio a failed part stays a gap for good.
 */
@Immutable
data class NoteUiState(
    val noteId: String,
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val error: String? = null,
    val title: String = "",
    val titleIsCustom: Boolean = false,
    val created: Long = 0,
    val durationMs: Long = 0,
    val phase: NotePhase = NotePhase.WAITING,
    val progress: Float? = null,
    val speech: SpeechAvailability = SpeechAvailability.READY,
    val waitingReason: String? = null,
    val audioPresent: Boolean = false,
    val canPlay: Boolean = false,
    val editable: Boolean = false,
    val paragraphs: List<ParagraphUi> = emptyList(),
    val gaps: List<GapUi> = emptyList(),
    val failedParts: Int = 0,
    val endReason: EndReason? = null,
    val noSpeech: Boolean = false,
    val legacy: Boolean = false,
    val canRemoveAudio: Boolean = false,
    val canRetry: Boolean = false,
)

/** Coarse playback state: changes on start, pause, stop and note change only (never with the position). */
@Immutable
data class PlaybackSession(
    val noteId: String? = null,
    val playing: Boolean = false,
    val durationMs: Long = 0,
)

enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun of(key: String): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Settings: [audioBytes] is the space the kept audio of [notesWithAudio] notes uses. */
@Immutable
data class SettingsUiState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val audioBytes: Long = 0,
    val notesWithAudio: Int = 0,
    val versionName: String = "",
)
