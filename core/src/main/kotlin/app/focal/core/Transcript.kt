package app.focal.core

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln

object Transcript {
    /** Absolute times in the original recording. Reviewed corrections are trusted by the caller. */
    data class Segment(val start: Long, val end: Long, val text: String, val uncertain: Boolean = false)

    /** A review cue, not a calibrated probability of correctness. */
    fun needsReview(probabilities: List<Float>, noSpeech: Float): Boolean {
        if (probabilities.isEmpty() || !noSpeech.isFinite() || noSpeech !in 0f..1f ||
            probabilities.any { !it.isFinite() || it !in 0f..1f }) return true
        val mean = exp(probabilities.map { ln(it.coerceIn(0.0001f, 1f).toDouble()) }.average())
        return mean < 0.62 || probabilities.count { it < 0.35f } >= 2 || noSpeech > 0.45
    }

    /** Lexical match only. Audio-boundary decisions belong in [reconcileBoundary]. */
    fun removeOverlap(previous: String, next: String): String {
        return dropWords(next, matchingPrefixWords(previous, next, minimum = 2))
    }

    /** A prompt contains whole words from a contiguous trusted tail, never overlapped audio. */
    fun trustedContext(previous: List<Segment>, before: Long, maxChars: Int = 240): String {
        require(maxChars >= 0)
        val tail = ArrayDeque<String>()
        var remaining = maxChars
        var cursor = before
        for (segment in previous.asReversed()) {
            if (segment.start >= before) continue
            if (segment.uncertain || segment.text.isBlank()) break
            // Segment timestamps do not locate individual words. Do not prompt with a
            // partly overlapping segment, or invent a textual cut within it.
            if (segment.end > before) continue
            if (segment.end > cursor || cursor - segment.end >= 5000) break
            val text = wholeWordTail(segment.text, remaining)
            if (text.isBlank()) break
            tail.addFirst(text)
            remaining -= text.length + 1
            cursor = segment.start
            if (remaining <= 0 || text.length < segment.text.trim().length) break
        }
        return tail.joinToString(" ")
    }

    /**
     * Only text timed wholly inside already decoded audio may be removed. A segment
     * straddling the boundary cannot be split safely without word alignment: retain it
     * and mark a possible repeated prefix for review. Missing new audio stays visible.
     */
    fun reconcileBoundary(previous: List<Segment>, decoded: List<Segment>, chunkStart: Long,
                          chunkEnd: Long, previousChunkEnd: Long? = null): List<Segment> {
        require(chunkEnd > chunkStart)
        val current = decoded.mapNotNull { segment ->
            val text = segment.text.trim()
            if (text.isBlank() || segment.start >= chunkEnd || segment.end <= chunkStart ||
                segment.end <= segment.start) null
            else segment.copy(start = segment.start.coerceAtLeast(chunkStart),
                end = segment.end.coerceAtMost(chunkEnd), text = text)
        }
        val overlapEnd = previousChunkEnd?.coerceIn(chunkStart, chunkEnd) ?: chunkStart
        if (overlapEnd == chunkStart) return current.ifEmpty {
            listOf(Segment(chunkStart, chunkEnd, "", uncertain = true))
        }
        val source = previous.filter { it.end > chunkStart && it.start < overlapEnd }
            .joinToString(" ") { it.text }
        val duplicated = current.takeWhile { it.start < overlapEnd && it.end <= overlapEnd }
        var wordsToDrop = matchingPrefixWords(source, duplicated.joinToString(" ") { it.text }, minimum = 1)
        val result = current.mapNotNull { segment ->
            if (segment in duplicated && wordsToDrop > 0) {
                val count = WORD.findAll(segment.text).count()
                val removed = minOf(wordsToDrop, count)
                wordsToDrop -= removed
                segment.copy(text = dropWords(segment.text, removed), uncertain = true).takeIf { it.text.isNotBlank() }
            } else if (segment.start < overlapEnd && segment.end <= overlapEnd) {
                // A different hypothesis for the same audio needs a human decision.
                segment.copy(uncertain = true)
            } else if (segment.start < overlapEnd && segment.end > overlapEnd &&
                matchingPrefixWords(source, segment.text, minimum = 1) > 0) {
                segment.copy(uncertain = true)
            } else segment
        }
        // Even a conflicting (retained) old-audio hypothesis has not accounted for
        // the new audio. Preserve both the alternative and the missing-range cue.
        return if (overlapEnd < chunkEnd && result.none { it.end > overlapEnd })
            result + Segment(overlapEnd, chunkEnd, "", uncertain = true)
        else result
    }

    private val WORD = Regex("\\S+")

    private fun wordKey(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFC)
        .lowercase().trim { !it.isLetterOrDigit() }

    private fun matchingPrefixWords(previous: String, next: String, minimum: Int): Int {
        val left = WORD.findAll(previous).map { wordKey(it.value) }.toList().takeLast(12)
        val right = WORD.findAll(next).take(12).map { wordKey(it.value) }.toList()
        for (count in minOf(left.size, right.size) downTo minimum) {
            val prefix = right.take(count)
            if (prefix.all { it.isNotBlank() } && left.takeLast(count) == prefix) return count
        }
        return 0
    }

    private fun dropWords(text: String, count: Int): String {
        if (count == 0) return text.trim()
        val last = WORD.findAll(text).take(count).lastOrNull() ?: return text.trim()
        return text.substring(last.range.last + 1).trimStart()
    }

    private fun wholeWordTail(text: String, limit: Int): String {
        if (limit <= 0) return ""
        val trimmed = text.trim()
        var start = (trimmed.length - limit).coerceAtLeast(0)
        if (start > 0 && !trimmed[start - 1].isWhitespace()) {
            while (start < trimmed.length && !trimmed[start].isWhitespace()) start++
        }
        return trimmed.substring(start).trimStart()
    }

    fun title(text: String): String = text.trim().split(Regex("\\s+")).take(7).joinToString(" ")
        .take(72).ifBlank { "Untitled note" }

    fun time(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }
}
