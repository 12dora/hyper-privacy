package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.scope.DeviceCoverage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceCoverageTest {
    private val single = rule("single", setOf(HOME))
    private val shared = rule("shared", setOf(HOME, CAR, HEALTH))
    private val absent = rule("absent", setOf(CAR, HEALTH))
    private val rules = listOf(single, shared, absent)

    @Test
    fun `rules whose targets are all missing are not applicable`() {
        assertEquals(listOf(single, shared), DeviceCoverage.applicableRules(rules, setOf(HOME)))
        assertEquals(emptyList(), DeviceCoverage.applicableRules(rules, emptySet()))
    }

    @Test
    fun `an app installed later makes its rules applicable`() {
        assertEquals(rules, DeviceCoverage.applicableRules(rules, setOf(HOME, CAR)))
    }

    @Test
    fun `installed targets keep rule order and drop missing apps`() {
        assertEquals(listOf(HOME, HEALTH), DeviceCoverage.installedTargets(shared, setOf(HEALTH, HOME)))
        assertEquals(emptyList(), DeviceCoverage.installedTargets(absent, setOf(HOME)))
    }

    @Test
    fun `category counts only include applicable rules`() {
        val counts = DeviceCoverage.applicableRules(rules, setOf(HOME)).groupingBy { it.category }.eachCount()
        assertEquals(mapOf(Category.ANALYTICS to 2), counts)
    }

    @Test
    fun `missing scope lists installed hooked packages not granted`() {
        val missing = DeviceCoverage.missingScope(
            installed = setOf(HOME, CAR, FIREWALLED),
            scope = listOf(Catalog.SYSTEM_SCOPE, HOME),
            hooked = setOf(HOME, CAR, HEALTH),
        )
        assertEquals(listOf(CAR), missing)
    }

    @Test
    fun `missing system scope comes first`() {
        val missing = DeviceCoverage.missingScope(setOf(HOME, CAR), scope = emptyList(), hooked = setOf(HOME, CAR))
        assertEquals(listOf(Catalog.SYSTEM_SCOPE, CAR, HOME), missing)
    }

    @Test
    fun `legacy framework name satisfies system scope`() {
        assertEquals(emptyList(), DeviceCoverage.missingScope(setOf(HOME), listOf("android", HOME), setOf(HOME)))
    }

    @Test
    fun `nothing is missing when everything is granted`() {
        assertEquals(emptyList(), DeviceCoverage.missingScope(setOf(HOME), Catalog.scope, Catalog.hookedPackages))
    }

    @Test
    fun `real catalog on a device without targets only needs system`() {
        assertEquals(listOf(Catalog.SYSTEM_SCOPE), DeviceCoverage.missingScope(emptySet(), emptyList()))
        assertTrue(DeviceCoverage.applicableRules(Catalog.rules, emptySet()).isEmpty())
    }

    @Test
    fun `firewall-only targets never need scope`() {
        val firewallOnly = Catalog.rules.filter { it.kind == RuleKind.FIREWALL }
            .flatMap { it.packages }
            .filterNot { it in Catalog.hookedPackages }
            .toSet()
        assertTrue(firewallOnly.isNotEmpty())
        assertEquals(emptyList(), DeviceCoverage.missingScope(firewallOnly, listOf(Catalog.SYSTEM_SCOPE)))
    }

    private fun rule(id: String, packages: Set<String>) = Rule(
        id = id,
        packages = packages,
        title = Text("标题", "Title"),
        summary = Text("摘要", "Summary"),
        payload = RulePayload.Domains(listOf("tracking.example.com")),
        category = Category.ANALYTICS,
        evidence = "test",
    )

    private companion object {
        const val HOME = "com.example.home"
        const val CAR = "com.example.car"
        const val HEALTH = "com.example.health"
        const val FIREWALLED = "com.example.firewalled"
    }
}
