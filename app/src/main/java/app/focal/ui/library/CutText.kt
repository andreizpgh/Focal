package app.focal.ui.library

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil

/** The last measured layout of a [CutText], read by its draw. */
private class CutLayout { var result: TextLayoutResult? = null }

/**
 * A preview that ends on the last whole word that fits (spec 4.12.2, 4.22.4, G4): never "at all.…" or "the way h…".
 * One measuring pass finds the end of the last line that fits ([maxLines], and no more lines than the height the
 * parent allows); the text is cut back to the word before it, trailing `, . ; : – —` go, and "…" follows. If the
 * ellipsis pushes the last word out, it backs off one more word. Text before [protectedEnd] (a run-in) is never
 * cut. TalkBack reads the full [text].
 */
@Composable
fun CutText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
    protectedEnd: Int = 0,
) {
    val measurer = rememberTextMeasurer()
    val holder = remember { CutLayout() }
    val styled = remember(style, color) { style.copy(color = color) }
    Spacer(
        modifier
            .semantics { this.text = text }
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                fun measure(value: AnnotatedString, lines: Int) = measurer.measure(
                    value, styled, overflow = TextOverflow.Clip, maxLines = lines,
                    constraints = Constraints(maxWidth = width), density = this, layoutDirection = layoutDirection,
                )
                val line = styled.lineHeight.toPx()
                val fit = if (constraints.hasBoundedHeight && line > 0f) (constraints.maxHeight / line).toInt().coerceAtLeast(1) else maxLines
                val lines = minOf(maxLines, fit)
                var result = measure(text, lines)
                if (result.hasVisualOverflow) {
                    var end = WordCut.cut(text, result.getLineEnd(lines - 1, visibleEnd = true))
                    while (end != null && end > protectedEnd) {
                        val candidate = measure(text.subSequence(0, end) + AnnotatedString(WordCut.ELLIPSIS), lines)
                        if (!candidate.hasVisualOverflow) { result = candidate; break }
                        end = WordCut.previousWord(text, end)
                    }
                    if (end == null || end <= protectedEnd) result = measure(text.subSequence(0, protectedEnd.coerceAtMost(text.length)), lines)
                }
                holder.result = result
                val height = ceil(result.size.height.toFloat()).toInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .drawBehind { holder.result?.let { drawText(it) } },
    )
}
