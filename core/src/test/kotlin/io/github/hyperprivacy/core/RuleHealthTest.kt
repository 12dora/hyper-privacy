package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.health.HealthClock
import io.github.hyperprivacy.core.health.HealthSignals
import io.github.hyperprivacy.core.health.RuleHealth
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.HookState.ACTIVE
import io.github.hyperprivacy.core.status.HookState.MISSED
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class RuleHealthTest {
    private val day = TimeUnit.DAYS.toMillis(1)
    private val window = RuleHealth.IDLE_WINDOW_MS
    private val boot = 1_000 * day
    private val now = boot + 20 * day
    private val clock = HealthClock(now = now, bootAt = boot)

    /** Long-running boot, for cases about the "seen within the window" edge. */
    private val longBoot = HealthClock(now = now, bootAt = now - 4 * window)

    private val d = RuleKind.DOMAIN
    private val u = RuleKind.URL
    private val m = RuleKind.METHOD

    /** Healthy baseline: installed, versions match, ACTIVE this boot, seen just now, hit recently. */
    private val h = HealthSignals(
        installed = true,
        currentVersionCode = 100,
        baselineVersionCode = 100,
        ruleState = ACTIVE,
        ruleReportedAt = now - 1,
        lastProcessSeenAt = now - 1,
        activeSince = boot + 1,
        lastHitAt = now - day,
    )

    private data class Case(
        val name: String,
        val kind: RuleKind,
        val signals: HealthSignals,
        val expected: Health,
        val clock: HealthClock? = null,
    )

    /** Healthy, but nothing blocked for [quietFor]. */
    private fun quiet(quietFor: Long) = h.copy(lastHitAt = now - quietFor, activeSince = now - quietFor - day)

    private fun sinks(vararg pairs: Pair<String, HookState>) = h.copy(sinkStates = mapOf(*pairs))

    private val notInstalledCases = listOf(
        Case("not installed wins", d, h.copy(installed = false, ruleState = MISSED), Health.NOT_INSTALLED),
        Case("not installed, nothing known", RuleKind.FIREWALL, HealthSignals(installed = false), Health.NOT_INSTALLED),
    )

    private val brokenCases = listOf(
        Case("missed this boot", m, h.copy(ruleState = MISSED), Health.BROKEN),
        Case("missed exactly at boot", m, h.copy(ruleState = MISSED, ruleReportedAt = boot), Health.BROKEN),
        Case(
            "missed before boot is stale",
            m,
            h.copy(ruleState = MISSED, ruleReportedAt = boot - 1, lastProcessSeenAt = boot - 1),
            Health.NOT_RUN,
        ),
        Case("broken beats version change", m, h.copy(ruleState = MISSED, currentVersionCode = 200), Health.BROKEN),
        Case("broken beats idle", d, quiet(window).copy(ruleState = MISSED), Health.BROKEN),
        Case("domain: all sinks missed", d, sinks("dns" to MISSED, "tls" to MISSED, "url.ok" to MISSED), Health.BROKEN),
        Case("domain: one sink active", d, sinks("dns" to MISSED, "tls" to ACTIVE, "url.okhttp" to MISSED), Health.OK),
        Case("domain: unrelated sink", d, sinks("dns" to MISSED, "tls" to MISSED, "id.miui" to ACTIVE), Health.BROKEN),
        Case("url: all url sinks missed", u, sinks("url.java" to MISSED, "dns" to ACTIVE), Health.BROKEN),
        Case("url: one url sink active", u, sinks("url.java" to ACTIVE, "url.okhttp" to MISSED), Health.OK),
        Case("url: no url sink reported", u, sinks("dns" to MISSED), Health.OK),
        Case("method ignores sinks", m, sinks("dns" to MISSED, "url.java" to MISSED), Health.OK),
    )

    private val reviewCases = listOf(
        Case("version upgraded", d, h.copy(currentVersionCode = 101), Health.NEEDS_REVIEW),
        Case("version downgraded", d, h.copy(currentVersionCode = 99), Health.NEEDS_REVIEW),
        Case(
            "version changed, not run",
            RuleKind.FIREWALL,
            HealthSignals(installed = true, currentVersionCode = 2, baselineVersionCode = 1),
            Health.NEEDS_REVIEW,
        ),
        Case("no baseline", d, h.copy(currentVersionCode = 5, baselineVersionCode = null), Health.OK),
        Case("unknown current version", d, h.copy(currentVersionCode = null), Health.OK),
        Case("version change beats idle", d, quiet(window).copy(currentVersionCode = 1), Health.NEEDS_REVIEW),
    )

    private val notRunCases = listOf(
        Case("installed, never reported", d, HealthSignals(installed = true), Health.NOT_RUN),
        Case(
            "only previous boot",
            d,
            h.copy(ruleReportedAt = boot - day, lastProcessSeenAt = boot - day),
            Health.NOT_RUN,
        ),
        Case("process seen, no rule state", d, h.copy(ruleState = null, ruleReportedAt = null), Health.OK),
        Case(
            "firewall reported by system_server",
            RuleKind.FIREWALL,
            h.copy(lastProcessSeenAt = null, lastHitAt = null),
            Health.OK,
        ),
    )

    private val idleCases = listOf(
        Case("domain quiet exactly the window", d, quiet(window), Health.IDLE),
        Case("domain quiet 1 ms short", d, quiet(window - 1), Health.OK),
        Case("url quiet", u, quiet(window + day), Health.IDLE),
        Case("method quiet", m, quiet(window + day), Health.IDLE),
        Case("firewall exempt", RuleKind.FIREWALL, quiet(window + day), Health.OK),
        Case("identifier exempt", RuleKind.IDENTIFIER, quiet(window + day), Health.OK),
        Case("disabled never idle", d, quiet(window + day).copy(enabled = false), Health.OK),
        Case("never hit, active the window", d, h.copy(lastHitAt = null, activeSince = now - window), Health.IDLE),
        Case("never hit, just activated", d, h.copy(lastHitAt = null, activeSince = now - window + 1), Health.OK),
        Case("old hit, re-activated", d, h.copy(lastHitAt = now - 2 * window, activeSince = now - day), Health.OK),
        Case("no activation or hit time", d, h.copy(lastHitAt = null, activeSince = null), Health.OK),
        Case(
            "process not seen within window",
            d,
            quiet(3 * window).copy(lastProcessSeenAt = now - window - 1, ruleReportedAt = now - window - 1),
            Health.OK,
            longBoot,
        ),
        Case(
            "process seen exactly at window edge",
            d,
            quiet(3 * window).copy(lastProcessSeenAt = now - window, ruleReportedAt = now - window),
            Health.IDLE,
            longBoot,
        ),
        Case("clock went backwards", d, h.copy(lastHitAt = now + window, activeSince = now + window), Health.OK),
        Case("seen in the future", d, quiet(window).copy(lastProcessSeenAt = now + day), Health.IDLE),
    )

    @Test
    fun `evaluation table`() {
        val all = notInstalledCases + brokenCases + reviewCases + notRunCases + idleCases +
            Case("healthy", d, h, Health.OK)
        val failures = all.mapNotNull { case ->
            val actual = RuleHealth.evaluate(case.kind, case.signals, case.clock ?: clock)
            "${case.name}: expected ${case.expected}, got $actual".takeIf { actual != case.expected }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `worst picks the most urgent state`() {
        assertEquals(Health.NOT_INSTALLED, Health.worst(emptyList()))
        assertEquals(Health.OK, Health.worst(listOf(Health.NOT_INSTALLED, Health.NOT_RUN, Health.OK)))
        assertEquals(Health.IDLE, Health.worst(listOf(Health.OK, Health.IDLE)))
        assertEquals(Health.BROKEN, Health.worst(Health.entries))
    }

    @Test
    fun `evaluateAll covers every package`() {
        val rule = Catalog.rules.first { it.packages.size > 1 }
        val first = rule.packages.first()
        val result = RuleHealth.evaluateAll(rule, clock) { pkg -> if (pkg == first) h else HealthSignals(false) }
        assertEquals(rule.packages, result.keys)
        assertEquals(Health.OK, result.getValue(first))
        assertEquals(Health.NOT_INSTALLED, result.filterKeys { it != first }.values.first())
    }
}
