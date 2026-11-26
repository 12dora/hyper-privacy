package io.github.hyperprivacy.status

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.edit
import io.github.hyperprivacy.core.health.ActivationHistory
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkReport
import io.github.hyperprivacy.core.status.saturatingAdd

/**
 * Latest known status of one rule in one package.
 *
 * @property lastHitAt when a report with a non-zero hit delta was last received (receipt time,
 *   so at most one flush interval late); `null` if the rule never blocked anything here.
 * @property activeSince when the rule was first reported ACTIVE after being MISSED or unknown;
 *   `null` while MISSED.
 * @property history whether the rule was ever ACTIVE here on this device, and with which app
 *   version last; survives MISSED reports and reboots.
 */
internal data class StoredStatus(
    val state: HookState,
    val totalHits: Long,
    val reportedAt: Long,
    val lastHitAt: Long? = null,
    val activeSince: Long? = null,
    val history: ActivationHistory = ActivationHistory.NEVER,
) {
    /** Reports from before the current boot say nothing about whether hooks are live now. */
    val isFromCurrentBoot: Boolean get() = reportedAt >= bootTimeMillis()
}

/** Wall-clock time of the current boot. */
internal fun bootTimeMillis(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

/**
 * App-private persistence for status reports: rule status keyed by `package|ruleId`, sink states
 * ([SinkTable]), process facts per package ([MetaTable]) and the diagnostics table of observed
 * endpoints ([ObservedTable]). Rule health queries live in `StatusHealth.kt`.
 *
 * Rule rows are `STATE,hits,reportedAt,lastHitAt,activeSince,firstActiveAt,lastActiveVersion`;
 * rows with 3 or 5 fields predate the later columns and are still read.
 */
internal class StatusStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val sinks = SinkTable(context.getSharedPreferences(SINKS_FILE_NAME, Context.MODE_PRIVATE))
    private val observed = ObservedTable(context.getSharedPreferences(OBSERVED_FILE_NAME, Context.MODE_PRIVATE))

    /** Running per-sink cost totals (diagnostics mode); one row per sink, never per package. */
    val perf = PerfTable(context.getSharedPreferences(PERF_FILE_NAME, Context.MODE_PRIVATE))

    /**
     * Vendor-trusted signers as system_server last reported them. Not in [allPrefs]: no UI shows
     * them, so they must not trigger UI rebuilds.
     */
    val trust = TrustTable(context.getSharedPreferences(TRUST_FILE_NAME, Context.MODE_PRIVATE))

    /** Process facts (version that ran, start and last-seen times) per package. */
    val meta = MetaTable(context.getSharedPreferences(META_FILE_NAME, Context.MODE_PRIVATE))

    /** Used by health queries for the installed state and version of target packages. */
    val packageManager: PackageManager = context.packageManager

    private val allPrefs: List<SharedPreferences> get() = listOf(prefs, sinks.prefs, observed.prefs, meta.prefs)

    /**
     * Merges [reports]: state is replaced, hits (deltas) are accumulated, hit and activation times
     * tracked. [versionOf] gives the versionCode the reporting app ran as (`null` if unknown); it
     * is recorded with every ACTIVE report as the rule's [ActivationHistory.lastActiveVersion].
     */
    @Synchronized
    fun merge(reports: List<RuleReport>, versionOf: (String) -> Long? = { null }) {
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
                    totalHits = saturatingAdd(previous?.totalHits ?: 0, report.hits),
                    reportedAt = now,
                    lastHitAt = if (report.hits > 0) now else previous?.lastHitAt,
                    activeSince = activeSince,
                    history = (previous?.history ?: ActivationHistory.NEVER)
                        .record(report.state, now, versionOf(report.packageName)),
                )
                putString(key, encode(stored))
            }
        }
    }

    fun get(packageName: String, ruleId: String): StoredStatus? = decode(
        prefs.getString(key(packageName, ruleId), null),
    )

    /** Replaces the stored state of each reported sink, stamped with the receipt time. */
    fun mergeSinks(reports: List<SinkReport>) = sinks.merge(reports, System.currentTimeMillis())

    /**
     * Latest known state of every sink in every package, keeping only those reported at or after
     * [since] (wall-clock millis). Rows stored before sinks carried a time count as reported at 0.
     */
    fun sinkStates(since: Long = 0): List<SinkReport> = sinks.states(since)

    /** Per package, the sinks that were ACTIVE at least once on this device. */
    fun sinksEverActive(): Map<String, Set<String>> = sinks.everActive()

    /** Accumulates diagnostics endpoints (count deltas) from a report. */
    fun mergeObserved(endpoints: List<ObservedEndpoint>) = observed.merge(endpoints)

    /** Observed endpoints (running totals), most recently seen first. */
    fun observedEndpoints(): List<ObservedEndpoint> = observed.all()

    /** Clears what diagnostics mode collected: observed endpoints and sink costs. */
    fun clearObserved() {
        observed.clear()
        perf.clear()
    }

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
        const val PERF_FILE_NAME = "status_perf"
        const val TRUST_FILE_NAME = "status_trust"
        const val KEY_SEPARATOR = "|"
        const val SEPARATOR = ","

        /** Rows written before hit and activation times were tracked. */
        const val LEGACY_FIELD_COUNT = 3

        /** Rows written before activation history was tracked. */
        const val PRE_HISTORY_FIELD_COUNT = 5
        const val FIELD_COUNT = 7
        const val LAST_HIT_INDEX = 3
        const val ACTIVE_SINCE_INDEX = 4
        const val FIRST_ACTIVE_INDEX = 5
        const val LAST_ACTIVE_VERSION_INDEX = 6
        val FIELD_COUNTS = setOf(LEGACY_FIELD_COUNT, PRE_HISTORY_FIELD_COUNT, FIELD_COUNT)

        fun key(packageName: String, ruleId: String) = "$packageName$KEY_SEPARATOR$ruleId"

        fun encode(status: StoredStatus) = listOf(
            status.state.name,
            status.totalHits,
            status.reportedAt,
            status.lastHitAt ?: "",
            status.activeSince ?: "",
            status.history.firstActiveAt ?: "",
            status.history.lastActiveVersion ?: "",
        ).joinToString(SEPARATOR)

        fun decode(text: String?): StoredStatus? {
            val fields = text?.split(SEPARATOR)?.takeIf { it.size in FIELD_COUNTS } ?: return null
            val state = HookState.parse(fields[0]) ?: return null
            val hits = fields[1].toLongOrNull() ?: return null
            val at = fields[2].toLongOrNull() ?: return null
            val activeSince = fields.getOrNull(ACTIVE_SINCE_INDEX)?.toLongOrNull()
            val history = if (fields.size == FIELD_COUNT) {
                ActivationHistory(
                    firstActiveAt = fields[FIRST_ACTIVE_INDEX].toLongOrNull(),
                    lastActiveVersion = fields[LAST_ACTIVE_VERSION_INDEX].toLongOrNull(),
                )
            } else {
                ActivationHistory.fromLegacy(state, activeSince, at)
            }
            return StoredStatus(
                state = state,
                totalHits = hits,
                reportedAt = at,
                lastHitAt = fields.getOrNull(LAST_HIT_INDEX)?.toLongOrNull(),
                activeSince = activeSince,
                history = history,
            )
        }
    }
}
