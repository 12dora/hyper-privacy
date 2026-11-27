package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.match.NetworkMatchers
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix
import io.github.hyperprivacy.core.tracker.TrackerCategory
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
    fun `app-level rules are the catalog's protected-app rules bound to the app`() {
        val appRules = TrackerRules.appRules(pkg)
        assertEquals(Catalog.protectedAppRules.map { it.id }, appRules.map { it.id })
        assertEquals(Catalog.protectedAppRules.map { it.copy(packages = setOf(pkg)) }, appRules)
        assertTrue("httpdns.protected-apps" in TrackerRules.appRuleIds)
        assertTrue("mediadrm.device-id" in TrackerRules.appRuleIds)
    }

    @Test
    fun `known-trackers rule stays unexpanded and costs nothing while off`() {
        val id = "known-trackers.all"
        val rule = Catalog.protectedAppRules.single { it.id == id }
        assertEquals(RulePayload.KnownTrackers, rule.payload)
        assertFalse(rule.defaultOn)
        val rules = TrackerRules.rulesFor(pkg, emptyList())
        assertEquals(1, rules.count { it.id == id })
        val raw = mapOf<String, Any>(TrackerProtection.protectKey(pkg) to "")
        val off = TrackerRules.resolve(raw, pkg, rules)
        assertEquals(false, off[id])
        // No per-SDK switches while off, and the expansion just drops the rule.
        assertEquals(rules.map { it.id }.toSet(), off.keys)
        val expanded = TrackerRules.expandKnown(rules) { off[it] == true }
        assertTrue(expanded.none { it.id == id })
        val withoutKnown = rules.filter { it.id != id }
        assertTrue(TrackerRules.expandKnown(withoutKnown) { true } === withoutKnown)
    }

    @Test
    fun `known-trackers rule blocks default sdks outside the scan, domain and url only`() {
        val id = "known-trackers.all"
        val covered = TrackerRules.knownTrackers
        assertTrue(covered.isNotEmpty())
        assertTrue(covered.all { it.defaultBlock && it.blockable })
        val excluded = setOf(TrackerCategory.IDENTIFICATION, TrackerCategory.PUSH)
        assertTrue(covered.none { sdk -> sdk.categories.any { it in excluded } })
        val rules = TrackerRules.rulesFor(pkg, listOf(blocking.id))
        val raw = mapOf<String, Any>(
            TrackerProtection.protectKey(pkg) to blocking.id,
            TrackerRules.appRuleKey(pkg, id) to true,
        )
        val on = TrackerRules.resolve(raw, pkg, rules)
        assertEquals(true, on[id])
        val expanded = TrackerRules.expandKnown(rules) { on[it] == true }.filter { it.id == id }
        assertEquals(setOf(RuleKind.DOMAIN, RuleKind.URL), expanded.map { it.kind }.toSet())
        val hosts = expanded.flatMap { (it.payload as? RulePayload.Domains)?.suffixes.orEmpty() }.toSet()
        // Detected SDKs are left to their own rules.
        assertEquals(covered.filter { it.id != blocking.id }.flatMap { it.blockHosts }.toSet(), hosts)
        val optOut = Trackers.all.filter { !it.defaultBlock || TrackerCategory.IDENTIFICATION in it.categories }
            .flatMap { it.blockHosts }.toSet() - hosts
        assertTrue(hosts.none { it in optOut })
        assertTrue(TrackerRules.honorsKeepHosts(id) && !TrackerRules.honorsKeepHosts("httpdns.protected-apps"))
        val all = TrackerRules.expandKnown(rules) { on[it] == true }
        val matchers = NetworkMatchers.of(all, Trackers.keepHosts, TrackerRules::honorsKeepHosts) { on[it] == true }
        assertEquals(id, matchers.findHost(hosts.first()))
        Trackers.keepHosts.forEach { keep -> assertTrue(matchers.findHost(keep) != id, keep) }
    }

    @Test
    fun `known-trackers rule never blocks an sdk switched off for the app`() {
        val id = "known-trackers.all"
        // Two covered SDKs that share a block host.
        val known = TrackerRules.knownTrackers
        val (first, second) = known.flatMap { a ->
            known.filter { b -> b.id > a.id && b.blockHosts.any { it in a.blockHosts } }.map { a to it }
        }.first()
        val shared = first.blockHosts.first { it in second.blockHosts }
        val base = mapOf<String, Any>(TrackerRules.appRuleKey(pkg, id) to true)

        fun hostsUnder(raw: Map<String, Any>, detected: List<String>): Set<String> {
            val rules = TrackerRules.rulesFor(pkg, detected)
            val withScan = raw + (TrackerProtection.protectKey(pkg) to TrackerProtection.encodeDetected(detected))
            val on = TrackerRules.resolve(withScan, pkg, rules)
            return TrackerRules.expandKnown(rules) { on[it] == true }.filter { it.id == id }
                .flatMap { (it.payload as? RulePayload.Domains)?.suffixes.orEmpty() }.toSet()
        }
        assertTrue(shared in hostsUnder(base, emptyList()))
        // Undetected but switched off for the app (e.g. found by an older scan): neither it nor shared hosts.
        val offFirst = base + (TrackerProtection.blockKey(pkg, first.id) to false)
        val undetectedOff = hostsUnder(offFirst, emptyList())
        assertTrue(shared !in undetectedOff && undetectedOff.none { it in first.blockHosts })
        // Detected and switched off: its own rule is off, and the sibling's copy of the shared host is dropped.
        val detectedOff = hostsUnder(offFirst, listOf(first.id))
        assertTrue(shared !in detectedOff && detectedOff.none { it in first.blockHosts })
        // The sibling's other hosts stay blocked.
        assertTrue(second.blockHosts.filter { it !in first.blockHosts }.all { it in detectedOff })
    }

    @Test
    fun `switches follow protection, defaults and overrides`() {
        val rules = TrackerRules.rulesFor(pkg, listOf(blocking.id, optIn.id))
        val blockRule = TrackerProtection.ruleId(blocking.id)
        val optRule = TrackerProtection.ruleId(optIn.id)
        val wide = "mediadrm.device-id"
        val dns = "httpdns.protected-apps"
        val off = TrackerRules.resolve(emptyMap<String, Any>(), pkg, rules)
        assertTrue(off.values.none { it }, "nothing applies while protection is off")

        val on = mapOf(
            TrackerProtection.protectKey(pkg) to TrackerProtection.encodeDetected(listOf(blocking.id, optIn.id)),
        )
        val defaults = TrackerRules.resolve(on, pkg, rules)
        assertEquals(true, defaults[blockRule])
        assertEquals(false, defaults[optRule], "opt-in SDKs stay allowed by default")
        assertEquals(true, defaults[wide])
        assertEquals(true, defaults[dns])
        // The global catalog switch applies unless the app has its own.
        assertEquals(false, TrackerRules.resolve(on + (ConfigCodec.key(dns) to false), pkg, rules)[dns])

        val overridden = on + mapOf(
            TrackerProtection.blockKey(pkg, blocking.id) to false,
            TrackerProtection.blockKey(pkg, optIn.id) to true,
            TrackerRules.appRuleKey(pkg, wide) to false,
            ConfigCodec.key(dns) to false,
            TrackerRules.appRuleKey(pkg, dns) to true,
        )
        val switched = TrackerRules.resolve(overridden, pkg, rules)
        assertEquals(false, switched[blockRule])
        assertEquals(true, switched[optRule])
        assertEquals(false, switched[wide])
        assertEquals(true, switched[dns], "the app's own switch wins over the global one")
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
    fun `keep hosts never weaken app-level rules`() {
        val rules = listOf(
            rule("tracker.${blocking.id}", RulePayload.Domains(listOf("amap.com"))),
            rule("app", RulePayload.Domains(listOf("httpdns.apilocate.amap.com"))),
        )
        val keep = listOf("httpdns.apilocate.amap.com", "apilocate.amap.com")
        val matchers = NetworkMatchers.of(rules, keep, { it.startsWith("tracker.") }) { true }
        assertEquals("app", matchers.findHost("httpdns.apilocate.amap.com"))
        assertEquals("app", matchers.findUrl("httpdns.apilocate.amap.com", "/d"))
        assertNull(matchers.findHost("a.apilocate.amap.com"), "still kept from the SDK rule")
        assertEquals("tracker.${blocking.id}", matchers.findHost("log.amap.com"))
    }

    @Test
    fun `keep hosts alone build no host matcher`() {
        val urlOnly = listOf(rule("u", RulePayload.Urls(listOf(UrlPrefix("feature.example.com", "/stat")))))
        val matchers = NetworkMatchers.of(urlOnly, listOf("feature.example.com", "push.example.com")) { true }
        assertTrue(matchers.hosts.isEmpty)
        assertFalse(matchers.blocksConnects)
        assertEquals("u", matchers.findUrl("feature.example.com", "/stat"))
    }

    @Test
    fun `affected keys`() {
        assertTrue(TrackerProtection.affects(TrackerProtection.protectKey(pkg)))
        assertTrue(TrackerProtection.affects(TrackerProtection.blockKey(pkg, blocking.id)))
        assertTrue(TrackerProtection.affects(null))
        assertFalse(TrackerProtection.affects(ConfigCodec.key("x")))
    }

    @Test
    fun `a protected app only reacts to its own keys`() {
        val other = "com.example.other"
        assertTrue(TrackerProtection.affects(TrackerProtection.protectKey(pkg), pkg))
        assertTrue(TrackerProtection.affects(TrackerProtection.blockKey(pkg, blocking.id), pkg))
        assertTrue(TrackerProtection.affects(TrackerRules.appRuleKey(pkg, "mediadrm.device-id"), pkg))
        assertTrue(TrackerProtection.affects(ConfigCodec.key("httpdns.protected-apps"), pkg))
        assertTrue(TrackerProtection.affects(null, pkg))
        assertFalse(TrackerProtection.affects(TrackerProtection.protectKey(other), pkg))
        assertFalse(TrackerProtection.affects(TrackerProtection.blockKey(other, blocking.id), pkg))
        assertFalse(TrackerProtection.affects(TrackerProtection.protectKey("$pkg.sub"), pkg))
        assertFalse(TrackerProtection.affects(ConfigCodec.key("httpdns.resolvers"), pkg))
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
