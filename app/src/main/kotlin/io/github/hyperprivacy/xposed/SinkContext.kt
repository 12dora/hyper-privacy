package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.status.HookState

/**
 * What every network sink needs: the policy to consult, plus the bookkeeping done on each
 * decision (diagnostics recording, hit counting, first-hit logging), so sinks only extract the
 * host / URL and act on the returned rule id.
 */
internal class SinkContext(val env: GuardEnv, private val policy: NetworkPolicy) {
    val xposed get() = env.xposed
    val log get() = env.log
    val classLoader get() = env.classLoader

    /** Rule id blocking [host] at a host-only sink (DNS, TLS SNI), or `null` to let it through. */
    fun checkHost(host: String?): String? {
        val ruleId = policy.blockingHost(host)
        observe(host, null, ruleId)
        if (ruleId != null) env.reporter.hit(env.packageName, ruleId, host)
        return ruleId
    }

    /** Rule id blocking a request to [host] with percent-*encoded* [rawPath], or `null`. */
    fun checkUrl(host: String?, rawPath: String?): String? {
        if (!policy.hasUrlRules && !env.switches.observe) return null
        val path = rawPath?.let(UrlParts::decodePath)
        val ruleId = policy.blockingUrl(host, path)
        observe(host, path, ruleId)
        if (ruleId != null) env.reporter.hit(env.packageName, ruleId, "$host$path")
        return ruleId
    }

    /** As [checkUrl] for a full URL string; unparsable URLs are let through. */
    fun checkUrl(url: String?): String? {
        if (!policy.hasUrlRules && !env.switches.observe) return null
        val parts = UrlParts.parse(url) ?: return null
        val ruleId = policy.blockingUrl(parts.host, parts.path)
        observe(parts.host, parts.path, ruleId)
        if (ruleId != null) env.reporter.hit(env.packageName, ruleId, parts.host + parts.path)
        return ruleId
    }

    fun reportSink(sink: String, hooked: Boolean) {
        env.reporter.sink(env.packageName, sink, if (hooked) HookState.ACTIVE else HookState.MISSED)
        if (!hooked) log.info("sink $sink not available in ${env.packageName}")
    }

    /** Diagnostics: records the endpoint and logs it once (`OBS` prefix) when first seen. */
    private fun observe(host: String?, path: String?, ruleId: String?) {
        if (host == null || !env.switches.observe) return
        val fresh = env.reporter.observe(env.packageName, host, path, ruleId != null) ?: return
        log.info("OBS ${fresh.packageName} ${fresh.host}${fresh.pathPrefix.orEmpty()} blocked=${fresh.blocked}")
    }
}
