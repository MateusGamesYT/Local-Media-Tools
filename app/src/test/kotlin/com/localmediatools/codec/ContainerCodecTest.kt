package com.localmediatools.codec

import com.localmediatools.codec.image.JpegInfo
import com.localmediatools.codec.image.Orientation
import com.localmediatools.codec.jpeg.JpegWriter
import com.localmediatools.codec.layout.IRect
import com.localmediatools.codec.layout.LargestRectangle
import com.localmediatools.codec.layout.MergeLayouts
import com.localmediatools.codec.mp4.Mp4FastStart
import com.localmediatools.codec.pdf.PdfImageWriter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Random

class ContainerCodecTest {

    /** A 2:1 raw JPEG whose left half is red and right half blue, optionally with an EXIF tag. */
    private fun twoColorJpeg(orientation: Int): ByteArray {
        val w = 200; val h = 100
        val px = IntArray(w * h) { if (it % w < w / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val out = ByteArrayOutputStream()
        JpegWriter(out, w, h, 95).apply { writeRows(px, 0, w, h); finish() }
        val plain = out.toByteArray()
        if (orientation == 1) return plain
        // Insert an APP1 EXIF segment right after SOI.
        val tiff = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 1,
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0)
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
        return plain.copyOfRange(0, 2) + seg + plain.copyOfRange(2, plain.size)
    }

    @Test
    fun pdfEmbedsJpegWithCorrectOrientation() {
        val out = ByteArrayOutputStream()
        val pdf = PdfImageWriter(out)
        val cases = listOf(1, 6, 3, 8, 2)
        for (o in cases) {
            val jpeg = twoColorJpeg(o)
            val info = JpegInfo.parse(ByteArrayInputStream(jpeg))!!
            pdf.addJpegPage({ ByteArrayInputStream(jpeg) }, info, Orientation.fromExif(o), PdfImageWriter.PageBox.A4_PORTRAIT, 20.0)
        }
        pdf.finish(title = "Test – ünïcode", creationDate = "20260101120000")
        PDDocument.load(out.toByteArray()).use { doc ->
            assertEquals(cases.size, doc.numberOfPages)
            val renderer = PDFRenderer(doc)
            for ((i, o) in cases.withIndex()) {
                val img = renderer.renderImageWithDPI(i, 36f)
                // Probe the displayed image near its edges to recover where red ended up.
                fun isRed(x: Int, y: Int): Boolean { val c = img.getRGB(x, y); return ((c shr 16) and 0xFF) > 150 && (c and 0xFF) < 100 }
                val cx = img.width / 2; val cy = img.height / 2
                when (o) {
                    1 -> { assertTrue(isRed(cx - 40, cy)); assertFalse(isRed(cx + 40, cy)) }
                    2 -> { assertFalse(isRed(cx - 40, cy)); assertTrue(isRed(cx + 40, cy)) } // mirrored
                    3 -> { assertFalse(isRed(cx - 40, cy)); assertTrue(isRed(cx + 40, cy)) }
                    6 -> { assertTrue(isRed(cx, cy - 40)); assertFalse(isRed(cx, cy + 40)) } // left -> top
                    8 -> { assertFalse(isRed(cx, cy - 40)); assertTrue(isRed(cx, cy + 40)) } // left -> bottom
                }
            }
        }
    }

    @Test
    fun pdfRawPagesWithAlpha() {
        val out = ByteArrayOutputStream()
        val pdf = PdfImageWriter(out)
        val w = 300; val h = 120
        val src = object : PdfImageWriter.RowSource {
            override val width = w
            override val height = h
            override val hasAlpha = true
            override val grayscale = false
            override fun readRows(y: Int, count: Int, dst: IntArray) {
                for (r in 0 until count) for (x in 0 until w) dst[r * w + x] = if (x < w / 2) 0xFF00FF00.toInt() else 0x00000000
            }
        }
        pdf.addRawPage(src, PdfImageWriter.PageBox.A4_LANDSCAPE, 20.0)
        pdf.finish()
        PDDocument.load(out.toByteArray()).use { doc ->
            assertEquals(1, doc.numberOfPages)
            assertEquals(841.8898f, doc.getPage(0).mediaBox.width, 0.01f)
            val img = PDFRenderer(doc).renderImageWithDPI(0, 36f)
            val left = img.getRGB(img.width / 2 - 60, img.height / 2)
            val right = img.getRGB(img.width / 2 + 60, img.height / 2)
            assertTrue((left shr 8 and 0xFF) > 200 && (left shr 16 and 0xFF) < 50)
            assertTrue((right and 0xFFFFFF) == 0xFFFFFF) // transparent -> page white
        }
    }

    @Test
    fun placementFitsInsideMargins() {
        for (o in Orientation.entries) {
            val m = PdfImageWriter.placement(4000, 3000, o, PdfImageWriter.PageBox.A4_PORTRAIT, 20.0)
            val xs = listOf(m[4], m[4] + m[0], m[4] + m[2], m[4] + m[0] + m[2])
            val ys = listOf(m[5], m[5] + m[1], m[5] + m[3], m[5] + m[1] + m[3])
            assertTrue(xs.min() >= 19.99 && xs.max() <= 595.28 - 19.99)
            assertTrue(ys.min() >= 19.99 && ys.max() <= 841.89 - 19.99)
            val drawW = xs.max() - xs.min(); val drawH = ys.max() - ys.min()
            val expectAspect = if (o.swapsDimensions) 3000.0 / 4000 else 4000.0 / 3000
            assertEquals(expectAspect, drawW / drawH, 1e-6)
        }
    }

    // ------------------------------------------------------------------ MP4
    private fun ffmpeg(vararg args: String): Boolean {
        return try {
            val p = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-y") + args).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor() == 0
        } catch (e: Exception) { false }
    }

    private fun frameHashes(file: File): String {
        val p = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-i", file.path, "-map", "0", "-c", "copy", "-f", "framemd5", "-").start()
        val text = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.joinToString("\n") { line ->
            val parts = line.split(",").map { it.trim() }
            "${parts[0]},${parts[2]},${parts[4]},${parts[5]}" // stream, pts, size, md5
        }
    }

    @Test
    fun fastStartMovesMoovAndKeepsSamples() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lmt-test-" + System.nanoTime()).apply { mkdirs() }
        val src = File(dir, "in.mp4")
        assumeTrue("ffmpeg not available", ffmpeg("-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-f", "lavfi",
            "-i", "sine=frequency=440", "-t", "3", "-c:v", "mpeg4", "-c:a", "aac", "-metadata:s:v", "rotate=90", src.path))
        val before = Mp4FastStart.scan(RandomAccessFile(src, "r").also { it.close() }.let { RandomAccessFile(src, "r") })
        assertTrue(before.indexOfFirst { it.type == "moov" } > before.indexOfFirst { it.type == "mdat" })
        val dst = File(dir, "out.mp4")
        RandomAccessFile(src, "r").use { raf -> dst.outputStream().buffered().use { Mp4FastStart.process(raf, it) } }
        val after = RandomAccessFile(dst, "r").use { Mp4FastStart.scan(it) }
        assertTrue(Mp4FastStart.isOptimized(after))
        assertEquals(frameHashes(src), frameHashes(dst))
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ layouts
    @Test
    fun smartPackHasNoOverlapsAndIsCompact() {
        val rnd = Random(5)
        for (n in listOf(2, 3, 7, 20, 120)) {
            val sizes = List(n) { (50 + rnd.nextInt(400)) to (50 + rnd.nextInt(400)) }
            val r = MergeLayouts.smartPack(sizes, 0)
            for (i in sizes.indices) {
                val a = r.positions[i]
                assertEquals(sizes[i], a.w to a.h)
                assertTrue(a.x >= 0 && a.y >= 0 && a.right <= r.width && a.bottom <= r.height)
                for (j in i + 1 until sizes.size) assertFalse("overlap $i $j", a.intersects(r.positions[j]))
            }
            val area = sizes.sumOf { it.first.toLong() * it.second }
            val canvas = r.width.toLong() * r.height
            assertTrue("n=$n fill ${area.toDouble() / canvas}", area.toDouble() / canvas > (if (n <= 3) 0.45 else 0.62))
        }
        val v = MergeLayouts.vertical(listOf(100 to 50, 60 to 40), 10, com.localmediatools.codec.layout.Align.CENTER)
        assertEquals(100, v.width); assertEquals(100, v.height); assertEquals(IRect(20, 60, 60, 40), v.positions[1])
    }

    @Test
    fun largestRectangle() {
        val w = 10; val h = 6
        val mask = BooleanArray(w * h) { true }
        mask[0] = false; mask[w * h - 1] = false
        for (y in 0 until h) mask[y * w + 9] = false
        val r = LargestRectangle.find(mask, w, h)!!
        assertEquals(48L, r.w.toLong() * r.h) // columns 1..8 over all 6 rows beats 9 x 5
    }
}
