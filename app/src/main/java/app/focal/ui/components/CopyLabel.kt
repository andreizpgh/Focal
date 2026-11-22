package app.focal.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.focal.R
import app.focal.ui.design.FocalMotion
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The copy glyph and "Copy", or the check and "Copied" (spec 4.5.1, T9): both states sit optically centred inside the
 * pill. On confirm, the whole pair glides smoothly to the centred offset for "Copied" while the copy glyph scales down
 * into the springing check and the labels swap with a delicate 3 dp vertical micro-slide and crossfade.
 */
@Composable
fun CopyLabel(copied: Boolean, ink: Color, style: TextStyle, glyph: Dp, gap: Dp, modifier: Modifier = Modifier) {
    val check = remember { Animatable(if (copied) 1f else 0f) }
    val checkScale = remember { Animatable(1f) }
    val slidePx = with(LocalDensity.current) { LABEL_SLIDE.toPx() }
    var copyOffset by remember { mutableFloatStateOf(0f) }
    var copiedOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(copied) {
        if (copied) launch {
            checkScale.snapTo(CHECK_START_SCALE)
            checkScale.animateTo(1f, FocalMotion.press())
        }
        check.animateTo(if (copied) 1f else 0f, FocalMotion.fadeFast())
    }
    Layout(
        contents = listOf(
            {
                Box(
                    Modifier.graphicsLayer { translationX = lerp(copyOffset, copiedOffset, check.value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(
                        FocalIcon.Copy, ink, size = glyph,
                        modifier = Modifier.graphicsLayer {
                            val t = check.value
                            alpha = (1f - t * 1.35f).coerceIn(0f, 1f)
                            val s = lerp(1f, COPY_END_SCALE, t)
                            scaleX = s
                            scaleY = s
                        },
                    )
                    Glyph(
                        FocalIcon.Check, ink, size = glyph,
                        modifier = Modifier.graphicsLayer {
                            alpha = check.value
                            scaleX = checkScale.value
                            scaleY = checkScale.value
                        },
                    )
                }
            },
            {
                Text(
                    stringResource(R.string.action_copy), style = style, color = ink, maxLines = 1, softWrap = false,
                    modifier = Modifier.graphicsLayer {
                        val t = check.value
                        translationX = lerp(copyOffset, copiedOffset, t)
                        translationY = -slidePx * t
                        alpha = (1f - t * 1.4f).coerceIn(0f, 1f)
                    },
                )
            },
            {
                Text(
                    stringResource(R.string.action_copied), style = style, color = ink, maxLines = 1, softWrap = false,
                    modifier = Modifier.graphicsLayer {
                        val t = check.value
                        translationX = lerp(copyOffset, copiedOffset, t)
                        translationY = slidePx * (1f - t)
                        alpha = ((t - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    },
                )
            },
        ),
        modifier = modifier,
    ) { (glyphs, copy, done), constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val icon = glyphs.first().measure(loose)
        val gapPx = gap.roundToPx()
        val labelCap = max(0, constraints.maxWidth - icon.width - gapPx)
        val label = copy.first().measure(Constraints(maxWidth = labelCap))
        val confirmed = done.first().measure(Constraints(maxWidth = labelCap))
        val copyWidth = icon.width + gapPx + label.width
        val copiedWidth = icon.width + gapPx + confirmed.width
        val totalWidth = max(copyWidth, copiedWidth)
        val height = maxOf(icon.height, label.height, confirmed.height)
        copyOffset = (totalWidth - copyWidth) / 2f
        copiedOffset = (totalWidth - copiedWidth) / 2f
        layout(totalWidth, height) {
            icon.place(0, (height - icon.height) / 2)
            label.place(icon.width + gapPx, (height - label.height) / 2)
            confirmed.place(icon.width + gapPx, (height - confirmed.height) / 2)
        }
    }
}

/** The check grows in from this scale (`press`); the outgoing copy icon recedes subtly. */
private const val CHECK_START_SCALE = 0.68f
private const val COPY_END_SCALE = 0.76f
private val LABEL_SLIDE = 3.dp

/** The narrowest pill (pixels) that holds [CopyLabel] centred in both "Copy" and "Copied" states with [padding] each side. */
fun copyPillWidth(copyLabel: Int, copiedLabel: Int, glyph: Int, gap: Int, padding: Int): Int =
    glyph + gap + max(copyLabel, copiedLabel) + 2 * padding
