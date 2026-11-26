package io.github.hyperprivacy.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface

/** Thin wrapper over the framework log so every message carries the module tag. */
internal class HookLog(private val xposed: XposedInterface) {
    fun info(message: String) = xposed.log(Log.INFO, TAG, message)

    fun warn(message: String, error: Throwable? = null) = xposed.log(Log.WARN, TAG, message, error)

    fun error(message: String, error: Throwable) = xposed.log(Log.ERROR, TAG, message, error)

    /** Runs [block], logging instead of propagating any failure. Returns `null` on failure. */
    inline fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        error("$what failed; leaving host untouched", t)
        null
    }

    private companion object {
        const val TAG = "HyperPrivacy"
    }
}
