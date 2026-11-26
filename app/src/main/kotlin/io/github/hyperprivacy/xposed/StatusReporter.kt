package io.github.hyperprivacy.xposed

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import io.github.hyperprivacy.core.status.EndpointRecorder
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedCodec
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.RetryBackoff
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkCodec
import io.github.hyperprivacy.core.status.SinkReport
import io.github.hyperprivacy.core.status.StatusCodec
import io.github.hyperprivacy.status.StatusContract
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
 * next attempt follows [RetryBackoff] (5 s doubling to 5 min). Once the attempt budget is spent
 * the reporter stops trying for the life of the process but keeps counting locally.
 *
 * @param context supplies a Context once one exists (returns `null` early in process start).
 */
internal class StatusReporter(private val log: HookLog, context: () -> Context?) {
    private data class RuleKey(val packageName: String, val ruleId: String)

    private data class SinkKey(val packageName: String, val sink: String)

    private val delivery = ReportDelivery(log, context)
    private val backoff = RetryBackoff()
    private val states = ConcurrentHashMap<RuleKey, HookState>()
    private val sinks = ConcurrentHashMap<SinkKey, HookState>()
    private val hits = ConcurrentHashMap<RuleKey, AtomicLong>()
    private val endpoints = EndpointRecorder()
    private val loggedHits: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val scheduled = AtomicBoolean(false)

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
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
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

    /** Diagnostics: records an endpoint; returns it if it was new (for one-time logging). */
    fun observe(packageName: String, host: String, path: String?, blocked: Boolean): ObservedEndpoint? {
        val fresh = endpoints.record(packageName, host, path, blocked, System.currentTimeMillis())
        markDirty()
        return fresh
    }

    private fun markDirty() {
        dirty.set(true)
        schedule()
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
        val observed = endpoints.drain()
        val outcome = log.guard("deliver status") { deliver(deltas, observed) } ?: ReportDelivery.Outcome.FAILED
        val delivered = outcome == ReportDelivery.Outcome.DELIVERED || outcome == ReportDelivery.Outcome.REJECTED
        if (delivered) {
            failures = 0
        } else {
            deltas.forEach { (key, count) -> hits.getOrPut(key) { AtomicLong() }.addAndGet(count) }
            endpoints.restore(observed)
            failures++
        }
        scheduled.set(false)
        if (!delivered || dirty.get()) schedule()
    }

    private fun deliver(deltas: Map<RuleKey, Long>, observed: List<ObservedEndpoint>): ReportDelivery.Outcome {
        val reports = (states.keys + deltas.keys).mapNotNull { key ->
            states[key]?.let { RuleReport(key.packageName, key.ruleId, it, deltas[key] ?: 0) }
        }
        val sinkReports = sinks.map { (key, state) -> SinkReport(key.packageName, key.sink, state) }
        if (reports.isEmpty() && sinkReports.isEmpty() && observed.isEmpty()) return ReportDelivery.Outcome.DELIVERED
        val extras = Bundle().apply {
            if (reports.isNotEmpty()) putString(StatusContract.KEY_PAYLOAD, StatusCodec.encode(reports))
            if (sinkReports.isNotEmpty()) putString(StatusContract.KEY_SINKS, SinkCodec.encode(sinkReports))
            if (observed.isNotEmpty()) putString(StatusContract.KEY_OBSERVED, ObservedCodec.encode(observed))
        }
        return delivery.send(extras)
    }

    private companion object {
        const val THREAD_NAME = "HyperPrivacyStatus"
        const val INITIAL_DELAY_MS = 5_000L
        const val FLUSH_INTERVAL_MS = 30_000L

        /** Distinct (rule, target) pairs logged per process before first-hit logging goes quiet. */
        const val MAX_LOGGED_HITS = 256
    }
}
