package io.github.hyperprivacy.pause

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import io.github.hyperprivacy.ModuleConnection
import io.github.hyperprivacy.core.config.ConfigCodec
import io.github.hyperprivacy.core.config.ProtectionPause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ends a timed pause ([ProtectionPause]) with exactly one alarm, set by this app: when it fires the
 * deadline is removed from the remote preferences, and every hooked process and system_server
 * re-reads its switches from that one change. Nothing polls, and no hooked process runs a timer.
 *
 * The alarm is exact (`USE_EXACT_ALARM`, so it fires at the deadline even in Doze); should the
 * platform ever refuse an exact alarm it falls back to a one-minute window. A process started after
 * the deadline is never paused, whatever the alarm does: it reads the stored deadline against the
 * clock once.
 *
 * If the deadline cannot be removed when the alarm fires (this app is not bound yet, or the write
 * fails), the pause must not be forgotten: [PauseBootReceiver] stays enabled and the wake is tried
 * again a few times at fixed delays ([ProtectionPause.retryAt]); after that the next boot retries.
 *
 * Alarms do not survive a reboot. While a timed pause is pending, [PauseBootReceiver] is enabled to
 * set the alarm again after boot; otherwise it is disabled, so a boot does not start this app.
 */
internal object PauseAlarm {
    private const val ACTION = "io.github.hyperprivacy.action.PAUSE_EXPIRED"
    internal const val EXTRA_ATTEMPT = "attempt"
    private const val WINDOW_MS = 60_000L
    private const val UNKNOWN = -1L

    /**
     * Deadline the alarm is set for in this process (`0`: none, [UNKNOWN]: not yet synced here, so
     * a boot receiver or alarm left by an earlier process is cleaned up once); avoids repeating the
     * same system calls. Changed only under the lock.
     */
    @Volatile
    private var armedFor = UNKNOWN

    /**
     * Makes the alarm match the stored deadline [until]: set for a running timed pause, cancelled
     * otherwise. Cheap when nothing changed since the last call in this process, unless [force].
     * Serialized, so two callers cannot leave the alarm and the boot receiver disagreeing.
     * Returns whether the alarm now matches.
     */
    @Synchronized
    fun sync(context: Context, until: Long, force: Boolean = false): Boolean {
        val at = ProtectionPause.resumeAt(until, System.currentTimeMillis()) ?: 0L
        if (at == armedFor && !force) return true
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return false
        val operation = operation(app, attempt = 0)
        return runCatching {
            if (at > 0) {
                // A user-set resume time is a timer: exact when allowed, else Android may widen the window.
                if (alarms.canScheduleExactAlarms()) {
                    setExact(alarms, at, operation)
                } else {
                    alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, operation)
                }
            } else {
                alarms.cancel(operation)
            }
            setBootReceiver(app, enabled = at > 0)
            armedFor = at
        }.isSuccess
    }

    // Only called after canScheduleExactAlarms(); USE_EXACT_ALARM grants it, which lint does not model.
    @SuppressLint("MissingPermission")
    private fun setExact(alarms: AlarmManager, at: Long, operation: PendingIntent) =
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)

    private fun operation(context: Context, attempt: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION).setClass(context, PauseReceiver::class.java).putExtra(EXTRA_ATTEMPT, attempt),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setBootReceiver(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, PauseBootReceiver::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Clears an expired deadline, or sets the alarm again for one still running (after a boot, or
     * when the clock was changed). Waits a few seconds for the framework to bind this process; when
     * that or the write fails, tries again later ([attempt] counts the earlier tries).
     */
    internal fun onWake(context: Context, attempt: Int, done: () -> Unit) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { ModuleConnection.service.filterNotNull().first() }
                val prefs = service?.let { bound ->
                    runCatching { bound.getRemotePreferences(ConfigCodec.PREFS_GROUP) }.getOrNull()
                }
                if (prefs == null || !settle(app, prefs)) retryLater(app, attempt)
            } finally {
                done()
            }
        }
    }

    /**
     * Ends an expired pause and syncs the alarm to whatever deadline is stored afterwards. The
     * stored value is read under the same lock as [sync], so a pause the user started meanwhile is
     * seen (or synced after this) and its alarm is never cancelled by a stale deadline. Returns
     * false, touching neither alarm nor boot receiver, when the preferences could not be read or
     * the expired deadline could not be removed.
     */
    @Synchronized
    @SuppressLint("UseKtx") // The KTX edit() hides commit()'s result, which says whether the removal happened.
    private fun settle(app: Context, prefs: SharedPreferences): Boolean {
        var until = ProtectionPause.untilOf(runCatching { prefs.all }.getOrNull() ?: return false)
        if (ProtectionPause.isExpired(until, System.currentTimeMillis())) {
            val removed = runCatching { prefs.edit().remove(ProtectionPause.KEY).commit() }.getOrDefault(false)
            if (!removed) return false
            until = ProtectionPause.untilOf(runCatching { prefs.all }.getOrNull() ?: return false)
        }
        return sync(app, until, force = true)
    }

    /** One bounded retry, carrying the attempt count; the boot receiver stays enabled until a wake settles. */
    @Synchronized
    private fun retryLater(app: Context, attempt: Int) {
        val at = ProtectionPause.retryAt(System.currentTimeMillis(), attempt) ?: return
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, operation(app, attempt + 1))
            setBootReceiver(app, enabled = true)
            armedFor = UNKNOWN
        }
    }

    /** Well within a broadcast's time limit. */
    private const val BIND_TIMEOUT_MS = 8_000L
}

/** The pause alarm's target; not exported (only this app's own alarm reaches it). */
internal open class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        PauseAlarm.onWake(context, intent.getIntExtra(PauseAlarm.EXTRA_ATTEMPT, 0)) { result.finish() }
    }
}

/** Enabled only while a timed pause is pending ([PauseAlarm]); sets the alarm again after boot. */
internal class PauseBootReceiver : PauseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) super.onReceive(context, intent)
    }
}
