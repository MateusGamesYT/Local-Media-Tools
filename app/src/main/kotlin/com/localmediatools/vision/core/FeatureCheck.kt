package com.localmediatools.vision.core

import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB

/**
 * Whether two pictures show the same scene: ORB features (500 at most, on a grey thumbnail of
 * about 256–512 px) matched with Lowe's ratio test (0.8), then a homography (RANSAC, 4 px). Shots of
 * the same moment share dozens of features that agree on one geometry; look-alikes (two skies, two
 * pages of text) rarely do. Used by [Duplicates] as a second signal next to the embedding.
 */
class FeatureCheck {
    /** Features of one picture. Release with [release]. */
    class Features internal constructor(val points: FloatArray, internal val descriptors: Mat?, val width: Int, val height: Int) {
        fun release() { descriptors?.release() }
    }

    private val orb = ORB.create(500, 1.2f, 6, 15, 0, 2, ORB.HARRIS_SCORE, 15, 20)
    private val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

    /** Features of an 8-bit grey picture. */
    fun features(gray: Mat): Features = synchronized(this) {
        val kp = MatOfKeyPoint()
        val desc = Mat()
        orb.detectAndCompute(gray, Mat(), kp, desc)
        val list = kp.toArray()
        kp.release()
        val pts = FloatArray(list.size * 2)
        for ((k, p) in list.withIndex()) { pts[2 * k] = p.pt.x.toFloat(); pts[2 * k + 1] = p.pt.y.toFloat() }
        if (desc.empty()) { desc.release(); Features(pts, null, gray.cols(), gray.rows()) } else Features(pts, desc, gray.cols(), gray.rows())
    }

    /** Inliers of the best homography from [a] to [b], and the share of [a] they span. */
    fun compare(a: Features, b: Features): Duplicates.Check = synchronized(this) {
        val da = a.descriptors; val db = b.descriptors
        if (da == null || db == null || a.points.size < 16 || b.points.size < 16) return Duplicates.Check(0, 0f)
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(da, db, knn, 2)
        val src = ArrayList<Point>(); val dst = ArrayList<Point>()
        for (m in knn) {
            val two = m.toArray()
            m.release()
            if (two.size == 2 && two[0].distance < 0.8f * two[1].distance) {
                val q = two[0].queryIdx; val t = two[0].trainIdx
                src.add(Point(a.points[2 * q].toDouble(), a.points[2 * q + 1].toDouble()))
                dst.add(Point(b.points[2 * t].toDouble(), b.points[2 * t + 1].toDouble()))
            }
        }
        if (src.size < 8) return Duplicates.Check(src.size / 4, 0f)
        val ms = MatOfPoint2f(*src.toTypedArray()); val md = MatOfPoint2f(*dst.toTypedArray())
        val mask = Mat()
        try {
            val h = Calib3d.findHomography(ms, md, Calib3d.RANSAC, 4.0, mask)
            if (h.empty()) { h.release(); return Duplicates.Check(0, 0f) }
            h.release()
            var n = 0
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -1.0; var y1 = -1.0
            val flags = ByteArray(mask.rows() * mask.cols())
            mask.get(0, 0, flags)
            for ((k, f) in flags.withIndex()) if (f.toInt() != 0) {
                n++
                val p = src[k]
                x0 = minOf(x0, p.x); y0 = minOf(y0, p.y); x1 = maxOf(x1, p.x); y1 = maxOf(y1, p.y)
            }
            val cover = if (n > 0) ((x1 - x0) * (y1 - y0) / (a.width.toDouble() * a.height)).toFloat() else 0f
            return Duplicates.Check(n, cover)
        } finally { ms.release(); md.release(); mask.release() }
    }
}
