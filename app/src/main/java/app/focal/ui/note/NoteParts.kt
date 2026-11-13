package app.focal.ui.note

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import app.focal.R
import app.focal.core.SpeechTuning
import app.focal.core.TextWords
import app.focal.core.Title
import app.focal.ui.components.inert
import app.focal.ui.components.untouchable
import app.focal.ui.components.waitingText
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTiming
import app.focal.ui.library.LibraryDates
import app.focal.ui.library.datelines
import app.focal.ui.library.durationText
import app.focal.ui.state.EndReason
import app.focal.ui.state.GapUi
import app.focal.ui.state.NotePhase
import app.focal.ui.state.NoteUiState
import app.focal.ui.state.ParagraphUi
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import java.time.LocalDate
import kotlin.math.abs

/** The reading band's thirds (spec 5.6 follow). */
internal const val THIRDS = 3f

/** A scroll farther than this many screens crossfades instead (spec 4.11, 5.8). */
private const val FAR_SCREENS = 3

/**
 * The note's rows: the head, paragraphs with the gap rows after the paragraph they follow, the writing head. With
 * [times], each paragraph after the first is headed by the time it starts at (the first starts where the note does,
 * which the head already says; a note of one paragraph carries none).
 */
internal fun noteItems(
    paragraphs: List<ParagraphUi>,
    gaps: List<GapUi>,
    writing: Boolean,
    noSpeech: Boolean,
    times: Boolean = false,
): List<NoteItem> = buildList {
    add(NoteItem.Head)
    val after = gaps.groupBy { it.afterParagraphId }
    after[null]?.forEach { add(NoteItem.Gap(it)) }
    paragraphs.forEachIndexed { index, paragraph ->
        if (times && index > 0) add(NoteItem.Time(paragraph))
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
    is NoteItem.Time -> "time"
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

/**
 * The text lines laid out on screen, as window spans: every visible paragraph's lines from its layout, the title's
 * lines from its window bounds ([titleTop]..[titleBottom], [titleLine] each) and the head's lines under it ([head]:
 * the meta's, the notices). What [TopEdge] keeps out of the fade.
 */
internal fun windowLines(
    list: LazyListState,
    items: List<NoteItem>,
    layouts: ParagraphLayouts,
    titleTop: Float,
    titleBottom: Float,
    titleLine: Float,
    head: List<Span> = emptyList(),
): List<Span> {
    val info = list.layoutInfo
    val out = ArrayList<Span>()
    for (item in info.visibleItemsInfo) {
        val top = (item.offset - info.viewportStartOffset).toFloat()
        when (val row = items.getOrNull(item.index)) {
            is NoteItem.Text -> layouts[row.paragraph.id]?.let { layout ->
                for (line in 0 until layout.lineCount) out += Span(top + layout.getLineTop(line), top + layout.getLineBottom(line))
            }
            NoteItem.Head -> if (titleLine > 0f && titleBottom > titleTop && titleBottom.isFinite()) {
                val count = ((titleBottom - titleTop) / titleLine).roundToInt().coerceAtLeast(1)
                val line = (titleBottom - titleTop) / count
                for (k in 0 until count) out += Span(titleTop + k * line, titleTop + (k + 1) * line)
                out += head
            }
            else -> Unit
        }
    }
    return out
}

/**
 * Where a bring may leave the line it brought, and how it lands the lines at the top on the band's edge ([TopEdge]):
 * [lines] on screen now, the band's top and fade, and the span the brought line must stay in.
 */
internal class EdgeSnap(
    val lines: () -> List<Span>,
    val bandTop: () -> Float,
    val fade: Float,
    val keepTop: () -> Float,
    val keepBottom: () -> Float,
) {
    fun adjust(scroll: Float, keep: Span): Float = TopEdge.snap(scroll, lines(), bandTop(), fade, keep, keepTop(), keepBottom())
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
 * Scrolls a line into a place on screen: on one `move` when it is near, by crossfading the list (`fadeFast` out,
 * `fade` in) when it is more than three screens away (spec 4.11, 5.8).
 */
internal class NoteScroller(private val list: LazyListState, private val fade: Animatable<Float, AnimationVector1D>) {
    /**
     * Brings the line at [lineTop] (from item [index]'s top, [lineHeight] tall) to window y [target]; with [edge], to
     * where the lines at the top land on the band's edge, the nearest place that keeps the line where [edge] allows.
     */
    suspend fun bring(index: Int, lineTop: () -> Float, target: Float, lineHeight: () -> Float = { 0f }, edge: EdgeSnap? = null) {
        windowY(list, index, lineTop())?.let { y ->
            val scroll = y - target
            list.animateScrollBy(edge?.adjust(scroll, Span(y, y + lineHeight())) ?: scroll, FocalMotion.move())
            return
        }
        val info = list.layoutInfo
        val visible = info.visibleItemsInfo
        val first = visible.firstOrNull() ?: return
        val pitch = visible.sumOf { it.size }.toFloat() / visible.size + info.mainAxisItemSpacing
        val far = abs(index - first.index) * pitch > FAR_SCREENS * info.viewportSize.height
        if (far) {
            fade.animateTo(0f, FocalMotion.fadeFast())
            list.scrollToItem(index)
            windowY(list, index, lineTop())?.let { list.scrollBy(it - target) }
            settle(index, lineTop, lineHeight, edge, animate = false)
            fade.animateTo(1f, FocalMotion.fade())
            return
        }
        // Not laid out yet: one `move` toward where the line should be (rows of the visible rows' average height),
        // corrected to its exact place once it is laid out. Each frame covers the share of the distance still to go
        // that the spring covers of its own, so the correction changes the speed, never the position or direction.
        val estimate = first.offset - info.viewportStartOffset + (index - first.index) * pitch + lineTop()
        list.scroll {
            var scrolled = 0f
            var last = 0f
            animate(0f, 1f, animationSpec = FocalMotion.move()) { fraction, _ ->
                val remaining = windowY(list, index, lineTop())?.let { it - target } ?: (estimate - target - scrolled)
                val share = if (last >= 1f - LANDED) 1f else ((fraction - last) / (1f - last)).coerceIn(0f, 1f)
                last = fraction
                scrolled += scrollBy(remaining * share)
            }
        }
        // With animations off the block above runs once, on the estimate: the line is laid out now, so it lands.
        windowY(list, index, lineTop())?.let { list.scrollBy(it - target) }
        settle(index, lineTop, lineHeight, edge, animate = true)
    }

    /** After a bring that could not see the lines it would land: the small move that lands them on the band's edge. */
    private suspend fun settle(index: Int, lineTop: () -> Float, lineHeight: () -> Float, edge: EdgeSnap?, animate: Boolean) {
        edge ?: return
        val y = windowY(list, index, lineTop()) ?: return
        val scroll = edge.adjust(0f, Span(y, y + lineHeight()))
        if (scroll == 0f) return
        if (animate) list.animateScrollBy(scroll, FocalMotion.move()) else list.scrollBy(scroll)
    }

    private companion object {
        /** A spring this close to its end has landed: the rest of the distance goes in one step. */
        const val LANDED = 1e-3f
    }
}

/**
 * In Edit the page moves for one reason only: the caret's line would otherwise be hidden ([caretScroll], run by the
 * page). Every other request to bring something into view is declined: a field that takes focus asks for its whole
 * paragraph, each key and each caret move asks again, and answering them stepped the text up and back under the
 * fingers.
 */
internal object StillPage : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * How far the list scrolls (pixels, positive moves the text up) to keep the caret's line, [top]..[bottom] in the
 * window, inside the band [bandTop]..[bandBottom]: nothing while it is inside, else the least that brings it in. A
 * line taller than the band keeps its top.
 */
internal fun caretScroll(top: Float, bottom: Float, bandTop: Float, bandBottom: Float): Float = when {
    top < bandTop || bottom - top > bandBottom - bandTop -> top - bandTop
    bottom > bandBottom -> bottom - bandBottom
    else -> 0f
}

/** A bar that is out of play (the top bar under Find, the bar Edit replaced): never read, focused or touched. */
internal fun Modifier.inertBar(on: Boolean): Modifier = inert(on).untouchable(on)

/** T7: a paragraph of the first screen fades in from +180 ms, the bottom one first, 24 ms apart. */
internal fun entranceDelay(landing: Boolean, index: Int, count: Int): Long? {
    if (!landing || index >= FocalTiming.STAGGER_MAX) return null
    val shown = minOf(count, FocalTiming.STAGGER_MAX)
    return FocalTiming.SETTLE_PARAGRAPH_DELAY_MS + (shown - 1 - index).coerceAtLeast(0) * FocalTiming.RACK_STAGGER_MS
}

/** The paragraph actions' labels (spec 8.2). */
internal data class ParagraphLabels(val play: String, val edit: String, val copy: String, val restore: String, val playUnclear: String)

/**
 * TalkBack's paragraph actions (spec 8.2): "Play from here", "Edit", "Copy paragraph", "Restore original" when edited,
 * and "Play unclear word" when a word is doubtful. No word-level nodes, so a long note never becomes thousands of
 * targets. In Edit nothing plays: "Edit" puts the caret in the paragraph, as a tap does (the field it leaves keeps its
 * draft, saved in turn), so a paragraph can be edited without a pointer; the focused one is a field with its own
 * actions. While Find is open over Edit there is no field to put the caret in.
 */
internal fun paragraphActions(
    paragraph: ParagraphUi,
    labels: ParagraphLabels,
    note: NoteUiState,
    canPlay: Boolean,
    editing: Boolean,
    finding: Boolean,
    play: (ParagraphUi, Int) -> Unit,
    edit: (CaretAt) -> Unit,
    copy: (ParagraphUi) -> Unit,
    restore: (ParagraphUi) -> Unit,
): List<CustomAccessibilityAction> = buildList {
    if (canPlay && !editing) add(CustomAccessibilityAction(labels.play) { play(paragraph, 0); true })
    if (note.editable && !(editing && finding)) add(CustomAccessibilityAction(labels.edit) { edit(CaretAt(paragraph.id, 0)); true })
    add(CustomAccessibilityAction(labels.copy) { copy(paragraph); true })
    if (paragraph.edited && paragraph.canRestore && !editing) add(CustomAccessibilityAction(labels.restore) { restore(paragraph); true })
    val doubtful = paragraph.words.firstOrNull { it.doubtful }
    if (SpeechTuning.DOUBT_ENABLED && doubtful != null && canPlay && !editing) {
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
 * One paragraph row: its text, or in Edit the field that holds the caret (the same box and style: the swap moves
 * nothing). Nothing is drawn around a paragraph while it is edited and nothing is added under it, so no line moves or
 * changes as the caret goes from one paragraph to the next: "Restore original" is in Edit's menu.
 */
@Composable
internal fun LazyItemScope.ParagraphItem(
    item: NoteItem.Text,
    page: NotePageState,
    placement: FiniteAnimationSpec<IntOffset>?,
    arrival: Arrivals.Arrival?,
    entrance: Long?,
    onTap: (ParagraphUi, offset: Int, word: IntRange?) -> Unit,
    onSweep: ((ParagraphUi, IntRange) -> Unit)?,
    actions: List<CustomAccessibilityAction>,
    onEnter: (full: String, at: Int) -> Unit,
    onJoin: () -> Unit,
) {
    val paragraph = item.paragraph
    val find = page.find
    val field = page.field?.takeIf { page.editing && !find.open && it.id == paragraph.id }
    val unclear = unclearDescription(paragraph)
    Column(
        Modifier
            .animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null)
            .padding(horizontal = FocalLayout.gutter),
    ) {
        if (field != null) {
            ParagraphField(field.text, paragraph.id, field.requester, page.layouts, onEnter, onJoin)
        } else {
            ParagraphText(
                paragraph, page.focus, find, page.layouts, arrival, entrance,
                onTap = { offset, word -> onTap(paragraph, offset, word) },
                onSweep = onSweep?.let { sweep -> { range -> sweep(paragraph, range) } },
                actions = actions, unclear = unclear,
            )
        }
    }
}

/**
 * The head's content from the note's state (spec 5.5, 5.9, 7.5). Without [interactive] (Edit, Find) the notices keep
 * their text but offer no Retry: transcribing again would take the note out of Edit from under the user.
 */
@Composable
internal fun headUi(
    note: NoteUiState,
    paragraphs: List<ParagraphUi>,
    dates: LibraryDates,
    today: LocalDate,
    captureElsewhere: Boolean,
    actions: NoteActions,
    interactive: Boolean = true,
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
            add(Notice(stringResource(R.string.partial_failure), action = retry.takeIf { note.canRetry && interactive }, onAction = actions.retry))
        }
        if (note.phase == NotePhase.WAITING) when {
            captureElsewhere -> add(Notice(stringResource(R.string.waiting_after_recording)))
            note.waitingReason != null -> add(
                Notice(
                    waitingText(note.waitingReason, page = true),
                    action = tryAgain.takeIf { note.canRetry && interactive && note.waitingReason.retries }, onAction = actions.retry,
                ),
            )
        }
    }
    return HeadUi(note.title.ifBlank { fallback }, note.title.isBlank(), derived, meta, notices)
}

/** A derived title comes from the first sentence; three paragraphs are always enough (as the store reads it). */
private const val TITLE_PARAGRAPHS = 3
