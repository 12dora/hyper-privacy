package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.xposed.SinkContext

/**
 * What [UrlGuard.install] achieved: [any] URL sink hooked, and whether an HTTP stack the app
 * visibly ships could not be hooked ([incomplete]), so URL rules there may not see every request.
 */
internal class UrlSinks(val any: Boolean, val incomplete: Boolean)

/**
 * Installs every HTTP-client-layer sink (URL rules, plus DOMAIN rules as host-only patterns).
 * Each sink is optional and reports its own state; a failing one never affects the others.
 */
internal class UrlGuard(private val sinks: SinkContext) {
    fun install(): UrlSinks {
        val java = sinks.log.guard("java.net.URL sink") { JavaUrlSink(sinks).install() } ?: false
        sinks.log.guard("Host header sink") { HostHeaderSink(sinks).install() }
        val okhttpSink = OkHttpSink(sinks)
        val okhttp = sinks.log.guard("OkHttp sink") { okhttpSink.install() } ?: false
        val cronet = sinks.log.guard("Cronet sinks") { CronetSink(sinks).install() } ?: false
        val webView = sinks.log.guard("WebView sink") { WebViewSink(sinks).install() } ?: false
        return UrlSinks(java || okhttp || cronet || webView, incomplete = okhttpSink.presentButMissed)
    }
}
