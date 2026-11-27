package io.github.hyperprivacy.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * When a native library is loaded in this process, for hooks that must wait until a class's native
 * methods are bound (see [MethodGuard]), and for the native resolver hooks, which patch every
 * library loaded later ([afterEveryLoad], see [NativeResolverGuard]).
 *
 * Every `System.loadLibrary` / `Runtime.loadLibrary` call ends in the private
 * `Runtime.loadLibrary0(ClassLoader, Class, String)` (libcore `ojluni` Runtime), which returns
 * normally only after `nativeLoad` has run the library's `JNI_OnLoad` (and so any
 * `RegisterNatives`). [afterLoad] observes that method once, after the original returned, and
 * removes its own hook as soon as the wanted library is through, leaving nothing behind.
 */
internal object NativeLibraries {
    private const val LOAD_LIBRARY = "loadLibrary0"
    private const val LOAD = "load0"
    private const val MAPS = "/proc/self/maps"

    /** `loadLibrary0(ClassLoader, Class, String)`. */
    private val LOAD_LIBRARY_PARAMS = arrayOf<Class<*>>(ClassLoader::class.java, Class::class.java, String::class.java)

    /** `load0(Class, String)`, behind `System.load(path)`. */
    private val LOAD_PARAMS = arrayOf<Class<*>>(Class::class.java, String::class.java)

    /** Whether `lib<name>.so` is already mapped into this process (read once per call; startup only). */
    fun isMapped(name: String): Boolean = try {
        val suffix = "/lib$name.so"
        File(MAPS).useLines { lines -> lines.any { it.endsWith(suffix) } }
    } catch (_: Throwable) {
        // Unreadable: treat as not loaded and wait for the load instead.
        false
    }

    /**
     * Runs [action] once, right after `System.loadLibrary(name)` returned normally; returns whether
     * the observer could be installed. A failure in [action] is logged; the load is never affected.
     *
     * A load another thread finishes between the caller's [isMapped] check and the observer going
     * in is caught by checking the mapping again once the observer is in place (a load still
     * running then may bind its natives after the hook; a hook that never calls through is not
     * affected by that, at worst it stays off). The observer removes itself exactly once, also
     * when it fires before its handle is published.
     */
    fun afterLoad(xposed: XposedInterface, log: HookLog, name: String, action: () -> Unit): Boolean {
        val target = loadLibrary0() ?: run {
            log.warn("Runtime#$LOAD_LIBRARY(ClassLoader, Class, String) not found; lib$name not observed")
            return false
        }
        val done = AtomicBoolean()
        val handle = AtomicReference<HookHandle?>()
        val installed = log.guard("observe lib$name") {
            xposed.hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (chain.args.lastOrNull() == name && done.compareAndSet(false, true)) {
                        log.guard("after lib$name") { action() }
                        handle.getAndSet(null)?.unhook()
                    }
                } catch (_: Throwable) {
                    // Observing is best effort; the library is loaded either way.
                }
                result
            }
        } ?: return false
        handle.set(installed)
        if (!done.get() && isMapped(name) && done.compareAndSet(false, true)) {
            log.guard("after lib$name") { action() }
        }
        // Fired already, possibly before the handle was set.
        if (done.get()) log.guard("unobserve lib$name") { handle.getAndSet(null)?.unhook() }
        return true
    }

    /**
     * Runs [action] after every library load through `Runtime.loadLibrary0(ClassLoader, Class,
     * String)` and `Runtime.load0(Class, String)` (every `System.loadLibrary` / `System.load`),
     * whether the load succeeded or failed, for the life of the process. The load itself, its
     * result and its exception are untouched; a failing [action] is ignored. Returns the number of
     * entry points observed.
     */
    fun afterEveryLoad(xposed: XposedInterface, log: HookLog, action: () -> Unit): Int {
        val targets = listOfNotNull(loadLibrary0(), runtimeMethod(LOAD, LOAD_PARAMS))
        return targets.count { target ->
            log.guard("observe ${target.name}") {
                xposed.hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    try {
                        chain.proceed()
                    } finally {
                        try {
                            action()
                        } catch (_: Throwable) {
                            // Best effort: the load's own outcome stands either way.
                        }
                    }
                }
            } != null
        }
    }

    private fun loadLibrary0(): Method? = runtimeMethod(LOAD_LIBRARY, LOAD_LIBRARY_PARAMS)

    private fun runtimeMethod(name: String, params: Array<Class<*>>): Method? = try {
        Runtime::class.java.declaredMethods.firstOrNull {
            it.name == name && !Modifier.isNative(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(params)
        }
    } catch (_: Throwable) {
        null
    }
}
