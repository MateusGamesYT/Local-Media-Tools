package com.localmediatools.codec.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pixel helpers for retouching masks (values 0..255 stored in bytes). */
object MaskOps {
    /** Separable box blur, applied twice (≈ Gaussian). Used to feather retouch edges. */
    fun feather(mask: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        if (radius <= 0) return mask.copyOf()
        var a = IntArray(w * h) { mask[it].toInt() and 255 }
        repeat(2) {
            a = boxH(a, w, h, radius)
            a = boxV(a, w, h, radius)
        }
        return ByteArray(w * h) { a[it].coerceIn(0, 255).toByte() }
    }

    private fun boxH(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        val n = 2 * r + 1
        for (y in 0 until h) {
            val b = y * w
            var sum = 0
            for (i in -r..r) sum += src[b + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                out[b + x] = (sum + n / 2) / n
                sum += src[b + (x + r + 1).coerceAtMost(w - 1)] - src[b + (x - r).coerceAtLeast(0)]
            }
        }
        return out
    }

    private fun boxV(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val out = IntArray(w * h)
        val n = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = (sum + n / 2) / n
                sum += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /**
     * Noise level (standard deviation of luma minus its 3×3 mean) over pixels where [hole] is 0 —
     * used to add matching grain to inpainted areas, which otherwise look smoother than the photo.
     */
    fun noiseSigma(argb: IntArray, w: Int, h: Int, hole: ByteArray?): Double {
        if (w < 3 || h < 3) return 0.0
        val luma = IntArray(w * h) { val c = argb[it]; (54 * ((c shr 16) and 255) + 183 * ((c shr 8) and 255) + 19 * (c and 255)) shr 8 }
        val residuals = ArrayList<Int>()
        val step = max(1, ((w.toLong() * h) / 200_000).toInt())
        var i = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (i++ % step != 0) continue
            val p = y * w + x
            if (hole != null && (hole[p].toInt() and 255) > 0) continue
            var s = 0
            for (dy in -1..1) for (dx in -1..1) s += luma[p + dy * w + dx]
            residuals.add(abs(luma[p] * 9 - s))
        }
        if (residuals.size < 50) return 0.0
        residuals.sort()
        // Median absolute residual → robust sigma (excludes edges and texture outliers).
        val med = residuals[residuals.size / 2] / 9.0
        return (med * 1.4826 * 9.0 / sqrt(72.0)).coerceIn(0.0, 12.0)
    }

    /** Adds monochrome Gaussian grain of [sigma] to pixels, weighted by [alpha] (0..255). Deterministic for a [seed]. */
    fun addGrain(argb: IntArray, alpha: ByteArray, sigma: Double, seed: Long) {
        if (sigma < 0.3) return
        val rnd = java.util.Random(seed)
        for (i in argb.indices) {
            val a = alpha[i].toInt() and 255
            if (a == 0) continue
            val n = (rnd.nextGaussian() * sigma * a / 255.0).roundToInt()
            if (n == 0) continue
            val c = argb[i]
            val r = ((c shr 16) and 255) + n; val g = ((c shr 8) and 255) + n; val b = (c and 255) + n
            argb[i] = (c and 0xFF000000.toInt()) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }

    /** Context window for inpainting a hole with bounds [l,t,r,b) in a [imgW]×[imgH] image. */
    data class ContextPlan(val left: Int, val top: Int, val width: Int, val height: Int, val scale: Double) {
        val scaledW: Int get() = max(1, min(MODEL_SIZE, (width * scale).roundToInt()))
        val scaledH: Int get() = max(1, min(MODEL_SIZE, (height * scale).roundToInt()))
    }

    const val MODEL_SIZE = 512

    /**
     * Square-ish window around the hole with room for surrounding context (so the model sees what
     * to continue), kept inside the image. [scale] maps window pixels to model pixels.
     */
    fun contextFor(imgW: Int, imgH: Int, l: Int, t: Int, r: Int, b: Int, model: Int = MODEL_SIZE): ContextPlan {
        val bw = r - l; val bh = b - t
        // At least the model size (or the whole photo when it is smaller), so small holes get full detail.
        val side = max(max(bw, bh) * 1.9 + 48, model.toDouble()).roundToInt()
        val w = min(imgW, max(side, bw + 16)); val h = min(imgH, max(side, bh + 16))
        val cx = (l + r) / 2.0; val cy = (t + b) / 2.0
        val left = (cx - w / 2.0).roundToInt().coerceIn(0, imgW - w)
        val top = (cy - h / 2.0).roundToInt().coerceIn(0, imgH - h)
        return ContextPlan(left, top, w, h, model.toDouble() / max(w, h))
    }
}
