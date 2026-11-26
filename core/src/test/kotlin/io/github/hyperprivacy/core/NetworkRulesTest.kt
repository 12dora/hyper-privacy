package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.match.NetworkMatchers
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix
import io.github.hyperprivacy.core.scope.PackageRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkRulesTest {
    private val domain = rule("d", RulePayload.Domains(listOf("tracking.example.com")), setOf(A, B))
    private val url = rule("u", RulePayload.Urls(listOf(UrlPrefix("api.example.com", "/log/"))), setOf(A))
    private val firewall = rule("f", RulePayload.Firewall, setOf(A))
    private val identifier = rule("i", RulePayload.Identifiers(IdentifierSource.MIUI_ID_PROVIDER), setOf(B))
    private val rules = listOf(domain, url, firewall, identifier)

    @Test
    fun `domain rules apply to host sinks and url sinks, url rules only to url sinks`() {
        val matchers = NetworkMatchers.of(rules) { true }
        assertEquals("d", matchers.hosts.find("x.tracking.example.com"))
        assertNull(matchers.hosts.find("api.example.com"))
        assertEquals("d", matchers.urls.find("https://tracking.example.com/anything"))
        assertEquals("u", matchers.urls.find("https://api.example.com/log/1"))
        assertNull(matchers.urls.find("https://api.example.com/store"))
    }

    @Test
    fun `disabled rules are left out`() {
        val matchers = NetworkMatchers.of(rules) { it == "u" }
        assertNull(matchers.hosts.find("tracking.example.com"))
        assertNull(matchers.urls.find("https://tracking.example.com/"))
        assertEquals("u", matchers.urls.find("https://api.example.com/log/1"))
        assertSame(NetworkMatchers.EMPTY, NetworkMatchers.of(rules) { false })
    }

    @Test
    fun `package index excludes firewall rules and keeps order`() {
        val index = PackageRules(rules)
        assertEquals(listOf(domain, url), index.inProcess(A))
        assertEquals(listOf(domain, identifier), index.inProcess(B))
        assertTrue(index.inProcess("com.example.none").isEmpty())
    }

    @Test
    fun `catalog index agrees with the catalog`() {
        Catalog.allPackages.forEach { pkg ->
            assertEquals(
                Catalog.rulesFor(pkg).filter { it.kind != RuleKind.FIREWALL },
                PackageRules.catalog.inProcess(pkg),
            )
        }
    }

    @Test
    fun `observe switch defaults off and only accepts booleans`() {
        assertFalse(ConfigCodec.isObserveEnabled(emptyMap<String, Any>()))
        assertTrue(ConfigCodec.isObserveEnabled(mapOf(ConfigCodec.OBSERVE_KEY to true)))
        assertFalse(ConfigCodec.isObserveEnabled(mapOf(ConfigCodec.OBSERVE_KEY to "true")))
        assertTrue(ConfigCodec.affectsHooks(ConfigCodec.OBSERVE_KEY))
        assertTrue(ConfigCodec.affectsHooks(ConfigCodec.key("x")))
        assertTrue(ConfigCodec.affectsHooks(null))
        assertFalse(ConfigCodec.affectsHooks("other"))
    }

    private fun rule(id: String, payload: RulePayload, packages: Set<String>) = Rule(
        id = id,
        packages = packages,
        title = Text(id, id),
        summary = Text(id, id),
        payload = payload,
        category = Category.ANALYTICS,
        evidence = "test",
    )

    private companion object {
        const val A = "com.example.a"
        const val B = "com.example.b"
    }
}
