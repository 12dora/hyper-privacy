package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.config.ModuleSecret
import io.github.hyperprivacy.core.fingerprint.HardwareTraits
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.IdentifierSource
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.tracker.TrackerRules
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardwareTraitsTest {
    private val secret = ByteArray(ModuleSecret.BYTES) { (it * 5 + 1).toByte() }
    private val packages = (0 until 200).map { "com.example.app$it" }
    private val rule: Rule = assertNotNull(Catalog.protectedAppRules.singleOrNull { it.id == "fingerprint.hardware" })

    @Test
    fun `stable per app and secret, different across apps and secrets`() {
        val a = HardwareTraits.derive("com.example.a", secret)
        val again = HardwareTraits.derive("com.example.a", secret.copyOf())
        val b = HardwareTraits.derive("com.example.b", secret)
        val other = HardwareTraits.derive("com.example.a", ByteArray(ModuleSecret.BYTES))
        assertEquals(a.resolution(0.01f), again.resolution(0.01f))
        assertEquals(a.power(0.5f), again.power(0.5f))
        assertEquals(a.version(3), again.version(3))
        assertEquals(a.fsid(0x1234L), again.fsid(0x1234L))
        assertNotEquals(a.fsid(0x1234L), b.fsid(0x1234L))
        assertNotEquals(a.fsid(0x1234L), other.fsid(0x1234L))
        assertNotEquals(a.resolution(0.01f), b.resolution(0.01f))
    }

    @Test
    fun `apps rarely share any value`() {
        val traits = packages.map { HardwareTraits.derive(it, secret) }
        assertEquals(packages.size, traits.map { it.fsid(1L) }.toSet().size)
        // 16-bit factors: collisions are possible but must be rare, never systematic.
        assertTrue(traits.map { it.resolution(1f) }.toSet().size > packages.size * 9 / 10)
        assertTrue(traits.map { it.power(1f) }.toSet().size > packages.size * 9 / 10)
        assertTrue(traits.map { it.version(10) }.toSet().size > 50)
        // The two scale factors of one app are independent of each other.
        assertTrue(traits.any { it.resolution(1f) != it.power(1f) })
    }

    @Test
    fun `scaled values stay within one percent and never equal the real value`() {
        val tolerance = HardwareTraits.MAX_SCALE_DELTA + 1e-6f
        packages.map { HardwareTraits.derive(it, secret) }.forEach { traits ->
            listOf(0.001f, 0.01f, 0.5f, 1f, 100f, 2000f, -3f).forEach { real ->
                val resolution = traits.resolution(real)
                val power = traits.power(real)
                assertTrue(abs(resolution / real - 1f) <= tolerance, "$real -> $resolution")
                assertTrue(abs(power / real - 1f) <= tolerance, "$real -> $power")
                assertNotEquals(real, resolution)
                assertNotEquals(real, power)
            }
        }
    }

    @Test
    fun `zero and non-finite readings are returned as they are`() {
        val traits = HardwareTraits.derive("com.example.a", secret)
        assertEquals(0f, traits.resolution(0f))
        assertEquals(0f, traits.power(0f))
        assertTrue(traits.resolution(Float.NaN).isNaN())
        assertEquals(Float.POSITIVE_INFINITY, traits.power(Float.POSITIVE_INFINITY))
        assertTrue(traits.resolution(Float.MAX_VALUE).isFinite())
    }

    @Test
    fun `version only ever grows by a bounded positive amount`() {
        packages.map { HardwareTraits.derive(it, secret) }.forEach { traits ->
            listOf(1, 2, 12, 20102, 1_000_000).forEach { real ->
                val delta = traits.version(real) - real
                assertTrue(delta in 1..HardwareTraits.MAX_VERSION_OFFSET, "$real -> $delta")
            }
            assertEquals(0, traits.version(0))
            assertEquals(-1, traits.version(-1))
            assertEquals(Int.MAX_VALUE, traits.version(Int.MAX_VALUE))
            assertEquals(Int.MAX_VALUE - 1, traits.version(Int.MAX_VALUE - 1))
        }
    }

    @Test
    fun `fsid keeps equality between paths on one file system and distinctness between file systems`() {
        val traits = HardwareTraits.derive("com.example.a", secret)
        val data = 0x6a2b_91c4_0f3d_77e8L
        val sdcard = 0x00ff_1a2bL
        assertEquals(traits.fsid(data), traits.fsid(data))
        assertNotEquals(traits.fsid(data), traits.fsid(sdcard))
        assertNotEquals(data, traits.fsid(data))
        assertEquals(data, traits.fsid(traits.fsid(data)))
        assertNotEquals(0L, traits.fsid(0L), "an empty id still becomes per-app")
    }

    @Test
    fun `rule is a protected-app rule, off by default, in the device-id category`() {
        assertTrue(rule.isProtectedAppRule && rule.preventive)
        assertFalse(rule.defaultOn)
        assertEquals(Category.DEVICE_ID, rule.category)
        assertEquals(RulePayload.Identifiers(IdentifierSource.HARDWARE_TRAITS), rule.payload)
        assertNull(Catalog.byId(rule.id), "not a vendor-catalog rule")
        assertEquals(mapOf(rule.id to false), ConfigCodec.decode(emptyMap<String, Any>(), listOf(rule)))
    }

    @Test
    fun `per-app switch turns it on for one app only`() {
        val raw = mapOf<String, Any>(TrackerRules.appRuleKey("com.example.a", rule.id) to true)
        assertTrue(TrackerRules.isAppRuleOn(raw, "com.example.a", rule))
        assertFalse(TrackerRules.isAppRuleOn(raw, "com.example.b", rule))
    }

    @Test
    fun `copy fits one line in both languages and describes the function`() {
        assertTrue(CopyWidth.zh(rule.title.zh) <= 10.0 && rule.title.en.length <= 32)
        assertTrue(CopyWidth.zh(rule.summary.zh) <= 14.0 && rule.summary.en.length <= 40)
        val banned = listOf("fraud", "反欺诈", "风控")
        listOf(rule.title.zh, rule.title.en, rule.summary.zh, rule.summary.en).forEach { text ->
            assertTrue(banned.none { text.contains(it, ignoreCase = true) }, text)
        }
    }
}
