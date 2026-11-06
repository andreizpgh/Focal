package app.focal.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Text
import app.focal.R
import app.focal.ui.design.FocalMotion
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The copy glyph and "Copy", or the check and "Copied" (spec 4.5.1, T9): the copy glyph turns into a check with a
 * 0.7 to 1 scale (`press`) while the labels crossfade (`fadeFast`). The row is as wide as glyph + gap + "Copy", so a
 * pill centres "Copy" optically as the renders do; "Copied" starts where "Copy" starts and runs on to the right, so
 * the glyph never shifts during the swap. [ink] is read in composition (it follows a pill's fill).
 */
@Composable
fun CopyLabel(copied: Boolean, ink: Color, style: TextStyle, glyph: Dp, gap: Dp, modifier: Modifier = Modifier) {
    val check = remember { Animatable(if (copied) 1f else 0f) }
    val checkScale = remember { Animatable(1f) }
    LaunchedEffect(copied) {
        if (copied) launch { checkScale.snapTo(CHECK_START_SCALE); checkScale.animateTo(1f, FocalMotion.press()) }
        check.animateTo(if (copied) 1f else 0f, FocalMotion.fadeFast())
    }
    Layout(
        contents = listOf(
            {
                Box(contentAlignment = Alignment.Center) {
                    Glyph(FocalIcon.Copy, ink, size = glyph, modifier = Modifier.graphicsLayer { alpha = 1 - check.value })
                    Glyph(
                        FocalIcon.Check, ink, size = glyph,
                        modifier = Modifier.graphicsLayer { alpha = check.value; scaleX = checkScale.value; scaleY = checkScale.value },
                    )
                }
            },
            { Text(stringResource(R.string.action_copy), style = style, color = ink, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = 1 - check.value }) },
            { Text(stringResource(R.string.action_copied), style = style, color = ink, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = check.value }) },
        ),
        modifier = modifier,
    ) { (glyphs, copy, done), constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val icon = glyphs.first().measure(loose)
        val gapPx = gap.roundToPx()
        val label = copy.first().measure(Constraints(maxWidth = max(0, constraints.maxWidth - icon.width - gapPx)))
        val confirmed = done.first().measure(Constraints())
        val height = maxOf(icon.height, label.height, confirmed.height)
        layout(icon.width + gapPx + label.width, height) {
            icon.place(0, (height - icon.height) / 2)
            label.place(icon.width + gapPx, (height - label.height) / 2)
            confirmed.place(icon.width + gapPx, (height - confirmed.height) / 2)
        }
    }
}

/** The check grows in from this scale (`press`). */
private const val CHECK_START_SCALE = 0.7f

/**
 * The narrowest pill (pixels) that holds [CopyLabel] centred on "Copy" with "Copied" still [padding] inside its end:
 * glyph + gap + 2 x width("Copied") - width("Copy") + 2 x padding.
 */
fun copyPillWidth(copyLabel: Int, copiedLabel: Int, glyph: Int, gap: Int, padding: Int): Int {
    val longest = max(copyLabel, copiedLabel)
    return glyph + gap + 2 * longest - copyLabel + 2 * padding
}
