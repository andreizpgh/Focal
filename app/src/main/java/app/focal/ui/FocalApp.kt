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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    var contextualNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailId = selected ?: contextualNoteId
    var modelRetryId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val searchQuery = searchValue.text
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
    val notifications = remember(context) { NotificationManagerCompat.from(context) }
    var notificationsEnabled by remember(notifications) { mutableStateOf(notifications.areNotificationsEnabled()) }
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

    fun closeDialog() { dialog = null; contextualNoteId = null }
    fun showSessionActions(id: String) {
        if (dialog != null || editingId != null || editorDirty || renameDirty || formSaving || pendingOpenTarget != null) return
        when {
            selected == id -> Unit
            destination == Destination.Sessions && library.notes.any { it.id == id } -> contextualNoteId = id
            else -> return
        }
        dismissInput()
        dialog = "actions"
    }
    fun performOpen(id: String?) {
        dismissInput(); editingId = null; closeDialog(); pendingOpenTarget = null
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
    LaunchedEffect(detailId) { model.select(detailId) }
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
    DisposableEffect(lifecycle, notifications) {
        val observer = LifecycleEventObserver { _, event ->
            // Commit an already hidden search IME before Android can restore its focused editor.
            if (event == Lifecycle.Event.ON_PAUSE && destination == Destination.Sessions && searchInputActive && searchImeSeen &&
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false) dismissInput()
            if (event == Lifecycle.Event.ON_STOP) model.player.pause()
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = notifications.areNotificationsEnabled()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    dismissPermissionNotice()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.player.pause() }
    }
    BackHandler(destination != Destination.Sessions) { open(null) }
    BackHandler(destination == Destination.Sessions && searchOpen) {
        searchOpen = false; searchValue = TextFieldValue(""); dismissInput()
    }
    BackHandler(dialog == "actions", onBack = ::closeDialog)
    LaunchedEffect(detailId, library.selectedId, library.note, library.loaded, library.error) {
        if (detailId != null && library.loaded && library.selectedId == detailId && library.note == null && library.error == null) {
            if (selected != null) open(null) else closeDialog()
            notify("Session is no longer available.")
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        dismissPermissionNotice()
        notificationsEnabled = notifications.areNotificationsEnabled()
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
    val actionNote = library.note?.takeIf { it.id == detailId && library.selectedId == detailId }
    val note = actionNote?.takeIf { it.id == selected }
    val active = note != null && live.noteId == note.id
    val audioAvailable = library.audioBytes > 0 && (live.noteId == null || live.processing)
    val actionActive = detailId != null && live.noteId == detailId
    val hasUnprocessedAudio = library.pending > 0 || library.hasUnpublishedTail
    val canRemoveAudio = actionNote != null && !actionActive && !hasUnprocessedAudio && library.audioBytes > 0 && library.error == null
    val transcript = remember(library.passages) { library.passages.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n") }
    val canShare = actionNote != null && transcript.isNotBlank() && library.error == null
    fun allowTextTransfer(action: String): Boolean {
        if (fitsTextTransferBudget(transcript)) return true
        notify("This transcript is too long to $action at once. Copy a smaller selection from the passage editor.")
        return false
    }
    fun copyTranscript(): Boolean {
        if (!allowTextTransfer("copy")) return false
        return try {
            val clip = ClipData.newPlainText("Focal transcript", transcript)
            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            true
        } catch (_: RuntimeException) {
            notify("Couldn't copy text. Try copying a smaller selection from the passage editor.")
            false
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
                AnimatedVisibility(
                    visible = !compactSearch,
                    enter = expandVertically(animationSpec = tween(180), expandFrom = Alignment.Top) + fadeIn(tween(120)),
                    exit = shrinkVertically(animationSpec = tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
                ) {
                    Box(Modifier.fillMaxWidth()
                        .onSizeChanged { if (destination == Destination.Sessions) sessionsHeaderHeight = it.height }
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BoxWithConstraints(Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                            val headerStyle = if (LocalDensity.current.fontScale >= 1.5f || maxWidth < 260.dp)
                                MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium
                            when (destination) {
                                Destination.Sessions -> SessionsHeader(
                                    headerStyle = headerStyle,
                                    attentionCount = library.notes.count(::sessionNeedsAttention),
                                    attentionOnly = attentionOnly,
                                    toggleAttention = {
                                        attentionOnly = !attentionOnly
                                        libraryListState.requestScrollToItem(0)
                                    },
                                ) {
                                    if (library.notes.isNotEmpty() || searchOpen || searchQuery.isNotBlank()) {
                                        val searchActionFocus = remember { FocusRequester() }
                                        IconAction(Symbol.Search, if (searchOpen) "Close search" else "Search sessions",
                                            Modifier.focusRequester(searchActionFocus)) {
                                            searchOpen = !searchOpen
                                            setSearchInputActive(searchOpen)
                                            if (!searchOpen) { searchValue = TextFieldValue(""); dismissInput() }
                                        }
                                        LaunchedEffect(searchInputActive, searchHasFocus) {
                                            // Keyboard navigation can immediately refocus the field after clearFocus.
                                            if (!searchInputActive && searchHasFocus) searchActionFocus.requestFocus()
                                        }
                                    }
                                    IconAction(Symbol.Settings, "Settings") {
                                        if (dialog == null) { dismissInput(); destination = Destination.Settings }
                                    }
                                }
                                else -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconAction(Symbol.Back, "Back to sessions") { open(null) }
                                    Text(
                                        if (destination == Destination.Settings) "Settings" else "Session",
                                        style = headerStyle,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
                                    )
                                    if (note != null) IconAction(Symbol.More, "Session options") { showSessionActions(note.id) }
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (destination != Destination.Settings) AnimatedVisibility(
                    visible = destination != Destination.Sessions || !searchOpen || live.noteId != null,
                    enter = expandVertically(animationSpec = tween(180), expandFrom = Alignment.Bottom) + fadeIn(tween(120)),
                    exit = shrinkVertically(animationSpec = tween(180), shrinkTowards = Alignment.Bottom) + fadeOut(tween(120)),
                ) {
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
                                    CopyTranscriptButton(note.id, transcript, enabled = canShare, copy = ::copyTranscript)
                                }
                                live.noteId != null -> PrimaryButton(
                                    if (live.processing) "Open transcript" else "Open recording", Symbol.Audio
                                ) { open(live.noteId) }
                                destination == Destination.Sessions -> {
                                    LibraryCaptureDock(
                                        settings.language, library.loaded && library.error == null,
                                        selectLanguage = model.app.preferences::language,
                                        dismissInput = ::dismissInput, record = ::start,
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
                            settings.theme, model.app.preferences::theme, ready, preparing, setupError, { openModel() }, ::notify,
                        )
                        Destination.Sessions -> LibraryScreen(
                            library.notes, library.loaded, live, ::open, searchValue, {
                                searchValue = it.copy(text = Transcript.truncateUtf16(it.text, SEARCH_QUERY_MAX_CHARS))
                            },
                            searchOpen, { searchOpen = it; if (!it) searchInputActive = false }, library.error, model::retryLibrary,
                            attentionOnly = attentionOnly, setAttentionOnly = { attentionOnly = it }, listState = libraryListState,
                            inputActive = searchInputActive, setInputActive = ::setSearchInputActive,
                            searchFocusChanged = { searchHasFocus = it }, searchFieldMeasured = { searchFieldHeight = it },
                            onSessionActions = ::showSessionActions,
                        )
                        is Destination.Session -> if (note != null) NoteScreen(
                            note, library, live.takeIf { active }, reviewOnly, { reviewOnly = !reviewOnly }, { if (library.error == null) editingId = it.id else notify("Reload this session before editing.") }, { if (ready) model.retry(note.id) else openModel(note.id) },
                            playback.isPlaying && playback.noteId == note.id, audioAvailable, { model.playNote(note) }, { dialog = "rename" },
                            seek = { model.seekNote(note, it) }, playbackPosition = playback.takeIf { it.noteId == note.id }?.positionMs,
                            retryLoad = model::retryLibrary, notificationsEnabled = notificationsEnabled,
                        ) else if (library.error != null) DataReadError("Couldn't load this session", library.error.orEmpty(), model::retryLibrary,
                            Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                FocusProcessingIndicator(contentDescription = "Loading session")
                            }
                    }
                }
            }
        }
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
        if (dialog == "actions" && detailId != null) {
            val loading = library.selectedId != detailId
            val activeReason = if (actionActive) {
                if (live.processing) "Transcription is running" else "Recording is active"
            } else null
            val shareReason = when {
                library.error != null || actionNote == null -> "Reload this session"
                transcript.isBlank() -> "No text to share"
                else -> null
            }
            val audioReason = activeReason ?: when {
                library.error != null || actionNote == null -> "Reload this session"
                hasUnprocessedAudio -> "Transcription is unfinished"
                library.audioBytes == 0L -> "No original audio"
                else -> null
            }
            SessionActionsSheet(
                title = actionNote?.title ?: library.notes.firstOrNull { it.id == detailId }?.title ?: "Session options",
                loading = loading, loaded = actionNote != null,
                shareReason = shareReason.takeUnless { loading }, audioReason = audioReason.takeUnless { loading },
                deleteReason = activeReason.takeUnless { loading },
                onDismiss = ::closeDialog,
            ) { action ->
                when (action) {
                    SessionAction.Rename -> if (actionNote != null) dialog = "rename" else closeDialog()
                    SessionAction.Share -> { closeDialog(); if (canShare) shareTranscript() }
                    SessionAction.RemoveAudio -> if (canRemoveAudio) dialog = "audio" else closeDialog()
                    SessionAction.Delete -> if (actionNote != null && !actionActive) dialog = "delete" else closeDialog()
                }
            }
        }
        if (dialog == "rename" && actionNote != null) RenameSessionDialog(actionNote, ::closeDialog,
            onDirtyChange = { renameDirty = it }, onSavingChange = { formSaving = it },
        ) { title -> model.renameSession(actionNote.id, title) }
        if (pendingOpenTarget != null) AlertDialog(
            onDismissRequest = { pendingOpenTarget = null }, title = { Text("Leave this session?") },
            text = { Text(if (formSaving) "Finishing your save…" else "Unsaved changes will be discarded.") },
            confirmButton = { TextButton(enabled = !formSaving, onClick = {
                val target = pendingOpenTarget
                performOpen(target?.takeIf { it.startsWith("session:") }?.removePrefix("session:"))
            }) { Text("Discard & open", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingOpenTarget = null }) { Text("Keep editing") } },
        )
        if ((dialog == "delete" || dialog == "audio") && actionNote != null) AlertDialog(
            onDismissRequest = ::closeDialog,
            title = { Text(if (dialog == "delete") "Delete this session?" else "Remove its audio?") },
            text = { Text(if (dialog == "delete") "The text and recording will be permanently deleted from this device." else "The text will stay. You won’t be able to listen to passages again. Frees ${"%.1f".format(library.audioBytes / 1048576.0)} MB.") },
            confirmButton = { TextButton(enabled = if (dialog == "delete") !actionActive else canRemoveAudio, onClick = {
                if (dialog == "delete") {
                    model.delete(actionNote.id)
                    if (selected == actionNote.id) open(null)
                } else model.removeAudio(actionNote.id)
                closeDialog()
            }) { Text(if (dialog == "delete") "Delete" else "Remove audio", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = ::closeDialog) { Text("Cancel") } }
        )
    }
}

@Composable private fun SessionsHeader(
    headerStyle: TextStyle,
    attentionCount: Int, attentionOnly: Boolean, toggleAttention: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val showFilter = attentionCount > 0 || attentionOnly
    @Composable fun ReviewFilter() {
        FilterChip(
            selected = attentionOnly,
            onClick = toggleAttention,
            label = { Text(if (attentionOnly) "Show all" else "$attentionCount to review") },
            colors = focalChipColors(), shape = CircleShape,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 4.dp)) {
        val inlineFilter = fontScale <= 1.15f && maxWidth >= 340.dp
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Sessions", modifier = Modifier.weight(1f).semantics { heading() },
                    style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (inlineFilter && showFilter) ReviewFilter()
                actions()
            }
            if (!inlineFilter && showFilter) ReviewFilter()
        }
    }
}

@Composable private fun LibraryCaptureDock(
    language: String, loaded: Boolean, selectLanguage: (String) -> Unit, dismissInput: () -> Unit, record: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    @Composable fun RecordAction(modifier: Modifier) {
        Button(onClick = record, enabled = loaded, shape = CircleShape,
            modifier = modifier.heightIn(min = 54.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            Glyph(Symbol.Record, Modifier.size(16.dp), if (loaded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Record", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (fontScale >= 1.5f || maxWidth < 280.dp) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CaptureLanguagePicker(language, selectLanguage, dismissInput)
            RecordAction(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CaptureLanguagePicker(language, selectLanguage, dismissInput)
            RecordAction(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun CaptureLanguagePicker(language: String, select: (String) -> Unit, dismissInput: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var anchorTop by remember { mutableIntStateOf(0) }
    val label = Preferences.languages.getValue(language)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val gap = with(density) { 8.dp.roundToPx() }
    val top = insets.getTop(density) + gap
    val left = insets.getLeft(density, direction) + gap
    val right = insets.getRight(density, direction) + gap
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.onGloballyPositioned { anchorTop = it.boundsInWindow().top.roundToInt() }) {
        OutlinedButton(
            onClick = { dismissInput(); expanded = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Speech language: $label" },
            shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
        ) {
            LanguageGlobe()
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            Glyph(Symbol.Chevron, Modifier.size(13.dp).rotate(if (expanded) 90f else -90f), color)
        }
        if (expanded) {
            val position = remember(top, left, right, gap) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
                    ): IntOffset {
                        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
                        val end = (windowSize.width - right - popupContentSize.width).coerceAtLeast(left)
                        return IntOffset(start.coerceIn(left, end),
                            (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(top))
                    }
                }
            }
            val available = with(density) { (anchorTop - top - gap).coerceAtLeast(1).toDp() }
            Popup(popupPositionProvider = position, onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)) {
                val selectedRequester = remember(language) { BringIntoViewRequester() }
                var selectedLaidOut by remember(language) { mutableStateOf(false) }
                LaunchedEffect(language, selectedLaidOut, available) {
                    if (selectedLaidOut) {
                        withFrameNanos { }
                        selectedRequester.bringIntoView()
                    }
                }
                Surface(
                    modifier = Modifier.width(212.dp).heightIn(max = minOf(360.dp, available))
                        .semantics { paneTitle = "Speech language" },
                    shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp).selectableGroup()) {
                        Preferences.languages.forEach { (id, name) ->
                            val selected = id == language
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                                    .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
                                    .then(if (selected) Modifier.bringIntoViewRequester(selectedRequester)
                                        .onGloballyPositioned { coordinates ->
                                            if (coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0)
                                                selectedLaidOut = true
                                        } else Modifier)
                                    .selectable(selected, role = Role.RadioButton, onClick = { select(id); expanded = false })
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (selected) Glyph(Symbol.Check, Modifier.size(17.dp), color)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LanguageGlobe() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.25.dp.toPx()
        val radius = size.minDimension / 2 - stroke / 2
        drawCircle(color, radius = radius, style = Stroke(stroke))
        drawOval(color, Offset(center.x - radius * .43f, center.y - radius),
            Size(radius * .86f, radius * 2), style = Stroke(stroke))
        drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), stroke)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SessionAudioDock(noteId: String, playback: app.focal.speech.PlaybackState, durationMs: Long, play: () -> Unit, seek: (Long) -> Unit) {
    val current = playback.takeIf { it.noteId == noteId }
    val duration = (current?.durationMs?.takeIf { it > 0 } ?: durationMs).coerceAtLeast(1)
    var scrub by remember(noteId) { mutableStateOf<Float?>(null) }
    val position = (scrub?.toLong() ?: current?.positionMs ?: 0).coerceIn(0, duration)
    val fontScale = LocalDensity.current.fontScale
    val fill = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.outline
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
                    thumb = {
                        Canvas(Modifier.size(16.dp)) { drawCircle(fill) }
                    },
                    track = {
                        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                            val radius = CornerRadius(size.height / 2)
                            val filledWidth = size.width * (position.toFloat() / duration)
                            drawRoundRect(track, cornerRadius = radius)
                            if (filledWidth > 0f) {
                                val rtl = layoutDirection == LayoutDirection.Rtl
                                clipRect(left = if (rtl) size.width - filledWidth else 0f,
                                    right = if (rtl) size.width else filledWidth) {
                                    drawRoundRect(fill, cornerRadius = radius)
                                }
                            }
                        }
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

@Composable internal fun CopyTranscriptButton(noteId: String, transcript: String, enabled: Boolean, copy: () -> Boolean) {
    key(noteId, transcript) {
        var successPulse by remember { mutableIntStateOf(0) }
        LaunchedEffect(successPulse) {
            if (successPulse != 0) {
                delay(1_800)
                successPulse = 0
            }
        }
        val copied = enabled && successPulse != 0
        Button(
            onClick = { successPulse = if (copy()) successPulse + 1 else 0 }, enabled = enabled,
            shape = CircleShape, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics {
                text = AnnotatedString(if (copied) "Copied" else "Copy text")
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            val color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Box(Modifier.clearAndSetSemantics {}, contentAlignment = Alignment.CenterStart) {
                // Reserve the full label width so the check and text fade in place.
                Text("Copy text", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 32.dp).alpha(0f))
                Crossfade(targetState = copied, animationSpec = tween(150), label = "Copy feedback") { success ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(if (success) Symbol.Check else Symbol.Copy, Modifier.size(20.dp), color)
                        Text(if (success) "Copied" else "Copy text", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable internal fun IconAction(symbol: Symbol, label: String, modifier: Modifier = Modifier, click: () -> Unit) {
    IconButton(onClick = click, modifier = modifier.semantics { contentDescription = label }) { Glyph(symbol) }
}
internal fun date(millis: Long) = SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date(millis))
