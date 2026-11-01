package com.localmediatools.video.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A box in a video frame, in fractions of the displayed picture (y down). */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val area get() = max(0f, w) * max(0f, h)
    val x1 get() = x + w
    val y1 get() = y + h

    fun union(o: Box): Box {
        val x0 = min(x, o.x); val y0 = min(y, o.y)
        return Box(x0, y0, max(x1, o.x1) - x0, max(y1, o.y1) - y0)
    }

    fun intersection(o: Box): Float = max(0f, min(x1, o.x1) - max(x, o.x)) * max(0f, min(y1, o.y1) - max(y, o.y))

    fun iou(o: Box): Float { val i = intersection(o); val u = area + o.area - i; return if (u > 0f) i / u else 0f }

    /** Grown by [f] of its own size on each side. */
    fun grown(fx: Float, fy: Float = fx) = Box(x - w * fx, y - h * fy, w * (1 + 2 * fx), h * (1 + 2 * fy))

    fun clipped(): Box {
        val x0 = x.coerceIn(0f, 1f); val y0 = y.coerceIn(0f, 1f)
        return Box(x0, y0, x1.coerceIn(0f, 1f) - x0, y1.coerceIn(0f, 1f) - y0)
    }
}

/** What can be followed, most wanted first. */
enum class SubjectKind(val weight: Float) { PERSON(1f), FACE(1f), ANIMAL(0.8f), THING(0.35f), SALIENT(0.3f) }

/** Something found in a sampled frame. */
class Found(val kind: SubjectKind, val box: Box, val score: Float)

/**
 * One sampled moment of a video: what was found in it, and whether a new shot starts there (then
 * [ms] is the first frame of the new shot, so the crop changes exactly at the cut).
 */
class ReframeSample(val ms: Long, val found: List<Found>, val cut: Boolean = false)

/** Where the picture is cropped at a moment of shot [shot], in fractions of the displayed picture. */
data class Key(val ms: Long, val crop: Box, val shot: Int)

/**
 * Auto reframe: a video turned into another shape (a landscape video into a portrait one) or zoomed
 * in, keeping its subject in the picture, the way a camera operator would. From the faces, people,
 * animals and things found in frames sampled through the video ([ReframeSample]):
 *
 *  1. the subject of each moment ([subjects]): the most prominent person (or animal, or thing),
 *     followed from moment to moment rather than jumping to whoever is biggest, with the people next to
 *     them when they fit in the picture together;
 *  2. a camera path for each shot ([path]): still when the subject fits in one crop for the whole
 *     shot, otherwise a smooth path (least squares: close to the subject, little speed, little change of
 *     speed) that is then pulled back wherever the subject would leave the picture; cuts start a new
 *     path instead of panning across them;
 *     When a person is too wide for the picture (close to the camera), their head stays in, as near
 *     their middle as that allows;
 *  3. with [Options.zoom], a closer crop for the shot so the subject fills more of the picture, at most
 *     [MAX_ZOOM] times (beyond that the picture gets soft).
 *
 * The settings were measured on real videos of people (buildtools/video/reframe_eval.py).
 */
object Reframe {
    /** How far in the crop may be (2× = half the width and height of the widest crop). */
    const val MAX_ZOOM = 2f
    /** With zoom: the subject's height as a share of the crop's. */
    const val FILL = 0.6f

    class Params(
        /** Weight of speed and of change of speed against staying on the subject (per second², per second⁴). */
        val speed: Double = 0.6, val accel: Double = 0.4,
        /** Room kept around the subject inside the crop (fraction of the subject's size). */
        val margin: Float = 0.08f,
        /** A subject missing for this long is let go (the camera eases to where it last was). */
        val holdMs: Long = 1200,
        /** Continuity: how much more a subject overlapping the last one counts. */
        val stick: Float = 2f,
        /** A subject wider than the picture: keep its head (the faces found in it) in, rather than its middle. */
        val heads: Boolean = true,
        /** How much less a subject at the picture's edge counts than one in the middle (0: no difference, 1: none at the edge). */
        val centre: Float = 0.5f,
    )

