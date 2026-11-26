package io.github.hyperprivacy.xposed.id

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.hyperprivacy.core.firewall.UidPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether a Binder caller is a third-party app: every package of its uid has neither
 * [ApplicationInfo.FLAG_SYSTEM] nor [ApplicationInfo.FLAG_UPDATED_SYSTEM_APP].
 *
 * Fail-open towards the vendor: anything unknown (non-app uid, uid without packages such as
 * isolated processes, a package we cannot resolve) counts as *not* third-party, so the caller
 * keeps the real value. Results are cached per uid; the cache is bounded and simply cleared when
 * full (a uid's system flag cannot change while it keeps its packages).
 */
internal class CallerClassifier {
    private val cache = ConcurrentHashMap<Int, Boolean>()

    fun isThirdPartyOnly(uid: Int, context: Context?): Boolean {
        if (uid % UidPolicy.PER_USER_RANGE < UidPolicy.FIRST_APPLICATION_UID) return false
        cache[uid]?.let { return it }
        val pm = context?.packageManager ?: return false
        val packages = pm.getPackagesForUid(uid)
        val result = !packages.isNullOrEmpty() && packages.none { isSystemOrUnknown(pm, it) }
        if (cache.size >= MAX_CACHED_UIDS) cache.clear()
        cache[uid] = result
        return result
    }

    private fun isSystemOrUnknown(pm: PackageManager, packageName: String): Boolean {
        val info = try {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } catch (_: PackageManager.NameNotFoundException) {
            return true
        }
        return info.flags and SYSTEM_FLAGS != 0
    }

    private companion object {
        const val MAX_CACHED_UIDS = 512
        const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}
