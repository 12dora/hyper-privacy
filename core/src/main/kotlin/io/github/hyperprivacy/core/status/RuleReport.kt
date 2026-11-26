package io.github.hyperprivacy.core.status

/** Installation outcome of a rule (or of one enforcement sink) in one process. */
public enum class HookState {
    /** The enforcement point was installed; the rule acts whenever it is enabled. */
    ACTIVE,

    /** The enforcement point could not be found or installed on this ROM. */
    MISSED,
    ;

    public companion object {
        /** Inverse of [name]; `null` for unknown text. */
        public fun parse(text: String?): HookState? = entries.firstOrNull { it.name == text }
    }
}

/**
 * One rule's status as seen by one reporter.
 *
 * @property packageName package the rule targets (system_server reports on behalf of others).
 * @property hits blocked calls since the previous report (a delta, not a running total).
 */
public data class RuleReport(val packageName: String, val ruleId: String, val state: HookState, val hits: Long)
