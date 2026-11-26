package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.SinkContext
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * OkHttp 3/4/5 (only when the `RealCall` class name survived obfuscation). Hooks the single
 * call-execution entry `getResponseWithInterceptorChain` of `RealCall`, which both `execute()` and
 * the async path go through, and throws [IOException]: `execute()` rethrows it and `enqueue()`
 * delivers it to `Callback.onFailure`, exactly like a network error.
 *
 * The entry is found by name (OkHttp 4 mangles internal names to
 * `getResponseWithInterceptorChain$okhttp`); when R8 renamed it, by shape: the declared no-arg
 * methods returning the call's `Response` type, minus `execute()` when another one remains (it
 * only forwards to the entry, so hooking both would check every request twice). The request URL
 * is read by [OkHttpUrlReader].
 */
internal class OkHttpSink(private val sinks: SinkContext) {
    fun install(): Boolean {
        val hooked = CALL_CLASSES.sumOf { name ->
            val type = Reflection.loadClass(name, sinks.classLoader) ?: return@sumOf 0
            sinks.log.guard("OkHttp $name") { hook(type) } ?: 0
        }
        sinks.reportSink(Sinks.URL_OKHTTP, hooked > 0)
        return hooked > 0
    }

    private fun hook(type: Class<*>): Int {
        val reader = OkHttpUrlReader.resolve(type) ?: return skip(type, "cannot read the request URL")
        val entries = entries(type, reader)
        if (entries.isEmpty()) return skip(type, "no call-execution entry")
        entries.forEach { method ->
            sinks.xposed.hook(method)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept(FailOpenHooker(sinks.log, Sinks.URL_OKHTTP) { chain -> decide(chain, reader) })
        }
        sinks.log.info("OkHttp: hooked ${entries.joinToString { it.name }} of ${type.name}")
        return entries.size
    }

    private fun entries(type: Class<*>, reader: OkHttpUrlReader): List<Method> {
        val candidates = type.declaredMethods.filter {
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                !it.isBridge && !it.isSynthetic
        }
        val named = candidates.filter { it.name == ENTRY || it.name.startsWith("$ENTRY$") }
        if (named.isNotEmpty()) return named
        val shapes = ShapeCache()
        val structural = candidates.filter { shapes.isResponse(it.returnType, reader.requestType) }
        return structural.filter { it.name != EXECUTE }.ifEmpty { structural }
    }

    /** Logs (once per class: install runs once) why [type] is skipped, with its member shapes. */
    private fun skip(type: Class<*>, why: String): Int {
        sinks.log.warn("OkHttp: $why in ${type.name}; sink skipped. ${OkHttpUrlReader.summarize(type)}")
        return 0
    }

    private fun decide(chain: Chain, reader: OkHttpUrlReader): Verdict {
        val call = chain.thisObject ?: return Verdict.Proceed
        if (!sinks.urlChecksActive) return Verdict.Proceed
        sinks.checkUrl(reader.read(call)) ?: return Verdict.Proceed
        return Verdict.Throw(IOException(BLOCKED_MESSAGE))
    }

    private companion object {
        val CALL_CLASSES = listOf(
            // OkHttp 4.x / 5.x
            "okhttp3.internal.connection.RealCall",
            // OkHttp 3.x
            "okhttp3.RealCall",
        )
        const val ENTRY = "getResponseWithInterceptorChain"
        const val EXECUTE = "execute"
    }
}
