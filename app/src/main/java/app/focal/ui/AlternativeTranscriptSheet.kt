package app.focal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.focal.core.Transcript
import app.focal.speech.PreviewPhase
import app.focal.speech.PreviewState

/** The alternative remains separate from the saved transcript and its manual edits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AlternativeTranscriptSheet(state: PreviewState?, stop: () -> Unit) {
    var shown by remember { mutableStateOf(state) }
    val busy = state?.phase in listOf(PreviewPhase.Loading, PreviewPhase.Running, PreviewPhase.Stopping)
    val canHide by rememberUpdatedState(!busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = remember { { value -> value != SheetValue.Hidden || canHide } })
    LaunchedEffect(state) {
        if (state != null) shown = state
        else if (shown != null) {
            sheet.hide()
            shown = null
        }
    }
    val current = shown ?: return
    val text = remember(current.segments) {
        current.segments.map { it.text }.filter(String::isNotBlank).joinToString("\n\n")
    }
    val context = LocalContext.current
    var copyError by remember(current.noteId, text) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = stop, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        DialogSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Alternative transcript", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
                IconAction(Symbol.Close, "Close alternative transcript", click = stop)
            }
            Text("Parakeet · Automatic language", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your saved transcript and edits stay unchanged.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (current.phase) {
                PreviewPhase.Loading, PreviewPhase.Running, PreviewPhase.Stopping -> {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FocusProcessingIndicator(contentDescription = "Preparing alternative transcript")
                        Text(if (current.phase == PreviewPhase.Stopping) "Stopping after the current passage…"
                            else "Transcribing original audio…", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (current.totalFrames > 0) LinearProgressIndicator(
                        progress = { current.completedFrames.toFloat() / current.totalFrames },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = stop, enabled = current.phase != PreviewPhase.Stopping,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop") }
                }
                PreviewPhase.Failed -> Text(current.error ?: "Couldn't prepare an alternative transcript.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                PreviewPhase.Ready -> {
                    // Copy stays before the scrollable result, reachable even for a long recording.
                    Box(Modifier.testTag("alternative-copy")) {
                        CopyTranscriptButton(current.noteId, text, text.isNotBlank()) {
                            copyError = null
                            if (!fitsTextTransferBudget(text)) {
                                copyError = "This alternative is too long to copy at once. Select a smaller part."
                                false
                            } else try {
                                val clip = ClipData.newPlainText("Focal alternative transcript", text)
                                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                                }
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                                true
                            } catch (_: RuntimeException) {
                                copyError = "Couldn't copy text. Select a smaller part and try again."
                                false
                            }
                        }
                    }
                    copyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val missing = current.segments.filter { it.text.isBlank() }
                    if (missing.isNotEmpty()) Text(
                        "No text returned for " + missing.joinToString(", ") {
                            "${Transcript.time(it.start)}–${Transcript.time(it.end)}"
                        } + ". These sections remain available in the original recording.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (text.isBlank()) Text("No text was returned.", style = MaterialTheme.typography.bodyLarge)
                    else SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}
