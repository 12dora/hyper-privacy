package io.github.hyperprivacy.ui.apps

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.ui.AllowlistModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The apps restricted apps still see ([AppListConfig.ALLOW_KEY], a remote preference read live by
 * system_server). Absent means the built-in [AppListConfig.DEFAULT_ALLOWLIST]; the first edit
 * stores the whole list. Writes run on [worker], which also owns [pending]; [onChange] republishes.
 */
internal class AllowlistStore(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val prefs: () -> SharedPreferences?,
    private val onChange: () -> Unit,
) : AllowlistModel {
    /** One write not yet seen in the preferences; [list] `null` removes the key. */
    private class Write(val list: Set<String>?)

    private var pending: Write? = null

    override fun setAllowed(packageName: String, on: Boolean) {
        scope.launch(worker) {
            val target = prefs() ?: return@launch
            val current = current(runCatching { target.all }.getOrNull().orEmpty()).first
            write(target, if (on) current + packageName else current - packageName)
        }
    }

    override fun reset() {
        scope.launch(worker) { prefs()?.let { write(it, null) } }
    }

    /** Forgets a write not yet visible, after an import or a reset wrote over it. Worker only. */
    fun clearPending() {
        pending = null
    }

    /**
     * The effective list under [raw] and whether it is the user's own; a pending write wins until
     * stored. Worker only.
     */
    fun current(raw: Map<String, *>): Pair<Set<String>, Boolean> {
        val stored = AppListConfig.parseAllowlist(raw[AppListConfig.ALLOW_KEY])
        val written = pending
        val list = if (written == null || written.list == stored) {
            pending = null
            stored
        } else {
            written.list
        }
        return (list ?: AppListConfig.DEFAULT_ALLOWLIST) to (list != null)
    }

    /** Stores [list]; shown at once, dropped again if the write fails. */
    private fun write(target: SharedPreferences, list: Set<String>?) {
        pending = Write(list)
        onChange()
        runCatching {
            target.edit {
                if (list == null) {
                    remove(AppListConfig.ALLOW_KEY)
                } else {
                    putString(AppListConfig.ALLOW_KEY, AppListConfig.encodeAllowlist(list.sorted()))
                }
            }
        }.onFailure {
            pending = null
            onChange()
        }
    }
}
