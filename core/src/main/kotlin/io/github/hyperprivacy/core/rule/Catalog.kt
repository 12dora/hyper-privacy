package io.github.hyperprivacy.core.rule

import io.github.hyperprivacy.core.rule.catalog.adRules
import io.github.hyperprivacy.core.rule.catalog.analyticsRules
import io.github.hyperprivacy.core.rule.catalog.appListRules
import io.github.hyperprivacy.core.rule.catalog.deviceIdRules
import io.github.hyperprivacy.core.rule.catalog.diagnosticsRules
import io.github.hyperprivacy.core.rule.catalog.mediaDrmRule
import io.github.hyperprivacy.core.rule.catalog.personalDataRules

/**
 * The built-in rule set. Adding a rule is a data-only change: append an entry to the list of its
 * category in the `catalog` package (shared target-package sets live in `PackageSets`).
 *
 * Scope policy: only privacy reporting channels. A rule that affects a vendor service
 * (app store, security scan, push, cloud sync, find device, weather, updates …) declares an `impact`
 * and ships disabled, unless it blocks a high-risk leak (`highRisk`): then it ships enabled and the
 * side effect is still shown.
 *
 * Invariants (enforced by tests): ids are unique, every rule cites evidence, DOMAIN rules name
 * specific telemetry hosts rather than vendor apexes, and FIREWALL targets own a dedicated uid.
 */
public object Catalog {
    /** LSPosed's virtual scope name for system_server. */
    public const val SYSTEM_SCOPE: String = "system"

    /**
     * Rules in a stable order: grouped by category, then in the order declared in each category file.
     * App-wide rules (`AppWideRules`) close their category and target every package the other
     * in-process rules already inject into, so they never widen the scope.
     */
    public val rules: List<Rule> = buildRules()

    private val byId: Map<String, Rule> = rules.associateBy { it.id }

    /** Packages whose own processes need the module injected (DOMAIN and METHOD rules). */
    public val hookedPackages: Set<String> = rules
        .filter { it.kind != RuleKind.FIREWALL }
        .flatMapTo(sortedSetOf()) { it.packages }

    /** Every package referenced by any rule. */
    public val allPackages: Set<String> = rules.flatMapTo(sortedSetOf()) { it.packages }

    /**
     * The LSPosed scope: hooked packages plus system_server, which enforces FIREWALL rules.
     * FIREWALL targets are deliberately absent; injecting into them would add risk for nothing.
     */
    public val scope: Set<String> = sortedSetOf(SYSTEM_SCOPE).apply { addAll(hookedPackages) }

    public fun byId(id: String): Rule? = byId[id]

    private fun buildRules(): List<Rule> {
        val targeted = analyticsRules + adRules + diagnosticsRules + deviceIdRules + appListRules + personalDataRules
        val injected = targeted.filter { it.kind != RuleKind.FIREWALL }.flatMapTo(sortedSetOf()) { it.packages }
        return analyticsRules + adRules + diagnosticsRules + deviceIdRules + mediaDrmRule(injected) +
            appListRules + personalDataRules
    }

    /** Rules applying to [packageName], in catalog order. */
    public fun rulesFor(packageName: String): List<Rule> = rules.filter { packageName in it.packages }
}
