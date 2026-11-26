package io.github.hyperprivacy.core.health

import io.github.hyperprivacy.core.status.HookState

/**
 * What this device remembers about a rule (or a sink) in one package, across boots and app
 * updates. It separates "this app never shipped the target" ([Health.NOT_APPLICABLE]) from "the
 * target went away" ([Health.BROKEN]), and gives the version reference for [Health.NEEDS_REVIEW]
 * on devices other than the surveyed one.
 *
 * @property firstActiveAt wall-clock millis of the first ACTIVE report seen here; `null` if the
 *   rule was never active in this package on this device.
 * @property lastActiveVersion app versionCode at the latest ACTIVE report; `null` if never active
 *   or the version was unknown every time.
 */
public data class ActivationHistory(val firstActiveAt: Long? = null, val lastActiveVersion: Long? = null) {
    public val everActive: Boolean get() = firstActiveAt != null

    /**
     * The history after a report of [state] received at [at] from an app running [versionCode].
     * MISSED never erases history. An ACTIVE report with an unknown version keeps the last known one.
     */
    public fun record(state: HookState, at: Long, versionCode: Long?): ActivationHistory =
        if (state == HookState.ACTIVE) {
            ActivationHistory(firstActiveAt ?: at, versionCode ?: lastActiveVersion)
        } else {
            this
        }

    public companion object {
        public val NEVER: ActivationHistory = ActivationHistory()

        /**
         * History for a row stored before history was tracked. An ACTIVE row proves activation
         * (since [activeSince], else [reportedAt]); a MISSED row proves nothing, so it reads as never
         * active. The version is unknown either way and is filled in by the next ACTIVE report.
         */
        public fun fromLegacy(state: HookState, activeSince: Long?, reportedAt: Long): ActivationHistory =
            if (state == HookState.ACTIVE) ActivationHistory(firstActiveAt = activeSince ?: reportedAt) else NEVER
    }
}
