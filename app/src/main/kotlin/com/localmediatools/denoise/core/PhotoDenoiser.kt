package com.localmediatools.denoise.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Removes noise from ordinary (8-bit) photos: OpenCV's non-local means on colour pictures (it works
 * in CIELAB, with separate strengths for brightness and colour). The strengths come from the noise
 * measured in the photo itself (brightness and colour separately), by a rule fitted on real phone
 * noise (SIDD) and checked on scenes it wasn't fitted on, and on clean photos full of fine detail,
 * which it must leave alone (buildtools/denoise/README.md).
 */
object PhotoDenoiser {
    /** Template and search window of the filter. */
    const val TEMPLATE = 7
    const val SEARCH = 21
    /** Rows of context above and below a strip so that strips give exactly the whole picture's result. */
    const val MARGIN = TEMPLATE / 2 + SEARCH / 2 + 3

    class Strength(val luma: Float, val color: Float)

    /**
     * σ of CIELAB L and of a and b (OpenCV's 8-bit Lab), from the quietest tenth of the photo's 8×8
     * blocks; [spread]: how far apart the quietest blocks are (10 % over 2 % quantile of their
     * estimates; 1.42 for noise alone).
     */
    class Noise(val luma: Double, val color: Double, val spread: Double = 1.0)

    /**
     * Rule: luma strength = [K] × (σL + 2 σab) / 2 × confidence, colour strength = luma strength.
     * Confidence is 1 when the quietest blocks are as alike as noise makes them (spread ≤ [SPREAD]) and
     * falls as (SPREAD / spread)^[SPREAD_POWER] when they differ: then they hold fine detail (grass,
     * branches, fabric) rather than noise, and smoothing at that level would wipe it out.
     */
    var K = 5.5
    var SPREAD = 1.5
    var SPREAD_POWER = 8.0
    var MAX_H = 24.0

    fun confidence(n: Noise): Double = if (n.spread <= SPREAD) 1.0 else Math.pow(SPREAD / n.spread, SPREAD_POWER)

    fun strengthFor(n: Noise, multiplier: Float = 1f): Strength {
        val h = (K * (n.luma + 2 * n.color) / 2 * confidence(n) * multiplier).coerceIn(0.0, MAX_H)
        return Strength(h.toFloat(), h.toFloat())
    }

    /**
     * Collects the 8×8 blocks of one or more pieces of a photo and measures their noise together (a
     * photo's quiet parts, its sky or walls, may be in any piece). Blocks touching black or white
     * clipping are left out: they show no noise whatever the photo has.
     */
    class Meter {
        private val l = DoubleList(); private val a = DoubleList(); private val b = DoubleList()

        /** Adds a piece of [w]×[h] ARGB pixels (row by row). */
        fun add(argb: IntArray, w: Int, h: Int) {
            if (w < NoiseEstimator.BLOCK || h < NoiseEstimator.BLOCK) return
            val bgr = toBgrMat(argb, w, h)
            val lab = Mat()
            try {
                Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
                val px = ByteArray(w * h * 3)
                lab.get(0, 0, px)
                val n = NoiseEstimator.BLOCK
                val planes = Array(3) { FloatArray(n * n) }
                for (by in 0 until h / n) for (bx in 0 until w / n) {
                    var clipped = false
                    for (y in 0 until n) for (x in 0 until n) {
                        val o = ((by * n + y) * w + bx * n + x) * 3
                        for (c in 0 until 3) planes[c][y * n + x] = (px[o + c].toInt() and 0xFF).toFloat()
                        val lv = planes[0][y * n + x]
                        if (lv >= 254.5f || lv <= 0.5f) clipped = true
                    }
                    if (clipped) continue
                    l.add(NoiseEstimator.blockVariance(planes[0], n, 0, 0))
                    a.add(NoiseEstimator.blockVariance(planes[1], n, 0, 0))
                    b.add(NoiseEstimator.blockVariance(planes[2], n, 0, 0))
                }
            } finally { bgr.release(); lab.release() }
        }

        fun result(): Noise {
            if (l.size < 64) return Noise(0.0, 0.0)
            fun sorted(d: DoubleList) = d.toArray().also { it.sort() }
            val sl = sorted(l); val sa = sorted(a); val sb = sorted(b)
            fun at(v: DoubleArray, q: Double) = v[(q * v.size).toInt()]
            fun sigma(v: DoubleArray) = sqrt(at(v, NoiseEstimator.QUANTILE) * NoiseEstimator.QUANTILE_SCALE)
            val q10 = at(sl, 0.10); val q2 = at(sl, 0.02)
            val spread = if (q10 <= 0.0) 1.0 else q10 / maxOf(q2, 1e-9)
            return Noise(sigma(sl), (sigma(sa) + sigma(sb)) / 2, spread)
        }
    }

    /** Noise of one 8-bit picture (ARGB pixels, row by row). */
    fun measure(argb: IntArray, w: Int, h: Int): Noise = Meter().apply { add(argb, w, h) }.result()

    private class DoubleList {
        private var data = DoubleArray(1024); var size = 0; private set
        fun add(v: Double) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun toArray(): DoubleArray = data.copyOf(size)
    }

    /** OpenCV's channel order (its colour denoiser converts from BGR). */
    fun toBgrMat(argb: IntArray, w: Int, h: Int, rows: Int = h, firstRow: Int = 0): Mat {
        val b = ByteArray(w * rows * 3)
        var o = 0
        for (i in firstRow * w until (firstRow + rows) * w) {
            val c = argb[i]
            b[o++] = c.toByte(); b[o++] = (c shr 8).toByte(); b[o++] = (c shr 16).toByte()
        }
        return Mat(rows, w, CvType.CV_8UC3).also { it.put(0, 0, b) }
    }

    /**
     * Denoises rows [from]..[to] of [argb] ([w] wide, [h] rows held; rows outside [from]..[to] are
     * context) into [out] (([to] − [from]) × [w], alpha kept from the source).
     */
    fun denoiseRows(argb: IntArray, w: Int, h: Int, from: Int, to: Int, s: Strength, out: IntArray) {
        val src = toBgrMat(argb, w, h)
        val dst = Mat()
        try {
            if (s.luma <= 0.05f && s.color <= 0.05f) {
                System.arraycopy(argb, from * w, out, 0, (to - from) * w); return
            }
            Photo.fastNlMeansDenoisingColored(src, dst, max(s.luma, 0.01f), max(s.color, 0.01f), TEMPLATE, SEARCH)
            val b = ByteArray(w * (to - from) * 3)
            dst.get(from, 0, b)
            for (i in 0 until w * (to - from)) {
                val a = argb[from * w + i] and 0xFF000000.toInt()
                out[i] = a or ((b[i * 3 + 2].toInt() and 0xFF) shl 16) or ((b[i * 3 + 1].toInt() and 0xFF) shl 8) or (b[i * 3].toInt() and 0xFF)
            }
        } finally { src.release(); dst.release() }
    }
}
