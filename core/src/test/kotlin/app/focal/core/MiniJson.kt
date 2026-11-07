package app.focal.core

/** Minimal JSON reader for test vectors: objects, arrays, strings, numbers (Long or Double), booleans, null. */
internal class MiniJson private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run {
            val value = value()
            skipSpace()
            check(i == s.length) { "trailing data at $i" }
            value
        }
    }

    private fun value(): Any? {
        skipSpace()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $i")
        }
    }

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        i++
        skipSpace()
        if (s[i] == '}') return out.also { i++ }
        while (true) {
            skipSpace()
            val key = string()
            skipSpace()
            expect(':')
            out[key] = value()
            skipSpace()
            if (s[i] == ',') { i++; continue }
            expect('}')
            return out
        }
    }

    private fun array(): List<Any?> {
        val out = ArrayList<Any?>()
        i++
        skipSpace()
        if (s[i] == ']') return out.also { i++ }
        while (true) {
            out += value()
            skipSpace()
            if (s[i] == ',') { i++; continue }
            expect(']')
            return out
        }
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            when (val c = s[i++]) {
                '"' -> return out.toString()
                '\\' -> when (val e = s[i++]) {
                    '"', '\\', '/' -> out.append(e)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> { out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("bad escape \\$e")
                }
                else -> out.append(c)
            }
        }
    }

    private fun number(): Any {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val text = s.substring(start, i)
        return if (text.any { it in ".eE" }) text.toDouble() else text.toLong()
    }

    private fun literal(word: String, value: Any?): Any? {
        check(s.startsWith(word, i)) { "bad literal at $i" }
        i += word.length
        return value
    }

    private fun expect(c: Char) {
        check(s[i] == c) { "expected '$c' at $i, found '${s[i]}'" }
        i++
    }

    private fun skipSpace() {
        while (i < s.length && s[i].isWhitespace()) i++
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.list(): List<Any?> = this as List<Any?>

internal fun Any?.long(): Long = (this as Number).toLong()

internal fun Any?.double(): Double = (this as Number).toDouble()

internal fun Any?.longOrNull(): Long? = (this as Number?)?.toLong()

internal fun Any?.doubleOrNull(): Double? = (this as Number?)?.toDouble()
