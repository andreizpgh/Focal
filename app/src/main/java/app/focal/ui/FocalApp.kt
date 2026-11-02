package app.focal.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import app.focal.FocalViewModel
import app.focal.LibraryState
import app.focal.core.Transcript
import app.focal.data.Note
import app.focal.data.Passage
import app.focal.data.Preferences
import app.focal.recording.LiveSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FocalApp(model: FocalViewModel, openedNote: StateFlow<Pair<String?, Long>>) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val ready by model.modelReady.collectAsStateWithLifecycle()
    val preparing by model.preparing.collectAsStateWithLifecycle()
    val playing by model.player.playing.collectAsStateWithLifecycle()
    val requested by openedNote.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var modelOpen by rememberSaveable { mutableStateOf(false) }
    var reviewOnly by rememberSaveable(selected) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Passage?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current

    fun open(id: String?) { selected = id; model.select(id); settingsOpen = false }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    LaunchedEffect(selected) { model.select(selected) }
    LaunchedEffect(requested) { requested.first?.let { open(it) } }
    LaunchedEffect(live.noteId) { live.noteId?.let { open(it) } }
    LaunchedEffect(live.message) { if (live.noteId == null && live.message.isNotBlank()) notify(live.message) }
    LaunchedEffect(Unit) { model.errors.collect { snackbar.showSnackbar(it) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.player.stop() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.player.stop() }
    }
    BackHandler(selected != null || settingsOpen) { if (settingsOpen) settingsOpen = false else open(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.RECORD_AUDIO] == true || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) model.record()
        else scope.launch {
            if (snackbar.showSnackbar("Allow microphone access to record a note.", "Settings") == SnackbarResult.ActionPerformed)
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.importModel(it) } }
    fun start() {
        if (!ready) { modelOpen = true; return }
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) model.record() else permissions.launch(needed.toTypedArray())
    }
    val note = library.note?.takeIf { it.id == selected }
    val active = note != null && live.noteId == note.id
    val transcript = library.passages.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")

    FocalTheme(settings.theme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selected != null || settingsOpen) {
                        IconAction(Symbol.Back, "Back to notes") { if (settingsOpen) settingsOpen = false else open(null) }
                        Text(if (settingsOpen) "Settings" else "Notes", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    } else {
                        Glyph(Symbol.Logo, Modifier.padding(start = 8.dp).size(28.dp))
                        Text("focal", fontSize = 27.sp, letterSpacing = (-1).sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    }
                    if (!settingsOpen && note == null) IconAction(Symbol.Settings, "Settings") { settingsOpen = true }
                    if (!settingsOpen && note != null) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconAction(Symbol.More, "Note options") { menu = true }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                                DropdownMenuItem(text = { Text(if (playing == -1L) "Stop playback" else "Listen to recording") }, enabled = !active && library.audioBytes > 0,
                                    onClick = { menu = false; model.playNote(note) })
                                DropdownMenuItem(text = { Text("Share text") }, enabled = transcript.isNotBlank(), onClick = {
                                    menu = false
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, transcript), "Share note"))
                                })
                                DropdownMenuItem(text = { Text("Remove audio") }, enabled = !active && library.pending == 0 && library.audioBytes > 0, onClick = { menu = false; confirm = "audio" })
                                DropdownMenuItem(text = { Text("Delete note", color = MaterialTheme.colorScheme.error) }, enabled = !active, onClick = { menu = false; confirm = "delete" })
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (!settingsOpen) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 18.dp)) {
                            when {
                                active && !live.processing -> RecordingControls(live, model::pause, model::finish)
                                active -> {
                                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                                    Text("${live.message.ifBlank { "Finishing transcript…" }}\nYou can leave Focal open in the background.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                                }
                                note != null -> {
                                    if (playing == -1L) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Playing recording", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        IconAction(Symbol.Stop, "Stop playback", model.player::stop)
                                    }
                                    PrimaryButton("Copy text", Symbol.Copy, transcript.isNotBlank()) {
                                    val clip = ClipData.newPlainText("Focal note", transcript)
                                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                                    if (Build.VERSION.SDK_INT < 33) notify("Text copied")
                                    }
                                }
                                live.noteId != null -> PrimaryButton(if (live.processing) "Back to transcript" else "Back to recording", Symbol.Arrow) { open(live.noteId) }
                                else -> {
                                    PrimaryButton(if (preparing) "Preparing…" else "Record a note", Symbol.Mic, !preparing, ::start)
                                    Text("${Preferences.languages[settings.language]}  ·  On this device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp))
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            AnimatedContent(targetState = when { settingsOpen -> "settings"; selected != null -> "note"; else -> "library" }, label = "screen", modifier = Modifier.padding(padding)) { screen ->
                when (screen) {
                    "settings" -> SettingsScreen(settings.language, settings.theme, model.app.preferences::language, model.app.preferences::theme)
                    "note" -> if (note != null) NoteScreen(note, library, live.takeIf { active }, reviewOnly, { reviewOnly = !reviewOnly }, { editing = it }, { model.retry(note.id) })
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    else -> LibraryScreen(library.notes, library.loaded, live, ::open)
                }
            }
        }
        if (modelOpen) AlertDialog(
            onDismissRequest = { modelOpen = false }, title = { Text(if (ready) "Speech model ready" else "Add the speech model") },
            text = { Text(if (ready) "You’re ready to record offline." else "This build does not include the speech model. Import ggml-base-q5_1.bin (multilingual Whisper base). The model stays on this device.") },
            confirmButton = { TextButton(onClick = { modelOpen = false; if (!ready) import.launch(arrayOf("*/*")) }) { Text(if (ready) "Done" else "Import model") } },
            dismissButton = { if (!ready) TextButton(onClick = { modelOpen = false }) { Text("Cancel") } }
        )
        editing?.let { passage ->
            val available = library.audioBytes > 0 && !active
            EditSheet(passage, playing == passage.id, available, { model.play(passage) }, {
                model.player.stop(); editing = null
            }) { text, next ->
                model.edit(passage, text); model.player.stop()
                editing = if (next) library.passages.firstOrNull { it.uncertain && !it.reviewed && it.id > passage.id } else null
            }
        }
        if (renaming && note != null) {
            var title by remember(note.id) { mutableStateOf(note.title) }
            AlertDialog(onDismissRequest = { renaming = false }, title = { Text("Note title") }, text = {
                OutlinedTextField(title, { title = it.take(120) }, singleLine = true, label = { Text("Title") })
            }, confirmButton = { TextButton(onClick = { model.rename(note.id, title); renaming = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
        }
        if (confirm != null && note != null) AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (confirm == "delete") "Delete this note?" else "Remove its audio?") },
            text = { Text(if (confirm == "delete") "The text and recording will be permanently deleted from this device." else "The text will stay. You won’t be able to listen to passages again. Frees ${"%.1f".format(library.audioBytes / 1048576.0)} MB.") },
            confirmButton = { TextButton(onClick = {
                if (confirm == "delete") { model.delete(note.id); open(null) } else model.removeAudio(note.id)
                confirm = null
            }) { Text(if (confirm == "delete") "Delete" else "Remove audio", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable private fun LibraryScreen(notes: List<Note>, loaded: Boolean, live: LiveSession, open: (String?) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Notes", style = MaterialTheme.typography.displayLarge)
                Text(notes.size.toString().padStart(2, '0'), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (loaded && notes.isEmpty()) item {
            Column(Modifier.padding(vertical = 56.dp)) {
                Glyph(Symbol.Logo, Modifier.size(44.dp), MaterialTheme.colorScheme.outline)
                Text("No notes yet.", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
                Text("Record at your pace.\nReview and copy when you’re done.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        items(notes, key = { it.id }) { note ->
            Column(Modifier.fillMaxWidth().clickable { open(note.id) }.padding(vertical = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(note.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Glyph(Symbol.Arrow, Modifier.padding(start = 16.dp).size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (note.preview.isNotBlank()) Text(note.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                val status = when {
                    live.noteId == note.id -> if (live.processing) "Transcribing" else if (live.paused) "Paused" else "Recording"
                    note.state in listOf("INTERRUPTED", "ERROR") -> "Needs attention"
                    note.reviewCount > 0 -> "${note.reviewCount} to review"
                    else -> ""
                }
                Text(listOf(date(note.created), Transcript.time(note.duration), status).filter { it.isNotEmpty() }.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable private fun NoteScreen(note: Note, library: LibraryState, live: LiveSession?, reviewOnly: Boolean,
                                  toggleReview: () -> Unit, edit: (Passage) -> Unit, retry: () -> Unit) {
    val review = library.passages.count { it.uncertain && !it.reviewed }
    val passages = if (reviewOnly) library.passages.filter { it.uncertain && !it.reviewed } else library.passages
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        item {
            Text(if (live != null && !live.processing) "VOICE NOTE" else date(note.created).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (note.title == "Untitled note" && live != null) "New recording" else note.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
            Text("${Transcript.time(live?.duration ?: note.duration)}  ·  ${Preferences.languages[note.language] ?: note.language}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (live != null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Meter(if (live.paused || live.processing) 0f else live.level)
                    Text(if (live.paused) "Paused" else live.message.ifBlank { "Listening" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else Spacer(Modifier.height(24.dp))
            if (note.error.isNotBlank() && live == null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(note.error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        if (library.pending > 0) TextButton(onClick = retry) { Text("Continue transcription") }
                    }
                }
            }
            if (library.passages.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TRANSCRIPT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(selected = reviewOnly, onClick = toggleReview, label = { Text(if (reviewOnly) "Show all" else "$review to review") })
                }
                if (review > 0) Text("Marked passages may need a closer listen.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
                else if (live == null) Text("Tap any passage to listen or edit.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
        if (passages.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
                Text(when { reviewOnly -> "All checked."; live != null -> "Your words will appear here."; else -> "No speech transcribed." }, style = MaterialTheme.typography.titleLarge)
                Text(when { reviewOnly -> "You can still edit any passage in the full transcript."; live != null -> "Long pauses are fine. Recording continues with the screen off."; else -> "Try speaking a little closer to the microphone." }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        items(passages, key = { it.id }) { passage ->
            val flagged = passage.uncertain && !passage.reviewed
            Surface(color = if (flagged) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { edit(passage) }
                    .semantics { contentDescription = "${if (flagged) "Review passage" else "Edit passage"} at ${Transcript.time(passage.start)}" }) {
                Column(Modifier.padding(horizontal = if (flagged) 16.dp else 0.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(Transcript.time(passage.start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (flagged) Text("CHECK", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text(passage.text.ifBlank { "Speech wasn’t clear. Tap to listen and add text." }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
    }
}

@Composable private fun RecordingControls(live: LiveSession, pause: () -> Unit, finish: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = pause, modifier = Modifier.size(60.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Box(Modifier.semantics { contentDescription = if (live.paused) "Resume recording" else "Pause recording" }) { Glyph(if (live.paused) Symbol.Play else Symbol.Pause) }
        }
        Button(onClick = finish, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).heightIn(min = 60.dp)) {
            Glyph(Symbol.Stop, color = MaterialTheme.colorScheme.onPrimary)
            Text("Finish recording", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable private fun Meter(level: Float) {
    val value by animateFloatAsState(level, label = "sound level")
    Row(Modifier.height(28.dp).width(38.dp).semantics { contentDescription = "Microphone level" }, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0.5f,0.85f,1f,0.7f,0.4f).forEach { scale ->
            Box(Modifier.width(4.dp).height((4 + value * 24 * scale).dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EditSheet(passage: Passage, playing: Boolean, audio: Boolean, play: () -> Unit, dismiss: () -> Unit, save: (String, Boolean) -> Unit) {
    var text by remember(passage.id) { mutableStateOf(passage.text) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (passage.uncertain && !passage.reviewed) "Review passage" else "Edit passage", style = MaterialTheme.typography.headlineMedium)
                Text(Transcript.time(passage.start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = play, enabled = audio, contentPadding = PaddingValues(vertical = 12.dp)) {
                Glyph(if (playing) Symbol.Pause else Symbol.Play, Modifier.size(18.dp), if (audio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                Text(if (!audio) "Audio unavailable during recording or after removal" else if (playing) "Stop playback" else "Listen to this part", modifier = Modifier.padding(start = 10.dp))
            }
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp), textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(14.dp), label = { Text("Transcript") })
            Spacer(Modifier.height(20.dp))
            PrimaryButton(if (passage.uncertain && !passage.reviewed) "Save & next" else "Save changes", Symbol.Check) { save(text, passage.uncertain && !passage.reviewed) }
            if (passage.uncertain && !passage.reviewed) TextButton(onClick = { save(text, false) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Save & close") }
        }
    }
}

@Composable private fun SettingsScreen(language: String, theme: String, setLanguage: (String) -> Unit, setTheme: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp)) {
        Text("RECORDING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().clickable { choosing = true }.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speech language", style = MaterialTheme.typography.titleLarge)
                Text("For your next recording", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(Preferences.languages[language].orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("APPEARANCE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 32.dp, bottom = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("light" to "Light", "dark" to "Dark", "system" to "System").forEach { (id, name) ->
                FilterChip(selected = theme == id, onClick = { setTheme(id) }, label = { Text(name) })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 28.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("Stored on this device", style = MaterialTheme.typography.titleLarge)
        Text("Audio and text stay in Focal’s private storage. No accounts, analytics, or cloud backup. Copy or share anything you want to keep before uninstalling.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        Text("Record with the screen off or while using another app. Force-stopping Focal or restarting the phone ends recording. Some phones also restrict background apps.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        Text("Review cues are suggestions. Speech recognition can miss errors even in unmarked text.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(36.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Symbol.Logo, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Focal 0.1  ·  Free & open source", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/andreizpgh/Focal".toUri())) }) { Text("Source code") }
            TextButton(onClick = { scope.launch { licenses = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } } } }) { Text("Licenses") }
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Speech language") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Preferences.languages.forEach { (id, name) ->
                Row(Modifier.fillMaxWidth().clickable { setLanguage(id); choosing = false }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (id == language) Glyph(Symbol.Check, Modifier.size(20.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } })
    licenses?.let { text -> AlertDialog(onDismissRequest = { licenses = null }, title = { Text("Open-source licenses") },
        text = { Text(text, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { licenses = null }) { Text("Done") } }) }
}

@Composable private fun PrimaryButton(label: String, symbol: Symbol, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
        Glyph(symbol, color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
@Composable private fun IconAction(symbol: Symbol, label: String, click: () -> Unit) {
    IconButton(onClick = click, modifier = Modifier.semantics { contentDescription = label }) { Glyph(symbol) }
}
private fun date(millis: Long) = SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date(millis))
