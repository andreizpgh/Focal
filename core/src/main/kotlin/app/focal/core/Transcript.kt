package app.focal.core

import kotlin.math.exp
import kotlin.math.ln

object Transcript {
    /** A review cue, not a calibrated probability of correctness. */
    fun needsReview(probabilities: List<Float>, noSpeech: Float): Boolean {
        if (probabilities.isEmpty()) return true
        val mean = exp(probabilities.map { ln(it.coerceIn(0.0001f, 1f).toDouble()) }.average())
        return mean < 0.62 || probabilities.count { it < 0.35f } >= 2 || noSpeech > 0.45
    }

    /** Only used for chunks deliberately overlapping at the maximum chunk length. */
    fun removeOverlap(previous: String, next: String): String {
        val left = Regex("\\S+").findAll(previous).map { it.value }.toList().takeLast(12)
        val right = Regex("\\S+").findAll(next).toList()
        fun key(s: String) = s.lowercase().trim { !it.isLetterOrDigit() }
        for (count in minOf(left.size, right.size) downTo 2) {
            if (left.takeLast(count).map(::key) == right.take(count).map { key(it.value) }) {
                return next.substring(right[count - 1].range.last + 1).trimStart()
            }
        }
        return next.trim()
    }

    fun title(text: String): String = text.trim().split(Regex("\\s+")).take(7).joinToString(" ")
        .take(72).ifBlank { "Untitled note" }

    fun time(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }
}
