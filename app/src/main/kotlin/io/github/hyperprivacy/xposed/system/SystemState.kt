package io.github.hyperprivacy.xposed.system

import android.os.SystemClock
import io.github.hyperprivacy.core.system.AppIndex
import io.github.hyperprivacy.core.system.AppListPolicy
import io.github.hyperprivacy.xposed.RuleSwitches
import io.github.hyperprivacy.xposed.StatusReporter
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

    /** A hook met an application uid missing from [index] (e.g. installed seconds ago). */
    fun requestIndexRefresh() {
        val now = SystemClock.uptimeMillis()
        val last = lastUnknownRequest.get()
        if (now - last < UNKNOWN_REFRESH_INTERVAL_MS || !lastUnknownRequest.compareAndSet(last, now)) return
        onUnknownApp?.invoke()
    }

    private companion object {
        const val UNKNOWN_REFRESH_INTERVAL_MS = 10_000L
    }
}
