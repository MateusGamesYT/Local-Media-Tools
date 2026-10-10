package com.localmediatools.vision.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
    /** The picture's layout: [Duplicates.grid] (16×16 grey, zero mean, unit length), or null. */
    val grid: FloatArray?,
    /** Standard deviation of that grid, in grey levels: blank, black or flat pictures have almost none. */
    val contrast: Float,
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
    SIMILAR("Similar shots", "Bursts and retakes of the same moment"),
}

/**
 * A group of duplicates. [best] is the photo to keep by default. [pictures] splits the members into
 * distinct pictures: members of one picture are copies of each other (identical or saved again);
 * different pictures are similar shots of the same moment.
 */
class DupGroup(val kind: DupKind, val members: List<Int>, val best: Int, val pictures: List<List<Int>> = members.map { listOf(it) }) {
    /** The other copies of [best]'s picture: safe to remove. */
    val copies: Set<Int> get() = pictures.firstOrNull { best in it }?.filter { it != best }?.toSet() ?: emptySet()
}

/**
 * Finds duplicates and similar shots.
 *
 * Every match needs two independent signals agreeing, because each signal alone sometimes calls
 * unrelated pictures the same: the image embedding finds two skies, two documents or two dark
 * photos alike, and picture layout alone matches plain backgrounds. Measured on 8,466 real photos
 * (Open Images, CC BY 2.0: 450 runs of consecutive camera shots labelled by eye, unrelated photos
 * including screenshots, documents, night shots and skies taken seconds apart, and re-saved copies;
 * buildtools/duplicates/README.md), 1.6.0's rules put unrelated photos together in 4 groups (53
 * pairs, one group of 13); these rules in none. Other shots of the same place go together less
 * often (16 pairs, was 36), at a price: 51 of the 84 same-moment pairs are found (was 65). Re-saved
 * copies are found as before (1,472 of 1,500; was 1,483).
 *
 * - The same picture saved again: same shape, layout within 0.95 and embedding within 0.93 (without
 *   the embedding: layout within 0.95 and difference hash within 10 bits).
 * - Similar shots: taken within [WINDOW_MS] of each other and the embedding at least 0.7 with the
 *   layout at least 0.7, or matching features ([Check]) in both pictures. Without capture times the
 *   bar is higher. Pictures of different shapes (a crop, a screenshot of a photo) match only on
 *   features.
 * - Groups join only when at least half of the pairs between them match (average linkage), so one
 *   in-between shot can't chain different scenes together.
 * - Flat pictures (contrast below [MIN_CONTRAST]: pages of text, dark shots) have no layout to
 *   compare: they match only as copies, with the embedding at least 0.95 and the hash within 6 bits.
 * - Two photos taken seconds to minutes apart are two shots however alike they look: similar
 *   shots (left to the user), never copies (suggested for the trash). On the evaluation this moved
 *   the 4 same-moment pairs that 1.7.0's first rules treated as copies to similar shots.
 */
object Duplicates {
    /** Similar shots are taken at most this far apart. */
    const val WINDOW_MS = 600_000L
    /** Photos taken this far apart (up to [WINDOW_MS]) are separate shots, so never copies of each other. */
    const val SEPARATE_MS = 1_500L
    const val MIN_CONTRAST = 4f
    private const val HASH_BITS = 10

    /** Matching features between two pictures (OpenCV ORB with a homography): inliers and the share of the picture they span. */
    class Check(val inliers: Int, val cover: Float)

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

