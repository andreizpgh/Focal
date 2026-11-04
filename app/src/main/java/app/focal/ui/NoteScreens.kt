package app.focal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.focal.LibraryState
import app.focal.core.Transcript
import app.focal.data.Note
import app.focal.data.Passage
import app.focal.data.Preferences
import app.focal.data.fitsPassageDraftBudget
import app.focal.data.mayNeedRecovery
import app.focal.recording.LiveSession
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Date
import android.text.format.DateFormat

internal const val SEARCH_QUERY_MAX_CHARS = 200

@Composable internal fun BrandMark(size: Int = 26) {
    Glyph(Symbol.Logo, Modifier.size(size.dp), MaterialTheme.colorScheme.onSurface)
}

internal fun sessionNeedsAttention(note: Note): Boolean =
    note.reviewCount > 0 || note.state == "ERROR" || note.state == "INTERRUPTED"

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun LibraryScreen(
    notes: List<Note>, loaded: Boolean, live: LiveSession, open: (String?) -> Unit,
    searchValue: TextFieldValue = TextFieldValue(""), setSearchValue: (TextFieldValue) -> Unit = {},
    searchOpen: Boolean = false, setSearchOpen: (Boolean) -> Unit = {},
    error: String? = null, retryLoad: () -> Unit = {},
    attentionOnly: Boolean = false, setAttentionOnly: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    inputActive: Boolean = false, setInputActive: (Boolean) -> Unit = {},
    searchFocusChanged: (Boolean) -> Unit = {}, searchFieldMeasured: (Int) -> Unit = {},
    onSessionActions: (String) -> Unit = {},
    recordingDay: LocalDate? = null, clearRecordingDay: () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val inputWindowReady by rememberUpdatedState(lifecycleState == Lifecycle.State.RESUMED && windowFocused)
    // Keep keyboard intent separate from showing or hiding the search panel.
    var inputRequestIssued by remember(inputActive) { mutableStateOf(false) }
    val query = searchValue.text
    var viewportHeight by remember { mutableIntStateOf(0) }
    // Text search is supplied by the store, including passages outside the preview.
    val visible = notes.filter { (!attentionOnly || sessionNeedsAttention(it)) &&
        (recordingDay == null || sessionDay(it.created) == recordingDay) }.sortedByDescending { it.created }
    val today = LocalDate.now()
    val groups = visible.groupBy { sessionDay(it.created) }
    fun dismissSearchInput() {
        focus.clearFocus(force = true)
        keyboard?.hide()
        setInputActive(false)
    }
    fun closeSearch() {
        setSearchValue(TextFieldValue(""))
        setSearchOpen(false)
        dismissSearchInput()
    }
    LaunchedEffect(query) { if (query.isNotBlank()) setSearchOpen(true) }
    Column(Modifier.fillMaxSize().onSizeChanged { viewportHeight = it.height }) {
        // This panel stays composed even when the results scroll far away.
        AnimatedVisibility(
            searchOpen,
            enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(220), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
        ) {
            val requester = remember { FocusRequester() }
            val visibilityRequester = remember { BringIntoViewRequester() }
            var focused by remember { mutableStateOf(false) }
            LaunchedEffect(inputActive) {
                if (inputActive && !inputRequestIssued) {
                    snapshotFlow { inputWindowReady }.first { it }
                    requester.requestFocus()
                    keyboard?.show()
                    inputRequestIssued = true
                }
            }
            LaunchedEffect(focused, viewportHeight) {
                // Follow the measured viewport as the keyboard changes its available height.
                if (focused && viewportHeight > 0) visibilityRequester.bringIntoView()
            }
            TextField(
                value = searchValue,
                onValueChange = {
                    val accepted = it.copy(text = Transcript.truncateUtf16(it.text, SEARCH_QUERY_MAX_CHARS))
                    setSearchValue(accepted)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    // Measure the field's required height even while the IME compresses its panel.
                    // The header can then yield space without feeding a clipped zero back into layout.
                    .wrapContentHeight(align = Alignment.Top, unbounded = true).focusRequester(requester)
                    .onFocusChanged {
                        focused = it.isFocused
                        // Initial focus loss and disposal must not erase saved input intent.
                        if (it.isFocused) setInputActive(true)
                        searchFocusChanged(it.isFocused)
                    }
                    .onSizeChanged { searchFieldMeasured(it.height) }.bringIntoViewRequester(visibilityRequester)
                    .semantics { contentDescription = "Search recordings" }.padding(bottom = 12.dp), singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { dismissSearchInput() }),
                placeholder = { Text("Search recordings", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Glyph(Symbol.Search, Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = { if (query.isNotEmpty()) IconAction(Symbol.Close, "Clear search") { setSearchValue(TextFieldValue("")) } },
                shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f), state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
        ) {
            if (error != null) item { DataReadError(if (loaded) "Couldn't refresh recordings" else "Couldn't load recordings", error, retryLoad) }
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
                            query.isNotBlank() || recordingDay != null -> "No matching recordings."
                            attentionOnly -> "Nothing to review."
                            else -> "No recordings yet."
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (query.isNotBlank() || attentionOnly || recordingDay != null) TextButton(
                        onClick = { closeSearch(); setAttentionOnly(false); clearRecordingDay() }, contentPadding = PaddingValues(0.dp),
                    ) { Text("Clear filters") }
                }
            }
            groups.entries.forEachIndexed { index, (day, sessions) ->
                if (index > 0) item(key = "day:$day") { SessionDayHeader(dayLabel(day, today), modifier = Modifier.animateItem(
                    fadeInSpec = tween(140, delayMillis = 240), placementSpec = tween(240), fadeOutSpec = tween(80),
                )) }
                items(sessions, key = { it.id }) { note ->
                    SessionRow(note, live,
                        open = { dismissSearchInput(); open(note.id) },
                        actions = { dismissSearchInput(); onSessionActions(note.id) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(140, delayMillis = 240), placementSpec = tween(240), fadeOutSpec = tween(80),
                        ),
                    )
                }
            }
        }
    }
}

@Composable private fun SessionDayHeader(label: String, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 34.dp).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val x = 4.dp.toPx()
            drawLine(line, Offset(x, size.height / 2), Offset(x, size.height), 1.dp.toPx())
            drawCircle(dot, 2.5.dp.toPx(), Offset(x, size.height / 2))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun SessionRow(
    note: Note, live: LiveSession, open: () -> Unit, actions: () -> Unit, modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val status = when {
        live.noteId == note.id -> if (live.processing) "Transcribing" else if (live.paused) "Recording paused" else "Recording now"
        note.state == "INTERRUPTED" -> "Interrupted"
        note.state == "ERROR" -> "Transcription error"
        note.reviewCount > 0 -> "${note.reviewCount} to review"
        else -> null
    }
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).combinedClickable(
            role = Role.Button, onClick = open, onLongClickLabel = "Recording options",
            onLongClick = actions,
        ),
    ) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val x = 4.dp.toPx()
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        Column(Modifier.weight(1f).padding(top = 13.dp, bottom = 17.dp)) {
            Text(note.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(
                modifier = Modifier.padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(sessionTime(note.created), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DurationLabel(note.duration)
                Text(Preferences.languages[note.language] ?: note.language,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun NoteScreen(
    note: Note, library: LibraryState, live: LiveSession?, reviewOnly: Boolean,
    toggleReview: () -> Unit, edit: (Passage) -> Unit, retry: () -> Unit,
    playing: Boolean, audioAvailable: Boolean, play: () -> Unit, rename: () -> Unit,
    seek: (Long) -> Unit = {}, playbackPosition: Long? = null,
    retryLoad: () -> Unit = {}, notificationsEnabled: Boolean = true,
    followSuspended: Boolean = false,
) {
    val review = library.passages.count { it.uncertain && !it.reviewed }
    val canRetryTranscription = library.pending > 0 || note.mayNeedRecovery
    val reviewing = live == null && reviewOnly
    val passages = if (reviewing) library.passages.filter { it.uncertain && !it.reviewed } else library.passages
    var following by rememberSaveable(note.id) { mutableStateOf(true) }
    var awaitingFinalNote by rememberSaveable(note.id) { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    SideEffect { if (live != null) awaitingFinalNote = true }
    // The live flow can finish before the final store snapshot reaches the ViewModel.
    val followSession = live != null || awaitingFinalNote
    val finalNoteLoaded = live == null && (note.state !in liveNoteStates || library.error != null)
    val showRecovery = live == null && (note.error.isNotBlank() || canRetryTranscription)
    val tail = passages.lastOrNull()
    // The extra item gives the true bottom even when a single passage is taller than the viewport.
    val tailIndex = passages.size + (if (live == null) 1 else 0) +
        (if (library.error != null) 1 else 0) + (if (showRecovery) 1 else 0)
    val listState = rememberSaveable(note.id, saver = LazyListState.Saver) {
        val initialIndex = if (live != null && following && !reviewOnly && !followSuspended && tail != null) tailIndex else 0
        LazyListState(firstVisibleItemIndex = initialIndex)
    }
    val viewport by remember(listState) { derivedStateOf { listState.layoutInfo.viewportSize } }
    val mayFollow = followSession && !reviewOnly && !followSuspended && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
        viewport.width > 0 && viewport.height > 0
    LaunchedEffect(mayFollow, following, finalNoteLoaded, tail?.id, tail?.text?.length, tailIndex, viewport) {
        if (mayFollow && following && tail != null) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == tailIndex + 1 }
            listState.animateScrollToItem(tailIndex)
        }
        if (finalNoteLoaded && (mayFollow || !following || reviewing || tail == null)) awaitingFinalNote = false
    }
    val followGestures = remember(listState) {
        object : NestedScrollConnection {
            // App-driven listState scrolling bypasses nested scroll. A gesture toward older
            // text must disengage before the first delta, even while an append is arriving.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && listState.canScrollBackward) following = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y + available.y < 0 && !listState.canScrollForward) following = true
                return Offset.Zero
            }
        }
    }
    val showLatest = live != null && !following && !reviewOnly
    @Composable fun ReviewFilter() {
        FilterChip(
            selected = reviewOnly, onClick = toggleReview, shape = CircleShape, colors = focalChipColors(),
            leadingIcon = { Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant)) },
            label = { Text(if (reviewOnly) "Show all" else "$review to review", style = MaterialTheme.typography.labelMedium) },
        )
    }
    val activePassage = playbackPosition?.let { position ->
        library.passages.lastOrNull { position >= it.start && position < it.end }?.id
    }
    Column(Modifier.fillMaxSize()) {
        if (live != null) RecordingReadout(live, notificationsEnabled)
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize().then(if (mayFollow) Modifier.nestedScroll(followGestures) else Modifier),
                state = listState,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = if (showLatest) 64.dp else 24.dp),
            ) {
                if (live == null) item(key = "note-header") {
                    Column(Modifier.padding(top = 0.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                note.title,
                                style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f).semantics { heading() },
                            )
                        }
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            DurationLabel(note.duration, Modifier.align(Alignment.CenterVertically))
                            Text(Preferences.languages[note.language] ?: note.language,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically))
                            if (review > 0 || reviewOnly) ReviewFilter()
                        }
                        if (library.audioBytes == 0L && library.passages.isNotEmpty()) Text(
                            "Text only", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
                if (library.error != null) item { DataReadError("Couldn't refresh this recording", library.error.orEmpty(), retryLoad) }
                if (showRecovery) item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp)) {
                        Text(
                            when { note.state == "INTERRUPTED" -> "Transcription interrupted"; note.error.isBlank() -> "Transcription unfinished"; else -> "Transcription error" },
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error,
                        )
                        if (note.error.isNotBlank()) Text(note.error, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        if (canRetryTranscription && library.audioBytes > 0) TextButton(onClick = retry, contentPadding = PaddingValues(0.dp)) {
                            Text("Continue transcription")
                        } else if (canRetryTranscription) Text("Original audio is unavailable.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                if (passages.isEmpty() && live == null) item {
                    Text(
                        if (reviewing) "No flagged passages." else "No speech transcribed.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                items(passages, key = { it.id }) { passage ->
                    TimedPassage(
                        passage, active = passage.id == activePassage, audioAvailable = audioAvailable,
                        seek = { seek(passage.start) }, edit = { edit(passage) },
                        modifier = if (live != null) Modifier else Modifier.animateItem(
                            fadeInSpec = tween(140, delayMillis = 240), placementSpec = tween(240), fadeOutSpec = tween(80),
                        ),
                    )
                }
                if (followSession && tail != null) item(key = "live-tail") { Spacer(Modifier.height(1.dp)) }
            }
            if (showLatest) FilledTonalButton(
                onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 8.dp)
                    .heightIn(min = 48.dp).semantics { contentDescription = "Jump to latest transcript" },
            ) { Text("Latest") }
        }
    }
}

