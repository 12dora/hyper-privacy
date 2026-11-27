package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.net.IpLiterals
import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.SinkContext
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URLConnection

/**
 * HTTPDNS done by hand: the URL host is replaced with a resolved IP and the real name travels in
 * `setRequestProperty("Host", …)`. [JavaUrlSink] decides at `URL#openConnection`, before any header
 * exists, so it only sees the IP. This sink re-checks such requests where the platform
 * `HttpURLConnection` (`com.android.okhttp.internal.huc.HttpURLConnectionImpl`, also the delegate of
 * its HTTPS variant) starts the exchange: `connect()`, `getInputStream()`, `getOutputStream()`.
 *
 * Only connections whose URL host is an IP literal and that are not yet connected are looked at;
 * everything else returns after a field read. The block is the route failure an IP connect would
 * produce (`Failed to connect to /ip:port`). HTTPS keeps the SNI check in the TLS sink as well.
 */
internal class HostHeaderSink(private val sinks: SinkContext) {
    private val connected: Field? = runCatching {
        URLConnection::class.java.getDeclaredField(CONNECTED).apply { isAccessible = true }
    }.getOrNull()

    fun install(): Boolean {
        val type = Reflection.loadClass(IMPL_CLASS, null)
        val methods = if (type == null || connected == null) emptyList() else ENTRIES.mapNotNull { method(type, it) }
        methods.forEach { method ->
            sinks.xposed.hook(method)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept(FailOpenHooker(sinks.log, Sinks.URL_JAVA_HOST, ::decide))
        }
        sinks.reportSink(Sinks.URL_JAVA_HOST, methods.isNotEmpty())
        return methods.isNotEmpty()
    }

    private fun decide(chain: Chain): Verdict {
        if (!sinks.urlChecksActive) return Verdict.Proceed
        val connection = chain.thisObject as? HttpURLConnection ?: return Verdict.Proceed
        val url = connection.url ?: return Verdict.Proceed
        if (!IpLiterals.isIpLiteral(url.host) || connected?.getBoolean(connection) != false) return Verdict.Proceed
        val host = UrlParts.hostOfHeader(connection.getRequestProperty(HOST)) ?: return Verdict.Proceed
        if (IpLiterals.isIpLiteral(host)) return Verdict.Proceed
        sinks.checkUrl(host, url.path, url.query) ?: return Verdict.Proceed
        return Verdict.Throw(sinks.urlFailure(url.host, url.protocol, url.port))
    }

    private fun method(type: Class<*>, name: String): Method? = runCatching { type.getDeclaredMethod(name) }.getOrNull()

    private companion object {
        const val IMPL_CLASS = "com.android.okhttp.internal.huc.HttpURLConnectionImpl"
        const val CONNECTED = "connected"
        const val HOST = "Host"
        val ENTRIES = listOf("connect", "getInputStream", "getOutputStream")
    }
}
