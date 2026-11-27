package io.github.hyperprivacy.core.stack

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LiveStackFilterTest {
    private val ours = setOf("m", "zn", "gi1")
    private val stubs = setOf("android.media.FaeZirs")
    private val filter = LiveStackFilter({ it in ours }, { it in stubs })

    private fun frames(vararg classes: String) = classes.map { StackTraceElement(it, "x", "F.java", 1) }.toTypedArray()

    private fun names(frames: Array<StackTraceElement>) = frames.map { it.className }

    @Test
    fun `no module frame returns the same array`() {
        val input = frames("java.lang.Thread", "com.app.Logger", "android.os.Looper")
        assertSame(input, filter.filter(input))
    }

    @Test
    fun `drops module frames and the adjacent stub but keeps the hooked method and app frames`() {
        val input = frames(
            "java.lang.Thread", "android.media.MediaDrm", "android.media.FaeZirs", "m", "zn", "gi1",
            "android.media.FaeZirs", "com.app.Sdk", "com.app.Main",
        )
        assertEquals(
            listOf("java.lang.Thread", "android.media.MediaDrm", "com.app.Sdk", "com.app.Main"),
            names(filter.filter(input)),
        )
    }

    @Test
    fun `a stub-like name away from any module frame stays`() {
        val input = frames("android.media.FaeZirs", "com.app.Sdk", "zn", "com.app.Main")
        assertEquals(listOf("android.media.FaeZirs", "com.app.Sdk", "com.app.Main"), names(filter.filter(input)))
    }

    @Test
    fun `app and platform frames next to a module frame are kept`() {
        val input = frames("com.plugin.Caller", "zn", "java.net.InetAddress", "android.os.Handler")
        assertEquals(
            listOf("com.plugin.Caller", "java.net.InetAddress", "android.os.Handler"),
            names(filter.filter(input)),
        )
    }

    @Test
    fun `framework packages are bridge frames when touching a module frame`() {
        val input = frames("android.media.MediaDrm", "org.lsposed.lspd.impl.Chain", "m", "com.app.Main")
        assertEquals(listOf("android.media.MediaDrm", "com.app.Main"), names(filter.filter(input)))
    }

    @Test
    fun `an all-module trace is left alone`() {
        val input = frames("m", "zn")
        assertSame(input, filter.filter(input))
    }

    @Test
    fun `filterAll rewrites only traces that changed`() {
        val clean = frames("com.app.A")
        val dirty = frames("com.app.A", "m")
        val a = Thread()
        val b = Thread()
        val map = hashMapOf(a to clean, b to dirty)
        assertEquals(true, filter.filterAll(map))
        assertSame(clean, map[a])
        assertNotSame(dirty, map[b])
        assertEquals(listOf("com.app.A"), names(map.getValue(b)))
    }
}
