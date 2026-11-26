package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.match.HostMatcher
import io.github.hyperprivacy.core.match.HostNames
import io.github.hyperprivacy.core.match.UrlMatcher
import io.github.hyperprivacy.core.match.UrlPattern
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.rule.RulePayload
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
    @Test
    fun `rule ids are unique`() {
        val ids = Catalog.rules.map { it.id }
        assertEquals(
            ids.size,
            ids.toSet().size,
            "Duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}",
        )
    }

    @Test
    fun `every rule cites evidence`() {
        val offenders = Catalog.rules.filter { it.evidence.isBlank() }
        assertTrue(offenders.isEmpty(), "Rules without evidence: ${offenders.map { it.id }}")
    }

    @Test
    fun `domain rules never block a vendor apex`() {
        val offenders = Catalog.rules.flatMap { rule ->
            (rule.payload as? RulePayload.Domains)?.suffixes.orEmpty()
                .filter { HostNames.normalize(it) in OVERLY_BROAD_SUFFIXES }
                .map { "${rule.id}:$it" }
        }
        assertTrue(offenders.isEmpty(), "Suffix would hit vendor services: $offenders")
    }

    @Test
    fun `firewall rules never target known shared-uid packages`() {
        val offenders = Catalog.rules
            .filter { it.kind == RuleKind.FIREWALL }
            .flatMap { rule -> rule.packages.filter { it in KNOWN_SHARED_UID_PACKAGES }.map { "${rule.id}:$it" } }
        assertTrue(offenders.isEmpty(), "FIREWALL target shares a uid: $offenders")
    }

    @Test
    fun `domain patterns are valid host names`() {
        Catalog.rules.map { it.payload }.filterIsInstance<RulePayload.Domains>().forEach { payload ->
            HostMatcher.of(payload.suffixes.map { it to Unit })
        }
    }

    @Test
    fun `every rule has user-facing copy`() {
        assertTrue(Catalog.rules.all { it.title.zh.isNotBlank() && it.summary.en.isNotBlank() })
    }

    @Test
    fun `scope is hooked packages plus system and excludes firewall-only targets`() {
        val expected = Catalog.rules
            .filter { it.kind != RuleKind.FIREWALL }
            .flatMap { it.packages }
            .toSet() + Catalog.SYSTEM_SCOPE
        assertEquals(expected, Catalog.scope)
        assertTrue("com.miui.analytics" !in Catalog.scope)
    }

    @Test
    fun `url rules never target a vendor apex host`() {
        val offenders = Catalog.rules.flatMap { rule ->
            (rule.payload as? RulePayload.Urls)?.prefixes.orEmpty()
                .filter { HostNames.normalize(it.host) in OVERLY_BROAD_SUFFIXES }
                .map { "${rule.id}:${it.host}${it.pathPrefix}" }
        }
        assertTrue(offenders.isEmpty(), "URL host would hit vendor services: $offenders")
    }

    @Test
    fun `rules with impact that default on are high risk`() {
        val offenders = Catalog.rules.filter { it.impact != null && it.defaultOn && !it.highRisk }
        assertTrue(offenders.isEmpty(), "Impact rules must default off unless highRisk: ${offenders.map { it.id }}")
    }

    @Test
    fun `high risk rules ship enabled`() {
        val offenders = Catalog.rules.filter { it.highRisk && !it.defaultOn }
        assertTrue(offenders.isEmpty(), "highRisk rules must default on: ${offenders.map { it.id }}")
    }

    @Test
    fun `high risk rules describe their impact`() {
        val offenders = Catalog.rules.filter { it.highRisk && it.impact == null }
        assertTrue(offenders.isEmpty(), "highRisk rules need an impact: ${offenders.map { it.id }}")
    }

    @Test
    fun `firewall-only targets are never injected`() {
        val hooked = Catalog.rules.filter { it.kind != RuleKind.FIREWALL }.flatMap { it.packages }.toSet()
        val firewallOnly = Catalog.rules.filter { it.kind == RuleKind.FIREWALL }
            .flatMap { it.packages }
            .toSet() - hooked
        assertTrue(firewallOnly.isNotEmpty())
        assertTrue(firewallOnly.none { it in Catalog.scope }, "Firewall-only targets leaked into scope")
        assertTrue(firewallOnly.all { it in Catalog.allPackages })
    }

    @Test
    fun `rules are grouped by category in a stable order`() {
        val sequence = Catalog.rules.map { it.category }
        val blocks = sequence.zipWithNext().count { (a, b) -> a != b } + 1
        assertEquals(Category.entries.toSet(), sequence.toSet(), "Every category must have rules")
        assertEquals(Category.entries.size, blocks, "Categories must not interleave")
    }

    @Test
    fun `url prefixes are unique within a rule`() {
        val offenders = Catalog.rules.filter { rule ->
            val prefixes = (rule.payload as? RulePayload.Urls)?.prefixes.orEmpty()
            prefixes.size != prefixes.toSet().size
        }
        assertTrue(offenders.isEmpty(), "Duplicate url prefixes: ${offenders.map { it.id }}")
    }

    @Test
    fun `domain suffixes are unique within a rule`() {
        val offenders = Catalog.rules.filter { rule ->
            val suffixes = (rule.payload as? RulePayload.Domains)?.suffixes.orEmpty()
            suffixes.size != suffixes.map(HostNames::normalize).toSet().size
        }
        assertTrue(offenders.isEmpty(), "Duplicate domain suffixes: ${offenders.map { it.id }}")
    }

    @Test
    fun `rulesFor and byId agree with rules`() {
        Catalog.rules.forEach { rule ->
            assertEquals(rule, Catalog.byId(rule.id))
            rule.packages.forEach { assertTrue(rule in Catalog.rulesFor(it)) }
        }
    }

    @Test
    fun `activation receipt prefix leaves SIM binding paths open`() {
        val prefixes = (Catalog.byId("simactivate.activation-receipt")!!.payload as RulePayload.Urls).prefixes
        val matcher = UrlMatcher.of(prefixes.map { UrlPattern(it.host, it.pathPrefix) to Unit })
        assertEquals(Unit, matcher.find("https://ac.account.xiaomi.com/pass/activation/report"))
        assertNull(matcher.find("https://ac.account.xiaomi.com/pass/activation/activationReport"))
        assertNull(matcher.find("https://ac.account.xiaomi.com/pass/activation/activate"))
    }

    @Test
    fun `scan result prefix leaves cloud scan on the same host open`() {
        val prefixes = (Catalog.byId("guard.scan-result-report")!!.payload as RulePayload.Urls).prefixes
        val matcher = UrlMatcher.of(prefixes.map { UrlPattern(it.host, it.pathPrefix) to Unit })
        assertEquals(Unit, matcher.find("https://virus-engine.sec.miui.com/virus/localResult/report"))
        assertNull(matcher.find("https://virus-engine.sec.miui.com/risk/scan"))
    }

    private companion object {
        /**
         * Apexes and shared service zones that also serve store, push, sync, find-device and update
         * traffic. Rules must name a specific telemetry host or subdomain beneath them instead.
         */
        val OVERLY_BROAD_SUFFIXES = setOf(
            "miui.com",
            "xiaomi.com",
            "mi.com",
            "xiaomi.net",
            "mi-img.com",
            "micloud.xiaomi.net",
            "api.miui.com",
            "api.xiaomi.com",
            "com",
            "net",
            "cn",
        )

        /**
         * Packages observed sharing `android.uid.system` (uid 1000) on HyperOS. Firewalling any of
         * them would cut system_server. The runtime guard is authoritative; this catches mistakes early.
         */
        val KNOWN_SHARED_UID_PACKAGES = setOf(
            "android",
            "com.miui.securitycenter",
            "com.miui.misightservice",
            "com.xiaomi.trustservice",
            "com.miui.powerkeeper",
            "com.xiaomi.joyose",
            "com.miui.uireporter",
        )
    }
}
