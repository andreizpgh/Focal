package app.focal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import app.focal.recording.RecordingService
import app.focal.ui.FocalApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openedNote = MutableStateFlow<Pair<String?, Long>>(null to 0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The saved Compose destination already reflects any previously handled
        // notification. Replaying the launch intent would override it on rotation.
        if (savedInstanceState == null) openFromIntent(intent)
        setContent { val model: FocalViewModel = viewModel(); FocalApp(model, openedNote) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    override fun onStop() {
        // Saved-audio previews are foreground work; rotation retains the ViewModel's preview.
        if (!isChangingConfigurations) ViewModelProvider(this)[FocalViewModel::class.java].let { model ->
            model.alternativePreview.stop()
            model.noiseReview.stop()
        }
        super.onStop()
    }

    private fun openFromIntent(intent: Intent) {
        openedNote.value = intent.getStringExtra(RecordingService.NOTE) to (openedNote.value.second + 1)
    }
}
