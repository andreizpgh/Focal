package app.focal.core

/**
 * A word of an edited paragraph. [start] and [end] are its char range in the edited text. Times come from
 * the source word it was matched to and are null for changed or inserted words: they are never invented.
 * [doubtful] survives only when the word is literally unchanged, so editing a word clears its doubt.
 */
data class AlignedWord(
    val text: String,
    val start: Int,
    val end: Int,
    val sourceIndex: Int?,
    val startMs: Long?,
    val endMs: Long?,
    val doubtful: Boolean,
)

/**
 * Maps an edited paragraph back onto the timed words it was made from. Words are matched in order (a
 * longest common subsequence) by their letters and digits, so case and punctuation edits keep a word's
 * times; anything else gets none.
 */
object Alignment {
    /** Above this many cells the middle part of a heavy rewrite is left unmatched (bounded memory). */
    private const val MAX_TABLE_CELLS = 250_000L
    private val TOKEN = Regex("\\S+")

    fun align(source: List<Word>, edited: String): List<AlignedWord> {
        val tokens = TOKEN.findAll(edited).toList()
        val sourceKeys = source.map { key(it.text) }
        val editedKeys = tokens.map { key(it.value) }
        val match = arrayOfNulls<Int>(tokens.size)
        var head = 0
        while (head < tokens.size && head < source.size && editedKeys[head] == sourceKeys[head]) {
            match[head] = head
            head++
        }
        var tailEdited = tokens.size
        var tailSource = source.size
        while (tailEdited > head && tailSource > head && editedKeys[tailEdited - 1] == sourceKeys[tailSource - 1]) {
            tailEdited--
            tailSource--
            match[tailEdited] = tailSource
        }
        matchMiddle(sourceKeys, editedKeys, head, tailSource, head, tailEdited, match)
        return tokens.mapIndexed { i, token ->
            val s = match[i]
            val word = s?.let(source::get)
            AlignedWord(
                text = token.value,
                start = token.range.first,
                end = token.range.last + 1,
                sourceIndex = s,
                startMs = word?.startMs,
                endMs = word?.endMs,
                doubtful = word != null && word.text == token.value && Doubt.isDoubtful(word),
            )
        }
    }

    /**
     * Where to start playback for a tap at [charOffset] of the edited text: the start of the tapped word if
     * it kept its time, else of the nearest earlier timed word. Null means "play from the paragraph start".
     */
    fun seekMs(words: List<AlignedWord>, charOffset: Int): Long? {
        var tapped = words.indexOfLast { it.start <= charOffset }
        if (tapped < 0) return null
        while (tapped >= 0) {
            words[tapped].startMs?.let { return it }
            tapped--
        }
        return null
    }

    private fun key(text: String): String = TextRules.core(text).ifEmpty { "\u0000$text" }

    /** Longest common subsequence of source[s0, s1) and edited[e0, e1), written into [match]. */
    private fun matchMiddle(
        source: List<String>, edited: List<String>, s0: Int, s1: Int, e0: Int, e1: Int, match: Array<Int?>,
    ) {
        val n = s1 - s0
        val m = e1 - e0
        if (n <= 0 || m <= 0 || (n + 1).toLong() * (m + 1) > MAX_TABLE_CELLS) return
        // lcs[i][j] = LCS length of source[s0 + i, s1) and edited[e0 + j, e1)
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (source[s0 + i] == edited[e0 + j]) lcs[i + 1][j + 1] + 1
            else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                source[s0 + i] == edited[e0 + j] -> { match[e0 + j] = s0 + i; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }
    }
}
