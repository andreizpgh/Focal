package app.focal.ui.note

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.focal.R
import app.focal.ui.components.BottomFade
import app.focal.ui.components.DockActions
import app.focal.ui.components.DockState
import app.focal.ui.components.FeedbackAction
import app.focal.ui.components.FeedbackResult
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.NotePill
import app.focal.ui.components.PageArea
import app.focal.ui.components.PaperTopFade
import app.focal.ui.components.pageInsets
import app.focal.ui.components.rememberRowPlacement
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.FocalType
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.design.columnInset
import app.focal.ui.library.LibraryDates
import app.focal.ui.library.LibraryModels
import app.focal.ui.library.RenameController
import app.focal.ui.library.noteDock
import app.focal.ui.shell.BackEase
import app.focal.ui.shell.Destination
import app.focal.ui.shell.DestinationBottom
import app.focal.ui.shell.LocalPageReveal
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.PageKey
import app.focal.ui.shell.PageKeysEffect
import app.focal.ui.shell.rememberShortcutKeys
import app.focal.ui.shell.shortcutKeys
import app.focal.ui.state.EditOutcome
import app.focal.ui.state.GapUi
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteUiState
import app.focal.ui.state.ParagraphUi
import app.focal.ui.state.PlayingWord
import app.focal.ui.state.SeekSnap
import app.focal.ui.state.WriteQueue
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One row of the note's list. */
@Immutable
internal sealed interface NoteItem {
    val key: Any

    data object Head : NoteItem { override val key: Any = "head" }
    data class Text(val paragraph: ParagraphUi, val index: Int) : NoteItem { override val key: Any = paragraph.id }
    /** The time the paragraph under it starts at in the recording. */
    data class Time(val paragraph: ParagraphUi, val index: Int = 0) : NoteItem { override val key: Any = timeKey(paragraph.id) }
    data class Gap(val gap: GapUi) : NoteItem { override val key: Any = "gap:${gap.startMs}" }
    data object Writing : NoteItem { override val key: Any = "writing" }
    data object NoSpeech : NoteItem { override val key: Any = "no-speech" }
}

/** The list key of the time over paragraph [id]. */
internal fun timeKey(id: Long): String = "time:$id"

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

/** The find match last centred in the band, and whether the keyboard was up then. */
private class CentredMatch {
    var match: Any? = null
    var keyboardUp = false
}

/**
 * The list keys last drawn in Edit: a paragraph put just before the first one on screen (the text before a split) takes
 * that one's place there ([NotePage]).
 */
private class ShownKeys {
    var keys: List<Any> = emptyList()
}

/** A tap at [offset] of paragraph [id]: the [word] under it selected, or the caret there on bare paper. */
private fun wordOrCaret(id: Long, offset: Int, word: IntRange?): CaretAt =
    if (word == null) CaretAt(id, offset) else CaretAt(id, word.first, word.last + 1)

/** The head as Edit found it: it stays as it was until Done ([NotePage]). */
private class HeldHead {
    var head: HeadUi? = null
}

/** The field and the page's changes as a write that reseeds the field found them ([NotePage]). */
private class FieldMark(val field: FieldFocus?, val text: String?, val changes: Int)

/** Whether the page is still composed: what a page that went had asked for is left to the page after it. */
private class PageLife {
    var gone = false
}

/** Above the text: the fades and the jump pill, then the bars. */
private const val FADES_Z = 1f
private const val BARS_Z = 2f

/** Edit keeps the caret's line this far inside the visible band, above the keyboard (spec 5.7). */
private val CaretMargin = FocalGrid.x24

/** ... and this far in a short window (a phone in landscape), where the band is a few lines tall. */
private val ShortCaretMargin = FocalGrid.x8

