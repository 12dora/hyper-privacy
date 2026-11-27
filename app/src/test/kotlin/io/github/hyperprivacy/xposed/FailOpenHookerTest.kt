package io.github.hyperprivacy.xposed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FailOpenHookerTest {
    private val xposed = FakeXposed()
    private val log = HookLog(xposed.api)
    private val method = Any::class.java.getMethod("toString")

    private fun hooker(decide: () -> Verdict) = FailOpenHooker(log, "test") { decide() }

    @Test
    fun `a failing decision runs the original exactly once`() {
        listOf(IllegalStateException("bug"), NoClassDefFoundError("gone"), AssertionError("no")).forEach { error ->
            val chain = FakeChain(method, original = { "real" })
            assertEquals("real", hooker { throw error }.intercept(chain))
            assertEquals(1, chain.proceeds, error.toString())
        }
    }

    @Test
    fun `the original's own exception propagates unchanged and is not retried`() {
        val failure = IOException("down")
        val chain = FakeChain(method, original = { throw failure })
        assertSame(failure, assertThrows<IOException> { hooker { Verdict.Proceed }.intercept(chain) })
        assertEquals(1, chain.proceeds)
    }

    @Test
    fun `a replaced call never runs the original`() {
        val chain = FakeChain(method, original = { "real" })
        assertEquals(0, hooker { Verdict.Return(0) }.intercept(chain))
        assertEquals(0, chain.proceeds)
    }

    @Test
    fun `a failing transform returns the real result without a second call`() {
        val chain = FakeChain(method, original = { "real" })
        assertEquals("real", hooker { Verdict.Transform { error("bug") } }.intercept(chain))
        assertEquals(1, chain.proceeds)
    }
}
