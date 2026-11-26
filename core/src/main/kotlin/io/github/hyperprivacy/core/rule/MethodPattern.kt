package io.github.hyperprivacy.core.rule

/**
 * Method name selector: either an exact Java identifier (`track`) or a prefix ending in a single
 * trailing `*` (`track*`). No other wildcards are supported, keeping matches predictable.
 */
public class MethodPattern private constructor(private val stem: String, public val isPrefix: Boolean) {
    public fun matches(methodName: String): Boolean = if (isPrefix) methodName.startsWith(stem) else methodName == stem

    override fun equals(other: Any?): Boolean =
        other is MethodPattern && other.stem == stem && other.isPrefix == isPrefix

    override fun hashCode(): Int = stem.hashCode() * HASH_MULTIPLIER + isPrefix.hashCode()

    override fun toString(): String = if (isPrefix) "$stem$WILDCARD" else stem

    public companion object {
        private const val WILDCARD = '*'
        private const val HASH_MULTIPLIER = 31
        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        /** Parses [text]; throws [IllegalArgumentException] if it is not a valid pattern. */
        public fun parse(text: String): MethodPattern {
            val prefix = text.endsWith(WILDCARD)
            val stem = if (prefix) text.dropLast(1) else text
            require(IDENTIFIER.matches(stem)) { "Invalid method pattern: $text" }
            return MethodPattern(stem, prefix)
        }
    }
}
