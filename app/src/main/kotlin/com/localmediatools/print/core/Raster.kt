package com.localmediatools.print.core

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Page-image formats printers accept without a driver. */
enum class RasterFormat(val mime: String) {
    /** PWG Raster (PWG 5102.4): IPP Everywhere and Mopria printers. */
    PWG("image/pwg-raster"),
    /** Apple Raster ("URF"): AirPrint printers. */
    URF("image/urf"),
}

/** How one sheet side is described in the raster stream. */
class RasterPage(
    val width: Int,
    val height: Int,
    val dpi: Int,
    /** sRGB 24-bit when true, sGray 8-bit otherwise. */
    val color: Boolean,
    /** Paper size in points (1/72 in). */
    val widthPt: Int,
    val heightPt: Int,
    /** PWG media name, e.g. iso_a4_210x297mm. */
    val sizeName: String = "",
    /** PWG media type, e.g. stationery or photographic-glossy. */
    val mediaType: String = "",
    /** 3 draft, 4 normal, 5 high (0: printer default). */
    val quality: Int = 0,
    /** "photo", "text" or "" (printer decides). */
    val contentOptimize: String = "",
    val duplex: Boolean = false,
    /** Two-sided along the short edge. */
    val tumble: Boolean = false,
    /** -1 when this (back) side was mirrored left-right for the printer. */
    val crossFeedTransform: Int = 1,
    /** -1 when this (back) side was turned upside down for the printer. */
    val feedTransform: Int = 1,
) {
    val bytesPerPixel get() = if (color) 3 else 1
    val bytesPerLine get() = width * bytesPerPixel
}

/**
 * Writes PWG or Apple raster: a file header, then for each page a header and its rows, each row
 * compressed with the formats' shared scheme (identical consecutive rows counted once; runs of
 * equal pixels and literal pixels within a row, PackBits style).
 */
class RasterWriter(private val out: OutputStream, val format: RasterFormat, private val totalPages: Int) {
    private var page: RasterPage? = null
    private var pending: ByteArray? = null
    private var pendingRepeat = 0
    private var row: ByteArray = ByteArray(0)
    private var rowsWritten = 0
    private var pagesWritten = 0
    private var packed = ByteArray(0)

    init {
        when (format) {
            RasterFormat.PWG -> out.write("RaS2".toByteArray(Charsets.US_ASCII))
            RasterFormat.URF -> {
                out.write("UNIRAST".toByteArray(Charsets.US_ASCII)); out.write(0)
                out.write(ByteBuffer.allocate(4).putInt(totalPages).array())
            }
        }
    }

    fun startPage(p: RasterPage) {
        check(page == null) { "Previous page not finished" }
        page = p; rowsWritten = 0; pending = null; pendingRepeat = 0
        row = ByteArray(p.bytesPerLine)
        packed = ByteArray(p.bytesPerLine + p.bytesPerLine / 128 + 8 + p.width)
        out.write(if (format == RasterFormat.PWG) pwgHeader(p) else urfHeader(p))
    }

