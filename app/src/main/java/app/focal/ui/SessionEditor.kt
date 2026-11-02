package app.focal.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.focal.core.Transcript
import app.focal.data.Note
import app.focal.data.Passage
import app.focal.data.fitsPassageDraftBudget
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable internal fun EditSheet(
    passage: Passage, playing: Boolean, audio: Boolean, play: () -> Unit, dismiss: () -> Unit,
    hasNextReview: Boolean, audioUnavailableReason: String,
    onDirtyChange: (Boolean) -> Unit = {}, onSavingChange: (Boolean) -> Unit = {},
    save: suspend (String, Boolean) -> Boolean,
) {
    if (!fitsPassageDraftBudget(passage.text)) {
        SideEffect { onDirtyChange(false); onSavingChange(false) }
        LargePassageSheet(passage, playing, audio, play, dismiss, audioUnavailableReason)
        return
    }
    var text by rememberSaveable(passage.id) { mutableStateOf(passage.text) }
    var discard by rememberSaveable(passage.id) { mutableStateOf(false) }
    var saving by remember(passage.id) { mutableStateOf(false) }
    var saveFailed by rememberSaveable(passage.id) { mutableStateOf(false) }
    var inputTooLarge by rememberSaveable(passage.id) { mutableStateOf(false) }
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
    val confirmSheetChange = rememberUpdatedState<(SheetValue) -> Boolean> { target ->
        when {
            target != SheetValue.Hidden -> true
            saving -> false
            text != passage.text -> { discard = true; false }
            else -> true
        }
    }
    // SheetState keys its confirmation callback. Keep that identity stable as
    // Save & next changes passages, while the callback reads the latest draft.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = remember { { target -> confirmSheetChange.value(target) } })
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
                    text, {
                        inputTooLarge = !fitsPassageDraftBudget(it)
                        if (!inputTooLarge) { text = it; onDirtyChange(it != passage.text) }
                    }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 144.dp, max = 320.dp), minLines = 3,
                    textStyle = MaterialTheme.typography.bodyLarge, shape = RoundedCornerShape(8.dp), label = { Text("Transcript") },
                    isError = inputTooLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
            if (inputTooLarge) {
                val requester = remember(passage.id) { BringIntoViewRequester() }
                val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                Text("This text is too long for one passage. Paste a smaller selection.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester)
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive })
                LaunchedEffect(passage.id, imeBottom) {
                    // The keyboard can keep moving after rejection or activity recreation.
                    withFrameNanos { }
                    requester.bringIntoView()
                }
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

internal const val LARGE_PASSAGE_PART_CHARS = 8192

private fun passageTextBoundary(text: String, offset: Int): Int {
    val position = offset.coerceIn(0, text.length)
    if (position in 1 until text.length &&
        ((text[position - 1].isHighSurrogate() && text[position].isLowSurrogate()) ||
            (text[position - 1] == '\r' && text[position] == '\n'))) return position + 1
    return position
}

internal fun passageTextPartCount(text: String, partChars: Int = LARGE_PASSAGE_PART_CHARS): Int {
    val count = (text.length + partChars - 1) / partChars
    return if (count > 1 && passageTextBoundary(text, (count - 1) * partChars) == text.length) count - 1 else count.coerceAtLeast(1)
}

internal fun passageTextPart(text: String, part: Int, partChars: Int = LARGE_PASSAGE_PART_CHARS): String =
    text.substring(passageTextBoundary(text, part * partChars), passageTextBoundary(text, (part + 1) * partChars))

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LargePassageSheet(
    passage: Passage, playing: Boolean, audio: Boolean, play: () -> Unit, dismiss: () -> Unit, audioUnavailableReason: String,
) {
    var part by rememberSaveable(passage.id) { mutableIntStateOf(0) }
    val count = passageTextPartCount(passage.text)
    val index = part.coerceIn(0, count - 1)
    val scroll = rememberSaveable(passage.id, index, saver = ScrollState.Saver) { ScrollState(0) }
    val text = remember(passage.text, index) { passageTextPart(passage.text, index) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Saved passage", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = dismiss, modifier = Modifier.semantics { contentDescription = "Close editor" }) {
                    Glyph(Symbol.Close, Modifier.size(20.dp))
                }
            }
            Text("${Transcript.time(passage.start)}–${Transcript.time(passage.end)}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            if (audio) TextButton(onClick = play, contentPadding = PaddingValues(vertical = 12.dp)) {
                Glyph(if (playing) Symbol.Pause else Symbol.Play, Modifier.size(17.dp))
                Text(if (playing) "Pause playback" else "Listen to this part", modifier = Modifier.padding(start = 10.dp))
            } else Text(audioUnavailableReason, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 14.dp))
            Text("This saved passage is too large to edit. Select text to copy; the full original is preserved.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp))
            key(passage.id, index) {
                SelectionContainer(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 320.dp).verticalScroll(scroll)) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { part = index - 1 }, enabled = index > 0,
                    modifier = Modifier.semantics { contentDescription = "Previous text part" }) { Glyph(Symbol.Back) }
                Text("Part ${index + 1} of $count", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                IconButton(onClick = { part = index + 1 }, enabled = index + 1 < count,
                    modifier = Modifier.semantics { contentDescription = "Next text part" }) { Glyph(Symbol.Arrow) }
            }
        }
    }
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
