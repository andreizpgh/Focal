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
import app.focal.data.SplitOrigin
import app.focal.data.TextMatch
import app.focal.data.WindowState
import app.focal.recording.AppPresence
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.SessionOperations
import app.focal.recording.SpeechAvailability
import app.focal.ui.note.EditBackend
import app.focal.ui.note.EditSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
     * revisions: after a split the new part before the split and then the paragraph that kept its id with
     * the rest (either may be empty and so not in [NoteUiState.paragraphs]), with the paragraph's [origin]
     * for undoing it; after a merge the joined paragraph. Session undo takes ids and revisions from here
     * instead of waiting for the state to reload.
     */
    data class Saved(val paragraphs: List<ParagraphUi>, val origin: SplitOrigin? = null) : EditOutcome
    /** The paragraph changed since it was read (or is gone): reload; the draft is not saved. */
    data object Stale : EditOutcome
    /** The note is recording or transcribing. */
    data object NotEditable : EditOutcome
    data object TooLarge : EditOutcome
    /** The paragraph has no recognizer text to restore. */
    data object NoOriginal : EditOutcome
    /** Merge refused: joining would lose a recognizer original. Both paragraphs stay; nothing failed. */
    data object CannotMerge : EditOutcome
    /** Storage failed: show "Not saved" and keep the draft. */
    data class Failed(val message: String) : EditOutcome
}

/** One-off outcomes of a note screen for the feedback pill. */
sealed interface NoteEvent {
    data object Deleted : NoteEvent
    data object AudioRemoved : NoteEvent
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

