package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.SinkContext
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
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

    private fun hookDeclared(cls: Class<*>): Int {
        val methods = cls.declaredMethods.filter {
            it.name in REQUEST_METHODS && !Modifier.isAbstract(it.modifiers) && !it.isBridge
        }
        val isBuilder = cls.simpleName.endsWith(BUILDER_SUFFIX) || cls.simpleName.endsWith(BUILDER_IMPL_SUFFIX)
        val constructors = if (isBuilder) cls.declaredConstructors.toList() else emptyList()
        return (methods + constructors).count { executable ->
            val indices = urlArgumentIndices(executable)
            if (indices.isNotEmpty()) {
                val hooker = FailOpenHooker(sinks.log, Sinks.CRONET_REWRITE) { rewrite(it, indices) }
                sinks.xposed.hook(executable).intercept(hooker)
            }
            indices.isNotEmpty()
        }
    }

    /** Observes `build()` of an engine builder and inspects the class of each engine it returns. */
    private fun observeBuilds(family: Family, builder: Class<*>, hooks: AtomicInteger) {
        val builds = builder.declaredMethods.filter {
            it.name == BUILD && it.parameterCount == 0 && !it.returnType.isPrimitive &&
                !Modifier.isAbstract(it.modifiers) && !it.isBridge
        }
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
        val args = chain.args.toTypedArray()
        args[index] = if (args[index] is URL) URL(BLACK_HOLE) else BLACK_HOLE
        return Verdict.ProceedWith(args)
    }

    private fun isBlocked(arg: Any?): Boolean = when (arg) {
        BLACK_HOLE -> false
        is String -> sinks.checkUrl(arg) != null
        is URL -> sinks.checkUrl(arg.toString()) != null
        else -> false
    }

    private companion object {
        /** Unroutable local endpoint: the connection is refused immediately, no traffic leaves. */
        const val BLACK_HOLE = "https://127.0.0.1:1/"

        const val BUILD = "build"
        const val BUILDER_SUFFIX = "Builder"
        const val BUILDER_IMPL_SUFFIX = "BuilderImpl"

        /** Concrete request entry points of Cronet-derived engines (URL usually the first argument). */
        val REQUEST_METHODS = setOf("newUrlRequestBuilder", "createRequest", "openConnection")

        /** Indices of the `String` / [URL] parameters that may carry the request URL. */
        fun urlArgumentIndices(executable: Executable): IntArray = executable.parameterTypes.withIndex()
            .filter { (_, type) -> type == String::class.java || type == URL::class.java }
            .map { it.index }
            .toIntArray()

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
                engineBuilders = listOf(
                    "org.chromium.net.CronetEngine\$Builder",
                    "org.chromium.net.ExperimentalCronetEngine\$Builder",
                ),
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
                engineBuilders = listOf("android.net.http.HttpEngine\$Builder"),
            ),
        )
    }
}
