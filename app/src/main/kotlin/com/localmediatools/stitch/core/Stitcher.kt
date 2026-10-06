package com.localmediatools.stitch.core

import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-shot stitching pipeline:
 *  1. features on reduced-resolution copies (SIFT),
 *  2. pairwise matching + RANSAC verification (AI resolves ambiguous / weak / texture-poor pairs
 *     when enabled),
 *  3. largest connected group of photos via a maximum spanning tree,
 *  4. motion model selection — flat (similarity/affine), rotating-camera panorama (bundle
 *     adjustment + horizon straightening) or general perspective — solved globally,
 *  5. exposure compensation, crop to the largest fully covered rectangle,
 *  6. strip-wise full-resolution rendering into a lossless sink.
 */
class Stitcher(
    private val images: StitchImages,
    private val options: StitchOptions,
    private val assist: AlignmentAssist?,
    private val monitor: StitchMonitor,
) {
    fun run(sink: StitchSink): StitchReport {
        val n = images.count
        if (n < 2) throw StitchException("Select at least two overlapping photos.")
        val notes = ArrayList<String>()
        // 1. Work images and features.
        val work = HashMap<Int, WorkImage>()
        try {
            for (i in 0 until n) {
                monitor.stage("Finding features (${i + 1}/$n)", i.toDouble() / n * 0.3)
                monitor.checkpoint()
                val full = images.fullSize(i)
                val rgba = images.workImage(i, options.workMaxSide)
                val wi = Features.extract(i, rgba, full[0], if (n > 12) 2500 else 4000)
                if (assist != null) {
                    try { wi.embedding = assist.embed(rgba) } catch (_: Exception) { }
                }
                work[i] = wi
            }
            // 2. Candidate pairs.
            val pairs = candidatePairs(work)
            val matcher = PairMatcher(assist, monitor)
            val links = ArrayList<PairLink>()
            for ((k, pr) in pairs.withIndex()) {
                monitor.stage("Matching photos (${k + 1}/${pairs.size})", 0.3 + 0.3 * k / pairs.size)
                val l = matcher.match(work[pr.first]!!, work[pr.second]!!)
                if (l != null) links.add(l)
            }
            if (links.isEmpty()) {
                throw StitchException("No overlap could be found between these photos. Make sure neighbouring shots overlap by about a third and show the same detailed content." +
                    if (assist == null) " Turning on AI Assisted Alignment may help with low-texture scenes." else "")
            }
            // 3. Graph.
            val aligner = GlobalAligner((0 until n).map { work[it]!! }, (0 until n).map { images.fullSize(it) }, DoubleArray(n) { images.focal35mm(it) }, monitor)
            val g = aligner.graph(links)
            val dropped = (0 until n).filter { it !in g.component.toSet() }
            if (g.component.size < 2) throw StitchException("None of the photos overlap clearly enough to be joined.")
            if (dropped.isNotEmpty()) notes.add("Not connected to the others, left out: ${dropped.joinToString { images.name(it) }}.")
            notes.addAll(matcher.log.take(12))

            // 4. Model selection.
            monitor.stage("Aligning", 0.62)
            val chained = aligner.chainHomographies(g)
            var warp: Warp? = null
            var model = ""
            val perspective = aligner.maxAffineDeviation(g.links)
            val tryFlat = options.mode == SceneMode.FLAT || (options.mode == SceneMode.AUTO && perspective < 0.012)
            if (tryFlat) {
                val sim = aligner.solveFlat(g, affine = false)
                val aff = if (sim == null || sim.second > 2.0) aligner.solveFlat(g, affine = true) else null
                val best = listOfNotNull(sim, aff).minByOrNull { it.second }
                if (best != null && (best.second < 4.0 || options.mode == SceneMode.FLAT)) {
                    warp = aligner.planarWarp(best.first, g, if (best === sim) "flat (similarity)" else "flat (affine)")
                    model = warp.description
                }
            }
            if (warp == null && options.mode != SceneMode.FLAT) {
                val rot = try { aligner.solveRotation(g) } catch (e: Exception) { null }
                if (rot != null && rot.rms < 3.5) {
                    warp = aligner.rotationWarp(rot, g)
                    model = warp.description
                }
            }
            if (warp == null) {
                val (hs, rms) = aligner.refineHomographies(g, chained)
                if (rms > 12.0) throw StitchException("The photos could not be aligned consistently (the scene may have moved between shots or overlaps are too small).")
                warp = aligner.planarWarp(hs, g, "perspective")
                model = "perspective"
            }

            // 5. Layout, exposure and crop.
            val nodes = g.component.sorted()
            val comp = Compositor(images, nodes, monitor)
            val layout = comp.layout(warp, options.maxOutputPixels)
            notes.addAll(layout.notes)
            val gains = comp.gains(work, g.links)
            monitor.stage("Finding clean edges", 0.66)
            val crop = if (options.cropToRectangle) comp.cropRect(layout) else intArrayOf(0, 0, layout.width, layout.height)
            // Work copies are no longer needed; free them before full-resolution rendering.
            work.values.forEach { it.release() }
            work.clear()
            // 6. Render.
            comp.render(layout, crop, gains, !options.cropToRectangle, options.memoryBudget, sink)
            return StitchReport(crop[2] - crop[0], crop[3] - crop[1], nodes, dropped, model, layout.scale, notes, matcher.aiDecisions)
        } finally {
            work.values.forEach { it.release() }
        }
    }

    /**
     * Pairs to test. Small sets: all pairs. Larger sets: neighbours in selection order plus the most
     * similar photos (AI embeddings, or colour thumbnails without AI).
     */
    private fun candidatePairs(work: Map<Int, WorkImage>): List<Pair<Int, Int>> {
        val n = work.size
        val all = LinkedHashSet<Pair<Int, Int>>()
        if (n <= 14) {
            for (i in 0 until n) for (j in i + 1 until n) all.add(i to j)
            return all.toList()
        }
        for (i in 0 until n - 1) all.add(i to i + 1)
        val desc = (0 until n).map { i -> work[i]!!.embedding ?: thumbDescriptor(work[i]!!.rgba) }
        val k = 6
        for (i in 0 until n) {
            val sims = (0 until n).filter { it != i }.map { j ->
                var d = 0.0; for (t in desc[i].indices) d += desc[i][t] * desc[j][t]
                j to d
            }.sortedByDescending { it.second }
            for ((j, _) in sims.take(k)) all.add(min(i, j) to max(i, j))
        }
        return all.toList()
    }

    private fun thumbDescriptor(rgba: Mat): FloatArray {
        val small = Mat()
        Imgproc.resize(rgba, small, org.opencv.core.Size(8.0, 8.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val b = ByteArray(8 * 8 * 4); small.get(0, 0, b); small.release()
        val v = FloatArray(8 * 8 * 3)
        var o = 0
        for (k in 0 until 64) for (c in 0 until 3) v[o++] = (b[k * 4 + c].toInt() and 0xFF).toFloat()
        val mean = v.average().toFloat()
        var norm = 0.0
        for (i in v.indices) { v[i] -= mean; norm += v[i] * v[i] }
        val s = Math.sqrt(norm).toFloat().coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= s
        return v
    }
}
