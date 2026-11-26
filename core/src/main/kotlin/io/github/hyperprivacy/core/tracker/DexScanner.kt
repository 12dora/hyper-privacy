package io.github.hyperprivacy.core.tracker

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Finds tracker SDKs in an APK by reading the class names it declares or references, without
 * loading any code: for each `classes*.dex` entry, the dex `type_ids` table is resolved through
 * `string_ids` to type descriptors, which are matched by a [CodePrefixMatcher].
 *
 * Streaming and forward-only, so a compressed entry is inflated once and never held in memory.
 * Every entry is read as a container of dex sections: a classic dex (035 to 040) is one section;
 * a 041 container holds several concatenated sections, each with its own header, where
 * `file_size` is the distance to the next header and `container_size` the size of the whole
 * entry. All offsets, in both formats, are relative to the start of the entry (the physical
 * file), never to a section header; a section may reference string data later in the container
 * but not earlier, so one forward pass serves every section: the type descriptors' string
 * offsets of all sections read so far are kept sorted (unsigned) and read as the stream passes
 * them, interleaved with the next section's header and id tables.
 *
 * Bounded: at most [MAX_SECTION_BYTES] per section, [MAX_ENTRY_BYTES] inflated per entry and
 * [MAX_APK_BYTES] inflated per APK; `string_ids_size` is capped by [MAX_STRINGS] and by the
 * section size, the type count by 65 536 per section, and pending offsets across sections by
 * [MAX_PENDING]. Reaching a bound, or a malformed, truncated or unsupported dex, ends that entry
 * with what was found before the problem (possibly nothing); packed apps that encrypt their dex
 * show no SDKs.
 */
public object DexScanner {
    /** Upper bound on `string_ids_size` we accept (8 MiB of offsets); larger sections are skipped. */
    public const val MAX_STRINGS: Int = 1 shl 21

    /** Bytes of one dex section that are scanned; a larger `file_size` ends the entry at this bound. */
    public const val MAX_SECTION_BYTES: Long = 128L shl 20

    /** Inflated bytes read from one `classes*.dex` entry (a whole 041 container). */
    public const val MAX_ENTRY_BYTES: Long = 256L shl 20

    /** Inflated bytes read across all dex entries of one APK. */
    public const val MAX_APK_BYTES: Long = 1L shl 30

    /** Type descriptor offsets waiting to be read, across the sections of one entry (8 MiB). */
    public const val MAX_PENDING: Int = 1 shl 20

    private const val MAX_TYPES = 1 shl 16
    private const val HEADER_SIZE = 0x70
    private const val CONTAINER_HEADER_EXTRA = 0x08
    private const val CONTAINER_VERSION = 41
    private const val ENDIAN_CONSTANT = 0x12345678
    private const val OFF_VERSION = 4
    private const val VERSION_DIGITS = 3
    private const val DECIMAL = 10
    private const val OFF_FILE_SIZE = 0x20
    private const val OFF_ENDIAN = 0x28
    private const val OFF_STRING_IDS = 0x38
    private const val OFF_TYPE_IDS = 0x40
    private const val OFF_CONTAINER_SIZE = 0x00
    private const val OFF_HEADER_OFFSET = 0x04
    private const val INT_BYTES = 4

