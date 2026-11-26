package io.github.hyperprivacy.core.tracker

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Finds tracker SDKs in an APK by reading the class names it declares or references, without
 * loading any code: for each `classes*.dex` entry, the dex `type_ids` table is resolved through
 * `string_ids` to type descriptors, which are matched by a [CodePrefixMatcher].
 *
 * Streaming and forward-only, so a compressed entry is inflated once and never held in memory.
 * Memory is bounded by the dex string count (4 bytes per string, at most [MAX_STRINGS]) and the
 * type count (at most 65 536). Malformed, truncated or unsupported dex files contribute what was
 * read before the problem (possibly nothing); packed apps that encrypt their dex show no SDKs.
 */
public object DexScanner {
    /** Upper bound on `string_ids_size` we accept (8 MiB of offsets); larger dex files are skipped. */
    public const val MAX_STRINGS: Int = 1 shl 21

    private const val MAX_TYPES = 1 shl 16
    private const val HEADER_SIZE = 0x70
    private const val ENDIAN_CONSTANT = 0x12345678
    private const val OFF_ENDIAN = 0x28
    private const val OFF_STRING_IDS = 0x38
    private const val OFF_TYPE_IDS = 0x40
    private const val INT_BYTES = 4

    /** Descriptor bytes kept for matching; prefixes are far shorter, the rest is skipped. */
    private const val MAX_DESCRIPTOR = 256
    private const val BUFFER = 64 * 1024
    private val DEX_ENTRY = Regex("classes[0-9]*\\.dex")
    private val MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())

    /**
     * Ids from [matcher] found in any dex of [apks] (base and split APKs). An unreadable APK is
     * skipped; [IOException] is only thrown when none could be read at all.
     */
    public fun scanApks(apks: List<File>, matcher: CodePrefixMatcher = Trackers.matcher): Set<String> {
        val found = BooleanArray(matcher.ids.size)
        var failure: IOException? = null
        var anyRead = false
        for (apk in apks) {
            try {
                scanApk(apk, matcher, found)
                anyRead = true
            } catch (e: IOException) {
                failure = e
            }
        }
        if (!anyRead && failure != null) throw failure
        return matcher.ids.filterIndexedTo(sortedSetOf()) { i, _ -> found[i] }
    }

    private fun scanApk(apk: File, matcher: CodePrefixMatcher, found: BooleanArray) {
        ZipFile(apk).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && DEX_ENTRY.matches(it.name) }
                .sortedBy { it.name }
                .toList()
            for (entry in dexEntries) {
                zip.getInputStream(entry).use { scanDex(it, matcher, found) }
            }
        }
    }

    /** Marks in [found] (indexed like [CodePrefixMatcher.ids]) every owner matched by a type in one dex. */
    public fun scanDex(input: InputStream, matcher: CodePrefixMatcher, found: BooleanArray) {
        val reader = Reader(BufferedInputStream(input, BUFFER))
        val header = ByteArray(HEADER_SIZE)
        if (!reader.readFully(header) || !isDex(header)) return
        val stringCount = header.intAt(OFF_STRING_IDS)
        val stringOff = header.intAt(OFF_STRING_IDS + INT_BYTES).toLong() and UINT
        val typeCount = header.intAt(OFF_TYPE_IDS)
        val typeOff = header.intAt(OFF_TYPE_IDS + INT_BYTES).toLong() and UINT
        if (stringCount !in 0..MAX_STRINGS || typeCount !in 0..MAX_TYPES || typeCount == 0) return
        val offsets = typeStringOffsets(reader, stringOff, stringCount, typeOff, typeCount) ?: return
        offsets.sort()
        val buffer = ByteArray(MAX_DESCRIPTOR)
        var previous = -1L
        for (raw in offsets) {
            val offset = raw.toLong() and UINT
            if (offset == previous || offset < reader.position) continue
            previous = offset
            if (!reader.skipTo(offset)) return
            val length = reader.readDescriptor(buffer) ?: return
            val owner = matcher.matchDescriptor(buffer, 0, length)
            if (owner >= 0) found[owner] = true
        }
    }

    /** `string_data_off` of every type's descriptor, reading both id tables in file order. */
    private fun typeStringOffsets(
        reader: Reader,
        stringOff: Long,
        stringCount: Int,
        typeOff: Long,
        typeCount: Int,
    ): IntArray? {
        val strings: IntArray
        val types: IntArray
        if (stringOff <= typeOff) {
            strings = reader.readInts(stringOff, stringCount) ?: return null
            types = reader.readInts(typeOff, typeCount) ?: return null
        } else {
            types = reader.readInts(typeOff, typeCount) ?: return null
            strings = reader.readInts(stringOff, stringCount) ?: return null
        }
        val out = IntArray(typeCount)
        for (i in 0 until typeCount) {
            val index = types[i]
            if (index !in 0 until stringCount) return null
            out[i] = strings[index]
        }
        return out
    }

    private fun isDex(header: ByteArray): Boolean =
        (MAGIC.indices).all { header[it] == MAGIC[it] } && header.intAt(OFF_ENDIAN) == ENDIAN_CONSTANT

    private fun ByteArray.intAt(offset: Int): Int = (this[offset].toInt() and BYTE) or
        ((this[offset + 1].toInt() and BYTE) shl BITS_1) or
        ((this[offset + 2].toInt() and BYTE) shl BITS_2) or
        ((this[offset + LAST_BYTE].toInt() and BYTE) shl BITS_3)

    private const val UINT = 0xFFFF_FFFFL
    private const val BYTE = 0xFF
    private const val LAST_BYTE = 3
    private const val BITS_1 = 8
    private const val BITS_2 = 16
    private const val BITS_3 = 24
    private const val ULEB_MORE = 0x80
    private const val ULEB_MAX_BYTES = 5

    /** Forward-only little-endian reader that tracks its offset in the dex. */
    private class Reader(private val input: InputStream) {
        var position: Long = 0
            private set

        fun readFully(target: ByteArray): Boolean {
            var read = 0
            while (read < target.size) {
                val n = input.read(target, read, target.size - read)
                if (n < 0) return false
                read += n
            }
            position += target.size
            return true
        }

        fun skipTo(offset: Long): Boolean {
            while (position < offset) {
                val skipped = input.skip(offset - position)
                if (skipped > 0) {
                    position += skipped
                } else {
                    if (input.read() < 0) return false
                    position++
                }
            }
            return position == offset
        }

        fun readInts(offset: Long, count: Int): IntArray? {
            if (offset < position || !skipTo(offset)) return null
            val out = IntArray(count)
            val chunk = ByteArray(INT_BYTES * CHUNK_INTS)
            var done = 0
            while (done < count) {
                val n = minOf(CHUNK_INTS, count - done)
                val bytes = if (n == CHUNK_INTS) chunk else ByteArray(n * INT_BYTES)
                if (!readFully(bytes)) return null
                for (i in 0 until n) out[done + i] = bytes.intAt(i * INT_BYTES)
                done += n
            }
            return out
        }

        /**
         * A `string_data_item`: ULEB128 UTF-16 length, then modified UTF-8 up to a NUL. Keeps the
         * first `target.size` bytes; returns how many were kept, or `null` at end of input.
         */
        fun readDescriptor(target: ByteArray): Int? {
            var lengthBytes = 0
            while (true) {
                val b = next() ?: return null
                if (b and ULEB_MORE == 0) break
                if (++lengthBytes >= ULEB_MAX_BYTES) return null
            }
            var kept = 0
            while (true) {
                val b = next() ?: return null
                if (b == 0) return kept
                // Matching needs only the prefix; once full, stop and let the next skip move on.
                if (kept == target.size) return kept
                target[kept++] = b.toByte()
            }
        }

        private fun next(): Int? {
            val b = input.read()
            if (b < 0) return null
            position++
            return b
        }

        private companion object {
            const val CHUNK_INTS = 4096
        }
    }
}
