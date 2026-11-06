package com.srivathsan.livepix.data

import kotlin.math.abs

/** A picture's fingerprint and how good a copy it is. */
class DuplicateCandidate(
    val key: String,
    val hash: Long,
    val color: Int,
    val width: Int,
    val height: Int,
    val size: Long,
    val date: Long,
    /** Live or Portrait: keeps more than a flat copy would. */
    val rich: Boolean,
)

object Duplicates {
    private const val CLOSE_BITS = 3
    private const val SAME_BITS = 1
    private const val CLOSE_COLOR = 12
    private const val SAME_COLOR = 4
    private const val SAME_MOMENT_MS = 1_000L

    /**
     * Groups exact and near-exact copies, each with the best copy first. Copies from the same moment
     * may differ slightly (a re-encode through another app); pictures from different times must look
     * almost identical, so that a burst or a similar shot isn't treated as a copy.
     */
    fun find(items: List<DuplicateCandidate>): List<List<DuplicateCandidate>> {
        val usable = items.filter { it.hash != 0L && it.width > 0 && it.height > 0 }
        val parent = IntArray(usable.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }

        // Two hashes within 3 bits must agree exactly on at least one of 4 chunks, so only compare those.
        for (chunk in 0 until 4) {
            val buckets = HashMap<Int, MutableList<Int>>()
            usable.forEachIndexed { i, c -> buckets.getOrPut(((c.hash ushr (16 * chunk)) and 0xFFFF).toInt()) { ArrayList() }.add(i) }
            for (bucket in buckets.values) {
                // A flat or dark picture lands in a huge bucket of lookalikes; those aren't worth comparing all-pairs.
                if (bucket.size > 400) continue
                for (a in bucket.indices) for (b in a + 1 until bucket.size) {
                    val i = bucket[a]; val j = bucket[b]
                    if (root(i) != root(j) && isCopy(usable[i], usable[j])) parent[root(i)] = root(j)
                }
            }
        }
        return usable.indices.groupBy(::root).values.filter { it.size > 1 }
            .map { group -> group.map { usable[it] }.sortedWith(BEST_FIRST) }
            .sortedByDescending { g -> g.maxOf { it.date } }
    }

    internal fun isCopy(a: DuplicateCandidate, b: DuplicateCandidate): Boolean {
        val ratioA = a.width.toFloat() / a.height
        val ratioB = b.width.toFloat() / b.height
        if (abs(ratioA - ratioB) > 0.01f * ratioA) return false
        val bits = java.lang.Long.bitCount(a.hash xor b.hash)
        val color = colorDistance(a.color, b.color)
        if (bits <= SAME_BITS && color <= SAME_COLOR) return true
        return bits <= CLOSE_BITS && color <= CLOSE_COLOR && abs(a.date - b.date) <= SAME_MOMENT_MS
    }

    private fun colorDistance(a: Int, b: Int): Int =
        maxOf(abs(((a shr 16) and 255) - ((b shr 16) and 255)), abs(((a shr 8) and 255) - ((b shr 8) and 255)), abs((a and 255) - (b and 255)))

    private val BEST_FIRST = compareByDescending<DuplicateCandidate> { it.rich }
        .thenByDescending { it.width.toLong() * it.height }
        .thenByDescending { it.size }
        .thenBy { it.date }
}
