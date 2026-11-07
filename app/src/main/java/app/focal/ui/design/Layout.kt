package app.focal.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * The 4 dp grid (spec 2.5). These are the only spacing steps; anything else is a derived optical offset that the
 * spec names, defined beside the component that uses it.
 */
object FocalGrid {
    val x2 = 2.dp
    val x4 = 4.dp
    val x8 = 8.dp
    val x12 = 12.dp
    val x16 = 16.dp
    val x20 = 20.dp
    val x24 = 24.dp
    val x32 = 32.dp
    val x36 = 36.dp
    val x40 = 40.dp
    val x48 = 48.dp
    val x56 = 56.dp
    val x64 = 64.dp
}

/** Layout constants shared by every screen (spec 2.5). Positions are built from insets, never from ref coordinates. */
object FocalLayout {
    /** Text, rows and every bottom element start and end this far from the window edges. */
    val gutter = FocalGrid.x24
    /** Icon buttons sit this far from the screen edge, so a 24 dp glyph's edge lands on the gutter. */
    val iconEdge = FocalGrid.x12
    /** The content column and the bottom column are capped at this width and centred on wider windows. */
    val contentMaxWidth = 600.dp
    /** The minimum touch target everywhere (words are the one exception). */
    val target = FocalGrid.x48
    val glyph = FocalGrid.x24
    /** The note, settings and licences top bar, below the status bar. */
    val topBar = FocalGrid.x64
    /** Stacked bottom elements sit this far apart; the lowest field rides this far above the keyboard. */
    val bottomGap = FocalGrid.x8
    /** The dock's bottom edge sits this far above the navigation inset. */
    val dockInset = FocalGrid.x16
    /** Scrolling content reserves the bottom column's height plus this, so the last line clears the fade. */
    val contentReserve = FocalGrid.x64
    /** Paper fades replace bars and dividers (spec 2.6). */
    val topFade = FocalGrid.x16
    val bottomFade = FocalGrid.x64
    /** Above search results and find rows the bottom fade is shorter. */
    val keyboardFade = FocalGrid.x48
}

/**
 * A fold or hinge that runs from the window's top to its bottom and separates its two sides (androidx.window's
 * `FoldingFeature`, vertical and separating: a foldable in book posture, or two screens joined by a hinge), as the
 * distances of its two edges from the window's start edge. A horizontal fold (tabletop) is never one: the window's
 * size alone lays it out, as on a phone.
 */
@Immutable
data class Hinge(val start: Dp, val end: Dp) {
    companion object {
        /** The hinge whose edges are [left] and [right] in a window [width] wide, from its start edge. */
        fun of(left: Dp, right: Dp, width: Dp, rtl: Boolean): Hinge = if (rtl) Hinge(width - right, width - left) else Hinge(left, right)
    }
}

/**
 * How the window is laid out (spec 5.14), from its size. A short window (a phone in landscape, height < 480 dp) puts
 * the dock in a 96 dp rail at the end edge, the top bar at 48 dp and the recording scene in two columns. From 840 dp
 * wide (and not short) the library and a note sit side by side: the list pane takes the window's first 412 dp, or, on
 * a [hinge] that leaves both sides at least [MIN_PANE], everything up to the hinge, with the note after it, so neither
 * pane lies across it. What takes the whole window (settings, the licences, the recording scene and its dock) then
 * keeps to the detail pane's side ([windowStart]), so no text or control lies under the hinge either. Every content
 * column is at most 600 dp, centred.
 */
@Immutable
data class WindowLayout(val width: Dp, val height: Dp, val hinge: Hinge? = null) {
    /** The dock is a rail at the end edge; pages with a dock end beside it. */
    val rail: Boolean get() = height < RAIL_BELOW
    /** The library list and the note side by side: the list pane at the start, the note in the rest. */
    val twoPane: Boolean get() = !rail && width >= TWO_PANE_FROM
    /** The note, settings and licences top bar: 48 dp in a short window. */
    val topBar: Dp get() = if (rail) FocalGrid.x48 else FocalLayout.topBar

    /** The hinge the two panes split at, if they do and it leaves each of them room. */
    val split: Hinge?
        get() = hinge?.takeIf { twoPane && it.start <= it.end && it.start >= MIN_PANE && width - it.end >= MIN_PANE }

    /**
     * Where a page that takes the whole window (settings, the licences, the recording scene) starts, from the window's
     * start edge: after a hinge that splits the panes (the detail pane's side; a column centred in the whole window
     * would lie across the hinge), else at the edge.
     */
    val windowStart: Dp get() = split?.end ?: 0.dp

    /** The list pane's width from the window's start edge (two panes). */
    val listPane: Dp get() = split?.start ?: LIST_PANE

    /** Where the detail pane starts, from the window's start edge (two panes): after the hinge, or where the list ends. */
    val detailStart: Dp get() = split?.end ?: LIST_PANE

    /**
     * The list pane's span ([detail] 0) or the detail pane's (1), in pixels from the window's left edge, inside the
     * page [area] (the side insets, the rail); between them while the dock crosses. One pane: the area itself.
     */
    fun paneSpan(widthPx: Float, area: ClosedFloatingPointRange<Float>, detail: Float, rtl: Boolean, density: Density): ClosedFloatingPointRange<Float> {
        if (!twoPane) return area
        val list = with(density) { listPane.toPx() }
        val after = with(density) { detailStart.toPx() }
        val listSpan = if (rtl) (widthPx - list)..area.endInclusive else area.start..list
        val detailSpan = if (rtl) area.start..(widthPx - after) else after..area.endInclusive
        return lerp(listSpan.start, detailSpan.start, detail)..lerp(listSpan.endInclusive, detailSpan.endInclusive, detail)
    }

    companion object {
        val RAIL_BELOW = 480.dp
        val TWO_PANE_FROM = 840.dp
        /** The rail's width; its controls are centred in it, 20 dp from either side. */
        val railWidth = 96.dp
        /** The library pane in two panes with no hinge: the reference phone's width, so it is laid out as designed. */
        val LIST_PANE = 412.dp
        /** A hinge splits the panes only if it leaves both at least this wide: the narrowest phone laid out for. */
        val MIN_PANE = 360.dp
        /** The design's reference frame (412 x 915 dp), until the shell has measured the window. */
        val Reference = WindowLayout(412.dp, 915.dp)
    }
}

/** The window's layout, provided by the shell from the window's size. */
val LocalWindowLayout = compositionLocalOf { WindowLayout.Reference }

/**
 * The content column's side inset in an area [width] wide (spec 2.5, 5.14): the 24 dp gutter, plus whatever centres
 * a column capped at 600 dp.
 */
fun FocalLayout.columnInset(width: Dp): Dp = gutter + ((width - gutter * 2 - contentMaxWidth) / 2).coerceAtLeast(0.dp)

/** [columnInset] in pixels. */
fun FocalLayout.columnInset(widthPx: Float, density: Density): Float = with(density) {
    gutter.toPx() + ((widthPx - 2 * gutter.toPx() - contentMaxWidth.toPx()) / 2).coerceAtLeast(0f)
}
