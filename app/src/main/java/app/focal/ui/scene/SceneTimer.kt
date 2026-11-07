package app.focal.ui.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focal.ui.components.Mark
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalType
import app.focal.ui.design.LocalWindowLayout
import app.focal.ui.library.Lengths
import kotlin.math.min
import kotlin.math.roundToInt

/** The live timer's sizes (spec 4.21.3, 4.22.7): one thin tabular face at two sizes, fixed while a recording runs. */
object TimerMetrics {
    /** The scene: the largest size in 56-76 sp (step 2) at which "0:00:00" fits the 364 dp column. */
    const val SCENE_MAX_SP = 76f
    const val SCENE_MIN_SP = 56f
    /** The stage: the largest size in 32-48 sp at which "0:00:00" fits between the wave and the card's inset. */
    const val STAGE_MAX_SP = 48f
    const val STAGE_MIN_SP = 32f
    const val STEP_SP = 2f
    const val WIDEST = "0:00:00"
    private val templates = listOf("0:00", "00:00", "0:00:00")

    /** The stage's text column on the reference frame (x 44-368), less the wave's 72 dp and the 16 dp gap. */
    private val stageColumn = 324.dp

    /** [FocalType.sceneTimer] at [sizeSp], its line box scaled with it (84 dp at 76 sp). */
    fun style(sizeSp: Float): TextStyle = FocalType.sceneTimer.let { style ->
        style.copy(fontSize = sizeSp.sp, lineHeight = (style.lineHeight.value * sizeSp / SCENE_MAX_SP).sp)
    }

    /** Measures both sizes and the reserved widths of the three formats at each (spec R5). */
    fun measure(measurer: TextMeasurer, density: Density, sceneColumn: Float): TimerStyles = with(density) {
        val stageRoom = (stageColumn - Mark.width(StageLiveMetrics.wave) - StageLiveMetrics.gap).toPx()
        val scene = fit(measurer, density, sceneColumn, SCENE_MAX_SP, SCENE_MIN_SP)
        val stage = fit(measurer, density, stageRoom, STAGE_MAX_SP, STAGE_MIN_SP)
        TimerStyles(
            scene = scene, stage = stage,
            sceneLine = scene.lineHeight.toPx(), stageLine = stage.lineHeight.toPx(),
            sceneWidths = templates.map { measurer.measure(it, scene, density = density).size.width.toFloat() },
            stageWidths = templates.map { measurer.measure(it, stage, density = density).size.width.toFloat() },
        )
    }

    private fun fit(measurer: TextMeasurer, density: Density, room: Float, max: Float, min: Float): TextStyle {
        var size = max
        while (size > min && measurer.measure(WIDEST, style(size), density = density).size.width > room) size -= STEP_SP
        return style(size)
    }

    /** Which reserved template a time uses: under 10 min, under an hour, from an hour. */
    fun template(seconds: Long): Int = templates.indexOf(Lengths.timerTemplate(seconds))
}

/** The timer's two sizes, measured once per density and font scale. Widths and lines in pixels. */
@Immutable
class TimerStyles(
    val scene: TextStyle,
    val stage: TextStyle,
    val sceneLine: Float,
    val stageLine: Float,
    private val sceneWidths: List<Float>,
    private val stageWidths: List<Float>,
) {
    /** The stage's timer is the scene's at this scale: the morph lays it out once and scales it. */
    val stageScale: Float get() = stage.fontSize.value / scene.fontSize.value

    fun sceneWidth(seconds: Long): Float = sceneWidths[TimerMetrics.template(seconds)]
    fun stageWidth(seconds: Long): Float = stageWidths[TimerMetrics.template(seconds)]
}

/**
 * The timer styles for the scene's column: the window's, or the detail pane's beside a hinge that splits two panes
 * (where the scene keeps to that side). The stage and the scene measure the same, so the timer morphs between them.
 */
@Composable
fun rememberSceneTimerStyles(): TimerStyles {
    val window = LocalWindowInfo.current.containerSize.width.toFloat()
    val start = with(LocalDensity.current) { LocalWindowLayout.current.windowStart.toPx() }
    return rememberTimerStyles(window - start)
}

/** The timer styles for the current density and font scale, the scene's sized to the text column of [windowWidth]. */
@Composable
fun rememberTimerStyles(windowWidth: Float): TimerStyles {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(density.density, density.fontScale, windowWidth) {
        val column = min(windowWidth - 2 * with(density) { FocalLayout.gutter.toPx() }, with(density) { FocalLayout.contentMaxWidth.toPx() })
        TimerMetrics.measure(measurer, density, column)
    }
}

/**
 * The timer's text in its reserved box (spec 4.21.3): the box is the width of the current format's template
 * ("0:00", "00:00", "0:00:00") and the text is centred in it, so ticking never shifts a glyph; at a format change
 * the box widens on `move`. [width] gives the reserved width for a time; [color] is read in the draw phase.
 */
@Composable
fun TimerText(seconds: Long, style: TextStyle, width: (Long) -> Float, color: ColorProducer, modifier: Modifier = Modifier) {
    val target = width(seconds)
    val box = remember { Animatable(target) }
    LaunchedEffect(target) { if (box.value != target) box.animateTo(target, FocalMotion.move()) }
    Box(
        modifier.layout { measurable, constraints ->
            val w = box.value.roundToInt()
            val placeable = measurable.measure(Constraints(minWidth = 0, maxWidth = Constraints.Infinity, maxHeight = constraints.maxHeight))
            layout(w, placeable.height) { placeable.place((w - placeable.width) / 2, 0) }
        },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(Lengths.timer(seconds), style = style.copy(textAlign = TextAlign.Center), color = color, maxLines = 1, softWrap = false)
    }
}
