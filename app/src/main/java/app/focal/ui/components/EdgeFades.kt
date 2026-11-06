package app.focal.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import app.focal.ui.design.FocalLayout
import app.focal.ui.design.FocalTheme
import app.focal.ui.design.Grid

/**
 * Edge fades replace bars and dividers (spec 2.6): draw-only, alpha-only overlays, identical on every API level. Each
 * fills the window and is drawn above the content it fades.
 */
object FadeMetrics {
    /** The library's pin band: solid to status + 40 dp, gradient to status + 56 dp. */
    val pinBandSolid = Grid.x40
    val pinBandEnd = Grid.x56
}

/** Solid paper from the window top to [solidBelowStatus] under the status bar, then [fade] to transparent. */
@Composable
fun PaperTopFade(solidBelowStatus: Dp, modifier: Modifier, fade: Dp = FocalLayout.topFade, alpha: () -> Float = { 1f }) {
    val paper = FocalTheme.colors.paper
    val status = WindowInsets.statusBars
    Spacer(
        modifier.drawBehind {
            val solid = status.getTop(this).toFloat() + solidBelowStatus.toPx()
            drawEdge(paper, solid, solid + fade.toPx(), alpha())
        },
    )
}

/**
 * The library's top (spec 2.6, G1): the status edge, solid paper to the status bar's bottom and 16 dp of gradient,
 * always at full alpha (at rest it covers only the paper above the stage); and the pin band, solid to status + 40
 * and fading to status + 56, which arrives only as the stage's bottom passes under the status edge:
 * alpha = clamp01((status + 16 - stage bottom) / 16). [stageBottom] is the stage's window y (NaN without a stage,
 * or once it has scrolled away: the band is fully on).
 */
@Composable
fun LibraryTopFade(stageBottom: () -> Float, modifier: Modifier) {
    val paper = FocalTheme.colors.paper
    val status = WindowInsets.statusBars
    Spacer(
        modifier.drawBehind {
            val top = status.getTop(this).toFloat()
            val edge = FocalLayout.topFade.toPx()
            val bottom = stageBottom()
            val band = if (bottom.isNaN()) 1f else ((top + edge - bottom) / edge).coerceIn(0f, 1f)
            if (band > 0f) drawEdge(paper, top + FadeMetrics.pinBandSolid.toPx(), top + FadeMetrics.pinBandEnd.toPx(), band)
            drawEdge(paper, top, top + edge, 1f)
        },
    )
}

/**
 * A single-line field's text that scrolls inside its box ([scroll], the field's own scroll state) fades out over
 * [width] at each side where text runs on beyond it, instead of being cut mid-glyph (spec 2.6: edge fades replace hard
 * edges). Nothing is drawn differently while the text fits.
 */
fun Modifier.scrollEdgeFade(scroll: ScrollState, width: Dp = FocalLayout.topFade): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width / 2)
        val rtl = layoutDirection == LayoutDirection.Rtl
        // The scroll offset runs from the text's start: in RTL the start is the right edge.
        val startHidden = scroll.value > 0
        val endHidden = scroll.value < scroll.maxValue
        val leftHidden = if (rtl) endHidden else startHidden
        val rightHidden = if (rtl) startHidden else endHidden
        if (leftHidden) drawRect(
            Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = 0f, endX = fade),
            size = Size(fade, size.height), blendMode = BlendMode.DstOut,
        )
        if (rightHidden) drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = size.width - fade, endX = size.width),
            topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height), blendMode = BlendMode.DstOut,
        )
    }

private fun DrawScope.drawEdge(paper: Color, solidEnd: Float, fadeEnd: Float, alpha: Float) {
    if (alpha <= 0f) return
    drawRect(paper, size = Size(size.width, solidEnd), alpha = alpha)
    drawRect(
        Brush.verticalGradient(0f to paper, 1f to Color.Transparent, startY = solidEnd, endY = fadeEnd),
        topLeft = Offset(0f, solidEnd), size = Size(size.width, fadeEnd - solidEnd), alpha = alpha,
    )
}
