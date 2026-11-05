package app.focal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.recording.RecordingService
import app.focal.recording.RecordingState
import app.focal.recording.ServiceEvent
import app.focal.recording.StartFailure
import app.focal.ui.FocalApp
import app.focal.ui.state.EntryRequest
import app.focal.ui.state.EntryRequests
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openedNote = MutableStateFlow<Pair<String?, Long>>(null to 0)
    /** The launcher shortcut asked to record; handled once the activity is in the foreground. */
    private var recordOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The saved Compose destination already reflects any previously handled
        // notification. Replaying the launch intent would override it on rotation.
        if (savedInstanceState == null) handle(intent)
        setContent { val model: FocalViewModel = viewModel(); FocalApp(model, openedNote) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        if (recordOnResume) {
            recordOnResume = false
            recordFromShortcut()
        }
        // Transcription that waits (process death, Android's background limit) continues only from here.
        (application as FocalApplication).resumeWaitingTranscription(this)
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_RECORD -> recordOnResume = true
            ACTION_SHOW_RECORDING -> {
                val id = intent.getStringExtra(RecordingService.NOTE)
                EntryRequests.post(EntryRequest.ShowRecording(id))
                openFromIntent(intent)
            }
            else -> {
                intent.getStringExtra(RecordingService.NOTE)?.let { EntryRequests.post(EntryRequest.OpenNote(it)) }
                openFromIntent(intent)
            }
        }
    }

    /**
     * "New recording": an existing recording is shown, never a second one started. With the microphone
     * permission the service starts here, from the visible activity; without it the UI asks first.
     */
    private fun recordFromShortcut() {
        val live = RecordingState.live.value
        if (live.capturing) {
            EntryRequests.post(EntryRequest.ShowRecording(live.noteId))
            openedNote.value = live.noteId to (openedNote.value.second + 1)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            EntryRequests.post(EntryRequest.Record)
            return
        }
        try {
            RecordingService.command(this, RecordingService.START)
            EntryRequests.post(EntryRequest.ShowRecording(null))
        } catch (error: Exception) {
            RecordingState.events.tryEmit(ServiceEvent.StartFailed(StartFailure.OTHER, error.message ?: "Could not start recording."))
        }
    }

    private fun openFromIntent(intent: Intent) {
        openedNote.value = intent.getStringExtra(RecordingService.NOTE) to (openedNote.value.second + 1)
    }

    companion object {
        /** The launcher shortcut "New recording" (res/xml/shortcuts.xml). */
        const val ACTION_RECORD = "app.focal.action.RECORD"
        /** The recording notification: show the recording screen. */
        const val ACTION_SHOW_RECORDING = "app.focal.action.SHOW_RECORDING"
    }
}
