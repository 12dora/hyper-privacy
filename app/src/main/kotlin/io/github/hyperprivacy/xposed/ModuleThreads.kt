package io.github.hyperprivacy.xposed

import android.os.Handler
import android.os.HandlerThread

/** Background threads the module starts inside a host process. */
internal object ModuleThreads {
    /**
     * Starts a [HandlerThread] named [name] and returns its handler. An exception that still escapes
     * a posted task is logged and ends only this thread: the platform's default handler would kill
     * the whole process, which in system_server is a soft reboot. Work posted afterwards is dropped,
     * i.e. the guards it served stop acting (fail open).
     */
    fun handler(name: String, log: HookLog): Handler {
        val thread = HandlerThread(name)
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            try {
                log.error("$name stopped; its guards are inactive until restart", error)
            } catch (_: Throwable) {
                // Nothing left to report with; the process must survive regardless.
            }
        }
        thread.start()
        return Handler(thread.looper)
    }
}
