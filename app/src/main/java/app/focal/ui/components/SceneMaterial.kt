package app.focal.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.focal.ui.design.Scene
import app.focal.ui.design.Stage
import java.nio.ByteBuffer

/**
 * The logo's material (spec 2.2, 3.4): the near-black vertical gradient, the top highlight and the grain. Gradient
 * stops and the highlight ellipse are relative to the drawn size (the window, the card, or the morph's clip); the
 * grain is in window pixels when [grainOrigin] gives the drawn area's window position, so it never swims while the
 * area moves. The draw reads no animated state: it is recorded once per size.
 */
fun Modifier.sceneMaterial(cornerRadius: Dp = 0.dp, ring: Color = Color.Transparent, grainOrigin: (() -> Offset)? = null): Modifier =
    drawWithCache { buildMaterial(cornerRadius, ring, grainOrigin) }

private fun CacheDrawScope.buildMaterial(cornerRadius: Dp, ring: Color, grainOrigin: (() -> Offset)?): DrawResult {
    val gradient = Brush.verticalGradient(
        0f to Scene.top, Scene.MID_STOP to Scene.mid, 1f to Scene.bottom, startY = 0f, endY = size.height,
    )
    // A circular gradient scaled into the ellipse: radii 130 % of the width by 46 % of the height.
    val radiusX = size.width * Scene.HIGHLIGHT_RADIUS_X
    val radiusY = size.height * Scene.HIGHLIGHT_RADIUS_Y
    val squash = radiusY / radiusX
    val highlight = Brush.radialGradient(
        0f to Color.White.copy(alpha = Scene.HIGHLIGHT_ALPHA), Scene.HIGHLIGHT_END to Color.Transparent,
        center = Offset(size.width / 2, 0f), radius = radiusX,
    )
    val grain = Grain.brush()
    val grainMatrix = Matrix()
    val radius = cornerRadius.toPx()
    val corner = CornerRadius(radius)
    val ringWidth = 1.dp.toPx()
    return onDrawBehind {
        drawRoundRect(gradient, cornerRadius = corner)
        // The highlight and the grain take the same rounded outline, so nothing lights the paper beyond the card.
        scale(1f, squash, pivot = Offset(size.width / 2, 0f)) {
            drawRoundRect(highlight, size = Size(size.width, size.height / squash), cornerRadius = CornerRadius(radius, radius / squash))
        }
        grainOrigin?.invoke()?.let { origin ->
            grainMatrix.setTranslate(-origin.x, -origin.y)
            grain.shader.setLocalMatrix(grainMatrix)
        }
        drawRoundRect(grain, cornerRadius = corner, alpha = Scene.GRAIN_ALPHA, colorFilter = Grain.white)
        if (ring.alpha > 0f) {
            drawRoundRect(
                ring, topLeft = Offset(ringWidth / 2, ringWidth / 2), size = Size(size.width - ringWidth, size.height - ringWidth),
                cornerRadius = CornerRadius(radius - ringWidth / 2), style = Stroke(ringWidth),
            )
        }
    }
}

/**
 * The tile's grain (spec 2.2): a 256 x 256 ALPHA_8 tile of seeded fractal value noise (two octaves), generated once
 * per process and repeated. It also dithers the gradient against 8-bit banding.
 */
private object Grain {
    private const val SIZE = 256
    private const val SEED = 0x9E3779B9.toInt()
    private const val FINE = 0.62f
    private const val COARSE = 0.38f
    private const val MAX = 255f
    private const val UNIT = 4294967296f

    /** The tile stores the noise as alpha; drawing it tinted white keeps the grain white at any alpha. */
    val white = ColorFilter.tint(Color.White)

    private val tile: Bitmap by lazy {
        var state = SEED
        // xorshift32, as the prototype's grain (the same tile on every device).
        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return (state.toLong() and 0xFFFFFFFFL) / UNIT
        }
        val half = SIZE / 2
        val coarse = FloatArray(half * half) { next() }
        val pixels = ByteBuffer.allocate(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val value = FINE * next() + COARSE * coarse[(y shr 1) * half + (x shr 1)]
            pixels.put((value * MAX).toInt().coerceIn(0, MAX.toInt()).toByte())
        }
        pixels.rewind()
        createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8).apply { copyPixelsFromBuffer(pixels) }
    }

    /** One brush per drawn material (sharing the tile): its shader's local matrix carries that material's offset. */
    fun brush(): GrainBrush = GrainBrush(BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
}

/** A [ShaderBrush] over one shader instance, so its local matrix can follow the material's window position. */
private class GrainBrush(val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

/** The paused mark (spec 2.2.1 `stageBadge`): 22 dp on the stage, 28 dp in the scene; every dimension scales with it. */
object PauseBadgeMetrics {
    val stage = 22.dp
    val scene = 28.dp
    /** At the 22 dp reference: the cut-out ring, the two bars and the gap between them. */
    const val RING = 3f
    const val BAR_WIDTH = 2.25f
    const val BAR_HEIGHT = 9f
    const val BAR_GAP = 2.7f
    const val REFERENCE = 22f
}

/**
 * The pause badge: a [Scene.ink] disc cut out of the bars by a ring in the material's colour, with two pill-ended
 * bars. [diameter] is read in the draw phase, so the morph can grow it 22 to 28 dp without recomposing. Decorative:
 * the merged timer or stage node speaks the state.
 */
@Composable
fun PauseBadge(diameter: () -> Dp, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .size(PauseBadgeMetrics.scene)
            .drawBehind { drawPauseBadge(diameter().toPx()) },
    )
}

/** Draws the badge centred in the current bounds. */
fun DrawScope.drawPauseBadge(diameterPx: Float) {
    val k = diameterPx / PauseBadgeMetrics.REFERENCE / density
    val centre = Offset(size.width / 2, size.height / 2)
    val radius = diameterPx / 2
    drawCircle(Stage.badgeRing, radius = radius + PauseBadgeMetrics.RING * k * density, center = centre)
    drawCircle(Scene.ink, radius = radius, center = centre)
    val barWidth = PauseBadgeMetrics.BAR_WIDTH * k * density
    val barHeight = PauseBadgeMetrics.BAR_HEIGHT * k * density
    val gap = PauseBadgeMetrics.BAR_GAP * k * density
    val top = centre.y - barHeight / 2
    val left = centre.x - gap / 2 - barWidth
    val corner = CornerRadius(barWidth / 2)
    drawRoundRect(Stage.badgeGlyph, Offset(left, top), Size(barWidth, barHeight), corner)
    drawRoundRect(Stage.badgeGlyph, Offset(centre.x + gap / 2, top), Size(barWidth, barHeight), corner)
}
