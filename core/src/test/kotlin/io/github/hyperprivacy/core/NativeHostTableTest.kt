package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.match.NativeHostTable
import io.github.hyperprivacy.core.match.NetworkMatchers
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.UrlPrefix
import io.github.hyperprivacy.core.tracker.TrackerRules
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Java side of the native resolver: what reaches native code, and in which format. */
class NativeHostTableTest {
    @Test
    fun golden() {
        // The same bytes are parsed by app/src/test/cpp/host_table_test.cc (MatchesKotlinEncoder).
        val expected = byteArrayOf(
            'H'.code.toByte(), 'P'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 2, 0, 0, 0,
            0, 16, *"keep.ads.example".toByteArray(), 1, 11, *"ads.example".toByteArray(),
        )
        assertContentEquals(
            expected,
            NativeHostTable.encode(listOf("keep.ads.example" to false, "ads.example" to true)),
        )
    }

    @Test
    fun `host patterns, keep hosts included, IP literals and URL rules left out`() {
        val rules = listOf(
            rule("d", RulePayload.Domains(listOf("ads.example", "203.0.113.7", "2001:db8::7"))),
            rule("u", RulePayload.Urls(listOf(UrlPrefix("api.example", "/log/")))),
            rule("n", RulePayload.NativeResolver),
        )
        val matchers = NetworkMatchers.of(rules, keepHosts = listOf("keep.ads.example")) { true }
        val patterns = HashMap<String, Boolean>()
        matchers.forEachHostPattern { pattern, blocked -> patterns[pattern] = blocked }
        assertEquals(true, patterns["ads.example"])
        assertEquals(false, patterns["keep.ads.example"])
        assertTrue("api.example" !in patterns, "URL rules are not host-level")
        val decoded = decode(NativeHostTable.encode(matchers))
        assertEquals(mapOf("ads.example" to true, "keep.ads.example" to false), decoded)
    }

    @Test
    fun `equal rule sets encode to equal bytes`() {
        val hosts = (1..200).map { "host$it.example" }
        val forward = NetworkMatchers.of(listOf(rule("a", RulePayload.Domains(hosts)))) { true }
        val backward = NetworkMatchers.of(listOf(rule("a", RulePayload.Domains(hosts.reversed())))) { true }
        assertContentEquals(NativeHostTable.encode(forward), NativeHostTable.encode(backward))
    }

    @Test
    fun `nothing to block encodes to no table`() {
        assertEquals(0, NativeHostTable.encode(NetworkMatchers.EMPTY).size)
        assertEquals(0, NativeHostTable.encode(listOf("keep.example" to false)).size)
        val off = NetworkMatchers.of(listOf(rule("d", RulePayload.Domains(listOf("ads.example"))))) { false }
        assertEquals(0, NativeHostTable.encode(off).size)
    }

    @Test
    fun `unusable patterns are skipped`() {
        val long = "a".repeat(254)
        assertEquals(
            mapOf("ok.example" to true),
            decode(NativeHostTable.encode(listOf(long to true, "ok.example" to true))),
        )
    }

    @Test
    fun `native rule is a protected-app rule that adds no hosts`() {
        val rule = assertNotNull(Catalog.protectedAppRules.singleOrNull { it.id == "native.dns" })
        assertEquals(RulePayload.NativeResolver, rule.payload)
        assertEquals(RuleKind.DOMAIN, rule.kind)
        assertTrue(rule.isProtectedAppRule && rule.preventive && !rule.defaultOn)
        val app = TrackerRules.appRules("com.example.app").single { it.id == "native.dns" }
        val enabled = NetworkMatchers.of(listOf(app)) { true }
        assertTrue(enabled.hosts.isEmpty && enabled.urls.isEmpty)
        assertEquals(listOf(app), TrackerRules.expandKnown(listOf(app)) { true })
    }

    private fun decode(table: ByteArray): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        if (table.isEmpty()) return out
        assertEquals("HPT1", String(table, 0, HEADER_MAGIC, Charsets.US_ASCII))
        val count = (0 until HEADER_MAGIC).sumOf { (table[HEADER_MAGIC + it].toInt() and BYTE) shl (BITS * it) }
        var pos = HEADER
        repeat(count) {
            val blocked = table[pos].toInt() == 1
            val length = table[pos + 1].toInt() and BYTE
            out[String(table, pos + 2, length, Charsets.US_ASCII)] = blocked
            pos += 2 + length
        }
        assertEquals(table.size, pos)
        return out
    }

    private fun rule(id: String, payload: RulePayload) = Rule(
        id = id,
        packages = setOf("com.example.app"),
        title = Text("测试", "Test"),
        summary = Text("测试", "Test"),
        payload = payload,
        category = Category.ANALYTICS,
        evidence = "test",
    )

    private companion object {
        const val HEADER_MAGIC = 4
        const val HEADER = 8
        const val BYTE = 0xFF
        const val BITS = 8
    }
}