    var DEFAULT = Params()

    class Options(
        /** The output's width over its height. */
        val outAspect: Float,
        /** 0: the widest crop of [outAspect]; 1: zoom in so the subject fills [FILL] of the picture. */
        val zoom: Float = 0f,
    )

    /** The part of each sample to keep in the picture (null: nothing found to follow). */
    fun subjects(samples: List<ReframeSample>, srcAspect: Float, outAspect: Float, p: Params = DEFAULT): List<Box?> {
        val out = ArrayList<Box?>(samples.size)
        var last: Box? = null
        var lastMs = Long.MIN_VALUE
        // The widest crop's width (fraction of the source): who fits next to the subject.
        val cropW = min(1f, outAspect / srcAspect)
        for (s in samples) {
            if (s.cut) last = null
            val cands = candidates(s.found)
            if (cands.isEmpty()) {
                val held = last?.takeIf { s.ms - lastMs <= p.holdMs }
                out.add(held); continue
            }
            fun score(c: Found): Float {
                val centre = 1f - p.centre * min(1f, sqrt((c.box.cx - 0.5f).let { it * it } + (c.box.cy - 0.5f).let { it * it }) * 2f)
                val cont = last?.let { l -> 1f + p.stick * max(c.box.iou(l), closeness(c.box, l)) } ?: 1f
                return c.kind.weight * c.score * sqrt(c.box.area) * centre * cont
            }
            val best = cands.maxBy { score(it) }
            var region = best.box
            // People (or animals) next to the subject and nearly as prominent come along when they fit.
            val bs = score(best)
            for (c in cands) {
                if (c === best || c.kind != best.kind) continue
                if (score(c) < 0.6f * bs) continue
                val u = region.union(c.box)
                if (u.w <= cropW * 0.9f) region = u
            }
            out.add(region)
            last = region; lastMs = s.ms
        }
        return out
    }

    /** People with their faces folded in, faces without a body (close-ups) as head and shoulders, and the rest. */
    private fun candidates(found: List<Found>): List<Found> {
        val people = found.filter { it.kind == SubjectKind.PERSON }
        val out = ArrayList<Found>()
        for (pp in people) out.add(pp)
        for (f in found.filter { it.kind == SubjectKind.FACE }) {
            if (people.any { it.box.intersection(f.box) >= 0.7f * f.box.area }) continue
            // A face alone: keep the head and shoulders.
            val b = Box(f.box.x - f.box.w * 0.6f, f.box.y - f.box.h * 0.3f, f.box.w * 2.2f, f.box.h * 2.6f).clipped()
            out.add(Found(SubjectKind.FACE, b, f.score))
        }
        if (out.isEmpty()) out.addAll(found.filter { it.kind == SubjectKind.ANIMAL })
        if (out.isEmpty()) out.addAll(found.filter { it.kind == SubjectKind.THING && it.box.area < 0.8f })
        if (out.isEmpty()) out.addAll(found.filter { it.kind == SubjectKind.SALIENT })
        return out
    }

    /** 1 when the centres coincide, 0 a quarter of the picture apart. */
    private fun closeness(a: Box, b: Box): Float {
        val d = sqrt((a.cx - b.cx).let { it * it } + (a.cy - b.cy).let { it * it })
        return max(0f, 1f - d * 4f)
    }

    /**
     * The crop at each sample ([Key]s; [at] interpolates between them), for a source of shape
     * [srcAspect] (width over height) shown as [Options.outAspect].
     */
    fun path(samples: List<ReframeSample>, subjects: List<Box?>, srcAspect: Float, o: Options, p: Params = DEFAULT): List<Key> {
        require(samples.size == subjects.size)
        if (samples.isEmpty()) return emptyList()
        // The widest crop of the output's shape (fractions of the source).
        val baseW = min(1f, o.outAspect / srcAspect)
        val baseH = min(1f, srcAspect / o.outAspect)
        val keys = ArrayList<Key>(samples.size)
        val heads = if (p.heads) heads(samples, subjects, p) else List(samples.size) { null }
        var start = 0; var shot = 0
        while (start < samples.size) {
            var end = start + 1
            while (end < samples.size && !samples[end].cut) end++
            shot(samples, subjects, heads, start, end, baseW, baseH, o, p, keys, shot++)
            start = end
        }
        return keys
    }

