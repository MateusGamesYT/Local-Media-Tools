package com.localmediatools.codec

import com.localmediatools.codec.gif.ColorHistogram
import com.localmediatools.codec.gif.Dithering
import com.localmediatools.codec.gif.GifAnimationWriter
import com.localmediatools.codec.gif.GifDecoder
import com.localmediatools.codec.gif.GifFormatException
import com.localmediatools.codec.gif.GifUnrepresentableException
import com.localmediatools.codec.gif.PaletteMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO

class GifCodecTest {

    private fun decodeAll(bytes: ByteArray): List<Pair<IntArray, Int>> {
        val d = GifDecoder(ByteArrayInputStream(bytes))
        val out = ArrayList<Pair<IntArray, Int>>()
        while (true) {
            val f = d.nextFrame() ?: break
            out.add(f.canvas.copyOf() to f.rawDelayCs)
        }
        return out
    }

    private fun norm(a: IntArray) = IntArray(a.size) { if ((a[it] ushr 24) == 0) 0 else a[it] }

    @Test
    fun animationRoundTripIsPixelExact() {
        val w = 50; val h = 40
        val rnd = Random(3)
        val colors = IntArray(200) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        val frames = ArrayList<IntArray>()
        var cur = IntArray(w * h) { colors[(it / 7) % colors.size] }
        frames.add(cur.copyOf())
        for (f in 0 until 8) {
            cur = cur.copyOf()
            // Small moving block plus a transparent hole in some frames.
            for (y in 5 until 15) for (x in f * 4 until f * 4 + 10) cur[y * w + x] = colors[(f * 13) % colors.size]
            if (f == 4) for (i in 0 until w * 3) cur[i] = 0 // opaque -> transparent needs disposal 2
            frames.add(cur.copyOf())
        }
        frames.add(frames.last().copyOf()) // identical frame: merged
        val out = ByteArrayOutputStream()
        val writer = GifAnimationWriter(out, w, h, null, 0)
        for (f in frames) writer.addFrame(f, 7)
        writer.finish()
        val decoded = decodeAll(out.toByteArray())
        assertEquals(frames.size - 1, decoded.size)
        for (i in decoded.indices) assertArrayEquals("frame $i", norm(frames[i]), norm(decoded[i].first))
        assertEquals(14, decoded.last().second) // merged delay
        // Java's decoder must also accept the file.
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(out.toByteArray()))
        assertEquals(decoded.size, reader.getNumImages(true))
    }

    @Test
    fun largeNoisyFrameExercisesLzwTableResets() {
        val w = 300; val h = 200
        val rnd = Random(9)
        val pal = IntArray(250) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        val frame = IntArray(w * h) { pal[rnd.nextInt(pal.size)] }
        val out = ByteArrayOutputStream()
        val writer = GifAnimationWriter(out, w, h, pal, null)
        writer.addFrame(frame, 5)
        writer.finish()
        val decoded = decodeAll(out.toByteArray())
        assertArrayEquals(frame, decoded[0].first)
        val img = ImageIO.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(frame.toList(), img.getRGB(0, 0, w, h, null, 0, w).toList())
    }

    @Test
    fun decodesJavaWrittenGifs() {
        val w = 33; val h = 21
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_BYTE_INDEXED)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, if ((x + y) % 3 == 0) 0xFFFF0000.toInt() else 0xFF0000FF.toInt())
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "gif", bos)
        val frames = decodeAll(bos.toByteArray())
        assertEquals(1, frames.size)
        assertArrayEquals(img.getRGB(0, 0, w, h, null, 0, w), frames[0].first)
    }

    @Test
    fun truncatedGifIsRejectedExplicitly() {
        val w = 64; val h = 64
        val rnd = Random(1)
        val frame = IntArray(w * h) { 0xFF000000.toInt() or (rnd.nextInt(64) * 0x030201) }
        val out = ByteArrayOutputStream()
        GifAnimationWriter(out, w, h, null, 0).apply { addFrame(frame, 4); finish() }
        val bytes = out.toByteArray()
        val cut = bytes.copyOf(bytes.size / 2)
        try {
            decodeAll(cut)
            fail("expected failure")
        } catch (e: GifFormatException) {
            assertTrue(e.message!!.contains("truncated"))
        }
        // Missing only the trailer is tolerated.
        assertEquals(1, decodeAll(bytes.copyOf(bytes.size - 1)).size)
    }

    @Test
    fun semiTransparentAndTooManyColoursAreRejected() {
        val out = ByteArrayOutputStream()
        val w = GifAnimationWriter(out, 2, 2, null, 0)
        try {
            w.addFrame(intArrayOf(0x80FF0000.toInt(), 0, 0, 0), 1); fail()
        } catch (e: GifUnrepresentableException) { /* expected */ }
        val big = IntArray(32 * 32) { 0xFF000000.toInt() or it * 7 } // 1024 colours
        val w2 = GifAnimationWriter(ByteArrayOutputStream(), 32, 32, null, 0)
        w2.addFrame(big, 1)
        try { w2.finish(); fail() } catch (e: GifUnrepresentableException) { /* expected */ }
    }

    @Test
    fun quantizerKeepsExactColoursAndApproximatesPhotos() {
        val few = IntArray(1000) { if (it % 2 == 0) 0xFF102030.toInt() else 0xFFA0B0C0.toInt() }
        val h1 = ColorHistogram(); h1.add(few)
        assertEquals(setOf(0xFF102030.toInt(), 0xFFA0B0C0.toInt()), h1.palette(32).toSet())

        val w = 128; val h = 96
        val photo = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            0xFF000000.toInt() or ((x * 2) shl 16) or ((y * 2) shl 8) or ((x + y) and 0xFF)
        }
        val hist = ColorHistogram(); hist.add(photo)
        for (n in listOf(32, 64, 256)) {
            val pal = hist.palette(n)
            assertTrue(pal.size <= n)
            for (d in Dithering.entries) {
                val dst = IntArray(photo.size)
                val mse = PaletteMapper(pal).map(photo, dst, w, h, d)
                val psnr = 10 * Math.log10(255.0 * 255 / mse)
                assertTrue("n=$n $d psnr=$psnr", psnr > (if (n >= 256) 30.0 else 22.0))
                assertTrue(dst.all { c -> pal.contains(c) })
            }
        }
    }
}
