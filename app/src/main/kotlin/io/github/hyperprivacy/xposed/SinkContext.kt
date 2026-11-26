package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.match.HostNames
import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.net.AddressBook
import io.github.hyperprivacy.core.net.PlausibleErrors
import io.github.hyperprivacy.core.status.HookState
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * What every network sink needs: the policy to consult, plus the bookkeeping done on each
 * decision (diagnostics recording, hit counting, first-hit logging), so sinks only extract the
 * host / URL and act on the returned rule id.
 */
internal class SinkContext(val env: GuardEnv, private val policy: NetworkPolicy) {
    val xposed get() = env.xposed
    val log get() = env.log
    val classLoader get() = env.classLoader

    /** Host names this process reached each address under (DNS answers, TLS SNI); see [AddressBook]. */
    private val addresses = AddressBook()

    /** Rule id blocking [host] at a host-only sink (DNS, TLS SNI), or `null` to let it through. */
    fun checkHost(host: String?): String? {
        val ruleId = policy.blockingHost(host)
        observe(host, null, ruleId)
        if (ruleId != null) env.reporter.hit(env.packageName, ruleId, host)
        return ruleId
    }

    /** Learns the [answers] the system resolver gave for [host]. */
    fun recordAnswers(host: String, answers: Array<*>) {
        for (answer in answers) if (answer is InetAddress) addresses.record(answer, host)
    }

    /** Learns that [address] was reached for [host] (TLS SNI). */
    fun recordPeer(host: String, address: InetAddress) = addresses.record(address, host)

    /** Whether [checkConnect] can block or record anything; one volatile read for the connect hot path. */
    val connectChecksActive: Boolean get() = policy.blocksConnects || env.switches.observe

    /**
     * Rule id blocking a connection to [address]:[port] (an IP listed by a DOMAIN rule, or the
     * learned host of the address), or `null`. Local addresses are never blocked. In diagnostics
     * mode an address with no known host name (a hard-coded or HTTPDNS-resolved IP) is recorded.
     *
     * Limited reach of the learned-address path: the book only learns hosts that were resolved or
     * reached while *allowed* (blocked hosts never resolve), so it matches only after a rule is
     * switched on mid-process. IP literals listed by rules are the effective part; HTTPDNS-resolved
     * IPs of blocked hosts are caught by the TLS SNI check (and, for cleartext HTTP, only when a
     * `Host` header names the host), not here.
     */
    fun checkConnect(address: InetAddress, port: Int): String? {
        if (!AddressBook.isPublic(address)) return null
        val ruleId = policy.blockingConnect(address, port, addresses)
        if (ruleId != null) env.reporter.hit(env.packageName, ruleId, address.hostAddress)
        if (env.switches.observe && addresses.hostOf(address) == null) {
            address.hostAddress?.let { observe(it, null, ruleId) }
        }
        return ruleId
    }

    /** Whether [checkUrl] can return anything or record; lets hot sinks skip reading the URL at all. */
    val urlChecksActive: Boolean get() = policy.hasUrlRules || env.switches.observe

    /** Rule id blocking a request to [host] with percent-*encoded* [rawPath], or `null`. */
    fun checkUrl(host: String?, rawPath: String?): String? {
        if (!urlChecksActive) return null
        val path = rawPath?.let(UrlParts::decodePath)
        val ruleId = policy.blockingUrl(host, path)
        observe(host, path, ruleId)
        if (ruleId != null) env.reporter.hit(env.packageName, ruleId, "$host$path")
        return ruleId
    }

    /** As [checkUrl] for a full URL string; unparsable URLs are let through. */
    fun checkUrl(url: String?): String? {
        if (!urlChecksActive) return null
        return checkParsed(UrlParts.parse(url) ?: return null)
    }

    /** As [checkUrl] for an already parsed URL (decoded path). */
    fun checkParsed(parts: UrlParts): String? {
        if (!urlChecksActive) return null
        val ruleId = policy.blockingUrl(parts.host, parts.path)
        observe(parts.host, parts.path, ruleId)
        if (ruleId != null) env.reporter.hit(env.packageName, ruleId, parts.host + parts.path)
        return ruleId
    }

    /**
     * The error a URL sink throws for a blocked request to [host] ([scheme] and [port] as in
     * `java.net.URL`, `-1` for the default port): see [NetErrors.httpFailure].
     */
    fun urlFailure(host: String, scheme: String?, port: Int): IOException = NetErrors.httpFailure(
        host,
        policy.blockingHost(host) != null,
        HostNames.normalize(host)?.let(addresses::addressOf)?.hostAddress,
        PlausibleErrors.portOf(scheme, port),
    )

    /** [urlFailure] for a full URL string, as checked by [checkUrl]. */
    fun urlFailure(url: String?): IOException {
        val host = UrlParts.parse(url)?.host ?: return SocketTimeoutException(PlausibleErrors.TIMEOUT)
        val scheme = url?.substringBefore(':')
        return urlFailure(host, scheme, portOf(url))
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

/** Explicit port of an absolute [url] (`scheme://host:port/…`), or `-1`. */
private fun portOf(url: String?): Int {
    val authority = url?.substringAfter("://", "")?.substringBefore('/')?.substringAfterLast('@') ?: return -1
    val portText = if (authority.startsWith('[')) {
        authority.substringAfter("]:", "")
    } else {
        authority.substringAfter(':', "")
    }
    return portText.toIntOrNull() ?: -1
}
