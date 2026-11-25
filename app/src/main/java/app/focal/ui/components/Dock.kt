package app.focal.ui.components

import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.focal.R
import app.focal.ui.design.FocalClock
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalTiming
import app.focal.ui.design.FocalType
import app.focal.ui.design.Scene
import app.focal.ui.design.TriggerClock
import app.focal.ui.design.rememberFocalHaptics
import app.focal.ui.design.smoothstep
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the dock holds (spec 4.3). The geometry is the bottom column's; only contents, widths and dress change. */
@Immutable
sealed interface DockState {
    /** Record alone, x 24-388: there is nothing to search yet. */
    data object LibraryEmpty : DockState
    /** Search circle + Record. */
    data object LibraryIdle : DockState
    /** While a capture runs (library and scene): Pause + Finish, or Resume pill + Finish while paused. */
    data class Live(val paused: Boolean) : DockState
    /**
     * A note: Play or Pause playback (or nothing for text only) + the progress pill or Copy. With [append], a second
     * circle between them continues the recording into this note (● "Continue recording"). [noteId] says whose dock
     * it is: what the pill remembers (its fill, having been ready, "Copied") belongs to that note only.
     */
    data class Note(val circle: PlayCircle, val pill: NotePill, val append: Boolean = false, val noteId: String? = null) : DockState
    /**
     * Notes are selected in the library: ✕ "Done selecting" + "Delete N". With [audio] or [hasText], ⋮ between them
     * offers bulk actions ("Copy text", "Share", "Remove audio").
     */
    data class Selecting(val count: Int, val audio: Boolean = false, val hasText: Boolean = false) : DockState
}

enum class PlayCircle { Play, PausePlayback, Unavailable, None }

enum class ProgressPhase { Transcribing, Waiting, Preparing }

@Immutable
sealed interface NotePill {
    /** [fraction] is resolved audio over total audio; Waiting and Preparing keep a static fill. */
    data class Progress(val phase: ProgressPhase, val fraction: Float) : NotePill
    data class Copy(val enabled: Boolean = true) : NotePill
}

/**
 * What the dock's controls do. [copy] performs the copy and returns whether `setPrimaryClip` succeeded; the pill
 * then confirms (haptic, check, "Copied"). [finish] fires its own `Confirm` once the service accepts the command
 * (spec 2.9), so the dock adds none. [openSettings] adds the accessibility action on Record and Finish in the
 * library (spec 4.23.6).
 */
@Stable
class DockActions(
    val search: () -> Unit = {},
    val record: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val finish: () -> Unit = {},
    val play: () -> Unit = {},
    val pausePlayback: () -> Unit = {},
    val copy: suspend () -> Boolean = { false },
    val openSettings: (() -> Unit)? = null,
    /** ● on a note: the recording continues into it. */
    val append: () -> Unit = {},
    /** ✕ while notes are selected: the selection ends. */
    val closeSelection: () -> Unit = {},
    /** "Delete N": the selected notes go to the trash (with Undo). */
    val deleteSelected: () -> Unit = {},
    /** ⋮ "Copy text": copies the combined text of the selected notes. */
    val copySelected: () -> Unit = {},
    /** ⋮ "Share": shares the combined text of the selected notes. */
    val shareSelected: () -> Unit = {},
    /** ⋮ "Remove audio": the selected notes keep their text and lose their audio (with Undo). */
    val removeSelectedAudio: () -> Unit = {},
    /** What that audio takes, for "Remove audio · 42 MB". */
    val selectedAudioSize: suspend () -> String? = { null },
)

/**
 * What a keyboard asks of the dock (spec 8.5): Ctrl+C runs the Copy pill's own tap, so it copies and confirms (check,
 * "Copied", `Confirm`) exactly as a tap does, and does nothing where a tap would do nothing.
 */
@Stable
class DockRequests {
    internal var copies by mutableIntStateOf(0)
        private set

    fun copy() {
        copies++
    }
}

/** The dock's two dresses: on paper, or on the recording material (spec 4.7). */
enum class Dress { Paper, Material }

/** Where the recording material is, for the dock's dress (window pixels): inside it the material dress, outside paper. */
object MaterialBounds {
    val Everywhere = Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    val Nowhere = Rect.Zero

    /** Everything above the window y [edge]: the material's bottom edge passing over a bottom dock. */
    fun above(edge: Float) = Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, edge)
}

/** Dock geometry (spec 4.3, 4.7). */
object DockMetrics {
    val height = FocalGrid.x56
    val circle = FocalGrid.x56
    val gap = FocalGrid.x12
    /** The labelled Resume pill that the circle widens into while paused (x 24-172). */
    val resume = 148.dp
    /** The landscape rail (spec 5.14): the circle above the primary circle, centred in this box. */
    val railHeight = circle * 2 + gap
    /** Glyph-only controls in the rail: the record dot and the finish square in a circle (spec 2.7, 5.14). */
    val railDot = FocalGrid.x12
    val railSquare = GlyphSize.finishSquare
    val glyphGap = FocalGrid.x8
    /** The record dot of a note's "Continue recording" circle: the rail's size, a glyph on its own. */
    val appendDot = FocalGrid.x12
    /** The material's Finish pill: shadow 0 8 28 (CSS blur, so sigma 14) and a 1 dp ring. */
    val actionShadowY = FocalGrid.x8
    val actionShadowSigma = 14.dp
    val ring = 1.dp
}

private enum class CircleFace { None, Search, Pause, Resume, Play, PausePlayback, PlayUnavailable, Close, Append, More }

private enum class PillFace { Record, Finish, NotePrimary, Delete }

private fun circleFace(state: DockState) = when (state) {
    DockState.LibraryEmpty -> CircleFace.None
    DockState.LibraryIdle -> CircleFace.Search
    is DockState.Live -> if (state.paused) CircleFace.Resume else CircleFace.Pause
    is DockState.Note -> when (state.circle) {
        PlayCircle.Play -> CircleFace.Play
        PlayCircle.PausePlayback -> CircleFace.PausePlayback
        PlayCircle.Unavailable -> CircleFace.PlayUnavailable
        PlayCircle.None -> CircleFace.None
    }
    is DockState.Selecting -> CircleFace.Close
}

/**
 * The second circle, between the first and the pill: ● "Continue recording" on a note that can take more, ⋮ over a
 * selection that has text to copy/share or audio to remove.
 */
private fun secondFace(state: DockState) = when {
    state is DockState.Note && state.append -> CircleFace.Append
    state is DockState.Selecting && (state.audio || state.hasText) -> CircleFace.More
    else -> CircleFace.None
}

private fun pillFace(state: DockState) = when (state) {
    DockState.LibraryEmpty, DockState.LibraryIdle -> PillFace.Record
    is DockState.Live -> PillFace.Finish
    is DockState.Note -> PillFace.NotePrimary
    is DockState.Selecting -> PillFace.Delete
}

/** How a dock transition advances: from a tap clock ([Tap]) or locked 1:1 to a page transition ([Page]). */
sealed interface DockPace {
    data class Tap(val clock: TriggerClock? = null) : DockPace

    /**
     * Locked 1:1 to a page transition. Equality is scoped to `(serial, destinationKey)` so [derivedStateOf] callers
     * stay stable across frames, while [liveProgress] returns `Float.NaN` if superseded by a newer transition so
     * mid-flight reversals hold the last drawn dock progress instead of jumping to `1f`.
     */
    class Page(val liveProgress: () -> Float, val serial: Int, val destinationKey: Any? = null) : DockPace {
        fun progress(): Float = liveProgress().let { if (it.isNaN()) 1f else it }

        override fun equals(other: Any?): Boolean =
            other is Page && serial == other.serial && destinationKey == other.destinationKey

        override fun hashCode(): Int = 31 * serial + (destinationKey?.hashCode() ?: 0)
    }
}

