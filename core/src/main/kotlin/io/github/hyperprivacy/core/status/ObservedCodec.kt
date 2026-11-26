package io.github.hyperprivacy.core.status

/**
 * Wire format for [ObservedEndpoint] batches, one per line, tab-separated:
 * `package  host  pathPrefix  count  firstSeen  lastSeen  blocked`, where an empty pathPrefix
 * means `null` and blocked is `1`/`0`. Rows that fail validation are dropped.
 */
public object ObservedCodec {
    internal const val FIELD_COUNT = 7
    private const val TRUE = "1"
    private const val FALSE = "0"

    public fun encode(endpoints: Iterable<ObservedEndpoint>): String = TsvRows.encode(endpoints.map(::fields))

    internal fun fields(it: ObservedEndpoint): List<String> = listOf(
        it.packageName,
        it.host,
        it.pathPrefix.orEmpty(),
        it.count.toString(),
        it.firstSeen.toString(),
        it.lastSeen.toString(),
        if (it.blocked) TRUE else FALSE,
    )

    public fun decode(text: String?, maxRows: Int = TsvRows.DEFAULT_MAX_ROWS): List<ObservedEndpoint> =
        TsvRows.decode(text, FIELD_COUNT, maxRows, ::parse)

    internal fun parse(fields: List<String>): ObservedEndpoint? {
        val pkg = fields[0].ifEmpty { return null }
        val host = fields[1].takeIf(ObservedEndpoint::isValidHost) ?: return null
        val path = fields[2].ifEmpty { null }
        if (path != null && ObservedEndpoint.pathPrefixOf(path) != path) return null
        val count = fields[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val first = fields[4].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val last = fields[5].toLongOrNull()?.takeIf { it >= first } ?: return null
        val blocked = when (fields[6]) {
            TRUE -> true
            FALSE -> false
            else -> return null
        }
        return ObservedEndpoint(pkg, host, path, count, first, last, blocked)
    }
}
