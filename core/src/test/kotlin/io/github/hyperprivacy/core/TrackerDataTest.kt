package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.match.HostMatcher
import io.github.hyperprivacy.core.match.HostNames
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
    fun `sdk display names stay short`() {
        val tooLong = Trackers.all.flatMap { sdk ->
            listOfNotNull(
                "${sdk.id}: ${sdk.displayName.zh}".takeIf { CopyWidth.zh(sdk.displayName.zh) > ZH_NAME_MAX },
                "${sdk.id}: ${sdk.displayName.en}".takeIf { sdk.displayName.en.length > EN_NAME_MAX },
            )
        }
        assertTrue(tooLong.isEmpty(), "SDK names too long:\n" + tooLong.joinToString("\n"))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MIN_TRACKERS = 100

        /** Display names may wrap to a second line in the app detail; these keep them within two. */
        const val ZH_NAME_MAX = 18.0
        const val EN_NAME_MAX = 48

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
