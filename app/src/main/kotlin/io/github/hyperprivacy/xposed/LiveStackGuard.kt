package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.stack.LiveStackFilter
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.ConcurrentHashMap

/**
 * Hides the module's frames from live stack sampling in a protected app: `Thread#getStackTrace()`
 * and `Thread.getAllStackTraces()`. A second thread sampling a thread that is inside a hooked API
 * would otherwise see module classes (R8-renamed, unresolvable by the app's loader) and the hook
 * stub class; see [LiveStackFilter] for exactly what is dropped. Exceptions we throw are handled
 * by [StackScrub] and are not affected here. Installed only in tracker-protected app processes.
 *
 * Cost: logging libraries call `getStackTrace` often. Per call, the frames' class names are looked
 * up in a bounded cache (ownership through [StackScrub.isModuleClass], itself cached); with no
 * module frame the original array is returned untouched, without allocating, locking, reflection or
 * I/O. Stub detection (`Class.forName` miss) only runs for platform-package names adjacent to a
 * module frame, once per name. Any failure returns the original result.
 */
internal class LiveStackGuard(private val env: GuardEnv) {
    private val stubs = ConcurrentHashMap<String, Boolean>()
    private val filter = LiveStackFilter(StackScrub::isModuleClass, ::isStub)

    fun install(): Int {
        var hooked = 0
        runCatching {
            env.xposed.hook(Thread::class.java.getDeclaredMethod("getStackTrace")).intercept(SingleHooker())
            hooked++
        }
        runCatching {
            env.xposed.hook(Thread::class.java.getDeclaredMethod("getAllStackTraces")).intercept(AllHooker())
            hooked++
        }
        env.log.info("live stack guard: $hooked hooks in ${env.packageName}")
        return hooked
    }

    /** A platform-package class that no loader can resolve is the hook stub; only a first miss is slow. */
    private fun isStub(className: String): Boolean {
        stubs[className]?.let { return it }
        if (stubs.size >= MAX_CACHED) return false
        val unresolvable = try {
            Class.forName(className, false, env.classLoader)
            false
        } catch (_: ClassNotFoundException) {
            true
        } catch (_: LinkageError) {
            false
        }
        stubs[className] = unresolvable
        return unresolvable
    }

    private inner class SingleHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            return try {
                @Suppress("UNCHECKED_CAST") // Thread#getStackTrace returns StackTraceElement[].
                val frames = result as? Array<StackTraceElement>
                if (frames == null || env.switches.paused) result else filter.filter(frames)
            } catch (_: Throwable) {
                result // Fail open: the unfiltered trace is still a working answer.
            }
        }
    }

    private inner class AllHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            try {
                if (!env.switches.paused) {
                    @Suppress("UNCHECKED_CAST") // Thread.getAllStackTraces returns Map<Thread, StackTraceElement[]>.
                    (result as? MutableMap<Thread, Array<StackTraceElement>>)?.let(filter::filterAll)
                }
            } catch (_: Throwable) {
                // Fail open: possibly half-filtered, still a valid map.
            }
            return result
        }
    }

    private companion object {
        const val MAX_CACHED = 1024
    }
}
