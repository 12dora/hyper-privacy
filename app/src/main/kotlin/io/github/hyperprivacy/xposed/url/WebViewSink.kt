package io.github.hyperprivacy.xposed.url

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.net.BlockedWebResponse
import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.SinkContext
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain
import java.io.ByteArrayInputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView (Chromium) requests: page JS (RUM beacons, `fetch`, XHR, images) is loaded by
 * Chromium's native stack, invisible to the DNS / TLS / Java HTTP sinks. Its only public Java
 * touchpoint is `WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest)`, which
 * Chromium calls for every request, main frame included, before loading it. A matched request is
 * answered with [BlockedWebResponse] (empty 204) instead of being loaded.
 *
 * Coverage:
 * - `WebViewClient`'s own implementation, reached by every client that does not override it
 *   (and by the null client WebView uses when the app sets none);
 * - overrides: the `WebViewClient` constructor is hooked, and the first time an instance of a
 *   class is built its hierarchy (below `WebViewClient`) is inspected and every declared override
 *   is hooked, once per class. The constructor hook is installed when the process starts, before
 *   app code runs, so every client is seen before `setWebViewClient` can hand it to a WebView.
 *
 * The deprecated `shouldInterceptRequest(WebView, String)` is not hooked: Chromium only calls the
 * `WebResourceRequest` overload, whose base implementation is what forwards to the String one,
 * so hooking it too would only check the same request twice.
 */
internal class WebViewSink(private val sinks: SinkContext) {
    private val inspected: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()
    private val hooker = FailOpenHooker(sinks.log, Sinks.URL_WEBVIEW, ::decide)

    fun install(): Boolean {
        val base = WebViewClient::class.java
        val constructors = base.declaredConstructors
        constructors.forEach { constructor ->
            sinks.xposed.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { onClient(it.javaClass) }
                result
            }
        }
        inspected.add(base)
        val baseEntry = interceptMethod(base)?.also { sinks.xposed.hook(it).intercept(hooker) }
        val hooked = constructors.isNotEmpty()
        sinks.log.info("WebView: hooked ${constructors.size} constructor(s), base entry ${baseEntry != null}")
        sinks.reportSink(Sinks.URL_WEBVIEW, hooked)
        return hooked
    }

    /** Hooks the overrides declared by [type] and its superclasses, each class at most once. */
    private fun onClient(type: Class<*>) {
        if (type in inspected) return
        sinks.log.guard("WebView client ${type.name}") {
            generateSequence(type) { it.superclass }
                .takeWhile { inspected.add(it) }
                .forEach { cls ->
                    interceptMethod(cls)?.let {
                        sinks.xposed.hook(it).intercept(hooker)
                        sinks.log.info("WebView: hooked shouldInterceptRequest of ${cls.name}")
                    }
                }
        }
    }

    private fun decide(chain: Chain): Verdict {
        if (!sinks.urlChecksActive) return Verdict.Proceed
        val request = chain.getArg(REQUEST_INDEX) as? WebResourceRequest ?: return Verdict.Proceed
        val url = request.url?.toString() ?: return Verdict.Proceed
        if (!UrlParts.isHttp(url)) return Verdict.Proceed
        sinks.checkUrl(url) ?: return Verdict.Proceed
        return Verdict.Return(blockedResponse(request))
    }

    private fun blockedResponse(request: WebResourceRequest) = WebResourceResponse(
        BlockedWebResponse.MIME_TYPE,
        BlockedWebResponse.ENCODING,
        BlockedWebResponse.STATUS,
        BlockedWebResponse.REASON,
        BlockedWebResponse.headers(request.requestHeaders.orEmpty()),
        ByteArrayInputStream(EMPTY_BODY),
    )

    private companion object {
        const val SHOULD_INTERCEPT_REQUEST = "shouldInterceptRequest"
        const val REQUEST_INDEX = 1
        val EMPTY_BODY = ByteArray(0)

        /** The concrete `shouldInterceptRequest(WebView, WebResourceRequest)` declared by [cls], if any. */
        fun interceptMethod(cls: Class<*>): Method? = cls.declaredMethods.firstOrNull {
            it.name == SHOULD_INTERCEPT_REQUEST && !Modifier.isAbstract(it.modifiers) && !it.isBridge &&
                it.parameterTypes.contentEquals(PARAMETER_TYPES)
        }

        val PARAMETER_TYPES = arrayOf(WebView::class.java, WebResourceRequest::class.java)
    }
}
