package com.localmediatools.codec.gif

import com.localmediatools.codec.png.IntIntMap
import java.io.OutputStream

/**
 * Writes an animation from fully composited target frames (ARGB with binary alpha), producing a
 * GIF whose displayed frames are pixel-identical to the targets:
 *
 *  - only the changed rectangle of each frame is stored,
 *  - unchanged pixels inside that rectangle become transparent (better LZW compression),
 *  - identical consecutive frames are merged by adding their delays,
 *  - frames that must turn opaque pixels transparent are handled with "restore to background"
 *    disposal on the previous frame (one-frame look-ahead),
 *  - frames whose colours are all in the global palette reuse it; others get a local palette.
 *
 * Colours are never approximated here: callers quantize beforehand (lossy tools) or pass the
 * source pixels unchanged (lossless optimizer). A frame that cannot be represented exactly raises
 * [GifUnrepresentableException].
 */
class GifAnimationWriter(
    out: OutputStream,
    val width: Int,
    val height: Int,
    globalPalette: IntArray?,
    loopCount: Int?,
) {
    private val global: IntArray? = globalPalette?.let { if (it.size > 256) null else it }
    private val globalIndex: IntIntMap? = global?.let { g -> IntIntMap(g.size * 2).also { m -> g.forEachIndexed { i, c -> m.put(c, i) } } }
    private val globalTransparent = global?.let { if (it.size < 256) it.size else -1 } ?: -1
    private val encoder = GifEncoder(out, width, height, global?.let { if (it.size < 256) it + intArrayOf(0) else it }, loopCount)
    private val displayed = IntArray(width * height) // what a viewer shows before the pending frame
    private var pending: IntArray? = null
    private var pendingDelay = 0
    var framesWritten = 0; private set

    fun addFrame(target: IntArray, delayCs: Int) {
        require(target.size == width * height) { "Frame size mismatch" }
        for (c in target) {
            val a = c ushr 24
            if (a != 0 && a != 0xFF) throw GifUnrepresentableException("GIF cannot store semi-transparent pixels")
        }
        val p = pending
        if (p == null) {
            pending = target.copyOf(); pendingDelay = delayCs
            return
        }
        if (sameImage(p, target)) {
            pendingDelay += delayCs
            return
        }
        var needsClear = false
        for (i in target.indices) {
            if ((target[i] ushr 24) == 0 && (p[i] ushr 24) != 0) { needsClear = true; break }
        }
        writePending(clearAfter = needsClear)
        pending = target.copyOf(); pendingDelay = delayCs
    }

    fun finish() {
        if (pending != null) writePending(clearAfter = false)
        pending = null
        encoder.finish()
    }

    private fun sameImage(a: IntArray, b: IntArray): Boolean {
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x != y && !((x ushr 24) == 0 && (y ushr 24) == 0)) return false
        }
        return true
    }

    private fun writePending(clearAfter: Boolean) {
        val t = pending!!
        // Changed rectangle relative to what is currently displayed.
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        if (clearAfter) {
            minX = 0; minY = 0; maxX = width - 1; maxY = height - 1
        } else {
            for (y in 0 until height) {
                val base = y * width
                for (x in 0 until width) {
                    if (differs(t[base + x], displayed[base + x])) {
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < 0) { minX = 0; minY = 0; maxX = 0; maxY = 0 } // nothing changed: 1x1 keeps the delay
        }
        val rw = maxX - minX + 1
        val rh = maxY - minY + 1
        val delay = pendingDelay.coerceAtMost(65535)
        val encoded = encodeRect(t, minX, minY, rw, rh, allowDiffTransparency = true)
            ?: encodeRect(t, minX, minY, rw, rh, allowDiffTransparency = false)
            ?: throw GifUnrepresentableException(
                "Frame ${framesWritten + 1} needs more than 256 colours including transparency, which GIF cannot store exactly"
            )
        encoder.writeFrame(encoded.indices, minX, minY, rw, rh, encoded.localPalette, encoded.transparentIndex, delay, if (clearAfter) 2 else 1)
        framesWritten++
        // Update the displayed canvas.
        for (y in minY..maxY) {
            val base = y * width
            for (x in minX..maxX) displayed[base + x] = if (clearAfter) 0 else normalize(t[base + x])
        }
    }

    private class Encoded(val indices: ByteArray, val localPalette: IntArray?, val transparentIndex: Int)

    private fun encodeRect(t: IntArray, rx: Int, ry: Int, rw: Int, rh: Int, allowDiffTransparency: Boolean): Encoded? {
        // Pass 1: which pixels are transparent in the file, and which colours are needed.
        val transparentMask = BooleanArray(rw * rh)
        val colors = IntIntMap(256)
        var needsTransparent = false
        var tooMany = false
        for (y in 0 until rh) {
            val base = (ry + y) * width + rx
            for (x in 0 until rw) {
                val c = t[base + x]
                val keep = (c ushr 24) == 0 || (allowDiffTransparency && !differs(c, displayed[base + x]))
                if (keep) {
                    transparentMask[y * rw + x] = true
                    needsTransparent = true
                } else if (colors.get(c) < 0) {
                    if (colors.size >= 256) { tooMany = true } else colors.put(c, colors.size)
                }
            }
        }
        if (tooMany) return null
        val list = colors.keys()
        // Prefer the global palette.
        val useGlobal = global != null && list.all { globalIndex!!.get(it) >= 0 } && (!needsTransparent || globalTransparent >= 0)
        val palette: IntArray?
        val index: IntIntMap
        val transparentIndex: Int
        if (useGlobal) {
            palette = null
            index = globalIndex!!
            transparentIndex = if (needsTransparent) globalTransparent else -1
        } else {
            val total = list.size + (if (needsTransparent) 1 else 0)
            if (total > 256) return null
            val pal = IntArray(maxOf(2, total))
            for (i in list.indices) pal[i] = list[i]
            palette = pal
            index = IntIntMap(list.size * 2).also { m -> list.forEachIndexed { i, c -> m.put(c, i) } }
            transparentIndex = if (needsTransparent) list.size else -1
        }
        val out = ByteArray(rw * rh)
        for (y in 0 until rh) {
            val base = (ry + y) * width + rx
            for (x in 0 until rw) {
                val i = y * rw + x
                out[i] = if (transparentMask[i]) transparentIndex.toByte() else index.get(t[base + x]).toByte()
            }
        }
        return Encoded(out, palette, transparentIndex)
    }

    private fun differs(a: Int, b: Int): Boolean {
        if (a == b) return false
        return !((a ushr 24) == 0 && (b ushr 24) == 0)
    }

    private fun normalize(c: Int) = if ((c ushr 24) == 0) 0 else c
}
