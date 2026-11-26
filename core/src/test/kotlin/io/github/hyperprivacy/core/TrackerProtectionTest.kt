package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.match.NetworkMatchers
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.core.tracker.TrackerRules
import io.github.hyperprivacy.core.tracker.Trackers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerProtectionTest {
    private val pkg = "com.example.app"

    private val blocking = Trackers.all.first { it.defaultBlock && it.blockHosts.isNotEmpty() }
    private val optIn = Trackers.all.first { !it.defaultBlock && it.blockable }
    private val detectionOnly = Trackers.all.first { !it.blockable }

    @Test
    fun `detected ids round-trip and absence means off`() {
        val raw = mapOf(TrackerProtection.protectKey(pkg) to TrackerProtection.encodeDetected(listOf("b", "a")))
        assertEquals(listOf("a", "b"), TrackerProtection.detected(raw, pkg))
        assertNull(TrackerProtection.detected(emptyMap<String, Any>(), pkg))
        assertEquals(emptyList(), TrackerProtection.detected(mapOf(TrackerProtection.protectKey(pkg) to ""), pkg))
        assertEquals(setOf(pkg), TrackerProtection.protectedPackages(raw + ("trackers." to "")))
    }

    @Test
    fun `rules cover blockable detected sdks plus app-wide rules`() {
        val rules = TrackerRules.rulesFor(pkg, listOf(blocking.id, optIn.id, detectionOnly.id, "unknown"))
        val ids = rules.map { it.id }.toSet()
        assertTrue(TrackerProtection.ruleId(blocking.id) in ids)
        assertTrue(TrackerProtection.ruleId(optIn.id) in ids)
        assertFalse(TrackerProtection.ruleId(detectionOnly.id) in ids)
        assertEquals(TrackerRules.appRuleIds, rules.map { it.id }.filter { it in TrackerRules.appRuleIds }.toSet())
        assertTrue(rules.all { TrackerProtection.sdkIdOf(it.id) != null || it.id in TrackerRules.appRuleIds })
        assertTrue(rules.all { it.packages == setOf(pkg) })
    }

    @Test
    fun `switches follow protection, defaults and overrides`() {
        val rules = TrackerRules.rulesFor(pkg, listOf(blocking.id, optIn.id))
        val blockRule = TrackerProtection.ruleId(blocking.id)
        val optRule = TrackerProtection.ruleId(optIn.id)
        val wide = TrackerRules.DRM_ID_RULE
        val off = TrackerRules.resolve(emptyMap<String, Any>(), pkg, rules)
        assertTrue(off.values.none { it }, "nothing applies while protection is off")

        val on = mapOf(
            TrackerProtection.protectKey(pkg) to TrackerProtection.encodeDetected(listOf(blocking.id, optIn.id)),
        )
        val defaults = TrackerRules.resolve(on, pkg, rules)
        assertEquals(true, defaults[blockRule])
        assertEquals(false, defaults[optRule], "opt-in SDKs stay allowed by default")
        assertEquals(true, defaults[wide])

        val overridden = on + mapOf(
            TrackerProtection.blockKey(pkg, blocking.id) to false,
            TrackerProtection.blockKey(pkg, optIn.id) to true,
            TrackerProtection.blockKey(pkg, "app.drm-id") to false,
        )
        val switched = TrackerRules.resolve(overridden, pkg, rules)
        assertEquals(false, switched[blockRule])
        assertEquals(true, switched[optRule])
        assertEquals(false, switched[wide])
        // An SDK no longer in the stored scan result is not enforced.
        val rescanned = overridden + (TrackerProtection.protectKey(pkg) to blocking.id)
        assertEquals(false, TrackerRules.resolve(rescanned, pkg, rules)[optRule])
    }

    @Test
    fun `keep hosts win over broader blocks but not over paths`() {
        val rules = listOf(
            rule("d", RulePayload.Domains(listOf("example.com"))),
            rule("u", RulePayload.Urls(listOf(UrlPrefix("feature.example.com", "/stat")))),
        )
        val matchers = NetworkMatchers.of(rules, listOf("feature.example.com")) { true }
        assertNull(matchers.findHost("feature.example.com"))
        assertNull(matchers.findHost("a.feature.example.com"))
        assertEquals("d", matchers.findHost("log.example.com"))
        assertNull(matchers.findUrl("feature.example.com", "/api"))
        assertEquals("u", matchers.findUrl("feature.example.com", "/stat/1"))
        assertEquals("d", matchers.findUrl("log.example.com", "/"))
        assertTrue(NetworkMatchers.of(rules, listOf("feature.example.com")) { false } === NetworkMatchers.EMPTY)
    }

    @Test
    fun `affected keys`() {
        assertTrue(TrackerProtection.affects(TrackerProtection.protectKey(pkg)))
        assertTrue(TrackerProtection.affects(TrackerProtection.blockKey(pkg, blocking.id)))
        assertTrue(TrackerProtection.affects(null))
        assertFalse(TrackerProtection.affects(ConfigCodec.key("x")))
    }

    private fun rule(id: String, payload: RulePayload) = Rule(
        id = id,
        packages = setOf(pkg),
        title = Text("t", "t"),
        summary = Text("s", "s"),
        payload = payload,
        category = Category.ANALYTICS,
        evidence = "test",
    )
}
