package app.focal.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The bottom column's shared state (spec 2.5): the feedback pill, and where the topmost bottom element's top edge is,
 * so the paper fade and the lists' bottom padding follow it.
 */
@Stable
class BottomColumnState {
    val feedback = FeedbackHostState()

    /** The topmost element's top (feedback pill included), in pixels from the top of the window. Layout writes it. */
    internal var topTarget by mutableFloatStateOf(Float.NaN)
    internal var windowHeight by mutableFloatStateOf(0f)
    internal var keyboardDriven by mutableStateOf(false)

    /** The same edge on `move` (exact while the keyboard drives it). Read it in draw or layout only. */
    internal val top = Animatable(Float.NaN)

    /** Scrolling content reserves the column's height plus 64 dp, so the last line scrolls clear of the fade. */
    fun contentReserve(density: Density): Dp = with(density) {
        val target = topTarget
        if (target.isNaN() || windowHeight <= 0f) return FocalLayout.contentReserve + FocalGrid.x56 + FocalLayout.dockInset
        (windowHeight - target).toDp() + FocalLayout.contentReserve
    }

    /**
     * A list's content padding: [top], and [contentReserve] at the bottom. The reserve follows the keyboard frame by
     * frame while rows ride it, so it is read where the list measures, never in composition: the page does not
     * recompose for the keyboard. Remember it per [top] and [density].
     */
    fun contentPadding(top: Dp, density: Density): PaddingValues = ReservePadding(top, this, density)
}

private class ReservePadding(private val top: Dp, private val column: BottomColumnState, private val density: Density) : PaddingValues {
    override fun calculateTopPadding(): Dp = top
    override fun calculateBottomPadding(): Dp = column.contentReserve(density)
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}

@Composable
fun rememberBottomColumnState(): BottomColumnState {
    val state = remember { BottomColumnState() }
    LaunchedEffect(state) {
        snapshotFlow { state.topTarget to state.keyboardDriven }.distinctUntilChanged().collect { (target, keyboard) ->
            if (target.isNaN()) return@collect
            if (keyboard || state.top.value.isNaN()) state.top.snapTo(target) else state.top.animateTo(target, FocalMotion.move())
        }
    }
    return state
}

