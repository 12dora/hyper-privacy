package io.github.hyperprivacy.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.conflict.ModuleScanner
import io.github.hyperprivacy.core.conflict.DeclaredScope
import io.github.hyperprivacy.core.conflict.OverlapArea
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
 * A dismissed hint stays dismissed for the modules and areas it named (stored locally, never in
 * the remote preferences); a module that appears later, or a known one found doing more, raises it
 * again. Settings and the detail page list every module, dismissed or not. The scopes modules
 * declare are read only when the detail page asks ([loadScopes]).
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

    /**
     * Declared scopes by package, read once the detail page asked ([scopesWanted]) and again after
     * every scan, so a module found later or updated meanwhile is covered (the scanner caches per
     * build, so an unchanged module costs nothing). Worker only.
     */
    private var scopes: Map<String, DeclaredScope> = emptyMap()
    private var scopesWanted = false

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
                    if (scopesWanted) scopes = scanner.scopesOf(hits)
                    onChange()
                } while (again)
            } finally {
                scanning = false
            }
        }
    }

    override fun dismiss() {
        scope.launch(worker) {
            val all = dismissed() + hits.flatMap { hit -> hit.areas.map { dismissKey(hit.packageName, it) } }
            runCatching { dismissedStore.edit { putStringSet(DISMISSED_KEY, all) } }
            onChange()
        }
    }

    override fun restoreHint() {
        scope.launch(worker) {
            runCatching { dismissedStore.edit { remove(DISMISSED_KEY) } }
            onChange()
        }
    }

    override fun loadScopes() {
        scope.launch(worker) {
            scopesWanted = true
            scopes = scanner.scopesOf(hits)
            onChange()
        }
    }

    /** The hint to show, or `null`; [appListHidingOn] is whether this module hides apps right now. */
    fun ui(appListHidingOn: Boolean): ConflictUi? {
        val seen = dismissed()
        val visible = modules().map { module ->
            module.copy(areas = module.areas.filter { dismissKey(module.packageName, it) !in seen })
        }
        return ConflictUi.of(visible, appListHidingOn)
    }

    /** Every overlapping module, dismissed ones included, or `null` when there is none. */
    fun all(appListHidingOn: Boolean): ConflictUi? = ConflictUi.of(modules(), appListHidingOn)

    /** Whether a module and area found now is dismissed from the hint (older or stale entries do not count). */
    val anyDismissed: Boolean
        get() {
            val seen = dismissed()
            return seen.isNotEmpty() && hits.any { hit -> hit.areas.any { dismissKey(hit.packageName, it) in seen } }
        }

    private fun modules(): List<ConflictModule> = hits.map {
        ConflictModule(it.packageName, it.label, it.areas.toList(), scopes[it.packageName] ?: DeclaredScope.Unknown)
    }

    /** Turns off every app-list hiding setting of this module; the result undoes it ([restoreAppListHiding]). */
    override suspend fun disableAppListHiding(): AppListRestore? = withContext(worker) {
        val target = prefs() ?: return@withContext null
        val raw = runCatching { target.all }.getOrNull() ?: return@withContext null
        val hidden = raw.filter { (key, value) -> key.startsWith(AppListConfig.HIDE_PREFIX) && value == true }.keys
        val selfHide = raw[AppListConfig.SELF_HIDE_KEY] as? Boolean
        // Already off (a second tap before the hint updated): nothing to undo, so the first undo is kept.
        if (hidden.isEmpty() && selfHide == false) return@withContext null
        pendingWrites.put(AppListConfig.SELF_HIDE_KEY, false)
        onChange()
        // Committed on the worker, so a call queued behind this one already reads the result.
        // KTX edit(commit = true) drops commit()'s result, which decides here.
        @SuppressLint("UseKtx")
        val written = runCatching {
            target.edit().apply {
                hidden.forEach(::remove)
                putBoolean(AppListConfig.SELF_HIDE_KEY, false)
            }.commit()
        }.getOrDefault(false)
        if (!written) {
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

    /** Dismissed `package|AREA` pairs; entries of an older format (package only) are simply not matched. */
    private fun dismissed(): Set<String> =
        runCatching { dismissedStore.getStringSet(DISMISSED_KEY, null) }.getOrNull().orEmpty()

    private fun dismissKey(packageName: String, area: OverlapArea) = "$packageName|${area.name}"

    private companion object {
        const val DISMISSED_FILE = "conflict"
        const val DISMISSED_KEY = "dismissed"
    }
}

/** The declared scopes of [hits], read off the main thread; modules no longer found are not in the result. */
private suspend fun ModuleScanner.scopesOf(hits: List<OverlapHit>): Map<String, DeclaredScope> {
    val packages = hits.map { it.packageName }
    return withContext(Dispatchers.IO) {
        packages.associateWith { runCatching { declaredScope(it) }.getOrDefault(DeclaredScope.Unknown) }
    }
}
