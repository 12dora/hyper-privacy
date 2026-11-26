package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.net.PlausibleErrors
import io.github.hyperprivacy.core.net.PlausibleFrames
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlausibleErrorsTest {
    @Test
    fun `dns failure matches libcore wording`() {
        val error = PlausibleErrors.unresolvedHost("tracking.example.com")
        assertEquals(
            "Unable to resolve host \"tracking.example.com\": No address associated with hostname",
            error.message,
        )
        assertEquals(
            "android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)",
            PlausibleErrors.GAI_CAUSE_MESSAGE,
        )
    }

    @Test
    fun `tls failure is one of the two conscrypt messages and stable per host`() {
        val pointer = PlausibleErrors.plausibleSslPointer(0x1234_5678_9abcL)
        val hosts = (0 until 64).map { "h$it.example.com" }
        val messages = hosts.map { PlausibleErrors.tlsHandshakeMessage(it, pointer) }
        val reset = Regex("Read error: ssl=0x[0-9a-f]{16}: I/O error during system call, Connection reset by peer")
        assertTrue(messages.all { it == PlausibleErrors.TLS_CLOSED_BY_PEER || reset.matches(it) }, "$messages")
        assertTrue(messages.any { it == PlausibleErrors.TLS_CLOSED_BY_PEER } && messages.any(reset::matches))
        hosts.forEach { host ->
            assertEquals(PlausibleErrors.tlsFailureFor(host), PlausibleErrors.tlsFailureFor(host))
        }
    }

    @Test
    fun `ssl pointers look like tagged arm64 heap addresses`() {
        listOf(0L, -1L, 0x7fff_ffffL, Long.MIN_VALUE, 42L).forEach { seed ->
            val hex = java.lang.Long.toHexString(PlausibleErrors.plausibleSslPointer(seed))
            assertTrue(hex.matches(Regex("b400007[0-9a-f]{8}[08]")), hex)
        }
    }

    @Test
    fun `connect refusal matches IoBridge wording`() {
        // libcore IoBridge.createMessageForException, as printed on device.
        assertEquals(
            "failed to connect to /203.107.1.1 (port 443) from /198.51.100.5 (port 41234) after 10000ms: " +
                "isConnected failed: ECONNREFUSED (Connection refused)",
            PlausibleErrors.connectRefusedMessage("/203.107.1.1", 443, 10_000, "/198.51.100.5", 41_234),
        )
        assertEquals(
            "failed to connect to dns.example/203.107.1.1 (port 80) from /2001:db8::5 (port 50000): " +
                "connect failed: ECONNREFUSED (Connection refused)",
            PlausibleErrors.connectRefusedMessage("dns.example/203.107.1.1", 80, 0, "/2001:db8::5", 50_000),
        )
        assertEquals(
            "failed to connect to /203.107.1.1 (port 443) from /:: (port 0) after 15000ms: " +
                "isConnected failed: ECONNREFUSED (Connection refused)",
            PlausibleErrors.connectRefusedMessage("/203.107.1.1", 443, 15_000, "/::", 0),
        )
        // getsockname failed: libcore leaves the clause out.
        assertEquals(
            "failed to connect to /203.107.1.1 (port 80): connect failed: ECONNREFUSED (Connection refused)",
            PlausibleErrors.connectRefusedMessage("/203.107.1.1", 80, 0),
        )
    }

    @Test
    fun `source ports and frame lines look real`() {
        listOf(0, 1, -1, Int.MIN_VALUE, Int.MAX_VALUE, 28_232, 123_456).forEach { seed ->
            val port = PlausibleErrors.ephemeralPort(seed)
            assertTrue(port in PlausibleErrors.FIRST_EPHEMERAL_PORT..PlausibleErrors.LAST_EPHEMERAL_PORT, "$port")
        }
        val line = PlausibleFrames.line("java.net.Inet6AddressImpl", "lookupHostByName")
        assertTrue(line in 60..899, "$line")
        assertEquals(line, PlausibleFrames.line("java.net.Inet6AddressImpl", "lookupHostByName"))
    }

    @Test
    fun `http failure uses OkHttp route wording or a plain timeout`() {
        val known = PlausibleErrors.httpFailure("api.example.com", "1.2.3.4", 443)
        assertIs<ConnectException>(known)
        assertEquals("Failed to connect to api.example.com/1.2.3.4:443", known.message)
        val literal = PlausibleErrors.httpFailure("203.107.1.2", null, 80)
        assertEquals("Failed to connect to /203.107.1.2:80", literal.message)
        val unknown = PlausibleErrors.httpFailure("api.example.com", null, 443)
        assertIs<SocketTimeoutException>(unknown)
        assertEquals("timeout", unknown.message)
    }

    @Test
    fun `default ports follow the scheme`() {
        assertEquals(80, PlausibleErrors.portOf("http", -1))
        assertEquals(443, PlausibleErrors.portOf("https", -1))
        assertEquals(8080, PlausibleErrors.portOf("http", 8080))
    }

    @Test
    fun `no message names the module or a policy`() {
        val messages = listOf(
            PlausibleErrors.unresolvedHostMessage("a.example.com"),
            PlausibleErrors.tlsHandshakeMessage("a.example.com", 0),
            PlausibleErrors.tlsHandshakeMessage("b.example.com", 0),
            PlausibleErrors.connectRefusedMessage("/1.2.3.4", 443, 1, "/198.51.100.5", 40_000),
            PlausibleErrors.httpFailureMessage("a.example.com", null, 443),
            PlausibleErrors.httpFailureMessage("a.example.com", "1.2.3.4", 443),
            PlausibleErrors.GAI_CAUSE_MESSAGE,
        )
        val forbidden = listOf("privacy", "block", "policy", "hyper", "rule", "module", "xposed")
        messages.forEach { message ->
            forbidden.forEach { word -> assertTrue(word !in message.lowercase(), "$word in $message") }
        }
    }
}
