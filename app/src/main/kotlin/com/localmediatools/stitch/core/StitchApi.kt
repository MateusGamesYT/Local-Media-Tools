package com.localmediatools.stitch.core

import org.opencv.core.Mat

/** Source photos for the stitcher. All images are in display orientation. */
interface StitchImages {
    val count: Int
    fun name(i: Int): String
    /** Full-resolution size (display orientation). */
    fun fullSize(i: Int): IntArray
    /** RGBA (CV_8UC4) image scaled so its longer side is at most [maxSide]. */
    fun workImage(i: Int, maxSide: Int): Mat
    /**
     * RGBA (CV_8UC4) crop of the full-resolution image: [x, y, w, h] in full-res pixels, decoded at
     * 1/[sample] resolution (the returned Mat is about w/sample x h/sample).
     */
    fun region(i: Int, x: Int, y: Int, w: Int, h: Int, sample: Int): Mat
    /** 35 mm-equivalent focal length from metadata, or 0 when unknown. */
    fun focal35mm(i: Int): Double = 0.0
}

/** Optional on-device AI used for ambiguous alignment decisions. */
interface AlignmentAssist {
    /** L2-normalised embedding of an RGBA patch (any size). */
    fun embed(rgba: Mat): FloatArray
    val name: String
}

/** Receives the stitched result row by row (non-premultiplied ARGB). */
interface StitchSink {
    fun begin(width: Int, height: Int, hasTransparency: Boolean)
    fun rows(y: Int, count: Int, argb: IntArray)
    fun finish()
}

enum class SceneMode { AUTO, FLAT, PANORAMA }

data class StitchOptions(
    val mode: SceneMode = SceneMode.AUTO,
    val useAi: Boolean = false,
    val cropToRectangle: Boolean = true,
    /** Longest side of the images used for matching. */
    val workMaxSide: Int = 1400,
    /** Upper bound for the output size; larger results are scaled down uniformly. */
    val maxOutputPixels: Long = 200_000_000L,
    /** Bytes available for decoded pixels during rendering. */
    val memoryBudget: Long = 256L * 1024 * 1024,
)

class StitchException(message: String) : Exception(message)

data class StitchReport(
    val width: Int,
    val height: Int,
    val usedImages: List<Int>,
    val droppedImages: List<Int>,
    val model: String,
    val outputScale: Double,
    val notes: List<String>,
    val aiDecisions: Int,
)

/** Progress/cancellation hooks. */
interface StitchMonitor {
    fun stage(text: String, fraction: Double)
    fun checkpoint()
}
