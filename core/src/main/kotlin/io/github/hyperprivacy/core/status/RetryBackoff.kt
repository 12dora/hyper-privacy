package io.github.hyperprivacy.core.status

/**
 * Exponential retry schedule for status delivery: [initialMillis], doubling up to [maxMillis];
 * after [fastAttempts] consecutive failures every [slowMillis]. It never gives up: a receiver that
 * is unavailable for hours (a phone left locked after an unattended reboot) is reached once it
 * comes back, at the cost of one attempt per [slowMillis] meanwhile. Pure and immutable.
 */
public class RetryBackoff(
    private val initialMillis: Long = DEFAULT_INITIAL_MILLIS,
    private val maxMillis: Long = DEFAULT_MAX_MILLIS,
    private val fastAttempts: Int = DEFAULT_FAST_ATTEMPTS,
    private val slowMillis: Long = DEFAULT_SLOW_MILLIS,
) {
    init {
        require(initialMillis > 0 && maxMillis >= initialMillis && fastAttempts > 0 && slowMillis >= maxMillis) {
            "Invalid backoff"
        }
    }

    /** Delay before retrying after the [failures]-th consecutive failure (1-based; less counts as 1). */
    public fun delayAfter(failures: Int): Long {
        if (failures > fastAttempts) return slowMillis
        var delay = initialMillis
        repeat(failures - 1) { delay = if (delay >= maxMillis / 2) maxMillis else delay * 2 }
        return minOf(delay, maxMillis)
    }

    public companion object {
        public const val DEFAULT_INITIAL_MILLIS: Long = 5_000L
        public const val DEFAULT_MAX_MILLIS: Long = 300_000L

        /** 5 s doubling to 5 min, then every 5 min: about two hours at the fast schedule. */
        public const val DEFAULT_FAST_ATTEMPTS: Int = 30

        public const val DEFAULT_SLOW_MILLIS: Long = 1_800_000L
    }
}
