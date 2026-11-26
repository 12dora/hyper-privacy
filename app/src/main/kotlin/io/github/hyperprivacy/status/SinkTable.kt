package io.github.hyperprivacy.status

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.SinkReport

/**
 * Persistent sink install states keyed by `package|sink`, stored as `STATE,reportedAt,firstActiveAt`.
 *
 * Older rows have only `STATE` (reported at 0) or `STATE,reportedAt`, and no activation time; an
 * ACTIVE one of those still proves the sink was active once, a MISSED one proves nothing.
 */
internal class SinkTable(val prefs: SharedPreferences) {
    private data class Row(val state: HookState, val reportedAt: Long, val firstActiveAt: Long?)

    /** Replaces the state of each reported sink, stamped with [now]; keeps when it was first ACTIVE. */
    @Synchronized
    fun merge(reports: List<SinkReport>, now: Long) {
        if (reports.isEmpty()) return
        prefs.edit {
            reports.forEach { report ->
                val key = key(report.packageName, report.sink)
                val firstActive = decode(prefs.getString(key, null))?.firstActiveAt
                    ?: now.takeIf { report.state == HookState.ACTIVE }
                putString(key, encode(Row(report.state, now, firstActive)))
            }
        }
    }

    /** Latest state of every sink reported at or after [since] (wall-clock millis). */
    fun states(since: Long): List<SinkReport> = rows().mapNotNull { (pkg, sink, row) ->
        SinkReport(pkg, sink, row.state).takeIf { row.reportedAt >= since }
    }

    /** Per package, the sinks that were ACTIVE at least once on this device (any boot). */
    fun everActive(): Map<String, Set<String>> = rows()
        .filter { (_, _, row) -> row.firstActiveAt != null }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, sinks) -> sinks.toSet() }

    private fun rows(): List<Triple<String, String, Row>> = prefs.all.mapNotNull { (key, value) ->
        val parts = key.split(KEY_SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val row = decode(value as? String) ?: return@mapNotNull null
        Triple(parts[0], parts[1], row)
    }

    private companion object {
        const val KEY_SEPARATOR = "|"
        const val SEPARATOR = ","
        const val REPORTED_AT_INDEX = 1
        const val FIRST_ACTIVE_INDEX = 2

        fun key(packageName: String, sink: String) = "$packageName$KEY_SEPARATOR$sink"

        fun encode(row: Row) = listOf(row.state.name, row.reportedAt, row.firstActiveAt ?: "").joinToString(SEPARATOR)

        fun decode(text: String?): Row? {
            val fields = text?.split(SEPARATOR) ?: return null
            val state = HookState.parse(fields[0]) ?: return null
            val at = fields.getOrNull(REPORTED_AT_INDEX)?.toLongOrNull() ?: 0
            val stored = fields.getOrNull(FIRST_ACTIVE_INDEX)
            val firstActive = if (stored == null) at.takeIf { state == HookState.ACTIVE } else stored.toLongOrNull()
            return Row(state, at, firstActive)
        }
    }
}
