package io.github.hyperprivacy.core.net

import io.github.hyperprivacy.core.match.HostNames
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process memory of which host name each remote address was reached under, learned from the
 * process's own successful DNS answers and TLS handshakes (SNI). The IP sink uses it to recognise a
 * connection to a blocked host that is made by address (HTTPDNS, cached IPs); the URL sinks use the
 * reverse direction for realistic error messages.
 *
 * Safety over coverage: an address ever seen under two different hosts is marked shared (CDN and
 * virtual hosting put unrelated services on one IP) and from then on [hostOf] returns `null` for
 * it, so blocking one host can never cut another that happens to share its address.
 *
 * Bounded: when either direction reaches [capacity] it is cleared and relearned, which costs
 * nothing but a little coverage. Lookups are a single map probe and allocate nothing.
 */
public class AddressBook(private val capacity: Int = DEFAULT_CAPACITY) {
    private class Entry(val host: String) {
        @Volatile
        var shared: Boolean = false
    }

    private val byAddress = ConcurrentHashMap<InetAddress, Entry>()
    private val byHost = ConcurrentHashMap<String, InetAddress>()

    /** Records that [address] answered for [host]. Local, private and literal hosts are ignored. */
    public fun record(address: InetAddress, host: String?) {
        if (!isPublic(address) || IpLiterals.isIpLiteral(host)) return
        val canonical = HostNames.normalize(host) ?: return
        val existing = byAddress[address] ?: run {
            if (byAddress.size >= capacity) byAddress.clear()
            byAddress.putIfAbsent(address, Entry(canonical))
        }
        if (existing != null && existing.host != canonical) existing.shared = true
        if (byHost.size >= capacity) byHost.clear()
        byHost[canonical] = address
    }

    /** The single host [address] was seen under, or `null` if unknown or shared by several hosts. */
    public fun hostOf(address: InetAddress): String? = byAddress[address]?.takeIf { !it.shared }?.host

    /** The latest address seen for canonical [host], or `null`. */
    public fun addressOf(host: String): InetAddress? = byHost[host]

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 1024

        /**
         * Whether [address] is a routable remote address: loopback, wildcard, link-local, private
         * (site-local) and multicast addresses are local traffic and never blocked or recorded.
         */
        public fun isPublic(address: InetAddress): Boolean = !address.isLoopbackAddress &&
            !address.isAnyLocalAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
            !address.isMulticastAddress
    }
}
