package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.conflict.DeclaredScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DeclaredScopeTest {
    @Test
    fun `the modern list reads one package per line, skipping comments and blanks`() {
        val text = "# targets\ncom.example.one\n\n  com.example.two  # inline\nandroid\ncom.example.one\n"
        val expected = DeclaredScope.Apps(setOf("com.example.one", "com.example.two", "android"))
        assertEquals(expected, DeclaredScope.parseList(text))
    }

    @Test
    fun `the legacy array is read the same way, nulls and junk skipped`() {
        val scope = DeclaredScope.of(listOf("com.example.one", null, " ", "not a package!", "system"))
        assertEquals(DeclaredScope.Apps(setOf("com.example.one", "system")), scope)
    }

    @Test
    fun `a wildcard means every app`() {
        assertEquals(DeclaredScope.AllApps, DeclaredScope.parseList("com.example.one\n*\n"))
        assertEquals(DeclaredScope.AllApps, DeclaredScope.of(listOf("ALL")))
    }

    @Test
    fun `absent, empty, malformed and oversized declarations are unknown`() {
        assertEquals(DeclaredScope.Unknown, DeclaredScope.parseList(null))
        assertEquals(DeclaredScope.Unknown, DeclaredScope.parseList(""))
        assertEquals(DeclaredScope.Unknown, DeclaredScope.parseList("\u0000\u0001 ### \n???"))
        assertEquals(DeclaredScope.Unknown, DeclaredScope.of(null))
        assertEquals(DeclaredScope.Unknown, DeclaredScope.parseList("a".repeat(DeclaredScope.MAX_CHARS + 1)))
        val many = (0..DeclaredScope.MAX_ENTRIES).joinToString("\n") { "com.example.app$it" }
        assertEquals(DeclaredScope.Unknown, DeclaredScope.parseList(many))
        assertEquals(DeclaredScope.Unknown, DeclaredScope.of(listOf("com." + "a".repeat(300))))
        // Few entries, but more text than allowed in total: rejected, not parsed partly.
        val heavy = List(4) { "com.example.a" + "b".repeat(DeclaredScope.MAX_CHARS / 3) }
        assertEquals(DeclaredScope.Unknown, DeclaredScope.of(heavy))
        assertEquals(DeclaredScope.Unknown, DeclaredScope.of(List(DeclaredScope.MAX_ENTRIES * 2 + 1) { "" }))
    }
}
