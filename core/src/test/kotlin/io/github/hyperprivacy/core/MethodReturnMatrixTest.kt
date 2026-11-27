package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.method.DefaultReturn
import io.github.hyperprivacy.core.method.Replacement
import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.MethodPattern
import io.github.hyperprivacy.core.rule.MethodShape
import io.github.hyperprivacy.core.rule.RulePayload
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The value a METHOD rule substitutes must be type-correct for the hooked method at runtime, for
 * every return type a target could declare, and the install-time checks must refuse the rest.
 */
class MethodReturnMatrixTest {
    private val primitives: Map<String, Class<*>> = listOf(
        java.lang.Boolean.TYPE,
        java.lang.Byte.TYPE,
        java.lang.Short.TYPE,
        Character.TYPE,
        Integer.TYPE,
        java.lang.Long.TYPE,
        java.lang.Float.TYPE,
        java.lang.Double.TYPE,
        Void.TYPE,
    ).associateBy { it.name }

    /** Every kind of declared return type: primitives, void, boxes, Unit, objects, arrays, interfaces. */
    private val matrix: List<Class<*>> = primitives.values + listOf(
        Void::class.java,
        Int::class.javaObjectType,
        Boolean::class.javaObjectType,
        Unit::class.java,
        String::class.java,
        Any::class.java,
        IntArray::class.java,
        Array<String>::class.java,
        List::class.java,
        Runnable::class.java,
    )

    private val methodRules = (Catalog.rules + Catalog.protectedAppRules).mapNotNull { rule ->
        (rule.payload as? RulePayload.Method)?.let { rule.id to it }
    }

    @Test
    fun `catalog has method rules`() {
        assertTrue(methodRules.isNotEmpty())
    }

    @Test
    fun `every method rule substitutes a valid value for every declarable return type`() {
        methodRules.forEach { (id, payload) ->
            matrix.forEach { type ->
                val replacement = DefaultReturn.forType(type, payload.allowNullReturn)
                if (replacement is Replacement.Return) {
                    assertTrue(DefaultReturn.isValidFor(type, replacement.value), "$id: ${type.name} -> $replacement")
                }
            }
        }
    }

    @Test
    fun `pinned return types get a valid value`() {
        methodRules.filter { (_, payload) -> payload.returns != null }.forEach { (id, payload) ->
            // An app class (not loadable here) stands in as a plain reference type.
            val type = primitives[payload.returns] ?: Any::class.java
            val replacement = DefaultReturn.forType(type, payload.allowNullReturn)
            assertTrue(replacement is Replacement.Return, "$id: ${payload.returns} would not be hooked")
            assertTrue(DefaultReturn.isValidFor(type, replacement.value), "$id: ${payload.returns}")
        }
    }

    @Test
    fun `null returns and obfuscated names are pinned to a full signature`() {
        methodRules.filter { (_, payload) -> payload.allowNullReturn || payload.method.isObfuscated }
            .forEach { (id, payload) ->
                assertTrue(payload.params != null && payload.returns != null, id)
                assertFalse(payload.returns in MethodShape.PRIMITIVE_NAMES, "$id returns null from a primitive")
            }
    }

    @Test
    fun `null only for references and void, primitives only as their exact box`() {
        assertTrue(DefaultReturn.isValidFor(Void.TYPE, null))
        assertFalse(DefaultReturn.isValidFor(Void.TYPE, 0))
        assertFalse(DefaultReturn.isValidFor(Integer.TYPE, null))
        assertFalse(DefaultReturn.isValidFor(Integer.TYPE, 0L))
        assertFalse(DefaultReturn.isValidFor(java.lang.Long.TYPE, 0))
        assertFalse(DefaultReturn.isValidFor(java.lang.Boolean.TYPE, 0))
        assertTrue(DefaultReturn.isValidFor(java.lang.Boolean.TYPE, false))
        assertTrue(DefaultReturn.isValidFor(String::class.java, null))
        assertFalse(DefaultReturn.isValidFor(String::class.java, 0))
        primitives.values.filter { it != Void.TYPE }.forEach { type ->
            val value = (DefaultReturn.forType(type, allowNullReference = false) as Replacement.Return).value
            assertTrue(DefaultReturn.isValidFor(type, value), type.name)
        }
    }

    @Test
    fun `kotlin Unit is never replaced by null`() {
        assertEquals(Replacement.Skip, DefaultReturn.forType(Unit::class.java, allowNullReference = true))
    }

    @Test
    fun `shape check rejects other signatures`() {
        val shape = MethodShape(listOf("android.content.Context", "java.lang.Object"), "q5")
        assertTrue(shape.accepts(listOf("android.content.Context", "java.lang.Object"), "q5"))
        assertFalse(shape.accepts(listOf("android.content.Context"), "q5"))
        assertFalse(shape.accepts(listOf("android.content.Context", "java.lang.Object"), "java.lang.String"))
        assertFalse(MethodShape(null, "int").accepts(emptyList(), "java.lang.Integer"))
        assertTrue(MethodShape(null, null).accepts(listOf("int"), "void"))
    }

    @Test
    fun `unpinned obfuscated or null-returning rules are refused`() {
        assertThrows<IllegalArgumentException> { RulePayload.Method("a.B", MethodPattern.parse("a")) }
        assertThrows<IllegalArgumentException> {
            RulePayload.Method("a.B", MethodPattern.parse("track"), allowNullReturn = true)
        }
        assertThrows<IllegalArgumentException> {
            RulePayload.Method("a.B", MethodPattern.parse("track"), true, listOf("int"), "int")
        }
        RulePayload.Method("a.B", MethodPattern.parse("a"), params = listOf("int"), returns = "void")
    }
}
