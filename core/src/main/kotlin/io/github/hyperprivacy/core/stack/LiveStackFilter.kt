package io.github.hyperprivacy.core.stack

/**
 * Removes the module's own frames from a *live* stack sample (`Thread#getStackTrace`,
 * `Thread#getAllStackTraces`), so an app sampling a thread while a hooked API runs does not see
 * classes its own class loader cannot resolve.
 *
 * What is dropped, always as one contiguous run around at least one module frame:
 * - module frames ([isModule]: classes the module class loader defines, R8-renamed or not);
 * - the hook framework's frames between and next to them: classes of the known framework packages
 *   ([FRAMEWORK_ROOTS]) and the generated hook stub. The stub is a class with a random name that
 *   the framework injects into the hooked method's own package, so it lives under a platform root
 *   ([PLATFORM_ROOTS]) yet no loader resolves it ([isStub], consulted only for such names that
 *   touch a module frame).
 *
 * App frames and genuine platform frames (the hooked method's own frame included) are never
 * dropped: a frame leaves only when it is a module frame, or a framework/stub frame in direct
 * contact with one.
 *
 * Hot path: [filter] returns the *same array* without allocating when no frame is a module frame
 * (the typical logging call); only [isModule] runs then, and it is expected to be a cached lookup.
 */
public class LiveStackFilter(private val isModule: (String) -> Boolean, private val isStub: (String) -> Boolean) {
    /** [frames] without bridge frames, or [frames] itself when there is nothing to drop. */
    public fun filter(frames: Array<StackTraceElement>): Array<StackTraceElement> {
        var firstModule = -1
        for (i in frames.indices) {
            if (isModule(frames[i].className)) {
                firstModule = i
                break
            }
        }
        if (firstModule < 0) return frames
        val drop = BooleanArray(frames.size)
        var dropped = 0
        for (i in firstModule until frames.size) {
            if (drop[i] || !isModule(frames[i].className)) continue
            drop[i] = true
            dropped++
            dropped += extend(frames, drop, i, -1) + extend(frames, drop, i, 1)
        }
        if (dropped == frames.size) return frames // Never return an empty trace: not plausible, fail open.
        val kept = arrayOfNulls<StackTraceElement>(frames.size - dropped)
        var next = 0
        for (i in frames.indices) if (!drop[i]) kept[next++] = frames[i]
        @Suppress("UNCHECKED_CAST") // Every slot was filled above; the array type is the declared one.
        return kept as Array<StackTraceElement>
    }

    /** Marks bridge frames next to [from] in direction [step]; returns how many were newly marked. */
    private fun extend(frames: Array<StackTraceElement>, drop: BooleanArray, from: Int, step: Int): Int {
        var count = 0
        var j = from + step
        while (j in frames.indices && !drop[j] && isBridge(frames[j].className)) {
            drop[j] = true
            count++
            j += step
        }
        return count
    }

    private fun isBridge(className: String): Boolean =
        isModule(className) || startsWithAny(className, FRAMEWORK_ROOTS) ||
            (startsWithAny(className, PLATFORM_ROOTS) && isStub(className))

    /** The `Map` form of a `getAllStackTraces` result: filters every value in place; `true` if any changed. */
    public fun filterAll(traces: MutableMap<Thread, Array<StackTraceElement>>): Boolean {
        var changed = false
        for (entry in traces.entries) {
            val before = entry.value
            val after = filter(before)
            if (after !== before) {
                entry.setValue(after)
                changed = true
            }
        }
        return changed
    }

    public companion object {
        /** Hook-framework packages whose frames are bridge frames. */
        public val FRAMEWORK_ROOTS: Array<String> =
            arrayOf("org.lsposed.", "io.github.libxposed.", "de.robv.android.xposed.")

        /** Packages the hook stub is injected into (the hooked method's declaring class lives here). */
        public val PLATFORM_ROOTS: Array<String> =
            arrayOf("java.", "javax.", "android.", "dalvik.", "libcore.", "com.android.")

        /** Whether [name] is under one of [PLATFORM_ROOTS]. */
        public fun isPlatform(name: String): Boolean = startsWithAny(name, PLATFORM_ROOTS)

        private fun startsWithAny(name: String, roots: Array<String>): Boolean {
            for (root in roots) if (name.startsWith(root)) return true
            return false
        }
    }
}