/** Animated slot lengths (in pixels) for the bottom dock: each circle carries its trailing gap (`0f` when absent). */
@Immutable
internal data class DockSlots(val first: Float, val second: Float, val primary: Float)

/** Exact measured sizes and layout coordinates produced by [DockSlotLayout.layoutHorizontal]. */
internal data class HorizontalDockPlacement(
    val firstWidth: Int,
    val firstHeight: Int,
    val secondWidth: Int,
    val secondHeight: Int,
    val pillWidth: Int,
    val firstX: Int,
    val secondX: Int,
    val pillX: Int,
    val visualFirstX: Int,
    val visualSecondX: Int,
    val visualPillX: Int,
) {
    fun firstY(layoutHeight: Int, circlePx: Float): Int =
        ((layoutHeight - circlePx / 2f) - firstHeight / 2f).roundToInt()

    fun secondY(layoutHeight: Int, circlePx: Float): Int =
        ((layoutHeight - circlePx / 2f) - secondHeight / 2f).roundToInt()

    fun pillY(layoutHeight: Int, pillHeight: Int): Int =
        layoutHeight - pillHeight
}

/** Exact measured sizes and coordinates produced by [DockSlotLayout.layoutRail]. */
internal data class RailDockPlacement(
    val firstWidth: Int,
    val firstHeight: Int,
    val pillSize: Int,
    val firstX: Int,
    val firstY: Int,
    val pillX: Int,
    val pillY: Int,
)

/** Pure, deterministic layout math shared directly between [Dock]'s custom [Layout] and unit tests. */
internal object DockSlotLayout {
    /**
     * Computes target slot lengths in pixels inside [totalPx]: each present circle slot includes [gapPx], while an
     * absent slot is `0f`, and [DockSlots.primary] takes whatever width remains.
     */
    fun targets(firstWidthPx: Float, secondWidthPx: Float, totalPx: Float, gapPx: Float): DockSlots {
        val firstSlot = if (firstWidthPx > 0f) firstWidthPx + gapPx else 0f
        val secondSlot = if (secondWidthPx > 0f) secondWidthPx + gapPx else 0f
        val primary = (totalPx - firstSlot - secondSlot).coerceAtLeast(0f)
        return DockSlots(firstSlot, secondSlot, primary)
    }

    /** Interpolates [from] toward [to] at progress [g] inside [totalPx]. */
    fun interpolate(from: DockSlots, to: DockSlots, g: Float, totalPx: Float): DockSlots {
        val firstSlot = lerp(from.first, to.first, g).coerceAtLeast(0f)
        val secondSlot = lerp(from.second, to.second, g).coerceAtLeast(0f)
        val primary = (totalPx - firstSlot - secondSlot).coerceAtLeast(0f)
        return DockSlots(firstSlot, secondSlot, primary)
    }

    /**
     * Visual diameter `0..circlePx` for an emerging/exiting circle inside a slot of length [slotPx] carrying [gapPx].
     * Remains `0f` until the slot has opened past [gapPx], then grows symmetrically as a true circle up to [circlePx].
     */
    fun circleDiameter(slotPx: Float, circlePx: Float, gapPx: Float): Float =
        (slotPx - gapPx).coerceIn(0f, circlePx)

    /**
     * Visual container `(widthPx, heightPx)` for a circle/pill slot of total length [slotPx] (including [gapPx]).
     * Below [circlePx], height equals width (`d x d`) so an emerging or exiting circle never stretches into a capsule.
     */
    fun circleBox(slotPx: Float, circlePx: Float, gapPx: Float): Pair<Float, Float> {
        val content = (slotPx - gapPx).coerceAtLeast(0f)
        val height = content.coerceIn(0f, circlePx)
        return content to height
    }

    fun layoutHorizontal(
        slots: DockSlots,
        totalWidth: Int,
        circlePx: Float,
        gapPx: Float,
        rtl: Boolean = false,
    ): HorizontalDockPlacement {
        val (w1, h1) = circleBox(slots.first, circlePx, gapPx)
        val (w2, h2) = circleBox(slots.second, circlePx, gapPx)
        val firstW = w1.roundToInt()
        val firstH = h1.roundToInt()
        val secondW = w2.roundToInt()
        val secondH = h2.roundToInt()
        val xSecond = slots.first.roundToInt()
        val xPill = (slots.first + slots.second).roundToInt().coerceIn(0, totalWidth)
        val pillWidth = (totalWidth - xPill).coerceAtLeast(0)
        val visualFirstX = if (rtl) totalWidth - firstW else 0
        val visualSecondX = if (rtl) totalWidth - xSecond - secondW else xSecond
        val visualPillX = if (rtl) 0 else xPill
        return HorizontalDockPlacement(
            firstWidth = firstW,
            firstHeight = firstH,
            secondWidth = secondW,
            secondHeight = secondH,
            pillWidth = pillWidth,
            firstX = 0,
            secondX = xSecond,
            pillX = xPill,
            visualFirstX = visualFirstX,
            visualSecondX = visualSecondX,
            visualPillX = visualPillX,
        )
    }

    fun layoutRail(
        slotFirst: Float,
        railWidthPx: Int,
        railHeightPx: Int,
        circlePx: Float,
        gapPx: Float,
    ): RailDockPlacement {
        val d1 = circleDiameter(slotFirst, circlePx, gapPx).roundToInt()
        val pillSize = circlePx.roundToInt()
        val stackTop = (railHeightPx - (slotFirst + circlePx)) / 2f
        val firstX = ((railWidthPx - d1) / 2f).roundToInt()
        val firstY = stackTop.roundToInt()
        val pillX = ((railWidthPx - pillSize) / 2f).roundToInt()
        val pillY = (stackTop + slotFirst).roundToInt()
        return RailDockPlacement(d1, d1, pillSize, firstX, firstY, pillX, pillY)
    }

    /** Container fill alpha for an emerging/exiting circle (`0 -> 0.5` of [circlePx]; exact reverse on exit). */
    fun emergingContainerAlpha(diameterPx: Float, circlePx: Float): Float =
        if (circlePx <= 0f) 0f else smoothstep(0f, HALF, diameterPx / circlePx)

    /** Glyph/dot alpha for an emerging/exiting circle (`0.6 -> 1.0` of [circlePx]; exact reverse on exit). */
    fun emergingGlyphAlpha(diameterPx: Float, circlePx: Float): Float =
        if (circlePx <= 0f) 0f else smoothstep(EMERGING_GLYPH_FROM, 1f, diameterPx / circlePx)
}

/**
 * The dock (spec 4.3): a 56 dp circle and a 56 dp pill on the bottom column, with a second circle between them on a
 * note that can go on recording. One [DockLayout] interpolates every slot's length including its trailing gap;
 * glyphs and labels fade through; containers that change crossfade without a coverage dip. Slots follow [pace]:
 * either locked 1:1 to a page transition ([DockPace.Page]) or animated from a tap ([DockPace.Tap]). While a capture
 * runs, [material] (the recording material's bounds in the window, read in the draw phase) wipes each control from its
 * paper dress to its material dress (spec 2.8.6), so a drag never recomposes the dock. Touch and semantics belong to
 * the control, never to a dress.
 *
 * In a short window it is a [rail] (spec 5.14): the circle above the primary control, which is a 56 dp circle with
 * its glyph only (the label is its content description, and a long press shows it), centred in a fixed box so that a
 * circle growing in moves the primary one on `move`.
 */
