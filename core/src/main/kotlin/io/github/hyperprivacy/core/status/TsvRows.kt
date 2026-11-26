package io.github.hyperprivacy.core.status

/**
 * Shared line/tab framing for the status wire formats. Rows with the wrong field count, or that
 * the row parser rejects, are dropped on decode, because payloads cross a trust boundary.
 */
internal object TsvRows {
    private const val FIELD_SEPARATOR = '\t'
    private const val LINE_SEPARATOR = '\n'

    fun encode(rows: Iterable<List<String>>): String = rows.joinToString(LINE_SEPARATOR.toString()) { fields ->
        require(fields.none { FIELD_SEPARATOR in it || LINE_SEPARATOR in it }) {
            "Report fields must not contain separators"
        }
        fields.joinToString(FIELD_SEPARATOR.toString())
    }

    fun <T : Any> decode(text: String?, fieldCount: Int, parse: (List<String>) -> T?): List<T> =
        text.orEmpty().split(LINE_SEPARATOR).mapNotNull { line ->
            line.split(FIELD_SEPARATOR).takeIf { it.size == fieldCount }?.let(parse)
        }
}
