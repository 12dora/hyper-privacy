package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.SinkContext
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cronet-style stacks (Chromium Cronet, Baidu turbonet, the platform `HttpEngine`): DNS and TLS
 * run natively, so the Java URL is the only thing we can see.
 *
 * A matched request is not failed by throwing; its URL argument is rewritten to [BLACK_HOLE] so
 * the request fails through the stack's normal `onFailed` path, which apps already handle.
 *
 * Entry points are found two ways, both per [Family]:
 * - statically: known engine / builder classes by name, hooking every concrete request entry
 *   ([REQUEST_METHODS], plus builder constructors) that takes the URL as `String` or [URL];
 * - at runtime: the engine builders' `build()` is observed and the class of the engine it returns
 *   (possibly loaded from another class loader, e.g. Play Services Cronet) is inspected the same
 *   way, once per class.
 *
 * Every `String` / [URL] parameter is a candidate, but only an argument that parses as a URL
 * matching an enabled rule is ever rewritten, so unrelated string parameters are never altered.
 */
internal class CronetSink(private val sinks: SinkContext) {
    private class Family(val sink: String, val entryClasses: List<String>, val engineBuilders: List<String>)

    private val inspected: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    fun install(): Boolean = FAMILIES.map { family ->
        val hooks = AtomicInteger()
        family.entryClasses.forEach { name -> load(name)?.let { hookEntries(family, it, hooks) } }
        family.engineBuilders.forEach { name -> load(name)?.let { observeBuilds(family, it, hooks) } }
        val hooked = hooks.get() > 0
        sinks.reportSink(family.sink, hooked)
        hooked
    }.any { it }

    private fun load(name: String): Class<*>? = Reflection.loadClass(name, sinks.classLoader)

    /** Hooks request entries declared on [type] and its superclasses, each class at most once. */
    private fun hookEntries(family: Family, type: Class<*>, hooks: AtomicInteger) {
        generateSequence(type) { it.superclass }
            .takeWhile { it != Any::class.java && inspected.add(it) }
            .forEach { cls ->
                val count = sinks.log.guard("${family.sink} entries of ${cls.name}") { hookDeclared(cls) } ?: 0
                if (count > 0) {
                    hooks.addAndGet(count)
                    sinks.log.info("${family.sink}: hooked $count request entr(ies) of ${cls.name}")
                }
            }
    }

    private fun hookDeclared(cls: Class<*>): Int = CronetEntries.requestEntries(cls).count { executable ->
        val indices = CronetEntries.urlArgumentIndices(executable)
        if (indices.isNotEmpty()) {
            val hooker = FailOpenHooker(sinks.log, Sinks.CRONET_REWRITE) { rewrite(it, indices) }
            sinks.xposed.hook(executable).intercept(hooker)
        }
        indices.isNotEmpty()
    }

    /** Observes `build()` of an engine builder and inspects the class of each engine it returns. */
    private fun observeBuilds(family: Family, builder: Class<*>, hooks: AtomicInteger) {
        val builds = CronetEntries.buildMethods(builder)
        builds.forEach { method ->
            sinks.xposed.hook(method).intercept { chain ->
                val engine = chain.proceed()
                if (engine != null) {
                    sinks.log.guard("${family.sink} engine discovery") { hookEntries(family, engine.javaClass, hooks) }
                }
                engine
            }
        }
        if (builds.isNotEmpty()) {
            hooks.addAndGet(builds.size)
            sinks.log.info("${family.sink}: observing ${builds.size} build() of ${builder.name}")
        }
    }

    /** Rewrites the first URL-typed argument that matches a rule; usually the first one. */
    private fun rewrite(chain: Chain, indices: IntArray): Verdict {
        val index = indices.firstOrNull { isBlocked(chain.getArg(it)) } ?: return Verdict.Proceed
        return CronetEntries.blackHoled(chain, index)
    }

    private fun isBlocked(arg: Any?): Boolean = when (arg) {
        CronetEntries.BLACK_HOLE -> false
        is String -> sinks.checkUrl(arg) != null
        is URL -> sinks.checkUrl(arg.toString()) != null
        else -> false
    }

