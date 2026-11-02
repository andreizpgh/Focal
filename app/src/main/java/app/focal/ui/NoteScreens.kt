package app.focal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focal.LibraryState
import app.focal.core.Transcript
import app.focal.data.Note
import app.focal.data.Passage
import app.focal.data.Preferences
import app.focal.data.fitsPassageDraftBudget
import app.focal.recording.LiveSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Date
import android.text.format.DateFormat

@Composable internal fun BrandMark(size: Int = 26) {
    Glyph(Symbol.Logo, Modifier.size(size.dp), MaterialTheme.colorScheme.onSurface)
}

@Composable internal fun LibraryScreen(
    notes: List<Note>, loaded: Boolean, live: LiveSession, open: (String?) -> Unit,
    query: String = "", setQuery: (String) -> Unit = {},
    searchOpen: Boolean = false, setSearchOpen: (Boolean) -> Unit = {},
    error: String? = null, retryLoad: () -> Unit = {},
    attentionOnly: Boolean = false, setAttentionOnly: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    focusSearch: Boolean = false, searchFocused: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun needsAttention(note: Note) = note.reviewCount > 0 || note.state in listOf("ERROR", "INTERRUPTED")
    val attentionCount = notes.count(::needsAttention)
    // Text search is supplied by the store, including passages outside the preview.
    val visible = notes.filter { !attentionOnly || needsAttention(it) }.sortedByDescending { it.created }
    val today = LocalDate.now()
    val groups = visible.groupBy { sessionDay(it.created) }
    fun closeSearch() {
        setQuery("")
        setSearchOpen(false)
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    LaunchedEffect(query) { if (query.isNotBlank()) setSearchOpen(true) }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
    ) {
        if (attentionCount > 0 || attentionOnly || searchOpen || query.isNotBlank()) item {
            Column {
                if (attentionCount > 0 || attentionOnly) Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = attentionOnly,
                        onClick = { setAttentionOnly(!attentionOnly) },
                        label = { Text(if (attentionOnly) "Show all" else "$attentionCount to review", style = MaterialTheme.typography.labelMedium) },
                        colors = focalChipColors(),
                        shape = RoundedCornerShape(50),
                    )
                }
                AnimatedVisibility(searchOpen) {
                    val requester = remember { FocusRequester() }
                    LaunchedEffect(focusSearch) {
                        if (focusSearch) { requester.requestFocus(); keyboard?.show(); searchFocused() }
                    }
                    TextField(
                        value = query, onValueChange = setQuery,
                        modifier = Modifier.fillMaxWidth().focusRequester(requester).semantics { contentDescription = "Search sessions" }.padding(bottom = 12.dp), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(force = true); keyboard?.hide() }),
                        placeholder = { Text("Search sessions", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Glyph(Symbol.Search, Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingIcon = { if (query.isNotEmpty()) IconAction(Symbol.Close, "Clear search") { setQuery("") } },
                        shape = RoundedCornerShape(10.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
        if (error != null) item { DataReadError(if (loaded) "Couldn't refresh sessions" else "Couldn't load sessions", error, retryLoad) }
        if (!loaded && error == null) item {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (loaded && visible.isEmpty()) item {
            Column(Modifier.padding(top = 48.dp, bottom = 32.dp)) {
                Text(
                    when {
                        query.isNotBlank() -> "No matching sessions."
                        attentionOnly -> "Nothing to review."
                        else -> "No sessions yet."
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (query.isNotBlank() || attentionOnly) TextButton(
                    onClick = { closeSearch(); setAttentionOnly(false) }, contentPadding = PaddingValues(0.dp),
                ) { Text("Clear filters") }
            }
        }
        groups.forEach { (day, sessions) ->
            item(key = "day:$day") { SessionDayHeader(dayLabel(day, today)) }
            items(sessions, key = { it.id }) { note ->
                SessionRow(note, live) { open(note.id) }
            }
        }
    }
}

@Composable private fun SessionDayHeader(label: String) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 34.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val x = 4.dp.toPx()
            drawLine(line, Offset(x, size.height / 2), Offset(x, size.height), 1.dp.toPx())
            drawCircle(dot, 2.5.dp.toPx(), Offset(x, size.height / 2))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
    }
}

@Composable private fun SessionRow(note: Note, live: LiveSession, open: () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val status = when {
        live.noteId == note.id -> if (live.processing) "Transcribing" else if (live.paused) "Recording paused" else "Recording now"
        note.state == "INTERRUPTED" -> "Interrupted"
        note.state == "ERROR" -> "Transcription error"
        note.reviewCount > 0 -> "${note.reviewCount} to review"
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(role = Role.Button, onClick = open),
    ) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val x = 4.dp.toPx()
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        Column(Modifier.weight(1f).padding(top = 13.dp, bottom = 17.dp)) {
            Text(note.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${sessionTime(note.created)} · ${Transcript.time(note.duration)} · ${Preferences.languages[note.language] ?: note.language}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
            if (note.preview.isNotBlank()) Text(
                note.preview, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp),
            )
            if (status != null) Row(
                Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable internal fun NoteScreen(
    note: Note, library: LibraryState, live: LiveSession?, reviewOnly: Boolean,
    toggleReview: () -> Unit, edit: (Passage) -> Unit, retry: () -> Unit,
    playing: Boolean, audioAvailable: Boolean, play: () -> Unit, rename: () -> Unit,
    seek: (Long) -> Unit = {}, playbackPosition: Long? = null,
    retryLoad: () -> Unit = {},
) {
    val review = library.passages.count { it.uncertain && !it.reviewed }
    val passages = if (reviewOnly) library.passages.filter { it.uncertain && !it.reviewed } else library.passages
    val activePassage = playbackPosition?.let { position ->
        library.passages.lastOrNull { position >= it.start && position < it.end }?.id
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item {
            Column(Modifier.padding(top = 8.dp, bottom = if (live == null) 22.dp else 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        if (note.title == "Untitled note" && live != null) "New recording" else note.title,
                        style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                Text(
                    "${dayLabel(sessionDay(note.created), LocalDate.now())}, ${sessionTime(note.created)}" +
                        (if (live == null) " · ${Transcript.time(note.duration)}" else "") +
                        " · ${Preferences.languages[note.language] ?: note.language}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                )
                if (live == null && library.audioBytes == 0L && library.passages.isNotEmpty()) Text(
                    "Text only", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        if (library.error != null) item { DataReadError("Couldn't refresh this session", library.error.orEmpty(), retryLoad) }
        if (live != null && !live.processing) item { RecordingReadout(live) }
        if (live == null && (note.error.isNotBlank() || library.pending > 0)) item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp)) {
                Text(
                    when { note.state == "INTERRUPTED" -> "Transcription interrupted"; note.error.isBlank() -> "Transcription unfinished"; else -> "Transcription error" },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error,
                )
                if (note.error.isNotBlank()) Text(note.error, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                if (library.pending > 0 && library.audioBytes > 0) TextButton(onClick = retry, contentPadding = PaddingValues(0.dp)) {
                    Text("Continue transcription")
                } else if (library.pending > 0) Text("Original audio is unavailable.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (review > 0 || reviewOnly) item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                FilterChip(
                    selected = reviewOnly, onClick = toggleReview, shape = RoundedCornerShape(50), colors = focalChipColors(),
                    label = { Text(if (reviewOnly) "Show all" else "$review to review", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        if (passages.isEmpty() && (live == null || reviewOnly)) item {
            Text(
                if (reviewOnly) "All checked." else "No speech transcribed.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        items(passages, key = { it.id }) { passage ->
            TimedPassage(
                passage, active = passage.id == activePassage, audioAvailable = audioAvailable,
                seek = { seek(passage.start) }, edit = { edit(passage) },
            )
        }
    }
}

@Composable private fun RecordingReadout(live: LiveSession) {
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 26.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (fontScale >= 1.5f || maxWidth < 280.dp) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RecordingElapsed(live)
                LevelMeter(if (live.paused) 0f else live.level, Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RecordingElapsed(live, Modifier.weight(1f))
                LevelMeter(if (live.paused) 0f else live.level)
            }
        }
        val exceptionalMessage = live.message.takeIf {
            it.isNotBlank() && it !in listOf("Listening", "Paused", "Recording · transcribing", "Text appears after each spoken phrase.")
        }
        if (exceptionalMessage != null) Text(
            exceptionalMessage, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable private fun RecordingElapsed(live: LiveSession, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            Text(if (live.paused) "Recording paused" else "Recording", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Text(Transcript.time(live.duration), style = MaterialTheme.typography.headlineLarge,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable private fun TimedPassage(
    passage: Passage, active: Boolean, audioAvailable: Boolean, seek: () -> Unit, edit: () -> Unit,
) {
    val flagged = passage.uncertain && !passage.reviewed
    val timestamp = Transcript.time(passage.start)
    val fontScale = LocalDensity.current.fontScale
    val line = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    @Composable fun TimeLabel(modifier: Modifier) {
        Column(
            modifier.then(if (audioAvailable) Modifier.clickable(role = Role.Button, onClick = seek) else Modifier)
                .semantics { contentDescription = if (audioAvailable) "Listen from $timestamp" else "Passage at $timestamp" }
                .padding(top = 5.dp, bottom = 14.dp),
        ) {
            Text(timestamp, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                color = if (active) ink else MaterialTheme.colorScheme.onSurfaceVariant)
            if (flagged) Text("Review", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
        }
    }
    @Composable fun PassageText(modifier: Modifier) {
        if (!fitsPassageDraftBudget(passage.text)) {
            Column(modifier.clickable(role = Role.Button, onClickLabel = "Open full passage at $timestamp", onClick = edit)
                .semantics { if (active) stateDescription = "Current audio passage" }.padding(top = 1.dp, bottom = 26.dp)) {
                Text(passageTextPart(passage.text, 0, 512), style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6, overflow = TextOverflow.Ellipsis)
                Text("Open full passage", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            }
            return
        }
        Text(passage.text.ifBlank { "Unclear speech" }, style = MaterialTheme.typography.bodyLarge,
            modifier = modifier.clickable(
                role = Role.Button, onClickLabel = "${if (flagged) "Review passage" else "Edit passage"} at $timestamp", onClick = edit,
            ).semantics { if (active) stateDescription = "Current audio passage" }
                .padding(top = 1.dp, bottom = 26.dp))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
        .background(if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)) {
        if (fontScale >= 1.4f || maxWidth < 300.dp) Column(Modifier.fillMaxWidth()) {
            TimeLabel(Modifier.heightIn(min = 48.dp))
            PassageText(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            TimeLabel(Modifier.width(if (timestamp.length > 5) 64.dp else 48.dp).fillMaxHeight())
            Canvas(Modifier.width(1.dp).fillMaxHeight()) {
                drawLine(line, Offset.Zero, Offset(0f, size.height), 1.dp.toPx())
            }
            PassageText(Modifier.weight(1f).padding(start = 15.dp))
        }
    }
}

@Composable private fun LevelMeter(level: Float, modifier: Modifier = Modifier.width(110.dp)) {
    val target = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
    val value by animateFloatAsState(target, animationSpec = tween(120), label = "microphone level")
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier.height(44.dp).semantics {
            contentDescription = "Microphone level"
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
        },
    ) {
        drawFocusWave(ink, level = value, minimumBarHeight = 3.dp.toPx())
    }
}

@Composable internal fun LanguageDialog(language: String, select: (String) -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Speech language") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
            Preferences.languages.forEach { (id, name) ->
                Row(Modifier.fillMaxWidth().selectable(id == language, role = Role.RadioButton, onClick = { select(id); dismiss() })
                    .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = id == language, onClick = null)
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable internal fun focalChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

private fun sessionDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
@Composable private fun sessionTime(millis: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(millis))
private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "MMMM d" else "MMMM d, yyyy", Locale.ENGLISH))
}

@Composable internal fun DataReadError(title: String, message: String, retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 7.dp))
        TextButton(onClick = retry, contentPadding = PaddingValues(0.dp)) { Text("Retry") }
    }
}
