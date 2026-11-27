package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.net.BlockedWebResponse
import io.github.hyperprivacy.xposed.SinkContext
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain

/**
 * WebView (Chromium) requests: page JS (RUM beacons, `fetch`, XHR, images) is loaded by
 * Chromium's native stack, invisible to the DNS / TLS / Java HTTP sinks. Its only public Java
 * touchpoint is `WebViewClient.shouldInterceptRequest(WebView, WebResourceRequest)`, which
 * Chromium calls for every request, main frame included, before loading it ([WebClientHooks]
 * covers the base implementation and every override), and for service-worker fetches
 * `ServiceWorkerClient.shouldInterceptRequest(WebResourceRequest)`. A matched request is answered with
 * [BlockedWebResponse] (empty 204) instead of being loaded.
 */
internal class WebViewSink(private val sinks: SinkContext) {
    fun install(): Boolean {
        val views = WebClientHooks.register(sinks.xposed, sinks.log, Sinks.URL_WEBVIEW, ::decide)
        // Same decision for service-worker fetches, which no WebViewClient sees.
        val workers = sinks.log.guard("WebView service workers") {
            WebClientHooks.registerServiceWorker(sinks.xposed, sinks.log, Sinks.URL_WEBVIEW, ::decide)
        } ?: 0
        val hooked = views + workers > 0
        sinks.reportSink(Sinks.URL_WEBVIEW, hooked)
        return hooked
    }

    private fun decide(chain: Chain): Verdict {
        if (!sinks.urlChecksActive) return Verdict.Proceed
        val request = WebClientHooks.requestOf(chain) ?: return Verdict.Proceed
        val url = request.url?.toString() ?: return Verdict.Proceed
        if (!UrlParts.isHttp(url)) return Verdict.Proceed
        sinks.checkUrl(url) ?: return Verdict.Proceed
        return Verdict.Return(WebClientHooks.blockedResponse(request))
    }
}
