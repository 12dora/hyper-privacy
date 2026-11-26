package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.tracker.CodePrefixMatcher
import io.github.hyperprivacy.core.tracker.OriginMatcher
import io.github.hyperprivacy.core.tracker.Trackers
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginMatcherTest {
    private val adPrefixes = Trackers.all.filter { it.originBlockable }.flatMap { sdk ->
        sdk.codePrefixes.map { it to sdk.id }
    }
    private val matcher = OriginMatcher(adPrefixes + ("a.b.c." to "bytedance-csj-pangle"), listOf(APP))

    @Test
    fun `class names match like descriptors`() {
        val trie = CodePrefixMatcher(listOf("com.qq.e." to "gdt", "com.qq.e.comm.x." to "deeper"))
        assertEquals("gdt", trie.ids[trie.matchName("com.qq.e.ads.Banner")])
        assertEquals("deeper", trie.ids[trie.matchName("com.qq.e.comm.x.Y")])
        assertEquals(-1, trie.matchName("com.qq.f.Other"))
        assertEquals(-1, trie.matchName("com.qq.e"))
        assertEquals(-1, trie.matchName(""))
        assertEquals(-1, trie.matchName("com.qq.é.X"))
    }

    @Test
    fun `classify separates sdk, app, platform and unknown code`() {
        assertEquals("tencent-gdt", matcher.sdkId(matcher.classify("com.qq.e.adsim.Loader")))
        assertEquals("bytedance-csj-pangle", matcher.sdkId(matcher.classify("a.b.c.activity.TTRewardVideoActivity")))
        assertEquals(OriginMatcher.OWN, matcher.classify("$APP.MainActivity"))
        assertEquals(OriginMatcher.PLATFORM, matcher.classify("java.net.URL"))
        assertEquals(OriginMatcher.PLATFORM, matcher.classify("io.github.hyperprivacy.xposed.FailOpenHooker"))
        // An SDK nested in a platform namespace is still the SDK (longest prefix).
        assertEquals("tencent-gdt", matcher.sdkId(matcher.classify("com.android.gdt.qone.X")))
        assertEquals(OriginMatcher.UNKNOWN, matcher.classify("com.squareup.picasso.Picasso"))
    }

    @Test
    fun `the innermost decisive frame wins`() {
        // SDK code reached through an executor: the SDK runnable is the first non-platform frame.
        val async = stack("java.net.URL", "com.kwad.sdk.adsim.Fetch\$1", "java.util.concurrent.ThreadPoolExecutor")
        assertEquals("kuaishou-ads", matcher.sdkId(matcher.attribute(async)))
        // App code called back by an SDK asks for itself.
        val callback = stack("java.net.URL", "$APP.Level", "com.qq.e.adsim.Callbacks", "android.os.Handler")
        assertEquals(OriginMatcher.UNKNOWN, matcher.attribute(callback))
        // Third-party libraries between the SDK and the sink do not hide it.
        val library = stack("okhttp3.OkHttpClient", "com.squareup.lib.X", "com.qq.e.adsim.Net", "$APP.Main")
        assertEquals("tencent-gdt", matcher.sdkId(matcher.attribute(library)))
        assertEquals(OriginMatcher.UNKNOWN, matcher.attribute(stack("java.net.URL", "$APP.Main")))
    }

    @Test
    fun `resolver claims only unknown frames`() {
        val asked = ArrayList<String>()
        val plugin = matcher.indexOf("kuaishou-ads")
        val frames = stack("java.net.URL", "plugin.Impl", "$APP.Main")
        val result = matcher.attribute(frames) { name ->
            asked += name
            if (name == "plugin.Impl") plugin else OriginMatcher.UNKNOWN
        }
        assertEquals("kuaishou-ads", matcher.sdkId(result))
        assertEquals(listOf("plugin.Impl"), asked)
    }

    /**
     * Micro-benchmark of the per-request cost (expected: tens of ns per class name, about 2-3 µs
     * per 40-frame stack on a desktop JVM; a phone is several times slower but still far below the
     * cost of capturing the stack). Bounds are loose so a slow CI machine never flakes, and the
     * lookups must not allocate at all.
     */
    @Test
    fun `lookups are cheap and allocation-free`() {
        val frames = realisticStack()
        repeat(WARMUP) { matcher.attribute(frames) }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val allocatedBefore = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        var sink = 0
        repeat(ITERATIONS) { sink += matcher.attribute(frames) }
        val perStack = (System.nanoTime() - start) / ITERATIONS
        val allocated = bean.getThreadAllocatedBytes(thread) - allocatedBefore
        println("OriginMatcher: ${perStack}ns per ${frames.size}-frame stack, $allocated bytes allocated (sink $sink)")
        assertTrue(perStack < MAX_NS_PER_STACK, "attribute took ${perStack}ns")
        assertTrue(allocated < MAX_ALLOCATED_BYTES, "attribute allocated $allocated bytes")
    }

    private fun realisticStack(): Array<StackTraceElement> {
        val platform = listOf(
            "io.github.hyperprivacy.xposed.FailOpenHooker", "LSPHooker_", "java.net.URL",
            "com.android.okhttp.internal.huc.HttpURLConnectionImpl", "android.os.Handler", "android.os.Looper",
            "android.app.ActivityThread", "java.lang.reflect.Method", "com.android.internal.os.ZygoteInit",
        )
        val names = (0 until FRAMES - 1).map { platform[it % platform.size] } + "$APP.Main"
        return stack(*names.toTypedArray())
    }

    private fun stack(vararg classes: String) =
        classes.map { StackTraceElement(it, "m", null, -1) }.toTypedArray()

    private companion object {
        const val APP = "io.example.app"
        const val FRAMES = 40
        const val WARMUP = 20_000
        const val ITERATIONS = 100_000
        const val MAX_NS_PER_STACK = 50_000L
        const val MAX_ALLOCATED_BYTES = 64 * 1024L
    }
}
