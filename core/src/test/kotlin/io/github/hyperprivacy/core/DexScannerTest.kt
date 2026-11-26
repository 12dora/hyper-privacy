package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.tracker.CodePrefixMatcher
import io.github.hyperprivacy.core.tracker.DexScanner
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `tracker/tiny.dex` is a hand-built dex (header, string_ids, type_ids, string data written in
 * reverse order). Strings: `Hello world`, `Lcom/example/App;`, `Lcom/tencent/bugly/Hidden;`
 * (a plain string, not a type), `Lcom/umeng/analytics/MobclickAgent;`, `Ljava/lang/Object;`,
 * `[Lcom/tencent/bugly/Foo;`; types: App, MobclickAgent, Object and the bugly array type.
 */
class DexScannerTest {
    private val matcher = CodePrefixMatcher(
        listOf(
            "com.umeng." to "umeng-broad",
            "com.umeng.analytics." to "umeng",
            "com.tencent.bugly." to "bugly",
            "com.example.App" to "app",
            "com.example.Oth" to "other",
        ),
    )

    private val dex: ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/tracker/tiny.dex"),
    ).use { it.readBytes() }

    @Test
    fun `matcher picks the longest prefix and only class descriptors`() {
        assertEquals("umeng", matcher.match("com.umeng.analytics.MobclickAgent"))
        assertEquals("umeng-broad", matcher.match("com.umeng.commonsdk.X"))
        assertEquals("app", matcher.match("com.example.AppHolder"))
        assertEquals(null, matcher.match("com.example.Ap"))
        val array = "[Lcom/tencent/bugly/Foo;".toByteArray()
        assertEquals(-1, matcher.matchDescriptor(array, 0, array.size))
    }

    @Test
    fun `scans type ids and ignores plain strings`() {
        val found = BooleanArray(matcher.ids.size)
        DexScanner.scanDex(ByteArrayInputStream(dex), matcher, found)
        assertEquals(setOf("umeng", "app"), matcher.ids.filterIndexed { i, _ -> found[i] }.toSet())
    }

    @Test
    fun `rejects non-dex and truncated input without throwing`() {
        val found = BooleanArray(matcher.ids.size)
        DexScanner.scanDex(ByteArrayInputStream(ByteArray(HEADER_SIZE)), matcher, found)
        DexScanner.scanDex(ByteArrayInputStream(dex.copyOf(TRUNCATED)), matcher, found)
        DexScanner.scanDex(ByteArrayInputStream(dex.copyOf(HEADER_SIZE / 2)), matcher, found)
        assertTrue(found.none { it })
    }

    @Test
    fun `scans every classes dex of base and split apks`(@TempDir dir: File) {
        val base = zip(File(dir, "base.apk"), "classes.dex" to dex, "assets/classes.dex" to ByteArray(0))
        val other = otherDex()
        val split = zip(File(dir, "split.apk"), "classes2.dex" to other, "classes.txt" to other)
        assertEquals(setOf("app", "umeng"), DexScanner.scanApks(listOf(base), matcher))
        assertEquals(setOf("app", "other", "umeng"), DexScanner.scanApks(listOf(base, split), matcher))
    }

    @Test
    fun `an unreadable base apk fails the scan even when splits are readable`(@TempDir dir: File) {
        val missing = File(dir, "missing.apk")
        val base = zip(File(dir, "base.apk"), "classes.dex" to dex)
        assertFailsWith<IOException> { DexScanner.scanApks(listOf(missing), matcher) }
        assertFailsWith<IOException> { DexScanner.scanApks(listOf(missing, base), matcher) }
        val garbage = File(dir, "garbage.apk").apply { writeBytes(ByteArray(GARBAGE) { it.toByte() }) }
        assertFailsWith<IOException> { DexScanner.scanApks(listOf(garbage, base), matcher) }
        val resources = zip(File(dir, "res.apk"), "resources.arsc" to ByteArray(1))
        assertFailsWith<IOException> { DexScanner.scanApks(listOf(missing, resources), matcher) }
        assertFailsWith<IOException> { DexScanner.scanApks(emptyList(), matcher) }
        // An unreadable split is skipped.
        assertEquals(setOf("app", "umeng"), DexScanner.scanApks(listOf(base, missing, resources), matcher))
    }

    @Test
    fun `entry names that are not utf-8 fail as io errors`(@TempDir dir: File) {
        val file = File(dir, "latin1.apk")
        ZipOutputStream(file.outputStream(), Charsets.ISO_8859_1).use { out ->
            out.putNextEntry(ZipEntry("res/caf\u00e9\u00ff.png"))
            out.write(1)
            out.closeEntry()
            out.putNextEntry(ZipEntry("classes.dex"))
            out.write(dex)
            out.closeEntry()
        }
        assertFailsWith<IOException> { DexScanner.scanApks(listOf(file), matcher) }
    }

    @Test
    fun `a corrupt dex entry does not stop the next one`(@TempDir dir: File) {
        val sdk = buildDex(listOf("Lcom/umeng/analytics/A;"))
        val apk = zip(File(dir, "base.apk"), "classes.dex" to ByteArray(GARBAGE) { 0x5A }, "classes2.dex" to sdk)
        assertEquals(setOf("umeng"), DexScanner.scanApks(listOf(apk), matcher))
    }

    @Test
    fun `a corrupt deflate stream in one entry does not stop the next one`(@TempDir dir: File) {
        val sdk = buildDex(listOf("Lcom/umeng/analytics/A;"))
        val first = buildDex(listOf("Lcom/example/App;"))
        val apk = zip(File(dir, "base.apk"), "classes.dex" to first, "classes2.dex" to sdk)
        // Corrupt the compressed data of classes.dex (the first local entry) right after its header.
        val bytes = apk.readBytes()
        val dataStart = LOCAL_HEADER + "classes.dex".length
        for (i in dataStart until dataStart + CORRUPT_BYTES) bytes[i] = 0xFF.toByte()
        apk.writeBytes(bytes)
        assertEquals(setOf("umeng"), DexScanner.scanApks(listOf(apk), matcher))
    }

    @Test
    fun `an apk whose only dex entries fail is unreadable`(@TempDir dir: File) {
        val apk = zip(File(dir, "base.apk"), "classes.dex" to buildDex(listOf("Lcom/umeng/analytics/A;")))
        val bytes = apk.readBytes()
        val dataStart = LOCAL_HEADER + "classes.dex".length
        for (i in dataStart until dataStart + CORRUPT_BYTES) bytes[i] = 0xFF.toByte()
        apk.writeBytes(bytes)
        assertFailsWith<IOException> { DexScanner.scanApks(listOf(apk), matcher) }
    }

    @Test
    fun `a string offset with the high bit set does not hide valid types`() {
        val bytes = buildDex(
            listOf("Lcom/umeng/analytics/A;", "Lcom/example/Bogus;", "Lcom/example/App;"),
            rawOffsets = mapOf(1 to HIGH_BIT_OFFSET),
        )
        assertEquals(setOf("umeng", "app"), scan(bytes))
    }

    @Test
    fun `v041 containers scan every section`() {
        val container = buildContainer(
            listOf("Lcom/example/App;", "Ljava/lang/Object;"),
            listOf("Lcom/umeng/analytics/MobclickAgent;"),
        )
        assertEquals(setOf("app", "umeng"), scan(container))
    }

    @Test
    fun `huge file and table sizes end the scan without hanging`() {
        val bytes = buildDex(listOf("Lcom/example/App;"))
        val hugeFile = bytes.copyOf().also { it.putInt(OFF_FILE_SIZE, -INT_TAIL) }
        val hugeStrings = bytes.copyOf().also { it.putInt(OFF_STRING_IDS, -1) }
        assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS)) {
            assertEquals(setOf("app"), scan(hugeFile))
            assertEquals(emptySet(), scan(hugeStrings))
            // Endless input behind a dex claiming 4 GiB, with a descriptor offset past the section cap.
            val far = buildDex(listOf("Lcom/example/App;", "Lcom/umeng/analytics/A;"), rawOffsets = mapOf(1 to FAR))
            far.putInt(OFF_FILE_SIZE, -INT_TAIL)
            val found = BooleanArray(matcher.ids.size)
            DexScanner.scanDex(SequenceInputStream(ByteArrayInputStream(far), Endless()), matcher, found)
            assertEquals(setOf("app"), matcher.ids.filterIndexed { i, _ -> found[i] }.toSet())
        }
    }

    /** [dex] with `Lcom/example/App;` renamed to `Lcom/example/Oth;` (same length), so offsets hold. */
    private fun otherDex(): ByteArray {
        val text = String(dex, Charsets.ISO_8859_1).replace("Lcom/example/App;", "Lcom/example/Oth;")
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    private fun scan(bytes: ByteArray): Set<String> {
        val found = BooleanArray(matcher.ids.size)
        DexScanner.scanDex(ByteArrayInputStream(bytes), matcher, found)
        return matcher.ids.filterIndexed { i, _ -> found[i] }.toSet()
    }

    /**
     * A minimal dex section whose strings are [types] (one type per string). All offsets are
     * absolute: [base] is where the section starts in its container. [rawOffsets] overrides the
     * `string_data_off` of the given string indexes. For version 041 the header is 0x78 bytes and
     * `container_size` is patched in by [buildContainer].
     */
    private fun buildDex(
        types: List<String>,
        rawOffsets: Map<Int, Int> = emptyMap(),
        base: Int = 0,
        version: String = "035",
    ): ByteArray {
        val headerSize = if (version >= "041") CONTAINER_HEADER else HEADER_SIZE
        val stringIds = base + headerSize
        val typeIds = stringIds + types.size * 4
        var dataOff = typeIds + types.size * 4
        val data = ByteArrayOutputStream()
        val offsets = types.mapIndexed { i, type ->
            val offset = dataOff + data.size()
            data.write(type.length)
            data.write(type.toByteArray())
            data.write(0)
            rawOffsets[i] ?: offset
        }
        val size = dataOff - base + data.size()
        val out = ByteArray(size)
        "dex\n$version\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(out)
        out.putInt(OFF_FILE_SIZE, size)
        out.putInt(OFF_HEADER_SIZE, headerSize)
        out.putInt(OFF_ENDIAN, ENDIAN)
        out.putInt(OFF_STRING_IDS, types.size)
        out.putInt(OFF_STRING_IDS + 4, stringIds)
        out.putInt(OFF_TYPE_IDS, types.size)
        out.putInt(OFF_TYPE_IDS + 4, typeIds)
        if (headerSize == CONTAINER_HEADER) out.putInt(OFF_HEADER_OFFSET, base)
        offsets.forEachIndexed { i, offset -> out.putInt(stringIds - base + i * 4, offset) }
        types.indices.forEach { i -> out.putInt(typeIds - base + i * 4, i) }
        data.toByteArray().copyInto(out, dataOff - base)
        return out
    }

    /** A 041 container of one section per entry of [sections]. */
    private fun buildContainer(vararg sections: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        sections.forEach { out.write(buildDex(it, base = out.size(), version = "041")) }
        val bytes = out.toByteArray()
        var start = 0
        while (start < bytes.size) {
            bytes.putInt(start + OFF_CONTAINER_SIZE, bytes.size)
            start += bytes.getInt(start + OFF_FILE_SIZE)
        }
        return bytes
    }

    private fun ByteArray.putInt(offset: Int, value: Int) {
        for (i in 0 until 4) this[offset + i] = (value ushr (i * 8)).toByte()
    }

    private fun ByteArray.getInt(offset: Int): Int =
        (0 until 4).fold(0) { acc, i -> acc or ((this[offset + i].toInt() and 0xFF) shl (i * 8)) }

    /** Zeros forever. */
    private class Endless : InputStream() {
        override fun read(): Int = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            b.fill(0, off, off + len)
            return len
        }
    }

    private fun zip(file: File, vararg entries: Pair<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private companion object {
        const val HEADER_SIZE = 0x70
        const val CONTAINER_HEADER = 0x78
        const val TRUNCATED = 0x90
        const val OFF_FILE_SIZE = 0x20
        const val OFF_HEADER_SIZE = 0x24
        const val OFF_ENDIAN = 0x28
        const val OFF_STRING_IDS = 0x38
        const val OFF_TYPE_IDS = 0x40
        const val OFF_CONTAINER_SIZE = 0x70
        const val OFF_HEADER_OFFSET = 0x74
        const val ENDIAN = 0x12345678
        const val HIGH_BIT_OFFSET = 0x9000_0000.toInt()
        const val FAR = 0x7000_0000
        const val INT_TAIL = 16
        const val GARBAGE = 4096
        const val LOCAL_HEADER = 30
        const val CORRUPT_BYTES = 8
        const val TIMEOUT_SECONDS = 20L
    }
}
