package io.github.hyperprivacy.trackers

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.tracker.DexScanner
import java.io.File
import java.io.IOException
import java.security.MessageDigest
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
     * Left out: system apps, the module itself, catalog targets and vendor-signed apps (vendor data
     * apps such as health or notes ship without the system flag but share a vendor system app's
     * certificate); the catalog covers those.
     */
    // Visibility comes from the manifest's launcher <queries>; QUERY_ALL_PACKAGES is not needed.
    @SuppressLint("QueryPermissionsNeeded")
    fun installedUserApps(): List<UserApp> {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val packages = pm.getInstalledPackages(flags)
        val vendorCerts = packages.filter { it.isSystem() && isVendorPackage(it.packageName) }
            .flatMapTo(HashSet()) { it.certDigests() }
        val collator = Collator.getInstance(Locale.getDefault())
        return packages.mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            if (info.isSystem() || info.packageName == BuildConfig.APPLICATION_ID) return@mapNotNull null
            if (info.packageName in Catalog.allPackages || info.certDigests().any { it in vendorCerts }) {
                return@mapNotNull null
            }
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

    /** Scans [app] and caches the result; `null` when no APK could be read. */
    fun scan(app: UserApp): Set<String>? = try {
        DexScanner.scanApks(app.apks).also { store.putScan(app.packageName, app.key, it) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun PackageInfo.isSystem(): Boolean = (applicationInfo?.flags ?: 0) and SYSTEM_FLAGS != 0

    /** SHA-256 of each current signing certificate; certificates are compared by digest only. */
    private fun PackageInfo.certDigests(): List<String> = signingInfo?.apkContentsSigners.orEmpty().map { cert ->
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * The platform and Xiaomi's own system packages. Deliberately not every system app: a
     * preinstalled Google or carrier app would otherwise pull in every Play app with that signer.
     */
    private fun isVendorPackage(packageName: String): Boolean =
        packageName == PLATFORM_PACKAGE || packageName in Catalog.allPackages ||
            VENDOR_PREFIXES.any { packageName.startsWith(it) }

    private companion object {
        const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        const val PLATFORM_PACKAGE = "android"
        val VENDOR_PREFIXES = listOf("com.miui.", "com.xiaomi.", "com.mi.", "miui.")
    }
}
