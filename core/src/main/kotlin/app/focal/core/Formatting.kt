package app.focal.core

/** Constants of the deterministic, word-preserving formatter (defaults: [SpeechTuning]). */
data class FormatConfig(
    val seamPauseMs: Long = SpeechTuning.SEAM_PAUSE_MS,
    val paragraphPauseMs: Long = SpeechTuning.PARAGRAPH_PAUSE_MS,
    val longPauseMs: Long = SpeechTuning.LONG_PAUSE_MS,
    val minSentences: Int = SpeechTuning.PARAGRAPH_MIN_SENTENCES,
    val maxWords: Int = SpeechTuning.PARAGRAPH_MAX_WORDS,
    val dedupeMs: Long = SpeechTuning.DEDUPE_MS,
    val minWordEndMs: Long = SpeechTuning.MIN_WORD_END_MS,
    val capWords: Int = SpeechTuning.PARAGRAPH_CAP_WORDS,
    val capLookbackWords: Int = SpeechTuning.PARAGRAPH_CAP_LOOKBACK_WORDS,
) {
    init {
        require(capWords >= 1 && capLookbackWords >= 0) { "A paragraph cap needs at least one word." }
    }
}

/** One seam repair: the period removed from word [afterWord] because word [nextWord] continues it. */
data class SeamOp(val afterWord: Int, val nextWord: Int, val before: String, val after: String)

/**
 * Seam repair R2: a window decoded without the following audio tends to end with '.'. When the next
 * window starts with a lowercase letter after a short pause, the model itself heard a continuation, so
 * that one '.' is removed. Nothing else changes: no casing, no words, no times.
 */
object SeamRepair {
    /** The repaired text of [previous], or null when the seam stays as it is. */
    fun repair(previous: Word, next: Word, seamPauseMs: Long = SpeechTuning.SEAM_PAUSE_MS): String? {
        if (next.startMs - previous.endMs >= seamPauseMs) return null
        if (!TextRules.startsLowercase(next.text)) return null
        val stem = TextRules.periodStem(previous.text) ?: return null
        if (TextRules.seamAbbreviation(stem)) return null
        val cut = stem.length
        val fixed = previous.text.removeRange(cut, cut + 1)
        // The formatter's invariant: a repair never changes a word's letters or digits.
        return fixed.takeIf { TextRules.core(it) == TextRules.core(previous.text) }
    }

    /** Repairs every seam (adjacent words from different windows) of a merged word list. */
    fun repairAll(words: List<Word>, seamPauseMs: Long = SpeechTuning.SEAM_PAUSE_MS): Pair<List<Word>, List<SeamOp>> {
        val out = words.toMutableList()
        val ops = ArrayList<SeamOp>()
        for (i in 0 until words.size - 1) {
            if (words[i].window == words[i + 1].window) continue
            val fixed = repair(out[i], out[i + 1], seamPauseMs) ?: continue
            ops += SeamOp(i, i + 1, out[i].text, fixed)
            out[i] = out[i].copy(text = fixed)
        }
        return out to ops
    }
}

/**
 * Paragraph breaks from pauses and sentence ends. A sentence end closes a paragraph after a long pause, after
 * a shorter pause once the paragraph holds enough sentences, or once it holds enough words. Any '.', '!', '?'
 * or '…' counts ([TextRules.endsSentence]): a pause that long after '3.' or 'Dr.' is a sentence end in speech.
 * Only the title knows ordinals and abbreviations.
 *
 * Speech the recognizer did not punctuate has no sentence ends, so a paragraph that reaches
 * [FormatConfig.capWords] closes anyway, at the longest pause near its end ([capCut]).
 */
object Paragraphs {
    /** Whether a paragraph closes between [previous] and [next] (call only when [previous] ends a sentence). */
    internal fun breaks(previous: Word, next: Word, sentences: Int, words: Int, config: FormatConfig): Boolean {
        val pause = next.startMs - previous.endMs
        return pause >= config.longPauseMs || (pause >= config.paragraphPauseMs && sentences >= config.minSentences) ||
            words >= config.maxWords
    }

    /**
     * Where a paragraph of [size] words (word i is [word] of i; [size] is the word that follows) that reached the
     * cap closes: the boundary b, between words b - 1 and b, with the longest pause among b in
     * [max(1, size - lookback), size]. Ties go to the latest boundary, so even pauses close it at [size].
     */
    internal inline fun capCut(size: Int, lookback: Int, word: (Int) -> Word): Int {
        var best = size
        var longest = Long.MIN_VALUE
        for (b in size downTo maxOf(1, size - lookback)) {
            val pause = word(b).startMs - word(b - 1).endMs
            if (pause > longest) { best = b; longest = pause }
        }
        return best
    }

