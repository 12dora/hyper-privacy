package io.github.hyperprivacy.core.rule

/**
 * Install-time signature check of a METHOD rule ([RulePayload.Method.params] /
 * [RulePayload.Method.returns]): `null` means "not pinned". Names are binary names as
 * `Class.getName` returns them, so `int` and `java.lang.Integer` never match each other.
 */
public data class MethodShape(val params: List<String>?, val returns: String?) {
    /** Whether a method taking [paramNames] and returning [returnName] has this shape. */
    public fun accepts(paramNames: List<String>, returnName: String): Boolean =
        (params == null || params == paramNames) && (returns == null || returns == returnName)

    public companion object {
        /** `Class.getName` of the eight primitive types and `void`. */
        public val PRIMITIVE_NAMES: Set<String> =
            setOf("boolean", "byte", "short", "char", "int", "long", "float", "double", "void")
    }
}
