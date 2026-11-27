package io.github.hyperprivacy.core.match

import io.github.hyperprivacy.core.net.IpLiterals
import java.io.ByteArrayOutputStream

/**
 * Encodes the host-level patterns of a [NetworkMatchers] snapshot for the native resolver hooks
 * (`app/src/main/cpp/host_table.h`), which rebuild the same most-specific-wins suffix matcher.
 *
 * Layout, little endian: `"HPT1"`, u32 entry count, then per entry a flags byte (bit 0: block,
 * clear: keep), a length byte and the canonical ASCII pattern. IP literals are left out: native
 * lookups of numeric hosts always pass through, so they could never match. Patterns are already
 * canonical ([HostNames.normalize]); one that is not, or is too long, is skipped here and would be
 * skipped by the native parser as well.
 */
public object NativeHostTable {
    private const val MAGIC = "HPT1"
    private const val MAX_LENGTH = 253
    private const val MAX_ENTRIES = 65_535
    private const val BLOCK: Int = 1
    private const val KEEP: Int = 0
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private const val INT_BYTES = 4
    private const val MAX_ASCII = 0x7E

    /**
     * The table for [matchers]; an empty array when there is nothing to block (no table at all).
     * Entries are sorted, so equal rule sets always encode to equal bytes.
     */
    public fun encode(matchers: NetworkMatchers): ByteArray {
        val entries = ArrayList<Pair<String, Boolean>>()
        matchers.forEachHostPattern { pattern, blocked ->
            if (usable(pattern)) entries += pattern to blocked
        }
        entries.sortBy { it.first }
        return encode(entries)
    }

    /** [encode] for explicit (pattern, blocked) pairs; keep entries alone encode to nothing. */
    public fun encode(entries: List<Pair<String, Boolean>>): ByteArray {
        val usable = entries.filter { usable(it.first) }.take(MAX_ENTRIES)
        if (usable.none { it.second }) return ByteArray(0)
        val out = ByteArrayOutputStream(INT_BYTES * 2 + usable.sumOf { it.first.length + 2 })
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        writeInt(out, usable.size)
        for ((pattern, blocked) in usable) {
            out.write(if (blocked) BLOCK else KEEP)
            out.write(pattern.length)
            out.write(pattern.toByteArray(Charsets.US_ASCII))
        }
        return out.toByteArray()
    }

    private fun usable(pattern: String): Boolean = pattern.length in 1..MAX_LENGTH &&
        pattern.all { it.code in '!'.code..MAX_ASCII && it !in 'A'..'Z' } &&
        !IpLiterals.isIpLiteral(pattern)

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        for (i in 0 until INT_BYTES) out.write((value ushr (i * BYTE_BITS)) and BYTE_MASK)
    }
}
