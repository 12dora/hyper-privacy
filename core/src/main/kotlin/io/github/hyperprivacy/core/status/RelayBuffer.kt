package io.github.hyperprivacy.core.status

/**
 * system_server's holding area for rows protected apps relayed ([StatusRelay]) until they are
 * forwarded to the module app: merged by key (latest state wins, counts add up saturating, seen
 * windows widen), each table capped (new keys beyond the cap are dropped, existing keys still
 * update), and drained on delivery by [take], so nothing relayed outlives its delivery: a killed or
 * unprotected app does not keep looking alive. Each app re-sends its own state on its heartbeat.
 *
 * Thread-safe (one lock; relay calls are rate-limited, so it is never contended in practice).
 */
public class RelayBuffer(
    private val maxReports: Int = DEFAULT_MAX_REPORTS,
    private val maxSinks: Int = DEFAULT_MAX_SINKS,
    private val maxObserved: Int = DEFAULT_MAX_OBSERVED,
    private val maxMeta: Int = DEFAULT_MAX_META,
) {
    private data class RuleKey(val packageName: String, val ruleId: String)

    private data class SinkKey(val packageName: String, val sink: String)

    private data class EndpointKey(val packageName: String, val host: String, val pathPrefix: String?)

    private val reports = LinkedHashMap<RuleKey, RuleReport>()
    private val sinks = LinkedHashMap<SinkKey, SinkReport>()
    private val observed = LinkedHashMap<EndpointKey, ObservedEndpoint>()
    private val meta = LinkedHashMap<String, ProcessMeta>()

    @get:Synchronized
    public val isEmpty: Boolean get() = reports.isEmpty() && sinks.isEmpty() && observed.isEmpty() && meta.isEmpty()

    /** Merges [batch] in (see the class comment). */
    @Synchronized
    public fun add(batch: StatusBatch) {
        batch.reports.forEach { row ->
            reports.merge(RuleKey(row.packageName, row.ruleId), row, maxReports) { old ->
                row.copy(hits = saturatingAdd(old.hits, row.hits))
            }
        }
        batch.sinks.forEach { row -> sinks.merge(SinkKey(row.packageName, row.sink), row, maxSinks) { row } }
        batch.observed.forEach(::addObserved)
        batch.meta.forEach { row -> meta.merge(row.packageName, row, maxMeta) { row } }
    }

    /** Re-adds rows a failed delivery took ([take]); rows relayed meanwhile keep their newer state. */
    @Synchronized
    public fun restore(batch: StatusBatch) {
        batch.reports.forEach { row ->
            reports.merge(RuleKey(row.packageName, row.ruleId), row, maxReports) { newer ->
                newer.copy(hits = saturatingAdd(newer.hits, row.hits))
            }
        }
        batch.sinks.forEach { row -> sinks.merge(SinkKey(row.packageName, row.sink), row, maxSinks) { it } }
        batch.observed.forEach(::addObserved)
        batch.meta.forEach { row -> meta.merge(row.packageName, row, maxMeta) { it } }
    }

    private fun addObserved(row: ObservedEndpoint) {
        observed.merge(EndpointKey(row.packageName, row.host, row.pathPrefix), row, maxObserved) { old ->
            old.copy(
                count = saturatingAdd(old.count, row.count),
                firstSeen = minOf(old.firstSeen, row.firstSeen),
                lastSeen = maxOf(old.lastSeen, row.lastSeen),
                blocked = old.blocked || row.blocked,
            )
        }
    }

    /** Removes and returns the oldest rows that fit in [maxBytes] once encoded ([RelayPayload.fit]). */
    @Synchronized
    public fun take(maxBytes: Int): StatusBatch {
        if (isEmpty) return StatusBatch()
        val all = StatusBatch(
            reports = reports.values.toList(),
            sinks = sinks.values.toList(),
            observed = observed.values.toList(),
            meta = meta.values.toList(),
        )
        val taken = RelayPayload.fit(all, maxBytes).first
        taken.reports.forEach { reports.remove(RuleKey(it.packageName, it.ruleId)) }
        taken.sinks.forEach { sinks.remove(SinkKey(it.packageName, it.sink)) }
        taken.observed.forEach { observed.remove(EndpointKey(it.packageName, it.host, it.pathPrefix)) }
        taken.meta.forEach { meta.remove(it.packageName) }
        return taken
    }

    private inline fun <K, V> LinkedHashMap<K, V>.merge(key: K, row: V, cap: Int, combine: (V) -> V) {
        val old = get(key)
        when {
            old != null -> put(key, combine(old))
            size < cap -> put(key, row)
        }
    }

    public companion object {
        public const val DEFAULT_MAX_REPORTS: Int = 2_048
        public const val DEFAULT_MAX_SINKS: Int = 1_024
        public const val DEFAULT_MAX_OBSERVED: Int = 1_000
        public const val DEFAULT_MAX_META: Int = 512
    }
}
