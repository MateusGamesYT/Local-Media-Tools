package com.localmediatools.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.localmediatools.codec.image.Orientation
import com.localmediatools.codec.image.PixelTransforms

object BitmapOps {

    fun ensureArgb8888(b: Bitmap): Bitmap {
        if (b.config == Bitmap.Config.ARGB_8888) return b
        val c = b.copy(Bitmap.Config.ARGB_8888, false) ?: throw OutOfMemoryError("bitmap copy failed")
        b.recycle()
        return c
    }

    /** Android matrix equivalent of [Orientation.rawToDisplay]. */
    fun matrix(o: Orientation, rawW: Int, rawH: Int): Matrix {
        val m = o.rawToDisplay(rawW, rawH)
        return Matrix().apply {
            setValues(floatArrayOf(m[0].toFloat(), m[1].toFloat(), m[4].toFloat(), m[2].toFloat(), m[3].toFloat(), m[5].toFloat(), 0f, 0f, 1f))
        }
    }

    /**
     * Returns [src] transformed into display orientation. The transform is a pure pixel permutation
     * (no filtering), so it is exact. Unpremultiplied bitmaps are transformed in software because
     * Canvas cannot draw them.
     */
    fun orient(src: Bitmap, o: Orientation, recycleSource: Boolean): Bitmap {
        if (o.isIdentity) return src
        val w = src.width; val h = src.height
        val dw = o.displayWidth(w, h); val dh = o.displayHeight(w, h)
        val dst = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        if (src.colorSpace != null && src.colorSpace != dst.colorSpace) {
            try { dst.setColorSpace(src.colorSpace!!) } catch (_: Exception) { }
        }
        if (src.isPremultiplied || !src.hasAlpha()) {
            dst.setHasAlpha(src.hasAlpha())
            val c = Canvas(dst)
            c.drawBitmap(src, matrix(o, w, h), Paint().apply { isFilterBitmap = false; isDither = false })
        } else {
            dst.isPremultiplied = false
            // Transform in bands of display rows to bound memory.
            val band = maxOf(1, (2 shl 20) / maxOf(1, dw))
            var top = 0
            val out = IntArray(dw * band)
            while (top < dh) {
                val rows = minOf(band, dh - top)
                val r = o.displayRectToRaw(0, top, dw, top + rows, w, h)
                val rw = r[2] - r[0]; val rh = r[3] - r[1]
                val raw = IntArray(rw * rh)
                src.getPixels(raw, 0, rw, r[0], r[1], rw, rh)
                PixelTransforms.orientRows(raw, r[0], r[1], rw, rh, w, h, o, top, rows, out)
                dst.setPixels(out, 0, dw, 0, top, dw, rows)
                top += rows
            }
        }
        if (recycleSource) src.recycle()
        return dst
    }

    /** High-quality scale: halving steps for large reductions, then one bilinear pass. */
    fun scale(src: Bitmap, w: Int, h: Int, recycleSource: Boolean): Bitmap {
        if (src.width == w && src.height == h) return src
        var cur = src
        var curOwned = recycleSource
        while (cur.width / 2 >= w && cur.height / 2 >= h && cur.width / 2 > 0) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (curOwned && next !== cur) cur.recycle()
            cur = next; curOwned = true
        }
        val out = Bitmap.createScaledBitmap(cur, w, h, true)
        if (curOwned && out !== cur) cur.recycle()
        return out
    }

    /** Draws [src] over a solid background (for formats without transparency). */
    fun flatten(src: Bitmap, background: Int, recycleSource: Boolean): Bitmap {
        if (!src.hasAlpha()) return src
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(dst)
        c.drawColor(background)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        dst.setHasAlpha(false)
        if (recycleSource) src.recycle()
        return dst
    }

    /** True if any pixel is not fully opaque (checked in bands). */
    fun hasTransparency(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val w = b.width
        val band = maxOf(1, (1 shl 20) / w)
        val px = IntArray(w * band)
        var y = 0
        while (y < b.height) {
            val n = minOf(band, b.height - y)
            b.getPixels(px, 0, w, 0, y, w, n)
            for (i in 0 until w * n) if ((px[i] ushr 24) != 0xFF) return true
            y += n
        }
        return false
    }
}
