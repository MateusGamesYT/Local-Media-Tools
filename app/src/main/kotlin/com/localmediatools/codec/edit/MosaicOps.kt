package com.localmediatools.codec.edit

/** Pixelation and blur used by the privacy brushes. */
object MosaicOps {
    /**
     * Replaces each [block]×[block] cell (grid shifted by [gx],[gy]) with its average colour where the
     * cell's centre lies inside [mask]; [alphaOut] gets 255 for those cells and 0 elsewhere.
     */
    fun pixelate(px: IntArray, w: Int, h: Int, block: Int, gx: Int, gy: Int, mask: ByteArray, alphaOut: ByteArray) {
        var by = -gy
        while (by < h) {
            var bx = -gx
            while (bx < w) {
                val x0 = bx.coerceAtLeast(0); val y0 = by.coerceAtLeast(0)
                val x1 = (bx + block).coerceAtMost(w); val y1 = (by + block).coerceAtMost(h)
                if (x1 > x0 && y1 > y0) {
                    val cx = ((x0 + x1) / 2).coerceIn(0, w - 1); val cy = ((y0 + y1) / 2).coerceIn(0, h - 1)
                    if ((mask[cy * w + cx].toInt() and 255) >= 128) {
                        var r = 0L; var g = 0L; var b = 0L; var a = 0L; var n = 0L
                        for (y in y0 until y1) for (x in x0 until x1) {
                            val c = px[y * w + x]
                            r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; a += c ushr 24; n++
                        }
                        val avg = ((a / n).toInt() shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
                        for (y in y0 until y1) for (x in x0 until x1) { px[y * w + x] = avg; alphaOut[y * w + x] = -1 }
                    }
                }
                bx += block
            }
            by += block
        }
    }

    /** Two-pass box blur of ARGB (alpha kept from [src]). */
    fun boxBlurArgb(src: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(w * h)
        pass(src, tmp, w, h, r, horizontal = true)
        pass(tmp, out, w, h, r, horizontal = false)
        for (i in out.indices) out[i] = (src[i] and 0xFF000000.toInt()) or (out[i] and 0x00FFFFFF)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val n = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int) = if (horizontal) line * w + i.coerceIn(0, w - 1) else i.coerceIn(0, h - 1) * w + line
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = src[idx(i)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val add = src[idx(i + r + 1)]; val rem = src[idx(i - r)]
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }
}
