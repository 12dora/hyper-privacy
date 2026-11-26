package io.github.hyperprivacy.core.status

/**
 * What one enforcement point cost, collected only in diagnostics mode: how often its own logic ran
 * and the time it took (the module's decision, not the intercepted call).
 *
 * Rows are per sink name, never per package, so they carry nothing about which apps are installed.
 * On the wire they are deltas since the previous report, except [maxNs], which is the largest
 * single call in that interval; the receiver accumulates them ([plus]).
 */
public data class SinkPerf(val sink: String, val calls: Long, val totalNs: Long, val maxNs: Long) {
    /** Mean cost of one call in nanoseconds; `0` without calls. */
    public val averageNs: Long get() = if (calls > 0) totalNs / calls else 0

    /** Running totals after adding [other] (same sink). */
    public operator fun plus(other: SinkPerf): SinkPerf = SinkPerf(
        sink = sink,
        calls = saturatingAdd(calls, other.calls),
        totalNs = saturatingAdd(totalNs, other.totalNs),
        maxNs = maxOf(maxNs, other.maxNs),
    )

    public companion object {
        /** Sink names are short identifiers (`dns`, `url.okhttp`); anything else is not ours. */
        private val SINK_NAME = Regex("[A-Za-z0-9._ -]{1,40}")

        public fun isValidSink(name: String): Boolean = SINK_NAME.matches(name)

        private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }
}

/** Wire format for [SinkPerf] batches: `sink  calls  totalNs  maxNs` per line. */
public object PerfCodec {
    private const val FIELD_COUNT = 4

    /** Upper bound on rows accepted from one call; there are only a dozen sinks. */
    public const val MAX_ROWS: Int = 64

    public fun encode(rows: Iterable<SinkPerf>): String =
        TsvRows.encode(rows.map { listOf(it.sink, it.calls.toString(), it.totalNs.toString(), it.maxNs.toString()) })

    public fun decode(text: String?): List<SinkPerf> = TsvRows.decode(text, FIELD_COUNT, ::parse).take(MAX_ROWS)

    private fun parse(fields: List<String>): SinkPerf? {
        val sink = fields[0].takeIf(SinkPerf::isValidSink) ?: return null
        val calls = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val total = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val max = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return SinkPerf(sink, calls, total, max)
    }
}
