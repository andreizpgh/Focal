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
        "dr", "prof", "mr", "mrs", "sr", "sra", "ecc", "pág", "tel",
    )

    /** Abbreviations listed only with this exact case: lowercase 'ms.' is usually milliseconds. */
    private val CASED_ABBREVIATIONS = setOf("Ms")

    /** A sentence ending in a number of up to this many digits ('3.', '15.') reads as an ordinal in the title. */
    private const val MAX_ORDINAL_DIGITS = 2

    /**
     * Function words a shortened title must not end on (articles, prepositions, conjunctions and the like), one list
     * per language measured for the recognizer: "We looked at the cache and then at the" reads as a cut, "We looked
     * at the cache" as a heading. The lists stay apart because a function word of one language is a content word of
     * another ("do", "no", "die", "sur"); [functionWords] picks the lists of the language the words are in. Compared
     * lowercased, letters only.
     */
    private val FUNCTION_WORDS: List<Set<String>> = listOf(
        // English
        setOf(
            "a", "an", "the", "and", "or", "but", "nor", "of", "to", "in", "on", "at", "for", "with", "from", "by", "as",
            "that", "than", "then", "if", "so", "is", "are", "was", "were", "be", "am", "this", "these", "those", "my",
            "your", "our", "their", "his", "her", "its", "into", "about", "which", "who", "when", "while", "because",
            "i", "we", "he", "she", "they",
        ),
        // Russian
        setOf(
            "и", "а", "но", "или", "в", "во", "на", "с", "со", "к", "ко", "у", "о", "об", "от", "до", "за", "из", "по",
            "для", "при", "про", "над", "под", "без", "через", "что", "чтобы", "как", "то", "это", "этот", "эта", "эти",
            "не", "ни", "же", "ли", "бы", "если", "когда", "потому", "который", "которая", "которые", "мой", "моя",
            "наш", "том", "тем", "того", "тому", "так", "там", "тот", "та", "те",
        ),
        // German
        setOf(
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "und", "oder", "aber",
            "in", "im", "an", "am", "auf", "aus", "bei", "mit", "nach", "von", "vom", "zu", "zum", "zur", "für", "über",
            "unter", "um", "als", "wie", "dass", "danach", "dann", "wenn", "weil",
        ),
        // French
        setOf(
            "le", "la", "les", "un", "une", "des", "et", "ou", "mais", "de", "du", "en", "au", "aux", "à", "dans", "sur",
            "sans", "pour", "par", "avec", "que", "qui", "si", "ce", "cette", "ces", "mon", "ma", "notre",
        ),
        // Spanish
        setOf(
            "el", "la", "los", "las", "un", "una", "unos", "unas", "y", "e", "o", "u", "pero", "de", "del", "al", "a",
            "en", "con", "sin", "por", "para", "sobre", "que", "como", "si", "mi", "su",
        ),
        // Italian
        setOf(
            "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "e", "ed", "o", "ma", "di", "del", "della", "dei",
            "a", "al", "da", "in", "nel", "nella", "con", "su", "per", "che", "se", "come",
        ),
        // Portuguese
        setOf(
            "o", "a", "os", "as", "um", "uma", "e", "ou", "mas", "de", "do", "da", "dos", "das", "em", "no", "na", "nos",
            "nas", "ao", "com", "por", "para", "que", "se", "como",
        ),
    )

    /**
     * The function words of the language [words] are in: the list most of them belong to, and every list that ties
     * with it (Spanish, Italian and Portuguese share most of theirs). Empty when no word is in any list. The
     * recognizer names no language, so the words themselves decide.
     */
    fun functionWords(words: List<String>): Set<String> {
        val keys = words.map { wordChars(it).lowercase(Locale.ROOT) }
        var most = 0
        val out = HashSet<String>()
        for (list in FUNCTION_WORDS) {
            val hits = keys.count { it in list }
            if (hits > most) {
                most = hits
                out.clear()
            }
            if (hits > 0 && hits == most) out += list
        }
        return out
    }

    /**
     * Whether a shortened title should not end on [word]: one of [functionWords], a bare number (its unit or month
     * went with the words after it: "am 4. Mai") or punctuation alone.
     */
    fun danglingWord(word: String, functionWords: Set<String>): Boolean {
        val letters = wordChars(word)
        return letters.isEmpty() || isDigits(letters) || letters.lowercase(Locale.ROOT) in functionWords
    }

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

    /**
     * Seam guard: the period after this stem is kept (initials, ALLCAPS, digits, any internal dot, listed
     * abbreviations). Conservative on purpose: keeping a period is the safe side of a repair.
     */
    fun seamAbbreviation(stem: String): Boolean {
        val letters = wordChars(stem)
        return letters.codePointCount(0, letters.length) <= 1 || isUpper(letters) || isDigits(letters) ||
            stem.trim('.').contains('.') || listed(stem)
    }

    /**
     * The title's sentence guard: the period of an ordinal ('3.', '15.'; not '2002.'), a single capital
     * initial, a dotted letter abbreviation ('z.B.', 'p.m.'; not '15.30.', '3.14.' or 'example.com.') or a
     * listed one ('Dr.') does not end a sentence. ALLCAPS words do. Paragraphs use [endsSentence].
     */
    fun sentenceAbbreviation(stem: String): Boolean {
        val letters = wordChars(stem)
        val bare = stem.trimStart { it in OPENERS }
        return (isDigits(bare) && bare.codePointCount(0, bare.length) <= MAX_ORDINAL_DIGITS) ||
            (letters.codePointCount(0, letters.length) == 1 && isUpper(letters)) || dottedLetters(stem) || listed(stem)
    }

    /** A sentence end for the title: not an ordinal or abbreviation period ([sentenceAbbreviation]). */
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

    private fun listed(stem: String): Boolean {
        val letters = wordChars(stem)
        return letters.lowercase(Locale.ROOT) in ABBREVIATIONS || letters in CASED_ABBREVIATIONS
    }

    /** Dot-separated runs of one to three letters: 'z.B', 'p.m', 'т.д', 'U.S'. */
    private fun dottedLetters(stem: String): Boolean {
        val parts = stem.trimStart { it in OPENERS }.trim('.').split('.')
        return parts.size >= 2 && parts.all { part ->
            part.codePointCount(0, part.length) in 1..3 && part.codePoints().allMatch(Character::isLetter)
        }
    }

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
