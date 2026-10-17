package com.localmediatools.codec

import com.localmediatools.codec.jpeg.JpegWriter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.log10

/**
 * How faithful a JPEG at full quality is: a RAW photo's JPEG is written by the app's own writer at
 * quality 100 (every quantisation step 1) with full colour resolution. A JPEG can't be bit-exact
 * (colour conversion and the transform round), so this measures what is left on real photos (the
 * duplicate finder's and the people photos, CC BY), decoded again, against Android's usual halved
 * colour resolution at the same quality.
 */
class FullQualityJpegTest {
    private class Score(var sq: Double = 0.0, var n: Long = 0, var max: Int = 0, var within1: Long = 0) {
        val psnr get() = 10 * log10(255.0 * 255.0 / (sq / n))
    }

    private fun measure(argb: IntArray, w: Int, h: Int, sub420: Boolean, s: Score) {
        val out = ByteArrayOutputStream()
        JpegWriter(out, w, h, 100, subsampling420 = sub420).apply { writeRows(argb, 0, w, h); finish() }
        val back = ImageIO.read(ByteArrayInputStream(out.toByteArray()))
        for (y in 0 until h) for (x in 0 until w) {
            val a = argb[y * w + x]; val b = back.getRGB(x, y)
            var worst = 0
            for (sh in intArrayOf(16, 8, 0)) {
                val d = abs(((a shr sh) and 255) - ((b shr sh) and 255))
                s.sq += d * d; s.n++; if (d > s.max) s.max = d; if (d > worst) worst = d
            }
            if (worst <= 1) s.within1++
        }
    }

    @Test fun fullQualityJpegsStayCloseToThePicture() {
        val dirs = listOf("/duplicates", "/people").map { File(javaClass.getResource(it)!!.toURI()) }
        val photos = dirs.flatMap { d -> d.listFiles()!!.filter { it.name.endsWith(".jpg") }.sortedBy { it.name } }
        val full = Score(); val halved = Score()
        for (f in photos) {
            val img = ImageIO.read(f)
            val w = img.width; val h = img.height
            val argb = img.getRGB(0, 0, w, h, null, 0, w)
            measure(argb, w, h, false, full)
            measure(argb, w, h, true, halved)
        }
        val pixels = full.n / 3
        println("${photos.size} real photos, quality 100: full colour ${"%.2f".format(full.psnr)} dB, largest difference ${full.max} of 255, " +
            "${"%.2f".format(100.0 * full.within1 / pixels)} % of pixels within 1 level; halved colour ${"%.2f".format(halved.psnr)} dB, largest ${halved.max}")
        assertTrue("${full.psnr}", full.psnr > 55)
        assertTrue("${full.max}", full.max <= 3)
        assertTrue("${full.within1} of $pixels", full.within1 > pixels * 0.95)
        assertTrue("${full.psnr} vs ${halved.psnr}", full.psnr > halved.psnr + 3)
    }
}
