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
import java.util.WeakHashMap

/**
 * OkHttp 3/4/5. Hooks the single
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
 * expect a checked exception there, so it is not hooked, unless the class is OkHttp's own call
 * ([OkHttpCalls.isOkHttpCall]: R8 drops `throws` clauses, while OkHttp's callers handle
 * `IOException` from the entry whatever the bytecode declares). The request URL
 * is read by [OkHttpUrlReader]. When the URL host is an IP literal, an explicit `Host` header (manual
 * HTTPDNS) is checked as the host too.
 *
 * When R8 renamed the call class (or no entry of the named one could be hooked), the class is
 * found at runtime as the origin guard finds it: `OkHttpClient.newCall` is observed and the class
 * of the call it returns is inspected once, on first sight ([OkHttpCalls.CallClasses]). That costs
 * one class-value lookup per call creation and allocates nothing after the first call.
 */
internal class OkHttpSink(private val sinks: SinkContext) {
    /**
     * Set by [install]: OkHttp is in this app under its public names but no entry could be hooked,
     * so requests through it are not checked.
     */
    var presentButMissed: Boolean = false
        private set

    /** Call classes met through `newCall`: entries hooked on first sight, the count kept per class. */
    private val renamed = OkHttpCalls.CallClasses { type, first ->
        if (first) sinks.log.guard("OkHttp ${type.name}") { hook(type) } ?: 0 else 0
    }

    fun install(): Boolean {
        // Through the same per-class state, so a call of a named class met via newCall is not inspected again.
        val byName = OkHttpCalls.callClasses(sinks.classLoader).sumOf { renamed.get(it) }
        val hooked = byName + if (byName == 0) observeNewCall() else 0
        sinks.reportSink(Sinks.URL_OKHTTP, hooked > 0)
        presentButMissed = hooked == 0 && OkHttpCalls.isPresent(sinks.classLoader)
        return hooked > 0
    }

    /** Observes `OkHttpClient.newCall` and inspects each new call class; returns the hooked methods. */
    private fun observeNewCall(): Int {
        val newCall = OkHttpCalls.newCallMethods(sinks.classLoader)
        newCall.forEach { method ->
            sinks.xposed.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val call = chain.proceed()
                    if (call != null) sinks.log.guard("OkHttp call class") { renamed.get(call.javaClass) }
                    call
                }
        }
        if (newCall.isNotEmpty()) sinks.log.info("OkHttp: observing ${newCall.size} newCall() for renamed call classes")
        return newCall.size
    }

    private fun hook(type: Class<*>): Int {
        val reader = OkHttpUrlReader.resolve(type) ?: return skip(type, "cannot read the request URL")
        val entries = OkHttpCalls.entries(type, reader)
        if (entries.isEmpty()) return skip(type, "no call-execution entry")
        entries.forEach { method ->
            sinks.xposed.hook(method)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept(FailOpenHooker(sinks.log, Sinks.URL_OKHTTP) { chain -> decide(chain, reader) })
        }
        sinks.log.info("OkHttp: hooked ${entries.joinToString { it.name }} of ${type.name}")
        return entries.size
    }

    /** Logs (once per class: install runs once) why [type] is skipped, with its member shapes. */
    private fun skip(type: Class<*>, why: String): Int {
        sinks.log.warn("OkHttp: $why in ${type.name}; sink skipped. ${OkHttpUrlReader.summarize(type)}")
        return 0
    }

    private fun decide(chain: Chain, reader: OkHttpUrlReader): Verdict {
        val call = chain.thisObject ?: return Verdict.Proceed
        if (!sinks.urlChecksActive) return Verdict.Proceed
        val url = reader.read(call)
        val parts = UrlParts.parse(url, keepQuery = sinks.matchesQueries) ?: return Verdict.Proceed
        if (sinks.checkParsed(parts) != null) return Verdict.Throw(sinks.urlFailure(url))
        // HTTPDNS by hand: IP in the URL, the real name in an explicit Host header.
        if (!IpLiterals.isIpLiteral(parts.host)) return Verdict.Proceed
        val host = UrlParts.hostOfHeader(reader.readHostHeader(call)) ?: return Verdict.Proceed
        if (IpLiterals.isIpLiteral(host)) return Verdict.Proceed
        sinks.checkParsed(parts.copy(host = host)) ?: return Verdict.Proceed
        return Verdict.Throw(sinks.urlFailure(url))
    }
}

