package app.focal.ui.note

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.focal.R
import app.focal.ui.components.BottomFade
import app.focal.ui.components.DockActions
import app.focal.ui.components.FeedbackAction
import app.focal.ui.components.FeedbackResult
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.NotePill
import app.focal.ui.components.PaperTopFade
import app.focal.ui.components.rememberRowPlacement
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.library.LibraryDates
import app.focal.ui.library.LibraryModels
import app.focal.ui.library.noteDock
import app.focal.ui.shell.BackEase
import app.focal.ui.shell.Destination
import app.focal.ui.shell.DestinationBottom
import app.focal.ui.shell.LocalPageReveal
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.PageKey
import app.focal.ui.shell.PageKeysEffect
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.GapUi
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteUiState
import app.focal.ui.state.ParagraphUi
import app.focal.ui.state.PlayingWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.ui.unit.dp

/** One row of the note's list. */
@Immutable
internal sealed interface NoteItem {
    val key: Any

    data object Head : NoteItem { override val key: Any = "head" }
    data class Text(val paragraph: ParagraphUi, val index: Int) : NoteItem { override val key: Any = paragraph.id }
    data class Gap(val gap: GapUi) : NoteItem { override val key: Any = "gap:${gap.startMs}" }
    data object Writing : NoteItem { override val key: Any = "writing" }
    data object NoSpeech : NoteItem { override val key: Any = "no-speech" }
}

/** What the page says when an edit outside the session's own writes does not save. */
private class OutcomeWords(val replace: String, val tooLarge: String, val restore: String, val tooLate: String)

/** Where in the reading band a found match or a search hit lands: its centre (spec 5.8). */
private const val HALF = 0.5f

/** Playback that stops this close to the end of its audio has ended (spec 5.6). */
private const val END_SLACK_MS = 250L

/** Find's band: from the status bar + 24 to the first row − 24 (spec 5.8). */
private val FindBandInset = FocalGrid.x24

/** Find's top fade: solid to the status bar, then 48 dp (spec 2.6). */
private val FindFade = FocalGrid.x48

/** Above the text: the fades and the jump pill, then the bars. */
private const val FADES_Z = 1f
private const val BARS_Z = 2f

/** Edit keeps the caret's line this far inside the visible band, above the keyboard (spec 5.7). */
private val CaretMargin = FocalGrid.x24