@Composable
fun Dock(
    state: DockState,
    actions: DockActions,
    modifier: Modifier = Modifier,
    material: (() -> Rect)? = null,
    pace: DockPace = DockPace.Tap(),
    hidden: Boolean = false,
    requests: DockRequests? = null,
    rail: Boolean = false,
    leavingOnMaterial: Boolean = false,
    /**
     * Keep the faces a swap has left composed, unseen, instead of removing them as it settles: while Finish's paper
     * grows over the window, so the dock is not recomposed in the frames the paper moves in.
     */
    holdLeaving: Boolean = false,
) {
    val circle = circleFace(state)
    val pill = pillFace(state)
    // The rail holds two controls only (spec 5.14): there the note's menu continues the recording.
    val second = if (rail) CircleFace.None else secondFace(state)
    val selected = (state as? DockState.Selecting)?.count ?: 0
    // ⋮ over a selection opens its menu above the dock, at the dock's end.
    var selectionMenu by remember { mutableStateOf(false) }
    if (second != CircleFace.More && selectionMenu) SideEffect { selectionMenu = false }
    val density = LocalDensity.current
    val circleWidthDp = when {
        circle == CircleFace.None -> 0.dp
        !rail && circle == CircleFace.Resume -> DockMetrics.resume
        else -> DockMetrics.circle
    }
    val secondWidthDp = if (second == CircleFace.None) 0.dp else DockMetrics.circle
    val circlePx = with(density) { DockMetrics.circle.toPx() }
    val gapPx = with(density) { DockMetrics.gap.toPx() }
    val firstWidthPx = with(density) {
        if (rail) (if (circle == CircleFace.None) 0f else circlePx)
        else (if (circleWidthDp > 0.dp) circleWidthDp.toPx() else 0f)
    }
    val secondWidthPx = with(density) {
        if (rail || secondWidthDp <= 0.dp) 0f else secondWidthDp.toPx()
    }
    val targetSlots = DockSlotLayout.targets(firstWidthPx, secondWidthPx, totalPx = 0f, gapPx = gapPx)
    val targets = DockGeometryTargets(targetSlots.first, targetSlots.second)
    val swap = rememberDockSwap(DockFaces(circle, pill, second), pace, targets)
    val leaving = if (holdLeaving) swap.left() else swap.leaving()
    val pagePace = swap.pace is DockPace.Page
    val edge = if (state is DockState.Live) material else null
    // Finish over the scene (T7): the note's paper grows out of the pill, and the scene's Pause | Finish leave in the
    // scene's dress over the note's controls, never turning into their paper dress (a black Finish) on the way out.
    val leavingDress = if (leavingOnMaterial) Dress.Material else null
    val firstDiameter: () -> Float = { DockSlotLayout.circleDiameter(swap.currentFirst(), circlePx, gapPx) }
    val secondDiameter: () -> Float = { DockSlotLayout.circleDiameter(swap.currentSecond(), circlePx, gapPx) }
    val firstEmerging = (circle == CircleFace.None) != ((leaving?.circle ?: circle) == CircleFace.None)
    val secondEmerging = (second == CircleFace.None) != ((leaving?.second ?: second) == CircleFace.None)
    val firstBoxModifier = if (rail) Modifier else Modifier.fillMaxSize()
    val secondBoxModifier = Modifier.fillMaxSize()
    val selecting = state as? DockState.Selecting
    Box(
        if (rail) modifier.size(DockMetrics.circle, DockMetrics.railHeight) else modifier.fillMaxWidth(),
    ) {
        Layout(
            contents = listOf(
                {
                    CircleSlot(
                        face = circle,
                        leaving = leaving?.circle,
                        q = swap::q,
                        pagePace = pagePace,
                        actions = actions,
                        edge = edge,
                        modifier = if (rail) Modifier.fillMaxSize() else firstBoxModifier,
                        rail = rail,
                        leavingDress = leavingDress,
                        emergingDiameter = firstDiameter.takeIf { firstEmerging },
                        circlePx = circlePx,
                    )
                },
                {
                    CircleSlot(
                        face = second,
                        leaving = leaving?.second,
                        q = swap::q,
                        pagePace = pagePace,
                        actions = actions,
                        edge = null,
                        modifier = secondBoxModifier,
                        onMore = { selectionMenu = true },
                        emergingDiameter = secondDiameter.takeIf { secondEmerging },
                        circlePx = circlePx,
                    )
                },
                {
                    PillSlot(
                        face = pill,
                        leaving = leaving?.pill,
                        q = swap::q,
                        pagePace = pagePace,
                        notePill = (state as? DockState.Note)?.pill,
                        noteId = (state as? DockState.Note)?.noteId,
                        actions = actions,
                        edge = edge,
                        modifier = Modifier,
                        hidden = hidden,
                        requests = requests,
                        rail = rail,
                        selected = selected,
                        leavingDress = leavingDress,
                    )
                },
            ),
            modifier = if (rail) Modifier.size(DockMetrics.circle, DockMetrics.railHeight) else Modifier.fillMaxWidth(),
        ) { (firstMeasurables, secondMeasurables, pillMeasurables), constraints ->
            val fromSlots = DockSlots(swap.fromGeom.first, swap.fromGeom.second, 0f)
            val toSlots = DockSlots(swap.toGeom.first, swap.toGeom.second, 0f)
            val g = swap.g()
            if (rail) {
                val widthPx = DockMetrics.circle.roundToPx()
                val heightPx = DockMetrics.railHeight.roundToPx()
                val slots = DockSlotLayout.interpolate(fromSlots, toSlots, g, 0f)
                val placement = DockSlotLayout.layoutRail(
                    slotFirst = slots.first,
                    railWidthPx = widthPx,
                    railHeightPx = heightPx,
                    circlePx = circlePx,
                    gapPx = gapPx,
                )
                val firstPlaceable = firstMeasurables.first().measure(Constraints.fixed(placement.firstWidth, placement.firstHeight))
                val pillPlaceable = pillMeasurables.first().measure(Constraints.fixed(placement.pillSize, placement.pillSize))
                return@Layout layout(widthPx, heightPx) {
                    if (firstPlaceable.width > 0 && firstPlaceable.height > 0) {
                        firstPlaceable.placeRelative(placement.firstX, placement.firstY)
                    }
                    pillPlaceable.placeRelative(placement.pillX, placement.pillY)
                }
            }
            val totalWidth = constraints.maxWidth
            val minHeightPx = DockMetrics.height.roundToPx()
            val slots = DockSlotLayout.interpolate(fromSlots, toSlots, g, totalWidth.toFloat())
            val placement = DockSlotLayout.layoutHorizontal(
                slots = slots,
                totalWidth = totalWidth,
                circlePx = circlePx,
                gapPx = gapPx,
                rtl = layoutDirection == LayoutDirection.Rtl,
            )
            val firstPlaceable = firstMeasurables.first().measure(Constraints.fixed(placement.firstWidth, placement.firstHeight))
            val secondPlaceable = secondMeasurables.first().measure(Constraints.fixed(placement.secondWidth, placement.secondHeight))
            val pillPlaceable = pillMeasurables.first().measure(
                Constraints(minWidth = placement.pillWidth, maxWidth = placement.pillWidth, minHeight = minHeightPx, maxHeight = constraints.maxHeight),
            )
            val layoutHeight = maxOf(minHeightPx, pillPlaceable.height)
            layout(totalWidth, layoutHeight) {
                if (firstPlaceable.width > 0 && firstPlaceable.height > 0) {
                    firstPlaceable.placeRelative(placement.firstX, placement.firstY(layoutHeight, circlePx))
                }
                if (secondPlaceable.width > 0 && secondPlaceable.height > 0) {
                    secondPlaceable.placeRelative(placement.secondX, placement.secondY(layoutHeight, circlePx))
                }
                pillPlaceable.placeRelative(placement.pillX, placement.pillY(layoutHeight, pillPlaceable.height))
            }
        }
        if (!rail) {
            // Anchored to the dock's wrapping Box: opens above it, its end on the dock's end.
            FocalMenu(expanded = selectionMenu, onDismiss = { selectionMenu = false }, above = true) {
                if (selecting?.hasText == true) {
                    FocalMenuItem(
                        stringResource(R.string.menu_copy_text),
                        onClick = {
                            selectionMenu = false
                            actions.copySelected()
                        },
                    )
                    FocalMenuItem(
                        stringResource(R.string.menu_share),
                        onClick = {
                            selectionMenu = false
                            actions.shareSelected()
                        },
                    )
                }
                if (selecting?.audio == true) {
                    if (selecting.hasText) FocalMenuDivider()
                    val size = remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) { size.value = actions.selectedAudioSize() }
                    FocalMenuItem(
                        stringResource(R.string.menu_remove_audio),
                        onClick = {
                            selectionMenu = false
                            actions.removeSelectedAudio()
                        },
                        trailing = size.value,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CircleSlot(
    face: CircleFace,
    leaving: CircleFace?,
    q: () -> Float,
    pagePace: Boolean,
    actions: DockActions,
    edge: (() -> Rect)?,
    modifier: Modifier,
    rail: Boolean = false,
    onMore: () -> Unit = {},
    /** The dress of a face on its way out, whatever the dress the slot now wipes to ([Dock]'s leavingOnMaterial). */
    leavingDress: Dress? = null,
    /** Non-null when this slot is emerging from or exiting to [CircleFace.None]: supplies visual diameter `0..circlePx`. */
    emergingDiameter: (() -> Float)? = null,
    circlePx: Float = 0f,
) {
    val source = remember { MutableInteractionSource() }
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val description = when (face) {
        CircleFace.None -> null
        CircleFace.Search -> stringResource(R.string.cd_search_open)
        CircleFace.Pause -> stringResource(R.string.action_pause)
        CircleFace.Resume -> stringResource(R.string.action_resume)
        CircleFace.Play -> stringResource(R.string.action_play)
        CircleFace.PausePlayback -> stringResource(R.string.action_pause_playback)
        CircleFace.PlayUnavailable -> stringResource(R.string.a11y_play_unavailable)
        CircleFace.Close -> stringResource(R.string.cd_selection_close)
        CircleFace.Append -> stringResource(R.string.action_append)
        CircleFace.More -> stringResource(R.string.cd_more)
    }
    val onClick: (() -> Unit)? = when (face) {
        CircleFace.Close -> actions.closeSelection
        CircleFace.Append -> actions.append
        CircleFace.More -> onMore
        CircleFace.Search -> actions.search
        CircleFace.Pause -> actions.pause
        CircleFace.Resume -> actions.resume
        CircleFace.Play -> actions.play
        CircleFace.PausePlayback -> actions.pausePlayback
        CircleFace.None, CircleFace.PlayUnavailable -> null
    }
    val control = if (description == null) modifier else modifier
        .clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            // Cleared semantics drop the click of `clickable` below, so the action is stated here.
            if (onClick == null) disabled() else this.onClick { onClick(); true }
        }
        .pressScale(source)
        .focusRing(FocalShapes.pill)
        .tapOrLabel(source, settledTap(face, onClick), rail) { scope.launch { tooltip.show() } }
    RailTooltip(description.takeIf { rail }, tooltip) {
        DressWipe(edge, control) { wiped ->
            SlotSwap(face, leaving, q, pagePace, Modifier.fillMaxSize()) { shown, containerAlpha, contentAlpha ->
                val dress = if (leavingDress != null && shown != face) leavingDress else wiped
                if (shown != CircleFace.None) {
                    val slotContainerAlpha: () -> Float = if (emergingDiameter != null) {
                        { DockSlotLayout.emergingContainerAlpha(emergingDiameter(), circlePx) }
                    } else containerAlpha
                    val slotContentAlpha: () -> Float = if (emergingDiameter != null) {
                        { DockSlotLayout.emergingGlyphAlpha(emergingDiameter(), circlePx) }
                    } else contentAlpha
                    TonalContainer(dress, slotContainerAlpha)
                    // Unbounded: "● Resume" keeps its width and glyphs keep 24 dp while a circle emerges or widens.
                    Box(
                        Modifier.wrapContentWidth(unbounded = true).wrapContentHeight(unbounded = true).graphicsLayer { alpha = slotContentAlpha() },
                        contentAlignment = Alignment.Center,
                    ) { CircleContent(shown, dress, rail) }
                }
            }
        }
    }
}

/**
 * [onClick] for a slot showing [face], unless the face changed a moment ago: the swap to a new face takes a fade, and a
 * second tap of a double tap (or a tap repeated because the first seemed to do nothing) was meant for the face that
 * was there. Double-tapping "Delete N" would otherwise start a recording on the Record that replaces it. Whether the
 * tap counts is decided when it lands; null stays null, so the control is still disabled.
 */
@Composable
private fun settledTap(face: Any, onClick: (() -> Unit)?): (() -> Unit)? {
    val guard = remember { FaceGuard(face) }
    if (guard.face != face) {
        guard.face = face
        guard.changedAt = SystemClock.uptimeMillis()
    }
    val click = onClick ?: return null
    return { if (SystemClock.uptimeMillis() - guard.changedAt >= FACE_SETTLE_MS) click() }
}

/** When a slot's face last changed (see [settledTap]). */
private class FaceGuard(var face: Any) { var changedAt = Long.MIN_VALUE / 2 }

/** A tap this soon after a face change is the second tap of a double tap (ViewConfiguration's double-tap timeout). */
private const val FACE_SETTLE_MS = 300L

/**
 * A control's tap; in the rail also a long press that shows its label (spec 5.14), disabled controls included (the
 * progress circle says "Transcribing" only that way).
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tapOrLabel(source: MutableInteractionSource, onClick: (() -> Unit)?, rail: Boolean, showLabel: () -> Unit): Modifier = when {
    !rail -> clickable(source, indication = null, enabled = onClick != null, onClick = { onClick?.invoke() })
    onClick != null -> combinedClickable(source, indication = null, onLongClick = showLabel, onClick = onClick)
    else -> pointerInput(showLabel) { detectTapGestures(onLongPress = { showLabel() }) }
}

/** In the rail, a plain tooltip with the control's label on its start side, shown by a long press or a hover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailTooltip(label: String?, state: TooltipState, content: @Composable () -> Unit) {
    if (label == null) {
        content()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Start),
        tooltip = { PlainTooltip { Text(label, style = FocalType.meta) } },
        state = state,
        enableUserInput = false,
        content = content,
    )
}

@Composable
private fun CircleContent(face: CircleFace, dress: Dress, rail: Boolean) {
    val colors = FocalTheme.colors
    val ink = if (dress == Dress.Material) Scene.ink else colors.ink
    when (face) {
        CircleFace.Search -> Glyph(FocalIcon.Search, ink)
        CircleFace.Pause -> Glyph(FocalIcon.Pause, ink)
        CircleFace.Play -> Glyph(FocalIcon.Play, ink)
        CircleFace.PausePlayback -> Glyph(FocalIcon.Pause, ink)
        CircleFace.PlayUnavailable -> Glyph(FocalIcon.Play, colors.faint)
        CircleFace.Close -> Glyph(FocalIcon.Close, ink)
        CircleFace.More -> Glyph(FocalIcon.More, ink)
        // The dot Record and Resume carry: on a note it records more of the same note.
        CircleFace.Append -> RecordDot(colors.recordDot, size = DockMetrics.appendDot)
        CircleFace.Resume -> if (rail) {
            RecordDot(colors.recordDot, size = DockMetrics.railDot)
        } else {
            LabelRow(stringResource(R.string.action_resume), ink) { RecordDot(colors.recordDot) }
        }
        CircleFace.None -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PillSlot(
    face: PillFace,
    leaving: PillFace?,
    q: () -> Float,
    pagePace: Boolean,
    notePill: NotePill?,
    noteId: String?,
    actions: DockActions,
    edge: (() -> Rect)?,
    modifier: Modifier,
    hidden: Boolean,
    requests: DockRequests?,
    rail: Boolean = false,
    selected: Int = 0,
    /** The dress of a face on its way out, whatever the dress the slot now wipes to ([Dock]'s leavingOnMaterial). */
    leavingDress: Dress? = null,
) {
    val source = remember { MutableInteractionSource() }
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val haptics = rememberFocalHaptics()
    var copied by remember(noteId) { mutableStateOf(false) }
    // The count the Delete pill last showed: it keeps its number while it leaves, after the selection has emptied.
    val shownCount = remember { mutableIntStateOf(selected) }
    if (selected > 0) SideEffect { shownCount.intValue = selected }
    val deleteCount = maxOf(1, if (selected > 0) selected else shownCount.intValue)
    val deleteLabel = pluralStringResource(R.plurals.action_delete_notes, deleteCount, deleteCount)
    val copyEnabled = (notePill as? NotePill.Copy)?.enabled == true
    val settingsLabel = stringResource(R.string.a11y_open_settings)
    val copyLabel = stringResource(R.string.action_copy)
    val copiedLabel = stringResource(R.string.action_copied)
    val progress = notePill as? NotePill.Progress
    val progressDescription = progress?.let { progressDescription(it) }
    val description = when (face) {
        PillFace.Record -> stringResource(R.string.action_record)
        PillFace.Finish -> stringResource(R.string.action_finish)
        PillFace.NotePrimary -> progressDescription ?: if (copied) copiedLabel else copyLabel
        PillFace.Delete -> deleteLabel
    }
    val onClick: (() -> Unit)? = when (face) {
        PillFace.Record -> actions.record
        PillFace.Finish -> actions.finish
        PillFace.Delete -> actions.deleteSelected.takeIf { selected > 0 }
        PillFace.NotePrimary -> if (copyEnabled && !copied) {
            {
                scope.launch {
                    if (actions.copy()) {
                        haptics.copy()
                        copied = true
                        delay(FocalTiming.COPIED_HOLD_MS)
                        copied = false
                    }
                }
            }
        } else null
    }
    // A keyboard's Copy is this pill's tap (only while it is the note's Copy).
    val tap by rememberUpdatedState(onClick.takeIf { face == PillFace.NotePrimary })
    if (requests != null) LaunchedEffect(requests) {
        var seen = requests.copies
        snapshotFlow { requests.copies }.collect { count ->
            if (count != seen) {
                seen = count
                tap?.invoke()
            }
        }
    }
    val control = modifier
        .clearAndSetSemantics {
            contentDescription = description
            if (progress != null) {
                progressBarRangeInfo = ProgressBarRangeInfo(announcedStep(progress.fraction), 0f..1f)
            } else {
                role = Role.Button
            }
            // The progress pill is not a button (spec 8.2: disabled, with its range); Copied is a moment, not a state.
            if (onClick == null && !copied) disabled()
            if (onClick != null) this.onClick { onClick(); true }
            if (face == PillFace.NotePrimary && progress == null) liveRegion = LiveRegionMode.Polite
            val openSettings = actions.openSettings
            if (openSettings != null && face != PillFace.NotePrimary) {
                customActions = listOf(CustomAccessibilityAction(settingsLabel) { openSettings(); true })
            }
        }
        .pressScale(source)
        .focusRing(FocalShapes.pill)
        .tapOrLabel(source, settledTap(face, onClick), rail) { scope.launch { tooltip.show() } }
    RailTooltip(description.takeIf { rail }, tooltip) {
        DressWipe(edge, control) { wiped ->
            // A black Record or Finish turning into the note's pill on paper (T16′, T7b): the pill's fill starts whole
            // and runs back to the note's progress while the black container leaves with it, instead of black fading
            // over the light track (a grey wash at the thumb).
            val primaryLeaves = face == PillFace.NotePrimary && (leaving == PillFace.Record || leaving == PillFace.Finish) &&
                (notePill as? NotePill.Copy)?.enabled != false
            val dark = FocalTheme.colors.dark
            // The scene's Finish is white: over Dark paper it is the fill's colour, over Light paper the paper's.
            val sceneFinish = leavingDress == Dress.Material
            val fromInk = primaryLeaves && (if (sceneFinish) dark else wiped == Dress.Paper)
            // Finish over the scene in Light (T7): the scene's white Finish melts into the note's paper growing out of
            // it (no shadow or ring off the material), and the note's fill runs in from the start edge instead of
            // fading in under white.
            val fromLight = primaryLeaves && sceneFinish && !dark
            val pillSwapModifier = if (rail) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(min = DockMetrics.height)
            val morphing = leaving != null
            SlotSwap(face, leaving, q, pagePace, pillSwapModifier) { shown, containerAlpha, contentAlpha ->
                val dress = if (leavingDress != null && shown != face) leavingDress else wiped
                val leavingInk = fromInk && shown != face
                val melts = fromLight && shown != face
                // Over Finish's paper (T7) the scene's white pill has neither its shadow nor its ring, which belong to
                // the material, from the first frame: also while it is still the dock's face.
                val plain = sceneFinish && dress == Dress.Material
                // White into white paper: gone by the swap's middle, so the fill runs in crisp.
                val primaryAlpha = when {
                    leavingInk -> NoContainer
                    melts -> { { 1f - smoothstep(0f, HALF, q()) } }
                    else -> containerAlpha
                }
                when (shown) {
                    PillFace.Record -> {
                        val dot = FocalTheme.colors.recordDot
                        PrimaryContainer(dress, primaryAlpha, onPaper = plain)
                        PillContent(dress, morphing, contentAlpha) { ink ->
                            if (rail) RecordDot(dot, size = DockMetrics.railDot)
                            else LabelRow(stringResource(R.string.action_record), ink) { RecordDot(dot) }
                        }
                    }
                    PillFace.Finish -> {
                        PrimaryContainer(dress, primaryAlpha, onPaper = plain)
                        PillContent(dress, morphing, contentAlpha) { ink ->
                            if (rail) FinishSquare(ink, size = DockMetrics.railSquare)
                            else LabelRow(stringResource(R.string.action_finish), ink) { FinishSquare(ink) }
                        }
                    }
                    // Keyed by its note: another note's pill starts from that note's state, with no readiness tick
                    // and no fill left over from the one before (two panes switch notes under one dock).
                    PillFace.NotePrimary -> key(noteId) {
                        NotePrimaryPill(
                            notePill ?: NotePill.Copy(), copied, containerAlpha, contentAlpha, hidden, rail, morphing,
                            fillFrom = when {
                                shown != face -> null
                                fromInk -> 1f
                                fromLight -> 0f
                                else -> null
                            },
                            swap = q,
                        )
                    }
                    PillFace.Delete -> {
                        PrimaryContainer(dress, containerAlpha)
                        PillContent(dress, morphing, contentAlpha) { ink ->
                            if (rail) Glyph(FocalIcon.Delete, ink)
                            else LabelRow(deleteLabel, ink) { Glyph(FocalIcon.Delete, ink, size = GlyphSize.inPill) }
                        }
                    }
                }
            }
        }
    }
}

/** The progress pill is announced at 25 % steps only (spec 4.5.3). */
private fun announcedStep(fraction: Float): Float = (fraction.coerceIn(0f, 1f) * ANNOUNCE_STEPS).toInt() / ANNOUNCE_STEPS
private const val ANNOUNCE_STEPS = 4f
private const val PERCENT = 100

@Composable
private fun progressDescription(progress: NotePill.Progress): String = when (progress.phase) {
    ProgressPhase.Transcribing -> (announcedStep(progress.fraction) * PERCENT).roundToInt().let { pluralStringResource(R.plurals.a11y_transcribing, it, it) }
    ProgressPhase.Waiting -> stringResource(R.string.stage_waiting)
    ProgressPhase.Preparing -> stringResource(R.string.stage_preparing)
}

@Composable
private fun PillContent(dress: Dress, morphing: Boolean, alpha: () -> Float, content: @Composable (Color) -> Unit) {
    val ink = if (dress == Dress.Material) Scene.actionLabel else FocalTheme.colors.onInv
    // Sized by its content, so a label that wraps at a large font scale grows the pill (min 56 dp).
    // Unbounded width while morphing avoids mid-morph text reflow, while wrapping still works at rest.
    Box(
        Modifier.fillMaxWidth().heightIn(min = DockMetrics.height)
            .then(if (morphing) Modifier.wrapContentWidth(unbounded = true) else Modifier)
            .graphicsLayer { this.alpha = alpha() },
        contentAlignment = Alignment.Center,
    ) {
        content(ink)
    }
}

/** A centred glyph + 8 dp + `label`: the 56 dp pills' content (spec 4.5.1). Wraps at large font scales. */
@Composable
private fun LabelRow(text: String, color: Color, glyph: @Composable () -> Unit) {
    Row(Modifier.padding(horizontal = FocalGrid.x16), verticalAlignment = Alignment.CenterVertically) {
        glyph()
        Spacer(Modifier.width(DockMetrics.glyphGap))
        Text(text, style = FocalType.label, color = color, textAlign = TextAlign.Center)
    }
}

/**
 * The Note dock's pill (spec 4.5.3, T8): the progress pill and Copy are one control, so readiness is one morph. The
 * fill moves on `move` at each commit and never backwards; at Ready it reaches 100 %, the background goes `s1` to
 * `inv` on `fade` and the label fades through to the copy glyph + "Copy"; `Confirm` fires on that first frame.
 * Copy confirms with the check and "Copied" for 1600 ms.
 */
@Composable
private fun BoxScope.NotePrimaryPill(
    pill: NotePill,
    copied: Boolean,
    containerAlpha: () -> Float,
    contentAlpha: () -> Float,
    hidden: Boolean,
    rail: Boolean,
    morphing: Boolean,
    /**
     * Where the fill comes from while it takes over from a Record or Finish ([swap] 0 → 1): the whole pill, from one of
     * the fill's own colour (on paper, or the scene's white one over Dark paper); nothing, from the scene's white one
     * melting into Light paper.
     */
    fillFrom: Float? = null,
    swap: () -> Float = { 1f },
) {
    val colors = FocalTheme.colors
    val haptics = rememberFocalHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val target = when (pill) {
        is NotePill.Progress -> pill.fraction.coerceIn(0f, 1f)
        is NotePill.Copy -> 1f
    }
    val ready = pill is NotePill.Copy
    val fill = remember { Animatable(target) }
    val inverted = remember { Animatable(if (ready) 1f else 0f) }
    val copyShown = remember { Animatable(if (ready) 1f else 0f) }
    var wasReady by remember { mutableStateOf(ready) }
    // Forward it moves; backward (a retry starts over what was full) it is simply the new value.
    LaunchedEffect(target) { if (target > fill.value) fill.animateTo(target, FocalMotion.move()) else fill.snapTo(target) }
    LaunchedEffect(ready) {
        // Felt only with the dock in sight: not while Edit or Find has it down behind the keyboard.
        if (ready && !wasReady && !hidden && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) haptics.ready()
        wasReady = ready
        launch { inverted.animateTo(if (ready) 1f else 0f, FocalMotion.fade()) }
        copyShown.animateTo(if (ready) 1f else 0f, FocalMotion.fade())
    }
    val enabled = pill !is NotePill.Copy || pill.enabled
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The fill as drawn: the progress, or more of the pill while it takes over from a black control.
    val shownFill: () -> Float = { fillFrom?.let { from -> from + (fill.value - from) * swap().coerceIn(0f, 1f) } ?: fill.value }
    val clip = remember { Path() }
    // Container: the `s1` track, the `inv` fill from the start edge (its leading end straight), clipped to the pill.
    // In the rail's circle the fill rises from the bottom, like a level.
    Box(
        Modifier.matchParentSize().drawBehind {
            val a = containerAlpha()
            if (a <= 0f || size.width <= MIN_DRAW_PX || size.height <= MIN_DRAW_PX) return@drawBehind
            val radius = minOf(size.width, size.height) / 2f
            val corner = CornerRadius(radius)
            val base = if (enabled) lerp(colors.s1, colors.inv, inverted.value) else colors.s1
            drawRoundRect(base, cornerRadius = corner, alpha = a)
            if (enabled) {
                val fraction = shownFill()
                if (fraction >= FULL_FILL) {
                    drawRoundRect(colors.inv, cornerRadius = corner, alpha = a)
                } else if (fraction > 0f) {
                    clip.rewind()
                    clip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius, radius))
                    clipPath(clip) {
                        if (rail) {
                            val height = size.height * fraction
                            drawRect(colors.inv, topLeft = Offset(0f, size.height - height), size = Size(size.width, height), alpha = a)
                        } else {
                            val width = size.width * fraction
                            val left = if (rtl) size.width - width else 0f
                            drawRect(colors.inv, topLeft = Offset(left, 0f), size = Size(width, size.height), alpha = a)
                        }
                    }
                }
            }
        },
    )
    if (rail) {
        // The rail's circle has room for the glyph only: the progress is its level, Copy its glyph.
        val ink = if (enabled) colors.onInv else colors.faint
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = contentAlpha() * smoothstep(HALF, 1f, copyShown.value) },
            contentAlignment = Alignment.Center,
        ) { CopyGlyph(copied, ink) }
        return
    }
    val label = when (pill) {
        is NotePill.Progress -> when (pill.phase) {
            ProgressPhase.Transcribing -> stringResource(R.string.action_transcribing)
            ProgressPhase.Waiting -> stringResource(R.string.stage_waiting)
            ProgressPhase.Preparing -> stringResource(R.string.stage_preparing)
        }
        is NotePill.Copy -> stringResource(R.string.action_transcribing)
    }
    // The progress label twice: ink underneath, onInv on top clipped to the fill width of the full pill track.
    val unboundedWhenMorphing = if (morphing) Modifier.wrapContentWidth(unbounded = true) else Modifier
    Box(
        Modifier.fillMaxWidth().heightIn(min = DockMetrics.height)
            .graphicsLayer { alpha = contentAlpha() * (1 - smoothstep(0f, HALF, copyShown.value)) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = FocalType.label, color = colors.ink, textAlign = TextAlign.Center,
            modifier = Modifier.then(unboundedWhenMorphing).padding(horizontal = FocalGrid.x16),
        )
        Box(
            Modifier.matchParentSize().drawWithContent {
                val width = size.width * shownFill()
                clipRect(left = if (rtl) size.width - width else 0f, right = if (rtl) size.width else width) { this@drawWithContent.drawContent() }
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, style = FocalType.label, color = colors.onInv, textAlign = TextAlign.Center,
                modifier = Modifier.then(unboundedWhenMorphing).padding(horizontal = FocalGrid.x16),
            )
        }
    }
    val ink = if (enabled) colors.onInv else colors.faint
    Box(
        Modifier.matchParentSize().graphicsLayer { alpha = contentAlpha() * smoothstep(HALF, 1f, copyShown.value) },
        contentAlignment = Alignment.Center,
    ) {
        Box(unboundedWhenMorphing, contentAlignment = Alignment.Center) { CopyContent(copied, ink) }
    }
}

