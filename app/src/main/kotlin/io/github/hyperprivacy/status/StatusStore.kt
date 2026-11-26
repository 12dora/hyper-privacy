package io.github.hyperprivacy.status

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.edit
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkReport

/**
 * Latest known status of one rule in one package.
 *
 * @property lastHitAt when a report with a non-zero hit delta was last received (receipt time,
 *   so at most one flush interval late); `null` if the rule never blocked anything here.
 * @property activeSince when the rule was first reported ACTIVE after being MISSED or unknown;
 *   `null` while MISSED.
 */
internal data class StoredStatus(
    val state: HookState,
    val totalHits: Long,
    val reportedAt: Long,
    val lastHitAt: Long? = null,
    val activeSince: Long? = null,
) {
    /** Reports from before the current boot say nothing about whether hooks are live now. */
    val isFromCurrentBoot: Boolean get() = reportedAt >= bootTimeMillis()
}

/** Wall-clock time of the current boot. */
internal fun bootTimeMillis(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

/**
 * App-private persistence for status reports: rule status keyed by `package|ruleId`, sink states
 * keyed by `package|sink`, process facts per package ([MetaTable]) and the diagnostics table of
 * observed endpoints ([ObservedTable]). Rule health queries live in `StatusHealth.kt`.
 */
internal class StatusStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val sinkPrefs: SharedPreferences = context.getSharedPreferences(SINKS_FILE_NAME, Context.MODE_PRIVATE)
    private val observed = ObservedTable(context.getSharedPreferences(OBSERVED_FILE_NAME, Context.MODE_PRIVATE))

    /** Process facts (version that ran, start and last-seen times) per package. */
    val meta = MetaTable(context.getSharedPreferences(META_FILE_NAME, Context.MODE_PRIVATE))

    /** Used by health queries for the installed state and version of target packages. */
    val packageManager: PackageManager = context.packageManager

    private val allPrefs: List<SharedPreferences> get() = listOf(prefs, sinkPrefs, observed.prefs, meta.prefs)

    /** Merges [reports]: state is replaced, hits (deltas) are accumulated, hit and activation times tracked. */
    @Synchronized
    fun merge(reports: List<RuleReport>) {
        if (reports.isEmpty()) return
        val now = System.currentTimeMillis()
        prefs.edit {
            reports.forEach { report ->
                val key = key(report.packageName, report.ruleId)
                val previous = decode(prefs.getString(key, null))
                val activeSince = when {
                    report.state != HookState.ACTIVE -> null
                    previous?.state == HookState.ACTIVE -> previous.activeSince ?: now
                    else -> now
                }
                val stored = StoredStatus(
                    state = report.state,
                    totalHits = (previous?.totalHits ?: 0) + report.hits,
                    reportedAt = now,
                    lastHitAt = if (report.hits > 0) now else previous?.lastHitAt,
                    activeSince = activeSince,
                )
                putString(key, encode(stored))
            }
        }
    }

    fun get(packageName: String, ruleId: String): StoredStatus? = decode(
        prefs.getString(key(packageName, ruleId), null),
    )

    /** Replaces the stored state of each reported sink, stamped with the receipt time. */
    @Synchronized
    fun mergeSinks(reports: List<SinkReport>) {
        if (reports.isEmpty()) return
        val now = System.currentTimeMillis()
        sinkPrefs.edit {
            reports.forEach { putString(key(it.packageName, it.sink), it.state.name + SEPARATOR + now) }
        }
    }

    /**
     * Latest known state of every sink in every package, keeping only those reported at or after
     * [since] (wall-clock millis). Rows stored before sinks carried a time count as reported at 0.
     */
    fun sinkStates(since: Long = 0): List<SinkReport> = sinkPrefs.all.mapNotNull { (key, value) ->
        val parts = key.split(KEY_SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val fields = (value as? String)?.split(SEPARATOR) ?: return@mapNotNull null
        val state = HookState.parse(fields[0]) ?: return@mapNotNull null
        val at = fields.getOrNull(1)?.toLongOrNull() ?: 0
        SinkReport(parts[0], parts[1], state).takeIf { at >= since }
    }

    /** Accumulates diagnostics endpoints (count deltas) from a report. */
    fun mergeObserved(endpoints: List<ObservedEndpoint>) = observed.merge(endpoints)

    /** Observed endpoints (running totals), most recently seen first. */
    fun observedEndpoints(): List<ObservedEndpoint> = observed.all()

    fun clearObserved() = observed.clear()

    /** Listens to every table: rule status, sinks, process facts and observed endpoints. */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        allPrefs.forEach { it.registerOnSharedPreferenceChangeListener(listener) }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        allPrefs.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }

    private companion object {
        const val FILE_NAME = "status"
        const val SINKS_FILE_NAME = "status_sinks"
        const val OBSERVED_FILE_NAME = "status_observed"
        const val META_FILE_NAME = "status_meta"
        const val KEY_SEPARATOR = "|"
        const val SEPARATOR = ","

        /** Rows written before hit and activation times were tracked. */
        const val LEGACY_FIELD_COUNT = 3
        const val FIELD_COUNT = 5
        const val LAST_HIT_INDEX = 3
        const val ACTIVE_SINCE_INDEX = 4

        fun key(packageName: String, ruleId: String) = "$packageName$KEY_SEPARATOR$ruleId"

        fun encode(status: StoredStatus) = listOf(
            status.state.name,
            status.totalHits,
            status.reportedAt,
            status.lastHitAt ?: "",
            status.activeSince ?: "",
        ).joinToString(SEPARATOR)

        fun decode(text: String?): StoredStatus? {
            val fields = text?.split(SEPARATOR)?.takeIf { it.size == FIELD_COUNT || it.size == LEGACY_FIELD_COUNT }
                ?: return null
            val state = HookState.parse(fields[0]) ?: return null
            val hits = fields[1].toLongOrNull() ?: return null
            val at = fields[2].toLongOrNull() ?: return null
            return StoredStatus(
                state = state,
                totalHits = hits,
                reportedAt = at,
                lastHitAt = fields.getOrNull(LAST_HIT_INDEX)?.toLongOrNull(),
                activeSince = fields.getOrNull(ACTIVE_SINCE_INDEX)?.toLongOrNull(),
            )
        }
    }
}
