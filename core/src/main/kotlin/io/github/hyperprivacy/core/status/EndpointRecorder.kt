package io.github.hyperprivacy.core.status

import io.github.hyperprivacy.core.match.HostNames
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-process, bounded, thread-safe tally of observed endpoints for diagnostics mode.
 *
 * Endpoints are deduplicated by (package, host, [ObservedEndpoint.pathPrefixOf]). At most
 * [maxEntries] distinct endpoints are kept for the life of the process, and at most
 * [maxNewPerWindow] new ones are admitted per [windowMillis], so a burst of random paths cannot
 * crowd out everything else or cost much. Repeat sightings of a known endpoint are lock-free.
 */
public class EndpointRecorder(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxNewPerWindow: Int = DEFAULT_MAX_NEW_PER_WINDOW,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private data class Key(val packageName: String, val host: String, val pathPrefix: String?)

    private class Tally(val firstSeen: Long) {
        val pending = AtomicLong()
        val announced = AtomicBoolean()

        @Volatile
        var lastSeen: Long = firstSeen

        @Volatile
        var blocked: Boolean = false
    }

    private val entries = ConcurrentHashMap<Key, Tally>()
    private val admissionLock = Any()
    private var windowStart = Long.MIN_VALUE
    private var admittedInWindow = 0

    /** Number of distinct endpoints currently tracked. */
    public val size: Int get() = entries.size

    /**
     * Records one request to [host] (any case) with decoded [path] (`null` for host-only sinks)
     * at wall-clock [now]. Returns the endpoint exactly once per tracked entry (on its first
     * sighting) so the caller can log it once; `null` otherwise, including when the bounds refuse
     * a new entry.
     */
    public fun record(
        packageName: String,
        host: String?,
        path: String?,
        blocked: Boolean,
        now: Long,
    ): ObservedEndpoint? {
        val canonical = HostNames.normalize(host)?.takeIf(ObservedEndpoint::isValidHost) ?: return null
        val key = Key(packageName, canonical, ObservedEndpoint.pathPrefixOf(path))
        val tally = entries[key] ?: admit(key, now) ?: return null
        tally.pending.incrementAndGet()
        if (now > tally.lastSeen) tally.lastSeen = now
        if (blocked) tally.blocked = true
        return if (tally.announced.compareAndSet(false, true)) key.toEndpoint(tally, 1) else null
    }

    /**
     * Takes the counts accumulated since the last drain. Entries stay tracked (so they are not
     * re-logged as new); only their pending counts reset. Give the result back via [restore] if
     * delivery fails.
     */
    public fun drain(): List<ObservedEndpoint> = entries.mapNotNull { (key, tally) ->
        val count = tally.pending.getAndSet(0)
        if (count > 0) key.toEndpoint(tally, count) else null
    }

    /** Re-adds counts from a [drain] whose delivery failed. Unknown endpoints are ignored. */
    public fun restore(endpoints: List<ObservedEndpoint>) {
        endpoints.forEach { endpoint ->
            entries[Key(endpoint.packageName, endpoint.host, endpoint.pathPrefix)]?.pending?.addAndGet(endpoint.count)
        }
    }

    private fun admit(key: Key, now: Long): Tally? = synchronized(admissionLock) {
        entries[key]?.let { return it }
        if (entries.size >= maxEntries) return null
        if (windowStart == Long.MIN_VALUE || now - windowStart >= windowMillis || now < windowStart) {
            windowStart = now
            admittedInWindow = 0
        }
        if (admittedInWindow >= maxNewPerWindow) return null
        admittedInWindow++
        Tally(now).also { entries[key] = it }
    }

    private fun Key.toEndpoint(tally: Tally, count: Long) =
        ObservedEndpoint(packageName, host, pathPrefix, count, tally.firstSeen, tally.lastSeen, tally.blocked)

    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 200
        public const val DEFAULT_MAX_NEW_PER_WINDOW: Int = 50
        public const val DEFAULT_WINDOW_MILLIS: Long = 60_000L
    }
}
