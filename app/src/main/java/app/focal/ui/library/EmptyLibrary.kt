package app.focal.ui.library

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.focal.R
import app.focal.ui.components.DockMetrics
import app.focal.ui.components.Mark
import app.focal.ui.components.NineBars
import app.focal.ui.components.WaveDress
import app.focal.ui.components.WaveMode
import app.focal.ui.components.markBox
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.Grid
import app.focal.ui.shell.LocalShell
import app.focal.ui.shell.SceneEntry
import kotlin.math.roundToInt

/** T1′e: the invitation's alpha while the material arrives at [wake] (H3). */
private fun invitationAlpha(waking: Boolean, wake: Float): Float = if (waking) (1 - wake) * (1 - wake) else 1f

/** The empty library's composition (spec 4.22.5). */
object EmptyMetrics {
    /** The instrument at rest: the scene's start hero at 112/176 (80 dp from font scale 1.5). */
    val instrument = 112.dp
    val instrumentLarge = 80.dp
    const val LARGE_FONT = 1.5f
    /** The headline sits this far under the instrument's box, the second line this far under the headline. */
    val headlineGap = Grid.x36
    val bodyGap = Grid.x12
    /** The headline's measure: two balanced lines at 1.0×. */
    val headlineWidth = 300.dp
    /**
     * A derived optical offset (spec 4.22.5): the visible group (the centre bar's ink to the second line's bottom) is
     * centred this far above the centre of the free paper between the status bar and the dock.
     */
    val opticalLift = 6.7.dp
}

/**
 * The empty library (spec 4.22.5): no card. The nine bars at rest in ink on paper (42 % of the profile, still,
 * never the full mark), "Speak, and it's written down." and the two teaching lines, centred as one group on the free
 * paper above the dock, 6.7 dp above its centre. Nothing responds to touch here but the settings swipe; TalkBack
 * reads it as one node with the "Open settings" action. It fills the window.
 */
@Composable
fun EmptyLibrary(openSettings: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FocalTheme.colors
    val density = LocalDensity.current
    val instrument = if (density.fontScale >= EmptyMetrics.LARGE_FONT) EmptyMetrics.instrumentLarge else EmptyMetrics.instrument
    val description = stringResource(R.string.a11y_stage_empty)
    val settingsLabel = stringResource(R.string.a11y_open_settings)
    val status = WindowInsets.statusBars
    val navigation = WindowInsets.navigationBars
    val live = LocalShell.current.live
    // Record wakes the instrument in place (T1′e): the scene draws the bars while it grows out of their box, and the
    // invitation leaves at (1 - a)^2 of the material's alpha a, gone before the material is opaque (H3).
    val waking = { live.spatial && live.entry == SceneEntry.RecordEmpty && live.expansion.value > 0f }
    Layout(
        contents = listOf(
            {
                NineBars(
                    WaveMode.Rest, WaveDress.paper(colors),
                    Modifier
                        .markBox(instrument)
                        .onGloballyPositioned { coordinates ->
                            val root = coordinates.findRootCoordinates().size
                            val box = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
                            live.restBounds = live.unscaled(box, Offset(root.width / 2f, root.height / 2f))
                        }
                        .graphicsLayer { alpha = if (waking()) 0f else 1f },
                )
                DisposableEffect(live) { onDispose { live.restBounds = null } }
            },
            {
                Text(
                    stringResource(R.string.empty_title), style = FocalType.title.copy(lineBreak = LineBreak.Heading),
                    color = colors.ink, textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { alpha = invitationAlpha(waking(), live.wake.value) },
                )
            },
            {
                // Each of its two lines wraps balanced at large font sizes ("Text appears after / each pause.").
                Text(
                    stringResource(R.string.empty_body), style = FocalType.field.copy(lineBreak = LineBreak.Heading),
                    color = colors.recede, textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer { alpha = invitationAlpha(waking(), live.wake.value) },
                )
            },
        ),
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            customActions = listOf(CustomAccessibilityAction(settingsLabel) { openSettings(); true })
        },
    ) { (bars, headlines, bodies), constraints ->
        val width = constraints.maxWidth
        val column = minOf(width - 2 * FocalLayout.gutter.roundToPx(), FocalLayout.contentMaxWidth.roundToPx())
        val mark = bars.first().measure(Constraints())
        val headline = headlines.first().measure(Constraints(maxWidth = minOf(column, EmptyMetrics.headlineWidth.roundToPx())))
        val body = bodies.first().measure(Constraints(maxWidth = column))
        val headlineTop = mark.height + EmptyMetrics.headlineGap.roundToPx()
        val bodyTop = headlineTop + headline.height + EmptyMetrics.bodyGap.roundToPx()
        val groupBottom = bodyTop + body.height
        // The centre bar's ink at the resting level is centred in the box: that is where the visible group starts.
        val inkTop = (mark.height * (1 - Mark.REST * Mark.height(Mark.CENTRE) / Mark.BOX_HEIGHT) / 2).roundToInt()
        val freeTop = status.getTop(this)
        // The free paper ends at the dock's top: navigation inset + 16, then the 56 dp dock.
        val freeBottom = constraints.maxHeight - navigation.getBottom(this) - (FocalLayout.dockInset + DockMetrics.height).roundToPx()
        val centre = (freeTop + freeBottom) / 2f - EmptyMetrics.opticalLift.toPx()
        val top = (centre - (inkTop + groupBottom) / 2f).roundToInt().coerceAtLeast(freeTop)
        layout(width, constraints.maxHeight) {
            mark.place((width - mark.width) / 2, top)
            headline.place((width - headline.width) / 2, top + headlineTop)
            body.place((width - body.width) / 2, top + bodyTop)
        }
    }
}
