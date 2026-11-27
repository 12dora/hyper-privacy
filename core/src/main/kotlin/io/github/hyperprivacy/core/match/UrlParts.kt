package io.github.hyperprivacy.core.match

import java.io.ByteArrayOutputStream

/**
 * The parts of a URL that URL rules look at: the canonical host, the percent-decoded path and,
 * only when asked for ([parse] `keepQuery`), the raw query string (without `?`). The fragment is
 * always dropped.
 */
public data class UrlParts(val host: String, val path: String, val query: String? = null) {
    public companion object {
        private const val SCHEME_SEPARATOR = "://"
        private const val HTTP_PREFIX = "http://"
        private const val HTTPS_PREFIX = "https://"
        private const val ROOT = "/"
        private const val HEX_RADIX = 16
        private const val ESCAPE_LENGTH = 3

        /**
         * Leniently parses an absolute hierarchical URL (`scheme://[user@]host[:port][/path][?q][#f]`).
         * Returns `null` for anything else (relative, opaque, missing or invalid host), which callers
         * treat as "no match". Unlike [java.net.URI] it tolerates characters apps leave unescaped.
         * The query is extracted only with [keepQuery] (when an enabled rule has a query condition),
         * so the common case allocates nothing for it.
         */
        public fun parse(url: String?, keepQuery: Boolean = false): UrlParts? {
            val text = url?.trim() ?: return null
            val schemeEnd = text.indexOf(SCHEME_SEPARATOR)
            if (schemeEnd <= 0 || !text.substring(0, schemeEnd).all(::isSchemeChar)) return null
            val authorityStart = schemeEnd + SCHEME_SEPARATOR.length
            val authorityEnd = indexOfAny(text, authorityStart, "/?#")
            val host = hostOf(text.substring(authorityStart, authorityEnd)) ?: return null
            val pathEnd = indexOfAny(text, authorityEnd, "?#")
            val rawPath = text.substring(authorityEnd, pathEnd)
            val query = if (keepQuery && pathEnd < text.length && text[pathEnd] == '?') {
                text.substring(pathEnd + 1, indexOfAny(text, pathEnd + 1, "#"))
            } else {
                null
            }
            return UrlParts(host, decodePath(rawPath.ifEmpty { ROOT }), query)
        }

        /**
         * Whether [url] is an `http:` / `https:` URL. Allocation-free, so hot paths can skip `data:`,
         * `blob:` and other schemes (which may be megabytes long) before [parse].
         */
        public fun isHttp(url: String): Boolean =
            url.startsWith(HTTPS_PREFIX, ignoreCase = true) || url.startsWith(HTTP_PREFIX, ignoreCase = true)

        /**
         * Decodes `%XX` escapes as UTF-8. Malformed escapes are kept literally; `+` is left alone
         * because it only means a space in query strings.
         */
        public fun decodePath(raw: String): String {
            if ('%' !in raw) return raw
            val out = ByteArrayOutputStream(raw.length)
            var i = 0
            while (i < raw.length) {
                val byte = if (raw[i] == '%') hexByte(raw, i + 1) else null
                if (byte != null) {
                    out.write(byte)
                    i += ESCAPE_LENGTH
                } else {
                    out.write(raw.substring(i, i + 1).toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /**
         * The canonical host of an HTTP `Host` header value (`host`, `host:port`, `[v6]:port`), or
         * `null` if it is not a usable host.
         */
        public fun hostOfHeader(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.let(::hostOf)

        private fun hostOf(authority: String): String? {
            val hostAndPort = authority.substringAfterLast('@')
            val host = if (hostAndPort.startsWith('[')) {
                val close = hostAndPort.indexOf(']')
                if (close < 0) return null
                hostAndPort.substring(0, close + 1)
            } else {
                hostAndPort.substringBefore(':')
            }
            return HostNames.normalize(host)
        }

        private fun hexByte(text: String, at: Int): Int? {
            if (at + 1 >= text.length) return null
            val high = Character.digit(text[at], HEX_RADIX)
            val low = Character.digit(text[at + 1], HEX_RADIX)
            return if (high < 0 || low < 0) null else high * HEX_RADIX + low
        }

        private fun indexOfAny(text: String, from: Int, chars: String): Int {
            for (i in from until text.length) if (text[i] in chars) return i
            return text.length
        }

        private fun isSchemeChar(char: Char): Boolean = char.isLetterOrDigit() || char in "+-."
    }
}
