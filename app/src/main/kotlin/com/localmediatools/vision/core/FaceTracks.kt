package com.localmediatools.vision.core

import kotlin.math.max
import kotlin.math.min

/** A face seen at one moment: box as fractions (0..1) of the display-oriented frame. */
class FaceSample(
    val tUs: Long,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val score: Float,
    val feature: FloatArray?,
) {
    val area get() = w * h
    fun iou(o: FaceSample): Float {
        val ix = max(0f, min(x + w, o.x + o.w) - max(x, o.x))
        val iy = max(0f, min(y + h, o.y + o.h) - max(y, o.y))
        val inter = ix * iy
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/**
 * One face followed through consecutive samples of one photo or video ([source]). A face in a
 * photo ([still]) is a track with a single sample.
 */
class FaceTrack(val id: Int, val source: Int = 0, val still: Boolean = false) {
    val samples = ArrayList<FaceSample>()
    val start get() = samples.first().tUs
    val end get() = samples.last().tUs
    val last get() = samples.last()

    /** Mean of the (normalised) features, renormalised; null when no sample has one. */
    fun meanFeature(): FloatArray? {
        val fs = samples.mapNotNull { it.feature }
        if (fs.isEmpty()) return null
        val m = FloatArray(fs[0].size)
        for (f in fs) for (i in m.indices) m[i] += f[i]
        return FaceEngine.normalize(m)
    }
}

/** A person: one or more tracks that the face embeddings say belong together. */
class FaceIdentity(val id: Int, val tracks: List<FaceTrack>) {
    /** The sample with the biggest face (for the thumbnail). */
    val best: FaceSample get() = tracks.flatMap { it.samples }.maxByOrNull { it.area * it.score }!!
    val visibleUs: Long get() = tracks.sumOf { (it.end - it.start).coerceAtLeast(0) + 100_000L }
    val sampleCount get() = tracks.sumOf { it.samples.size }
}

/** An ellipse to blur, as fractions of the frame: centre and radii. */
data class BlurRegion(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/**
 * Links per-frame detections into tracks (box overlap, helped by embeddings) and groups tracks
 * into people (embedding similarity, never merging two faces seen in the same frame).
 */
class FaceTracker(private val maxGapUs: Long = 800_000, private val minIou: Float = 0.2f, private val source: Int = 0) {
    val tracks = ArrayList<FaceTrack>()
    private var nextId = 0

    fun add(tUs: Long, faces: List<FaceSample>) {
        val active = tracks.filter { tUs - it.end <= maxGapUs && it.end < tUs }.toMutableList()
        for (f in faces.sortedByDescending { it.score * it.area }) {
            var best: FaceTrack? = null
            var bestScore = 0f
            for (t in active) {
                val l = t.last
                val iou = f.iou(l)
                // Close centres also count (fast motion at low sampling rates).
                val dx = (f.x + f.w / 2) - (l.x + l.w / 2); val dy = (f.y + f.h / 2) - (l.y + l.h / 2)
                val near = dx * dx + dy * dy < (max(f.w, l.w) * 0.9f).let { it * it }
                val sim = if (f.feature != null && l.feature != null) FaceEngine.cosine(f.feature, l.feature) else 0.5f
                if ((iou >= minIou || near) && sim > 0.2f) {
                    val s = iou + 0.5f * sim
                    if (s > bestScore) { bestScore = s; best = t }
                }
            }
            val t = best ?: FaceTrack(nextId++, source).also { tracks.add(it) }
            t.samples.add(f)
            active.remove(t)
        }
    }

    /** Groups tracks into people. Single-sample tracks with weak detections are dropped as noise. */
    fun identities(threshold: Float = FaceEngine.SAME_PERSON): List<FaceIdentity> = cluster(tracks, threshold)

    companion object {
        /**
         * Groups tracks from any number of photos and videos into people: tracks are merged while
         * their mean embeddings are similar enough, but never two faces seen at the same moment of
         * the same video or in the same photo. Brief, weak video detections are dropped as noise.
         */
        fun cluster(all: List<FaceTrack>, threshold: Float = FaceEngine.SAME_PERSON): List<FaceIdentity> {
            val kept = all.filter { it.still || it.samples.size >= 2 || it.samples[0].score >= 0.8f }
            val n = kept.size
            val members = Array(n) { mutableListOf(kept[it]) }
            val moments = Array(n) { i -> kept[i].samples.mapTo(HashSet()) { kept[i].source.toLong() * 1_000_000_000_000L + it.tUs } }
            val feats = Array(n) { kept[it].meanFeature() }
            val alive = BooleanArray(n) { true }
            // Pairwise similarity (upper triangle); -inf = can't be merged.
            val sim = Array(n) { FloatArray(n) { Float.NEGATIVE_INFINITY } }
            fun score(i: Int, j: Int): Float { val a = feats[i]; val b = feats[j]; return if (a == null || b == null) Float.NEGATIVE_INFINITY else FaceEngine.cosine(a, b) }
            for (i in 0 until n) for (j in i + 1 until n) sim[i][j] = score(i, j)
            while (true) {
                var bi = -1; var bj = -1; var bs = threshold
                for (i in 0 until n) {
                    if (!alive[i]) continue
                    val row = sim[i]
                    for (j in i + 1 until n) if (alive[j] && row[j] >= bs) { bs = row[j]; bi = i; bj = j }
                }
                if (bi < 0) break
                // Two faces seen at the same moment are different people, whatever the model says.
                if (moments[bj].any { it in moments[bi] }) { sim[bi][bj] = Float.NEGATIVE_INFINITY; continue }
                members[bi].addAll(members[bj]); moments[bi].addAll(moments[bj]); alive[bj] = false
                feats[bi] = FaceTrack(-1).apply { members[bi].forEach { samples.addAll(it.samples) } }.meanFeature()
                for (k in 0 until n) if (alive[k] && k != bi) { if (k < bi) sim[k][bi] = score(k, bi) else sim[bi][k] = score(bi, k) }
            }
            val clusters = (0 until n).filter { alive[it] }.map { members[it] }
            return clusters.mapIndexed { i, c -> FaceIdentity(i, c.sortedWith(compareBy({ it.source }, { it.start }))) }
                .sortedByDescending { it.sampleCount }
                .mapIndexed { i, idn -> FaceIdentity(i, idn.tracks) }
        }

        /** [trackRegionsAt] for every track of [identities]. */
        fun regionsAt(identities: List<FaceIdentity>, tUs: Long, holdUs: Long = 400_000, grow: Float = 1.45f): List<BlurRegion> =
            trackRegionsAt(identities.flatMap { it.tracks }, tUs, holdUs, grow)

        /**
         * Blur ellipses for [tracks] at time [tUs]: boxes are interpolated between samples and held
         * for [holdUs] before/after a track, grown by [grow] to cover hair and fast motion.
         */
        fun trackRegionsAt(tracks: List<FaceTrack>, tUs: Long, holdUs: Long = 400_000, grow: Float = 1.45f): List<BlurRegion> {
            val out = ArrayList<BlurRegion>()
            for (t in tracks) {
                if (tUs < t.start - holdUs || tUs > t.end + holdUs) continue
                val s = t.samples
                val k = s.indexOfFirst { it.tUs >= tUs }
                val (x, y, w, h) = when {
                    k < 0 -> s.last().let { floatArrayOf(it.x, it.y, it.w, it.h) }
                    k == 0 -> s[0].let { floatArrayOf(it.x, it.y, it.w, it.h) }
                    else -> {
                        val a = s[k - 1]; val b = s[k]
                        val f = ((tUs - a.tUs).toFloat() / (b.tUs - a.tUs).coerceAtLeast(1)).coerceIn(0f, 1f)
                        floatArrayOf(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.w + (b.w - a.w) * f, a.h + (b.h - a.h) * f)
                    }
                }
                // Faster motion between samples → a bit more margin.
                val speed = if (k > 0 && k < s.size) { val a = s[k - 1]; val b = s[k]; kotlin.math.abs((b.x + b.w / 2) - (a.x + a.w / 2)) + kotlin.math.abs((b.y + b.h / 2) - (a.y + a.h / 2)) } else 0f
                val g = grow + speed * 2f
                out.add(BlurRegion(x + w / 2, y + h / 2 - h * 0.08f, w / 2 * g, h / 2 * g * 1.12f))
            }
            return out
        }
    }

}
