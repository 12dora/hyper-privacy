package io.github.hyperprivacy.core.health

import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind
import io.github.hyperprivacy.core.status.HookState
import java.util.concurrent.TimeUnit

/**
 * Everything known about one rule in one target package. All timestamps are wall-clock millis.
 *
 * @property ruleState latest reported install state of the rule; ignored unless [ruleReportedAt]
 *   is from the current boot.
 * @property sinkStates install state of each network sink in this package, **current boot only**
 *   (the caller filters out older reports).
 * @property lastProcessSeenAt latest evidence that a process of this package was alive (process
 *   start or a report from it).
 * @property activeSince when the rule was first reported ACTIVE in this package (reset after a
 *   MISSED report); the start of the idle clock when nothing was ever blocked.
 * @property lastHitAt when the rule last blocked something in this package.
 * @property enabled whether the user has the rule switched on; a disabled rule cannot be idle.
 */
public data class HealthSignals(
    val installed: Boolean,
    val currentVersionCode: Long? = null,
    val baselineVersionCode: Long? = null,
    val ruleState: HookState? = null,
    val ruleReportedAt: Long? = null,
    val sinkStates: Map<String, HookState> = emptyMap(),
    val lastProcessSeenAt: Long? = null,
    val activeSince: Long? = null,
    val lastHitAt: Long? = null,
    val enabled: Boolean = true,
)

/** Evaluation time ([now]) and the wall-clock time of the current boot ([bootAt]). */
public data class HealthClock(val now: Long, val bootAt: Long)

/**
 * Decides whether a rule may be stale in one package. Pure; see [evaluate] for the precedence.
 *
 * Sink names follow the status contract: `dns` and `tls` are host sinks, `url.*` are HTTP-client
 * sinks.
 */
public object RuleHealth {
    /** Days without a block, while the app keeps running, after which a rule counts as [Health.IDLE]. */
    public const val IDLE_DAYS: Long = 7

    /** [IDLE_DAYS] in millis. */
    public val IDLE_WINDOW_MS: Long = TimeUnit.DAYS.toMillis(IDLE_DAYS)

    private const val SINK_DNS = "dns"
    private const val SINK_TLS = "tls"
    private const val URL_SINK_PREFIX = "url."

    /** Kinds whose silence is meaningful: FIREWALL never reports hits, IDENTIFIER depends on third-party callers. */
    private val IDLE_KINDS = setOf(RuleKind.DOMAIN, RuleKind.URL, RuleKind.METHOD)

    public fun evaluate(rule: Rule, signals: HealthSignals, clock: HealthClock): Health =
        evaluate(rule.kind, signals, clock)

    /**
     * First match wins:
     * 1. [Health.NOT_INSTALLED];
     * 2. [Health.BROKEN]: the rule reported MISSED this boot, or (DOMAIN/URL) every relevant sink
     *    reported this boot is MISSED;
     * 3. [Health.NEEDS_REVIEW]: both the installed and the baseline version are known and differ;
     * 4. [Health.NOT_RUN]: nothing from this package since boot;
     * 5. [Health.IDLE]: see [isIdle];
     * 6. [Health.OK].
     */
    public fun evaluate(kind: RuleKind, signals: HealthSignals, clock: HealthClock): Health {
        val ruleState = signals.ruleState.takeIf { isThisBoot(signals.ruleReportedAt, clock) }
        return when {
            !signals.installed -> Health.NOT_INSTALLED
            ruleState == HookState.MISSED || allSinksMissed(kind, signals.sinkStates) -> Health.BROKEN
            versionChanged(signals) -> Health.NEEDS_REVIEW
            ruleState == null && !isThisBoot(signals.lastProcessSeenAt, clock) -> Health.NOT_RUN
            ruleState == HookState.ACTIVE && isIdle(kind, signals, clock) -> Health.IDLE
            else -> Health.OK
        }
    }

    /** Evaluates every package of [rule]; [signals] returns the inputs for one package. */
    public fun evaluateAll(rule: Rule, clock: HealthClock, signals: (String) -> HealthSignals): Map<String, Health> =
        rule.packages.sorted().associateWith { evaluate(rule, signals(it), clock) }

    /**
     * An enabled, ACTIVE rule of a kind in [IDLE_KINDS] whose package was seen within the idle
     * window, and which has not blocked anything for at least the window since it last blocked
     * (or, if it never did, since it became active). A clock that went backwards never yields idle.
     */
    private fun isIdle(kind: RuleKind, signals: HealthSignals, clock: HealthClock): Boolean {
        if (kind !in IDLE_KINDS || !signals.enabled) return false
        val seenAt = signals.lastProcessSeenAt ?: signals.ruleReportedAt ?: return false
        if (clock.now - seenAt > IDLE_WINDOW_MS) return false
        val quietSince = listOfNotNull(signals.lastHitAt, signals.activeSince).maxOrNull() ?: return false
        return clock.now - quietSince >= IDLE_WINDOW_MS
    }

    private fun isThisBoot(at: Long?, clock: HealthClock): Boolean = at != null && at >= clock.bootAt

    private fun versionChanged(signals: HealthSignals): Boolean {
        val current = signals.currentVersionCode ?: return false
        val baseline = signals.baselineVersionCode ?: return false
        return current != baseline
    }

    private fun allSinksMissed(kind: RuleKind, sinks: Map<String, HookState>): Boolean {
        val relevant = sinks.filterKeys { isRelevantSink(kind, it) }.values
        return relevant.isNotEmpty() && relevant.all { it == HookState.MISSED }
    }

    private fun isRelevantSink(kind: RuleKind, sink: String): Boolean = when (kind) {
        RuleKind.DOMAIN -> sink == SINK_DNS || sink == SINK_TLS || sink.startsWith(URL_SINK_PREFIX)
        RuleKind.URL -> sink.startsWith(URL_SINK_PREFIX)
        else -> false
    }
}
