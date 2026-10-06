package com.localmediatools.codec

import com.localmediatools.codec.image.ExifOrientationReader
import com.localmediatools.codec.image.JpegInfo
import com.localmediatools.codec.image.Orientation
import com.localmediatools.codec.image.PixelTransforms
import com.localmediatools.codec.jpeg.JpegWriter
import com.localmediatools.codec.png.PngColorAnalyzer
import com.localmediatools.codec.png.PngColorMode
import com.localmediatools.codec.png.PngWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO

class ImageCodecTest {

    private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    private fun pixels(img: BufferedImage): IntArray = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    private fun photoLike(w: Int, h: Int, alpha: Boolean = false, seed: Long = 1): IntArray {
        val r = Random(seed)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val rr = (128 + 100 * Math.sin(x / 13.0) + r.nextInt(9)).toInt().coerceIn(0, 255)
            val gg = (128 + 100 * Math.cos(y / 17.0) + r.nextInt(9)).toInt().coerceIn(0, 255)
            val bb = ((x + y) * 255 / (w + h)).coerceIn(0, 255)
            val a = if (alpha) ((x * 255) / w) else 255
            (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    // ---------------------------------------------------------------- orientation
    @Test
    fun orientationDimensionsAndInverse() {
        for (o in Orientation.entries) {
            assertEquals(o.swapsDimensions, o.exifValue >= 5)
            assertEquals(Orientation.NORMAL, o.then(o.inverse()))
        }
        assertEquals(Orientation.TRANSPOSE, Orientation.fromRotation(270, mirrored = true))
        assertEquals(Orientation.TRANSVERSE, Orientation.fromRotation(90, mirrored = true))
        assertEquals(Orientation.ROTATE_90, Orientation.fromRotation(-270))
    }

    @Test
    fun rotate90MatchesExifDefinition() {
        // Raw 3x2 image:  a b c / d e f. EXIF 6 (rotate 90 CW) displays: d a / e b / f c.
        val raw = intArrayOf(1, 2, 3, 4, 5, 6)
        val disp = PixelTransforms.orient(raw, 3, 2, Orientation.ROTATE_90)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), disp)
        // EXIF 8 (rotate 270 CW): c f / b e / a d
        assertArrayEquals(intArrayOf(3, 6, 2, 5, 1, 4), PixelTransforms.orient(raw, 3, 2, Orientation.ROTATE_270))
        // EXIF 2 mirror: c b a / f e d
        assertArrayEquals(intArrayOf(3, 2, 1, 6, 5, 4), PixelTransforms.orient(raw, 3, 2, Orientation.FLIP_HORIZONTAL))
        // EXIF 5 transpose: a d / b e / c f
        assertArrayEquals(intArrayOf(1, 4, 2, 5, 3, 6), PixelTransforms.orient(raw, 3, 2, Orientation.TRANSPOSE))
        // EXIF 7 transverse: f c / e b / d a
        assertArrayEquals(intArrayOf(6, 3, 5, 2, 4, 1), PixelTransforms.orient(raw, 3, 2, Orientation.TRANSVERSE))
    }

    @Test
    fun pixelTransformsAgreeWithAffineMatrices() {
        val w = 5; val h = 3
        val raw = IntArray(w * h) { it }
        for (o in Orientation.entries) {
            val disp = PixelTransforms.orient(raw, w, h, o)
            val dw = o.displayWidth(w, h)
            val m = o.rawToDisplay(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val cx = x + 0.5; val cy = y + 0.5
                val dx = (m[0] * cx + m[1] * cy + m[4] - 0.5).toInt()
                val dy = (m[2] * cx + m[3] * cy + m[5] - 0.5).toInt()
                assertEquals("$o", raw[y * w + x], disp[dy * dw + dx])
            }
            // Rect mapping round-trips the whole image.
            val r = o.displayRectToRaw(0, 0, dw, o.displayHeight(w, h), w, h)
            assertArrayEquals(intArrayOf(0, 0, w, h), r)
        }
    }

