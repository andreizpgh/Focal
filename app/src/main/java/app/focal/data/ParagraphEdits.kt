package app.focal.data

import app.focal.core.Alignment
import app.focal.core.Word

/**
 * The stored content of a paragraph that Split and Merge redistribute: current text, its source (the
 * formatted recognizer text; null = unknown) and the timed source words (null = no word times). Times are
 * never invented: a part without source words keeps the time range of the paragraph it came from.
 */
internal data class ParagraphContent(
    val text: String,
    val sourceText: String?,
    val words: List<Word>?,
    val startMs: Long,
    val endMs: Long,
    val edited: Boolean,
)

internal object ParagraphEdits {
    private val TOKEN = Regex("\\S+")

    /**
     * Splits [content] at char [offset] of its text. Source words and source text are divided where the
     * edited text was divided, using the word alignment that maps edits back onto the source.
     */
    fun split(content: ParagraphContent, offset: Int): Pair<ParagraphContent, ParagraphContent> {
        require(offset in 0..content.text.length)
        val leftText = content.text.substring(0, offset).trimEnd()
        val rightText = content.text.substring(offset).trimStart()
        val words = content.words
        val source = content.sourceText
        val wordCut = words?.let { cut(it, content.text, offset, leftText.isBlank()) }
        val sourceIsWords = words != null && source == words.joinToString(" ") { it.text }
        val (leftSource, rightSource) = when {
            source == null -> null to null
            sourceIsWords -> words!!.subList(0, wordCut!!).joinToString(" ") { it.text } to
                words.subList(wordCut, words.size).joinToString(" ") { it.text }
            else -> {
                val tokens = TOKEN.findAll(source).toList()
                val pseudo = tokens.map { Word(it.value, 0, 0, null, false, 0) }
                val at = cut(pseudo, content.text, offset, leftText.isBlank())
                val position = if (at < tokens.size) tokens[at].range.first else source.length
                source.substring(0, position).trimEnd() to source.substring(position).trimStart()
            }
        }
        fun part(text: String, partSource: String?, partWords: List<Word>?): ParagraphContent {
            val known = partSource?.takeIf { it.isNotEmpty() }
            val timed = partWords?.takeIf { it.isNotEmpty() }
            return ParagraphContent(
                text, known, timed,
                timed?.first()?.startMs ?: content.startMs, timed?.last()?.endMs ?: content.endMs,
                if (known != null) text != known else content.edited,
            )
        }
        return part(leftText, leftSource, words?.subList(0, wordCut!!)) to
            part(rightText, rightSource, words?.subList(wordCut!!, words.size))
    }

    /** Joins two adjacent paragraphs with one space. */
    fun merge(first: ParagraphContent, second: ParagraphContent): ParagraphContent {
        val text = listOf(first.text.trim(), second.text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        val source = if (first.sourceText != null && second.sourceText != null)
            listOf(first.sourceText, second.sourceText).filter { it.isNotEmpty() }.joinToString(" ") else null
        val words = if (first.words == null && second.words == null) null
        else first.words.orEmpty() + second.words.orEmpty()
        return ParagraphContent(
            text, source, words, minOf(first.startMs, second.startMs), maxOf(first.endMs, second.endMs),
            if (source != null) text != source else first.edited || second.edited,
        )
    }

    /**
     * Index of the first source word that belongs after [offset] of [text]: the source of the first aligned
     * word that ends after the offset, else one past the last aligned word before it.
     */
    private fun cut(source: List<Word>, text: String, offset: Int, leftBlank: Boolean): Int {
        if (leftBlank) return 0
        val aligned = Alignment.align(source, text)
        aligned.firstOrNull { it.end > offset && it.sourceIndex != null }?.let { return it.sourceIndex!! }
        aligned.lastOrNull { it.sourceIndex != null }?.let { return it.sourceIndex!! + 1 }
        return source.size
    }
}

/** Which parts of a saved recording no window covers yet. */
internal object Coverage {
    data class Range(val startMs: Long, val endMs: Long)

    /** [0, endMs) minus the union of [covered] ranges, in order. */
    fun uncovered(covered: List<Range>, endMs: Long): List<Range> {
        val out = ArrayList<Range>()
        var through = 0L
        for (range in covered.sortedWith(compareBy({ it.startMs }, { it.endMs }))) {
            if (range.endMs <= range.startMs) continue
            if (range.startMs > through) out += Range(through, minOf(range.startMs, endMs))
            through = maxOf(through, range.endMs)
            if (through >= endMs) break
        }
        if (through < endMs) out += Range(through, endMs)
        return out.filter { it.endMs > it.startMs }
    }
}
