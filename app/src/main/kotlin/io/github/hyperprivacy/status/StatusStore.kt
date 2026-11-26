package io.github.hyperprivacy.status

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.RuleReport

/** Latest known status of one rule in one package. */
internal data class StoredStatus(val state: HookState, val totalHits: Long, val reportedAt: Long) {
    /** Reports from before the current boot say nothing about whether hooks are live now. */
    val isFromCurrentBoot: Boolean get() = reportedAt >= System.currentTimeMillis() - SystemClock.elapsedRealtime()
}

/** App-private persistence for status reports, keyed by `package|ruleId`. */
internal class StatusStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Merges [reports]: state is replaced, hits (deltas) are accumulated. */
    @Synchronized
    fun merge(reports: List<RuleReport>) {
        if (reports.isEmpty()) return
        val now = System.currentTimeMillis()
        prefs.edit {
            reports.forEach { report ->
                val key = key(report.packageName, report.ruleId)
                val previousHits = decode(prefs.getString(key, null))?.totalHits ?: 0
                putString(key, encode(StoredStatus(report.state, previousHits + report.hits, now)))
            }
        }
    }

    fun get(packageName: String, ruleId: String): StoredStatus? = decode(
        prefs.getString(key(packageName, ruleId), null),
    )

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val FILE_NAME = "status"
        const val SEPARATOR = ","
        const val FIELD_COUNT = 3

        fun key(packageName: String, ruleId: String) = "$packageName|$ruleId"

        fun encode(status: StoredStatus) = listOf(
            status.state.name,
            status.totalHits,
            status.reportedAt,
        ).joinToString(SEPARATOR)

        fun decode(text: String?): StoredStatus? {
            val fields = text?.split(SEPARATOR)?.takeIf { it.size == FIELD_COUNT } ?: return null
            val state = HookState.entries.firstOrNull { it.name == fields[0] } ?: return null
            val hits = fields[1].toLongOrNull() ?: return null
            val at = fields[2].toLongOrNull() ?: return null
            return StoredStatus(state, hits, at)
        }
    }
}
