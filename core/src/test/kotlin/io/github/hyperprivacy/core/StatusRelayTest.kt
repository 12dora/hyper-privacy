package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ModuleSecret
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.ProcessMeta
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkReport
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.core.status.StatusRelay
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.Trackers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StatusRelayTest {
    private val pkg = "com.example.shop"
    private val sdkRule = TrackerProtection.ruleId(Trackers.all.first().id)

    @Test
    fun `method is derived from the secret`() {
        val a = StatusRelay.method(ByteArray(ModuleSecret.BYTES) { 1 })
        val b = StatusRelay.method(ByteArray(ModuleSecret.BYTES) { 2 })
        assertEquals(a, StatusRelay.method(ByteArray(ModuleSecret.BYTES) { 1 }))
        assertNotEquals(a, b)
        assertTrue(Regex("[0-9a-f]{32}").matches(a))
    }

    @Test
    fun `only the caller's own protected-app rows pass`() {
        val vendor = Catalog.allPackages.first { it != Catalog.SYSTEM_SCOPE }
        val other = "com.example.other"
        val batch = StatusBatch(
            reports = listOf(
                RuleReport(pkg, sdkRule, HookState.ACTIVE, 3),
                RuleReport(pkg, "mediadrm.device-id", HookState.ACTIVE, 1),
                RuleReport(pkg, "httpdns.resolvers", HookState.ACTIVE, 1),
                RuleReport(pkg, "tracker.unknown-sdk", HookState.ACTIVE, 1),
                RuleReport(other, sdkRule, HookState.ACTIVE, 1),
                RuleReport(vendor, sdkRule, HookState.ACTIVE, 1),
                RuleReport(Catalog.SYSTEM_SCOPE, sdkRule, HookState.ACTIVE, 1),
                RuleReport(pkg, sdkRule, HookState.ACTIVE, -5),
            ),
            sinks = listOf(SinkReport(pkg, "dns", HookState.ACTIVE), SinkReport(other, "dns", HookState.ACTIVE)),
            observed = listOf(
                ObservedEndpoint(pkg, "a.example.com", null, 1, 0, 0, false),
                ObservedEndpoint(other, "a.example.com", null, 1, 0, 0, false),
            ),
            meta = listOf(ProcessMeta(pkg, 1, 0), ProcessMeta(other, 1, 0)),
        )
        val accepted = StatusRelay.accept(batch) { it == pkg || it == vendor || it == Catalog.SYSTEM_SCOPE }
        val expected = listOf(
            RuleReport(pkg, sdkRule, HookState.ACTIVE, 3),
            RuleReport(pkg, "mediadrm.device-id", HookState.ACTIVE, 1),
        )
        assertEquals(expected, accepted.reports)
        assertEquals(listOf(pkg), accepted.sinks.map { it.packageName })
        assertEquals(listOf(pkg), accepted.observed.map { it.packageName })
        assertEquals(listOf(pkg), accepted.meta.map { it.packageName })
    }

    @Test
    fun `tables are capped`() {
        val many = List(StatusRelay.MAX_ROWS_PER_TABLE * 2) { SinkReport(pkg, "s$it", HookState.ACTIVE) }
        assertEquals(StatusRelay.MAX_ROWS_PER_TABLE, StatusRelay.accept(StatusBatch(sinks = many)) { true }.sinks.size)
    }
}
