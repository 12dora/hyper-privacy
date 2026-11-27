package io.github.hyperprivacy.core.net

import java.net.InetAddress

/** Allocation-free recognition and parsing of IP address literals, never touching a resolver. */
public object IpLiterals {
    private const val IPV4_PARTS = 4
    private const val MAX_OCTET = 255
    private const val MAX_OCTET_DIGITS = 3
    private const val DECIMAL = 10
    private const val IPV6_BYTES = 16
    private const val IPV6_GROUPS = 8
    private const val MAX_HEX_DIGITS = 4
    private const val HEX = 16
    private const val BYTE_BITS = 8

    /** Whether [text] is a dotted-quad IPv4 literal (`203.107.1.1`; no leading zeros beyond `0`). */
    public fun isIpv4(text: String?): Boolean = text != null && parseIpv4Into(text, null)

    /**
     * Whether [host] is an IP literal as URL hosts carry them: dotted-quad IPv4, or IPv6 with or
     * without brackets. Host names never contain ':', so any colon means IPv6.
     */
    public fun isIpLiteral(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val first = host[0]
        return first == '[' || host.indexOf(':') >= 0 || (first in '0'..'9' && isIpv4(host))
    }

    /** The IPv4 address of a dotted-quad [text], or `null`. Uses [InetAddress.getByAddress], so no lookup. */
    public fun parseIpv4(text: String): InetAddress? {
        val bytes = ByteArray(IPV4_PARTS)
        return if (parseIpv4Into(text, bytes)) InetAddress.getByAddress(bytes) else null
    }

    /**
     * The IPv6 address of [text] (RFC 4291 text form: hex groups, one `::`, an optional dotted-quad
     * tail; brackets allowed, no zone id), or `null`. Uses [InetAddress.getByAddress], so no lookup;
     * an IPv4-mapped literal yields the IPv4 address, as the platform reports such peers.
     */
    public fun parseIpv6(text: String): InetAddress? {
        val body = if (text.startsWith('[') && text.endsWith(']')) text.substring(1, text.length - 1) else text
        val bytes = ByteArray(IPV6_BYTES)
        return if (parseIpv6Into(body, bytes)) InetAddress.getByAddress(bytes) else null
    }

    /** [parseIpv4] or [parseIpv6], whichever form [text] has; `null` for a host name. */
    public fun parse(text: String): InetAddress? = if (text.indexOf(':') >= 0) parseIpv6(text) else parseIpv4(text)

    // One left-to-right pass filling groups into [out]; groups after `::` are shifted right at the end.
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ReturnCount")
    private fun parseIpv6Into(text: String, out: ByteArray): Boolean {
        if (text.length < 2) return false
        var groups = 0
        var gap = -1
        var i = 0
        if (text.startsWith("::")) {
            gap = 0
            i = 2
            if (i == text.length) return true
        }
        while (i < text.length) {
            if (groups == IPV6_GROUPS) return false
            var end = i
            var value = 0
            while (end < text.length && end - i < MAX_HEX_DIGITS) {
                val digit = hexDigit(text[end])
                if (digit < 0) break
                value = value * HEX + digit
                end++
            }
            if (end < text.length && text[end] == '.') {
                // Dotted-quad tail: the last 32 bits.
                if (groups > IPV6_GROUPS - 2) return false
                val tail = ByteArray(IPV4_PARTS)
                if (!parseIpv4Into(text.substring(i), tail)) return false
                tail.copyInto(out, groups * 2)
                groups += 2
                i = text.length
                break
            }
            if (end == i) return false
            out[groups * 2] = (value ushr BYTE_BITS).toByte()
            out[groups * 2 + 1] = value.toByte()
            groups++
            i = end
            if (i == text.length) break
            if (text[i] != ':') return false
            i++
            if (i < text.length && text[i] == ':') {
                if (gap >= 0) return false
                gap = groups
                i++
                if (i == text.length) break
            } else if (i == text.length) {
                return false
            }
        }
        if (gap < 0) return groups == IPV6_GROUPS
        if (groups == IPV6_GROUPS) return false
        // Move the groups after the gap to the end, zero-filling the gap.
        val moved = (groups - gap) * 2
        val from = gap * 2
        out.copyInto(out, IPV6_BYTES - moved, from, from + moved)
        out.fill(0, from, IPV6_BYTES - moved)
        return true
    }

    /** ASCII hex digit value, or -1 ([Character.digit] would accept fullwidth and other scripts' digits). */
    private fun hexDigit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + DECIMAL
        in 'A'..'F' -> char - 'A' + DECIMAL
        else -> -1
    }

    private fun parseIpv4Into(text: String, out: ByteArray?): Boolean {
        var part = 0
        var value = 0
        var digits = 0
        for (i in 0..text.length) {
            val char = if (i < text.length) text[i] else '.'
            if (char == '.') {
                if (digits == 0 || part >= IPV4_PARTS) return false
                out?.set(part, value.toByte())
                part++
                value = 0
                digits = 0
            } else {
                val leadingZero = digits == 1 && value == 0
                if (char !in '0'..'9' || digits == MAX_OCTET_DIGITS || leadingZero) return false
                value = value * DECIMAL + (char - '0')
                digits++
                if (value > MAX_OCTET) return false
            }
        }
        return part == IPV4_PARTS
    }
}
