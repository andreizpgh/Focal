package app.focal.data

import java.text.Normalizer

/**
 * Text matching shared by library search (SQLite FTS4, unicode61 tokenizer) and Find in note.
 *
 * unicode61 folds case and removes diacritics from Latin letters, but it keeps Cyrillic letters as they
 * are, so 'ё' is folded to 'е' explicitly: in the indexed view (SQL replace) and in every query.
 * [fold] maps each UTF-16 char to exactly one char, so match offsets in folded text are offsets in the
 * original text.
 */
internal object SearchText {
    /** Longest query that is searched; longer input is cut. */
    const val MAX_QUERY_CHARS = 200
    private const val MAX_TERMS = 16
    private const val SNIPPET_BEFORE = 40
    private const val SNIPPET_CHARS = 160

    fun fold(c: Char): Char {
        if (c == 'ё' || c == 'Ё') return 'е'
        val lower = c.lowercaseChar()
        // Precomposed Latin letters lose their marks, like unicode61 remove_diacritics.
        if (lower.code !in 0xC0..0x24F && lower.code !in 0x1E00..0x1EFF) return lower
        val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
        val base = decomposed[0]
        return if (decomposed.length > 1 && base.code < 0x80 && base.isLetter() &&
            decomposed.drop(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }) base else lower
    }

    fun fold(text: String): String {
        val out = CharArray(text.length)
        for (i in text.indices) out[i] = fold(text[i])
        return String(out)
    }

    /** How the indexed SQL view folds stored text before unicode61 tokenizes it. */
    const val SQL_FOLD_PREFIX = "replace(replace("
    const val SQL_FOLD_SUFFIX = ",'ё','е'),'Ё','Е')"

    private fun isTokenChar(c: Char): Boolean = c.isLetterOrDigit() ||
        Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    /** Query terms as unicode61 would split them, folded. */
    fun terms(query: String): List<String> {
        val text = fold(query.take(MAX_QUERY_CHARS))
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length && out.size < MAX_TERMS) {
            while (i < text.length && !isTokenChar(text[i])) i++
            val start = i
            while (i < text.length && isTokenChar(text[i])) i++
            if (i > start) out += text.substring(start, i)
        }
        return out
    }

    /**
     * An FTS4 MATCH expression requiring every term; the last term matches as a prefix so results appear
     * while typing. Terms hold only letters, digits and marks, so quoting them is safe.
     */
    fun matchExpression(terms: List<String>): String? {
        if (terms.isEmpty()) return null
        return terms.mapIndexed { i, term -> if (i == terms.lastIndex) "\"$term*\"" else "\"$term\"" }
            .joinToString(" ")
    }

    /** The first word of [text] matched by [terms] (the last as a prefix), as a char range, or null. */
    fun firstHit(text: String, terms: List<String>): IntRange? {
        if (terms.isEmpty()) return null
        val folded = fold(text)
        var i = 0
        while (i < folded.length) {
            while (i < folded.length && !isTokenChar(folded[i])) i++
            val start = i
            while (i < folded.length && isTokenChar(folded[i])) i++
            if (i == start) break
            val word = folded.substring(start, i)
            for ((index, term) in terms.withIndex()) {
                if (word == term || (index == terms.lastIndex && word.startsWith(term))) return start until i
            }
        }
        return null
    }

    /** About [SNIPPET_CHARS] chars of [text] around [hit], cut at spaces, with ellipses where it was cut. */
    fun snippet(text: String, hit: IntRange?): Pair<String, Int> {
        val at = hit?.first ?: 0
        var from = (at - SNIPPET_BEFORE).coerceAtLeast(0)
        if (from > 0) {
            val space = text.indexOf(' ', from)
            from = if (space in from until at) space + 1 else from
        }
        var to = (from + SNIPPET_CHARS).coerceAtMost(text.length)
        if (to < text.length) {
            val space = text.lastIndexOf(' ', to)
            if (space > (hit?.last ?: at)) to = space
        }
        val prefix = if (from > 0) "…" else ""
        val body = text.substring(from, to).replace('\n', ' ')
        return (prefix + body + if (to < text.length) "…" else "") to (at - from + prefix.length)
    }

    /** Non-overlapping case-, ё- and Latin-diacritic-insensitive occurrences of [query] in [text]. */
    fun occurrences(text: String, query: String): List<IntRange> {
        if (query.isEmpty() || query.length > text.length) return emptyList()
        val haystack = fold(text)
        val needle = fold(query)
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            out += at until at + needle.length
            from = at + needle.length
        }
        return out
    }

    /** [text] with every [occurrences] range replaced by [replacement]. */
    fun replace(text: String, ranges: List<IntRange>, replacement: String): String {
        if (ranges.isEmpty()) return text
        val out = StringBuilder(text.length)
        var last = 0
        for (range in ranges) {
            out.append(text, last, range.first).append(replacement)
            last = range.last + 1
        }
        return out.append(text, last, text.length).toString()
    }
}
