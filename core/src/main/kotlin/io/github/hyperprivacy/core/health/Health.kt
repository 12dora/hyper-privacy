package io.github.hyperprivacy.core.health

/**
 * Whether a rule still matches its target app, from most to least urgent. Declaration order is
 * the aggregation order: across a rule's packages the first (most urgent) state wins, so a rule
 * that is [OK] in one app and [NOT_APPLICABLE] in another is [OK] overall.
 */
public enum class Health {
    /**
     * The enforcement point was not found in a process that ran this boot, although the rule was
     * active in this package on this device before: the vendor changed the interface.
     */
    BROKEN,

    /**
     * The app version differs from the reference (the surveyed baseline on the surveyed device,
     * else the version the rule was last active with here); the rule may need re-checking.
     */
    NEEDS_REVIEW,

    /** Hooked and running, but nothing blocked for [RuleHealth.IDLE_DAYS] days: the channel may have moved. */
    IDLE,

    /** Hooked; nothing suggests the rule is stale. */
    OK,

    /** Installed, but no process has reported since boot. */
    NOT_RUN,

    /**
     * The target class or sink does not exist in this package on this device and never did: this
     * app (or this model's build of it) does not ship what the rule targets. Never needs attention.
     */
    NOT_APPLICABLE,

    /** The target app is not installed. */
    NOT_INSTALLED,
    ;

    public companion object {
        /** The most urgent of [states]; [NOT_INSTALLED] when there are none. */
        public fun worst(states: Collection<Health>): Health = states.minOrNull() ?: NOT_INSTALLED
    }
}
