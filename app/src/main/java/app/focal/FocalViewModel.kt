package app.focal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.focal.data.*
import app.focal.recording.*
import app.focal.speech.SpeechPlayer
import app.focal.speech.UltraModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** [notes] are search results (preview = snippet) while a query is entered. */
data class LibraryState(val notes: List<Note> = emptyList(), val note: Note? = null,
                        val paragraphs: List<Paragraph> = emptyList(), val audioBytes: Long = 0,
                        val pending: Int = 0, val loaded: Boolean = false,
                        val selectedId: String? = null, val error: String? = null,
                        val hasUntranscribedAudio: Boolean = false, internal val storeRevision: Long = 0)

@OptIn(FlowPreview::class)
class FocalViewModel internal constructor(
    application: Application,
    private val beforeLibraryReadForTests: (() -> Unit)?,
    private val deleteForTests: ((String) -> Unit)?,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null, null)
    val app = application as FocalApplication
    private val selected = MutableStateFlow<String?>(null)
    private val search = MutableStateFlow("")
    private val libraryRefresh = MutableStateFlow(0L)
    private val libraryState = MutableStateFlow(LibraryState())
    val settings = app.preferences.settings
    val live = RecordingState.live
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 3)
    val player = SpeechPlayer(app, app.store, viewModelScope)
    /** False only for checks-only debug builds made without the model (see scripts/fetch-model.py). */
    val speechModelBundled = UltraModel.isBundled(app)
    val library: StateFlow<LibraryState> = libraryState.asStateFlow()
    init {
        viewModelScope.launch {
            combine(app.store.changes, selected, search.debounce(150), libraryRefresh) { revision, id, query, _ ->
                Triple(revision, id, query)
            }.collect { (revision, id, query) ->
                val next = try {
                    withContext(Dispatchers.IO) {
                        app.storageReady.await()
                        beforeLibraryReadForTests?.invoke()
                        val note = id?.let(app.store::note)
                        val notes = if (query.isBlank()) app.store.library()
                            else app.store.search(query).map { it.note.copy(preview = it.snippet) }
                        LibraryState(notes, note, id?.let(app.store::paragraphs).orEmpty(),
                            id?.let(app.store::audioBytes) ?: 0, id?.let(app.store::pendingCount) ?: 0, true, id,
                            hasUntranscribedAudio = note?.let(app.store::hasUntranscribedAudio) == true)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val previous = libraryState.value
                    val sameSelection = previous.selectedId == id
                    previous.copy(
                        note = previous.note.takeIf { sameSelection },
                        paragraphs = if (sameSelection) previous.paragraphs else emptyList(),
                        audioBytes = if (sameSelection) previous.audioBytes else 0,
                        pending = if (sameSelection) previous.pending else 0,
                        hasUntranscribedAudio = sameSelection && previous.hasUntranscribedAudio,
                        selectedId = id, error = if (e is PassageSizeException) e.message else "Could not load sessions. Try again when storage is available."
                    )
                }
                libraryState.value = next.copy(storeRevision = revision)
            }
        }
    }
    fun retryLibrary() {
        app.retryStorage()
        libraryRefresh.update { it + 1 }
    }
    internal fun latestTransferSnapshot(noteId: String): LibraryState? = libraryState.value.takeIf {
        selected.value == noteId && it.loaded && it.selectedId == noteId && it.note?.id == noteId &&
            it.error == null && it.storeRevision == app.store.changes.value
    }

    fun select(id: String?) { if (selected.value != id) { player.stop(); selected.value = id } }
    fun search(query: String) { search.value = query.take(200) }
    fun record() {
        player.stop()
        if (!library.value.loaded || library.value.error != null || live.value.noteId != null) return
        try { RecordingService.command(app, RecordingService.START) }
        catch (e: Exception) { errors.tryEmit(e.message ?: "Could not start recording.") }
    }
    fun pause() { RecordingService.command(app, if (live.value.paused) RecordingService.RESUME else RecordingService.PAUSE) }
    fun finish() { RecordingService.command(app, RecordingService.STOP) }
    fun retry(id: String) {
        if (live.value.noteId != null || SessionOperations.matchesSession(capture = false, noteId = id)) {
            errors.tryEmit("Finish the current recording or transcription first.")
            return
        }
        player.stop()
        try { RecordingService.command(app, RecordingService.RECOVER, id, retryFailed = true) }
        catch (e: Exception) { errors.tryEmit(e.message ?: "Could not continue transcription.") }
    }
    suspend fun edit(paragraph: Paragraph, text: String): Boolean = withContext(Dispatchers.IO) {
        val message = try {
            when (app.store.editParagraph(paragraph.id, paragraph.revision, text)) {
                is EditResult.Saved -> return@withContext true
                EditResult.TooLarge -> "This passage is too long to save. Shorten it and try again; your draft is still open."
                EditResult.NotEditable -> "Edit this note when its transcription is finished. Your draft is still open."
                EditResult.Conflict, EditResult.Missing, EditResult.NoOriginal ->
                    "This passage changed. Your draft has not been saved."
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Could not save changes. Your draft is still open."
        }
        errors.emit(message)
        false
    }
    /** Shows a hidden legacy paragraph again, or puts back the recognizer's text of an edited one. */
    internal suspend fun restorePassage(paragraph: Paragraph): Boolean = viewModelScope.async(Dispatchers.Main.immediate) {
        val restored = withContext(Dispatchers.IO) {
            try {
                if (paragraph.hidden) app.store.unhideParagraph(paragraph.id)
                else app.store.restoreOriginal(paragraph.id, paragraph.revision) is EditResult.Saved
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }.also { if (!it) errors.emit("Could not restore this passage. Try again.") }
        }
        if (restored) awaitPassageRefresh(paragraph.noteId)
        restored
    }.await()

    // A committed restore can outlive its editor. Wait for the matching Main
    // snapshot before reporting success, or block transfer when the read fails.
    private suspend fun awaitPassageRefresh(noteId: String) {
        if (selected.value != noteId) return
        val revision = app.store.changes.value
        val published = withTimeoutOrNull(10_000) {
            combine(selected, libraryState) { selection, snapshot ->
                selection != noteId || (snapshot.selectedId == noteId && snapshot.storeRevision >= revision)
            }.first { it }
        }
        if (selected.value != noteId) return
        if (published == null) libraryState.update { snapshot ->
            if (snapshot.selectedId == noteId && snapshot.storeRevision < revision && snapshot.error == null) {
                snapshot.copy(error = "Could not refresh this recording. Try again.")
            } else snapshot
        }
        if (published == null || libraryState.value.error != null) {
            errors.tryEmit("Your change was saved. Reload this recording to see it.")
        }
    }
    fun rename(id: String, title: String) = work { app.store.rename(id, title) }
    suspend fun renameSession(id: String, title: String): Boolean = withContext(Dispatchers.IO) {
        try { app.store.rename(id, title); true }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            errors.emit("Could not save the title. Your draft is still open.")
            false
        }
    }
    fun delete(id: String) = destructive(id) {
        check(live.value.noteId != id) { "Finish this recording or transcription before deleting it." }
        player.stopNote(id)
        (deleteForTests ?: app.store::delete)(id)
        selected.compareAndSet(id, null)
    }
    fun removeAudio(id: String) = destructive(id) {
        check(live.value.noteId != id && app.store.pendingCount(id) == 0) { "Finish transcription before removing its source audio." }
        player.stopNote(id); app.store.removeAudio(id)
    }
    fun play(paragraph: Paragraph) {
        if (live.value.noteId != null && !live.value.processing) {
            errors.tryEmit("Finish recording before playing audio.")
            return
        }
        player.play(paragraph) { errors.tryEmit(it) }
    }
    fun playNote(note: Note) {
        if (live.value.noteId != null && !live.value.processing) return
        player.playNote(note) { errors.tryEmit(it) }
    }
    fun seekNote(note: Note, positionMs: Long) {
        if (live.value.noteId != null && !live.value.processing) return
        player.seekTo(note, positionMs) { errors.tryEmit(it) }
    }
    private fun destructive(id: String, block: suspend () -> Unit): Job {
        val claim = try { SessionOperations.tryDestructive(id) }
        catch (e: Exception) { return work { throw e } }
        if (claim == null) return work { errors.emit("This session is busy. Finish its current operation before changing it.") }
        return work {
            try { block() } finally { claim.release() }
        }.also { job ->
            // A ViewModel may be cleared before its IO coroutine starts.
            job.invokeOnCompletion { claim.release() }
        }
    }
    private fun work(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: Exception) {
            if (e is CancellationException) throw e
            errors.emit(e.message ?: "Something went wrong. Your saved notes are still on this device.")
        }
    }
    override fun onCleared() { player.stop() }
}
