package app.focal.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import app.focal.R
import app.focal.ui.components.FocalIcon
import app.focal.ui.components.Glyph
import app.focal.ui.components.focusRing
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.FocalWeight
import app.focal.ui.design.Grid
import app.focal.ui.state.ThemeMode

/** The segmented control (spec 4.17). */
object SegmentMetrics {
    val track = Grid.x48
    /** The thumb is inset this far inside the track (40 dp tall). */
    val inset = Grid.x4
    /**
     * The room a label needs either side inside its segment: clear of the thumb's rounded ends, half the thumb's
     * height (20 dp). A label that would come closer turns the control into stacked radio rows.
     */
    val labelRoom = (track - inset * 2) / 2
    /** From this font scale a label without that room turns the control into stacked radio rows (spec 4.17). */
    const val STACK_FONT = 1.8f
}

private val themes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)

/**
 * Theme: System | Light | Dark (spec 4.17, T22). A 48 dp `s1` track, three equal segments and an `inv` thumb that
 * slides on `move`; labels are `ink`, and `onInv` wherever the thumb is under them (drawn twice, clipped to the
 * thumb, so the colours are exact at every frame). Applies at once. From font scale 1.8, if a label would come
 * closer to its thumb's rounded ends than half the thumb's height, it becomes three stacked 48 dp radio rows (so it
 * never sits cramped in the pill, let alone truncates). A radio group for TalkBack.
 */
@Composable
fun ThemeControl(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark))
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = FocalType.meta.copy(fontWeight = FocalWeight.Medium)
    val widest = remember(labels, density) { labels.maxOf { measurer.measure(it, style).size.width } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A segment is a third of the track inside its inset: the thumb's width.
        val segment = (constraints.maxWidth - 2 * with(density) { SegmentMetrics.inset.toPx() }) / themes.size
        val stacked = density.fontScale >= SegmentMetrics.STACK_FONT &&
            widest + 2 * with(density) { SegmentMetrics.labelRoom.toPx() } > segment
        if (stacked) RadioRows(labels, selected, onSelect, Modifier) else Segments(labels, style, selected, onSelect, Modifier)
    }
}

@Composable
private fun Segments(labels: List<String>, style: androidx.compose.ui.text.TextStyle, selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier) {
    val colors = FocalTheme.colors
    val index = themes.indexOf(selected).coerceAtLeast(0)
    val thumb = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) { thumb.animateTo(index.toFloat(), FocalMotion.move()) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The thumb's rect in the track, in pixels, for the label clip and its own fill: the three equal segments divide
    // the track inside its 4 dp inset, so the thumb is (364 - 8) / 3 = 118.7 dp wide on a phone.
    fun thumbRect(width: Float, height: Float, inset: Float): Pair<Offset, Size> {
        val segment = (width - 2 * inset) / themes.size
        val position = if (rtl) themes.size - 1 - thumb.value else thumb.value
        return Offset(inset + segment * position, inset) to Size(segment, height - 2 * inset)
    }
    val labelRow: @Composable (androidx.compose.ui.graphics.Color, Boolean) -> Unit = { color, interactive ->
        Row(Modifier.fillMaxWidth().padding(horizontal = SegmentMetrics.inset).then(if (interactive) Modifier.selectableGroup() else Modifier)) {
            themes.forEachIndexed { i, mode ->
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = SegmentMetrics.track)
                        .then(
                            if (interactive) Modifier.focusRing(FocalShapes.pill)
                                .selectable(selected = mode == selected, interactionSource = source, indication = null, role = Role.RadioButton) { onSelect(mode) }
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) { Text(labels[i], style = style, color = color, maxLines = 1, textAlign = TextAlign.Center) }
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = SegmentMetrics.track)
            .background(colors.s1, FocalShapes.pill)
            .drawWithContent {
                val (offset, size) = thumbRect(size.width, size.height, SegmentMetrics.inset.toPx())
                drawRoundRect(colors.inv, offset, size, CornerRadius(size.height / 2))
                drawContent()
            },
    ) {
        labelRow(colors.ink, true)
        // The labels again in `onInv`, clipped to the thumb: drawing only, so TalkBack reads each label once.
        Box(
            Modifier.matchParentSize().clearAndSetSemantics { }.drawWithContent {
                val (offset, rect) = thumbRect(size.width, size.height, SegmentMetrics.inset.toPx())
                clipRect(offset.x, offset.y, offset.x + rect.width, offset.y + rect.height) { this@drawWithContent.drawContent() }
            },
        ) { labelRow(colors.onInv, false) }
    }
}

/** The stacked form at large font scales: three 48 dp rows, the selected one marked with a check. */
@Composable
private fun RadioRows(labels: List<String>, selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier) {
    val colors = FocalTheme.colors
    Column(modifier.fillMaxWidth().selectableGroup()) {
        themes.forEachIndexed { i, mode ->
            val source = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = SegmentMetrics.track)
                    .focusRing(FocalShapes.block)
                    .selectable(selected = mode == selected, interactionSource = source, indication = null, role = Role.RadioButton) { onSelect(mode) }
                    .padding(vertical = SegmentMetrics.inset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(labels[i], style = FocalType.field.copy(fontWeight = FocalWeight.Medium), color = colors.ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Grid.x16))
                if (mode == selected) Glyph(FocalIcon.Check, colors.ink)
            }
        }
    }
}
