package com.localmediatools.denoise.core

import java.util.Random
import kotlin.math.sqrt

/** Noise of one sensor channel: variance = slope × value + offset (shot noise plus read noise). */
data class NoiseModel(val slope: Double, val offset: Double) {
    fun variance(x: Double): Double = slope * x + offset
}

/**
 * Measures noise from a picture alone. Each 8×8 block gives its mean and a high-pass estimate of
 * its noise variance (Immerkær's operator, which a smooth picture doesn't trigger). Textured blocks
 * read too high, so within each band of brightness a low quantile of the blocks' estimates is
 * taken and scaled by what that quantile is for pure noise ([QUANTILE_SCALE], computed from noise
 * itself, not tuned).
 */
object NoiseEstimator {
    const val BLOCK = 8
    const val QUANTILE = 0.10

    /** Ratio of the mean to the [QUANTILE] of the block estimate on pure Gaussian noise. */
    val QUANTILE_SCALE: Double by lazy {
        val rnd = Random(1234)
        val n = 20000
        val est = DoubleArray(n) { blockVariance(FloatArray(BLOCK * BLOCK) { rnd.nextGaussian().toFloat() }, BLOCK, 0, 0) }
        est.sort()
        est.average() / est[(QUANTILE * n).toInt()]
    }

    /** Noise variance in one block of [p] (row length [stride]) from its 6×6 inner high-pass values. */
    fun blockVariance(p: FloatArray, stride: Int, x0: Int, y0: Int): Double {
        var s = 0.0
        for (y in y0 + 1 until y0 + BLOCK - 1) {
            val o = y * stride
            for (x in x0 + 1 until x0 + BLOCK - 1) {
                val r = p[o - stride + x - 1] - 2 * p[o - stride + x] + p[o - stride + x + 1] -
                    2 * p[o + x - 1] + 4 * p[o + x] - 2 * p[o + x + 1] +
                    p[o + stride + x - 1] - 2 * p[o + stride + x] + p[o + stride + x + 1]
                s += r.toDouble() * r
            }
        }
        return s / ((BLOCK - 2) * (BLOCK - 2)) / 36.0
    }

    class Bin(val mean: Double, val variance: Double)

    /** Reads block ([bx], [by]) of a plane into [out] (row by row, [BLOCK]×[BLOCK]). */
    fun interface Blocks { fun read(bx: Int, by: Int, out: FloatArray) }

    /**
     * Brightness bands of a plane of [across]×[down] blocks and their noise variance; blocks
     * touching values ≥ [clip] are left out. [step] reads every step-th block row (for speed).
     */
    fun bins(blocks: Blocks, across: Int, down: Int, clip: Float, bands: Int = 32, step: Int = 1): List<Bin> {
        val means = ArrayList<Double>(); val vars = ArrayList<Double>()
        val buf = FloatArray(BLOCK * BLOCK)
        var by = 0
        while (by < down) {
            for (bx in 0 until across) {
                blocks.read(bx, by, buf)
                var sum = 0.0; var clipped = false
                for (v in buf) { if (v >= clip) clipped = true; sum += v }
                if (!clipped) { means.add(sum / buf.size); vars.add(blockVariance(buf, BLOCK, 0, 0)) }
            }
            by += step
        }
        val n = means.size
        if (n < 64) return emptyList()
        val order = (0 until n).sortedBy { means[it] }
        val nb = minOf(bands, n / 32).coerceAtLeast(1)
        val out = ArrayList<Bin>()
        for (b in 0 until nb) {
            val from = b * n / nb; val to = (b + 1) * n / nb
            val idx = order.subList(from, to)
            val v = idx.map { vars[it] }.sorted()
            val q = v[(QUANTILE * v.size).toInt().coerceAtMost(v.size - 1)] * QUANTILE_SCALE
            out.add(Bin(idx.sumOf { means[it] } / idx.size, q))
        }
        return out
    }

    /** [bins] of a whole plane held in [p] ([w]×[h], row by row). */
    fun bins(p: FloatArray, w: Int, h: Int, clip: Float, bands: Int = 32): List<Bin> =
        bins({ bx, by, out -> for (y in 0 until BLOCK) System.arraycopy(p, (by * BLOCK + y) * w + bx * BLOCK, out, y * BLOCK, BLOCK) }, w / BLOCK, h / BLOCK, clip, bands)

    /**
     * variance = slope × value + offset through [bins], fitted to their lower edge: texture only ever
     * adds to a band's estimate, so bands well above the line (25 %) are set aside and the line refitted
     * (relative least squares, slope ≥ 0). The line may not fall below the darkest bands' own level, where
     * the read noise lives (a straight fit through bright, textured bands can reach zero there).
     * Measured against known noise on real RAW files (buildtools/denoise/README.md).
     */
    fun fit(bins: List<Bin>): NoiseModel? {
        if (bins.isEmpty()) return null
        if (bins.size < 3) return NoiseModel(0.0, bins.map { it.variance }.average())
        val x = DoubleArray(bins.size) { bins[it].mean }; val y = DoubleArray(bins.size) { bins[it].variance }
        val low = y.sorted()[(0.3 * (y.size - 1)).toInt()]
        var keep = BooleanArray(y.size) { true }
        var slope = 0.0; var offset = y.sorted()[y.size / 2]
        for (iter in 0 until 6) {
            var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (i in y.indices) if (keep[i]) {
                val w = 1.0 / maxOf(y[i], 1e-9).let { it * it }
                sw += w; sx += w * x[i]; sy += w * y[i]; sxx += w * x[i] * x[i]; sxy += w * x[i] * y[i]
            }
            val det = sw * sxx - sx * sx
            if (det > 0) { slope = (sw * sxy - sx * sy) / det; offset = (sy - slope * sx) / sw } else { slope = 0.0; offset = sy / sw }
            if (slope < 0) { slope = 0.0; offset = y.filterIndexed { i, _ -> keep[i] }.sorted().let { it[it.size / 2] } }
            val next = BooleanArray(y.size) { i -> y[i] <= 1.25 * maxOf(slope * x[i] + offset, 1e-9) || y[i] <= low }
            if (next.count { it } < maxOf(3, (0.3 * y.size).toInt()) || next.contentEquals(keep)) break
            keep = next
        }
        val order = x.indices.sortedBy { x[it] }
        val dark = order[0]
        if (slope * x[dark] + offset < 0.7 * y[dark]) offset = order.take(3).minOf { y[it] } - slope * x[dark]
        return NoiseModel(slope, offset)
    }

    /** Standard deviation of the noise for a plane whose noise doesn't depend on brightness (8-bit photos). */
    fun sigma(p: FloatArray, w: Int, h: Int): Double {
        val b = bins(p, w, h, Float.MAX_VALUE, bands = 1)
        return if (b.isEmpty()) 0.0 else sqrt(b[0].variance)
    }
}
