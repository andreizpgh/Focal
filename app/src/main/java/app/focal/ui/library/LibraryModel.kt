package app.focal.ui.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import app.focal.recording.SpeechAvailability
import app.focal.ui.components.DockState
import app.focal.ui.components.NotePill
import app.focal.ui.components.PlayCircle
import app.focal.ui.components.ProgressPhase
import app.focal.ui.design.FocalTiming
import app.focal.ui.state.LibraryDay
import app.focal.ui.state.LibraryUiState
import app.focal.ui.state.LiveParagraph
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteRow
import app.focal.ui.state.PauseReason
import app.focal.ui.state.RecordingPhase
import app.focal.ui.state.RecordingUiState

/** What the stage holds (spec 4.22.2): the live capture, or one note. */
@Immutable
sealed interface StageUi {
    /** Stable across state changes of the same content, for keys and transitions. */
    val key: String

    /**
     * A capture running or paused while the scene is folded (spec 4.22.3). [hint] shows "Text appears after each
     * pause." before the first text of the first recordings.
     */
    data class Live(
        val elapsedSeconds: Long,
        val paused: Boolean,
        val pauseReason: PauseReason?,
        val speech: SpeechAvailability,
        val paragraphs: List<LiveParagraph>,
        val hint: Boolean,
    ) : StageUi {
        override val key: String get() = LIVE_KEY
        val storageFull: Boolean get() = paused && pauseReason == PauseReason.STORAGE
    }

    /** A note in any state but live (spec 4.22.4). [fresh]: Ready and not copied yet (the white Copy). */
    data class Note(val row: NoteRow, val fresh: Boolean) : StageUi {
        override val key: String get() = row.id
    }

    companion object {
        const val LIVE_KEY = "live"
    }
}

/**
 * The library as drawn: whether the first query returned, the stage, the days of the list (without the stage's
 * note), and the error to offer a retry for. Built by [LibraryModels.of]; the gallery builds it from fakes.
 */
@Immutable
data class LibraryModel(
    val loaded: Boolean = false,
    val stage: StageUi? = null,
    val days: List<LibraryDay> = emptyList(),
    val error: String? = null,
) {
    /** No notes and no recording: the instrument at rest, the invitation and Record alone (spec 4.22.5). */
    val empty: Boolean get() = loaded && stage == null && days.isEmpty()
    val live: StageUi.Live? get() = stage as? StageUi.Live

    /** The dock the library shows (spec 4.3). */
    val dock: DockState get() {
        val capture = live
        return when {
            capture != null -> DockState.Live(paused = capture.paused)
            empty -> DockState.LibraryEmpty
            else -> DockState.LibraryIdle
        }
    }
}

/**
 * The dock of a note's page (spec 4.3): ▶ (‖ while it plays there; a faint ▶ while a recording holds the microphone;
 * nothing for a note without audio) and the progress pill, or Copy once the note is ready.
 */
fun noteDock(audioPresent: Boolean, canPlay: Boolean, playing: Boolean, phase: NotePhase, progress: Float?, copyEnabled: Boolean): DockState.Note =
    DockState.Note(
        circle = when {
            !audioPresent -> PlayCircle.None
            !canPlay -> PlayCircle.Unavailable
            playing -> PlayCircle.PausePlayback
            else -> PlayCircle.Play
        },
        pill = when (phase) {
            NotePhase.READY, NotePhase.PARTIAL_FAILURE, NotePhase.ERROR -> NotePill.Copy(enabled = copyEnabled)
            NotePhase.PREPARING_MODEL -> NotePill.Progress(ProgressPhase.Preparing, progress ?: 0f)
            NotePhase.WAITING -> NotePill.Progress(ProgressPhase.Waiting, progress ?: 0f)
            else -> NotePill.Progress(ProgressPhase.Transcribing, progress ?: 0f)
        },
    )

/** The dock [row]'s page will show when it opens from the stage or the list, before it has loaded the note. */
fun pageDock(row: NoteRow, capturing: Boolean): DockState.Note = noteDock(
    audioPresent = row.audioPresent, canPlay = row.audioPresent && !capturing, playing = false,
    phase = row.phase, progress = row.progress, copyEnabled = row.preview.isNotBlank(),
)

/** The dock of the note Finish opens from the live stage (T7b): its audio is there and it starts transcribing. */
val FinishedDock: DockState.Note = noteDock(
    audioPresent = true, canPlay = true, playing = false, phase = NotePhase.TRANSCRIBING, progress = 0f, copyEnabled = false,
)

