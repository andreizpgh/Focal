package app.focal.ui.shell

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.FocalApplication
import app.focal.R
import app.focal.recording.RecordingState
import app.focal.recording.ServiceEvent
import app.focal.recording.StartFailure
import app.focal.ui.components.FeedbackAction
import app.focal.ui.components.FeedbackResult
import app.focal.ui.components.FocalIcon
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.design.rememberFocalHaptics
import app.focal.ui.library.FreshNotes
import app.focal.ui.library.ReadyWatch
import app.focal.ui.state.EntryRequest
import app.focal.ui.state.EntryRequests
import app.focal.ui.state.LibraryEvent
import app.focal.ui.state.LibraryViewModel
import app.focal.ui.state.PlaybackViewModel
import app.focal.ui.state.RecordingPhase
import app.focal.ui.state.RecordingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the whole app reacts to, whichever page is shown: the library's and the service's outcomes in the feedback
 * pill (spec 4.8, 5.1.7, 5.1.8), notes turning Ready (the fresh mark, 4.22.2), the Recording-started tick (2.9),
 * the notification permission after the first capture (5.11), and entry requests from the shortcut and
 * notifications (5.13). Messages run in the activity composition's scope, so a deferred Delete still commits or
 * undoes after the page changes, and commits when that scope ends with the activity (a rotation included).
 */
