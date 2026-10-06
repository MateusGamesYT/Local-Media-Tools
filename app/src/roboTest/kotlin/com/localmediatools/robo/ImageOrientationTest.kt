package com.localmediatools.robo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.robolectric.RuntimeEnvironment
import com.localmediatools.export.ItemOutcome
import com.localmediatools.image.ImageSource
import com.localmediatools.tools.ConvertImagesJob
import com.localmediatools.image.ImageOutFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Every EXIF orientation must produce a visually upright image, both when decoding and in exported files. */
@RunWith(RobolectricTestRunner::class)
class ImageOrientationTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs

    private val dw = 64
    private val dh = 40
    private val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW) // TL, TR, BL, BR

    private fun canonical(x: Int, y: Int): Int = colors[(if (y < dh / 2) 0 else 2) + (if (x < dw / 2) 0 else 1)]

    /** Builds the raw (stored) pixels that display as the canonical image under EXIF [tag]. */
    private fun rawFor(tag: Int): Bitmap {
        val swap = tag >= 5
        val rw = if (swap) dh else dw
        val rh = if (swap) dw else dh
        val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        for (y in 0 until dh) for (x in 0 until dw) {
            val (sx, sy) = when (tag) {
                1 -> x to y
                2 -> (dw - 1 - x) to y
                3 -> (dw - 1 - x) to (dh - 1 - y)
                4 -> x to (dh - 1 - y)
                5 -> y to x
                6 -> y to (rh - 1 - x)
                7 -> (rw - 1 - y) to (rh - 1 - x)
                8 -> (rw - 1 - y) to x
                else -> error("tag")
            }
            bmp.setPixel(sx, sy, canonical(x, y))
        }
        return bmp
    }

    private fun assertUpright(b: Bitmap, what: String) {
        assertEquals("$what width", dw, b.width)
        assertEquals("$what height", dh, b.height)
        val pts = listOf(dw / 4 to dh / 4, 3 * dw / 4 to dh / 4, dw / 4 to 3 * dh / 4, 3 * dw / 4 to 3 * dh / 4)
        for ((i, p) in pts.withIndex()) {
            val got = b.getPixel(p.first, p.second)
            assertTrue("$what quadrant $i: expected ${Integer.toHexString(colors[i])} got ${Integer.toHexString(got)}", Robo.diff(got, colors[i]) < 40)
        }
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        inDir = File(app.cacheDir, "in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "out").apply { deleteRecursively() }).also { it.install() }
    }

    @After fun tearDown() = outputs.uninstall()

    @Test fun decodeAppliesEveryExifOrientation() {
        for (tag in 1..8) {
            val f = Robo.write(inDir, "o$tag.jpg", Robo.withExifOrientation(Robo.encode(rawFor(tag), Bitmap.CompressFormat.JPEG), tag))
            val item = Robo.item(app, f)
            ImageSource.open(app, item).use { src ->
                assertEquals("tag $tag display width", dw, src.width)
                assertEquals("tag $tag display height", dh, src.height)
                assertUpright(src.decode(), "decode tag $tag")
                assertUpright(src.preview(256), "preview tag $tag")
            }
        }
    }

    @Test fun exportedFilesAreUpright() {
        val items = (1..8).map { tag ->
            Robo.item(app, Robo.write(inDir, "e$tag.jpg", Robo.withExifOrientation(Robo.encode(rawFor(tag), Bitmap.CompressFormat.JPEG), tag)))
        }
        val results = Robo.runJob(app, ConvertImagesJob(items, ImageOutFormat.PNG, 100))
        assertEquals(8, results.size)
        for (r in results) {
            assertEquals(r.inputName + ": " + r.message, ItemOutcome.SUCCESS, r.outcome)
            val f = File(r.outputs.single().uri.path!!)
            assertUpright(Robo.decode(f), "export ${r.inputName}")
        }
    }

    @Test fun stripRenderingFollowsOrientation() {
        // With too little memory for a whole-image encode, the exporter renders in strips; rotated
        // sources must still come out upright. (Robolectric has no BitmapRegionDecoder, so this runs
        // the cached-decode fallback; devices use region decoding with the same orientation mapping.)
        for (tag in listOf(1, 3, 6, 7, 8)) {
            val f = Robo.write(inDir, "r$tag.jpg", Robo.withExifOrientation(Robo.encode(rawFor(tag), Bitmap.CompressFormat.JPEG), tag))
            val need = dw * dh * 4L * (if (tag == 1) 1 else 2)
            val results = Robo.runJob(app, ConvertImagesJob(listOf(Robo.item(app, f)), ImageOutFormat.JPEG, 95), budget = need + 1024)
            val r = results.single()
            assertEquals(r.message, ItemOutcome.SUCCESS, r.outcome)
            assertUpright(Robo.decode(File(r.outputs.single().uri.path!!)), "strip export tag $tag")
        }
    }
}
