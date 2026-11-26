package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.Reflection
import io.github.hyperprivacy.xposed.SinkContext
import io.github.hyperprivacy.xposed.Sinks
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * OkHttp 3/4/5 (only when its class names survived obfuscation). Hooks the single call-execution
 * entry `getResponseWithInterceptorChain` of `RealCall`, which both `execute()` and the async
 * path go through, and throws [IOException]: `execute()` rethrows it and `enqueue()` delivers it
 * to `Callback.onFailure`, exactly like a network error.
 *
 * Everything is discovered by reflection: the method by name prefix (OkHttp 4 mangles internal
 * names to `getResponseWithInterceptorChain$okhttp`), the request via `request()` or the
 * `originalRequest` field, and its URL via `url()`.
 */
internal class OkHttpSink(private val sinks: SinkContext) {
    /** Reads the request URL string of a call object. */
    private class UrlReader(private val request: Method?, private val requestField: Field?, private val url: Method) {
        fun read(call: Any): String? {
            val req = request?.invoke(call) ?: requestField?.get(call) ?: return null
            return url.invoke(req)?.toString()
        }
    }

    fun install(): Boolean {
        val hooked = CALL_CLASSES.sumOf { name ->
            val type = Reflection.loadClass(name, sinks.classLoader) ?: return@sumOf 0
            sinks.log.guard("OkHttp $name") { hook(type) } ?: 0
        }
        sinks.reportSink(Sinks.URL_OKHTTP, hooked > 0)
        return hooked > 0
    }

    private fun hook(type: Class<*>): Int {
        val reader = readerFor(type) ?: run {
            sinks.log.warn("OkHttp: cannot read the request URL of ${type.name}; sink skipped")
            return 0
        }
        val entries = type.declaredMethods.filter { it.isEntry() }
        entries.forEach { method ->
            sinks.xposed.hook(method)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept(FailOpenHooker(sinks.log, Sinks.URL_OKHTTP) { chain -> decide(chain, reader) })
        }
        sinks.log.info("OkHttp: hooked ${entries.size} entry method(s) of ${type.name}")
        return entries.size
    }

    private fun decide(chain: Chain, reader: UrlReader): Verdict {
        val call = chain.thisObject ?: return Verdict.Proceed
        sinks.checkUrl(reader.read(call)) ?: return Verdict.Proceed
        return Verdict.Throw(IOException(BLOCKED_MESSAGE))
    }

    private fun readerFor(type: Class<*>): UrlReader? {
        val request = Reflection.findNoArgMethod(type, REQUEST)
        val field = if (request == null) findField(type, ORIGINAL_REQUEST) else null
        val requestType = request?.returnType ?: field?.type ?: return null
        val url = Reflection.findNoArgMethod(requestType, URL) ?: return null
        return UrlReader(request, field, url)
    }

    private fun findField(type: Class<*>, name: String): Field? = generateSequence(type) { it.superclass }
        .firstNotNullOfOrNull { cls -> runCatching { cls.getDeclaredField(name) }.getOrNull() }
        ?.apply { isAccessible = true }

    private fun Method.isEntry(): Boolean = (name == ENTRY || name.startsWith("$ENTRY$")) &&
        parameterCount == 0 && !Modifier.isAbstract(modifiers) && !isBridge

    private companion object {
        val CALL_CLASSES = listOf(
            // OkHttp 4.x / 5.x
            "okhttp3.internal.connection.RealCall",
            // OkHttp 3.x
            "okhttp3.RealCall",
        )
        const val ENTRY = "getResponseWithInterceptorChain"
        const val REQUEST = "request"
        const val ORIGINAL_REQUEST = "originalRequest"
        const val URL = "url"
    }
}
