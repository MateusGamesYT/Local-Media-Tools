package com.localmediatools.codec.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One adjustment slider. Values are -1..1 (sharpness 0..1 sharpens, below 0 softens). */
enum class AdjustKey(val label: String) {
    EXPOSURE("Exposure"), BRIGHTNESS("Brightness"), CONTRAST("Contrast"), HIGHLIGHTS("Highlights"),
    SHADOWS("Shadows"), SATURATION("Saturation"), VIBRANCE("Vibrance"), WARMTH("Warmth"), TINT("Tint"),
    SHARPNESS("Sharpness"), VIGNETTE("Vignette");
}

data class Adjustments(val values: Map<AdjustKey, Float> = emptyMap()) {
    operator fun get(k: AdjustKey): Float = values[k] ?: 0f
    fun with(k: AdjustKey, v: Float): Adjustments {
        val m = values.toMutableMap()
        val c = v.coerceIn(-1f, 1f)
        if (abs(c) < 1e-4f) m.remove(k) else m[k] = c
        return Adjustments(m)
    }
    val isIdentity: Boolean get() = values.values.all { abs(it) < 1e-4f }
}

/**
 * Look presets. Each one is a set of per-channel tone curves plus a saturation factor, applied
 * after the user's adjustments and blended by the filter intensity.
 */
enum class FilterPreset(val label: String, val saturation: Float = 1f) {
    NONE("Original"),
    VIVID("Vivid", 1.28f),
    GOLDEN("Golden", 1.08f),
    NORDIC("Nordic", 0.88f),
    MATTE("Matte", 0.86f),
    VINTAGE("Vintage", 0.78f),
    CINEMA("Cinema", 0.92f),
    DRAMA("Drama", 0.9f),
    MONO("Mono", 0f),
    NOIR("Noir", 0f),
    SEPIA("Sepia", 0f);

    /** Output 0..1 of channel [ch] (0 = R, 1 = G, 2 = B) for input [x] in 0..1. */
    fun curve(ch: Int, x: Double): Double = when (this) {
        NONE -> x
        VIVID -> sCurve(x, 0.18)
        GOLDEN -> when (ch) { 0 -> lift(sCurve(x, 0.08), 0.02, 1.0) * 1.0 + 0.04 * hump(x); 1 -> x + 0.015 * hump(x); else -> x * 0.92 + 0.02 }
        NORDIC -> when (ch) { 0 -> x * 0.94; 1 -> x * 0.99 + 0.01; else -> lift(x, 0.04, 1.0) + 0.03 * hump(x) }
        MATTE -> lift(sCurve(x, 0.06), 0.09, 0.94)
        VINTAGE -> when (ch) { 0 -> lift(x, 0.07, 0.97) + 0.03 * hump(x); 1 -> lift(x, 0.06, 0.94); else -> lift(x, 0.12, 0.84) }
        CINEMA -> {
            val s = sCurve(x, 0.14)
            val shadow = (1 - x).pow(2.0); val highlight = x * x
            when (ch) { 0 -> s - 0.05 * shadow + 0.05 * highlight; 1 -> s + 0.02 * shadow; else -> s + 0.07 * shadow - 0.07 * highlight }
        }
        DRAMA -> sCurve(sCurve(x, 0.25), 0.1)
        MONO -> sCurve(x, 0.06)
        NOIR -> sCurve(sCurve(x, 0.3), 0.15)
        SEPIA -> { val s = sCurve(x, 0.05); when (ch) { 0 -> s * 1.0 + 0.07 * hump(s) + 0.02; 1 -> s * 0.95 + 0.03 * hump(s); else -> s * 0.78 + 0.03 } }
    }.coerceIn(0.0, 1.0)

    companion object {
        private fun sCurve(x: Double, k: Double): Double { val s = x * x * (3 - 2 * x); return x + k * 2 * (s - x) }
        private fun lift(x: Double, black: Double, white: Double) = black + x * (white - black)
        private fun hump(x: Double) = 4 * x * (1 - x)
    }
}

data class FilterSpec(val preset: FilterPreset = FilterPreset.NONE, val intensity: Float = 1f) {
    val isIdentity get() = preset == FilterPreset.NONE || intensity <= 0f
}

