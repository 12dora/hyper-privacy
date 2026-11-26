package io.github.hyperprivacy.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Release builds must not be able to show the debug demo mode (sample data instead of the live
 * module state). The demo lives in the app's `src/debug` source set only; `src/release` defines no
 * factory, the one call site checks `BuildConfig.DEBUG`, and nothing in `src/main` (code or
 * manifest) names the demo's classes or launch extras.
 */
class DemoModeGuardTest {
    private val app: File = requireNotNull(
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "app/src") }
            .firstOrNull { File(it, "main").isDirectory },
    ) { "app/src not found" }

    @Test
    fun `release defines no demo factory`() {
        val release = File(app, "release").walkTopDown().filter { it.isFile }.toList()
        val code = release.filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue(Regex("""val demoFactory: DemoFactory\? = null""").containsMatchIn(code), "release stub missing")
        val leaks = DEMO_NAMES.filter { code.contains(it) }
        assertTrue(leaks.isEmpty(), "Release source set mentions $leaks")
        assertTrue(release.none { it.name == "AndroidManifest.xml" }, "Release must not add components")
    }

    @Test
    fun `main source set never names the demo`() {
        val offenders = File(app, "main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .flatMap { file -> DEMO_NAMES.filter { file.readText().contains(it) }.map { "${file.name}: $it" } }
            .toList()
        assertTrue(offenders.isEmpty(), "Demo leaks into src/main:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `the only call site is behind BuildConfig DEBUG`() {
        val uses = File(app, "main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().filter { "demoFactory" in it }.map { file.name to it } }
            .toList()
        assertEquals(1, uses.size, "Expected one call site: $uses")
        assertTrue("if (BuildConfig.DEBUG) demoFactory" in uses.single().second, "Unguarded: ${uses.single()}")
    }

    @Test
    fun `debug manifest adds no demo entry point`() {
        val manifest = File(app, "debug/AndroidManifest.xml")
        if (manifest.isFile) assertTrue("demo" !in manifest.readText().lowercase(), "Demo entry in debug manifest")
    }

    private companion object {
        /** The demo's classes and its launch extras (`"demo"`, `"demo_paused"`). */
        val DEMO_NAMES = listOf("DemoModel", "DemoData", "DemoSwitches", "\"demo\"", "\"demo_paused\"")
    }
}
