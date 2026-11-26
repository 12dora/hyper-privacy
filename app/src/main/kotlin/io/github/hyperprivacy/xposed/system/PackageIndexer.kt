package io.github.hyperprivacy.xposed.system

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.SystemClock
import io.github.hyperprivacy.BuildConfig
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.core.system.AppListConfig
import io.github.hyperprivacy.core.system.AppListPolicy
import io.github.hyperprivacy.core.system.InstalledApp
import io.github.hyperprivacy.core.tracker.TrackerProtection
import io.github.hyperprivacy.xposed.HookLog
import io.github.hyperprivacy.xposed.id.SignerDigests

/**
 * Keeps [SystemState.index] and [SystemState.appList] current: rebuilt after boot, after package
 * changes in any user (debounced, but never postponed by more than [MAX_DELAY_MS]), when a hook
 * meets an unknown app, when the default home app changes, and when the user edits the app-list
 * config (remote preferences listener, so no reboot is needed).
 *
 * Every rebuild invalidates the client-side PackageManager caches, so an answer an app cached while
 * the snapshot was older (a new install seen, the module seen) does not outlive it.
 *
 * All work runs on [handler]'s thread with no lock held; nothing here is ever called from a hooked
 * method directly. PackageManager is called in-process (system_server's own service).
 */
internal class PackageIndexer(
    private val context: Context,
    private val handler: Handler,
    private val log: HookLog,
    private val state: SystemState,
    private val prefs: SharedPreferences?,
) {
    /** Written and read on [handler]'s thread only. */
    private var homeApp: String? = null

    /** Uptime of the first rebuild request not yet served, or 0; [handler]'s thread only. */
    private var pendingSince = 0L

    private val rebuild = Runnable {
        pendingSince = 0L
        log.guard("rebuild app index") { rebuildIndex() }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PREFERRED_ACTIVITY_CHANGED) {
                log.guard("refresh home app") { refreshHome() }
            } else {
                scheduleRebuild()
            }
        }
    }

    // Held in a field: listener registries are commonly weak, and losing it would freeze the config.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Protected apps are exempt from module self-hiding, so turning protection on or off rebuilds too.
        val protection = key?.startsWith(TrackerProtection.PROTECT_PREFIX) == true
        if (AppListConfig.affects(key) || protection) {
            handler.post { log.guard("rebuild app-list policy") { rebuildPolicy() } }
        }
    }

    fun start() {
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerForAllUsers(packages)
        // Sent by PackageManager when the default home (ROLE_HOME holder) or another preferred activity changes.
        registerForAllUsers(IntentFilter(ACTION_PREFERRED_ACTIVITY_CHANGED))
        prefs?.registerOnSharedPreferenceChangeListener(prefsListener)
        state.onUnknownApp = { handler.post(::scheduleRebuild) }
        rebuildIndex()
    }

    /**
     * Protected broadcasts sent by system_server itself, so NOT_EXPORTED still receives them.
     * `registerReceiverForAllUsers` is a system API; without it only user 0's broadcasts arrive,
     * and installs in other users are still caught by the unknown-app refresh.
     */
    @SuppressLint("DiscouragedPrivateApi", "BlockedPrivateApi")
    private fun registerForAllUsers(filter: IntentFilter) {
        val allUsers = log.guard("register receiver for all users") {
            Context::class.java.getMethod(
                REGISTER_ALL_USERS,
                BroadcastReceiver::class.java,
                IntentFilter::class.java,
                String::class.java,
                Handler::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(context, receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
            true
        }
        if (allUsers == true) return
        log.warn("app index: receiving user 0 broadcasts only")
        context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
    }

    /** Trailing [DEBOUNCE_MS] debounce, capped at [MAX_DELAY_MS] after the first pending request. */
    private fun scheduleRebuild() {
        val now = SystemClock.uptimeMillis()
        if (pendingSince == 0L) pendingSince = now
        handler.removeCallbacks(rebuild)
        val deadline = pendingSince + MAX_DELAY_MS
        handler.postAtTime(rebuild, minOf(now + DEBOUNCE_MS, deadline))
    }

    // Runs in system_server as the system uid, which package visibility never filters.
    @SuppressLint("QueryPermissionsNeeded")
    private fun rebuildIndex() {
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong() or
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        val packages = context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
        state.index = AppIndex.build(packages.mapNotNull(::installedApp))
        state.settleRefreshRequests()
        homeApp = log.guard("resolve home app") { resolveHome() }
        rebuildPolicy()
    }

    private fun installedApp(info: PackageInfo): InstalledApp? {
        val app = info.applicationInfo ?: return null
        return InstalledApp(info.packageName, app.uid, app.flags and SYSTEM_FLAGS != 0, SignerDigests.of(info))
    }

    private fun refreshHome() {
        val home = resolveHome()
        if (home == homeApp) return
        homeApp = home
        log.info("app-list: default home is now $home")
        rebuildPolicy()
    }

    /**
     * The current default home app of user 0 (the `ROLE_HOME` holder, applied by PackageManager as
     * the preferred HOME activity), or `null` when the user has not chosen one: only that app keeps
     * seeing the module. Any app can declare a HOME activity, so declaring one exempts nothing.
     */
    @SuppressLint("QueryPermissionsNeeded")
    private fun resolveHome(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        // With several launchers and no default this is the system chooser (package "android").
        return context.packageManager.resolveActivity(home, flags)?.activityInfo?.packageName
            ?.takeIf { it != ANDROID_PACKAGE }
    }

    private fun rebuildPolicy() {
        val raw = log.guard("read app-list config") { prefs?.all }.orEmpty()
        val config = AppListConfig.decode(raw)
        // Hooked apps report status to the module's provider, so they must keep seeing it.
        val exempt = Catalog.scope + listOfNotNull(homeApp) + TrackerProtection.protectedPackages(raw)
        val policy = AppListPolicy.resolve(config, state.index, BuildConfig.APPLICATION_ID, exempt)
        state.appList = policy
        // Apps cache PackageManager answers in-process; a new snapshot or config must not wait for the next install.
        log.guard("invalidate package caches") { invalidateClientCaches() }
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
        const val MAX_DELAY_MS = 5_000L
        const val INVALIDATE_CACHE = "invalidatePackageInfoCache"
        const val REGISTER_ALL_USERS = "registerReceiverForAllUsers"
        const val ACTION_PREFERRED_ACTIVITY_CHANGED = "android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED"
        const val ANDROID_PACKAGE = "android"
        const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}
