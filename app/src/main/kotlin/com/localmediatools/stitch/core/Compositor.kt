package com.localmediatools.stitch.core

import com.localmediatools.codec.layout.LargestRectangle
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Renders the stitched panorama in horizontal strips: for every strip each overlapping photo is
 * resampled from its full-resolution pixels (OpenCV remap), exposure-compensated and blended with
 * feathered weights. Memory use is bounded by the strip size, not by the panorama size.
 */
class Compositor(
    private val images: StitchImages,
    private val nodes: List<Int>,
    private val monitor: StitchMonitor,
) {
    class Layout(val warp: Warp, val width: Int, val height: Int, val scale: Double, val notes: List<String>)

    /** Output bounds of all images, optional size cap. */
    fun layout(baseWarp: Warp, maxPixels: Long): Layout {
        val notes = ArrayList<String>()
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        val p = DoubleArray(2)
        for (i in nodes) {
            val (w, h) = images.fullSize(i).let { it[0].toDouble() to it[1].toDouble() }
            val steps = 48
            for (k in 0..steps) {
                val t = k.toDouble() / steps
                for ((u, v) in listOf(t * w to 0.0, t * w to h, 0.0 to t * h, w to t * h)) {
                    if (baseWarp.fullToOut(i, u, v, p)) {
                        minX = min(minX, p[0]); maxX = max(maxX, p[0]); minY = min(minY, p[1]); maxY = max(maxY, p[1])
                    }
                }
            }
        }
        if (minX >= maxX || minY >= maxY) throw StitchException("The aligned photos produced an empty result.")
        var scale = 1.0
        val w0 = maxX - minX; val h0 = maxY - minY
        if (w0 * h0 > maxPixels) {
            scale = sqrt(maxPixels / (w0 * h0))
            notes.add("The panorama was reduced to ${(scale * 100).toInt()}% of full resolution to stay within the size limit.")
        }
        if (w0 * scale > 65000 || h0 * scale > 65000) {
            val s2 = 65000 / max(w0, h0)
            if (s2 < scale) { scale = s2; notes.add("The panorama was scaled to fit PNG viewers' 65000 px limit.") }
        }
        val warp = baseWarp.withOutputTransform(scale, minX * scale, minY * scale)
        return Layout(warp, ceil(w0 * scale).toInt().coerceAtLeast(1), ceil(h0 * scale).toInt().coerceAtLeast(1), scale, notes)
    }

    /** Largest axis-aligned rectangle fully covered by photos (computed on a reduced grid). */
    fun cropRect(l: Layout): IntArray {
        val m = min(1.0, 1200.0 / max(l.width, l.height))
        val gw = max(1, (l.width * m).toInt()); val gh = max(1, (l.height * m).toInt())
        val mask = BooleanArray(gw * gh)
        val p = DoubleArray(2)
        val sizes = nodes.associateWith { images.fullSize(it) }
        for (y in 0 until gh) {
            monitor.checkpoint()
            for (x in 0 until gw) {
                val ox = (x + 0.5) / m; val oy = (y + 0.5) / m
                for (i in nodes) {
                    if (l.warp.outToFull(i, ox, oy, p)) {
                        val s = sizes[i]!!
                        if (p[0] >= 1 && p[1] >= 1 && p[0] <= s[0] - 1 && p[1] <= s[1] - 1) { mask[y * gw + x] = true; break }
                    }
                }
            }
        }
        val r = LargestRectangle.find(mask, gw, gh) ?: return intArrayOf(0, 0, l.width, l.height)
        // Shrink by one grid cell on each side so no empty pixel survives rounding.
        val x0 = ceil((r.x + 1) / m).toInt(); val y0 = ceil((r.y + 1) / m).toInt()
        val x1 = floor((r.x + r.w - 1) / m).toInt(); val y1 = floor((r.y + r.h - 1) / m).toInt()
        if (x1 - x0 < 16 || y1 - y0 < 16) return intArrayOf(0, 0, l.width, l.height)
        return intArrayOf(x0.coerceIn(0, l.width - 1), y0.coerceIn(0, l.height - 1), x1.coerceAtMost(l.width), y1.coerceAtMost(l.height))
    }

    /**
     * Exposure gains (Brown & Lowe): equalise mean intensities of matched neighbourhoods while
     * keeping gains close to 1.
     */
    fun gains(work: Map<Int, WorkImage>, links: List<PairLink>): Map<Int, Double> {
        val n = nodes.size
        val pos = HashMap<Int, Int>(); nodes.forEachIndexed { k, v -> pos[v] = k }
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        val sigmaN = 10.0; val sigmaG = 0.1
        for (l in links) {
            val pi = pos[l.i] ?: continue; val pj = pos[l.j] ?: continue
            val gi = work[l.i]!!.gray; val gj = work[l.j]!!.gray
            var si = 0.0; var sj = 0.0; var cnt = 0
            val bufI = ByteArray(25); val bufJ = ByteArray(25)
            var k = 0
            while (k < l.corr.size) {
                val xi = l.corr[k].toInt(); val yi = l.corr[k + 1].toInt(); val xj = l.corr[k + 2].toInt(); val yj = l.corr[k + 3].toInt()
                if (xi in 2 until gi.cols() - 2 && yi in 2 until gi.rows() - 2 && xj in 2 until gj.cols() - 2 && yj in 2 until gj.rows() - 2) {
                    var mi = 0.0; var mj = 0.0
                    for (dy in -2..2) {
                        gi.get(yi + dy, xi - 2, bufI); gj.get(yj + dy, xj - 2, bufJ)
                        for (dx in 0 until 5) { mi += bufI[dx].toInt() and 0xFF; mj += bufJ[dx].toInt() and 0xFF }
                    }
                    si += mi / 25; sj += mj / 25; cnt++
                }
                k += 4 * maxOf(1, l.inliers / 150)
            }
            if (cnt == 0) continue
            val iij = si / cnt; val iji = sj / cnt
            val nij = cnt.toDouble()
            a[pi][pi] += nij * (iij * iij / (sigmaN * sigmaN) + 1 / (sigmaG * sigmaG))
            a[pi][pj] -= nij * iij * iji / (sigmaN * sigmaN)
            b[pi] += nij / (sigmaG * sigmaG)
            a[pj][pj] += nij * (iji * iji / (sigmaN * sigmaN) + 1 / (sigmaG * sigmaG))
            a[pj][pi] -= nij * iij * iji / (sigmaN * sigmaN)
            b[pj] += nij / (sigmaG * sigmaG)
        }
        for (i in 0 until n) if (a[i][i] == 0.0) { a[i][i] = 1.0; b[i] = 1.0 }
        val g = Dense.solve(a, b) ?: DoubleArray(n) { 1.0 }
        return nodes.associateWith { g[pos[it]!!].coerceIn(0.5, 2.0) }
    }

    /**
     * Renders [crop] (x0, y0, x1, y1 in layout pixels) to [sink]. [budget] bounds the decoded
     * source pixels per strip.
     */
    fun render(l: Layout, crop: IntArray, gains: Map<Int, Double>, transparentEdges: Boolean, budget: Long, sink: StitchSink) {
        val outW = crop[2] - crop[0]; val outH = crop[3] - crop[1]
        sink.begin(outW, outH, transparentEdges)
        val sizes = nodes.associateWith { images.fullSize(it) }
        // Output-space bounding boxes of every photo.
        val boxes = HashMap<Int, DoubleArray>()
        val p = DoubleArray(2)
        for (i in nodes) {
            val s = sizes[i]!!
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (k in 0..32) {
                val t = k / 32.0
                for ((u, v) in listOf(t * s[0] to 0.0, t * s[0] to s[1].toDouble(), 0.0 to t * s[1], s[0].toDouble() to t * s[1])) {
                    if (l.warp.fullToOut(i, u, v, p)) { x0 = min(x0, p[0]); x1 = max(x1, p[0]); y0 = min(y0, p[1]); y1 = max(y1, p[1]) }
                }
            }
            boxes[i] = doubleArrayOf(x0 - crop[0], y0 - crop[1], x1 - crop[0], y1 - crop[1])
        }
        // Resampling ratio: output pixels per source pixel (used to decode at reduced resolution).
        val ratio = estimateRatio(l, sizes)
        val stripRows = (budget / 6 / (outW.toLong() * 24)).toInt().coerceIn(16, 512).coerceAtMost(outH)
        val sumR = FloatArray(outW * stripRows); val sumG = FloatArray(sumR.size); val sumB = FloatArray(sumR.size); val sumW = FloatArray(sumR.size)
        val mapX = FloatArray(sumR.size); val mapY = FloatArray(sumR.size); val wts = FloatArray(sumR.size)
        val argb = IntArray(sumR.size)
        var y = 0
        while (y < outH) {
            val rows = min(stripRows, outH - y)
            java.util.Arrays.fill(sumR, 0f); java.util.Arrays.fill(sumG, 0f); java.util.Arrays.fill(sumB, 0f); java.util.Arrays.fill(sumW, 0f)
            for (i in nodes) {
                monitor.checkpoint()
                val bx = boxes[i]!!
                if (bx[3] < y || bx[1] > y + rows || bx[2] < 0 || bx[0] > outW) continue
                val s = sizes[i]!!
                val xa = max(0, floor(bx[0]).toInt()); val xb = min(outW, ceil(bx[2]).toInt())
                if (xb <= xa) continue
                // Source coordinates for every output pixel in the strip's x-range.
                var su0 = Double.MAX_VALUE; var sv0 = Double.MAX_VALUE; var su1 = -Double.MAX_VALUE; var sv1 = -Double.MAX_VALUE
                var any = false
                val cw = xb - xa
                for (r in 0 until rows) {
                    val oy = y + r + crop[1] + 0.5
                    for (c in 0 until cw) {
                        val idx = r * cw + c
                        val ox = xa + c + crop[0] + 0.5
                        if (l.warp.outToFull(i, ox, oy, p) && p[0] >= 0 && p[1] >= 0 && p[0] <= s[0] && p[1] <= s[1]) {
                            mapX[idx] = p[0].toFloat(); mapY[idx] = p[1].toFloat()
                            // Feather weight: distance to the nearest image edge, emphasised.
                            val e = min(min(p[0], s[0] - p[0]), min(p[1], s[1] - p[1])) / (0.5 * min(s[0], s[1]))
                            val w = e.coerceIn(0.0, 1.0)
                            wts[idx] = (w * w * w * w).toFloat().coerceAtLeast(1e-6f)
                            su0 = min(su0, p[0]); su1 = max(su1, p[0]); sv0 = min(sv0, p[1]); sv1 = max(sv1, p[1])
                            any = true
                        } else {
                            mapX[idx] = -1f; mapY[idx] = -1f; wts[idx] = 0f
                        }
                    }
                }
                if (!any) continue
                val sample = sampleFor(ratio[i] ?: 1.0)
                val rx = max(0, floor(su0).toInt() - 2); val ry = max(0, floor(sv0).toInt() - 2)
                val rx1 = min(s[0], ceil(su1).toInt() + 3); val ry1 = min(s[1], ceil(sv1).toInt() + 3)
                if (rx1 <= rx || ry1 <= ry) continue
                val src = images.region(i, rx, ry, rx1 - rx, ry1 - ry, sample)
                val sx = src.cols().toDouble() / (rx1 - rx); val sy = src.rows().toDouble() / (ry1 - ry)
                val mx = Mat(rows, cw, CvType.CV_32FC1); val my = Mat(rows, cw, CvType.CV_32FC1)
                val fx = FloatArray(rows * cw); val fy = FloatArray(rows * cw)
                for (k in 0 until rows * cw) {
                    if (mapX[k] < 0) { fx[k] = -10f; fy[k] = -10f } else {
                        fx[k] = ((mapX[k] - rx) * sx - 0.5).toFloat(); fy[k] = ((mapY[k] - ry) * sy - 0.5).toFloat()
                    }
                }
                mx.put(0, 0, fx); my.put(0, 0, fy)
                val dst = Mat()
                Imgproc.remap(src, dst, mx, my, Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE, Scalar(0.0, 0.0, 0.0, 0.0))
                src.release(); mx.release(); my.release()
                val px = ByteArray(rows * cw * 4)
                dst.get(0, 0, px)
                dst.release()
                val g = (gains[i] ?: 1.0).toFloat()
                for (r in 0 until rows) {
                    val outBase = r * outW + xa
                    for (c in 0 until cw) {
                        val k = r * cw + c
                        val w = wts[k]
                        if (w <= 0f) continue
                        val o = k * 4
                        sumR[outBase + c] += w * g * (px[o].toInt() and 0xFF)
                        sumG[outBase + c] += w * g * (px[o + 1].toInt() and 0xFF)
                        sumB[outBase + c] += w * g * (px[o + 2].toInt() and 0xFF)
                        sumW[outBase + c] += w
                    }
                }
            }
            for (k in 0 until rows * outW) {
                val w = sumW[k]
                argb[k] = if (w <= 0f) (if (transparentEdges) 0 else 0xFF000000.toInt()) else {
                    val r = (sumR[k] / w + 0.5f).toInt().coerceIn(0, 255)
                    val gg = (sumG[k] / w + 0.5f).toInt().coerceIn(0, 255)
                    val b = (sumB[k] / w + 0.5f).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
                }
            }
            sink.rows(y, rows, argb)
            y += rows
            monitor.stage("Rendering", y.toDouble() / outH)
        }
        sink.finish()
    }

    private fun sampleFor(ratio: Double): Int {
        var s = 1
        while (s * 2 <= 1.0 / ratio.coerceAtLeast(1e-6) && s < 16) s *= 2
        return s
    }

    /** Output pixels per source pixel near each image centre. */
    private fun estimateRatio(l: Layout, sizes: Map<Int, IntArray>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        val a = DoubleArray(2); val b = DoubleArray(2)
        for (i in nodes) {
            val s = sizes[i]!!
            val cx = s[0] / 2.0; val cy = s[1] / 2.0; val d = min(s[0], s[1]) / 10.0
            if (l.warp.fullToOut(i, cx - d, cy, a) && l.warp.fullToOut(i, cx + d, cy, b)) {
                out[i] = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])) / (2 * d)
            }
        }
        return out
    }
}
