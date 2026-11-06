package app.focal.ui.note

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import app.focal.R
import app.focal.core.SpeechTuning
import app.focal.core.TextWords
import app.focal.core.Title
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.library.LibraryDates
import app.focal.ui.library.datelines
import app.focal.ui.library.durationText
import app.focal.ui.state.EndReason
import app.focal.ui.state.GapUi
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteUiState
import app.focal.ui.state.ParagraphUi
import kotlinx.coroutines.Job
import java.time.LocalDate
import kotlin.math.abs

/** The reading band's thirds (spec 5.6 follow). */
internal const val THIRDS = 3f

/** A scroll farther than this many screens crossfades instead (spec 4.11, 5.8). */
private const val FAR_SCREENS = 3

/** At most this many paragraphs on screen stagger (T7's develop, rack focus). */
private const val STAGGER_MAX = 12

/** T7: paragraphs fade in from this delay (`settleParagraphDelay`, spec 2.8.2), the bottom one first. */
private const val SETTLE_PARAGRAPH_MS = 180L

/** T7's develop applies to paragraphs composed this soon after the page arrived. */
private const val ENTRANCE_WINDOW_MS = 600L

/** The note's rows: the head, paragraphs with the gap rows after the paragraph they follow, the writing head. */
internal fun noteItems(paragraphs: List<ParagraphUi>, gaps: List<GapUi>, writing: Boolean, noSpeech: Boolean): List<NoteItem> = buildList {
    add(NoteItem.Head)
    val after = gaps.groupBy { it.afterParagraphId }
    after[null]?.forEach { add(NoteItem.Gap(it)) }
    paragraphs.forEachIndexed { index, paragraph ->
        add(NoteItem.Text(paragraph, index))
        after[paragraph.id]?.forEach { add(NoteItem.Gap(it)) }
    }
    // A gap after a paragraph that is no longer shown keeps its place at the end.
    val shown = paragraphs.mapTo(HashSet()) { it.id }
    gaps.filter { it.afterParagraphId != null && it.afterParagraphId !in shown }.forEach { add(NoteItem.Gap(it)) }
    if (noSpeech && paragraphs.isEmpty()) add(NoteItem.NoSpeech)
    if (writing) add(NoteItem.Writing)
}

internal fun contentTypeOf(item: NoteItem): String = when (item) {
    NoteItem.Head -> "head"
    is NoteItem.Text -> "p"
    is NoteItem.Gap -> "gap"
    NoteItem.Writing -> "writing"
    NoteItem.NoSpeech -> "no-speech"
}

/** The paragraphs on screen, top to bottom. */
internal fun visibleParagraphs(list: LazyListState, items: List<NoteItem>): List<Long> =
    list.layoutInfo.visibleItemsInfo.mapNotNull { info -> (items.getOrNull(info.index) as? NoteItem.Text)?.paragraph?.id }

/** The top and bottom of the line holding [offset] (the first line without one), from the paragraph's top. */
internal fun lineBox(layout: TextLayoutResult?, offset: Int?): Pair<Float, Float> {
    if (layout == null || layout.lineCount == 0) return 0f to 0f
    val line = offset?.let { layout.getLineForOffset(it.coerceIn(0, layout.layoutInput.text.length)) } ?: 0
    return layout.getLineTop(line) to layout.getLineBottom(line)
}

/** The window y of [lineTop] inside item [index], while it is laid out (the list fills the window from its top). */
internal fun windowY(list: LazyListState, index: Int, lineTop: Float): Float? {
    val info = list.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    return item.offset - info.viewportStartOffset + lineTop
}

/** The follow scroll in flight, so a frame never starts a second one. */
internal class FollowJob {
    var job: Job? = null
    val idle: Boolean get() = job?.isActive != true
}

/** Remembers whether the note was still being transcribed, to announce "Note ready" once (spec 5.4). */
internal class PhaseMemory {
    var working = false
}

/**
 * Scrolls a line into a place on screen: on `move` when it is near, by crossfading the list (`fadeFast` out, `fade`
 * in) when it is more than three screens away (spec 4.11, 5.8).
 */
