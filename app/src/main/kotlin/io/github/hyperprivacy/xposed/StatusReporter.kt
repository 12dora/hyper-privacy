package io.github.hyperprivacy.xposed

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.core.firewall.UidPolicy
import io.github.hyperprivacy.core.status.EndpointRecorder
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.ProcessMeta
import io.github.hyperprivacy.core.status.RetryBackoff
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkPerf
import io.github.hyperprivacy.core.status.SinkReport
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.status.StatusContract
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects rule states, sink states, hit counts and (in diagnostics mode) observed endpoints in a
 * hooked process and ships them to the module app in coalesced batches, at most once per
 * [FLUSH_INTERVAL_MS], from a single daemon thread.
 *
 * Recording is lock-free and safe on hot paths; delivery never runs on the caller's thread, so a
 * slow or absent module app cannot stall the host. When delivery fails, counts are kept and the
 * next attempt follows [RetryBackoff] (5 s doubling to 5 min). Once the attempt budget is spent
 * the reporter stops trying for the life of the process but keeps counting locally.
 *
 * Every batch from an app process also carries its [ProcessMeta] (own versionCode, read once,
 * and process start time), and a heartbeat every [HEARTBEAT_HOURS] re-sends the state so the app
 * can tell a long-lived quiet process (a candidate for "idle rule") from one that is gone.
 *
 * In system_server it also carries what protected third-party apps hand it through the status
 * relay ([relay]); those apps pass [relayMethod] and never talk to the module app themselves.
 *
 * @param context supplies a Context once one exists (returns `null` early in process start).
 * @param relayMethod for a protected third-party app: the relay method name, `null` while unknown.
 */
