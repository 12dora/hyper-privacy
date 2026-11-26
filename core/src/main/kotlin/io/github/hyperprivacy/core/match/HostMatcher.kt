package io.github.hyperprivacy.core.match

/**
 * Immutable exact-or-dot-suffix host matcher.
 *
 * A pattern `example.com` matches `example.com` and `a.b.example.com`, but not `badexample.com`.
 * Lookup walks the host's label boundaries from most to least specific, i.e. O(labels) hash
 * probes. Instances never change after construction and are safe to share across threads.
 *
 * @param T value attached to each pattern, returned on a match (e.g. the owning rule id).
 */
public class HostMatcher<T : Any> private constructor(private val patterns: Map<String, T>) {
    public val isEmpty: Boolean get() = patterns.isEmpty()

    /** Returns the value of the most specific pattern covering [host], or `null`. */
    public fun find(host: String?): T? {
        val canonical = HostNames.normalize(host) ?: return null
        var start = 0
        while (true) {
            patterns[canonical.substring(start)]?.let { return it }
            val dot = canonical.indexOf('.', start)
            if (dot < 0) return null
            start = dot + 1
        }
    }

    public fun matches(host: String?): Boolean = find(host) != null

    public companion object {
        private val EMPTY = HostMatcher<Nothing>(emptyMap())

        @Suppress("UNCHECKED_CAST") // Safe: an empty matcher never yields a value.
        public fun <T : Any> empty(): HostMatcher<T> = EMPTY as HostMatcher<T>

        /**
         * Builds a matcher from (pattern, value) pairs. On duplicate patterns the first value wins.
         *
         * @throws IllegalArgumentException if any pattern is not a valid host name.
         */
        public fun <T : Any> of(entries: Iterable<Pair<String, T>>): HostMatcher<T> {
            val map = LinkedHashMap<String, T>()
            for ((pattern, value) in entries) {
                val canonical = requireNotNull(HostNames.normalize(pattern)) { "Invalid host pattern: '$pattern'" }
                map.putIfAbsent(canonical, value)
            }
            return if (map.isEmpty()) empty() else HostMatcher(map)
        }
    }
}
