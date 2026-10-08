package com.localmediatools.print

import java.nio.ByteBuffer

/** A decoded raster page: header values and ARGB pixels. */
class DecodedPage(val width: Int, val height: Int, val dpi: Int, val color: Boolean, val sizeName: String, val mediaType: String,
                  val widthPt: Int, val heightPt: Int, val quality: Int, val pixels: IntArray) {
    fun at(x: Int, y: Int) = pixels[y * width + x]
}

/** Reads PWG ("RaS2") and Apple ("UNIRAST") raster streams, written independently of the app's writer for checking it. */
object RasterReader {
    fun read(data: ByteArray): List<DecodedPage> {
        val b = ByteBuffer.wrap(data)
        val magic = String(data, 0, 4, Charsets.US_ASCII)
        val pages = ArrayList<DecodedPage>()
        if (magic == "RaS2") {
            b.position(4)
            while (b.remaining() >= 1796) {
                val h = ByteArray(1796).also { b.get(it) }
                val hb = ByteBuffer.wrap(h)
                fun str(at: Int) = String(h, at, 64, Charsets.US_ASCII).trimEnd('\u0000')
                require(str(0) == "PwgRaster") { "Not a PWG page header" }
                val w = hb.getInt(372); val ht = hb.getInt(376); val bpp = hb.getInt(388)
                require(hb.getInt(384) == 8 && (bpp == 24 || bpp == 8)) { "Unexpected depth $bpp" }
                require(hb.getInt(392) == w * bpp / 8) { "Bytes per line" }
                require(hb.getInt(400) == if (bpp == 24) 19 else 18) { "Colour space" }
                pages.add(DecodedPage(w, ht, hb.getInt(276), bpp == 24, str(1732), str(128), hb.getInt(352), hb.getInt(356), hb.getInt(484), pixels(b, w, ht, bpp / 8)))
            }
        } else {
            require(String(data, 0, 8, Charsets.US_ASCII) == "UNIRAST\u0000") { "Unknown raster: $magic" }
            b.position(8)
            val count = b.int
            repeat(count) {
                val h = ByteArray(32).also { b.get(it) }
                val hb = ByteBuffer.wrap(h)
                val bpp = h[0].toInt() and 0xFF
                val w = hb.getInt(12); val ht = hb.getInt(16); val dpi = hb.getInt(20)
                require((h[1].toInt() == 1 && bpp == 24) || (h[1].toInt() == 0 && bpp == 8)) { "Colour space" }
                pages.add(DecodedPage(w, ht, dpi, bpp == 24, "", "", 0, 0, h[3].toInt(), pixels(b, w, ht, bpp / 8)))
            }
        }
        require(!b.hasRemaining()) { "${b.remaining()} bytes after the last page" }
        return pages
    }

    private fun pixels(b: ByteBuffer, w: Int, h: Int, bpp: Int): IntArray {
        val out = IntArray(w * h)
        var y = 0
        val line = ByteArray(w * bpp)
        while (y < h) {
            val repeat = (b.get().toInt() and 0xFF) + 1
            var x = 0
            while (x < w) {
                val c = b.get().toInt() and 0xFF
                when {
                    c == 128 -> { while (x < w) { for (k in 0 until bpp) line[x * bpp + k] = -1; x++ } }
                    c < 128 -> {
                        val px = ByteArray(bpp).also { b.get(it) }
                        repeat(c + 1) { require(x < w) { "Run past the end of a row" }; System.arraycopy(px, 0, line, x * bpp, bpp); x++ }
                    }
                    else -> repeat(257 - c) { require(x < w) { "Literal past the end of a row" }; b.get(line, x * bpp, bpp); x++ }
                }
            }
            repeat(repeat) {
                require(y < h) { "Rows past the end of the page" }
                for (i in 0 until w) {
                    out[y * w + i] = if (bpp == 3) (0xFF shl 24) or ((line[i * 3].toInt() and 0xFF) shl 16) or ((line[i * 3 + 1].toInt() and 0xFF) shl 8) or (line[i * 3 + 2].toInt() and 0xFF)
                    else (line[i].toInt() and 0xFF).let { g -> (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                }
                y++
            }
        }
        return out
    }
}
