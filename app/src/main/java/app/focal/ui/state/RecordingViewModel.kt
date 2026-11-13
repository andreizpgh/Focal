package app.focal.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.focal.FocalApplication
import app.focal.R
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
 * timer; it never carries the meter level: the nine bars read [app.focal.recording.AudioLevel] inside a draw lambda,
 * smoothed with [app.focal.core.MeterMath] and [app.focal.core.LevelHistory].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FocalApplication
    private val store = app.store
    private val startRequested = MutableStateFlow(false)

    /** A start was sent and the service has neither confirmed nor refused it yet. */
    val starting: Boolean get() = startRequested.value
    private val finishingNote = MutableStateFlow<String?>(null)

    /**
     * The note Finish was last asked for from here. Its capture is still closing for a moment after the tap: what shows
     * that note meanwhile (its page under the settling scene) already treats the recording as over.
     */
    val finishRequested: StateFlow<String?> = finishingNote

    /** Where a recording that continues a note starts counting, until the service publishes its own time. */
    private val startSeconds = MutableStateFlow(0L)

    /**
     * What [record] was last asked for: the note it continues (null: a new note) and that note's length then. "Try
     * again" after a start that failed repeats it, so a continued note is never retried as a new one.
     */
    var lastRequest: Pair<String?, Long> = null to 0L
        private set

    /** Service outcomes without a note: "Nothing was recorded", storage or microphone problems at Record. */
    val events: SharedFlow<ServiceEvent> = RecordingState.events

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

    val state: StateFlow<RecordingUiState> = combine(session, paragraphs, startRequested, finishingNote, startSeconds) { live, text, isStarting, finishing, from ->
        val phase = when {
            live.capturing -> if (live.paused) RecordingPhase.PAUSED else RecordingPhase.RECORDING
            finishing != null && live.noteId == finishing && live.processing -> RecordingPhase.FINISHING
            isStarting -> RecordingPhase.STARTING
            else -> RecordingPhase.IDLE
        }
        RecordingUiState(
            phase = phase,
            noteId = live.noteId.takeIf { phase != RecordingPhase.IDLE && phase != RecordingPhase.STARTING },
            // A continued note's timer opens on the note's length, not on 0:00, before the microphone has answered.
            elapsedSeconds = if (phase == RecordingPhase.STARTING) from else live.seconds,
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
     * Starts a recording: a new note, or with [appendTo] more of that ready note (its audio and text go on where they
     * ended; [fromMs] is its length so far, which the timer shows at once). Ask for RECORD_AUDIO (and POST_NOTIFICATIONS on API 33+) first; call while Focal is visible. A recording
     * that is already live is not restarted. Failures arrive on [events].
     */
    fun record(appendTo: String? = null, fromMs: Long = 0) {
        if (RecordingState.live.value.capturing) return
        lastRequest = appendTo to fromMs
        startSeconds.value = if (appendTo == null) 0 else fromMs / 1000
        finishingNote.value = null
        try {
            RecordingService.command(app, RecordingService.START, appendTo)
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
