package io.github.hyperprivacy.trackers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.tracker.OriginHints
import io.github.hyperprivacy.core.tracker.Trackers

/** Which build of an app a scan result belongs to. */
internal data class ScanKey(val versionCode: Long, val lastUpdateTime: Long)

/**
 * App-private tracker scan cache, never shared with hooked processes: scan results per package
 * (`scan.<pkg>` → `versionCode,lastUpdateTime,dbVersion,format|id,id|hints`, hints as
 * [OriginHints.encode]), valid while the app build, the signature database version and the cache
 * format are unchanged.
 *
 * Which apps are protected lives only in the remote preferences (`TrackerProtection` keys), the
 * single source of truth for the UI, the hooks and system_server's status relay.
 */
internal class TrackerStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Cached SDK ids for [packageName] at [key], or `null` when it must be (re)scanned. */
    fun cached(packageName: String, key: ScanKey): AppScan? {
        val value = prefs.getString(SCAN_PREFIX + packageName, null) ?: return null
        val fields = value.split(FIELD)
        if (fields.size != FIELDS || fields[0] != header(key)) return null
        val ids = fields[1].split(SEPARATOR).filterTo(sortedSetOf()) { it.isNotEmpty() }
        return AppScan(ids, OriginHints.decode(fields[2]))
    }

    fun putScan(packageName: String, key: ScanKey, scan: AppScan) = prefs.edit {
        val ids = scan.ids.sorted().joinToString(SEPARATOR)
        putString(SCAN_PREFIX + packageName, listOf(header(key), ids, scan.hints.encode()).joinToString(FIELD))
    }

    /** Drops scan results of packages that are no longer installed. */
    fun retainScans(installed: Set<String>) {
        val stale = prefs.all.keys.filter { it.startsWith(SCAN_PREFIX) && it.removePrefix(SCAN_PREFIX) !in installed }
        if (stale.isNotEmpty()) prefs.edit { stale.forEach(::remove) }
    }

    private fun header(key: ScanKey) =
        listOf(key.versionCode, key.lastUpdateTime, Trackers.version, FORMAT).joinToString(SEPARATOR)

    private companion object {
        const val FILE_NAME = "trackers"
        const val SCAN_PREFIX = "scan."
        const val SEPARATOR = ","
        const val FIELD = "|"
        const val FIELDS = 3

        /** Bumped when the cached fields change, so older entries are rescanned. */
        const val FORMAT = "2"
    }
}