/** Copy and Copied in the rail's circle: the copy glyph turning into the check (T9 without its labels). */
@Composable
private fun CopyGlyph(copied: Boolean, ink: Color) {
    val check = remember { Animatable(if (copied) 1f else 0f) }
    val checkScale = remember { Animatable(1f) }
    LaunchedEffect(copied) {
        if (copied) launch { checkScale.snapTo(CHECK_START_SCALE); checkScale.animateTo(1f, FocalMotion.press()) }
        check.animateTo(if (copied) 1f else 0f, FocalMotion.fadeFast())
    }
    Box(contentAlignment = Alignment.Center) {
        Glyph(FocalIcon.Copy, ink, modifier = Modifier.graphicsLayer { alpha = 1 - check.value })
        Glyph(FocalIcon.Check, ink, modifier = Modifier.graphicsLayer { alpha = check.value; scaleX = checkScale.value; scaleY = checkScale.value })
    }
}

private const val CHECK_START_SCALE = 0.7f

/** Copy and Copied in the dock's pill (spec 4.5.1): `label`, a 20 dp glyph and an 8 dp gap, centred on "Copy". */
@Composable
private fun CopyContent(copied: Boolean, ink: Color) {
    CopyLabel(copied, ink, FocalType.label, GlyphSize.inPill, DockMetrics.glyphGap, Modifier.padding(horizontal = FocalGrid.x16))
}

