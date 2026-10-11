package com.localmediatools.vision.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
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

/** Turns aligned 112×112 BGR faces ([FaceAlign]) into L2-normalised embeddings, one per face. */
interface FaceEmbedder {
    fun describe(aligned: List<Mat>): Array<FloatArray>
    fun close() {}
}

/** An ONNX face model through OpenCV's DNN module (MobileFaceNet with 16-bit weights). */
class OpenCvFaceEmbedder(path: String) : FaceEmbedder {
    private val net: Net = Dnn.readNetFromONNX(path)

    /** RGB scaled to −1…1, as the model was trained. */
    override fun describe(aligned: List<Mat>): Array<FloatArray> {
        val blob = Dnn.blobFromImages(aligned, 1.0 / 127.5, Size(FaceAlign.SIZE.toDouble(), FaceAlign.SIZE.toDouble()),
            Scalar(127.5, 127.5, 127.5), true, false)
        try {
            net.setInput(blob)
            val out = net.forward()
            try {
                val f32 = Mat(); out.convertTo(f32, CvType.CV_32F)
                try {
                    val dim = f32.cols()
                    return Array(f32.rows()) { r -> FloatArray(dim).also { f32.get(r, 0, it); FaceEngine.normalize(it) } }
                } finally { f32.release() }
            } finally { out.release() }
        } finally { blob.release() }
    }
}

/** How a TensorFlow Lite face model takes a face: 112×112×3, RGB, (x − 127.5) / 127.5, row by row. */
object FaceInput {
    fun nhwc(aligned: Mat): FloatArray {
        val n = FaceAlign.SIZE
        require(aligned.rows() == n && aligned.cols() == n && aligned.type() == CvType.CV_8UC3) { "not an aligned face" }
        val px = ByteArray(n * n * 3); aligned.get(0, 0, px)
        val out = FloatArray(px.size)
        for (i in 0 until n * n) for (c in 0 until 3) out[i * 3 + c] = ((px[i * 3 + 2 - c].toInt() and 255) - 127.5f) / 127.5f
        return out
    }
}

/**
 * Face detection (YuNet, MIT) and face embeddings with one of the InsightFace models ([FaceModel];
 * weights for non-commercial research use only, credited in the app): ResNet-50 with 8-bit weights
 * through TensorFlow Lite for the Gallery, MobileFaceNet with 16-bit weights through OpenCV's DNN
 * module for face blur. Faces are aligned to the standard 112×112 crop ([FaceAlign]) and described by
 * 512 numbers. Both models run on the device; nothing leaves it.
 */
class FaceEngine(yunetPath: String, private val recognizer: FaceEmbedder?, val model: FaceModel = FaceModel.MBF, private val minScore: Float = 0.62f) : AutoCloseable {
    /** With an ONNX model run by OpenCV (MobileFaceNet). */
    constructor(yunetPath: String, recognizerPath: String?, model: FaceModel = FaceModel.MBF) :
        this(yunetPath, recognizerPath?.let { OpenCvFaceEmbedder(it) }, model)

    private val detector: FaceDetectorYN = FaceDetectorYN.create(yunetPath, "", Size(320.0, 320.0), minScore, 0.3f, 500)
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

    /** The face aligned to the recogniser's 112×112 crop (BGR). */
    fun align(bgr: Mat, face: Face): Mat {
        val m = Mat(2, 3, CvType.CV_64F); m.put(0, 0, *FaceAlign.matrix(face.row))
        val out = Mat()
        try { Imgproc.warpAffine(bgr, out, m, Size(FaceAlign.SIZE.toDouble(), FaceAlign.SIZE.toDouble()), Imgproc.INTER_LINEAR) } finally { m.release() }
        return out
    }

    /** L2-normalised identity embedding of one view of the face, or null without the recognition model. */
    fun embed(bgr: Mat, face: Face): FloatArray? = synchronized(this) {
        if (recognizer == null) return null
        val aligned = align(bgr, face)
        try { describe(listOf(aligned))[0] } finally { aligned.release() }
    }

    /**
     * Embedding averaged with the mirrored face (more stable across head turns; this is how the
     * gallery's people grouping was calibrated). L2-normalised; null without the recognition model.
     */
    fun embedTta(bgr: Mat, face: Face): FloatArray? = synchronized(this) {
        if (recognizer == null) return null
        val aligned = align(bgr, face); val flipped = Mat()
        try {
            Core.flip(aligned, flipped, 1)
            mirrored(aligned, flipped)
        } finally { aligned.release(); flipped.release() }
    }

    private fun mirrored(a: Mat, b: Mat): FloatArray {
        val (x, y) = describe(listOf(a, b))
        return normalize(FloatArray(x.size) { x[it] + y[it] })
    }

    private fun describe(aligned: List<Mat>): Array<FloatArray> = recognizer!!.describe(aligned)

    /**
     * Whether the recogniser gives the embedding it gave when it was converted, for a fixed pattern
     * (a phone that computes it wrongly gets the basic fallback instead of wrong people).
     */
    fun recognizerWorks(): Boolean = synchronized(this) {
        if (recognizer == null) return false
        val (a, b) = FaceModel.checkPattern()
        try {
            val e = mirrored(a, b)
            cosine(e, model.expected) >= 0.99f
        } finally { a.release(); b.release() }
    }

    override fun close() { synchronized(this) { recognizer?.close() } }

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

        /**
         * "Same person" for one view of a face (face blur, MobileFaceNet): SFace's 0.40 carried over at
         * the same rate of different people's faces above it (3.6 in 1,000 pairs of the real-people
         * set), where MobileFaceNet finds 75 % of same-person pairs (SFace 74 %).
         */
        const val SAME_PERSON = 0.29f

        /** Below this, two faces in consecutive video frames are not the same face (SFace's 0.20, carried over the same way). */
        const val TRACK_GATE = 0.10f

        /** Eye distance in pixels and how far the head is turned (0 = frontal) from YuNet's landmarks. */
        fun eyesAndYaw(row: FloatArray): Pair<Float, Float> {
            val rx = row[4]; val ry = row[5]; val lx = row[6]; val ly = row[7]; val nx = row[8]
            val ed = sqrt((lx - rx) * (lx - rx) + (ly - ry) * (ly - ry))
            val yaw = kotlin.math.abs(nx - (rx + lx) / 2) / ed.coerceAtLeast(1e-3f)
            return ed to yaw
        }
    }
}