    /** One row of [page] width, ARGB pixels from [px] starting at [offset]. Transparency is shown on white. */
    fun writeRow(px: IntArray, offset: Int = 0) {
        val p = page ?: error("No page started")
        check(rowsWritten < p.height) { "Too many rows" }
        val r = row
        if (p.color) {
            var o = 0
            for (i in offset until offset + p.width) {
                val c = px[i]; val a = c ushr 24
                if (a == 255) { r[o] = (c shr 16).toByte(); r[o + 1] = (c shr 8).toByte(); r[o + 2] = c.toByte() }
                else { r[o] = onWhite((c shr 16) and 255, a); r[o + 1] = onWhite((c shr 8) and 255, a); r[o + 2] = onWhite(c and 255, a) }
                o += 3
            }
        } else {
            var o = 0
            for (i in offset until offset + p.width) {
                val c = px[i]; val a = c ushr 24
                // Rec. 601 luma, as printers convert sRGB to gray.
                val y = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114 + 500) / 1000
                r[o++] = if (a == 255) y.toByte() else onWhite(y, a)
            }
        }
        rowsWritten++
        val prev = pending
        if (prev != null && pendingRepeat < 255 && prev.contentEquals(r)) { pendingRepeat++; return }
        flushPending()
        pending = r.copyOf(); pendingRepeat = 0
    }

    /** Rows of white up to the page height, then the page is done. */
    fun endPage() {
        val p = page ?: error("No page started")
        if (rowsWritten < p.height) {
            val white = IntArray(p.width) { -1 }
            while (rowsWritten < p.height) writeRow(white)
        }
        flushPending()
        page = null; pagesWritten++
    }

    fun finish() {
        check(page == null) { "Page not finished" }
        check(format != RasterFormat.URF || pagesWritten == totalPages) { "URF promised $totalPages pages, wrote $pagesWritten" }
        out.flush()
    }

    private fun onWhite(v: Int, a: Int): Byte = ((v * a + 255 * (255 - a) + 127) / 255).toByte()

    private fun flushPending() {
        val line = pending ?: return
        val bpp = page!!.bytesPerPixel
        val n = line.size / bpp
        var o = 0
        val buf = packed
        buf[o++] = pendingRepeat.toByte()
        var i = 0
        while (i < n) {
            var run = 1
            while (i + run < n && run < 128 && same(line, i, i + run, bpp)) run++
            if (run > 1) {
                buf[o++] = (run - 1).toByte()
                System.arraycopy(line, i * bpp, buf, o, bpp); o += bpp
                i += run
            } else {
                val start = i
                var count = 0
                while (i < n && count < 128) {
                    if (i + 1 < n && same(line, i, i + 1, bpp)) break
                    i++; count++
                }
                buf[o++] = if (count == 1) 0 else (257 - count).toByte()
                System.arraycopy(line, start * bpp, buf, o, count * bpp); o += count * bpp
            }
        }
        out.write(buf, 0, o)
        pending = null; pendingRepeat = 0
    }

    private fun same(l: ByteArray, a: Int, b: Int, bpp: Int): Boolean {
        val x = a * bpp; val y = b * bpp
        for (k in 0 until bpp) if (l[x + k] != l[y + k]) return false
        return true
    }

    private fun pwgHeader(p: RasterPage): ByteArray {
        val h = ByteBuffer.allocate(1796).order(ByteOrder.BIG_ENDIAN)
        fun str(at: Int, s: String) { val b = s.toByteArray(Charsets.US_ASCII); h.position(at); h.put(b, 0, minOf(b.size, 63)) }
        fun u32(at: Int, v: Int) { h.putInt(at, v) }
        str(0, "PwgRaster")
        str(128, p.mediaType)
        str(192, p.contentOptimize)
        u32(272, if (p.duplex) 1 else 0)
        u32(276, p.dpi); u32(280, p.dpi)
        u32(340, 1)                         // NumCopies (copies are a job setting)
        u32(352, p.widthPt); u32(356, p.heightPt)
        u32(368, if (p.tumble) 1 else 0)
        u32(372, p.width); u32(376, p.height)
        u32(384, 8)                         // bits per colour
        u32(388, if (p.color) 24 else 8)
        u32(392, p.bytesPerLine)
        u32(396, 0)                         // chunky pixels
        u32(400, if (p.color) 19 else 18)   // sRGB / sGray
        u32(420, if (p.color) 3 else 1)
        u32(452, totalPages)
        u32(456, p.crossFeedTransform); u32(460, p.feedTransform)
        u32(464, 0); u32(468, 0); u32(472, p.width); u32(476, p.height)
        u32(480, 0xFFFFFF)                  // alternate primary: white
        u32(484, p.quality)
        str(1732, p.sizeName)
        return h.array()
    }

    private fun urfHeader(p: RasterPage): ByteArray {
        val h = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        h.put(0, (if (p.color) 24 else 8).toByte())
        h.put(1, (if (p.color) 1 else 0).toByte())                     // sRGB / sGray
        h.put(2, (if (!p.duplex) 1 else if (p.tumble) 2 else 3).toByte())
        h.put(3, p.quality.toByte())
        h.putInt(12, p.width); h.putInt(16, p.height); h.putInt(20, p.dpi)
        return h.array()
    }
}
