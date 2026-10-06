package com.localmediatools.codec

import com.localmediatools.codec.image.SoftImageDecoders
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SoftDecoderTest {
    private val dir: File = run {
        val url = javaClass.classLoader!!.getResource("softimages/img.qoi")
        if (url != null) File(url.toURI()).parentFile else File("app/src/test/resources/softimages")
    }

    private fun kindOf(name: String) = when (name.substringAfterLast('.')) {
        "tif" -> SoftImageDecoders.Kind.TIFF
        "ppm", "pgm" -> SoftImageDecoders.Kind.PNM
        "tga" -> SoftImageDecoders.Kind.TGA
        "qoi" -> SoftImageDecoders.Kind.QOI
        "psd" -> SoftImageDecoders.Kind.PSD
        else -> error(name)
    }

    @Test
    fun allFixturesDecodeExactly() {
        val files = dir.listFiles()!!.filter { !it.name.endsWith(".rgba") }.sortedBy { it.name }
        check(files.size > 10)
        for (f in files) {
            val expected = File(dir, f.name + ".rgba").readBytes()
            val w = java.nio.ByteBuffer.wrap(expected, 0, 8).int
            val h = java.nio.ByteBuffer.wrap(expected, 4, 4).int
            val data = f.readBytes()
            val kind = kindOf(f.name)
            assertEquals(f.name, w to h, SoftImageDecoders.size(kind, data))
            val got = IntArray(w * h)
            SoftImageDecoders.decode(kind, data) { y, count, argb, stride ->
                for (r in 0 until count) System.arraycopy(argb, r * stride, got, (y + r) * w, w)
            }
            for (i in 0 until w * h) {
                val o = 8 + i * 4
                val r = expected[o].toInt() and 0xFF; val g = expected[o + 1].toInt() and 0xFF
                val b = expected[o + 2].toInt() and 0xFF; val a = expected[o + 3].toInt() and 0xFF
                val c = got[i]
                val gr = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val gb = c and 0xFF; val ga = c ushr 24
                if (a == 0 && ga == 0) continue
                if (Math.abs(r - gr) > 1 || Math.abs(g - gg) > 1 || Math.abs(b - gb) > 1 || a != ga) {
                    fail("${f.name} pixel $i: expected ${listOf(r, g, b, a)} got ${listOf(gr, gg, gb, ga)}")
                }
            }
        }
    }
}
