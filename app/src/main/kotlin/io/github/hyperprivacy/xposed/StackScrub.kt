package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.net.PlausibleFrames
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Genuine platform frames to put on top of a thrown error (and of its first cause) instead of the
 * single synthesized frame of the hooked method; see `ConnectFrames`.
 */
internal class TopFrames(val error: Array<StackTraceElement>, val cause: Array<StackTraceElement>?)

/**
 * Rewrites the stack trace of an exception a hook throws on purpose, so the app (and any crash SDK
 * that uploads traces) sees what a genuine failure of the hooked method would show:
 * - every frame of a module class is dropped (our hooker, decision logic, error factories, and the
 *   bundled Kotlin runtime they run on); a class is "ours" when the module class loader defines it,
 *   so this holds after R8 renames or repackages classes;
 * - on top go genuine platform frames when the caller has them ([TopFrames]), else one frame for the
 *   hooked method itself, where the original would have thrown, with a stable plausible line number
 *   ([PlausibleFrames.line]).
 *
 * What remains are the app's own frames and the framework's hook-bridge frames between them and
 * the hooked method. The bridge frames are inherent to LSPosed (they are also present in every
 * exception a hooked method throws naturally) and are not ours to hide. Likewise our hooker's frame
 * stays visible in exceptions thrown by the *original* method through `chain.proceed()`.
 *
 * Cost: an SDK retrying a blocked request in a loop scrubs on every attempt, on its own thread.
 * Ownership is decided with `ClassLoader#findLoadedClass` (a class-table lookup that returns `null`
 * instead of building a `ClassNotFoundException` and its stack trace) and cached per class name up
 * to [MAX_CACHED] names, so an uncached frame stays cheap once the cache is full.
 */
internal object StackScrub {
    private val moduleLoader: ClassLoader? = StackScrub::class.java.classLoader
    private val ownership = ConcurrentHashMap<String, Boolean>()

    /** `ClassLoader#findLoadedClass(String)` (protected SDK API), or `null` to fall back to `Class.forName`. */
    private val findLoaded: Method? = try {
        ClassLoader::class.java.getDeclaredMethod("findLoadedClass", String::class.java).apply { isAccessible = true }
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** Cause-chain depth rewritten; real platform exceptions are rarely deeper. */
    private const val MAX_CHAIN = 4
    private const val MAX_CACHED = 1024

    /** Package roots that the hooked app may share with the module class loader (bundled runtimes). */
    private val SHARED_ROOTS = arrayOf("java.", "javax.", "android.", "dalvik.", "libcore.", "com.android.")

    private val KOTLIN_ROOTS = arrayOf("kotlin.", "kotlinx.")

    /** Rewrites [error] and its causes in place; returns [error]. Never throws. */
    fun <T : Throwable> scrub(error: T, hooked: Executable?, top: TopFrames? = null): T {
        try {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CHAIN) {
                val head = when (depth) {
                    0 -> top?.error ?: hooked?.let { arrayOf(frameOf(it)) }
                    1 -> top?.cause
                    else -> null
                }
                current.stackTrace = rewrite(current.stackTrace, head)
                current = current.cause?.takeIf { it !== current }
                depth++
            }
        } catch (_: Throwable) {
            // Best effort: an unscrubbed trace is still a working error.
        }
        return error
    }

    private fun rewrite(frames: Array<StackTraceElement>, head: Array<StackTraceElement>?): Array<StackTraceElement> {
        val kept = ArrayList<StackTraceElement>(frames.size + (head?.size ?: 0))
        head?.let { kept += it }
        // Leading frames are the hook's own call path: drop all of ours, bundled Kotlin included.
        // Below the first foreign frame the app's own Kotlin runtime may appear, so keep it.
        var start = 0
        while (start < frames.size && isModuleFrame(frames[start].className)) start++
        for (i in start until frames.size) {
            val name = frames[i].className
            // The hooked method's own frame can survive below a synthesized one; never show it twice.
            val duplicate = i == start && !head.isNullOrEmpty() && frames[i] == head.last()
            if (!duplicate && (KOTLIN_ROOTS.any(name::startsWith) || !isModuleFrame(name))) kept += frames[i]
        }
        return kept.toTypedArray()
    }

    private fun frameOf(executable: Executable): StackTraceElement {
        val type = executable.declaringClass
        val method = if (executable is Constructor<*>) "<init>" else executable.name
        val outer = type.name.substringAfterLast('.').substringBefore('$')
        return StackTraceElement(type.name, method, "$outer.java", PlausibleFrames.line(type.name, method))
    }

    private fun isModuleFrame(className: String): Boolean {
        if (moduleLoader == null || SHARED_ROOTS.any(className::startsWith)) return false
        ownership[className]?.let { return it }
        val ours = loadedByModule(className)
        if (ownership.size < MAX_CACHED) ownership[className] = ours
        return ours
    }

    /** Every module frame belongs to a class the module loader has already loaded, since it is running. */
    private fun loadedByModule(className: String): Boolean {
        val loader = moduleLoader ?: return false
        return try {
            val type = findLoaded?.invoke(loader, className) as? Class<*>
                ?: if (findLoaded == null) Class.forName(className, false, loader) else null
            type?.classLoader === loader
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
