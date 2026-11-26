package io.github.hyperprivacy.xposed

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.StatusCodec
import io.github.hyperprivacy.status.StatusContract
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects per-rule hook state and hit counts in a hooked process and ships them to the module
 * app in batches, at most once per [FLUSH_INTERVAL_MS], from a single daemon thread.
 *
 * Recording is lock-free and safe on hot paths such as DNS resolution; delivery never runs on
 * the caller's thread, so a slow or absent module app cannot stall the host.
 *
 * @param context supplies a Context once one exists (returns `null` early in process start).
 */
internal class StatusReporter(private val log: HookLog, private val context: () -> Context?) {
    private data class Key(val packageName: String, val ruleId: String)

    private val states = ConcurrentHashMap<Key, HookState>()
    private val hits = ConcurrentHashMap<Key, AtomicLong>()
    private val scheduled = AtomicBoolean(false)

    /** Set by every record; cleared when a flush starts, so records racing a flush are not lost. */
    private val dirty = AtomicBoolean(false)
    private val failedAttempts = AtomicInteger()

    @Volatile
    private var lastFlushAt = 0L

    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }
    }

    fun state(packageName: String, ruleId: String, state: HookState) {
        states[Key(packageName, ruleId)] = state
        dirty.set(true)
        schedule()
    }

    fun hit(packageName: String, ruleId: String) {
        hits.getOrPut(Key(packageName, ruleId)) { AtomicLong() }.incrementAndGet()
        dirty.set(true)
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        val sinceLast = SystemClock.elapsedRealtime() - lastFlushAt
        val delay = maxOf(INITIAL_DELAY_MS, FLUSH_INTERVAL_MS - sinceLast)
        log.guard("schedule status flush") { executor.schedule(::flush, delay, TimeUnit.MILLISECONDS) }
            ?: scheduled.set(false)
    }

    private fun flush() {
        lastFlushAt = SystemClock.elapsedRealtime()
        dirty.set(false)
        val deltas = hits.mapValues { (_, count) -> count.getAndSet(0) }
        val delivered = log.guard("deliver status") { deliver(deltas) } == true
        scheduled.set(false)
        if (delivered) {
            failedAttempts.set(0)
            if (dirty.get()) schedule()
        } else {
            deltas.forEach { (key, count) -> hits.getOrPut(key) { AtomicLong() }.addAndGet(count) }
            // Bounded retries cover the window before user unlock, when the app cannot start yet.
            if (failedAttempts.incrementAndGet() <= MAX_FAILED_ATTEMPTS) schedule()
        }
    }

    private fun deliver(deltas: Map<Key, Long>): Boolean {
        val ctx = context() ?: return false
        val keys = states.keys + deltas.keys
        val reports = keys.mapNotNull { key ->
            val state = states[key] ?: return@mapNotNull null
            RuleReport(key.packageName, key.ruleId, state, deltas[key] ?: 0)
        }
        if (reports.isEmpty()) return true
        val extras = Bundle().apply { putString(StatusContract.KEY_PAYLOAD, StatusCodec.encode(reports)) }
        // A null reply means the app rejected the caller; retrying would not help.
        ctx.contentResolver.call(StatusContract.AUTHORITY, StatusContract.METHOD_REPORT, null, extras)
        return true
    }

    private companion object {
        const val THREAD_NAME = "HyperPrivacyStatus"
        const val INITIAL_DELAY_MS = 5_000L
        const val FLUSH_INTERVAL_MS = 30_000L
        const val MAX_FAILED_ATTEMPTS = 40
    }
}
