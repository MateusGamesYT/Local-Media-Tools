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
import com.localmediatools.tools.CompressImagesJob
import com.localmediatools.tools.ConvertImagesJob
import com.localmediatools.tools.DenoiseJob
import com.localmediatools.tools.DenoiseStrength
import com.localmediatools.tools.OptimizeImagesJob
import com.localmediatools.tools.RawFallback
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
 * RAW photos are files like any other: every tool takes them, and where you choose the format they
 * get your choice. Tools that keep each file's format keep them RAW where they can (the Denoiser);
 * where they can't, they say so before starting and save them like the other files, at full quality.
 * (The RAW reading and writing itself is tested on real camera data in RawCodecTest; OpenCV's
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

    private fun photo(name: String, fmt: android.graphics.Bitmap.CompressFormat) =
        file(name, Robo.encode(android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }, fmt))
    private val orfHead = ascii("IIRO") + byteArrayOf(8, 0, 0, 0) + ByteArray(500)
    private val cr3Head = byteArrayOf(0, 0, 0, 0x18) + ascii("ftypcrx ") + ByteArray(500)

    /** A JPEG's quantisation steps and each colour component's sampling, from its headers. */
    private fun jpegTables(b: ByteArray): Pair<List<Int>, List<Int>> {
        val q = ArrayList<Int>(); val sampling = ArrayList<Int>()
        var i = 2
        while (i + 4 <= b.size && b[i] == 0xFF.toByte()) {
            val m = b[i + 1].toInt() and 255
            val len = ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255)
            if (m == 0xDB) { var k = i + 4; while (k < i + 2 + len) { k++; repeat(64) { q.add(b[k].toInt() and 255); k++ } } }
            if (m == 0xC0 || m == 0xC2) for (c in 0 until (b[i + 9].toInt() and 255)) sampling.add(b[i + 11 + c * 3].toInt() and 255)
            if (m == 0xDA) break
            i += 2 + len
        }
        return q to sampling
    }

    /** At full quality: every quantisation step 1, colour at full resolution. */
    private fun assertFullQuality(name: String, b: ByteArray) {
        val (q, sampling) = jpegTables(b)
        assertTrue("$name: $q", q.isNotEmpty() && q.all { it == 1 })
        assertTrue("$name: $sampling", sampling.size == 3 && sampling.all { it == 0x11 })
    }

    @Test fun rawPhotosThatCantStayRawAreToldAndFollowTheOtherFiles() {
        val cr2 = file("shot.cr2", cr2Head)
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        val orf = file("night.orf", orfHead)
        val cr3 = file("night.cr3", cr3Head)
        val jpg = photo("beach.jpg", android.graphics.Bitmap.CompressFormat.JPEG)
        val png = photo("logo.png", android.graphics.Bitmap.CompressFormat.PNG)
        val png2 = photo("chart.png", android.graphics.Bitmap.CompressFormat.PNG)
        // Tools that keep each file's format can't keep a RAW photo RAW: a JPEG among JPEGs (and with
        // nothing to follow, or as many of each), a PNG among PNGs.
        for (t in listOf(ToolId.AUTO_ENHANCE, ToolId.WATERMARK, ToolId.FACE_BLUR, ToolId.REMOVE_METADATA)) {
            val one = ToolRules.rawWarning(t, listOf(cr2, jpg))
            assertTrue("$t: $one", one != null && one.startsWith("1 RAW file (CR2) can't be saved in its own format by ${t.title}, so it will be saved as a JPEG at full quality."))
            val amongPngs = ToolRules.rawWarning(t, listOf(png, cr2, png2, jpg))
            assertTrue("$t: $amongPngs", amongPngs != null && amongPngs.startsWith("1 RAW file (CR2) can't be saved in its own format by ${t.title}, so it will be saved as a lossless PNG, like the other files."))
            val alone = ToolRules.rawWarning(t, listOf(dng, cr2))
            assertTrue("$t: $alone", alone != null && alone.startsWith("2 RAW files (CR2, DNG) can't be saved in their own format by ${t.title}, so they will be saved as JPEGs at full quality."))
            assertEquals(t.name, ImageOutFormat.JPEG, RawFallback.format(t, listOf(cr2, png, jpg)))
            assertNull("$t without RAW files", ToolRules.rawWarning(t, listOf(jpg)))
        }
        // The Denoiser writes DNG, CR2, NEF and ARW back as themselves; other RAW types follow the other files.
        assertNull(ToolRules.rawWarning(ToolId.DENOISE, listOf(cr2, dng, jpg)))
        val orfs = ToolRules.rawWarning(ToolId.DENOISE, listOf(orf, cr2, png, png2))
        assertTrue(orfs, orfs != null && orfs.startsWith("1 RAW file (ORF) can't be saved in its own format by ${ToolId.DENOISE.title}, so it will be saved as a lossless PNG"))
        val two = ToolRules.rawWarning(ToolId.DENOISE, listOf(cr3, orf, jpg))
        assertTrue(two, two != null && two.startsWith("2 RAW files (CR3, ORF) can't be saved in their own format by ${ToolId.DENOISE.title}, so they will be saved as JPEGs at full quality."))
        // Where you choose the format (or make something new from photos), RAW photos follow your choice: nothing to say.
        for (t in listOf(ToolId.OPTIMIZE_IMAGES, ToolId.CONVERT_IMAGES, ToolId.COMPRESS_IMAGES, ToolId.BACKGROUND_REMOVER,
                ToolId.IMAGES_TO_PDF, ToolId.MERGE_IMAGES, ToolId.STITCH, ToolId.PRINT, ToolId.HIGHLIGHT_VIDEO)) {
            assertNull(t.name, ToolRules.rawWarning(t, listOf(cr2, jpg)))
        }
        // A stack: said for the first step that changes the RAW photos.
        val stack = ToolRules.rawWarning(listOf(ToolId.DENOISE, ToolId.COMPRESS_VIDEO, ToolId.AUTO_ENHANCE, ToolId.WATERMARK), listOf(cr2, jpg))
        assertTrue(stack, stack != null && stack.contains("by ${ToolId.AUTO_ENHANCE.title}, so it will be saved as a JPEG"))
        assertNull(ToolRules.rawWarning(listOf(ToolId.CONVERT_IMAGES, ToolId.AUTO_ENHANCE), listOf(cr2)))
        assertNull(ToolRules.rawWarning(listOf(ToolId.OPTIMIZE_IMAGES, ToolId.WATERMARK), listOf(cr2)))
        assertNull(ToolRules.rawWarning(listOf(ToolId.IMAGES_TO_PDF, ToolId.WATERMARK), listOf(cr2)))
        assertNull(ToolRules.rawWarning(listOf(ToolId.DENOISE), listOf(cr2)))
        val clean = ToolRules.rawWarning(listOf(ToolId.DENOISE, ToolId.REMOVE_METADATA), listOf(cr2))
        assertTrue(clean, clean != null && clean.contains("by ${ToolId.REMOVE_METADATA.title}"))
        val denoised = ToolRules.rawWarning(listOf(ToolId.DENOISE, ToolId.AUTO_ENHANCE), listOf(orf))
        assertTrue(denoised, denoised != null && denoised.contains("by ${ToolId.DENOISE.title}"))
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
        val jpg = photo("beach.jpg", android.graphics.Bitmap.CompressFormat.JPEG)
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
        assertTrue(message, message.startsWith("1 RAW file (DNG) can't be saved in its own format by ${t.title}, so it will be saved as a JPEG at full quality."))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        assertEquals("Cancel starts nothing", before, ExportManager.state.value.let { it.history.size + it.queued.size + (if (it.active != null) 1 else 0) })
        start(); idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertNotNull("Continue starts the export", ExportManager.state.value.latestFor(t))
        val deadline = System.currentTimeMillis() + 60_000
        while (ExportManager.state.value.latestFor(t)?.status?.finished != true && System.currentTimeMillis() < deadline) { idle(100); Thread.sleep(50) }
        val snap = ExportManager.state.value.latestFor(t)!!
        assertTrue(snap.status.toString(), snap.status.finished)
        // The RAW photo became a JPEG like the other one, but at full quality; nothing is saved as a DNG.
        val byName = outputs.committed.associateBy { it.name }
        assertEquals(snap.results.joinToString { "${it.inputName}: ${it.outcome} ${it.message}" }, setOf("leaf_enhanced.jpg", "beach_enhanced.jpg"), byName.keys)
        assertFullQuality("leaf_enhanced.jpg", byName.getValue("leaf_enhanced.jpg").readBytes())
        assertTrue("the other photo keeps the tool's own quality", jpegTables(byName.getValue("beach_enhanced.jpg").readBytes()).first.any { it > 1 })
        assertTrue(snap.results.toString(), snap.results.first { it.inputName == "leaf.dng" }.message == "Saved as a JPEG at full quality, because this tool can't write DNG files.")
        selection.clear()
    }

    /** The upright picture Android decodes from the test DNG (the copy carries no orientation tag, so its pixels must be turned). */
    private fun uprightDng(): IntArray {
        val path = inDir.resolve("leaf.dng").path
        val orientation = android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        val turn = when (orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; 1 -> 0f; else -> throw AssertionError("orientation $orientation") }
        val decoded = android.graphics.BitmapFactory.decodeFile(path)!!
        val want = android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, android.graphics.Matrix().apply { postRotate(turn) }, false)
        assertEquals(128 to 256, want.width to want.height)
        return IntArray(want.width * want.height).also { want.getPixels(it, 0, want.width, 0, 0, want.width, want.height) }
    }

    private fun pixels(f: File): IntArray = android.graphics.BitmapFactory.decodeFile(f.path)!!.let { b ->
        assertEquals(128 to 256, b.width to b.height)
        IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    /**
     * Remove metadata can't clean a RAW file in its own format, so it makes a copy of its picture alone,
     * upright: a lossless PNG among PNGs (the very pixels Android decodes from the DNG), otherwise a
     * JPEG at full quality; neither carries any of the RAW file's metadata.
     */
    @Test fun removeMetadataMakesACleanCopyOfARawPhotoLikeTheOtherFiles() {
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        val png = photo("logo.png", android.graphics.Bitmap.CompressFormat.PNG)
        val r = Robo.runJob(app, RemoveMetadataJob(listOf(dng, png))).first { it.inputName == "leaf.dng" }
        assertEquals(r.toString(), ItemOutcome.SUCCESS, r.outcome)
        assertTrue(r.message, r.message!!.startsWith("Saved as a lossless PNG, because this tool can't write DNG files. None of its metadata was copied"))
        val out = outputs.committed.single()
        assertEquals("leaf_clean.png", out.name)
        // Nothing but the picture: no text, EXIF, time or other chunks.
        val bytes = out.readBytes()
        val chunks = ArrayList<String>()
        var at = 8
        while (at + 8 <= bytes.size) {
            val len = java.nio.ByteBuffer.wrap(bytes, at, 4).int
            chunks.add(String(bytes, at + 4, 4, Charsets.ISO_8859_1))
            at += 12 + len
        }
        assertTrue(chunks.toString(), chunks.first() == "IHDR" && chunks.last() == "IEND" && chunks.all { it in setOf("IHDR", "PLTE", "IDAT", "IEND", "sRGB", "gAMA") })
        val want = uprightDng()
        assertTrue("pixel-identical", want.contentEquals(pixels(out)))

        // On its own (nothing to follow), a JPEG at full quality, with no EXIF or XMP segment.
        outputs.committed.clear()
        assertEquals(ItemOutcome.SUCCESS, Robo.runJob(app, RemoveMetadataJob(listOf(dng))).single().outcome)
        val jpg = outputs.committed.single()
        assertEquals("leaf_clean.jpg", jpg.name)
        val jb = jpg.readBytes()
        assertFullQuality(jpg.name, jb)
        var i = 2
        val markers = ArrayList<Int>()
        while (i + 4 <= jb.size && jb[i] == 0xFF.toByte()) {
            val m = jb[i + 1].toInt() and 255; markers.add(m)
            if (m == 0xDA) break
            i += 2 + (((jb[i + 2].toInt() and 255) shl 8) or (jb[i + 3].toInt() and 255))
        }
        assertTrue(markers.joinToString { Integer.toHexString(it) }, markers.none { it in 0xE1..0xEF || it == 0xFE })
        val got = pixels(jpg)
        var worst = 0
        for (k in want.indices) for (sh in intArrayOf(16, 8, 0)) worst = maxOf(worst, Math.abs(((want[k] shr sh) and 255) - ((got[k] shr sh) and 255)))
        assertTrue("largest difference $worst", worst <= 4)
    }

    /** Where you choose the format, a RAW photo gets your choice like any other file. */
    @Test fun rawPhotosFollowTheFormatYouChoose() {
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        val jobs = listOf(
            OptimizeImagesJob(listOf(dng), ImageOutFormat.WEBP_LOSSLESS, keepOnlyIfSmaller = false) to "leaf_lossless.webp",
            ConvertImagesJob(listOf(dng), ImageOutFormat.WEBP, 80) to "leaf.webp",
            ConvertImagesJob(listOf(dng), ImageOutFormat.PNG, 90) to "leaf.png",
            CompressImagesJob(listOf(dng), ImageOutFormat.JPEG, 70, 0) to "leaf_q70.jpg",
        )
        for ((job, name) in jobs) {
            outputs.committed.clear()
            val r = Robo.runJob(app, job).single()
            assertEquals(r.toString(), ItemOutcome.SUCCESS, r.outcome)
            assertEquals(name, outputs.committed.single().name)
        }
        // The compressor's quality is yours too: not raised to full quality for a RAW photo.
        assertTrue(jpegTables(outputs.committed.single().readBytes()).first.any { it > 1 })
    }

    /** A watermarked RAW photo is a PNG among PNGs, otherwise a JPEG at full quality. */
    @Test fun aWatermarkedRawPhotoFollowsTheOtherFiles() {
        val dng = file("leaf.dng", res("sm-g960f_crop.dng"))
        val png = photo("logo.png", android.graphics.Bitmap.CompressFormat.PNG)
        val spec = WatermarkJobSpec("© Villa", null, null, 30, 100, android.graphics.Color.WHITE, emptyMap(), WatermarkPosition.BOTTOM_RIGHT)
        Robo.runJob(app, WatermarkJob(listOf(dng, png), spec)).forEach { assertEquals(it.toString(), ItemOutcome.SUCCESS, it.outcome) }
        assertEquals(setOf("leaf_wm.png", "logo_wm.png"), outputs.committed.map { it.name }.toSet())
        outputs.committed.clear()
        Robo.runJob(app, WatermarkJob(listOf(dng), spec)).single().let { assertEquals(it.toString(), ItemOutcome.SUCCESS, it.outcome) }
        val jpg = outputs.committed.single()
        assertEquals("leaf_wm.jpg", jpg.name)
        assertFullQuality(jpg.name, jpg.readBytes())
    }

    /**
     * RAW types the Denoiser can't write back are no longer left out: they are cleaned as pictures.
     * These two are made-up headers Android can't decode, so here they fail, with the decoder's reason.
     */
    @Test fun theDenoiserCleansRawFilesItCantWriteBackAsPictures() {
        val items = listOf(file("night.orf", orfHead), file("night.cr3", cr3Head))
        val results = Robo.runJob(app, DenoiseJob(items, DenoiseStrength.NORMAL))
        assertEquals(results.toString(), 2, results.size)
        for (r in results) {
            assertEquals(r.toString(), ItemOutcome.FAILED, r.outcome)
            assertTrue(r.message, r.message!!.contains("isn't supported by Android's decoder"))
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
