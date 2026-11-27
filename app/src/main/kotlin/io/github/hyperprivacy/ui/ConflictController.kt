package io.github.hyperprivacy.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.conflict.ModuleScanner
import io.github.hyperprivacy.core.conflict.OverlapHit
import io.github.hyperprivacy.core.system.AppListConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What [ConflictController.disableAppListHiding] switched off, so the user can take it back exactly:
 * the hide keys that were on, and the stored self-hiding value (`null`: not stored, the default).
 */
internal class AppListRestore(val hiddenFrom: Set<String>, val selfHide: Boolean?)

/**
 * The Home hint about other Xposed modules that overlap with this one ([ModuleScanner]), and its
 * one action: turning off this module's own app-list hiding. Other modules are only ever read,
 * never changed. Scanning runs on [Dispatchers.IO]; state is confined to [worker].
 *
 * A dismissed hint stays dismissed for the modules it named (stored locally, never in the remote
 * preferences); a module that appears later raises it again.
 */
internal class ConflictController(
    context: Context,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val prefs: () -> SharedPreferences?,
    private val pendingWrites: PendingWrites,
    private val onChange: () -> Unit,
) : ConflictModel {
    private val appContext = context.applicationContext
    private val scanner by lazy { ModuleScanner(appContext) }
    private val dismissedStore by lazy { appContext.getSharedPreferences(DISMISSED_FILE, Context.MODE_PRIVATE) }
    private var hits: List<OverlapHit> = emptyList()

    /** A scan is running; a refresh meanwhile only asks for one more pass ([again]). Both on [worker]. */
    private var scanning = false
    private var again = false

    /**
     * Looks for overlapping modules; called on resume and when packages change. Calls are
     * coalesced: one scan at a time, and at most one more after it, however many requests came.
     */
    fun refresh() {
        scope.launch(worker) {
            if (scanning) {
                again = true
                return@launch
            }
            scanning = true
            try {
                do {
                    again = false
                    hits = withContext(Dispatchers.IO) {
                        runCatching { scanner.overlapping() }.getOrDefault(emptyList())
                    }
                    onChange()
                } while (again)
            } finally {
                scanning = false
            }
        }
    }

    /** Hides the hint for the modules it names now. */
    override fun dismiss() {
        scope.launch(worker) {
            val all = dismissed() + hits.map { it.packageName }
            dismissedStore.edit { putStringSet(DISMISSED_KEY, all) }
            onChange()
        }
    }

    /** The hint to show, or `null`; [appListHidingOn] is whether this module hides apps right now. */
    fun ui(appListHidingOn: Boolean): ConflictUi? {
        val seen = dismissed()
        val visible = hits.filter { it.packageName !in seen }
            .map { ConflictModule(it.packageName, it.label, it.areas.toList()) }
        return ConflictUi.of(visible, appListHidingOn)
    }

    /** Turns off every app-list hiding setting of this module; the result undoes it ([restoreAppListHiding]). */
    override suspend fun disableAppListHiding(): AppListRestore? = withContext(worker) {
        val target = prefs() ?: return@withContext null
        val raw = runCatching { target.all }.getOrNull() ?: return@withContext null
        val hidden = raw.filter { (key, value) -> key.startsWith(AppListConfig.HIDE_PREFIX) && value == true }.keys
        val selfHide = raw[AppListConfig.SELF_HIDE_KEY] as? Boolean
        pendingWrites.put(AppListConfig.SELF_HIDE_KEY, false)
        onChange()
        val written = runCatching {
            target.edit {
                hidden.forEach(::remove)
                putBoolean(AppListConfig.SELF_HIDE_KEY, false)
            }
        }
        if (written.isFailure) {
            pendingWrites.remove(AppListConfig.SELF_HIDE_KEY)
            onChange()
            return@withContext null
        }
        AppListRestore(hidden, selfHide)
    }

    /** Puts back exactly what [disableAppListHiding] changed, the self-hiding value included. */
    override fun restoreAppListHiding(restore: AppListRestore) {
        scope.launch(worker) {
            val target = prefs() ?: return@launch
            pendingWrites.put(AppListConfig.SELF_HIDE_KEY, restore.selfHide ?: AppListConfig.EMPTY.hideSelf)
            onChange()
            val written = runCatching {
                target.edit {
                    restore.hiddenFrom.forEach { putBoolean(it, true) }
                    val selfHide = restore.selfHide
                    if (selfHide == null) {
                        remove(AppListConfig.SELF_HIDE_KEY)
                    } else {
                        putBoolean(AppListConfig.SELF_HIDE_KEY, selfHide)
                    }
                }
            }
            if (written.isFailure) {
                pendingWrites.remove(AppListConfig.SELF_HIDE_KEY)
                onChange()
            }
        }
    }

    private fun dismissed(): Set<String> = dismissedStore.getStringSet(DISMISSED_KEY, null).orEmpty()

    private companion object {
        const val DISMISSED_FILE = "conflict"
        const val DISMISSED_KEY = "dismissed"
    }
}
