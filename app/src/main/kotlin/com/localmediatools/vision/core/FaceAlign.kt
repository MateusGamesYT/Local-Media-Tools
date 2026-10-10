package com.localmediatools.vision.core

/**
 * The standard 112×112 face alignment of ArcFace-style recognisers (the one OpenCV's
 * FaceRecognizerSF.alignCrop does): the similarity transform (rotation, uniform scale, shift) that
 * best maps the five landmarks YuNet finds (eyes, nose tip, mouth corners) onto fixed positions in
 * the crop, in the least-squares sense. The same as OpenCV's to about 1/10,000 of a pixel; the
 * embeddings of faces aligned either way agree to a cosine of 0.99999 (buildtools/gallery/people).
 */
object FaceAlign {
    const val SIZE = 112

    /** Where the right eye, left eye, nose tip, right and left mouth corners go (x, y). */
    private val DST = doubleArrayOf(38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041)
    /** Their mean, as OpenCV has it. */
    private const val DST_MX = 56.0262
    private const val DST_MY = 71.9008

    /**
     * The 2×3 affine matrix (row by row) taking picture coordinates to the crop, from YuNet's
     * 15-value row (landmarks at 4..13).
     */
    fun matrix(row: FloatArray): DoubleArray {
        var mx = 0.0; var my = 0.0
        for (k in 0 until 5) { mx += row[4 + 2 * k]; my += row[5 + 2 * k] }
        mx /= 5; my /= 5
        var n = 0.0; var dot = 0.0; var cross = 0.0
        for (k in 0 until 5) {
            val sx = row[4 + 2 * k] - mx; val sy = row[5 + 2 * k] - my
            val dx = DST[2 * k] - DST_MX; val dy = DST[2 * k + 1] - DST_MY
            n += sx * sx + sy * sy; dot += sx * dx + sy * dy; cross += sx * dy - sy * dx
        }
        if (n < 1e-12) return doubleArrayOf(1.0, 0.0, DST_MX - mx, 0.0, 1.0, DST_MY - my)
        val a = dot / n; val b = cross / n
        return doubleArrayOf(a, -b, DST_MX - (a * mx - b * my), b, a, DST_MY - (b * mx + a * my))
    }
}
