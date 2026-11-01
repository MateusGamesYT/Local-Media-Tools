package com.localmediatools.robo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.localmediatools.export.ItemOutcome
import com.localmediatools.tools.AutoEnhanceJob
import com.localmediatools.tools.CutoutBackground
import com.localmediatools.tools.EnhanceStrength
import com.localmediatools.tools.FaceBlurJob
import com.localmediatools.tools.FaceBlurPlan
import com.localmediatools.tools.RemoveBackgroundJob
import com.localmediatools.vision.VisionOps
import com.localmediatools.vision.core.FaceSample
import com.localmediatools.vision.core.FaceTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The AI photo tools end to end. The neural networks themselves need native code (they are tested
 * on the JVM in VisionCoreTest); here they are replaced by simple stand-ins so the full jobs —
 * decoding, compositing, cropping, encoding, verification — run inside Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class AiToolsTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        inDir = File(app.cacheDir, "ai-in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "ai-out").apply { deleteRecursively() }).also { it.install() }
    }

    @After fun tearDown() { outputs.uninstall(); VisionOps.maskOverride = null }

    /** Grey background with a red disc (the "subject") centred at (0.4, 0.5), radius 0.2 of the width. */
    private fun product(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF9AA0A6.toInt())
        Canvas(b).drawCircle(w * 0.4f, h * 0.5f, w * 0.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        return b
    }

    private fun discMask(bmp: Bitmap): FloatArray {
        val w = bmp.width; val h = bmp.height
        return FloatArray(w * h) { i ->
            val dx = (i % w) - w * 0.4f; val dy = (i / w) - h * 0.5f
            if (dx * dx + dy * dy <= (w * 0.2f) * (w * 0.2f)) 1f else 0f
        }
    }

    @Test fun backgroundRemoverMakesTransparentCroppedCutouts() {
        VisionOps.maskOverride = ::discMask
        val src = Robo.item(app, Robo.write(inDir, "mug.jpg", Robo.encode(product(800, 600), Bitmap.CompressFormat.JPEG, 95)))
        val r = Robo.runJob(app, RemoveBackgroundJob(listOf(src), CutoutBackground.TRANSPARENT, crop = true)).single()
        assertEquals(r.message, ItemOutcome.SUCCESS, r.outcome)
        val f = File(r.outputs.single().uri.path!!)
        assertTrue(f.name, f.name == "mug_cutout.png")
        val out = Robo.decode(f)
        // Cropped around the 320 px disc with a small margin.
        assertTrue("${out.width}×${out.height}", out.width in 330..400 && out.height in 330..400)
        val centre = out.getPixel(out.width / 2, out.height / 2)
        assertEquals(255, Color.alpha(centre))
        assertTrue(Color.red(centre) > 200 && Color.green(centre) < 60)
        assertEquals("corner is transparent", 0, Color.alpha(out.getPixel(2, 2)))

        // On white, uncropped: same size as the photo and the background is white.
        val r2 = Robo.runJob(app, RemoveBackgroundJob(listOf(src), CutoutBackground.WHITE, crop = false)).single()
        val out2 = Robo.decode(File(r2.outputs.single().uri.path!!))
        assertEquals(800, out2.width); assertEquals(600, out2.height)
        assertTrue(Robo.diff(out2.getPixel(780, 20), Color.WHITE) < 6)
        assertTrue(Robo.diff(out2.getPixel(320, 300), Color.RED) < 40)
    }

    @Test fun backgroundRemoverSkipsPhotosWithoutASubject() {
        VisionOps.maskOverride = { b -> FloatArray(b.width * b.height) }
        val src = Robo.item(app, Robo.write(inDir, "wall.png", Robo.encode(product(200, 200), Bitmap.CompressFormat.PNG)))
        val r = Robo.runJob(app, RemoveBackgroundJob(listOf(src), CutoutBackground.TRANSPARENT, crop = true)).single()
        assertEquals(ItemOutcome.SKIPPED, r.outcome)
        assertTrue(outputs.committed.isEmpty())
    }

    @Test fun autoEnhanceBrightensADarkPhoto() {
        // A dull, dark, slightly blue photo (the face/scene models are unavailable here, so it uses its own analysis).
        // (A cold-tinted portrait being warmed is VisionCoreTest's, with the real face model.)
        val b = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint()
        for (y in 0 until 400 step 20) for (x in 0 until 600 step 20) {
            val v = 20 + ((x * 7 + y * 3) % 60)
            p.color = Color.rgb(v, v + 4, v + 22); c.drawRect(x.toFloat(), y.toFloat(), x + 20f, y + 20f, p)
        }
        val src = Robo.item(app, Robo.write(inDir, "dusk.png", Robo.encode(b, Bitmap.CompressFormat.PNG)))
        val r = Robo.runJob(app, AutoEnhanceJob(listOf(src), EnhanceStrength.NATURAL)).single()
        assertEquals(r.message, ItemOutcome.SUCCESS, r.outcome)
        assertTrue(r.details!!, r.details!!.contains("brightened"))
        val out = Robo.decode(File(r.outputs.single().uri.path!!))
        assertEquals(600, out.width)
        fun mean(bmp: Bitmap): Double { var s = 0.0; for (y in 0 until bmp.height step 7) for (x in 0 until bmp.width step 7) { val q = bmp.getPixel(x, y); s += (Color.red(q) + Color.green(q) + Color.blue(q)) / 3.0 }; return s / ((bmp.height / 7 + 1) * (bmp.width / 7 + 1)) }
        // Brightened about as much as the experts brightened photos this dark (buildtools/enhance/README.md).
        assertTrue("brighter: ${mean(out)} vs ${mean(b)}", mean(out) > mean(b) + 10)
        // Without faces a cool cast may be the scene's own light, which the experts kept; it is never made bluer.
        val q = out.getPixel(310, 210); val o = b.getPixel(310, 210)
        assertTrue("not bluer: $q vs $o (${r.details})", Color.red(q).toDouble() / Color.blue(q) >= 0.97 * Color.red(o).toDouble() / Color.blue(o))
    }

    @Test fun faceBlurHidesOnlyTheChosenFacesInPhotos() {
        // Fine stripes everywhere: blurring makes them uniform, so the effect is measurable.
        val w = 640; val h = 480
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, if ((x / 3) % 2 == 0) Color.WHITE else Color.BLACK)
        val src = Robo.item(app, Robo.write(inDir, "group.png", Robo.encode(b, Bitmap.CompressFormat.PNG)))
        val face = FaceTrack(0, 0, still = true).apply { samples.add(FaceSample(0, 0.15f, 0.3f, 0.15f, 0.2f, 0.9f, null)) }
        val plan = FaceBlurPlan(mapOf(src.key to listOf(face)), pixelate = false, strength = 0.8f)
        val r = Robo.runJob(app, FaceBlurJob(listOf(src), plan)).single()
        assertEquals(r.message, ItemOutcome.SUCCESS, r.outcome)
        val out = Robo.decode(File(r.outputs.single().uri.path!!))
        fun contrast(cx: Int, cy: Int): Int { var lo = 255; var hi = 0; for (x in cx - 6..cx + 6) { val v = Color.red(out.getPixel(x, cy)); lo = minOf(lo, v); hi = maxOf(hi, v) }; return hi - lo }
        val fx = (0.225f * w).toInt(); val fy = (0.4f * h).toInt()
        assertTrue("face area blurred (contrast ${contrast(fx, fy)})", contrast(fx, fy) < 80)
        assertTrue("rest untouched", contrast((0.75f * w).toInt(), (0.5f * h).toInt()) > 240)
        assertTrue(r.details!!, r.details!!.startsWith("1 face blurred"))

        // A file without any chosen face is skipped, not copied.
        val other = Robo.item(app, Robo.write(inDir, "empty.png", Robo.encode(b, Bitmap.CompressFormat.PNG)))
        val r2 = Robo.runJob(app, FaceBlurJob(listOf(other), plan)).single()
        assertEquals(ItemOutcome.SKIPPED, r2.outcome)
    }

    /**
     * Private details in a real advert (resources/privacy, CC BY 2.0): the phone number and e-mail
     * addresses TextOCR outlined lose their letters even at the lightest strength, blurred and
     * pixelated; the rest of the photo is left as it was.
     */
    @Test fun privateDetailsAreHiddenEvenAtLight() {
        val name = "223514026649d937.jpg"
        val bytes = javaClass.getResourceAsStream("/privacy/$name")!!.readBytes()
        val src = Robo.item(app, Robo.write(inDir, name, bytes))
        val orig = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val quads = String(javaClass.getResourceAsStream("/privacy/labels.tsv")!!.readBytes()).lines()
            .filter { it.startsWith(name) && "\tprivate\t" in it }.map { l -> l.split('\t')[3].split(' ').map { it.toFloat() }.toFloatArray() }
        assertEquals(3, quads.size)
        val tracks = quads.mapIndexed { i, q ->
            com.localmediatools.vision.core.PrivateTrack(i, 0, com.localmediatools.vision.core.FindKind.TEXT, setOf(com.localmediatools.vision.core.Detail.EMAIL), "", still = true)
                .apply { samples.add(com.localmediatools.vision.core.PrivateSample(0, q)) }
        }
        fun spread(b: Bitmap, q: FloatArray): Double {
            val xs = q.filterIndexed { i, _ -> i % 2 == 0 }; val ys = q.filterIndexed { i, _ -> i % 2 == 1 }
            val vals = ArrayList<Double>()
            for (y in (ys.min() * b.height).toInt()..(ys.max() * b.height).toInt()) for (x in (xs.min() * b.width).toInt()..(xs.max() * b.width).toInt()) {
                val c = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1)); vals.add((Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0)
            }
            val m = vals.average(); return Math.sqrt(vals.sumOf { (it - m) * (it - m) } / vals.size)
        }
        for (pixelate in listOf(false, true)) {
            val plan = FaceBlurPlan(emptyMap(), pixelate, 0.4f, privates = mapOf(src.key to tracks))
            val r = Robo.runJob(app, FaceBlurJob(listOf(src), plan)).single()
            assertEquals(r.message, ItemOutcome.SUCCESS, r.outcome)
            assertTrue(r.details!!, r.details!!.startsWith(if (pixelate) "3 private details pixelated" else "3 private details blurred"))
            val out = Robo.decode(File(r.outputs.single().uri.path!!))
            // Kept for checking with the text reader (buildtools/privacy/harness: run.sh find lines).
            File("build/robo-privacy").mkdirs(); File(r.outputs.single().uri.path!!).copyTo(File("build/robo-privacy/${if (pixelate) "pixelated" else "blurred"}-$name"), overwrite = true)
            for (q in quads) assertTrue("letters gone: ${spread(out, q)} vs ${spread(orig, q)}", spread(out, q) < 0.4 * spread(orig, q))
            // Far from the details (the top of the advert) nothing changed beyond JPEG noise.
            var diff = 0.0; var n = 0
            for (y in 0 until orig.height / 3 step 5) for (x in 0 until orig.width step 5) {
                val a = orig.getPixel(x, y); val b = out.getPixel(x, y); diff += Math.abs(Color.green(a) - Color.green(b)); n++
            }
            assertTrue("rest unchanged: ${diff / n}", diff / n < 4)
        }
    }
}
