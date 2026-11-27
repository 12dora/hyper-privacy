package io.github.hyperprivacy.xposed.origin

import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.hyperprivacy.xposed.url.CronetEntries
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Executable
import java.net.URL
import java.util.WeakHashMap

/**
 * Cronet creation points of [AdOrigin]: request builders under their public names in the app's
 * class loader (`UrlRequestBuilderImpl`, and the concrete `UrlRequest.Builder` of old Cronet). The
 * URL of a request an enabled ad SDK builds is rewritten to an unroutable local endpoint, so it
 * fails through the stack's normal `onFailed` path, which apps already handle; a keep host is
 * never rewritten.
 *
 * Engines from other class loaders (Play Services Cronet, the platform `HttpEngine`) are found as
 * [io.github.hyperprivacy.xposed.url.CronetSink] finds them: the engine builders' `build()` is
 * observed and the request entries of each returned engine's class hierarchy are hooked once, on
 * first sight. An engine class of the app's own loader is skipped when its builders are already
 * hooked by name, so a request is never attributed twice. Native stacks are not seen here.
 *
 * Per-class state lives in a [ClassValue] and a weak set, so an engine class never keeps its
 * class loader alive; a class is hooked under a lock before any thread can see it as inspected.
 */
internal class OriginCronetGuard(private val hooks: OriginHooks) {
    private val origin get() = hooks.origin
    private val loader get() = hooks.env.classLoader

    /** Number of builder constructors hooked by name in the app's loader. */
    private var named = 0

    /** Engine classes whose entries are hooked (or skipped); weak keys, guarded by itself. */
    private val inspectedClasses = WeakHashMap<Class<*>, Boolean>()

    private val engines = object : ClassValue<Boolean>() {
        override fun computeValue(type: Class<*>): Boolean {
            inspect(type)
            return true
        }
    }

    /** Returns the number of hooked builder constructors and observed engine builds. */
    fun install(): Int {
        BUILDERS.mapNotNull { Reflection.loadClass(it, loader) }.forEach { type ->
            type.declaredConstructors.forEach { constructor ->
                val index = urlIndex(constructor)
                if (index >= 0) {
                    hooks.sink(constructor, Sinks.ORIGIN_URL) { chain -> rewrite(chain, index) }
                    named++
                }
            }
        }
        return named + observeBuilds()
    }

    /** Observes `build()` of every engine builder present, Cronet and platform alike. */
    private fun observeBuilds(): Int {
        val builders = (CronetEntries.CRONET_BUILDERS + CronetEntries.HTTP_ENGINE_BUILDERS)
            .mapNotNull { Reflection.loadClass(it, loader) }
        var count = 0
        builders.flatMap(CronetEntries::buildMethods).forEach { build ->
            hooks.hook(build, ExceptionMode.PROTECTIVE) { chain ->
                val engine = chain.proceed()
                if (engine != null) hooks.safely("cronet engine") { engines.get(engine.javaClass) }
                engine
            }
            count++
        }
        return count
    }

    /** Hooks the request entries of [type] and its superclasses, each class at most once. */
    private fun inspect(type: Class<*>) = synchronized(inspectedClasses) {
        var cls: Class<*>? = type
        while (cls != null && cls != Any::class.java && inspectedClasses.put(cls, true) == null) {
            val current = cls
            // The app's own Cronet: its request builders are hooked by name already.
            if (named == 0 || current.classLoader !== loader) {
                hooks.safely("cronet entries") { hookEntries(current) }
            }
            cls = current.superclass
        }
    }

    private fun hookEntries(cls: Class<*>) {
        CronetEntries.requestEntries(cls).forEach { executable ->
            val index = urlIndex(executable)
            if (index >= 0) {
                hooks.sink(executable, Sinks.ORIGIN_URL) { chain -> rewrite(chain, index) }
                hooks.log.info("origin: hooked ${executable.name} of ${cls.name}")
            }
        }
    }

    private fun rewrite(chain: Chain, index: Int): Verdict {
        if (!origin.active) return Verdict.Proceed
        val arg = chain.getArg(index)
        val url = (arg as? String ?: (arg as? URL)?.toString()) ?: return Verdict.Proceed
        if (url == CronetEntries.BLACK_HOLE || !UrlParts.isHttp(url) || AdOrigin.isKept(hooks.hostOf(url))) {
            return Verdict.Proceed
        }
        val rule = hooks.sdkRule() ?: return Verdict.Proceed
        origin.hit(rule, "cronet")
        return CronetEntries.blackHoled(chain, index)
    }

    private companion object {
        val BUILDERS = listOf(
            "org.chromium.net.impl.UrlRequestBuilderImpl",
            // Pre-2017 Cronet: UrlRequest.Builder was concrete with a (String url, …) constructor.
            "org.chromium.net.UrlRequest\$Builder",
        )

        /** Index of the first `String` / [URL] parameter (the request URL), or -1. */
        fun urlIndex(executable: Executable): Int =
            executable.parameterTypes.indexOfFirst { it == String::class.java || it == URL::class.java }
    }
}
