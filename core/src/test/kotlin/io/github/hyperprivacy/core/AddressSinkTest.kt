package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.match.HostMatcher
import io.github.hyperprivacy.core.match.NetworkMatchers
import io.github.hyperprivacy.core.match.UrlMatcher
import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.match.UrlPattern
import io.github.hyperprivacy.core.net.AddressBook
import io.github.hyperprivacy.core.net.IpLiterals
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Category
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.rule.RulePayload
import io.github.hyperprivacy.core.rule.Text
import io.github.hyperprivacy.core.rule.catalog.HttpDnsResolvers
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressSinkTest {
    private fun ip(text: String): InetAddress = requireNotNull(IpLiterals.parseIpv4(text))

    @Test
    fun `ipv4 literals parse without a resolver`() {
        assertEquals(listOf<Byte>(-53, 107, 1, 1), ip("203.107.1.1").address.toList())
        assertTrue(IpLiterals.isIpv4("0.0.0.0"))
        assertTrue(IpLiterals.isIpLiteral("[2001:db8::1]"))
        assertTrue(IpLiterals.isIpLiteral("2001:db8::1"))
        assertFalse(IpLiterals.isIpLiteral("dns.google"))
        assertFalse(IpLiterals.isIpLiteral("1.example.com"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "1..2.3", "1.2.3.", "a.b.c.d", "1234.1.1.1"],
    )
    fun `malformed ipv4 literals are rejected`(text: String) {
        assertNull(IpLiterals.parseIpv4(text))
        assertFalse(IpLiterals.isIpv4(text))
    }

    @Test
    fun `host headers yield the canonical host`() {
        assertEquals("tracking.example.com", UrlParts.hostOfHeader(" Tracking.Example.com:8080 "))
        assertEquals("tracking.example.com", UrlParts.hostOfHeader("tracking.example.com"))
        assertEquals("[2001:db8::1]", UrlParts.hostOfHeader("[2001:db8::1]:443"))
        assertNull(UrlParts.hostOfHeader(""))
        assertNull(UrlParts.hostOfHeader(null))
    }

    @Test
    fun `address book maps an address to its single host`() {
        val book = AddressBook()
        book.record(ip("1.2.3.4"), "Tracking.Example.com.")
        assertEquals("tracking.example.com", book.hostOf(ip("1.2.3.4")))
        assertEquals(ip("1.2.3.4"), book.addressOf("tracking.example.com"))
        assertNull(book.hostOf(ip("1.2.3.5")))
    }

    @Test
    fun `an address shared by two hosts is never attributed`() {
        val book = AddressBook()
        book.record(ip("1.2.3.4"), "tracking.example.com")
        book.record(ip("1.2.3.4"), "tracking.example.com")
        assertEquals("tracking.example.com", book.hostOf(ip("1.2.3.4")))
        book.record(ip("1.2.3.4"), "shop.example.com")
        assertNull(book.hostOf(ip("1.2.3.4")))
        book.record(ip("1.2.3.4"), "tracking.example.com")
        assertNull(book.hostOf(ip("1.2.3.4")), "shared is sticky")
    }

    @Test
    fun `local addresses and literal hosts are not recorded`() {
        val book = AddressBook()
        // Private ranges built from bytes so no private-IP literal sits in the repository.
        val local = listOf("127.0.0.1", "169.254.1.1", "0.0.0.0", "224.0.0.1").map(::ip) +
            listOf(byteArrayOf(10, 0, 0, 1), byteArrayOf(-64, -88, 1, 1)).map(InetAddress::getByAddress)
        local.forEach {
            book.record(it, "tracking.example.com")
            assertNull(book.hostOf(it), it.toString())
        }
        book.record(ip("1.2.3.4"), "1.2.3.4")
        assertNull(book.hostOf(ip("1.2.3.4")))
    }

    @Test
    fun `address book stays bounded`() {
        val book = AddressBook(capacity = 4)
        (1..10).forEach { book.record(ip("1.1.1.$it"), "h$it.example.com") }
        assertEquals("h10.example.com", book.hostOf(ip("1.1.1.10")))
        assertNull(book.hostOf(ip("1.1.1.1")))
    }

    @Test
    fun `connects match listed literals and learned hosts of blocked domains`() {
        val rule = Rule(
            id = "r",
            packages = setOf("com.example.a"),
            title = Text("标题", "Title"),
            summary = Text("摘要", "Summary"),
            payload = RulePayload.Domains(listOf("tracking.example.com", "203.107.1.1")),
            category = Category.ANALYTICS,
            evidence = "test",
        )
        val matchers = NetworkMatchers.of(listOf(rule)) { true }
        val book = AddressBook()
        book.record(ip("1.2.3.4"), "a.tracking.example.com")
        book.record(ip("5.6.7.8"), "shop.example.com")
        assertEquals("r", matchers.findConnect(ip("203.107.1.1"), 443, book))
        assertNull(matchers.findConnect(ip("203.107.1.1"), 53, book), "DNS port stays open")
        assertEquals("r", matchers.findConnect(ip("1.2.3.4"), 80, book))
        assertNull(matchers.findConnect(ip("5.6.7.8"), 443, book))
        assertNull(NetworkMatchers.of(listOf(rule)) { false }.findConnect(ip("203.107.1.1"), 443, book))
    }

    @Test
    fun `httpdns rule lists valid resolvers and every ip`() {
        val rule = assertNotNull(Catalog.byId("httpdns.resolvers"))
        val suffixes = (rule.payload as RulePayload.Domains).suffixes
        HostMatcher.of(suffixes.map { it to Unit })
        HttpDnsResolvers.addresses.forEach { assertNotNull(IpLiterals.parseIpv4(it), it) }
        val matchers = NetworkMatchers.of(listOf(rule)) { true }
        assertEquals(HttpDnsResolvers.addresses.size, matchers.addresses.size)
        listOf(
            "resolvers-cn.httpdns.aliyuncs.com",
            "resolvers-sg.httpdns.aliyuncs.com",
            "dns.alidns.com",
            "dns.google",
            "doh.pub",
            "httpsdns.baidu.com",
            "httpdns.apilocate.amap.com",
            "dispatch.mgslb.com",
        ).forEach { assertEquals(rule.id, matchers.hosts.find(it), it) }
        listOf("apilocate.amap.com", "www.baidu.com", "aliyuncs.com", "google.com", "mi.com").forEach {
            assertNull(matchers.hosts.find(it), it)
        }
    }

    @Test
    fun `no rule ever matches the push resolver`() {
        val urls = UrlMatcher.of(
            Catalog.rules.flatMap { rule ->
                when (val payload = rule.payload) {
                    is RulePayload.Domains -> payload.suffixes.map { UrlPattern(it) to rule.id }
                    is RulePayload.Urls -> payload.prefixes.map { UrlPattern(it.host, it.pathPrefix) to rule.id }
                    else -> emptyList()
                }
            },
        )
        val host = HttpDnsResolvers.ALWAYS_ALLOWED
        listOf("/", "/gslb/", "/gslb/gslb/getbucket.asp").forEach { path -> assertNull(urls.find(host, path), path) }
    }

    @Test
    fun `app-wide rules target exactly the app processes other in-process rules inject into`() {
        val injected = Catalog.rules.filter { it.kind != RuleKind.FIREWALL && !it.preventive }
            .flatMap { it.packages }
            .toSet() - Catalog.SYSTEM_SCOPE
        val appWide = Catalog.rules.filter { it.preventive }
        assertEquals(setOf("mediadrm.device-id", "httpdns.resolvers"), appWide.map { it.id }.toSet())
        appWide.forEach { assertEquals(injected, it.packages, it.id) }
        assertTrue(appWide.none { Catalog.SYSTEM_SCOPE in it.packages }, "app-wide rules never target system_server")
        assertTrue(appWide.all { it.defaultOn && it.impact == null })
    }
}
