package app.focal.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import kotlin.math.min

/** A day label in the list (spec 4.12.1): `section` in `muted`, a heading for TalkBack. */
@Composable
fun DayLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = FocalType.section, color = FocalTheme.colors.recede, modifier = modifier.semantics { heading() })
}

/**
 * Where pinned labels sit, relative to the status bar's bottom (spec 4.1.1, 4.12.1, 2.6). The ref values (band solid
 * to status + 40, its end at + 56, labels 36 dp apart) are the 20 dp label line plus fixed room, so they follow the
 * label as the font scale grows it: the band always covers the pinned label and 8 dp under it.
 */
object StickyLabelMetrics {
    /** The pin line: status + 12 dp. */
    val pinLine = FocalGrid.x12
    /** The pin band stays solid this far under the pinned label. */
    val bandClearance = FocalGrid.x8
    /** The next label pushes the pinned one up, keeping this much room between them. */
    val pushGap = FocalGrid.x16

    /** The pin band's solid end under the status bar: the pinned label's bottom + 8 dp (ref 40 at 1.0x). */
    fun bandSolid(density: Density): Float = with(density) {
        pinLine.toPx() + FocalType.section.lineHeight.toPx() + bandClearance.toPx()
    }

    /** The band's end (ref 56): labels above it are drawn by the overlay, above the fades. */
    fun bandEnd(density: Density): Float = bandSolid(density) + with(density) { FocalLayout.topFade.toPx() }

    /** How far apart the next label keeps the pinned one (ref 36). */
    fun pushDistance(density: Density): Float = with(density) { FocalType.section.lineHeight.toPx() + pushGap.toPx() }
}

/**
 * The sticky day labels (spec 4.12.1, G5). Labels stay ordinary list items; once a label's top rises above the pin
 * band's end, the overlay draws it at max(its list position, the pin line) above the top fades and its list copy is
 * hidden, so it is never covered on its way to the pin; it stays pinned while its section scrolls under it, the next
 * label pushes it up, and it slides under the status edge. Fed by [LazyListState.layoutInfo] in the draw phase:
 * scrolling never recomposes. [sectionOf] maps any item key to its section's label key (a label maps to itself;
 * items before the first label, such as the stage, to null). The list and the overlay share the window's top edge.
 */
@Stable
class StickyLabels(val listState: LazyListState, private val sectionOf: (key: Any) -> Any?) {
    internal var statusPx = 0f
    internal var pinPx = 0f
    internal var bandPx = 0f
    internal var pushPx = 0f

    /** True while the overlay draws the label with this [key] (its list copy hides). */
    fun covered(key: Any): Boolean = placements().any { it.key == key }

    internal class Placement(val key: Any, val y: Float)

    /** Labels the overlay draws, top to bottom, at their window y. */
    internal fun placements(): List<Placement> {
        val info = listState.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return emptyList()
        val shift = -info.viewportStartOffset.toFloat()
        val labels = ArrayList<Pair<Any, Float>>()
        // The section scrolled under the top keeps its label pinned even once the label item itself is gone.
        sectionOf(items.first().key)?.takeIf { it != items.first().key }?.let { labels += it to Float.NEGATIVE_INFINITY }
        for (item in items) if (sectionOf(item.key) == item.key) labels += item.key to shift + item.offset
        val result = ArrayList<Placement>(2)
        for ((index, label) in labels.withIndex()) {
            val (key, y) = label
            if (y >= bandPx) break
            val next = labels.getOrNull(index + 1)?.second
            val pinned = maxOf(y, pinPx)
            val pushed = if (next != null) min(pinned, next - pushPx) else pinned
            // A label pushed entirely under the status edge is gone.
            if (pushed + pushPx > statusPx) result += Placement(key, pushed)
        }
        return result
    }
}

/** The labels of [listState]; [sectionOf] may change with the list's content and is always read as it is now. */
@Composable
fun rememberStickyLabels(listState: LazyListState, sectionOf: (key: Any) -> Any?): StickyLabels {
    val current by rememberUpdatedState(sectionOf)
    return remember(listState) { StickyLabels(listState) { key -> current(key) } }
}

/** Hides a label's list copy while the overlay draws it. */
fun Modifier.stickyLabelSource(labels: StickyLabels, key: Any): Modifier =
    graphicsLayer { alpha = if (labels.covered(key)) 0f else 1f }

/** Draws the pinned labels; place it above the library's top fade. Masked under the status edge like the fade. */
@Composable
fun StickyLabelOverlay(labels: StickyLabels, text: (key: Any) -> String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = FocalType.section.copy(color = FocalTheme.colors.recede)
    val status = WindowInsets.statusBars
    val density = LocalDensity.current
    Spacer(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val top = status.getTop(density).toFloat()
                labels.statusPx = top
                labels.pinPx = top + StickyLabelMetrics.pinLine.toPx()
                labels.bandPx = top + StickyLabelMetrics.bandEnd(this)
                labels.pushPx = StickyLabelMetrics.pushDistance(this)
                val column = min(size.width - 2 * FocalLayout.gutter.toPx(), FocalLayout.contentMaxWidth.toPx())
                val start = (size.width - column) / 2
                for (placement in labels.placements()) {
                    val layout = measurer.measure(text(placement.key), style)
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - start - layout.size.width else start
                    drawText(layout, topLeft = Offset(x, placement.y))
                }
                // The status edge's mask: nothing above the status bar, a 16 dp ramp under it.
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = top, endY = top + FocalLayout.topFade.toPx()),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}
