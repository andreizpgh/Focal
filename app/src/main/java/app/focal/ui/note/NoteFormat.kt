package app.focal.ui.note

import app.focal.ui.library.Lengths
import kotlin.math.abs
import kotlin.math.ceil

/** Times on the note page (spec 4.10, 4.13, 5.9). */
object NoteTimes {
    private const val MS_PER_S = 1000L

    /** The player's times, a gap's length and the stop time of an interrupted recording: "3:08", "1:02:03". */
    fun clock(ms: Long): String = Lengths.timer(ms.coerceAtLeast(0) / MS_PER_S)

    /** Whole seconds, for the spoken forms. */
    fun seconds(ms: Long): Long = ms.coerceAtLeast(0) / MS_PER_S
}

/**
 * The player strip as an adjustable control (spec 4.10, 8.2): TalkBack's swipe up and down move the position by
 * exactly 5 s, so the range is cut into 5 s steps (Compose increments an adjustable node by range / (steps + 1)).
 */
object PlayerSteps {
    const val STEP_MS = 5_000L

    /** The range's end: the duration rounded up to a whole step. */
    fun rangeEnd(durationMs: Long): Float = (stepCount(durationMs) * STEP_MS).toFloat()

    /** The steps between the range's ends (the platform counts the positions between them). */
    fun steps(durationMs: Long): Int = (stepCount(durationMs) - 1).toInt().coerceAtLeast(0)

    private fun stepCount(durationMs: Long): Long = ceil(durationMs.coerceAtLeast(STEP_MS) / STEP_MS.toDouble()).toLong()
}

/**
 * Playback follow (spec 5.6): the playing line stays inside the middle third of the reading band. When it leaves it
 * (the line ran past the lower third, or a seek moved it), the band scrolls so the line's top sits at the top of the
 * middle third, so reading carries on downward like turning a third of a page. Returns the scroll distance, or 0.
 */
object Follow {
    private const val THIRDS = 3f

    fun distance(lineTop: Float, lineBottom: Float, bandTop: Float, bandBottom: Float): Float {
        val third = (bandBottom - bandTop) / THIRDS
        val upper = bandTop + third
        val lower = bandTop + 2 * third
        return if (lineTop < upper || lineBottom > lower) lineTop - upper else 0f
    }
}

/** A line's span in the window, top to bottom (pixels). */
data class Span(val top: Float, val bottom: Float) {
    fun shifted(scroll: Float) = Span(top - scroll, bottom - scroll)
}

/**
 * The top band's soft edge (spec 2.6): solid paper down to [bandTop], then [fade] of gradient. A line left across it
 * reads as cut text (at 2.0x a line is taller than the fade), so when the page scrolls a line into place (follow, a
 * search hit, the caret) the scroll changes by the least amount that lands a line's edge on [bandTop]: the line below
 * it then starts whole under the solid paper's edge and the one above is hidden.
 */
object TopEdge {
    private const val EPSILON = 0.5f

    /** Whether [line] shows partly faded: it crosses [bandTop], or starts inside the fade. */
    fun cuts(line: Span, bandTop: Float, fade: Float): Boolean =
        line.bottom > bandTop + EPSILON && line.top < bandTop + fade - EPSILON && abs(line.top - bandTop) > EPSILON

    /**
     * [scroll] (pixels, positive moves the text up) changed so that no line of [lines] (window spans before it) is
     * cut once scrolled, by at most the tallest line and the fade, while [keep] (the line brought into place, before
     * the scroll) stays within [keepTop]..[keepBottom]. [scroll] itself when nothing is cut or no change can keep it.
     */
    fun snap(scroll: Float, lines: List<Span>, bandTop: Float, fade: Float, keep: Span?, keepTop: Float, keepBottom: Float): Float {
        fun cut(s: Float) = lines.any { cuts(it.shifted(s), bandTop, fade) }
        if (lines.isEmpty() || !cut(scroll)) return scroll
        val reach = lines.maxOf { it.bottom - it.top } + fade
        var best = scroll
        var bestShift = Float.POSITIVE_INFINITY
        for (line in lines) for (edge in floatArrayOf(line.top, line.bottom)) {
            // The scroll that puts this edge on bandTop.
            val s = edge - bandTop
            val shift = abs(s - scroll)
            if (shift >= bestShift || shift > reach) continue
            val kept = keep?.shifted(s)
            if (kept != null && (kept.top < keepTop - EPSILON || kept.bottom > keepBottom + EPSILON)) continue
            if (cut(s)) continue
            best = s
            bestShift = shift
        }
        return best
    }
}
