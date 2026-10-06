package com.localmediatools.stitch

import com.localmediatools.stitch.core.SceneMode
import com.localmediatools.stitch.core.StitchException
import com.localmediatools.stitch.core.StitchImages
import com.localmediatools.stitch.core.StitchMonitor
import com.localmediatools.stitch.core.StitchOptions
import com.localmediatools.stitch.core.StitchReport
import com.localmediatools.stitch.core.StitchSink
import com.localmediatools.stitch.core.Stitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Random

class StitcherTest {
    companion object {
        @BeforeClass @JvmStatic
        fun load() { nu.pattern.OpenCV.loadLocally() }

        /** Procedural scene with plenty of distinctive detail. */
        fun scene(w: Int, h: Int, seed: Long): Mat {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = Random(seed)
            g.paint = java.awt.GradientPaint(0f, 0f, Color(40, 70, 120), w.toFloat(), h.toFloat(), Color(200, 160, 90))
            g.fillRect(0, 0, w, h)
            repeat(w * h / 3000) {
                g.color = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))
                when (r.nextInt(4)) {
                    0 -> g.fillOval(r.nextInt(w), r.nextInt(h), 8 + r.nextInt(60), 8 + r.nextInt(60))
                    1 -> g.fillRect(r.nextInt(w), r.nextInt(h), 6 + r.nextInt(50), 6 + r.nextInt(50))
                    2 -> { g.stroke = BasicStroke(1f + r.nextInt(4)); g.drawLine(r.nextInt(w), r.nextInt(h), r.nextInt(w), r.nextInt(h)) }
                    else -> { g.font = Font("SansSerif", Font.BOLD, 12 + r.nextInt(30)); g.drawString(Integer.toHexString(r.nextInt()), r.nextInt(w), r.nextInt(h)) }
                }
            }
            g.dispose()
            val m = Mat(h, w, CvType.CV_8UC4)
            val px = img.getRGB(0, 0, w, h, null, 0, w)
            val b = ByteArray(w * h * 4)
            for (i in px.indices) {
                val c = px[i]
                b[i * 4] = (c shr 16).toByte(); b[i * 4 + 1] = (c shr 8).toByte(); b[i * 4 + 2] = c.toByte(); b[i * 4 + 3] = 0xFF.toByte()
            }
            m.put(0, 0, b)
            return m
        }
    }

    private class MatImages(val mats: List<Mat>) : StitchImages {
        override val count get() = mats.size
        override fun name(i: Int) = "img$i"
        override fun fullSize(i: Int) = intArrayOf(mats[i].cols(), mats[i].rows())
        override fun workImage(i: Int, maxSide: Int): Mat {
            val m = mats[i]
            val s = minOf(1.0, maxSide.toDouble() / maxOf(m.cols(), m.rows()))
            val out = Mat()
            Imgproc.resize(m, out, Size(m.cols() * s, m.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
            return out
        }
        override fun region(i: Int, x: Int, y: Int, w: Int, h: Int, sample: Int): Mat {
            val sub = mats[i].submat(Rect(x, y, w, h))
            if (sample == 1) return sub.clone()
            val out = Mat()
            Imgproc.resize(sub, out, Size((w / sample).coerceAtLeast(1).toDouble(), (h / sample).coerceAtLeast(1).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            return out
        }
    }

    private class Collect : StitchSink {
        var w = 0; var h = 0
        lateinit var px: IntArray
        override fun begin(width: Int, height: Int, hasTransparency: Boolean) { w = width; h = height; px = IntArray(width * height) }
        override fun rows(y: Int, count: Int, argb: IntArray) { System.arraycopy(argb, 0, px, y * w, count * w) }
        override fun finish() {}
    }

    private val monitor = object : StitchMonitor {
        override fun stage(text: String, fraction: Double) {}
        override fun checkpoint() {}
    }

    private fun stitch(mats: List<Mat>, mode: SceneMode = SceneMode.AUTO): Pair<StitchReport, Collect> {
        val sink = Collect()
        val report = Stitcher(MatImages(mats), StitchOptions(mode = mode, workMaxSide = 900, memoryBudget = 64L shl 20), null, monitor).run(sink)
        System.getenv("LMT_DUMP")?.let { dir ->
            val img = BufferedImage(sink.w, sink.h, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, sink.w, sink.h, sink.px, 0, sink.w)
            val name = Thread.currentThread().stackTrace[2].methodName
            javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "$name.png"))
            println("$name: ${report.model} ${sink.w}x${sink.h} notes=${report.notes}")
        }
        return report to sink
    }

    /** Mean absolute difference between [px] and the scene region starting at (sx, sy). */
    private fun diffAgainst(scene: Mat, px: IntArray, w: Int, h: Int, sx: Int, sy: Int): Double {
        val b = ByteArray(4)
        var sum = 0.0; var n = 0
        var y = 4
        while (y < h - 4) {
            var x = 4
            while (x < w - 4) {
                scene.get(sy + y, sx + x, b)
                val c = px[y * w + x]
                sum += Math.abs(((c shr 16) and 0xFF) - (b[0].toInt() and 0xFF)) + Math.abs(((c shr 8) and 0xFF) - (b[1].toInt() and 0xFF)) + Math.abs((c and 0xFF) - (b[2].toInt() and 0xFF))
                n += 3
                x += 7
            }
            y += 7
        }
        return sum / n
    }

    @Test
    fun horizontalStripOfScreenshotsIsExact() {
        val s = scene(2600, 800, 1)
        val tiles = listOf(0, 700, 1400).map { x -> s.submat(Rect(x, 0, 1200, 800)).clone() }
        val (rep, out) = stitch(tiles)
        assertTrue(rep.model, rep.model.startsWith("flat"))
        assertEquals(3, rep.usedImages.size)
        assertTrue("width ${out.w}", out.w in 2560..2600)
        assertTrue("height ${out.h}", out.h in 780..800)
        // Locate the crop origin by brute force (it can be shifted by a pixel or two).
        val best = (0..40).flatMap { dx -> (0..20).map { dy -> dx to dy } }
            .filter { (dx, dy) -> dx + out.w <= 2600 && dy + out.h <= 800 }
            .minOf { (dx, dy) -> diffAgainst(s, out.px, out.w, out.h, dx, dy) }
        assertTrue("mean abs diff $best", best < 3.0)
    }

    @Test
    fun gridArrangementInAnyOrder() {
        val s = scene(2000, 1600, 2)
        val tiles = ArrayList<Mat>()
        for ((x, y) in listOf(800 to 700, 0 to 0, 800 to 0, 0 to 700)) tiles.add(s.submat(Rect(x, y, 1200, 900)).clone())
        val (rep, out) = stitch(tiles)
        assertEquals(4, rep.usedImages.size)
        assertTrue("${out.w}x${out.h}", out.w in 1950..2000 && out.h in 1550..1600)
    }

    @Test
    fun rotatingCameraPanorama() {
        // Views of a distant scene from a camera rotating about its centre (pure homographies).
        val s = scene(5000, 2200, 3)
        val f = 1100.0
        val views = listOf(-25.0, -5.0, 15.0).map { yawDeg ->
            val yaw = Math.toRadians(yawDeg)
            val w = 1200; val h = 900
            // Camera looks at the plane z = f0 (scene centre); map view pixels -> scene pixels.
            val k = doubleArrayOf(f, 0.0, w / 2.0, 0.0, f, h / 2.0, 0.0, 0.0, 1.0)
            val kInv = com.localmediatools.stitch.core.M3.inverse(k)!!
            val ry = doubleArrayOf(Math.cos(yaw), 0.0, Math.sin(yaw), 0.0, 1.0, 0.0, -Math.sin(yaw), 0.0, Math.cos(yaw))
            val ks = doubleArrayOf(f, 0.0, 2500.0, 0.0, f, 1100.0, 0.0, 0.0, 1.0)
            val hm = com.localmediatools.stitch.core.M3.mul(com.localmediatools.stitch.core.M3.mul(ks, ry), kInv)
            val m = Mat(3, 3, CvType.CV_64F); m.put(0, 0, *hm)
            val out = Mat()
            Imgproc.warpPerspective(s, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP)
            out
        }
        val (rep, out) = stitch(views)
        assertEquals(3, rep.usedImages.size)
        assertTrue(rep.model, rep.model.startsWith("panorama") || rep.model == "perspective")
        assertTrue("${out.w}x${out.h}", out.w > 1800 && out.h > 600)
    }

    @Test
    fun unrelatedPhotosFailClearly() {
        val a = scene(900, 700, 10); val b = scene(900, 700, 11)
        try {
            stitch(listOf(a, b))
            fail("expected failure")
        } catch (e: StitchException) {
            assertTrue(e.message!!.contains("overlap"))
        }
    }
}
