package io.github.hyperprivacy.core.match

import io.github.hyperprivacy.core.net.AddressBook
import io.github.hyperprivacy.core.net.IpLiterals
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import java.net.InetAddress

/**
 * Immutable snapshot of the network rules enabled in one process, mapping matches to rule ids.
 *
 * @property hosts DOMAIN rules, for host-only sinks (DNS, TLS SNI).
 * @property urls DOMAIN rules as host-only patterns plus URL rules, for HTTP-client sinks; DOMAIN
 *   rules are included so stacks with native DNS/TLS (Cronet and forks) are still covered.
 * @property addresses IPv4 and IPv6 literals listed by DOMAIN rules (hard-coded HTTPDNS / reporting IPs),
 *   for the IP connect sink.
 *
 * Sinks look up through [findHost] and [findUrl], which honour keep hosts (see [of]); [hosts] and
 * [urls] may then contain a keep marker and are exposed for tests only.
 */
public class NetworkMatchers private constructor(
    public val hosts: HostMatcher<String>,
    public val urls: UrlMatcher<String>,
    public val addresses: Map<InetAddress, String>,
) {
    /** Whether [findConnect] can ever match; lets the connect sink return before reading arguments. */
    public val blocksConnects: Boolean get() = addresses.isNotEmpty() || !hosts.isEmpty

    /**
     * Rule blocking a connection to [address]:[port]: an IP literal listed by an enabled DOMAIN rule,
     * or the single host [book] learned for that address when a DOMAIN rule blocks the host.
     * Port 53 is never blocked: apps probe public resolvers on it to test connectivity, and blocking
     * plain DNS to them would break that without hiding anything an HTTP sink could not see.
     */
    public fun findConnect(address: InetAddress, port: Int, book: AddressBook): String? {
        if (port == DNS_PORT || !blocksConnects) return null
        addresses[address]?.let { return it }
        if (hosts.isEmpty) return null
        return book.hostOf(address)?.let(::findHost)
    }

    /** Rule blocking [host] at host-only sinks, or `null` (allowed, or kept). */
    public fun findHost(host: String?): String? = hosts.find(host)?.takeIf { it != KEEP }

    /** Rule blocking [host] + decoded [path] at HTTP-client sinks, or `null` (allowed, or kept). */
    public fun findUrl(host: String?, path: String?): String? = urls.find(host, path)?.takeIf { it != KEEP }

    public companion object {
        public const val DNS_PORT: Int = 53

        public val EMPTY: NetworkMatchers = NetworkMatchers(HostMatcher.empty(), UrlMatcher.empty(), emptyMap())

        /** Marker value of a keep host; never a rule id (rule ids cannot contain NUL). */
        private const val KEEP = "\u0000keep"

        /**
         * Builds matchers from the [rules] for which [isEnabled] holds; other payloads are ignored.
         *
         * [keepHosts] (exact or dot-suffix, like DOMAIN patterns) are never blocked at host level
         * by a rule [keepOverrides] accepts: the most specific pattern wins, so a block suffix
         * above a kept host leaves the kept host alone, while a URL rule with a path on a kept host
         * (or a block pattern below it) still applies. A keep host that an enabled DOMAIN rule
         * outside [keepOverrides] covers is dropped, so such a rule (e.g. the HTTPDNS block of a
         * protected app) is never weakened by an SDK's keep list.
         */
        public fun of(
            rules: List<Rule>,
            keepHosts: Collection<String> = emptyList(),
            keepOverrides: (String) -> Boolean = { true },
            isEnabled: (String) -> Boolean,
        ): NetworkMatchers {
            val enabled = rules.filter { isEnabled(it.id) }
            val strong = enabled.filter { !keepOverrides(it.id) }
                .flatMap { (it.payload as? RulePayload.Domains)?.suffixes.orEmpty() }
            val strongHosts = if (strong.isEmpty()) HostMatcher.empty() else HostMatcher.of(strong.map { it to it })
            val keep = keepHosts.filter { !strongHosts.matches(it) }
            val hostEntries = ArrayList<Pair<String, String>>()
            val urlEntries = ArrayList<Pair<UrlPattern, String>>()
            val addresses = HashMap<InetAddress, String>()
            for (rule in enabled) {
                when (val payload = rule.payload) {
                    is RulePayload.Domains -> payload.suffixes.forEach { suffix ->
                        hostEntries += suffix to rule.id
                        urlEntries += UrlPattern(suffix) to rule.id
                        IpLiterals.parse(suffix)?.let { addresses.putIfAbsent(it, rule.id) }
                    }

                    is RulePayload.Urls -> payload.prefixes.forEach { prefix ->
                        urlEntries += UrlPattern(prefix.host, prefix.pathPrefix) to rule.id
                    }

                    else -> Unit
                }
            }
            if (hostEntries.isEmpty() && urlEntries.isEmpty()) return EMPTY
            // Keep markers only matter next to a block pattern; first value wins on duplicates, so they go first.
            val keepUrlEntries = keep.map { UrlPattern(it) to KEEP }
            val hosts = if (hostEntries.isEmpty()) {
                HostMatcher.empty()
            } else {
                HostMatcher.of(keep.map { it to KEEP } + hostEntries)
            }
            return NetworkMatchers(hosts, UrlMatcher.of(keepUrlEntries + urlEntries), addresses)
        }
    }
}
