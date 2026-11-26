package io.github.hyperprivacy.xposed

import dalvik.system.BaseDexClassLoader
import io.github.hyperprivacy.core.status.CodeLoads
import io.github.libxposed.api.XposedInterface.Chain
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostics only (never blocks): records code the app loads at runtime, so the tracker scanner
 * can later look for SDKs that are not in the installed APK (hot fixes, plugins, downloaded dex
 * and native libraries). Installed only when diagnostics mode is on as the process starts; each
 * record is also gated on the switch at the time of the load.
 *
 * Hooks, observed after the original returns normally:
 * - every `dalvik.system.BaseDexClassLoader` constructor (`PathClassLoader`, `DexClassLoader`,
 *   `InMemoryDexClassLoader` and app subclasses all chain into one): the dex path list, or an
 *   in-memory marker for `ByteBuffer` dex;
 * - `java.lang.Runtime#load0` (`System.load`, absolute file) and `loadLibrary0` (`System.loadLibrary`,
 *   resolved through the loader's `findLibrary`). `nativeLoad` is native and not hooked.
 *
 * Not recorded: in-memory dex whose parent loader is this module's class loader or one of its
 * ancestors (the hooking framework's). LSPlant builds every hook trampoline that way, for this
 * module's lazy hooks and for other modules loaded into the process alike; it is not app code.
 *
 * Records go out on the observed-endpoint channel as [CodeLoads] entries (redacted path), bounded by
 * the reporter's endpoint limits plus a per-process de-duplication set.
 */
internal class CodeLoadGuard(private val env: GuardEnv) {
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The module's class loader and its ancestors, minus the boot class loader every app shares. */
    private val frameworkLoaders: Set<ClassLoader> =
        generateSequence(CodeLoadGuard::class.java.classLoader) { it.parent }
            .filter { it !== Any::class.java.classLoader }
            .toSet()

    /** Returns the number of hooked entry points. */
    fun install(): Int {
        val loaders = BaseDexClassLoader::class.java.declaredConstructors.toList()
        loaders.forEach { hookAfter(it, ::onClassLoader) }
        val natives = Runtime::class.java.declaredMethods.filter {
            (it.name == LOAD || it.name == LOAD_LIBRARY) && !Modifier.isNative(it.modifiers) &&
                it.parameterTypes.lastOrNull() == String::class.java
        }
        natives.forEach { hookAfter(it, ::onNativeLoad) }
        env.log.info("code-load diagnostics: ${loaders.size} loader constructor(s), ${natives.size} native load(s)")
        return loaders.size + natives.size
    }

    private fun hookAfter(executable: Executable, record: (Chain) -> Unit) {
        env.xposed.hook(executable).intercept { chain ->
            val result = chain.proceed()
            if (env.switches.observe) {
                try {
                    record(chain)
                } catch (_: Throwable) {
                    // Diagnostics are best effort and must never affect the load.
                }
            }
            result
        }
    }

    private fun onClassLoader(chain: Chain) {
        when (val source = chain.args.firstOrNull()) {
            is String -> source.split(File.pathSeparatorChar).forEach { report(CodeLoads.DEX_HOST, it) }

            is Array<*> -> if (source.isArrayOf<ByteBuffer>() && !isFrameworkDex(chain)) {
                recordPath(CodeLoads.DEX_HOST, CodeLoads.MEMORY_PATH)
            }

            else -> Unit
        }
    }

    /** A hook trampoline: the constructor's parent `ClassLoader` argument is a framework loader. */
    private fun isFrameworkDex(chain: Chain): Boolean {
        val parent = chain.args.firstNotNullOfOrNull { it as? ClassLoader } ?: return false
        return parent in frameworkLoaders
    }

    private fun onNativeLoad(chain: Chain) {
        val args = chain.args
        val name = args.lastOrNull() as? String ?: return
        if (chain.executable.name == LOAD) {
            report(CodeLoads.NATIVE_HOST, name)
            return
        }
        // loadLibrary0(ClassLoader, Class, String) or loadLibrary0(Class, String).
        val loader = args.firstNotNullOfOrNull { it as? ClassLoader } ?: (args.firstOrNull() as? Class<*>)?.classLoader
        (loader as? BaseDexClassLoader)?.findLibrary(name)?.let { report(CodeLoads.NATIVE_HOST, it) }
    }

    private fun report(host: String, path: String) {
        CodeLoads.describe(path, env.packageName)?.let { recordPath(host, it) }
    }

    private fun recordPath(host: String, path: String) {
        val key = "$host$path"
        if (seen.size >= MAX_SEEN || !seen.add(key)) return
        env.reporter.observe(env.packageName, host, path, blocked = false)?.let {
            env.log.info("OBS ${env.packageName} code load $host$path")
        }
    }

    private companion object {
        const val LOAD = "load0"
        const val LOAD_LIBRARY = "loadLibrary0"
        const val MAX_SEEN = 128
    }
}
