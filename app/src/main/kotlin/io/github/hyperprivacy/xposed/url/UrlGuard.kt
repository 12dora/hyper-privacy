package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.xposed.SinkContext

/**
 * Installs every HTTP-client-layer sink (URL rules, plus DOMAIN rules as host-only patterns).
 * Each sink is optional and reports its own state; a failing one never affects the others.
 */
internal class UrlGuard(private val sinks: SinkContext) {
    /** Returns whether at least one URL sink was hooked in this process. */
    fun install(): Boolean {
        val java = sinks.log.guard("java.net.URL sink") { JavaUrlSink(sinks).install() } ?: false
        val okhttp = sinks.log.guard("OkHttp sink") { OkHttpSink(sinks).install() } ?: false
        val cronet = sinks.log.guard("Cronet sinks") { CronetSink(sinks).install() } ?: false
        val webView = sinks.log.guard("WebView sink") { WebViewSink(sinks).install() } ?: false
        return java || okhttp || cronet || webView
    }
}
