package com.localmediatools.gallery.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * What the search model (SigLIP 2) says a photo or a text is about: [DIM] numbers of length 1. A photo
 * and a text match as well as their cosine says. Stored in 8 bits each with one scale per embedding
 * (4 + [DIM] bytes): the cosine moves by less than 0.002, far below the gaps that decide a search.
 */
object Embedding {
    const val DIM = 768
    const val BYTES = 4 + DIM

    fun pack(e: FloatArray): ByteArray {
        require(e.size == DIM)
        var m = 0f
        for (v in e) m = maxOf(m, abs(v))
        val scale = if (m > 0f) m / 127f else 1f
        val b = ByteBuffer.allocate(BYTES).order(ByteOrder.BIG_ENDIAN)
        b.putFloat(scale)
        for (v in e) b.put(Math.round(v / scale).coerceIn(-127, 127).toByte())
        return b.array()
    }

    fun unpack(b: ByteArray): FloatArray {
        require(b.size == BYTES)
        val scale = ByteBuffer.wrap(b, 0, 4).order(ByteOrder.BIG_ENDIAN).float
        return FloatArray(DIM) { b[4 + it] * scale }
    }

    /** Scaled to length 1 (in place). */
    fun normalize(e: FloatArray): FloatArray {
        var s = 0.0
        for (v in e) s += v.toDouble() * v
        val n = sqrt(s).toFloat()
        if (n > 0f) for (i in e.indices) e[i] /= n
        return e
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

/**
 * How sure a match is, and where a search's list of matches ends. SigLIP 2 scores a photo and a text
 * as a probability, sigmoid([scale] × cosine + [bias]) (the model's own two numbers, from
 * search/model.json), small across a library for short queries. Where the list ends was measured with
 * the app's own model files on Open Images (buildtools/search/cutoff.py: 4,999 photos, 112 classes with
 * human-checked labels, their names in the app's ten languages; settings chosen on half the classes,
 * scored on the other half): a photo matches when its cosine reaches [SURE], or, for a query whose best
 * photo scores lower (short words in other languages than English often do), when it comes within
 * [DROP] of the best one, never below [FLOOR]. That found 43% of the photos people marked instead of
 * 35% with one threshold for all, at precision 0.34 instead of 0.38 (lower bounds: photos nobody
 * checked count as wrong), F1 0.316 instead of 0.303. [calibrated] puts the cut at 0.5, like the
 * categories' scores ([Taxonomy.TAGGED]), so the two can be compared and combined.
 */
class MatchScale(val scale: Float, val bias: Float) {
    fun probability(cos: Float): Double = 1.0 / (1.0 + exp(-(scale.toDouble() * cos + bias)))

    /** Where the matches of a query end, given its best photo's cosine [best] in the library. */
    fun cutoff(best: Float): Float = maxOf(FLOOR, minOf(SURE, best - DROP))

    /** 0.5 at [cut], up to 1 for a sure match, down to 0 (as [Taxonomy.calibrate]). */
    fun calibrated(cos: Float, cut: Float = SURE): Float {
        val pc = probability(cut)
        val p = probability(cos)
        return if (p >= pc) (0.5 + 0.5 * ((p - pc) / (1 - pc)).coerceIn(0.0, 1.0)).toFloat()
        else (0.5 * p / pc).toFloat()
    }

    companion object {
        /** Cosines of SigLIP 2 B/32 (measured, see above; chosen on all classes, the halves gave 0.08–0.09, 0.0425 and 0.04–0.06). */
        const val SURE = 0.09f
        const val DROP = 0.0425f
        const val FLOOR = 0.06f
    }
}

/**
 * The library's image embeddings in memory, for scoring every photo against a query at once (videos
 * have one per frame looked at; a video matches as well as its best frame). Rows are added as the
 * index stores them; a media item added again replaces its earlier rows.
 */
class SemanticIndex {
    private var ids = LongArray(1024)
    private var scales = FloatArray(1024)
    private var values = ByteArray(1024 * Embedding.DIM)
    private var dead = BooleanArray(1024)
    private var n = 0
    private val rowsOf = HashMap<Long, IntArray>()

    val size get() = rowsOf.size

    /** Replaces what is known about [mediaId] with [packed] embeddings ([Embedding.pack]); empty forgets it. */
    @Synchronized fun put(mediaId: Long, packed: List<ByteArray>) {
        rowsOf.remove(mediaId)?.forEach { dead[it] = true }
        if (packed.isEmpty()) return
        val rows = IntArray(packed.size)
        for ((k, p) in packed.withIndex()) {
            if (n == ids.size) grow()
            ids[n] = mediaId
            scales[n] = ByteBuffer.wrap(p, 0, 4).order(ByteOrder.BIG_ENDIAN).float
            System.arraycopy(p, 4, values, n * Embedding.DIM, Embedding.DIM)
            dead[n] = false
            rows[k] = n++
        }
        rowsOf[mediaId] = rows
        // Mostly replaced rows: compact, so memory follows the library.
        if (n > 4096 && n > rowsOf.values.sumOf { it.size } * 2) compact()
    }

    @Synchronized fun has(mediaId: Long) = rowsOf.containsKey(mediaId)

    @Synchronized fun mediaIds(): List<Long> = rowsOf.keys.toList()

    /** The cosine of each item's best row with [q] (length 1), for the items in [among] (all if null). */
    @Synchronized fun best(q: FloatArray, among: Collection<Long>? = null): HashMap<Long, Float> {
        val out = HashMap<Long, Float>()
        val rows: Sequence<Int> = if (among == null) (0 until n).asSequence().filter { !dead[it] }
            else among.asSequence().flatMap { (rowsOf[it] ?: IntArray(0)).asSequence() }
        for (r in rows) {
            var s = 0f
            val o = r * Embedding.DIM
            for (i in 0 until Embedding.DIM) s += values[o + i] * q[i]
            val c = s * scales[r]
            val id = ids[r]
            val prev = out[id]
            if (prev == null || c > prev) out[id] = c
        }
        return out
    }

    @Synchronized fun clear() { n = 0; rowsOf.clear() }

    private fun grow() {
        val cap = ids.size * 2
        ids = ids.copyOf(cap); scales = scales.copyOf(cap); dead = dead.copyOf(cap)
        values = values.copyOf(cap * Embedding.DIM)
    }

    private fun compact() {
        var w = 0
        val moved = HashMap<Int, Int>()
        for (r in 0 until n) {
            if (dead[r]) continue
            if (w != r) {
                ids[w] = ids[r]; scales[w] = scales[r]; dead[w] = false
                System.arraycopy(values, r * Embedding.DIM, values, w * Embedding.DIM, Embedding.DIM)
            }
            moved[r] = w; w++
        }
        n = w
        for ((k, rows) in rowsOf) rowsOf[k] = IntArray(rows.size) { moved[rows[it]]!! }
    }
}

/**
 * The search model's token embedding table (search/tokens.emb, buildtools/search/build_search_model.py):
 * magic "LMTE", version, rows, dimension, bits; one float scale per row; then the values (8 bits, or 4
 * bits two per byte, low nibble first, as signed numbers). Read straight from the (memory-mapped) asset.
 */
class TokenTable(buffer: ByteBuffer) {
    private val buf: ByteBuffer = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
    val rows: Int
    val dim: Int
    val bits: Int
    private val scalesAt: Int
    private val valuesAt: Int

    init {
        val b = buf
        require(b.getInt(0) == 0x4C4D5445) { "not a token table" }
        require(b.getInt(4) == 1) { "unknown token table version" }
        rows = b.getInt(8); dim = b.getInt(12); bits = b.getInt(16)
        require(bits == 8 || bits == 4) { "unsupported bits $bits" }
        scalesAt = 20; valuesAt = scalesAt + rows * 4
        require(b.capacity() >= valuesAt + rows.toLong() * dim * bits / 8) { "token table cut short" }
    }

    /** Row [r] into [out] at [offset]. */
    fun row(r: Int, out: FloatArray, offset: Int = 0) {
        require(r in 0 until rows)
        val b = buf
        val scale = b.getFloat(scalesAt + r * 4)
        if (bits == 8) {
            val o = valuesAt + r * dim
            for (i in 0 until dim) out[offset + i] = b.get(o + i) * scale
        } else {
            val o = valuesAt + r * dim / 2
            for (i in 0 until dim / 2) {
                val v = b.get(o + i).toInt()
                val lo = (v shl 28) shr 28; val hi = (v shl 24) shr 28
                out[offset + 2 * i] = lo * scale; out[offset + 2 * i + 1] = hi * scale
            }
        }
    }
}
