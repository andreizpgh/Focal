package app.focal.ui.note

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.focal.core.SpeechTuning
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import app.focal.ui.design.LocalReducedMotion
import app.focal.ui.state.ParagraphUi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Paragraph geometry (spec 4.13, 4.14). */
object ParagraphMetrics {
    val gap = Grid.x16
    /** The lens, find matches and the search-arrival mark span the 28 dp line box inset this much (22 dp tall). */
    val markInset = 3.dp
    /** The lens is the word box plus this much on each side; a match, [matchOutset]. */
    val lensOutset = Grid.x4
    val matchOutset = Grid.x2
    /** Doubtful words: round dots this big, this far apart, this far under the baseline. */
    val dotRadius = 0.75.dp
    val dotPitch = Grid.x4
    val dotDrop = 3.dp
    /** The edit sheet is drawn this far outside the text box (x 12-400 for a 24-388 paragraph). */
    val sheetOutsetX = Grid.x12
    val sheetOutsetY = Grid.x8
    /** An arriving paragraph rises this far as it develops. */
    val developRise = Grid.x8
    const val LENS_START_SCALE = 0.92f
}

/** A paragraph's marks in its text layout: one rectangle per line a range covers, in the 22 dp mark band. */
private fun TextLayoutResult.boxes(range: IntRange, outset: Float, inset: Float): List<Rect> {
    if (range.isEmpty() || layoutInput.text.isEmpty()) return emptyList()
    val last = min(range.last, layoutInput.text.length - 1)
    val first = range.first.coerceIn(0, last)
    val out = ArrayList<Rect>(1)
    for (line in getLineForOffset(first)..getLineForOffset(last)) {
        val start = max(first, getLineStart(line))
        val end = min(last, getLineEnd(line, visibleEnd = true) - 1)
        if (end < start) continue
        val a = getBoundingBox(start)
        val b = getBoundingBox(end)
        out += Rect(min(a.left, b.left) - outset, getLineTop(line) + inset, max(a.right, b.right) + outset, getLineBottom(line) - inset)
    }
    return out
}

/** The playing-word lens (spec 2.8.1): it glides within a line on `press`, and across lines fades out, jumps, fades in. */
private class Lens {
    val rect = Animatable(Rect.Zero, Rect.VectorConverter)
    val alpha = Animatable(0f)
    val scale = Animatable(1f)

    suspend fun show(target: Rect?, reduced: Boolean) = coroutineScope {
        when {
            target == null -> if (reduced) alpha.snapTo(0f) else alpha.animateTo(0f, FocalMotion.fadeFast())
            alpha.targetValue == 0f -> {
                rect.snapTo(target)
                if (reduced) {
                    alpha.snapTo(1f)
                    scale.snapTo(1f)
                } else {
                    // Playback starts: the lens fades in at the word, growing from 0.92.
                    scale.snapTo(ParagraphMetrics.LENS_START_SCALE)
                    launch { scale.animateTo(1f, FocalMotion.press()) }
                    alpha.animateTo(1f, FocalMotion.fadeFast())
                }
            }
            abs(rect.targetValue.top - target.top) < LINE_EPSILON ->
                if (reduced) rect.snapTo(target) else rect.animateTo(target, FocalMotion.press())
            else -> {
                if (!reduced) alpha.animateTo(0f, FocalMotion.fadeFast())
                rect.snapTo(target)
                if (reduced) alpha.snapTo(1f) else alpha.animateTo(1f, FocalMotion.fadeFast())
            }
        }
    }

    private companion object { const val LINE_EPSILON = 1f }
}

/**
 * A paragraph in reading, playing and find (spec 4.13): one `body` text whose state is colour and drawn shapes only,
 * so its lines break on the same words in every state. Its ink and its receded step are a draw-phase colour; the
 * lens, find matches, the search-arrival mark and doubt dots are drawn behind the glyphs. A tap resolves to a
 * character offset ([onTap]); long-press is left to the system selection around the list.
 *
 * [arrival] develops new text (2.8.1): a new paragraph rises 8 dp, fades in and inks from `faint`, from +60 ms; an
 * extended one inks its new range only. [entrance] is T7's: the paragraph fades in (`fadeSlow`) after that many ms.
 */
