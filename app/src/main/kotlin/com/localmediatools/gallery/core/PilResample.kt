package com.localmediatools.gallery.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Pillow's bilinear resize (`Image.resize(size, BILINEAR)`), reproduced exactly: an antialiased
 * triangle filter widened by the scale factor, 22-bit fixed-point weights, horizontal pass first,
 * each pass rounded to 8 bits. The recognition thresholds were calibrated on pictures shrunk this
 * way, so the app shrinks them the same way. Works on ARGB pixels; the result is opaque RGB.
 */
object PilResample {
    private const val PRECISION_BITS = 32 - 8 - 2

    private class Coeffs(val start: IntArray, val count: IntArray, val k: IntArray, val ksize: Int)

    private fun coeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 1.0 * filterScale
        val ksize = ceil(support).toInt() * 2 + 1
        val start = IntArray(outSize); val count = IntArray(outSize); val k = IntArray(outSize * ksize)
        val w = DoubleArray(ksize)
        val ss = 1.0 / filterScale
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt(); if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt(); if (xmax > inSize) xmax = inSize
            xmax -= xmin
            var sum = 0.0
            for (x in 0 until xmax) {
                val t = abs((x + xmin - center + 0.5) * ss)
                w[x] = if (t < 1.0) 1.0 - t else 0.0
                sum += w[x]
            }
            for (x in 0 until xmax) {
                val v = if (sum != 0.0) w[x] / sum else w[x]
                k[xx * ksize + x] = if (v < 0) (-0.5 + v * (1 shl PRECISION_BITS)).toInt() else (0.5 + v * (1 shl PRECISION_BITS)).toInt()
            }
            start[xx] = xmin; count[xx] = xmax
        }
        return Coeffs(start, count, k, ksize)
    }

    private fun clip8(ss: Int): Int { val v = ss shr PRECISION_BITS; return if (v < 0) 0 else if (v > 255) 255 else v }

    /** Resizes [w]×[h] ARGB pixels to [ow]×[oh]. */
    fun resize(src: IntArray, w: Int, h: Int, ow: Int, oh: Int): IntArray {
        var cur = src; var cw = w
        if (ow != w) { cur = horizontal(cur, w, h, ow); cw = ow }
        if (oh != h) cur = vertical(cur, cw, h, oh)
        return if (cur === src) IntArray(src.size) { src[it] or (0xFF shl 24) } else cur
    }

    private fun horizontal(src: IntArray, w: Int, h: Int, ow: Int): IntArray {
        val c = coeffs(w, ow)
        val out = IntArray(ow * h)
        val half = 1 shl (PRECISION_BITS - 1)
        for (y in 0 until h) {
            val row = y * w
            for (xx in 0 until ow) {
                var r = half; var g = half; var b = half
                val o = xx * c.ksize; val s = row + c.start[xx]
                for (x in 0 until c.count[xx]) {
                    val p = src[s + x]; val k = c.k[o + x]
                    r += ((p shr 16) and 255) * k; g += ((p shr 8) and 255) * k; b += (p and 255) * k
                }
                out[y * ow + xx] = (0xFF shl 24) or (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        return out
    }

    private fun vertical(src: IntArray, w: Int, h: Int, oh: Int): IntArray {
        val c = coeffs(h, oh)
        val out = IntArray(w * oh)
        val half = 1 shl (PRECISION_BITS - 1)
        for (yy in 0 until oh) {
            val o = yy * c.ksize; val s = c.start[yy]; val n = c.count[yy]
            for (x in 0 until w) {
                var r = half; var g = half; var b = half
                for (y in 0 until n) {
                    val p = src[(s + y) * w + x]; val k = c.k[o + y]
                    r += ((p shr 16) and 255) * k; g += ((p shr 8) and 255) * k; b += (p and 255) * k
                }
                out[yy * w + x] = (0xFF shl 24) or (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        return out
    }
}
