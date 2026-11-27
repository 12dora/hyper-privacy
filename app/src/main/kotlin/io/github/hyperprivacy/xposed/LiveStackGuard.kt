package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.stack.LiveStackFilter
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method

/**
 * Hides the module's frames from live stack sampling in a protected app: `Thread#getStackTrace()`
 * and `Thread.getAllStackTraces()`. A thread sampled while inside a hooked API would otherwise show
 * module classes and hook stubs; see [LiveStackFilter] for exactly what is dropped and
 * [ModuleFrames] for how a frame is known to be ours. Exceptions we throw are handled by
 * [StackScrub]. Installed only in tracker-protected app processes.
 *
 * Once these two methods are hooked, our own hooker and the framework frames around it are on the
 * calling thread's stack for every call, so that window is stripped always, while protection is
 * paused too (then only that window: other threads' samples and the rest of the stack are returned
 * as they are).
 *
 * Cost, per call (logging libraries call `getStackTrace` often): one `startsWith` per module
 * prefix per frame, no reflection, class lookup, lock or cache. A thread sampling itself always
 * gets a trimmed copy (one `BooleanArray` plus the copy, on top of the VM's own array); sampling
 * another thread that is not inside a hook returns the original array without allocating.
 * Nested calls on the same thread (our own code sampling a stack) return the unfiltered result.
 */
internal class LiveStackGuard(private val env: GuardEnv) {
    private val filter = LiveStackFilter(ModuleFrames.prefixes)
    private val busy = ThreadLocal<Boolean>()

    fun install(): Int {
        val hooked = hook("getStackTrace", SingleHooker()) + hook("getAllStackTraces", AllHooker())
        env.log.info("live stack guard: $hooked hooks in ${env.packageName}")
        return hooked
    }

    private fun hook(name: String, hooker: Hooker): Int = try {
        val method: Method = Thread::class.java.getDeclaredMethod(name)
        env.xposed.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker)
        1
    } catch (t: Throwable) {
        env.log.warn("live stack guard: Thread#$name not hooked", t)
        0
    }

    /** Runs [block] unless this thread is already inside it; a nested call gets [result] as is. */
    private inline fun <T> once(result: T, block: () -> T): T {
        if (busy.get() == true) return result
        busy.set(true)
        return try {
            block()
        } catch (_: Throwable) {
            result // Fail open: the unfiltered trace is still a working answer.
        } finally {
            busy.remove() // No per-thread entry left behind.
        }
    }

    private inner class SingleHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            @Suppress("UNCHECKED_CAST") // Thread#getStackTrace returns StackTraceElement[].
            val frames = result as? Array<StackTraceElement> ?: return result
            val self = chain.thisObject === Thread.currentThread()
            val paused = env.switches.paused
            if (paused && !self) return result
            return once(result) { filter.filter(frames, firstWindowOnly = paused) }
        }
    }

    private inner class AllHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            @Suppress("UNCHECKED_CAST") // Thread.getAllStackTraces returns Map<Thread, StackTraceElement[]>.
            val traces = result as? MutableMap<Thread, Array<StackTraceElement>> ?: return result
            once(Unit) {
                if (env.switches.paused) {
                    val self = Thread.currentThread()
                    traces[self]?.let { traces[self] = filter.filter(it, firstWindowOnly = true) }
                } else {
                    filter.filterAll(traces)
                }
            }
            return result // Possibly half-filtered on failure: still a valid map.
        }
    }
}
