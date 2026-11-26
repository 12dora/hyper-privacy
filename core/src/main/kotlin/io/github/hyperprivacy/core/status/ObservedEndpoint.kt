package io.github.hyperprivacy.core.status

import io.github.hyperprivacy.core.match.HostNames

/**
 * A network endpoint seen by a sink while diagnostics ("observe") mode is on.
 *
 * Endpoints are deduplicated by ([host], [pathPrefix]); see [pathPrefixOf].
 *
 * @property pathPrefix first path segments ([pathPrefixOf]); `null` when the sink only sees the
 *   host (DNS, TLS SNI).
 * @property count requests seen; a delta on the wire, a running total once stored.
 * @property firstSeen wall-clock millis of the first sighting.
 * @property lastSeen wall-clock millis of the latest sighting.
 * @property blocked whether an enabled rule blocked at least one of these requests.
 */
public data class ObservedEndpoint(
    val packageName: String,
    val host: String,
    val pathPrefix: String?,
    val count: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    val blocked: Boolean,
) {
    public companion object {
        /** Number of leading path segments that identify an endpoint. */
        public const val PATH_SEGMENTS: Int = 2

        /** Upper bound on a stored path prefix, so hostile or random paths cannot bloat storage. */
        public const val MAX_PATH_LENGTH: Int = 128

        private const val ROOT = "/"
        private const val MAX_HOST_LENGTH = 253

        /**
         * The dedupe form of a decoded [path]: its first [PATH_SEGMENTS] segments without a
         * trailing slash (`/a/b/c?x` → `/a/b`, empty → `/`), cut at the first control character
         * and at [MAX_PATH_LENGTH]. `null` stays `null` (host-only sink).
         */
        public fun pathPrefixOf(path: String?): String? {
            if (path == null) return null
            if (!path.startsWith('/')) return ROOT
            val end = prefixEnd(path)
            val prefix = path.substring(0, minOf(end, MAX_PATH_LENGTH)).trimEnd('/')
            return prefix.ifEmpty { ROOT }
        }

        /** Index where the [PATH_SEGMENTS]-segment prefix of [path] ends. */
        private fun prefixEnd(path: String): Int {
            var slashes = 0
            for (i in path.indices) {
                val char = path[i]
                val boundary = char == '/' && i > 0 && ++slashes == PATH_SEGMENTS
                if (boundary || isTerminator(char)) return i
            }
            return path.length
        }

        private fun isTerminator(char: Char): Boolean = char.isISOControl() || char == '?' || char == '#'

        /** Whether [host] is in canonical form and free of characters that could break storage keys. */
        public fun isValidHost(host: String): Boolean =
            host.length <= MAX_HOST_LENGTH && HostNames.normalize(host) == host && host.all(::isHostChar)

        private fun isHostChar(char: Char): Boolean = char in 'a'..'z' || char in '0'..'9' || char in "-._:[]"
    }
}
