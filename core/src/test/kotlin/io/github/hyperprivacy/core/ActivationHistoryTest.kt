package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.health.ActivationHistory
import io.github.hyperprivacy.core.rule.Baseline
import io.github.hyperprivacy.core.rule.DeviceBuild
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.HookState.ACTIVE
import io.github.hyperprivacy.core.status.HookState.MISSED
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivationHistoryTest {
    private val never = ActivationHistory.NEVER
    private val seen = ActivationHistory(firstActiveAt = 10, lastActiveVersion = 100)

    private data class Step(
        val name: String,
        val before: ActivationHistory,
        val state: HookState,
        val version: Long?,
        val expected: ActivationHistory,
    )

    @Test
    fun `record table`() {
        val steps = listOf(
            Step("first activation stamps time and version", never, ACTIVE, 100, ActivationHistory(50, 100)),
            Step("first activation, version unknown", never, ACTIVE, null, ActivationHistory(50, null)),
            Step("missed while never active stays never", never, MISSED, 100, never),
            Step("missed after activation keeps history", seen, MISSED, 200, seen),
            Step("active after update moves the version", seen, ACTIVE, 200, ActivationHistory(10, 200)),
            Step("active again keeps the first time", seen, ACTIVE, 100, seen),
            Step("unknown version keeps the last known", seen, ACTIVE, null, seen),
        )
        val failures = steps.mapNotNull { step ->
            val actual = step.before.record(step.state, at = 50, versionCode = step.version)
            "${step.name}: expected ${step.expected}, got $actual".takeIf { actual != step.expected }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `legacy rows`() {
        assertEquals(ActivationHistory(7), ActivationHistory.fromLegacy(ACTIVE, activeSince = 7, reportedAt = 9))
        assertEquals(ActivationHistory(9), ActivationHistory.fromLegacy(ACTIVE, activeSince = null, reportedAt = 9))
        assertEquals(never, ActivationHistory.fromLegacy(MISSED, activeSince = null, reportedAt = 9))
        assertFalse(never.everActive)
        assertTrue(seen.everActive)
    }

    @Test
    fun `baseline applies only on the surveyed device and build`() {
        val rom = Baseline.device.incremental
        val cases = listOf(
            DeviceBuild(model = "X", device = "nezha", incremental = rom) to true,
            DeviceBuild(model = "NEZHA", device = "other", incremental = rom) to true,
            DeviceBuild(model = "X", device = "nezha", incremental = "OS4.0.6.0.XPACNXM") to false,
            DeviceBuild(model = "X", device = "aurora", incremental = rom) to false,
            DeviceBuild(model = "", device = "", incremental = "") to false,
        )
        assertEquals(cases.map { it.second }, cases.map { Baseline.isSurveyed(it.first) })
    }
}