/** Where OkHttp calls are found and executed; shared by [OkHttpSink] and the origin guard. */
internal object OkHttpCalls {
    private val CALL_CLASSES = listOf(
        // OkHttp 4.x / 5.x
        "okhttp3.internal.connection.RealCall",
        // OkHttp 3.x
        "okhttp3.RealCall",
    )

    private const val CLIENT_CLASS = "okhttp3.OkHttpClient"
    private const val CALL_INTERFACE = "okhttp3.Call"

    /** Public OkHttp names that usually survive R8 (consumer rules, reflection by other libraries). */
    private val MARKER_CLASSES = listOf(CLIENT_CLASS, CALL_INTERFACE)
    private const val ENTRY = "getResponseWithInterceptorChain"
    private const val EXECUTE = "execute"
    private const val NEW_CALL = "newCall"

    /**
     * Per-call-class state computed once per class: [compute] runs under a lock with `first` true
     * for the first computation of a class only (a [ClassValue] may compute twice under a race), so
     * entries are hooked once and before any thread can see the class as done. Weak keys and a
     * [ClassValue], so a plugin's call class never keeps its class loader alive.
     */
    class CallClasses<T>(private val compute: (type: Class<*>, first: Boolean) -> T) {
        private val seen = WeakHashMap<Class<*>, Boolean>()

        private val values = object : ClassValue<T>() {
            override fun computeValue(type: Class<*>): T = synchronized(seen) {
                compute(type, seen.put(type, true) == null)
            }
        }

        fun get(type: Class<*>): T = values.get(type)
    }

    /** `OkHttpClient.newCall(Request)` in [loader], when the client kept its public name. */
    fun newCallMethods(loader: ClassLoader): List<Method> {
        val client = Reflection.loadClass(CLIENT_CLASS, loader) ?: return emptyList()
        return client.declaredMethods.filter { it.name == NEW_CALL && it.parameterCount == 1 && !it.isBridge }
    }

    /** Whether [type] is OkHttp's own call class: a known name, or an implementation of `okhttp3.Call`. */
    fun isOkHttpCall(type: Class<*>): Boolean = type.name in CALL_CLASSES ||
        generateSequence(type) { it.superclass }.any { cls -> cls.interfaces.any { it.name == CALL_INTERFACE } }

    /** The call classes present in [loader] under their public names. */
    fun callClasses(loader: ClassLoader): List<Class<*>> = CALL_CLASSES.mapNotNull { Reflection.loadClass(it, loader) }

    /** Whether OkHttp is in [loader] under its public names at all. */
    fun isPresent(loader: ClassLoader): Boolean =
        (CALL_CLASSES + MARKER_CLASSES).any { Reflection.loadClass(it, loader) != null }

    /**
     * The call-execution entries of call class [type] (see [OkHttpSink]): by name, else by shape,
     * which needs the [reader]'s `Request` type (without one only named entries are found).
     */
    fun entries(type: Class<*>, reader: OkHttpUrlReader?): List<Method> {
        val candidates = type.declaredMethods.filter {
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                !it.isBridge && !it.isSynthetic
        }
        val named = candidates.filter { it.name == ENTRY || it.name.startsWith("$ENTRY$") }
        if (named.isNotEmpty() || reader == null) return named
        val shapes = ShapeCache()
        val shaped = candidates.filter { shapes.isResponse(it.returnType, reader.requestType) }
        val structural = shaped.filter { it.throwsIo() }.ifEmpty { if (isOkHttpCall(type)) shaped else emptyList() }
        return structural.filter { it.name != EXECUTE }.ifEmpty { structural }
    }

    private fun Method.throwsIo(): Boolean = exceptionTypes.any { it.isAssignableFrom(IOException::class.java) }
}
