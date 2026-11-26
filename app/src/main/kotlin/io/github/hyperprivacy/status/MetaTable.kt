package io.github.hyperprivacy.status

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.status.ProcessMeta

/**
 * Latest process facts of one package.
 *
 * @property versionCode version the reporting process ran as.
 * @property lastSeenAt latest evidence the package had a live process: its start, or the receipt
 *   of a report from it (reporters send a heartbeat, so a long-lived process stays fresh).
 */
internal data class StoredMeta(val versionCode: Long, val processStartAt: Long, val lastSeenAt: Long)

/** Persistent [ProcessMeta] per package, keyed by package name. */
internal class MetaTable(val prefs: SharedPreferences) {
    /** Replaces each package's row with the reported facts; [receivedAt] refreshes `lastSeenAt`. */
    @Synchronized
    fun merge(rows: List<ProcessMeta>, receivedAt: Long) {
        if (rows.isEmpty()) return
        prefs.edit {
            rows.forEach { row ->
                val seen = maxOf(row.processStartAt, receivedAt)
                putString(row.packageName, encode(StoredMeta(row.versionCode, row.processStartAt, seen)))
            }
        }
    }

    fun get(packageName: String): StoredMeta? = decode(prefs.getString(packageName, null))

    private companion object {
        const val SEPARATOR = ","
        const val FIELD_COUNT = 3

        fun encode(meta: StoredMeta) =
            listOf(meta.versionCode, meta.processStartAt, meta.lastSeenAt).joinToString(SEPARATOR)

        fun decode(text: String?): StoredMeta? {
            val fields = text?.split(SEPARATOR)?.takeIf { it.size == FIELD_COUNT } ?: return null
            val numbers = fields.map { it.toLongOrNull() ?: return null }
            return StoredMeta(numbers[0], numbers[1], numbers[2])
        }
    }
}
