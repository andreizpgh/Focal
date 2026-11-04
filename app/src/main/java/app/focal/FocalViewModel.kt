package app.focal

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.focal.data.*
import app.focal.recording.*
import app.focal.speech.SpeechPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LibraryState(val notes: List<Note> = emptyList(), val note: Note? = null,
                        val passages: List<Passage> = emptyList(), val audioBytes: Long = 0,
                        val pending: Int = 0, val loaded: Boolean = false,
                        val selectedId: String? = null, val error: String? = null)

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
    private val modelImportLock = Mutex()
    val settings = app.preferences.settings
    val live = RecordingState.live
    val modelReady = MutableStateFlow(false)
    val preparing = MutableStateFlow(true)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 3)
    val player = SpeechPlayer(app, app.store, viewModelScope)
    val library: StateFlow<LibraryState> = libraryState.asStateFlow()
    private val initialPreparation = viewModelScope.async(Dispatchers.IO) {
        try { app.initialized.await(); modelReady.value = app.models.ready() }
        finally { preparing.value = false }
    }
    init {
        viewModelScope.launch(Dispatchers.IO) {
            combine(app.store.changes, selected, search.debounce(150), libraryRefresh) { _, id, query, _ -> id to query }
                .collect { (id, query) ->
                    try {
                        app.storageReady.await()
                        beforeLibraryReadForTests?.invoke()
                        libraryState.value = LibraryState(app.store.notes(query), id?.let(app.store::note), id?.let(app.store::passages).orEmpty(),
                            id?.let(app.store::audioBytes) ?: 0, id?.let(app.store::pendingCount) ?: 0, true, id)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val previous = libraryState.value
                        val sameSelection = previous.selectedId == id
                        libraryState.value = previous.copy(
                            note = previous.note.takeIf { sameSelection },
                            passages = if (sameSelection) previous.passages else emptyList(),
                            audioBytes = if (sameSelection) previous.audioBytes else 0,
                            pending = if (sameSelection) previous.pending else 0,
                            selectedId = id, error = if (e is PassageSizeException) e.message else "Could not load sessions. Try again when storage is available."
                        )
                    }
                }
        }
    }
    fun retryLibrary() {
        app.retryStorage()
        libraryRefresh.update { it + 1 }
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
        try { RecordingService.command(app, RecordingService.RECOVER, id) }
        catch (e: Exception) { errors.tryEmit(e.message ?: "Could not continue transcription.") }
    }
    suspend fun edit(passage: Passage, text: String): Boolean = withContext(Dispatchers.IO) {
        try { app.store.edit(passage, text); true }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            errors.emit(if (e is PassageSizeException) e.message ?: "This passage is too large. Your draft is still open."
                else "Could not save changes. Your draft is still open.")
            false
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
    fun importModel(uri: Uri, retryId: String? = null) = work {
        initialPreparation.await()
        modelImportLock.withLock {
            preparing.value = true
            try {
                val job = currentCoroutineContext()
                app.contentResolver.openInputStream(uri)?.use { input ->
                    app.models.install(input, checkCancelled = { job.ensureActive() })
                } ?: error("Could not read this file.")
                modelReady.value = app.models.ready()
                app.startupError.value = null
                if (retryId != null && modelReady.value) retry(retryId)
            } finally { preparing.value = false }
        }
    }
    fun play(passage: Passage) {
        if (live.value.noteId != null && !live.value.processing) {
            errors.tryEmit("Finish recording before playing audio.")
            return
        }
        player.play(passage) { errors.tryEmit(it) }
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
