package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.conflict.DeclaredScope
import io.github.hyperprivacy.core.conflict.OurSide
import io.github.hyperprivacy.core.conflict.OverlapArea
import io.github.hyperprivacy.core.conflict.OverlapTargets
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlapTargetsTest {
    private val adRule = Catalog.rules.first { it.category == Category.ADS && Catalog.SYSTEM_SCOPE !in it.packages }
    private val adApp = adRule.packages.first()
    private val idRule = Catalog.rules.first { it.category == Category.DEVICE_ID && adApp !in it.packages }
    private val systemRule = Catalog.rules.firstOrNull { Catalog.SYSTEM_SCOPE in it.packages }
    private val protectedApp = "com.example.user"
    private val ours = OurSide(
        enabledRules = listOfNotNull(adRule, idRule, systemRule),
        protectedOptions = mapOf(
            protectedApp to mapOf(OverlapArea.ADS to listOf("sdk.ads"), OverlapArea.APP_LIST to listOf("applist.hide")),
            "com.example.other" to mapOf(OverlapArea.ADS to listOf("sdk.other")),
        ),
    )

    @Test
    fun `an unknown scope gives no rule-level detail`() {
        assertNull(OverlapTargets.of(DeclaredScope.Unknown, OverlapArea.ADS, ours))
    }

    @Test
    fun `declared apps meet this module's enabled rules of the same area only`() {
        val targets = OverlapTargets.of(DeclaredScope.Apps(setOf(adApp, protectedApp)), OverlapArea.ADS, ours)!!
        val byApp = targets.associateBy { it.packageName }
        assertTrue(adRule in byApp.getValue(adApp).rules)
        assertTrue(byApp.getValue(adApp).rules.none { it.category != Category.ADS })
        assertEquals(listOf("sdk.ads"), byApp.getValue(protectedApp).options)
        assertTrue("com.example.other" !in byApp, "not in the declared scope")
    }

    @Test
    fun `system_server is matched under either name modules use`() {
        val rule = systemRule ?: return
        val area = OverlapArea.of(rule.category)!!
        listOf("android", "system").forEach { name ->
            val targets = OverlapTargets.of(DeclaredScope.Apps(setOf(name)), area, ours)!!
            assertEquals(listOf(name), targets.map { it.packageName })
        }
    }

    @Test
    fun `an all-apps scope lists the protected apps, not every built-in rule`() {
        val targets = OverlapTargets.of(DeclaredScope.AllApps, OverlapArea.ADS, ours)!!
        assertEquals(listOf("com.example.other", protectedApp), targets.map { it.packageName })
        assertTrue(targets.all { it.rules.isEmpty() })
    }

    @Test
    fun `nothing of this module on there gives an empty list, not null`() {
        val none = OverlapTargets.of(DeclaredScope.Apps(setOf("com.example.none")), OverlapArea.ADS, ours)
        assertEquals(emptyList(), none)
    }

    @Test
    fun `privacy covers analytics, diagnostics and personal data`() {
        listOf(Category.ANALYTICS, Category.DIAGNOSTICS, Category.PERSONAL_DATA).forEach {
            assertEquals(OverlapArea.PRIVACY, OverlapArea.of(it))
        }
        assertEquals(OverlapArea.APP_LIST, OverlapArea.of(Category.APP_LIST))
    }
}
