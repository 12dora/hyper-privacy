package io.github.hyperprivacy.trackers

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.InstalledApp
import io.github.hyperprivacy.core.system.VendorTrust
import io.github.hyperprivacy.core.tracker.DexScanner
import io.github.hyperprivacy.xposed.id.SignerDigests
import java.io.File
import java.text.Collator
import java.util.Locale

/** A user-installed app outside the catalog, as listed on the Apps page. */
internal data class UserApp(val packageName: String, val label: String, val key: ScanKey, val apks: List<File>)

/**
 * Lists user-installed apps and scans their APKs for tracker SDKs, in the module app's own process
 * only (never in a hooked app). Scanning reads dex tables from the APK files ([DexScanner]); no
 * code is loaded. Callers run it on a background dispatcher.
 */
internal class AppScanner(private val pm: PackageManager, private val store: TrackerStore) {
    /**
     * Third-party apps this app can see (the manifest queries launcher activities), sorted by label.
     * Left out: the module itself, catalog targets and every vendor-trusted app ([VendorTrust], the
     * same test system_server applies: system apps and apps sharing a vendor system app's
     * certificate, such as HyperOS data apps shipped without the system flag). Protection may never
     * select those ([io.github.hyperprivacy.core.rule.Catalog.protectedAppRules]).
     */
    // Visibility comes from the manifest's launcher <queries>; QUERY_ALL_PACKAGES is not needed.
    @SuppressLint("QueryPermissionsNeeded")
    fun installedUserApps(): List<UserApp> {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val packages = pm.getInstalledPackages(flags).mapNotNull { info -> info.installedApp()?.let { info to it } }
        val trusted = VendorTrust.trustedSigners(packages.map { it.second })
        val collator = Collator.getInstance(Locale.getDefault())
        return packages.mapNotNull { (info, installed) ->
            val app = info.applicationInfo ?: return@mapNotNull null
            if (info.packageName == BuildConfig.APPLICATION_ID || info.packageName in Catalog.allPackages) {
                return@mapNotNull null
            }
            if (VendorTrust.isVendorTrusted(installed, trusted)) return@mapNotNull null
            val apks = listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()
            UserApp(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(info.packageName),
                key = ScanKey(info.longVersionCode, info.lastUpdateTime),
                apks = apks.map(::File),
            )
        }.sortedWith(compareBy(collator) { it.label })
    }

    /** Cached result for [app]'s current build, if any; cheap, no file access. */
    fun cached(app: UserApp): Set<String>? = store.cached(app.packageName, app.key)

    /**
     * Scans [app] and caches the result; `null` (nothing cached, retried later) when the base APK
     * could not be read or the scan failed in any other way.
     */
    // A hostile or corrupt APK must never crash the module app; any Exception means "scan failed".
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun scan(app: UserApp): Set<String>? = try {
        DexScanner.scanApks(app.apks).also { store.putScan(app.packageName, app.key, it) }
    } catch (_: Exception) {
        null
    }

    private fun PackageInfo.installedApp(): InstalledApp? {
        val app = applicationInfo ?: return null
        return InstalledApp(packageName, app.uid, app.flags and SYSTEM_FLAGS != 0, SignerDigests.of(this))
    }

    private companion object {
        const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}
