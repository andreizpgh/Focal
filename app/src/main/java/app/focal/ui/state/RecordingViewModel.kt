package app.focal.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.focal.FocalApplication
import app.focal.R
import app.focal.recording.AudioLevel
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.ServiceEvent
import app.focal.recording.SpeechAvailability
import app.focal.recording.StartFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The recording screen and the live row. [state] changes when the session changes and once a second for the
 * timer; it never carries the meter level: draw the nine bars from [level] inside a draw lambda, smoothed
 * with [app.focal.core.MeterMath] and [app.focal.core.LevelHistory].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FocalApplication
    private val store = app.store
    private val startRequested = MutableStateFlow(false)

    /** A start was sent and the service has neither confirmed nor refused it yet. */
    val starting: Boolean get() = startRequested.value
    private val finishingNote = MutableStateFlow<String?>(null)

    /** Service outcomes without a note: "Nothing was recorded", storage or microphone problems at Record. */
    val events: SharedFlow<ServiceEvent> = RecordingState.events

    /** The latest microphone level (0..1). Read it only in draw or frame callbacks, never in composition. */
    fun level(): Float = AudioLevel.current

    private data class Session(
        val noteId: String?, val capturing: Boolean, val paused: Boolean, val processing: Boolean, val seconds: Long,
        val pauseReason: String?, val storageShort: Long, val speech: SpeechAvailability,
    )

    private val session: Flow<Session> = RecordingState.live.map {
        Session(it.noteId, it.capturing, it.paused, it.processing, it.duration / 1000, it.pauseReason, it.storageShortBytes, it.speech)
    }.distinctUntilChanged()

    /** The live note's paragraphs, re-read when the store changes while a recording runs. */
    private val paragraphs: Flow<List<LiveParagraph>> = session.map { it.noteId?.takeIf { _ -> it.capturing } }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else store.changes.map {
                runCatching { store.paragraphs(id).filter { p -> p.visible }.map { p -> LiveParagraph(p.id, p.text, p.open) } }
                    .getOrDefault(emptyList())
            }.distinctUntilChanged()
        }.flowOn(Dispatchers.IO)

    val state: StateFlow<RecordingUiState> = combine(session, paragraphs, startRequested, finishingNote) { live, text, isStarting, finishing ->
        val phase = when {
            live.capturing -> if (live.paused) RecordingPhase.PAUSED else RecordingPhase.RECORDING
            finishing != null && live.noteId == finishing && live.processing -> RecordingPhase.FINISHING
            isStarting -> RecordingPhase.STARTING
            else -> RecordingPhase.IDLE
        }
        RecordingUiState(
            phase = phase,
            noteId = live.noteId.takeIf { phase != RecordingPhase.IDLE && phase != RecordingPhase.STARTING },
            elapsedSeconds = live.seconds,
            pauseReason = PauseReason.of(live.pauseReason).takeIf { live.capturing && live.paused },
            storageShortBytes = if (live.capturing) live.storageShort else 0,
            speech = live.speech,
            paragraphs = if (live.capturing) text else emptyList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingUiState())

    /**
     * [state] without its timer ([RecordingUiState.elapsedSeconds] is 0): it changes only when the session does, for
     * what shows the recording but not its time (the library, apart from the live stage's timer).
     */
    val stateWithoutTime: StateFlow<RecordingUiState> = state.map { it.copy(elapsedSeconds = 0L) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingUiState())

    /** The recording's whole seconds, for a timer that reads them on its own. */
    val elapsedSeconds: StateFlow<Long> = state.map { it.elapsedSeconds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    init {
        // STARTING ends when the service publishes the note or reports that it could not start.
        viewModelScope.launch { RecordingState.live.collect { if (it.capturing) startRequested.value = false } }
        viewModelScope.launch { RecordingState.events.collect { if (it is ServiceEvent.StartFailed) startRequested.value = false } }
    }

    /**
     * Starts a recording. Ask for RECORD_AUDIO (and POST_NOTIFICATIONS on API 33+) first; call while Focal is
     * visible. A recording that is already live is not restarted. Failures arrive on [events].
     */
    fun record() {
        if (RecordingState.live.value.capturing) return
        try {
            RecordingService.command(app, RecordingService.START)
            startRequested.value = true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            RecordingState.events.tryEmit(ServiceEvent.StartFailed(
                if (error is SecurityException) StartFailure.PERMISSION else StartFailure.OTHER,
                RecordingService.refusal(error) ?: app.getString(R.string.error_start)))
        }
    }

    fun pause() = send(RecordingService.PAUSE)

    /** Resumes; storage is checked again (a refusal keeps the pause and sets storageShortBytes). */
    fun resume() = send(RecordingService.RESUME)

    /** Ends the recording; the note continues as TRANSCRIBING and becomes READY on its own. */
    fun finish() {
        finishingNote.value = RecordingState.live.value.noteId
        send(RecordingService.STOP)
    }

    private fun send(action: String) {
        if (!RecordingState.live.value.capturing) return
        try { RecordingService.command(app, action) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            RecordingState.events.tryEmit(ServiceEvent.StartFailed(StartFailure.OTHER, app.getString(R.string.error_recording_unreachable)))
        }
    }
}
