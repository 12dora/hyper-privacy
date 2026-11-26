package io.github.hyperprivacy.core.match

import java.net.IDN
import java.util.Locale

/** Canonicalisation shared by rule patterns and looked-up hosts, so both sides compare equal. */
public object HostNames {
    private const val LABEL_SEPARATOR = '.'
    private const val MAX_ASCII = 0x7F

    /**
     * Returns the canonical ASCII lowercase form of [raw], or `null` if it is not a usable host.
     *
     * Tolerates surrounding whitespace and one trailing dot (FQDN form). Non-ASCII names are
     * converted to punycode first; lowercasing uses [Locale.ROOT] so that e.g. a Turkish default
     * locale cannot turn `I` into a dotless `ı` and silently defeat a match.
     */
    public fun normalize(raw: String?): String? {
        var host = raw?.trim() ?: return null
        if (host.endsWith(LABEL_SEPARATOR)) host = host.dropLast(1)
        if (host.any { it.code > MAX_ASCII }) host = toAscii(host) ?: return null
        host = host.lowercase(Locale.ROOT)
        return host.takeIf(::hasValidLabels)
    }

    private fun toAscii(host: String): String? = try {
        IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun hasValidLabels(host: String): Boolean =
        host.isNotEmpty() && host.split(LABEL_SEPARATOR).none { it.isEmpty() || it.any(Char::isWhitespace) }
}