    /** Paragraph index ranges over [words]. */
    fun split(words: List<Word>, config: FormatConfig = FormatConfig()): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        var sentences = 0
        for (j in 1 until words.size) {
            val previous = words[j - 1]
            val end = TextRules.endsSentence(previous.text)
            if (end) {
                sentences++
                if (breaks(previous, words[j], sentences, j - start, config)) {
                    out += start until j
                    start = j
                    sentences = 0
                    continue
                }
            }
            if (j - start >= config.capWords) {
                val cut = start + capCut(j - start, config.capLookbackWords) { words[start + it] }
                out += start until cut
                start = cut
                sentences = (cut until j).count { TextRules.endsSentence(words[it].text) }
            }
        }
        if (start < words.size) out += start until words.size
        return out
    }
}

/**
 * Note title from its first words: the first sentence. A sentence longer than the limit is shortened to whole
 * words: to the last clause that fits (the words up to a comma, semicolon, colon or dash) when that keeps at least
 * half of the room, and never left on a dangling function word ("…and then at the"). A shortened title carries no
 * ellipsis: the words after it follow in the note's text ([remainder]), so it reads as a heading, not a cut.
 */
object Title {
    private const val CLIP_TRIM = ",;:–—-.… "
    private const val CLAUSE_END = ",;:"
    private val DASHES = setOf("–", "—", "-")

    /** Empty when there are no words (the UI shows the date instead). Lengths are UTF-16 chars. */
    fun of(
        words: Iterable<String>,
        maxChars: Int = SpeechTuning.TITLE_MAX_CHARS,
        minChars: Int = SpeechTuning.TITLE_MIN_CHARS,
    ): String = derive(words, maxChars, minChars).first

    /**
     * What follows a derived title in [text]: the words after the ones the title shows (a shortened title shows
     * fewer words than its sentence, and the rest of the sentence follows). Rows and the stage start their preview
     * here, so a derived title is never repeated under itself. Empty when the title takes every word.
     */
    fun remainder(
        text: String,
        maxChars: Int = SpeechTuning.TITLE_MAX_CHARS,
        minChars: Int = SpeechTuning.TITLE_MIN_CHARS,
    ): String {
        val ranges = TextWords.ranges(text)
        val shown = derive(ranges.map { text.substring(it.first, it.last + 1) }, maxChars, minChars).second
        return if (shown >= ranges.size) "" else text.substring(ranges[shown].first)
    }

    /** The title and how many of the leading words it shows. */
    private fun derive(words: Iterable<String>, maxChars: Int, minChars: Int): Pair<String, Int> {
        val first = ArrayList<String>()
        var length = -1
        for (word in words) {
            first += word
            length += word.length + 1
            if (TextRules.sentenceEnd(word) && length >= minChars) break
            if (length > maxChars) break
        }
        if (first.isEmpty()) return "" to 0
        val text = first.joinToString(" ")
        // A final period goes; '?' and '!' stay, and so does the period of an ordinal or abbreviation.
        val stem = TextRules.periodStem(first.last())
        val whole = if (stem == null || TextRules.sentenceAbbreviation(stem)) text else {
            val stripped = TextRules.stripClosers(text)
            text.removeRange(stripped.length - 1, stripped.length)
        }
        if (whole.length <= maxChars) return whole to first.size
        // The whole words that fit.
        var fit = 0
        var fitLength = -1
        for (word in first) {
            if (fitLength + 1 + word.length > maxChars) break
            fitLength += 1 + word.length
            fit++
        }
        // One word longer than the limit is cut inside itself, and says so.
        if (fit == 0) return Transcript.truncateUtf16(first[0], maxChars - 1).trimEnd { it in CLIP_TRIM } + "…" to 1
        var shown = clauseEnd(first, fit, maxChars) ?: fit
        // Also after a clause end: "…поговорить о том, что…" ends its first clause on "о том".
        val functionWords = TextRules.functionWords(first)
        while (shown > MIN_WORDS && TextRules.danglingWord(first[shown - 1], functionWords)) shown--
        // A dash that would end the title goes with the words after it.
        while (shown > 1 && first[shown - 1] in DASHES) shown--
        return first.take(shown).joinToString(" ").trimEnd { it in CLIP_TRIM } to shown
    }

    /**
     * How many of the [fit] leading words end the last clause that still fills half of the room: the words up to
     * a ',', ';' or ':' or before a dash. Null when no clause ends there.
     */
    private fun clauseEnd(words: List<String>, fit: Int, maxChars: Int): Int? {
        var length = words.take(fit).sumOf { it.length } + fit - 1
        for (count in fit downTo 1) {
            if (length * 2 < maxChars) return null
            val last = TextRules.stripClosers(words[count - 1])
            val beforeDash = words.getOrNull(count) in DASHES
            if (last.lastOrNull()?.let { it in CLAUSE_END } == true || beforeDash) return count
            length -= words[count - 1].length + 1
        }
        return null
    }

    /** A shortened title keeps at least this many words, whatever they are. */
    private const val MIN_WORDS = 2
}

