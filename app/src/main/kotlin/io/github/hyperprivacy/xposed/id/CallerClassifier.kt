package io.github.hyperprivacy.xposed.id

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.core.system.VendorTrust
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether a Binder caller is a third-party app, with the same policy as system_server's
 * [AppIndex]: no package of its uid is vendor-trusted ([VendorTrust]: a system app, or signed with
 * the platform certificate or a certificate of a vendor system package). SDK-sandbox uids are
 * third-party whatever their owner.
 *
 * Fail-open towards the vendor: anything unknown (non-app uid, uid without packages such as
 * isolated processes, a package we cannot resolve) counts as *not* third-party, so the caller keeps
 * the real value. If the trusted certificates cannot be read, only system apps count as vendor
 * (retried at most every [RETRY_MS]). Results are cached per uid; the cache is bounded and simply
 * cleared when full (a uid's classification cannot change while it keeps its packages). The
 * trusted certificates are read once per process, on the first lookup.
 */
internal class CallerClassifier {
    private val cache = ConcurrentHashMap<Int, Boolean>()

    @Volatile
    private var trusted: Set<String>? = null

    @Volatile
    private var lastAttempt = Long.MIN_VALUE / 2

    fun isThirdPartyOnly(uid: Int, context: Context?): Boolean {
        val appId = AppIndex.callerAppId(uid)
        if (appId < 0) return false
        if (AppIndex.isSdkSandboxAppId(appId)) return true
        cache[uid]?.let { return it }
        val pm = context?.packageManager ?: return false
        // Without the certificates only the system flag counts; such an answer is not cached.
        val signers = trustedSigners(pm)
        val packages = pm.getPackagesForUid(uid)
        val result = !packages.isNullOrEmpty() && packages.none { isVendorOrUnknown(pm, it, signers.orEmpty()) }
        if (signers == null) return result
        if (cache.size >= MAX_CACHED_UIDS) cache.clear()
        cache[uid] = result
        return result
    }

    /** Certificates of the platform and of vendor system packages; `null` if unreadable (retried later). */
    // Runs in the ID provider's host (the security center, which sees all packages); a filtered answer falls back.
    @SuppressLint("QueryPermissionsNeeded")
    private fun trustedSigners(pm: PackageManager): Set<String>? {
        trusted?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (now - lastAttempt < RETRY_MS) return null
        lastAttempt = now
        val flags = PackageManager.MATCH_SYSTEM_ONLY.toLong() or PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        val system = try {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
        } catch (_: RuntimeException) {
            emptyList()
        }
        val apps = system.map { SignerDigests.installedApp(it, -1, isSystem = true) }
        // Empty: the query failed or was filtered; fall back to the system flag and retry later.
        return VendorTrust.trustedSigners(apps).takeIf { it.isNotEmpty() }?.also { trusted = it }
    }

    private fun isVendorOrUnknown(pm: PackageManager, packageName: String, signers: Set<String>): Boolean {
        val info: PackageInfo = try {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(SIGNING))
        } catch (_: PackageManager.NameNotFoundException) {
            return true
        }
        val isSystem = (info.applicationInfo?.flags ?: SYSTEM_FLAGS) and SYSTEM_FLAGS != 0
        return VendorTrust.isVendorTrusted(SignerDigests.installedApp(info, -1, isSystem), signers)
    }

    private companion object {
        const val MAX_CACHED_UIDS = 512
        const val RETRY_MS = 60_000L
        const val SIGNING = PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}
