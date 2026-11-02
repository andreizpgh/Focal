package app.focal.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.focal.core.Transcript
import app.focal.data.Note
import app.focal.data.Passage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EditSheet(
    passage: Passage, playing: Boolean, audio: Boolean, play: () -> Unit, dismiss: () -> Unit,
    hasNextReview: Boolean, audioUnavailableReason: String,
    onDirtyChange: (Boolean) -> Unit = {}, onSavingChange: (Boolean) -> Unit = {},
    save: suspend (String, Boolean) -> Boolean,
) {
    var text by rememberSaveable(passage.id) { mutableStateOf(passage.text) }
    var discard by rememberSaveable(passage.id) { mutableStateOf(false) }
    var saving by remember(passage.id) { mutableStateOf(false) }
    var saveFailed by rememberSaveable(passage.id) { mutableStateOf(false) }
    val scroll = rememberSaveable(passage.id, saver = ScrollState.Saver) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    SideEffect { onDirtyChange(text != passage.text) }
    DisposableEffect(passage.id) { onDispose { onDirtyChange(false); onSavingChange(false) } }
    val needsReview = passage.uncertain && !passage.reviewed
    val canAdvance = needsReview && hasNextReview
    fun closeEditor() { focus.clearFocus(force = true); keyboard?.hide(); dismiss() }
    fun requestClose() {
        if (saving) return
        if (text != passage.text) discard = true else closeEditor()
    }
    fun commit(next: Boolean) {
        if (saving) return
        focus.clearFocus(force = true); keyboard?.hide()
        saving = true; onSavingChange(true)
        scope.launch { try { saveFailed = !save(text, next) } finally { saving = false; onSavingChange(false) } }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
        when {
            target != SheetValue.Hidden -> true
            saving -> false
            text != passage.text -> { discard = true; false }
            else -> true
        }
    })
    ModalBottomSheet(
        onDismissRequest = ::requestClose, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(scroll).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (needsReview) "Review passage" else "Edit passage", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = ::requestClose, enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = "Close editor" }) { Glyph(Symbol.Close, Modifier.size(20.dp)) }
            }
            Text("${Transcript.time(passage.start)}–${Transcript.time(passage.end)}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            if (audio) TextButton(onClick = play, contentPadding = PaddingValues(vertical = 12.dp)) {
                Glyph(if (playing) Symbol.Pause else Symbol.Play, Modifier.size(17.dp))
                Text(if (playing) "Pause playback" else "Listen to this part", modifier = Modifier.padding(start = 10.dp))
            } else Text(audioUnavailableReason, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 14.dp))
            key(passage.id) {
                OutlinedTextField(
                    text, { text = it; onDirtyChange(it != passage.text) }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 144.dp, max = 320.dp), minLines = 3,
                    textStyle = MaterialTheme.typography.bodyLarge, shape = RoundedCornerShape(8.dp), label = { Text("Transcript") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
            Spacer(Modifier.height(20.dp))
            if (saveFailed) Text(
                "Couldn't save. Your changes are still here. Try again.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
            PrimaryButton(if (saving) "Saving…" else if (canAdvance) "Save & next" else "Save changes", Symbol.Check, !saving) { commit(canAdvance) }
            if (canAdvance) TextButton(onClick = { commit(false) }, enabled = !saving, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Save & close")
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text("Discard your changes?") },
        text = { Text("Your edits to this passage won’t be saved.") },
        confirmButton = { TextButton(onClick = ::closeEditor) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } },
    )
}

@Composable internal fun RenameSessionDialog(
    note: Note, dismiss: () -> Unit,
    onDirtyChange: (Boolean) -> Unit = {}, onSavingChange: (Boolean) -> Unit = {},
    save: suspend (String) -> Boolean,
) {
    var title by rememberSaveable(note.id) { mutableStateOf(note.title) }
    var discard by rememberSaveable(note.id) { mutableStateOf(false) }
    var saving by remember(note.id) { mutableStateOf(false) }
    var failed by rememberSaveable(note.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    SideEffect { onDirtyChange(title != note.title) }
    DisposableEffect(note.id) { onDispose { onDirtyChange(false); onSavingChange(false) } }
    fun close() { focus.clearFocus(force = true); keyboard?.hide(); dismiss() }
    fun requestClose() {
        if (saving) return
        if (title != note.title) discard = true else close()
    }
    fun commit() {
        if (saving || title.isBlank()) return
        focus.clearFocus(force = true); keyboard?.hide(); saving = true; onSavingChange(true)
        scope.launch {
            try { if (save(title)) close() else failed = true }
            finally { saving = false; onSavingChange(false) }
        }
    }
    AlertDialog(
        onDismissRequest = ::requestClose, title = { Text("Session title") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    title, { title = it.take(120); onDirtyChange(title != note.title) }, singleLine = true, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().focusRequester(requester), label = { Text("Title") },
                    isError = title.isBlank(), supportingText = if (title.isBlank()) ({ Text("Enter a title.") }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                )
                if (failed) Text("Couldn't save the title. Try again.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Assertive })
            }
        },
        confirmButton = { TextButton(onClick = ::commit, enabled = !saving && title.isNotBlank()) { Text(if (saving) "Saving…" else "Save") } },
        dismissButton = { TextButton(onClick = ::requestClose, enabled = !saving) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { requester.requestFocus(); keyboard?.show() }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text("Discard this title?") },
        text = { Text("Your changes won’t be saved.") },
        confirmButton = { TextButton(onClick = ::close) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } },
    )
}
