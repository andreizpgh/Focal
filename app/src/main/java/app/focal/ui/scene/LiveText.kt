package app.focal.ui.scene

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.state.LiveParagraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** A place in the live transcript: [offset] characters into paragraph [paragraphId]. */
@Immutable
data class TextMark(val paragraphId: Long, val offset: Int)

/**
 * The live transcript as one typesetting sees it (spec 2.8.1, 4.21.6): the paragraphs, where the latest resolved
 * stretch begins ([latest]; everything after it is current, in ink), where the stretch before it began ([previous];
 * it recedes as the latest develops), and which arrival this is. [arrival] 0 means nothing arrived since the
 * typesetting first saw the text: it renders settled and never replays. Ids in [known] were there before the latest
 * arrival; a paragraph not in it is new and opens.
 */
@Immutable
class TranscriptView(
    val paragraphs: List<LiveParagraph>,
    val latest: TextMark?,
    val previous: TextMark?,
    val arrival: Int,
    val arrivedAt: Long,
    val known: Set<Long>,
) {
    /** For paragraph [index]: where its current part begins, and where its receding part begins (both <= length). */
    fun spans(index: Int): Pair<Int, Int> {
        val paragraph = paragraphs[index]
        val length = paragraph.text.length
        val bright = startIn(latest, index, length)
        val recede = min(startIn(previous, index, length), bright)
        return bright to recede
    }

    /** Is the paragraph at [index] new in the latest arrival, recently enough to open and develop? */
    fun opens(index: Int, nowMs: Long): Boolean =
        arrival > 0 && paragraphs[index].id !in known && nowMs - arrivedAt < FocalTiming.SETTLED_AFTER_MS

    private fun startIn(mark: TextMark?, index: Int, length: Int): Int {
        if (mark == null) return length
        val at = paragraphs.indexOfFirst { it.id == mark.paragraphId }
        return when {
            at < 0 || at > index -> length
            at < index -> 0
            else -> mark.offset.coerceIn(0, length)
        }
    }
}

/** How a typesetting follows the transcript's changes (pure, for tests). */
object Transcript {
    /** What a typesetting shows when it first composes: the text as it is, settled; the last paragraph current. */
    fun first(paragraphs: List<LiveParagraph>): TranscriptView = TranscriptView(
        paragraphs, latest = paragraphs.lastOrNull()?.let { TextMark(it.id, 0) }, previous = null, arrival = 0, arrivedAt = 0L,
        known = paragraphs.mapTo(HashSet()) { it.id },
    )

