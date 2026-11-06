package app.focal.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.focal.FocalApplication
import app.focal.R
import app.focal.data.EditResult
import app.focal.data.NoteState
import app.focal.data.ReplaceUndo
import app.focal.data.TextMatch
import app.focal.data.WindowState
import app.focal.recording.AppPresence
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import app.focal.recording.SpeechAvailability
import app.focal.speech.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The result of an edit, rename, split, merge or restore. On anything but [Saved] keep the user's draft. */
sealed interface EditOutcome {
    /**
     * The change is stored. [paragraphs] are the affected paragraphs as now stored, with their new
     * revisions: after a split the kept part and the new one (the new one's id is what undo merges; it may
     * be empty and so not yet in [NoteUiState.paragraphs]), after a merge the joined paragraph. Session
     * undo takes ids and revisions from here instead of waiting for the state to reload.
     */
    data class Saved(val paragraphs: List<ParagraphUi>) : EditOutcome
    /** The paragraph changed since it was read (or is gone): reload; the draft is not saved. */
    data object Stale : EditOutcome
    /** The note is recording or transcribing. */
    data object NotEditable : EditOutcome
    data object TooLarge : EditOutcome
    /** The paragraph has no recognizer text to restore. */
    data object NoOriginal : EditOutcome
    /** Storage failed: show "Not saved" and keep the draft. */
    data class Failed(val message: String) : EditOutcome
}

/** One-off outcomes of a note screen for the feedback pill. */
sealed interface NoteEvent {
    data object Deleted : NoteEvent
    data object AudioRemoved : NoteEvent
    /** Undo came after the deletion or removal was committed: "Too late to undo". */
    data object TooLateToUndo : NoteEvent
    data class Replaced(val count: Int) : NoteEvent
    data class Error(val message: String) : NoteEvent
}

