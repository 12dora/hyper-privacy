package io.github.hyperprivacy.core

import io.github.hyperprivacy.core.method.DefaultReturn
import io.github.hyperprivacy.core.method.Replacement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DefaultReturnTest {
    @Test
    fun `void returns null`() {
        assertEquals(Replacement.Return(null), DefaultReturn.forType(Void.TYPE, allowNullReference = false))
    }

    @Test
    fun `primitives return zero values`() {
        val expected = mapOf<Class<*>, Any>(
            java.lang.Boolean.TYPE to false,
            java.lang.Byte.TYPE to 0.toByte(),
            java.lang.Short.TYPE to 0.toShort(),
            Character.TYPE to Char.MIN_VALUE,
            Integer.TYPE to 0,
            java.lang.Long.TYPE to 0L,
            java.lang.Float.TYPE to 0f,
            java.lang.Double.TYPE to 0.0,
        )
        expected.forEach { (type, value) ->
            assertEquals(Replacement.Return(value), DefaultReturn.forType(type, allowNullReference = false), type.name)
        }
    }

    @Test
    fun `reference types need explicit opt-in`() {
        assertEquals(Replacement.Skip, DefaultReturn.forType(String::class.java, allowNullReference = false))
        assertEquals(Replacement.Skip, DefaultReturn.forType(Boolean::class.javaObjectType, allowNullReference = false))
        assertEquals(Replacement.Return(null), DefaultReturn.forType(String::class.java, allowNullReference = true))
    }
}