@Composable
internal fun ParagraphText(
    paragraph: ParagraphUi,
    focus: NoteFocus,
    find: FindState,
    layouts: ParagraphLayouts,
    arrival: Arrivals.Arrival?,
    entrance: Long?,
    onTap: ((Int) -> Unit)?,
    tapBlocked: () -> Boolean,
    actions: List<CustomAccessibilityAction>,
    unclear: String?,
    modifier: Modifier = Modifier,
    sheet: () -> Float = { 0f },
) {
    val colors = FocalTheme.colors
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val id = paragraph.id
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val inks by remember(id, focus) { derivedStateOf { focus.inks(id) } }
    val receded = remember { Animatable(if (inks) 0f else 1f) }
    LaunchedEffect(inks, reduced) {
        val target = if (inks) 0f else 1f
        if (reduced) receded.snapTo(target) else {
            if (inks) delay(focus.rackDelay(id))
            receded.animateTo(target, FocalMotion.develop())
        }
    }

    val whole = arrival?.start == 0
    val rangeStart = arrival?.start?.takeIf { it > 0 }
    val developed = remember { Animatable(if (arrival == null || reduced) 1f else 0f) }
    val shown = remember { Animatable(if (whole && !reduced) 0f else 1f) }
    val rise = remember { Animatable(if (whole && !reduced) 1f else 0f) }
    LaunchedEffect(arrival, reduced) {
        if (arrival == null || reduced) {
            developed.snapTo(1f)
            shown.snapTo(1f)
            rise.snapTo(0f)
            return@LaunchedEffect
        }
        developed.snapTo(0f)
        if (arrival.start == 0) {
            shown.snapTo(0f)
            rise.snapTo(1f)
        }
        delay((arrival.atMs + FocalTiming.DEVELOP_DELAY_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
        launch { shown.animateTo(1f, FocalMotion.fadeSlow()) }
        launch { rise.animateTo(0f, FocalMotion.move()) }
        developed.animateTo(1f, FocalMotion.develop())
    }
    val entered = remember { Animatable(if (entrance == null || reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (entrance == null || reduced) return@LaunchedEffect
        delay(entrance)
        entered.animateTo(1f, FocalMotion.fadeSlow())
    }

    val matches by remember(id, find) { derivedStateOf { find.matchesIn(id) } }
    val current by remember(id, find) {
        derivedStateOf { find.currentMatch?.takeIf { it.paragraphId == id }?.let { it.start until it.end } }
    }
    val hit by remember(id, focus) { derivedStateOf { focus.hit?.takeIf { it.paragraphId == id }?.let { it.start until it.end } } }
    // Only an extended paragraph recomposes while it develops: its new range is a span (spec 10).
    val rangeInk = if (rangeStart != null) lerp(colors.faint, colors.ink, developed.value) else null
    val text = remember(paragraph.text, matches, current, rangeStart, rangeInk, colors.ink, colors.onInv) {
        buildAnnotatedString {
            append(paragraph.text)
            if (rangeStart != null && rangeInk != null && rangeStart < paragraph.text.length) {
                addStyle(SpanStyle(color = rangeInk), rangeStart, paragraph.text.length)
            }
            matches.forEach { range ->
                val end = min(range.last + 1, paragraph.text.length)
                if (range.first < end) addStyle(SpanStyle(color = if (range == current) colors.onInv else colors.ink), range.first, end)
            }
        }
    }
    val words = paragraph.words
    val lensWord by remember(id, focus) { derivedStateOf { if (focus.playingId == id) focus.playingWord else null } }
    val lens = remember { Lens() }
    LaunchedEffect(lensWord, layout, words, reduced) {
        val target = layout?.let { result ->
            lensWord?.let { words.getOrNull(it) }?.let { word ->
                with(density) {
                    result.boxes(word.start until word.end, ParagraphMetrics.lensOutset.toPx(), ParagraphMetrics.markInset.toPx()).firstOrNull()
                }
            }
        }
        lens.show(target, reduced)
    }
    val tap by rememberUpdatedState(onTap)
    val color = ColorProducer {
        val ink = if (whole) lerp(colors.faint, colors.ink, developed.value) else colors.ink
        lerp(ink, colors.recede, receded.value)
    }
    BasicText(
        text = text,
        style = FocalType.body,
        color = color,
        onTextLayout = { result ->
            layout = result
            layouts.put(id, result)
        },
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = shown.value * entered.value
                translationY = rise.value * ParagraphMetrics.developRise.toPx()
            }
            .editSheet(colors.s1, sheet)
            .drawWithCache {
                val result = layout
                val inset = ParagraphMetrics.markInset.toPx()
                val matchOutset = ParagraphMetrics.matchOutset.toPx()
                val matchBoxes = if (result == null) emptyList() else matches.flatMap { result.boxes(it, matchOutset, inset) }
                val currentBoxes = if (result == null) emptyList() else current?.let { result.boxes(it, matchOutset, inset) }.orEmpty()
                val hitBoxes = if (result == null) emptyList() else hit?.let { result.boxes(it, matchOutset, inset) }.orEmpty()
                val dots = if (result == null || !SpeechTuning.DOUBT_ENABLED) emptyList() else doubtDots(result, paragraph, this)
                val radius = CornerRadius(FocalShapes.markRadius.toPx())
                val dotRadius = ParagraphMetrics.dotRadius.toPx()
                onDrawBehind {
                    val hitAlpha = focus.hitAlpha.value
                    if (hitAlpha > 0f) hitBoxes.forEach { drawRoundRect(colors.s2, it.topLeft, it.size, radius, alpha = hitAlpha) }
                    matchBoxes.forEach { drawRoundRect(colors.s2, it.topLeft, it.size, radius) }
                    currentBoxes.forEach { drawRoundRect(colors.inv, it.topLeft, it.size, radius) }
                    val lensAlpha = lens.alpha.value
                    if (lensAlpha > 0f) {
                        val box = lens.rect.value
                        val k = lens.scale.value
                        val size = Size(box.width * k, box.height * k)
                        val topLeft = Offset(box.center.x - size.width / 2, box.center.y - size.height / 2)
                        drawRoundRect(colors.lens, topLeft, size, radius, alpha = lensAlpha)
                    }
                    dots.forEach { drawCircle(colors.faint, dotRadius, it) }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Taps fire on pointer-up; a long-press belongs to the system selection (spec 2.8.3, 5.5).
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                        ?: return@awaitEachGesture
                    val handler = tap ?: return@awaitEachGesture
                    if (tapBlocked()) return@awaitEachGesture
                    val result = layout ?: return@awaitEachGesture
                    handler(result.getOffsetForPosition(up.position))
                }
            }
            .semantics {
                customActions = actions
                if (unclear != null) stateDescription = unclear
            },
    )
}

/** Dots under each doubtful word: 1.5 dp, on a 4 dp pitch, 3 dp below the baseline (spec 4.13). */
private fun doubtDots(layout: TextLayoutResult, paragraph: ParagraphUi, density: Density): List<Offset> = with(density) {
    val pitch = ParagraphMetrics.dotPitch.toPx()
    val drop = ParagraphMetrics.dotDrop.toPx()
    val radius = ParagraphMetrics.dotRadius.toPx()
    val out = ArrayList<Offset>()
    for (word in paragraph.words) {
        if (!word.doubtful || word.end > layout.layoutInput.text.length) continue
        for (box in layout.boxes(word.start until word.end, 0f, 0f)) {
            val line = layout.getLineForVerticalPosition(box.center.y)
            val y = layout.getLineBaseline(line) + drop
            var x = box.left + radius
            while (x <= box.right - radius) {
                out += Offset(x, y)
                x += pitch
            }
        }
    }
    out
}

/** The edit sheet (spec 4.14): `s1`, radius 12, drawn 12 dp outside the text box sideways and 8 dp above and below. */
internal fun Modifier.editSheet(color: Color, alpha: () -> Float): Modifier = drawBehind {
    val a = alpha()
    if (a <= 0f) return@drawBehind
    val x = ParagraphMetrics.sheetOutsetX.toPx()
    val y = ParagraphMetrics.sheetOutsetY.toPx()
    drawRoundRect(
        color, topLeft = Offset(-x, -y), size = Size(size.width + 2 * x, size.height + 2 * y),
        cornerRadius = CornerRadius(FocalShapes.blockRadius.toPx()), alpha = a,
    )
}

/**
 * The focused paragraph in Edit (spec 4.14): a text field with the reading text's exact style and box, so the swap
 * moves nothing. Enter splits at the caret ([onEnter] with the offset; the line break itself is never inserted),
 * Backspace at the very start joins the paragraph before ([onJoin]); pasted line breaks become spaces.
 */
@Composable
internal fun ParagraphField(
    state: TextFieldState,
    paragraphId: Long,
    focusRequester: FocusRequester,
    layouts: ParagraphLayouts,
    onEnter: (Int) -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
    sheet: () -> Float = { 0f },
) {
    val colors = FocalTheme.colors
    val enter by rememberUpdatedState(onEnter)
    val join by rememberUpdatedState(onJoin)
    val input = remember { ParagraphInput { enter(it) } }
    BasicTextField(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .editSheet(colors.s1, sheet)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val atStart = state.selection == TextRange.Zero
                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && atStart) {
                    join()
                    true
                } else false
            },
        inputTransformation = input,
        textStyle = FocalType.body.copy(color = colors.ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
        lineLimits = TextFieldLineLimits.MultiLine(),
        onTextLayout = { result -> result()?.let { layouts.put(paragraphId, it) } },
        cursorBrush = SolidColor(colors.ink),
    )
}

/** Enter is a split, never a line break inside a paragraph (spec 5.7). */
@OptIn(ExperimentalFoundationApi::class)
private class ParagraphInput(private val onEnter: (Int) -> Unit) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount == 1) {
            val range = changes.getRange(0)
            val original = changes.getOriginalRange(0)
            if (original.collapsed && range.length == 1 && asCharSequence()[range.start] == '\n') {
                val at = range.start
                revertAllChanges()
                onEnter(at)
                return
            }
        }
        val text = asCharSequence()
        for (index in text.indices) if (text[index] == '\n') replace(index, index + 1, " ")
    }
}
