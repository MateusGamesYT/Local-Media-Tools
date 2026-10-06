package com.localmediatools.codec.image

/** Resampling of packed ARGB buffers (non-premultiplied). */
object Resample {
    /** Nearest-neighbour: never invents colours (used where pixels must stay from the source palette). */
    fun nearest(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int, dst: IntArray = IntArray(dw * dh)): IntArray {
        for (y in 0 until dh) {
            val sy = minOf(sh - 1, ((y + 0.5) * sh / dh).toInt())
            val sBase = sy * sw
            val dBase = y * dw
            for (x in 0 until dw) {
                val sx = minOf(sw - 1, ((x + 0.5) * sw / dw).toInt())
                dst[dBase + x] = src[sBase + sx]
            }
        }
        return dst
    }

    /**
     * Area-averaging downscale (box filter with fractional coverage), alpha-weighted so transparent
     * pixels don't darken edges. Upscaling falls back to bilinear-free nearest sampling.
     */
    fun area(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int, dst: IntArray = IntArray(dw * dh)): IntArray {
        if (dw >= sw || dh >= sh) return nearest(src, sw, sh, dw, dh, dst)
        val fx = sw.toDouble() / dw
        val fy = sh.toDouble() / dh
        val accA = DoubleArray(dw); val accR = DoubleArray(dw); val accG = DoubleArray(dw); val accB = DoubleArray(dw); val accW = DoubleArray(dw)
        // Precompute horizontal coverage for each destination column.
        val x0 = IntArray(dw); val x1 = IntArray(dw)
        for (x in 0 until dw) { x0[x] = (x * fx).toInt(); x1[x] = minOf(sw, Math.ceil((x + 1) * fx).toInt()) }
        for (y in 0 until dh) {
            java.util.Arrays.fill(accA, 0.0); java.util.Arrays.fill(accR, 0.0); java.util.Arrays.fill(accG, 0.0)
            java.util.Arrays.fill(accB, 0.0); java.util.Arrays.fill(accW, 0.0)
            val ys = y * fy; val ye = (y + 1) * fy
            var sy = ys.toInt()
            while (sy < ye && sy < sh) {
                val wy = minOf(ye, sy + 1.0) - maxOf(ys, sy.toDouble())
                val base = sy * sw
                for (x in 0 until dw) {
                    val xs = x * fx; val xe = (x + 1) * fx
                    for (sx in x0[x] until x1[x]) {
                        val wx = minOf(xe, sx + 1.0) - maxOf(xs, sx.toDouble())
                        if (wx <= 0) continue
                        val w = wx * wy
                        val c = src[base + sx]
                        val a = (c ushr 24) / 255.0
                        accA[x] += a * w
                        accR[x] += ((c shr 16) and 0xFF) * a * w
                        accG[x] += ((c shr 8) and 0xFF) * a * w
                        accB[x] += (c and 0xFF) * a * w
                        accW[x] += w
                    }
                }
                sy++
            }
            val dBase = y * dw
            for (x in 0 until dw) {
                val a = accA[x]
                if (a <= 1e-9) { dst[dBase + x] = 0; continue }
                val alpha = (a / accW[x] * 255 + 0.5).toInt().coerceIn(0, 255)
                val r = (accR[x] / a + 0.5).toInt().coerceIn(0, 255)
                val g = (accG[x] / a + 0.5).toInt().coerceIn(0, 255)
                val b = (accB[x] / a + 0.5).toInt().coerceIn(0, 255)
                dst[dBase + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return dst
    }

    /** Makes alpha binary (GIF): below [threshold] becomes fully transparent, otherwise opaque. */
    fun binarizeAlpha(px: IntArray, threshold: Int = 128) {
        for (i in px.indices) {
            val c = px[i]
            px[i] = if ((c ushr 24) < threshold) 0 else c or (0xFF shl 24)
        }
    }
}