@Composable
fun AppEffects(shell: ShellState, library: LibraryViewModel, recording: RecordingViewModel, retry: () -> Unit, record: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberFocalHaptics()
    val feedback = shell.bottom.feedback
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val onRecord by rememberUpdatedState(record)
    // What a failed start is tried again with: the same request, not a new note ([record] is the shortcut's Record).
    val onRetry by rememberUpdatedState(retry)
    // The current configuration's resources, for a plural counted when its message is shown.
    val resources by rememberUpdatedState(LocalResources.current)
    // Read in composition, so a configuration change (a new locale) reaches messages shown later.
    val words by rememberUpdatedState(
        FeedbackWords(
            undo = stringResource(R.string.action_undo), storage = stringResource(R.string.action_storage),
            tryAgain = stringResource(R.string.action_try_again), deleted = stringResource(R.string.feedback_deleted),
            audioRemoved = stringResource(R.string.feedback_audio_removed), tooLate = stringResource(R.string.feedback_too_late),
            nothingRecorded = stringResource(R.string.feedback_nothing_recorded), storageShort = stringResource(R.string.feedback_storage),
            storageResume = stringResource(R.string.feedback_storage_resume), micBusy = stringResource(R.string.feedback_mic_busy),
            interrupted = stringResource(R.string.feedback_interrupted),
        ),
    )

    LaunchedEffect(library) {
        library.events.collect { event ->
            scope.launch {
                when (event) {
                    is LibraryEvent.Deleted -> feedback.showDeferred(
                        if (event.noteIds.size == 1) words.deleted
                        else resources.getQuantityString(R.plurals.feedback_deleted_many, event.noteIds.size, event.noteIds.size),
                        FeedbackAction(words.undo, FocalIcon.Undo),
                        undo = { library.undoDelete(event.noteIds) }, commit = { library.commitDelete(event.noteIds) },
                    )
                    is LibraryEvent.AudioRemoved -> feedback.showDeferred(
                        if (event.noteIds.size == 1) words.audioRemoved
                        else resources.getQuantityString(R.plurals.feedback_audio_removed_many, event.noteIds.size, event.noteIds.size),
                        FeedbackAction(words.undo, FocalIcon.Undo),
                        undo = { library.undoRemoveAudio(event.noteIds) }, commit = { library.commitRemoveAudio(event.noteIds) },
                    )
                    is LibraryEvent.TooLateToUndo -> feedback.show(words.tooLate)
                    is LibraryEvent.Error -> feedback.show(event.message)
                }
            }
        }
    }
    LaunchedEffect(recording) {
        recording.events.collect { event ->
            scope.launch {
                when (event) {
                    ServiceEvent.NothingRecorded -> feedback.show(words.nothingRecorded)
                    is ServiceEvent.StartFailed -> when (event.reason) {
                        StartFailure.STORAGE -> {
                            val amount = Formatter.formatShortFileSize(context, event.storageShortBytes)
                            if (feedback.show(words.storageShort.format(amount), FeedbackAction(words.storage)) == FeedbackResult.ActionPerformed) {
                                context.openStorageSettings()
                            }
                        }
                        StartFailure.MICROPHONE -> {
                            if (feedback.show(words.micBusy, FeedbackAction(words.tryAgain)) == FeedbackResult.ActionPerformed) onRetry()
                        }
                        // The permission went missing: ask again. With the permission in place Android refused
                        // the microphone for another reason, and asking again would only repeat the refusal.
                        StartFailure.PERMISSION -> {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (granted) feedback.show(event.message) else onRetry()
                        }
                        StartFailure.OTHER -> feedback.show(event.message)
                    }
                    ServiceEvent.Interrupted -> feedback.show(words.interrupted)
                    // Resume refused for storage: say how much to free, at every refusal (spec 5.3.5).
                    is ServiceEvent.ResumeRefused -> {
                        val text = words.storageResume.format(Formatter.formatShortFileSize(context, event.storageShortBytes))
                        if (feedback.show(text, FeedbackAction(words.storage)) == FeedbackResult.ActionPerformed) context.openStorageSettings()
                    }
                }
            }
        }
    }
    // The library's load error offers a retry.
    LaunchedEffect(library) {
        library.state.map { it.error }.distinctUntilChanged().filterNotNull().collect { message ->
            scope.launch {
                if (feedback.show(message, FeedbackAction(words.tryAgain)) == FeedbackResult.ActionPerformed) library.retryLoad()
            }
        }
    }
    // Notes that turn Ready while Focal watches become fresh (the stage's white Copy). Compared off the main thread,
    // and not for the recording's timer: only when the notes or the session change.
    LaunchedEffect(library, recording) {
        val watch = ReadyWatch()
        combine(library.state, recording.stateWithoutTime) { notes, capture -> watch.update(notes, capture) }
            .flowOn(Dispatchers.Default)
            .collect { if (it.isNotEmpty()) FreshNotes.markReady(it) }
    }
    // Recording started: the tick when the service confirms capture; a start dismisses the feedback pill (4.8).
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(recording) {
        var previous = recording.state.value.phase
        recording.state.map { it.phase }.distinctUntilChanged().collectLatest { phase ->
            val started = phase == RecordingPhase.RECORDING && previous == RecordingPhase.STARTING
            if (phase == RecordingPhase.STARTING) feedback.dismiss()
            previous = phase
            if (!started) return@collectLatest
            haptics.recordingStarted()
            val preferences = (context.applicationContext as FocalApplication).preferences
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !preferences.notificationsAsked &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                delay(FocalTiming.NOTIFICATION_PERMISSION_DELAY_MS)
                preferences.notificationsAsked = true
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    // The fresh mark lasts until the app stops; playback pauses with the activity, whatever page is shown (5.6).
    val player: PlaybackViewModel = viewModel()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                FreshNotes.clear()
                player.pause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // The shortcut and the notifications (spec 5.13).
    LaunchedEffect(Unit) {
        EntryRequests.pending.filterNotNull().collect { request ->
            when (request) {
                is EntryRequest.OpenNote -> shell.open(Destination.Note(request.noteId))
                EntryRequest.Record -> onRecord()
                // Only for a capture that is live, or the one the shortcut has just started (no note yet): a stale
                // notification, or another app, never puts up a scene with nothing behind it.
                is EntryRequest.ShowRecording -> if (request.noteId == null || RecordingState.live.value.capturing) {
                    // Settings snaps closed under the covering scene (4.23.7); the scene is simply open (5.13).
                    shell.settings.snapClosed()
                    shell.live.show(reduced)
                }
            }
            EntryRequests.consume(request)
        }
    }
}

/** The feedback pill's words, resolved in composition. */
private data class FeedbackWords(
    val undo: String, val storage: String, val tryAgain: String, val deleted: String, val audioRemoved: String,
    val tooLate: String, val nothingRecorded: String, val storageShort: String, val storageResume: String, val micBusy: String,
    val interrupted: String,
)

private fun Context.openStorageSettings() {
    try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } catch (_: ActivityNotFoundException) { }
}
