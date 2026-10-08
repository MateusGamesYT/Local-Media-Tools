package com.localmediatools.robo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ItemOutcome
import com.localmediatools.image.ImageSource
import com.localmediatools.tools.ExtractPagesJob
import com.localmediatools.tools.ImagesToPdfJob
import com.localmediatools.tools.PageMode
import com.localmediatools.tools.PageOrientation
import com.localmediatools.tools.PageRanges
import com.localmediatools.tools.PdfImageQuality
import com.localmediatools.tools.PdfVerifier
import com.localmediatools.tools.RemoveMetadataJob
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.TrimMath
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class NewToolsTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(app)
        inDir = File(app.cacheDir, "nt-in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "nt-out").apply { deleteRecursively() }).also { it.install() }
        PdfVerifier.impl = { _, uri, pages ->
            val n = PDDocument.load(File(uri.path!!)).use { it.numberOfPages }
            if (n != pages) throw UserFacingException("page count $n != $pages")
        }
    }

    @After fun tearDown() { outputs.uninstall(); PdfVerifier.impl = null }

    /** JPEG with EXIF: orientation 6, GPS and camera model. */
    private fun jpegWithExif(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, if (x < w / 2) Color.RED else Color.BLUE)
        val jpeg = Robo.encode(b, Bitmap.CompressFormat.JPEG, 95)
        fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val tiff = java.io.ByteArrayOutputStream().apply {
            write("MM".toByteArray()); write(be16(42)); write(be32(8)); write(be16(3))
            write(be16(0x0110)); write(be16(2)); write(be32(4)); write(be32(0x50686F00))
            write(be16(0x0112)); write(be16(3)); write(be32(1)); write(be32(6 shl 16))
            write(be16(0x8825)); write(be16(4)); write(be32(1)); write(be32(8 + 2 + 36 + 4))
            write(be32(0))
            write(be16(1)); write(be16(2)); write(be16(5)); write(be32(3)); write(be32(0)); write(be32(0))
        }.toByteArray()
        val app1 = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + be16(app1.size + 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test fun removeMetadataCleansPhotosAndKeepsThemUpright() {
        val src = Robo.item(app, Robo.write(inDir, "trip.jpg", jpegWithExif(80, 40)))
        // Before: displayed rotated (orientation 6) → 40×80.
        ImageSource.open(app, src).use { assertEquals(40, it.width); assertEquals(80, it.height) }
        val r = Robo.runJob(app, RemoveMetadataJob(listOf(src))).single()
        assertEquals(r.message, ItemOutcome.SUCCESS, r.outcome)
        assertTrue(r.message!!.contains("GPS location") && r.message!!.contains("camera make & model"))
        assertTrue(r.message!!.contains("orientation"))
        val f = File(r.outputs.single().uri.path!!)
        assertTrue(f.name.endsWith("_clean.jpg"))
        assertTrue(f.parentFile!!.exists())
        val bytes = f.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Pho"))
        val clean = Robo.item(app, f)
        ImageSource.open(app, clean).use { assertEquals(40, it.width); assertEquals(80, it.height) }
        // Running it again finds nothing to remove.
        val again = Robo.runJob(app, RemoveMetadataJob(listOf(clean))).single()
        assertEquals(ItemOutcome.SKIPPED, again.outcome)
    }

    @Test fun removeMetadataSkipsFormatsItCannotCleanLosslessly() {
        val bmp = Robo.encode(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888), Bitmap.CompressFormat.PNG)
        val tiffLike = Robo.item(app, Robo.write(inDir, "x.tif", byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0) + ByteArray(64)))
        val r = Robo.runJob(app, RemoveMetadataJob(listOf(tiffLike))).single()
        assertTrue(r.outcome != ItemOutcome.SUCCESS)
        assertTrue(bmp.isNotEmpty())
    }

    @Test fun pageRangesParse() {
        assertEquals(listOf(0, 1, 2, 4), PageRanges.parse("1-3, 5", 10))
        assertEquals(listOf(7, 8, 9), PageRanges.parse("8-", 10))
        assertEquals(listOf(0, 1), PageRanges.parse("-2", 10))
        assertEquals(listOf(4, 0), PageRanges.parse("5;1", 10))
        for (bad in listOf("", "0", "11", "3-1", "abc", "12-")) {
            try { PageRanges.parse(bad, 10); fail("accepted \"$bad\"") } catch (e: UserFacingException) { assertTrue(e.message!!.isNotBlank()) }
        }
        assertEquals(0L, TrimMath.keyframeAtOrBefore(longArrayOf(0, 2_000_000, 4_000_000), 1_999_999))
        assertEquals(2_000_000L, TrimMath.keyframeAtOrBefore(longArrayOf(0, 2_000_000, 4_000_000), 3_500_000))
    }

    @Test fun extractPagesInOrderAsOneOrManyFiles() {
        val imgs = (1..4).map { n ->
            val b = Bitmap.createBitmap(100 + n * 10, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(n * 50, 0, 0)) }
            Robo.item(app, Robo.write(inDir, "p$n.png", Robo.encode(b, Bitmap.CompressFormat.PNG)))
        }
        val pdf = Robo.runJob(app, ImagesToPdfJob(ToolId.IMAGES_TO_PDF, imgs, PageOrientation.PORTRAIT, PdfImageQuality.ORIGINAL, "Four")).single()
        assertEquals(pdf.message, ItemOutcome.SUCCESS, pdf.outcome)
        val item = Robo.item(app, File(pdf.outputs.single().uri.path!!))
        val one = Robo.runJob(app, ExtractPagesJob(listOf(item), "4, 1-2", PageMode.ONE_FILE)).single()
        assertEquals(one.message, ItemOutcome.SUCCESS, one.outcome)
        PDDocument.load(File(one.outputs.single().uri.path!!)).use { assertEquals(3, it.numberOfPages) }
        val many = Robo.runJob(app, ExtractPagesJob(listOf(item), "2-3", PageMode.EACH_PAGE)).single()
        assertEquals(2, many.outputs.size)
        assertTrue(many.outputs.map { it.displayName }.containsAll(listOf("Four_p2.pdf", "Four_p3.pdf")))
        val bad = Robo.runJob(app, ExtractPagesJob(listOf(item), "9", PageMode.ONE_FILE)).single()
        assertEquals(ItemOutcome.FAILED, bad.outcome)
        assertTrue(bad.message!!.contains("4 pages"))
    }
}
