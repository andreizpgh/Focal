package app.focal.ui.scene

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focal.FocalApplication
import app.focal.R
import app.focal.recording.ServiceEvent
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.WaveMeter
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.rememberFocalHaptics
import app.focal.ui.shell.Destination
import app.focal.ui.shell.PageMotion
import app.focal.ui.shell.SceneBottom
import app.focal.ui.shell.ShellState
import app.focal.ui.state.LibraryViewModel
import app.focal.ui.state.PauseReason
import app.focal.ui.state.RecordingPhase
import app.focal.ui.state.RecordingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The recording surface in the app (spec 5.3): the overlay from the recording view model, composed by the shell while
 * p > 0, with the scene's dock (Pause | Finish, or Resume | Finish) on the root column. Finish settles the scene into
 * the note's page (T7): `Confirm` once the service has the command, the page pushed under the scene. [scope] outlives
 * the overlay (the settle ends by removing it).
 */
@Composable
fun SceneRoute(shell: ShellState, recording: RecordingViewModel, library: LibraryViewModel, meter: WaveMeter, scope: CoroutineScope) {
    val live = shell.live
    val state by recording.state.collectAsStateWithLifecycle()
    val notes by library.state.collectAsStateWithLifecycle()
    // Read when an action runs: the remembered actions below outlive a change of the setting.
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val haptics = rememberFocalHaptics()
    val notificationsOff = rememberNotificationsOff()
    val firstRecordings = remember(notes.days) { notes.days.sumOf { it.rows.size } < FocalTiming.HINT_RECORDINGS }
    val paused = state.phase == RecordingPhase.PAUSED
    val scene = SceneUi(
        elapsedSeconds = state.elapsedSeconds, paused = paused, pauseReason = state.pauseReason, speech = state.speech,
        paragraphs = state.paragraphs, hint = firstRecordings, notificationsOff = notificationsOff,
    )
    val noteId by rememberUpdatedState(state.noteId)
    val isPaused by rememberUpdatedState(paused)
    val dockActions = remember(recording, live) {
        DockActions(
            pause = recording::pause,
            resume = recording::resume,
            finish = {
                val id = noteId
                if (id != null && live.landingNote == null) {
                    live.prepareLanding(id)
                    recording.finish()
                    haptics.finish()
                    scope.launch { live.land(reduced) { shell.open(Destination.Note(id), PageMotion.Cut) } }
                }
            },
        )
    }
    SceneBottom(DockState.Live(paused), dockActions)
    val actions = remember(recording, live) {
        SceneActions(
            collapse = { scope.launch { live.collapse(reduced) } },
            togglePause = { if (isPaused) recording.resume() else recording.pause() },
        )
    }
    RecordingSurface(live, scene, meter, actions, onLibrary = shell.current == Destination.Library, bars = shell.bars)
}

/**
 * Whether notifications are off (spec 5.3.8): disabled for Focal, and on API 33+ only once Android has asked (5.11:
 * 600 ms after the first capture), so the notice never shows while the question is still open. Re-read whenever
 * Focal resumes (the user may have answered or changed it).
 */
@Composable
private fun rememberNotificationsOff(): Boolean {
    val context = LocalContext.current
    val preferences = (context.applicationContext as FocalApplication).preferences
    fun read(): Boolean = !NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || preferences.notificationsAsked)
    var off by remember { mutableStateOf(read()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) off = read()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return off
}

/**
 * What the recording surface does whatever is on screen (spec 5.3, 8.2, 8.6): it folds away when the capture ends or
 * fails to start while it is up (not when Finish settles it into the note); focus returns to the stage after a fold
 * for keyboard and screen-reader users; and Pause, Resume and automatic pauses are announced politely, with the
 * reason that is never written ("Paused for a call.").
 */
@Composable
fun SceneEffects(shell: ShellState, recording: RecordingViewModel, scope: CoroutineScope) {
    val live = shell.live
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val wantsFocus = rememberFocusHandOff()
    LaunchedEffect(recording, live) {
        var previous = recording.state.value.phase
        recording.state.map { it.phase }.distinctUntilChanged().collect { phase ->
            val wasLive = previous == RecordingPhase.STARTING || previous == RecordingPhase.RECORDING || previous == RecordingPhase.PAUSED
            val ended = phase == RecordingPhase.IDLE || phase == RecordingPhase.FINISHING
            previous = phase
            if (wasLive && ended && live.visible && live.landingNote == null) scope.launch { live.collapse(reduced) }
        }
    }
    LaunchedEffect(recording, live) {
        recording.events.collect { event ->
            val failed = event is ServiceEvent.StartFailed || event == ServiceEvent.NothingRecorded
            if (failed && live.visible && live.landingNote == null && !recording.state.value.live) scope.launch { live.collapse(reduced) }
        }
    }
    LaunchedEffect(live) {
        var wasVisible = live.visible
        snapshotFlow { live.visible }.collect { visible ->
            if (wasVisible && !visible && live.landingNote == null && wantsFocus()) runCatching { live.stageFocus?.requestFocus() }
            wasVisible = visible
        }
    }
    val words = AnnounceWords(
        paused = stringResource(R.string.a11y_announce_paused), resumed = stringResource(R.string.a11y_announce_resumed),
        call = stringResource(R.string.a11y_announce_paused_call), otherApp = stringResource(R.string.a11y_announce_paused_other_app),
        storage = stringResource(R.string.a11y_announce_paused_storage),
    )
    val currentWords by rememberUpdatedState(words)
    var announcement by remember { mutableStateOf("") }
    LaunchedEffect(recording) {
        var previous: Pair<RecordingPhase, PauseReason?>? = null
        recording.state.map { it.phase to it.pauseReason }.distinctUntilChanged().collect { now ->
            val (phase, reason) = now
            val before = previous?.first
            previous = now
            announcement = when {
                phase == RecordingPhase.PAUSED && (before == RecordingPhase.RECORDING || before == RecordingPhase.PAUSED) -> currentWords.paused(reason)
                phase == RecordingPhase.RECORDING && before == RecordingPhase.PAUSED -> currentWords.resumed
                else -> return@collect
            }
        }
    }
    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
}

/** The polite announcements (spec 7.8): the reason is spoken, never drawn. */
private class AnnounceWords(val paused: String, val resumed: String, val call: String, val otherApp: String, val storage: String) {
    fun paused(reason: PauseReason?): String = when (reason) {
        PauseReason.CALL -> call
        PauseReason.SILENCED -> otherApp
        PauseReason.STORAGE -> storage
        null -> paused
    }
}
