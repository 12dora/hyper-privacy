package io.github.hyperprivacy.xposed.origin

import io.github.hyperprivacy.core.match.UrlParts
import io.github.hyperprivacy.core.net.PlausibleErrors
import io.github.hyperprivacy.xposed.FailOpenHooker
import io.github.hyperprivacy.xposed.GuardEnv
import io.github.hyperprivacy.xposed.NetErrors
import io.github.hyperprivacy.xposed.Verdict
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import java.io.IOException
import java.lang.reflect.Executable
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hook plumbing shared by the origin guards ([OriginNetGuard], [OriginWebGuard]): creation points
 * that decide once per outermost construction, execution sinks under [FailOpenHooker], and the
 * neutral failure of a blocked HTTP request. Nothing here ever throws into the app except a
 * sink's deliberate [Verdict.Throw].
 */
internal class OriginHooks(val env: GuardEnv, val origin: AdOrigin) {
    val log get() = env.log
    private val failures = AtomicInteger()

    /** The object a creation family is constructing on this thread (`null`: none). */
    private class Building {
        var target: Any? = null
    }

    /**
     * Creation points of one family (e.g. every `WebView` constructor): while an ad SDK is enabled,
     * [decide] runs before the original, once per object. The family shares one per-thread record
     * of the object under construction, so constructors delegating to each other (`this(...)`,
     * `super(...)` within the family) decide once, while constructing a *different* object of the
     * family inside a constructor still decides for that one. Returns the number of hooked executables.
     */
    fun creation(executables: Collection<Executable>, name: String, decide: (Chain) -> Unit): Int {
        val building = ThreadLocal.withInitial { Building() }
        executables.forEach { executable ->
            hook(executable) { chain ->
                if (!origin.active) return@hook chain.proceed()
                val target = chain.thisObject
                val state = building.get() ?: return@hook chain.proceed()
                if (target == null || state.target === target) return@hook chain.proceed()
                val outer = state.target
                state.target = target
                try {
                    safely(name) { decide(chain) }
                    chain.proceed()
                } finally {
                    state.target = outer
                }
            }
        }
        return executables.size
    }

    /**
     * An execution sink named [sink] (one of `Sinks.ORIGIN_*`): may fail the call with a
     * [Verdict.Throw], so the deliberate exception must pass ([ExceptionMode.PASSTHROUGH]);
     * [FailOpenHooker] keeps every other failure of [decide] from reaching the app.
     */
    fun sink(executable: Executable, sink: String, decide: (Chain) -> Verdict) {
        env.xposed.hook(executable)
            .setExceptionMode(ExceptionMode.PASSTHROUGH)
            .intercept(FailOpenHooker(log, sink, decide))
    }

    /** A sink that only proceeds, returns or rewrites arguments (never [Verdict.Throw]): fully protective. */
    fun rewriteSink(executable: Executable, sink: String, decide: (Chain) -> Verdict) {
        env.xposed.hook(executable)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(FailOpenHooker(log, sink, decide))
    }

    /** Hooks [executable] with [hooker], which must guard its own logic (see [safely]). */
    fun hook(executable: Executable, hooker: Hooker) {
        env.xposed.hook(executable).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker)
    }

    /** Tags the object under construction when an enabled ad SDK's code is creating it. */
    fun tagSdk(chain: Chain) {
        val target = chain.thisObject ?: return
        val rule = sdkRule() ?: return
        origin.tag(target, rule)
    }

    /** The creating SDK's rule id, `null` for the app's own or unattributed code. */
    fun sdkRule(): String? = origin.attribute()?.takeIf { it != AdOrigin.OWN }

    /** The host of [url], if it parses as a URL with one. */
    fun hostOf(url: String?): String? = UrlParts.parse(url)?.host

    /** What an HTTP client reports for a blocked request to [url] (a plausible network failure). */
    fun httpFailure(url: String?): IOException {
        val parts = UrlParts.parse(url) ?: return SocketTimeoutException(PlausibleErrors.TIMEOUT)
        val scheme = url?.substringBefore(':')
        return httpFailure(parts.host, scheme, -1)
    }

    fun httpFailure(host: String, scheme: String?, port: Int): IOException =
        NetErrors.httpFailure(host, false, null, PlausibleErrors.portOf(scheme, port))

    /** Runs [block]; any failure is logged (bounded) and yields `null`: attribution fails open. */
    inline fun <T> safely(name: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        logFailure(name, t)
        null
    }

    fun logFailure(name: String, error: Throwable) {
        if (failures.incrementAndGet() <= MAX_LOGGED_FAILURES) log.error("origin $name failed", error)
    }

    private companion object {
        const val MAX_LOGGED_FAILURES = 5
    }
}
