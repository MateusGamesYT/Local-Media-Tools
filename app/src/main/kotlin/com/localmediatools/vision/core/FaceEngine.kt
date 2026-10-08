package com.localmediatools.vision.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import kotlin.math.sqrt

/** A detected face in pixel coordinates of the image it was found in. */
class Face(
    val x: Float, val y: Float, val w: Float, val h: Float,
    val score: Float,
    /** YuNet's raw 15-value row (box, 5 landmarks, score); needed for alignment. */
    val row: FloatArray,
) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
}

/**
 * Face detection (YuNet, MIT) and face embeddings (SFace, Apache-2.0) through OpenCV's DNN module.
 * Both models run on the device; nothing leaves it.
 */
class FaceEngine(yunetPath: String, sfacePath: String?, private val minScore: Float = 0.62f) : AutoCloseable {
    private val detector: FaceDetectorYN = FaceDetectorYN.create(yunetPath, "", Size(320.0, 320.0), minScore, 0.3f, 500)
    private val recognizer: FaceRecognizerSF? = sfacePath?.let { FaceRecognizerSF.create(it, "") }
    val canRecognize get() = recognizer != null

    /** Faces in a BGR image, largest first. Faces smaller than [minSize] px are ignored. */
    fun detect(bgr: Mat, minSize: Float = 12f): List<Face> = synchronized(this) {
        detector.inputSize = Size(bgr.cols().toDouble(), bgr.rows().toDouble())
        val out = Mat()
        try {
            detector.detect(bgr, out)
            val faces = ArrayList<Face>()
            for (r in 0 until out.rows()) {
                val v = FloatArray(15)
                out.get(r, 0, v)
                if (v[14] < minScore || v[2] < minSize || v[3] < minSize) continue
                faces.add(Face(v[0], v[1], v[2], v[3], v[14], v))
            }
            faces.sortedByDescending { it.w * it.h }
        } finally { out.release() }
    }

    /** L2-normalised 128-value identity embedding, or null without the recognition model. */
    fun embed(bgr: Mat, face: Face): FloatArray? = synchronized(this) {
        val rec = recognizer ?: return null
        val row = Mat(1, 15, CvType.CV_32F); row.put(0, 0, face.row)
        val aligned = Mat(); val feat = Mat()
        try {
            rec.alignCrop(bgr, row, aligned)
            rec.feature(aligned, feat)
            val f = FloatArray(feat.total().toInt())
            val f32 = Mat(); feat.convertTo(f32, CvType.CV_32F); f32.get(0, 0, f); f32.release()
            normalize(f)
        } finally { row.release(); aligned.release(); feat.release() }
    }

    /**
     * Embedding averaged with the mirrored face (more stable across head turns; this is how the
     * gallery's people grouping was calibrated). L2-normalised; null without the recognition model.
     */
    fun embedTta(bgr: Mat, face: Face): FloatArray? = synchronized(this) {
        val rec = recognizer ?: return null
        val row = Mat(1, 15, CvType.CV_32F); row.put(0, 0, face.row)
        val aligned = Mat(); val flipped = Mat(); val feat = Mat(); val feat2 = Mat()
        try {
            rec.alignCrop(bgr, row, aligned)
            rec.feature(aligned, feat)
            Core.flip(aligned, flipped, 1)
            rec.feature(flipped, feat2)
            val a = normalize(floats(feat)); val b = normalize(floats(feat2))
            normalize(FloatArray(a.size) { a[it] + b[it] })
        } finally { row.release(); aligned.release(); flipped.release(); feat.release(); feat2.release() }
    }

    private fun floats(m: Mat): FloatArray {
        val f = FloatArray(m.total().toInt())
        val f32 = Mat(); m.convertTo(f32, CvType.CV_32F); f32.get(0, 0, f); f32.release()
        return f
    }

    override fun close() {}

    companion object {
        fun normalize(f: FloatArray): FloatArray {
            var n = 0.0
            for (x in f) n += x * x
            val s = sqrt(n).toFloat().coerceAtLeast(1e-9f)
            for (i in f.indices) f[i] /= s
            return f
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        /** Recommended SFace cosine threshold for "same person". */
        const val SAME_PERSON = 0.40f

        /** Eye distance in pixels and how far the head is turned (0 = frontal) from YuNet's landmarks. */
        fun eyesAndYaw(row: FloatArray): Pair<Float, Float> {
            val rx = row[4]; val ry = row[5]; val lx = row[6]; val ly = row[7]; val nx = row[8]
            val ed = sqrt((lx - rx) * (lx - rx) + (ly - ry) * (ly - ry))
            val yaw = kotlin.math.abs(nx - (rx + lx) / 2) / ed.coerceAtLeast(1e-3f)
            return ed to yaw
        }
    }
}
