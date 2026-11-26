package io.github.hyperprivacy.xposed

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The timer that ends a timed pause ([io.github.hyperprivacy.core.config.ProtectionPause]).
 *
 * One daemon thread per process, created on the first timed pause and gone again when idle, so a
 * process that never sees a pause never has it. Tasks only re-read the preferences, so they are
 * cheap, and they are re-armed from the wall clock rather than trusted to fire on time.
 */
internal object ResumeTimer {
    private const val IDLE_SECONDS = 30L

    private val executor by lazy {
        // Unnamed (`Thread-N`): thread names are visible to the host app and its crash SDKs.
        ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable).apply { isDaemon = true } }.apply {
            setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
            removeOnCancelPolicy = true
        }
    }

    /** Runs [task] after [delayMs]; `null` if the executor refused it (nothing to do then: the pause just lasts). */
    fun schedule(delayMs: Long, task: Runnable): ScheduledFuture<*>? =
        runCatching { executor.schedule(task, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
}
