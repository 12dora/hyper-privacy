package io.github.hyperprivacy.core.conflict

/**
 * The apps another Xposed module declares it hooks, read from its own APK: the modern API's
 * `META-INF/xposed/scope.list` (one package per line) or the legacy `xposedscope` string-array.
 * A declaration is only the module's own suggestion (LSPosed lets the user change the scope), so
 * it narrows what the overlap page lists; it never proves an overlap.
 */
public sealed interface DeclaredScope {
    /** Nothing usable declared (absent, unreadable, malformed or over the limits). */
    public data object Unknown : DeclaredScope

    /** Every app (a wildcard entry). */
    public data object AllApps : DeclaredScope

    /** These packages; `system` / `android` stand for system_server. */
    public data class Apps(val packages: Set<String>) : DeclaredScope

    public companion object {
        /** Most entries kept, and most characters read; a real scope is far smaller. */
        public const val MAX_ENTRIES: Int = 512
        public const val MAX_CHARS: Int = 64 * 1024

        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")
        private const val MAX_PACKAGE = 255
        private val WILDCARDS = setOf("*", "all", ".*")

        /** The modern `scope.list` text: one entry per line, `#` comments and blank lines skipped. */
        public fun parseList(text: String?): DeclaredScope {
            if (text == null || text.length > MAX_CHARS) return Unknown
            return of(text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList())
        }

        /** Entries as read (the legacy string-array); invalid entries are skipped. */
        public fun of(entries: List<String?>?): DeclaredScope {
            if (entries == null) return Unknown
            val names = entries.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            if (names.any { it.lowercase() in WILDCARDS }) return AllApps
            val valid = names.filter { it.length <= MAX_PACKAGE && PACKAGE.matches(it) }.distinct()
            return when {
                valid.isEmpty() || valid.size > MAX_ENTRIES -> Unknown
                else -> Apps(valid.toSet())
            }
        }
    }
}
