package io.github.hyperprivacy.core.net

import java.net.InetAddress

/** Allocation-free recognition and parsing of IP address literals, never touching a resolver. */
public object IpLiterals {
    private const val IPV4_PARTS = 4
    private const val MAX_OCTET = 255
    private const val MAX_OCTET_DIGITS = 3
    private const val DECIMAL = 10

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
