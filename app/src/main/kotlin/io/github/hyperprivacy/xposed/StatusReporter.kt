package io.github.hyperprivacy.xposed

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import io.github.hyperprivacy.core.firewall.UidPolicy
import io.github.hyperprivacy.core.status.EndpointRecorder
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.ProcessMeta
import io.github.hyperprivacy.core.status.RelayBuffer
import io.github.hyperprivacy.core.status.RelayKeys
import io.github.hyperprivacy.core.status.RelayPayload
import io.github.hyperprivacy.core.status.RetryBackoff
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkReport
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.core.status.StatusRelay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects rule states, sink states, hit counts and (in diagnostics mode) observed endpoints in a
 * hooked process and ships them to the module app in coalesced batches, at most once per
 * [FLUSH_INTERVAL_MS], from a single daemon thread.
 *
 * Recording is lock-free and safe on hot paths; delivery never runs on the caller's thread, so a
 * slow or absent module app cannot stall the host. When delivery fails, counts are kept and the
 * next attempt follows [backoff] (5 s doubling to 5 min, then slower); it never gives up, so
 * status arrives once the receiver is back (e.g. after the user unlocks a phone that rebooted
 * unattended).
 *
 * Every batch from an app process also carries its [ProcessMeta] (own versionCode, read once,
 * and process start time), and a heartbeat every [HEARTBEAT_HOURS] re-sends the state so the app
 * can tell a long-lived quiet process (a candidate for "idle rule") from one that is gone. One
 * batch holds at most [StatusRelay.MAX_PAYLOAD_BYTES] (relay) or [OWN_BUDGET_BYTES] of this
 * process's encoded rows ([RelayPayload.fit]); counts that do not fit stay for the next flush.
 *
 * In system_server it also carries what protected third-party apps hand it through the status
 * relay ([relay]), held in a bounded [RelayBuffer] until forwarded (at most
 * [RELAYED_BUDGET_BYTES] per batch, so a batch stays far below the Binder transaction limit), and
 * the vendor-trusted signers of its package snapshot ([trust]). Protected apps pass [relayKeys]
 * and never talk to the module app themselves.
 *
 * @param context supplies a Context once one exists (returns `null` early in process start).
 * @param relayKeys for a protected third-party app: its relay credentials, `null` while unknown.
 */
