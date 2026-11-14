package app.focal.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.focal.ui.design.FocalGrid
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalMotion
import app.focal.ui.design.FocalShapes
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.FocalType
import app.focal.ui.design.smoothstep

/**
 * Content that swaps by fading through (spec 4.3): the outgoing content at 1 - smoothstep(0, 0.5, q), the incoming at
 * smoothstep(0.5, 1, q), q on `fadeFast`, so two words are never superimposed. The alphas are read in the layer
 * phase. A change during a swap goes on from what is drawn: nothing that has faded comes back at full strength.
 */
@Composable
fun <K : Any> FadeThrough(target: K, modifier: Modifier = Modifier, contentAlignment: Alignment = Alignment.Center, content: @Composable BoxScope.(K) -> Unit) {
    var shown by remember { mutableStateOf(target) }
    var leaving by remember { mutableStateOf<K?>(null) }
    val q = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == shown) return@LaunchedEffect
        if (leaving == target && q.value < HALF) {
            // Back to what was leaving before anything new showed: it comes back from where it was.
            q.animateTo(0f, FocalMotion.fadeFast())
            shown = target
            leaving = null
            q.snapTo(1f)
            return@LaunchedEffect
        }
        val from = if (leaving != null && q.value < HALF) {
            // The old content is still fading out and the one after it never showed: the newest takes that one's place,
            // and the fade goes on from where it is.
            q.value
        } else {
            // What is on screen leaves from the alpha it has now, never from full again.
            val onScreen = if (leaving == null) 1f else smoothstep(HALF, 1f, q.value)
            leaving = shown
            leavingAt(onScreen)
        }
        shown = target
        q.snapTo(from)
        q.animateTo(1f, FocalMotion.fadeFast())
        leaving = null
    }
    Box(modifier, contentAlignment = contentAlignment) {
        leaving?.let { old ->
            key(old) { Box(Modifier.graphicsLayer { alpha = 1 - smoothstep(0f, HALF, q.value) }, contentAlignment = contentAlignment) { content(old) } }
        }
        key(shown) {
            Box(Modifier.graphicsLayer { alpha = if (leaving == null) 1f else smoothstep(HALF, 1f, q.value) }, contentAlignment = contentAlignment) { content(shown) }
        }
    }
}

private const val HALF = 0.5f

/** The q at which leaving content is drawn at [alpha] (1 - smoothstep(0, 0.5, q) = alpha), by bisection. */
private fun leavingAt(alpha: Float): Float {
    var low = 0f
    var high = HALF
    repeat(LEAVING_STEPS) {
        val mid = (low + high) / 2
        if (1 - smoothstep(0f, HALF, mid) > alpha) low = mid else high = mid
    }
    return (low + high) / 2
}

private const val LEAVING_STEPS = 12

/**
 * The icon button (spec 4.2): a 48 dp target with a 24 dp glyph; pressed, a 40 dp `press` circle behind it.
 * Disabled: `faint`, no press layer. [contentDescription] is required.
 */
@Composable
fun FocalIconButton(icon: FocalIcon, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(FocalLayout.target)
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = IconPressPlate, enabled = enabled, role = Role.Button, onClickLabel = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, tint = if (enabled) colors.ink else colors.faint, contentDescription = contentDescription)
    }
}

private val IconPressPlate = PressPlate(FocalShapes.pill, insetX = FocalGrid.x4, insetY = FocalGrid.x4)

/**
 * The text button (spec 4.20): `label` in ink, at least 48 dp tall, 12 dp padding, a `press` pill when pressed. The
 * feedback pill's action uses `action` with 16 dp padding and an 18 dp leading glyph.
 */
@Composable
fun FocalTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: FocalIcon? = null,
    style: TextStyle = FocalType.label,
    horizontalPadding: Dp = FocalGrid.x12,
) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .defaultMinSize(minHeight = FocalLayout.target)
            .focusRing(FocalShapes.pill)
            .clickable(source, indication = PillPressPlate, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Glyph(leading, colors.ink, size = GlyphSize.small)
            Spacer(Modifier.width(SMALL_GLYPH_GAP))
        }
        Text(text, style = style, color = colors.ink)
    }
}

internal val PillPressPlate = PressPlate(FocalShapes.pill)

/** The feedback action's glyph sits this far before its label (spec 2.7). */
internal val SMALL_GLYPH_GAP = 6.dp

/**
 * The primary pill outside the dock (spec 4.5.1): 56 dp, `inv` with an `onInv` label, a centred glyph + 8 dp +
 * label; pressed it scales to 0.97 with no overlay. Disabled: `s1` with `faint` content. It grows for a label that
 * wraps at large font scales.
 */
@Composable
fun FocalPrimaryPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable (tint: Color) -> Unit)? = null,
) {
    val colors = FocalTheme.colors
    val source = remember { MutableInteractionSource() }
    val content = if (enabled) colors.onInv else colors.faint
    Row(
        modifier
            .defaultMinSize(minHeight = FocalGrid.x56)
            .pressScale(source)
            .focusRing(FocalShapes.pill)
            .background(if (enabled) colors.inv else colors.s1, FocalShapes.pill)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = FocalGrid.x16, vertical = FocalGrid.x8),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading(content)
            Spacer(Modifier.width(FocalGrid.x8))
        }
        Text(text, style = FocalType.label, color = content, textAlign = TextAlign.Center)
    }
}
