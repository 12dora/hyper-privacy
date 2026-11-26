package io.github.hyperprivacy.core.status

/**
 * Whether one enforcement sink (e.g. `dns`, `url.okhttp`) could be installed in [packageName].
 * Sinks are optional: a MISSED HTTP-client sink usually just means the app does not bundle that
 * client (or ships it obfuscated).
 */
public data class SinkReport(val packageName: String, val sink: String, val state: HookState)

/** Wire format for [SinkReport] batches: `package  sink  STATE` per line. */
public object SinkCodec {
    internal const val FIELD_COUNT = 3

    public fun encode(reports: Iterable<SinkReport>): String = TsvRows.encode(reports.map(::fields))

    internal fun fields(it: SinkReport): List<String> = listOf(it.packageName, it.sink, it.state.name)

    public fun decode(text: String?, maxRows: Int = TsvRows.DEFAULT_MAX_ROWS): List<SinkReport> =
        TsvRows.decode(text, FIELD_COUNT, maxRows, ::parse)

    internal fun parse(fields: List<String>): SinkReport? {
        val pkg = fields[0].ifEmpty { return null }
        val sink = fields[1].ifEmpty { return null }
        val state = HookState.parse(fields[2]) ?: return null
        return SinkReport(pkg, sink, state)
    }
}