internal class StatusReporter(
    private val log: HookLog,
    private val context: () -> Context?,
    relayMethod: (() -> String?)? = null,
) {
    private data class RuleKey(val packageName: String, val ruleId: String)

    private data class SinkKey(val packageName: String, val sink: String)

    private val delivery = ReportDelivery(log, context, relayMethod)
    private val backoff = RetryBackoff()
    private val states = ConcurrentHashMap<RuleKey, HookState>()
    private val sinks = ConcurrentHashMap<SinkKey, HookState>()
    private val hits = ConcurrentHashMap<RuleKey, AtomicLong>()
    private val endpoints = EndpointRecorder()
    private val loggedHits: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val relayedObserved = BoundedQueue<ObservedEndpoint>(MAX_RELAYED_OBSERVED)
    private val relayedMeta = ConcurrentHashMap<String, ProcessMeta>()
    private val scheduled = AtomicBoolean(false)
    private val heartbeatStarted = AtomicBoolean(false)

    init {
        // Diagnostics mode just turned off: ship the last sink costs instead of leaving them unsent.
        SinkStats.addDisabledListener { if (SinkStats.hasSamples()) markDirty() }
    }

    /** This process's facts; read once on the executor thread. */
    private var processMeta: ProcessMeta? = null
    private var processMetaRead = false

    /** Set by every record; cleared when a flush starts, so records racing a flush are not lost. */
    private val dirty = AtomicBoolean(false)

    /** Consecutive failed deliveries; touched only on the executor thread. */
    @Volatile
    private var failures = 0

    @Volatile
    private var gaveUp = false

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
     * system_server: merges a batch a protected app relayed (already filtered by
     * [io.github.hyperprivacy.core.status.StatusRelay.accept]); it goes out with the next flush.
     */
    fun relay(batch: StatusBatch) {
        batch.reports.forEach { report ->
            val key = RuleKey(report.packageName, report.ruleId)
            states[key] = report.state
            if (report.hits > 0) hits.getOrPut(key) { AtomicLong() }.addAndGet(report.hits)
        }
        batch.sinks.forEach { sinks[SinkKey(it.packageName, it.sink)] = it.state }
        batch.observed.forEach(relayedObserved::offer)
        batch.meta.forEach { if (relayedMeta.size < MAX_RELAYED_META) relayedMeta[it.packageName] = it }
        // Per sink name only; folded into this process's counters and sent with its own next flush.
        SinkStats.restore(batch.perf)
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
        if (heartbeatStarted.compareAndSet(false, true)) startHeartbeat()
    }

    private fun startHeartbeat() {
        log.guard("schedule status heartbeat") {
            executor.scheduleWithFixedDelay(::markDirty, HEARTBEAT_HOURS, HEARTBEAT_HOURS, TimeUnit.HOURS)
        }
    }

    private fun schedule() {
        if (gaveUp || !scheduled.compareAndSet(false, true)) return
        val delay = if (failures == 0) {
            maxOf(INITIAL_DELAY_MS, FLUSH_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastFlushAt))
        } else {
            backoff.delayAfter(failures)
        }
        if (delay == null) {
            gaveUp = true
            log.warn("status delivery failed $failures times; giving up for this process, counting locally")
            return
        }
        log.guard("schedule status flush") { executor.schedule(::flush, delay, TimeUnit.MILLISECONDS) }
            ?: scheduled.set(false)
    }

    private fun flush() {
        lastFlushAt = SystemClock.elapsedRealtime()
        dirty.set(false)
        val deltas = hits.mapValues { (_, count) -> count.getAndSet(0) }.filterValues { it > 0 }
        val relayed = relayedObserved.drain()
        val observed = endpoints.drain() + relayed
        val perf = SinkStats.drain()
        val outcome = log.guard("deliver status") { deliver(deltas, observed, perf) } ?: ReportDelivery.Outcome.FAILED
        val delivered = outcome == ReportDelivery.Outcome.DELIVERED || outcome == ReportDelivery.Outcome.REJECTED
        if (delivered) {
            failures = 0
        } else {
            SinkStats.restore(perf)
            deltas.forEach { (key, count) -> hits.getOrPut(key) { AtomicLong() }.addAndGet(count) }
            endpoints.restore(observed)
            relayed.forEach(relayedObserved::offer)
            failures++
        }
        scheduled.set(false)
        if (!delivered || dirty.get()) schedule()
    }

    private fun deliver(
        deltas: Map<RuleKey, Long>,
        observed: List<ObservedEndpoint>,
        perf: List<SinkPerf>,
    ): ReportDelivery.Outcome {
        val reports = (states.keys + deltas.keys).mapNotNull { key ->
            states[key]?.let { RuleReport(key.packageName, key.ruleId, it, deltas[key] ?: 0) }
        }
        val sinkReports = sinks.map { (key, state) -> SinkReport(key.packageName, key.sink, state) }
        val nothingToSend = reports.isEmpty() && sinkReports.isEmpty() && observed.isEmpty()
        if (nothingToSend && perf.isEmpty()) return ReportDelivery.Outcome.DELIVERED
        val meta = listOfNotNull(readProcessMeta()) + relayedMeta.values
        return delivery.send(StatusContract.encode(StatusBatch(reports, sinkReports, observed, meta, perf)))
    }

    /**
     * The host's own versionCode and wall-clock start time. Read at most once (a PackageManager
     * IPC). system_server reports on behalf of other packages and has no package of its own: it
     * reports the module build it loaded instead ([ProcessMeta.MODULE]; compiled in, so no IPC),
     * which the app compares with the installed one to tell that a restart is due.
     */
    private fun readProcessMeta(): ProcessMeta? {
        if (processMetaRead) return processMeta
        val started = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        if (Process.myUid() % UidPolicy.PER_USER_RANGE == Process.SYSTEM_UID) {
            processMetaRead = true
            processMeta = ProcessMeta(ProcessMeta.MODULE, BuildConfig.VERSION_CODE.toLong(), started)
            return processMeta
        }
        val ctx = context() ?: return null
        processMetaRead = true
        processMeta = log.guard("read process meta") {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            ProcessMeta(ctx.packageName, info.longVersionCode, started)
        }
        return processMeta
    }

    private companion object {
        const val INITIAL_DELAY_MS = 5_000L
        const val FLUSH_INTERVAL_MS = 30_000L
        const val HEARTBEAT_HOURS = 6L

        /** Distinct (rule, target) pairs logged per process before first-hit logging goes quiet. */
        const val MAX_LOGGED_HITS = 256

        /** system_server: relayed endpoints held between flushes, and relayed processes remembered. */
        const val MAX_RELAYED_OBSERVED = 1_000
        const val MAX_RELAYED_META = 512
    }
}

/** A lock-free queue holding at most [capacity] items; offers beyond it are dropped. */
private class BoundedQueue<T : Any>(private val capacity: Int) {
    private val items = ConcurrentLinkedQueue<T>()
    private val count = AtomicInteger()

    fun offer(item: T) {
        if (count.incrementAndGet() > capacity) {
            count.decrementAndGet()
            return
        }
        items.add(item)
    }

    fun drain(): List<T> = buildList {
        while (true) {
            val next = items.poll() ?: break
            count.decrementAndGet()
            add(next)
        }
    }
}
