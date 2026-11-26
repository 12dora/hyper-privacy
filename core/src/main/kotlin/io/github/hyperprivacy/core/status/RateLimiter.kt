package io.github.hyperprivacy.core.status

/**
 * Token buckets keyed by a non-negative int (an app id): each key may spend [burst] calls at once
 * and regains one every [refillMillis]. Used by system_server to bound how often one app may hand
 * it a relayed batch, before anything of the call is decoded.
 *
 * Fixed-size open addressing over primitive arrays: [tryAcquire] never allocates, takes one
 * uncontended lock and looks at no more than [slots] entries. When every slot is taken, the
 * least recently used one is reused for the new key with an empty bucket (so evicting a key never
 * hands out a fresh burst).
 */
public class RateLimiter(
    private val burst: Int,
    private val refillMillis: Long,
    private val slots: Int = DEFAULT_SLOTS,
) {
    init {
        require(burst > 0 && refillMillis > 0 && slots > 0) { "Invalid rate limit" }
    }

    private val keys = IntArray(slots) { EMPTY }
    private val tokens = IntArray(slots)
    private val refilledAt = LongArray(slots)
    private val usedAt = LongArray(slots)

    /** Spends one call for [key] at monotonic time [now]; `false` when its bucket is empty. */
    @Synchronized
    public fun tryAcquire(key: Int, now: Long): Boolean {
        require(key >= 0) { "Negative key" }
        val slot = slotOf(key, now)
        usedAt[slot] = now
        val elapsed = now - refilledAt[slot]
        if (elapsed >= refillMillis) {
            val regained = elapsed / refillMillis
            val total = tokens[slot] + minOf(regained, burst.toLong()).toInt()
            tokens[slot] = minOf(total, burst)
            refilledAt[slot] = if (tokens[slot] == burst) now else refilledAt[slot] + regained * refillMillis
        } else if (elapsed < 0) {
            refilledAt[slot] = now
        }
        if (tokens[slot] == 0) return false
        tokens[slot]--
        return true
    }

    /** The slot of [key], claiming a free one (full bucket) or the least recently used (empty bucket). */
    private fun slotOf(key: Int, now: Long): Int {
        val start = Math.floorMod(key * HASH_MULTIPLIER, slots)
        var oldest = start
        for (probe in 0 until slots) {
            val slot = (start + probe) % slots
            when (keys[slot]) {
                key -> return slot
                EMPTY -> return claim(slot, key, burst, now)
            }
            if (usedAt[slot] < usedAt[oldest]) oldest = slot
        }
        return claim(oldest, key, 0, now)
    }

    private fun claim(slot: Int, key: Int, initial: Int, now: Long): Int {
        keys[slot] = key
        tokens[slot] = initial
        refilledAt[slot] = now
        return slot
    }

    public companion object {
        public const val DEFAULT_SLOTS: Int = 256
        private const val EMPTY = -1
        private const val HASH_MULTIPLIER = -0x61c88647
    }
}
