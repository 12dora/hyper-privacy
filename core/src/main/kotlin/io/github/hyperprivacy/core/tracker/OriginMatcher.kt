package io.github.hyperprivacy.core.tracker

/**
 * Attributes code to the advertising SDK it belongs to, by class name: one longest-prefix trie
 * ([CodePrefixMatcher]) over the enabled SDKs' code prefixes (database prefixes, relocated copies
 * found through manifest anchors, suspected components the user enabled), the app's own code
 * roots and the platform / runtime namespaces. Longest prefix wins, so an SDK nested in the app's
 * namespace (`com.app.ads.` under `com.app.`) is still the SDK, and the app's own package nested
 * in an SDK's namespace is still the app.
 *
 * [attribute] reads a call stack from the innermost frame outwards: the first SDK frame decides
 * (the request originates in that SDK, however it reached the network: directly, through an
 * executor, a Handler or its own thread), and an app frame met before any SDK frame means the app
 * itself asked (e.g. from an SDK callback), so the request is left alone. Platform frames are
 * skipped; frames of any other class are skipped unless [attribute]'s resolver claims them (classes
 * defined by a class loader an SDK created).
 *
 * Immutable and thread-safe. Cost, measured by `OriginMatcherTest` on a desktop JVM: [classify] is
 * one trie walk over the class name, allocation-free, in the order of 60 ns; [attribute] over a
 * typical 40-frame stack takes about 2-3 µs, on top of capturing the stack itself
 * (`Throwable().stackTrace`, about 10-30 µs on a phone), and is meant only for the points where a
 * request, socket or web view is created, never per byte or per connect.
 */
public class OriginMatcher(sdkPrefixes: List<Pair<String, String>>, ownPrefixes: Collection<String>) {
    private val trie = CodePrefixMatcher(
        sdkPrefixes.filter { it.second != OWN_ID && it.second != PLATFORM_ID } +
            ownPrefixes.map { it to OWN_ID } +
            PLATFORM_PREFIXES.map { it to PLATFORM_ID },
    )
    private val ownIndex = trie.ids.indexOf(OWN_ID)
    private val platformIndex = trie.ids.indexOf(PLATFORM_ID)

    /** Whether no SDK prefix is known: [attribute] never returns an SDK. */
    public val isEmpty: Boolean = trie.ids.none { it != OWN_ID && it != PLATFORM_ID }

    /**
     * What [className] (dot form) is: an SDK (index for [sdkId], >= 0), [OWN], [PLATFORM] or
     * [UNKNOWN]. Allocation-free.
     */
    public fun classify(className: String): Int = when (val index = trie.matchName(className)) {
        NO_MATCH -> UNKNOWN
        ownIndex -> OWN
        platformIndex -> PLATFORM
        else -> index
    }

    /** The SDK id of an index returned by [classify] or [attribute]. */
    public fun sdkId(index: Int): String = trie.ids[index]

    /** Index of [sdkId] for [attribute]'s resolver, or [UNKNOWN] if it has no prefix here. */
    public fun indexOf(sdkId: String): Int = trie.ids.indexOf(sdkId).takeIf { it >= 0 } ?: UNKNOWN

    /**
     * The SDK the innermost decisive frame of [frames] belongs to (an index for [sdkId]), [OWN]
     * when an app frame comes first, or [UNKNOWN] when no frame is claimed. At most [MAX_FRAMES] frames
     * are read. [resolver], if given, is asked about frames of unknown classes only (it may return
     * an SDK index from [indexOf], else [UNKNOWN]); it is the only part that may cost more.
     */
    public fun attribute(frames: Array<StackTraceElement>, resolver: ((String) -> Int)? = null): Int {
        val end = minOf(frames.size, MAX_FRAMES)
        for (i in 0 until end) {
            val name = frames[i].className
            when (val kind = classify(name)) {
                OWN -> return OWN

                PLATFORM -> Unit

                UNKNOWN -> if (resolver != null) {
                    val resolved = resolver(name)
                    if (resolved >= 0) return resolved
                }

                else -> return kind
            }
        }
        return UNKNOWN
    }

    public companion object {
        /** [classify]: no prefix covers the class. */
        public const val UNKNOWN: Int = -1

        /** [classify]: the app's own code. */
        public const val OWN: Int = -2

        /** [classify]: the platform, the runtime or the hooking framework. */
        public const val PLATFORM: Int = -3

        /** Frames read per stack; deeper frames are thread entry plumbing. */
        public const val MAX_FRAMES: Int = 256

        private const val NO_MATCH = -1
        private const val OWN_ID = "\u0000own"
        private const val PLATFORM_ID = "\u0000platform"

        /**
         * Namespaces that never decide an attribution: the platform and runtime, Kotlin, the
         * hooking framework and this module. SDK prefixes nested in them (`com.android.gdt.`)
         * still win, being longer.
         */
        public val PLATFORM_PREFIXES: List<String> = listOf(
            "java.", "javax.", "jdk.", "sun.", "libcore.", "dalvik.", "android.", "androidx.", "com.android.",
            "kotlin.", "kotlinx.", "org.json.", "org.chromium.", "com.google.android.webview.",
            "io.github.libxposed.", "org.lsposed.", "de.robv.android.xposed.", "LSPHooker_",
            "io.github.hyperprivacy.",
        )
    }
}
