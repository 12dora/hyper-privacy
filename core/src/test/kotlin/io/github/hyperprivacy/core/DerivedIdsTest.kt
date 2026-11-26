package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.config.ModuleSecret
import io.github.hyperprivacy.core.id.DerivedIds
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DerivedIdsTest {
    private val secret = ByteArray(ModuleSecret.BYTES) { it.toByte() }
    private val real = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun `derivation is hmac of real value and package truncated to length`() {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        val expected = mac.doFinal(real + "com.example.a".toByteArray())
        assertContentEquals(expected, DerivedIds.derive(real, "com.example.a", secret))
        val short = real.copyOf(16)
        assertContentEquals(
            mac.doFinal(short + "com.example.a".toByteArray()).copyOf(16),
            DerivedIds.derive(short, "com.example.a", secret),
        )
    }

    @Test
    fun `stable per app, different across apps and secrets`() {
        val a1 = DerivedIds.derive(real, "com.example.a", secret)
        val a2 = DerivedIds.derive(real.copyOf(), "com.example.a", secret.copyOf())
        val b = DerivedIds.derive(real, "com.example.b", secret)
        val other = DerivedIds.derive(real, "com.example.a", ByteArray(ModuleSecret.BYTES))
        assertContentEquals(a1, a2)
        assertFalse(a1.contentEquals(b))
        assertFalse(a1.contentEquals(other))
        assertFalse(a1.contentEquals(real))
    }

    @Test
    fun `lengths are preserved, including beyond one hmac block`() {
        listOf(0, 1, 16, 32, 33, 64, 100).forEach { length ->
            val derived = DerivedIds.derive(ByteArray(length) { 1 }, "com.example.a", secret)
            assertEquals(length, derived.size)
        }
        val long = DerivedIds.derive(ByteArray(64) { 1 }, "com.example.a", secret)
        assertFalse(long.copyOfRange(0, 32).contentEquals(long.copyOfRange(32, 64)))
    }

    @Test
    fun `text ids keep their format`() {
        val hex = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
        val derivedHex = DerivedIds.deriveText(hex, "com.example.a", secret)
        assertEquals(hex.length, derivedHex.length)
        assertTrue(derivedHex.all { it in '0'..'9' || it in 'a'..'f' }, derivedHex)
        assertNotEquals(hex, derivedHex)

        val upperUuid = "0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9"
        val derivedUuid = DerivedIds.deriveText(upperUuid, "com.example.a", secret)
        assertTrue(
            derivedUuid.matches(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")),
            derivedUuid,
        )

        val decimal = DerivedIds.deriveText("123456789012345", "com.example.a", secret)
        assertTrue(decimal.all { it in '0'..'9' } && decimal.length == 15, decimal)

        assertEquals("", DerivedIds.deriveText("", "com.example.a", secret))
        assertEquals(
            DerivedIds.deriveText(hex, "com.example.a", secret),
            DerivedIds.deriveText(hex, "com.example.a", secret),
        )
        assertNotEquals(derivedHex, DerivedIds.deriveText(hex, "com.example.b", secret))
    }

    @Test
    fun `module secret round-trips and rejects malformed values`() {
        val text = ModuleSecret.encode(ByteArray(ModuleSecret.BYTES).also(SecureRandom()::nextBytes))
        assertEquals(ModuleSecret.BYTES * 2, text.length)
        assertTrue(text.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(ModuleSecret.BYTES, ModuleSecret.parse(text)?.size)
        assertNotEquals(text, ModuleSecret.encode(ByteArray(ModuleSecret.BYTES).also(SecureRandom()::nextBytes)))
        assertNull(ModuleSecret.parse(null))
        assertNull(ModuleSecret.parse(true))
        assertNull(ModuleSecret.parse("abc"))
        assertNull(ModuleSecret.parse("z".repeat(ModuleSecret.BYTES * 2)))
    }

    @Test
    fun `secret changes reach hooked processes but are not rule switches`() {
        assertTrue(ConfigCodec.affectsHooks(ModuleSecret.KEY))
        assertNull(ConfigCodec.ruleIdOf(ModuleSecret.KEY))
    }
}
