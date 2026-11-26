package io.github.hyperprivacy.core.system

/**
 * Immutable set of non-negative ints with O(1), allocation-free [contains]: open addressing with
 * linear probing over a power-of-two table at most half full. Meant for system_server hot paths
 * where a `Set<Int>` would box every lookup.
 */
public class IntSet private constructor(private val table: IntArray, public val size: Int) {
    private val mask = table.size - 1

    public operator fun contains(value: Int): Boolean {
        if (value < 0 || size == 0) return false
        var i = mix(value) and mask
        while (true) {
            val slot = table[i]
            if (slot == value) return true
            if (slot == EMPTY) return false
            i = (i + 1) and mask
        }
    }

    public fun isEmpty(): Boolean = size == 0

    public fun toSet(): Set<Int> = table.filterTo(HashSet()) { it != EMPTY }

    override fun equals(other: Any?): Boolean = other is IntSet && toSet() == other.toSet()

    override fun hashCode(): Int = toSet().hashCode()

    override fun toString(): String = toSet().sorted().toString()

    public companion object {
        private const val EMPTY = -1
        private const val MIN_CAPACITY = 4
        private const val GOLDEN = -0x61c88647

        public val EMPTY_SET: IntSet = IntSet(IntArray(MIN_CAPACITY) { EMPTY }, 0)

        /** Builds a set of [values]; negative values are dropped (they can never be app ids). */
        public fun of(values: Iterable<Int>): IntSet {
            val distinct = values.filterTo(HashSet()) { it >= 0 }
            if (distinct.isEmpty()) return EMPTY_SET
            var capacity = MIN_CAPACITY
            while (capacity < distinct.size * 2) capacity = capacity shl 1
            val table = IntArray(capacity) { EMPTY }
            val mask = capacity - 1
            distinct.forEach { value ->
                var i = mix(value) and mask
                while (table[i] != EMPTY) i = (i + 1) and mask
                table[i] = value
            }
            return IntSet(table, distinct.size)
        }

        public fun of(vararg values: Int): IntSet = of(values.asIterable())

        private fun mix(value: Int): Int {
            val h = value * GOLDEN
            return h xor (h ushr Int.SIZE_BITS / 2)
        }
    }
}