/**
 * The bottom column (spec 2.5, 4.8, 4.9): one layout for everything touched often, on the gutter-to-gutter column
 * (capped at 600 dp and centred on wide windows). Bottom up: the dock, navigation inset + 16; the player [strip], 8
 * above it; keyboard-riding [rows], whose lowest edge is min(IME top, navigation top - 8) - 8; the feedback pill, 8
 * above the topmost of them (above the keyboard while the dock is hidden) and drawn behind the dock. Positions read
 * the IME inset in the layout phase, so on API 30+ the rows follow the keyboard's own curve frame by frame; on API
 * 26-29 they appear where the keyboard settles, fading in on `fadeFast`. The dock hides (24 dp down and out) while rows are open (or [dockHidden]), following the
 * keyboard when it drives the change. It never moves for any other reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BottomColumn(
    state: BottomColumnState,
    dock: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    strip: (@Composable () -> Unit)? = null,
    rows: (@Composable ColumnScope.() -> Unit)? = null,
    dockHidden: Boolean = rows != null,
) {
    val density = LocalDensity.current
    val hideDistance = with(density) { FocalGrid.x24.toPx() }
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val navigation = WindowInsets.navigationBars
    // The dock slides 24 dp down on `move` and fades on `fade` (spec 4.3), or follows the keyboard while it moves.
    val slide = remember { Animatable(if (dockHidden) 1f else 0f) }
    val fade = remember { Animatable(if (dockHidden) 1f else 0f) }
    LaunchedEffect(dockHidden) {
        launch { slide.animateTo(if (dockHidden) 1f else 0f, FocalMotion.move()) }
        fade.animateTo(if (dockHidden) 1f else 0f, FocalMotion.fade())
    }
    val rowsAlpha = remember { Animatable(1f) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) LaunchedEffect(rows != null) {
        // API 26-29 do not animate the IME inset: soften the jump to the settled position.
        snapshotFlow { ime.getBottom(density) }.distinctUntilChanged().collect {
            rowsAlpha.snapTo(0f)
            rowsAlpha.animateTo(1f, FocalMotion.fadeFast())
        }
    }
    SideEffect { state.keyboardDriven = rows != null }
    Layout(
        contents = listOf(
            { FeedbackPill(state.feedback) },
            { strip?.invoke() },
            {
                if (rows != null) {
                    Column(Modifier.graphicsLayer { alpha = rowsAlpha.value }, verticalArrangement = Arrangement.spacedBy(FocalLayout.bottomGap), content = rows)
                }
            },
            {
                val graphics = Modifier.graphicsLayer {
                    // Hiding, the dock goes as far as the keyboard has risen or its own springs have run, whichever
                    // leads: it follows a keyboard that moves, and still hides when a requested keyboard never shows
                    // (a hardware keyboard, a refused IME). It never comes back while it is meant to be hidden.
                    val target = imeTarget.getBottom(density)
                    val keyboard = if (dockHidden && target > 0) (ime.getBottom(density).toFloat() / target).coerceIn(0f, 1f) else 0f
                    translationY = maxOf(keyboard, slide.value) * hideDistance
                    alpha = 1 - maxOf(keyboard, fade.value)
                }
                Box(graphics) { dock() }
            },
        ),
        modifier = modifier,
    ) { (feedbackMeasurables, stripMeasurables, rowMeasurables, dockMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val column = min(width - 2 * FocalLayout.gutter.roundToPx(), FocalLayout.contentMaxWidth.roundToPx()).coerceAtLeast(0)
        val left = (width - column) / 2
        val childConstraints = Constraints(minWidth = column, maxWidth = column, maxHeight = height)
        val navigationTop = height - navigation.getBottom(this)
        val imeTop = height - ime.getBottom(this)
        val gap = FocalLayout.bottomGap.roundToPx()
        val dockPlaceables = dockMeasurables.map { it.measure(childConstraints) }
        val dockHeight = dockPlaceables.maxOfOrNull { it.height } ?: 0
        val dockBottom = navigationTop - FocalLayout.dockInset.roundToPx()
        val dockTop = dockBottom - dockHeight
        val rowPlaceables = rowMeasurables.map { it.measure(childConstraints) }
        val rowsHeight = rowPlaceables.maxOfOrNull { it.height } ?: 0
        val rowsBottom = min(imeTop, navigationTop - gap) - gap
        val stripPlaceables = if (rows == null) stripMeasurables.map { it.measure(childConstraints) } else emptyList()
        val stripHeight = stripPlaceables.maxOfOrNull { it.height } ?: 0
        val stripBottom = dockTop - gap
        val topmost = when {
            rows != null -> rowsBottom - rowsHeight
            stripHeight > 0 -> stripBottom - stripHeight
            else -> dockTop
        }
        val feedbackPlaceables = feedbackMeasurables.map { it.measure(childConstraints) }
        val feedbackHeight = feedbackPlaceables.maxOfOrNull { it.height } ?: 0
        // With the dock hidden and nothing riding the keyboard (Edit, a rename), the pill rides it as a row would:
        // the keyboard is then the topmost visible bottom element (spec 2.5, 4.1.3).
        val feedbackBottom = if (dockHidden && rows == null) rowsBottom else topmost - gap
        state.windowHeight = height.toFloat()
        state.topTarget = (if (state.feedback.current != null) feedbackBottom - feedbackHeight else topmost).toFloat()
        layout(width, height) {
            feedbackPlaceables.forEach { it.place(left, feedbackBottom - it.height) }
            stripPlaceables.forEach { it.place(left, stripBottom - it.height) }
            rowPlaceables.forEach { it.place(left, rowsBottom - it.height) }
            dockPlaceables.forEach { it.place(left, dockBottom - it.height) }
        }
    }
}

/**
 * The paper fade above the bottom column (spec 2.6), drawn by the screen under its own content: a gradient from
 * transparent to paper ending 8 dp above the topmost bottom element (64 dp tall), then solid paper to the window
 * bottom. Above search results and find rows ([keyboard]) it is 48 dp and ends at the element itself. It follows the
 * element on `move` (exactly, while the keyboard drives it). When the keyboard covers the topmost element (a rename),
 * the 48 dp fade ends at the keyboard's top instead. [alpha] lets a screen hold it off while nothing scrolls under it.
 * Fill the window with [modifier].
 */
@Composable
fun BottomFade(state: BottomColumnState, modifier: Modifier, keyboard: Boolean = false, alpha: () -> Float = { 1f }) {
    val paper = FocalTheme.colors.paper
    val ime = WindowInsets.ime
    Spacer(
        modifier.drawBehind {
            val top = state.top.value
            if (top.isNaN()) return@drawBehind
            val strength = alpha()
            if (strength <= 0f) return@drawBehind
            // A keyboard over the bottom column (an inline rename hides the dock and rides nothing on the keyboard):
            // the content that ends at its top gets the keyboard rows' fade instead of a cut through a line.
            val imeTop = size.height - ime.getBottom(this)
            val elementEnd = if (keyboard) top else top - FocalLayout.bottomGap.toPx()
            val overKeyboard = imeTop < elementEnd
            val end = if (overKeyboard) imeTop else elementEnd
            val start = end - (if (keyboard || overKeyboard) FocalLayout.keyboardFade else FocalLayout.bottomFade).toPx()
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to paper, startY = start, endY = end),
                topLeft = Offset(0f, start), size = Size(size.width, end - start), alpha = strength,
            )
            drawRect(paper, topLeft = Offset(0f, end), size = Size(size.width, size.height - end), alpha = strength)
        },
    )
}

/** Find and replace rows (spec 4.9.2): fields fill the start column, the controls keep 136 dp, 8 dp apart. */
object KeyboardRowMetrics {
    val searchField = FocalGrid.x56
    val row = FocalGrid.x48
    val controls = 136.dp
    val columnGap = FocalGrid.x8
}

/**
 * A find or replace row: the field cell and the control cell on the two-column grid. The row grows as one (spec 8.4):
 * it is as tall as its taller cell, and both cells fill that height (each with `fillMaxHeight`).
 */
@Composable
fun KeyboardRow(field: @Composable RowScope.() -> Unit, control: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, content = field)
        Spacer(Modifier.width(KeyboardRowMetrics.columnGap))
        Row(Modifier.width(KeyboardRowMetrics.controls).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, content = control)
    }
}
