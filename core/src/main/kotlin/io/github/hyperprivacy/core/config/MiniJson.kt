package io.github.hyperprivacy.core.config

/**
 * A small, strict JSON reader and writer for [ConfigBackup], kept here so the format can be tested
 * on the JVM without Android's `org.json`. Input comes from a file the user picked, so the parser
 * is bounded: nesting depth and total length are capped, and any malformed text fails as a whole.
 *
 * Values read are `Map<String, Any?>`, `List<Any?>`, `String`, `Long`, `Double`, `Boolean` or `null`.
 */
internal object MiniJson {
    const val MAX_LENGTH: Int = 1 shl 20
    private const val MAX_DEPTH = 8
    private const val UNICODE_ESCAPE_LENGTH = 4
    private const val HEX_RADIX = 16
    private const val CONTROL_LIMIT = 0x20

    /** Parses [text]; `null` when it is not one well-formed JSON value, too long or nested too deeply. */
    fun parse(text: String): Any? = try {
        if (text.length > MAX_LENGTH) throw ParseFailure()
        Reader(text).readDocument()
    } catch (_: ParseFailure) {
        Failed
    }

    /** Marker returned by [parse] for malformed input, distinct from a JSON `null`. */
    object Failed

    fun write(value: Any?): String = buildString { put(value) }

    private fun StringBuilder.put(value: Any?) {
        when (value) {
            null -> append("null")

            is Boolean, is Long, is Int -> append(value.toString())

            is String -> quote(value)

            is Map<*, *> -> {
                append('{')
                var first = true
                value.forEach { (key, item) ->
                    if (!first) append(',')
                    first = false
                    quote(key as String)
                    append(':')
                    put(item)
                }
                append('}')
            }

            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    put(item)
                }
                append(']')
            }

            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class.simpleName}")
        }
    }

    private fun StringBuilder.quote(text: String) {
        append('"')
        text.forEach { char ->
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char.code < CONTROL_LIMIT -> unicodeEscape(char)
                else -> append(char)
            }
        }
        append('"')
    }

    private fun StringBuilder.unicodeEscape(char: Char) {
        append("\\u").append(char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_LENGTH, '0'))
    }

    private class ParseFailure : Exception()

    /** Position and character primitives of [Reader]. */
    private open class Cursor(protected val text: String) {
        protected var pos = 0

        protected fun skipSpace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        protected fun peek(): Char = if (pos < text.length) text[pos] else throw ParseFailure()

        protected fun next(): Char = peek().also { pos++ }

        protected fun expect(char: Char) {
            if (next() != char) throw ParseFailure()
        }
    }

    private class Reader(text: String) : Cursor(text) {
        fun readDocument(): Any? {
            val value = readValue(0)
            skipSpace()
            if (pos != text.length) throw ParseFailure()
            return value
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseFailure()
            skipSpace()
            return when (val char = peek()) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char in '0'..'9') readNumber() else throw ParseFailure()
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipSpace()
                val key = readString()
                skipSpace()
                expect(':')
                result[key] = readValue(depth + 1)
                skipSpace()
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> throw ParseFailure()
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result += readValue(depth + 1)
                skipSpace()
                when (next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> throw ParseFailure()
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val char = next()) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> if (char.code < CONTROL_LIMIT) throw ParseFailure() else out.append(char)
                }
            }
        }

        private fun readEscape(): Char = when (next()) {
            '"' -> '"'

            '\\' -> '\\'

            '/' -> '/'

            'n' -> '\n'

            'r' -> '\r'

            't' -> '\t'

            'b' -> '\b'

            'f' -> '\u000C'

            'u' -> {
                val end = pos + UNICODE_ESCAPE_LENGTH
                if (end > text.length) throw ParseFailure()
                val code = text.substring(pos, end).toIntOrNull(HEX_RADIX) ?: throw ParseFailure()
                pos = end
                code.toChar()
            }

            else -> throw ParseFailure()
        }

        private fun readNumber(): Any {
            val start = pos
            while (pos < text.length && (text[pos] in '0'..'9' || text[pos] in "+-.eE")) pos++
            val token = text.substring(start, pos)
            return token.toLongOrNull() ?: token.toDoubleOrNull() ?: throw ParseFailure()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw ParseFailure()
            pos += word.length
            return value
        }
    }
}
