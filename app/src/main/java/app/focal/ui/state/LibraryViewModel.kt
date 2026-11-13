package app.focal.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.focal.FocalApplication
import app.focal.R
import app.focal.data.Note
import app.focal.data.NoteState
import app.focal.data.SearchResult
import app.focal.data.SearchText
import app.focal.recording.LiveSession
import app.focal.recording.RecordingState
import app.focal.recording.SpeechAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** One-off library outcomes for the feedback pill. */
sealed interface LibraryEvent {
    /** These notes went to the trash together (one, or a selection): "Note deleted · Undo", "3 notes deleted · Undo". */
    data class Deleted(val noteIds: List<String>) : LibraryEvent {
        constructor(noteId: String) : this(listOf(noteId))
    }
    /** The audio of these notes is leaving (one, or a selection): "Audio removed · Undo", "Audio removed from 3 notes · Undo". */
    data class AudioRemoved(val noteIds: List<String>) : LibraryEvent {
        constructor(noteId: String) : this(listOf(noteId))
    }
    /** Undo came after the deletion or removal was committed: "Too late to undo". */
    data class TooLateToUndo(val noteId: String) : LibraryEvent
    data class Error(val message: String) : LibraryEvent
}

/**
 * The library and its search. The note list is read from the store only when the store changes; the live
 * recording, transcription progress and model state re-derive rows without a query (no per-second requery).
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FocalApplication
    private val store = app.store
    private val query = MutableStateFlow("")
    private val loadError = MutableStateFlow<String?>(null)
    private val refresh = MutableStateFlow(0)
    private val eventFlow = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LibraryEvent> = eventFlow.asSharedFlow()

    /** The service state that affects rows; elapsed time and meter ticks are not part of it. */
    private data class LiveRows(val noteId: String?, val capturing: Boolean, val paused: Boolean, val processing: Boolean,
                                val percent: Int?, val speech: SpeechAvailability)

    private val live: Flow<LiveRows> = RecordingState.live
        .map { LiveRows(it.noteId, it.capturing, it.paused, it.processing, it.progress?.let { p -> (p * 100).toInt() }, it.speech) }
        .distinctUntilChanged()

    private val modelState: Flow<ModelState> = app.modelStates()

    private val notes: Flow<List<Note>?> = combine(store.changes, refresh) { _, _ -> }.mapLatest {
        try {
            app.storageReady.await()
            store.library().also { loadError.value = null }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            loadError.value = app.getString(R.string.error_load_notes)
            null
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<LibraryUiState> = combine(notes, live, modelState, loadError, UndoableActions.audioRemovalsPending) {
            list, liveRows, model, error, removals ->
        if (list == null) return@combine LibraryUiState(loaded = true, error = error)
        val session = RecordingState.live.value
        val liveId = liveRows.noteId?.takeIf { liveRows.capturing }
        val zone = ZoneId.systemDefault()
        val days = list.asSequence().filter { it.id != liveId }.map { row(it, session, model, removals) }
            .groupBy { Instant.ofEpochMilli(it.created).atZone(zone).toLocalDate().toEpochDay() }
            .map { (day, rows) -> LibraryDay(day, rows) }
        LibraryUiState(loaded = true, error = error, days = days, liveNoteId = liveId, empty = list.isEmpty() && liveId == null)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    val search: StateFlow<SearchUiState> = combine(
        combine(query.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(), store.changes) { text, _ -> text }.mapLatest { text ->
            text to if (text.isBlank()) emptyList() else try { withContext(Dispatchers.IO) { store.search(text) } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                emptyList()
            }
        },
        live, modelState, query, UndoableActions.audioRemovalsPending,
    ) { (resultsQuery, results), _, model, typed, removals ->
        val session = RecordingState.live.value
        val terms = SearchText.terms(resultsQuery)
        SearchUiState(typed, resultsQuery, results.map { searchRow(it, terms, session, model, removals) }, searching = typed != resultsQuery)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(text: String) { query.value = text.take(MAX_QUERY_CHARS) }

    fun rename(noteId: String, title: String) = launch { store.rename(noteId, title) }

    /** Moves the note to the trash; emits [LibraryEvent.Deleted] for "Note deleted · Undo". */
    fun delete(noteId: String) = launch {
        if (UndoableActions.delete(app, noteId) { eventFlow.tryEmit(LibraryEvent.Error(it)) }) eventFlow.emit(LibraryEvent.Deleted(noteId))
    }

    /**
     * Moves the selected notes to the trash and emits one [LibraryEvent.Deleted] for those that went ("3 notes deleted
     * · Undo"). A note that cannot be deleted now stays. Its refusal is said only when nothing went: a second message
     * would replace "N notes deleted · Undo" at once, which commits the deletion and takes its Undo away; the count and
     * the note that is still in the list say the rest.
     */
    fun deleteAll(noteIds: List<String>) = launch {
        var refusal: String? = null
        val deleted = noteIds.filter { id -> UndoableActions.delete(app, id) { if (refusal == null) refusal = it } }
        if (deleted.isNotEmpty()) eventFlow.emit(LibraryEvent.Deleted(deleted))
        else refusal?.let { eventFlow.emit(LibraryEvent.Error(it)) }
    }

    /** Undo on "Note deleted"; emits [LibraryEvent.TooLateToUndo] if it was already deleted for good. */
    fun undoDelete(noteId: String) = undoDelete(listOf(noteId))

    /** Undo on "3 notes deleted": every one comes back; [LibraryEvent.TooLateToUndo] once if any was already gone. */
    fun undoDelete(noteIds: List<String>) = launch {
        val late = noteIds.filterNot { UndoableActions.undoDelete(app, it) }
        late.firstOrNull()?.let { eventFlow.emit(LibraryEvent.TooLateToUndo(it)) }
    }

    /** The "Note deleted" snackbar closed without Undo (`SnackbarResult.Dismissed`): delete it for good now. */
    fun commitDelete(noteId: String) = UndoableActions.commitDelete(app, noteId)

    fun commitDelete(noteIds: List<String>) = noteIds.forEach { UndoableActions.commitDelete(app, it) }

    /**
     * Schedules the audio removal; emits [LibraryEvent.AudioRemoved] for "Audio removed · Undo". Refused with
     * [LibraryEvent.Error] unless the row [NoteRow.canRemoveAudio] (ready, no failed part to retry).
     */
    fun removeAudio(noteId: String) = launch {
        if (UndoableActions.removeAudio(app, noteId) { eventFlow.tryEmit(LibraryEvent.Error(it)) })
            eventFlow.emit(LibraryEvent.AudioRemoved(noteId))
    }

    /**
     * Schedules the removal of the selected notes' audio (their text stays) and emits one [LibraryEvent.AudioRemoved]
     * for those it was scheduled for. A note whose audio cannot go now (it has none, or a part of it still waits to be
     * transcribed) keeps it; its refusal is said only when nothing was scheduled, as for [deleteAll].
     */
    fun removeAudioAll(noteIds: List<String>) = launch {
        var refusal: String? = null
        val removed = noteIds.filter { id -> UndoableActions.removeAudio(app, id) { if (refusal == null) refusal = it } }
        if (removed.isNotEmpty()) eventFlow.emit(LibraryEvent.AudioRemoved(removed))
        else refusal?.let { eventFlow.emit(LibraryEvent.Error(it)) }
    }

    /** Undo on "Audio removed"; emits [LibraryEvent.TooLateToUndo] if the audio was already deleted. */
    fun undoRemoveAudio(noteId: String) = undoRemoveAudio(listOf(noteId))

    /** Undo for a selection: every note keeps its audio; [LibraryEvent.TooLateToUndo] once if any was already gone. */
    fun undoRemoveAudio(noteIds: List<String>) {
        val late = noteIds.filterNot { UndoableActions.undoRemoveAudio(it) }
        late.firstOrNull()?.let { eventFlow.tryEmit(LibraryEvent.TooLateToUndo(it)) }
    }

    /** The "Audio removed" snackbar closed without Undo (`SnackbarResult.Dismissed`): delete the audio now. */
    fun commitRemoveAudio(noteId: String) = commitRemoveAudio(listOf(noteId))

    fun commitRemoveAudio(noteIds: List<String>) = noteIds.forEach { id ->
        UndoableActions.commitRemoveAudio(app, id) { eventFlow.tryEmit(LibraryEvent.Error(it)) }
    }

    /** The bytes the audio of these notes takes together, for "Remove audio · 42 MB". */
    suspend fun audioBytes(noteIds: List<String>): Long = withContext(Dispatchers.IO) { noteIds.sumOf { store.audioBytes(it) } }

    /** The bytes the note's audio takes, for the menu's "Remove audio · 9 MB" (0 when it has none). */
    suspend fun audioBytes(noteId: String): Long = withContext(Dispatchers.IO) { store.audioBytes(noteId) }

    /** The note's body as Copy puts it (visible paragraphs joined by a blank line; no title). */
    suspend fun copyText(noteId: String): String = withContext(Dispatchers.IO) { store.transcript(noteId) }

    /** Retries a failed recovery of storage at startup. */
    fun retryLoad() { app.retryStorage(); refresh.value++ }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            eventFlow.emit(LibraryEvent.Error(app.getString(R.string.error_notes)))
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 120L
        private const val MAX_QUERY_CHARS = 200

        internal fun row(
            note: Note, live: LiveSession, model: ModelState, removals: Set<String> = emptySet(),
        ): NoteRow {
            val phase = NotePhases.of(note, live, model.availability)
            // A removal waiting for its Undo snackbar already shows the note as text only.
            val audio = note.audioPresent && note.id !in removals
            return NoteRow(
                id = note.id, title = note.title, titleIsCustom = note.titleIsCustom, created = note.created,
                durationMs = note.duration, preview = note.preview, phase = phase,
                progress = live.progress.takeIf { live.noteId == note.id && phase == NotePhase.TRANSCRIBING },
                audioPresent = audio, failedParts = note.failedWindows,
                canRemoveAudio = audio && note.state == NoteState.READY && phase == NotePhase.READY,
                waitingReason = waitingReason(note, phase, model),
                canAppend = audio && !note.legacy && note.state == NoteState.READY &&
                    (phase == NotePhase.READY || phase == NotePhase.PARTIAL_FAILURE),
            )
        }

        /** A result's row; every word of its snippet that the query's [terms] match is a hit (spec 5.2). */
        private fun searchRow(result: SearchResult, terms: List<String>, live: LiveSession, model: ModelState, removals: Set<String>) = SearchRow(
            row = row(result.note, live, model, removals), paragraphId = result.paragraphId,
            hitStart = result.hitStart, hitEnd = result.hitEnd, snippet = result.snippet,
            snippetHits = if (result.snippetHitStart < 0) emptyList() else SearchText.hits(result.snippet, terms),
        )
    }
}
