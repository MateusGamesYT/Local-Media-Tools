package com.localmediatools.stitch.core

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Work-resolution image with its features. */
class WorkImage(
    val index: Int,
    val rgba: Mat,
    val gray: Mat,
    /** work = full * scale */
    val scale: Double,
    val pts: FloatArray,           // x0, y0, x1, y1 ...
    val descriptors: Mat,          // CV_32F, one row per keypoint
) {
    val w get() = rgba.cols()
    val h get() = rgba.rows()
    val count get() = pts.size / 2
    var embedding: FloatArray? = null

    fun release() { rgba.release(); gray.release(); descriptors.release() }
}

/** A verified pairwise relation: homography mapping work coords of [i] into work coords of [j]. */
class PairLink(
    val i: Int,
    val j: Int,
    val h: DoubleArray,
    /** Inlier correspondences: xi, yi, xj, yj, ... */
    val corr: FloatArray,
    val confidence: Double,
    val method: String,
) {
    val inliers get() = corr.size / 4
}

object Features {
    fun extract(index: Int, rgba: Mat, fullW: Int, maxFeatures: Int): WorkImage {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val kps = MatOfKeyPoint()
        val desc = Mat()
        val sift = SIFT.create(maxFeatures, 3, 0.02, 10.0, 1.6)
        sift.detectAndCompute(gray, Mat(), kps, desc)
        val arr = kps.toArray()
        val pts = FloatArray(arr.size * 2)
        for (k in arr.indices) { pts[k * 2] = arr[k].pt.x.toFloat(); pts[k * 2 + 1] = arr[k].pt.y.toFloat() }
        kps.release()
        val d = if (desc.type() == CvType.CV_32F) desc else Mat().also { desc.convertTo(it, CvType.CV_32F); desc.release() }
        return WorkImage(index, rgba, gray, rgba.cols().toDouble() / fullW, pts, d)
    }
}

/**
 * Pairwise matching and geometric verification. Without AI the matcher is conservative: weak or
 * ambiguous pairs are rejected. With AI assistance, those cases are resolved by comparing learned
 * embeddings of the predicted overlap (and, for texture-poor pairs, by dense patch matching).
 */
class PairMatcher(private val assist: AlignmentAssist?, private val monitor: StitchMonitor) {
    var aiDecisions = 0; private set
    val log = ArrayList<String>()