    @Test
    fun stripwiseOrientationEqualsWholeImage() {
        val w = 7; val h = 4
        val raw = IntArray(w * h) { it * 3 + 1 }
        for (o in Orientation.entries) {
            val full = PixelTransforms.orient(raw, w, h, o)
            val dw = o.displayWidth(w, h); val dh = o.displayHeight(w, h)
            for (top in 0 until dh) {
                val rect = o.displayRectToRaw(0, top, dw, top + 1, w, h)
                val rw = rect[2] - rect[0]; val rh = rect[3] - rect[1]
                val sub = IntArray(rw * rh)
                for (yy in 0 until rh) for (xx in 0 until rw) sub[yy * rw + xx] = raw[(rect[1] + yy) * w + rect[0] + xx]
                val row = IntArray(dw)
                PixelTransforms.orientRows(sub, rect[0], rect[1], rw, rh, w, h, o, top, 1, row)
                for (x in 0 until dw) assertEquals(full[top * dw + x], row[x])
            }
        }
    }

    // ---------------------------------------------------------------- EXIF
    private fun tiffWithOrientation(value: Int, littleEndian: Boolean): ByteArray {
        val b = java.nio.ByteBuffer.allocate(26)
        b.order(if (littleEndian) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
        b.put(if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte())
        b.put(if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte())
        b.putShort(42); b.putInt(8); b.putShort(1)
        b.putShort(0x0112); b.putShort(3); b.putInt(1); b.putShort(value.toShort()); b.putShort(0)
        b.putInt(0)
        return b.array()
    }

    @Test
    fun readsJpegExifOrientation() {
        for (le in listOf(true, false)) for (v in 1..8) {
            val tiff = tiffWithOrientation(v, le)
            val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) +
                byteArrayOf(((tiff.size + 8) shr 8).toByte(), (tiff.size + 8).toByte()) +
                "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
            val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 4, 1, 2)
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + app1 + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
            assertEquals(Orientation.fromExif(v), ExifOrientationReader.read(jpeg))
        }
    }

    @Test
    fun readsPngAndWebpExif() {
        val tiff = tiffWithOrientation(6, false)
        val png = ByteArrayOutputStream()
        png.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            png.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
            png.write(type.toByteArray()); png.write(data); png.write(ByteArray(4))
        }
        chunk("IHDR", ByteArray(13)); chunk("eXIf", tiff); chunk("IDAT", ByteArray(2))
        assertEquals(Orientation.ROTATE_90, ExifOrientationReader.read(png.toByteArray()))

        val webp = ByteArrayOutputStream()
        val exifChunk = "EXIF".toByteArray() + byteArrayOf(tiff.size.toByte(), 0, 0, 0) + tiff
        webp.write("RIFF".toByteArray()); webp.write(byteArrayOf(0, 0, 0, 0)); webp.write("WEBP".toByteArray())
        webp.write("VP8X".toByteArray()); webp.write(byteArrayOf(10, 0, 0, 0)); webp.write(ByteArray(10))
        webp.write(exifChunk)
        assertEquals(Orientation.ROTATE_90, ExifOrientationReader.read(webp.toByteArray()))
    }

    @Test
    fun jpegWithoutExifIsNormal() {
        val out = ByteArrayOutputStream()
        val w = JpegWriter(out, 16, 16, 90)
        w.writeRows(IntArray(256) { 0xFF336699.toInt() }, 0, 16, 16); w.finish()
        assertEquals(Orientation.NORMAL, ExifOrientationReader.read(out.toByteArray()))
        val info = JpegInfo.parse(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(info)
        assertEquals(16, info!!.width); assertEquals(3, info.components); assertTrue(info.embeddableInPdf)
    }

    // ---------------------------------------------------------------- PNG
    @Test
    fun pngRgbaRoundTripIsExact() {
        val w = 97; val h = 61
        val px = photoLike(w, h, alpha = true)
        // Non-premultiplied semi-transparent values must survive unchanged.
        val out = ByteArrayOutputStream()
        val pw = PngWriter(out, w, h, PngColorMode.RGBA, compressionLevel = 9)
        var y = 0
        while (y < h) { val n = minOf(7, h - y); pw.writeRows(px, y * w, w, n); y += n }
        pw.finish()
        val img = decode(out.toByteArray())
        assertEquals(w, img.width); assertEquals(h, img.height)
        assertArrayEquals(px, pixels(img))
    }

    @Test
    fun pngRgbGrayAndPaletteModes() {
        val w = 40; val h = 30
        val rgb = photoLike(w, h)
        val gray = IntArray(w * h) { val v = (it * 7) % 256; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val pal = IntArray(w * h) { if ((it / 3) % 2 == 0) 0xFFFF0000.toInt() else if (it % 5 == 0) 0x00000000 else 0x800000FF.toInt() }
        for ((src, expectMode) in listOf(rgb to PngColorMode.RGB, gray to PngColorMode.GRAY, pal to PngColorMode.PALETTE)) {
            val an = PngColorAnalyzer()
            an.feed(src, 0, w, w, h)
            val (mode, palette) = an.choose()
            assertEquals(expectMode, mode)
            val out = ByteArrayOutputStream()
            val pw = PngWriter(out, w, h, mode, palette)
            pw.writeRows(src, 0, w, h); pw.finish()
            val img = decode(out.toByteArray())
            if (mode == PngColorMode.GRAY) {
                // Java2D gamma-converts gray images in getRGB(); compare raw samples instead.
                for (i in src.indices) assertEquals(src[i] and 0xFF, img.raster.getSample(i % w, i / w, 0))
                continue
            }
            val got = pixels(img)
            for (i in src.indices) {
                // Java2D returns 0 colour channels for fully transparent palette entries.
                if ((src[i] ushr 24) == 0) assertEquals(0, got[i] ushr 24) else assertEquals(src[i], got[i])
            }
        }
    }

    @Test
    fun pngLowBitDepthPalette() {
        for (colors in listOf(2, 3, 9)) {
            val w = 13; val h = 5
            val palette = IntArray(colors) { (0xFF shl 24) or (it * 20 shl 16) or (it * 3) }
            val src = IntArray(w * h) { palette[(it * 7) % colors] }
            val out = ByteArrayOutputStream()
            val pw = PngWriter(out, w, h, PngColorMode.PALETTE, palette)
            pw.writeRows(src, 0, w, h); pw.finish()
            assertArrayEquals(src, pixels(decode(out.toByteArray())))
        }
    }

    // ---------------------------------------------------------------- JPEG
    private fun psnr(a: IntArray, b: IntArray): Double {
        var se = 0.0
        for (i in a.indices) {
            for (s in intArrayOf(0, 8, 16)) {
                val d = ((a[i] ushr s) and 0xFF) - ((b[i] ushr s) and 0xFF)
                se += d * d
            }
        }
        val mse = se / (a.size * 3)
        return 10 * Math.log10(255.0 * 255.0 / mse)
    }

    @Test
    fun jpegDecodesWithGoodQuality() {
        for ((w, h) in listOf(64 to 48, 101 to 77, 1 to 1, 17 to 300)) {
            val px = photoLike(w, h)
            for (q in listOf(50, 85, 95)) {
                val out = ByteArrayOutputStream()
                val jw = JpegWriter(out, w, h, q)
                var y = 0
                while (y < h) { val n = minOf(5, h - y); jw.writeRows(px, y * w, w, n); y += n }
                jw.finish()
                val img = decode(out.toByteArray())
                assertEquals(w, img.width); assertEquals(h, img.height)
                if (w * h > 100) {
                    val p = psnr(px, pixels(img))
                    assertTrue("PSNR $p at q=$q ${w}x$h", p > (if (q >= 85) 30.0 else 26.0))
                }
            }
        }
    }

    @Test
    fun jpegGrayscaleAndAlphaBackground() {
        val w = 32; val h = 32
        val out = ByteArrayOutputStream()
        val jw = JpegWriter(out, w, h, 95, background = 0xFFFFFFFF.toInt())
        jw.writeRows(IntArray(w * h) { 0x00000000 }, 0, w, h); jw.finish()
        val px = pixels(decode(out.toByteArray()))
        assertTrue(px.all { (it and 0xFF) > 250 }) // transparent -> white
        val out2 = ByteArrayOutputStream()
        val g = JpegWriter(out2, w, h, 90, grayscale = true)
        g.writeRows(IntArray(w * h) { 0xFF808080.toInt() }, 0, w, h); g.finish()
        assertEquals(w, decode(out2.toByteArray()).width)
    }
}
