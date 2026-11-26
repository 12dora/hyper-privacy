package io.github.hyperprivacy.status

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkReport

/** Latest known status of one rule in one package. */
internal data class StoredStatus(val state: HookState, val totalHits: Long, val reportedAt: Long) {
    /** Reports from before the current boot say nothing about whether hooks are live now. */
    val isFromCurrentBoot: Boolean get() = reportedAt >= System.currentTimeMillis() - SystemClock.elapsedRealtime()
}

/**
 * App-private persistence for status reports: rule status keyed by `package|ruleId`, sink states
 * keyed by `package|sink`, and the diagnostics table of observed endpoints ([ObservedTable]).
 */
internal class StatusStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val sinkPrefs: SharedPreferences = context.getSharedPreferences(SINKS_FILE_NAME, Context.MODE_PRIVATE)
    private val observed = ObservedTable(context.getSharedPreferences(OBSERVED_FILE_NAME, Context.MODE_PRIVATE))

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

    /** Replaces the stored state of each reported sink. */
    @Synchronized
    fun mergeSinks(reports: List<SinkReport>) {
        if (reports.isEmpty()) return
        sinkPrefs.edit { reports.forEach { putString(key(it.packageName, it.sink), it.state.name) } }
    }

    /** Latest known state of every sink in every package. */
    fun sinkStates(): List<SinkReport> = sinkPrefs.all.mapNotNull { (key, value) ->
        val parts = key.split(KEY_SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val state = HookState.parse(value as? String) ?: return@mapNotNull null
        SinkReport(parts[0], parts[1], state)
    }

    /** Accumulates diagnostics endpoints (count deltas) from a report. */
    fun mergeObserved(endpoints: List<ObservedEndpoint>) = observed.merge(endpoints)

    /** Observed endpoints (running totals), most recently seen first. */
    fun observedEndpoints(): List<ObservedEndpoint> = observed.all()

    fun clearObserved() = observed.clear()

    /** Listens to every table: rule status, sinks and observed endpoints. */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        allPrefs().forEach { it.registerOnSharedPreferenceChangeListener(listener) }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        allPrefs().forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }

    private fun allPrefs(): List<SharedPreferences> = listOf(prefs, sinkPrefs, observed.prefs)

    private companion object {
        const val FILE_NAME = "status"
        const val SINKS_FILE_NAME = "status_sinks"
        const val OBSERVED_FILE_NAME = "status_observed"
        const val KEY_SEPARATOR = "|"
        const val SEPARATOR = ","
        const val FIELD_COUNT = 3

        fun key(packageName: String, ruleId: String) = "$packageName$KEY_SEPARATOR$ruleId"

        fun encode(status: StoredStatus) = listOf(
            status.state.name,
            status.totalHits,
            status.reportedAt,
        ).joinToString(SEPARATOR)

        fun decode(text: String?): StoredStatus? {
            val fields = text?.split(SEPARATOR)?.takeIf { it.size == FIELD_COUNT } ?: return null
            val state = HookState.parse(fields[0]) ?: return null
            val hits = fields[1].toLongOrNull() ?: return null
            val at = fields[2].toLongOrNull() ?: return null
            return StoredStatus(state, hits, at)
        }
    }
}
