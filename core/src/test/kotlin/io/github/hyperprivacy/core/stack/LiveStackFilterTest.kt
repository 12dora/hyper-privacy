package io.github.hyperprivacy.core.stack

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiveStackFilterTest {
    // As in a release build: renamed classes under the repackage prefix, the entry under the source root.
    private val filter = LiveStackFilter(arrayOf("u_qz.", "io.github.hyperprivacy."))

    private fun f(name: String, method: String = "x") = StackTraceElement(name, method, "F.java", 1)

    private fun frames(vararg classes: String) = classes.map { f(it) }.toTypedArray()

    private fun names(frames: Array<StackTraceElement>) = frames.map { it.className }

    @Test
    fun `no module frame returns the same array`() {
        val input = frames("java.lang.Thread", "com.app.Logger", "android.os.Looper")
        assertSame(input, filter.filter(input))
        val empty = emptyArray<StackTraceElement>()
        assertSame(empty, filter.filter(empty))
        assertSame(empty, filter.filter(empty, firstWindowOnly = true))
    }

    @Test
    fun `R8 short names and bundled runtimes are never ours`() {
        val input = frames(
            "a",
            "m",
            "zn",
            "gi1",
            "kotlin.collections.ArraysKt",
            "kotlinx.coroutines.Job",
            "androidx.core.A",
        )
        assertSame(input, filter.filter(input))
        assertFalse(filter.isModule("u_q.A"))
        assertTrue(filter.isModule("u_qz.a"))
        assertTrue(filter.isModule("io.github.hyperprivacy.xposed.ModuleMain"))
    }

    /** `Thread.currentThread().getStackTrace()` as seen inside our hook, with LSPosed's default stub. */
    private fun selfSample(caller: String = "com.app.Logger") = arrayOf(
        f("dalvik.system.VMStack", "getThreadStackTrace"),
        f("java.lang.Thread", "getStackTrace"),
        f("org.lsposed.lspd.impl.LSPosedHookChain", "proceed"),
        f("u_qz.b", "intercept"),
        f("org.lsposed.lspd.impl.LSPosedBridge", "dispatch"),
        f("LSPHooker_", "getStackTrace"),
        f(caller, "log"),
        f("com.app.Main", "run"),
        f("u_qz.c", "intercept"), // A second, unrelated window further down.
        f("LSPHooker_", "connect"),
        f("com.app.Net", "open"),
    )

    @Test
    fun `current thread sample loses the hook window and keeps the caller at its genuine index`() {
        val out = filter.filter(selfSample())
        assertEquals(
            listOf("dalvik.system.VMStack", "java.lang.Thread", "com.app.Logger", "com.app.Main", "com.app.Net"),
            names(out),
        )
    }

    @Test
    fun `a reflective backup call goes with the window, with a generated stub too`() {
        val input = arrayOf(
            f("dalvik.system.VMStack", "getThreadStackTrace"),
            f("java.lang.Thread", "getStackTrace"),
            f("java.lang.reflect.Method", "invoke"),
            f("org.lsposed.lspd.impl.LSPosedHookChain", "proceed"),
            f("u_qz.b", "intercept"),
            f("LSPHooker_", "getStackTrace"),
            f("com.app.Logger", "log"),
        )
        val out = filter.filter(input)
        assertEquals(listOf("dalvik.system.VMStack", "java.lang.Thread", "com.app.Logger"), names(out))
        assertEquals(
            listOf("dalvik.system.VMStack", "java.lang.Thread", "com.app.Logger"),
            names(filter.filter(input, true)),
        )
        // An app's own reflective call far from any window stays.
        val app = arrayOf(f("java.lang.reflect.Method", "invoke"), f("com.app.A", "go"))
        assertSame(app, filter.filter(app))
    }

    @Test
    fun `paused strips only the calling hook's window`() {
        val out = filter.filter(selfSample(), firstWindowOnly = true)
        assertEquals(
            listOf(
                "dalvik.system.VMStack",
                "java.lang.Thread",
                "com.app.Logger",
                "com.app.Main",
                "u_qz.c",
                "LSPHooker_",
                "com.app.Net",
            ),
            names(out),
        )
    }

    @Test
    fun `a stub in the hooked method's package goes, its caller stays`() {
        val input = arrayOf(
            f("sun.nio.ch.Net", "connect0"),
            f("sun.nio.ch.SocketChannelImpl", "connect"),
            f("java.lang.reflect.Method", "invoke"),
            f("org.lsposed.lspd.impl.Chain", "proceed"),
            f("u_qz.d", "intercept"),
            f("sun.nio.ch.QxYzStub", "connect"),
            f("okhttp3.internal.connection.RealConnection", "connect"),
        )
        assertEquals(
            listOf("sun.nio.ch.Net", "sun.nio.ch.SocketChannelImpl", "okhttp3.internal.connection.RealConnection"),
            names(filter.filter(input)),
        )
    }

    @Test
    fun `a default-package stub goes, overload delegation and other packages stay`() {
        val stub = arrayOf(
            f("okhttp3.RealCall", "execute"),
            f("u_qz.d", "intercept"),
            f("Zq", "execute"),
            f("com.app.A", "go"),
        )
        assertEquals(listOf("okhttp3.RealCall", "com.app.A"), names(filter.filter(stub)))
        val overload = arrayOf(
            f("java.net.Socket", "connect"),
            f("u_qz.d", "intercept"),
            f("java.net.Socket", "connect"),
        )
        assertEquals(listOf("java.net.Socket", "java.net.Socket"), names(filter.filter(overload)))
        val wrapper = arrayOf(
            f("okhttp3.RealCall", "execute"),
            f("u_qz.d", "intercept"),
            f("com.app.Client", "execute"),
        )
        assertEquals(listOf("okhttp3.RealCall", "com.app.Client"), names(filter.filter(wrapper)))
    }

    @Test
    fun `WebView frames next to a module window stay`() {
        val input = arrayOf(
            f("com.app.web.Client", "shouldInterceptRequest"),
            f("org.lsposed.lspd.impl.Chain", "proceed"),
            f("u_qz.e", "intercept"),
            f("LSPHooker_", "shouldInterceptRequest"),
            f("com.android.webview.chromium.WebViewContentsClientAdapter", "shouldInterceptRequest"),
            f("org.chromium.android_webview.AwContentsBackgroundThreadClient", "shouldInterceptRequestFromNative"),
        )
        assertEquals(
            listOf(
                "com.app.web.Client",
                "com.android.webview.chromium.WebViewContentsClientAdapter",
                "org.chromium.android_webview.AwContentsBackgroundThreadClient",
            ),
            names(filter.filter(input)),
        )
    }

    @Test
    fun `disjoint windows are filtered independently and app frames between them stay`() {
        val input = arrayOf(
            f("u_qz.a"),
            f("org.lsposed.X"),
            f("com.app.Sdk", "load"),
            f("u_qz.b"),
            f("com.app.Main", "run"),
        )
        assertEquals(listOf("com.app.Sdk", "com.app.Main"), names(filter.filter(input)))
    }

    @Test
    fun `an all-module trace is left alone`() {
        val input = frames("u_qz.a", "u_qz.b")
        assertSame(input, filter.filter(input))
    }

    @Test
    fun `filterAll rewrites only traces that changed`() {
        val clean = frames("com.app.A")
        val dirty = frames("com.app.A", "u_qz.a")
        val a = Thread()
        val b = Thread()
        val map = hashMapOf(a to clean, b to dirty)
        assertEquals(true, filter.filterAll(map))
        assertSame(clean, map[a])
        assertNotSame(dirty, map[b])
        assertEquals(listOf("com.app.A"), names(map.getValue(b)))
        assertFalse(filter.filterAll(hashMapOf(a to clean)))
    }
}
