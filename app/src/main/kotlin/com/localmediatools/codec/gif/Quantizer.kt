package com.localmediatools.codec.gif

/**
 * Colour quantization for GIF output: weighted median-cut on a 15-bit histogram followed by
 * k-means refinement. When the input already has few enough distinct colours, they are used
 * verbatim so no detail is lost.
 */
class ColorHistogram {
    val counts = IntArray(32768)
    private val sumR = LongArray(32768)
    private val sumG = LongArray(32768)
    private val sumB = LongArray(32768)
    private val exact = com.localmediatools.codec.png.IntIntMap(512)
    private var exactOverflow = false
    var total = 0L; private set

    /** Adds opaque pixels (alpha < 128 is ignored); [step] subsamples large frames. */
    fun add(argb: IntArray, offset: Int = 0, count: Int = argb.size, step: Int = 1) {
        var i = offset
        val end = offset + count
        while (i < end) {
            val c = argb[i]
            if ((c ushr 24) >= 128) {
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                counts[key]++
                sumR[key] += r.toLong(); sumG[key] += g.toLong(); sumB[key] += b.toLong()
                total++
                if (!exactOverflow) {
                    val rgb = c or (0xFF shl 24)
                    if (exact.get(rgb) < 0) {
                        if (exact.size >= 256) exactOverflow = true else exact.put(rgb, exact.size)
                    }
                }
            }
            i += step
        }
    }

