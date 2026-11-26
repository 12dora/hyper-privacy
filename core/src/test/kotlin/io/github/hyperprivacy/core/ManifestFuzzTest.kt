package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.tracker.ManifestScanner
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Crafted hostile manifests: every one must parse (or be rejected as unreadable) quickly and in
 * bounded memory, never throw and never read outside the chunk a value belongs to.
 */
class ManifestFuzzTest {
    @Test
    fun `aliased string offsets decode once`() {
        // 100 000 offsets all pointing at one 4000-character string, referenced by 1000 attributes.
        val big = utf16(String(CharArray(BIG_CHARS) { 'a' }))
        val strings = listOf(utf16("manifest"), utf16("package"), utf16(PKG), big)
        val starts = offsets(strings)
        val table = IntArray(ALIASES) { starts[3] }.also { t -> (0..2).forEach { t[it] = starts[it] } }
        val attrs = (0 until ATTRS).map { attr(name = 3 + it, raw = 3 + it) } + attr(name = 1, raw = 2)
        val doc = document(pool(strings, table), element(0, attrs))
        val info = timed { ManifestScanner.parse(doc) }
        assertEquals(PKG, info?.packageName)
    }

    @Test
    fun `too many decoded characters make the manifest unreadable`() {
        // Overlapping strings: every offset reads a length of 4000 and 8000 bytes after it.
        val region = utf16Raw(CharArray(BIG_CHARS + OVERLAPS) { BIG_CHARS.toChar() })
        val strings = listOf(utf16("manifest"), utf16("package"), utf16(PKG), region)
        val starts = offsets(strings)
        val table = IntArray(3 + OVERLAPS) { i -> if (i < 3) starts[i] else starts[3] + (i - 3) * 2 }
        val attrs = (0 until OVERLAPS).map { attr(name = 3 + it, raw = -1) } + attr(name = 1, raw = 2)
        val doc = document(pool(strings, table), element(0, attrs))
        assertNull(timed { ManifestScanner.parse(doc) })
    }

    @Test
    fun `attributes never reach past their chunk`() {
        // 90 000 element chunks each claiming 65535 attributes of 20 bytes: none fits its chunk.
        val strings = listOf(utf16("manifest"), utf16("package"), utf16(PKG), utf16("activity"))
        val chunks = ArrayList<ByteArray>()
        chunks += pool(strings, offsets(strings))
        chunks += element(0, listOf(attr(name = 1, raw = 2)))
        repeat(ELEMENT_BOMB) { chunks += element(3, emptyList(), claimedCount = 0xFFFF) }
        val info = timed { ManifestScanner.parse(document(*chunks.toTypedArray())) }
        assertEquals(PKG, info?.packageName)
        assertTrue(info?.components.orEmpty().isEmpty())
    }

    @Test
    fun `too many chunks make the manifest unreadable`() {
        val strings = listOf(utf16("manifest"), utf16("package"), utf16(PKG))
        val filler = buffer(8).putShort(0x0777).putShort(8).putInt(8).array()
        val chunks = listOf(pool(strings, offsets(strings)), element(0, listOf(attr(name = 1, raw = 2)))) +
            List(ManifestScanner.MAX_CHUNKS + 1) { filler }
        assertNull(timed { ManifestScanner.parse(document(*chunks.toTypedArray())) })
    }

    @Test
    fun `string pool headers and offsets are validated`() {
        val strings = listOf(utf16("manifest"), utf16("package"), utf16(PKG))
        val manifest = element(0, listOf(attr(name = 1, raw = 2)))
        // Header too short to be a string pool: header fields would be read as offsets.
        assertNull(ManifestScanner.parse(document(pool(strings, offsets(strings), headerSize = 8), manifest)))
        // String data start past the chunk, or before the offset table ends (signed overflow).
        assertNull(
            ManifestScanner.parse(document(pool(strings, offsets(strings), stringsStart = 0x7FFF_FFF0), manifest)),
        )
        assertNull(ManifestScanner.parse(document(pool(strings, offsets(strings), stringsStart = 4), manifest)))
        // An offset outside the string data reads as no string.
        val outside = offsets(strings).also { it[2] = 0x8000_0010.toInt() }
        assertNull(ManifestScanner.parse(document(pool(strings, outside), manifest)))
        // A string longer than the cap reads as no string.
        val long = listOf(
            utf16("manifest"),
            utf16("package"),
            utf16("a." + "b".repeat(ManifestScanner.MAX_STRING_CHARS)),
        )
        assertNull(ManifestScanner.parse(document(pool(long, offsets(long)), manifest)))
        // The well-formed version of the same document parses.
        assertEquals(PKG, ManifestScanner.parse(document(pool(strings, offsets(strings)), manifest))?.packageName)
    }

