package io.github.hyperprivacy.core.method

/** What a no-op'd method should do instead of running. */
public sealed interface Replacement {
    /** Leave the method alone; its return type has no safe default. */
    public data object Skip : Replacement

    /** Return [value] without calling the original. */
    public data class Return(val value: Any?) : Replacement
}

/** Picks the inert return value for a method being replaced. */
public object DefaultReturn {
    private val PRIMITIVE_DEFAULTS: Map<Class<*>, Any> = mapOf(
        java.lang.Boolean.TYPE to false,
        java.lang.Byte.TYPE to 0.toByte(),
        java.lang.Short.TYPE to 0.toShort(),
        java.lang.Character.TYPE to Char.MIN_VALUE,
        java.lang.Integer.TYPE to 0,
        java.lang.Long.TYPE to 0L,
        java.lang.Float.TYPE to 0f,
        java.lang.Double.TYPE to 0.0,
    )

    /**
     * `void` → `null`; primitives → their zero value; reference types → `null` only when
     * [allowNullReference], otherwise [Replacement.Skip].
     */
    public fun forType(returnType: Class<*>, allowNullReference: Boolean): Replacement = when {
        returnType == java.lang.Void.TYPE -> Replacement.Return(null)
        returnType.isPrimitive -> Replacement.Return(PRIMITIVE_DEFAULTS.getValue(returnType))
        allowNullReference -> Replacement.Return(null)
        else -> Replacement.Skip
    }
}