    private val matcher: DescriptorMatcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED)

    fun match(a: WorkImage, b: WorkImage): PairLink? {
        monitor.checkpoint()
        if (a.count < 8 || b.count < 8) return if (assist != null) dense(a, b) else null
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(a.descriptors, b.descriptors, knn, 2)
        val src = ArrayList<Point>(); val dst = ArrayList<Point>()
        for (m in knn) {
            val arr = m.toArray()
            m.release()
            if (arr.size < 2) continue
            if (arr[0].distance < 0.75f * arr[1].distance) {
                val qi = arr[0].queryIdx; val ti = arr[0].trainIdx
                src.add(Point(a.pts[qi * 2].toDouble(), a.pts[qi * 2 + 1].toDouble()))
                dst.add(Point(b.pts[ti * 2].toDouble(), b.pts[ti * 2 + 1].toDouble()))
            }
        }
        if (src.size < 10) return if (assist != null) dense(a, b) else null
        val first = ransac(src, dst) ?: return if (assist != null) dense(a, b) else null
        val (h1, mask1) = first
        val in1 = mask1.count { it }
        val strong = in1 >= 20 && in1 > 8 + 0.3 * src.size
        val plausible = in1 >= 10 && valid(h1, a, b)
        if (!plausible) return if (assist != null) dense(a, b) else null

        // Look for a competing hypothesis among the remaining matches (repetitive textures).
        val restS = ArrayList<Point>(); val restD = ArrayList<Point>()
        for (k in src.indices) if (!mask1[k]) { restS.add(src[k]); restD.add(dst[k]) }
        var competitor: Pair<DoubleArray, BooleanArray>? = null
        if (restS.size >= 12) {
            val second = ransac(restS, restD)
            if (second != null) {
                val in2 = second.second.count { it }
                if (in2 >= 0.5 * in1 && in2 >= 10 && valid(second.first, a, b) && differs(h1, second.first, a)) competitor = second
            }
        }

        var chosen = h1
        var chosenMask = mask1
        var chosenSrc = src; var chosenDst = dst
        var confidence = in1.toDouble() / (8 + 0.3 * src.size)
        if (competitor != null) {
            if (assist == null) {
                log.add("${a.index + 1}↔${b.index + 1}: ambiguous match (repetitive pattern) rejected")
                return null
            }
            val s1 = overlapScore(a, b, h1)
            val s2 = overlapScore(a, b, competitor.first)
            aiDecisions++
            if (s2 > s1 + 0.03) {
                chosen = competitor.first; chosenMask = competitor.second; chosenSrc = restS; chosenDst = restD
                confidence = competitor.second.count { it }.toDouble() / (8 + 0.3 * src.size)
            } else if (abs(s1 - s2) <= 0.03) {
                log.add("${a.index + 1}↔${b.index + 1}: AI could not separate two alignments; pair skipped")
                return null
            }
            log.add("${a.index + 1}↔${b.index + 1}: AI chose between two candidate alignments")
        } else if (!strong) {
            if (assist == null) return null
            val s = overlapScore(a, b, h1)
            aiDecisions++
            if (s < 0.80) {
                log.add("${a.index + 1}↔${b.index + 1}: weak match rejected by AI check")
                return null
            }
            log.add("${a.index + 1}↔${b.index + 1}: weak match confirmed by AI check")
        }
        val corr = FloatArray(chosenMask.count { it } * 4)
        var o = 0
        for (k in chosenSrc.indices) if (chosenMask[k]) {
            corr[o++] = chosenSrc[k].x.toFloat(); corr[o++] = chosenSrc[k].y.toFloat()
            corr[o++] = chosenDst[k].x.toFloat(); corr[o++] = chosenDst[k].y.toFloat()
        }
        if (!spread(corr, a)) return null
        return PairLink(a.index, b.index, chosen, corr, confidence, "features")
    }

    private fun ransac(src: List<Point>, dst: List<Point>): Pair<DoubleArray, BooleanArray>? {
        val s = MatOfPoint2f(*src.toTypedArray()); val d = MatOfPoint2f(*dst.toTypedArray())
        val mask = Mat()
        val h = try { Calib3d.findHomography(s, d, Calib3d.RANSAC, 3.0, mask, 3000, 0.995) } catch (e: Exception) { null }
        s.release(); d.release()
        if (h == null || h.empty()) { mask.release(); return null }
        val hv = DoubleArray(9); h.get(0, 0, hv); h.release()
        val mk = ByteArray(src.size); mask.get(0, 0, mk); mask.release()
        return M3.normalize(hv) to BooleanArray(src.size) { mk[it].toInt() != 0 }
    }

    /** Rejects degenerate or implausible homographies. */
    fun valid(h: DoubleArray, a: WorkImage, b: WorkImage): Boolean {
        val det2 = h[0] * h[4] - h[1] * h[3]
        if (det2 < 0.15 || det2 > 6.5) return false
        val diag = sqrt((a.w * a.w + a.h * a.h).toDouble())
        if (abs(h[6]) * diag > 0.9 || abs(h[7]) * diag > 0.9) return false
        val c = cornersMapped(h, a) ?: return false
        if (!convex(c)) return false
        // Some overlap with the target image.
        var inside = 0
        val p = DoubleArray(2)
        for (y in 0..8) for (x in 0..8) {
            if (!M3.project(h, a.w * x / 8.0, a.h * y / 8.0, p)) continue
            if (p[0] >= 0 && p[1] >= 0 && p[0] < b.w && p[1] < b.h) inside++
        }
        return inside >= 2
    }

    private fun differs(h1: DoubleArray, h2: DoubleArray, a: WorkImage): Boolean {
        val p = DoubleArray(2); val q = DoubleArray(2)
        var maxD = 0.0
        for ((x, y) in listOf(0.0 to 0.0, a.w.toDouble() to 0.0, 0.0 to a.h.toDouble(), a.w.toDouble() to a.h.toDouble(), a.w / 2.0 to a.h / 2.0)) {
            if (!M3.project(h1, x, y, p) || !M3.project(h2, x, y, q)) return true
            maxD = max(maxD, sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1])))
        }
        return maxD > 0.05 * max(a.w, a.h)
    }

    private fun cornersMapped(h: DoubleArray, a: WorkImage): Array<DoubleArray>? {
        val out = Array(4) { DoubleArray(2) }
        val cs = arrayOf(0.0 to 0.0, a.w.toDouble() to 0.0, a.w.toDouble() to a.h.toDouble(), 0.0 to a.h.toDouble())
        for (k in 0 until 4) if (!M3.project(h, cs[k].first, cs[k].second, out[k])) return null
        return out
    }

    private fun convex(c: Array<DoubleArray>): Boolean {
        var sign = 0
        for (k in 0 until 4) {
            val a = c[k]; val b = c[(k + 1) % 4]; val d = c[(k + 2) % 4]
            val cross = (b[0] - a[0]) * (d[1] - b[1]) - (b[1] - a[1]) * (d[0] - b[0])
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Inliers must cover a reasonable area, otherwise the transform is poorly constrained. */
    private fun spread(corr: FloatArray, a: WorkImage): Boolean {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var k = 0
        while (k < corr.size) {
            minX = min(minX, corr[k]); maxX = max(maxX, corr[k]); minY = min(minY, corr[k + 1]); maxY = max(maxY, corr[k + 1])
            k += 4
        }
        return (maxX - minX) * (maxY - minY) >= 0.004 * a.w * a.h
    }

    // ------------------------------------------------------------------ AI-assisted checks
    /**
     * Semantic agreement of the predicted overlap: image [a] is warped into [b]'s frame and patches
     * inside the overlap are embedded by the on-device model; their mean cosine similarity
     * (blended with photometric correlation) scores the alignment.
     */
    fun overlapScore(a: WorkImage, b: WorkImage, h: DoubleArray): Double {
        val assist = assist ?: return 0.0
        val hm = Mat(3, 3, CvType.CV_64F); hm.put(0, 0, *h)
        val warped = Mat(); val mask = Mat()
        Imgproc.warpPerspective(a.rgba, warped, hm, Size(b.w.toDouble(), b.h.toDouble()))
        val ones = Mat(a.h, a.w, CvType.CV_8UC1, Scalar(255.0))
        Imgproc.warpPerspective(ones, mask, hm, Size(b.w.toDouble(), b.h.toDouble()), Imgproc.INTER_NEAREST)
        ones.release(); hm.release()
        val p = (min(b.w, b.h) / 4).coerceAtLeast(48)
        val scores = ArrayList<Double>()
        val step = p / 2
        var y = 0
        while (y + p <= b.h && scores.size < 6) {
            var x = 0
            while (x + p <= b.w && scores.size < 6) {
                val r = Rect(x, y, p, p)
                val m = mask.submat(r)
                val full = Core.countNonZero(m) >= p * p * 0.98
                m.release()
                if (full) {
                    val pa = warped.submat(r); val pb = b.rgba.submat(r)
                    val ea = assist.embed(pa); val eb = assist.embed(pb)
                    var dot = 0.0
                    for (k in ea.indices) dot += ea[k] * eb[k]
                    val ncc = ncc(pa, pb)
                    pa.release(); pb.release()
                    scores.add(0.75 * dot + 0.25 * ncc)
                    x += p
                } else x += step
            }
            y += step
        }
        warped.release(); mask.release()
        return if (scores.isEmpty()) 0.0 else scores.average()
    }

    private fun ncc(a: Mat, b: Mat): Double {
        val ga = Mat(); val gb = Mat()
        Imgproc.cvtColor(a, ga, Imgproc.COLOR_RGBA2GRAY); Imgproc.cvtColor(b, gb, Imgproc.COLOR_RGBA2GRAY)
        val res = Mat()
        Imgproc.matchTemplate(ga, gb, res, Imgproc.TM_CCOEFF_NORMED)
        val v = res.get(0, 0)[0]
        ga.release(); gb.release(); res.release()
        return if (v.isNaN()) 0.0 else v
    }

    /**
     * Texture-poor fallback: match coarse patches by embedding similarity to get a rough
     * similarity transform, then refine it with intensity-based ECC alignment.
     */
    private fun dense(a: WorkImage, b: WorkImage): PairLink? {
        val assist = assist ?: return null
        val ea = a.embedding; val eb = b.embedding
        if (ea != null && eb != null) {
            var dot = 0.0; for (k in ea.indices) dot += ea[k] * eb[k]
            if (dot < 0.6) return null // the model sees different scenes: not worth trying
        }
        monitor.checkpoint()
        val p = (min(min(a.w, a.h), min(b.w, b.h)) / 3).coerceAtLeast(64)
        fun grid(img: WorkImage, n: Int): List<Rect> {
            val out = ArrayList<Rect>()
            for (gy in 0 until n) for (gx in 0 until n) {
                val x = ((img.w - p) * gx / (n - 1).coerceAtLeast(1)).coerceIn(0, img.w - p)
                val y = ((img.h - p) * gy / (n - 1).coerceAtLeast(1)).coerceIn(0, img.h - p)
                out.add(Rect(x, y, p, p))
            }
            return out
        }
        val ra = grid(a, 4); val rb = grid(b, 7)
        val va = ra.map { r -> a.rgba.submat(r).let { m -> assist.embed(m).also { m.release() } } }
        val vb = rb.map { r -> b.rgba.submat(r).let { m -> assist.embed(m).also { m.release() } } }
        aiDecisions++
        val src = ArrayList<Point>(); val dst = ArrayList<Point>()
        for (i in va.indices) {
            var best = -1.0; var second = -1.0; var bi = -1
            for (j in vb.indices) {
                var d = 0.0; val x = va[i]; val y = vb[j]
                for (k in x.indices) d += x[k] * y[k]
                if (d > best) { second = best; best = d; bi = j } else if (d > second) second = d
            }
            if (best > 0.8 && best - second > 0.015) {
                src.add(Point(ra[i].x + p / 2.0, ra[i].y + p / 2.0)); dst.add(Point(rb[bi].x + p / 2.0, rb[bi].y + p / 2.0))
            }
        }
        if (src.size < 3) return null
        val inl = Mat()
        val sMat = MatOfPoint2f(*src.toTypedArray()); val dMat = MatOfPoint2f(*dst.toTypedArray())
        val sim = Calib3d.estimateAffinePartial2D(sMat, dMat, inl, Calib3d.RANSAC, p / 2.5, 2000, 0.99, 10)
        sMat.release(); dMat.release(); inl.release()
        if (sim == null || sim.empty()) return null
        val sv = DoubleArray(6); sim.get(0, 0, sv); sim.release()
        val coarse = doubleArrayOf(sv[0], sv[1], sv[2], sv[3], sv[4], sv[5], 0.0, 0.0, 1.0)
        // ECC refinement at reduced size: template = b, input = a, warp maps b coords -> a coords.
        val k = min(1.0, 480.0 / max(b.w, b.h))
        val ga = Mat(); val gb = Mat()
        Imgproc.resize(a.gray, ga, Size(a.w * k, a.h * k), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.resize(b.gray, gb, Size(b.w * k, b.h * k), 0.0, 0.0, Imgproc.INTER_AREA)
        val inv = M3.inverse(coarse) ?: return null
        val s = doubleArrayOf(k, 0.0, 0.0, 0.0, k, 0.0, 0.0, 0.0, 1.0)
        val sInv = doubleArrayOf(1 / k, 0.0, 0.0, 0.0, 1 / k, 0.0, 0.0, 0.0, 1.0)
        val w0 = M3.normalize(M3.mul(M3.mul(s, inv), sInv))
        val warp = Mat(3, 3, CvType.CV_32F)
        warp.put(0, 0, FloatArray(9) { w0[it].toFloat() })
        val cc = try {
            Video.findTransformECC(gb, ga, warp, Video.MOTION_HOMOGRAPHY,
                TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 100, 1e-5), Mat(), 5)
        } catch (e: Exception) { -1.0 }
        ga.release(); gb.release()
        if (cc < 0.85) { warp.release(); return null }
        val wf = FloatArray(9); warp.get(0, 0, wf); warp.release()
        val wSmall = DoubleArray(9) { wf[it].toDouble() }
        val bToA = M3.mul(M3.mul(sInv, wSmall), s)
        val h = M3.inverse(bToA)?.let { M3.normalize(it) } ?: return null
        if (!valid(h, a, b)) return null
        // Synthetic correspondences on a grid inside the overlap.
        val corr = ArrayList<Float>()
        val q = DoubleArray(2)
        for (gy in 0..10) for (gx in 0..10) {
            val x = a.w * gx / 10.0; val y = a.h * gy / 10.0
            if (M3.project(h, x, y, q) && q[0] in 0.0..b.w.toDouble() && q[1] in 0.0..b.h.toDouble()) {
                corr.add(x.toFloat()); corr.add(y.toFloat()); corr.add(q[0].toFloat()); corr.add(q[1].toFloat())
            }
        }
        if (corr.size < 4 * 6) return null
        log.add("${a.index + 1}↔${b.index + 1}: aligned by AI dense matching (low texture)")
        return PairLink(a.index, b.index, h, corr.toFloatArray(), cc, "ai-dense")
    }
}
