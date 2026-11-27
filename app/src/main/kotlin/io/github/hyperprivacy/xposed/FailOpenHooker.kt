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
     * rewritten by [StackScrub] so it reads as thrown by the hooked method itself, under [top] when
     * the sink has genuine platform frames for this failure.
     */
    class Throw(val error: Throwable, val top: TopFrames? = null) : Verdict

    /** Run the original with [args] substituted, e.g. a request URL rewritten to a black hole. */
    class ProceedWith(val args: Array<Any?>) : Verdict

    /**
     * Run the original once and return [transform] of its result. The original's exceptions
     * propagate unchanged; a failure of [transform] itself returns the original result (fail-open).
     */
    class Transform(val transform: (Any?) -> Any?) : Verdict
}

/**
 * Runs [decide] and acts on its [Verdict]. Any exception thrown by [decide] itself is treated as
 * [Verdict.Proceed] (fail-open: a module bug must never break the host). Exceptions from
 * `chain.proceed()` are the original method's and propagate unchanged.
 *
 * In diagnostics mode the cost of [decide] is recorded per sink ([SinkStats], keyed by [name]
 * unless [measuredAs] says otherwise). With it off, the only addition on this path is one
 * volatile boolean read.
 */
internal class FailOpenHooker(
    private val log: HookLog,
    private val name: String,
    private val decide: (Chain) -> Verdict,
) : Hooker {
    private val failures = AtomicInteger()

    /**
     * Resolved on the first measured call (diagnostics mode only); set before the hook is installed if
     * renamed. Volatile: binder threads race on the first call; read only while measuring.
     */
    @Volatile
    private var cell: SinkStats.Cell? = null

    /** Records this hook's cost under sink [key] instead of its [name], e.g. to group many hooks of one kind. */
    fun measuredAs(key: String): FailOpenHooker {
        cell = SinkStats.cell(key)
        return this
    }

    override fun intercept(chain: Chain): Any? {
        val verdict = try {
            if (SinkStats.enabled) measured(chain) else decide(chain)
        } catch (t: Throwable) {
            // Bounded so a persistent bug on a hot path cannot flood the log.
            if (failures.incrementAndGet() <= MAX_LOGGED_FAILURES) log.error("$name hook logic failed", t)
            Verdict.Proceed
        }
        return when (verdict) {
            Verdict.Proceed -> chain.proceed()
            is Verdict.Return -> verdict.value
            is Verdict.Throw -> throw StackScrub.scrub(verdict.error, chain.executable, verdict.top)
            is Verdict.ProceedWith -> chain.proceed(verdict.args)
            is Verdict.Transform -> transformed(chain.proceed(), verdict)
        }
    }

    private fun transformed(result: Any?, verdict: Verdict.Transform): Any? = try {
        verdict.transform(result)
    } catch (t: Throwable) {
        if (failures.incrementAndGet() <= MAX_LOGGED_FAILURES) log.error("$name result transform failed", t)
        result
    }

    private fun measured(chain: Chain): Verdict {
        val sink = cell ?: SinkStats.cell(name).also { cell = it }
        val start = System.nanoTime()
        try {
            return decide(chain)
        } finally {
            sink.record(System.nanoTime() - start)
        }
    }

    private companion object {
        const val MAX_LOGGED_FAILURES = 5
    }
}
