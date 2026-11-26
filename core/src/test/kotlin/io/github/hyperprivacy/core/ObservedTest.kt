package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.status.EndpointRecorder
import io.github.hyperprivacy.core.status.HookState
import io.github.hyperprivacy.core.status.ObservedCodec
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.RetryBackoff
import io.github.hyperprivacy.core.status.SinkCodec
import io.github.hyperprivacy.core.status.SinkReport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObservedTest {
    @Test
    fun `path prefix keeps the first two segments`() {
        assertEquals("/a/b", ObservedEndpoint.pathPrefixOf("/a/b/c/d"))
        assertEquals("/a/b", ObservedEndpoint.pathPrefixOf("/a/b/"))
        assertEquals("/a", ObservedEndpoint.pathPrefixOf("/a"))
        assertEquals("/", ObservedEndpoint.pathPrefixOf("/"))
        assertEquals("/", ObservedEndpoint.pathPrefixOf(""))
        assertEquals("/a", ObservedEndpoint.pathPrefixOf("/a?x=/b/c"))
        assertEquals("/a", ObservedEndpoint.pathPrefixOf("/a\n/b"))
        assertNull(ObservedEndpoint.pathPrefixOf(null))
        val long = "/" + "x".repeat(ObservedEndpoint.MAX_PATH_LENGTH * 2)
        assertEquals(ObservedEndpoint.MAX_PATH_LENGTH, ObservedEndpoint.pathPrefixOf(long)?.length)
    }

    @Test
    fun `recorder dedupes by host and path prefix and reports new entries once`() {
        val recorder = EndpointRecorder()
        assertNotNull(recorder.record(PKG, "A.example.com", "/v1/t/x", blocked = false, now = 10))
        assertNull(recorder.record(PKG, "a.example.com", "/v1/t/y", blocked = true, now = 20))
        assertNotNull(recorder.record(PKG, "a.example.com", null, blocked = false, now = 30))
        assertNull(recorder.record(PKG, "not a host", "/x", blocked = false, now = 30))
        val drained = recorder.drain().associateBy { it.pathPrefix }
        assertEquals(ObservedEndpoint(PKG, "a.example.com", "/v1/t", 2, 10, 20, true), drained["/v1/t"])
        assertEquals(ObservedEndpoint(PKG, "a.example.com", null, 1, 30, 30, false), drained[null])
        assertTrue(recorder.drain().isEmpty())
    }

    @Test
    fun `restore puts failed deliveries back`() {
        val recorder = EndpointRecorder()
        recorder.record(PKG, "a.com", "/x", blocked = false, now = 1)
        val first = recorder.drain()
        recorder.record(PKG, "a.com", "/x", blocked = false, now = 2)
        recorder.restore(first)
        assertEquals(2, recorder.drain().single().count)
    }

    @Test
    fun `recorder is bounded in size and admission rate`() {
        val bounded = EndpointRecorder(maxEntries = 3, maxNewPerWindow = 100, windowMillis = 1_000)
        repeat(10) { bounded.record(PKG, "h$it.com", null, blocked = false, now = 1) }
        assertEquals(3, bounded.size)

        val limited = EndpointRecorder(maxEntries = 100, maxNewPerWindow = 2, windowMillis = 1_000)
        repeat(5) { limited.record(PKG, "h$it.com", null, blocked = false, now = 0) }
        assertEquals(2, limited.size)
        limited.record(PKG, "late.com", null, blocked = false, now = 1_000)
        assertEquals(3, limited.size)
        // Known endpoints are always counted even when admission is closed.
        limited.record(PKG, "h0.com", null, blocked = false, now = 1_001)
        assertEquals(2, limited.drain().first { it.host == "h0.com" }.count)
    }

    @Test
    fun `observed codec round-trips and validates`() {
        val rows = listOf(
            ObservedEndpoint(PKG, "a.example.com", "/v1/t", 3, 1, 2, true),
            ObservedEndpoint(PKG, "b.example.com", null, 1, 5, 5, false),
        )
        assertEquals(rows, ObservedCodec.decode(ObservedCodec.encode(rows)))
        val bad = listOf(
            "$PKG\tA.com\t\t1\t1\t1\t0",
            "$PKG\ta.com\t/a/b/c\t1\t1\t1\t0",
            "$PKG\ta.com\t\t0\t1\t1\t0",
            "$PKG\ta.com\t\t1\t2\t1\t0",
            "$PKG\ta.com\t\t1\t1\t1\tyes",
            "$PKG\ta|b.com\t\t1\t1\t1\t0",
            "\ta.com\t\t1\t1\t1\t0",
        ).joinToString("\n")
        assertTrue(ObservedCodec.decode(bad).isEmpty())
    }

    @Test
    fun `sink codec round-trips`() {
        val rows = listOf(SinkReport(PKG, "dns", HookState.ACTIVE), SinkReport(PKG, "url.okhttp", HookState.MISSED))
        assertEquals(rows, SinkCodec.decode(SinkCodec.encode(rows)))
        assertTrue(SinkCodec.decode("$PKG\tdns\tBOGUS").isEmpty())
    }

    @Test
    fun `backoff doubles up to the cap and then stops`() {
        val backoff = RetryBackoff(initialMillis = 5, maxMillis = 30, maxAttempts = 6)
        assertEquals(listOf(5L, 10L, 20L, 30L, 30L, 30L), (1..6).map { backoff.delayAfter(it) })
        assertNull(backoff.delayAfter(7))
        assertNull(backoff.delayAfter(0))
        assertEquals(RetryBackoff.DEFAULT_MAX_MILLIS, RetryBackoff().delayAfter(RetryBackoff.DEFAULT_MAX_ATTEMPTS))
    }

    private companion object {
        const val PKG = "com.example.app"
    }
}
