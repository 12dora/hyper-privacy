package io.github.hyperprivacy.trackers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.tracker.Trackers

/** Which build of an app a scan result belongs to. */
internal data class ScanKey(val versionCode: Long, val lastUpdateTime: Long)

/**
 * App-private tracker scan cache, never shared with hooked processes: scan results per package
 * (`scan.<pkg>` → `versionCode,lastUpdateTime,dbVersion|id,id`), valid while the app build and the
 * signature database version are unchanged.
 *
 * Which apps are protected lives only in the remote preferences (`TrackerProtection` keys), the
 * single source of truth for the UI, the hooks and system_server's status relay.
 */
internal class TrackerStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Cached SDK ids for [packageName] at [key], or `null` when it must be (re)scanned. */
    fun cached(packageName: String, key: ScanKey): Set<String>? {
        val value = prefs.getString(SCAN_PREFIX + packageName, null) ?: return null
        val header = value.substringBefore(ID_LIST)
        if (header != header(key)) return null
        return value.substringAfter(ID_LIST, "").split(SEPARATOR).filterTo(sortedSetOf()) { it.isNotEmpty() }
    }

    fun putScan(packageName: String, key: ScanKey, ids: Set<String>) = prefs.edit {
        putString(SCAN_PREFIX + packageName, header(key) + ID_LIST + ids.sorted().joinToString(SEPARATOR))
    }

    /** Drops scan results of packages that are no longer installed. */
    fun retainScans(installed: Set<String>) {
        val stale = prefs.all.keys.filter { it.startsWith(SCAN_PREFIX) && it.removePrefix(SCAN_PREFIX) !in installed }
        if (stale.isNotEmpty()) prefs.edit { stale.forEach(::remove) }
    }

    private fun header(key: ScanKey) = "${key.versionCode}$SEPARATOR${key.lastUpdateTime}$SEPARATOR${Trackers.version}"

    private companion object {
        const val FILE_NAME = "trackers"
        const val SCAN_PREFIX = "scan."
        const val SEPARATOR = ","
        const val ID_LIST = "|"
    }
}
