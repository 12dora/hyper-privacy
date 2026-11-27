package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.stack.FrameOrigin
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * Tells [io.github.hyperprivacy.core.stack.LiveStackFilter] which class loader a frame's class
 * name belongs to, so hook-framework frames are recognised even when the framework randomises its
 * class names (see [FrameOrigin]).
 *
 * The framework's loader is taken from classes it certainly defined: the [XposedInterface]
 * implementation handed to the module, the API interface itself (the module only compiles against
 * it) and the module loader's parent; the app's, the boot and the module's own loaders are never
 * counted as the framework's. A name counts as the framework's only if a framework loader itself
 * defined it (`ClassLoader#findLoadedClass` on that loader, then `Class#getClassLoader`). Whether
 * the app or the platform knows a name is asked the same way, of the app's and the boot loader.
 * Only `findLoadedClass` is used: it never calls a loader's Java `loadClass`, so no custom loader
 * code, lock or initialiser runs inside a stack sample. It reliably answers for classes already
 * loaded by that loader, which every class with a frame on the stack is; for anything else this
 * code assumes nothing. A lookup that fails counts as "known to the app" (keep the frame).
 *
 * Consequences, all fail-open: classes only a split, plugin or WebView loader defines read as
 * [FrameOrigin.FOREIGN], which alone never drops a frame; an app class sharing a framework class's
 * name and not yet loaded when that name is first seen next to a window reads as
 * [FrameOrigin.FRAMEWORK] from then on (a residual: such a frame would also have to sit right at
 * a window's edge).
 *
 * Each answer is computed once per class name and cached ([FrameOrigin.Cache]); only names at the
 * edges of a module window are ever asked about, inside the guard's re-entrancy fence.
 */
internal object FrameLoaders {
    /** The origin oracle for [env]'s process, or [FrameOrigin.NONE] if the lookup is unavailable. */
    fun origin(env: GuardEnv): FrameOrigin = try {
        val find = ClassLoader::class.java.getDeclaredMethod("findLoadedClass", String::class.java)
        find.isAccessible = true
        val boot = Any::class.java.classLoader
        val module = ModuleFrames::class.java.classLoader
        val framework = listOfNotNull(
            env.xposed.javaClass.classLoader,
            XposedInterface::class.java.classLoader,
            module?.parent,
        ).filter { it !== boot && it !== module && it !== env.classLoader }
        val distinct = ArrayList<ClassLoader>(framework.size)
        for (loader in framework) if (distinct.none { it === loader }) distinct += loader
        val lookup = Lookup(find, distinct.toTypedArray(), env.classLoader, boot)
        FrameOrigin.Cache(lookup::origin)
    } catch (t: Throwable) {
        env.log.warn("live stack guard: class origin lookup unavailable", t)
        FrameOrigin.NONE
    }

    private class Lookup(
        private val find: Method,
        private val framework: Array<ClassLoader>,
        private val app: ClassLoader,
        private val boot: ClassLoader?,
    ) {
        fun origin(name: String): Int {
            for (loader in framework) {
                if (find(loader, name)?.classLoader === loader) {
                    return if (appKnows(name)) FrameOrigin.SHARED else FrameOrigin.FRAMEWORK
                }
            }
            val known = appKnows(name) || (boot != null && find(boot, name) != null)
            return if (known) FrameOrigin.OTHER else FrameOrigin.FOREIGN
        }

        /** Whether the app's loader has [name] loaded (or reports it); never loads a class itself. */
        private fun appKnows(name: String): Boolean = find(app, name) != null

        /** The class [loader] has (or would) load for [name], or `null`; a failed lookup counts as found. */
        private fun find(loader: ClassLoader, name: String): Class<*>? = try {
            find.invoke(loader, name) as? Class<*>
        } catch (_: Throwable) {
            Any::class.java // Erroneous class or refused lookup: unsure, so not foreign.
        }
    }
}
