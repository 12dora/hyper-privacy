package io.github.hyperprivacy.core.scope

import io.github.hyperprivacy.core.rule.Catalog
import java.io.File

/**
 * Build-time generator invoked by the `:app` Gradle build so that the LSPosed scope and the app's
 * package-visibility queries can never drift from [Catalog].
 */
public object ScopeExport {
    /** Content of `META-INF/xposed/scope.list`: one scope entry per line. */
    public fun scopeList(): String = Catalog.scope.joinToString(separator = "\n", postfix = "\n")

    /**
     * A manifest fragment declaring `<queries>` for every catalog package, so the app can show
     * labels and map caller uids to packages without `QUERY_ALL_PACKAGES`.
     */
    public fun queriesManifest(): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">""")
        appendLine("    <queries>")
        // system_server is not a package; it needs no visibility.
        val packages = Catalog.allPackages - Catalog.SYSTEM_SCOPE
        packages.forEach { appendLine("""        <package android:name="$it" />""") }
        appendLine("    </queries>")
        appendLine("</manifest>")
    }

    /** Usage: `ScopeExport <scope.list path> <AndroidManifest.xml path>`. */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: ScopeExport <scope.list> <AndroidManifest.xml>" }
        write(File(args[0]), scopeList())
        write(File(args[1]), queriesManifest())
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
