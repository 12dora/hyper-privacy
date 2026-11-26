package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.tracker.CodePrefixMatcher
import io.github.hyperprivacy.core.tracker.DexScanner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
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
    fun `unreadable apks fail only when nothing could be read`(@TempDir dir: File) {
        val missing = File(dir, "missing.apk")
        assertFailsWith<java.io.IOException> { DexScanner.scanApks(listOf(missing), matcher) }
        val base = zip(File(dir, "base.apk"), "classes.dex" to dex)
        assertEquals(setOf("app", "umeng"), DexScanner.scanApks(listOf(missing, base), matcher))
    }

    /** [dex] with `Lcom/example/App;` renamed to `Lcom/example/Oth;` (same length), so offsets hold. */
    private fun otherDex(): ByteArray {
        val text = String(dex, Charsets.ISO_8859_1).replace("Lcom/example/App;", "Lcom/example/Oth;")
        return text.toByteArray(Charsets.ISO_8859_1)
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
        const val TRUNCATED = 0x90
    }
}
