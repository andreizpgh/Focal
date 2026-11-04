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
            // Manual text stays intact, but a NUL cannot enter Whisper's C-string
            // prompt. Stop the contiguous tail rather than rewriting the correction.
            if (segment.uncertain || segment.text.isBlank() || '\u0000' in segment.text) break
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
        val source = trailingWordKeys(previous.filter { it.end > chunkStart && it.start < overlapEnd }.map { it.text })
        val duplicated = current.takeWhile { it.start < overlapEnd && it.end <= overlapEnd }
        var wordsToDrop = matchingPrefixWords(source, leadingWordKeys(duplicated.map { it.text }), minimum = 1)
        val result = current.mapNotNull { segment ->
            if (segment in duplicated && wordsToDrop > 0) {
                val count = WORD.findAll(segment.text).take(wordsToDrop).count()
                val removed = minOf(wordsToDrop, count)
                wordsToDrop -= removed
                segment.copy(text = dropWords(segment.text, removed), uncertain = true).takeIf { it.text.isNotBlank() }
            } else if (segment.start < overlapEnd && segment.end <= overlapEnd) {
                // A different hypothesis for the same audio needs a human decision.
                segment.copy(uncertain = true)
            } else if (segment.start < overlapEnd && segment.end > overlapEnd) {
                val prefix = leadingWordKeys(listOf(segment.text))
                if (source.any { it == null } || prefix.any { it == null } || matchingPrefixWords(source, prefix, minimum = 1) > 0)
                    segment.copy(uncertain = true)
                else segment
            } else segment
        }
        // Even a conflicting (retained) old-audio hypothesis has not accounted for
        // the new audio. Preserve both the alternative and the missing-range cue.
        return if (overlapEnd < chunkEnd && result.none { it.end > overlapEnd })
            result + Segment(overlapEnd, chunkEnd, "", uncertain = true)
        else result
    }

    private val WORD = Regex("\\S+")
    private const val OVERLAP_WORDS = 12
    private const val MAX_OVERLAP_WORD_CHARS = 4096

    private fun wordKey(word: MatchResult): String? {
        // A pathological manual token is not evidence for deleting spoken text.
        // Inspect its range before copying or normalizing the potentially huge word.
        if (word.range.last - word.range.first >= MAX_OVERLAP_WORD_CHARS) return null
        return Normalizer.normalize(word.value, Normalizer.Form.NFC).lowercase().trim { !it.isLetterOrDigit() }
    }

    private fun trailingWordKeys(texts: List<String>): List<String?> {
        val tail = ArrayDeque<MatchResult>(OVERLAP_WORDS)
        for (text in texts.asReversed()) {
            val remaining = OVERLAP_WORDS - tail.size
            val segmentTail = ArrayDeque<MatchResult>(remaining)
            for (word in WORD.findAll(text)) {
                if (segmentTail.size == remaining) segmentTail.removeFirst()
                segmentTail.addLast(word)
            }
            segmentTail.asReversed().forEach(tail::addFirst)
            if (tail.size == OVERLAP_WORDS) break
        }
        return tail.map(::wordKey)
    }

    private fun leadingWordKeys(texts: List<String>): List<String?> {
        val head = ArrayList<String?>(OVERLAP_WORDS)
        for (text in texts) {
            for (word in WORD.findAll(text).take(OVERLAP_WORDS - head.size)) head += wordKey(word)
            if (head.size == OVERLAP_WORDS) break
        }
        return head
    }

    private fun matchingPrefixWords(previous: String, next: String, minimum: Int): Int =
        matchingPrefixWords(trailingWordKeys(listOf(previous)), leadingWordKeys(listOf(next)), minimum)

    private fun matchingPrefixWords(left: List<String?>, right: List<String?>, minimum: Int): Int {
        for (count in minOf(left.size, right.size) downTo minimum) {
            val prefix = right.take(count)
            if (prefix.all { !it.isNullOrBlank() } && left.takeLast(count) == prefix) return count
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

    /** Keep a UTF-16 prefix without cutting a surrogate pair. */
    fun truncateUtf16(text: String, maxChars: Int): String {
        require(maxChars >= 0)
        if (text.length <= maxChars) return text
        val end = if (maxChars > 0 && text[maxChars - 1].isHighSurrogate() && text[maxChars].isLowSurrogate())
            maxChars - 1 else maxChars
        return text.substring(0, end)
    }

    fun title(text: String): String = truncateUtf16(text.trim().split(Regex("\\s+")).take(7).joinToString(" "), 72)
        .ifBlank { "Untitled note" }

    fun time(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }
}
