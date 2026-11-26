package io.github.hyperprivacy.ui

import io.github.hyperprivacy.core.health.Health
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.status.ObservedEndpoint
import io.github.hyperprivacy.status.StatusStore
import io.github.hyperprivacy.status.diagnosticReport
import io.github.hyperprivacy.status.ruleHealth
import io.github.hyperprivacy.status.suspectedEndpoints

/** Rule health as the UI consumes it. Implementations may block; callers use a worker thread. */
internal interface RuleHealthSource {
    /** Health of [rule] per target package, given the user's real switch state. */
    fun health(rule: Rule, enabled: Boolean): Map<String, Health>

    /** Endpoints seen in diagnostic mode that look like reporting no rule covers. */
    fun suspected(): List<ObservedEndpoint>

    /** The shareable Markdown diagnostic report. */
    fun report(moduleVersion: String, isEnabled: (Rule) -> Boolean): String
}

/** Health backed by the status reports in [StatusStore]. */
internal class StoreHealthSource(private val store: StatusStore) : RuleHealthSource {
    override fun health(rule: Rule, enabled: Boolean): Map<String, Health> = store.ruleHealth(rule, enabled)

    override fun suspected(): List<ObservedEndpoint> = store.suspectedEndpoints()

    override fun report(moduleVersion: String, isEnabled: (Rule) -> Boolean): String =
        store.diagnosticReport(moduleVersion, isEnabled)
}
