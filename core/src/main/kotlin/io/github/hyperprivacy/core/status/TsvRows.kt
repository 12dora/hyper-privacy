package io.github.hyperprivacy.core.status

/**
 * Shared line/tab framing for the status wire formats. Rows with the wrong field count, or that
 * the row parser rejects, are dropped on decode, because payloads cross a trust boundary.
 *
 * Decoding streams over the text: it never splits the whole payload, looks at no more than the
 * row cap plus [SLACK_LINES] lines and never splits a line into more than its expected fields, so
 * a hostile payload costs at most one pass over a bounded prefix.
 */
internal object TsvRows {
    const val FIELD_SEPARATOR = '\t'
    const val LINE_SEPARATOR = '\n'

    /** Lines looked at beyond the row cap (malformed or rejected rows) before decoding stops. */
    const val SLACK_LINES = 64

    /** Rows one table decodes by default: well above any reporter's batch. */
    const val DEFAULT_MAX_ROWS = 4_096

    fun encode(rows: Iterable<List<String>>): String = rows.joinToString(LINE_SEPARATOR.toString(), transform = ::line)

    /** One encoded row (no line separator). */
    fun line(fields: List<String>): String {
        require(fields.none { FIELD_SEPARATOR in it || LINE_SEPARATOR in it }) {
            "Report fields must not contain separators"
        }
        return fields.joinToString(FIELD_SEPARATOR.toString())
    }

    fun <T : Any> decode(text: String?, fieldCount: Int, maxRows: Int, parse: (List<String>) -> T?): List<T> {
        val out = ArrayList<T>()
        forEachLine(text, maxRows + SLACK_LINES) { line ->
            fields(line, fieldCount)?.let(parse)?.let(out::add)
            out.size < maxRows
        }
        return out
    }

    /** Calls [action] for each line of [text] (at most [maxLines]) until it returns `false`. */
    inline fun forEachLine(text: String?, maxLines: Int, action: (String) -> Boolean) {
        if (text.isNullOrEmpty()) return
        var start = 0
        var lines = 0
        while (start <= text.length && lines < maxLines) {
            val end = text.indexOf(LINE_SEPARATOR, start).let { if (it < 0) text.length else it }
            lines++
            if (!action(text.substring(start, end))) return
            start = end + 1
        }
    }

    /** The fields of [line] when it has exactly [count] of them, else `null`. */
    fun fields(line: String, count: Int): List<String>? {
        val out = ArrayList<String>(count)
        var start = 0
        while (out.size < count - 1) {
            val end = line.indexOf(FIELD_SEPARATOR, start)
            if (end < 0) return null
            out += line.substring(start, end)
            start = end + 1
        }
        if (line.indexOf(FIELD_SEPARATOR, start) >= 0) return null
        out += line.substring(start)
        return out
    }
}
