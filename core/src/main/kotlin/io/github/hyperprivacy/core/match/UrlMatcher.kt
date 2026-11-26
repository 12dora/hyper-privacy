package io.github.hyperprivacy.core.match

/**
 * One URL block pattern. [host] matches exactly or as a dot-suffix (like [HostMatcher]);
 * [pathPrefix] `null` blocks the whole host, otherwise the decoded request path must start with
 * it (plain, case-sensitive string prefix: `/track` also covers `/tracking`; add a trailing `/`
 * to stop at a segment boundary).
 */
public data class UrlPattern(val host: String, val pathPrefix: String? = null) {
    init {
        require(pathPrefix == null || pathPrefix.startsWith('/')) { "pathPrefix must start with '/': $pathPrefix" }
    }
}

/**
 * Immutable host + path-prefix matcher, safe to share across threads.
 *
 * Lookup walks host suffixes from most to least specific; at each level the longest matching path
 * prefix wins and a host-only pattern is the fallback. Hosts are case-insensitive, paths are not,
 * and the query string is never consulted.
 *
 * @param T value attached to each pattern, returned on a match (e.g. the owning rule id).
 */
public class UrlMatcher<T : Any> private constructor(private val hosts: SuffixTable<List<PathEntry<T>>>) {
    /** One path rule under a host; [prefix] is empty for a host-only pattern. */
    private class PathEntry<T : Any>(val prefix: String, val value: T)

    public val isEmpty: Boolean get() = hosts.isEmpty

    /** Parses [url] with [UrlParts.parse] and matches it; `null` if unparsable or allowed. */
    public fun find(url: String?): T? {
        if (hosts.isEmpty) return null
        val parts = UrlParts.parse(url) ?: return null
        return find(parts.host, parts.path)
    }

    /**
     * Matches a [host] (any case, normalised here) and an already percent-decoded [path]
     * (`null` or empty means `/`).
     */
    public fun find(host: String?, path: String?): T? {
        if (hosts.isEmpty) return null
        val canonical = HostNames.normalize(host) ?: return null
        val effectivePath = if (path.isNullOrEmpty()) ROOT else path
        var start = 0
        while (true) {
            hosts.get(canonical, start)?.let { entries -> match(entries, effectivePath)?.let { return it } }
            val dot = canonical.indexOf('.', start)
            if (dot < 0) return null
            start = dot + 1
        }
    }

    // Indexed loop: no iterator allocation on the lookup path.
    private fun match(entries: List<PathEntry<T>>, path: String): T? {
        for (i in entries.indices) if (path.startsWith(entries[i].prefix)) return entries[i].value
        return null
    }

    public companion object {
        private const val ROOT = "/"
        private val EMPTY = UrlMatcher<Nothing>(SuffixTable(emptyMap()))

        @Suppress("UNCHECKED_CAST") // Safe: an empty matcher never yields a value.
        public fun <T : Any> empty(): UrlMatcher<T> = EMPTY as UrlMatcher<T>

        /**
         * Builds a matcher from (pattern, value) pairs. On duplicate patterns the first value wins.
         *
         * @throws IllegalArgumentException if any host is not a valid host name.
         */
        public fun <T : Any> of(entries: Iterable<Pair<UrlPattern, T>>): UrlMatcher<T> {
            val byHost = LinkedHashMap<String, LinkedHashMap<String, T>>()
            for ((pattern, value) in entries) {
                val host = HostNames.requireCanonical(pattern.host)
                byHost.getOrPut(host) { LinkedHashMap() }.putIfAbsent(pattern.pathPrefix.orEmpty(), value)
            }
            if (byHost.isEmpty()) return empty()
            val table = byHost.mapValues { (_, prefixes) ->
                // Longest prefix first; the empty (host-only) prefix therefore always comes last.
                prefixes.entries.sortedByDescending { it.key.length }.map { PathEntry(it.key, it.value) }
            }
            return UrlMatcher(SuffixTable(table))
        }
    }
}