object LibraryModels {
    /**
     * Picks the stage (spec 4.22.2; the first match wins): a live or paused capture; else the most recent note that
     * is transcribing, waiting or preparing; else the most recent note. The stage's note leaves the list, so nothing
     * appears twice; a live capture keeps every note in the list.
     */
    fun of(library: LibraryUiState, recording: RecordingUiState, fresh: Set<String>): LibraryModel {
        if (!library.loaded) return LibraryModel()
        val rows = library.days.flatMap { it.rows }.filter { it.id != recording.noteId || !recording.live }
        val stage: StageUi? = if (recording.live) {
            StageUi.Live(
                elapsedSeconds = recording.elapsedSeconds,
                paused = recording.phase == RecordingPhase.PAUSED,
                pauseReason = recording.pauseReason,
                speech = recording.speech,
                paragraphs = recording.paragraphs,
                // "The first three recordings": the live one is among them while fewer notes exist before it.
                hint = rows.size < FocalTiming.HINT_RECORDINGS,
            )
        } else {
            (rows.firstOrNull { it.phase in WORKING } ?: rows.firstOrNull())?.let { StageUi.Note(it, fresh = it.id in fresh) }
        }
        val stageNote = (stage as? StageUi.Note)?.row?.id
        val days = library.days.mapNotNull { day ->
            val kept = day.rows.filter { it.id != stageNote && (it.id != recording.noteId || !recording.live) }
            if (kept.isEmpty()) null else if (kept.size == day.rows.size) day else day.copy(rows = kept)
        }
        return LibraryModel(loaded = true, stage = stage, days = days, error = library.error)
    }

    /** Phases in which a note is still becoming text: such a note takes the stage before older ready ones. */
    val WORKING = setOf(NotePhase.TRANSCRIBING, NotePhase.WAITING, NotePhase.PREPARING_MODEL)
    private val DONE = setOf(NotePhase.READY, NotePhase.PARTIAL_FAILURE)

    /** True for a note that has text to copy (ready, or ready with a gap). */
    fun copyable(row: NoteRow): Boolean = row.phase in DONE && !noSpeech(row)

    /** A ready note with sound but no recognised words: the fallback title and "No speech was recognized.". */
    fun noSpeech(row: NoteRow): Boolean = row.phase == NotePhase.READY && row.title.isBlank() && row.preview.isBlank()

    /** Whether [row] is done becoming text. */
    fun done(row: NoteRow): Boolean = row.phase in DONE
}

/** The row menu's items (spec 4.15). */
enum class MenuItem { CopyText, Share, Rename, RemoveAudio, Delete }

/**
 * Row menu = Copy text, Share, Rename, divider, Remove audio, Delete; a note that is not ready yet offers Rename and
 * Delete only. Items that do not apply are hidden, not disabled.
 */
fun menuItems(row: NoteRow): List<MenuItem> {
    if (!LibraryModels.done(row)) return listOf(MenuItem.Rename, MenuItem.Delete)
    return buildList {
        if (LibraryModels.copyable(row)) {
            add(MenuItem.CopyText)
            add(MenuItem.Share)
        }
        add(MenuItem.Rename)
        if (row.canRemoveAudio) add(MenuItem.RemoveAudio)
        add(MenuItem.Delete)
    }
}

/**
 * Notes that became Ready while Focal watched (spec 4.22.2, "fresh"): set when a note turns Ready, cleared by its
 * first Copy from any surface, and all at once when the app stops. Memory only; nothing is stored.
 */
@Stable
object FreshNotes {
    private val ids = mutableStateMapOf<String, Unit>()
    /** Notes whose readiness moment already played (at most once per note, spec 2.9). */
    private val played = HashSet<String>()

    val current: Set<String> get() = ids.keys.toSet()

    fun markReady(noteIds: Collection<String>) { noteIds.forEach { ids[it] = Unit } }

    /** The note was copied: its Copy relaxes from white to glass. */
    fun copied(noteId: String) { ids.remove(noteId) }

    /** The app stopped. */
    fun clear() { ids.clear() }

    /** Claims the readiness moment for [noteId]; false if it already played. */
    fun claimMoment(noteId: String): Boolean = played.add(noteId)
}

/**
 * Finds the notes that just became Ready from the library's successive states: a note that was transcribing,
 * waiting, preparing or live and is now Ready (or Ready with a gap).
 */
class ReadyWatch {
    private var phases: Map<String, NotePhase>? = null
    private var liveNote: String? = null

    /** The ids that turned Ready since the previous call. The first call only records. */
    fun update(library: LibraryUiState, recording: RecordingUiState): List<String> {
        if (!library.loaded) return emptyList()
        val now = library.days.asSequence().flatMap { it.rows }.associate { it.id to it.phase }
        val before = phases
        val wasLive = liveNote
        phases = now
        val capturing = recording.noteId?.takeIf { recording.live || recording.phase == RecordingPhase.FINISHING }
        // The live note is not in the list; remember it until it shows up there.
        liveNote = capturing ?: wasLive?.takeIf { it !in now }
        if (before == null) return emptyList()
        return now.filter { (id, phase) ->
            phase in DONE && (before[id] in LibraryModels.WORKING || (before[id] == null && id == wasLive))
        }.keys.toList()
    }

    private companion object {
        val DONE = setOf(NotePhase.READY, NotePhase.PARTIAL_FAILURE)
    }
}
