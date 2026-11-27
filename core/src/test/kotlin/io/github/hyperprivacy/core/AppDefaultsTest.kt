package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.tracker.AppDefaults
import io.github.hyperprivacy.core.tracker.TrackerCategory
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.TrackerRules
import io.github.hyperprivacy.core.tracker.Trackers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDefaultsTest {
    private val pkg = "com.example.app"
    private val blocking = Trackers.all.first { it.switchable && it.defaultBlock }
    private val optIn = Trackers.all.first { it.switchable && !it.defaultBlock }
    private val detectionOnly = Trackers.all.first { !it.switchable }
    private val detected = listOf(blocking.id, optIn.id, detectionOnly.id, "unknown")
    private val onRule = Catalog.protectedAppRules.first { it.defaultOn }
    private val offRule = Catalog.protectedAppRules.first { !it.defaultOn }

    @Test
    fun `built-in defaults write nothing, so protection behaves as before`() {
        assertTrue(AppDefaults.read(emptyMap<String, Any>()) == AppDefaults.BUILT_IN)
        assertTrue(AppDefaults.BUILT_IN.applyTo(emptyMap<String, Any>(), pkg, detected).isEmpty())
        assertTrue(AppDefaults.BUILT_IN.entries().isEmpty())
        AppDefaults.categories.forEach { assertTrue(AppDefaults.BUILT_IN.isCategoryBlocked(it)) }
        Catalog.protectedAppRules.forEach { assertEquals(it.defaultOn, AppDefaults.BUILT_IN.isRuleOn(it.id)) }
    }

    @Test
    fun `a category turned off turns its detected sdks off`() {
        val defaults = AppDefaults(sdk = mapOf(blocking.category to false))
        val puts = defaults.applyTo(emptyMap<String, Any>(), pkg, detected)
        assertEquals(false, puts[TrackerProtection.blockKey(pkg, blocking.id)])
        assertFalse(TrackerProtection.blockKey(pkg, detectionOnly.id) in puts)
        assertFalse(puts.keys.any { it.endsWith(".unknown") })
    }

    @Test
    fun `a category left on never opts in sdks that may break sign-in or payment`() {
        val defaults = AppDefaults(sdk = AppDefaults.categories.associateWith { true })
        val puts = defaults.applyTo(emptyMap<String, Any>(), pkg, detected)
        assertFalse(TrackerProtection.blockKey(pkg, optIn.id) in puts)
        assertFalse(TrackerProtection.isBlocked(emptyMap<String, Any>(), pkg, optIn))
    }

    @Test
    fun `rules and app-list hiding follow the defaults`() {
        val defaults = AppDefaults(rules = mapOf(onRule.id to false, offRule.id to true), hide = true, strict = true)
        val puts = defaults.applyTo(emptyMap<String, Any>(), pkg, emptyList())
        assertEquals(false, puts[TrackerRules.appRuleKey(pkg, onRule.id)])
        assertEquals(true, puts[TrackerRules.appRuleKey(pkg, offRule.id)])
        assertEquals(true, puts[AppListConfig.hideKey(pkg)])
        assertEquals(true, puts[AppListConfig.strictKey(pkg)])
        // Strict mode without hiding means nothing.
        val strictOnly = AppDefaults(strict = true).applyTo(emptyMap<String, Any>(), pkg, emptyList())
        assertTrue(strictOnly.isEmpty())
    }

    @Test
    fun `choices already made for the app are kept`() {
        val raw = mapOf(
            TrackerProtection.blockKey(pkg, blocking.id) to true,
            TrackerRules.appRuleKey(pkg, onRule.id) to true,
            AppListConfig.hideKey(pkg) to false,
        )
        val defaults = AppDefaults(
            sdk = mapOf(blocking.category to false),
            rules = mapOf(onRule.id to false),
            hide = true,
        )
        assertTrue(defaults.applyTo(raw, pkg, detected).isEmpty())
    }

    @Test
    fun `a legacy global rule switch counts as what the hooks assume`() {
        val raw = mapOf(ConfigCodec.key(onRule.id) to false)
        val puts = AppDefaults(rules = mapOf(onRule.id to false)).applyTo(raw, pkg, emptyList())
        assertTrue(puts.isEmpty())
        // Untouched defaults keep today's behaviour, legacy switch included.
        assertTrue(AppDefaults.BUILT_IN.applyTo(raw, pkg, emptyList()).isEmpty())
        val on = AppDefaults(rules = mapOf(onRule.id to true)).applyTo(raw, pkg, emptyList())
        assertEquals(true, on[TrackerRules.appRuleKey(pkg, onRule.id)])
    }

    @Test
    fun `stored defaults round-trip through their preference entries`() {
        val defaults = AppDefaults(
            sdk = mapOf(TrackerCategory.ANALYTICS to false),
            rules = mapOf(offRule.id to true),
            hide = true,
            strict = true,
        )
        val entries = defaults.entries()
        entries.keys.forEach { assertTrue(AppDefaults.isKey(it), it) }
        assertEquals(defaults, AppDefaults.read(entries))
        val noise = mapOf(
            AppDefaults.SDK_PREFIX + "NOPE" to false,
            AppDefaults.RULE_PREFIX + "gone.rule" to true,
            AppDefaults.HIDE_KEY to "yes",
        )
        noise.keys.filter { it != AppDefaults.HIDE_KEY }.forEach { assertFalse(AppDefaults.isKey(it), it) }
        assertEquals(AppDefaults.BUILT_IN, AppDefaults.read(noise))
    }

    @Test
    fun `defaults keys never collide with keys the hooks read`() {
        val keys = AppDefaults(
            sdk = AppDefaults.categories.associateWith { false },
            rules = Catalog.protectedAppRules.associate { it.id to !it.defaultOn },
            hide = true,
            strict = true,
        ).entries().keys
        keys.forEach { key ->
            assertFalse(TrackerProtection.affects(key), key)
            assertFalse(AppListConfig.affects(key), key)
            assertEquals(null, ConfigCodec.ruleIdOf(key), key)
        }
    }
}
