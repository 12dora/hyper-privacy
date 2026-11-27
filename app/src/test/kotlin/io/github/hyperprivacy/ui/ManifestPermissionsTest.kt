package io.github.hyperprivacy.ui

import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertTrue

/**
 * The app has no network access by design (updates are a link to the browser). The main manifest
 * removes INTERNET and network state for good (`tools:node="remove"`, so no library can add them),
 * no source set may request them, and a merged manifest, when one was built, must not contain them.
 */
class ManifestPermissionsTest {
    private val root: File = requireNotNull(
        generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .map { File(it, "app") }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile },
    ) { "app module not found" }

    @Test
    fun `the main manifest removes network permissions`() {
        val removed = permissions(File(root, "src/main/AndroidManifest.xml"))
            .filter { it.getAttribute("tools:node") == "remove" }
            .map { it.getAttribute("android:name") }
        assertTrue(removed.containsAll(FORBIDDEN), "Not removed: ${FORBIDDEN - removed.toSet()}")
    }

    @Test
    fun `no source set requests network access`() {
        val offenders = File(root, "src").walkTopDown().filter { it.name == MANIFEST }.flatMap { file ->
            permissions(file).filter { it.getAttribute("tools:node") != "remove" }
                .map { it.getAttribute("android:name") }
                .filter { it in FORBIDDEN }
                .map { "$file: $it" }
        }.toList()
        assertTrue(offenders.isEmpty(), "Network permission requested: $offenders")
    }

    @Test
    fun `built merged manifests carry no network permission`() {
        val merged = File(root, "build/intermediates").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.name == MANIFEST && "merged_manifest" in it.path }?.toList().orEmpty()
        val offenders = merged.flatMap { file ->
            permissions(file).map { it.getAttribute("android:name") }.filter { it in FORBIDDEN }.map { "$file: $it" }
        }
        assertTrue(offenders.isEmpty(), "Network permission merged: $offenders")
    }

    private fun permissions(file: File): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("uses-permission")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private companion object {
        const val MANIFEST = "AndroidManifest.xml"
        val FORBIDDEN = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
    }
}