    /** Builds a palette of at most [maxColors] opaque colours. */
    fun palette(maxColors: Int): IntArray {
        require(maxColors in 2..256)
        if (!exactOverflow && exact.size in 1..maxColors) return exact.keys().toIntArray()
        if (total == 0L) return intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        // Collect populated bins.
        var n = 0
        for (c in counts) if (c > 0) n++
        val bins = IntArray(n)
        n = 0
        for (k in counts.indices) if (counts[k] > 0) bins[n++] = k
        val br = FloatArray(n); val bg = FloatArray(n); val bb = FloatArray(n); val bw = FloatArray(n)
        for (i in 0 until n) {
            val k = bins[i]; val c = counts[k].toFloat()
            br[i] = sumR[k] / c; bg[i] = sumG[k] / c; bb[i] = sumB[k] / c; bw[i] = c
        }
        if (n <= maxColors) {
            return IntArray(n) { rgb(br[it], bg[it], bb[it]) }
        }
        // Median cut: boxes are index ranges into `order`.
        val order = IntArray(n) { it }
        val boxStart = IntArray(maxColors); val boxEnd = IntArray(maxColors)
        boxStart[0] = 0; boxEnd[0] = n
        var boxes = 1
        while (boxes < maxColors) {
            // Pick the box with the largest weighted variance along its widest channel.
            var bestBox = -1; var bestScore = 0.0; var bestAxis = 0
            for (b in 0 until boxes) {
                val s = boxStart[b]; val e = boxEnd[b]
                if (e - s < 2) continue
                var w = 0.0; var mr = 0.0; var mg = 0.0; var mb = 0.0
                for (j in s until e) { val i = order[j]; w += bw[i]; mr += br[i] * bw[i]; mg += bg[i] * bw[i]; mb += bb[i] * bw[i] }
                mr /= w; mg /= w; mb /= w
                var vr = 0.0; var vg = 0.0; var vb = 0.0
                for (j in s until e) {
                    val i = order[j]
                    val dr = br[i] - mr; val dg = bg[i] - mg; val db = bb[i] - mb
                    vr += dr * dr * bw[i]; vg += dg * dg * bw[i]; vb += db * db * bw[i]
                }
                // Perceptual weighting: green differences matter most.
                vr *= 0.8; vg *= 1.0; vb *= 0.6
                val axis = if (vr >= vg && vr >= vb) 0 else if (vg >= vb) 1 else 2
                val score = maxOf(vr, maxOf(vg, vb))
                if (score > bestScore) { bestScore = score; bestBox = b; bestAxis = axis }
            }
            if (bestBox < 0) break
            val s = boxStart[bestBox]; val e = boxEnd[bestBox]
            val key: (Int) -> Float = when (bestAxis) { 0 -> { i -> br[i] }; 1 -> { i -> bg[i] }; else -> { i -> bb[i] } }
            val sub = order.copyOfRange(s, e).sortedBy { key(it) }
            for (j in sub.indices) order[s + j] = sub[j]
            var w = 0.0
            for (j in s until e) w += bw[order[j]]
            var acc = 0.0
            var split = s + 1
            for (j in s until e - 1) {
                acc += bw[order[j]]
                if (acc >= w / 2) { split = j + 1; break }
                split = j + 1
            }
            split = split.coerceIn(s + 1, e - 1)
            boxStart[boxes] = split; boxEnd[boxes] = e
            boxEnd[bestBox] = split
            boxes++
        }
        val pr = FloatArray(boxes); val pg = FloatArray(boxes); val pb = FloatArray(boxes)
        for (b in 0 until boxes) {
            var w = 0.0; var r = 0.0; var g = 0.0; var bl = 0.0
            for (j in boxStart[b] until boxEnd[b]) { val i = order[j]; w += bw[i]; r += br[i] * bw[i]; g += bg[i] * bw[i]; bl += bb[i] * bw[i] }
            pr[b] = (r / w).toFloat(); pg[b] = (g / w).toFloat(); pb[b] = (bl / w).toFloat()
        }
        // K-means refinement over the bins.
        val sr = DoubleArray(boxes); val sg = DoubleArray(boxes); val sb = DoubleArray(boxes); val sw = DoubleArray(boxes)
        repeat(4) {
            java.util.Arrays.fill(sr, 0.0); java.util.Arrays.fill(sg, 0.0); java.util.Arrays.fill(sb, 0.0); java.util.Arrays.fill(sw, 0.0)
            for (i in 0 until n) {
                var best = 0; var bestD = Float.MAX_VALUE
                for (p in 0 until boxes) {
                    val dr = br[i] - pr[p]; val dg = bg[i] - pg[p]; val db = bb[i] - pb[p]
                    val d = dr * dr * 0.8f + dg * dg + db * db * 0.6f
                    if (d < bestD) { bestD = d; best = p }
                }
                sr[best] += br[i] * bw[i]; sg[best] += bg[i] * bw[i]; sb[best] += bb[i] * bw[i]; sw[best] += bw[i].toDouble()
            }
            for (p in 0 until boxes) if (sw[p] > 0) {
                pr[p] = (sr[p] / sw[p]).toFloat(); pg[p] = (sg[p] / sw[p]).toFloat(); pb[p] = (sb[p] / sw[p]).toFloat()
            }
        }
        val result = LinkedHashSet<Int>()
        for (p in 0 until boxes) result.add(rgb(pr[p], pg[p], pb[p]))
        return result.toIntArray()
    }

