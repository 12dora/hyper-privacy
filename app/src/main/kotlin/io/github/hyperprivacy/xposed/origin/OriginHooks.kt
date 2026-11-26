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

    /**
     * A creation point: while an ad SDK is enabled, [decide] runs before the original, once per
     * outermost construction on this thread (constructors delegating to each other decide once).
     * Returns 1 (hooked).
     */
    fun creation(executable: Executable, name: String, decide: (Chain) -> Unit): Int {
        val depth = object : ThreadLocal<IntArray>() {
            override fun initialValue() = IntArray(1)
        }
        hook(executable, ExceptionMode.PROTECTIVE) { chain ->
            if (!origin.active) return@hook chain.proceed()
            val nesting = depth.get() ?: return@hook chain.proceed()
            if (nesting[0] == 0) safely(name) { decide(chain) }
            nesting[0]++
            try {
                chain.proceed()
            } finally {
                nesting[0]--
            }
        }
        return 1
    }

    /** An execution sink: may fail the call with a [Verdict.Throw]. */
    fun sink(executable: Executable, name: String, decide: (Chain) -> Verdict) {
        env.xposed.hook(executable)
            .setExceptionMode(ExceptionMode.PASSTHROUGH)
            .intercept(FailOpenHooker(log, "origin $name", decide))
    }

    fun hook(executable: Executable, mode: ExceptionMode, hooker: Hooker) {
        env.xposed.hook(executable).setExceptionMode(mode).intercept(hooker)
    }

    /** Tags the object under construction when an enabled ad SDK's code is creating it. */
    fun tagSdk(chain: Chain) {
        val target = chain.thisObject ?: return
        val rule = sdkRule() ?: return
        origin.tag(target, rule)
    }

    /** The creating SDK's rule id, `null` for the app's own or unattributed code. */
    fun sdkRule(): String? = origin.attribute()?.takeIf { it != AdOrigin.OWN }

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
