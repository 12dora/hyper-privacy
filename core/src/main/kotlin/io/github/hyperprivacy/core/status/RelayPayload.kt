package io.github.hyperprivacy.core.status

/**
 * Plaintext of one relayed status batch ([StatusRelay]): UTF-8, one row per line, the table's tag
 * then the row's fields in the table's own codec format ([StatusCodec], [SinkCodec],
 * [ObservedCodec], [MetaCodec]), tab-separated.
 *
 * [decode] reads at most [StatusRelay.MAX_ROWS_PER_TABLE] rows per table and looks at a bounded
 * number of lines, so its cost is bounded whatever the input.
 */
public object RelayPayload {
    private const val REPORT = "r"
    private const val SINK = "s"
    private const val OBSERVED = "o"
    private const val META = "m"
    private const val TABLES = 4
    private const val MAX_LINES = TABLES * StatusRelay.MAX_ROWS_PER_TABLE + TsvRows.SLACK_LINES

    public fun encode(batch: StatusBatch): ByteArray = lines(batch).joinToString(
        TsvRows.LINE_SEPARATOR.toString(),
    ).toByteArray(Charsets.UTF_8)

    /**
     * Splits [batch] into the rows that fit in [maxBytes] once encoded and the rest. Tables are
     * filled in order meta, sinks, reports, observed; within a table rows keep their order, and the
     * first row that does not fit sends it and everything after it in that table to the rest.
     */
    public fun fit(batch: StatusBatch, maxBytes: Int): Pair<StatusBatch, StatusBatch> {
        var left = maxBytes
        fun <T> split(rows: List<T>, line: (T) -> String): Pair<List<T>, List<T>> {
            var taken = 0
            while (taken < rows.size) {
                val size = utf8Length(line(rows[taken])) + 1
                if (size > left) break
                left -= size
                taken++
            }
            return rows.subList(0, taken).toList() to rows.subList(taken, rows.size).toList()
        }
        val meta = split(batch.meta) { metaLine(it) }
        val sinks = split(batch.sinks) { sinkLine(it) }
        val reports = split(batch.reports) { reportLine(it) }
        val observed = split(batch.observed) { observedLine(it) }
        return StatusBatch(reports.first, sinks.first, observed.first, meta.first) to
            StatusBatch(reports.second, sinks.second, observed.second, meta.second)
    }

    /** Decodes [bytes]; malformed or unknown rows are dropped, each table capped. */
    public fun decode(bytes: ByteArray): StatusBatch {
        val reports = ArrayList<RuleReport>()
        val sinks = ArrayList<SinkReport>()
        val observed = ArrayList<ObservedEndpoint>()
        val meta = ArrayList<ProcessMeta>()
        TsvRows.forEachLine(String(bytes, Charsets.UTF_8), MAX_LINES) { line ->
            val tab = line.indexOf(TsvRows.FIELD_SEPARATOR)
            if (tab > 0) {
                val rest = line.substring(tab + 1)
                when (line.substring(0, tab)) {
                    REPORT -> reports.addRow(rest, StatusCodec.FIELD_COUNT, StatusCodec::parse)
                    SINK -> sinks.addRow(rest, SinkCodec.FIELD_COUNT, SinkCodec::parse)
                    OBSERVED -> observed.addRow(rest, ObservedCodec.FIELD_COUNT, ObservedCodec::parse)
                    META -> meta.addRow(rest, MetaCodec.FIELD_COUNT, MetaCodec::parse)
                }
            }
            true
        }
        return StatusBatch(reports, sinks, observed, meta)
    }

    private fun <T : Any> MutableList<T>.addRow(text: String, count: Int, parse: (List<String>) -> T?) {
        if (size >= StatusRelay.MAX_ROWS_PER_TABLE) return
        TsvRows.fields(text, count)?.let(parse)?.let(::add)
    }

    private fun lines(batch: StatusBatch): List<String> =
        batch.meta.map(::metaLine) + batch.sinks.map(::sinkLine) + batch.reports.map(::reportLine) +
            batch.observed.map(::observedLine)

    private fun reportLine(row: RuleReport) = TsvRows.line(listOf(REPORT) + StatusCodec.fields(row))

    private fun sinkLine(row: SinkReport) = TsvRows.line(listOf(SINK) + SinkCodec.fields(row))

    private fun observedLine(row: ObservedEndpoint) = TsvRows.line(listOf(OBSERVED) + ObservedCodec.fields(row))

    private fun metaLine(row: ProcessMeta) = TsvRows.line(listOf(META) + MetaCodec.fields(row))

    @Suppress("MagicNumber") // UTF-8 code unit boundaries.
    private fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val char = text[i]
            bytes += when {
                char.code < 0x80 -> 1

                char.code < 0x800 -> 2

                Character.isHighSurrogate(char) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }

                else -> 3
            }
            i++
        }
        return bytes
    }
}
