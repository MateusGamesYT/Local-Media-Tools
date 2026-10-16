package com.localmediatools.denoise.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Removes noise from a RAW file's sensor data and leaves it sensor data (nothing is demosaiced,
 * white-balanced or otherwise developed), so the result opens in any RAW editor like the original.
 *
 * The colour filter's four sites (two greens, red, blue) are taken as four half-size planes. For
 * each, the noise is measured from the photo itself as variance = slope × value + offset (shot noise
 * grows with light, read noise doesn't; [NoiseEstimator]). A generalised Anscombe transform makes
 * that noise the same everywhere, non-local means removes it from the four planes together
 * (OpenCV, 16-bit, L1 patch distance), and the exact unbiased inverse (Mäkitalo & Foi 2013) brings
 * the values back without darkening the shadows. Saturated photosites keep their original value, so
 * clipped highlights stay clipped for the RAW editor.
 */
object RawDenoiser {
    /** Measured settings (buildtools/denoise/README.md). [h]: filter strength in noise standard deviations. */
    class Settings(val h: Float = 1.5f, val template: Int = 5, val search: Int = 15)

    /**
     * Measured on real RAW files at seven real high-ISO noise levels (buildtools/denoise/README.md):
     * h = 1.5 was best on the half it was chosen on and on the other half; template 5 / search 15 scored
     * as well as 7 / 21 there and is a fifth faster.
     */
    var defaults = Settings()

    class Result(
        /** Per colour-filter site (row parity × 2 + column parity), or null where the noise couldn't be measured. */
        val models: List<NoiseModel?>,
        /** Typical noise before, as a share of the brightest value. */
        val noiseShare: Double,
        val changed: Boolean,
    )

    /** The four colour-filter sites inside [active], as half-size planes. */
    private class Planes(active: IntArray, private val width: Int) {
        val top = active[0] + (active[0] and 1)
        val left = active[1] + (active[1] and 1)
        val pw = (active[3] - left) / 2
        val ph = (active[2] - top) / 2
        fun at(k: Int, x: Int, y: Int) = (top + 2 * y + (k shr 1)) * width + left + 2 * x + (k and 1)
    }

    /** Saturation of each site (the largest value, when enough photosites sit exactly on it) and its noise. */
    class Measure(val clip: IntArray, val models: List<NoiseModel?>, /** Largest value present. */ val top: Int)

    fun measure(data: ShortArray, width: Int, active: IntArray, maxValue: Int): Measure {
        val p = Planes(active, width)
        if (p.pw < 32 || p.ph < 32) return Measure(IntArray(4) { Int.MAX_VALUE }, List(4) { null }, maxValue)
        var top = 0
        val clip = IntArray(4) { k ->
            var mx = 0; var n = 0
            for (y in 0 until p.ph) for (x in 0 until p.pw) {
                val v = data[p.at(k, x, y)].toInt() and 0xFFFF
                if (v > mx) { mx = v; n = 1 } else if (v == mx) n++
            }
            top = max(top, mx)
            if (n >= max(16, p.pw * p.ph / 20000)) mx else Int.MAX_VALUE
        }
        // One model for the four sites: the same sensor gain and read noise behind each (pooling also
        // makes the fit steadier).
        val b = NoiseEstimator.BLOCK
        val bins = (0 until 4).flatMap { k ->
            NoiseEstimator.bins({ bx, by, out ->
                for (yy in 0 until b) for (xx in 0 until b) out[yy * b + xx] = (data[p.at(k, bx * b + xx, by * b + yy)].toInt() and 0xFFFF).toFloat()
            }, p.pw / b, p.ph / b, if (clip[k] == Int.MAX_VALUE) Float.MAX_VALUE else clip[k] * 0.98f, step = 2)
        }
        val model = NoiseEstimator.fit(bins)?.takeIf { it.variance(top / 2.0) > 1e-6 }
        return Measure(clip, List(4) { model }, top)
    }

    /**
     * Denoises [data] ([width]×[height] photosites) in place, inside [active] (top, left, bottom,
     * right); values stay within 0..[maxValue]. [strength] multiplies the measured setting.
     */
    fun denoise(
        data: ShortArray, width: Int, height: Int, active: IntArray, maxValue: Int,
        strength: Float = 1f, settings: Settings = defaults, progress: (Float) -> Unit = {},
    ): Result {
        val p = Planes(active, width)
        val pw = p.pw; val ph = p.ph
        if (pw < 32 || ph < 32 || p.top + 2 * ph > height) return Result(List(4) { null }, 0.0, false)
        fun at(k: Int, x: Int, y: Int) = p.at(k, x, y)
        val m = measure(data, width, active, maxValue)
        val clip = m.clip; val models = m.models
        progress(0.15f)
        if (models.any { it == null }) return Result(models, 0.0, false)
        val ms = models.map { it!! }
        val noiseShare = ms.map { sqrt(max(it.variance(maxValue * 0.18), 0.0)) / maxValue }.average()

        // Variance-stabilised values, as 16-bit numbers with the noise's standard deviation = scale.
        fun vst(n: NoiseModel, x: Double): Double =
            if (n.slope > 1e-9) 2.0 * sqrt(max(x / n.slope + 0.375 + n.offset / (n.slope * n.slope), 0.0)) else x / sqrt(n.offset)
        val zMax = ms.maxOf { vst(it, m.top.toDouble()) }.coerceAtLeast(1.0)
        val scale = 60000.0 / zMax
        val src = Mat(ph, pw, CvType.CV_16UC4)
        val dst = Mat()
        try {
            val row = ShortArray(pw * 4)
            for (y in 0 until ph) {
                for (x in 0 until pw) for (k in 0 until 4) {
                    val v = (data[at(k, x, y)].toInt() and 0xFFFF).toDouble()
                    row[x * 4 + k] = (vst(ms[k], v) * scale).roundToInt().coerceIn(0, 65535).toShort()
                }
                src.put(y, 0, row)
            }
            progress(0.25f)
            val h = (settings.h * strength * scale).toFloat()
            Photo.fastNlMeansDenoising(src, dst, MatOfFloat(h), settings.template, settings.search, Core.NORM_L1)
            progress(0.9f)
            for (y in 0 until ph) {
                dst.get(y, 0, row)
                for (x in 0 until pw) for (k in 0 until 4) {
                    val i = at(k, x, y)
                    val orig = data[i].toInt() and 0xFFFF
                    if (orig >= clip[k]) continue
                    val d = (row[x * 4 + k].toInt() and 0xFFFF) / scale
                    data[i] = inverse(ms[k], d).roundToInt().coerceIn(0, min(maxValue, clip[k])).toShort()
                }
            }
        } finally { src.release(); dst.release() }
        progress(1f)
        return Result(models, noiseShare, true)
    }

    private val SQRT_3_2 = sqrt(1.5)

    /** Value for stabilised [d]: the exact unbiased inverse (closed form, Mäkitalo & Foi 2013) of the generalised Anscombe transform. */
    fun inverse(m: NoiseModel, d: Double): Double {
        if (m.slope <= 1e-9) return d * sqrt(m.offset)
        val s2 = m.offset / (m.slope * m.slope)
        val y = if (d < 1.0) (d / 2) * (d / 2) - 0.375 - s2
        else d * d / 4 + SQRT_3_2 / (4 * d) - 11.0 / (8 * d * d) + 5 * SQRT_3_2 / (8 * d * d * d) - 0.125 - s2
        return y * m.slope
    }
}