/** What the dock's slots show: the circle, the pill and the second circle between them. */
@Immutable
private data class DockFaces(val circle: CircleFace, val pill: PillFace, val second: CircleFace = CircleFace.None)

@Immutable
private data class DockGeometryTargets(val first: Float, val second: Float)

/**
 * One coordinated swap for the whole dock (spec 4.3, Q7): faces and slot geometries advance together on [pace]
 * (either locked 1:1 to a page transition via [DockPace.Page] or triggered from a tap via [DockPace.Tap]).
 */
@Stable
private class DockSwap(
    val shown: DockFaces,
    private val from: DockFaces?,
    val pace: DockPace,
    private val tapClock: TriggerClock?,
    private val tapSerial: Int,
    val fromGeom: DockGeometryTargets,
    val toGeom: DockGeometryTargets,
    val initialGeomVelocity: Float = 0f,
) {
    val ownAlpha = Animatable(if (from == null || tapClock != null || pace is DockPace.Page) 1f else 0f)
    val ownGeom = Animatable(if (fromGeom == toGeom || pace is DockPace.Page) 1f else 0f)
    val runsOwnAlpha: Boolean get() = from != null && pace is DockPace.Tap && tapClock == null
    val runsOwnGeom: Boolean get() = fromGeom != toGeom && pace is DockPace.Tap
    val claimedTapSerial: Int? get() = if (pace is DockPace.Tap && tapClock != null) tapSerial else null

    private var lastPageProgress = if (from == null && fromGeom == toGeom) 1f else 0f

    private fun pageProgress(): Float {
        val sample = (pace as DockPace.Page).liveProgress()
        return if (sample.isNaN()) lastPageProgress else sample.coerceIn(0f, 1f).also { lastPageProgress = it }
    }

    fun q(): Float = when (pace) {
        is DockPace.Page -> if (from == null && fromGeom == toGeom) 1f else pageProgress()
        is DockPace.Tap -> when {
            from == null -> 1f
            tapClock != null -> if (tapClock.serial == tapSerial) FocalClock.fadeFast(tapClock.elapsed) else 1f
            else -> ownAlpha.value
        }
    }

    fun g(): Float = when (pace) {
        is DockPace.Page -> if (fromGeom == toGeom) 1f else pageProgress()
        is DockPace.Tap -> if (fromGeom == toGeom) 1f else ownGeom.value
    }

    fun currentFirst(): Float = lerp(fromGeom.first, toGeom.first, g()).coerceAtLeast(0f)
    fun currentSecond(): Float = lerp(fromGeom.second, toGeom.second, g()).coerceAtLeast(0f)

    fun currentGeometry(): DockGeometryTargets = DockGeometryTargets(currentFirst(), currentSecond())

    /** The faces still leaving (kept until both alpha and geometry settle so shrinking circles never pop out). */
    val settled = derivedStateOf { q() >= 1f && g() >= 1f }
    fun leaving(): DockFaces? = if (settled.value) null else from

    /** The faces this swap leaves, settled or not (drawn at nothing once it has settled). */
    fun left(): DockFaces? = from

    /** What leaves if the dock changes again now: a swap caught before its midpoint keeps what was leaving. */
    fun outgoing(): DockFaces {
        val midpoint = if (pace is DockPace.Page) PAGE_CROSSOVER else HALF
        return if (from != null && q() < midpoint) from else shown
    }
}