    @Test
    fun `random corruption of a real-shaped manifest never throws`() {
        val strings = listOf(utf16("manifest"), utf16("package"), utf16(PKG), utf16("application"))
        val good = document(
            pool(strings, offsets(strings)),
            element(0, listOf(attr(name = 1, raw = 2))),
            element(3, listOf(attr(name = 1, raw = 2))),
        )
        val random = java.util.Random(SEED)
        timed {
            repeat(FUZZ_ROUNDS) {
                val bytes = good.copyOf()
                repeat(1 + random.nextInt(MAX_FLIPS)) { bytes[random.nextInt(bytes.size)] = random.nextInt().toByte() }
                ManifestScanner.parse(bytes)
            }
        }
    }

    private fun <T> timed(block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / NANOS_PER_MS
        assertTrue(ms < MAX_MS, "parse took ${ms}ms")
        return result
    }

    private fun utf16(text: String): ByteArray = utf16Raw(text.toCharArray(), text.length)

    /** A UTF-16 pool string: length, characters, NUL. */
    private fun utf16Raw(chars: CharArray, length: Int = chars.size): ByteArray {
        val b = buffer(2 + chars.size * 2 + 2)
        b.putShort(length.toShort())
        chars.forEach { b.putChar(it) }
        b.putShort(0)
        return b.array()
    }

    private fun offsets(strings: List<ByteArray>): IntArray {
        var at = 0
        return IntArray(strings.size) { i -> at.also { at += strings[i].size } }
    }

    private fun pool(
        strings: List<ByteArray>,
        table: IntArray,
        headerSize: Int = POOL_HEADER,
        stringsStart: Int? = null,
    ): ByteArray {
        val data = ByteArrayOutputStream().apply { strings.forEach { write(it) } }
        while (data.size() % 4 != 0) data.write(0)
        val header = maxOf(headerSize, POOL_HEADER)
        val size = header + table.size * 4 + data.size()
        val pool = buffer(header + table.size * 4)
        pool.putShort(0x0001).putShort(headerSize.toShort()).putInt(size).putInt(table.size).putInt(0)
        pool.putInt(0).putInt(stringsStart ?: (header + table.size * 4)).putInt(0)
        pool.position(header)
        table.forEach { pool.putInt(it) }
        return pool.array() + data.toByteArray()
    }

    /** One `ResXMLTree_attribute`: namespace, name, raw value, typed value (string-typed when [raw] is set). */
    private fun attr(name: Int, raw: Int, ns: Int = -1): ByteArray =
        buffer(ATTR).putInt(ns).putInt(name).putInt(raw).putShort(8).put(0).put(3).putInt(raw).array()

    private fun element(name: Int, attrs: List<ByteArray>, claimedCount: Int = attrs.size): ByteArray {
        val chunk = buffer(ELEMENT_HEADER + ELEMENT_EXT + ATTR * attrs.size)
        chunk.putShort(0x0102).putShort(ELEMENT_HEADER.toShort()).putInt(chunk.capacity()).putInt(1).putInt(-1)
        chunk.putInt(-1).putInt(name).putShort(ELEMENT_EXT.toShort()).putShort(ATTR.toShort())
        chunk.putShort(claimedCount.toShort()).putShort(0).putShort(0).putShort(0)
        attrs.forEach { chunk.put(it) }
        return chunk.array()
    }

    private fun document(vararg chunks: ByteArray): ByteArray {
        val total = 8 + chunks.sumOf { it.size }
        val out = ByteArrayOutputStream(total)
        out.write(buffer(8).putShort(0x0003).putShort(8).putInt(total).array())
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private companion object {
        const val PKG = "com.example.app"
        const val POOL_HEADER = 28
        const val ELEMENT_HEADER = 16
        const val ELEMENT_EXT = 20
        const val ATTR = 20
        const val BIG_CHARS = 4000
        const val ALIASES = 100_000
        const val ATTRS = 1000
        const val OVERLAPS = 600
        const val ELEMENT_BOMB = 90_000
        const val SEED = 11L
        const val FUZZ_ROUNDS = 5_000
        const val MAX_FLIPS = 4
        const val NANOS_PER_MS = 1_000_000L
        const val MAX_MS = 3_000L
    }
}
