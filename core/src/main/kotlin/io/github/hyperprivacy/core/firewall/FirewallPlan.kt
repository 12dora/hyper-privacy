package io.github.hyperprivacy.core.firewall

/** Changes needed to move the firewall chain from [applied] to [desired] deny-listed uids. */
public data class FirewallPlan(val toDeny: Set<Int>, val toClear: Set<Int>) {
    val isEmpty: Boolean get() = toDeny.isEmpty() && toClear.isEmpty()

    public companion object {
        public fun diff(applied: Set<Int>, desired: Set<Int>): FirewallPlan =
            FirewallPlan(toDeny = desired - applied, toClear = applied - desired)
    }
}
