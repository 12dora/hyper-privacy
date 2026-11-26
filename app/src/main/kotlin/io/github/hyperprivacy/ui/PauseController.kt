package io.github.hyperprivacy.ui

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.hyperprivacy.core.config.ProtectionPause
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Writes the global pause deadline ([ProtectionPause.KEY]) to the remote preferences and keeps the
 * UI's view of it. Hooked processes and system_server end a timed pause on their own; this only
 * has to show the result: a rebuild is requested when the deadline passes, and the expired value
 * is cleared from the preferences (an open app tidies up; nothing depends on it).
 *
 * State is confined to [worker].
 */
internal class PauseController(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val prefs: () -> SharedPreferences?,
    private val onChange: () -> Unit,
) {
    /** A deadline written but not yet visible in the preferences (`0`: a resume). */
    private var pending: Long? = null
    private var expiry: Job? = null

    /** The deadline to show: the stored one, unless a write of ours is still on its way. */
    fun resolve(stored: Map<String, *>): Long {
        val storedUntil = ProtectionPause.untilOf(stored)
        val written = pending ?: return storedUntil
        if (written == storedUntil) pending = null
        return written
    }

    /**
     * Follows the shown deadline: a finished timed pause is cleared, a running one is revisited
     * when it ends so the banner disappears on time.
     */
    fun track(until: Long) {
        expiry?.cancel()
        val now = System.currentTimeMillis()
        if (until <= 0 || until == ProtectionPause.UNTIL_MANUAL) return
        if (now >= until) {
            write(0)
            return
        }
        expiry = scope.launch(worker) {
            delay(until - now + EXPIRY_SLACK_MS)
            onChange()
        }
    }

    fun pause(option: ProtectionPause.Option) = write(ProtectionPause.deadline(option, System.currentTimeMillis()))

    fun resume() = write(0)

    private fun write(until: Long) {
        scope.launch(worker) {
            val target = prefs() ?: return@launch
            pending = until
            onChange()
            runCatching {
                target.edit { if (until > 0) putLong(ProtectionPause.KEY, until) else remove(ProtectionPause.KEY) }
            }.onFailure {
                pending = null
                onChange()
            }
        }
    }

    private companion object {
        /** Lets hooked processes (whose timers fire at the deadline) go first. */
        const val EXPIRY_SLACK_MS = 250L
    }
}
