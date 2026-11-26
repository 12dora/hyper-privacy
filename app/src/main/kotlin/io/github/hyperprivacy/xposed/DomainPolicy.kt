package io.github.hyperprivacy.xposed

import io.github.hyperprivacy.core.match.HostMatcher
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RulePayload
import java.util.concurrent.atomic.AtomicReference

/**
 * The process's current host block list, shared by every network sink. Rebuilt off the hot path
 * whenever switches change and published atomically, so lookups never lock.
 */
internal class DomainPolicy(
    private val rules: List<Rule>,
    private val switches: RuleSwitches,
    private val log: HookLog,
) {
    private val matcher = AtomicReference(build())

    init {
        switches.addListener { matcher.set(build()) }
    }

    /** Id of the enabled rule blocking [host], or `null` if the host is allowed. */
    fun blockingRule(host: String?): String? = matcher.get().find(host)

    private fun build(): HostMatcher<String> = log.guard("build host matcher") {
        HostMatcher.of(
            rules.filter { switches.isEnabled(it.id) }.flatMap { rule ->
                (rule.payload as? RulePayload.Domains)?.suffixes.orEmpty().map { it to rule.id }
            },
        )
    } ?: HostMatcher.empty()
}
