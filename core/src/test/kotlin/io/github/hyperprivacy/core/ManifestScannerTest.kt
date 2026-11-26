package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.tracker.ManifestAnalyzer
import io.github.hyperprivacy.core.tracker.ManifestInfo
import io.github.hyperprivacy.core.tracker.ManifestInfo.Kind
import io.github.hyperprivacy.core.tracker.ManifestScanner
import io.github.hyperprivacy.core.tracker.OriginHints
import io.github.hyperprivacy.core.tracker.TrackerOrigin
import io.github.hyperprivacy.core.tracker.TrackerProtection
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestScannerTest {
    @Test
    fun `binary manifest components are read`() {
        val xml = Axml()
        xml.start("manifest", "package" to (PKG to false))
        xml.start("application", "name" to (".App" to true))
        xml.start("activity", "name" to (".Main" to true))
        xml.start("intent-filter")
        xml.leaf("action", "name" to ("android.intent.action.MAIN" to true))
        xml.leaf("category", "name" to ("android.intent.category.LAUNCHER" to true))
        xml.end()
        xml.end()
        xml.leaf("activity", "name" to ("a.b.c.activity.TTRewardVideoActivity" to true))
        xml.leaf("provider", "name" to ("a.b.c.TTFileProvider" to true))
        xml.leaf("activity-alias", "targetActivity" to ("Settings" to true))
        xml.leaf("service", "name" to ("com.qq.e.comm.DownloadService" to true))
        xml.end()
        xml.end()
        val info = ManifestScanner.parse(xml.bytes())
        assertEquals(PKG, info?.packageName)
        assertEquals("$PKG.App", info?.applicationClass)
        assertEquals(
            listOf(
                ManifestInfo.Component(Kind.ACTIVITY, "$PKG.Main", launcher = true),
                ManifestInfo.Component(Kind.ACTIVITY, "a.b.c.activity.TTRewardVideoActivity"),
                ManifestInfo.Component(Kind.PROVIDER, "a.b.c.TTFileProvider"),
                ManifestInfo.Component(Kind.ACTIVITY, "$PKG.Settings"),
                ManifestInfo.Component(Kind.SERVICE, "com.qq.e.comm.DownloadService"),
            ),
            info?.components,
        )
    }

    @Test
    fun `malformed input never throws`() {
        assertNull(ManifestScanner.parse(ByteArray(0)))
        assertNull(ManifestScanner.parse("<manifest/>".toByteArray()))
        val good = Axml().apply {
            start("manifest", "package" to (PKG to false))
            end()
        }.bytes()
        for (cut in good.indices) ManifestScanner.parse(good.copyOf(cut))
        val random = java.util.Random(SEED)
        repeat(FUZZ_ROUNDS) {
            val bytes = good.copyOf()
            bytes[random.nextInt(bytes.size)] = random.nextInt().toByte()
            ManifestScanner.parse(bytes)
        }
    }

    @Test
    fun `anchors map relocated sdks and own code stays safe`() {
        val info = ManifestInfo(
            packageName = PKG,
            applicationClass = "com.example.core.App",
            components = listOf(
                ManifestInfo.Component(Kind.ACTIVITY, "com.example.ui.Main", launcher = true),
                ManifestInfo.Component(Kind.ACTIVITY, "a.b.c.activity.TTRewardVideoActivity"),
                ManifestInfo.Component(Kind.ACTIVITY, "com.qq.e.ads.ADActivity"),
                // Relocated into the app's own root: never mapped (it would cover the app).
                ManifestInfo.Component(Kind.ACTIVITY, "com.example.ui.activity.TTFullScreenVideoActivity"),
                ManifestInfo.Component(Kind.ACTIVITY, "x.y.ads.RewardAdActivity"),
                ManifestInfo.Component(Kind.ACTIVITY, "com.example.ui.SplashAdActivity"),
                ManifestInfo.Component(Kind.ACTIVITY, "x.y.AddressActivity"),
            ),
        )
        val hints = ManifestAnalyzer.analyze(info)
        assertEquals(mapOf("a.b.c." to "bytedance-csj-pangle"), hints.relocated)
        assertTrue("bytedance-csj-pangle" in hints.detected && "tencent-gdt" in hints.detected)
        assertEquals(setOf("$PKG.", "com.example.core.", "com.example.ui."), hints.own)
        assertEquals(listOf(OriginHints.Suspect("x.y.ads.", listOf("RewardAdActivity"))), hints.suspects)
    }

    @Test
    fun `ad-looking names`() {
        listOf("RewardAdActivity", "ADActivity", "AdWebViewActivity", "InterstitialActivity", "SplashAdView")
            .forEach { assertTrue(ManifestAnalyzer.looksLikeAd(it), it) }
        listOf("AddressActivity", "AdminActivity", "ReadActivity", "HeadService", "UploadProvider", "MainActivity")
            .forEach { assertFalse(ManifestAnalyzer.looksLikeAd(it), it) }
    }

    @Test
    fun `origin hints round-trip and gate the keys`() {
        val hints = OriginHints(
            relocated = mapOf("a.b.c." to "bytedance-csj-pangle"),
            own = setOf("$PKG."),
            suspects = listOf(OriginHints.Suspect("x.y.ads.", listOf("RewardAdActivity", "AdService"))),
        )
        assertEquals(hints, OriginHints.decode(hints.encode()))
        assertEquals(OriginHints.EMPTY, OriginHints.decode("junk,r:only,s:bad"))
        assertTrue(TrackerProtection.affects(TrackerOrigin.originKey(PKG), PKG))
        assertTrue(TrackerProtection.affects(TrackerOrigin.suspectKey(PKG, "x.y.ads."), PKG))
        assertFalse(TrackerProtection.affects(TrackerOrigin.originKey("other.app"), PKG))
        val raw = mapOf(TrackerOrigin.suspectKey(PKG, "x.y.ads.") to true)
        assertTrue(TrackerOrigin.isSuspectBlocked(raw, PKG, "x.y.ads."))
        assertFalse(TrackerOrigin.isSuspectBlocked(raw, PKG, "z.ads."))
    }

    /** Minimal AXML writer: UTF-16 string pool, resource map for `name` / `targetActivity`, elements. */
    private class Axml {
        private val strings = arrayListOf("name", "targetActivity", ANDROID_NS)
        private val body = ByteArrayOutputStream()
        private val open = ArrayList<String>()

        private fun index(text: String): Int = strings.indexOf(text).takeIf { it >= 0 } ?: strings.run {
            add(text)
            size - 1
        }

        /** [attrs]: name to (value to inAndroidNamespace). */
        fun start(name: String, vararg attrs: Pair<String, Pair<String, Boolean>>) {
            open += name
            val chunk = buffer(ELEMENT_HEADER + ELEMENT_EXT + ATTR * attrs.size)
            chunk.putShort(0x0102).putShort(ELEMENT_HEADER.toShort()).putInt(chunk.capacity()).putInt(1).putInt(-1)
            chunk.putInt(-1).putInt(index(name)).putShort(ELEMENT_EXT.toShort()).putShort(ATTR.toShort())
            chunk.putShort(attrs.size.toShort()).putShort(0).putShort(0).putShort(0)
            for ((attr, value) in attrs) {
                val (text, android) = value
                val valueIndex = index(text)
                chunk.putInt(if (android) index(ANDROID_NS) else -1).putInt(index(attr)).putInt(valueIndex)
                chunk.putShort(TYPED_SIZE).put(0).put(TYPE_STRING).putInt(valueIndex)
            }
            body.write(chunk.array())
        }

        fun leaf(name: String, vararg attrs: Pair<String, Pair<String, Boolean>>) {
            start(name, *attrs)
            end()
        }

        fun end() {
            val name = open.removeAt(open.size - 1)
            val chunk = buffer(ELEMENT_HEADER + END_EXT)
            chunk.putShort(0x0103).putShort(ELEMENT_HEADER.toShort()).putInt(chunk.capacity()).putInt(1).putInt(-1)
            chunk.putInt(-1).putInt(index(name))
            body.write(chunk.array())
        }

        fun bytes(): ByteArray {
            val data = ByteArrayOutputStream()
            strings.forEach { text ->
                val b = buffer(2 + text.length * 2 + 2)
                b.putShort(text.length.toShort())
                text.forEach { b.putChar(it) }
                b.putShort(0)
                data.write(b.array())
            }
            while (data.size() % 4 != 0) data.write(0)
            val poolSize = POOL_HEADER + strings.size * 4 + data.size()
            val pool = buffer(POOL_HEADER + strings.size * 4)
            pool.putShort(0x0001).putShort(POOL_HEADER.toShort()).putInt(poolSize).putInt(strings.size).putInt(0)
            pool.putInt(0).putInt(POOL_HEADER + strings.size * 4).putInt(0)
            var offset = 0
            strings.forEach {
                pool.putInt(offset)
                offset += 2 + it.length * 2 + 2
            }
            val map = buffer(CHUNK + 8)
            map.putShort(0x0180).putShort(CHUNK.toShort()).putInt(CHUNK + 8).putInt(NAME_ID).putInt(TARGET_ID)
            val total = CHUNK + poolSize + map.capacity() + body.size()
            val out = ByteArrayOutputStream()
            out.write(buffer(CHUNK).putShort(0x0003).putShort(CHUNK.toShort()).putInt(total).array())
            out.write(pool.array())
            out.write(data.toByteArray())
            out.write(map.array())
            out.write(body.toByteArray())
            return out.toByteArray()
        }

        private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        private companion object {
            const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
            const val CHUNK = 8
            const val POOL_HEADER = 28
            const val ELEMENT_HEADER = 16
            const val ELEMENT_EXT = 20
            const val END_EXT = 8
            const val ATTR = 20
            const val TYPED_SIZE: Short = 8
            const val TYPE_STRING: Byte = 3
            const val NAME_ID = 0x01010003
            const val TARGET_ID = 0x01010202
        }
    }

    private companion object {
        const val PKG = "com.example.app"
        const val SEED = 7L
        const val FUZZ_ROUNDS = 2_000
    }
}
