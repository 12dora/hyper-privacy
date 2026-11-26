package io.github.hyperprivacy.xposed.system

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.system.AppListPolicy
import io.github.hyperprivacy.core.system.InstalledApp
import io.github.hyperprivacy.xposed.HookLog

/**
 * Keeps [SystemState.index] and [SystemState.appList] current: rebuilt after boot, after package
 * changes (debounced), when a hook meets an unknown app, and when the user edits the app-list
 * config (remote preferences listener, so no reboot is needed).
 *
 * All work runs on [handler]'s thread; nothing here is ever called from a hooked method directly.
 */
internal class PackageIndexer(
    private val context: Context,
    private val handler: Handler,
    private val log: HookLog,
    private val state: SystemState,
    private val prefs: SharedPreferences?,
) {
    /** Written and read on [handler]'s thread only. */
    private var homeApps: Set<String> = emptySet()

    private val rebuild = Runnable { log.guard("rebuild app index") { rebuildIndex() } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleRebuild()
    }

    // Held in a field: listener registries are commonly weak, and losing it would freeze the config.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (AppListConfig.affects(key)) handler.post { log.guard("rebuild app-list policy") { rebuildPolicy() } }
    }

    fun start() {
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // Protected broadcasts sent by system_server itself, so NOT_EXPORTED still receives them.
        context.registerReceiver(receiver, packages, null, handler, Context.RECEIVER_NOT_EXPORTED)
        prefs?.registerOnSharedPreferenceChangeListener(prefsListener)
        state.onUnknownApp = ::scheduleRebuild
        rebuildIndex()
    }

    private fun scheduleRebuild() {
        handler.removeCallbacks(rebuild)
        handler.postDelayed(rebuild, DEBOUNCE_MS)
    }

    // Runs in system_server as the system uid, which package visibility never filters.
    @SuppressLint("QueryPermissionsNeeded")
    private fun rebuildIndex() {
        val flags = PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
        val apps = context.packageManager.getInstalledApplications(flags).map {
            InstalledApp(it.packageName, it.uid, it.flags and SYSTEM_FLAGS != 0)
        }
        state.index = AppIndex.build(apps)
        homeApps = log.guard("resolve home apps") { resolveHomeApps() }.orEmpty()
        rebuildPolicy(configChanged = false)
    }

    /** Every app with a HOME activity: launchers must keep showing (and starting) the module. */
    @SuppressLint("QueryPermissionsNeeded")
    private fun resolveHomeApps(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
        return context.packageManager.queryIntentActivities(home, flags)
            .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
    }

    private fun rebuildPolicy(configChanged: Boolean = true) {
        val raw = log.guard("read app-list config") { prefs?.all }.orEmpty()
        val config = AppListConfig.decode(raw)
        val exempt = homeApps + Catalog.scope
        val policy = AppListPolicy.resolve(config, state.index, BuildConfig.APPLICATION_ID, exempt)
        state.appList = policy
        // Apps cache PackageManager answers in-process; a config change must not wait for the next install.
        if (configChanged) log.guard("invalidate package caches") { invalidateClientCaches() }
        val counts = "${config.restricted.size} restricted, ${config.strict.size} strict"
        log.info("app-list: $counts, ${config.allowlist.size} allowlisted, self hidden: ${config.hideSelf}")
    }

    /** `PackageManager.invalidatePackageInfoCache()` (hidden): bumps the cache nonce every client checks. */
    // system_server is exempt from hidden-API enforcement (and LSPosed lifts it anyway).
    @SuppressLint("DiscouragedPrivateApi", "BlockedPrivateApi")
    private fun invalidateClientCaches() {
        PackageManager::class.java.getDeclaredMethod(INVALIDATE_CACHE).invoke(null)
    }

    private companion object {
        const val DEBOUNCE_MS = 1_000L
        const val INVALIDATE_CACHE = "invalidatePackageInfoCache"
        const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}
