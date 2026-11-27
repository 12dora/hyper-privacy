package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.status.SinkPerf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Per-sink cost counters of this process, collected only while diagnostics mode is on.
 *
 * The hot path is [FailOpenHooker]: with [enabled] off it costs one volatile boolean read and
 * nothing else. With it on, one call is two `System.nanoTime` reads and three lock-free updates
 * ([LongAdder] for the totals, so concurrent binder threads do not contend; a compare-and-set
 * only when a call sets a new maximum). Nothing allocates after a sink's first sample.
 *
 * Counters are drained into the status batch by [StatusReporter]; what they hold is the cost of
 * the module's own decision, not of the call it intercepted.
 */
internal object SinkStats {
    /** Diagnostics mode, set from the preferences listener; read on every intercepted call. */
    @Volatile
    @JvmField
    var enabled: Boolean = false

    /** Counters of one sink; shared by every hook of that sink in the process. */
    class Cell {
        private val calls = LongAdder()
        private val totalNs = LongAdder()
        private val maxNs = AtomicLong()

        fun record(nanos: Long) {
            calls.increment()
            totalNs.add(nanos)
            raiseMax(nanos)
        }

        fun restore(row: SinkPerf) {
            calls.add(row.calls)
            totalNs.add(row.totalNs)
            raiseMax(row.maxNs)
        }

        fun hasSamples(): Boolean = calls.sum() > 0

        private fun raiseMax(nanos: Long) {
            var seen = maxNs.get()
            while (nanos > seen && !maxNs.compareAndSet(seen, nanos)) seen = maxNs.get()
        }

        /**
         * Counts since the last drain; the maximum is per drain interval. A sample racing the drain
         * may land its time without its count: that time is kept for the next drain, not dropped.
         */
        fun drain(sink: String): SinkPerf? {
            val count = calls.sumThenReset()
            val total = totalNs.sumThenReset()
            val max = maxNs.getAndSet(0)
            if (count > 0) return SinkPerf(sink, count, total, max)
            if (total > 0) totalNs.add(total)
            if (max > 0) raiseMax(max)
            return null
        }
    }

    private val cells = ConcurrentHashMap<String, Cell>()
    private val disabledListeners = CopyOnWriteArrayList<() -> Unit>()

    fun cell(sink: String): Cell = cells.getOrPut(sink) { Cell() }

    /** Samples taken since the last [drain]. */
    fun drain(): List<SinkPerf> = cells.mapNotNull { (sink, cell) -> cell.drain(sink) }

    /** Puts [rows] (a delivery that failed) back. */
    fun restore(rows: List<SinkPerf>) {
        rows.forEach { row -> cell(row.sink).restore(row) }
    }

    /** Whether anything was recorded since the last [drain]; checked only off the hot path. */
    fun hasSamples(): Boolean = cells.values.any { it.hasSamples() }

    /** Runs [listener] (on an arbitrary thread) when diagnostics mode turns off, so pending samples get delivered. */
    fun addDisabledListener(listener: () -> Unit) {
        disabledListeners += listener
    }

    /** Follows the diagnostics switch; called off the hot path whenever the preferences change. */
    fun setEnabled(on: Boolean) {
        val wasOn = enabled
        enabled = on
        if (wasOn && !on) {
            disabledListeners.forEach { listener ->
                try {
                    listener()
                } catch (_: Throwable) {
                    // A listener only schedules a delivery; the others must still run.
                }
            }
        }
    }
}
