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

/**
 * Face detection (YuNet, MIT) and face embeddings through OpenCV's DNN module: InsightFace's
 * MobileFaceNet "w600k_mbf" (weights for non-commercial research use only, credited in the app),
 * stored with 16-bit weights. Faces are aligned to the standard 112×112 crop ([FaceAlign]) and
 * described by 512 numbers. Both models run on the device; nothing leaves it.
 */
class FaceEngine(yunetPath: String, recognizerPath: String?, private val minScore: Float = 0.62f) : AutoCloseable {
    private val detector: FaceDetectorYN = FaceDetectorYN.create(yunetPath, "", Size(320.0, 320.0), minScore, 0.3f, 500)
    private val recognizer: Net? = recognizerPath?.let { Dnn.readNetFromONNX(it) }
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

    /** Embeddings of aligned 112×112 BGR faces, L2-normalised: RGB scaled to −1…1, as the model was trained. */
    private fun describe(aligned: List<Mat>): Array<FloatArray> {
        val net = recognizer!!
        val blob = Dnn.blobFromImages(aligned, 1.0 / 127.5, Size(FaceAlign.SIZE.toDouble(), FaceAlign.SIZE.toDouble()),
            Scalar(127.5, 127.5, 127.5), true, false)
        try {
            net.setInput(blob)
            val out = net.forward()
            try {
                val f32 = Mat(); out.convertTo(f32, CvType.CV_32F)
                try {
                    val dim = f32.cols()
                    return Array(f32.rows()) { r -> FloatArray(dim).also { f32.get(r, 0, it); normalize(it) } }
                } finally { f32.release() }
            } finally { out.release() }
        } finally { blob.release() }
    }

    /**
     * Whether the recogniser gives the embedding it gave when it was converted, for a fixed pattern
     * (a phone whose OpenCV computes it wrongly gets the basic fallback instead of wrong people).
     */
    fun recognizerWorks(): Boolean = synchronized(this) {
        if (recognizer == null) return false
        val (a, b) = checkPattern()
        try {
            val e = mirrored(a, b)
            cosine(e, CHECK) >= 0.99f
        } finally { a.release(); b.release() }
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

        /**
         * "Same person" for one view of a face (face blur): SFace's 0.40 carried over at the same rate
         * of different people's faces above it (3.6 in 1,000 pairs of the real-people set), where
         * MobileFaceNet finds 75 % of same-person pairs (SFace 74 %).
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

        /** The self-check picture (and its mirror image): a fixed 112×112 BGR pattern. */
        internal fun checkPattern(): Pair<Mat, Mat> {
            val n = FaceAlign.SIZE
            val px = ByteArray(n * n * 3)
            for (y in 0 until n) for (x in 0 until n) for (c in 0 until 3) px[(y * n + x) * 3 + c] = (((x * 7 + y * 13 + c * 50) xor (x * y)) and 255).toByte()
            val a = Mat(n, n, CvType.CV_8UC3); a.put(0, 0, px)
            val b = Mat(); Core.flip(a, b, 1)
            return a to b
        }

        /** The pattern's embedding (face and mirror averaged) as converted (buildtools/gallery/face_model.py), 16-bit floats. */
        private val CHECK: FloatArray by lazy {
            val raw = java.util.Base64.getDecoder().decode(
                "6Co+LN2sHC4UpXKerqJQIzQitaxTqjOhBKT6I86pJ5/rKWIkfqWHK04r/aQjIDEmWxXJJCCnzB1gowwlsKt1KXAiKiogLJ2WoagapWCh+6QMlROkpylQK+YfyapXKPCsLLDNKXcnpqBJG34kcydBJQileqKrIgusGSOwFkWoI6SYLMGo9aNBrBcrIKH7rKgqv6JeJvytjaxAnH8r96grrRUpTa8KJMktDqxwmjQn/SoQqLApw6NUqOCqO6ZEKIyiOqRmJBKp1CNDKHuqjibQodEsvRZgK/8oRasJKC6kl63XotEkk6F1oDmskSrOKTYo7SdHKfQipSecJSSo859vIBOqnq3jpRwiv5kTLe0sr6DCKgeQxKXdJ/wqdpwbLbkd6KrDpTAk05pzqNsoLaq2Ibwst6Ajq2alkaSWLCkmPisvKQUszapsLX0iViBbm6KjGSimqvum+il0oQcq5xhmpG8tjau1j58WmCetHC6j3CcNqIsgJK7aqr8pVajSLAStpSuoqJ6ppa0gH5+mX6tMnVwnLCFHJCwjGyaTKsOkBSvlJq8tmSydKz8saSvKnFAo4aokJvWntarYLPwgnS0ZJ48lXST6qQwoWqSJJJ8oMiidpCqgsKmGLU8nRx47o8elFJdGqGAcbKmYrLCkG6fajC+uT6e/KLkqDi2jJQmdXBKVKimrWJzPJJijmCKaH6Sp6hwMonwlHSzPn3+mfieNIiAlsiPsoFUpliGDLBkp0h7KouoiDCD+rLgqI53sLf2rAKhPpZcUoiJapzkr0yeuqmQnBKcXpgChDp8vqs0kzSgVrkCnXSptIcanJSw9HVUsfRw4rHuoQis8qvcs66U/ICinqSzSI7euDip8KYqoQKtioygpCqqzKZYp3p2pJWsoH6WxIUwuJqXXLiCrm6f4nC8oqq0/pomuaiikJQ0uFq5EJvqd46XhIvmt4ChvKfmnhSU8IvkYXxbnKUYkYKwOqjEojSjYpH6lNhjQrHWiVSyJpOsr4CR2rB6ocyPnKa6luRlko0AaECabKTeYE58dpIWlqSObox8lEix+qnwq/KYOozuiF6UcpcusxyQhnpUthxY6LXAqE5+bK88qB6jCpo0rGi3mkiIp7SyzmT+uDiqNqgwfGyewKlYmA6MCGeotKSFfrAkrs6bSFg+LPSyYqBustKoNKVyoQyWyrDiTDiYvrG0ss6pSKHqn8KWGpXikHi2FJBOwB6ATKi0pHiHLKPcq1iZvLEErDahyrF4dXSk7LJuhoSnCpwIlWhwZpJMvPSyHrMKpFyUZKVios6iTKp2n16nlF2CgCCkMqo6jUiRbqoCbJ6kvoqArBiq8K7sjM6drq+SnGR6SKcWfHabSqQ==")
            normalize(FloatArray(raw.size / 2) { half((raw[2 * it].toInt() and 255) or ((raw[2 * it + 1].toInt() and 255) shl 8)) })
        }

        /** An IEEE 754 half-precision value (16 bits) as a float. */
        internal fun half(h: Int): Float {
            val sign = if (h and 0x8000 != 0) -1f else 1f
            val exp = (h shr 10) and 0x1F; val man = h and 0x3FF
            return sign * when (exp) {
                0 -> man / 1024f * 6.1035156e-5f
                31 -> if (man == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + man / 1024f) * Math.scalb(1f, exp - 15)
            }
        }
    }
}
