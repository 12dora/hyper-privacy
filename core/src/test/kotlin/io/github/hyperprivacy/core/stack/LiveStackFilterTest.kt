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

    // An LSPosed build that randomises its own class names per install: only the class loader tells them apart.
    private val hookBridge = "PwtZ.MmA.U.KKXb.gSxtyVfyUf.AC.poMM.HookBridge"
    private val frameworkNames = setOf("k2", "m", "k", "j2", hookBridge)
    private val appNames = setOf("m", "a", "com.app.Drm", "com.app.Main") // The app's R8 also emitted an `m`.
    private val asked = mutableListOf<String>()
    private val origin = FrameOrigin { name ->
        asked += name
        when {
            name in frameworkNames && name in appNames -> FrameOrigin.SHARED
            name in frameworkNames -> FrameOrigin.FRAMEWORK
            name in appNames || name.startsWith("android.media.") || name.startsWith("java.") -> FrameOrigin.OTHER
            else -> FrameOrigin.FOREIGN
        }
    }
    private val loaders = LiveStackFilter(arrayOf("u_qz.", "io.github.hyperprivacy."), origin)

    /** A second thread sampling a thread inside a hooked `MediaDrm#getPropertyByteArray`, as seen on the device. */
    private fun drmSample(
        stub: String = "android.hardware.FruntshQuierniep",
        stubMethod: String = "getPropertyByteArray",
    ) = arrayOf(
        f("android.media.MediaDrm", "getPropertyByteArray"),
        f(hookBridge, "invokeOriginalMethod"),
        f("j2", "proceed"),
        f("u_qz.f", "intercept"),
        f("k", "intercept"),
        f("m", "a"), // Defined by the framework and, under the same name, by the app: inside the window.
        f("k2", "callback"),
        f(stub, stubMethod),
        f("com.app.Drm", "read"),
        f("com.app.Main", "run"),
    )

    @Test
    fun `random-name framework frames, HookBridge and a stub in a platform package go`() {
        assertEquals(
            listOf("android.media.MediaDrm", "com.app.Drm", "com.app.Main"),
            names(loaders.filter(drmSample())),
        )
        // Without the loader oracle only the module frame goes: what the device showed before.
        assertEquals(
            listOf(
                "android.media.MediaDrm", hookBridge, "j2", "k", "m", "k2",
                "android.hardware.FruntshQuierniep", "com.app.Drm", "com.app.Main",
            ),
            names(filter.filter(drmSample())),
        )
    }

    @Test
    fun `a foreign caller with another method name stays, as does a platform class with the hooked name`() {
        assertEquals(
            listOf("android.media.MediaDrm", "com.plugin.Loaded", "com.app.Drm", "com.app.Main"),
            names(loaders.filter(drmSample(stub = "com.plugin.Loaded", stubMethod = "fetch"))),
        )
        assertEquals(
            listOf("android.media.MediaDrm", "java.util.Drm", "com.app.Drm", "com.app.Main"),
            names(loaders.filter(drmSample(stub = "java.util.Drm"))),
        )
    }

    @Test
    fun `app frames with short names touching the window stay`() {
        val input = arrayOf(
            f("a", "b"), // The app's hooked method, R8-renamed.
            f(hookBridge, "invokeOriginalMethod"),
            f("u_qz.f", "intercept"),
            f("k2", "callback"),
            f("m", "c"), // The app's caller: its name clashes with a framework class, but nothing of ours lies beyond.
            f("com.app.Main", "run"),
        )
        assertEquals(listOf("a", "m", "com.app.Main"), names(loaders.filter(input)))
        // Above the window too: a clashing name only joins when a bridge frame lies beyond it.
        val above = arrayOf(f("com.app.Main", "x"), f("m", "c"), f("u_qz.f", "intercept"), f("com.app.Drm", "read"))
        assertEquals(listOf("com.app.Main", "m", "com.app.Drm"), names(loaders.filter(above)))
    }

    @Test
    fun `the loader oracle is asked only next to module frames`() {
        val input = frames("k2", "m", hookBridge, "android.hardware.FruntshQuierniep", "com.app.Main")
        assertSame(input, loaders.filter(input))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the origin cache resolves each name once and fails open`() {
        var calls = 0
        val cache = FrameOrigin.Cache { name ->
            calls++
            when (name) {
                "boom" -> error("lookup failed")
                "odd" -> 42
                else -> FrameOrigin.FRAMEWORK
            }
        }
        assertEquals(FrameOrigin.FRAMEWORK, cache.of("k2"))
        assertEquals(FrameOrigin.FRAMEWORK, cache.of(StringBuilder("k").append('2').toString()))
        assertEquals(1, calls)
        assertEquals(FrameOrigin.OTHER, cache.of("boom"))
        assertEquals(FrameOrigin.OTHER, cache.of("boom"))
        assertEquals(FrameOrigin.OTHER, cache.of("odd"))
        assertEquals(3, calls)
        // Many more names than slots: every answer stays correct, evicted ones are just resolved again.
        repeat(1_000) { assertEquals(FrameOrigin.FRAMEWORK, cache.of("c$it")) }
        assertEquals(FrameOrigin.OTHER, cache.of("boom"))
    }

    @Test
    fun `a stub the framework loader defined goes with the window and the genuine caller stays`() {
        // The stub class is framework-defined, so extend() absorbs it; the same-named caller below is genuine.
        val stubOrigin = FrameOrigin { name ->
            when (name) {
                "k2", "Qz", "a.b.QzStub" -> FrameOrigin.FRAMEWORK
                "com.android.webview.chromium.WebViewContentsClientAdapter", "com.plugin.Client" -> FrameOrigin.FOREIGN
                else -> FrameOrigin.OTHER
            }
        }
        val filter = LiveStackFilter(arrayOf("u_qz."), stubOrigin)
        fun sample(stub: String, caller: String) = arrayOf(
            f("com.app.web.Client", "shouldInterceptRequest"),
            f("u_qz.e", "intercept"),
            f("k2", "callback"),
            f(stub, "shouldInterceptRequest"),
            f(caller, "shouldInterceptRequest"),
            f("com.app.Main", "run"),
        )
        for (caller in listOf(
            "com.android.webview.chromium.WebViewContentsClientAdapter", // FOREIGN: another loader defines it.
            "com.plugin.Client", // FOREIGN plugin class.
            "com.app.web.Other", // Same package as the hooked method.
            "Zq", // Default package (R8).
        )) {
            for (stub in listOf("Qz", "a.b.QzStub")) {
                assertEquals(
                    listOf("com.app.web.Client", caller, "com.app.Main"),
                    names(filter.filter(sample(stub, caller))),
                    "stub $stub, caller $caller",
                )
            }
        }
    }

    @Test
    fun `WebView and Play services frames are never taken as a foreign stub`() {
        val everythingForeign = LiveStackFilter(arrayOf("u_qz."), FrameOrigin { FrameOrigin.FOREIGN })
        for (caller in listOf(
            "com.android.webview.chromium.WebViewContentsClientAdapter",
            "org.chromium.android_webview.AwContentsBackgroundThreadClient",
            "com.google.android.gms.dynamite.X",
        )) {
            val input = arrayOf(
                f("com.app.web.Client", "shouldInterceptRequest"),
                f("u_qz.e", "intercept"),
                f(caller, "shouldInterceptRequest"),
            )
            assertEquals(listOf("com.app.web.Client", caller), names(everythingForeign.filter(input)))
        }
        // A random foreign class in a platform package still goes when it is the stub.
        val stub = arrayOf(
            f("android.media.MediaDrm", "getPropertyByteArray"),
            f("u_qz.e", "intercept"),
            f("android.hardware.FruntshQuierniep", "getPropertyByteArray"),
            f("com.app.Drm", "read"),
        )
        assertEquals(listOf("android.media.MediaDrm", "com.app.Drm"), names(everythingForeign.filter(stub)))
    }
}
