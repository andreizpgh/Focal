package app.focal.data

import app.focal.core.AsrToken
import app.focal.core.Word

/**
 * Compact JSON for the recognizer tokens of a window and the timed words of a paragraph.
 *
 * Tokens: `[[text, startSec, durationSec, logProb|null], ...]` exactly as the recognizer returned them.
 * Words: `[[text, startMs, endMs, confidence|null, flags, window], ...]`, flags bit 0 = punctuation only,
 * bit 1 = `<unk>` inside the word. Non-finite numbers are stored as null.
 */
internal object TranscriptJson {
    fun encodeTokens(tokens: List<AsrToken>): String = buildString {
        append('[')
        tokens.forEachIndexed { i, token ->
            if (i > 0) append(',')
            append('['); string(token.text)
            append(','); number(token.startSec)
            append(','); number(token.durationSec)
            append(','); number(token.logProb)
            append(']')
        }
        append(']')
    }

    fun decodeTokens(json: String): List<AsrToken> = Parser(json).rows { row ->
        require(row.size == 4) { "Malformed token" }
        AsrToken(row[0] as String, (row[1] as Double), (row[2] as Double), row[3] as Double?)
    }

    fun encodeWords(words: List<Word>): String = buildString {
        append('[')
        words.forEachIndexed { i, word ->
            if (i > 0) append(',')
            append('['); string(word.text)
            append(','); append(word.startMs)
            append(','); append(word.endMs)
            append(','); number(word.confidence)
            append(','); append((if (word.punctOnly) 1 else 0) or (if (word.unknownPiece) 2 else 0))
            append(','); append(word.window)
            append(']')
        }
        append(']')
    }

    fun decodeWords(json: String): List<Word> = Parser(json).rows { row ->
        require(row.size == 6) { "Malformed word" }
        val flags = (row[4] as Double).toInt()
        Word(row[0] as String, (row[1] as Double).toLong(), (row[2] as Double).toLong(), row[3] as Double?,
            flags and 1 != 0, (row[5] as Double).toInt(), flags and 2 != 0)
    }

    private fun StringBuilder.number(value: Double?) {
        if (value == null || !value.isFinite()) append("null") else append(value.toString())
    }

    private fun StringBuilder.string(value: String) {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }

    /** A strict parser for an array of flat arrays of strings, numbers and nulls. */
    private class Parser(private val s: String) {
        private var i = 0

        fun <T> rows(map: (List<Any?>) -> T): List<T> {
            val out = ArrayList<T>()
            expect('[')
            if (peek() == ']') { i++; return finish(out) }
            while (true) {
                out += map(row())
                when (next()) {
                    ',' -> continue
                    ']' -> return finish(out)
                    else -> malformed()
                }
            }
        }

        private fun <T> finish(out: List<T>): List<T> {
            skipSpace()
            if (i != s.length) malformed()
            return out
        }

        private fun row(): List<Any?> {
            expect('[')
            val values = ArrayList<Any?>(6)
            if (peek() == ']') { i++; return values }
            while (true) {
                values += value()
                when (next()) {
                    ',' -> continue
                    ']' -> return values
                    else -> malformed()
                }
            }
        }

        private fun value(): Any? {
            return when (peek()) {
                '"' -> string()
                'n' -> { literal("null"); null }
                else -> number()
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) malformed()
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= s.length) malformed()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) malformed()
                                out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: malformed())
                                i += 4
                            }
                            else -> malformed()
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull()?.takeIf { it.isFinite() } ?: malformed()
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) malformed()
            i += word.length
        }

        private fun expect(c: Char) { if (next() != c) malformed() }

        private fun next(): Char { skipSpace(); if (i >= s.length) malformed(); return s[i++] }

        private fun peek(): Char { skipSpace(); if (i >= s.length) malformed(); return s[i] }

        private fun skipSpace() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun malformed(): Nothing = throw IllegalArgumentException("Malformed transcript JSON at $i")
    }
}