    /**
     * The next view after [paragraphs] arrived. The latest stretch begins at the first place the text grew: a new
     * paragraph, or the first changed word of a paragraph that grew (an open paragraph extends, and the merge may
     * rewrite its last words). Text that only shrank or vanished keeps the current marks.
     */
    fun next(view: TranscriptView, paragraphs: List<LiveParagraph>, nowMs: Long): TranscriptView {
        if (paragraphs == view.paragraphs) return view
        val before = view.paragraphs.associateBy { it.id }
        var mark: TextMark? = null
        for (paragraph in paragraphs) {
            val old = before[paragraph.id]
            if (old == null) {
                mark = TextMark(paragraph.id, 0)
                break
            }
            val common = commonPrefix(old.text, paragraph.text)
            if (paragraph.text != old.text && paragraph.text.length > common) {
                mark = TextMark(paragraph.id, stretchStart(paragraph.text, common))
                break
            }
        }
        val ids = paragraphs.mapTo(HashSet()) { it.id }
        if (mark == null) {
            return TranscriptView(
                paragraphs, view.latest?.takeIf { it.paragraphId in ids }, view.previous?.takeIf { it.paragraphId in ids },
                view.arrival, view.arrivedAt, view.known,
            )
        }
        return TranscriptView(paragraphs, mark, view.latest?.takeIf { it.paragraphId in ids }, view.arrival + 1, nowMs, before.keys)
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = min(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /**
     * Where new text begins after the [common] prefix: past the space when the old text ended on a whole word, at the
     * start of the word when the merge rewrote its ending, so a stretch never begins mid-word.
     */
    private fun stretchStart(text: String, common: Int): Int {
        var i = common.coerceIn(0, text.length)
        if (i < text.length && text[i].isWhitespace()) {
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }
}

/** Keeps a typesetting's view of the transcript from one composition to the next. */
private class TranscriptHolder { var view: TranscriptView? = null }

@Composable
fun rememberTranscript(paragraphs: List<LiveParagraph>): TranscriptView {
    val holder = remember { TranscriptHolder() }
    return remember(paragraphs) {
        val current = holder.view
        (if (current == null) Transcript.first(paragraphs) else Transcript.next(current, paragraphs, SystemClock.uptimeMillis()))
            .also { holder.view = it }
    }
}

/** The inks of a typesetting: current text, receded text, and the faint colour arriving text develops from. */
@Immutable
data class TranscriptInks(val ink: Color, val muted: Color, val faint: Color)

/**
 * One arrival's colour motion, shared by the paragraphs of a typesetting: the latest stretch develops faint to ink
 * 60 ms after it arrives (`develop`), the stretch before it recedes ink to muted 140 ms after that (spec 2.8.1).
 * [focus] off (a storage-full pause) recedes the current stretch too. Values are read where they are drawn.
 */
@Stable
class TranscriptMotion(arrival: Int, focus: Boolean) {
    val develop = Animatable(if (arrival == 0) 1f else 0f)
    val recede = Animatable(if (arrival == 0) 1f else 0f)
    val focus = Animatable(if (focus) 1f else 0f)

    fun bright(inks: TranscriptInks): Color = lerp(inks.muted, lerp(inks.faint, inks.ink, develop.value), focus.value)
    fun receding(inks: TranscriptInks): Color = lerp(inks.ink, inks.muted, recede.value)
}

@Composable
private fun rememberTranscriptMotion(view: TranscriptView, focus: Boolean): TranscriptMotion {
    val reduced = LocalReducedMotion.current
    val motion = remember(view.arrival) { TranscriptMotion(if (reduced) 0 else view.arrival, focus) }
    LaunchedEffect(motion) {
        if (motion.develop.value >= 1f) return@LaunchedEffect
        launch {
            delay(FocalTiming.DEVELOP_DELAY_MS)
            motion.develop.animateTo(1f, FocalMotion.develop())
        }
        delay(FocalTiming.DEVELOP_DELAY_MS + FocalTiming.RECEDE_DELAY_MS)
        motion.recede.animateTo(1f, FocalMotion.develop())
    }
    LaunchedEffect(motion, focus) {
        val target = if (focus) 1f else 0f
        if (reduced) motion.focus.snapTo(target) else motion.focus.animateTo(target, FocalMotion.develop())
    }
    return motion
}

/**
 * A paragraph in its three inks: receded text, the stretch that recedes, the stretch that is current. Colours are
 * drawn, never composed: a paragraph in one ink draws with a colour producer; one that mixes inks is three copies of
 * one layout, each showing its own stretch through its own producer with the rest transparent, so the develop and
 * the recede only redraw and the glyphs of the three line up exactly.
 */
@Composable
private fun LiveParagraphText(
    text: String,
    bright: Int,
    recede: Int,
    style: TextStyle,
    inks: TranscriptInks,
    motion: TranscriptMotion,
    modifier: Modifier = Modifier,
) {
    val length = text.length
    when {
        recede >= length -> BasicText(text, modifier, style, color = { inks.muted })
        bright == 0 -> BasicText(text, modifier, style, color = { motion.bright(inks) })
        recede == 0 && bright >= length -> BasicText(text, modifier, style, color = { motion.receding(inks) })
        else -> {
            val runs = remember(text, recede, bright) { listOf(0 until recede, recede until bright, bright until length).map { visibleOnly(text, it) } }
            Box(modifier) {
                BasicText(runs[0], style = style, color = { inks.muted })
                // The copies are drawing only: the first one carries the paragraph's text for accessibility.
                BasicText(runs[1], Modifier.clearAndSetSemantics { }, style = style, color = { motion.receding(inks) })
                BasicText(runs[2], Modifier.clearAndSetSemantics { }, style = style, color = { motion.bright(inks) })
            }
        }
    }
}

/** [text] with everything outside [run] transparent: the run takes the colour its text is drawn with. */
private fun visibleOnly(text: String, run: IntRange): AnnotatedString = buildAnnotatedString {
    append(text)
    val hidden = SpanStyle(color = Color.Transparent)
    if (run.first > 0) addStyle(hidden, 0, run.first)
    if (run.last + 1 < text.length) addStyle(hidden, run.last + 1, text.length)
}

/**
 * A new paragraph opens (spec 2.8.1): its height grows on `move`, and 60 ms later it develops (alpha on `fadeSlow`,
 * an 8 dp rise on `move`). Later growth of the same paragraph (an open paragraph extending) animates its height on
 * `move`, so the text above slides instead of jumping.
 */
@Composable
private fun Arriving(opens: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val start = opens && !reduced
    val height = remember { Animatable(if (start) 0f else 1f) }
    val alpha = remember { Animatable(if (start) 0f else 1f) }
    val rise = remember { Animatable(if (start) ARRIVAL_RISE else 0f) }
    if (start) LaunchedEffect(Unit) {
        launch { height.animateTo(1f, FocalMotion.move()) }
        delay(FocalTiming.DEVELOP_DELAY_MS)
        launch { rise.animateTo(0f, FocalMotion.move()) }
        alpha.animateTo(1f, FocalMotion.fadeSlow())
    }
    Box(
        modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height * height.value).roundToInt()) { placeable.place(0, 0) }
            }
            .then(if (reduced) Modifier else Modifier.animateContentSize(FocalMotion.move()))
            .graphicsLayer {
                this.alpha = alpha.value
                translationY = rise.value * density
            },
    ) { content() }
}

