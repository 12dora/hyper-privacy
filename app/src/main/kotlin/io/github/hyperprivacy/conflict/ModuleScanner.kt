package io.github.hyperprivacy.conflict

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.edit
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.core.conflict.ModuleOverlap
import io.github.hyperprivacy.core.conflict.OverlapHit
import java.io.File
import java.util.zip.ZipFile

/**
 * Finds other installed Xposed modules that overlap with this one ([ModuleOverlap]).
 *
 * Runs in this app's own process only, on a background thread, and never touches the other apps:
 * it reads package metadata and, for apps without the legacy metadata flag, the entry names of the
 * APK's zip directory (the modern module marker is `META-INF/xposed/`). Which apps are visible is
 * decided by the manifest `<queries>` (launcher apps, module settings screens and the well-known
 * modules); there is no `QUERY_ALL_PACKAGES`. The per-app verdict is cached by version and update
 * time, so after the first pass only new or updated apps are opened.
 */
internal class ModuleScanner(context: Context) {
    private val pm: PackageManager = context.packageManager
    private val cache: SharedPreferences by lazy { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }

    /** Overlapping modules among the visible apps, sorted by label. Blocking: call off the main thread. */
    // Visibility comes from the manifest `<queries>` (launcher apps, module settings screens, known modules).
    @SuppressLint("QueryPermissionsNeeded")
    fun overlapping(): List<OverlapHit> {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        val installed = runCatching { pm.getInstalledPackages(flags) }.getOrDefault(emptyList())
        val verdicts = HashMap<String, String>()
        val hits = installed.mapNotNull { info -> hit(info, verdicts) }
        cache.edit {
            clear()
            verdicts.forEach { (pkg, verdict) -> putString(pkg, verdict) }
        }
        return hits.sortedBy { it.label.lowercase() }
    }

    private fun hit(info: PackageInfo, verdicts: MutableMap<String, String>): OverlapHit? {
        val app = info.applicationInfo ?: return null
        val pkg = info.packageName
        if (pkg == BuildConfig.APPLICATION_ID || app.flags and ApplicationInfo.FLAG_SYSTEM != 0) return null
        val build = "${info.longVersionCode}:${info.lastUpdateTime}"
        val cached = cache.getString(pkg, null)?.takeIf { it.startsWith("$build:") }
        val isModule = pkg in ModuleOverlap.KNOWN || app.metaData?.containsKey(LEGACY_FLAG) == true ||
            (cached?.endsWith(YES) ?: isModuleApk(app))
        verdicts[pkg] = "$build:${if (isModule) YES else NO}"
        if (!isModule) return null
        val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg)
        val description = listOfNotNull(
            runCatching { app.loadDescription(pm)?.toString() }.getOrNull(),
            app.metaData?.getString(LEGACY_DESCRIPTION),
        ).joinToString(" ")
        val areas = ModuleOverlap.areasOf(pkg, label, description)
        return if (areas.isEmpty()) null else OverlapHit(pkg, label, areas)
    }

    /** Whether the APK carries the modern module marker; an unreadable one is simply not a module. */
    private fun isModuleApk(app: ApplicationInfo): Boolean = runCatching {
        ZipFile(File(app.sourceDir)).use { zip -> MARKERS.any { zip.getEntry(it) != null } }
    }.getOrDefault(false)

    private companion object {
        const val CACHE_FILE = "xposed_modules"
        const val LEGACY_FLAG = "xposedmodule"
        const val LEGACY_DESCRIPTION = "xposeddescription"
        const val YES = "1"
        const val NO = "0"
        val MARKERS = listOf("META-INF/xposed/java_init.list", "META-INF/xposed/module.prop")
    }
}
