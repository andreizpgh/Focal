package app.focal.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import app.focal.FocalViewModel
import app.focal.core.Transcript
import app.focal.data.Passage
import app.focal.data.Preferences
import app.focal.recording.LiveSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Destination {
    data object Sessions : Destination
    data object Settings : Destination
    data class Session(val id: String) : Destination
}
private val destinationSaver = androidx.compose.runtime.saveable.Saver<Destination, String>(
    save = { when (it) { Destination.Sessions -> "sessions"; Destination.Settings -> "settings"; is Destination.Session -> "session:${it.id}" } },
    restore = { when { it == "settings" -> Destination.Settings; it.startsWith("session:") -> Destination.Session(it.removePrefix("session:")); else -> Destination.Sessions } }
)

// Leave room for Android's parcel envelope and other in-flight Binder transactions.
private const val TEXT_TRANSFER_BYTE_BUDGET = 256 * 1024
internal fun fitsTextTransferBudget(text: String): Boolean =
    text.length <= TEXT_TRANSFER_BYTE_BUDGET / 2 && text.toByteArray(Charsets.UTF_8).size <= TEXT_TRANSFER_BYTE_BUDGET

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FocalApp(model: FocalViewModel, openedNote: StateFlow<Pair<String?, Long>>) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val ready by model.modelReady.collectAsStateWithLifecycle()
    val preparing by model.preparing.collectAsStateWithLifecycle()
    val setupError by model.app.startupError.collectAsStateWithLifecycle()
    val playing by model.player.playing.collectAsStateWithLifecycle()
    val playback by model.player.playback.collectAsStateWithLifecycle()
    val requested by openedNote.collectAsStateWithLifecycle()
    val view = LocalView.current
    var destination by rememberSaveable(stateSaver = destinationSaver) { mutableStateOf<Destination>(Destination.Sessions) }
    val selected = (destination as? Destination.Session)?.id
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var modelRetryId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchImeSeen by rememberSaveable { mutableStateOf(false) }
    var searchInputActive by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<Boolean, Boolean>(
        // Preserve never-shown hardware input; a dismissed software IME must stay dismissed.
        save = { active -> active && (!searchImeSeen || ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.ime()) != false) },
        restore = { it },
    )) { mutableStateOf(false) }
    var searchHasFocus by remember { mutableStateOf(false) }
    var searchFieldHeight by remember { mutableIntStateOf(0) }
    var windowHeight by remember { mutableIntStateOf(0) }
    var sessionsHeaderHeight by remember { mutableIntStateOf(0) }
    var attentionOnly by rememberSaveable { mutableStateOf(false) }
    val libraryListState = rememberLazyListState()
    var reviewOnly by rememberSaveable(selected) { mutableStateOf(false) }
    var editingId by rememberSaveable(selected) { mutableStateOf<Long?>(null) }
    val editing = library.passages.firstOrNull { it.id == editingId }
    var editorDirty by rememberSaveable(selected, editingId) { mutableStateOf(false) }
    var renameDirty by rememberSaveable(selected) { mutableStateOf(false) }
    var formSaving by remember { mutableStateOf(false) }
    var pendingOpenTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var observedLiveId by rememberSaveable { mutableStateOf<String?>(null) }
    var handledRequest by rememberSaveable { mutableStateOf<Long?>(null) }
    var handledRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun setSearchInputActive(active: Boolean) {
        if (active && !searchInputActive) searchImeSeen = false
        searchInputActive = active
    }
    fun dismissInput() { focus.clearFocus(force = true); keyboard?.hide(); searchInputActive = false }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var permissionNotice by remember { mutableStateOf<Job?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    fun dismissPermissionNotice() { permissionNotice?.cancel(); permissionNotice = null }
    DisposableEffect(view, searchInputActive, searchImeSeen) {
        if (searchInputActive && !searchImeSeen) {
            val observer = view.viewTreeObserver
            // Record only the first visible IME; lifecycle hides do not cancel input intent.
            val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                if (searchInputActive && ViewCompat.getRootWindowInsets(view)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true) searchImeSeen = true
                true
            }
            observer.addOnPreDrawListener(listener)
            onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
        } else onDispose { }
    }

    fun performOpen(id: String?) {
        dismissInput(); editingId = null; dialog = null; pendingOpenTarget = null
        editorDirty = false; renameDirty = false; formSaving = false
        model.select(id)
        destination = id?.let { Destination.Session(it) } ?: Destination.Sessions
    }
    fun open(id: String?) {
        if ((id != null && (destination as? Destination.Session)?.id == id) || (id == null && destination == Destination.Sessions)) return
        if (editorDirty || renameDirty || formSaving) {
            pendingOpenTarget = id?.let { "session:$it" } ?: "sessions"
        } else performOpen(id)
    }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    LaunchedEffect(selected) { model.select(selected) }
    LaunchedEffect(searchQuery) { model.search(searchQuery) }
    LaunchedEffect(requested) {
        if (handledRequest != requested.second || handledRequestId != requested.first) {
            handledRequest = requested.second
            handledRequestId = requested.first
            requested.first?.let { open(it) }
        }
    }
    LaunchedEffect(live.noteId) {
        if (live.noteId != observedLiveId) {
            observedLiveId = live.noteId
            live.noteId?.let { open(it) }
        }
    }
    LaunchedEffect(pendingOpenTarget, editorDirty, renameDirty, formSaving) {
        val target = pendingOpenTarget
        if (target != null && !editorDirty && !renameDirty && !formSaving) {
            performOpen(target.takeIf { it.startsWith("session:") }?.removePrefix("session:"))
        }
    }
    LaunchedEffect(live.message) { if (live.noteId == null && live.message.isNotBlank()) notify(live.message) }
    LaunchedEffect(Unit) { model.errors.collect { snackbar.showSnackbar(it) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            // Commit an already hidden search IME before Android can restore its focused editor.
            if (event == Lifecycle.Event.ON_PAUSE && destination == Destination.Sessions && searchInputActive && searchImeSeen &&
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false) dismissInput()
            if (event == Lifecycle.Event.ON_STOP) model.player.pause()
            if (event == Lifecycle.Event.ON_RESUME &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                dismissPermissionNotice()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.player.pause() }
    }
    BackHandler(destination != Destination.Sessions) { open(null) }
    BackHandler(destination == Destination.Sessions && searchOpen) {
        searchOpen = false; searchQuery = ""; dismissInput()
    }
    LaunchedEffect(selected, library.selectedId, library.note, library.loaded, library.error) {
        if (selected != null && library.loaded && library.selectedId == selected && library.note == null && library.error == null) {
            open(null)
            notify("Session is no longer available.")
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        dismissPermissionNotice()
        val granted = result[Manifest.permission.RECORD_AUDIO] == true || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) model.record()
        else permissionNotice = scope.launch {
            if (snackbar.showSnackbar("Allow microphone access to record.", "Settings") == SnackbarResult.ActionPerformed)
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importModel(it, retryId = modelRetryId) }
        modelRetryId = null
    }
    fun openModel(retryId: String? = null) { modelRetryId = retryId; dismissInput(); dialog = "model" }
    fun start() {
        if (!library.loaded || library.error != null) return
        // Cancelling this request removes its displayed or queued notice without
        // dismissing unrelated save, playback or transcription errors.
        dismissPermissionNotice()
        dismissInput()
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) model.record() else permissions.launch(needed.toTypedArray())
    }
    val note = library.note?.takeIf { it.id == selected }
    val active = note != null && live.noteId == note.id
    val audioAvailable = library.audioBytes > 0 && (live.noteId == null || live.processing)
    val transcript = remember(library.passages) { library.passages.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n") }
    fun allowTextTransfer(action: String): Boolean {
        if (fitsTextTransferBudget(transcript)) return true
        notify("This transcript is too long to $action at once. Copy a smaller selection from the passage editor.")
        return false
    }
    fun copyTranscript() {
        if (!allowTextTransfer("copy")) return
        try {
            val clip = ClipData.newPlainText("Focal transcript", transcript)
            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            if (Build.VERSION.SDK_INT < 33) notify("Text copied")
        } catch (_: RuntimeException) {
            notify("Couldn't copy text. Try copying a smaller selection from the passage editor.")
        }
    }
    fun shareTranscript() {
        if (!allowTextTransfer("share")) return
        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, transcript), "Share transcript")) }
        catch (_: ActivityNotFoundException) { notify("No app is available to share text.") }
        catch (_: RuntimeException) { notify("Couldn't share text. Try copying a smaller selection from the passage editor.") }
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // Keep the field in the same composition; only make room for it in a short IME viewport.
    val compactSearch = destination == Destination.Sessions && searchOpen && searchInputActive && searchHasFocus && imeBottom > 0 &&
        windowHeight - imeBottom - sessionsHeaderHeight < searchFieldHeight
    FocalTheme(settings.theme) {
        Scaffold(
            modifier = Modifier.onSizeChanged { windowHeight = it.height },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (!compactSearch) Row(Modifier.fillMaxWidth()
                    .onSizeChanged { if (destination == Destination.Sessions) sessionsHeaderHeight = it.height }
                    .statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (destination) {
                        Destination.Sessions -> {
                            Text("Sessions", style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
                            if (library.notes.isNotEmpty() || searchOpen || searchQuery.isNotBlank()) {
                                val searchActionFocus = remember { FocusRequester() }
                                IconAction(Symbol.Search, if (searchOpen) "Close search" else "Search sessions",
                                    Modifier.focusRequester(searchActionFocus)) {
                                    searchOpen = !searchOpen
                                    setSearchInputActive(searchOpen)
                                    if (searchOpen) libraryListState.requestScrollToItem(0)
                                    if (!searchOpen) { searchQuery = ""; dismissInput() }
                                }
                                LaunchedEffect(searchInputActive, searchHasFocus) {
                                    // Keyboard navigation can immediately refocus the field after clearFocus.
                                    if (!searchInputActive && searchHasFocus) searchActionFocus.requestFocus()
                                }
                            }
                            IconAction(Symbol.Settings, "Settings") { dismissInput(); destination = Destination.Settings }
                        }
                        Destination.Settings -> {
                            IconAction(Symbol.Back, "Back to sessions") { open(null) }
                            Text("Settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() })
                        }
                        is Destination.Session -> {
                            IconAction(Symbol.Back, "Back to sessions") { open(null) }
                            Spacer(Modifier.weight(1f))
                            if (note != null) {
                                var menu by remember(selected) { mutableStateOf(false) }
                                Box {
                                    IconAction(Symbol.More, "Session options") { menu = true }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = "rename" })
                                        DropdownMenuItem(text = { Text("Share text") }, enabled = transcript.isNotBlank() && library.error == null, onClick = {
                                            menu = false
                                            shareTranscript()
                                        })
                                        DropdownMenuItem(text = {
                                            Column {
                                                Text("Remove audio")
                                                val reason = when {
                                                    active -> if (live.processing) "Transcription is running" else "Recording is active"
                                                    library.pending > 0 -> "Transcription is unfinished"
                                                    library.audioBytes == 0L -> "No original audio"
                                                    else -> null
                                                }
                                                if (reason != null) Text(reason, style = MaterialTheme.typography.labelMedium)
                                            }
                                        }, enabled = !active && library.pending == 0 && library.audioBytes > 0,
                                            onClick = { menu = false; dialog = "audio" })
                                        DropdownMenuItem(text = {
                                            Column {
                                                Text("Delete session", color = MaterialTheme.colorScheme.error)
                                                if (active) Text(if (live.processing) "Transcription is running" else "Recording is active", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }, enabled = !active, onClick = { menu = false; dialog = "delete" })
                                    }
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (destination != Destination.Settings) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 12.dp)) {
                            when {
                                active && !live.processing -> RecordingControls(live, model::pause, model::finish)
                                note != null -> {
                                    if (active && live.processing) Row(
                                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        FocusProcessingIndicator()
                                        Text(
                                            live.message.ifBlank { "Transcribing" },
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                        )
                                    }
                                    if (audioAvailable) SessionAudioDock(
                                        note.id, playback, library.audioBytes / 32,
                                        { model.playNote(note) }, { model.seekNote(note, it) }
                                    )
                                    PrimaryButton("Copy text", Symbol.Copy, transcript.isNotBlank() && library.error == null, ::copyTranscript)
                                }
                                live.noteId != null -> PrimaryButton(
                                    if (live.processing) "Open transcript" else "Open recording", Symbol.Audio
                                ) { open(live.noteId) }
                                destination == Destination.Sessions -> {
                                    LibraryCaptureDock(
                                        Preferences.languages[settings.language].orEmpty(), library.loaded && library.error == null,
                                        chooseLanguage = { dismissInput(); dialog = "language" }, record = ::start,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)
                .then(if (destination == Destination.Sessions) Modifier.consumeWindowInsets(padding).imePadding() else Modifier),
                contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    when (destination) {
                        Destination.Settings -> SettingsScreen(
                            settings.language, settings.theme, model.app.preferences::language, model.app.preferences::theme,
                            ready, preparing, setupError, { openModel() }, ::notify,
                        )
                        Destination.Sessions -> LibraryScreen(
                            library.notes, library.loaded, live, ::open, searchQuery, { searchQuery = Transcript.truncateUtf16(it, SEARCH_QUERY_MAX_CHARS) },
                            searchOpen, { searchOpen = it; if (!it) searchInputActive = false }, library.error, model::retryLibrary,
                            attentionOnly = attentionOnly, setAttentionOnly = { attentionOnly = it }, listState = libraryListState,
                            inputActive = searchInputActive, setInputActive = ::setSearchInputActive,
                            searchFocusChanged = { searchHasFocus = it }, searchFieldMeasured = { searchFieldHeight = it },
                        )
                        is Destination.Session -> if (note != null) NoteScreen(
                            note, library, live.takeIf { active }, reviewOnly, { reviewOnly = !reviewOnly }, { if (library.error == null) editingId = it.id else notify("Reload this session before editing.") }, { if (ready) model.retry(note.id) else openModel(note.id) },
                            playback.isPlaying && playback.noteId == note.id, audioAvailable, { model.playNote(note) }, { dialog = "rename" },
                            seek = { model.seekNote(note, it) }, playbackPosition = playback.takeIf { it.noteId == note.id }?.positionMs,
                            retryLoad = model::retryLibrary,
                        ) else if (library.error != null) DataReadError("Couldn't load this session", library.error.orEmpty(), model::retryLibrary,
                            Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                FocusProcessingIndicator(contentDescription = "Loading session")
                            }
                    }
                }
            }
        }
        if (dialog == "language") LanguageDialog(settings.language, model.app.preferences::language) { dialog = null }
        if (dialog == "model") AlertDialog(
            onDismissRequest = { dialog = null; modelRetryId = null }, title = { Text("Speech model") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(when {
                    preparing -> "Preparing the speech model…"
                    ready -> "The multilingual speech model is installed. You can choose a replacement file."
                    else -> "Import ggml-base-q5_1.bin to transcribe saved and new recordings. You can record audio without it."
                })
                if (setupError != null) Text(setupError.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            } },
            confirmButton = { TextButton(onClick = { dialog = null; import.launch(arrayOf("*/*")) }, enabled = !preparing) { Text("Choose model") } },
            dismissButton = { TextButton(onClick = { dialog = null; modelRetryId = null }) { Text("Cancel") } }
        )
        editing?.let { passage ->
            val ordered = library.passages.sortedWith(compareBy<Passage> { it.start }.thenBy { it.id })
            val index = ordered.indexOfFirst { it.id == passage.id }
            val nextReview = (ordered.drop(index + 1) + ordered.take(index.coerceAtLeast(0))).firstOrNull { it.uncertain && !it.reviewed && it.id != passage.id }
            EditSheet(
                passage, playing == passage.id, audioAvailable, { model.play(passage) }, { model.player.pause(); editingId = null },
                hasNextReview = nextReview != null,
                audioUnavailableReason = if (live.noteId != null && !live.processing) "Audio unavailable during recording." else "Original audio is unavailable.",
                onDirtyChange = { editorDirty = it }, onSavingChange = { formSaving = it },
            ) { text, next ->
                val saved = model.edit(passage, text)
                if (saved) {
                    // Do not expose Copy/Share until the displayed transcript includes this commit.
                    val refreshed = model.library.first { refreshed ->
                        refreshed.error != null || (refreshed.selectedId == passage.noteId && (refreshed.note == null || refreshed.passages.any {
                            it.id == passage.id && it.text == text.trim() && it.reviewed
                        }))
                    }
                    model.player.pause()
                    editingId = if (next && refreshed.error == null) nextReview?.id else null
                    if (refreshed.error != null) notify("Your edits were saved. Reload this session to see them.")
                }
                saved
            }
        }
        if (dialog == "rename" && note != null) RenameSessionDialog(note, { dialog = null },
            onDirtyChange = { renameDirty = it }, onSavingChange = { formSaving = it },
        ) { title -> model.renameSession(note.id, title) }
        if (pendingOpenTarget != null) AlertDialog(
            onDismissRequest = { pendingOpenTarget = null }, title = { Text("Leave this session?") },
            text = { Text(if (formSaving) "Finishing your save…" else "Unsaved changes will be discarded.") },
            confirmButton = { TextButton(enabled = !formSaving, onClick = {
                val target = pendingOpenTarget
                performOpen(target?.takeIf { it.startsWith("session:") }?.removePrefix("session:"))
            }) { Text("Discard & open", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingOpenTarget = null }) { Text("Keep editing") } },
        )
        if (dialog in listOf("delete", "audio") && note != null) AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (dialog == "delete") "Delete this session?" else "Remove its audio?") },
            text = { Text(if (dialog == "delete") "The text and recording will be permanently deleted from this device." else "The text will stay. You won’t be able to listen to passages again. Frees ${"%.1f".format(library.audioBytes / 1048576.0)} MB.") },
            confirmButton = { TextButton(onClick = {
                if (dialog == "delete") { model.delete(note.id); open(null) } else model.removeAudio(note.id)
                dialog = null
            }) { Text(if (dialog == "delete") "Delete" else "Remove audio", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }
        )
    }
}

@Composable private fun LibraryCaptureDock(language: String, loaded: Boolean, chooseLanguage: () -> Unit, record: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    @Composable fun LanguageAction() {
        TextButton(onClick = chooseLanguage, modifier = Modifier.heightIn(min = 52.dp)
            .semantics { contentDescription = "Speech language: $language" }) {
            Text(language, style = MaterialTheme.typography.labelLarge)
            Glyph(Symbol.Chevron, Modifier.padding(start = 6.dp).size(12.dp).rotate(90f))
        }
    }
    @Composable fun RecordAction(modifier: Modifier) {
        Button(onClick = record, enabled = loaded, shape = androidx.compose.foundation.shape.CircleShape,
            modifier = modifier.heightIn(min = 54.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            Glyph(Symbol.Record, Modifier.size(16.dp), if (loaded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Record", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (fontScale >= 1.5f || maxWidth < 280.dp) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LanguageAction()
            RecordAction(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LanguageAction()
            RecordAction(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SessionAudioDock(noteId: String, playback: app.focal.speech.PlaybackState, durationMs: Long, play: () -> Unit, seek: (Long) -> Unit) {
    val current = playback.takeIf { it.noteId == noteId }
    val duration = (current?.durationMs?.takeIf { it > 0 } ?: durationMs).coerceAtLeast(1)
    var scrub by remember(noteId) { mutableStateOf<Float?>(null) }
    val position = (scrub?.toLong() ?: current?.positionMs ?: 0).coerceIn(0, duration)
    val fontScale = LocalDensity.current.fontScale
    val interaction = remember { MutableInteractionSource() }
    val sliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.onSurface,
        activeTrackColor = MaterialTheme.colorScheme.onSurface,
        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
    )
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        val compact = fontScale >= 1.5f || maxWidth < 280.dp
        Column {
            if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Transcript.time(position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Transcript.time(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = play, modifier = Modifier.size(48.dp).semantics { contentDescription = if (current?.isPlaying == true) "Pause original audio" else "Play original audio" }) {
                    Glyph(if (current?.isPlaying == true) Symbol.Pause else Symbol.Play, Modifier.size(18.dp))
                }
                Slider(value = position.toFloat(),
                    onValueChange = { scrub = it }, onValueChangeFinished = { scrub?.let { seek(it.toLong()) }; scrub = null },
                    colors = sliderColors, interactionSource = interaction,
                    thumb = {
                        SliderDefaults.Thumb(
                            interactionSource = interaction, colors = sliderColors,
                            modifier = Modifier.size(12.dp), thumbSize = DpSize(12.dp, 12.dp),
                        )
                    },
                    track = { state ->
                        SliderDefaults.Track(
                            sliderState = state, modifier = Modifier.height(3.dp), colors = sliderColors,
                            drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 1.5.dp,
                        )
                    },
                    valueRange = 0f..duration.toFloat(), modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                        contentDescription = "Audio position"
                        stateDescription = "${Transcript.time(position)} of ${Transcript.time(duration)}"
                    })
                if (!compact) Text(Transcript.time(position), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 38.dp))
            }
        }
    }
}

@Composable private fun RecordingControls(live: LiveSession, pause: () -> Unit, finish: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = pause, modifier = Modifier.size(54.dp).semantics { contentDescription = if (live.paused) "Resume recording" else "Pause recording" }) {
            Glyph(if (live.paused) Symbol.Play else Symbol.Pause, Modifier.size(18.dp))
        }
        Button(onClick = finish, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.weight(1f).heightIn(min = 54.dp)
            .semantics { contentDescription = "Finish recording" }, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            Glyph(Symbol.Stop, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
            Text("Finish", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable internal fun PrimaryButton(label: String, symbol: Symbol, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = androidx.compose.foundation.shape.CircleShape, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
        Glyph(symbol, Modifier.size(20.dp), color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
@Composable internal fun IconAction(symbol: Symbol, label: String, modifier: Modifier = Modifier, click: () -> Unit) {
    IconButton(onClick = click, modifier = modifier.semantics { contentDescription = label }) { Glyph(symbol) }
}
internal fun date(millis: Long) = SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date(millis))
