package io.github.hyperprivacy.core.stack

/**
 * Removes the module's own frames from a *live* stack sample (`Thread#getStackTrace`,
 * `Thread#getAllStackTraces`), so an app sampling a thread while a hooked API runs does not see
 * classes its own class loader cannot resolve.
 *
 * Ownership is a pure name test: a frame is the module's iff its class name starts with one of
 * [modulePrefixes] (the module ships its classes in one package of its own, see the app's R8
 * rules). No class is loaded or looked up, so the test is collision-free against the app's own
 * R8-renamed classes (`a`, `zn`) and never claims `kotlin.`, `kotlinx.` or `androidx.` frames:
 * those names, wherever they appear, belong to whoever bundles them.
 *
 * What is dropped, always as one contiguous run around at least one module frame (a *window*):
 * - module frames;
 * - hook-framework frames touching them: classes of the known framework packages and LSPosed's
 *   generated stub classes ([FRAMEWORK_ROOTS], `LSPHooker_*` in the default package), plus one
 *   reflective `Method.invoke` frame right above the window (the framework calling the hooked
 *   method's backup), whatever the stub;
 * - when the window holds no generated `LSPHooker_*` stub, a stub the framework placed elsewhere
 *   (the hooked method's own package, or a random default-package class): the one frame right
 *   below the window (caller side) whose method has the hooked method's name, in the hooked
 *   method's package or the default package, but another class. The hooked method is the frame
 *   right above the window (the framework's backup of it keeps its class and name). Overload
 *   delegation (`C.m` calling `C.m`) is never mistaken for a stub: the class must differ.
 *
 * App frames and genuine platform frames (the hooked method's own frame, WebView's
 * `com.android.webview.chromium.*`, `sun.*` callers) are never dropped otherwise. Several
 * windows in one stack (nested hooks, a hooker calling back into app code) are filtered
 * independently; app frames between them stay.
 *
 * Cost: one `startsWith` per prefix per frame, no allocation when no frame is a module frame (the
 * original array is returned). A filtered stack costs one `BooleanArray` and the trimmed copy.
 */
public class LiveStackFilter(private val modulePrefixes: Array<String>) {
    /** Whether [className] is one of the module's classes; O(prefixes), allocation-free. */
    public fun isModule(className: String): Boolean = startsWithAny(className, modulePrefixes)

    /**
     * [frames] without module windows, or [frames] itself when there is nothing to drop (or
     * everything would be). With [firstWindowOnly] only the innermost window goes: the calling
     * hook's own frames when the current thread samples itself while protection is paused.
     */
    public fun filter(frames: Array<StackTraceElement>, firstWindowOnly: Boolean = false): Array<StackTraceElement> {
        var marks: BooleanArray? = null
        var floor = 0 // First index a new window may extend up to (below the previous window).
        var i = 0
        while (i < frames.size) {
            if (isModule(frames[i].className)) {
                val drop = marks ?: BooleanArray(frames.size).also { marks = it }
                i = markWindow(frames, i, floor, drop) + 1
                floor = i
                if (firstWindowOnly) break
            } else {
                i++
            }
        }
        return marks?.let { compact(frames, it) } ?: frames
    }

    /** Marks the window around module frame [at], not above [floor]; returns its last index. */
    private fun markWindow(frames: Array<StackTraceElement>, at: Int, floor: Int, drop: BooleanArray): Int {
        var top = at
        while (top - 1 >= floor && isBridge(frames[top - 1].className)) top--
        // The framework may call the hooked method's backup reflectively: that invoke frame is ours too.
        if (top - 1 >= floor && isReflectInvoke(frames[top - 1])) top--
        var bottom = at
        while (bottom + 1 < frames.size && isBridge(frames[bottom + 1].className)) bottom++
        if (hasPackageStub(frames, top, bottom, floor)) bottom++
        for (j in top..bottom) drop[j] = true
        return bottom
    }

    /** [frames] without the marked ones; [frames] itself if all are marked (an empty trace is not plausible). */
    private fun compact(frames: Array<StackTraceElement>, drop: BooleanArray): Array<StackTraceElement> {
        var dropped = 0
        for (marked in drop) if (marked) dropped++
        if (dropped == frames.size) return frames
        val kept = arrayOfNulls<StackTraceElement>(frames.size - dropped)
        var next = 0
        for (j in frames.indices) if (!drop[j]) kept[next++] = frames[j]
        @Suppress("UNCHECKED_CAST") // Every slot was filled above; the array type is the declared one.
        return kept as Array<StackTraceElement>
    }

    private fun isBridge(className: String): Boolean = isModule(className) || startsWithAny(className, FRAMEWORK_ROOTS)

    private fun hasGeneratedStub(frames: Array<StackTraceElement>, top: Int, bottom: Int): Boolean {
        for (j in top..bottom) if (frames[j].className.startsWith(GENERATED_STUB)) return true
        return false
    }

    /** Whether the frame right below the window [top]..[bottom] is a stub outside the generated `LSPHooker_` class. */
    private fun hasPackageStub(frames: Array<StackTraceElement>, top: Int, bottom: Int, floor: Int): Boolean {
        val hooked = top - 1 // The hooked method's backup frame.
        if (hooked < floor || bottom + 1 >= frames.size) return false
        return !hasGeneratedStub(frames, top, bottom) && isPackageStub(frames[hooked], frames[bottom + 1])
    }

    private fun isReflectInvoke(frame: StackTraceElement): Boolean =
        frame.className == REFLECT_METHOD && frame.methodName == INVOKE

    private fun isPackageStub(hooked: StackTraceElement, candidate: StackTraceElement): Boolean {
        val stub = candidate.className
        if (candidate.methodName != hooked.methodName || stub == hooked.className) return false
        return stub.indexOf('.') < 0 || samePackage(stub, hooked.className)
    }

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
        /** Hook-framework packages and LSPosed's generated stub classes: bridge frames when touching a module frame. */
        public val FRAMEWORK_ROOTS: Array<String> =
            arrayOf("org.lsposed.", "io.github.libxposed.", "de.robv.android.xposed.", "LSPHooker_")

        private const val GENERATED_STUB = "LSPHooker_"
        private const val REFLECT_METHOD = "java.lang.reflect.Method"
        private const val INVOKE = "invoke"

        private fun startsWithAny(name: String, roots: Array<String>): Boolean {
            for (root in roots) if (name.startsWith(root)) return true
            return false
        }

        private fun samePackage(a: String, b: String): Boolean {
            val end = a.lastIndexOf('.')
            return end == b.lastIndexOf('.') && a.regionMatches(0, b, 0, end)
        }
    }
}
