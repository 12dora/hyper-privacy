package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.net.PlausibleFrames
import java.lang.reflect.Constructor
import java.lang.reflect.Executable

/**
 * Genuine platform frames to put on top of a thrown error (and of its first cause) instead of the
 * single synthesized frame of the hooked method; see `ConnectFrames`.
 */
internal class TopFrames(val error: Array<StackTraceElement>, val cause: Array<StackTraceElement>?)

/**
 * Rewrites the stack trace of an exception a hook throws on purpose, so the app (and any crash SDK
 * that uploads traces) sees what a genuine failure of the hooked method would show:
 * - every frame of a module class is dropped (our hooker, decision logic, error factories, and the
 *   bundled Kotlin runtime they run on); a class is "ours" by [ModuleFrames]' package test, and
 *   leading `kotlin.`/`kotlinx.` frames (our runtime in debug builds) go too, while such frames
 *   further down, the app's own runtime, stay;
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
 * Ownership is a `startsWith` per frame ([ModuleFrames]): no class lookup, reflection or cache.
 */
internal object StackScrub {
    /** Cause-chain depth rewritten; real platform exceptions are rarely deeper. */
    private const val MAX_CHAIN = 4
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
        while (start < frames.size && isLeadingOurs(frames[start].className)) start++
        for (i in start until frames.size) {
            val name = frames[i].className
            // The hooked method's own frame can survive below a synthesized one; never show it twice.
            val duplicate = i == start && !head.isNullOrEmpty() && frames[i] == head.last()
            if (!duplicate && !ModuleFrames.isOurs(name)) kept += frames[i]
        }
        return kept.toTypedArray()
    }

    private fun frameOf(executable: Executable): StackTraceElement {
        val type = executable.declaringClass
        val method = if (executable is Constructor<*>) "<init>" else executable.name
        val outer = type.name.substringAfterLast('.').substringBefore('$')
        return StackTraceElement(type.name, method, "$outer.java", PlausibleFrames.line(type.name, method))
    }

    /** In the leading run our bundled Kotlin runtime counts as ours (debug builds keep its `kotlin.` names). */
    private fun isLeadingOurs(name: String): Boolean = ModuleFrames.isOurs(name) || KOTLIN_ROOTS.any(name::startsWith)
}
