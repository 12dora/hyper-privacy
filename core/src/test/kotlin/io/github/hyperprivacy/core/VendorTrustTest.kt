package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.system.AppListPolicy
import io.github.hyperprivacy.core.system.InstalledApp
import io.github.hyperprivacy.core.system.VendorTrust
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VendorTrustTest {
    private val apps = listOf(
        InstalledApp(VendorTrust.PLATFORM_PACKAGE, 1_000, isSystem = true, signers = setOf(PLATFORM)),
        InstalledApp("com.miui.securitycenter", 1_000, isSystem = true, signers = setOf(PLATFORM)),
        InstalledApp("com.miui.player", 10_010, isSystem = true, signers = setOf(XIAOMI_APPS)),
        // A Google app preinstalled under com.android.*: its certificate must not become trusted.
        InstalledApp("com.android.vending", 10_011, isSystem = true, signers = setOf(GOOGLE)),
        // HyperOS data apps: no system flag, vendor certificates.
        InstalledApp("com.mi.health", 10_100, isSystem = false, signers = setOf(XIAOMI_APPS)),
        InstalledApp("com.android.soundrecorder", 10_101, isSystem = false, signers = setOf(PLATFORM)),
        // Third-party apps, one squatting a vendor namespace, one signed by Google.
        InstalledApp("com.xiaomi.fake", 10_200, isSystem = false, signers = setOf(OTHER)),
        InstalledApp("com.google.android.gm", 10_201, isSystem = false, signers = setOf(GOOGLE)),
        InstalledApp("com.example.unsigned", 10_202, isSystem = false),
        InstalledApp("com.example.rotated", 10_203, isSystem = false, signers = setOf(OTHER, XIAOMI_APPS)),
    )
    private val index = AppIndex.build(apps)

    private fun appId(pkg: String) = requireNotNull(index.appIdOf(pkg))

    @Test
    fun `trusted signers come from vendor system packages and the platform only`() {
        assertEquals(setOf(PLATFORM, XIAOMI_APPS), VendorTrust.trustedSigners(apps))
        assertTrue(VendorTrust.isVendorNamespace("com.miui.notes"))
        assertTrue(VendorTrust.isVendorNamespace("miui.systemui.plugin"))
        assertTrue(VendorTrust.isVendorNamespace("com.mi.health"))
        assertFalse(VendorTrust.isVendorNamespace("com.mipay.wallet"))
        assertFalse(VendorTrust.isVendorNamespace("com.android.vending"))
    }

    @Test
    fun `vendor data apps are not third-party`() {
        assertFalse(index.isThirdParty(appId("com.mi.health")), "vendor app certificate")
        assertFalse(index.isThirdParty(appId("com.android.soundrecorder")), "platform certificate")
        assertFalse(index.isThirdParty(appId("com.miui.player")), "system app")
        assertFalse(index.isThirdParty(appId("com.android.vending")), "system app")
    }

    @Test
    fun `everything else is third-party`() {
        assertTrue(index.isThirdParty(appId("com.xiaomi.fake")), "vendor namespace is not enough")
        assertTrue(index.isThirdParty(appId("com.google.android.gm")), "non-vendor com.android.* signer")
        assertTrue(index.isThirdParty(appId("com.example.unsigned")))
        assertFalse(index.isThirdParty(appId("com.example.rotated")), "any vendor signer is enough")
    }

    @Test
    fun `vendor data apps keep seeing the module and stay visible to restricted apps`() {
        val config = AppListConfig.decode(mapOf(AppListConfig.hideKey("com.xiaomi.fake") to true))
        val policy = AppListPolicy.resolve(config, index, "com.example.unsigned")
        assertFalse(policy.shouldHide(appId("com.mi.health"), appId("com.example.unsigned")))
        assertTrue(policy.shouldHide(appId("com.google.android.gm"), appId("com.example.unsigned")))
        assertFalse(policy.shouldHide(appId("com.xiaomi.fake"), appId("com.mi.health")))
        assertTrue(policy.shouldHide(appId("com.xiaomi.fake"), appId("com.google.android.gm")))
    }

    @Test
    fun `APK v3 rotation lineage counts on both sides`() {
        val rotated = listOf(
            InstalledApp(VendorTrust.PLATFORM_PACKAGE, 1_000, isSystem = true, signers = setOf(PLATFORM)),
            // A vendor system app that rotated from its original key to a new one.
            InstalledApp("com.xiaomi.account", 10_001, true, setOf(XIAOMI_NEW), pastSigners = setOf(XIAOMI_APPS)),
            // A data app still signed with the original key, and one that rotated away from it.
            InstalledApp("com.mi.health", 10_100, isSystem = false, signers = setOf(XIAOMI_APPS)),
            InstalledApp("com.mi.shop", 10_101, false, signers = setOf(OTHER), pastSigners = setOf(XIAOMI_APPS)),
            InstalledApp("com.mi.notes", 10_102, isSystem = false, signers = setOf(XIAOMI_NEW)),
            InstalledApp("com.example.app", 10_200, false, signers = setOf(OTHER), pastSigners = setOf(GOOGLE)),
        )
        val trusted = VendorTrust.trustedSigners(rotated)
        assertEquals(setOf(PLATFORM, XIAOMI_NEW, XIAOMI_APPS), trusted)
        val rotatedIndex = AppIndex.build(rotated)
        assertEquals(trusted, rotatedIndex.trustedSigners)
        listOf("com.mi.health", "com.mi.shop", "com.mi.notes").forEach {
            assertFalse(rotatedIndex.isThirdParty(requireNotNull(rotatedIndex.appIdOf(it))), it)
        }
        assertTrue(rotatedIndex.isThirdParty(requireNotNull(rotatedIndex.appIdOf("com.example.app"))))
    }

    @Test
    fun `a partial view plus system_server's trusted signers classifies like system_server`() {
        // The module app does not see com.miui.player (no launcher entry, not queried).
        val visible = apps.filter { it.packageName != "com.miui.player" }
        val local = VendorTrust.trustedSigners(visible)
        assertFalse(XIAOMI_APPS in local, "the partial view alone misses a vendor certificate")
        val combined = local + index.trustedSigners
        apps.filter { index.appIdOf(it.packageName) != null }.forEach { app ->
            val vendor = VendorTrust.isVendorTrusted(app, combined)
            assertEquals(!index.isThirdParty(appId(app.packageName)), vendor, app.packageName)
        }
    }

    private companion object {
        const val PLATFORM = "platform"
        const val XIAOMI_APPS = "xiaomi"
        const val XIAOMI_NEW = "xiaomi-rotated"
        const val GOOGLE = "google"
        const val OTHER = "other"
    }
}
