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
 *   generated stub classes ([FRAMEWORK_ROOTS], `LSPHooker_*` in the default package), and classes
 *   [origin] reports as defined by the framework's own class loader ([FrameOrigin.FRAMEWORK]),
 *   which is how builds that randomise their class names per install (`k2`, `a.b.c.HookBridge`)
 *   are recognised. A name the framework defines but the app resolves too ([FrameOrigin.SHARED],
 *   an R8 short-name clash) joins only when a certain bridge frame lies beyond it, so an app frame
 *   that merely touches the window is kept. Plus one reflective `Method.invoke` frame right above
 *   the window (the framework calling the hooked method's backup), whatever the stub;
 * - when the window holds no generated `LSPHooker_*` stub, a stub the framework placed elsewhere
 *   (the hooked method's own package, a random default-package class, or a random class in any
 *   package that no class loader of the app resolves, [FrameOrigin.FOREIGN]): the one frame right
 *   below the window (caller side) whose method has the hooked method's name, but another class.
 *   The hooked method is the frame right above the window (the framework's backup of it keeps its
 *   class and name). Overload delegation (`C.m` calling `C.m`) is never mistaken for a stub: the
 *   class must differ. Nothing below the window is taken when the window already holds a frame
 *   with the hooked method's name (the stub, absorbed as a framework frame), and a WebView or
 *   Play services class is never taken for being unknown to the app.
 *
 * App frames and genuine platform frames (the hooked method's own frame, WebView's
 * `com.android.webview.chromium.*`, `sun.*` callers) are never dropped otherwise. Several
 * windows in one stack (nested hooks, a hooker calling back into app code) are filtered
 * independently; app frames between them stay.
 *
 * Cost: one `startsWith` per prefix per frame, no allocation when no frame is a module frame (the
 * original array is returned). A filtered stack costs one `BooleanArray` and the trimmed copy;
 * [origin] is asked only about the few frames at a window's edges (a cached lookup, see
 * [FrameOrigin.Cache]).
 */
public class LiveStackFilter(
    private val modulePrefixes: Array<String>,
    private val origin: FrameOrigin = FrameOrigin.NONE,
) {
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
        var top = extend(frames, at, -1, floor)
        // The framework may call the hooked method's backup reflectively: that invoke frame is ours too.
        if (top - 1 >= floor && isReflectInvoke(frames[top - 1])) top--
        var bottom = extend(frames, at, 1, frames.size - 1)
        if (hasPackageStub(frames, top, bottom, floor)) bottom++
        for (j in top..bottom) drop[j] = true
        return bottom
    }

    /**
     * The last index from [edge] in direction [step] (towards [limit], inclusive) still in the window:
     * bridge frames, and runs of [FrameOrigin.SHARED] frames that end in a bridge frame.
     */
    private fun extend(frames: Array<StackTraceElement>, edge: Int, step: Int, limit: Int): Int {
        var last = edge
        var next = edge + step
        while (next * step <= limit * step) {
            val kind = bridgeOrigin(frames[next].className)
            if (kind == FrameOrigin.FRAMEWORK) {
                last = next
            } else if (kind != FrameOrigin.SHARED) {
                break // A SHARED frame joins only if a bridge frame follows it.
            }
            next += step
        }
        return last
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

    /** [FrameOrigin.FRAMEWORK] for any bridge frame (module, known framework name or class), else [origin]'s answer. */
    private fun bridgeOrigin(className: String): Int {
        if (isModule(className) || startsWithAny(className, FRAMEWORK_ROOTS)) return FrameOrigin.FRAMEWORK
        return origin.of(className)
    }

    /**
     * Whether the window [top]..[bottom] already holds the hooked method's stub: a generated
     * `LSPHooker_` class, or any non-module frame with the hooked method's name (a stub the
     * framework's own loader defined, absorbed as a bridge frame).
     */
    private fun hasStubInside(frames: Array<StackTraceElement>, top: Int, bottom: Int, hooked: String): Boolean {
        for (j in top..bottom) {
            val frame = frames[j]
            if (frame.className.startsWith(GENERATED_STUB)) return true
            if (frame.methodName == hooked && !isModule(frame.className)) return true
        }
        return false
    }

    /** Whether the frame right below the window [top]..[bottom] is a stub the window does not already hold. */
    private fun hasPackageStub(frames: Array<StackTraceElement>, top: Int, bottom: Int, floor: Int): Boolean {
        val hooked = top - 1 // The hooked method's backup frame.
        if (hooked < floor || bottom + 1 >= frames.size) return false
        if (hasStubInside(frames, top, bottom, frames[hooked].methodName)) return false
        return isPackageStub(frames[hooked], frames[bottom + 1])
    }

    private fun isReflectInvoke(frame: StackTraceElement): Boolean =
        frame.className == REFLECT_METHOD && frame.methodName == INVOKE

    private fun isPackageStub(hooked: StackTraceElement, candidate: StackTraceElement): Boolean {
        val stub = candidate.className
        if (candidate.methodName != hooked.methodName || stub == hooked.className) return false
        if (stub.indexOf('.') < 0 || samePackage(stub, hooked.className)) return true
        // Unknown to the app's and the boot loader, but not code another loader of the platform defines.
        return !startsWithAny(stub, LOADED_ELSEWHERE) && origin.of(stub) == FrameOrigin.FOREIGN
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

        /** Platform code defined by loaders other than the app's and the boot one (WebView, Play services modules). */
        private val LOADED_ELSEWHERE =
            arrayOf("org.chromium.", "com.android.webview.", "com.google.android.webview.", "com.google.android.gms.")

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
