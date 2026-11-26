package io.github.hyperprivacy.core.config

import io.github.hyperprivacy.core.rule.Catalog
import io.github.hyperprivacy.core.rule.Rule

/**
 * Flat key/value form of the user's rule switches, shared by the UI (writer) and hooks (reader).
 *
 * Each rule maps to `rule.<id>` → Boolean. Absent keys mean "use the rule default", so a fresh
 * install and newly shipped rules behave correctly without a migration.
 */
public object ConfigCodec {
    /** Remote preferences group holding the switches. */
    public const val PREFS_GROUP: String = "rules"

    private const val KEY_PREFIX = "rule."

    public fun key(ruleId: String): String = KEY_PREFIX + ruleId

    /** Inverse of [key]; `null` for keys not owned by this codec. */
    public fun ruleIdOf(key: String?): String? = key?.takeIf { it.startsWith(KEY_PREFIX) }?.removePrefix(KEY_PREFIX)

    public fun encode(enabled: Map<String, Boolean>): Map<String, Boolean> = enabled.mapKeys { (id, _) -> key(id) }

    /**
     * Resolves the effective state of every rule in [rules] from [raw] preferences.
     * Unknown keys and non-Boolean values are ignored.
     */
    public fun decode(raw: Map<String, *>, rules: List<Rule> = Catalog.rules): Map<String, Boolean> =
        rules.associate { rule -> rule.id to ((raw[key(rule.id)] as? Boolean) ?: rule.defaultOn) }

    /** Rules from [rules] that are effectively enabled under [raw]. */
    public fun enabledRules(raw: Map<String, *>, rules: List<Rule> = Catalog.rules): List<Rule> {
        val states = decode(raw, rules)
        return rules.filter { states.getValue(it.id) }
    }
}