    /**
     * The note page's Edit writes, in order, in this model's scope: a sequence running when the page goes still
     * finishes, and a recreated page's writes wait for its predecessor's. Main, not Main.immediate: a write asked for
     * from a text field's input handling starts after it.
     */
    val writes = WriteQueue(CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Main))

    /** The note's paragraphs as Edit writes them. */
    val editBackend: EditBackend = object : EditBackend {
        override suspend fun edit(id: Long, revision: Long, text: String): EditOutcome = editParagraph(id, revision, text)
        override suspend fun split(id: Long, revision: Long, offset: Int): EditOutcome = this@NoteViewModel.split(id, revision, offset)
        override suspend fun unsplit(keepId: Long, keepRevision: Long, removeId: Long, removeRevision: Long, offset: Int, origin: SplitOrigin): EditOutcome =
            this@NoteViewModel.unsplit(keepId, keepRevision, removeId, removeRevision, offset, origin)
        override suspend fun merge(firstId: Long, firstRevision: Long, secondId: Long, secondRevision: Long): EditOutcome =
            this@NoteViewModel.merge(firstId, firstRevision, secondId, secondRevision)
        override suspend fun restore(id: Long, revision: Long): EditOutcome = restoreOriginal(id, revision)
    }

    /**
     * The note page's Edit session (spec 5.7), here so it outlives the page: a page recreated by rotation, or the note
     * opened again beside the library, goes on with what was shown and not stored yet, its undo log and its field.
     */
    val editSession = EditSession(editBackend)
    /** Paragraph conversions by id and revision: a commit re-aligns only the paragraphs it changed. */
    private val converted = HashMap<Long, ParagraphUi>()

    private data class LiveKey(val noteId: String?, val capturing: Boolean, val paused: Boolean, val processing: Boolean,
                               val percent: Int?, val speech: SpeechAvailability)

    private val live: Flow<LiveKey> = RecordingState.live.map {
        LiveKey(it.noteId, it.capturing, it.paused, it.processing, it.progress?.let { p -> (p * 100).toInt() }, it.speech)
    }.distinctUntilChanged()

    private val modelState: Flow<ModelState> = app.modelStates()

    private data class Loaded(val note: app.focal.data.Note?, val paragraphs: List<ParagraphUi>, val blank: List<BlankParagraph>,
                              val gaps: List<GapUi>, val remainingMs: Long, val error: String?)

    private val loaded: Flow<Loaded> = combine(store.changes, refresh) { _, _ -> }.mapLatest {
        try {
            app.storageReady.await()
            val note = store.note(noteId) ?: return@mapLatest Loaded(null, emptyList(), emptyList(), emptyList(), 0, null)
            val all = store.paragraphs(noteId)
            val visible = all.filter { it.visible }
            // Paragraphs with no text yet (Enter at the end of one), each after the one before it.
            val shown = all.filter { !it.hidden }
            val blank = shown.withIndex().filter { it.value.text.isBlank() }
                .map { (index, paragraph) -> BlankParagraph(ParagraphUi.of(paragraph), shown.getOrNull(index - 1)?.id) }
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
            Loaded(note, paragraphs, blank, gaps, store.progress(noteId).remainingMs, null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Loaded(null, emptyList(), emptyList(), emptyList(), 0, app.getString(R.string.error_load_note))
        }
    }.flowOn(Dispatchers.IO)

    val state: StateFlow<NoteUiState> = combine(loaded, live, modelState, UndoableActions.audioRemovalsPending) { data, liveKey, model, removals ->
        val note = data.note
        if (note == null) return@combine NoteUiState(noteId, loaded = true, missing = data.error == null, error = data.error)
        val session = RecordingState.live.value
        val phase = NotePhases.of(note, session, model.availability)
        val progress = when {
            phase == NotePhase.READY || phase == NotePhase.PARTIAL_FAILURE -> null
            liveKey.noteId == noteId && liveKey.processing -> session.progress
            note.duration > 0 -> (1f - data.remainingMs.toFloat() / note.duration).coerceIn(0f, 1f)
            else -> null
        }
        val audio = note.audioPresent && noteId !in removals
        NoteUiState(
            noteId = noteId, loaded = true, title = note.title, titleIsCustom = note.titleIsCustom, created = note.created,
            durationMs = note.duration, phase = phase, progress = progress, speech = if (liveKey.noteId == noteId) liveKey.speech else model.availability,
            waitingReason = waitingReason(note, phase, model),
            audioPresent = audio,
            canPlay = audio && !liveKey.capturing,
            editable = note.state == NoteState.READY && (phase == NotePhase.READY || phase == NotePhase.PARTIAL_FAILURE),
            canRemoveAudio = audio && note.state == NoteState.READY && phase == NotePhase.READY,
            canRetry = note.audioPresent && (phase == NotePhase.PARTIAL_FAILURE || phase == NotePhase.WAITING),
            // More can be recorded into a ready note that still has its audio (the new audio goes on in the same file),
            // while nothing records and its own audio is not about to be removed.
            canAppend = audio && !note.legacy && note.state == NoteState.READY && !liveKey.capturing &&
                (phase == NotePhase.READY || phase == NotePhase.PARTIAL_FAILURE),
            paragraphs = data.paragraphs, blankParagraphs = data.blank, gaps = data.gaps, failedParts = note.failedWindows,
            endReason = EndReason.of(note.endReason),
            noSpeech = note.state == NoteState.READY && data.paragraphs.isEmpty(),
            legacy = note.legacy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteUiState(noteId))

    /**
     * Whether the store has the note now. [state] keeps its last value while nothing collects it, and the view model
     * outlives its page, so a page opened again starts from the state it was left in: a note deleted from its page and
     * restored since still reads as missing until the store is read again.
     */
    suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        app.storageReady.await()
        store.note(noteId) != null
    }

    /** Call with true while the note is on screen, false when it leaves: "Note ready" is not posted for it. */
    fun setShown(shown: Boolean) {
        if (shown) AppPresence.shownNote.value = noteId
        else AppPresence.shownNote.compareAndSet(noteId, null)
    }

    /** A blank title returns to the automatic one. */
    fun rename(title: String) = launch { store.rename(noteId, title) }

    suspend fun editParagraph(paragraphId: Long, revision: Long, text: String): EditOutcome =
        edit { store.editParagraph(paragraphId, revision, text) }

    /**
     * Enter at [charOffset]: the text before it becomes a new paragraph before this one, which keeps the rest; each
     * part keeps its words' times.
     */
    suspend fun split(paragraphId: Long, revision: Long, charOffset: Int): EditOutcome =
        edit { store.splitParagraph(paragraphId, revision, charOffset) }

    /** Undoes a split exactly: [keepId] takes back [origin] and [removeId], the part before it, goes. */
    suspend fun unsplit(keepId: Long, keepRevision: Long, removeId: Long, removeRevision: Long, offset: Int, origin: SplitOrigin): EditOutcome =
        edit { store.unsplitParagraph(keepId, keepRevision, removeId, removeRevision, offset, origin) }

    /**
     * Backspace at the start of [secondId]: [firstId], the paragraph the editor shows before it, joins it, and
     * [secondId] keeps the joined text. The editor names both, so a new paragraph that is still empty (and therefore
     * not in [NoteUiState.paragraphs]) can be joined.
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

    /**
     * Schedules the audio removal (the text stays); emits [NoteEvent.AudioRemoved] for "Audio removed · Undo".
     * Only when [NoteUiState.canRemoveAudio]: never while transcribing or with a failed part to retry.
     */
    fun removeAudio() = launch {
        if (UndoableActions.removeAudio(app, noteId) { eventFlow.tryEmit(NoteEvent.Error(it)) }) eventFlow.emit(NoteEvent.AudioRemoved)
    }

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
        is EditResult.Saved -> EditOutcome.Saved(result.paragraphs.map(ParagraphUi::of), result.origin)
        EditResult.Conflict, EditResult.Missing -> EditOutcome.Stale
        EditResult.NotEditable -> EditOutcome.NotEditable
        EditResult.TooLarge -> EditOutcome.TooLarge
        EditResult.NoOriginal -> EditOutcome.NoOriginal
        EditResult.CannotMerge -> EditOutcome.CannotMerge
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
