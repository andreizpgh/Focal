package app.focal.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the activity was opened: a launcher shortcut, a notification or a link to a note. */
sealed interface EntryRequest {
    /** "New recording" without the microphone permission: ask for it, then record. */
    data object Record : EntryRequest

    /** Show the recording screen expanded ([noteId] null while the service is still creating the note). */
    data class ShowRecording(val noteId: String?) : EntryRequest

    /** Open a note (a "Note ready" or transcription notification). */
    data class OpenNote(val noteId: String) : EntryRequest
}

/**
 * The latest entry request, posted by MainActivity and consumed by the UI once it acted on it. A state
 * (not an event stream), so a request posted before the UI collects is not lost; [consume] clears it.
 */
object EntryRequests {
    private val state = MutableStateFlow<EntryRequest?>(null)
    val pending: StateFlow<EntryRequest?> = state.asStateFlow()

    fun post(request: EntryRequest) { state.value = request }

    /** Clears [request] if it is still the pending one. */
    fun consume(request: EntryRequest) { state.compareAndSet(request, null) }
}