    private fun rgb(r: Float, g: Float, b: Float): Int {
        val ri = (r + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

enum class Dithering { NONE, ORDERED, DIFFUSION }

/**
 * Maps ARGB pixels onto a palette. Lookups go through a lazily filled 18-bit table; dithering is
 * optional. Pixels with alpha < 128 become fully transparent (0), others become opaque palette colours.
 */
class PaletteMapper(private val palette: IntArray) {
    private val lut = ShortArray(1 shl 18) { -1 }
    private val pr = IntArray(palette.size) { (palette[it] shr 16) and 0xFF }
    private val pg = IntArray(palette.size) { (palette[it] shr 8) and 0xFF }
    private val pb = IntArray(palette.size) { palette[it] and 0xFF }

    fun nearest(r: Int, g: Int, b: Int): Int {
        val key = ((r shr 2) shl 12) or ((g shr 2) shl 6) or (b shr 2)
        val cached = lut[key].toInt()
        if (cached >= 0) return cached
        val cr = (r and 0xFC) + 2; val cg = (g and 0xFC) + 2; val cb = (b and 0xFC) + 2
        var best = 0; var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val dr = cr - pr[i]; val dg = cg - pg[i]; val db = cb - pb[i]
            val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (d < bestD) { bestD = d; best = i }
        }
        lut[key] = best.toShort()
        return best
    }

    /** Maps [src] (w x h) into [dst] as palette colours (ARGB). Returns the mean squared error. */
    fun map(src: IntArray, dst: IntArray, w: Int, h: Int, dithering: Dithering): Double {
        val spread = (255.0 / Math.cbrt(palette.size.toDouble()) * 0.55).toFloat()
        var err = 0.0
        val diffusion = dithering == Dithering.DIFFUSION
        var errR = FloatArray(if (diffusion) w + 2 else 0); var errG = FloatArray(errR.size); var errB = FloatArray(errR.size)
        var nErrR = FloatArray(errR.size); var nErrG = FloatArray(errR.size); var nErrB = FloatArray(errR.size)
        for (y in 0 until h) {
            val base = y * w
            val ltr = (y and 1) == 0
            var x = if (dithering == Dithering.DIFFUSION && !ltr) w - 1 else 0
            val dx = if (dithering == Dithering.DIFFUSION && !ltr) -1 else 1
            for (k in 0 until w) {
                val c = src[base + x]
                if ((c ushr 24) < 128) {
                    dst[base + x] = 0
                } else {
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    var tr = r.toFloat(); var tg = g.toFloat(); var tb = b.toFloat()
                    when (dithering) {
                        Dithering.ORDERED -> {
                            val t = (BAYER[((y and 7) shl 3) or (x and 7)] / 64f - 0.484375f) * spread
                            tr += t; tg += t; tb += t
                        }
                        Dithering.DIFFUSION -> {
                            tr += errR[x + 1]; tg += errG[x + 1]; tb += errB[x + 1]
                        }
                        Dithering.NONE -> Unit
                    }
                    val ri = (tr + 0.5f).toInt().coerceIn(0, 255)
                    val gi = (tg + 0.5f).toInt().coerceIn(0, 255)
                    val bi = (tb + 0.5f).toInt().coerceIn(0, 255)
                    val idx = nearest(ri, gi, bi)
                    dst[base + x] = palette[idx]
                    val er = r - pr[idx]; val eg = g - pg[idx]; val eb = b - pb[idx]
                    err += (er * er + eg * eg + eb * eb).toDouble()
                    if (dithering == Dithering.DIFFUSION) {
                        // Floyd–Steinberg with 85% strength to limit noise; serpentine scan.
                        val qr = (tr - pr[idx]) * 0.85f; val qg = (tg - pg[idx]) * 0.85f; val qb = (tb - pb[idx]) * 0.85f
                        val xn = x + 1 + dx
                        val xp = x + 1 - dx
                        errR[xn] += qr * 7 / 16; errG[xn] += qg * 7 / 16; errB[xn] += qb * 7 / 16
                        nErrR[xp] += qr * 3 / 16; nErrG[xp] += qg * 3 / 16; nErrB[xp] += qb * 3 / 16
                        nErrR[x + 1] += qr * 5 / 16; nErrG[x + 1] += qg * 5 / 16; nErrB[x + 1] += qb * 5 / 16
                        nErrR[xn] += qr / 16; nErrG[xn] += qg / 16; nErrB[xn] += qb / 16
                    }
                }
                x += dx
            }
            if (diffusion) {
                var t = errR; errR = nErrR; nErrR = t; java.util.Arrays.fill(nErrR, 0f)
                t = errG; errG = nErrG; nErrG = t; java.util.Arrays.fill(nErrG, 0f)
                t = errB; errB = nErrB; nErrB = t; java.util.Arrays.fill(nErrB, 0f)
            }
        }
        return err / (w.toLong() * h * 3).coerceAtLeast(1)
    }

    companion object {
        private val BAYER = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21
        )
    }
}
