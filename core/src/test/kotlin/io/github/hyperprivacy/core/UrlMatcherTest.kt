package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.match.HostMatcher
import io.github.hyperprivacy.core.match.UrlMatcher
import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.match.UrlPattern
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlMatcherTest {
    private val matcher = UrlMatcher.of(
        listOf(
            UrlPattern("api.example.com", "/v1/track") to "track",
            UrlPattern("api.example.com", "/v1/track/deep") to "deep",
            UrlPattern("Telemetry.Example.COM.") to "host",
            UrlPattern("example.com", "/stat/") to "suffix-path",
        ),
    )

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://api.example.com/v1/track",
            "https://API.EXAMPLE.COM/v1/track?x=1",
            "http://api.example.com:8080/v1/track/event#frag",
            "https://user:pw@api.example.com/v1/tracking",
            "https://cdn.api.example.com/v1/track/x",
            "https://api.example.com/v1/%74rack",
            "  https://api.example.com/v1/track  ",
        ],
    )
    fun `matches host suffix and path prefix`(url: String) {
        assertEquals("track", matcher.find(url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://api.example.com/v1/TRACK",
            "https://api.example.com/v2/track",
            "https://api.example.com/",
            "https://api.example.com",
            "https://api.example.com/v1?p=/v1/track",
            "https://api.example.com/x#/v1/track",
            "https://badapi.example.com/v1/track",
            "https://api.example.com.evil/v1/track",
        ],
    )
    fun `path is case-sensitive and query or fragment never match`(url: String) {
        assertNull(matcher.find(url))
    }

    @Test
    fun `longest path prefix wins`() {
        assertEquals("deep", matcher.find("https://api.example.com/v1/track/deep/1"))
    }

    @Test
    fun `host-only pattern blocks every path`() {
        assertEquals("host", matcher.find("https://telemetry.example.com/"))
        assertEquals("host", matcher.find("wss://a.telemetry.example.com/any/path?q"))
        assertEquals("host", matcher.find("telemetry.example.com", null))
    }

    @Test
    fun `more specific host is tried first, then parent suffixes`() {
        // api.example.com has only path rules; a non-matching path falls through to example.com.
        assertEquals("suffix-path", matcher.find("https://api.example.com/stat/x"))
        assertNull(matcher.find("https://api.example.com/stats"))
    }

    @Test
    fun `find by host and decoded path`() {
        assertEquals("track", matcher.find("API.example.com", "/v1/track/x"))
        assertNull(matcher.find("api.example.com", ""))
        assertNull(matcher.find(null, "/v1/track"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not a url", "/v1/track", "api.example.com/v1/track", "://api.example.com/v1/track"])
    fun `unparsable urls never match`(url: String) {
        assertNull(matcher.find(url))
    }

    @Test
    fun `null url never matches`() {
        assertNull(matcher.find(null))
    }

    @Test
    fun `first value wins on duplicates`() {
        val dup = UrlMatcher.of(listOf(UrlPattern("a.com", "/x") to 1, UrlPattern("A.com", "/x") to 2))
        assertEquals(1, dup.find("https://a.com/x"))
    }

    @Test
    fun `invalid patterns are rejected`() {
        assertFailsWith<IllegalArgumentException> { UrlPattern("a.com", "x") }
        assertFailsWith<IllegalArgumentException> { UrlMatcher.of(listOf(UrlPattern("a..com") to 1)) }
    }

    @Test
    fun `empty matcher matches nothing`() {
        assertTrue(UrlMatcher.empty<String>().isEmpty)
        assertNull(UrlMatcher.of(emptyList<Pair<UrlPattern, Int>>()).find("https://a.com/"))
    }

    @Test
    fun `large tables resolve every entry`() {
        val hosts = (0 until 500).map { "h$it.example.org" }
        val urls = UrlMatcher.of(hosts.map { UrlPattern(it, "/p") to it })
        val plain = HostMatcher.of(hosts.map { it to it })
        hosts.forEach { host ->
            assertEquals(host, urls.find("https://x.$host/p/q"))
            assertNull(urls.find("https://$host/q"))
            assertEquals(host, plain.find("x.$host"))
        }
        assertNull(plain.find("h500.example.org"))
    }
}

class UrlPartsTest {
    @Test
    fun `parses host and decoded path`() {
        assertEquals(UrlParts("a.example.com", "/x y/ü"), UrlParts.parse("HTTPS://A.Example.com:443/x%20y/%C3%BC?q=1"))
        assertEquals(UrlParts("a.com", "/"), UrlParts.parse("http://a.com"))
        assertEquals(UrlParts("a.com", "/"), UrlParts.parse("http://a.com?x"))
        assertEquals(UrlParts("[::1]", "/p"), UrlParts.parse("http://[::1]:80/p"))
    }

    @Test
    fun `malformed escapes and plus signs are kept`() {
        assertEquals("/a%zz/b+c%", UrlParts.decodePath("/a%zz/b+c%"))
        assertEquals("/a%2", UrlParts.decodePath("/a%2"))
        assertEquals("/a/b", UrlParts.decodePath("/a%2Fb"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "mailto:a@example.org", "http://", "http:///path", "http://[::1/p", "ht tp://a.com/"])
    fun `rejects urls without a usable host`(url: String) {
        assertNull(UrlParts.parse(url))
    }
}
