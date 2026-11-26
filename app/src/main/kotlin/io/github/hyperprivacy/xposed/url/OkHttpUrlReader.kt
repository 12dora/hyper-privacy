package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.core.net.ClassShape
import io.github.hyperprivacy.core.net.OkHttpShapes
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** A resolved, cached accessor (no-arg method or field) of one class, as an `(Object)Object` handle. */
internal class Getter private constructor(val type: Class<*>, private val handle: MethodHandle) {
    fun get(target: Any): Any? = handle.invoke(target)

    companion object {
        private val lookup = MethodHandles.lookup()
        private val GENERIC = MethodType.methodType(Any::class.java, Any::class.java)

        fun of(method: Method) = Getter(method.returnType, lookup.unreflect(method.accessible()).asType(GENERIC))

        fun of(field: Field) = Getter(field.type, lookup.unreflectGetter(field.accessible()).asType(GENERIC))

        private fun <T : java.lang.reflect.AccessibleObject> T.accessible(): T = apply { isAccessible = true }
    }
}

/**
 * Reads the request URL of an OkHttp call object (`RealCall`) across OkHttp 3.x / 4.x / 5.x and
 * builds whose `Request` / `HttpUrl` classes and members R8 renamed. Resolution runs once per call
 * class; reads then go through cached [Getter]s and `HttpUrl.toString()` (the full URL).
 *
 * Call → request, first that works: `request()`, field `originalRequest`, then any no-arg method
 * or field whose type has the shape of a `Request` (see [OkHttpShapes]). Request → URL:
 * `url()`, field `url`, then any no-arg method or field whose type has the shape of an `HttpUrl`.
 */
internal class OkHttpUrlReader private constructor(private val request: Getter, private val url: Getter) {
    /** The `Request` class, for recognising the `Response` type of entry methods. */
    val requestType: Class<*> get() = request.type

    fun read(call: Any): String? {
        val req = request.get(call) ?: return null
        return url.get(req)?.toString()
    }

    companion object {
        private const val REQUEST = "request"
        private const val ORIGINAL_REQUEST = "originalRequest"
        private const val URL = "url"
        private const val MAX_SUMMARY_MEMBERS = 40

        /** Resolves a reader for [callType], or `null` when no request/URL path is recognisable. */
        fun resolve(callType: Class<*>): OkHttpUrlReader? {
            val shapes = ShapeCache()
            val named = listOfNotNull(
                callType.noArgMethods().firstOrNull { it.name == REQUEST }?.let(Getter::of),
                callType.instanceFields().firstOrNull { it.name == ORIGINAL_REQUEST }?.let(Getter::of),
            )
            val structural = accessors(callType) { shapes.isRequest(it) }
            return (named + structural).firstNotNullOfOrNull { request ->
                urlGetter(request.type, shapes)?.let { OkHttpUrlReader(request, it) }
            }
        }

        private fun urlGetter(requestType: Class<*>, shapes: ShapeCache): Getter? {
            val method = requestType.noArgMethods().firstOrNull { it.name == URL && !it.returnType.isPrimitive }
            val field = requestType.instanceFields().firstOrNull { it.name == URL && !it.type.isPrimitive }
            return method?.let(Getter::of)
                ?: field?.let(Getter::of)
                ?: accessors(requestType) { shapes.isHttpUrl(it) }.firstOrNull()
        }

        /** No-arg methods, then fields, of [type] whose value type satisfies [accept]. */
        private fun accessors(type: Class<*>, accept: (Class<*>) -> Boolean): List<Getter> =
            type.noArgMethods().filter { accept(it.returnType) }.map(Getter::of) +
                type.instanceFields().filter { accept(it.type) }.map(Getter::of)

        /** Declared member signatures of [type] (type names only, no values), for diagnosing misses. */
        fun summarize(type: Class<*>): String {
            val fields = runCatching { type.instanceFields().map { it.type.name } }.getOrDefault(emptyList())
            val methods = runCatching { type.noArgMethods().map { "${it.name}():${it.returnType.name}" } }
                .getOrDefault(emptyList())
            return "fields=${fields.take(MAX_SUMMARY_MEMBERS)} noArgMethods=${methods.take(MAX_SUMMARY_MEMBERS)}"
        }
    }
}

/** Shapes of the classes met during one resolution; a class that cannot be inspected matches nothing. */
internal class ShapeCache {
    private val shapes = HashMap<Class<*>, ClassShape?>()

    fun isHttpUrl(type: Class<*>): Boolean = shape(type)?.let(OkHttpShapes::isHttpUrl) == true

    fun isRequest(type: Class<*>): Boolean {
        val shape = shape(type) ?: return false
        return OkHttpShapes.isRequest(shape) { name -> shape.fieldNamed(name, type)?.let(::isHttpUrl) == true }
    }

    fun isResponse(type: Class<*>, requestType: Class<*>): Boolean =
        shape(type)?.let { OkHttpShapes.isResponse(it, requestType.name) } == true

    private fun shape(type: Class<*>): ClassShape? = shapes.getOrPut(type) {
        if (type.isPrimitive || type.isArray || type.name.startsWith(JAVA_PREFIX)) {
            null
        } else {
            runCatching { ClassShape(type.name, type.instanceFields().map { it.type.name }) }.getOrNull()
        }
    }

    /** The class of [type]'s field whose type is named [name]. */
    private fun ClassShape.fieldNamed(name: String, type: Class<*>): Class<*>? =
        if (name in fieldTypes) type.instanceFields().firstOrNull { it.type.name == name }?.type else null

    private companion object {
        const val JAVA_PREFIX = "java."
    }
}

/** Instance fields declared by this class and its superclasses (up to `Object`). */
internal fun Class<*>.instanceFields(): List<Field> = hierarchy().flatMap { cls ->
    cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
}.toList()

/** Concrete, non-static, no-arg methods declared by this class and its superclasses (up to `Object`). */
internal fun Class<*>.noArgMethods(): List<Method> = hierarchy().flatMap { cls ->
    cls.declaredMethods.filter {
        it.parameterCount == 0 && it.returnType != Void.TYPE && !it.isBridge && !it.isSynthetic &&
            !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers)
    }
}.toList()

private fun Class<*>.hierarchy(): Sequence<Class<*>> =
    generateSequence(this) { it.superclass }.takeWhile { it != Any::class.java }
