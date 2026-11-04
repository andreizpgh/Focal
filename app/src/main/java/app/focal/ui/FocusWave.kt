package app.focal.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin

private val focusWaveHeights = floatArrayOf(7f, 12f, 19f, 30f, 40f, 30f, 19f, 12f, 7f)
private val focusWaveAlphas = floatArrayOf(.18f, .3f, .5f, .75f, 1f, .75f, .5f, .3f, .18f)

/** The approved flat mark, also used as the live microphone's amplitude envelope. */
internal fun DrawScope.drawFocusWave(
    color: Color,
    bounds: Size = size,
    level: Float = 1f,
    minimumBarHeight: Float = 0f,
    processingPhase: Float? = null,
) {
    if (!bounds.width.isFinite() || !bounds.height.isFinite() || bounds.width <= 0f || bounds.height <= 0f) return
    val amplitude = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
    // Match the source SVG's 54 × 42 viewBox, 3.2 stroke and 6.2 pitch.
    val scale = min(bounds.width / 54f, bounds.height / 42f)
    val startX = (bounds.width - 54f * scale) / 2f + .6f * scale
    val centerY = bounds.height / 2f
    val barWidth = 3.2f * scale
    val minimum = if (minimumBarHeight.isFinite()) minimumBarHeight.coerceAtLeast(0f) else 0f
    for (index in focusWaveHeights.indices) {
        val maximum = focusWaveHeights[index] * scale
        val resting = minimum.coerceAtMost(maximum)
        val pulse = processingPhase?.takeIf { it.isFinite() }?.let {
            .35f + .65f * ((sin(it * 2f * PI.toFloat() - index * .55f) + 1f) / 2f)
        } ?: 1f
        val height = resting + (maximum - resting) * amplitude * pulse
        if (height <= 0f) continue
        drawRoundRect(
            color = color.copy(alpha = color.alpha * focusWaveAlphas[index]),
            topLeft = Offset(startX + index * 6.2f * scale, centerY - height / 2f),
            size = Size(barWidth, height),
            cornerRadius = CornerRadius(min(barWidth, height) / 2f),
        )
    }
}

@Composable internal fun FocusProcessingIndicator(
    modifier: Modifier = Modifier.size(width = 32.dp, height = 24.dp),
    contentDescription: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = lifecycleState.isAtLeast(Lifecycle.State.STARTED) && ValueAnimator.areAnimatorsEnabled()
    val phase: State<Float> = if (animate) {
        val transition = rememberInfiniteTransition(label = "transcription focus")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "processing wave",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    Canvas(modifier.semantics {
        if (contentDescription != null) this.contentDescription = contentDescription
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        // Draw-only animation keeps the surrounding text and layout still.
        drawFocusWave(color, processingPhase = if (animate) phase.value else null)
    }
}