/**
 * Per-pixel colour pipeline shared by the live preview and the full-resolution export, so both show
 * the same result: white balance + exposure + tone curve (LUTs) → saturation/vibrance → filter look
 * → vignette. Alpha is preserved. Sharpening is a separate neighbourhood pass ([sharpenRows]).
 */
class ColorPipeline(val adjust: Adjustments, val filter: FilterSpec) {
    private val lut = Array(3) { IntArray(256) }
    private val post = Array(3) { IntArray(256) }
    private val satBase: Float
    private val vibrance: Float
    private val vignette: Float
    val isIdentity: Boolean
    val sharpness: Float = adjust[AdjustKey.SHARPNESS]

    init {
        val ev = adjust[AdjustKey.EXPOSURE] * 2.0
        val gain = 2.0.pow(ev)
        val bright = adjust[AdjustKey.BRIGHTNESS].toDouble()
        val gamma = 2.0.pow(-bright * 0.85)
        val contrast = adjust[AdjustKey.CONTRAST].toDouble()
        val hl = adjust[AdjustKey.HIGHLIGHTS].toDouble()
        val sh = adjust[AdjustKey.SHADOWS].toDouble()
        val warmth = adjust[AdjustKey.WARMTH].toDouble()
        val tint = adjust[AdjustKey.TINT].toDouble()
        val wb = doubleArrayOf(1 + 0.14 * warmth + 0.04 * tint, 1 - 0.1 * tint, 1 - 0.14 * warmth + 0.04 * tint)
        for (ch in 0 until 3) for (i in 0 until 256) {
            var x = i / 255.0
            // White balance and exposure in linear light.
            var lin = srgbToLinear(x) * wb[ch]
            if (ev != 0.0) lin *= gain
            x = linearToSrgb(lin.coerceIn(0.0, 1.0))
            if (bright != 0.0) x = x.pow(gamma)
            if (contrast > 0) { val s = x * x * (3 - 2 * x); x += contrast * (s - x) }
            else if (contrast < 0) x = 0.5 + (x - 0.5) * (1 + contrast * 0.6)
            if (hl != 0.0) { val w = smooth(0.45, 1.0, x); x += if (hl > 0) hl * 0.4 * w * (1 - x) else hl * 0.4 * w * (x - 0.35) }
            if (sh != 0.0) { val w = 1 - smooth(0.0, 0.55, x); x += if (sh > 0) sh * 0.45 * w * (0.55 - x) else sh * 0.45 * w * x }
            lut[ch][i] = (x.coerceIn(0.0, 1.0) * 255).roundToInt()
        }
        val k = if (filter.isIdentity) 0f else filter.intensity.coerceIn(0f, 1f)
        for (ch in 0 until 3) for (i in 0 until 256) {
            val x = i / 255.0
            val y = if (k == 0f) x else x + (filter.preset.curve(ch, x) - x) * k
            post[ch][i] = (y.coerceIn(0.0, 1.0) * 255).roundToInt()
        }
        val filterSat = 1f + (filter.preset.saturation - 1f) * k
        satBase = (1f + adjust[AdjustKey.SATURATION]) * filterSat
        vibrance = adjust[AdjustKey.VIBRANCE]
        vignette = adjust[AdjustKey.VIGNETTE]
        isIdentity = adjust.isIdentity && filter.isIdentity
    }

