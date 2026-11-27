package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.catalog.HTTPDNS_BROWSER_APPS
import io.github.hyperprivacy.core.rule.catalog.HTTPDNS_EXCLUDED_APPS
import io.github.hyperprivacy.core.rule.catalog.HttpDnsResolvers
import io.github.hyperprivacy.core.scope.PackageRules
import io.github.hyperprivacy.core.tracker.TrackerRules
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppWideRulesTest {
    private val injected = Catalog.rules.filter { it.kind != RuleKind.FIREWALL && !it.preventive }
        .flatMap { it.packages }
        .toSet() - Catalog.SYSTEM_SCOPE

    @Test
    fun `httpdns rule targets injected packages minus the sensitive set`() {
        val rule = assertNotNull(Catalog.byId(HTTPDNS))
        assertEquals(injected - HTTPDNS_EXCLUDED_APPS - HTTPDNS_BROWSER_APPS, rule.packages)
        assertTrue(rule.defaultOn && rule.impact == null && rule.preventive)
        assertTrue(Catalog.SYSTEM_SCOPE !in rule.packages, "never system_server")
        assertEquals(listOf(HTTPDNS, BROWSER_HTTPDNS), Catalog.rules.filter { it.preventive }.map { it.id })
    }

    @Test
    fun `vendor browsers block sdk httpdns but keep public doh`() {
        val rule = assertNotNull(Catalog.byId(BROWSER_HTTPDNS))
        assertEquals(HTTPDNS_BROWSER_APPS, rule.packages)
        assertEquals(emptySet(), HTTPDNS_BROWSER_APPS - injected, "every browser is injected anyway")
        assertTrue(rule.defaultOn && rule.impact == null && rule.preventive)
        assertEquals(HttpDnsResolvers.sdkHosts + HttpDnsResolvers.sdkAddresses, rule.domains)
        val vendor = assertNotNull(Catalog.byId(HTTPDNS))
        assertEquals(HttpDnsResolvers.hosts + HttpDnsResolvers.addresses, vendor.domains)
        assertTrue(HTTPDNS_BROWSER_APPS.none { it in vendor.packages })
        assertTrue("resolve.umeng.com" in rule.domains)
    }

    @Test
    fun `sdk httpdns and public doh lists are disjoint and complete`() {
        val sdk = HttpDnsResolvers.sdkHosts + HttpDnsResolvers.sdkAddresses
        val doh = HttpDnsResolvers.publicDohHosts + HttpDnsResolvers.publicDohAddresses
        assertEquals(emptySet(), sdk.toSet() intersect doh.toSet())
        assertEquals((HttpDnsResolvers.hosts + HttpDnsResolvers.addresses).toSet(), (sdk + doh).toSet())
        listOf("dns.google", "dns.alidns.com", "doh.pub", "8.8.8.8", "223.5.5.5", "2400:3200::1").forEach {
            assertTrue(it in doh, it)
        }
    }

    @Test
    fun `protected browsers and vpn apps keep public doh under the same rule id`() {
        val plain = TrackerRules.appRules(PKG).single { it.id == PROTECTED_HTTPDNS }
        val userDns = TrackerRules.appRules(PKG, userDns = true)
        val dns = userDns.single { it.id == PROTECTED_HTTPDNS }
        assertEquals(HttpDnsResolvers.hosts + HttpDnsResolvers.addresses, plain.domains)
        assertEquals(HttpDnsResolvers.sdkHosts + HttpDnsResolvers.sdkAddresses, dns.domains)
        assertEquals(setOf(PKG), dns.packages)
        assertEquals(plain.copy(payload = dns.payload), dns, "only the resolver list differs")
        assertEquals(TrackerRules.appRules(PKG).map { it.id }, userDns.map { it.id })
        assertEquals(
            TrackerRules.appRules(PKG).filter { it.id != PROTECTED_HTTPDNS },
            userDns.filter { it.id != PROTECTED_HTTPDNS },
        )
    }

    @Test
    fun `httpdns exclusions are exactly the account, payment, security, cloud and push services`() {
        assertEquals(
            setOf(
                "com.android.phone",
                "com.android.provision",
                "com.android.settings",
                "com.android.updater",
                "com.fido.asm",
                "com.lbe.security.miui",
                "com.miui.cloudbackup",
                "com.miui.cloudservice",
                "com.miui.guardprovider",
                "com.miui.newmidrive",
                "com.miui.passwords",
                "com.miui.securityadd",
                "com.miui.securitycenter",
                "com.miui.securitycore",
                "com.miui.tsmclient",
                "com.miui.virtualsim",
                "com.unionpay.tsmservice.mi",
                "com.xiaomi.account",
                "com.xiaomi.digitalkey",
                "com.xiaomi.finddevice",
                "com.xiaomi.payment",
                "com.xiaomi.simactivate.service",
                "com.xiaomi.xmsf",
            ),
            HTTPDNS_EXCLUDED_APPS,
        )
        // Every exclusion names a package some other rule injects into, so the list cannot rot silently.
        assertEquals(emptySet(), HTTPDNS_EXCLUDED_APPS - injected)
    }

    @Test
    fun `no injected package that looks sensitive gets the httpdns rule`() {
        val rule = assertNotNull(Catalog.byId(HTTPDNS))
        val sensitive = Regex("account|pay|wallet|tsm|fido|finddevice|cloud|xmsf|security|password|digitalkey|sim")
        // Not sensitive despite the name: the Xiaomi community app.
        val notSensitive = setOf("com.xiaomi.vipaccount")
        val offenders = rule.packages.filter { sensitive.containsMatchIn(it) && it !in notSensitive }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `media drm rule applies to protected third-party apps only`() {
        assertNull(Catalog.byId(MEDIA_DRM), "not a vendor-catalog rule")
        assertTrue(
            Catalog.rules.none { rule ->
                (rule.payload as? RulePayload.Identifiers)?.source == IdentifierSource.MEDIA_DRM_ID
            },
        )
        val rule = assertNotNull(Catalog.protectedAppRules.singleOrNull { it.id == MEDIA_DRM })
        assertEquals(setOf(Catalog.PROTECTED_APPS), rule.packages)
        assertTrue(rule.isProtectedAppRule && rule.preventive && rule.defaultOn)
        assertTrue(Catalog.PROTECTED_APPS !in Catalog.scope && Catalog.PROTECTED_APPS !in Catalog.allPackages)
        // A protected app's process gets them through the virtual scope; no vendor package ever does.
        val protectedRules = PackageRules(Catalog.protectedAppRules)
        assertEquals(Catalog.protectedAppRules, protectedRules.inProcess(Catalog.PROTECTED_APPS))
        Catalog.allPackages.forEach { assertEquals(emptyList(), protectedRules.inProcess(it), it) }
        assertEquals(
            mapOf(MEDIA_DRM to true, PROTECTED_HTTPDNS to true, HARDWARE to false),
            ConfigCodec.decode(emptyMap<String, Any>(), Catalog.protectedAppRules),
        )
    }

    @Test
    fun `protected apps block the same resolvers as vendor packages`() {
        val vendor = assertNotNull(Catalog.byId(HTTPDNS))
        val protected = assertNotNull(Catalog.protectedAppRules.singleOrNull { it.id == PROTECTED_HTTPDNS })
        assertEquals(vendor.payload, protected.payload)
        assertEquals(setOf(Catalog.PROTECTED_APPS), protected.packages)
        assertNull(Catalog.byId(PROTECTED_HTTPDNS))
    }

    @Test
    fun `protected-app rules are separate from the catalog`() {
        assertTrue(Catalog.protectedAppRules.all { it.isProtectedAppRule })
        assertTrue(Catalog.rules.none { it.isProtectedAppRule || Catalog.PROTECTED_APPS in it.packages })
        val ids = (Catalog.rules + Catalog.protectedAppRules).map { it.id }
        assertEquals(ids.size, ids.toSet().size, "ids shared between catalog and protected-app rules")
        val rule = Catalog.protectedAppRules.first()
        assertThrows<IllegalArgumentException> { rule.copy(packages = setOf(Catalog.PROTECTED_APPS, "com.miui.notes")) }
    }

    private companion object {
        const val HTTPDNS = "httpdns.resolvers"
        const val MEDIA_DRM = "mediadrm.device-id"
        const val PROTECTED_HTTPDNS = "httpdns.protected-apps"
        const val HARDWARE = "fingerprint.hardware"
        const val BROWSER_HTTPDNS = "httpdns.browsers"
        const val PKG = "com.example.browser"

        val Rule.domains: List<String> get() = (payload as RulePayload.Domains).suffixes
    }
}
