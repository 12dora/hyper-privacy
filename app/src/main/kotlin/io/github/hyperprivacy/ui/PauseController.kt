package io.github.hyperprivacy.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.config.ProtectionPause
import io.github.hyperprivacy.pause.PauseAlarm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Writes the global pause deadline ([ProtectionPause.KEY]) to the remote preferences and keeps the
 * UI's view of it. The deadline is removed by one alarm ([PauseAlarm]) when a timed pause ends;
 * hooked processes and system_server only react to that change. While the app is open it also
 * refreshes the banner on time and removes an expired deadline itself (once per deadline: a failed
 * removal is not retried on every rebuild; the alarm still covers it).
 *
 * State is confined to [worker].
 */
internal class PauseController(
    context: Context,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val prefs: () -> SharedPreferences?,
    private val onChange: () -> Unit,
) : PauseModel {
    private val appContext = context.applicationContext

    /** A deadline written but not yet visible in the preferences (`0`: a resume). */
    private var pending: Long? = null
    private var expiry: Job? = null

    /** The expired deadline last removed by [track], so a failed removal does not loop. */
    private var cleared = 0L

    /** The deadline to show: the stored one, unless a write of ours is still on its way. */
    fun resolve(stored: Map<String, *>): Long {
        val storedUntil = ProtectionPause.untilOf(stored)
        val written = pending ?: return storedUntil
        if (written == storedUntil) pending = null
        return written
    }

    /**
     * Follows the shown deadline: the resume alarm is kept in step, a finished timed pause is
     * cleared, and a running one is revisited when it ends so the banner disappears on time.
     */
    fun track(until: Long) {
        expiry?.cancel()
        val now = System.currentTimeMillis()
        if (pending == null) PauseAlarm.sync(appContext, until)
        if (!ProtectionPause.isExpired(until, now)) {
            val at = ProtectionPause.resumeAt(until, now) ?: return
            expiry = scope.launch(worker) {
                delay(at - now)
                onChange()
            }
            return
        }
        if (until != cleared) {
            cleared = until
            write(0)
        }
    }

    override fun pause(option: ProtectionPause.Option) = write(
        ProtectionPause.deadline(option, System.currentTimeMillis()),
    )

    override fun resume() = write(0)

    private fun write(until: Long) {
        scope.launch(worker) {
            val target = prefs() ?: return@launch
            pending = until
            onChange()
            val written = runCatching {
                target.edit { if (until > 0) putLong(ProtectionPause.KEY, until) else remove(ProtectionPause.KEY) }
            }
            if (written.isSuccess) {
                PauseAlarm.sync(appContext, until)
            } else {
                pending = null
                onChange()
            }
        }
    }
}
