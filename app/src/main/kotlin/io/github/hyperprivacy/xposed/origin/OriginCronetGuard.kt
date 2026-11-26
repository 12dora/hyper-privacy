package io.github.hyperprivacy.xposed.origin

import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.net.URL

/**
 * Cronet creation points of [AdOrigin]: request builders under their public names in the app's
 * class loader (`UrlRequestBuilderImpl`, and the concrete `UrlRequest.Builder` of old Cronet). The
 * URL of a request an enabled ad SDK builds is rewritten to an unroutable local endpoint, so it
 * fails through the stack's normal `onFailed` path, which apps already handle; a keep host is
 * never rewritten. Engines in other class loaders (Play Services Cronet, the platform
 * `HttpEngine`) and native stacks are not seen here.
 */
internal class OriginCronetGuard(private val hooks: OriginHooks) {
    private val origin get() = hooks.origin

    /** Returns the number of hooked builder constructors. */
    fun install(): Int {
        var count = 0
        BUILDERS.mapNotNull { Reflection.loadClass(it, hooks.env.classLoader) }.forEach { type ->
            type.declaredConstructors.forEach { constructor ->
                val index = urlIndex(constructor)
                if (index >= 0) {
                    hooks.sink(constructor, Sinks.ORIGIN_URL) { chain -> rewrite(chain, index) }
                    count++
                }
            }
        }
        return count
    }

    private fun rewrite(chain: Chain, index: Int): Verdict {
        if (!origin.active) return Verdict.Proceed
        val arg = chain.getArg(index)
        val url = (arg as? String ?: (arg as? URL)?.toString()) ?: return Verdict.Proceed
        if (url == BLACK_HOLE || !UrlParts.isHttp(url) || AdOrigin.isKept(hooks.hostOf(url))) return Verdict.Proceed
        val rule = hooks.sdkRule() ?: return Verdict.Proceed
        origin.hit(rule, "cronet")
        val args = chain.args.toTypedArray()
        args[index] = if (arg is URL) URL(BLACK_HOLE) else BLACK_HOLE
        return Verdict.ProceedWith(args)
    }

    private companion object {
        /** Unroutable local endpoint (as `CronetSink`): the connection is refused, no traffic leaves. */
        const val BLACK_HOLE = "https://127.0.0.1:1/"

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
