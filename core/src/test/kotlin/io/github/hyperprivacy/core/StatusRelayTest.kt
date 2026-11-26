package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ModuleSecret
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.ProcessMeta
import io.github.hyperprivacy.core.status.RelayKeys
import io.github.hyperprivacy.core.status.RelayPayload
import io.github.hyperprivacy.core.status.RuleReport
import io.github.hyperprivacy.core.status.SinkReport
import io.github.hyperprivacy.core.status.StatusBatch
import io.github.hyperprivacy.core.status.StatusRelay
import io.github.hyperprivacy.core.status.saturatingAdd
import io.github.hyperprivacy.core.system.SettingsObservation
import io.github.hyperprivacy.core.system.SettingsTable
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.Trackers
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusRelayTest {
    private val pkg = "com.example.shop"
    private val sdkRule = TrackerProtection.ruleId(Trackers.all.first().id)
    private val secret = ByteArray(ModuleSecret.BYTES) { 1 }
    private val known = setOf("dns", "url.okhttp")

    private fun accept(batch: StatusBatch, now: Long = NOW, owned: (String) -> Boolean = { it == pkg }) =
        StatusRelay.accept(batch, owned, known::contains, now)

    @Test
    fun `tokens differ per package and per secret and never look like a stock method`() {
        val a = RelayKeys.derive(secret, pkg).token
        assertEquals(a, RelayKeys.derive(secret, pkg).token)
        assertNotEquals(a, RelayKeys.derive(secret, "com.example.other").token)
        assertNotEquals(a, RelayKeys.derive(ByteArray(ModuleSecret.BYTES) { 2 }, pkg).token)
        assertTrue(Regex("[0-9a-f]{${RelayKeys.TOKEN_LENGTH}}").matches(a))
    }

    @Test
    fun `payloads open only with the sending package's key`() {
        val plaintext = RelayPayload.encode(StatusBatch(sinks = listOf(SinkReport(pkg, "dns", HookState.ACTIVE))))
        val sealed = RelayKeys.derive(secret, pkg).seal(plaintext, SecureRandom())
        assertContentEquals(plaintext, RelayKeys.derive(secret, pkg).open(sealed))
        assertNull(RelayKeys.derive(secret, "com.example.other").open(sealed))
        assertNull(RelayKeys.derive(secret, pkg).open(sealed.copyOf(sealed.size - 1)))
        assertNull(RelayKeys.derive(secret, pkg).open(ByteArray(3)))
        val tampered = sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertNull(RelayKeys.derive(secret, pkg).open(tampered))
        assertEquals(plaintext.size + RelayKeys.OVERHEAD, sealed.size)
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
        val accepted = accept(batch) { it == pkg || it == vendor || it == Catalog.SYSTEM_SCOPE }
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
    fun `unknown sinks, settings hosts, huge hit deltas and future times are refused or clamped`() {
        val settingsHost = SettingsObservation.host(SettingsTable.entries.first())
        val batch = StatusBatch(
            reports = listOf(RuleReport(pkg, sdkRule, HookState.ACTIVE, Long.MAX_VALUE)),
            sinks = listOf(
                SinkReport(pkg, "dns", HookState.ACTIVE),
                SinkReport(pkg, "x".repeat(4096), HookState.ACTIVE),
            ),
            observed = listOf(
                ObservedEndpoint(pkg, settingsHost, "/k", 1, 0, 0, false),
                ObservedEndpoint(pkg, "a.example.com", null, 1, Long.MAX_VALUE, Long.MAX_VALUE, false),
            ),
            meta = listOf(ProcessMeta(pkg, 1, Long.MAX_VALUE)),
        )
        val accepted = accept(batch)
        assertEquals(StatusRelay.MAX_HITS_PER_REPORT, accepted.reports.single().hits)
        assertEquals(listOf("dns"), accepted.sinks.map { it.sink })
        val endpoint = accepted.observed.single()
        assertEquals("a.example.com", endpoint.host)
        assertEquals(NOW, endpoint.firstSeen)
        assertEquals(NOW, endpoint.lastSeen)
        assertEquals(NOW, accepted.meta.single().processStartAt)
    }

    @Test
    fun `tables are capped`() {
        val many = List(StatusRelay.MAX_ROWS_PER_TABLE * 2) { RuleReport(pkg, sdkRule, HookState.ACTIVE, 1) }
        assertEquals(StatusRelay.MAX_ROWS_PER_TABLE, accept(StatusBatch(reports = many)).reports.size)
    }

    @Test
    fun `counts saturate instead of overflowing`() {
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE, 1))
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertEquals(5L, saturatingAdd(2, 3))
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