    /** Descriptor bytes kept for matching; prefixes are far shorter, the rest is skipped. */
    private const val MAX_DESCRIPTOR = 256
    private const val BUFFER = 64 * 1024
    private val DEX_ENTRY = Regex("classes[0-9]*\\.dex")
    private val MAGIC = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())

    /**
     * Ids from [matcher] found in any dex of [apks]: the base APK first, then its splits.
     *
     * An APK counts as read when it opened as a zip and either has no dex entry (a resource-only
     * split) or at least one of its dex entries could be read; a corrupt `classesN.dex` does not
     * stop the others. If the base APK (the first file) is not read, or [apks] is empty, throws
     * [IOException] even when splits were read, since the result would hide the base code. An
     * unreadable split is skipped. Never throws anything but [IOException] for bad input.
     */
    public fun scanApks(apks: List<File>, matcher: CodePrefixMatcher = Trackers.matcher): Set<String> {
        if (apks.isEmpty()) throw IOException("No APK to scan")
        val found = BooleanArray(matcher.ids.size)
        apks.forEachIndexed { index, apk ->
            try {
                scanApk(apk, matcher, found)
            } catch (e: IOException) {
                if (index == 0) throw e
            }
        }
        return matcher.ids.filterIndexedTo(sortedSetOf()) { i, _ -> found[i] }
    }

    /** Scans every dex entry of [apk]; throws [IOException] when the APK does not count as read. */
    @Suppress("TooGenericExceptionCaught")
    private fun scanApk(apk: File, matcher: CodePrefixMatcher, found: BooleanArray) {
        val zip = unchecked("Cannot open $apk") { ZipFile(apk) }
        zip.use {
            val dexEntries = unchecked("Cannot list $apk") {
                zip.entries().asSequence()
                    .filter { !it.isDirectory && DEX_ENTRY.matches(it.name) }
                    .sortedBy { it.name }
                    .toList()
            }
            if (dexEntries.isEmpty()) return
            val budget = Budget(MAX_APK_BYTES)
            var read = 0
            var failure: Exception? = null
            for (entry in dexEntries) {
                // The APK budget is spent: later entries could only be opened, not read.
                if (budget.remaining <= 0) break
                // Any failure (a corrupt deflate stream, a bad entry) loses only this entry.
                try {
                    zip.getInputStream(entry).use { scanDex(it, matcher, found, budget) }
                    read++
                } catch (e: Exception) {
                    failure = e
                }
            }
            if (read == 0) throw IOException("No readable dex in $apk", failure)
        }
    }

    // ZipFile reports bad archives through unchecked exceptions too (IllegalArgumentException for
    // entry names that are not valid UTF-8, IllegalStateException, SecurityException); all of
    // them mean "this APK could not be read" and are rethrown as IOException.
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> unchecked(message: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(message, e)
    }

    /**
     * Marks in [found] (indexed like [CodePrefixMatcher.ids]) every owner matched by a type in one
     * dex entry (a classic dex or a 041 container), reading at most [MAX_ENTRY_BYTES] of [input].
     * Malformed input ends the scan quietly; only [input]'s own [IOException]s propagate.
     */
    public fun scanDex(input: InputStream, matcher: CodePrefixMatcher, found: BooleanArray) {
        scanDex(input, matcher, found, Budget(MAX_ENTRY_BYTES))
    }

    private fun scanDex(input: InputStream, matcher: CodePrefixMatcher, found: BooleanArray, apkBudget: Budget) {
        val limited = LimitedInputStream(input, minOf(MAX_ENTRY_BYTES, apkBudget.remaining), apkBudget)
        val walk = Walk(Reader(BufferedInputStream(limited, BUFFER)), matcher, found)
        walk.run()
    }

    /** One forward pass over a dex entry: section headers, id tables and descriptors in file order. */
    private class Walk(private val reader: Reader, matcher: CodePrefixMatcher, found: BooleanArray) {
        private val pending = Pending(reader, matcher, found)
        private var containerEnd = Long.MAX_VALUE

        fun run() {
            var start = 0L
            var more = true
            while (more && start < containerEnd) {
                if (!pending.drainBefore(start) || !reader.skipTo(start)) return
                val section = readHeader(start)
                more = section != null && readTables(section) && !section.last
                if (section != null) start = section.end
            }
            pending.drainBefore(containerEnd)
        }

        /** Header of the section at [start] (the current position), or `null` when it is unusable. */
        private fun readHeader(start: Long): Section? {
            val header = ByteArray(HEADER_SIZE)
            if (!reader.readFully(header) || !isDex(header)) return null
            val container = versionOf(header) >= CONTAINER_VERSION
            // Only a 041 container has sections after the first; each must say where it starts.
            val containerSize = if (container) readContainerSize(start) ?: return null else 0L
            if (!container && start != 0L) return null
            val fileSize = header.uintAt(OFF_FILE_SIZE)
            if (start == 0L) {
                // A classic dex is a container of one section; it ends where its only section does.
                val size = if (container) containerSize else minOf(fileSize, MAX_SECTION_BYTES)
                containerEnd = minOf(size, MAX_ENTRY_BYTES)
            }
            val end = minOf(start + minOf(fileSize, MAX_SECTION_BYTES), containerEnd)
            if (end <= reader.position) return null
            return Section(
                end = end,
                last = !container || fileSize > MAX_SECTION_BYTES || end >= containerEnd,
                stringCount = header.uintAt(OFF_STRING_IDS),
                stringOff = header.uintAt(OFF_STRING_IDS + INT_BYTES),
                typeCount = header.uintAt(OFF_TYPE_IDS),
                typeOff = header.uintAt(OFF_TYPE_IDS + INT_BYTES),
            )
        }

        /** `container_size` from the 041 header tail, or `null` if `header_offset` is not [start]. */
        private fun readContainerSize(start: Long): Long? {
            val extra = ByteArray(CONTAINER_HEADER_EXTRA)
            if (!reader.readFully(extra) || extra.uintAt(OFF_HEADER_OFFSET) != start) return null
            return extra.uintAt(OFF_CONTAINER_SIZE)
        }

        /** Reads both id tables and queues the type descriptors; false to stop the entry. */
        private fun readTables(section: Section): Boolean {
            val maxIds = (section.end - reader.position) / INT_BYTES
            if (section.stringCount > minOf(MAX_STRINGS.toLong(), maxIds)) return false
            if (section.typeCount > minOf(MAX_TYPES.toLong(), maxIds)) return false
            if (section.typeCount == 0L) return true
            val stringCount = section.stringCount.toInt()
            val typeCount = section.typeCount.toInt()
            if (!fits(section.stringOff, stringCount, section) || !fits(section.typeOff, typeCount, section)) {
                return false
            }
            val (strings, types) = readIdTables(section, stringCount, typeCount) ?: return false
            val offsets = LongArray(typeCount)
            var n = 0
            for (index in types) {
                // Unsigned: a negative int is an index >= 2^31, never valid.
                if (index !in 0 until stringCount) continue
                val offset = strings[index].toLong() and UINT
                if (offset >= reader.position && offset < containerEnd) offsets[n++] = offset
            }
            return pending.add(offsets, n)
        }

        /** `string_ids` and `type_ids`, read in file order. */
        private fun readIdTables(section: Section, stringCount: Int, typeCount: Int): Pair<IntArray, IntArray>? {
            if (section.stringOff <= section.typeOff) {
                val strings = readInts(section.stringOff, stringCount) ?: return null
                val types = readInts(section.typeOff, typeCount) ?: return null
                return strings to types
            }
            val types = readInts(section.typeOff, typeCount) ?: return null
            val strings = readInts(section.stringOff, stringCount) ?: return null
            return strings to types
        }

        private fun fits(offset: Long, count: Int, section: Section): Boolean =
            count == 0 || (offset >= reader.position && offset + count.toLong() * INT_BYTES <= section.end)

        private fun readInts(offset: Long, count: Int): IntArray? {
            if (count == 0) return IntArray(0)
            if (!pending.drainBefore(offset)) return null
            return reader.readInts(offset, count)
        }
    }

    private class Section(
        val end: Long,
        val last: Boolean,
        val stringCount: Long,
        val stringOff: Long,
        val typeCount: Long,
        val typeOff: Long,
    )

    /** Sorted, distinct descriptor offsets not yet read; [next] is the first unread one. */
    private class Pending(
        private val reader: Reader,
        private val matcher: CodePrefixMatcher,
        private val found: BooleanArray,
    ) {
        private var offsets = LongArray(0)
        private var next = 0
        private val buffer = ByteArray(MAX_DESCRIPTOR)

        /** Merges the first [count] of [added] in; false (nothing added) when over [MAX_PENDING]. */
        fun add(added: LongArray, count: Int): Boolean {
            if (count == 0) return true
            if (offsets.size - next + count > MAX_PENDING) return false
            added.sort(0, count)
            val merged = LongArray(offsets.size - next + count)
            var i = next
            var j = 0
            var n = 0
            while (i < offsets.size || j < count) {
                val takeOld = j == count || (i < offsets.size && offsets[i] <= added[j])
                val value = if (takeOld) offsets[i++] else added[j++]
                if (n == 0 || merged[n - 1] != value) merged[n++] = value
            }
            offsets = merged.copyOf(n)
            next = 0
            return true
        }

        /** Reads every pending descriptor before [limit]; false at end of input. */
        fun drainBefore(limit: Long): Boolean {
            while (next < offsets.size && offsets[next] < limit) {
                val offset = offsets[next++]
                if (offset < reader.position) continue
                if (!reader.skipTo(offset)) return false
                val length = reader.readDescriptor(buffer) ?: return false
                val owner = matcher.matchDescriptor(buffer, 0, length)
                if (owner >= 0) found[owner] = true
            }
            return true
        }
    }

    private fun isDex(header: ByteArray): Boolean =
        (MAGIC.indices).all { header[it] == MAGIC[it] } && header.intAt(OFF_ENDIAN) == ENDIAN_CONSTANT &&
            versionOf(header) >= 0

    /** Dex format version from the `dex\nNNN\0` magic, or -1 when it is not three digits and a NUL. */
    private fun versionOf(header: ByteArray): Int {
        var version = 0
        for (i in OFF_VERSION until OFF_VERSION + VERSION_DIGITS) {
            val digit = header[i] - '0'.code.toByte()
            if (digit !in 0 until DECIMAL) return -1
            version = version * DECIMAL + digit
        }
        return if (header[OFF_VERSION + VERSION_DIGITS] == 0.toByte()) version else -1
    }

    private fun ByteArray.intAt(offset: Int): Int = (this[offset].toInt() and BYTE) or
        ((this[offset + 1].toInt() and BYTE) shl BITS_1) or
        ((this[offset + 2].toInt() and BYTE) shl BITS_2) or
        ((this[offset + LAST_BYTE].toInt() and BYTE) shl BITS_3)

    private fun ByteArray.uintAt(offset: Int): Long = intAt(offset).toLong() and UINT

    private const val UINT = 0xFFFF_FFFFL
    private const val BYTE = 0xFF
    private const val LAST_BYTE = 3
    private const val BITS_1 = 8
    private const val BITS_2 = 16
    private const val BITS_3 = 24
    private const val ULEB_MORE = 0x80
    private const val ULEB_MAX_BYTES = 5

    /** Inflated bytes still allowed, shared by the entries of one APK. */
    private class Budget(var remaining: Long)

    /** Ends input after [limit] bytes, also charging every byte read or skipped to [budget]. */
    private class LimitedInputStream(input: InputStream, private var limit: Long, private val budget: Budget) :
        FilterInputStream(input) {
        private fun allowed(wanted: Long): Long = minOf(wanted, limit, budget.remaining)

        private fun consume(n: Long) {
            limit -= n
            budget.remaining -= n
        }

        override fun read(): Int {
            if (allowed(1) <= 0) return -1
            val b = super.read()
            if (b >= 0) consume(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = allowed(len.toLong()).toInt()
            if (n <= 0) return -1
            val read = super.read(b, off, n)
            if (read > 0) consume(read.toLong())
            return read
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(allowed(n).coerceAtLeast(0))
            if (skipped > 0) consume(skipped)
            return skipped
        }

        override fun available(): Int = minOf(super.available().toLong(), allowed(Int.MAX_VALUE.toLong())).toInt()

        override fun markSupported(): Boolean = false
    }

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
