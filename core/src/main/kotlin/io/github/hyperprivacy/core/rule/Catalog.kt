package io.github.hyperprivacy.core.rule

import io.github.hyperprivacy.core.rule.catalog.HTTPDNS_EXCLUDED_APPS
import io.github.hyperprivacy.core.rule.catalog.adRules
import io.github.hyperprivacy.core.rule.catalog.analyticsRules
import io.github.hyperprivacy.core.rule.catalog.appListRules
import io.github.hyperprivacy.core.rule.catalog.deviceIdRules
import io.github.hyperprivacy.core.rule.catalog.diagnosticsRules
import io.github.hyperprivacy.core.rule.catalog.httpDnsRule
import io.github.hyperprivacy.core.rule.catalog.mediaDrmRule
import io.github.hyperprivacy.core.rule.catalog.personalDataRules
import io.github.hyperprivacy.core.rule.catalog.protectedHttpDnsRule

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
     * Virtual target of [protectedAppRules]: every user-installed third-party app the user chose to
     * protect. Like [SYSTEM_SCOPE] it has no dot, so it can never collide with a package name.
     */
    public const val PROTECTED_APPS: String = "protected"

    /**
     * Rules in a stable order: grouped by category, then in the order declared in each category file.
     * App-wide rules (`AppWideRules`) close their category and target packages the other in-process
     * rules already inject into, so they never widen the scope. Every target is a vendor package.
     */
    public val rules: List<Rule> = buildRules()

    /**
     * Rules for protected third-party apps, all targeting [PROTECTED_APPS] and nothing else. They are
     * kept out of [rules] (and so out of [scope], [allPackages] and the vendor rule list): the set of
     * protected apps is the user's choice and is resolved at runtime, and none of these rules may
     * ever apply to a vendor package. Ids are disjoint from [rules] (enforced by tests); switches use
     * the same `rule.<id>` keys and defaults ([io.github.hyperprivacy.core.config.ConfigCodec]).
     *
     * Whoever selects the protected apps must leave out vendor-trusted packages
     * ([io.github.hyperprivacy.core.system.VendorTrust]), not only system apps: HyperOS ships many
     * vendor apps as updatable data apps.
     *
     * Inside one protected app each rule is bound to that package
     * ([io.github.hyperprivacy.core.tracker.TrackerRules.appRules]); the app's own switch for it
     * overrides the global `rule.<id>` value.
     */
    public val protectedAppRules: List<Rule> = listOf(mediaDrmRule(), protectedHttpDnsRule())

    /** Ids of [protectedAppRules]. */
    public val protectedAppRuleIds: Set<String> = protectedAppRules.mapTo(LinkedHashSet()) { it.id }

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
        // App processes only: system_server rules (target SYSTEM_SCOPE) must not pull app-wide hooks into it.
        val injected = targeted.filter { it.kind != RuleKind.FIREWALL }
            .flatMapTo(sortedSetOf()) { it.packages }
            .apply { remove(SYSTEM_SCOPE) }
        return analyticsRules + adRules + diagnosticsRules + deviceIdRules + appListRules + personalDataRules +
            httpDnsRule(injected - HTTPDNS_EXCLUDED_APPS)
    }

    /** Rules applying to [packageName], in catalog order. */
    public fun rulesFor(packageName: String): List<Rule> = rules.filter { packageName in it.packages }
}
