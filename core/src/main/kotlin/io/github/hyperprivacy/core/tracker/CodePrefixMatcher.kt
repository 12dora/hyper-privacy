package io.github.hyperprivacy.core.tracker

/**
 * Longest-prefix matcher from Java code prefixes (dot form, e.g. `com.umeng.analytics.`) to an
 * owner id, applied to dex type descriptors (`Lcom/umeng/analytics/X;`) without decoding them.
 *
 * A byte trie over the descriptor form of each prefix; lookup walks at most the descriptor's
 * length and allocates nothing. On duplicate prefixes the first owner wins. Immutable and safe to
 * share across threads.
 */
public class CodePrefixMatcher(entries: List<Pair<String, String>>) {
    /** Owner ids; [matchDescriptor] returns indexes into this list. */
    public val ids: List<String>

    // Node i: children keys[i] (sorted bytes) -> next[i]; owner[i] is an index into ids or -1.
    private val keys: Array<ByteArray>
    private val next: Array<IntArray>
    private val owner: IntArray

    init {
        val idIndex = LinkedHashMap<String, Int>()
        val children = arrayListOf(HashMap<Byte, Int>())
        val owners = arrayListOf(NONE)
        for ((prefix, id) in entries) {
            val bytes = descriptorOf(prefix) ?: continue
            var node = 0
            for (b in bytes) {
                node = children[node].getOrPut(b) {
                    children += HashMap()
                    owners += NONE
                    children.size - 1
                }
            }
            if (owners[node] == NONE) owners[node] = idIndex.getOrPut(id) { idIndex.size }
        }
        ids = idIndex.keys.toList()
        keys = Array(children.size) { i -> children[i].keys.sorted().toByteArray() }
        next = Array(children.size) { i -> IntArray(keys[i].size) { k -> children[i].getValue(keys[i][k]) } }
        owner = owners.toIntArray()
    }

    /** Whether no prefix was accepted. */
    public val isEmpty: Boolean get() = ids.isEmpty()

    /**
     * Index into [ids] of the longest prefix covering the type descriptor in
     * `bytes[offset until offset + length]` (modified UTF-8, as stored in dex), or -1.
     * Only class descriptors (`L...;`) can match.
     */
    public fun matchDescriptor(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0 || bytes[offset] != CLASS_TAG) return NONE
        var node = 0
        var best = NONE
        val end = offset + length
        var i = offset
        while (i < end) {
            node = child(node, bytes[i])
            if (node < 0) break
            if (owner[node] != NONE) best = owner[node]
            i++
        }
        return best
    }

    /** Owner of the longest prefix covering [className] (dot form, e.g. `com.foo.Bar`), or `null`. */
    public fun match(className: String): String? {
        val bytes = descriptorOf("$className;") ?: return null
        return matchDescriptor(bytes, 0, bytes.size).takeIf { it >= 0 }?.let(ids::get)
    }

    /**
     * As [match] for a dot-form class name (e.g. `StackTraceElement.className`), returning the
     * index into [ids] or -1. Walks [className] in place: no allocation, at most one trie step
     * (a binary search over a node's few children) per character; a non-ASCII character ends
     * the walk with the best match so far.
     */
    public fun matchName(className: String): Int {
        var node = child(0, CLASS_TAG)
        var best = NONE
        var i = 0
        val end = className.length
        while (node >= 0 && i < end) {
            val c = className[i++]
            if (c.code > MAX_ASCII) break
            node = child(node, if (c == '.') SLASH else c.code.toByte())
            if (node >= 0 && owner[node] != NONE) best = owner[node]
        }
        return best
    }

    private fun child(node: Int, b: Byte): Int {
        val k = keys[node]
        // Fan-out is small (package name characters); binary search keeps deep, wide nodes cheap too.
        var lo = 0
        var hi = k.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = k[mid]
            when {
                v < b -> lo = mid + 1
                v > b -> hi = mid - 1
                else -> return next[node][mid]
            }
        }
        return NONE
    }

    public companion object {
        private const val NONE = -1
        private const val CLASS_TAG = 'L'.code.toByte()
        private const val SLASH = '/'.code.toByte()
        private const val MAX_ASCII = 0x7F

        /** `com.foo.` → `Lcom/foo/` as bytes; `null` for blank or non-ASCII prefixes. */
        internal fun descriptorOf(prefix: String): ByteArray? {
            if (prefix.isBlank() || prefix.any { it.code > MAX_ASCII }) return null
            return ("L" + prefix.replace('.', '/')).toByteArray(Charsets.US_ASCII)
        }
    }
}
