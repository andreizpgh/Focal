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

data class LibraryState(val notes: List<Note> = emptyList(), val note: Note? = null,
                        val passages: List<Passage> = emptyList(), val audioBytes: Long = 0,
                        val pending: Int = 0, val loaded: Boolean = false)

class FocalViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as FocalApplication
    private val selected = MutableStateFlow<String?>(null)
    val settings = app.preferences.settings
    val live = RecordingState.live
    val modelReady = MutableStateFlow(false)
    val preparing = MutableStateFlow(true)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 3)
    val player = SpeechPlayer(app, app.store, viewModelScope)
    val library = combine(app.store.changes, selected) { _, id ->
        app.initialized.await()
        LibraryState(app.store.notes(), id?.let(app.store::note), id?.let(app.store::passages).orEmpty(),
            id?.let(app.store::audioBytes) ?: 0, id?.let { app.store.chunks(it).count { c -> c.state != "DONE" } } ?: 0, true)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryState())
    init {
        work {
            try { app.initialized.await(); modelReady.value = app.models.ready() }
            finally { preparing.value = false }
        }
    }
    fun select(id: String?) { player.stop(); selected.value = id }
    fun record() {
        player.stop()
        if (preparing.value || live.value.noteId != null) return
        try { RecordingService.command(app, RecordingService.START) }
        catch (e: Exception) { errors.tryEmit(e.message ?: "Could not start recording.") }
    }
    fun pause() { RecordingService.command(app, RecordingService.PAUSE) }
    fun finish() { RecordingService.command(app, RecordingService.STOP) }
    fun retry(id: String) {
        player.stop()
        try { RecordingService.command(app, RecordingService.RECOVER, id) }
        catch (e: Exception) { errors.tryEmit(e.message ?: "Could not continue transcription.") }
    }
    fun edit(passage: Passage, text: String) = work { app.store.edit(passage, text) }
    fun rename(id: String, title: String) = work { app.store.rename(id, title) }
    fun delete(id: String) = work {
        if (live.value.noteId == id) return@work
        player.stop(); app.store.delete(id); selected.value = null
    }
    fun removeAudio(id: String) = work {
        if (live.value.noteId == id || app.store.chunks(id).any { it.state != "DONE" }) return@work
        player.stop(); app.store.removeAudio(id)
    }
    fun importModel(uri: Uri) = work {
        preparing.value = true
        try {
            app.contentResolver.openInputStream(uri)?.use { app.models.install(it) } ?: error("Could not read this file.")
            modelReady.value = app.models.ready()
        } finally { preparing.value = false }
    }
    fun play(passage: Passage) { player.play(passage) { errors.tryEmit(it) } }
    fun playNote(note: Note) {
        play(Passage(id = -1, noteId = note.id, chunkId = "", start = 0, end = note.duration, text = "", uncertain = false))
    }
    private fun work(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: Exception) {
            if (e is CancellationException) throw e
            errors.emit(e.message ?: "Something went wrong. Your saved notes are still on this device.")
        }
    }
    override fun onCleared() { player.stop() }
}
