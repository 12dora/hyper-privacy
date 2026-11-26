package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.system.AppListPolicy
import io.github.hyperprivacy.core.system.InstalledApp
import io.github.hyperprivacy.core.system.IntSet
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppListPolicyTest {
    private val index = AppIndex.build(
        listOf(
            InstalledApp(PROBER, 10_100, isSystem = false),
            InstalledApp(STRICT_PROBER, 10_101, isSystem = false),
            InstalledApp(BANK, 10_200, isSystem = false),
            InstalledApp(WECHAT, 10_201, isSystem = false),
            InstalledApp(VENDOR, 10_050, isSystem = true),
            // Shared uid with a system app: never third-party.
            InstalledApp(SHARED_USER, 10_060, isSystem = false),
            InstalledApp(SHARED_SYSTEM, 10_060, isSystem = true),
            // A second user's copy maps to the same app id.
            InstalledApp(BANK, 1_010_200, isSystem = false),
            InstalledApp("android", 1_000, isSystem = true),
            InstalledApp(MODULE, 10_300, isSystem = false),
            InstalledApp(LAUNCHER, 10_301, isSystem = false),
            InstalledApp(MANAGER, 10_302, isSystem = false),
        ),
    )

    private fun policy(vararg raw: Pair<String, Any?>) = AppListPolicy.resolve(AppListConfig.decode(mapOf(*raw)), index)

    private fun id(pkg: String): Int = requireNotNull(index.appIdOf(pkg))

    @Test
    fun `int set agrees with a hash set`() {
        val random = Random(7)
        val values = List(500) { random.nextInt(0, 20_000) }
        val set = IntSet.of(values)
        val reference = values.toSet()
        assertEquals(reference.size, set.size)
        (0 until 20_000).forEach { assertEquals(it in reference, it in set, "value $it") }
        assertFalse(-1 in set)
        assertFalse(5 in IntSet.EMPTY_SET)
        assertEquals(IntSet.of(3, 1, 2), IntSet.of(1, 2, 3, -4))
    }

    @Test
    fun `app index classifies by app id`() {
        assertTrue(index.isThirdParty(10_100))
        assertTrue(index.isThirdParty(10_200))
        assertFalse(index.isThirdParty(10_050), "system app")
        assertFalse(index.isThirdParty(10_060), "uid shared with a system app")
        assertFalse(index.isThirdParty(1_000), "not an application uid")
        assertFalse(index.isKnown(1_000))
        assertTrue(index.isKnown(10_060))
        assertFalse(index.isKnown(10_999))
        assertTrue(index.isThirdParty(10_999), "installed after the snapshot: fail closed")
        assertFalse(AppIndex.EMPTY.isThirdParty(10_999), "no snapshot yet: fail open")
        assertTrue(AppIndex.EMPTY.isEmpty && !index.isEmpty)
        assertEquals(SHARED_SYSTEM, index.packageOf(10_060))
        assertEquals(10_200, AppIndex.appIdOf(1_010_200))
        assertEquals(-1, AppIndex.appIdOf(1_000))
        assertEquals(-1, AppIndex.appIdOf(-5))
        assertEquals(-1, AppIndex.appIdOf(20_001), "sdk sandbox / isolated range")
    }

    @Test
    fun `sdk sandbox callers are third-party and attributed to their owner`() {
        val sandbox = AppIndex.callerAppId(1_020_050)
        assertEquals(20_050, sandbox)
        assertTrue(index.isThirdParty(sandbox), "sandbox of a system app still runs SDK code")
        assertTrue(index.isKnown(sandbox))
        assertEquals(VENDOR, index.packageOf(sandbox))
        assertEquals(10_100, AppIndex.callerAppId(10_100))
        assertEquals(-1, AppIndex.callerAppId(99_001), "isolated")
        assertEquals(-1, AppIndex.callerAppId(1_000))
        assertFalse(AppIndex.EMPTY.isThirdParty(sandbox))
    }

    @Test
    fun `nothing is hidden by default`() {
        val inactive = policy()
        assertTrue(inactive.isInactive)
        assertFalse(inactive.shouldHide(id(PROBER), id(BANK)))
        assertEquals(AppListConfig.DEFAULT_ALLOWLIST, AppListConfig.decode(emptyMap<String, Any>()).allowlist)
    }

    @Test
    fun `restricted caller loses sight of third-party apps only`() {
        val p = policy(AppListConfig.hideKey(PROBER) to true)
        assertFalse(p.isInactive)
        assertTrue(p.shouldHide(id(PROBER), id(BANK)))
        assertFalse(p.shouldHide(id(PROBER), id(PROBER)), "itself")
        assertFalse(p.shouldHide(id(PROBER), id(VENDOR)), "system app")
        assertFalse(p.shouldHide(id(PROBER), id(SHARED_USER)), "shares a system uid")
        assertTrue(p.shouldHide(id(PROBER), 10_999), "app id installed after the snapshot")
        assertFalse(p.shouldHide(id(PROBER), 1_000), "not an application")
        assertFalse(p.shouldHide(id(PROBER), id(WECHAT)), "default allowlist")
        assertFalse(p.shouldHide(id(BANK), id(PROBER)), "unrestricted caller")
    }

    @Test
    fun `strict ignores the allowlist and requires the app to be restricted`() {
        val p = policy(
            AppListConfig.hideKey(STRICT_PROBER) to true,
            AppListConfig.strictKey(STRICT_PROBER) to true,
            AppListConfig.hideKey(PROBER) to true,
            AppListConfig.strictKey(BANK) to true,
        )
        assertTrue(p.shouldHide(id(STRICT_PROBER), id(WECHAT)))
        assertFalse(p.shouldHide(id(PROBER), id(WECHAT)))
        assertFalse(p.isRestricted(id(BANK)), "strict alone does not restrict")
        assertEquals(emptySet(), AppListConfig.decode(mapOf(AppListConfig.strictKey(BANK) to true)).strict)
    }

    @Test
    fun `allowlist value replaces the default`() {
        val replaced = policy(AppListConfig.hideKey(PROBER) to true, AppListConfig.ALLOW_KEY to " $BANK,\n x.y ")
        assertFalse(replaced.shouldHide(id(PROBER), id(BANK)))
        assertTrue(replaced.shouldHide(id(PROBER), id(WECHAT)))
        val emptied = policy(AppListConfig.hideKey(PROBER) to true, AppListConfig.ALLOW_KEY to "")
        assertTrue(emptied.shouldHide(id(PROBER), id(WECHAT)))
        val asSet = policy(AppListConfig.hideKey(PROBER) to true, AppListConfig.ALLOW_KEY to setOf(BANK))
        assertFalse(asSet.shouldHide(id(PROBER), id(BANK)))
        assertEquals(setOf("a", "b"), AppListConfig.parseAllowlist(AppListConfig.encodeAllowlist(listOf("a", "b"))))
        assertNull(AppListConfig.parseAllowlist(42))
    }

    @Test
    fun `malformed or uninstalled entries are ignored`() {
        val p = policy(
            AppListConfig.hideKey(PROBER) to "true",
            AppListConfig.hideKey("not.installed") to true,
            AppListConfig.HIDE_PREFIX to true,
            AppListConfig.ALLOW_KEY to 3,
        )
        assertTrue(p.isInactive)
        val decoded = AppListConfig.decode(mapOf(AppListConfig.hideKey("not.installed") to true))
        assertEquals(setOf("not.installed"), decoded.restricted)
    }

    @Test
    fun `module hides itself from third-party apps except exempt ones`() {
        val p = AppListPolicy.resolve(AppListConfig.decode(emptyMap<String, Any>()), index, MODULE, setOf(LAUNCHER))
        assertFalse(p.isInactive)
        assertTrue(p.shouldHide(id(PROBER), id(MODULE)))
        assertTrue(p.mayHideFrom(id(PROBER)))
        assertFalse(p.shouldHide(id(PROBER), id(BANK)), "self-hiding restricts nothing else")
        assertFalse(p.shouldHide(id(LAUNCHER), id(MODULE)), "launcher")
        assertFalse(p.shouldHide(id(MANAGER), id(MODULE)), "LSPosed manager")
        assertFalse(p.shouldHide(id(VENDOR), id(MODULE)), "system app (hooked targets report status)")
        assertFalse(p.shouldHide(id(MODULE), id(MODULE)))
        assertFalse(p.mayHideFrom(id(VENDOR)))
        assertTrue(p.shouldHide(10_999, id(MODULE)), "caller installed after the snapshot")
        assertFalse(p.mayHideFrom(-1), "non-application caller")
        val off = AppListPolicy.resolve(
            AppListConfig.decode(mapOf(AppListConfig.SELF_HIDE_KEY to false)),
            index,
            MODULE,
        )
        assertTrue(off.isInactive)
    }

    @Test
    fun `config keys are recognised for live refresh`() {
        assertTrue(AppListConfig.affects(AppListConfig.SELF_HIDE_KEY))
        assertTrue(AppListConfig.affects(null))
        assertTrue(AppListConfig.affects(AppListConfig.ALLOW_KEY))
        assertTrue(AppListConfig.affects(AppListConfig.hideKey(PROBER)))
        assertTrue(AppListConfig.affects(AppListConfig.strictKey(PROBER)))
        assertFalse(AppListConfig.affects("rule.settings.identifiers"))
        assertFalse(AppListConfig.affects("diag.observe"))
    }

    private companion object {
        const val PROBER = "com.example.prober"
        const val STRICT_PROBER = "com.example.strict"
        const val BANK = "com.example.bank"
        const val WECHAT = "com.tencent.mm"
        const val VENDOR = "com.miui.vendor"
        const val SHARED_USER = "com.example.shared"
        const val SHARED_SYSTEM = "com.android.shared"
        const val MODULE = "io.github.hyprivacy"
        const val LAUNCHER = "com.example.launcher"
        const val MANAGER = "org.lsposed.manager"
    }
}
