package com.localmediatools.denoise.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The video denoiser's arithmetic, frame by frame. The GPU shader (video/GlDenoiser.kt) does
 * exactly this per pixel; this CPU version is what the evaluation measures
 * (buildtools/denoise/README.md) and what the tests compare the shader's formulas with.
 *
 * Each output pixel blends the previous output with the current frame. Where the picture stands
 * still, the previous output weighs most, so noise averages out over several frames; where it
 * moves (the 3×3 average brightness differs from the previous output's by more than the noise
 * explains), the current frame takes over, smoothed within the frame by a small edge-preserving
 * filter instead. Scene cuts reset by themselves (everything "moves").
 */
object VideoDenoise {
    /**
     * [still]: share of the current frame where nothing moves (the rest is the previous output).
     * [moveLo]/[moveHi]: the difference, in noise standard deviations, where motion starts / is
     * certain. [range]: the spatial filter's tolerance in noise standard deviations. [spatialStill]
     * / [spatialMoving]: how much of the spatially smoothed frame is used where still / moving.
     */
    class Params(
        val still: Float = 0.25f,
        val moveLo: Float = 2f,
        val moveHi: Float = 5f,
        val range: Float = 3.5f,
        val spatialStill: Float = 0.5f,
        val spatialMoving: Float = 1f,
    )

    /**
     * Measured on clips made of real phone noise (SIDD) over still, panning and moving pictures
     * (buildtools/denoise/README.md): on scenes not used to choose it, 28.3 → 34.3 dB (the first guess
     * 1.5 / 4 / 2.5 / 0.3: 33.5 dB) and less trailing behind moving things (31.6 dB there, the guess 31.3).
     */
    var defaults = Params()

    /**
     * The noise σ the filter works with (share of full brightness), from a few frames measured
     * together as photos are ([PhotoDenoiser.Meter]): brightness noise, lowered as for photos when the
     * quietest blocks look like fine detail rather than noise.
     */
    fun sigma(n: PhotoDenoiser.Noise): Float = (n.luma * PhotoDenoiser.confidence(n) / 255.0).toFloat()

    private fun lum(f: FloatArray, i: Int) = 0.299f * f[3 * i] + 0.587f * f[3 * i + 1] + 0.114f * f[3 * i + 2]

    /** GLSL's smoothstep. */
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /**
     * One frame: [cur] and [prev] (the previous output, or null for the first frame) are RGB in
     * 0..1, row by row; [sigma] is the noise's standard deviation in the same units. Writes [out].
     */
    fun step(cur: FloatArray, prev: FloatArray?, w: Int, h: Int, sigma: Float, p: Params, out: FloatArray) {
        val s = max(sigma, 1e-4f)
        val inv2r2 = 1f / (2f * (p.range * s) * (p.range * s))
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            // Spatial: 3×3 edge-preserving average of the current frame.
            var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
            var boxC = 0f; var boxP = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val j = min(h - 1, max(0, y + dy)) * w + min(w - 1, max(0, x + dx))
                val r = cur[3 * j]; val g = cur[3 * j + 1]; val b = cur[3 * j + 2]
                val dr = r - cur[3 * i]; val dg = g - cur[3 * i + 1]; val db = b - cur[3 * i + 2]
                val wt = exp(-(dr * dr + dg * dg + db * db) * inv2r2)
                sr += wt * r; sg += wt * g; sb += wt * b; sw += wt
                boxC += lum(cur, j)
                if (prev != null) boxP += lum(prev, j)
            }
            val m = if (prev == null) 1f else smoothstep(p.moveLo * s, p.moveHi * s, abs(boxC - boxP) / 9f)
            val k = p.spatialStill + (p.spatialMoving - p.spatialStill) * m
            val a = p.still + (1f - p.still) * m
            for (c in 0 until 3) {
                val sp = (if (c == 0) sr else if (c == 1) sg else sb) / sw
                val cand = cur[3 * i + c] + (sp - cur[3 * i + c]) * k
                out[3 * i + c] = if (prev == null) cand else prev[3 * i + c] + (cand - prev[3 * i + c]) * a
            }
        }
    }
}
