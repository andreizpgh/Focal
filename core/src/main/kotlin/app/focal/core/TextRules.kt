package app.focal.core

import java.text.Normalizer
import java.util.Locale

/**
 * Character rules shared by the word builder and the formatter. Unicode-aware; they follow Python's
 * `str.isalnum`, `str.isupper` and `re` classes as used by the reference implementation.
 */
internal object TextRules {
    private const val SENTENCE_END = ".!?…"
    const val CLOSERS = "\"'»”’)]"
    const val OPENERS = "\"'«„“(¿¡"

    /** Abbreviations whose period never ends a sentence (compared case-insensitively, letters only). */
    private val ABBREVIATIONS = setOf(
        "etc", "vs", "cf", "approx", "incl", "esp", "usw", "bzw", "ggf", "evtl", "inkl", "ca", "nr",
        "dr", "prof", "mr", "mrs", "ms", "sr", "sra", "ecc", "pág", "tel",
    )

    fun isAlnum(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    fun hasAlnum(text: String): Boolean = text.codePoints().anyMatch(::isAlnum)

    /** NFC, lowercased, maximal runs of letters and digits. */
    fun normTokens(text: String): List<String> {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val out = ArrayList<String>()
        val run = StringBuilder()
        folded.codePoints().forEach { cp ->
            if (isAlnum(cp)) run.appendCodePoint(cp)
            else if (run.isNotEmpty()) { out += run.toString(); run.setLength(0) }
        }
        if (run.isNotEmpty()) out += run.toString()
        return out
    }

    /** The letters and digits of [text], normalised: equal for the same spoken word. */
    fun core(text: String): String = normTokens(text).joinToString("")

    fun stripClosers(text: String): String = text.trimEnd { it in CLOSERS }

    /** Ends with '.', '!', '?' or '…', closing quotes and brackets allowed after it. */
    fun endsSentence(text: String): Boolean = stripClosers(text).lastOrNull()?.let { it in SENTENCE_END } == true

    /** The text before a final single '.' (closers stripped), or null when it does not end that way. */
    fun periodStem(text: String): String? {
        val t = stripClosers(text)
        return if (t.endsWith('.') && !t.endsWith("..")) t.dropLast(1) else null
    }

    /** Seam guard: the period after this stem is kept (initials, ALLCAPS, digits, abbreviations). */
    fun seamAbbreviation(stem: String): Boolean {
        val letters = wordChars(stem)
        return letters.codePointCount(0, letters.length) <= 1 || isUpper(letters) || isDigits(letters) ||
            listedOrDotted(stem)
    }

    /**
     * Sentence guard for titles and paragraphs: the period of an ordinal ('3.'), a single capital initial,
     * a dotted abbreviation ('z.B.') or a listed one ('Dr.') does not end a sentence. ALLCAPS words do.
     */
    fun sentenceAbbreviation(stem: String): Boolean {
        val letters = wordChars(stem)
        return isDigits(stem.trimStart { it in OPENERS }) ||
            (letters.codePointCount(0, letters.length) == 1 && isUpper(letters)) || listedOrDotted(stem)
    }

    /** A sentence end that is not an ordinal or abbreviation period. */
    fun sentenceEnd(text: String): Boolean {
        if (!endsSentence(text)) return false
        val stem = periodStem(text) ?: return true
        return !sentenceAbbreviation(stem)
    }

    /** True when the first letter after any opening quotes or brackets is lowercase. */
    fun startsLowercase(text: String): Boolean {
        var i = 0
        while (i < text.length && text[i] in OPENERS) i++
        if (i >= text.length) return false
        val cp = text.codePointAt(i)
        val letter = isAlnum(cp) && Character.getType(cp).toByte() != Character.DECIMAL_DIGIT_NUMBER
        return letter && Character.isLowerCase(cp)
    }

    private fun listedOrDotted(stem: String): Boolean =
        stem.trim('.').contains('.') || wordChars(stem).lowercase(Locale.ROOT) in ABBREVIATIONS

    private fun wordChars(text: String): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { if (isAlnum(it) || it == '_'.code) out.appendCodePoint(it) }
        return out.toString()
    }

    /** Python `str.isupper`: at least one cased character and no lowercase or titlecase one. */
    private fun isUpper(text: String): Boolean {
        var cased = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLowerCase(cp) || Character.isTitleCase(cp)) return false
            if (Character.isUpperCase(cp)) cased = true
            i += Character.charCount(cp)
        }
        return cased
    }

    private fun isDigits(text: String): Boolean = text.isNotEmpty() && text.codePoints().allMatch(Character::isDigit)
}