    /**
     * The head of each sample's subject: the faces found inside it, widened to the whole head (null when
     * none). A face missed for a moment is taken to be where it last was, while that is in the subject.
     */
    internal fun heads(samples: List<ReframeSample>, subjects: List<Box?>, p: Params = DEFAULT): List<Box?> {
        val out = ArrayList<Box?>(samples.size)
        var last: Box? = null; var lastMs = Long.MIN_VALUE
        for ((i, s) in samples.withIndex()) {
            if (s.cut) last = null
            val sub = subjects[i]
            if (sub == null) { out.add(null); continue }
            var head: Box? = null
            for (f in s.found) {
                if (f.kind != SubjectKind.FACE || f.box.intersection(sub) < 0.7f * f.box.area) continue
                val h = Box(f.box.x - f.box.w * 0.3f, f.box.y - f.box.h * 0.35f, f.box.w * 1.6f, f.box.h * 1.45f).clipped()
                head = head?.union(h) ?: h
            }
            if (head != null) { last = head; lastMs = s.ms }
            else if (last != null && s.ms - lastMs <= p.holdMs && last.intersection(sub) >= 0.7f * last.area) head = last
            out.add(head)
        }
        return out
    }

    private fun shot(samples: List<ReframeSample>, subjects: List<Box?>, heads: List<Box?>, a: Int, b: Int, baseW: Float, baseH: Float, o: Options, p: Params, out: MutableList<Key>, shot: Int) {
        val subs = subjects.subList(a, b)
        val known = subs.filterNotNull()
        // Zoom, once for the shot (a crop that keeps changing size is tiring to watch).
        var zoom = 1f
        if (o.zoom > 0f && known.isNotEmpty()) {
            val hs = known.map { it.h }.sorted()
            val tall = hs[(hs.size * 0.8).toInt().coerceAtMost(hs.size - 1)]
            val want = (baseH * FILL / max(tall, 1e-3f)).coerceIn(1f, MAX_ZOOM)
            zoom = 1f + (want - 1f) * o.zoom.coerceIn(0f, 1f)
            // Never so close that the subject can't fit across.
            val wide = known.maxOf { it.w } * (1 + 2 * p.margin)
            zoom = min(zoom, max(1f, baseW / max(wide, 1e-3f)))
        }
        val w = baseW / zoom; val h = baseH / zoom
        val n = b - a
        // Where the crop's centre would put each subject in the middle (null: free).
        val wantX = DoubleArray(n) { i -> subs[i]?.cx?.toDouble() ?: Double.NaN }
        val wantY = DoubleArray(n) { i -> subs[i]?.let { s -> (s.y + min(s.h, h * 0.9f) * 0.45f).toDouble() } ?: Double.NaN }
        val xs = axis(samples, a, n, wantX, subs, w, true, p, heads.subList(a, b))
        val ys = axis(samples, a, n, wantY, subs, h, false, p)
        for (i in 0 until n) {
            val cx = xs[i].toFloat().coerceIn(w / 2, 1 - w / 2); val cy = ys[i].toFloat().coerceIn(h / 2, 1 - h / 2)
            out.add(Key(samples[a + i].ms, Box(cx - w / 2, cy - h / 2, w, h), shot))
        }
    }

