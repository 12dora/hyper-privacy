package io.github.hyperprivacy.xposed

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap

/**
 * Rewrites the stack trace of an exception a hook throws on purpose, so the app (and any crash SDK
 * that uploads traces) sees what a genuine failure of the hooked method would show:
 * - every frame of a module class is dropped (our hooker, decision logic, error factories, and the
 *   bundled Kotlin runtime they run on); a class is "ours" when the module class loader defines it,
 *   so this holds after R8 renames or repackages classes;
 * - a frame for the hooked method itself is put on top, where the original would have thrown.
 *
 * What remains are the app's own frames and the framework's hook-bridge frames between them and
 * the hooked method. The bridge frames are inherent to LSPosed (they are also present in every
 * exception a hooked method throws naturally) and are not ours to hide. Likewise our hooker's frame
 * stays visible in exceptions thrown by the *original* method through `chain.proceed()`.
 */
internal object StackScrub {
    private val moduleLoader: ClassLoader? = StackScrub::class.java.classLoader
    private val ownership = ConcurrentHashMap<String, Boolean>()

    /** Cause-chain depth rewritten; real platform exceptions are rarely deeper. */
    private const val MAX_CHAIN = 4
    private const val MAX_CACHED = 1024

    /** Package roots that the hooked app may share with the module class loader (bundled runtimes). */
    private val SHARED_ROOTS = arrayOf("java.", "javax.", "android.", "dalvik.", "libcore.", "com.android.")

    private val KOTLIN_ROOTS = arrayOf("kotlin.", "kotlinx.")

    /** Rewrites [error] and its causes in place; returns [error]. Never throws. */
    fun <T : Throwable> scrub(error: T, hooked: Executable?): T {
        try {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CHAIN) {
                current.stackTrace = rewrite(current.stackTrace, if (depth == 0) hooked else null)
                current = current.cause?.takeIf { it !== current }
                depth++
            }
        } catch (_: Throwable) {
            // Best effort: an unscrubbed trace is still a working error.
        }
        return error
    }

    private fun rewrite(frames: Array<StackTraceElement>, hooked: Executable?): Array<StackTraceElement> {
        val kept = ArrayList<StackTraceElement>(frames.size + 1)
        hooked?.let { kept += frameOf(it) }
        // Leading frames are the hook's own call path: drop all of ours, bundled Kotlin included.
        // Below the first foreign frame the app's own Kotlin runtime may appear, so keep it.
        var start = 0
        while (start < frames.size && isModuleFrame(frames[start].className)) start++
        for (i in start until frames.size) {
            val name = frames[i].className
            if (KOTLIN_ROOTS.none(name::startsWith) && isModuleFrame(name)) continue
            kept += frames[i]
        }
        if (hooked != null && kept.size > 1 && kept[1] == kept[0]) kept.removeAt(1)
        return kept.toTypedArray()
    }

    private fun frameOf(executable: Executable): StackTraceElement {
        val type = executable.declaringClass
        val method = if (executable is Constructor<*>) "<init>" else executable.name
        val outer = type.name.substringAfterLast('.').substringBefore('$')
        return StackTraceElement(type.name, method, "$outer.java", -1)
    }

    private fun isModuleFrame(className: String): Boolean {
        if (moduleLoader == null || SHARED_ROOTS.any(className::startsWith)) return false
        ownership[className]?.let { return it }
        val ours = try {
            Class.forName(className, false, moduleLoader).classLoader === moduleLoader
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (ownership.size < MAX_CACHED) ownership[className] = ours
        return ours
    }
}
