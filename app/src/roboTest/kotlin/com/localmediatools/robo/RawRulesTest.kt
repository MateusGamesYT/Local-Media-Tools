package com.localmediatools.robo

import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.localmediatools.app.MainActivity
import com.localmediatools.core.MediaKind
import com.localmediatools.core.SniffedFormat
import com.localmediatools.export.ExportManager
import com.localmediatools.export.ItemOutcome
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.WatermarkPosition
import com.localmediatools.tools.DenoiseJob
import com.localmediatools.tools.DenoiseStrength
import com.localmediatools.tools.OptimizeImagesJob
import com.localmediatools.tools.RemoveMetadataJob
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.WatermarkJob
import com.localmediatools.tools.WatermarkJobSpec
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.Selection
import com.localmediatools.ui.ToolRules
import com.localmediatools.ui.tools.ToolScreens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration

/**
 * RAW photos: every tool takes them; the ones that save an edited copy of each photo in another type
 * say so before they start. The Denoiser writes RAW files back as RAW and leaves alone the ones it
 * can't. (The RAW reading and writing itself is tested on real camera data in RawCodecTest; OpenCV's
 * denoising doesn't run under Robolectric.)
 */
@RunWith(RobolectricTestRunner::class)
class RawRulesTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        inDir = File(app.cacheDir, "raw-in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "raw-out").apply { deleteRecursively() }).also { it.install() }
    }

    @After fun tearDown() { outputs.uninstall() }

    private fun res(name: String) = RawRulesTest::class.java.getResourceAsStream("/raw/$name")!!.readBytes()
    private fun file(name: String, bytes: ByteArray) = Robo.item(app, Robo.write(inDir, name, bytes))
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    /** A Canon CR2's first bytes: a little-endian TIFF header, then "CR", version 2. */
    private val cr2Head = byteArrayOf(0x49, 0x49, 0x2A, 0, 0x10, 0, 0, 0, 0x43, 0x52, 2, 0) + ByteArray(500)

    @Test fun rawFilesAreRecognisedByTheirContents() {
        val cases = mapOf(
            file("a.cr2", cr2Head) to SniffedFormat.RAW_CAMERA,
            file("b.cr3", byteArrayOf(0, 0, 0, 0x18) + ascii("ftypcrx ") + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            file("c.raf", ascii("FUJIFILMCCD-RAW 0201FF383501") + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            file("d.orf", ascii("IIRO") + byteArrayOf(8, 0, 0, 0) + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            file("e.rw2", ascii("IIU") + byteArrayOf(0, 8, 0, 0, 0) + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            file("f.nef", byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8) + ByteArray(500)) to SniffedFormat.RAW_CAMERA,
            // Named like a JPEG, but a DNG inside.
            file("g.jpg", res("sm-g960f_crop.dng")) to SniffedFormat.DNG,
            file("h.dng", res("pixel4a_tiles.dng")) to SniffedFormat.DNG,
        )
        for ((item, format) in cases) {
            assertEquals(item.name, format, item.format)
            assertTrue(item.name, item.format.isRaw)
            assertEquals(item.name, MediaKind.IMAGE, item.kind)
        }
    }

    @Test fun everyPhotoToolTakesRawFiles() {
        val cr2 = file("shot.cr2", cr2Head)
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        for (t in listOf(ToolId.AUTO_ENHANCE, ToolId.WATERMARK, ToolId.OPTIMIZE_IMAGES, ToolId.FACE_BLUR, ToolId.REMOVE_METADATA, ToolId.DENOISE,
                ToolId.CONVERT_IMAGES, ToolId.COMPRESS_IMAGES, ToolId.BACKGROUND_REMOVER, ToolId.IMAGES_TO_PDF, ToolId.MERGE_IMAGES, ToolId.PRINT)) {
            assertNull(t.name, ToolRules.issue(t, cr2))
            assertNull(t.name, ToolRules.issue(t, dng))
        }
    }

    @Test fun toolsThatSaveRawFilesAsAnotherTypeSaySoBeforeStarting() {
        val cr2 = file("shot.cr2", cr2Head)
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        val jpg = file("beach.jpg", Robo.encode(android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888), android.graphics.Bitmap.CompressFormat.JPEG))
        for (t in listOf(ToolId.AUTO_ENHANCE, ToolId.WATERMARK, ToolId.OPTIMIZE_IMAGES, ToolId.FACE_BLUR, ToolId.REMOVE_METADATA)) {
            val one = ToolRules.rawWarning(t, listOf(cr2, jpg))
            assertTrue("$t: $one", one != null && one.startsWith("1 RAW file (CR2) will be saved as a lossless PNG by ${t.title}"))
            val two = ToolRules.rawWarning(t, listOf(dng, jpg, cr2))
            assertTrue("$t: $two", two != null && two.startsWith("2 RAW files (CR2, DNG) will be saved as lossless PNGs") && two.contains("only the Denoiser keeps RAW files as RAW"))
            assertNull("$t without RAW files", ToolRules.rawWarning(t, listOf(jpg)))
        }
        // The Denoiser keeps them RAW, the format tools are there to change the format, and the others
        // make something new from photos.
        for (t in listOf(ToolId.DENOISE, ToolId.CONVERT_IMAGES, ToolId.COMPRESS_IMAGES, ToolId.BACKGROUND_REMOVER,
                ToolId.IMAGES_TO_PDF, ToolId.MERGE_IMAGES, ToolId.STITCH, ToolId.PRINT, ToolId.HIGHLIGHT_VIDEO)) {
            assertNull(t.name, ToolRules.rawWarning(t, listOf(cr2, jpg)))
        }
        // A stack: said for the first step that changes the RAW files, if it is one of those tools.
        val stack = ToolRules.rawWarning(listOf(ToolId.DENOISE, ToolId.COMPRESS_VIDEO, ToolId.AUTO_ENHANCE, ToolId.WATERMARK), listOf(cr2))
        assertTrue(stack, stack != null && stack.contains("by ${ToolId.AUTO_ENHANCE.title}"))
        assertNull(ToolRules.rawWarning(listOf(ToolId.CONVERT_IMAGES, ToolId.AUTO_ENHANCE), listOf(cr2)))
        assertNull(ToolRules.rawWarning(listOf(ToolId.IMAGES_TO_PDF, ToolId.WATERMARK), listOf(cr2)))
        assertNull(ToolRules.rawWarning(listOf(ToolId.DENOISE), listOf(cr2)))
        val clean = ToolRules.rawWarning(listOf(ToolId.DENOISE, ToolId.REMOVE_METADATA), listOf(cr2))
        assertTrue(clean, clean != null && clean.contains("by ${ToolId.REMOVE_METADATA.title}"))
        assertNull(ToolRules.rawWarning(listOf(ToolId.AUTO_ENHANCE), listOf(jpg)))
    }

    /** The warning on the tool screen: Cancel starts nothing, Continue starts the export. */
    @Test fun theWarningComesBeforeTheExportStarts() {
        Robo.resetUiState()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val t = ToolId.AUTO_ENHANCE
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        val jpg = file("beach.jpg", Robo.encode(android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }, android.graphics.Bitmap.CompressFormat.JPEG))
        val selection = Selection.of(t)
        selection.clear(); selection.add(listOf(dng, jpg))
        a.navigator.push(ToolScreens.create(a, t)); idle()
        val before = ExportManager.state.value.let { it.history.size + it.queued.size + (if (it.active != null) 1 else 0) }
        fun start() = all(a.navigator.top!!.view).filterIsInstance<ButtonView>().first { it.label == "Start export" }.performClick()
        start(); idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("a warning", dialog)
        assertEquals("RAW files will change type", shadowOf(dialog).title.toString())
        val message = shadowOf(dialog).message.toString()
        assertTrue(message, message.startsWith("1 RAW file (DNG) will be saved as a lossless PNG by ${t.title}"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertEquals("Cancel starts nothing", before, ExportManager.state.value.let { it.history.size + it.queued.size + (if (it.active != null) 1 else 0) })
        start(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertNotNull("Continue starts the export", ExportManager.state.value.latestFor(t))
        val deadline = System.currentTimeMillis() + 60_000
        while (ExportManager.state.value.latestFor(t)?.status?.finished != true && System.currentTimeMillis() < deadline) { idle(100); Thread.sleep(50) }
        val snap = ExportManager.state.value.latestFor(t)!!
        assertTrue(snap.status.toString(), snap.status.finished)
        // The RAW file's result is a PNG (the JPEG's stays a JPEG); nothing is saved as a DNG.
        assertTrue(snap.results.joinToString { "${it.inputName}: ${it.outcome} ${it.message}" }, outputs.committed.map { it.name }.toSet() == setOf("leaf_enhanced.png", "beach_enhanced.jpg"))
        selection.clear()
    }

    /**
     * Remove metadata can't clean a RAW file in its own format, so it makes a lossless PNG of its
     * picture: the same pixels Android decodes from the DNG, upright, with nothing but the picture in it.
     */
    @Test fun removeMetadataMakesACleanLosslessPngOfARawPhoto() {
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        val r = Robo.runJob(app, RemoveMetadataJob(listOf(dng))).single()
        assertEquals(r.toString(), ItemOutcome.SUCCESS, r.outcome)
        assertTrue(r.message, r.message!!.startsWith("Saved as a lossless PNG, because a DNG file can't be cleaned in its own format."))
        val out = outputs.committed.single()
        assertEquals("leaf_clean.png", out.name)
        // Nothing but the picture: no text, EXIF, time or other chunks.
        val png = out.readBytes()
        val chunks = ArrayList<String>()
        var at = 8
        while (at + 8 <= png.size) {
            val len = java.nio.ByteBuffer.wrap(png, at, 4).int
            chunks.add(String(png, at + 4, 4, Charsets.ISO_8859_1))
            at += 12 + len
        }
        assertTrue(chunks.toString(), chunks.first() == "IHDR" && chunks.last() == "IEND" && chunks.all { it in setOf("IHDR", "PLTE", "IDAT", "IEND", "sRGB", "gAMA") })
        // The very pixels Android decodes from the RAW file, turned upright as its orientation tag says
        // (this phone's file is a turned shot: the copy carries no tag, so the pixels must be turned).
        val path = inDir.resolve("leaf.dng").path
        val orientation = android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        val turn = when (orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; 1 -> 0f; else -> throw AssertionError("orientation $orientation") }
        val decoded = android.graphics.BitmapFactory.decodeFile(path)!!
        val want = android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, android.graphics.Matrix().apply { postRotate(turn) }, false)
        val got = android.graphics.BitmapFactory.decodeFile(out.path)!!
        assertEquals("orientation $orientation", want.width to want.height, got.width to got.height)
        val a = IntArray(want.width * want.height).also { want.getPixels(it, 0, want.width, 0, 0, want.width, want.height) }
        val b = IntArray(got.width * got.height).also { got.getPixels(it, 0, got.width, 0, 0, got.width, got.height) }
        assertTrue("pixel-identical", a.contentEquals(b))
    }

    /** Tools not made to convert RAW files save what they make from one as a lossless PNG, whatever else they would use. */
    @Test fun otherToolsSaveTheirResultOfARawPhotoAsPng() {
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        val spec = WatermarkJobSpec("© Villa", null, null, 30, 100, android.graphics.Color.WHITE, emptyMap(), WatermarkPosition.BOTTOM_RIGHT)
        val jobs = listOf(
            WatermarkJob(listOf(dng), spec) to "leaf_wm.png",
            OptimizeImagesJob(listOf(dng), ImageOutFormat.WEBP_LOSSLESS, keepOnlyIfSmaller = false) to "leaf_lossless.png",
        )
        for ((job, name) in jobs) {
            outputs.committed.clear()
            val r = Robo.runJob(app, job).single()
            assertEquals(r.toString(), ItemOutcome.SUCCESS, r.outcome)
            assertEquals(name, outputs.committed.single().name)
            assertTrue(name, outputs.committed.single().readBytes().copyOf(8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        }
    }

    @Test fun theDenoiserLeavesRawFilesItCantWriteBackAndSaysWhy() {
        val items = listOf(
            file("night.orf", ascii("IIRO") + byteArrayOf(8, 0, 0, 0) + ByteArray(500)),
            file("night.cr3", byteArrayOf(0, 0, 0, 0x18) + ascii("ftypcrx ") + ByteArray(500)),
        )
        val results = Robo.runJob(app, DenoiseJob(items, DenoiseStrength.NORMAL))
        assertEquals(results.toString(), 2, results.size)
        for (r in results) {
            assertEquals(r.toString(), ItemOutcome.SKIPPED, r.outcome)
            val label = r.inputName.substringAfterLast('.').uppercase()
            assertTrue(r.message, r.message!!.contains("can't be written back as $label"))
        }
        assertTrue(outputs.committed.isEmpty())
    }

    @Test fun aDamagedRawFileIsReportedNotCrashedOn() {
        val good = res("pixel4a_tiles.dng")
        val broken = file("cut.dng", good.copyOf(good.size / 3))
        val r = Robo.runJob(app, DenoiseJob(listOf(broken), DenoiseStrength.NORMAL)).single()
        assertEquals(r.toString(), ItemOutcome.FAILED, r.outcome)
        assertTrue(r.message, r.message!!.contains("looks damaged"))
    }
}