internal class NoteScroller(private val list: LazyListState, private val fade: Animatable<Float, AnimationVector1D>) {
    /** Brings the line at [lineTop] (from item [index]'s top) to window y [target]. */
    suspend fun bring(index: Int, lineTop: () -> Float, target: Float) {
        windowY(list, index, lineTop())?.let { y ->
            list.animateScrollBy(y - target, FocalMotion.move())
            return
        }
        val info = list.layoutInfo
        val visible = info.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val average = if (visible.isEmpty()) 0f else visible.sumOf { it.size }.toFloat() / visible.size
        val far = abs(index - first) * average > FAR_SCREENS * info.viewportSize.height
        if (far) {
            fade.animateTo(0f, FocalMotion.fadeFast())
            list.scrollToItem(index)
            windowY(list, index, lineTop())?.let { list.scrollBy(it - target) }
            fade.animateTo(1f, FocalMotion.fade())
        } else {
            list.animateScrollToItem(index)
            windowY(list, index, lineTop())?.let { list.animateScrollBy(it - target, FocalMotion.move()) }
        }
    }
}

/**
 * Edit keeps the caret's line inside the band between the edit bar and the keyboard, [margin] from each (spec 5.7):
 * the list is padded by the keyboard, and the bar lies over its top.
 */
internal class CaretSpec(private val top: () -> Float, private val margin: () -> Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = top()
        val end = containerSize - margin()
        val last = offset + size
        return when {
            offset >= start && last <= end -> 0f
            // Taller than the band and covering it (a focused paragraph, the list itself): nothing to bring.
            offset < start && last > end -> 0f
            abs(offset - start) < abs(last - end) -> offset - start
            else -> last - end
        }
    }
}

/** The page's inert layers (a bar under Find, the bar Edit replaced): never read, focused or touched. */
internal fun Modifier.inert(on: Boolean): Modifier = if (!on) this else this
    .clearAndSetSemantics { }
    .focusProperties { canFocus = false }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

/** T7: a paragraph of the first screen fades in from +180 ms, the bottom one first, 24 ms apart. */
internal fun entranceDelay(landing: Boolean, arrivedAt: Long, index: Int, count: Int): Long? {
    if (!landing || index >= STAGGER_MAX || SystemClock.uptimeMillis() - arrivedAt > ENTRANCE_WINDOW_MS) return null
    val shown = minOf(count, STAGGER_MAX)
    return SETTLE_PARAGRAPH_MS + (shown - 1 - index).coerceAtLeast(0) * FocalTiming.RACK_STAGGER_MS
}

/** The paragraph actions' labels (spec 8.2). */
internal data class ParagraphLabels(val play: String, val edit: String, val copy: String, val restore: String, val playUnclear: String)

/**
 * TalkBack's paragraph actions (spec 8.2): "Play from here", "Edit", "Copy paragraph", "Restore original" when edited,
 * and "Play unclear word" when a word is doubtful. No word-level nodes, so a long note never becomes thousands of
 * targets.
 */
internal fun paragraphActions(
    paragraph: ParagraphUi,
    labels: ParagraphLabels,
    note: NoteUiState,
    canPlay: Boolean,
    editing: Boolean,
    play: (ParagraphUi, Int) -> Unit,
    enterEdit: (CaretAt) -> Unit,
    copy: (ParagraphUi) -> Unit,
    restore: (ParagraphUi) -> Unit,
): List<CustomAccessibilityAction> = buildList {
    if (canPlay) add(CustomAccessibilityAction(labels.play) { play(paragraph, 0); true })
    if (note.editable && !editing) add(CustomAccessibilityAction(labels.edit) { enterEdit(CaretAt(paragraph.id, 0)); true })
    add(CustomAccessibilityAction(labels.copy) { copy(paragraph); true })
    if (paragraph.edited && paragraph.canRestore && !editing) add(CustomAccessibilityAction(labels.restore) { restore(paragraph); true })
    val doubtful = paragraph.words.firstOrNull { it.doubtful }
    if (SpeechTuning.DOUBT_ENABLED && doubtful != null && canPlay) {
        add(CustomAccessibilityAction(labels.playUnclear) { play(paragraph, doubtful.start); true })
    }
}

/** "2 unclear words: Saturday, trailhead" (spec 7.8), or null. */
@Composable
private fun unclearDescription(paragraph: ParagraphUi): String? {
    if (!SpeechTuning.DOUBT_ENABLED) return null
    val words = paragraph.words.filter { it.doubtful && it.end <= paragraph.text.length }.map { paragraph.text.substring(it.start, it.end) }
    if (words.isEmpty()) return null
    return pluralStringResource(R.plurals.a11y_unclear, words.size, words.size, words.joinToString(UNCLEAR_SEPARATOR))
}

private const val UNCLEAR_SEPARATOR = ", "

