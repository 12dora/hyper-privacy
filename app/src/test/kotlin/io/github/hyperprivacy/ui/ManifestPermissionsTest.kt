package io.github.hyperprivacy.ui

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * The app has no network access by design (updates are a link to the browser): no manifest of any
 * source set may request INTERNET or network state. The merged manifest is built from these.
 */
class ManifestPermissionsTest {
    private val src: File = requireNotNull(
        generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
            .map { File(it, "app/src") }
            .firstOrNull { File(it, "main/AndroidManifest.xml").isFile },
    ) { "app/src not found" }

    @Test
    fun `no manifest requests network access`() {
        val manifests = src.walkTopDown().filter { it.name == "AndroidManifest.xml" }.toList()
        assertTrue(manifests.isNotEmpty())
        val offenders = manifests.filter { file -> FORBIDDEN.any { it in file.readText() } }
        assertTrue(offenders.isEmpty(), "Network permission requested in: $offenders")
    }

    private companion object {
        val FORBIDDEN = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
    }
}
