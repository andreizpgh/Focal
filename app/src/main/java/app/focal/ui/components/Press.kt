package app.focal.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.LocalFocalColors
import kotlinx.coroutines.launch

/**
 * The press state layer: `press` (ink at 8 % / 10 %) in [shape], fading with `fadeFast`, drawn behind the content.
 * [insetX] / [insetY] move its edges inside the bounds (negative: outside, as the row plates at x 8-404). No ripple
 * anywhere in Focal.
 */
data class PressPlate(val shape: Shape = RectangleShape, val insetX: Dp = 0.dp, val insetY: Dp = 0.dp) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressPlateNode(interactionSource, this)

    companion object : IndicationNodeFactory by PressPlate()
}

private class PressPlateNode(private val source: InteractionSource, private val plate: PressPlate) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                val target = if (presses.isEmpty()) 0f else 1f
                // The draw phase reads the value, so each frame redraws only this node.
                launch { alpha.animateTo(target, FocalMotion.fadeFast()) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val a = alpha.value
        if (a > 0f) {
            val dx = plate.insetX.toPx()
            val dy = plate.insetY.toPx()
            val plateSize = Size(size.width - 2 * dx, size.height - 2 * dy)
            val outline = plate.shape.createOutline(plateSize, layoutDirection, this)
            translate(dx, dy) { drawOutline(outline, currentValueOf(LocalFocalColors).press, alpha = a) }
        }
        drawContent()
    }
}

/** Pressed controls scale to 0.97 on `press` (pills, circles, the stage): no overlay, no ripple. */
fun Modifier.pressScale(source: InteractionSource): Modifier = this then PressScaleElement(source)

private const val PRESSED_SCALE = 0.97f

private data class PressScaleElement(val source: InteractionSource) : ModifierNodeElement<PressScaleNode>() {
    override fun create() = PressScaleNode(source)
    override fun update(node: PressScaleNode) { node.source = source }
    override fun InspectorInfo.inspectableProperties() { name = "pressScale" }
}

private class PressScaleNode(var source: InteractionSource) : Modifier.Node(), LayoutModifierNode {
    private val scale = Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                }
                // Read in the layer block below: each frame updates the layer, never the composition.
                launch { scale.animateTo(if (pressed > 0) PRESSED_SCALE else 1f, FocalMotion.press()) }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { scaleX = scale.value; scaleY = scale.value }
        }
    }
}

/**
 * The keyboard focus ring (spec 4.20): a 2 dp outline, 2 dp outside the control, following its [shape]. Shown only
 * while the control has focus in keyboard or D-pad navigation. Place it before the focusable modifier.
 */
fun Modifier.focusRing(shape: Shape, color: Color? = null): Modifier = this then FocusRingElement(shape, color)

private val RING_WIDTH = 2.dp
private val RING_OFFSET = 2.dp

private data class FocusRingElement(val shape: Shape, val color: Color?) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape, color)
    override fun update(node: FocusRingNode) { node.shape = shape; node.color = color; node.invalidateDraw() }
    override fun InspectorInfo.inspectableProperties() { name = "focusRing" }
}

private class FocusRingNode(var shape: Shape, var color: Color?) :
    Modifier.Node(), DrawModifierNode, FocusEventModifierNode, CompositionLocalConsumerModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focused != focusState.isFocused) { focused = focusState.isFocused; invalidateDraw() }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused || currentValueOf(LocalInputModeManager).inputMode != InputMode.Keyboard) return
        val stroke = RING_WIDTH.toPx()
        val grow = RING_OFFSET.toPx() + stroke / 2
        val outline = shape.createOutline(Size(size.width + 2 * grow, size.height + 2 * grow), layoutDirection, this)
        translate(-grow, -grow) {
            drawOutline(outline, color ?: currentValueOf(LocalFocalColors).ink, style = Stroke(stroke))
        }
    }
}
