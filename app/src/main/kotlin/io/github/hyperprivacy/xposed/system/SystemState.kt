package io.github.hyperprivacy.xposed.system

import android.os.SystemClock
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.core.system.AppListPolicy
import io.github.hyperprivacy.core.system.IntSet
import io.github.hyperprivacy.xposed.RuleSwitches
import io.github.hyperprivacy.xposed.StatusReporter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable snapshots shared between the system_server hooks (readers, on arbitrary binder threads,
 * often under PackageManager or SettingsProvider locks) and [PackageIndexer] (the only writer, on
 * its own thread). Hooks only do volatile reads here: no IPC, no locks, no allocation.
 *
 * Everything starts empty, so until `systemReady` has built the first snapshot no caller counts
 * as third-party and nothing is hidden (fail-open).
 */
internal class SystemState {
    @Volatile
    var index: AppIndex = AppIndex.EMPTY

    @Volatile
    var appList: AppListPolicy = AppListPolicy.INACTIVE

    @Volatile
    var switches: RuleSwitches? = null

    @Volatile
    var reporter: StatusReporter? = null

    /** Rebuilds [index] soon; set once [PackageIndexer] runs. Called from hot paths, so rate-limited. */
    @Volatile
    var onUnknownApp: (() -> Unit)? = null

    private val lastUnknownRequest = AtomicLong(Long.MIN_VALUE / 2)

    /** App ids that asked for a rebuild since the last one. */
    private val requested: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * App ids a rebuild did not resolve (e.g. installed in a user the snapshot does not list): asking
     * again would only rebuild every few seconds for nothing. Package broadcasts still rebuild.
     */
    @Volatile
    private var unresolvable: IntSet = IntSet.EMPTY_SET

    /** A hook met [appId], an application or SDK-sandbox app id missing from [index] (e.g. installed seconds ago). */
    fun requestIndexRefresh(appId: Int) {
        if (appId in unresolvable) return
        if (requested.size < MAX_TRACKED) requested += appId
        val now = SystemClock.uptimeMillis()
        val last = lastUnknownRequest.get()
        if (now - last < UNKNOWN_REFRESH_INTERVAL_MS || !lastUnknownRequest.compareAndSet(last, now)) return
        onUnknownApp?.invoke()
    }

    /** Called by [PackageIndexer] after publishing a new [index]. */
    fun settleRefreshRequests() {
        val still = (requested + unresolvable.toSet()).filter { !index.isKnown(it) }.take(MAX_TRACKED)
        requested.clear()
        unresolvable = IntSet.of(still)
    }

    private companion object {
        const val UNKNOWN_REFRESH_INTERVAL_MS = 10_000L
        const val MAX_TRACKED = 256
    }
}
