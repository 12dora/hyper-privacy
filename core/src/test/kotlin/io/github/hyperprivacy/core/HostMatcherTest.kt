package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.match.HostMatcher
import io.github.hyperprivacy.core.match.HostNames
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostMatcherTest {
    private val matcher = HostMatcher.of(
        listOf(
            "tracking.miui.com" to "a",
            "Mistat.Xiaomi.COM." to "b",
            "deep.mistat.xiaomi.com" to "c",
        ),
    )

    @ParameterizedTest
    @ValueSource(
        strings = [
            "tracking.miui.com",
            "TRACKING.MIUI.COM",
            "tracking.miui.com.",
            "x.y.tracking.miui.com",
            " tracking.miui.com ",
        ],
    )
    fun `matches exact and subdomains case-insensitively`(host: String) {
        assertEquals("a", matcher.find(host))
    }

    @ParameterizedTest
    @ValueSource(strings = ["miui.com", "badtracking.miui.com", "tracking.miui.com.cn", "tracking-miui.com", "com"])
    fun `does not match siblings, parents or lookalikes`(host: String) {
        assertNull(matcher.find(host))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", ".", "..", ".tracking.miui.com", "tracking..miui.com", "tracking.miui.com.."])
    fun `rejects malformed hosts`(host: String) {
        assertFalse(matcher.matches(host))
    }

    @Test
    fun `null host never matches`() {
        assertNull(matcher.find(null))
    }

    @Test
    fun `most specific pattern wins`() {
        assertEquals("c", matcher.find("x.deep.mistat.xiaomi.com"))
        assertEquals("b", matcher.find("other.mistat.xiaomi.com"))
    }

    @Test
    fun `first value wins on duplicate patterns`() {
        val dup = HostMatcher.of(listOf("a.com" to 1, "A.com." to 2))
        assertEquals(1, dup.find("a.com"))
    }

    @Test
    fun `lowercasing ignores default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val m = HostMatcher.of(listOf("ads.mi.com" to true))
            assertTrue(m.matches("ADS.MI.COM"))
            assertTrue(m.matches("IMG.ADS.MI.COM"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `unicode hosts are compared in punycode form`() {
        val m = HostMatcher.of(listOf("例子.测试" to "idn"))
        assertEquals("idn", m.find("xn--fsqu00a.xn--0zwm56d"))
        assertEquals("idn", m.find("sub.例子.测试"))
        assertEquals("xn--fsqu00a.xn--0zwm56d", HostNames.normalize("例子.测试"))
    }

    @Test
    fun `invalid patterns are rejected at build time`() {
        assertFailsWith<IllegalArgumentException> { HostMatcher.of(listOf("" to 1)) }
        assertFailsWith<IllegalArgumentException> { HostMatcher.of(listOf("a..b" to 1)) }
        assertFailsWith<IllegalArgumentException> { HostMatcher.of(listOf(".a.com" to 1)) }
    }

    @Test
    fun `empty matcher matches nothing`() {
        val empty = HostMatcher.of(emptyList<Pair<String, Int>>())
        assertTrue(empty.isEmpty)
        assertFalse(empty.matches("a.com"))
        assertFalse(HostMatcher.empty<String>().matches("a.com"))
    }
}
