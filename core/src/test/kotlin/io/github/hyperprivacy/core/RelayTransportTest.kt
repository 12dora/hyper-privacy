package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.ProcessMeta
import io.github.hyperprivacy.core.status.RateLimiter
import io.github.hyperprivacy.core.status.RelayBuffer
import io.github.hyperprivacy.core.status.RelayPayload
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkCodec
import io.github.hyperprivacy.core.status.SinkReport
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.core.status.StatusRelay
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayTransportTest {
    private val batch = StatusBatch(
        reports = listOf(RuleReport(PKG, "tracker.x", HookState.ACTIVE, 3)),
        sinks = listOf(SinkReport(PKG, "dns", HookState.MISSED)),
        observed = listOf(ObservedEndpoint(PKG, "a.example.com", "/v1", 2, 10, 20, true)),
        meta = listOf(ProcessMeta(PKG, 7, 5)),
    )

    @Test
    fun `payload round-trips and drops malformed or unknown lines`() {
        assertEquals(batch, RelayPayload.decode(RelayPayload.encode(batch)))
        val encoded = String(RelayPayload.encode(batch), Charsets.UTF_8)
        val noisy = "x\tjunk\n\n" + encoded + "\ns\ttoo\tmany\tfields\tHERE\n"
        assertEquals(batch, RelayPayload.decode(noisy.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `payload decoding is capped per table and in lines looked at`() {
        val line = "s\t$PKG\tdns\tACTIVE\n"
        val many = RelayPayload.decode(line.repeat(StatusRelay.MAX_ROWS_PER_TABLE * 3).toByteArray())
        assertEquals(StatusRelay.MAX_ROWS_PER_TABLE, many.sinks.size)
        // Junk first: decoding stops after a bounded number of lines, valid rows after them are never read.
        val junkFirst = "\n".repeat(StatusRelay.MAX_ROWS_PER_TABLE * 8) + line
        assertTrue(RelayPayload.decode(junkFirst.toByteArray()).isEmpty)
    }

    @Test
    fun `table decoding stops at the row cap`() {
        val text = (1..10_000).joinToString("\n") { "$PKG\ts$it\tACTIVE" }
        assertEquals(100, SinkCodec.decode(text, maxRows = 100).size)
        assertEquals(listOf("s1", "s2"), SinkCodec.decode(text, maxRows = 2).map { it.sink })
    }

    @Test
    fun `fit keeps meta first and never exceeds the budget`() {
        val big = batch.copy(reports = List(1_000) { RuleReport(PKG, "tracker.x$it", HookState.ACTIVE, 1) })
        val (head, rest) = RelayPayload.fit(big, 2_000)
        assertTrue(RelayPayload.encode(head).size <= 2_000)
        assertEquals(batch.meta, head.meta)
        assertEquals(big.reports, head.reports + rest.reports)
        assertEquals(big.observed, head.observed + rest.observed)
        val (all, none) = RelayPayload.fit(batch, Int.MAX_VALUE)
        assertEquals(batch, all)
        assertTrue(none.isEmpty)
    }

    @Test
    fun `relay buffer merges, caps and drains`() {
        val buffer = RelayBuffer(maxReports = 2, maxSinks = 1, maxObserved = 1, maxMeta = 1)
        buffer.add(batch)
        buffer.add(batch.copy(reports = batch.reports.map { it.copy(hits = Long.MAX_VALUE) }))
        buffer.add(
            StatusBatch(
                reports = listOf(
                    RuleReport(PKG, "tracker.y", HookState.ACTIVE, 1),
                    RuleReport(PKG, "tracker.z", HookState.ACTIVE, 1),
                ),
                sinks = listOf(SinkReport(PKG, "tls", HookState.ACTIVE)),
                meta = listOf(ProcessMeta(PKG, 8, 6)),
            ),
        )
        val taken = buffer.take(Int.MAX_VALUE)
        assertTrue(buffer.isEmpty)
        assertEquals(listOf("tracker.x", "tracker.y"), taken.reports.map { it.ruleId }, "new keys beyond cap dropped")
        assertEquals(Long.MAX_VALUE, taken.reports.first().hits, "saturated")
        assertEquals(listOf("dns"), taken.sinks.map { it.sink })
        assertEquals(4L, taken.observed.single().count)
        assertEquals(8L, taken.meta.single().versionCode, "existing keys still update")
    }

    @Test
    fun `relay buffer restore keeps newer state and adds counts back`() {
        val buffer = RelayBuffer()
        buffer.add(batch)
        val taken = buffer.take(Int.MAX_VALUE)
        buffer.add(StatusBatch(reports = listOf(RuleReport(PKG, "tracker.x", HookState.MISSED, 1))))
        buffer.restore(taken)
        val again = buffer.take(Int.MAX_VALUE)
        assertEquals(RuleReport(PKG, "tracker.x", HookState.MISSED, 4), again.reports.single())
        assertEquals(batch.observed, again.observed)
        assertTrue(buffer.take(10).isEmpty)
    }

    @Test
    fun `relay buffer takes no more than the budget`() {
        val buffer = RelayBuffer()
        buffer.add(StatusBatch(reports = List(500) { RuleReport(PKG, "tracker.x$it", HookState.ACTIVE, 1) }))
        val first = buffer.take(1_000)
        assertTrue(RelayPayload.encode(first).size <= 1_000)
        assertFalse(buffer.isEmpty)
        var rows = first.reports.size
        while (!buffer.isEmpty) rows += buffer.take(1_000).reports.size
        assertEquals(500, rows)
    }

    @Test
    fun `rate limiter allows a burst, then one call per interval, per key`() {
        val limiter = RateLimiter(burst = 2, refillMillis = 100)
        assertTrue(limiter.tryAcquire(1, 0))
        assertTrue(limiter.tryAcquire(1, 0))
        assertFalse(limiter.tryAcquire(1, 50))
        assertTrue(limiter.tryAcquire(2, 50), "other keys have their own bucket")
        assertTrue(limiter.tryAcquire(1, 100))
        assertFalse(limiter.tryAcquire(1, 150))
        assertTrue(limiter.tryAcquire(1, 10_000))
        assertTrue(limiter.tryAcquire(1, 10_000))
        assertFalse(limiter.tryAcquire(1, 10_000), "a long pause refills to the burst, not beyond")
    }

    @Test
    fun `rate limiter evicts without handing out a fresh burst`() {
        val limiter = RateLimiter(burst = 3, refillMillis = 1_000, slots = 2)
        assertTrue(limiter.tryAcquire(10, 0))
        assertTrue(limiter.tryAcquire(11, 1))
        assertFalse(limiter.tryAcquire(12, 2), "table full: the newcomer starts empty")
        assertTrue(limiter.tryAcquire(12, 1_002))
    }

    private companion object {
        const val PKG = "com.example.shop"
    }
}
