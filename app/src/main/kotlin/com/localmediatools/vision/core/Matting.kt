package com.localmediatools.vision.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc

/**
 * Subject cut-out: U²-Net-p (Apache-2.0, via rembg) predicts a soft foreground mask at 320×320;
 * [refine] then snaps it to the photo's edges with a guided filter at working resolution.
 */
class Matting(modelPath: String) : AutoCloseable {
    private val net: Net = Dnn.readNetFromONNX(modelPath)

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
