package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.match.HostMatcher
import io.github.hyperprivacy.core.match.HostNames
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.catalog.HttpDnsResolvers
import io.github.hyperprivacy.core.tracker.TrackerCategory
import io.github.hyperprivacy.core.tracker.TrackerData
import io.github.hyperprivacy.core.tracker.Trackers
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Gates on the generated tracker signature database (data/trackers.json → TrackerData.kt). */
class TrackerDataTest {
    @Test
    fun `generated table is up to date with data json`() {
        val source = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "data/trackers.json") }
            .firstOrNull { it.isFile }
        assertNotNull(source, "data/trackers.json not found")
        assertEquals(
            TrackerData.SOURCE_SHA256,
            sha256(source.readBytes()),
            "data/trackers.json changed: run python3 -I scripts/gen-trackers.py",
        )
        assertEquals(
            TrackerData.TABLE_SHA256,
            sha256(TrackerData.PARTS.joinToString("").toByteArray()),
            "TrackerData.kt was edited by hand: run python3 -I scripts/gen-trackers.py",
        )
    }

    @Test
    fun `every record parses and ids are unique`() {
        val records = TrackerData.PARTS.sumOf { part -> part.lineSequence().count { it.startsWith("sdk ") } }
        assertTrue(records > MIN_TRACKERS)
        assertEquals(records, Trackers.all.size, "some records did not parse")
        assertEquals(Trackers.all.size, Trackers.all.map { it.id }.toSet().size)
    }

    @Test
    fun `code prefixes are specific and never shared between sdks`() {
        val owners = Trackers.all.flatMap { sdk -> sdk.codePrefixes.map { it to sdk.id } }.groupBy(
            { it.first },
            { it.second },
        )
        val shared = owners.filterValues { it.size > 1 }
        assertTrue(shared.isEmpty(), "Prefixes owned by several SDKs: $shared")
        val broad = owners.keys.filter { prefix ->
            prefix.trimEnd('.') in BROAD_PACKAGES || prefix.trimEnd('.').count { it == '.' } < 1
        }
        assertTrue(broad.isEmpty(), "Prefixes too broad: $broad")
        // Longest prefix decides nested prefixes, so every prefix must resolve to its own SDK.
        val misrouted = owners.filter { (prefix, ids) -> Trackers.matcher.match(prefix + "X") != ids.single() }
        assertTrue(misrouted.isEmpty(), "Prefixes resolving to another SDK: ${misrouted.keys}")
    }

    @Test
    fun `hosts pass the domain rule checks`() {
        Trackers.all.forEach { sdk ->
            HostMatcher.of((sdk.blockHosts + sdk.keepHosts + sdk.blockUrls.map { it.host }).map { it to Unit })
        }
        val apex = Trackers.all.flatMap { sdk ->
            (sdk.blockHosts + sdk.blockUrls.map { it.host })
                .filter { HostNames.normalize(it) in OVERLY_BROAD_SUFFIXES }
                .map { "${sdk.id}:$it" }
        }
        assertTrue(apex.isEmpty(), "Block hosts would hit shared services: $apex")
        val duplicates = Trackers.all.filter { it.blockHosts.size != it.blockHosts.toSet().size }
        assertTrue(duplicates.isEmpty(), "Duplicate block hosts: ${duplicates.map { it.id }}")
    }

    @Test
    fun `no block host covers any keep host`() {
        // Path-level blockUrls on a kept host are allowed (reporting path on a feature host).
        val blocks = HostMatcher.of(Trackers.all.flatMap { sdk -> sdk.blockHosts.map { it to sdk.id } })
        val offenders = Trackers.all.flatMap { sdk ->
            sdk.keepHosts.mapNotNull { keep -> blocks.find(keep)?.let { "${sdk.id} keeps $keep, blocked by $it" } }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    @Test
    fun `push sdks are detection only`() {
        val offenders = Trackers.all.filter { it.category == TrackerCategory.PUSH && it.blockable }
        assertTrue(offenders.isEmpty(), "PUSH SDKs must not block: ${offenders.map { it.id }}")
    }

    @Test
    fun `no keep host exempts an httpdns resolver`() {
        // Most specific pattern wins, so a kept parent (apilocate.amap.com) leaves a resolver below it
        // blocked; a kept host equal to or below a resolver host would exempt it.
        val resolvers = HttpDnsResolvers.hosts.mapNotNull(HostNames::normalize)
        val addresses = HttpDnsResolvers.addresses.toSet()
        val offenders = Trackers.all.flatMap { sdk ->
            sdk.keepHosts.mapNotNull(HostNames::normalize).filter { keep ->
                keep in addresses || resolvers.any { keep == it || keep.endsWith(".$it") }
            }.map { "${sdk.id} keeps $it" }
        }
        assertTrue(offenders.isEmpty(), "Keep hosts covering HTTPDNS resolvers:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `sdk display names fit a list row`() {
        val tooLong = Trackers.all.flatMap { sdk ->
            listOfNotNull(
                "${sdk.id}: ${sdk.displayName.zh}".takeIf { CopyWidth.zh(sdk.displayName.zh) > ZH_NAME_MAX },
                "${sdk.id}: ${sdk.displayName.en}".takeIf { sdk.displayName.en.length > EN_NAME_MAX },
            )
        }
        assertTrue(tooLong.isEmpty(), "SDK names too long:\n" + tooLong.joinToString("\n"))
    }

    @Test
    fun `sdk display names are clean product names`() {
        val offenders = Trackers.all.flatMap { sdk ->
            listOf(sdk.name.zh to sdk.displayName.zh, sdk.name.en to sdk.displayName.en).mapNotNull { (name, shown) ->
                val withoutAliases = name.replace(PARENTHESISED, "").replace(Regex("\\s+"), " ").trim()
                val vendorWords = sdk.vendor.split(VENDOR_SEPARATORS).filter { it.isNotBlank() }.toSet()
                when {
                    shown.isBlank() -> "empty"
                    shown != shown.trim() || "  " in shown -> "stray spaces"
                    shown.any { it in BRACKETS } -> "brackets"
                    " / " in shown -> "slash alternatives"
                    shown.first() in SEPARATORS || shown.last() in SEPARATORS -> "dangling separator"
                    shown in vendorWords && shown != withoutAliases -> "bare vendor"
                    else -> null
                }?.let { "${sdk.id}: '$shown' ($it)" }
            }
        }
        assertTrue(offenders.isEmpty(), "Display names:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `display names keep the product`() {
        val expected = mapOf(
            "amap-location" to Text("高德定位 SDK", "AMap Location SDK"),
            "amazon-aps" to Text("亚马逊广告", "Amazon Publisher Services"),
            "oppo-push" to Text("OPPO 推送", "OPPO Push"),
            "adsuyi-tianmu" to Text("ADSuyi 天目广告", "ADSuyi Tianmu Ads"),
            "tongdun" to Text("同盾设备指纹", "Tongdun device fingerprint"),
        )
        expected.forEach { (id, name) -> assertEquals(name, assertNotNull(Trackers.byId(id)).displayName, id) }
    }

    @Test
    fun `display name falls back to name without parenthesised aliases`() {
        val table = """
            sdk a 1 ADS
            n 甲 SDK（别名） 广告
            e AMap (Gaode) Location SDK
            v Vendor
            p com.example.a.
            sdk b 1 ADS
            n 乙
            e OPPO / HeyTap Push
            dn 乙推送
            de OPPO Push
            v OPPO
            p com.example.b.
        """.trimIndent()
        val (a, b) = Trackers.parse(table)
        assertEquals(Text("甲 SDK 广告", "AMap Location SDK"), a.displayName)
        assertEquals(Text("乙推送", "OPPO Push"), b.displayName)
        assertEquals(Text("乙", "OPPO / HeyTap Push"), b.name)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MIN_TRACKERS = 100

        /** Display names are switch-row titles and must not truncate on a phone (same as AppsCopyTest titles in en). */
        const val ZH_NAME_MAX = 12.0
        const val EN_NAME_MAX = 28

        val PARENTHESISED = Regex("\\s*[(（][^()（）]*[)）]")
        const val BRACKETS = "()（）[]【】"
        const val SEPARATORS = "/／,，、;；:：-–—&+|"
        val VENDOR_SEPARATORS = Regex("[\\s()（）/／,，]+")

        val BROAD_PACKAGES = setOf(
            "com.tencent", "com.baidu", "com.google", "com.google.android", "com.google.android.gms",
            "com.alibaba", "com.huawei", "com.xiaomi", "com.bytedance", "com.ss.android", "com.kuaishou",
            "com.taobao", "com.alipay", "com.android", "android", "androidx", "java", "javax", "kotlin",
            "com.facebook", "com.amazon", "com.microsoft", "com.squareup", "okhttp3",
        )

        val OVERLY_BROAD_SUFFIXES = setOf(
            "qq.com", "baidu.com", "alibaba.com", "aliyun.com", "aliyuncs.com", "google.com", "googleapis.com",
            "huawei.com", "xiaomi.com", "mi.com", "miui.com", "bytedance.com", "douyin.com", "kuaishou.com",
            "tencent.com", "163.com", "taobao.com", "alipay.com", "facebook.com", "amazon.com", "microsoft.com",
            "log.aliyuncs.com", "cls.tencentcs.com", "com", "net", "cn", "io",
        )
    }
}
