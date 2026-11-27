package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.match.NetworkMatchers
import io.github.hyperprivacy.core.net.AddressBook
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.tracker.TrackerRules
import io.github.hyperprivacy.core.tracker.Trackers
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * The process's current network block lists (DOMAIN and URL rules), shared by every network sink.
 * Rebuilt off the hot path whenever switches change and published atomically as an immutable
 * [NetworkMatchers] snapshot, so lookups never lock.
 */
internal class NetworkPolicy(
    private val rules: List<Rule>,
    private val switches: RuleSwitches,
    private val log: HookLog,
) {
    private val matchers = AtomicReference(build())

    init {
        switches.addListener(::rebuild)
    }

    /** Serialized, so switch changes on different threads cannot publish an older build over a newer one. */
    @Synchronized
    private fun rebuild() {
        matchers.set(build())
    }

    /** Id of the enabled DOMAIN rule blocking [host], or `null` if allowed. */
    fun blockingHost(host: String?): String? = matchers.get().findHost(host)

    /** Id of the enabled DOMAIN or URL rule blocking [host] + decoded [path] (+ raw [query]), or `null`. */
    fun blockingUrl(host: String?, path: String?, query: String? = null): String? =
        matchers.get().findUrl(host, path, query)

    /** Whether an enabled URL rule has a query condition; only then do sinks extract the query. */
    val matchesQueries: Boolean get() = matchers.get().matchesQueries

    /** Id of the enabled DOMAIN rule blocking a connection to [address]:[port]; see [NetworkMatchers.findConnect]. */
    fun blockingConnect(address: InetAddress, port: Int, book: AddressBook): String? =
        matchers.get().findConnect(address, port, book)

    /** Whether any enabled rule can block a connect by address; lets the connect sink return at once. */
    val blocksConnects: Boolean get() = matchers.get().blocksConnects

    /** Whether any URL-layer rule is currently enabled; lets sinks skip URL parsing entirely. */
    val hasUrlRules: Boolean get() = !matchers.get().urls.isEmpty

    /**
     * Tracker-protected apps never block the hosts SDKs need for features (push, login, maps) by
     * an SDK rule or the known-trackers rule; other app-level rules (HTTPDNS) are not overridden.
     * The known-trackers rule is expanded, and keep hosts loaded, only while such a rule is on.
     */
    private fun build(): NetworkMatchers = log.guard("build network matchers") {
        val effective = TrackerRules.expandKnown(rules, switches::isEnabled)
        val keepHosts = if (effective.any { TrackerRules.honorsKeepHosts(it.id) && switches.isEnabled(it.id) }) {
            Trackers.keepHosts
        } else {
            emptySet()
        }
        NetworkMatchers.of(effective, keepHosts, TrackerRules::honorsKeepHosts, switches::isEnabled)
    } ?: NetworkMatchers.EMPTY
}
