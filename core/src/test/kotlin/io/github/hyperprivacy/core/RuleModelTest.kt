package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.MethodPattern
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleModelTest {
    @Test
    fun `prefix pattern matches names starting with stem`() {
        val p = MethodPattern.parse("track*")
        assertTrue(p.isPrefix)
        assertTrue(p.matches("track"))
        assertTrue(p.matches("trackEvent"))
        assertFalse(p.matches("Track"))
        assertFalse(p.matches("untrack"))
        assertEquals("track*", p.toString())
    }

    @Test
    fun `exact pattern matches only that name`() {
        val p = MethodPattern.parse("track")
        assertTrue(p.matches("track"))
        assertFalse(p.matches("trackEvent"))
        assertEquals(p, MethodPattern.parse("track"))
        assertEquals(p.hashCode(), MethodPattern.parse("track").hashCode())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "*", "tr*ck", "*track", "track**", "1track", "a.b"])
    fun `invalid patterns are rejected`(text: String) {
        assertFailsWith<IllegalArgumentException> { MethodPattern.parse(text) }
    }

    @Test
    fun `kind derives from payload`() {
        assertEquals(RuleKind.FIREWALL, rule(payload = RulePayload.Firewall).kind)
        assertEquals(RuleKind.DOMAIN, rule(payload = RulePayload.Domains(listOf("a.com"))).kind)
        assertEquals(RuleKind.METHOD, rule(payload = RulePayload.Method("a.B", MethodPattern.parse("track"))).kind)
    }

    @Test
    fun `invalid rules are rejected`() {
        assertFailsWith<IllegalArgumentException> { rule(id = "Bad Id") }
        assertFailsWith<IllegalArgumentException> { rule(packages = emptySet()) }
        assertFailsWith<IllegalArgumentException> { rule(packages = setOf("nodots")) }
        assertFailsWith<IllegalArgumentException> { rule(evidence = " ") }
        assertFailsWith<IllegalArgumentException> { RulePayload.Domains(emptyList()) }
        assertFailsWith<IllegalArgumentException> { RulePayload.Method(" ", MethodPattern.parse("a")) }
    }

    @Test
    fun `impact on a default-on rule requires highRisk`() {
        val impact = Text("i", "i")
        assertFailsWith<IllegalArgumentException> { rule(impact = impact, defaultOn = true) }
        assertEquals(impact, rule(impact = impact, defaultOn = false).impact)
        assertTrue(rule(impact = impact, defaultOn = true, highRisk = true).highRisk)
    }

    @Test
    fun `highRisk must ship enabled and describe its impact`() {
        val impact = Text("i", "i")
        assertFailsWith<IllegalArgumentException> { rule(impact = impact, defaultOn = false, highRisk = true) }
        assertFailsWith<IllegalArgumentException> { rule(defaultOn = true, highRisk = true) }
        assertFailsWith<IllegalArgumentException> { rule(defaultOn = false, highRisk = true) }
        val error = assertFailsWith<IllegalArgumentException> { rule(id = "x.y", highRisk = true) }
        assertEquals("Rule x.y is highRisk, so it must ship enabled and describe its impact", error.message)
    }

    @Test
    fun `invariant message names the rule`() {
        val error = assertFailsWith<IllegalArgumentException> { rule(id = "x.y", impact = Text("i", "i")) }
        assertEquals("Rule x.y affects a service and must ship disabled unless highRisk", error.message)
    }

    @Test
    fun `highRisk defaults to false`() {
        assertFalse(rule().highRisk)
    }

    private fun rule(
        id: String = "a.b",
        packages: Set<String> = setOf("com.example"),
        payload: RulePayload = RulePayload.Firewall,
        evidence: String = "test",
        impact: Text? = null,
        defaultOn: Boolean = true,
        highRisk: Boolean = false,
    ) = Rule(
        id, packages, Text("t", "t"), Text("s", "s"), payload, Category.ANALYTICS, evidence,
        defaultOn = defaultOn, impact = impact, highRisk = highRisk,
    )
}
