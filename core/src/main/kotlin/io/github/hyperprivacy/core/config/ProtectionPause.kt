package io.github.hyperprivacy.core.config

/**
 * The global "pause all protection" switch, stored in the remote preferences group
 * [ConfigCodec.PREFS_GROUP] as one absolute deadline so every process reads the same state:
 *
 * - [KEY] → Long, wall-clock millis (`System.currentTimeMillis`) at which protection resumes;
 *   [UNTIL_MANUAL] pauses until the user resumes. Absent, zero or negative: not paused.
 *
 * Wall-clock time is used because the value crosses processes and survives reboots. Hooked
 * processes never compare against the clock per call: they derive the effective switches once
 * when the preference changes, and again from a timer when the deadline passes
 * ([nextCheckDelayMs]).
 */
public object ProtectionPause {
    public const val KEY: String = "pause.until"

    /** Deadline meaning "no automatic resume". */
    public const val UNTIL_MANUAL: Long = Long.MAX_VALUE

    private const val MINUTE_MS = 60_000L
    private const val QUARTER_HOUR_MINUTES = 15
    private const val HOUR_MINUTES = 60

    /** Longest wait of the resume timer; bounds the lateness after the device slept through the deadline. */
    private const val MAX_CHECK_MS = MINUTE_MS

    /** Smallest wait, so a deadline a few millis away does not spin the timer. */
    private const val MIN_CHECK_MS = 50L

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
     * How long a timer should wait before looking again, or `null` when no timer is needed (not
     * paused, or paused until resumed). Capped at one minute and re-armed from the wall clock each
     * time, so a deadline the device slept through is honoured within a minute of waking.
     */
    public fun nextCheckDelayMs(until: Long, now: Long): Long? {
        if (until == UNTIL_MANUAL || !isPaused(until, now)) return null
        return (until - now).coerceIn(MIN_CHECK_MS, MAX_CHECK_MS)
    }
}
