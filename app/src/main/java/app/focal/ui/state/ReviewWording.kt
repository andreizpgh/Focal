package app.focal.ui.state

import app.focal.core.AlignedWord
import app.focal.core.TextWords
import app.focal.core.Word

/** Short replacements visible between matching words in a saved, edited paragraph. */
data class ReviewWording(val original: String, val replacement: String)

internal fun reviewWording(source: List<Word>, aligned: List<AlignedWord>): List<ReviewWording> = buildList {
    var fromSource = 0
    var fromEdit = 0
    for (at in 0..aligned.size) {
        val nextSource = if (at == aligned.size) source.size else aligned[at].sourceIndex ?: continue
        val sourceCount = nextSource - fromSource
        val editCount = at - fromEdit
        // Require an unchanged neighbour and a short substitution on both sides. Pure insertions,
        // deletions and paragraphs with no matching words have no attributable replacement.
        if ((fromEdit > 0 || at < aligned.size) && sourceCount in 1..3 && editCount in 1..3) {
            val original = reviewPhrase(source.subList(fromSource, nextSource).map { it.text })
            val replacement = reviewPhrase(aligned.subList(fromEdit, at).map { it.text })
            if (original != null && replacement != null && original != replacement) add(ReviewWording(original, replacement))
        }
        fromSource = nextSource + 1
        fromEdit = at + 1
    }
}

private fun reviewPhrase(words: List<String>): String? {
    if (words.any { it.length > 64 }) return null
    val text = words.joinToString(" ")
    val lexical = TextWords.lexicalRange(text, 0, text.length) ?: return null
    val value = text.substring(lexical)
    return value.takeIf { it.length <= 80 && it.all { c ->
        TextWords.isLetterOrMark(c) || c.isDigit() || c == ' ' || c == '-' || c == '\'' || c == '’'
    } }
}
