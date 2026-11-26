package io.github.hyperprivacy.xposed.url

import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.net.IpLiterals
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
 * the async path go through, and throws an `IOException` subtype worded like a network failure
 * ([SinkContext.urlFailure]): `execute()` rethrows it and `enqueue()` delivers it to
 * `Callback.onFailure`, exactly like a network error.
 *
 * The entry is found by name (OkHttp 4 mangles internal names to
 * `getResponseWithInterceptorChain$okhttp`); when R8 renamed it, by shape: the declared no-arg
 * methods returning the call's `Response` type that declare `IOException` (or a supertype), minus
 * `execute()` when another one remains (it only forwards to the entry, so hooking both would check
 * every request twice). A method that does not declare it may belong to a fork whose callers never
 * expect a checked exception there, so it is never hooked. The request URL
 * is read by [OkHttpUrlReader]. When the URL host is an IP literal, an explicit `Host` header (manual
 * HTTPDNS) is checked as the host too.
 */
internal class OkHttpSink(private val sinks: SinkContext) {
    /**
     * Set by [install]: OkHttp is in this app under its public names but no entry could be hooked,
     * so requests through it are not checked.
     */
    var presentButMissed: Boolean = false
        private set

    fun install(): Boolean {
        val hooked = CALL_CLASSES.sumOf { name ->
            val type = Reflection.loadClass(name, sinks.classLoader) ?: return@sumOf 0
            sinks.log.guard("OkHttp $name") { hook(type) } ?: 0
        }
        sinks.reportSink(Sinks.URL_OKHTTP, hooked > 0)
        presentButMissed = hooked == 0 && (CALL_CLASSES + MARKER_CLASSES).any {
            Reflection.loadClass(it, sinks.classLoader) != null
        }
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
        val structural = candidates.filter { shapes.isResponse(it.returnType, reader.requestType) && it.throwsIo() }
        return structural.filter { it.name != EXECUTE }.ifEmpty { structural }
    }

    private fun Method.throwsIo(): Boolean = exceptionTypes.any { it.isAssignableFrom(IOException::class.java) }

    /** Logs (once per class: install runs once) why [type] is skipped, with its member shapes. */
    private fun skip(type: Class<*>, why: String): Int {
        sinks.log.warn("OkHttp: $why in ${type.name}; sink skipped. ${OkHttpUrlReader.summarize(type)}")
        return 0
    }

    private fun decide(chain: Chain, reader: OkHttpUrlReader): Verdict {
        val call = chain.thisObject ?: return Verdict.Proceed
        if (!sinks.urlChecksActive) return Verdict.Proceed
        val url = reader.read(call)
        val parts = UrlParts.parse(url) ?: return Verdict.Proceed
        if (sinks.checkParsed(parts) != null) return Verdict.Throw(sinks.urlFailure(url))
        // HTTPDNS by hand: IP in the URL, the real name in an explicit Host header.
        if (!IpLiterals.isIpLiteral(parts.host)) return Verdict.Proceed
        val host = UrlParts.hostOfHeader(reader.readHostHeader(call)) ?: return Verdict.Proceed
        if (IpLiterals.isIpLiteral(host)) return Verdict.Proceed
        sinks.checkParsed(UrlParts(host, parts.path)) ?: return Verdict.Proceed
        return Verdict.Throw(sinks.urlFailure(url))
    }

    private companion object {
        val CALL_CLASSES = listOf(
            // OkHttp 4.x / 5.x
            "okhttp3.internal.connection.RealCall",
            // OkHttp 3.x
            "okhttp3.RealCall",
        )

        /** Public OkHttp names that usually survive R8 (consumer rules, reflection by other libraries). */
        val MARKER_CLASSES = listOf("okhttp3.OkHttpClient", "okhttp3.Call")
        const val ENTRY = "getResponseWithInterceptorChain"
        const val EXECUTE = "execute"
    }
}
