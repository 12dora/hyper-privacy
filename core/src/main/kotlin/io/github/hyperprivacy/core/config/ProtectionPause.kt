package io.github.hyperprivacy.core.config

/**
 * The global "pause all protection" switch, stored in the remote preferences group
 * [ConfigCodec.PREFS_GROUP] as one absolute deadline so every process reads the same state:
 *
 * - [KEY] → Long, wall-clock millis (`System.currentTimeMillis`) at which protection resumes;
 *   [UNTIL_MANUAL] pauses until the user resumes. Absent, zero or negative: not paused.
 *
 * Wall-clock time is used because the value crosses processes and survives reboots. Hooked
 * processes and system_server never run a timer and never compare against the clock per call:
 * they derive the effective switches once, with one clock read, whenever the preferences change
 * (so a process that starts after the deadline is not paused). The module app ends a timed pause
 * by removing [KEY] from one scheduled alarm at [resumeAt]; every process reacts to that change.
 */
public object ProtectionPause {
    public const val KEY: String = "pause.until"

    /** Deadline meaning "no automatic resume". */
    public const val UNTIL_MANUAL: Long = Long.MAX_VALUE

    private const val MINUTE_MS = 60_000L
    private const val QUARTER_HOUR_MINUTES = 15
    private const val HOUR_MINUTES = 60

    /** The durations the UI offers; `null` means until the user resumes. */
    public enum class Option(public val durationMs: Long?) {
        QUARTER_HOUR(QUARTER_HOUR_MINUTES * MINUTE_MS),
        HOUR(HOUR_MINUTES * MINUTE_MS),
        UNTIL_RESUMED(null),
    }

    /** The stored deadline in [raw] preferences; `0` when not paused or malformed. */
    public fun untilOf(raw: Map<String, *>): Long = (raw[KEY] as? Long)?.takeIf { it > 0 } ?: 0L

    /** Whether protection is paused at [now] under the stored [until]. */
    public fun isPaused(until: Long, now: Long): Boolean = until > 0 && (until == UNTIL_MANUAL || now < until)

    /** The deadline to store for [option] chosen at [now]. */
    public fun deadline(option: Option, now: Long): Long = option.durationMs?.let { now + it } ?: UNTIL_MANUAL

    /** Whole minutes left (rounded up, at least 1); `null` for [UNTIL_MANUAL] or when not paused. */
    public fun remainingMinutes(until: Long, now: Long): Int? {
        if (until == UNTIL_MANUAL || !isPaused(until, now)) return null
        return ((until - now + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(1)
    }

    /**
     * When the module app should clear the stored [until] (its one resume alarm), or `null` when
     * nothing is to be scheduled: not paused, already over, or paused until resumed.
     */
    public fun resumeAt(until: Long, now: Long): Long? = until.takeIf { it != UNTIL_MANUAL && isPaused(it, now) }

    /** Whether [until] is a timed pause whose deadline has passed at [now]: the stored value is stale. */
    public fun isExpired(until: Long, now: Long): Boolean = until > 0 && until != UNTIL_MANUAL && now >= until
}
