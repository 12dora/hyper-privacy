package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.conflict.ModuleOverlap
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.scope.ScopeExport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScopeExportTest {
    @Test
    fun `scope list equals catalog scope`() {
        assertEquals(Catalog.scope, ScopeExport.scopeList().lines().filter { it.isNotEmpty() }.toSet())
    }

    @Test
    fun `queries manifest lists every catalog package but the system scope`() {
        val manifest = ScopeExport.queriesManifest()
        Catalog.allPackages.filter { it != Catalog.SYSTEM_SCOPE }.forEach {
            assertTrue("""android:name="$it"""" in manifest, it)
        }
        assertTrue("""android:name="${Catalog.SYSTEM_SCOPE}"""" !in manifest)
    }

    @Test
    fun `queries manifest also covers known overlapping modules and module settings screens`() {
        val manifest = ScopeExport.queriesManifest()
        ModuleOverlap.KNOWN.keys.forEach { assertTrue("""android:name="$it"""" in manifest, it) }
        assertTrue("""android:name="${ModuleOverlap.SETTINGS_CATEGORY}"""" in manifest)
    }

    @Test
    fun `main writes both files`(@TempDir dir: File) {
        val scope = File(dir, "x/scope.list")
        val manifest = File(dir, "y/AndroidManifest.xml")
        ScopeExport.main(arrayOf(scope.path, manifest.path))
        assertEquals(ScopeExport.scopeList(), scope.readText())
        assertEquals(ScopeExport.queriesManifest(), manifest.readText())
    }
}