/** Arriving text rises this far (dp) as it develops. */
private const val ARRIVAL_RISE = 8f

/**
 * The stage's live typesetting (spec 4.22.3): the last paragraphs, bottom-anchored, [gap] apart, in the stage's inks;
 * [hint] before the first text and the [footer] line (storage full, a model condition) under the text. The caller
 * clips and masks the region.
 */
@Composable
fun StageTranscript(
    paragraphs: List<LiveParagraph>,
    style: TextStyle,
    inks: TranscriptInks,
    focus: Boolean,
    modifier: Modifier = Modifier,
    gap: Dp = StageLiveMetrics.paragraphGap,
    hint: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val shown = remember(paragraphs) { paragraphs.takeLast(STAGE_PARAGRAPHS) }
    val view = rememberTranscript(shown)
    val motion = rememberTranscriptMotion(view, focus)
    val now = remember(view) { SystemClock.uptimeMillis() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap, Alignment.Bottom)) {
        if (view.paragraphs.isEmpty()) hint?.invoke()
        view.paragraphs.forEachIndexed { index, paragraph ->
            androidx.compose.runtime.key(paragraph.id) {
                val (bright, recede) = view.spans(index)
                Arriving(view.opens(index, now), Modifier.fillMaxWidth()) {
                    LiveParagraphText(paragraph.text, bright, recede, style, inks, motion)
                }
            }
        }
        footer?.invoke()
    }
}

/** Enough paragraphs to fill the stage's region; older ones are under the mask anyway. */
private const val STAGE_PARAGRAPHS = 6

/**
 * The scene's live typesetting (spec 4.21.6): the whole transcript in a list that starts at the latest paragraph. It
 * opens at the region's top under the instrument (T2) and is bottom-anchored once it overflows, so from then on the
 * latest line rides the bottom edge and older text scrolls up under the mask. The morph lays out the same list in its
 * moving region, so a short transcript sits under the timer at every p and the scene's frame at p = 1 is the morph's. While [follow] is on (the list shows the latest paragraph), a new paragraph is brought into view and opens
 * from zero height, so the text above slides up on `move`; scrolled back by more than a paragraph, the list holds
 * still and the caller shows "Latest".
 */
@Composable
fun SceneTranscript(
    paragraphs: List<LiveParagraph>,
    state: LazyListState,
    style: TextStyle,
    inks: TranscriptInks,
    focus: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val view = rememberTranscript(paragraphs)
    val motion = rememberTranscriptMotion(view, focus)
    val now = remember(view) { SystemClock.uptimeMillis() }
    val newest = view.paragraphs.lastOrNull()?.id
    // The data changes in composition, so the list keeps the latest paragraph in view in the same frame.
    val following by remember(state) { derivedStateOf { state.firstVisibleItemIndex == 0 } }
    remember(newest) {
        if (newest != null && following) state.requestScrollToItem(0)
        newest
    }
    val reversed = remember(view.paragraphs) { view.paragraphs.asReversed() }
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        reverseLayout = true,
        // Top: a transcript shorter than the region sits at its top; a longer one fills it from the bottom.
        verticalArrangement = Arrangement.spacedBy(SceneMetrics.paragraphGap, Alignment.Top),
    ) {
        itemsIndexed(reversed, key = { _, paragraph -> paragraph.id }, contentType = { _, _ -> PARAGRAPH }) { position, paragraph ->
            val index = view.paragraphs.lastIndex - position
            val (bright, recede) = view.spans(index)
            Arriving(view.opens(index, now), Modifier.fillMaxWidth()) {
                LiveParagraphText(paragraph.text, bright, recede, style, inks, motion)
            }
        }
    }
}

private const val PARAGRAPH = "paragraph"
