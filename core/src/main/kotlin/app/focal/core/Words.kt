package app.focal.core

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

/**
 * One recognizer token as sherpa-onnx returns it: piece text (a leading space or U+2581 starts a word),
 * start and duration in seconds relative to the decoded audio, and the token's log-probability
 * (null when the result did not carry one).
 */
data class AsrToken(val text: String, val startSec: Double, val durationSec: Double, val logProb: Double? = null)

/**
 * A decoded window: its tokens, where its decoded audio starts in the recording, and the keep filter
 * (a word is kept when its midpoint lies in [keepLoMs, keepHiMs); null means unbounded).
 */
data class DecodedWindow(
    val audioStartMs: Long,
    val keepLoMs: Long?,
    val keepHiMs: Long?,
    val tokens: List<AsrToken>,
)

/**
 * A word with absolute times in the recording. [confidence] is the lowest probability of its letter or
 * digit tokens (null for punctuation-only words or unknown scores). [window] is the index of the window
 * that produced it. [unknownPiece] means the recognizer emitted `<unk>` inside the word, so its text is
 * missing a character.
 */
data class Word(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Double?,
    val punctOnly: Boolean,
    val window: Int,
    val unknownPiece: Boolean = false,
)

/**
 * Builds words from one window's tokens. Joining the words with spaces gives sherpa's text without `<unk>`,
 * except that a space stays before punctuation that opens a word or stands alone ('$50', '(page', ' - '),
 * which sherpa glues to the word on its left.
 */
object WordBuilder {
    private val SPECIAL = Regex("^<\\|?[^<>]*\\|?>$")

    /** Closing punctuation: after a space it still joins the word on its left (audit L6). */
    private const val ATTACH_LEFT = ",.;:!?%)]}"

    /** Seconds to integer milliseconds, rounding half up. */
    fun ms(seconds: Double): Long = floor(seconds * 1000 + 0.5).toLong()

    fun build(window: DecodedWindow, windowIndex: Int, minWordEndMs: Long = SpeechTuning.MIN_WORD_END_MS): List<Word> {
        val all = window.tokens
        val texts = all.map { it.text.replace('▁', ' ') }
        val special = texts.map { SPECIAL.matches(it.trim()) }
        val logProbs = all.mapTo(ArrayList()) { it.logProb }
        val unknown = BooleanArray(all.size)
        // A special piece (<unk>) right after a letter/digit piece stands for part of that word: the word is
        // marked and the piece lends its probability. After a space or punctuation piece (how Ultra emits a
        // dash) it has no effect.
        for (k in 1 until all.size) {
            if (!special[k] || special[k - 1] || !TextRules.hasAlnum(texts[k - 1])) continue
            unknown[k - 1] = true
            val lent = logProbs[k]
            val own = logProbs[k - 1]
            if (lent != null && own != null) logProbs[k - 1] = min(own, lent)
        }
        val kept = all.indices.filter { !special[it] }
        val pieces = kept.map { texts[it] }
        val groups = ArrayList<MutableList<Int>>()
        for (i in pieces.indices) {
            if (groups.isEmpty() || startsWord(pieces, i)) groups += mutableListOf(i) else groups.last() += i
        }
        val words = ArrayList<Word>(groups.size)
        for (group in groups) {
            val surface = group.joinToString("") { pieces[it] }.replace(" ", "")
            if (surface.isEmpty()) continue
            val content = group.filter { TextRules.hasAlnum(pieces[it].trim()) }
            val timing = content.ifEmpty { group }
            val first = all[kept[group.first()]]
            val last = all[kept[timing.last()]]
            val startMs = window.audioStartMs + ms(first.startSec)
            val lastMs = window.audioStartMs + ms(last.startSec)
            val endMs = maxOf(lastMs + ms(last.durationSec), lastMs + minWordEndMs)
            val scores = content.map { logProbs[kept[it]] }
            val confidence = if (content.isEmpty() || scores.any { it == null }) null
            else scores.minOf { exp(it!!) }
            val hasUnknown = group.any { unknown[kept[it]] }
            words += Word(surface, startMs, endMs, confidence, content.isEmpty(), windowIndex, hasUnknown)
        }
        return words
    }

    /**
     * A piece with a leading space starts a word unless its next visible character is closing punctuation
     * ([ATTACH_LEFT]), which joins the previous word. sherpa's `RemoveSpaceBetweenCjk` (text-utils.cc) glues
     * every ASCII punctuation mark to the left ('costs$50', 'thing-'); '$', '(', quotes and ' -' start a word
     * here instead.
     */
    private fun startsWord(pieces: List<String>, i: Int): Boolean {
        val piece = pieces[i]
        if (!piece.startsWith(' ')) return false
        var rest = piece.trimStart(' ')
        var k = i + 1
        while (rest.isEmpty() && k < pieces.size && !pieces[k].startsWith(' ')) rest = pieces[k++]
        return !(rest.isNotEmpty() && rest[0] in ATTACH_LEFT)
    }
}

/** Joins windows into one word sequence using their keep filters and the overlap duplicate guard. */
object WindowMerge {
    /**
     * The words of [window] that it owns: midpoint inside its keep range, and, at an overlapped seam,
     * not a repeat of [last] (same letters, starting less than [dedupeMs] from it).
     */
    fun keptWords(
        window: DecodedWindow,
        windowIndex: Int,
        last: Word?,
        dedupeMs: Long = SpeechTuning.DEDUPE_MS,
        minWordEndMs: Long = SpeechTuning.MIN_WORD_END_MS,
    ): List<Word> {
        val lo = window.keepLoMs
        val hi = window.keepHiMs
        val out = ArrayList<Word>()
        var first = true
        for (word in WordBuilder.build(window, windowIndex, minWordEndMs)) {
            val mid2 = word.startMs + word.endMs
            if (lo != null && mid2 < 2 * lo) continue
            if (hi != null && mid2 >= 2 * hi) continue
            if (first) {
                first = false
                if (lo != null && last != null && isRepeat(last, word, dedupeMs)) continue
            }
            out += word
        }
        return out
    }

    fun merge(
        windows: List<DecodedWindow>,
        dedupeMs: Long = SpeechTuning.DEDUPE_MS,
        minWordEndMs: Long = SpeechTuning.MIN_WORD_END_MS,
    ): List<Word> {
        val out = ArrayList<Word>()
        windows.forEachIndexed { index, window ->
            out += keptWords(window, index, out.lastOrNull(), dedupeMs, minWordEndMs)
        }
        return out
    }

    private fun isRepeat(last: Word, word: Word, dedupeMs: Long): Boolean {
        val key = TextRules.core(word.text)
        return key.isNotEmpty() && key == TextRules.core(last.text) && kotlin.math.abs(word.startMs - last.startMs) < dedupeMs
    }
}
