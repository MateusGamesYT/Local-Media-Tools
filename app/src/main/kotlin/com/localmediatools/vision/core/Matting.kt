package com.localmediatools.vision.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Subject cut-out: U²-Net-p (Apache-2.0, via rembg) predicts a soft foreground mask at 320×320;
 * [refine] then snaps it to the photo's edges with a guided filter at working resolution. [cutout]
 * is the whole pipeline the app uses.
 */
class Matting(modelPath: String) : AutoCloseable {
    private val net: Net = Dnn.readNetFromONNX(modelPath)

    /**
     * Subject mask (0..1) for an ARGB picture of [w]×[h] (the app's working size, ≤ 1024 px): the
     * model looks at the photo and at its mirror image (averaged), looks again at the subject cropped
     * when it is clearly smaller than the photo, and the result is fitted to the photo's edges.
     * Measured on AIM-500 (buildtools/matting/README.md): about 8 % less error than one look.
     */
    fun cutout(px: IntArray, w: Int, h: Int): FloatArray {
        val c = closerLook(px, w, h)
        val r = max(4, (min(w, h) * REFINE_RADIUS).roundToInt())
        return refine(luma(px), c, w, h, r)
    }

    /** The model's mask at [SIZE]² for an ARGB picture, averaged with the mask of its mirror image. */
    private fun predictBoth(px: IntArray, w: Int, h: Int): FloatArray {
        val a = rgbMat(px, w, h, false).let { m -> try { predict(m) } finally { m.release() } }
        val b = rgbMat(px, w, h, true).let { m -> try { predict(m) } finally { m.release() } }
        return FloatArray(SIZE * SIZE) { i -> (a[i] + b[(i / SIZE) * SIZE + (SIZE - 1 - i % SIZE)]) / 2 }
    }

    /** The coarse mask at the picture's size, the subject's area replaced by a closer look at it. */
    private fun closerLook(px: IntArray, w: Int, h: Int): FloatArray {
        val first = resize(predictBoth(px, w, h), SIZE, SIZE, w, h)
        val box = bounds(first, w, h, 0.25f) ?: return first
        val bw = box[2] - box[0]; val bh = box[3] - box[1]
        val mx = (bw * 0.12f).roundToInt() + 8; val my = (bh * 0.12f).roundToInt() + 8
        val l = max(0, box[0] - mx); val t = max(0, box[1] - my); val r = min(w, box[2] + mx); val b = min(h, box[3] + my)
        val cw = r - l; val ch = b - t
        if (cw * ch > 0.7 * w * h || cw < 16 || ch < 16) return first
        val crop = IntArray(cw * ch) { i -> px[(t + i / cw) * w + l + i % cw] }
        val second = resize(predictBoth(crop, cw, ch), SIZE, SIZE, cw, ch)
        val out = first.copyOf()
        // Blended over a band inside the crop's edges, except where the crop meets the photo's edge.
        val band = max(4, min(cw, ch) / 20)
        for (y in 0 until ch) for (x in 0 until cw) {
            val d = min(min(x, cw - 1 - x), min(y, ch - 1 - y))
            val atPhotoEdge = (x == 0 && l == 0) || (y == 0 && t == 0) || (x == cw - 1 && r == w) || (y == ch - 1 && b == h)
            val k = if (atPhotoEdge) 1f else min(1f, d.toFloat() / band)
            val i = (t + y) * w + l + x
            out[i] = first[i] * (1 - k) + second[y * cw + x] * k
        }
        return out
    }

