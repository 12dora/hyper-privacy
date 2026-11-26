package io.github.hyperprivacy.core.status

/**
 * Exponential retry schedule for status delivery: [initialMillis], doubling up to [maxMillis],
 * for at most [maxAttempts] consecutive failures (per process lifetime, i.e. per boot for
 * system_server). Pure and immutable.
 */
public class RetryBackoff(
    private val initialMillis: Long = DEFAULT_INITIAL_MILLIS,
    private val maxMillis: Long = DEFAULT_MAX_MILLIS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(initialMillis > 0 && maxMillis >= initialMillis && maxAttempts > 0) { "Invalid backoff" }
    }

    /**
     * Delay before retrying after the [failures]-th consecutive failure (1-based), or `null` once
     * the attempt budget is exhausted.
     */
    public fun delayAfter(failures: Int): Long? {
        if (failures < 1 || failures > maxAttempts) return null
        var delay = initialMillis
        repeat(failures - 1) { delay = if (delay >= maxMillis / 2) maxMillis else delay * 2 }
        return minOf(delay, maxMillis)
    }

    public companion object {
        public const val DEFAULT_INITIAL_MILLIS: Long = 5_000L
        public const val DEFAULT_MAX_MILLIS: Long = 300_000L

        /** 5 s doubling to 5 min, then every 5 min: about two hours of retries in total. */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 30
    }
}
