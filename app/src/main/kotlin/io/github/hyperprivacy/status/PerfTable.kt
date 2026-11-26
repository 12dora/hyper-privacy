package io.github.hyperprivacy.status

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.status.SinkPerf

/**
 * Running per-sink cost totals ([SinkPerf]) from diagnostics mode, keyed by sink name and stored
 * as `calls,totalNs,maxNs`. There is one row per sink, never per package.
 */
internal class PerfTable(private val prefs: SharedPreferences) {
    /** Adds each reported delta row to its sink's totals. */
    @Synchronized
    fun merge(rows: List<SinkPerf>) {
        if (rows.isEmpty()) return
        val current = all().associateBy { it.sink }
        prefs.edit {
            rows.forEach { row ->
                val total = current[row.sink]?.plus(row) ?: row
                putString(row.sink, encode(total))
            }
        }
    }

    fun all(): List<SinkPerf> = prefs.all.mapNotNull { (sink, value) -> decode(sink, value as? String) }

    @Synchronized
    fun clear() = prefs.edit { clear() }

    private companion object {
        const val SEPARATOR = ","
        const val FIELD_COUNT = 3

        fun encode(row: SinkPerf) = listOf(row.calls, row.totalNs, row.maxNs).joinToString(SEPARATOR)

        fun decode(sink: String, text: String?): SinkPerf? {
            if (!SinkPerf.isValidSink(sink)) return null
            val fields = text?.split(SEPARATOR)?.takeIf { it.size == FIELD_COUNT } ?: return null
            val numbers = fields.map { it.toLongOrNull() ?: return null }
            return SinkPerf(sink, numbers[0], numbers[1], numbers[2])
        }
    }
}
