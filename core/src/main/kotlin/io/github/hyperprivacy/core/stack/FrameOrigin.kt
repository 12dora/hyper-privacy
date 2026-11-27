package io.github.hyperprivacy.core.stack

/**
 * Which class loader a stack frame's class name belongs to, for frames next to a module window
 * ([LiveStackFilter]). Hook frameworks may randomise their own class names per install or boot
 * (short default-package names, a random package with `HookBridge`, stubs placed in platform
 * packages), so name lists cannot recognise them; the loader that defined the class can.
 *
 * A frame carries only a class name, so an answer is about the name: [of] must be cheap for a
 * name seen before (see [Cache]) and must never throw.
 */
public fun interface FrameOrigin {
    /** One of [OTHER], [FRAMEWORK], [SHARED] or [FOREIGN] for [className]. */
    public fun of(className: String): Int

    public companion object {
        /** An app or platform class, or unknown: never dropped for its origin. */
        public const val OTHER: Int = 0

        /** Defined by the hook framework's class loader, and the app cannot resolve that name. */
        public const val FRAMEWORK: Int = 1

        /** Defined by the hook framework's class loader, but the app resolves that name too (an R8 name clash). */
        public const val SHARED: Int = 2

        /** Known to none of the framework's, the app's and the boot loader: a hook stub or runtime-loaded code. */
        public const val FOREIGN: Int = 3

        /** Knows nothing: only the name rules of [LiveStackFilter] apply. */
        public val NONE: FrameOrigin = FrameOrigin { OTHER }
    }

    /**
     * Memoises [resolve] in a fixed, direct-mapped table of [SLOTS] entries keyed by the exact
     * class name: a hit costs the name's hash and one `equals`; no lock, no allocation. Writers race
     * benignly: an entry is immutable and fully built before it is published, so a reader sees a
     * whole entry or another one, and a lost or evicted entry is just resolved again. A [resolve]
     * that throws answers [OTHER] (keep the frame), and that answer is cached too.
     */
    public class Cache(private val resolve: (String) -> Int) : FrameOrigin {
        private class Entry(val name: String, val origin: Int)

        private val slots = arrayOfNulls<Entry>(SLOTS)

        override fun of(className: String): Int {
            val slot = className.hashCode() and (SLOTS - 1)
            val hit = slots[slot]
            if (hit != null && hit.name == className) return hit.origin
            val origin = try {
                val resolved = resolve(className)
                if (resolved in OTHER..FOREIGN) resolved else OTHER
            } catch (_: Throwable) {
                OTHER // Unsure: keep the frame.
            }
            slots[slot] = Entry(className, origin)
            return origin
        }

        private companion object {
            /** A power of two; a hooked process sees a few dozen distinct names next to its windows. */
            const val SLOTS = 128
        }
    }
}
