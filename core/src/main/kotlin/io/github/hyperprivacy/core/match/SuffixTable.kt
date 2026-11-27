package io.github.hyperprivacy.core.match

/**
 * Immutable open-addressing string table that can be probed with a *suffix* of a string without
 * creating the substring, so per-label host lookups on hot paths (DNS, TLS) allocate nothing.
 */
internal class SuffixTable<T : Any>(entries: Map<String, T>) {
    private val mask: Int
    private val keys: Array<String?>
    private val values: Array<Any?>

    init {
        var capacity = MIN_CAPACITY
        while (capacity < entries.size * LOAD_FACTOR_INVERSE) capacity = capacity shl 1
        mask = capacity - 1
        keys = arrayOfNulls(capacity)
        values = arrayOfNulls(capacity)
        for ((key, value) in entries) {
            var slot = hash(key, 0) and mask
            while (keys[slot] != null) slot = (slot + 1) and mask
            keys[slot] = key
            values[slot] = value
        }
    }

    val isEmpty: Boolean = entries.isEmpty()

    /** Visits every stored key and value, in table order (cold path: building native tables). */
    @Suppress("UNCHECKED_CAST") // Only values of type T are ever stored.
    fun forEach(action: (String, T) -> Unit) {
        for (slot in keys.indices) keys[slot]?.let { action(it, values[slot] as T) }
    }

    /** The value stored under `text.substring(start)`, or `null`. */
    @Suppress("UNCHECKED_CAST") // Only values of type T are ever stored.
    fun get(text: String, start: Int): T? {
        val length = text.length - start
        var slot = hash(text, start) and mask
        while (true) {
            val key = keys[slot] ?: return null
            if (key.length == length && key.regionMatches(0, text, start, length)) return values[slot] as T
            slot = (slot + 1) and mask
        }
    }

    private companion object {
        const val MIN_CAPACITY = 8
        const val LOAD_FACTOR_INVERSE = 2
        const val HASH_MULTIPLIER = 31
        const val SPREAD_SHIFT = 16

        fun hash(text: String, start: Int): Int {
            var h = 0
            for (i in start until text.length) h = HASH_MULTIPLIER * h + text[i].code
            return h xor (h ushr SPREAD_SHIFT)
        }
    }
}
