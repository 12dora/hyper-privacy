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

    /**
     * Global diagnostics ("observe") switch, Boolean, default [OBSERVE_DEFAULT]. When on, hooked
     * processes record the endpoints their network sinks see and ship them with status reports.
     * Stored in the same [PREFS_GROUP] so hooked processes get live updates.
     */
    public const val OBSERVE_KEY: String = "diag.observe"
    public const val OBSERVE_DEFAULT: Boolean = false

    private const val KEY_PREFIX = "rule."

    public fun key(ruleId: String): String = KEY_PREFIX + ruleId

    /** Effective observe switch under [raw] preferences. */
    public fun isObserveEnabled(raw: Map<String, *>): Boolean = raw[OBSERVE_KEY] as? Boolean ?: OBSERVE_DEFAULT

    /** Whether a change of preference [key] can affect hooks (`null` means "everything changed"). */
    public fun affectsHooks(key: String?): Boolean =
        key == null || key == OBSERVE_KEY || key == ModuleSecret.KEY || key == ProtectionPause.KEY ||
            ruleIdOf(key) != null

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
