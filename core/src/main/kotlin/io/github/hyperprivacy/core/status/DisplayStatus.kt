package io.github.hyperprivacy.core.status

/** What the UI shows for a rule, aggregated over all its target packages. */
public enum class DisplayStatus {
    ACTIVE,
    MISSED,

    /** No target process has reported yet (not started since boot, or not in scope). */
    NOT_RUN,
    ;

    public companion object {
        /** Any ACTIVE wins: the rule works somewhere. MISSED only if every report missed. */
        public fun of(states: Collection<HookState>): DisplayStatus = when {
            HookState.ACTIVE in states -> ACTIVE
            states.isNotEmpty() -> MISSED
            else -> NOT_RUN
        }
    }
}
