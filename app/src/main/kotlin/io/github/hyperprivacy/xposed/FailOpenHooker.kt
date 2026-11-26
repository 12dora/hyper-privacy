package io.github.hyperprivacy.xposed

import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.atomic.AtomicInteger

/** Decision taken by a hook's own logic for one intercepted call. */
internal sealed interface Verdict {
    data object Proceed : Verdict

    data class Return(val value: Any?) : Verdict

    /**
     * Deliberately surface [error] to the caller, e.g. a blocked DNS lookup. Its stack trace is
     * rewritten by [StackScrub] so it reads as thrown by the hooked method itself.
     */
    data class Throw(val error: Throwable) : Verdict

    /** Run the original with [args] substituted, e.g. a request URL rewritten to a black hole. */
    class ProceedWith(val args: Array<Any?>) : Verdict
}

/**
 * Runs [decide] and acts on its [Verdict]. Any exception thrown by [decide] itself is treated as
 * [Verdict.Proceed] (fail-open: a module bug must never break the host). Exceptions from
 * `chain.proceed()` are the original method's and propagate unchanged.
 */
internal class FailOpenHooker(
    private val log: HookLog,
    private val name: String,
    private val decide: (Chain) -> Verdict,
) : Hooker {
    private val failures = AtomicInteger()

    override fun intercept(chain: Chain): Any? {
        val verdict = try {
            decide(chain)
        } catch (t: Throwable) {
            // Bounded so a persistent bug on a hot path cannot flood the log.
            if (failures.incrementAndGet() <= MAX_LOGGED_FAILURES) log.error("$name hook logic failed", t)
            Verdict.Proceed
        }
        return when (verdict) {
            Verdict.Proceed -> chain.proceed()
            is Verdict.Return -> verdict.value
            is Verdict.Throw -> throw StackScrub.scrub(verdict.error, chain.executable)
            is Verdict.ProceedWith -> chain.proceed(verdict.args)
        }
    }

    private companion object {
        const val MAX_LOGGED_FAILURES = 5
    }
}
