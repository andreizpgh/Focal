package app.focal.core

import java.util.Locale

/**
 * Turns repeated ephemeral decodes of the currently open recording tail into calm, readable live preview text:
 * - While the speaker is mid-utterance ([paused] == false), trims words ending inside the trailing unstable
 *   margin so half-spoken syllables never surface as garbled words.
 * - Withholds noisy decodes where too many words have low token confidence or `<unk>` pieces (common before
 *   Parakeet Ultra has enough acoustic context to lock the spoken language).
 * - Uses consecutive-pass prefix agreement while speaking continuously so shown words grow smoothly forward
 *   instead of rewriting 2-3 times per sentence, while still revealing complete phrases promptly after a brief
 *   natural pause.
 *
 * Pure and in-memory only: committed windows and stored raw ASR remain completely untouched.
 */
class LivePreviewStabilizer(
    private val unstableTailMs: Long = SpeechTuning.LIVE_PREVIEW_UNSTABLE_TAIL_MS,
    private val minInitialWords: Int = SpeechTuning.LIVE_PREVIEW_MIN_WORDS,
    private val maxDoubtRatio: Double = SpeechTuning.LIVE_PREVIEW_MAX_DOUBT_RATIO,
    private val initialStrictDoubtRatio: Double = SpeechTuning.LIVE_PREVIEW_INITIAL_DOUBT_RATIO,
    private val doubtThreshold: Double = SpeechTuning.DOUBT_THRESHOLD,
) {
    private var openTailStartMs: Long = -1L
    private var lastCandidate: List<Word> = emptyList()
    private var shownWords: List<Word> = emptyList()

    /** Clears all state (called when a committed window lands or a new recording starts). */
    fun reset() {
        openTailStartMs = -1L
        lastCandidate = emptyList()
        shownWords = emptyList()
    }

    /**
     * Incorporates one ephemeral preview decode of `[tailStartMs, tailEndMs]` and returns the preview text to
     * display (or null when nothing reliable should be shown yet).
     */
    fun accept(tailStartMs: Long, tailEndMs: Long, words: List<Word>, paused: Boolean): String? {
        if (tailStartMs != openTailStartMs) {
            reset()
            openTailStartMs = tailStartMs
        }
        val candidate = trimTrailing(words, tailEndMs, paused)
        if (candidate.isEmpty()) return format(shownWords)

        val content = candidate.filterNot { it.punctOnly }
        if (content.isEmpty()) return format(shownWords)

        val doubtRatio = content.count(::isDoubtful).toDouble() / content.size
        // Always withhold broadly noisy/hallucinated decodes; keep whatever stable text was already shown.
        if (doubtRatio > maxDoubtRatio) {
            lastCandidate = candidate
            return format(shownWords)
        }

        if (paused) {
            // Brief natural pause (>= 350 ms silence): the phrase is acoustically complete and confident enough.
            if (shownWords.isEmpty() && content.size == 1 && isDoubtful(content.first())) {
                lastCandidate = candidate
                return null
            }
            shownWords = candidate
            lastCandidate = candidate
            return format(shownWords)
        }

        // Speaker is actively talking (no pause yet): balance responsiveness against stability.
        if (shownWords.isEmpty()) {
            val agreed = agreedPrefix(lastCandidate, candidate)
            val chosen = when {
                agreed.count { !it.punctOnly } >= minInitialWords -> agreed
                content.size >= minInitialWords && doubtRatio <= initialStrictDoubtRatio -> candidate
                else -> emptyList()
            }
            if (chosen.isNotEmpty()) shownWords = chosen
            lastCandidate = candidate
            return format(shownWords)
        }

        // Already showing a preview for this open window: advance cleanly via agreement with the previous pass so
        // earlier words stay calm instead of jumping back and forth while the speaker continues.
        val agreed = agreedPrefix(lastCandidate, candidate)
        val stable = extendConfidently(agreed, candidate)
        if (stable.size >= shownWords.size) {
            shownWords = stable
        } else if (candidate.size >= shownWords.size + 2 && doubtRatio <= initialStrictDoubtRatio) {
            // Substantial new context resolved an earlier misheard phrase cleanly: allow a clean upgrade.
            shownWords = candidate
        }
        lastCandidate = candidate
        return format(shownWords)
    }

    private fun trimTrailing(words: List<Word>, tailEndMs: Long, paused: Boolean): List<Word> {
        if (words.isEmpty() || paused) return words
        val cutoff = tailEndMs - unstableTailMs
        var end = words.size
        while (end > 0 && words[end - 1].endMs > cutoff) end--
        return words.subList(0, end)
    }

    private fun isDoubtful(word: Word): Boolean =
        word.unknownPiece || (word.confidence != null && word.confidence < doubtThreshold)

    private fun agreedPrefix(previous: List<Word>, current: List<Word>): List<Word> {
        val n = minOf(previous.size, current.size)
        var i = 0
        while (i < n && normalize(previous[i].text) == normalize(current[i].text)) i++
        return current.subList(0, i)
    }

    private fun extendConfidently(agreed: List<Word>, candidate: List<Word>): List<Word> {
        var end = agreed.size
        while (end < candidate.size) {
            val w = candidate[end]
            if (!w.punctOnly && isDoubtful(w)) break
            end++
        }
        return candidate.subList(0, end)
    }

    private fun normalize(text: String): String =
        text.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)

    private fun format(words: List<Word>): String? =
        words.joinToString(" ") { it.text }.trim().takeIf { it.isNotEmpty() }
}