    /**
     * The picture's layout from ARGB pixels: grey (0.299 R + 0.587 G + 0.114 B) area-averaged to
     * 16×16 cells (exact fractional coverage), minus its mean, scaled to unit length; and the
     * standard deviation of the 256 cells in grey levels.
     */
    fun grid(argb: IntArray, w: Int, h: Int): Pair<FloatArray, Float> {
        val n = 16
        // Horizontal pass: each row into 16 columns.
        val wx = areaWeights(w, n); val wy = areaWeights(h, n)
        val rows = DoubleArray(h * n)
        for (y in 0 until h) {
            val base = y * w
            for (o in 0 until n) {
                var s = 0.0
                for ((x, wt) in wx[o]) {
                    val c = argb[base + x]
                    s += wt * ((299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000.0)
                }
                rows[y * n + o] = s
            }
        }
        val v = DoubleArray(n * n)
        for (o in 0 until n) for ((y, wt) in wy[o]) for (x in 0 until n) v[o * n + x] += wt * rows[y * n + x]
        val mean = v.average()
        var ss = 0.0
        for (k in v.indices) { v[k] -= mean; ss += v[k] * v[k] }
        val norm = sqrt(ss)
        val out = FloatArray(n * n) { if (norm > 1e-6) (v[it] / norm).toFloat() else 0f }
        return out to (norm / n).toFloat()
    }

    /** For each of [cells] outputs, the input indices it covers and their weights (summing to 1). */
    private fun areaWeights(size: Int, cells: Int): List<List<Pair<Int, Double>>> = (0 until cells).map { o ->
        val a = o.toDouble() * size / cells; val b = (o + 1).toDouble() * size / cells
        val list = ArrayList<Pair<Int, Double>>()
        var x = kotlin.math.floor(a).toInt()
        while (x < min(size, kotlin.math.ceil(b).toInt())) {
            val wt = min(b, x + 1.0) - max(a, x.toDouble())
            if (wt > 0) list.add(x to wt)
            x++
        }
        val sum = list.sumOf { it.second }
        list.map { it.first to it.second / sum }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (k in a.indices) s += a[k] * b[k]
        return s
    }

    /**
     * Groups [items]. [verify] compares features of two pictures (positions in [items]), or returns
     * null when it can't; without it only layout and embedding are used.
     */
    fun group(
        items: List<PhotoInfo>,
        verify: ((Int, Int) -> Check?)? = null,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
        cancelled: () -> Boolean = { false },
    ): List<DupGroup> {
        val n = items.size
        if (n < 2) return emptyList()
        val links = HashMap<Long, Pair<DupKind, Float>>()
        fun key(i: Int, j: Int) = min(i, j).toLong() * n + max(i, j)
        // 1. Identical files.
        items.indices.filter { items[it].digest != null }.groupBy { items[it].digest + ":" + items[it].bytes }.values
            .filter { it.size > 1 }.forEach { g -> for (a in g.indices) for (b in a + 1 until g.size) links[key(g[a], g[b])] = DupKind.IDENTICAL to 3f }
        // 2. Candidate pairs, then the rules.
        val candidates = candidates(items, threads, cancelled) ?: return emptyList()
        val checks = HashMap<Long, Check?>()
        fun check(i: Int, j: Int): Check? = if (verify == null) null else checks.getOrPut(key(i, j)) { verify(i, j) }
        var done = 0
        for (pair in candidates) {
            if (++done % 512 == 0 && cancelled()) return emptyList()
            val i = (pair / n).toInt(); val j = (pair % n).toInt()
            if (links.containsKey(pair)) continue
            relation(items[i], items[j]) { inl, cover -> check(i, j)?.let { it.inliers >= inl && it.cover >= cover } ?: false }?.let { links[pair] = it }
        }
        // 3. Average linkage, strongest links first.
        val parent = IntArray(n) { it }
        val members = HashMap<Int, MutableList<Int>>()
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        for ((pair, _) in links.entries.sortedByDescending { it.value.second }) {
            val i = (pair / n).toInt(); val j = (pair % n).toInt()
            val ri = find(i); val rj = find(j)
            if (ri == rj) continue
            val a = members[ri] ?: mutableListOf(ri); val b = members[rj] ?: mutableListOf(rj)
            var hit = 0
            for (x in a) for (y in b) if (links.containsKey(key(x, y))) hit++
            if (hit * 2 >= a.size * b.size) {
                parent[rj] = ri
                a.addAll(b); members[ri] = a; members.remove(rj)
            }
        }
        return members.values.filter { it.size > 1 }.map { g ->
            val best = g.maxWith(compareBy<Int>({ items[it].pixels }, { items[it].sharpness }, { items[it].bytes }))
            var kind = DupKind.SIMILAR
            for (a in g.indices) for (b in a + 1 until g.size) links[key(g[a], g[b])]?.first?.let { if (it.ordinal < kind.ordinal) kind = it }
            // Same picture: members joined by identical or saved-again links.
            val pic = HashMap<Int, Int>()
            fun pf(x: Int): Int { var r = x; while (pic[r] != null && pic[r] != r) r = pic[r]!!; return r }
            for (a in g.indices) for (b in a + 1 until g.size) {
                val k = links[key(g[a], g[b])]?.first
                if (k == DupKind.IDENTICAL || k == DupKind.NEAR_DUPLICATE) { val ra = pf(g[a]); val rb = pf(g[b]); if (ra != rb) pic[rb] = ra }
            }
            val pictures = g.sorted().groupBy { pf(it) }.values.map { p -> p.map { items[it].index } }
            DupGroup(kind, g.sorted().map { items[it].index }, items[best].index, pictures)
        }.sortedWith(compareBy({ it.kind.ordinal }, { -it.members.size }))
    }

    /**
     * How two photos relate, or null. [features] asks for at least that many matching features
     * spanning at least that share of the picture.
     */
    internal fun relation(a: PhotoInfo, b: PhotoInfo, features: (Int, Float) -> Boolean): Pair<DupKind, Float>? {
        val ga = a.grid ?: return null; val gb = b.grid ?: return null
        val ea = a.embedding; val eb = b.embedding
        val c = if (ea != null && eb != null) dot(ea, eb) else null
        val ra = a.width.toDouble() / max(1, a.height); val rb = b.width.toDouble() / max(1, b.height)
        val sameShape = abs(ra - rb) / max(ra, rb) <= 0.03
        val ta = a.takenMs; val tb = b.takenMs
        val timed = ta != null && tb != null
        val near = ta == null || tb == null || abs(ta - tb) <= WINDOW_MS
        // Taken seconds or minutes apart: two shots, never copies of one (a copy keeps its photo's
        // time, has none, or got the time it was saved, usually much later).
        val separate = timed && abs(ta!! - tb!!) in SEPARATE_MS..WINDOW_MS
        if (a.contrast < MIN_CONTRAST || b.contrast < MIN_CONTRAST) {
            // Pages of text, dark shots: no layout to compare, so only near-certain copies.
            return if (!separate && c != null && sameShape && c >= 0.95f && hamming(a.dhash, b.dhash) <= 6) DupKind.NEAR_DUPLICATE to (1f + c) else null
        }
        if (!sameShape) {
            // Different shapes (a crop, a screenshot of the photo): only matching features can tell.
            return if (c != null && near && c >= 0.8f && features(50, 0f)) DupKind.SIMILAR to c else null
        }
        val s = dot(ga, gb)
        if (c == null) return if (!separate && s >= 0.95f && hamming(a.dhash, b.dhash) <= HASH_BITS) DupKind.NEAR_DUPLICATE to (1f + s) else null
        if (!separate && s >= 0.95f && c >= 0.93f) return DupKind.NEAR_DUPLICATE to (1f + c)
        if (!near) return null
        val similar = if (timed) {
            (c >= 0.7f && s >= 0.7f) || (c >= 0.85f && s >= 0.5f) || (c >= 0.7f && features(30, 0.1f)) || (c >= 0.85f && features(25, 0f))
        } else {
            (c >= 0.8f && (s >= 0.75f || features(50, 0f))) || (c >= 0.92f && s >= 0.5f)
        }
        return if (similar) DupKind.SIMILAR to c else null
    }

    /**
     * Pairs worth checking (as i·n + j, i < j): timed photos with embeddings against those taken
     * within the window; photos without a capture time against all others when their embeddings are
     * at least 0.8 alike (prefiltered on a 128-number projection); and any two whose difference
     * hashes are within [HASH_BITS] bits (found through 11 hash slices: two hashes that close agree
     * exactly on at least one).
     */
    private fun candidates(items: List<PhotoInfo>, threads: Int, cancelled: () -> Boolean): LongArray? {
        val n = items.size
        val out = LongArrayList()
        val usable = items.indices.filter { items[it].grid != null }
        // Time window.
        val timed = usable.filter { items[it].takenMs != null && items[it].embedding != null }.sortedBy { items[it].takenMs }
        for (a in timed.indices) {
            if (a % 256 == 0 && cancelled()) return null
            val ta = items[timed[a]].takenMs!!
            var b = a + 1
            while (b < timed.size && items[timed[b]].takenMs!! - ta <= WINDOW_MS) {
                val i = timed[a]; val j = timed[b]
                if (dot(items[i].embedding!!, items[j].embedding!!) >= 0.7f) out.add(min(i, j).toLong() * n + max(i, j))
                b++
            }
        }
        // Photos without a capture time against everything (in parallel).
        val withEmb = usable.filter { items[it].embedding != null }
        val untimed = withEmb.filter { items[it].takenMs == null }
        if (untimed.isNotEmpty()) {
            val proj = arrayOfNulls<FloatArray>(n)
            for (i in withEmb) proj[i] = project(items[i].embedding!!)
            val others = withEmb.toIntArray()
            val pool = Executors.newFixedThreadPool(max(1, threads))
            try {
                val chunks = untimed.chunked(max(1, untimed.size / (threads * 4) + 1))
                val futures = chunks.map { chunk ->
                    pool.submit(Callable {
                        val found = LongArrayList()
                        for (i in chunk) {
                            if (cancelled()) break
                            val pi = proj[i]!!; val ei = items[i].embedding!!
                            for (j in others) {
                                if (j == i || (items[j].takenMs == null && j < i)) continue  // untimed pairs once
                                if (dot(pi, proj[j]!!) < 0.6f) continue
                                if (dot(ei, items[j].embedding!!) >= 0.8f) found.add(min(i, j).toLong() * n + max(i, j))
                            }
                        }
                        found
                    })
                }
                for (f in futures) out.addAll(f.get())
            } finally { pool.shutdownNow() }
            if (cancelled()) return null
        }
        // Difference-hash slices.
        val slices = 11
        val tables = Array(slices) { HashMap<Int, MutableList<Int>>() }
        for (i in usable) {
            val h = items[i].dhash
            for (t in 0 until slices) tables[t].getOrPut(slice(h, t)) { ArrayList() }.add(i)
        }
        for (t in 0 until slices) {
            if (cancelled()) return null
            for (bucket in tables[t].values) {
                // Thousands of identical hashes (all-black pictures) would cost millions of checks for nothing.
                if (bucket.size < 2 || bucket.size > 4000) continue
                for (a in bucket.indices) for (b in a + 1 until bucket.size) {
                    val i = bucket[a]; val j = bucket[b]
                    val hi = items[i].dhash; val hj = items[j].dhash
                    // Counted once: in the first slice where they agree.
                    var first = true
                    for (u in 0 until t) if (slice(hi, u) == slice(hj, u)) { first = false; break }
                    if (first && hamming(hi, hj) <= HASH_BITS) out.add(min(i, j).toLong() * n + max(i, j))
                }
            }
        }
        val arr = out.toArray()
        arr.sort()
        // Remove repeats.
        var w = 0
        for (k in arr.indices) if (k == 0 || arr[k] != arr[k - 1]) arr[w++] = arr[k]
        return arr.copyOf(w)
    }

    /** Bits of slice [t] of 11 (six bits each, the last two five). */
    private fun slice(h: Long, t: Int): Int {
        val start = if (t < 9) t * 6 else 54 + (t - 9) * 5
        val len = if (t < 9) 6 else 5
        return ((h ushr start) and ((1L shl len) - 1)).toInt()
    }

    private val projection: IntArray by lazy {
        // Each of the 1,024 embedding numbers goes to one of 128 sums with a random sign (fixed seed).
        val rnd = java.util.Random(20261009L)
        IntArray(1024) { (rnd.nextInt(128) shl 1) or rnd.nextInt(2) }
    }

    /** A 128-number sketch of an embedding whose dot products approximate the full ones. */
    private fun project(e: FloatArray): FloatArray {
        val p = FloatArray(128)
        val map = projection
        for (k in e.indices) {
            val m = map[k % map.size]
            if (m and 1 == 0) p[m shr 1] += e[k] else p[m shr 1] -= e[k]
        }
        var s = 0f
        for (v in p) s += v * v
        val norm = sqrt(s).coerceAtLeast(1e-6f)
        for (k in p.indices) p[k] /= norm
        return p
    }

    private class LongArrayList {
        private var a = LongArray(64); var size = 0; private set
        fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun addAll(o: LongArrayList) { for (k in 0 until o.size) add(o.a[k]) }
        fun toArray() = a.copyOf(size)
    }
}
