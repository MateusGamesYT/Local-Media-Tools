package com.localmediatools.gallery.core

import kotlin.math.sqrt

/**
 * Classical face descriptor used when the face AI can't run: the face is aligned by its eyes
 * (scale and position), and described by uniform local binary patterns (8 neighbours, radius 1)
 * in an 8×8 grid. Histograms are square-rooted (Hellinger) and L2-normalised, so the dot product of
 * two descriptors measures similarity.
 */
object Lbp {
    const val SIZE = 64
    private const val CELLS = 8
    private const val BINS = 59
    const val DIM = CELLS * CELLS * BINS
    /** Eye positions in the aligned crop. */
    private const val EYE_Y = 24f
    private const val EYE_DX = 12f

    /** Uniform pattern index (0..57) for each byte, 58 for non-uniform patterns. */
    private val uniform: IntArray = IntArray(256).also { t ->
        var k = 0
        for (v in 0 until 256) {
            var transitions = 0
            for (b in 0 until 8) if (((v shr b) and 1) != ((v shr ((b + 1) % 8)) and 1)) transitions++
            t[v] = if (transitions <= 2) k++ else 58
        }
    }

    /**
     * Descriptor of the face whose eyes' midpoint is ([cx], [cy]) and eye distance [eyeDist], in a
     * grey image ([gray] row-major, [w]×[h], values 0..255).
     */
    fun describe(gray: FloatArray, w: Int, h: Int, cx: Float, cy: Float, eyeDist: Float): FloatArray {
        val n = SIZE + 2
        val crop = FloatArray(n * n)
        val s = eyeDist / (2 * EYE_DX)
        // Crop pixel (u, v) (with a 1 px border for the patterns) maps to the image around the eyes.
        for (v in 0 until n) for (u in 0 until n) {
            val x = cx + (u - 1 - SIZE / 2f) * s
            val y = cy + (v - 1 - EYE_Y) * s
            crop[v * n + u] = sample(gray, w, h, x, y)
        }
        val hist = FloatArray(DIM)
        val cell = SIZE / CELLS
        for (v in 1..SIZE) for (u in 1..SIZE) {
            val c = crop[v * n + u]
            var code = 0
            if (crop[(v - 1) * n + u - 1] >= c) code = code or 1
            if (crop[(v - 1) * n + u] >= c) code = code or 2
            if (crop[(v - 1) * n + u + 1] >= c) code = code or 4
            if (crop[v * n + u + 1] >= c) code = code or 8
            if (crop[(v + 1) * n + u + 1] >= c) code = code or 16
            if (crop[(v + 1) * n + u] >= c) code = code or 32
            if (crop[(v + 1) * n + u - 1] >= c) code = code or 64
            if (crop[v * n + u - 1] >= c) code = code or 128
            val ci = ((v - 1) / cell) * CELLS + (u - 1) / cell
            hist[ci * BINS + uniform[code]] += 1f
        }
        var norm = 0f
        for (i in hist.indices) { val r = sqrt(hist[i] / (cell * cell)); hist[i] = r; norm += r * r }
        val inv = 1f / sqrt(norm).coerceAtLeast(1e-6f)
        for (i in hist.indices) hist[i] *= inv
        return hist
    }

    private fun sample(g: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xf = x.coerceIn(0f, (w - 1).toFloat()); val yf = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
        val ax = xf - x0; val ay = yf - y0
        val a = g[y0 * w + x0] * (1 - ax) + g[y0 * w + x1] * ax
        val b = g[y1 * w + x0] * (1 - ax) + g[y1 * w + x1] * ax
        return a * (1 - ay) + b * ay
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
