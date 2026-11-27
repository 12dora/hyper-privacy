package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.conflict.ModuleOverlap
import io.github.hyperprivacy.core.conflict.OverlapArea
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleOverlapTest {
    private fun areas(description: String?, pkg: String = "com.example.mod", label: String? = null) =
        ModuleOverlap.areasOf(pkg, label, description)

    @Test
    fun `known packages overlap whatever their text says`() {
        assertEquals(setOf(OverlapArea.APP_LIST), areas(null, pkg = "com.tsng.hidemyapplist"))
        assertTrue(ModuleOverlap.KNOWN.keys.all { areas("", pkg = it).isNotEmpty() })
    }

    @Test
    fun `keywords find app list hiding in both languages`() {
        assertEquals(setOf(OverlapArea.APP_LIST), areas("Hide installed apps from other apps"))
        assertEquals(setOf(OverlapArea.APP_LIST), areas("隐藏应用，防止被检测"))
        assertTrue(OverlapArea.APP_LIST in areas("Custom app list filter"))
    }

    @Test
    fun `keywords find ad blocking and privacy`() {
        assertTrue(OverlapArea.ADS in areas("Removes ads in system apps"))
        assertTrue(OverlapArea.ADS in areas("屏蔽广告"))
        assertTrue(OverlapArea.ADS in areas("AdBlock for apps"))
        assertTrue(OverlapArea.PRIVACY in areas("Stops tracking and telemetry"))
        assertTrue(OverlapArea.PRIVACY in areas("保护隐私"))
    }

    @Test
    fun `keywords find device identifier rewriting`() {
        assertTrue(OverlapArea.DEVICE_ID in areas("Fake Android ID and IMEI per app"))
        assertTrue(OverlapArea.DEVICE_ID in areas("随机设备标识"))
        assertEquals(emptySet(), areas("Improves device idle battery life"))
    }

    @Test
    fun `each area links to its own rule category`() {
        assertEquals(OverlapArea.entries.size, OverlapArea.entries.map { it.category }.toSet().size)
    }

    @Test
    fun `unrelated modules and look-alike words do not match`() {
        assertEquals(emptySet(), areas("Adds a dark theme and improves the status bar clock"))
        assertEquals(emptySet(), areas("Load more downloads, heads-up tweaks, padding fixes"))
        assertEquals(emptySet(), areas(null))
    }

    @Test
    fun `the label counts too`() {
        assertTrue(OverlapArea.APP_LIST in areas(null, label = "Hide My Applist"))
    }
}
