package io.github.hyperprivacy.pause

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit
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
 * The alarm is inexact with a one-minute window (exact alarms need a special permission this app
 * does not ask for), so the platform may batch it with other wakeups; it may also widen the window
 * to its own minimum. A process started after the deadline is never paused, whatever the alarm
 * does: it reads the stored deadline against the clock once.
 *
 * Alarms do not survive a reboot. While a timed pause is pending, [PauseBootReceiver] is enabled to
 * set the alarm again after boot; otherwise it is disabled, so a boot does not start this app.
 */
internal object PauseAlarm {
    private const val ACTION = "io.github.hyperprivacy.action.PAUSE_EXPIRED"
    private const val WINDOW_MS = 60_000L

    /** Deadline the alarm is set for in this process (`0`: none); avoids repeating the same system calls. */
    @Volatile
    private var armedFor = 0L

    /**
     * Makes the alarm match the stored deadline [until]: set for a running timed pause, cancelled
     * otherwise. Cheap when nothing changed since the last call in this process, unless [force].
     */
    fun sync(context: Context, until: Long, force: Boolean = false) {
        val at = ProtectionPause.resumeAt(until, System.currentTimeMillis()) ?: 0L
        if (at == armedFor && !force) return
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val operation = operation(app)
        runCatching {
            if (at > 0) {
                alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, operation)
            } else {
                alarms.cancel(operation)
            }
            setBootReceiver(app, enabled = at > 0)
            armedFor = at
        }
    }

    private fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION).setClass(context, PauseReceiver::class.java),
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
     * when the clock was changed). Waits a few seconds for the framework to bind this process.
     */
    internal fun onWake(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { ModuleConnection.service.filterNotNull().first() }
                val prefs = service?.let { bound ->
                    runCatching { bound.getRemotePreferences(ConfigCodec.PREFS_GROUP) }.getOrNull()
                }
                if (prefs != null) {
                    val until = ProtectionPause.untilOf(runCatching { prefs.all }.getOrNull().orEmpty())
                    if (ProtectionPause.isExpired(until, System.currentTimeMillis())) {
                        runCatching { prefs.edit(commit = true) { remove(ProtectionPause.KEY) } }
                    }
                    sync(app, until, force = true)
                }
            } finally {
                done()
            }
        }
    }

    /** Well within a broadcast's time limit. */
    private const val BIND_TIMEOUT_MS = 8_000L
}

/** The pause alarm's target; not exported (only this app's own alarm reaches it). */
internal open class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        PauseAlarm.onWake(context) { result.finish() }
    }
}

/** Enabled only while a timed pause is pending ([PauseAlarm]); sets the alarm again after boot. */
internal class PauseBootReceiver : PauseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) super.onReceive(context, intent)
    }
}