/**
 * One paragraph row: its text, or in Edit the field that holds the caret (the same box: the swap moves nothing), the
 * edit sheet behind it (`fade` in, `fadeFast` out) and the provenance line under it.
 */
@Composable
internal fun LazyItemScope.ParagraphItem(
    item: NoteItem.Text,
    page: NotePageState,
    arrival: Arrivals.Arrival?,
    entrance: Long?,
    onTap: (ParagraphUi, Int) -> Unit,
    tapBlocked: () -> Boolean,
    actions: List<CustomAccessibilityAction>,
    onEnter: (Int) -> Unit,
    onJoin: () -> Unit,
    onRestore: () -> Unit,
) {
    val paragraph = item.paragraph
    val find = page.find
    val field = page.field?.takeIf { page.editing && !find.open && it.id == paragraph.id }
    val reduced = LocalReducedMotion.current
    val sheet = remember { Animatable(if (field != null) 1f else 0f) }
    val focused = field != null
    LaunchedEffect(focused, reduced) {
        val target = if (focused) 1f else 0f
        when {
            reduced -> sheet.snapTo(target)
            focused -> sheet.animateTo(1f, FocalMotion.fade())
            else -> sheet.animateTo(0f, FocalMotion.fadeFast())
        }
    }
    val unclear = unclearDescription(paragraph)
    Column(
        Modifier
            .animateItem(fadeInSpec = null, placementSpec = FocalMotion.move(), fadeOutSpec = null)
            .padding(horizontal = FocalLayout.gutter),
    ) {
        if (field != null) {
            ParagraphField(field.text, paragraph.id, field.requester, page.layouts, onEnter, onJoin, sheet = { sheet.value })
        } else {
            val text: @Composable () -> Unit = {
                ParagraphText(
                    paragraph, page.focus, find, page.layouts, arrival, entrance,
                    onTap = { offset -> onTap(paragraph, offset) }, tapBlocked = tapBlocked,
                    actions = actions, unclear = unclear, sheet = { sheet.value },
                )
            }
            // Edit and Find place the caret and show matches; the system selection is for reading.
            if (page.editing || find.open) DisableSelection(text) else text()
        }
        ProvenanceLine(visible = focused && paragraph.edited && paragraph.canRestore, onRestore = onRestore)
    }
}

/** The head's content from the note's state (spec 5.5, 5.9, 7.5). */
@Composable
internal fun headUi(
    note: NoteUiState,
    paragraphs: List<ParagraphUi>,
    dates: LibraryDates,
    today: LocalDate,
    captureElsewhere: Boolean,
    actions: NoteActions,
): HeadUi {
    val fallback = stringResource(R.string.title_fallback, dates.time(note.created))
    val leading = paragraphs.take(TITLE_PARAGRAPHS).map { it.text }
    val derived = remember(leading) { Title.of(leading.flatMap { TextWords.split(it) }) }.ifBlank { fallback }
    val whenText = dates.noteWhen(note.created, today, datelines())
    val length = stringResource(R.string.meta, whenText, durationText(note.durationMs))
    val meta = if (note.audioPresent) length else stringResource(R.string.meta, length, stringResource(R.string.meta_text_only))
    val stoppedAt = NoteTimes.clock(note.durationMs)
    val retry = stringResource(R.string.action_retry)
    val tryAgain = stringResource(R.string.action_try_again)
    val notices = buildList {
        when (note.endReason) {
            EndReason.PROCESS_ENDED -> add(Notice(stringResource(R.string.ended_unexpectedly, stoppedAt), quiet = true))
            EndReason.PERMISSION -> add(Notice(stringResource(R.string.ended_permission, stoppedAt)))
            null -> Unit
        }
        if (note.phase == NotePhase.PARTIAL_FAILURE) {
            add(Notice(stringResource(R.string.partial_failure), action = retry.takeIf { note.canRetry }, onAction = actions.retry))
        }
        if (note.phase == NotePhase.WAITING) when {
            captureElsewhere -> add(Notice(stringResource(R.string.waiting_after_recording)))
            note.waitingReason != null -> add(
                Notice(stringResource(R.string.model_failed), action = tryAgain.takeIf { note.canRetry }, onAction = actions.retry),
            )
        }
    }
    return HeadUi(note.title.ifBlank { fallback }, note.title.isBlank(), derived, meta, notices)
}

/** A derived title comes from the first sentence; three paragraphs are always enough (as the store reads it). */
private const val TITLE_PARAGRAPHS = 3