    private companion object {
        val FAMILIES = listOf(
            Family(
                sink = Sinks.URL_CRONET,
                entryClasses = listOf(
                    "org.chromium.net.impl.CronetEngineBase",
                    "org.chromium.net.impl.CronetUrlRequestContext",
                    "org.chromium.net.impl.JavaCronetEngine",
                    "org.chromium.net.impl.UrlRequestBuilderImpl",
                    // Pre-2017 Cronet: UrlRequest.Builder was concrete with a (String url, …) constructor.
                    "org.chromium.net.UrlRequest\$Builder",
                ),
                engineBuilders = CronetEntries.CRONET_BUILDERS,
            ),
            Family(
                sink = Sinks.URL_TURBONET,
                // Baidu's early-Cronet fork; names are best guesses, the build() observer covers the rest.
                entryClasses = listOf(
                    "com.baidu.turbonet.net.TurbonetEngine",
                    "com.baidu.turbonet.net.CronetUrlRequestContext",
                    "com.baidu.turbonet.net.UrlRequest\$Builder",
                    "com.baidu.turbonet.net.impl.CronetEngineBase",
                    "com.baidu.turbonet.net.impl.CronetUrlRequestContext",
                    "com.baidu.turbonet.net.impl.UrlRequestBuilderImpl",
                ),
                engineBuilders = listOf(
                    "com.baidu.turbonet.net.TurbonetEngine\$Builder",
                    "com.baidu.turbonet.net.ExperimentalTurbonetEngine\$Builder",
                ),
            ),
            Family(
                sink = Sinks.URL_HTTP_ENGINE,
                entryClasses = emptyList(),
                engineBuilders = CronetEntries.HTTP_ENGINE_BUILDERS,
            ),
        )
    }
}

/**
 * Cronet-style entry points shared by [CronetSink] and the origin guard
 * ([io.github.hyperprivacy.xposed.origin.OriginCronetGuard]): engine builders whose `build()` is
 * observed, the request entries of an engine or builder class, and the black-hole rewrite.
 */
internal object CronetEntries {
    /** Unroutable local endpoint: the connection is refused immediately, no traffic leaves. */
    const val BLACK_HOLE = "https://127.0.0.1:1/"

    private const val BUILD = "build"
    private const val BUILDER_SUFFIX = "Builder"
    private const val BUILDER_IMPL_SUFFIX = "BuilderImpl"

    /** Concrete request entry points of Cronet-derived engines (URL usually the first argument). */
    private val REQUEST_METHODS = setOf("newUrlRequestBuilder", "createRequest", "openConnection")

    /** Cronet engine builders (the API classes the app bundles; the engine may come from Play Services). */
    val CRONET_BUILDERS = listOf(
        "org.chromium.net.CronetEngine\$Builder",
        "org.chromium.net.ExperimentalCronetEngine\$Builder",
    )

    /** The platform engine builder (API 34+). */
    val HTTP_ENGINE_BUILDERS = listOf("android.net.http.HttpEngine\$Builder")

    /** The concrete no-arg `build()` methods of engine builder [builder]. */
    fun buildMethods(builder: Class<*>): List<Method> = builder.declaredMethods.filter {
        it.name == BUILD && it.parameterCount == 0 && !it.returnType.isPrimitive &&
            !Modifier.isAbstract(it.modifiers) && !it.isBridge
    }

    /** Request entries declared by [cls]: [REQUEST_METHODS], plus constructors of a builder class. */
    fun requestEntries(cls: Class<*>): List<Executable> {
        val methods = cls.declaredMethods.filter {
            it.name in REQUEST_METHODS && !Modifier.isAbstract(it.modifiers) && !it.isBridge
        }
        val isBuilder = cls.simpleName.endsWith(BUILDER_SUFFIX) || cls.simpleName.endsWith(BUILDER_IMPL_SUFFIX)
        return if (isBuilder) methods + cls.declaredConstructors else methods
    }

    /** Indices of the `String` / [URL] parameters that may carry the request URL. */
    fun urlArgumentIndices(executable: Executable): IntArray = executable.parameterTypes.withIndex()
        .filter { (_, type) -> type == String::class.java || type == URL::class.java }
        .map { it.index }
        .toIntArray()

    /** Proceeds with argument [index] replaced by [BLACK_HOLE] (as a [URL] when it is one). */
    fun blackHoled(chain: Chain, index: Int): Verdict {
        val args = chain.args.toTypedArray()
        args[index] = if (args[index] is URL) URL(BLACK_HOLE) else BLACK_HOLE
        return Verdict.ProceedWith(args)
    }
}
