package io.github.hyperprivacy.core.match

import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload

/**
 * Immutable snapshot of the network rules enabled in one process, mapping matches to rule ids.
 *
 * @property hosts DOMAIN rules, for host-only sinks (DNS, TLS SNI).
 * @property urls DOMAIN rules as host-only patterns plus URL rules, for HTTP-client sinks; DOMAIN
 *   rules are included so stacks with native DNS/TLS (Cronet and forks) are still covered.
 */
public class NetworkMatchers private constructor(
    public val hosts: HostMatcher<String>,
    public val urls: UrlMatcher<String>,
) {
    public companion object {
        public val EMPTY: NetworkMatchers = NetworkMatchers(HostMatcher.empty(), UrlMatcher.empty())

        /** Builds matchers from the [rules] for which [isEnabled] holds; other payloads are ignored. */
        public fun of(rules: List<Rule>, isEnabled: (String) -> Boolean): NetworkMatchers {
            val hostEntries = ArrayList<Pair<String, String>>()
            val urlEntries = ArrayList<Pair<UrlPattern, String>>()
            for (rule in rules) {
                if (!isEnabled(rule.id)) continue
                when (val payload = rule.payload) {
                    is RulePayload.Domains -> payload.suffixes.forEach { suffix ->
                        hostEntries += suffix to rule.id
                        urlEntries += UrlPattern(suffix) to rule.id
                    }

                    is RulePayload.Urls -> payload.prefixes.forEach { prefix ->
                        urlEntries += UrlPattern(prefix.host, prefix.pathPrefix) to rule.id
                    }

                    else -> Unit
                }
            }
            if (hostEntries.isEmpty() && urlEntries.isEmpty()) return EMPTY
            return NetworkMatchers(HostMatcher.of(hostEntries), UrlMatcher.of(urlEntries))
        }
    }
}
