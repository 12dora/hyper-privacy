package io.github.hyperprivacy.core.health

/**
 * Whether a rule still matches its target app, from most to least urgent. Declaration order is
 * the aggregation order: across a rule's packages the first (most urgent) state wins.
 */
public enum class Health {
    /** The enforcement point was not found in a process that ran this boot: the interface changed. */
    BROKEN,

    /** The app version differs from the surveyed baseline; the rule may need re-checking. Informational. */
    NEEDS_REVIEW,

    /** Hooked and running, but nothing blocked for [RuleHealth.IDLE_DAYS] days: the channel may have moved. */
    IDLE,

    /** Hooked; nothing suggests the rule is stale. */
    OK,

    /** Installed, but no process has reported since boot. */
    NOT_RUN,

    /** The target app is not installed. */
    NOT_INSTALLED,
    ;

    public companion object {
        /** The most urgent of [states]; [NOT_INSTALLED] when there are none. */
        public fun worst(states: Collection<Health>): Health = states.minOrNull() ?: NOT_INSTALLED
    }
}
