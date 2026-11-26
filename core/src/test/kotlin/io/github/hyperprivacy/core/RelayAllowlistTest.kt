package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ModuleSecret
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ProcessMeta
import io.github.hyperprivacy.core.status.RelayKeys
import io.github.hyperprivacy.core.status.RelayPayload
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkNames
import io.github.hyperprivacy.core.status.SinkPerf
import io.github.hyperprivacy.core.status.SinkReport
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.core.status.StatusRelay
import io.github.hyperprivacy.core.tracker.TrackerOrigin
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sink allowlist ([SinkNames.ALL]) and what main-line features send through the relay. */
class RelayAllowlistTest {
    private val pkg = "com.example.shop"
    private val keys = RelayKeys.derive(ByteArray(ModuleSecret.BYTES) { 3 }, pkg)

    /** Seals, opens and filters [batch] exactly as the reporter and system_server do. */
    private fun relay(batch: StatusBatch): StatusBatch {
        val sealed = keys.seal(RelayPayload.encode(batch), SecureRandom())
        assertTrue(sealed.size <= StatusRelay.MAX_SEALED_BYTES)
        val opened = keys.open(sealed) ?: error("payload did not open")
        return StatusRelay.accept(RelayPayload.decode(opened), { it == pkg }, SinkNames::isKnown, NOW)
    }

    @Test
    fun `every sink constant is in the allowlist`() {
        val constants = SinkNames::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) }
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
        assertTrue(constants.size >= SinkNames.ALL.size, "reflection found the constants")
        assertEquals(constants.toSet(), SinkNames.ALL, "a new sink constant must be added to SinkNames.ALL")
        assertEquals(constants.size, constants.toSet().size, "sink names are distinct")
    }

    @Test
    fun `sink states of every known sink survive the relay, unknown ones do not`() {
        val sinks = SinkNames.ALL.map { SinkReport(pkg, it, HookState.ACTIVE) }
        val unknown = SinkReport(pkg, "made.up", HookState.ACTIVE)
        assertEquals(sinks, relay(StatusBatch(sinks = sinks + unknown)).sinks)
    }

    @Test
    fun `suspected ad package hits survive the relay`() {
        val hit = RuleReport(pkg, TrackerOrigin.SUSPECT_RULE_ID, HookState.ACTIVE, 7)
        assertEquals(listOf(hit), relay(StatusBatch(reports = listOf(hit))).reports)
        val huge = hit.copy(hits = Long.MAX_VALUE)
        assertEquals(StatusRelay.MAX_HITS_PER_REPORT, relay(StatusBatch(reports = listOf(huge))).reports.single().hits)
    }

    @Test
    fun `perf rows survive the relay next to the app's own rows`() {
        val perf = listOf(SinkPerf(SinkNames.ORIGIN_OKHTTP, 3, 900, 500), SinkPerf(SinkNames.METHOD, 1, 10, 10))
        val batch = StatusBatch(
            reports = listOf(RuleReport(pkg, TrackerOrigin.SUSPECT_RULE_ID, HookState.ACTIVE, 1)),
            meta = listOf(ProcessMeta(pkg, 2, 1)),
            perf = perf + SinkPerf("made.up", 1, 1, 1),
        )
        val relayed = relay(batch)
        assertEquals(perf, relayed.perf)
        assertEquals(batch.reports, relayed.reports)
        assertEquals(batch.meta, relayed.meta)
    }

    @Test
    fun `a protected app cannot report the module's own build`() {
        val spoof = StatusBatch(meta = listOf(ProcessMeta(ProcessMeta.MODULE, 1, 0)))
        assertTrue(StatusRelay.accept(spoof, { true }, SinkNames::isKnown, NOW).meta.all { it.packageName == pkg })
        assertFalse(relay(spoof).meta.any { it.packageName == ProcessMeta.MODULE })
    }

    @Test
    fun `batches add up their perf rows`() {
        val a = StatusBatch(perf = listOf(SinkPerf(SinkNames.DNS, 1, 1, 1)))
        val b = StatusBatch(perf = listOf(SinkPerf(SinkNames.TLS, 1, 1, 1)))
        assertEquals(a.perf + b.perf, (a + b).perf)
        assertFalse((StatusBatch() + a).isEmpty)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
