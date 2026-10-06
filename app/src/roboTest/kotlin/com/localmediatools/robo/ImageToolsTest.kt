package com.localmediatools.robo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.localmediatools.codec.gif.Dithering
import com.localmediatools.codec.gif.GifAnimationWriter
import com.localmediatools.codec.gif.GifDecoder
import com.localmediatools.codec.layout.Align
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.WatermarkPosition
import com.localmediatools.tools.CompressGifJob
import com.localmediatools.tools.CompressImagesJob
import com.localmediatools.tools.ConvertImagesJob
import com.localmediatools.tools.MergeBackground
import com.localmediatools.tools.MergeImagesJob
import com.localmediatools.tools.MergeLayout
import com.localmediatools.tools.OptimizeGifJob
import com.localmediatools.tools.OptimizeImagesJob
import com.localmediatools.tools.WatermarkJob
import com.localmediatools.tools.WatermarkJobSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class ImageToolsTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        inDir = File(app.cacheDir, "in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "out").apply { deleteRecursively() }).also { it.install() }
    }

    @After fun tearDown() = outputs.uninstall()

    private fun photo(w: Int, h: Int, seed: Long = 1): Bitmap {
        val r = Random(seed)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val n = r.nextInt(24)
            px[y * w + x] = Color.rgb((x * 255 / w + n).coerceAtMost(255), (y * 255 / h + n).coerceAtMost(255), ((x + y) * 128 / (w + h) + n).coerceAtMost(255))
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    private fun solid(w: Int, h: Int, c: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }

    private fun input(name: String, bmp: Bitmap, fmt: Bitmap.CompressFormat, q: Int = 100) = Robo.item(app, Robo.write(inDir, name, Robo.encode(bmp, fmt, q)))

    private fun ok(r: ItemResult): File {
        assertEquals("${r.inputName}: ${r.message}", ItemOutcome.SUCCESS, r.outcome)
        val f = File(r.outputs.single().uri.path!!)
        assertTrue("output exists", f.length() > 0)
        assertTrue("committed", f in outputs.committed)
        return f
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test fun compressJpegAndWebpWithMaxWidth() {
        val src = input("big.png", photo(400, 300), Bitmap.CompressFormat.PNG)
        for (fmt in listOf(ImageOutFormat.JPEG, ImageOutFormat.WEBP)) {
            val f = ok(Robo.runJob(app, CompressImagesJob(listOf(src), fmt, 72, 200)).single())
            val b = Robo.decode(f)
            assertEquals(200, b.width); assertEquals(150, b.height)
            assertTrue(f.name.endsWith("_q72_200w.${fmt.ext}"))
            assertTrue("smaller than PNG", f.length() < src.size)
        }
    }

    @Test fun compressSkipsWhenItWouldGrow() {
        val src = input("tiny.jpg", photo(120, 90), Bitmap.CompressFormat.JPEG, 20)
        val r = Robo.runJob(app, CompressImagesJob(listOf(src), ImageOutFormat.JPEG, 100, 0)).single()
        assertEquals(ItemOutcome.SKIPPED, r.outcome)
        assertTrue(r.outputs.isEmpty())
        assertTrue("hidden output removed", outputs.aborted.isNotEmpty() && outputs.aborted.none { it.exists() })
    }

    @Test fun transparencyIsFlattenedOnWhiteForJpeg() {
        val b = solid(80, 60, Color.TRANSPARENT)
        for (y in 0 until 30) for (x in 0 until 80) b.setPixel(x, y, Color.RED)
        val src = input("alpha.png", b, Bitmap.CompressFormat.PNG)
        val r = Robo.runJob(app, ConvertImagesJob(listOf(src), ImageOutFormat.JPEG, 90)).single()
        val out = Robo.decode(ok(r))
        assertTrue(r.message!!.contains("white"))
        assertTrue(Robo.diff(out.getPixel(40, 50), Color.WHITE) < 12)
        assertTrue(Robo.diff(out.getPixel(40, 10), Color.RED) < 40)
    }

    @Test fun losslessOptimizerKeepsEveryPixel() {
        val bmp = photo(97, 61, 7)
        // A few translucent pixels too.
        for (i in 0 until 50) bmp.setPixel(i, 3, Color.argb(i * 5, 10, 200, 30))
        val src = input("noise.png", bmp, Bitmap.CompressFormat.PNG)
        val expected = pixels(Robo.decode(File(src.uri.path!!)))
        for (fmt in listOf(ImageOutFormat.PNG, ImageOutFormat.WEBP_LOSSLESS)) {
            val f = ok(Robo.runJob(app, OptimizeImagesJob(listOf(src), fmt, keepOnlyIfSmaller = false)).single())
            val got = pixels(Robo.decode(f))
            // Compare opaque pixels exactly; translucent ones up to premultiplication rounding.
            for (i in expected.indices) {
                val a = expected[i] ushr 24
                if (a == 255) assertEquals("pixel $i (${fmt.label})", expected[i], got[i])
                else assertTrue("alpha pixel $i", Math.abs((got[i] ushr 24) - a) <= 0 && (a < 8 || Robo.diff(got[i], expected[i]) <= 255 / a + 2))
            }
        }
    }

    @Test fun convertsBetweenFormatsAndKeepsSize() {
        val src = input("p.jpg", photo(150, 100), Bitmap.CompressFormat.JPEG, 90)
        for (fmt in ImageOutFormat.entries) {
            val f = ok(Robo.runJob(app, ConvertImagesJob(listOf(src), fmt, 85)).single())
            val b = Robo.decode(f)
            assertEquals(150, b.width); assertEquals(100, b.height)
            assertTrue(f.name.endsWith("." + fmt.ext))
        }
    }

    @Test fun unreadableAndTruncatedInputsFailExplicitly() {
        val good = Robo.encode(photo(200, 150), Bitmap.CompressFormat.JPEG, 90)
        val cut = input("cut.jpg", solid(1, 1, 0), Bitmap.CompressFormat.PNG).let { Robo.item(app, Robo.write(inDir, "cut.jpg", good.copyOf(good.size / 2))) }
        val junk = Robo.item(app, Robo.write(inDir, "junk.png", ByteArray(300) { (it * 7).toByte() }))
        val results = Robo.runJob(app, ConvertImagesJob(listOf(cut, junk), ImageOutFormat.PNG, 100))
        assertEquals(2, results.size)
        for (r in results) {
            assertEquals(r.inputName, ItemOutcome.FAILED, r.outcome)
            assertFalse(r.message.isNullOrBlank())
        }
        assertTrue(outputs.committed.isEmpty())
    }

    @Test fun mergeVerticalHorizontalAndSmart() {
        val a = input("a.png", solid(100, 40, Color.RED), Bitmap.CompressFormat.PNG)
        val b = input("b.png", solid(60, 80, Color.GREEN), Bitmap.CompressFormat.PNG)
        val c = input("c.png", solid(30, 30, Color.BLUE), Bitmap.CompressFormat.PNG)
        val v = Robo.decode(ok(Robo.runJob(app, MergeImagesJob(listOf(a, b, c), MergeLayout.VERTICAL, MergeBackground.WHITE, 0, Align.START)).single()))
        assertEquals(100, v.width); assertEquals(150, v.height)
        assertEquals(Color.RED, v.getPixel(50, 20)); assertEquals(Color.GREEN, v.getPixel(30, 100)); assertEquals(Color.BLUE, v.getPixel(10, 130))
        assertEquals(Color.WHITE, v.getPixel(90, 100))
        val h = Robo.decode(ok(Robo.runJob(app, MergeImagesJob(listOf(a, b, c), MergeLayout.HORIZONTAL, MergeBackground.TRANSPARENT, 0, Align.START)).single()))
        assertEquals(190, h.width); assertEquals(80, h.height)
        assertEquals(Color.GREEN, h.getPixel(150, 70)); assertEquals(0, h.getPixel(50, 70) ushr 24)
        val s = Robo.decode(ok(Robo.runJob(app, MergeImagesJob(listOf(a, b, c), MergeLayout.SMART, MergeBackground.BLACK, 0, Align.START)).single()))
        val px = pixels(s)
        // Every source appears at full size (no scaling): count its pixels.
        assertEquals(100 * 40, px.count { it == Color.RED })
        assertEquals(60 * 80, px.count { it == Color.GREEN })
        assertEquals(30 * 30, px.count { it == Color.BLUE })
    }

    @Test fun mergeInStripsWithLittleMemory() {
        val items = (0 until 6).map { input("m$it.jpg", photo(120, 90, it.toLong()), Bitmap.CompressFormat.JPEG, 95) }
        val r = Robo.runJob(app, MergeImagesJob(items, MergeLayout.VERTICAL, MergeBackground.WHITE, 4, Align.CENTER), budget = 120 * 90 * 4L * 2 + 4096).single()
        val out = Robo.decode(ok(r))
        assertEquals(120, out.width); assertEquals(6 * 90 + 5 * 4, out.height)
        val ref = Robo.decode(File(items[3].uri.path!!))
        assertEquals(ref.getPixel(60, 45), out.getPixel(60, 3 * 94 + 45))
    }

    @Test fun watermarkStampsOnlyItsCorner() {
        val src = input("w.png", solid(400, 300, Color.DKGRAY), Bitmap.CompressFormat.PNG)
        val spec = WatermarkJobSpec("© Villa", null, null, 30, 100, Color.WHITE, emptyMap(), WatermarkPosition.BOTTOM_RIGHT)
        val f = ok(Robo.runJob(app, WatermarkJob(listOf(src), spec)).single())
        assertTrue(f.name.endsWith("_wm.png"))
        val out = Robo.decode(f)
        assertEquals(400, out.width); assertEquals(300, out.height)
        val px = pixels(out)
        var changedBR = 0; var changedTL = 0
        for (y in 0 until 300) for (x in 0 until 400) if (px[y * 400 + x] != Color.DKGRAY) {
            if (x >= 200 && y >= 150) changedBR++ else changedTL++
        }
        assertTrue("watermark drawn ($changedBR px)", changedBR > 200)
        assertEquals("nothing drawn outside the chosen corner", 0, changedTL)
    }

    @Test fun watermarkUsesPerAspectGroupPlacement() {
        val wide = input("wide.png", solid(320, 180, Color.BLACK), Bitmap.CompressFormat.PNG)
        val tall = input("tall.png", solid(180, 320, Color.BLACK), Bitmap.CompressFormat.PNG)
        val spec = WatermarkJobSpec("LOGO", null, null, 25, 100, Color.WHITE,
            mapOf("16:9" to WatermarkPosition.TOP_LEFT, "9:16" to WatermarkPosition.BOTTOM_RIGHT), WatermarkPosition.CENTER)
        val res = Robo.runJob(app, WatermarkJob(listOf(wide, tall), spec)).associateBy { it.inputName }
        val w = Robo.decode(ok(res.getValue("wide.png")))
        val t = Robo.decode(ok(res.getValue("tall.png")))
        fun inQuadrant(b: Bitmap, right: Boolean, bottom: Boolean): Int {
            var n = 0
            for (y in 0 until b.height) for (x in 0 until b.width) if (b.getPixel(x, y) != Color.BLACK &&
                (x >= b.width / 2) == right && (y >= b.height / 2) == bottom) n++
            return n
        }
        assertTrue(inQuadrant(w, false, false) > 50); assertEquals(0, inQuadrant(w, true, true))
        assertTrue(inQuadrant(t, true, true) > 50); assertEquals(0, inQuadrant(t, false, false))
    }

    private fun gifFile(name: String, w: Int, h: Int, frames: Int): File {
        val bos = ByteArrayOutputStream()
        val gw = GifAnimationWriter(bos, w, h, null, 0)
        for (f in 0 until frames) {
            val px = IntArray(w * h) { i -> val x = i % w; val y = i / w
                if ((x / 8 + y / 8 + f) % 2 == 0) Color.rgb(200, 30 + f * 20, 40) else Color.rgb(20, 60, 180 - f * 10) }
            gw.addFrame(px, 8)
        }
        gw.finish()
        return Robo.write(inDir, name, bos.toByteArray())
    }

    private fun gifFrames(f: File): List<IntArray> = f.inputStream().use { s ->
        val d = GifDecoder(s.buffered()); val out = ArrayList<IntArray>()
        while (true) { val fr = d.nextFrame() ?: break; out.add(fr.canvas.copyOf()) }
        out
    }

    @Test fun gifOptimizerIsPixelIdentical() {
        val src = gifFile("anim.gif", 64, 48, 5)
        val f = ok(Robo.runJob(app, OptimizeGifJob(listOf(Robo.item(app, src)), 0, 0.0, keepOnlyIfSmaller = false)).single())
        val a = gifFrames(src); val b = gifFrames(f)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertArrayEquals("frame $i", a[i], b[i])
    }

    @Test fun gifCompressorResizesAndLimitsColours() {
        val src = gifFile("anim2.gif", 120, 80, 4)
        val f = ok(Robo.runJob(app, CompressGifJob(listOf(Robo.item(app, src)), 60, 0.0, 32, Dithering.NONE)).single())
        val frames = f.inputStream().use { s -> val d = GifDecoder(s.buffered()); val l = ArrayList<IntArray>(); while (true) { l.add((d.nextFrame() ?: break).canvas.copyOf()) }; l }
        assertEquals(4, frames.size)
        assertEquals(60 * 40, frames[0].size)
        assertTrue(frames.flatMap { it.toList() }.toSet().size <= 33)
        assertNotEquals(0, f.length())
    }
}