    /**
     * Applies the pipeline in place to [rows] rows of [px] (row stride [w]); [y0] is the first row's
     * y in an output of size [fullW]×[fullH] (needed for the vignette).
     */
    fun apply(px: IntArray, offset: Int, w: Int, rows: Int, y0: Int, fullW: Int, fullH: Int) {
        if (isIdentity) return
        val lr = lut[0]; val lg = lut[1]; val lb = lut[2]
        val pr = post[0]; val pg = post[1]; val pb = post[2]
        val doSat = abs(satBase - 1f) > 1e-4f || abs(vibrance) > 1e-4f
        val cx = fullW / 2.0; val cy = fullH / 2.0
        val maxD = sqrt(cx * cx + cy * cy)
        for (r in 0 until rows) {
            val base = offset + r * w
            val dy = (y0 + r + 0.5 - cy)
            for (x in 0 until w) {
                val c = px[base + x]
                val a = c ushr 24
                var red = lr[(c shr 16) and 255]; var green = lg[(c shr 8) and 255]; var blue = lb[c and 255]
                if (doSat) {
                    val l = (54 * red + 183 * green + 19 * blue) shr 8
                    var k = satBase
                    if (vibrance != 0f) {
                        val mx = max(red, max(green, blue)); val mn = min(red, min(green, blue))
                        k *= 1f + vibrance * (1f - (mx - mn) / 255f) * (if (vibrance > 0) 1f else 0.9f)
                    }
                    red = clamp255(l + ((red - l) * k).roundToInt())
                    green = clamp255(l + ((green - l) * k).roundToInt())
                    blue = clamp255(l + ((blue - l) * k).roundToInt())
                }
                red = pr[red]; green = pg[green]; blue = pb[blue]
                if (vignette != 0f) {
                    val dx = x + 0.5 - cx
                    val d = sqrt(dx * dx + dy * dy) / maxD
                    val v = smooth(0.3, 1.05, d)
                    val f = if (vignette > 0) 1.0 - vignette * 0.75 * v * v else 1.0 - vignette * 0.6 * v * v
                    if (vignette > 0) {
                        red = (red * f).roundToInt(); green = (green * f).roundToInt(); blue = (blue * f).roundToInt()
                    } else {
                        val t = f - 1.0
                        red = clamp255((red + (255 - red) * t).roundToInt()); green = clamp255((green + (255 - green) * t).roundToInt()); blue = clamp255((blue + (255 - blue) * t).roundToInt())
                    }
                }
                px[base + x] = (a shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
    }

    companion object {
        /** Extra rows needed above and below a strip for [sharpenRows]. */
        const val SHARPEN_PAD = 2

        fun srgbToLinear(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        fun linearToSrgb(v: Double) = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        private fun smooth(e0: Double, e1: Double, x: Double): Double { val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
        private fun clamp255(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

        /**
         * Unsharp mask (amount > 0) or soften (amount < 0) with a 5×5 binomial blur. [src] holds
         * [srcRows] rows of width [w]; output rows are src rows [pad, srcRows - pad). Image edges
         * (first/last [pad] rows of the whole picture) must be supplied by edge replication.
         */
        fun sharpenRows(src: IntArray, w: Int, srcRows: Int, pad: Int, amount: Float, out: IntArray, outOffset: Int) {
            val k = intArrayOf(1, 4, 6, 4, 1)
            val outRows = srcRows - 2 * pad
            val tmp = IntArray(w * srcRows * 3)
            // Horizontal pass.
            for (r in 0 until srcRows) {
                val b = r * w
                for (x in 0 until w) {
                    var sr = 0; var sg = 0; var sb = 0
                    for (i in -2..2) {
                        val xx = (x + i).coerceIn(0, w - 1)
                        val c = src[b + xx]; val kk = k[i + 2]
                        sr += ((c shr 16) and 255) * kk; sg += ((c shr 8) and 255) * kk; sb += (c and 255) * kk
                    }
                    val t = (b + x) * 3
                    tmp[t] = sr; tmp[t + 1] = sg; tmp[t + 2] = sb
                }
            }
            val amt = amount * 1.6f
            for (r in 0 until outRows) {
                val sy = r + pad
                for (x in 0 until w) {
                    var br = 0; var bg = 0; var bb = 0
                    for (i in -2..2) {
                        val yy = (sy + i).coerceIn(0, srcRows - 1)
                        val t = (yy * w + x) * 3; val kk = k[i + 2]
                        br += tmp[t] * kk; bg += tmp[t + 1] * kk; bb += tmp[t + 2] * kk
                    }
                    val c = src[sy * w + x]
                    val r0 = (c shr 16) and 255; val g0 = (c shr 8) and 255; val b0 = c and 255
                    val r1 = br / 256f; val g1 = bg / 256f; val b1 = bb / 256f
                    val nr: Int; val ng: Int; val nb: Int
                    if (amt >= 0) {
                        nr = clamp255((r0 + (r0 - r1) * amt).roundToInt()); ng = clamp255((g0 + (g0 - g1) * amt).roundToInt()); nb = clamp255((b0 + (b0 - b1) * amt).roundToInt())
                    } else {
                        val t = -amount
                        nr = clamp255((r0 + (r1 - r0) * t).roundToInt()); ng = clamp255((g0 + (g1 - g0) * t).roundToInt()); nb = clamp255((b0 + (b1 - b0) * t).roundToInt())
                    }
                    out[outOffset + r * w + x] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
    }
}