private val liveNoteStates = setOf("RECORDING", "PAUSED", "PROCESSING")

@Composable private fun DurationLabel(duration: Long, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Glyph(Symbol.Audio, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Transcript.time(duration), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Duration ${Transcript.time(duration)}" })
    }
}

@Composable private fun RecordingReadout(live: LiveSession, notificationsEnabled: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 8.dp)) {
        val status = when {
            live.processing -> live.message.ifBlank { "Transcribing" }
            live.paused -> "Recording paused"
            else -> "Recording"
        }
        FlowRow(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
                Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(Transcript.time(live.duration), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                if (live.processing) FocusProcessingIndicator(Modifier.size(width = 34.dp, height = 22.dp))
                else LevelMeter(if (live.paused) 0f else live.level)
            }
        }
        val exceptionalMessage = live.message.takeIf {
            !live.processing &&
            it.isNotBlank() && it !in listOf("Listening", "Paused", "Recording · transcribing", "Text appears after each spoken phrase.")
        }
        if (exceptionalMessage != null) Text(
            exceptionalMessage, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!notificationsEnabled && !live.processing) Text(
            "Notifications are off. Return to Focal to pause or finish.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable private fun TimedPassage(
    passage: Passage, active: Boolean, audioAvailable: Boolean, seek: () -> Unit, edit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flagged = passage.uncertain && !passage.reviewed
    val timestamp = Transcript.time(passage.start)
    val fontScale = LocalDensity.current.fontScale
    val ink = MaterialTheme.colorScheme.onSurface
    val highlight by animateColorAsState(
        if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        tween(180), label = "current passage",
    )
    @Composable fun TimeLabel(modifier: Modifier) {
        Column(
            modifier.clip(RoundedCornerShape(12.dp))
                .then(if (audioAvailable) Modifier.clickable(role = Role.Button, onClick = seek) else Modifier)
                .semantics {
                    contentDescription = if (audioAvailable) "Listen from $timestamp" else "Passage at $timestamp"
                    if (flagged) stateDescription = "Flagged for review"
                }
                .padding(horizontal = 4.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(timestamp, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                color = if (active) ink else MaterialTheme.colorScheme.onSurfaceVariant)
            if (flagged) Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
    @Composable fun PassageText(modifier: Modifier) {
        val oversized = !fitsPassageDraftBudget(passage.text)
        Column(modifier.clip(RoundedCornerShape(12.dp))
            .background(highlight)
            .clickable(
                role = Role.Button,
                onClickLabel = "${if (oversized) "Open full passage" else if (flagged) "Review passage" else "Edit passage"} at $timestamp",
                onClick = edit,
            ).semantics {
                if (active || flagged) stateDescription = listOfNotNull(
                    "Current audio passage".takeIf { active },
                    "Flagged for review".takeIf { flagged },
                ).joinToString(", ")
            }.padding(12.dp)) {
            Text(
                if (oversized) passageTextPart(passage.text, 0, 512) else passage.text.ifBlank { "Unclear speech" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (oversized) 6 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
            if (oversized) Text("Open full passage", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (fontScale >= 1.4f || maxWidth < 260.dp) Column(Modifier.fillMaxWidth()) {
            TimeLabel(Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp))
            PassageText(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TimeLabel(Modifier.width(if (timestamp.length > 5) 76.dp else 56.dp).heightIn(min = 48.dp).alignBy(FirstBaseline))
            PassageText(Modifier.weight(1f).alignBy(FirstBaseline))
        }
    }
}

@Composable private fun LevelMeter(level: Float) {
    val target = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
    val value by animateFloatAsState(target, animationSpec = tween(120), label = "microphone level")
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier.size(width = 34.dp, height = 22.dp).semantics {
            contentDescription = "Microphone level"
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
        },
    ) {
        drawFocusWave(ink, level = value, minimumBarHeight = 3.dp.toPx())
    }
}

@Composable internal fun focalChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

internal fun sessionDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
@Composable internal fun sessionTime(millis: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(millis))
internal fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
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
