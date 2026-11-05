package app.focal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.focal.core.Transcript
import app.focal.data.Passage
import app.focal.speech.NoiseReviewPhase
import app.focal.speech.NoiseReviewState
import kotlinx.coroutines.launch

/** A detector proposes a range; the owner decides what belongs in their note. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun BackgroundSoundSheet(
    state: NoiseReviewState?, playingId: Long?, dismiss: () -> Unit,
    play: (Passage) -> Unit, edit: (Passage) -> Unit,
    decide: suspend (Passage, Boolean) -> Boolean,
    restore: suspend (Passage) -> Boolean,
) {
    if (state == null) return
    var saving by remember(state.noteId) { mutableStateOf(false) }
    var lastExcluded by remember(state.noteId) { mutableStateOf<Passage?>(null) }
    val scope = rememberCoroutineScope()
    val currentSaving by rememberUpdatedState(saving)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = remember { { it: SheetValue -> it != SheetValue.Hidden || !currentSaving } })
    ModalBottomSheet(onDismissRequest = { if (!saving) dismiss() }, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        DialogSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 16.dp).animateContentSize()) {
            Text("Background sound", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            when (state.phase) {
                NoiseReviewPhase.Checking, NoiseReviewPhase.Stopping -> {
                    Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FocusProcessingIndicator(contentDescription = "Checking saved audio")
                        Text(if (state.phase == NoiseReviewPhase.Stopping) "Stopping…"
                            else if (state.total > 0) "Checking audio ${state.checked} of ${state.total}"
                            else "Preparing saved audio…")
                    }
                    Text("Your transcript stays unchanged.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = dismiss, enabled = state.phase != NoiseReviewPhase.Stopping,
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("Cancel") }
                }
                NoiseReviewPhase.Failed -> {
                    Text(state.error ?: "Could not check this recording. Your text is unchanged.",
                        modifier = Modifier.padding(vertical = 20.dp))
                    Button(onClick = dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Close") }
                }
                NoiseReviewPhase.Ready -> {
                    Text(if (state.proposals.isNotEmpty()) "The detector found no speech in these audio ranges. Listen before excluding any text."
                        else "No further passages suggested. Nothing is removed automatically.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                    lastExcluded?.let { passage ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Text excluded from Copy and Share", style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            TextButton(enabled = !saving, onClick = {
                                saving = true
                                scope.launch { try { if (restore(passage)) lastExcluded = null } finally { saving = false } }
                            }) { Text("Undo") }
                        }
                    }
                    AnimatedContent(targetState = state.proposals.firstOrNull(),
                        contentKey = { it?.id }, label = "background sound passage") { passage ->
                        Column(Modifier.fillMaxWidth()) {
                            if (passage == null) {
                                Text("Nothing else to check.", modifier = Modifier.padding(vertical = 16.dp))
                                Button(onClick = dismiss, enabled = !saving,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Done") }
                            } else {
                                Text("Possible background sound", style = MaterialTheme.typography.titleSmall)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${Transcript.time(passage.start)}–${Transcript.time(passage.end)}",
                                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { play(passage) }, enabled = !saving) {
                                        Glyph(if (playingId == passage.id) Symbol.Pause else Symbol.Play, Modifier.size(16.dp))
                                        Text(if (playingId == passage.id) "Pause" else "Listen", Modifier.padding(start = 8.dp))
                                    }
                                }
                                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                                    Text(passage.text, style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.fillMaxWidth().padding(16.dp))
                                }
                                TextButton(onClick = { edit(passage) }, enabled = !saving,
                                    modifier = Modifier.align(Alignment.End)) { Text("Edit text") }
                                fun commit(exclude: Boolean) {
                                    if (saving) return
                                    saving = true
                                    scope.launch {
                                        try { if (decide(passage, exclude) && exclude) lastExcluded = passage }
                                        finally { saving = false }
                                    }
                                }
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = { commit(true) }, enabled = !saving,
                                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Exclude text") }
                                    Button(onClick = { commit(false) }, enabled = !saving,
                                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Keep text") }
                                }
                                Text("${state.proposals.size} remaining", style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