/**
 * The note page (spec 5.4 to 5.9): a document. Its title, meta and notices, then the paragraphs in a lazy list, with
 * gap rows where a part could not be transcribed and the writing head while the tail transcribes. Tap a word to play
 * from just before it; the playing paragraph stays in ink while the rest recede and the lens follows the word. ✎
 * turns the paragraphs into fields in place; ⋮ opens Find and replace, Rename, Share, Remove audio and Delete. The dock
 * is Play | Copy (or the progress pill); the player strip, the find rows and the feedback pill share the bottom column.
 *
 * Everything is drawn from [note] and the page's own state, so the gallery renders it from fakes; [playback] and
 * [edits] are the activity's player and the note's store, or fakes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotePage(
    note: NoteUiState,
    playback: NotePlayback,
    actions: NoteActions,
    edits: EditBackend,
    destination: Destination.Note,
    dates: LibraryDates,
    today: LocalDate,
    captureElsewhere: Boolean,
    landing: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    page: NotePageState = rememberNotePageState(),
) {
    val shell = LocalShell.current
    val bottom = shell.bottom
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session = remember(edits) { EditSession(edits) }
    val focus = page.focus
    val find = page.find
    val listFade = remember { Animatable(1f) }
    val scroller = remember(listState) { NoteScroller(listState, listFade) }
    val selection = rememberSelectionState()
    val status = WindowInsets.statusBars
    // Inside a container transform the chrome has the paper's alpha and the body arrives on its own (spec 6.1).
    val reveal = LocalPageReveal.current

    // What is drawn: the note's paragraphs with the session's saved ones laid over them.
    val paragraphs by remember(note.paragraphs, page.editing) {
        derivedStateOf {
            val merged = session.paragraphs(note.paragraphs)
            if (page.editing) merged else merged.filter { it.text.isNotBlank() }
        }
    }
    LaunchedEffect(note.paragraphs, page.editing) { session.sync(note.paragraphs, page.editing) }
    val transcribing = note.phase == NotePhase.TRANSCRIBING || note.phase == NotePhase.PREPARING_MODEL ||
        note.phase == NotePhase.WAITING || note.phase == NotePhase.RECORDING || note.phase == NotePhase.PAUSED
    // New text is noted once the composition is applied; the paragraphs it shows are composed after it, in layout.
    SideEffect { page.arrivals.observe(note.paragraphs, transcribing, SystemClock.uptimeMillis()) }
    val writing = transcribing && !note.noSpeech
    val items = remember(paragraphs, note.gaps, writing, note.noSpeech) { noteItems(paragraphs, note.gaps, writing, note.noSpeech) }
    val indexOf = remember(items) { items.withIndex().associate { (index, item) -> item.key to index } }
    val placement = rememberRowPlacement(remember(items) { items.map { it.key } })

    // Modes and the focus rule.
    val audio = playback.session
    val playingHere = audio.noteId == note.noteId && !page.playerDismissed && note.audioPresent
    val mode = when {
        find.open -> NoteMode.Finding
        page.editing -> NoteMode.Editing
        playingHere -> NoteMode.Playing
        else -> NoteMode.Reading
    }
    LaunchedEffect(mode) {
        if (mode == NoteMode.Reading && focus.mode != NoteMode.Reading) focus.rackFocus(visibleParagraphs(listState, items))
        else focus.mode = mode
    }

    // Playback: the position read per frame, the playing paragraph and word, follow, and the strip.
    val position = remember(playback) { PlayerPosition(playback::positionMs) }
    SideEffect { position.durationMs = audio.durationMs.takeIf { it > 0 } ?: note.durationMs }
    val bandTop: () -> Float = { status.getTop(density).toFloat() + with(density) { FocalLayout.topBar.toPx() } }
    val bandBottom: () -> Float = { bottom.top.value.takeUnless { it.isNaN() } ?: listState.layoutInfo.viewportSize.height.toFloat() }
    val follow = remember { FollowJob() }
    val scrubbing by remember { derivedStateOf { position.scrubMs != null } }
    LaunchedEffect(playingHere, audio.playing, scrubbing, paragraphs, items) {
        if (!playingHere) {
            focus.playingId = null
            focus.playingWord = null
            page.playingOutside = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                withFrameMillis {
                    if (playback.session.playing) position.frame++
                    val location = PlayingWord.locate(paragraphs, position.ms())
                    val paragraph = location?.let { paragraphs.getOrNull(it.paragraphIndex) }
                    if (focus.playingId != paragraph?.id) focus.playingId = paragraph?.id
                    val word = location?.wordIndex
                    if (focus.playingWord != word) focus.playingWord = word
                }
                val id = focus.playingId
                val index = id?.let { indexOf[it] }
                if (id != null && index != null) {
                    val paragraph = paragraphs.firstOrNull { it.id == id }
                    val word = focus.playingWord?.let { paragraph?.words?.getOrNull(it) }
                    val line = lineBox(page.layouts[id], word?.start)
                    val y = windowY(listState, index, line.first)
                    val height = line.second - line.first
                    if (page.followSuspended) {
                        page.playingOutside = when {
                            y == null -> index < (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0)
                            y + height < bandTop() -> true
                            y > bandBottom() -> false
                            else -> null
                        }
                    } else if (!listState.isScrollInProgress && follow.idle) {
                        page.playingOutside = null
                        val distance = if (y == null) Float.NaN else Follow.distance(y, y + height, bandTop(), bandBottom())
                        if (distance.isNaN() || distance != 0f) follow.job = scope.launch {
                            val third = (bandBottom() - bandTop()) / THIRDS
                            scroller.bring(index, { lineBox(page.layouts[id], word?.start).first }, bandTop() + third)
                        }
                    }
                }
            } while (playback.session.playing || position.scrubMs != null)
        }
    }
    // A manual scroll suspends follow (spec 5.6); "Now playing", ▶ or a word tap resumes it.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) page.followSuspended = true }
    }
    // The end of the audio (or of a gap's range) ends the session: the strip leaves, the paragraphs return to ink.
    LaunchedEffect(audio.playing, audio.noteId) {
        if (audio.playing || audio.noteId != note.noteId) return@LaunchedEffect
        val end = page.rangeEndMs ?: position.durationMs
        if (end > 0 && playback.positionMs() >= end - END_SLACK_MS) {
            page.rangeEndMs = null
            playback.stop()
        }
    }
    val stripWanted = playingHere && !page.editing && !find.open
    LaunchedEffect(stripWanted) { if (stripWanted) page.stripShown = true }

    // Edit (spec 5.7).
    // [commit]: a tap moved the caret, so the paragraph it left is saved now; a split, join, undo or restore already
    // wrote it (saving its old draft again would undo the step).
    fun focusField(at: CaretAt, refresh: Boolean = true, commit: Boolean = false) {
        val text = session.paragraph(at.id)?.text ?: paragraphs.firstOrNull { it.id == at.id }?.text ?: return
        val current = page.field
        if (current != null && current.id == at.id) {
            current.text.edit {
                if (refresh && toString() != text) replace(0, length, text)
                this.selection = TextRange(at.caret.coerceIn(0, length))
            }
        } else {
            if (current != null && commit) {
                val draft = current.text.text.toString()
                scope.launch { session.save(current.id, draft) }
            }
            page.field = FieldFocus(at.id, text, at.caret)
        }
        focus.editingId = at.id
    }
    fun enterEdit(at: CaretAt?) {
        if (!note.editable || page.editing) return
        val playingAt = focus.playingId?.takeIf { playingHere }?.let { id ->
            val paragraph = paragraphs.firstOrNull { it.id == id }
            CaretAt(id, focus.playingWord?.let { paragraph?.words?.getOrNull(it)?.start } ?: 0)
        }
        if (playingHere) {
            playback.pause()
            page.playerDismissed = true
        }
        page.menu = false
        session.begin(paragraphs)
        page.editing = true
        (at ?: playingAt)?.let { focusField(it) }
    }
    fun exitEdit() {
        page.editing = false
        page.field = null
        page.editMenu = false
        focus.editingId = null
        session.end()
        keyboard?.hide()
        focusManager.clearFocus()
    }
    val saveFailed = stringResource(R.string.feedback_save_failed)
    fun done() {
        scope.launch {
            val field = page.field
            if (field == null || session.save(field.id, field.text.text.toString())) exitEdit()
            else if (session.failures >= 2) bottom.feedback.show(saveFailed)
        }
    }
    // Writes that changed the stored text under the field (Replace all, its Undo): the field takes the stored text
    // back, keeping its caret, so its draft never writes the old words over them. A paragraph that went away drops it.
    fun reseedField() {
        val current = page.field ?: return
        val stored = session.paragraph(current.id)
        if (stored == null) {
            page.field = null
            focus.editingId = null
            return
        }
        focusField(CaretAt(stored.id, current.text.selection.start.coerceAtMost(stored.text.length)))
    }
    // The note stopped being editable under Edit (it started transcribing again): Edit ends after one last save of
    // the draft, which the store may refuse; then the pill says so.
    val notSaved = stringResource(R.string.error_save)
    LaunchedEffect(note.loaded, note.editable) {
        if (!note.loaded || note.editable || !page.editing) return@LaunchedEffect
        val current = page.field
        val saved = current == null || session.save(current.id, current.text.text.toString())
        exitEdit()
        if (!saved) bottom.feedback.show(notSaved)
    }
    fun withDraft(block: suspend (FieldFocus?) -> CaretAt?) {
        scope.launch { block(page.field)?.takeIf { it.id >= 0 }?.let { focusField(it) } }
    }
    val undo: () -> Unit = {
        withDraft { field ->
            if (field != null && !session.save(field.id, field.text.text.toString())) null else session.undo()
        }
    }
    val redo: () -> Unit = {
        withDraft { field ->
            if (field != null && !session.save(field.id, field.text.text.toString())) null else session.redo()
        }
    }
    val restore: () -> Unit = { withDraft { field -> field?.let { session.restore(it.id, it.text.text.toString()) } } }
    // Backspace at a paragraph's start. A join the store refuses (a paragraph whose original is unknown beside one
    // whose original is known) keeps both and the caret, and says so in the pill (spec 4.8: no haptic, 2.9).
    val cannotJoin = stringResource(R.string.error_join)
    val join: () -> Unit = {
        page.field?.let { current ->
            val draft = current.text.text.toString()
            scope.launch {
                when (val joined = session.mergeIntoPrevious(current.id, draft)) {
                    is JoinResult.Joined -> joined.caret.takeIf { it.id >= 0 }?.let { focusField(it) }
                    JoinResult.Refused -> bottom.feedback.show(cannotJoin)
                    JoinResult.Skipped -> Unit
                }
            }
        }
    }
    // A page recreated in Edit (rotation, process death) goes on with its session once the note is there, its undo
    // and redo log included; the saved state reads the log from here.
    LaunchedEffect(note.loaded) {
        if (note.loaded && page.editing && !session.started) {
            session.begin(paragraphs, page.restoredLog)
            page.restoredLog = null
        }
    }
    DisposableEffect(page, session) {
        page.editLog = { session.log() ?: page.restoredLog }
        onDispose { page.editLog = { page.restoredLog } }
    }
    // The draft's last save outlives the page: when the activity stops (recreation included) and when the page goes,
    // it is written in the note's own scope, so nothing typed in the last 600 ms is lost.
    val currentActions by rememberUpdatedState(actions)
    DisposableEffect(lifecycle, session) {
        fun persistDraft() {
            val current = page.field?.takeIf { page.editing } ?: return
            val draft = current.text.text.toString()
            currentActions.persist { session.save(current.id, draft) }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) persistDraft() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            persistDraft()
        }
    }
    // Autosave 600 ms after typing stops (spec 5.7).
    val field = page.field
    LaunchedEffect(field) {
        val current = field ?: return@LaunchedEffect
        snapshotFlow { current.text.text.toString() }.drop(1).collectLatest { draft ->
            delay(FocalTiming.AUTOSAVE_DEBOUNCE_MS)
            session.save(current.id, draft)
        }
    }
    LaunchedEffect(field, find.open) {
        if (field != null && !find.open && page.takesFocus) runCatching { field.requester.requestFocus() }
    }

    // Find and replace (spec 5.8).
    fun openFind() {
        if (!note.editable && !(note.phase == NotePhase.READY && !note.audioPresent)) return
        page.menu = false
        page.editMenu = false
        page.field?.let { current ->
            val draft = current.text.text.toString()
            scope.launch { session.save(current.id, draft) }
        }
        if (playingHere) {
            playback.pause()
            page.playerDismissed = true
        }
        find.open(fromEdit = page.editing)
    }
    fun closeFind() {
        val back = find.fromEdit
        find.close()
        if (back) reseedField() else {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    LaunchedEffect(find.open) {
        if (!find.open || !page.takesFocus) return@LaunchedEffect
        // The rows are composed in the bottom column this frame; the field takes focus on the next.
        withFrameMillis { }
        runCatching { find.queryFocus.requestFocus() }
        keyboard?.show()
    }
    LaunchedEffect(find.open, find.generation) {
        if (!find.open) return@LaunchedEffect
        snapshotFlow { find.query.text.toString() }.collectLatest { query ->
            if (query.isEmpty()) {
                find.show("", emptyList()) { true }
                return@collectLatest
            }
            delay(FocalTiming.FIND_DEBOUNCE_MS)
            val found = actions.find(query)
            val first = visibleParagraphs(listState, items).firstOrNull()
            val from = first?.let { id -> paragraphs.indexOfFirst { it.id == id } } ?: 0
            find.show(query, found) { match -> paragraphs.indexOfFirst { it.id == match.paragraphId } >= from }
        }
    }
    val currentMatch = find.currentMatch
    LaunchedEffect(currentMatch, find.open) {
        val match = currentMatch?.takeIf { find.open } ?: return@LaunchedEffect
        val index = indexOf[match.paragraphId] ?: return@LaunchedEffect
        val top = status.getTop(density) + with(density) { FindBandInset.toPx() }
        val rows = bandBottom() - with(density) { FindBandInset.toPx() }
        val line = lineBox(page.layouts[match.paragraphId], match.start)
        val target = top + (rows - top) * HALF - (line.second - line.first) * HALF
        scroller.bring(index, { lineBox(page.layouts[match.paragraphId], match.start).first }, target)
    }
    val undoLabel = stringResource(R.string.action_undo)
    val replacedTemplate = pluralStringResource(R.plurals.feedback_replaced, find.matches.size, find.matches.size)
    // An outcome that did not save is said in the pill: the store's own sentence for a storage failure, otherwise
    // what did not happen ("Too late to undo" when the text changed again since).
    val words = OutcomeWords(
        replace = stringResource(R.string.error_replace), tooLarge = stringResource(R.string.error_replace_too_large),
        restore = stringResource(R.string.error_restore), tooLate = stringResource(R.string.feedback_too_late),
    )
    fun report(outcome: EditOutcome, otherwise: String) {
        val message = when (outcome) {
            is EditOutcome.Saved -> return
            is EditOutcome.Failed -> outcome.message
            else -> otherwise
        }
        scope.launch { bottom.feedback.show(message) }
    }
    val replaceAll: () -> Unit = {
        val query = find.matchedQuery
        val replacement = find.replacement.text.toString()
        val label = replacedTemplate
        val before = paragraphs.associate { it.id to it.text }
        scope.launch {
            val outcome = actions.replaceAll(query, replacement)
            if (outcome !is EditOutcome.Saved) {
                report(outcome, if (outcome == EditOutcome.TooLarge) words.tooLarge else words.replace)
                return@launch
            }
            if (page.editing) {
                session.recordReplace(outcome.paragraphs, before)
                reseedField()
            } else session.applySaved(outcome.paragraphs)
            find.refresh()
            if (bottom.feedback.show(label, FeedbackAction(undoLabel, FocalIcon.Undo)) == FeedbackResult.ActionPerformed) {
                if (page.editing && session.undoReplace()) reseedField() else {
                    val undone = actions.undoReplace()
                    if (undone is EditOutcome.Saved) {
                        session.applySaved(undone.paragraphs)
                        reseedField()
                    } else report(undone, words.tooLate)
                }
                find.refresh()
            }
        }
    }

    // Restore original outside Edit (TalkBack's paragraph action): "Original restored · Undo".
    val restoredLabel = stringResource(R.string.feedback_restored)
    val restoreOutside: (ParagraphUi) -> Unit = { paragraph ->
        scope.launch {
            val outcome = edits.restore(paragraph.id, paragraph.revision)
            if (outcome !is EditOutcome.Saved) {
                report(outcome, words.restore)
                return@launch
            }
            session.applySaved(outcome.paragraphs)
            val restored = outcome.paragraphs.firstOrNull { it.id == paragraph.id } ?: return@launch
            if (bottom.feedback.show(restoredLabel, FeedbackAction(undoLabel, FocalIcon.Undo)) == FeedbackResult.ActionPerformed) {
                val undone = edits.edit(restored.id, restored.revision, paragraph.text)
                if (undone is EditOutcome.Saved) session.applySaved(undone.paragraphs) else report(undone, words.tooLate)
            }
        }
    }

    val playFromWord: (ParagraphUi, Int) -> Unit = { paragraph, offset ->
        page.rangeEndMs = null
        page.playerDismissed = false
        page.followSuspended = false
        playback.playWord(paragraph, offset)
    }

    // Taps: play from just before the word (reading); place the caret (Edit).
    val canPlay = note.canPlay && note.audioPresent
    val onTap: (ParagraphUi, Int) -> Unit = { paragraph, offset ->
        when {
            page.editing && !find.open -> focusField(CaretAt(paragraph.id, offset), refresh = false, commit = true)
            find.open || page.renaming -> Unit
            canPlay -> playFromWord(paragraph, offset)
        }
    }

    // Back layers, lowest first: the player, Edit (= Done), Find, an inline rename (spec 2.8.5).
    PredictiveBackHandler(enabled = page.stripShown && stripWanted) { events ->
        try {
            events.collect { page.stripBack.snapTo(BackEase.transform(it.progress)) }
            // Committed: the strip leaves from where the gesture left it, and is reset once it is gone.
            playback.stop()
        } catch (cancelled: CancellationException) {
            // The handler's own coroutine is ending: the spring back runs in the page's scope (spec 2.8.5, `press`).
            scope.launch { page.stripBack.animateTo(0f, FocalMotion.press()) }
            throw cancelled
        }
    }
    BackHandler(enabled = page.editing && !find.open) { done() }
    BackHandler(enabled = find.open) { closeFind() }
    BackHandler(enabled = page.renaming) { page.renaming = false }

    // The dock (spec 4.3) and the bottom column.
    val dockState = noteDock(
        audioPresent = note.audioPresent, canPlay = note.canPlay, playing = playingHere && audio.playing,
        phase = note.phase, progress = note.progress, copyEnabled = !note.noSpeech && paragraphs.isNotEmpty(),
    )
    val currentParagraphs by rememberUpdatedState(paragraphs)
    val currentItems by rememberUpdatedState(items)
    // Action holders are rebuilt with each composition: they close over the note's current state.
    val dockActions = run {
        DockActions(
            play = {
                page.playerDismissed = false
                page.followSuspended = false
                if (playback.session.noteId == note.noteId) playback.toggle() else {
                    // With no session: from the start of the topmost paragraph on screen (spec 5.5).
                    val top = visibleParagraphs(listState, currentItems).firstOrNull()
                    currentParagraphs.firstOrNull { it.id == top }?.let { playback.playWord(it, 0) } ?: playback.playFrom(0)
                }
            },
            pausePlayback = { playback.toggle() },
            copy = actions.copy,
        )
    }
    val findActions = run {
        FindActions(close = { closeFind() }, next = { find.next() }, previous = { find.previous() }, replaceAll = { replaceAll() })
    }
    // Only once the note has loaded: an empty state would put a waiting note's dock up for a frame or two (and turn
    // it into Copy with the readiness morph); until then the dock holds what the page's origin said it would show.
    if (note.loaded) DestinationBottom(
        destination,
        dockState,
        dockActions,
        strip = if (page.stripShown) {
            {
                Box(Modifier.graphicsLayer { translationY = page.stripBack.value * FocalGrid.x16.toPx(); alpha = 1 - page.stripBack.value }) {
                    StripMotion(stripWanted, onGone = {
                        page.stripShown = false
                        scope.launch { page.stripBack.snapTo(0f) }
                    }) {
                        PlayerStrip(position, onSeek = { ms ->
                            page.rangeEndMs = null
                            playback.playFrom(ms)
                        })
                    }
                }
            }
        } else null,
        rows = if (find.open) { { FindRows(find, findActions) } } else null,
        dockHidden = page.editing || page.renaming || find.open,
    )

    // Strings for the head, notices and the paragraphs' actions.
    val head = headUi(note, paragraphs, dates, today, captureElsewhere, actions, interactive = !page.editing && !find.open)
    val labels = ParagraphLabels(
        play = stringResource(R.string.a11y_play_here), edit = stringResource(R.string.a11y_edit),
        copy = stringResource(R.string.a11y_copy_paragraph), restore = stringResource(R.string.menu_restore),
        playUnclear = stringResource(R.string.a11y_play_unclear),
    )
    val noteReady = stringResource(R.string.a11y_note_ready)
    var announcement by remember { mutableStateOf("") }
    val wasWorking = remember { PhaseMemory() }
    LaunchedEffect(note.phase) {
        if (note.phase == NotePhase.READY && wasWorking.working) announcement = noteReady
        wasWorking.working = note.phase == NotePhase.TRANSCRIBING || note.phase == NotePhase.PREPARING_MODEL || note.phase == NotePhase.WAITING
    }

    // The search hit (spec 5.2): scrolled to the reading band's centre and marked for 1.5 s, once: a recreated page
    // keeps the place the reader had reached.
    LaunchedEffect(destination.hit, items.size > 1) {
        val hit = destination.hit?.takeUnless { page.hitShown } ?: return@LaunchedEffect
        val index = indexOf[hit.paragraphId] ?: return@LaunchedEffect
        withFrameMillis { }
        val line = lineBox(page.layouts[hit.paragraphId], hit.start)
        val target = bandTop() + (bandBottom() - bandTop()) * HALF - (line.second - line.first) * HALF
        scroller.bring(index, { lineBox(page.layouts[hit.paragraphId], hit.start).first }, target)
        page.hitShown = true
        focus.hit = hit
        focus.hitAlpha.snapTo(1f)
        delay(FocalTiming.ARRIVAL_HIGHLIGHT_MS)
        focus.hitAlpha.animateTo(0f, FocalMotion.fade())
        focus.hit = null
    }

    val editT = remember { Animatable(if (page.editing) 1f else 0f) }
    LaunchedEffect(page.editing) { editT.animateTo(if (page.editing) 1f else 0f, FocalMotion.fadeFast()) }
    val findT = remember { Animatable(if (find.open) 1f else 0f) }
    LaunchedEffect(find.open) { findT.animateTo(if (find.open) 1f else 0f, FocalMotion.fade()) }
    val collapsed by remember { derivedStateOf { page.titleBottom < bandTop() } }
    // The bars cross-fade (`fadeFast`); each is composed only while it shows, decided twice per change, not per frame.
    val topBarShown by remember { derivedStateOf { editT.value < 1f } }
    val editBarShown by remember { derivedStateOf { editT.value > 0f } }
    // A note with no text has nothing to edit or find (spec 5.9: no speech).
    val editable = note.editable && paragraphs.isNotEmpty()
    val menuItems = NoteMenuItems(
        find = editable || (note.phase == NotePhase.READY && !note.audioPresent && paragraphs.isNotEmpty()),
        share = note.phase == NotePhase.READY || note.phase == NotePhase.PARTIAL_FAILURE,
        removeAudio = note.canRemoveAudio,
        delete = note.phase !in LibraryModels.BUSY,
    )
    val menuActions = run {
        NoteMenuActions(
            find = { openFind() }, rename = { page.renaming = true }, share = actions.share,
            removeAudio = {
                playback.stop()
                actions.removeAudio()
            },
            delete = {
                playback.stop()
                actions.delete()
            },
            audioSize = actions.audioSize,
        )
    }
    val editBarActions = run {
        EditBarActions(undo = undo, redo = redo, done = { done() }, restore = restore, find = { openFind() })
    }
    val focusedParagraph = page.field?.id?.let { id -> paragraphs.firstOrNull { it.id == id } }

    // The page's shortcuts (spec 8.5): each where it applies; false leaves the key to whoever else wants it.
    val reading = (mode == NoteMode.Reading || mode == NoteMode.Playing) && !page.renaming
    val onKey: (PageKey) -> Boolean = { key ->
        when (key) {
            PageKey.Undo -> page.editing.also { if (it) undo() }
            PageKey.Redo -> page.editing.also { if (it) redo() }
            PageKey.Find -> (!find.open).also { if (it) openFind() }
            PageKey.PlayPause -> (reading && canPlay).also { if (it) { if (playingHere && audio.playing) playback.toggle() else dockActions.play() } }
            PageKey.Copy -> (reading && selection.selectedTexts.none { it.isNotEmpty() } && dockState.pill is NotePill.Copy).also {
                if (it) scope.launch { actions.copy() }
            }
        }
    }
    // Heard by the activity when nothing on the page has focus; only the page on top answers.
    PageKeysEffect { key -> shell.current == destination && onKey(key) }

    Box(
        Modifier
            .fillMaxSize()
            // A focused field or control inside the page (Edit's field, Find's query) hears them first, here.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val key = when {
                    event.isCtrlPressed && event.key == Key.Z -> if (event.isShiftPressed) PageKey.Redo else PageKey.Undo
                    event.isCtrlPressed && event.key == Key.F -> PageKey.Find
                    event.isCtrlPressed && event.key == Key.C -> PageKey.Copy
                    event.key == Key.Spacebar -> PageKey.PlayPause
                    else -> null
                }
                key != null && onKey(key)
            }
            .then(
                // While the title is renamed, a tap anywhere else commits it and does nothing else (spec 5.5).
                if (!page.renaming) Modifier else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                            if (down.position.y > page.titleBottom) {
                                event.changes.forEach { it.consume() }
                                focusManager.clearFocus()
                            }
                        }
                    }
                },
            ),
    ) {
        // The bars come first, so keyboard focus and TalkBack meet them before the text (spec 8.5); they draw on top.
        val chrome = { (1 - findT.value) * reveal.chrome() }
        if (topBarShown) {
            NoteTopBar(
                title = head.title, titleMuted = head.titleFallback, collapsed = collapsed && mode != NoteMode.Editing,
                editable = editable, menuItems = menuItems, menuOpen = page.menu, onMenu = { page.menu = it }, menu = menuActions,
                onBack = actions.back, onEdit = { enterEdit(null) },
                chrome = { chrome() * (1 - editT.value) },
                modifier = Modifier.zIndex(BARS_Z).inertBar(page.editing || find.open),
            )
        }
        if (editBarShown) {
            EditBar(
                canUndo = session.canUndo, canRedo = session.canRedo,
                canRestore = focusedParagraph?.let { it.edited && it.canRestore } == true,
                failed = session.failed, menuOpen = page.editMenu, onMenu = { page.editMenu = it }, actions = editBarActions,
                modifier = Modifier.zIndex(BARS_Z).graphicsLayer { alpha = editT.value * chrome() }.inertBar(!page.editing || find.open),
            )
        }
        val topPadding = with(density) { status.getTop(this).toDp() } + FocalLayout.topBar + FocalGrid.x8
        val padding = remember(topPadding, bottom, density) { bottom.contentPadding(topPadding, density) }
        val bringSpec = remember(density, status) { CaretSpec({ bandTop() + with(density) { CaretMargin.toPx() } }, { with(density) { CaretMargin.toPx() } }) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringSpec) {
            SelectionContainer(selection, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = listFade.value * reveal.body() }, contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = FocalLayout.contentMaxWidth + FocalLayout.gutter * 2)
                            .fillMaxWidth()
                            .imePadding(),
                        contentPadding = padding,
                        verticalArrangement = Arrangement.spacedBy(ParagraphMetrics.gap),
                    ) {
                        items(items, key = { it.key }, contentType = { contentTypeOf(it) }) { item ->
                            when (item) {
                                NoteItem.Head -> NoteHead(
                                    noteId = note.noteId,
                                    head = head,
                                    titleInks = focus.titleInks,
                                    renaming = page.renaming,
                                    canRename = mode == NoteMode.Reading || mode == NoteMode.Playing,
                                    onRename = { page.renaming = true },
                                    onCommitRename = { text ->
                                        page.renaming = false
                                        if (text != note.title || note.titleIsCustom) actions.rename(text)
                                    },
                                    onTitleBottom = { page.titleBottom = it },
                                    entrance = landing,
                                    modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null),
                                )
                                is NoteItem.Text -> ParagraphItem(
                                    item, page, placement,
                                    arrival = page.arrivals.of(item.paragraph.id, SystemClock.uptimeMillis()),
                                    entrance = entranceDelay(landing, item.index, paragraphs.size),
                                    onTap = onTap, tapBlocked = { selection.selectedTexts.any { it.isNotEmpty() } },
                                    actions = paragraphActions(
                                        item.paragraph, labels, note, canPlay, page.editing, play = playFromWord,
                                        enterEdit = { enterEdit(it) }, copy = actions.copyParagraph, restore = restoreOutside,
                                    ),
                                    onEnter = { offset ->
                                        val current = page.field ?: return@ParagraphItem
                                        val draft = current.text.text.toString()
                                        withDraft { session.split(current.id, draft, offset) }
                                    },
                                    onJoin = join,
                                    onRestore = restore,
                                )
                                is NoteItem.Gap -> GapRow(
                                    item.gap, playable = note.audioPresent && note.canPlay,
                                    onPlay = {
                                        page.rangeEndMs = item.gap.endMs
                                        page.playerDismissed = false
                                        playback.playRange(item.gap.startMs, item.gap.endMs)
                                    },
                                    modifier = Modifier.animateItem(fadeInSpec = FocalMotion.fade(), placementSpec = placement, fadeOutSpec = FocalMotion.fadeFast()),
                                )
                                NoteItem.Writing -> WritingHead(
                                    working = note.phase != NotePhase.WAITING,
                                    modifier = Modifier.animateItem(fadeInSpec = FocalMotion.fade(), placementSpec = placement, fadeOutSpec = FocalMotion.fadeFast()),
                                )
                                NoteItem.NoSpeech -> NoSpeechLine()
                            }
                        }
                    }
                }
            }
        }
        // The top fade under the bar; Find's is shorter (spec 2.6).
        PaperTopFade(FocalLayout.topBar, Modifier.fillMaxSize().zIndex(FADES_Z), alpha = { (1 - findT.value) * reveal.chrome() })
        PaperTopFade(0.dp, Modifier.fillMaxSize().zIndex(FADES_Z), fade = FindFade, alpha = { findT.value * reveal.chrome() })
        BottomFade(bottom, Modifier.fillMaxSize().zIndex(FADES_Z).graphicsLayer { alpha = (1 - editT.value) * reveal.chrome() }, keyboard = find.open)
        JumpPill(
            visible = page.playingOutside != null && stripWanted,
            up = page.playingOutside == true,
            bottom = bottom,
            onClick = {
                page.followSuspended = false
                page.playingOutside = null
            },
            modifier = Modifier.fillMaxSize().zIndex(FADES_Z),
        )
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
    }
}
