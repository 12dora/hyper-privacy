package io.github.hyperprivacy.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.conflict.ModuleScanner
import io.github.hyperprivacy.core.conflict.OverlapArea
import io.github.hyperprivacy.core.conflict.OverlapHit
import io.github.hyperprivacy.core.system.AppListConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What [ConflictController.disableAppListHiding] switched off, so the user can take it back. */
internal class AppListRestore(val hiddenFrom: Set<String>)

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
) {
    private val appContext = context.applicationContext
    private val scanner by lazy { ModuleScanner(appContext) }
    private val dismissedStore by lazy { appContext.getSharedPreferences(DISMISSED_FILE, Context.MODE_PRIVATE) }
    private var hits: List<OverlapHit> = emptyList()

    /** Looks for overlapping modules; called on resume and when packages change. */
    fun refresh() {
        scope.launch(worker) {
            hits = withContext(Dispatchers.IO) { runCatching { scanner.overlapping() }.getOrDefault(emptyList()) }
            onChange()
        }
    }

    /** Hides the hint for the modules it names now. */
    fun dismiss() {
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
            .sortedBy { if (OverlapArea.APP_LIST in it.areas) 0 else 1 }
        val first = visible.firstOrNull() ?: return null
        val area = OverlapArea.entries.first { it in first.areas }
        return ConflictUi(
            label = first.label,
            others = visible.size - 1,
            area = area,
            canDisableAppList = area == OverlapArea.APP_LIST && appListHidingOn,
        )
    }

    /** Turns off every app-list hiding setting of this module; the result undoes it ([restoreAppListHiding]). */
    suspend fun disableAppListHiding(): AppListRestore? = withContext(worker) {
        val target = prefs() ?: return@withContext null
        val raw = runCatching { target.all }.getOrNull() ?: return@withContext null
        val hidden = raw.filter { (key, value) -> key.startsWith(AppListConfig.HIDE_PREFIX) && value == true }.keys
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
        AppListRestore(hidden)
    }

    fun restoreAppListHiding(restore: AppListRestore) {
        scope.launch(worker) {
            val target = prefs() ?: return@launch
            pendingWrites.put(AppListConfig.SELF_HIDE_KEY, true)
            onChange()
            runCatching {
                target.edit {
                    restore.hiddenFrom.forEach { putBoolean(it, true) }
                    putBoolean(AppListConfig.SELF_HIDE_KEY, true)
                }
            }
        }
    }

    private fun dismissed(): Set<String> = dismissedStore.getStringSet(DISMISSED_KEY, null).orEmpty()

    private companion object {
        const val DISMISSED_FILE = "conflict"
        const val DISMISSED_KEY = "dismissed"
    }
}