/**
 * One note: its paragraphs with word times and doubt flags, its state and progress, and every edit. Create it
 * with [factory] keyed by the note id. Reads happen when the store changes; transcription progress of the
 * note being processed also re-derives the state without a query.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewModel(application: Application, val noteId: String) : AndroidViewModel(application) {
    private val app = application as FocalApplication
    private val store = app.store
    private val eventFlow = MutableSharedFlow<NoteEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<NoteEvent> = eventFlow.asSharedFlow()
    private val refresh = MutableStateFlow(0)
    private var lastReplace: ReplaceUndo? = null
    /** Paragraph conversions by id and revision: a commit re-aligns only the paragraphs it changed. */
    private val converted = HashMap<Long, ParagraphUi>()

    private data class LiveKey(val noteId: String?, val capturing: Boolean, val paused: Boolean, val processing: Boolean,
                               val percent: Int?, val speech: SpeechAvailability)

    private val live: Flow<LiveKey> = RecordingState.live.map {
        LiveKey(it.noteId, it.capturing, it.paused, it.processing, it.progress?.let { p -> (p * 100).toInt() }, it.speech)
    }.distinctUntilChanged()

    private val speech: Flow<SpeechAvailability> = app.models.status.map {
        when (it) {
            ModelStatus.Checking, is ModelStatus.Installing -> SpeechAvailability.PREPARING
            is ModelStatus.Ready -> SpeechAvailability.READY
            else -> SpeechAvailability.UNAVAILABLE
        }
    }.distinctUntilChanged()

    private data class Loaded(val note: app.focal.data.Note?, val paragraphs: List<ParagraphUi>, val gaps: List<GapUi>,
                              val remainingMs: Long, val error: String?)

    private val loaded: Flow<Loaded> = combine(store.changes, refresh) { _, _ -> }.mapLatest {
        try {
            app.storageReady.await()
            val note = store.note(noteId) ?: return@mapLatest Loaded(null, emptyList(), emptyList(), 0, null)
            val all = store.paragraphs(noteId)
            val visible = all.filter { it.visible }
            val paragraphs = synchronized(converted) {
                converted.keys.retainAll(visible.mapTo(HashSet()) { it.id })
                visible.map { p -> converted[p.id]?.takeIf { it.revision == p.revision } ?: ParagraphUi.of(p).also { converted[p.id] = it } }
            }
            fun after(startMs: Long) = visible.lastOrNull { it.startMs < startMs }?.id
            // Failed windows, and ranges of a migrated note that were never transcribed (its coverage map).
            val gaps = (store.windows(noteId).filter { it.state == WindowState.FAILED }
                .map { GapUi(it.ownStartMs, it.ownEndMs, after(it.ownStartMs), failed = true) } +
                store.gaps(noteId).map { GapUi(it.startMs, it.endMs, after(it.startMs), failed = false) })
                .sortedBy { it.startMs }
            Loaded(note, paragraphs, gaps, store.progress(noteId).remainingMs, null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Loaded(null, emptyList(), emptyList(), 0, app.getString(R.string.error_load_note))
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<NoteUiState> = combine(loaded, live, speech, UndoableActions.audioRemovalsPending) { data, liveKey, model, removals ->
        val note = data.note
        if (note == null) return@combine NoteUiState(noteId, loaded = true, missing = data.error == null, error = data.error)
        val session = RecordingState.live.value
        val phase = NotePhases.of(note, session, model)
        val progress = when {
            phase == NotePhase.READY || phase == NotePhase.PARTIAL_FAILURE -> null
            liveKey.noteId == noteId && liveKey.processing -> session.progress
            note.duration > 0 -> (1f - data.remainingMs.toFloat() / note.duration).coerceIn(0f, 1f)
            else -> null
        }
        val audio = note.audioPresent && noteId !in removals
        NoteUiState(
            noteId = noteId, loaded = true, title = note.title, titleIsCustom = note.titleIsCustom, created = note.created,
            durationMs = note.duration, phase = phase, progress = progress, speech = if (liveKey.noteId == noteId) liveKey.speech else model,
            waitingReason = note.error.takeIf { phase == NotePhase.WAITING && it.isNotBlank() },
            audioPresent = audio,
            canPlay = audio && !liveKey.capturing,
            editable = note.state == NoteState.READY && (phase == NotePhase.READY || phase == NotePhase.PARTIAL_FAILURE),
            canRemoveAudio = audio && note.state == NoteState.READY && phase == NotePhase.READY,
            canRetry = note.audioPresent && (phase == NotePhase.PARTIAL_FAILURE || phase == NotePhase.WAITING),
            paragraphs = data.paragraphs, gaps = data.gaps, failedParts = note.failedWindows,
            endReason = EndReason.of(note.endReason),
            noSpeech = note.state == NoteState.READY && data.paragraphs.isEmpty(),
            legacy = note.legacy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteUiState(noteId))

    /** Call with true while the note is on screen, false when it leaves: "Note ready" is not posted for it. */
    fun setShown(shown: Boolean) {
        if (shown) AppPresence.shownNote.value = noteId
        else AppPresence.shownNote.compareAndSet(noteId, null)
    }

    /** A blank title returns to the automatic one. */
    fun rename(title: String) = launch { store.rename(noteId, title) }

    suspend fun editParagraph(paragraphId: Long, revision: Long, text: String): EditOutcome =
        edit { store.editParagraph(paragraphId, revision, text) }

    /** Enter at [charOffset]: the paragraph splits there; each half keeps its words' times. */
    suspend fun split(paragraphId: Long, revision: Long, charOffset: Int): EditOutcome =
        edit { store.splitParagraph(paragraphId, revision, charOffset) }

    /** Backspace at the start of [paragraphId]: it joins the visible paragraph before it. */
    suspend fun mergeWithPrevious(paragraphId: Long, revision: Long): EditOutcome {
        val paragraphs = state.value.paragraphs
        val index = paragraphs.indexOfFirst { it.id == paragraphId }
        if (index <= 0) return EditOutcome.Stale
        val previous = paragraphs[index - 1]
        return edit { store.mergeParagraphs(previous.id, previous.revision, paragraphId, revision) }
    }

    /**
     * Joins [secondId] into [firstId], the paragraph the editor shows before it. Unlike [mergeWithPrevious] the
     * editor names both, so a new paragraph that is still empty (and therefore not in [NoteUiState.paragraphs]) can
     * be joined, and a split can be undone by id.
     */
    suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditOutcome =
        edit { store.mergeParagraphs(firstId, firstRevision, secondId, secondRevision) }

    /** Puts back the recognizer's text of an edited paragraph. */
    suspend fun restoreOriginal(paragraphId: Long, revision: Long): EditOutcome =
        edit { store.restoreOriginal(paragraphId, revision) }

    /** Matches of [query] (case, accents and ё/е ignored), in reading order. */
    suspend fun find(query: String): List<TextMatch> =
        if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) { store.findInNote(noteId, query) }

    /** Replaces every match verbatim in one undoable step; emits [NoteEvent.Replaced]. */
    suspend fun replaceAll(query: String, replacement: String): EditOutcome = withContext(Dispatchers.IO) {
        try {
            val result = store.replaceAll(noteId, query, replacement)
            lastReplace = result.undo
            if (result.count > 0) eventFlow.emit(NoteEvent.Replaced(result.count))
            outcome(result.outcome)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            EditOutcome.Failed(app.getString(R.string.error_replace))
        }
    }

    /** Undoes the last Replace all, while none of its paragraphs changed again. */
    suspend fun undoReplace(): EditOutcome {
        val undo = lastReplace ?: return EditOutcome.Stale
        return edit { store.undoReplace(undo) }.also { if (it is EditOutcome.Saved) lastReplace = null }
    }

    /** The body as Copy puts it: visible paragraphs joined by a blank line (no title, no doubt marks). */
    suspend fun copyText(): String = withContext(Dispatchers.IO) { store.transcript(noteId) }

    /** "Retry" (failed parts) and "Try again" (the model could not load): transcribes what is missing. */
    fun retry() {
        if (!state.value.canRetry || SessionOperations.matchesSession(capture = false, noteId = noteId)) return
        try { RecordingService.command(app, RecordingService.RECOVER, noteId, retryFailed = true) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            eventFlow.tryEmit(NoteEvent.Error(RecordingService.refusal(error) ?: app.getString(R.string.error_retry)))
        }
    }

    fun delete() = launch {
        if (UndoableActions.delete(app, noteId) { eventFlow.tryEmit(NoteEvent.Error(it)) }) eventFlow.emit(NoteEvent.Deleted)
    }

    /** Undo on "Note deleted"; emits [NoteEvent.TooLateToUndo] if it was already deleted for good. */
    fun undoDelete() = launch { if (!UndoableActions.undoDelete(app, noteId)) eventFlow.emit(NoteEvent.TooLateToUndo) }

    /** The "Note deleted" snackbar closed without Undo (`SnackbarResult.Dismissed`): delete it for good now. */
    fun commitDelete() = UndoableActions.commitDelete(app, noteId)

    /**
     * Schedules the audio removal (the text stays); emits [NoteEvent.AudioRemoved] for "Audio removed · Undo".
     * Only when [NoteUiState.canRemoveAudio]: never while transcribing or with a failed part to retry.
     */
    fun removeAudio() = launch {
        if (UndoableActions.removeAudio(app, noteId) { eventFlow.tryEmit(NoteEvent.Error(it)) }) eventFlow.emit(NoteEvent.AudioRemoved)
    }

    /** Undo on "Audio removed"; emits [NoteEvent.TooLateToUndo] if the audio was already deleted. */
    fun undoRemoveAudio() { if (!UndoableActions.undoRemoveAudio(noteId)) eventFlow.tryEmit(NoteEvent.TooLateToUndo) }

    /** The "Audio removed" snackbar closed without Undo (`SnackbarResult.Dismissed`): delete the audio now. */
    fun commitRemoveAudio() = UndoableActions.commitRemoveAudio(app, noteId) { eventFlow.tryEmit(NoteEvent.Error(it)) }

    /** Re-reads the note after a read failure. */
    fun reload() { refresh.value++ }

    override fun onCleared() { setShown(false) }

    private suspend fun edit(block: () -> EditResult): EditOutcome = withContext(Dispatchers.IO) {
        try { outcome(block()) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            EditOutcome.Failed(app.getString(R.string.error_save))
        }
    }

    private fun outcome(result: EditResult): EditOutcome = when (result) {
        is EditResult.Saved -> EditOutcome.Saved(result.paragraphs.map(ParagraphUi::of))
        EditResult.Conflict, EditResult.Missing -> EditOutcome.Stale
        EditResult.NotEditable -> EditOutcome.NotEditable
        EditResult.TooLarge -> EditOutcome.TooLarge
        EditResult.NoOriginal -> EditOutcome.NoOriginal
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            eventFlow.emit(NoteEvent.Error(app.getString(R.string.error_note)))
        }
    }

    companion object {
        /** `viewModel(key = noteId, factory = NoteViewModel.factory(noteId))`. */
        fun factory(noteId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { NoteViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]), noteId) }
        }
    }
}
