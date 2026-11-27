package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.net.BlockedWebResponse
import io.github.hyperprivacy.core.net.ClassShape
import io.github.hyperprivacy.core.net.OkHttpShapes
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSinkSupportTest {
    @ParameterizedTest
    @ValueSource(strings = ["http://a.com/", "https://a.com/x", "HTTPS://A.COM"])
    fun `http urls are recognised`(url: String) = assertTrue(UrlParts.isHttp(url))

    @ParameterizedTest
    @ValueSource(strings = ["data:text/html,hi", "blob:https://a.com/1", "file:///x", "about:blank", "", "http:/a"])
    fun `other schemes are skipped`(url: String) = assertFalse(UrlParts.isHttp(url))

    @Test
    fun `same-origin requests get no CORS headers`() =
        assertEquals(emptyMap(), BlockedWebResponse.headers(mapOf("Accept" to "*/*")))

    @Test
    fun `cross-origin requests get their origin echoed`() {
        val headers = BlockedWebResponse.headers(
            mapOf(
                "origin" to "https://page.example",
                "access-control-request-method" to "POST",
                "Access-Control-Request-Headers" to "content-type",
            ),
        )
        assertEquals("https://page.example", headers["Access-Control-Allow-Origin"])
        assertEquals("true", headers["Access-Control-Allow-Credentials"])
        assertEquals("POST", headers["Access-Control-Allow-Methods"])
        assertEquals("content-type", headers["Access-Control-Allow-Headers"])
    }

    private val obfuscatedUrl = ClassShape(
        "okhttp3.x",
        listOf(S, S, S, S, "int", "java.util.List", "java.util.List", S, S),
    )
    private val obfuscatedRequest = ClassShape(
        "okhttp3.e0",
        listOf("okhttp3.x", S, "okhttp3.w", "okhttp3.f0", "java.util.Map"),
    )
    private val address = ClassShape(
        "okhttp3.a",
        listOf("okhttp3.q", "javax.net.SocketFactory", "okhttp3.x", "java.util.List"),
    )
    private val isUrlType = { name: String -> name == obfuscatedUrl.name }

    @Test
    fun `obfuscated okhttp value classes are recognised by shape`() {
        assertTrue(OkHttpShapes.isHttpUrl(obfuscatedUrl))
        assertTrue(OkHttpShapes.isRequest(obfuscatedRequest, isUrlType))
        val fields = listOf("okhttp3.e0", "okhttp3.d0", "int", S, "okhttp3.w", "long", "long")
        val response = ClassShape("okhttp3.g0", fields)
        assertTrue(OkHttpShapes.isResponse(response, obfuscatedRequest.name))
        val holder = ClassShape("a.b", listOf("okhttp3.e0", "int"))
        assertFalse(OkHttpShapes.isResponse(holder, obfuscatedRequest.name), "a Request and an int are not a Response")
    }

    @Test
    fun `real names win and look-alikes are rejected`() {
        assertTrue(OkHttpShapes.isHttpUrl(ClassShape(OkHttpShapes.HTTP_URL, emptyList())))
        assertTrue(OkHttpShapes.isRequest(ClassShape(OkHttpShapes.REQUEST, emptyList())) { false })
        assertFalse(OkHttpShapes.isHttpUrl(obfuscatedRequest))
        assertFalse(OkHttpShapes.isRequest(address, isUrlType), "Address holds an HttpUrl but no String")
        assertFalse(OkHttpShapes.isResponse(obfuscatedRequest, obfuscatedRequest.name))
    }

    private companion object {
        const val S = "java.lang.String"
    }
}
