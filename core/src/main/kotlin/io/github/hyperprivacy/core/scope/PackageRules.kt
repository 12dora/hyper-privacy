package io.github.hyperprivacy.core.scope

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule
import io.github.hyperprivacy.core.rule.RuleKind

/**
 * Package → in-process rules index, so a hooked process does work proportional to its own rules
 * instead of scanning the whole catalog. FIREWALL rules are excluded: they are enforced from
 * system_server, never inside the target.
 */
public class PackageRules(rules: List<Rule>) {
    private val byPackage: Map<String, List<Rule>> = HashMap<String, MutableList<Rule>>().apply {
        rules.filter { it.kind != RuleKind.FIREWALL }.forEach { rule ->
            rule.packages.forEach { pkg -> getOrPut(pkg) { ArrayList() } += rule }
        }
    }

    /** Rules to enforce inside [packageName]'s processes, in catalog order; empty if none. */
    public fun inProcess(packageName: String): List<Rule> = byPackage[packageName].orEmpty()

    public companion object {
        /** Index over [Catalog.rules], built once per process on first use. */
        public val catalog: PackageRules by lazy { PackageRules(Catalog.rules) }
    }
}
