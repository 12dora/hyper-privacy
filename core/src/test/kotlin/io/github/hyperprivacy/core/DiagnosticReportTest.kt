package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.health.DiagnosticInput
import io.github.hyperprivacy.core.health.DiagnosticReport
import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.health.PackageHealthRow
import io.github.hyperprivacy.core.health.RuleHealthRow
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.SinkReport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticReportTest {
    private fun endpoint(host: String, path: String?) = ObservedEndpoint(
        packageName = "com.miui.analytics",
        host = host,
        pathPrefix = path,
        count = 3,
        firstSeen = 1_700_000_000_123,
        lastSeen = 1_700_000_999_456,
        blocked = false,
    )

    private val input = DiagnosticInput(
        moduleVersion = "0.1.0",
        sdkInt = 37,
        romIncremental = "OS3.0.1.0.WOCCNXM",
        deviceModel = "24031PN0DC",
        deviceCodename = "aurora",
        rules = listOf(
            RuleHealthRow(
                ruleId = "market.tracking-domains",
                enabled = true,
                health = Health.NEEDS_REVIEW,
                packages = listOf(
                    PackageHealthRow("com.xiaomi.market", Health.NEEDS_REVIEW, 40007541, 40007540, 40007540),
                ),
            ),
            RuleHealthRow(
                ruleId = "onetrack.method",
                enabled = true,
                health = Health.OK,
                packages = listOf(
                    PackageHealthRow("com.xiaomi.bluetooth", Health.NOT_APPLICABLE, 1, null),
                    PackageHealthRow("com.miui.weather2", Health.NOT_APPLICABLE, 2, null),
                    PackageHealthRow("com.xiaomi.market", Health.OK, 3, null, 3),
                ),
            ),
            RuleHealthRow(
                ruleId = "onetrack.track",
                enabled = false,
                health = Health.BROKEN,
                packages = listOf(PackageHealthRow("com.android.thememanager", Health.BROKEN, null, 11540)),
            ),
        ),
        missedSinks = listOf(SinkReport("com.xiaomi.market", "url.okhttp", HookState.MISSED)),
        suspectedEndpoints = listOf(
            endpoint("tracking.miui.com", "/track/v4?imei=861234567890123&uid=42#frag"),
            endpoint("log-sdk.clickwifi.net", "/u/123456789/upload"),
            endpoint("api.example.com", "/d/0123456789abcdef0123"),
            endpoint("Bad Host!", "/x"),
        ),
    )

    private val report = DiagnosticReport.render(input)

    @Test
    fun `report lists versions, rules, sinks and endpoints`() {
        assertTrue("- module: 0.1.0" in report)
        assertTrue("- android sdk: 37" in report)
        assertTrue("- rom: OS3.0.1.0.WOCCNXM" in report)
        assertTrue("- market.tracking-domains [on] NEEDS_REVIEW" in report)
        assertTrue(
            "com.xiaomi.market: NEEDS_REVIEW (installed 40007541, baseline 40007540, last active 40007540)" in report,
        )
        assertTrue("- onetrack.track [off] BROKEN" in report)
        assertTrue("com.android.thememanager: BROKEN (installed -, baseline 11540, last active -)" in report)
        assertTrue("- device: 24031PN0DC (aurora)" in report)
        assertTrue("- baseline device: no" in report)
        assertTrue("## Not applicable\n\n- onetrack.method: 2 of 3 packages\n\n" in report)
        assertTrue("- com.xiaomi.market: url.okhttp" in report)
        assertTrue("- com.miui.analytics tracking.miui.com/track/v4\n" in report)
    }

    @Test
    fun `report never carries queries, ids, timestamps or invalid hosts`() {
        val secrets = listOf("?", "frag", "imei", "861234567890123", "uid=", "123456789", "0123456789abcdef")
        (secrets + listOf("Bad Host", "1700000"))
            .forEach { assertFalse(it in report, "report leaks '$it'") }
        assertTrue("- com.miui.analytics log-sdk.clickwifi.net/u/*\n" in report)
        assertTrue("- com.miui.analytics api.example.com/d/*\n" in report)
    }

    @Test
    fun `report only contains allowed line shapes`() {
        val allowed = listOf(
            Regex("# HyperPrivacy diagnostic report"),
            Regex("## (Rules|Not applicable|Missed sinks|Suspected reporting endpoints)"),
            Regex("- module: [A-Za-z0-9._+-]+"),
            Regex("- android sdk: \\d+"),
            Regex("- rom: [A-Za-z0-9._+-]+"),
            Regex("- device: [A-Za-z0-9._+-]+ \\([A-Za-z0-9._+-]+\\)"),
            Regex("- baseline device: (yes|no)"),
            Regex("- [a-z0-9._-]+: \\d+ of \\d+ packages"),
            Regex("- [a-z0-9._-]+ \\[(on|off)] [A-Z_]+"),
            Regex("  - [A-Za-z0-9_.]+: [A-Z_]+ \\(installed (\\d+|-), baseline (\\d+|-), last active (\\d+|-)\\)"),
            Regex("- [A-Za-z0-9_.]+: [A-Za-z0-9._+-]+"),
            Regex("- [A-Za-z0-9_.]+ [a-z0-9.:\\[\\]-]+(/[A-Za-z0-9._+*-]*)*"),
            Regex("\\(none\\)"),
            Regex(""),
        )
        val unexpected = report.lines().filterNot { line -> allowed.any { it.matches(line) } }
        assertEquals(emptyList(), unexpected)
    }

    @Test
    fun `free text fields are sanitized`() {
        val text = DiagnosticReport.render(
            input.copy(
                moduleVersion = "1.0 (debug)\n# injected",
                romIncremental = "",
                deviceModel = "Mi 14 / Pro",
                rules = emptyList(),
            ),
        )
        assertTrue("- module: 1.0debuginjected" in text)
        assertTrue("- device: Mi14Pro (aurora)" in text)
        assertTrue("- rom: -" in text)
        assertTrue("(none)" in text)
    }
}