    /** Foreground probability (0..1) at [SIZE]² for an RGB (CV_8UC3) image of any size. */
    fun predict(rgb: Mat): FloatArray = synchronized(this) {
        val small = Mat(); val f = Mat()
        try {
            Imgproc.resize(rgb, small, Size(SIZE.toDouble(), SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            small.convertTo(f, CvType.CV_32FC3, 1.0 / 255)
            // rembg: scale by the image maximum, then ImageNet mean/std.
            val mm = Core.minMaxLoc(f.reshape(1))
            if (mm.maxVal > 0) Core.multiply(f, Scalar(1 / mm.maxVal, 1 / mm.maxVal, 1 / mm.maxVal), f)
            Core.subtract(f, Scalar(0.485, 0.456, 0.406), f)
            Core.divide(f, Scalar(0.229, 0.224, 0.225), f)
            val blob = Dnn.blobFromImage(f)
            net.setInput(blob)
            val out = net.forward()
            blob.release()
            val v = FloatArray(SIZE * SIZE)
            out.reshape(1, 1).get(0, 0, v)
            out.release()
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (x in v) { if (x < lo) lo = x; if (x > hi) hi = x }
            val d = (hi - lo).coerceAtLeast(1e-6f)
            for (i in v.indices) v[i] = (v[i] - lo) / d
            v
        } finally { small.release(); f.release() }
    }

    override fun close() {}

    companion object {
        const val SIZE = 320

        /** Guided-filter radius as a fraction of the short side (AIM-500: 3.6 % beat the 1.2 % used until 1.8). */
        const val REFINE_RADIUS = 0.036f

        fun luma(px: IntArray) = FloatArray(px.size) { i -> val c = px[i]; (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f }

        /** An RGB (CV_8UC3) Mat of an ARGB picture, mirrored left to right with [flip]. */
        fun rgbMat(px: IntArray, w: Int, h: Int, flip: Boolean): Mat {
            val bytes = ByteArray(w * h * 3)
            for (y in 0 until h) for (x in 0 until w) {
                val c = px[y * w + (if (flip) w - 1 - x else x)]; val o = (y * w + x) * 3
                bytes[o] = (c shr 16).toByte(); bytes[o + 1] = (c shr 8).toByte(); bytes[o + 2] = c.toByte()
            }
            return Mat(h, w, CvType.CV_8UC3).also { it.put(0, 0, bytes) }
        }

        /** Bilinear resize of a float map. */
        fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
            val out = FloatArray(dw * dh)
            for (y in 0 until dh) {
                val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                val y0 = fy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val ty = fy - y0
                for (x in 0 until dw) {
                    val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                    val x0 = fx.toInt(); val x1 = minOf(x0 + 1, sw - 1); val tx = fx - x0
                    val a = src[y0 * sw + x0] + (src[y0 * sw + x1] - src[y0 * sw + x0]) * tx
                    val b = src[y1 * sw + x0] + (src[y1 * sw + x1] - src[y1 * sw + x0]) * tx
                    out[y * dw + x] = a + (b - a) * ty
                }
            }
            return out
        }

        private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
            val tmp = FloatArray(w * h); val out = FloatArray(w * h)
            val n = (2 * r + 1).toFloat()
            for (y in 0 until h) {
                var s = 0f
                for (i in -r..r) s += src[y * w + i.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[y * w + x] = s / n
                    s += src[y * w + (x + r + 1).coerceAtMost(w - 1)] - src[y * w + (x - r).coerceAtLeast(0)]
                }
            }
            for (x in 0 until w) {
                var s = 0f
                for (i in -r..r) s += tmp[i.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    out[y * w + x] = s / n
                    s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
                }
            }
            return out
        }

        /**
         * Guided filter (He et al.): fits the mask to the edges of [guide] (luma 0..1), then a
         * contrast curve makes the subject solid and the background clear while keeping soft edges.
         */
        fun refine(guide: FloatArray, coarse: FloatArray, w: Int, h: Int, radius: Int, eps: Float = 1e-3f): FloatArray {
            val n = w * h
            val mI = box(guide, w, h, radius)
            val mp = box(coarse, w, h, radius)
            val ip = FloatArray(n) { guide[it] * coarse[it] }
            val ii = FloatArray(n) { guide[it] * guide[it] }
            val mIp = box(ip, w, h, radius); val mII = box(ii, w, h, radius)
            val a = FloatArray(n); val b = FloatArray(n)
            for (i in 0 until n) {
                val cov = mIp[i] - mI[i] * mp[i]; val v = mII[i] - mI[i] * mI[i]
                a[i] = cov / (v + eps); b[i] = mp[i] - a[i] * mI[i]
            }
            val ma = box(a, w, h, radius); val mb = box(b, w, h, radius)
            return FloatArray(n) { i ->
                val q = (ma[i] * guide[i] + mb[i]).coerceIn(0f, 1f)
                val t = ((q - 0.2f) / 0.6f).coerceIn(0f, 1f)
                t * t * (3 - 2 * t)
            }
        }

        /** Bounds (l, t, r, b) of pixels with alpha above [threshold], or null if there are none. */
        fun bounds(alpha: FloatArray, w: Int, h: Int, threshold: Float = 0.1f): IntArray? {
            var l = w; var t = h; var r = -1; var b = -1
            for (y in 0 until h) for (x in 0 until w) if (alpha[y * w + x] > threshold) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
            return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
        }
    }
}
