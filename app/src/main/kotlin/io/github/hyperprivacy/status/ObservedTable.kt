package io.github.hyperprivacy.status

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.core.status.saturatingAdd

/**
 * Persistent diagnostics table of [ObservedEndpoint]s, keyed by (package, host, pathPrefix).
 * Merging adds counts, widens the seen window and ORs `blocked`. Bounded to [MAX_ROWS]; the
 * least recently seen rows are evicted first.
 */
internal class ObservedTable(val prefs: SharedPreferences) {
    @Synchronized
    fun merge(batch: List<ObservedEndpoint>) {
        if (batch.isEmpty()) return
        val current = all().associateByTo(HashMap()) { it.key() }
        batch.forEach { incoming ->
            val previous = current[incoming.key()]
            current[incoming.key()] = previous?.let {
                it.copy(
                    count = saturatingAdd(it.count, incoming.count),
                    firstSeen = minOf(it.firstSeen, incoming.firstSeen),
                    lastSeen = maxOf(it.lastSeen, incoming.lastSeen),
                    blocked = it.blocked || incoming.blocked,
                )
            } ?: incoming
        }
        val evicted = current.values.sortedByDescending { it.lastSeen }.drop(MAX_ROWS).map { it.key() }
        prefs.edit {
            batch.forEach { putString(it.key(), encode(current.getValue(it.key()))) }
            evicted.forEach { remove(it) }
        }
    }

    /** All rows, most recently seen first. */
    fun all(): List<ObservedEndpoint> = prefs.all.mapNotNull { (key, value) -> decode(key, value as? String) }
        .sortedByDescending { it.lastSeen }

    @Synchronized
    fun clear() = prefs.edit { clear() }

    private companion object {
        const val MAX_ROWS = 2_000
        const val KEY_SEPARATOR = '\t'
        const val VALUE_SEPARATOR = ","
        const val KEY_FIELDS = 3
        const val VALUE_FIELDS = 4
        const val TRUE = "1"

        fun ObservedEndpoint.key(): String =
            listOf(packageName, host, pathPrefix.orEmpty()).joinToString(KEY_SEPARATOR.toString())

        fun encode(row: ObservedEndpoint): String =
            listOf(row.count, row.firstSeen, row.lastSeen, if (row.blocked) TRUE else "0").joinToString(VALUE_SEPARATOR)

        fun decode(key: String, value: String?): ObservedEndpoint? {
            val keys = key.split(KEY_SEPARATOR).takeIf { it.size == KEY_FIELDS } ?: return null
            val values = value?.split(VALUE_SEPARATOR)?.takeIf { it.size == VALUE_FIELDS } ?: return null
            return ObservedEndpoint(
                packageName = keys[0],
                host = keys[1],
                pathPrefix = keys[2].ifEmpty { null },
                count = values[0].toLongOrNull() ?: return null,
                firstSeen = values[1].toLongOrNull() ?: return null,
                lastSeen = values[2].toLongOrNull() ?: return null,
                blocked = values[3] == TRUE,
            )
        }
    }
}
