package app.focal.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.LayoutDirection
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import kotlin.math.min

/** A day label in the list (spec 4.12.1): `section` in `muted`, a heading for TalkBack. */
@Composable
fun DayLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = FocalType.section, color = FocalTheme.colors.recede, modifier = modifier.semantics { heading() })
}

/** Where pinned labels sit, relative to the status bar's bottom (spec 4.1.1, 4.12.1). */
object StickyLabelMetrics {
    /** The pin line: status + 12 dp. */
    val pinLine = Grid.x12
    /** Labels above this line (the pin band's end, status + 56 dp) are drawn by the overlay, above the fades. */
    val bandEnd = Grid.x56
    /** The next label pushes the pinned one up, keeping them this far apart. */
    val pushDistance = Grid.x36
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

@Composable
fun rememberStickyLabels(listState: LazyListState, sectionOf: (key: Any) -> Any?): StickyLabels =
    remember(listState) { StickyLabels(listState, sectionOf) }

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
                labels.bandPx = top + StickyLabelMetrics.bandEnd.toPx()
                labels.pushPx = StickyLabelMetrics.pushDistance.toPx()
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