private class DockSwapMemory(var swap: DockSwap, var claimedTap: Int = 0)

@Composable
private fun rememberDockSwap(faces: DockFaces, pace: DockPace, targets: DockGeometryTargets): DockSwap {
    val memory = remember {
        DockSwapMemory(DockSwap(faces, null, DockPace.Tap(), null, 0, targets, targets))
    }
    val swap = remember(faces, targets, pace) {
        val last = memory.swap
        if (last.shown == faces && last.toGeom == targets && (last.pace == pace || last.settled.value)) last
        else if (last.shown == faces && last.pace == pace && last.settled.value) DockSwap(faces, null, pace, null, 0, targets, targets)
        else Snapshot.withoutReadObservation {
            val from = last.outgoing().takeIf { it != faces }
            val drawn = last.currentGeometry()
            val tapVelocity = if (last.ownGeom.isRunning) {
                geomVelocity(last.fromGeom, last.toGeom, drawn, targets, last.ownGeom.velocity)
            } else 0f
            val tapClock = (pace as? DockPace.Tap)?.clock
            val fresh = tapClock?.takeIf { it.elapsed <= FocalTiming.TRIGGER_LEAD_MS && it.serial != memory.claimedTap }
            DockSwap(faces, from, pace, fresh, fresh?.serial ?: 0, drawn, targets, tapVelocity)
        }
    }
    SideEffect {
        memory.swap = swap
        swap.claimedTapSerial?.let { memory.claimedTap = it }
    }
    if (swap.runsOwnAlpha || swap.runsOwnGeom) LaunchedEffect(swap) {
        coroutineScope {
            if (swap.runsOwnAlpha) launch { swap.ownAlpha.animateTo(1f, FocalMotion.fromTrigger(FocalMotion.fadeFast())) }
            if (swap.runsOwnGeom) swap.ownGeom.animateTo(
                1f,
                FocalMotion.fromTrigger(FocalMotion.move()),
                initialVelocity = swap.initialGeomVelocity,
            )
        }
    }
    return swap
}