/**
 * Doubtful words (provisional; one switch turns the feature off): a letter or digit token below the
 * probability threshold, or `<unk>` inside the word. Punctuation-only words are never doubtful.
 */
object Doubt {
    fun isDoubtful(
        word: Word,
        enabled: Boolean = SpeechTuning.DOUBT_ENABLED,
        threshold: Double = SpeechTuning.DOUBT_THRESHOLD,
    ): Boolean {
        if (!enabled || word.punctOnly) return false
        val confidence = word.confidence
        return word.unknownPiece || (confidence != null && confidence < threshold)
    }

    /** Indices of the doubtful words. */
    fun flagged(
        words: List<Word>,
        enabled: Boolean = SpeechTuning.DOUBT_ENABLED,
        threshold: Double = SpeechTuning.DOUBT_THRESHOLD,
    ): List<Int> = words.indices.filter { isDoubtful(words[it], enabled, threshold) }
}

/** A formatted transcript: merged raw words, repaired words, paragraphs and title. */
data class FormattedTranscript(
    val rawWords: List<Word>,
    val words: List<Word>,
    val seamOps: List<SeamOp>,
    val paragraphs: List<IntRange>,
    val title: String,
) {
    fun paragraphText(index: Int): String = paragraphs[index].joinToString(" ") { words[it].text }

    /** Paragraphs joined by a blank line: what Copy puts on the clipboard. */
    val copyText: String get() = paragraphs.indices.joinToString("\n\n") { paragraphText(it) }
}

/** Batch formatting of a whole note; [TranscriptAssembler] produces the same result window by window. */
object Formatter {
    fun format(windows: List<DecodedWindow>, config: FormatConfig = FormatConfig()): FormattedTranscript {
        val raw = WindowMerge.merge(windows, config.dedupeMs, config.minWordEndMs)
        val (words, ops) = SeamRepair.repairAll(raw, config.seamPauseMs)
        return FormattedTranscript(raw, words, ops, Paragraphs.split(words, config), Title.of(words.map { it.text }))
    }
}

/**
 * Incremental formatter for the speech worker: windows arrive in order and the paragraphs grow. Only the
 * last paragraph is open; a closed paragraph never changes again, so the worker can persist each update
 * with the window it came from. Feeding all windows gives exactly [Formatter.format]. A paragraph closes at
 * [FormatConfig.capWords] words at the latest, so the open paragraph the worker stores and reads back stays
 * bounded however long the speech runs without a sentence end.
 *
 * Restore after process death with the words of the open paragraph as stored. Start with an empty
 * open paragraph when the last stored paragraph has no word timings (legacy text): new words then start
 * a new paragraph.
 */
class TranscriptAssembler(
    private val config: FormatConfig = FormatConfig(),
    openParagraph: List<Word> = emptyList(),
) {
    private val open = openParagraph.toMutableList()
    private var sentences = openParagraph.dropLast(1).count { TextRules.endsSentence(it.text) }

    /**
     * The result of one window. [paragraphs] is empty when nothing changed. Otherwise its first entry
     * replaces the open paragraph (it is new when there was none), the following entries are new, and only
     * the last one is still open. [seamOp] indices are positions in the open paragraph before the update.
     */
    data class Update(val paragraphs: List<List<Word>>, val seamOp: SeamOp?)

    /** The open paragraph's words. */
    val openWords: List<Word> get() = open.toList()

    fun append(window: DecodedWindow, windowIndex: Int): Update {
        val fresh = WindowMerge.keptWords(window, windowIndex, open.lastOrNull(), config.dedupeMs, config.minWordEndMs)
        if (fresh.isEmpty()) return Update(emptyList(), null)
        val result = ArrayList<List<Word>>()
        var seamOp: SeamOp? = null
        val last = open.lastOrNull()
        if (last != null && last.window != fresh.first().window) {
            SeamRepair.repair(last, fresh.first(), config.seamPauseMs)?.let { fixed ->
                seamOp = SeamOp(open.size - 1, open.size, last.text, fixed)
                open[open.size - 1] = last.copy(text = fixed)
            }
        }
        for (word in fresh) {
            val previous = open.lastOrNull()
            if (previous != null && TextRules.endsSentence(previous.text)) {
                sentences++
                if (Paragraphs.breaks(previous, word, sentences, open.size, config)) {
                    result += open.toList()
                    open.clear()
                    sentences = 0
                }
            }
            if (open.size >= config.capWords) {
                // The words after the cut carry over into the next paragraph, which stays open.
                val cut = Paragraphs.capCut(open.size, config.capLookbackWords) { if (it == open.size) word else open[it] }
                result += open.subList(0, cut).toList()
                val carried = open.subList(cut, open.size).toList()
                open.clear()
                open += carried
                sentences = carried.count { TextRules.endsSentence(it.text) }
            }
            open += word
        }
        result += open.toList()
        return Update(result, seamOp)
    }
}
