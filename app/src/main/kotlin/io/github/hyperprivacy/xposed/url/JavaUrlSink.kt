package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.SinkContext
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.io.IOException
import java.lang.reflect.Method
import java.net.Proxy
import java.net.URL

/**
 * `java.net.URL#openConnection()` and `openConnection(Proxy)`: the entry of HttpURLConnection
 * (and of libraries built on it). Throws [IOException], which both overloads declare.
 * Non-HTTP(S) URLs (`file:`, `jar:` class-loading resources …) are let through untouched.
 */
internal class JavaUrlSink(private val sinks: SinkContext) {
    fun install(): Boolean {
        val methods = listOfNotNull(
            method(),
            method(Proxy::class.java),
        )
        methods.forEach { method ->
            sinks.xposed.hook(method)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept(FailOpenHooker(sinks.log, Sinks.URL_JAVA, ::decide))
        }
        sinks.reportSink(Sinks.URL_JAVA, methods.isNotEmpty())
        return methods.isNotEmpty()
    }

    private fun decide(chain: Chain): Verdict {
        val url = chain.thisObject as? URL ?: return Verdict.Proceed
        if (url.protocol != HTTP && url.protocol != HTTPS) return Verdict.Proceed
        sinks.checkUrl(url.host, url.path) ?: return Verdict.Proceed
        return Verdict.Throw(IOException(BLOCKED_MESSAGE))
    }

    private fun method(vararg parameterTypes: Class<*>): Method? =
        runCatching { URL::class.java.getDeclaredMethod(OPEN_CONNECTION, *parameterTypes) }.getOrNull()

    private companion object {
        const val OPEN_CONNECTION = "openConnection"
        const val HTTP = "http"
        const val HTTPS = "https"
    }
}
