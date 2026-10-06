package com.localmediatools.robo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ItemOutcome
import com.localmediatools.tools.ImagesToPdfJob
import com.localmediatools.tools.MergePdfsJob
import com.localmediatools.tools.PageOrientation
import com.localmediatools.tools.PdfImageQuality
import com.localmediatools.tools.PdfVerifier
import com.localmediatools.tools.ToolId
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfToolsTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(app)
        inDir = File(app.cacheDir, "in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "out").apply { deleteRecursively() }).also { it.install() }
        // PdfRenderer is not available under Robolectric: verify with PDFBox instead.
        PdfVerifier.impl = { _, uri, pages ->
            val n = PDDocument.load(File(uri.path!!)).use { it.numberOfPages }
            if (n != pages) throw UserFacingException("page count $n != $pages")
        }
    }

    @After fun tearDown() {
        outputs.uninstall()
        PdfVerifier.impl = null
    }

    private fun img(name: String, w: Int, h: Int, color: Int, fmt: Bitmap.CompressFormat, exif: Int = 0): com.localmediatools.core.MediaItem {
        var bytes = Robo.encode(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, fmt, 90)
        if (exif != 0) bytes = Robo.withExifOrientation(bytes, exif)
        return Robo.item(app, Robo.write(inDir, name, bytes))
    }

    private fun pageSizes(f: File): List<Pair<Float, Float>> = PDDocument.load(f).use { d ->
        (0 until d.numberOfPages).map { d.getPage(it).mediaBox.let { b -> b.width to b.height } }
    }

    @Test fun imagesToA4PdfInOrderWithAutoOrientation() {
        val portrait = img("1.jpg", 300, 400, Color.RED, Bitmap.CompressFormat.JPEG)
        val rotated = img("2.jpg", 400, 300, Color.GREEN, Bitmap.CompressFormat.JPEG, exif = 6) // displays 300×400 (portrait)
        val wide = img("3.png", 800, 300, Color.BLUE, Bitmap.CompressFormat.PNG)
        val r = Robo.runJob(app, ImagesToPdfJob(ToolId.IMAGES_TO_PDF, listOf(portrait, rotated, wide), PageOrientation.AUTO, PdfImageQuality.ORIGINAL, "Album")).single()
        assertEquals(r.message, ItemOutcome.SUCCESS, r.outcome)
        val f = File(r.outputs.single().uri.path!!)
        assertEquals("Album.pdf", f.name)
        val sizes = pageSizes(f)
        assertEquals(3, sizes.size)
        fun near(a: Float, b: Double) = Math.abs(a - b) < 1.5
        assertTrue(near(sizes[0].first, 595.28) && near(sizes[0].second, 841.89))
        assertTrue("EXIF-rotated photo gets a portrait page", near(sizes[1].first, 595.28) && near(sizes[1].second, 841.89))
        assertTrue("wide image gets a landscape page", near(sizes[2].first, 841.89) && near(sizes[2].second, 595.28))
    }

    @Test fun scannerPdfAndMergeKeepAllPages() {
        val a = Robo.runJob(app, ImagesToPdfJob(ToolId.PDF_SCANNER, listOf(img("s1.jpg", 200, 280, Color.WHITE, Bitmap.CompressFormat.JPEG)),
            PageOrientation.PORTRAIT, PdfImageQuality.COMPACT, "Scan A")).single()
        assertEquals(a.message, ItemOutcome.SUCCESS, a.outcome)
        assertTrue(a.message!!.contains("not searchable"))
        val b = Robo.runJob(app, ImagesToPdfJob(ToolId.IMAGES_TO_PDF, listOf(
            img("p1.png", 120, 160, Color.YELLOW, Bitmap.CompressFormat.PNG), img("p2.png", 160, 120, Color.CYAN, Bitmap.CompressFormat.PNG)),
            PageOrientation.AUTO, PdfImageQuality.ORIGINAL, "Two")).single()
        assertEquals(b.message, ItemOutcome.SUCCESS, b.outcome)
        val fa = File(a.outputs.single().uri.path!!); val fb = File(b.outputs.single().uri.path!!)
        val inputs = listOf(Robo.item(app, fb), Robo.item(app, fa))
        val m = Robo.runJob(app, MergePdfsJob(inputs, "Merged docs")).single()
        assertEquals(m.message, ItemOutcome.SUCCESS, m.outcome)
        val merged = pageSizes(File(m.outputs.single().uri.path!!))
        assertEquals(3, merged.size)
        // Order: "Two" (portrait, landscape) then "Scan A" (portrait).
        assertTrue(merged[0].first < merged[0].second && merged[1].first > merged[1].second && merged[2].first < merged[2].second)
    }

    @Test fun mergeRejectsBrokenPdf() {
        val good = Robo.runJob(app, ImagesToPdfJob(ToolId.IMAGES_TO_PDF, listOf(img("g.png", 50, 50, Color.RED, Bitmap.CompressFormat.PNG)),
            PageOrientation.AUTO, PdfImageQuality.ORIGINAL, "G")).single()
        val broken = Robo.item(app, Robo.write(inDir, "broken.pdf", "%PDF-1.4\n1 0 obj << /Type /Catalog >> garbage".toByteArray()))
        val r = Robo.runJob(app, MergePdfsJob(listOf(Robo.item(app, File(good.outputs.single().uri.path!!)), broken), "X")).single()
        assertEquals(ItemOutcome.FAILED, r.outcome)
        assertTrue(r.message!!.isNotBlank())
        assertTrue(outputs.committed.none { it.name.startsWith("X") })
    }
}
