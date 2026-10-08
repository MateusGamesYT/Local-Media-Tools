package com.localmediatools.vision.core

/** What the finder knows about one photo. */
class PhotoInfo(
    val index: Int,
    val bytes: Long,
    val width: Int,
    val height: Int,
    /** 64-bit difference hash of the picture. */
    val dhash: Long,
    /** L2-normalised image embedding (on-device model), or null. */
    val embedding: FloatArray?,
    val sharpness: Double,
    /** Capture time (ms since epoch) when known. */
    val takenMs: Long?,
    /** Content digest of the file bytes, for exact copies. */
    val digest: String?,
) {
    val pixels get() = width.toLong() * height
}

enum class DupKind(val label: String, val explain: String) {
    IDENTICAL("Identical copies", "Byte-for-byte the same file"),
    NEAR_DUPLICATE("Duplicates", "The same picture saved again, resized or re-compressed"),
    SIMILAR("Similar shots", "Bursts and near-identical photos of the same moment"),
}

class DupGroup(val kind: DupKind, val members: List<Int>, val best: Int)

/**
 * Groups duplicates and similar shots. Pairs are linked when files are identical, when their
 * difference hashes are within [hashDistance] bits, or when their embeddings are similar enough
 * ([similar], or [burst] for photos taken within [burstWindowMs] of each other).
 */
object Duplicates {
    /** Difference hash from a 9×8 grey thumbnail (row-major luma values). */
    fun dhash(gray9x8: IntArray): Long {
        var h = 0L; var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (gray9x8[y * 9 + x + 1] > gray9x8[y * 9 + x]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    private class UnionFind(n: Int) {
        val p = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (p[r] != r) { p[r] = p[p[r]]; r = p[r] }; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) p[rb] = ra }
    }

    fun group(
        items: List<PhotoInfo>,
        hashDistance: Int = 6,
        similar: Float = 0.82f,
        burst: Float = 0.70f,
        burstWindowMs: Long = 90_000,
        cancelled: () -> Boolean = { false },
    ): List<DupGroup> {
        val n = items.size
        if (n < 2) return emptyList()
        val uf = UnionFind(n)
        val kind = HashMap<Int, DupKind>()
        fun link(i: Int, j: Int, k: DupKind) {
            val ki = kind[uf.find(i)]; val kj = kind[uf.find(j)]
            uf.union(i, j)
            // Keep the strongest relation seen anywhere in the merged group.
            kind[uf.find(i)] = listOfNotNull(ki, kj, k).minBy { it.ordinal }
        }
        // 1. Identical files.
        items.indices.filter { items[it].digest != null }.groupBy { items[it].digest + ":" + items[it].bytes }.values
            .filter { it.size > 1 }.forEach { g -> for (k in 1 until g.size) link(g[0], g[k], DupKind.IDENTICAL) }
        // 2. Same picture saved again (hash distance; aspect ratio must match, otherwise it's a crop).
        for (i in 0 until n) {
            if (i % 256 == 0 && cancelled()) return emptyList()
            for (j in i + 1 until n) {
                if (hamming(items[i].dhash, items[j].dhash) > hashDistance) continue
                val ra = items[i].width.toDouble() / items[i].height; val rb = items[j].width.toDouble() / items[j].height
                if (kotlin.math.abs(ra - rb) / ra < 0.03 && uf.find(i) != uf.find(j)) link(i, j, DupKind.NEAR_DUPLICATE)
            }
        }
        // 3. Similar shots by embedding: photos with a capture time are compared with neighbours in a
        //    one-hour window; photos without one are compared with everything (bounded).
        fun similarPair(i: Int, j: Int) {
            val ea = items[i].embedding ?: return; val eb = items[j].embedding ?: return
            val ta = items[i].takenMs; val tb = items[j].takenMs
            val need = if (ta != null && tb != null && kotlin.math.abs(ta - tb) <= burstWindowMs) burst else similar
            if (uf.find(i) != uf.find(j) && FaceEngine.cosine(ea, eb) >= need) link(i, j, DupKind.SIMILAR)
        }
        val timed = items.indices.filter { items[it].takenMs != null && items[it].embedding != null }.sortedBy { items[it].takenMs }
        for (a in timed.indices) {
            if (a % 128 == 0 && cancelled()) return emptyList()
            var b = a + 1
            while (b < timed.size && items[timed[b]].takenMs!! - items[timed[a]].takenMs!! <= 3_600_000) { similarPair(timed[a], timed[b]); b++ }
        }
        val untimed = items.indices.filter { items[it].takenMs == null && items[it].embedding != null }
        if (untimed.size <= 4000) for (a in untimed.indices) {
            if (a % 64 == 0 && cancelled()) return emptyList()
            for (b in a + 1 until untimed.size) similarPair(untimed[a], untimed[b])
            for (t in timed) similarPair(untimed[a], t)
        }
        val groups = (0 until n).groupBy { uf.find(it) }.filter { it.value.size > 1 }
        return groups.map { (root, members) ->
            val best = members.maxWith(compareBy<Int>({ items[it].pixels }, { items[it].sharpness }, { items[it].bytes }))
            DupGroup(kind[root] ?: DupKind.SIMILAR, members.map { items[it].index }, items[best].index)
        }.sortedWith(compareBy({ it.kind.ordinal }, { -it.members.size }))
    }
}
