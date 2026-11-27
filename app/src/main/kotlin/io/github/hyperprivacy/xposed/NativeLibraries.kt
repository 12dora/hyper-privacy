package io.github.hyperprivacy.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When a native library is loaded in this process, for hooks that must wait until a class's native
 * methods are bound (see [MethodGuard]).
 *
 * Every `System.loadLibrary` / `Runtime.loadLibrary` call ends in the private
 * `Runtime.loadLibrary0(ClassLoader, Class, String)` (libcore `ojluni` Runtime), which returns
 * normally only after `nativeLoad` has run the library's `JNI_OnLoad` (and so any
 * `RegisterNatives`). [afterLoad] observes that method once, after the original returned, and
 * removes its own hook as soon as the wanted library is through, leaving nothing behind.
 */
internal object NativeLibraries {
    private const val LOAD_LIBRARY = "loadLibrary0"
    private const val MAPS = "/proc/self/maps"

    /** `loadLibrary0(ClassLoader, Class, String)`. */
    private val LOAD_LIBRARY_PARAMS = arrayOf(ClassLoader::class.java, Class::class.java, String::class.java)

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
     */
    fun afterLoad(xposed: XposedInterface, log: HookLog, name: String, action: () -> Unit): Boolean {
        val target = loadLibrary0() ?: run {
            log.warn("Runtime#$LOAD_LIBRARY(ClassLoader, Class, String) not found; lib$name not observed")
            return false
        }
        val done = AtomicBoolean()
        var handle: HookHandle? = null
        handle = log.guard("observe lib$name") {
            xposed.hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    if (chain.args.lastOrNull() == name && done.compareAndSet(false, true)) {
                        log.guard("after lib$name") { action() }
                        handle?.unhook()
                    }
                } catch (_: Throwable) {
                    // Observing is best effort; the library is loaded either way.
                }
                result
            }
        }
        return handle != null
    }

    private fun loadLibrary0(): Method? = try {
        Runtime::class.java.declaredMethods.firstOrNull {
            it.name == LOAD_LIBRARY && !Modifier.isNative(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(LOAD_LIBRARY_PARAMS)
        }
    } catch (_: Throwable) {
        null
    }
}