internal class StatusReporter(
    private val log: HookLog,
    private val context: () -> Context?,
    relayKeys: (() -> RelayKeys?)? = null,
    private val backoff: RetryBackoff = RetryBackoff(),
) {
    private val delivery = ReportDelivery(log, context, relayKeys)
    private val states = ConcurrentHashMap<RuleKey, HookState>()
    private val sinks = ConcurrentHashMap<SinkKey, HookState>()
    private val hits = ConcurrentHashMap<RuleKey, AtomicLong>()
    private val endpoints = EndpointRecorder()
    private val loggedHits: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val relayed by lazy { RelayBuffer() }
    private val scheduled = AtomicBoolean(false)
    private val heartbeatStarted = AtomicBoolean(false)

    /** system_server: whether [relay] was ever called (so [relayed] exists). */
    @Volatile
    private var relaying = false

    /** system_server: vendor-trusted signers forwarded with every batch. */
    @Volatile
    private var trustedSigners: Set<String> = emptySet()

    /** This process's facts; read once on the executor thread, never for system_server. */
    private var processMeta: ProcessMeta? = null
    private var processMetaRead = false

    /** Set by every record; cleared when a flush starts, so records racing a flush are not lost. */
    private val dirty = AtomicBoolean(false)

    /** Consecutive failed deliveries; touched only on the executor thread. */
    @Volatile
    private var failures = 0

    @Volatile
    private var lastFlushAt = 0L

    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            // Unnamed (`Thread-N`): thread names are visible to the host app and its crash SDKs.
            Thread(runnable).apply { isDaemon = true }
        }
    }

    fun state(packageName: String, ruleId: String, state: HookState) {
        states[RuleKey(packageName, ruleId)] = state
        markDirty()
    }

    fun sink(packageName: String, sink: String, state: HookState) {
        sinks[SinkKey(packageName, sink)] = state
        markDirty()
    }

    /**
     * Counts one block by [ruleId]. The first hit per (rule, [target]) is logged; later ones are
     * only counted. [target] is what was blocked (host, URL, caller uid) and is log-only.
     */
    fun hit(packageName: String, ruleId: String, target: String?) {
        hits.getOrPut(RuleKey(packageName, ruleId)) { AtomicLong() }.incrementAndGet()
        if (loggedHits.size < MAX_LOGGED_HITS && loggedHits.add("$ruleId $target")) {
            log.info("blocked by $ruleId in $packageName: $target (further hits are only counted)")
        }
        markDirty()
    }

    /**
     * system_server: holds a batch a protected app relayed (already filtered by
     * [StatusRelay.accept]) until it is forwarded; see [RelayBuffer] for its bounds.
     */
    fun relay(batch: StatusBatch) {
        relayed.add(batch)
        relaying = true
        markDirty()
    }

    /** system_server: the vendor-trusted signers of the current package snapshot. */
    fun trust(signers: Set<String>) {
        if (signers == trustedSigners) return
        trustedSigners = signers
        markDirty()
    }

    /** Diagnostics: records an endpoint; returns it if it was new (for one-time logging). */
    fun observe(packageName: String, host: String, path: String?, blocked: Boolean): ObservedEndpoint? {
        val fresh = endpoints.record(packageName, host, path, blocked, System.currentTimeMillis())
        markDirty()
        return fresh
    }

    private fun markDirty() {
        dirty.set(true)
        schedule()
        if (heartbeatStarted.compareAndSet(false, true)) {
            log.guard("schedule status heartbeat") {
                executor.scheduleWithFixedDelay(::markDirty, HEARTBEAT_HOURS, HEARTBEAT_HOURS, TimeUnit.HOURS)
            }
        }
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        val delay = if (failures == 0) {
            maxOf(INITIAL_DELAY_MS, FLUSH_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastFlushAt))
        } else {
            backoff.delayAfter(failures)
        }
        log.guard("schedule status flush") { executor.schedule(::flush, delay, TimeUnit.MILLISECONDS) }
            ?: scheduled.set(false)
    }

    private fun flush() {
        lastFlushAt = SystemClock.elapsedRealtime()
        dirty.set(false)
        val deltas = hits.mapValues { (_, count) -> count.getAndSet(0) }.filterValues { it > 0 }
        val mine = ownRows(states, sinks, deltas, endpoints.drain(), readProcessMeta())
        val budget = if (delivery.relayed) StatusRelay.MAX_PAYLOAD_BYTES else OWN_BUDGET_BYTES
        val (own, left) = RelayPayload.fit(mine, budget)
        val forwarded = if (relaying) relayed.take(RELAYED_BUDGET_BYTES) else StatusBatch()
        val outcome = log.guard("deliver status") { deliver(own, forwarded) } ?: ReportDelivery.Outcome.FAILED
        val delivered = outcome == ReportDelivery.Outcome.DELIVERED || outcome == ReportDelivery.Outcome.REJECTED
        // Rows that did not fit wait for the next flush (states and sinks are re-sent anyway).
        restoreCounts(left, hits, endpoints)
        if (delivered) {
            failures = 0
        } else {
            restoreCounts(own, hits, endpoints)
            if (relaying) relayed.restore(forwarded)
            failures++
        }
        val leftCounts = left.reports.any { it.hits > 0 } || left.observed.isNotEmpty()
        if (leftCounts || (relaying && !relayed.isEmpty)) dirty.set(true)
        scheduled.set(false)
        if (!delivered || dirty.get()) schedule()
    }

    private fun deliver(own: StatusBatch, forwarded: StatusBatch): ReportDelivery.Outcome {
        val nothing = own.reports.isEmpty() && own.sinks.isEmpty() && own.observed.isEmpty() && forwarded.isEmpty
        if (nothing) return ReportDelivery.Outcome.DELIVERED
        return delivery.send(own + forwarded, trustedSigners)
    }

    /**
     * The host's own versionCode and wall-clock start time. Read at most once (a PackageManager
     * IPC); `null` in system_server, which reports on behalf of other packages.
     */
    private fun readProcessMeta(): ProcessMeta? {
        if (processMetaRead) return processMeta
        if (Process.myUid() % UidPolicy.PER_USER_RANGE == Process.SYSTEM_UID) {
            processMetaRead = true
            return null
        }
        val ctx = context() ?: return null
        processMetaRead = true
        processMeta = log.guard("read process meta") {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            val uptime = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            ProcessMeta(ctx.packageName, info.longVersionCode, System.currentTimeMillis() - uptime)
        }
        return processMeta
    }

    private companion object {
        const val INITIAL_DELAY_MS = 5_000L
        const val FLUSH_INTERVAL_MS = 30_000L
        const val HEARTBEAT_HOURS = 6L

        /** Distinct (rule, target) pairs logged per process before first-hit logging goes quiet. */
        const val MAX_LOGGED_HITS = 256

        /** Encoded size of this process's own rows in one batch (UTF-8 bytes, so at least as many as chars). */
        const val OWN_BUDGET_BYTES = 64 * 1024

        /** system_server: encoded size of relayed rows forwarded in one batch. */
        const val RELAYED_BUDGET_BYTES = 64 * 1024
    }
}

private data class RuleKey(val packageName: String, val ruleId: String)

private data class SinkKey(val packageName: String, val sink: String)

/** One process's own rows: every known state with its hit delta, sinks, [observed] and its [meta]. */
private fun ownRows(
    states: Map<RuleKey, HookState>,
    sinks: Map<SinkKey, HookState>,
    deltas: Map<RuleKey, Long>,
    observed: List<ObservedEndpoint>,
    meta: ProcessMeta?,
): StatusBatch {
    val reports = (states.keys + deltas.keys).mapNotNull { key ->
        states[key]?.let { RuleReport(key.packageName, key.ruleId, it, deltas[key] ?: 0) }
    }
    val sinkReports = sinks.map { (key, state) -> SinkReport(key.packageName, key.sink, state) }
    return StatusBatch(reports, sinkReports, observed, listOfNotNull(meta))
}

/** Gives the hit deltas and endpoint counts of an unsent [batch] back to the local tallies. */
private fun restoreCounts(
    batch: StatusBatch,
    hits: ConcurrentHashMap<RuleKey, AtomicLong>,
    endpoints: EndpointRecorder,
) {
    batch.reports.forEach { report ->
        if (report.hits <= 0) return@forEach
        hits.getOrPut(RuleKey(report.packageName, report.ruleId)) { AtomicLong() }.addAndGet(report.hits)
    }
    endpoints.restore(batch.observed)
}
