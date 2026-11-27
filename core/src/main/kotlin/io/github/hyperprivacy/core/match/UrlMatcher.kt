package io.github.hyperprivacy.core.match

/**
 * One URL block pattern. [host] matches exactly or as a dot-suffix (like [HostMatcher]);
 * [pathPrefix] `null` blocks the whole host, otherwise the decoded request path must start with
 * it (plain, case-sensitive string prefix: `/track` also covers `/tracking`; add a trailing `/`
 * to stop at a segment boundary). [query], if set, is one `key=value` parameter the raw query
 * string must contain as a whole `&`-separated segment ([UrlQuery.hasParam]); it needs a path.
 */
public data class UrlPattern(val host: String, val pathPrefix: String? = null, val query: String? = null) {
    init {
        require(pathPrefix == null || pathPrefix.startsWith('/')) { "pathPrefix must start with '/': $pathPrefix" }
        require(query == null || (pathPrefix != null && UrlQuery.isParam(query))) { "invalid query parameter: $query" }
    }
}

/** Query-string checks for [UrlPattern.query]; allocation-free, so they are safe on hook hot paths. */
public object UrlQuery {
    /** Whether [param] is a usable `key=value` condition: non-empty key, no separators. */
    public fun isParam(param: String): Boolean {
        val eq = param.indexOf('=')
        return eq > 0 && param.none { it == '&' || it == '#' || it == '?' || it.isWhitespace() }
    }

    /**
     * Whether the raw (still percent-encoded) [query] contains [param] as one whole `&`-separated
     * segment: `action=zubc` matches `a=1&action=zubc`, not `action=zubc2` or `xaction=zubc`.
     */
    public fun hasParam(query: String?, param: String): Boolean {
        if (query == null || query.length < param.length) return false
        var start = 0
        while (start <= query.length) {
            var end = query.indexOf('&', start)
            if (end < 0) end = query.length
            if (end - start == param.length && query.regionMatches(start, param, 0, param.length)) return true
            start = end + 1
        }
        return false
    }
}

/**
 * Immutable host + path-prefix matcher, safe to share across threads.
 *
 * Lookup walks host suffixes from most to least specific; at each level the longest matching path
 * prefix wins (a pattern with a [UrlPattern.query] condition before the same prefix without one)
 * and a host-only pattern is the fallback. Hosts are case-insensitive, paths and queries are not;
 * the query string is consulted only by patterns with a query condition.
 *
 * @param T value attached to each pattern, returned on a match (e.g. the owning rule id).
 */
public class UrlMatcher<T : Any> private constructor(
    private val hosts: SuffixTable<List<PathEntry<T>>>,
    /** Whether any pattern has a query condition, i.e. callers must pass the query to [find]. */
    public val matchesQueries: Boolean,
) {
    /** One path rule under a host; [prefix] is empty for a host-only pattern. */
    private class PathEntry<T : Any>(val prefix: String, val query: String?, val value: T)

    public val isEmpty: Boolean get() = hosts.isEmpty

    /** Parses [url] with [UrlParts.parse] and matches it; `null` if unparsable or allowed. */
    public fun find(url: String?): T? {
        if (hosts.isEmpty) return null
        val parts = UrlParts.parse(url, keepQuery = matchesQueries) ?: return null
        return find(parts.host, parts.path, parts.query)
    }

    /**
     * Matches a [host] (any case, normalised here), an already percent-decoded [path] (`null` or
     * empty means `/`) and the raw [query] (without `?`; only read when [matchesQueries]).
     */
    public fun find(host: String?, path: String?, query: String? = null): T? {
        if (hosts.isEmpty) return null
        val canonical = HostNames.normalize(host) ?: return null
        val effectivePath = if (path.isNullOrEmpty()) ROOT else path
        var start = 0
        while (true) {
            hosts.get(canonical, start)?.let { entries -> match(entries, effectivePath, query)?.let { return it } }
            val dot = canonical.indexOf('.', start)
            if (dot < 0) return null
            start = dot + 1
        }
    }

    // Indexed loop: no iterator allocation on the lookup path.
    private fun match(entries: List<PathEntry<T>>, path: String, query: String?): T? {
        for (i in entries.indices) {
            val entry = entries[i]
            if (!path.startsWith(entry.prefix)) continue
            if (entry.query == null || UrlQuery.hasParam(query, entry.query)) return entry.value
        }
        return null
    }

    public companion object {
        private const val ROOT = "/"
        private val EMPTY = UrlMatcher<Nothing>(SuffixTable(emptyMap()), matchesQueries = false)

        @Suppress("UNCHECKED_CAST") // Safe: an empty matcher never yields a value.
        public fun <T : Any> empty(): UrlMatcher<T> = EMPTY as UrlMatcher<T>

        /**
         * Builds a matcher from (pattern, value) pairs. On duplicate patterns the first value wins.
         *
         * @throws IllegalArgumentException if any host is not a valid host name.
         */
        public fun <T : Any> of(entries: Iterable<Pair<UrlPattern, T>>): UrlMatcher<T> {
            val byHost = LinkedHashMap<String, LinkedHashMap<PathKey, T>>()
            for ((pattern, value) in entries) {
                val host = HostNames.requireCanonical(pattern.host)
                val key = PathKey(pattern.pathPrefix.orEmpty(), pattern.query)
                byHost.getOrPut(host) { LinkedHashMap() }.putIfAbsent(key, value)
            }
            if (byHost.isEmpty()) return empty()
            val table = byHost.mapValues { (_, prefixes) ->
                // Longest prefix first, a query condition before the same prefix without one; the
                // empty (host-only) prefix therefore always comes last.
                val order = compareByDescending<Map.Entry<PathKey, T>> { it.key.prefix.length }
                    .thenBy { it.key.query == null }
                prefixes.entries.sortedWith(order).map { PathEntry(it.key.prefix, it.key.query, it.value) }
            }
            val matchesQueries = table.values.any { list -> list.any { it.query != null } }
            return UrlMatcher(SuffixTable(table), matchesQueries)
        }

        /** Deduplication key of a pattern under one host. */
        private data class PathKey(val prefix: String, val query: String?)
    }
}
