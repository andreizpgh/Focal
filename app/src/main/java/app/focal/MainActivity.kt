package app.focal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import app.focal.recording.RecordingService
import app.focal.ui.FocalApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openedNote = MutableStateFlow<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openedNote.value = intent.getStringExtra(RecordingService.NOTE)
        setContent { val model: FocalViewModel = viewModel(); FocalApp(model, openedNote) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openedNote.value = intent.getStringExtra(RecordingService.NOTE)
    }
}