    /** One axis of the camera's path: still if one position keeps every subject in, else smoothed and pulled back where needed. */
    private fun axis(samples: List<ReframeSample>, a: Int, n: Int, want: DoubleArray, subs: List<Box?>, size: Float, horizontal: Boolean, p: Params,
                     heads: List<Box?>? = null): DoubleArray {
        val half = size / 2.0
        val lo = half; val hi = 1.0 - half
        if (hi <= lo + 1e-6) return DoubleArray(n) { 0.5 }
        // The range of centres that keeps each subject (with margin) in, where it fits.
        val minC = DoubleArray(n) { Double.NaN }; val maxC = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            val s = subs[i] ?: continue
            val s0 = if (horizontal) s.x - s.w * p.margin else s.y - s.h * p.margin
            val s1 = if (horizontal) s.x1 + s.w * p.margin else min(s.y1 + s.h * p.margin, s.y + size * 0.95f)
            val cMin = s1 - half; val cMax = s0 + half
            val head = heads?.get(i)
            if (cMin <= cMax) { minC[i] = max(lo, cMin); maxC[i] = min(hi, cMax) }
            else if (head != null) {
                // Too wide to fit: the head stays in, the crop as near the subject's middle as that allows.
                val hMin = head.x1 + head.w * p.margin - half; val hMax = head.x - head.w * p.margin + half
                if (hMin <= hMax) { minC[i] = max(lo, hMin).coerceAtMost(hi); maxC[i] = min(hi, hMax).coerceAtLeast(minC[i]); want[i] = want[i].coerceIn(minC[i], maxC[i]) }
                else { val c = head.cx.toDouble().coerceIn(lo, hi); minC[i] = c; maxC[i] = c; want[i] = c }
            }
            else { val c = (want[i]).coerceIn(lo, hi); minC[i] = c; maxC[i] = c }
        }
        // Still camera: one position inside every range.
        var still = true
        var low = lo; var high = hi
        var any = false
        for (i in 0 until n) if (!minC[i].isNaN()) { any = true; low = max(low, minC[i]); high = min(high, maxC[i]) }
        if (!any) return DoubleArray(n) { 0.5.coerceIn(lo, hi) }
        if (low > high) still = false
        if (still) {
            val mid = want.filter { !it.isNaN() }.average().coerceIn(low, high)
            return DoubleArray(n) { mid }
        }
        // Moving: least squares with a pull towards each subject, then towards its range where it leaves it.
        val dt = DoubleArray(n) { i -> if (i == 0) 0.2 else ((samples[a + i].ms - samples[a + i - 1].ms) / 1000.0).coerceAtLeast(0.02) }
        // Where nothing is followed, the camera waits where the nearest subject (in time) is, pulled
        // there more lightly (left free, the smoothing would carry it on to an edge).
        val near = nearest(want)
        val wt = DoubleArray(n) { i -> if (want[i].isNaN()) (if (near[i].isNaN()) 0.0 else UNKNOWN_WEIGHT) else 1.0 }
        val target = DoubleArray(n) { i -> (if (want[i].isNaN()) (if (near[i].isNaN()) 0.5 else near[i]) else want[i]).coerceIn(lo, hi) }
        var x = solve(target, wt, dt, p)
        repeat(6) {
            var changed = false
            for (i in 0 until n) {
                if (minC[i].isNaN()) continue
                if (x[i] < minC[i] - 1e-4) { target[i] = minC[i]; wt[i] *= 8; changed = true }
                else if (x[i] > maxC[i] + 1e-4) { target[i] = maxC[i]; wt[i] *= 8; changed = true }
            }
            if (!changed) return x.map { it.coerceIn(lo, hi) }.toDoubleArray()
            x = solve(target, wt, dt, p)
        }
        return x.map { it.coerceIn(lo, hi) }.toDoubleArray()
    }

    /** Weight of the pull towards the nearest subject where none is found. */
    private const val UNKNOWN_WEIGHT = 0.25

    /** For each position, the nearest known value (NaN where all are unknown). */
    private fun nearest(v: DoubleArray): DoubleArray {
        val n = v.size
        val out = DoubleArray(n) { Double.NaN }
        var last = Double.NaN; var lastAt = -1
        val fwd = DoubleArray(n); val fwdAt = IntArray(n)
        for (i in 0 until n) { if (!v[i].isNaN()) { last = v[i]; lastAt = i }; fwd[i] = last; fwdAt[i] = lastAt }
        last = Double.NaN; lastAt = -1
        for (i in n - 1 downTo 0) {
            if (!v[i].isNaN()) { last = v[i]; lastAt = i }
            out[i] = when {
                !v[i].isNaN() -> v[i]
                fwdAt[i] < 0 -> last
                lastAt < 0 -> fwd[i]
                i - fwdAt[i] <= lastAt - i -> fwd[i]
                else -> last
            }
        }
        return out
    }

    /**
     * Minimises Σ wᵢ(xᵢ − dᵢ)² + speed·Σ((xᵢ₊₁ − xᵢ)/Δt)²·Δt + accel·Σ(second differences/Δt²)²·Δt,
     * a pentadiagonal system solved directly (dense banded elimination).
     */
    internal fun solve(d: DoubleArray, w: DoubleArray, dt: DoubleArray, p: Params): DoubleArray {
        val n = d.size
        if (n == 1) return doubleArrayOf((w[0] * d[0] + 1e-6 * 0.5) / (w[0] + 1e-6))
        // Banded symmetric matrix: band[k][i] = A[i][i+k], k = 0..2.
        val a0 = DoubleArray(n); val a1 = DoubleArray(n); val a2 = DoubleArray(n)
        val rhs = DoubleArray(n)
        for (i in 0 until n) { a0[i] += w[i] + 1e-6; rhs[i] += w[i] * d[i] + 1e-6 * 0.5 }
        for (i in 0 until n - 1) {
            val h = dt[i + 1]
            val c = p.speed / h
            a0[i] += c; a0[i + 1] += c; a1[i] -= c
        }
        for (i in 1 until n - 1) {
            val h = (dt[i] + dt[i + 1]) / 2
            val c = p.accel / (h * h * h)
            // (x[i-1] − 2x[i] + x[i+1])² terms.
            a0[i - 1] += c; a0[i] += 4 * c; a0[i + 1] += c
            a1[i - 1] -= 2 * c; a1[i] -= 2 * c
            a2[i - 1] += c
        }
        // Gaussian elimination on the band (symmetric positive definite: no pivoting needed).
        val m0 = a0.copyOf(); val m1 = a1.copyOf(); val m2 = a2.copyOf(); val r = rhs.copyOf()
        // Lower part mirrors the upper: l1[i] = A[i+1][i] = m1[i], l2[i] = A[i+2][i] = m2[i].
        val l1 = a1.copyOf(); val l2 = a2.copyOf()
        for (i in 0 until n) {
            val piv = m0[i]
            if (i + 1 < n) {
                val f = l1[i] / piv
                m0[i + 1] -= f * m1[i]
                if (i + 2 < n) m1[i + 1] -= f * m2[i]
                r[i + 1] -= f * r[i]
                if (i + 2 < n) l1[i + 1] -= f * m2[i]
            }
            if (i + 2 < n) {
                val f = l2[i] / piv
                m0[i + 2] -= f * m2[i]
                r[i + 2] -= f * r[i]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = r[i]
            if (i + 1 < n) s -= m1[i] * x[i + 1]
            if (i + 2 < n) s -= m2[i] * x[i + 2]
            x[i] = s / m0[i]
        }
        return x
    }

    /** The crop at [ms]: between the keys around it; at a cut, the new shot's crop from its first frame. */
    fun at(keys: List<Key>, ms: Long): Box {
        if (keys.isEmpty()) return Box(0f, 0f, 1f, 1f)
        if (ms <= keys.first().ms) return keys.first().crop
        if (ms >= keys.last().ms) return keys.last().crop
        var lo = 0; var hi = keys.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (keys[mid].ms <= ms) lo = mid else hi = mid }
        val k0 = keys[lo]; val k1 = keys[hi]
        if (k0.shot != k1.shot) return k0.crop
        val t = ((ms - k0.ms).toFloat() / max(1L, k1.ms - k0.ms))
        return Box(k0.crop.x + (k1.crop.x - k0.crop.x) * t, k0.crop.y + (k1.crop.y - k0.crop.y) * t, k0.crop.w, k0.crop.h)
    }
}