private fun geomVelocity(
    prevFrom: DockGeometryTargets,
    prevTo: DockGeometryTargets,
    drawn: DockGeometryTargets,
    nextTo: DockGeometryTargets,
    prevNormalizedVelocity: Float,
): Float {
    val deltas = listOf(
        (prevTo.first - prevFrom.first) to (nextTo.first - drawn.first),
        (prevTo.second - prevFrom.second) to (nextTo.second - drawn.second),
    )
    val dominant = deltas.maxByOrNull { abs(it.second) } ?: return 0f
    if (abs(dominant.second) < 1f) return 0f
    val pxVelocity = dominant.first * prevNormalizedVelocity
    return (pxVelocity / dominant.second).coerceIn(-MAX_GEOM_VELOCITY, MAX_GEOM_VELOCITY)
}

/**
 * Draws the slot's face during a swap (spec 4.3, Q7): incoming container beneath at full alpha, outgoing on top so
 * coverage never dips, and contents crossfade cleanly without double text.
 */
@Composable
private fun <F : Any> SlotSwap(
    shown: F,
    leaving: F?,
    q: () -> Float,
    pagePace: Boolean,
    modifier: Modifier,
    face: @Composable BoxScope.(F, containerAlpha: () -> Float, contentAlpha: () -> Float) -> Unit,
) {
    val outgoing = leaving?.takeIf { it != shown }
    Box(modifier, contentAlignment = Alignment.Center) {
        key(shown) {
            face(
                shown,
                { 1f },
                {
                    if (outgoing == null) 1f
                    else if (pagePace) smoothstep(PAGE_IN_CONTENT_FROM, PAGE_IN_CONTENT_TO, q())
                    else smoothstep(HALF, 1f, q())
                },
            )
        }
        outgoing?.let { old ->
            key(old) {
                face(
                    old,
                    {
                        if (pagePace) 1f - smoothstep(PAGE_OUT_CONTAINER_FROM, PAGE_OUT_CONTAINER_TO, q())
                        else 1f - q()
                    },
                    {
                        if (pagePace) 1f - smoothstep(0f, PAGE_CROSSOVER, q())
                        else 1f - smoothstep(0f, HALF, q())
                    },
                )
            }
        }
    }
}

