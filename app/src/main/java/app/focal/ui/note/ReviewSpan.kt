package app.focal.ui.note

import app.focal.core.TextWords
import app.focal.ui.state.ParagraphUi
import java.io.Serializable
import java.text.Normalizer

/** Offsets are valid only for this stored revision. Small enough to retain across recreation. */
internal data class ReviewSpan(val paragraphId: Long, val revision: Long, val start: Int, val end: Int) : Serializable {
    fun text(paragraph: ParagraphUi) = paragraph.text.substring(start, end)
}

internal fun ParagraphUi.reviewSpan(range: IntRange): ReviewSpan =
    ReviewSpan(id, revision, range.first, range.last + 1)

/** Consecutive doubtful words can be heard and replaced as one phrase. */
internal fun ParagraphUi.unclearSpans(): List<ReviewSpan> = buildList {
    var i = 0
    while (i < words.size) {
        if (!words[i].doubtful) { i++; continue }
        val first = i
        while (i + 1 < words.size && words[i + 1].doubtful &&
            !TextWords.endsSentence(text.substring(words[i].start, words[i].end))) i++
        val range = TextWords.lexicalRange(text, words[first].start, words[i++].end)
        if (range != null) add(reviewSpan(range))
    }
}

/** A nearby spelling already used in the note. Bound the search even for imported, very long paragraphs. */
internal fun nearbySpellings(paragraphs: List<ParagraphUi>, span: ReviewSpan): List<String> {
    val index = paragraphs.indexOfFirst { it.id == span.paragraphId }
    if (index < 0) return emptyList()
    val paragraph = paragraphs[index]
    val selected = span.text(paragraph)
    val key = spellingKey(selected)
    if (key.length !in 4..40 || !key.all(TextWords::isLetterOrMark)) return emptyList()
    val candidates = linkedMapOf<String, String>()
    fun inspect(p: ParagraphUi, range: IntRange) {
        for (i in range) {
            val word = p.words[i]
            if (word.doubtful || word.end - word.start > 44) continue
            val range = TextWords.lexicalRange(p.text, word.start, word.end) ?: continue
            val value = p.text.substring(range)
            val other = spellingKey(value)
            if (other.all(TextWords::isLetterOrMark) && oneEditApart(key, other)) candidates.putIfAbsent(other, value)
        }
    }
    val hit = paragraph.words.binarySearchBy(span.start) { it.end }.let { if (it < 0) -it - 1 else it }
    inspect(paragraph, maxOf(0, hit - 48)..minOf(paragraph.words.lastIndex, hit + 48))
    paragraphs.getOrNull(index - 1)?.let { inspect(it, maxOf(0, it.words.size - 32)..it.words.lastIndex) }
    paragraphs.getOrNull(index + 1)?.let { inspect(it, 0 until minOf(32, it.words.size)) }
    return candidates.values.take(3)
}

internal fun spellingKey(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)

/** Conflicting edits in this note give no reliable suggestion for another occurrence. */
internal fun previousWording(paragraphs: List<ParagraphUi>, selected: String): String? {
    if (selected.length > 80) return null
    val key = spellingKey(selected)
    var found: String? = null
    for (paragraph in paragraphs) for (wording in paragraph.reviewedWording) {
        if (spellingKey(wording.original) != key || spellingKey(wording.replacement) == key) continue
        if (found != null && found != wording.replacement) return null
        found = wording.replacement
    }
    return found
}

private fun oneEditApart(a: String, b: String): Boolean {
    if (a == b || kotlin.math.abs(a.length - b.length) > 1) return false
    val prefix = a.commonPrefixWith(b).length
    val suffix = a.commonSuffixWith(b).length
    return prefix + suffix >= maxOf(a.length, b.length) - 1
}