/**
 * The note page (spec 5.4 to 5.9): a document. Its title, meta and notices, then the paragraphs in a lazy list, with
 * gap rows where a part could not be transcribed and the writing head while the tail transcribes. With
 * [paragraphTimes], a note that has audio carries a small time over each paragraph after the first: where it starts in
 * the recording. A word tap opens review and a long press selects a phrase. The menu opens the full editor. ▶ in the dock plays the note (the
 * playing paragraph stays in ink while the rest recede and the lens follows the word), and Edit's own ▶ plays just the
 * words at the caret. ⋮ opens Find and replace, Rename, Share, Remove audio and Delete. The dock is Play | Copy (or the
 * progress pill); the player strip, the find rows and the feedback pill share the bottom column.
 *
 * Everything is drawn from [note] and the page's own state, so the gallery renders it from fakes; [playback] and
 * [edits] are the activity's player and the note's store, or fakes.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    /** Finish was tapped for this note's recording: its capture is closing, and the page already shows it as over. */
    captureFinishing: Boolean = false,
    landing: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    page: NotePageState = rememberNotePageState(),
    /** The setting "Paragraph times": a note with audio shows where each paragraph after the first starts. */
    paragraphTimes: Boolean = false,
) {
    val shell = LocalShell.current
    val bottom = shell.bottom
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The note's view model's session on a real page, which outlives the page; the page's own in the gallery and tests.
    val ownSession = remember(edits) { EditSession(edits) }
    val session = actions.session ?: ownSession
    // Every write of Edit, one at a time in the order asked for (the note's view model's queue on a real page).
    val ownWrites = remember(scope) { WriteQueue(scope) }
    val writes = actions.writes ?: ownWrites
    val life = remember { PageLife() }
    DisposableEffect(life) { onDispose { life.gone = true } }
    val latestNote by rememberUpdatedState(note)
    val focus = page.focus
    val find = page.find
    val renameController = remember { RenameController() }
    val listFade = remember { Animatable(1f) }
    val scroller = remember(listState) { NoteScroller(listState, listFade) }
    // A far jump cut short leaves the text faded: it comes back on `fade` (NoteScroller.restoresFade).
    LaunchedEffect(scroller) {
        // Latest: a new far jump that takes the fade over cancels only this restore, not the watch for the next one.
        snapshotFlow { scroller.restoresFade }.collectLatest { stuck -> if (stuck) listFade.animateTo(1f, FocalMotion.fade()) }
    }
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
    // Observe synchronously before ParagraphItem reads arrivals.of(...) so Frame 0 already carries any new arrival.
    page.arrivals.observe(note.paragraphs, transcribing, SystemClock.uptimeMillis())
    val writing = transcribing && !note.noSpeech
    // Times only while there is audio to go to: without it they are numbers about nothing.
    val times = paragraphTimes && note.audioPresent
    val items = remember(paragraphs, note.gaps, writing, note.noSpeech, times) { noteItems(paragraphs, note.gaps, writing, note.noSpeech, times) }
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
    val topBar = LocalWindowLayout.current.topBar
    val bandTop: () -> Float = { status.getTop(density).toFloat() + with(density) { topBar.toPx() } }
    val bandBottom: () -> Float = { bottom.top.value.takeUnless { it.isNaN() } ?: listState.layoutInfo.viewportSize.height.toFloat() }
    // The lines on screen, for landing them on the top band's edge after a bring (TopEdge).
    val fadePx = with(density) { FocalLayout.topFade.toPx() }
    val titleLinePx = with(density) { FocalType.title.lineHeight.toPx() }
    val itemsNow = rememberUpdatedState(items)
    val topLines: () -> List<Span> = {
        windowLines(listState, itemsNow.value, page.layouts, page.titleTop, page.titleBottom, titleLinePx, page.headLines)
    }
    fun lineHeight(id: Long, offset: Int?): () -> Float = { lineBox(page.layouts[id], offset).let { it.second - it.first } }
    val follow = remember { FollowJob() }
    val scrubbing by remember { derivedStateOf { position.scrubMs != null } }
    LaunchedEffect(playingHere, audio.playing, audio.parked, scrubbing, paragraphs, items, note.gaps) {
        if (!playingHere) {
            page.seekPin = null
            focus.playingId = null
            focus.playingWord = null
            page.playingOutside = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastLocatedMs = Long.MIN_VALUE
            var initialized = false
            do {
                var locatedThisFrame = false
                withFrameMillis {
                    if (playback.session.playing) position.frame++
                    val ms = position.ms()
                    if (!initialized || ms != lastLocatedMs) {
                        initialized = true
                        lastLocatedMs = ms
                        locatedThisFrame = true
                        val scrubOrParked = position.scrubMs != null || audio.parked
                        if (position.scrubMs != null) page.seekPin = null
                        val location = if (scrubOrParked) {
                            SeekSnap.locateScrub(paragraphs, note.gaps, ms)
                        } else {
                            val pin = page.seekPin
                            if (pin != null) {
                                if (!position.gliding && (ms >= pin.wordStartMs || ms < pin.targetMs - 200L)) {
                                    page.seekPin = null
                                    PlayingWord.locate(paragraphs, ms)
                                } else {
                                    pin.location
                                }
                            } else {
                                PlayingWord.locate(paragraphs, ms)
                            }
                        }
                        val paragraph = location?.let { paragraphs.getOrNull(it.paragraphIndex) }
                        val word = location?.wordIndex
                        val suppressParagraph = audio.parked && word == null
                        val nextId = if (suppressParagraph) null else paragraph?.id
                        if (focus.playingId != nextId) focus.playingId = nextId
                        if (focus.playingWord != word) focus.playingWord = word
                    }
                }
                val id = focus.playingId
                val index = id?.let { indexOf[it] }
                if (locatedThisFrame && id != null && index != null && !audio.parked) {
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
                            // The lines at the top land on the band's edge; the playing line stays in the middle third.
                            val edge = EdgeSnap(topLines, bandTop, fadePx, { bandTop() + third }, { bandTop() + 2 * third })
                            scroller.bring(index, { lineBox(page.layouts[id], word?.start).first }, bandTop() + third, lineHeight(id, word?.start), edge)
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
    LaunchedEffect(audio.playing, audio.noteId, audio.parked) {
        if (audio.playing || audio.noteId != note.noteId || audio.parked) return@LaunchedEffect
        val end = page.rangeEndMs ?: position.durationMs
        if (end > 0 && playback.positionMs() >= end - END_SLACK_MS) {
            page.rangeEndMs = null
            page.listening = false
            playback.stop()
        }
    }
    val stripWanted = playingHere && !page.editing && !find.open
    LaunchedEffect(stripWanted) { if (stripWanted) page.stripShown = true }

    // Edit (spec 5.7). Every write goes through [writes], one at a time in the order asked for, and reads the field
    // when it runs; Enter and Backspace change the page at once, so what is typed after them is never lost. The
    // session reads the field from here.
    val cannotJoin = stringResource(R.string.error_join)
    val currentCannotJoin by rememberUpdatedState(cannotJoin)
    val tooLate = stringResource(R.string.feedback_too_late)
    val currentTooLate by rememberUpdatedState(tooLate)
    val hold = remember(session) { SessionHold() }
    SideEffect {
        // A field restored with the page is the session's once the page has taken up the session (below).
        session.field = { page.field?.takeIf { page.editing && page.fieldBound }?.let { FieldText(it.id, it.text.text.toString()) } }
        page.storedId = session::resolve
        page.fieldShown = { id -> session.paragraph(id)?.text }
        session.tooLate = { scope.launch { bottom.feedback.show(currentTooLate) } }
        // A write that was still running when the field was kept changed its paragraph: the field takes the new text,
        // unless something was typed in it since.
        session.fieldMoved = { from, to ->
            val current = page.field
            if (current != null && session.resolve(current.id) == session.resolve(from.id) && current.text.text.toString() == from.text) {
                page.field = to?.let { FieldFocus(it.id, it.text, TextRange(it.start, it.end)) }
                focus.editingId = to?.id
            }
        }
        // A join the store refused after the page showed it: the paragraph comes back, and leaves the field's text.
        session.joinRefused = { id, prefix ->
            val current = page.field
            if (current != null && session.resolve(current.id) == session.resolve(id) && current.text.text.startsWith(prefix)) {
                current.text.edit {
                    val from = this.selection
                    replace(0, prefix.length, "")
                    this.selection = TextRange((from.start - prefix.length).coerceAtLeast(0), (from.end - prefix.length).coerceAtLeast(0))
                }
            }
            scope.launch { bottom.feedback.show(currentCannotJoin) }
        }
        hold.field = session.field
    }
    // The session outlives the page (its view model is kept for the note's next visit): a page that goes lets go of
    // what it lent it, which holds the page and its laid-out text, unless a page after it has taken the session up.
    DisposableEffect(session, hold) {
        onDispose {
            if (session.field === hold.field) {
                session.field = { null }
                session.tooLate = {}
                session.fieldMoved = { _, _ -> }
                session.joinRefused = { _, _ -> }
            }
        }
    }
    // The field stays on its paragraph when that paragraph's stored id changes (a split it was typed in reached the
    // store, or an undo or redo brought it back under a new id).
    fun followField() {
        val current = page.field ?: return
        val id = session.resolve(current.id)
        if (id == current.id) return
        page.field = FieldFocus(id, current.text.text.toString(), current.text.selection)
        focus.editingId = id
    }
    // A page that went (recreated, or replaced beside the library) leaves what it still had asked for to the page
    // after it, which goes on with the session: a caret or a reseed would land on a field no one sees. Its last save
    // (below) runs all the same.
    fun write(block: suspend () -> Unit) {
        writes.launch {
            if (life.gone) return@launch
            block()
            if (!life.gone) followField()
        }
    }
    // [commit]: a tap moved the caret, so the paragraph it left keeps its draft on screen and is saved in turn; a
    // split, join, undo or restore already wrote it (saving its old draft again would undo the step).
    fun focusField(at: CaretAt, refresh: Boolean = true, commit: Boolean = false) {
        val id = session.resolve(at.id)
        val text = session.paragraph(id)?.text ?: paragraphs.firstOrNull { it.id == id }?.text ?: return
        val current = page.field
        if (current != null && session.resolve(current.id) == id) {
            current.text.edit {
                if (refresh && toString() != text) replace(0, length, text)
                this.selection = TextRange(at.caret.coerceIn(0, length), at.end.coerceIn(0, length))
            }
        } else {
            if (current != null && commit) {
                session.leave(current.id, current.text.text.toString())
                write { session.save() }
            }
            page.field = FieldFocus(id, text, TextRange(at.caret, at.end))
        }
        focus.editingId = id
    }
    // Edit starts in turn too: a Replace all still writing outside Edit lands first, and the field starts from its
    // result, never from the words it replaced.
    fun enterEdit(at: CaretAt?, review: Boolean = false) {
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
        write {
            if (!latestNote.editable || page.editing) return@write
            session.begin(session.paragraphs(latestNote.paragraphs).filter { it.text.isNotBlank() })
            page.editing = true
            page.reviewing = review
            (at ?: playingAt)?.let { focusField(it) }
        }
    }
    fun exitEdit(hideKeyboard: Boolean = true) {
        // A clip Edit was playing ends with it (the player strip stays away: it was never the page's playback).
        if (page.listening) {
            page.listening = false
            page.rangeEndMs = null
            playback.stop()
        }
        page.editing = false
        page.reviewing = false
        page.field = null
        page.editMenu = false
        focus.editingId = null
        session.end()
        if (hideKeyboard) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    val saveFailed = stringResource(R.string.feedback_save_failed)
    // Done, Esc and Back store everything shown, as the writes before them left it: an undo or redo still writing (a
    // busy database holds it) reseeds the field first, so its text is never saved back over the undo as a new step.
    // What is typed while Done's own write runs is stored too: Edit ends once a save finds the field as it started.
    fun done() {
        write {
            while (true) {
                val before = session.field()
                if (!session.save()) {
                    if (session.failures >= 2) scope.launch { bottom.feedback.show(saveFailed) }
                    return@write
                }
                if (life.gone) return@write
                if (session.field() == before) break
            }
            exitEdit()
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
    // everything shown (once the writes on their way have reseeded it). The store may refuse it; the pill says so.
    val notSaved = stringResource(R.string.error_save)
    LaunchedEffect(note.loaded, note.editable) {
        if (!note.loaded || note.editable || !page.editing) return@LaunchedEffect
        write {
            if (!page.editing) return@write
            val saved = session.save()
            exitEdit()
            if (!saved) scope.launch { bottom.feedback.show(notSaved) }
        }
    }
    // A write that changes the stored text under the field (undo, redo, restore, Replace all) has the field take the
    // result, unless something was typed, Enter or Backspace pressed or the caret moved while it ran: what the person
    // did since wins and the next write stores it, so nothing typed is dropped for a step it came after. (A field whose
    // paragraph the step took away goes where the step puts the caret.)
    fun mark() = page.field.let { FieldMark(it, it?.text?.text?.toString(), session.changesMade) }
    fun untouched(since: FieldMark): Boolean {
        val current = page.field
        val same = current === since.field && current?.text?.text?.toString() == since.text && session.changesMade == since.changes
        return same || current?.let { session.paragraph(it.id) } == null
    }
    fun step(run: suspend () -> CaretAt?) {
        write {
            val since = mark()
            val caret = run() ?: return@write
            if (!life.gone && untouched(since)) focusField(caret)
        }
    }
    val undo: () -> Unit = { step { session.undo() } }
    val redo: () -> Unit = { step { session.redo() } }
    val restore: () -> Unit = { step { session.restore() } }
    // Enter, from the field's input handling: the field already holds the text after the caret; the text before it
    // is drawn as a new paragraph above at once, and the store splits in turn.
    val enter: (String, Int) -> Unit = { full, at ->
        page.field?.let { current ->
            session.split(current.id, full, at)
            write { session.save() }
        }
    }
    // Backspace at a paragraph's start: the paragraph before goes in front of the field's text at once. A join the
    // store keeps apart (a paragraph whose original is unknown beside one whose original is known) keeps both and the
    // caret, and says so in the pill (spec 4.8: no haptic, 2.9).
    val join: () -> Unit = {
        page.field?.let { current ->
            when (val joined = session.join(current.id, current.text.text.toString())) {
                is JoinResult.Joined -> {
                    current.text.edit {
                        replace(0, 0, joined.prefix)
                        this.selection = TextRange(joined.caret.coerceIn(0, length))
                    }
                    write { session.save() }
                }
                JoinResult.Refused -> {
                    scope.launch { bottom.feedback.show(cannotJoin) }
                    write { session.save() }
                }
                JoinResult.Skipped -> Unit
            }
        }
    }
    // A page recreated in Edit (rotation, process death), or a note opened again beside the library while its Edit
    // session runs, goes on with the session once the note is there. The view model's session goes on as it is, with
    // the field the page before it parked (fresher than the saved state's). After process death the session starts
    // again from the saved state: its log and changes, and the empty paragraphs it had made (the field may be typing
    // in one). Either way the first write checks the field against its paragraph (a write may have been running as
    // the field was kept). The saved state reads the log from here.
    LaunchedEffect(note.loaded) {
        if (!note.loaded) return@LaunchedEffect
        val restored = page.field?.takeIf { !page.fieldBound }
            ?.let { KeptField(it.id, it.text.text.toString(), it.text.selection.start, it.text.selection.end, page.restoredShown) }
        page.restoredShown = null
        if (!session.started) {
            if (!page.editing) return@LaunchedEffect
            session.begin(paragraphs, page.restoredLog, note.blankParagraphs, (restored?.id ?: page.field?.id))
            page.restoredLog = null
        }
        page.editing = true
        val kept = session.unpark() ?: restored
        if (kept != null) {
            val id = session.resolve(kept.id)
            val current = page.field
            if (current == null || current.id != id || current.text.text.toString() != kept.text) {
                page.field = FieldFocus(id, kept.text, TextRange(kept.start, kept.end))
            }
            focus.editingId = id
            session.adopt(kept)
            write { session.check() }
        }
        page.fieldBound = true
    }
    DisposableEffect(page, session) {
        // With the focused field's draft: its last save (below) is still on its way when the state is saved.
        page.editLog = { session.log() ?: page.restoredLog }
        onDispose { page.editLog = { page.restoredLog } }
    }
    // Everything not stored yet is written when the activity stops (recreation included) and when the page goes, after
    // the writes already asked for, in the note's own scope: nothing typed in the last 600 ms is lost, and an undo still
    // writing is never overwritten by the text it replaces. A page that goes leaves its field with the session, which
    // stores it and hands it to the next page (a save that fails then is not lost with the page).
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && page.editing && page.fieldBound) write { session.save() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (page.editing && page.fieldBound && session.started) {
                session.park(page.field?.let { session.keep(it.id, it.text.text.toString(), it.text.selection.start, it.text.selection.end) })
                // Not left to the page after it: this one is the page's own last save.
                writes.launch { session.save() }
            }
        }
    }
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    // Autosave 600 ms after typing stops (spec 5.7).
    val field = page.field
    LaunchedEffect(field) {
        val current = field ?: return@LaunchedEffect
        snapshotFlow { current.text.text.toString() }.drop(1).collectLatest {
            delay(FocalTiming.AUTOSAVE_DEBOUNCE_MS)
            write { session.save() }
        }
    }
    LaunchedEffect(field, find.open) {
        if (field == null || find.open || !page.takesFocus) return@LaunchedEffect
        // A step that put the caret in a paragraph off screen (an undo or a redo of a change made elsewhere): the list
        // goes there first, so its field exists to take the focus and the keyboard stays.
        val index = indexOf[field.id]
        if (index != null && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
            withFrameMillis { }
        }
        runCatching { field.requester.requestFocus() }
        keyboard?.show()
    }
    // A paragraph put just before the first one on screen in Edit (the text before a split, by Enter or a redo) takes
    // its place there, so the lines stay put and the rest moves down, as text does where Enter is pressed: the list
    // would keep the first paragraph's own key in place and push the new one out of view above it.
    val itemKeys = remember(items) { items.map { it.key } }
    val shownKeys = remember { ShownKeys() }
    SideEffect {
        val before = shownKeys.keys
        shownKeys.keys = itemKeys
        if (!page.editing || before === itemKeys || before.isEmpty()) return@SideEffect
        val first = before.getOrNull(listState.firstVisibleItemIndex) ?: return@SideEffect
        val now = indexOf[first] ?: return@SideEffect
        // The paragraph just above it (a time between them belongs to the lower one), with its own time if it has one.
        val above = itemKeys.subList(0, now).indexOfLast { it is Long }
        val added = itemKeys.getOrNull(above)
        if (added is Long && added !in before) {
            val from = if (itemKeys.getOrNull(above - 1) == timeKey(added)) above - 1 else above
            listState.requestScrollToItem(from, listState.firstVisibleItemScrollOffset)
        }
    }

    // Find and replace (spec 5.8).
    fun openFind() {
        if (!note.editable && !(note.phase == NotePhase.READY && !note.audioPresent)) return
        page.menu = false
        page.editMenu = false
        if (page.editing) write { session.save() }
        if (playingHere) {
            playback.pause()
            page.playerDismissed = true
        }
        find.open(fromEdit = page.editing)
    }
    // Back to Edit, the field shows what it held: Replace all and its Undo gave it the stored text in their own writes
    // when nothing was typed since, and Find kept it from being typed in.
    fun closeFind() {
        val back = find.fromEdit
        find.close()
        if (!back) {
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
    // A keyboard that opens after the match was placed (a query already in the field) moves the band up: the match is
    // brought to its new centre once the keyboard has settled, never left under the rows (spec 5.8, 5.14).
    val keyboardUp = imeTarget.getBottom(density) > 0
    val centred = remember { CentredMatch() }
    LaunchedEffect(currentMatch, find.open, keyboardUp) {
        val match = currentMatch?.takeIf { find.open } ?: return@LaunchedEffect
        val index = indexOf[match.paragraphId] ?: return@LaunchedEffect
        // The keyboard going away leaves a placed match where it is; only a new match or a rising keyboard moves it.
        if (match == centred.match && centred.keyboardUp && !keyboardUp) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) == imeTarget.getBottom(density) }.first { it }
        centred.match = match
        centred.keyboardUp = keyboardUp
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
            is EditOutcome.Failed -> outcome.message.ifBlank { otherwise }
            else -> otherwise
        }
        scope.launch { bottom.feedback.show(message) }
    }
    // Replace all and its Undo are writes in turn too: inside Edit they first store everything shown, so the store
    // replaces in the text the page shows and the field takes the result back.
    val replaceAll: () -> Unit = {
        val query = find.matchedQuery
        val replacement = find.replacement.text.toString()
        val label = replacedTemplate
        write {
            if (page.editing && !session.save()) {
                scope.launch { bottom.feedback.show(notSaved) }
                return@write
            }
            val since = mark()
            val before = session.storedTexts()
            val outcome = actions.replaceAll(query, replacement)
            if (outcome !is EditOutcome.Saved) {
                report(outcome, if (outcome == EditOutcome.TooLarge) words.tooLarge else words.replace)
                return@write
            }
            if (page.editing) {
                session.recordReplace(outcome.paragraphs, before)
                if (untouched(since)) reseedField()
            } else session.applySaved(outcome.paragraphs)
            find.refresh()
            // The pill waits for its Undo outside the queue; the Undo is a write in turn.
            scope.launch {
                if (bottom.feedback.show(label, FeedbackAction(undoLabel, FocalIcon.Undo)) != FeedbackResult.ActionPerformed) return@launch
                write {
                    val undoing = mark()
                    if (page.editing && session.undoReplace()) {
                        if (untouched(undoing)) reseedField()
                    } else {
                        val undone = actions.undoReplace()
                        if (undone is EditOutcome.Saved) {
                            session.applySaved(undone.paragraphs)
                            if (untouched(undoing)) reseedField()
                        } else report(undone, words.tooLate)
                    }
                    find.refresh()
                }
            }
        }
    }

    // All reading-mode edits use the saved revision for Undo. A later change cannot be overwritten.
    fun offerUndo(before: ParagraphUi, saved: EditOutcome.Saved, label: String) {
        session.applySaved(saved.paragraphs)
        val after = saved.paragraphs.firstOrNull { it.id == before.id } ?: return
        scope.launch {
            if (bottom.feedback.show(label, FeedbackAction(undoLabel, FocalIcon.Undo)) != FeedbackResult.ActionPerformed) return@launch
            write {
                if (page.editing && !session.save()) {
                    scope.launch { bottom.feedback.show(notSaved) }
                    return@write
                }
                val since = mark()
                val outcome = edits.edit(after.id, after.revision, before.text)
                if (outcome is EditOutcome.Saved) {
                    session.applySaved(outcome.paragraphs)
                    if (page.editing && untouched(since)) reseedField()
                } else report(outcome, words.tooLate)
            }
        }
    }
    val restoredLabel = stringResource(R.string.feedback_restored)
    val restoreOutside: (ParagraphUi) -> Unit = { paragraph ->
        write {
            val outcome = edits.restore(paragraph.id, paragraph.revision)
            if (outcome is EditOutcome.Saved) offerUndo(paragraph, outcome, restoredLabel) else report(outcome, words.restore)
        }
    }

    val playFromWord: (ParagraphUi, Int) -> Unit = { paragraph, offset ->
        page.rangeEndMs = null
        page.seekPin = null
        page.playerDismissed = false
        page.followSuspended = false
        playback.playWord(paragraph, offset)
    }

    val canPlay = note.canPlay && note.audioPresent
    val canEdit = note.editable
    val unclear = remember(paragraphs, canEdit) {
        if (canEdit) paragraphs.flatMap { it.unclearSpans() } else emptyList()
    }
    val focusedParagraph = page.field?.id?.let { id -> paragraphs.firstOrNull { it.id == id } }
    val fieldText = page.field?.text?.text?.toString()
    val fieldSelection = page.field?.text?.selection
    val fieldDirty = focusedParagraph != null && fieldText != null && fieldText.trim() != focusedParagraph.text
    val activeUnclear = if (page.editing && !find.open && !fieldDirty && focusedParagraph != null && fieldSelection != null) {
        unclear.firstOrNull { span ->
            span.paragraphId == focusedParagraph.id && if (fieldSelection.collapsed) {
                fieldSelection.start in span.start..span.end
            } else {
                fieldSelection.min < span.end && fieldSelection.max > span.start
            }
        }
    } else null
    val activeSpan = activeUnclear ?: if (page.editing && !find.open && !fieldDirty && focusedParagraph != null && fieldSelection != null) {
        if (!fieldSelection.collapsed && fieldSelection.max <= focusedParagraph.text.length) {
            ReviewSpan(focusedParagraph.id, focusedParagraph.revision, fieldSelection.min, fieldSelection.max)
        } else {
            wordIn(focusedParagraph.text, fieldSelection.start)?.let { focusedParagraph.reviewSpan(it) }
        }
    } else null
    val inReview = page.reviewing || activeUnclear != null
    val activeSpanWord = remember(activeSpan, paragraphs) {
        val span = activeSpan ?: return@remember null
        val paragraph = paragraphs.firstOrNull { it.id == span.paragraphId } ?: return@remember null
        span.text(paragraph)
    }
    var spellSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(page.editing, find.open, activeSpanWord) {
        spellSuggestions = emptyList()
        val word = activeSpanWord?.takeIf { page.editing && !find.open } ?: return@LaunchedEffect
        spellSuggestions = SpellSuggestions.forWord(context, word)
    }
    val reviewSuggestions = remember(activeSpan, paragraphs, spellSuggestions) {
        val span = activeSpan ?: return@remember emptyList()
        val paragraph = paragraphs.firstOrNull { it.id == span.paragraphId } ?: return@remember emptyList()
        val selected = span.text(paragraph)
        val seen = hashSetOf(spellingKey(selected))
        buildList {
            previousWording(paragraphs, selected)?.takeIf { seen.add(spellingKey(it)) }?.let(::add)
            nearbySpellings(paragraphs, span).filter { seen.add(spellingKey(it)) }.forEach(::add)
            spellSuggestions.filter { seen.add(spellingKey(it)) }.forEach(::add)
        }
    }
    val reviewPrimary = when {
        !page.editing || find.open -> null
        session.failed -> ReviewPrimary.Retry
        activeUnclear != null -> ReviewPrimary.Keep
        page.reviewing -> {
            val others = if (!fieldDirty) unclear.isNotEmpty() else unclear.any { span ->
                span.paragraphId != focusedParagraph.id ||
                    (fieldSelection != null && (span.end <= fieldSelection.min || span.start >= fieldSelection.max))
            }
            if (others) ReviewPrimary.Next else ReviewPrimary.Done
        }
        else -> ReviewPrimary.Done
    }
    // Always ride a single 48 dp companion strip above the keyboard in Edit & Review so Listen and Done stay in thumb reach.
    val reviewStripShown = page.editing && !find.open
    val reviewPosition = if (activeUnclear != null) unclear.indexOf(activeUnclear) + 1 else 0
    val reviewTotal = if (page.reviewing) unclear.size else 0

    // Edit moves the page for the caret alone (spec 5.7): when the caret's line is not between the bar and the
    // keyboard (or ReviewStrip above it), the list scrolls by the least that brings it there, on `move`.
    val window = LocalWindowInfo.current
    val indexNow = rememberUpdatedState(indexOf)
    val caretMargin = with(density) { (if (LocalWindowLayout.current.rail) ShortCaretMargin else CaretMargin).toPx() }
    val stripReservePx = with(density) { (ReviewStripMetrics.height + FocalLayout.bottomGap * 2).toPx() }
    LaunchedEffect(field, find.open, reviewStripShown) {
        val current = field ?: return@LaunchedEffect
        if (find.open) return@LaunchedEffect
        snapshotFlow { Triple(current.text.selection, current.text.text.length, imeTarget.getBottom(density)) }.collectLatest { (selection, _, keyboard) ->
            // The field lays out what changed in the frame that follows.
            withFrameMillis { }
            val index = indexNow.value[current.id] ?: return@collectLatest
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
                withFrameMillis { }
            }
            val line = lineBox(page.layouts[current.id], selection.end)
            val y = windowY(listState, index, line.first) ?: return@collectLatest
            val stripReserve = if (reviewStripShown) stripReservePx else 0f
            val bandEnd = (if (keyboard > 0) window.containerSize.height - keyboard else listState.layoutInfo.viewportSize.height) - stripReserve
            val scroll = caretScroll(y, y + (line.second - line.first), bandTop() + caretMargin, bandEnd - caretMargin)
            if (scroll != 0f) listState.animateScrollBy(scroll, FocalMotion.move())
        }
    }

    val wordConfirmedLabel = stringResource(R.string.review_confirmed)
    val allReviewedLabel = stringResource(R.string.review_all_done)
    fun freshParagraphs(): List<ParagraphUi> =
        session.paragraphs(latestNote.paragraphs).filter { it.text.isNotBlank() }
    fun nextSpanIn(list: List<ParagraphUi>, paragraphId: Long?, offset: Int): ReviewSpan? {
        val spans = list.flatMap { it.unclearSpans() }
        if (spans.isEmpty()) return null
        val order = list.withIndex().associate { (i, p) -> p.id to i }
        val currentP = order[paragraphId] ?: -1
        return spans.firstOrNull { span ->
            val p = order[span.paragraphId] ?: -1
            if (p == currentP) span.start >= offset else p > currentP
        } ?: spans.first()
    }
    fun prevSpanIn(list: List<ParagraphUi>, paragraphId: Long?, offset: Int): ReviewSpan? {
        val spans = list.flatMap { it.unclearSpans() }
        if (spans.isEmpty()) return null
        val order = list.withIndex().associate { (i, p) -> p.id to i }
        val currentP = order[paragraphId] ?: Int.MAX_VALUE
        return spans.lastOrNull { span ->
            val p = order[span.paragraphId] ?: Int.MAX_VALUE
            if (p == currentP) span.end <= offset else p < currentP
        } ?: spans.last()
    }
    fun focusSpan(span: ReviewSpan) {
        focusField(CaretAt(span.paragraphId, span.start, span.end), refresh = true, commit = false)
    }
    fun offerConfirmUndo(saved: EditOutcome.Saved, label: String) {
        val receipt = saved.confirmUndo ?: return
        scope.launch {
            if (bottom.feedback.show(label, FeedbackAction(undoLabel, FocalIcon.Undo)) != FeedbackResult.ActionPerformed) return@launch
            write {
                if (page.editing && !session.save()) {
                    scope.launch { bottom.feedback.show(notSaved) }
                    return@write
                }
                val undone = actions.undoConfirmDoubt(receipt)
                if (undone is EditOutcome.Saved) session.applySaved(undone.paragraphs)
                else report(undone, words.tooLate)
            }
        }
    }
    fun keepCurrent(span: ReviewSpan) {
        write {
            val id = session.resolve(span.paragraphId)
            val outcome = session.confirmDoubt(id, span.start, span.end)
            if (outcome !is EditOutcome.Saved) {
                if (session.failures >= 2) scope.launch { bottom.feedback.show(saveFailed) }
                else report(outcome, notSaved)
                return@write
            }
            val fresh = freshParagraphs()
            val remaining = fresh.flatMap { it.unclearSpans() }
            if (page.reviewing && remaining.isNotEmpty()) {
                // Top-bar Undo handles Keep cleanly while staying in Review; no bottom pill obscuring the next word.
                nextSpanIn(fresh, id, span.end)?.let(::focusSpan)
            } else {
                val allDone = page.reviewing && remaining.isEmpty()
                exitEdit()
                offerConfirmUndo(outcome, if (allDone) allReviewedLabel else wordConfirmedLabel)
            }
        }
    }
    fun advanceReview(delta: Int) {
        write {
            val currentId = page.field?.id?.let(session::resolve)
            val currentStart = page.field?.text?.selection?.min ?: 0
            val currentEnd = page.field?.text?.selection?.max ?: 0
            if (!session.save()) {
                if (session.failures >= 2) scope.launch { bottom.feedback.show(saveFailed) }
                return@write
            }
            val fresh = freshParagraphs()
            val target = if (delta >= 0) nextSpanIn(fresh, currentId, currentEnd) else prevSpanIn(fresh, currentId, currentStart)
            if (target == null) {
                exitEdit()
                scope.launch { bottom.feedback.show(allReviewedLabel) }
            } else {
                focusSpan(target)
            }
        }
    }
    fun markAllReviewed() {
        write {
            if (page.editing && !session.save()) {
                if (session.failures >= 2) scope.launch { bottom.feedback.show(saveFailed) }
                return@write
            }
            val outcome = actions.confirmAllDoubt()
            if (outcome !is EditOutcome.Saved) {
                report(outcome, notSaved)
                return@write
            }
            session.applySaved(outcome.paragraphs)
            if (page.editing && page.reviewing) exitEdit()
            offerConfirmUndo(outcome, allReviewedLabel)
        }
    }
    fun pickSuggestion(wording: String) {
        val current = page.field ?: return
        val span = activeSpan ?: return
        val sel = current.text.selection
        val replaceStart = (if (sel.collapsed) span.start else sel.min).coerceIn(0, current.text.text.length)
        val replaceEnd = (if (sel.collapsed) span.end else sel.max).coerceIn(replaceStart, current.text.text.length)
        current.text.edit {
            replace(replaceStart, replaceEnd, wording)
            selection = TextRange(replaceStart + wording.length)
        }
        write {
            val before = session.paragraph(current.id)
            if (!session.save()) return@write
            val after = session.paragraph(current.id)
            if (page.reviewing) {
                val fresh = freshParagraphs()
                val next = nextSpanIn(fresh, session.resolve(current.id), replaceStart + wording.length)
                if (next == null) {
                    exitEdit()
                    if (before != null && after != null && before.text != after.text) {
                        scope.launch {
                            if (bottom.feedback.show(allReviewedLabel, FeedbackAction(undoLabel, FocalIcon.Undo)) != FeedbackResult.ActionPerformed) return@launch
                            write {
                                if (page.editing && !session.save()) {
                                    scope.launch { bottom.feedback.show(notSaved) }
                                    return@write
                                }
                                val since = mark()
                                val outcome = edits.edit(after.id, after.revision, before.text)
                                if (outcome is EditOutcome.Saved) {
                                    session.applySaved(outcome.paragraphs)
                                    if (page.editing && untouched(since)) reseedField()
                                } else report(outcome, words.tooLate)
                            }
                        }
                    } else {
                        scope.launch { bottom.feedback.show(allReviewedLabel) }
                    }
                } else {
                    focusSpan(next)
                }
            }
        }
    }

    val onTap: (ParagraphUi, Int, IntRange?) -> Unit = { paragraph, offset, word ->
        when {
            find.open -> Unit
            page.renaming -> {
                // Symmetric with tapping the title from Edit: commit the title and jump directly into body editing.
                renameController.commit()
                if (canEdit) {
                    val unclearHit = word?.let { w ->
                        unclear.firstOrNull { it.paragraphId == paragraph.id && w.first in it.start until it.end }
                    }
                    val target = unclearHit?.let { CaretAt(it.paragraphId, it.start, it.end) }
                        ?: wordOrCaret(paragraph.id, offset, word)
                    enterEdit(target, review = false)
                } else {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
            }
            page.editing -> focusField(wordOrCaret(paragraph.id, offset, word), refresh = false, commit = true)
            canEdit -> {
                val unclearHit = word?.let { w ->
                    unclear.firstOrNull { it.paragraphId == paragraph.id && w.first in it.start until it.end }
                }
                val target = unclearHit?.let { CaretAt(it.paragraphId, it.start, it.end) }
                    ?: wordOrCaret(paragraph.id, offset, word)
                enterEdit(target, review = false)
            }
            !canEdit && canPlay -> playFromWord(paragraph, offset)
        }
    }
    val onSweep: ((ParagraphUi, IntRange) -> Unit)? = if (!canEdit || find.open || page.renaming) null else { paragraph, range ->
        if (page.editing) focusField(CaretAt(paragraph.id, range.first, range.last + 1), refresh = false, commit = true)
        else enterEdit(CaretAt(paragraph.id, range.first, range.last + 1), review = false)
    }
    // Edit's ▶ (spec 5.7): plays just the audio of the selected words, or of the words around the caret, and stops. It
    // is how a doubtful word is checked against what was said without leaving the place being corrected. The strip and
    // the follow scroll stay away: the page is not being played, and the caret must not move.
    val listening = page.listening && audio.noteId == note.noteId && audio.playing
    val listen: () -> Unit = {
        val current = page.field
        val paragraph = current?.let { field -> paragraphs.firstOrNull { it.id == field.id } }
        when {
            listening -> playback.pause()
            current != null && paragraph != null && canPlay -> {
                val selection = current.text.selection
                val lead = if (selection.collapsed) FocalTiming.LISTEN_CARET_MS else FocalTiming.LISTEN_SELECTION_MS
                val clip = paragraph.listenRange(selection.min, selection.max, lead)
                val end = (clip.last + 1).coerceAtMost(position.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
                page.playerDismissed = true
                page.seekPin = null
                page.listening = true
                page.rangeEndMs = end
                playback.playRange(clip.first, end)
            }
        }
    }

    // Back layers, lowest first: the player, Edit (= Done), Find, an inline rename (spec 2.8.5). None of them while
    // settings covers this page beside the library (two panes): Back is the strip's then, which closes it.
    val behindSettings by remember(shell) { derivedStateOf { shell.twoPane && shell.settings.progress.value > 0f } }
    PredictiveBackHandler(enabled = page.stripShown && stripWanted && !behindSettings) { events ->
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
    BackHandler(enabled = page.editing && !find.open && !behindSettings) { done() }
    BackHandler(enabled = find.open && !behindSettings) { closeFind() }
    BackHandler(enabled = page.renaming && !behindSettings) { page.renaming = false }

    // The dock (spec 4.3) and the bottom column. While this note's own recording runs under a folded scene (it was
    // continued from this page), the dock is the recording's: Pause | Finish, as on the library.
    val recordingHere = (note.phase == NotePhase.RECORDING || note.phase == NotePhase.PAUSED) && !captureFinishing
    val dockState: DockState = if (recordingHere) DockState.Live(paused = note.phase == NotePhase.PAUSED) else noteDock(
        audioPresent = note.audioPresent, canPlay = note.canPlay, playing = playingHere && audio.playing,
        phase = note.phase, progress = note.progress, copyEnabled = !note.noSpeech && paragraphs.isNotEmpty(),
        append = note.canAppend, noteId = note.noteId,
    )
    val currentParagraphs by rememberUpdatedState(paragraphs)
    val currentItems by rememberUpdatedState(items)
    // Action holders are rebuilt with each composition: they close over the note's current state.
    val dockActions = run {
        DockActions(
            play = {
                page.playerDismissed = false
                page.followSuspended = false
                page.seekPin = null
                // The page's own playback from here on: a gap's range that was paused no longer ends it.
                page.rangeEndMs = null
                if (playback.session.noteId == note.noteId) playback.toggle() else {
                    // With no session: from the start of the topmost paragraph on screen (spec 5.5).
                    val top = visibleParagraphs(listState, currentItems).firstOrNull()
                    currentParagraphs.firstOrNull { it.id == top }?.let { playback.playWord(it, 0) } ?: playback.playFrom(0)
                }
            },
            // A pause and nothing else: a toggle would restart a gap's range as the whole note's playback.
            pausePlayback = {
                page.seekPin = null
                playback.pause()
            },
            copy = actions.copy,
            append = actions.recordMore,
            pause = actions.pause, resume = actions.resume, finish = actions.finish,
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
                        PlayerStrip(
                            position,
                            onSeek = { rawMs ->
                                page.rangeEndMs = null
                                val resolved = SeekSnap.resolve(paragraphs, note.gaps, rawMs, position.durationMs)
                                val snapped = resolved.targetMs
                                if (snapped != null) {
                                    val loc = resolved.location
                                    val wordStart = loc?.wordIndex?.let { idx ->
                                        paragraphs.getOrNull(loc.paragraphIndex)?.words?.getOrNull(idx)?.startMs
                                    }
                                    page.seekPin = if (loc != null && wordStart != null && snapped < wordStart) {
                                        SeekPin(loc, snapped, wordStart)
                                    } else null
                                    playback.playFrom(snapped)
                                    snapped
                                } else {
                                    page.seekPin = null
                                    val parked = rawMs.coerceIn(0L, position.durationMs.coerceAtLeast(0L))
                                    playback.parkAt(parked)
                                    parked
                                }
                            },
                            speed = audio.speed,
                            onSelectSpeed = playback::setSpeed,
                        )
                    }
                }
            }
        } else null,
        rows = when {
            find.open -> { { FindRows(find, findActions) } }
            page.renaming -> {
                {
                    RenameStrip(
                        onDone = {
                            renameController.commit()
                            keyboard?.hide()
                            focusManager.clearFocus()
                        },
                    )
                }
            }
            reviewStripShown -> {
                {
                    ReviewStrip(
                        canListen = canPlay && page.field != null,
                        listening = listening,
                        onListen = listen,
                        suggestions = reviewSuggestions,
                        onPickSuggestion = ::pickSuggestion,
                        primary = reviewPrimary,
                        onPrimary = {
                            when (reviewPrimary) {
                                ReviewPrimary.Keep -> activeUnclear?.let(::keepCurrent)
                                ReviewPrimary.Next -> advanceReview(+1)
                                ReviewPrimary.Done, ReviewPrimary.Retry -> done()
                                null -> Unit
                            }
                        },
                    )
                }
            }
            else -> null
        },
        dockHidden = page.editing || page.renaming || find.open,
    )

    // Strings for the head, notices and the paragraphs' actions.
    // In Edit the head stays as Edit found it: a title derived from the first sentence would change with every
    // autosave of that sentence, wrap onto another line and push the text being typed down the page.
    val liveHead = headUi(note, paragraphs, dates, today, captureElsewhere, actions, interactive = !page.editing && !find.open)
    val heldHead = remember(page.editing) { HeldHead() }
    val head = if (page.editing) heldHead.head ?: liveHead.also { heldHead.head = it } else liveHead
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
        val edge = EdgeSnap(topLines, bandTop, fadePx, { bandTop() + fadePx }, bandBottom)
        // The page opens at the hit (it fades in over its first frames): placed there, not scrolled there in view.
        scroller.bring(index, { lineBox(page.layouts[hit.paragraphId], hit.start).first }, target, lineHeight(hit.paragraphId, hit.start), edge, instant = true)
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
    // Scrolled past the head (also a note reopened where it was read, whose head was never composed), or the title
    // has gone under the bar.
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || page.titleBottom < bandTop() } }
    // The bars cross-fade (`fadeFast`); each is composed only while it shows, decided twice per change, not per frame.
    val topBarShown by remember { derivedStateOf { editT.value < 1f } }
    val editBarShown by remember { derivedStateOf { editT.value > 0f } }
    // A note with no text has nothing to edit or find (spec 5.9: no speech).
    val editable = note.editable && paragraphs.isNotEmpty()
    val menuItems = NoteMenuItems(
        edit = note.editable,
        markAllReviewed = note.editable && unclear.isNotEmpty(),
        find = editable || (note.phase == NotePhase.READY && !note.audioPresent && paragraphs.isNotEmpty()),
        share = note.phase == NotePhase.READY || note.phase == NotePhase.PARTIAL_FAILURE,
        removeAudio = note.canRemoveAudio,
        delete = note.phase !in LibraryModels.BUSY && !recordingHere,
        append = note.canAppend,
    )
    val menuActions = run {
        NoteMenuActions(
            // Edit without a pointer: the caret goes before the playing word, else to the start of the topmost
            // paragraph on screen.
            edit = {
                val top = visibleParagraphs(listState, currentItems).firstOrNull()
                enterEdit(if (playingHere) null else top?.let { CaretAt(it, 0) })
            },
            markAllReviewed = { markAllReviewed() },
            find = { openFind() }, rename = { page.renaming = true }, share = actions.share, append = actions.recordMore,
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
        EditBarActions(
            undo = undo, redo = redo, done = { done() }, restore = restore, find = { openFind() }, listen = listen,
            reviewPrevious = { advanceReview(-1) }, reviewNext = { advanceReview(+1) }, markAllReviewed = { markAllReviewed() },
        )
    }

    // The page's shortcuts (spec 8.5): each where it applies; false leaves the key to whoever else wants it.
    val reading = (mode == NoteMode.Reading || mode == NoteMode.Playing) && !page.renaming
    val onKey: (PageKey) -> Boolean = { key ->
        when (key) {
            PageKey.Undo -> page.editing.also { if (it) undo() }
            PageKey.Redo -> page.editing.also { if (it) redo() }
            PageKey.Find -> (!find.open).also { if (it) openFind() }
            // While reading, Space is the page's even with nothing to play (text only, or a capture holds the
            // audio): left to a control that the first key after a touch focused by itself, it would press it.
            PageKey.PlayPause -> reading.also { if (it && canPlay) { if (playingHere && audio.playing) playback.pause() else dockActions.play() } }
            // Through the dock's Copy, so it confirms as a tap does (spec 5.5).
            PageKey.Copy -> (reading && (dockState as? DockState.Note)?.pill is NotePill.Copy).also {
                if (it) bottom.dock.copy()
            }
        }
    }
    // Space and the Ctrl shortcuts heard before a focused control inside the page; a held Space acts once.
    val shortcuts = rememberShortcutKeys(onKey)
    // Heard by the activity when nothing on the page has focus; only the page on top answers, and only while it shows:
    // the activity hears keys past the inert modifiers, and the scene covers the page in both layouts, settings in two
    // panes (the whole window slides; in one pane the page covers settings).
    PageKeysEffect { key ->
        val covered = shell.live.visible || shell.twoPane && shell.settings.isOpen
        shell.current == destination && !covered && onKey(key)
    }

    Box(
        Modifier
            .fillMaxSize()
            // Beside the landscape rail and inside the side insets (spec 5.14).
            .windowInsetsPadding(pageInsets(PageArea.Note))
            // With a field or control focused inside the page (Edit's field, Find's query) the shortcuts come here
            // before it. Space too, while reading (spec 8.5: Space plays or pauses; Enter activates a focused
            // control): the first key after a touch leaves touch mode and puts focus on the top bar's back button by
            // itself, so a Space left to the focused control would do nothing and the next one would go Back. A held
            // Space acts once; its repeats go with it, so no focused control sees half of the press.
            .shortcutKeys(shortcuts)
            .then(
                // While renaming the title, tapping blank paper/meta below the title commits and hides the keyboard;
                // tapping a paragraph is handled directly by ParagraphText -> onTap to commit and switch straight to Edit.
                if (!page.renaming) Modifier else Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val up = event.changes.firstOrNull { !it.pressed && it.previousPressed } ?: continue
                            if (!up.isConsumed && up.position.y > page.titleBottom) {
                                renameController.commit()
                                keyboard?.hide()
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
                reviewCount = if (find.open || page.renaming) 0 else unclear.size,
                onReview = {
                    unclear.firstOrNull()?.let { span ->
                        enterEdit(CaretAt(span.paragraphId, span.start, span.end), review = true)
                    }
                },
                menuItems = menuItems, menuOpen = page.menu, onMenu = { page.menu = it }, menu = menuActions,
                onBack = actions.back,
                chrome = { chrome() * (1 - editT.value) },
                modifier = Modifier.zIndex(BARS_Z).inertBar(page.editing || find.open),
            )
        }
        if (editBarShown) {
            EditBar(
                canUndo = session.canUndo, canRedo = session.canRedo,
                canRestore = focusedParagraph?.let { it.edited && it.canRestore } == true,
                failed = session.failed, menuOpen = page.editMenu, onMenu = { page.editMenu = it }, actions = editBarActions,
                canListen = false, listening = listening,
                reviewPosition = reviewPosition,
                reviewTotal = reviewTotal,
                canMarkAllReviewed = inReview && unclear.isNotEmpty(),
                showTopDone = inReview && reviewPrimary != ReviewPrimary.Done && reviewPrimary != ReviewPrimary.Retry,
                modifier = Modifier.zIndex(BARS_Z).graphicsLayer { alpha = editT.value * chrome() }.inertBar(!page.editing || find.open),
            )
        }
        val topPadding = with(density) { status.getTop(this).toDp() } + topBar + FocalGrid.x8
        val short = rememberUpdatedState(LocalWindowLayout.current.rail)
        val settleLiftPx = with(density) { FocalGrid.x12.toPx() }
        // Nothing of a note is drawn before the note is there: an empty state would show a date in 1970 and a
        // writing head for a frame or two. The page's opening waits for this ([PageTransitions.ready]).
        if (note.loaded) SideEffect { shell.pages.ready(destination) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides StillPage) {
            if (note.loaded) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = listFade.value * reveal.body()
                        }
                        .drawWithContent {
                            val shift = reveal.bodyShift()
                            val dy = if (shift > 0f) shift else if (reveal.opening()) (1f - reveal.body()) * settleLiftPx else 0f
                            if (dy > 0.5f) translate(0f, dy) { this@drawWithContent.drawContent() } else drawContent()
                        }
                        // Isolate LazyColumn in its own RenderNode so bodyShift only re-records translate + drawRenderNode
                        // without shifting title layout coordinates read via localBoundingBoxOf.
                        .graphicsLayer {},
                ) {
                    // The text column is at most 600 dp and centred (spec 2.5); the list itself spans the page, so it
                    // scrolls from its margins too.
                    val side = FocalLayout.columnInset(maxWidth) - FocalLayout.gutter
                    val padding = remember(topPadding, side, bottom, density) { bottom.contentPadding(topPadding, density, side) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
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
                                    canRename = mode == NoteMode.Reading || mode == NoteMode.Playing || mode == NoteMode.Editing,
                                    onRename = {
                                        if (page.editing) {
                                            write {
                                                while (true) {
                                                    val before = session.field()
                                                    if (!session.save()) {
                                                        if (session.failures >= 2) scope.launch { bottom.feedback.show(saveFailed) }
                                                        return@write
                                                    }
                                                    if (life.gone) return@write
                                                    if (session.field() == before) break
                                                }
                                                exitEdit(hideKeyboard = false)
                                                page.renaming = true
                                            }
                                        } else {
                                            page.renaming = true
                                        }
                                    },
                                    onCommitRename = { text ->
                                        page.renaming = false
                                        if (text != note.title || note.titleIsCustom) actions.rename(text)
                                    },
                                    onTitleBounds = { top, bottom ->
                                        page.titleTop = top
                                        page.titleBottom = bottom
                                    },
                                    entrance = landing,
                                    modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null),
                                    onHeadLines = { page.headLines = it },
                                    renameController = renameController,
                                )
                                is NoteItem.Text -> ParagraphItem(
                                    item, page, placement,
                                    arrival = page.arrivals.of(item.paragraph.id, SystemClock.uptimeMillis()),
                                    entrance = entranceDelay(landing, item.index, paragraphs.size),
                                    onTap = onTap, onSweep = onSweep,
                                    actions = paragraphActions(
                                        item.paragraph, labels, note, canPlay, page.editing, finding = find.open, play = playFromWord,
                                        // In Edit, TalkBack's Edit puts the caret in this paragraph, as a tap does.
                                        edit = { at -> if (page.editing) focusField(at, refresh = false, commit = true) else enterEdit(at) },
                                        copy = actions.copyParagraph, restore = restoreOutside,
                                    ),
                                    onEnter = enter,
                                    onJoin = join,
                                )
                                is NoteItem.Time -> ParagraphTime(
                                    item.paragraph.startMs,
                                    // Not from Edit or Find: playback would scroll the text under the caret.
                                    onPlay = if (canPlay && !page.editing && !find.open) { { playFromWord(item.paragraph, 0) } } else null,
                                    modifier = Modifier.animateItem(fadeInSpec = FocalMotion.fade(), placementSpec = placement, fadeOutSpec = FocalMotion.fadeFast()),
                                    entrance = entranceDelay(landing, item.index, paragraphs.size),
                                )
                                is NoteItem.Gap -> GapRow(
                                    // Not from Edit or Find: playback would scroll the text under the caret.
                                    item.gap, playable = note.audioPresent && note.canPlay && !page.editing && !find.open,
                                    onPlay = {
                                        page.rangeEndMs = item.gap.endMs
                                        page.seekPin = null
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
        PaperTopFade(topBar, Modifier.fillMaxSize().zIndex(FADES_Z), alpha = { (1 - findT.value) * reveal.chrome() * reveal.paperFade() })
        // In a short window Find's fades are the 16 dp soft edges: 48 dp ones would cover the little text a landscape
        // keyboard leaves between the status bar and the rows (spec 5.8, 5.14).
        val findFade = if (short.value) FocalLayout.topFade else FindFade
        PaperTopFade(0.dp, Modifier.fillMaxSize().zIndex(FADES_Z), fade = findFade, alpha = { findT.value * reveal.chrome() * reveal.paperFade() })
        BottomFade(
            bottom,
            Modifier.fillMaxSize().zIndex(FADES_Z).graphicsLayer {
                alpha = (if (reviewStripShown || page.renaming) 1f else 1 - editT.value) * reveal.chrome() * reveal.paperFade()
            },
            keyboard = find.open || reviewStripShown || page.renaming,
            keyboardFade = if (short.value) FocalLayout.topFade else FocalLayout.keyboardFade,
        )
        JumpPill(
            visible = page.playingOutside != null && stripWanted,
            up = page.playingOutside == true,
            bottom = bottom,
            onClick = {
                page.followSuspended = false
                page.playingOutside = null
            },
            modifier = Modifier.fillMaxSize().zIndex(FADES_Z).graphicsLayer { alpha = reveal.chrome() },
        )
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
    }
}

/** What a page last lent its Edit session ([EditSession.field]): it takes it back as it goes, if it is still its own. */
private class SessionHold { var field: (() -> FieldText?)? = null }