private const val HALF = 0.5f
private const val PAGE_CROSSOVER = 0.40f
private const val PAGE_IN_CONTENT_FROM = 0.40f
private const val PAGE_IN_CONTENT_TO = 0.85f
private const val PAGE_OUT_CONTAINER_FROM = 0.10f
private const val PAGE_OUT_CONTAINER_TO = 0.70f
private const val EMERGING_GLYPH_FROM = 0.6f
private const val MIN_DRAW_PX = 0.5f
private const val FULL_FILL = 0.999f
private const val MAX_GEOM_VELOCITY = 12f

/** A container that is not drawn: another control's fill stands in for it (see PillSlot). */
private val NoContainer: () -> Float = { 0f }

/** Search, Pause, Play circles and the Resume pill: `s1` on paper, glass on the material. */
@Composable
private fun BoxScope.TonalContainer(dress: Dress, alpha: () -> Float) {
    val colors = FocalTheme.colors
    val glassHighlightCache = remember { GlassHighlightCache() }
    Box(
        Modifier.matchParentSize().drawBehind {
            val a = alpha()
            if (a <= 0f || size.width <= MIN_DRAW_PX || size.height <= MIN_DRAW_PX) return@drawBehind
            val corner = CornerRadius(minOf(size.width, size.height) / 2f)
            if (dress == Dress.Paper) {
                drawRoundRect(colors.s1, cornerRadius = corner, alpha = a)
            } else {
                drawRoundRect(Scene.glass, cornerRadius = corner, alpha = a)
                val highlight = glassHighlightCache.highlight(size, DockMetrics.ring.toPx())
                drawPath(highlight, Scene.glassHighlight, alpha = a)
            }
        },
    )
}

private class GlassHighlightCache {
    private var lastSize = Size.Unspecified
    private var lastRing = Float.NaN
    private val cached = Path()
    private val shape = Path()
    private val shifted = Path()

    fun highlight(size: Size, ring: Float): Path {
        if (lastSize == size && lastRing == ring) return cached
        lastSize = size
        lastRing = ring
        val radius = minOf(size.width, size.height) / 2f
        val rect = RoundRect(0f, 0f, size.width, size.height, radius, radius)
        shape.rewind()
        shape.addRoundRect(rect)
        shifted.rewind()
        shifted.addRoundRect(rect)
        shifted.translate(Offset(0f, ring))
        cached.rewind()
        cached.op(shape, shifted, PathOperation.Difference)
        return cached
    }
}

/**
 * Record, Finish and Copy: `inv` on paper; the material's white gradient with ring and shadow (spec 2.2). [onPaper]:
 * the material's pill leaving over paper, its gradient only.
 */
@Composable
private fun BoxScope.PrimaryContainer(dress: Dress, alpha: () -> Float, onPaper: Boolean = false) {
    val colors = FocalTheme.colors
    val density = LocalDensity.current
    val ring = with(density) { DockMetrics.ring.toPx() }
    val shadow = remember(density) {
        with(density) {
            actionShadowPaint(DockMetrics.actionShadowY.toPx(), DockMetrics.actionShadowSigma.toPx(), Scene.actionShadow)
        }
    }
    val gradient = remember { Brush.verticalGradient(listOf(Scene.actionTop, Scene.actionBottom)) }
    Box(
        Modifier.matchParentSize().drawBehind {
            val a = alpha()
            if (a <= 0f || size.width <= MIN_DRAW_PX || size.height <= MIN_DRAW_PX) return@drawBehind
            val radius = minOf(size.width, size.height) / 2f
            val corner = CornerRadius(radius)
            if (dress == Dress.Paper) {
                drawRoundRect(colors.inv, cornerRadius = corner, alpha = a)
            } else if (onPaper) {
                // The material's white pill over paper (T7): its shadow and ring belong to the material.
                drawRoundRect(gradient, cornerRadius = corner, alpha = a)
            } else {
                if (shadow != null) {
                    shadow.alpha = (Scene.actionShadow.alpha * a * 255).roundToInt()
                    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, shadow) }
                }
                drawRoundRect(
                    Scene.actionRing, topLeft = Offset(-ring, -ring),
                    size = Size(size.width + 2 * ring, size.height + 2 * ring),
                    cornerRadius = CornerRadius(radius + ring), alpha = a,
                )
                drawRoundRect(gradient, cornerRadius = corner, alpha = a)
            }
        },
    )
}

/**
 * A soft drop shadow drawn with the platform's shadow layer (API 28+ draws it on hardware canvases for any shape).
 * Below API 28 the pill has no shadow: it is decorative (spec 9).
 */
internal fun actionShadowPaint(offsetY: Float, sigma: Float, color: Color): Paint? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.TRANSPARENT
        setShadowLayer(blurRadius(sigma), 0f, offsetY, color.copy(alpha = 1f).toArgb())
    }
}

/** The 1 dp inner top highlight on glass: the shape minus itself moved down by [width]. */
internal fun glassHighlight(outline: Outline, width: Float): Path {
    val shape = Path().apply { addOutline(outline) }
    val shifted = Path().apply { addOutline(outline); translate(Offset(0f, width)) }
    return Path().apply { op(shape, shifted, PathOperation.Difference) }
}

/** Where the slot sits in the window, for the dress wipe. */
private class SlotPlacement { var coordinates: LayoutCoordinates? = null }

/**
 * Draws [content] in both dresses split at the material's bounds (window pixels from [material]): paper outside them,
 * the material inside them, glyphs and labels included (`dockDress`, spec 2.8.6). On a bottom dock that is its bottom
 * edge passing over the row; on the landscape rail the clip's end edge and bottom edge both cross it. Without bounds
 * only the paper dress exists. The slot and its paper dress keep their place in the composition whether or not
 * bounds exist, so the slot's bounds animation and the paper content's fade-through carry on when the material
 * arrives behind the dock (T1′e: Pause grows from 0 while Record's left edge moves 24 → 92, as the scene registers
 * its bounds).
 */
@Composable
private fun DressWipe(material: (() -> Rect)?, modifier: Modifier, content: @Composable BoxScope.(Dress) -> Unit) {
    val placement = remember { SlotPlacement() }
    // The bounds in the slot's own pixels, kept within reach of it (the pill's shadow and ring draw outside it).
    fun DrawScope.local(bounds: Rect): Rect {
        val origin = placement.coordinates?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero
        val reach = 2 * size.maxDimension
        fun x(v: Float) = (v - origin.x).coerceIn(-reach, reach)
        fun y(v: Float) = (v - origin.y).coerceIn(-reach, reach)
        return Rect(x(bounds.left), y(bounds.top), x(bounds.right), y(bounds.bottom))
    }
    Box(modifier.onPlaced { placement.coordinates = it }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.drawWithContent {
                if (material == null) {
                    drawContent()
                    return@drawWithContent
                }
                val r = local(material())
                if (r.isEmpty) drawContent()
                else clipRect(r.left, r.top, r.right, r.bottom, ClipOp.Difference) { this@drawWithContent.drawContent() }
            },
            contentAlignment = Alignment.Center,
        ) { content(Dress.Paper) }
        if (material != null) Box(
            Modifier.matchParentSize().drawWithContent {
                val r = local(material())
                if (!r.isEmpty) clipRect(r.left, r.top, r.right, r.bottom) { this@drawWithContent.drawContent() }
            },
            contentAlignment = Alignment.Center,
        ) { content(Dress.Material) }
    }
}
