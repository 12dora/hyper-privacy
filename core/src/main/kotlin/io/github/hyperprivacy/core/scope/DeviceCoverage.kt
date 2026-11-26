package io.github.hyperprivacy.core.scope

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule

/**
 * What the catalog means on one device, given which of its target packages are installed. Pure, so
 * the UI's filtering and the scope check are unit-testable.
 */
public object DeviceCoverage {
    /**
     * Names LSPosed may use for system_server in a module scope. The module requests
     * [Catalog.SYSTEM_SCOPE]; older frameworks listed the framework as `android`.
     */
    private val SYSTEM_ALIASES: Set<String> = setOf(Catalog.SYSTEM_SCOPE, "android")

    /** The framework package, whose version stands in for [Catalog.SYSTEM_SCOPE] (system_server). */
    public const val FRAMEWORK_PACKAGE: String = "android"

    /**
     * [rule]'s target packages that are installed, in the rule's order. [Catalog.SYSTEM_SCOPE]
     * (system_server) is always present.
     */
    public fun installedTargets(rule: Rule, installed: Set<String>): List<String> =
        rule.packages.filter { isPresent(it, installed) }

    /** Rules with at least one installed target, in [rules] order; the rest cannot matter here. */
    public fun applicableRules(rules: List<Rule>, installed: Set<String>): List<Rule> =
        rules.filter { rule -> rule.packages.any { isPresent(it, installed) } }

    /**
     * The installed package whose version describes [target]: the framework for
     * [Catalog.SYSTEM_SCOPE], [target] itself otherwise.
     */
    public fun versionPackage(target: String): String =
        if (target == Catalog.SYSTEM_SCOPE) FRAMEWORK_PACKAGE else target

    private fun isPresent(target: String, installed: Set<String>): Boolean =
        target == Catalog.SYSTEM_SCOPE || target in installed

    /**
     * Scope entries the module needs but LSPosed has not granted: installed [hooked] packages
     * missing from [scope], preceded by [Catalog.SYSTEM_SCOPE] when system_server is missing
     * (it enforces FIREWALL rules and is always needed). LSPosed never adds packages installed after
     * the module was enabled on its own, so this is how those get noticed.
     */
    public fun missingScope(
        installed: Set<String>,
        scope: Collection<String>,
        hooked: Set<String> = Catalog.hookedPackages,
    ): List<String> {
        val granted = scope.toHashSet()
        val system = listOf(Catalog.SYSTEM_SCOPE).filter { granted.none(SYSTEM_ALIASES::contains) }
        val apps = hooked.filter { it in installed && it !in granted && it !in SYSTEM_ALIASES }.sorted()
        return system + apps
    }
}
