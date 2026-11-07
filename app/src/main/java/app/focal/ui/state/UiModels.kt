package app.focal.ui.state

import androidx.compose.runtime.Immutable
import app.focal.FocalApplication
import app.focal.core.Alignment
import app.focal.core.Doubt
import app.focal.core.TextWords
import app.focal.data.CaptureReason
import app.focal.data.Note
import app.focal.data.NoteState
import app.focal.data.Paragraph
import app.focal.data.WaitCause
import app.focal.recording.LiveSession
import app.focal.recording.SpeechAvailability
import app.focal.speech.EngineFault
import app.focal.speech.ModelStatus
import app.focal.speech.waitCause
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

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

/**
 * Why a waiting note is not being transcribed, as its notice and the stage's last line say it (spec 5.4 "Model
 * couldn't load"), each with Try again on the note.
 */
@Immutable
sealed interface WaitingReason {
    /** The engine guard stopped the speech work after a confirmed out-of-memory: "Not enough free memory…". */
    data object Memory : WaitingReason

    /** The engine guard stopped it after repeated crashes or failed loads, causes unknown: a neutral sentence. */
    data object Failures : WaitingReason

    /**
     * Too little free storage to prepare the speech model: "Free up about 850 MB…". Try again checks again, as does the
     * next foreground start.
     */
    data class Storage(val shortBytes: Long) : WaitingReason

    /** The speech model could not be prepared otherwise: Try again copies it out of the APK again. */
    data object Install : WaitingReason
}

/**
 * Why a waiting note is not being transcribed. The speech work that stopped after killing the process or failing to load
 * twice comes first ([ModelState.stop], until Try again), named as memory only when that was confirmed; then a model
 * install that fails now ([ModelState.failure]); then the cause the speech worker stored on the note, unless the model
 * is ready again or the guard no longer stops the work. Null when the note simply waits (an interrupted recording
 * resumes at the next foreground start) and when it has no audio left to transcribe.
 */
fun waitingReason(note: Note, phase: NotePhase, model: ModelState = ModelState(SpeechAvailability.READY)): WaitingReason? {
    if (phase != NotePhase.WAITING || !note.audioPresent) return null
    when (model.stop) {
        EngineFault.MEMORY -> return WaitingReason.Memory
        EngineFault.OTHER -> return WaitingReason.Failures
        null -> Unit
    }
    val stored = WaitCause.decode(note.error).takeIf { note.state == NoteState.INTERRUPTED }
    val ready = model.availability == SpeechAvailability.READY
    // The install failing now, then the cause the note stored (until it is tried again: a start clears it).
    return when (val cause = model.failure ?: stored) {
        is WaitCause.ModelStorage -> WaitingReason.Storage(cause.shortBytes).takeUnless { ready }
        WaitCause.ModelInstall -> WaitingReason.Install.takeUnless { ready }
        // The speech work failed with the model ready: why, so the note offers Try again at once.
        WaitCause.OutOfMemory -> WaitingReason.Memory
        WaitCause.EngineFailed -> WaitingReason.Failures
        // The guard no longer stops the work (model.stop is null): the note resumes.
        WaitCause.EngineStopped, null -> null
    }
}

/**
 * The speech model as notes see it: whether it can transcribe ([availability]), why the speech work stopped ([stop]: the
 * app's engine guard, after it killed the process or failed to load twice in a row, until Try again) and why the model
 * install failed ([failure], while it does).
 */
data class ModelState(val availability: SpeechAvailability, val stop: EngineFault? = null, val failure: WaitCause? = null) {
    val stopped: Boolean get() = stop != null
}

/** [ModelState] from the model install and the engine guard, changing only when one of them does. */
fun FocalApplication.modelStates(): Flow<ModelState> = combine(models.status, engineGuard.stop) { status, stop ->
    val availability = when (status) {
        ModelStatus.Checking, is ModelStatus.Installing -> SpeechAvailability.PREPARING
        is ModelStatus.Ready -> SpeechAvailability.READY
        else -> SpeechAvailability.UNAVAILABLE
    }
    ModelState(availability, stop, (status as? ModelStatus.Failed)?.waitCause())
}.distinctUntilChanged()

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
    /** Why a waiting note is not being transcribed (the stage's last line), or null. */
    val waitingReason: WaitingReason? = null,
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
 * A search result. [snippet] is about 40 characters either side of the first hit with [snippetHits] the range of
 * every hit inside it, first hit first (empty when only the title matched). Open the note at [paragraphId] and
 * highlight [hitStart] until [hitEnd] in that paragraph's text.
 */
@Immutable
data class SearchRow(
    val row: NoteRow,
    val paragraphId: Long?,
    val hitStart: Int,
    val hitEnd: Int,
    val snippet: String,
    val snippetHits: List<IntRange>,
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
 * [originKnown] is false for text saved before recognizer originals were kept, and [hasOriginal] says the
 * recognizer produced some of it: Edit tells from them which joins the store keeps apart.
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
    val originKnown: Boolean = true,
    val hasOriginal: Boolean = false,
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
                originKnown = paragraph.sourceText != null, hasOriginal = !paragraph.sourceText.isNullOrEmpty(),
            )
        }
    }
}

/**
 * A stored paragraph with no text yet (Enter at the end of a paragraph makes one), placed after [after], the
 * paragraph before it in the note (null: the first). Reading leaves it out; an Edit session that was recreated
 * (rotation, process death) puts back the ones it had made, with the field that may be typing in one.
 */
@Immutable
data class BlankParagraph(val paragraph: ParagraphUi, val after: Long?)

/**
 * An untranscribed range, drawn as a gap row after [afterParagraphId] (null: before the first paragraph).
 * [failed]: a part that could not be transcribed ("0:42 not transcribed", Retry); otherwise a range of a note
 * from an older version that is still waiting for transcription.
 */
@Immutable
data class GapUi(val startMs: Long, val endMs: Long, val afterParagraphId: Long?, val failed: Boolean = true)

/**
 * A note screen. [title] empty = fallback ("Note at 14:32" from [created], muted). [progress] (0..1) is the
 * transcribed share while not READY. [editable] gates Edit, Find and Replace (READY only). [canPlay] is false
 * without audio or while any recording is live (the speaker must not leak into it). [noSpeech]: a ready note
 * with sound but no recognized words ("No speech was recognized."). [canRemoveAudio]: Remove audio is offered
 * (ready, audio kept, no failed part that Retry still needs). [canRetry]: Retry / Try again can work (the
 * audio is still there); without audio a failed part stays a gap for good. [blankParagraphs] are the stored paragraphs
 * with no text yet, which [paragraphs] leaves out.
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
    /** Why a waiting note is not being transcribed: its notice, with Try again. */
    val waitingReason: WaitingReason? = null,
    val audioPresent: Boolean = false,
    val canPlay: Boolean = false,
    val editable: Boolean = false,
    val paragraphs: List<ParagraphUi> = emptyList(),
    val blankParagraphs: List<BlankParagraph> = emptyList(),
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
