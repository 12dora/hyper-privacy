package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.health.DiagnosticInput
import io.github.hyperprivacy.core.health.DiagnosticReport
import io.github.hyperprivacy.core.status.PerfCodec
import io.github.hyperprivacy.core.status.RelayPayload
import io.github.hyperprivacy.core.status.SinkNames
import io.github.hyperprivacy.core.status.SinkPerf
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.core.status.StatusRelay
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SinkPerfTest {
    private val rows = listOf(SinkPerf("dns", 10, 5_000, 900), SinkPerf("url.okhttp", 4, 200_000, 150_000))

    private val input = DiagnosticInput(
        moduleVersion = "1",
        sdkInt = 37,
        romIncremental = "x",
        rules = emptyList(),
        missedSinks = emptyList(),
        suspectedEndpoints = emptyList(),
    )

    @Test
    fun `codec round-trips`() {
        assertEquals(rows, PerfCodec.decode(PerfCodec.encode(rows)))
    }

    @Test
    fun `codec drops malformed rows`() {
        val text = "dns\t1\t2\t3\nbad name!\t1\t2\t3\nip\t-1\t2\t3\nip\tx\t2\t3\nshort\t1\nok\t0\t0\t0"
        assertEquals(listOf(SinkPerf("dns", 1, 2, 3), SinkPerf("ok", 0, 0, 0)), PerfCodec.decode(text))
    }

    @Test
    fun `codec bounds the row count`() {
        val many = (0 until 500).map { SinkPerf("s$it", 1, 1, 1) }
        assertEquals(PerfCodec.MAX_ROWS, PerfCodec.decode(PerfCodec.encode(many)).size)
    }

    @Test
    fun `rows accumulate with the largest call kept`() {
        val sum = SinkPerf("dns", 1, 100, 100) + SinkPerf("dns", 3, 200, 70)
        assertEquals(SinkPerf("dns", 4, 300, 100), sum)
        assertEquals(75, sum.averageNs)
        assertEquals(Long.MAX_VALUE, (SinkPerf("a", Long.MAX_VALUE, 0, 0) + SinkPerf("a", 5, 0, 0)).calls)
    }

    @Test
    fun `relay passes perf rows of known sinks without package names`() {
        val unknown = SinkPerf("made.up", 1, 1, 1)
        val accepted = StatusRelay.accept(StatusBatch(perf = rows + unknown), { false }, SinkNames::isKnown, NOW)
        assertEquals(rows, accepted.perf)
        assertFalse(accepted.isEmpty)
    }

    @Test
    fun `perf rows survive the sealed relay payload and the relay filter`() {
        val perf = SinkNames.ALL.map { SinkPerf(it, 2, 300, 200) }
        val decoded = RelayPayload.decode(RelayPayload.encode(StatusBatch(perf = perf)))
        assertEquals(perf, decoded.perf)
        assertEquals(perf, StatusRelay.accept(decoded, { false }, SinkNames::isKnown, NOW).perf)
    }

    @Test
    fun `relayed perf rows are capped`() {
        val many = (0 until 500).map { SinkPerf(SinkNames.DNS, 1, 1, 1) }
        val decoded = RelayPayload.decode(RelayPayload.encode(StatusBatch(perf = many)))
        assertEquals(PerfCodec.MAX_ROWS, decoded.perf.size)
        val accepted = StatusRelay.accept(StatusBatch(perf = many), { false }, { true }, NOW)
        assertEquals(PerfCodec.MAX_ROWS, accepted.perf.size)
    }

    @Test
    fun `fit splits perf rows within the budget`() {
        val perf = SinkNames.ALL.map { SinkPerf(it, 2, 300, 200) }
        val (head, rest) = RelayPayload.fit(StatusBatch(perf = perf), 100)
        assertTrue(RelayPayload.encode(head).size <= 100)
        assertEquals(perf, head.perf + rest.perf)
    }

    @Test
    fun `every sink name fits one perf batch`() {
        assertTrue(SinkNames.ALL.size <= PerfCodec.MAX_ROWS)
        assertTrue(SinkNames.ALL.all(SinkPerf::isValidSink), "perf rows of every sink pass the codec")
    }

    @Test
    fun `report lists cost per sink, costliest first, in microseconds`() {
        val report = DiagnosticReport.render(input.copy(sinkPerf = rows))
        assertTrue("## Sink cost (diagnostics mode)" in report)
        assertTrue("- url.okhttp: 4 calls, avg 50.0 us, max 150.0 us" in report)
        assertTrue("- dns: 10 calls, avg 0.5 us, max 0.9 us" in report)
        assertTrue(report.indexOf("url.okhttp") < report.indexOf("- dns"))
    }

    @Test
    fun `report has no cost section without data and never names packages`() {
        assertFalse("Sink cost" in DiagnosticReport.render(input))
        val report = DiagnosticReport.render(input.copy(includeUserApps = false, sinkPerf = rows))
        assertFalse("com." in report)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
