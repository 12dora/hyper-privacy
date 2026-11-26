package io.github.hyperprivacy.core.health

import io.github.hyperprivacy.core.status.SinkPerf

/**
 * The "sink cost" section of [DiagnosticReport]: per sink name, how often the module's own logic
 * ran and what it cost, in microseconds. Sink names are the only free text and must be valid
 * identifiers ([SinkPerf.isValidSink]); there is nothing about apps to redact.
 */
internal object PerfReport {
    private const val NANOS_PER_MICRO = 1_000L
    private const val NANOS_PER_TENTH = 100L

    /** Appends the section to [out]; nothing when no sink was measured. */
    fun append(out: StringBuilder, rows: List<SinkPerf>) {
        val valid = rows.filter { SinkPerf.isValidSink(it.sink) }
        if (valid.isEmpty()) return
        out.appendLine()
        out.appendLine("## Sink cost (diagnostics mode)")
        out.appendLine()
        valid.sortedWith(compareBy({ -it.totalNs }, { it.sink })).forEach {
            out.appendLine(
                "- ${it.sink}: ${it.calls} calls, avg ${micros(it.averageNs)} us, max ${micros(it.maxNs)} us",
            )
        }
    }

    /** Nanoseconds as microseconds with one decimal, e.g. `1.5`. */
    private fun micros(nanos: Long): String = "${nanos / NANOS_PER_MICRO}.${nanos % NANOS_PER_MICRO / NANOS_PER_TENTH}"
}
