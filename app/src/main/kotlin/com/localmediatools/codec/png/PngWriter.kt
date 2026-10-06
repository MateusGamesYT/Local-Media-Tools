package com.localmediatools.codec.png

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

enum class PngColorMode(val colorType: Int) { GRAY(0), RGB(2), PALETTE(3), GRAY_ALPHA(4), RGBA(6) }

/**
 * Streaming PNG encoder. Rows are supplied top to bottom as packed, non-premultiplied ARGB and are
 * filtered and compressed immediately, so memory use is independent of the image height. This is
 * what makes very large merges, stitches and PDF renders possible without one giant bitmap.
 *
 * In [PngColorMode.PALETTE] mode every pixel must exist in [palette] (exact match) – the encoder
 * never approximates colours, which keeps the output pixel-identical to its input.
 */
class PngWriter(
    private val out: OutputStream,
    val width: Int,
    val height: Int,
    val mode: PngColorMode,
    palette: IntArray? = null,
    compressionLevel: Int = 6,
    writeSrgb: Boolean = true,
) {
    private val crc = CRC32()
    private val deflater = Deflater(compressionLevel.coerceIn(0, 9))
    private val bitDepth: Int
    private val bytesPerPixel: Int // filter unit (>= 1)
    private val rowBytes: Int
    private var prev: ByteArray
    private var cur: ByteArray
    private val filtered = Array(5) { ByteArray(0) }
    private val idat = ByteArray(1 shl 16)
    private var idatLen = 0
    private val deflateBuf = ByteArray(1 shl 16)
    private var rowsWritten = 0
    private var finished = false
    private val paletteIndex: IntIntMap?
    private val adaptiveFilters: Boolean

    init {
        require(width > 0 && height > 0) { "Image has no pixels" }
        val pal = palette
        if (mode == PngColorMode.PALETTE) {
            requireNotNull(pal) { "Palette mode needs a palette" }
            require(pal.size in 1..256) { "Palette must have 1..256 entries" }
            bitDepth = when {
                pal.size <= 2 -> 1
                pal.size <= 4 -> 2
                pal.size <= 16 -> 4
                else -> 8
            }
            paletteIndex = IntIntMap(pal.size * 2).also { m -> pal.forEachIndexed { i, c -> m.put(c, i) } }
        } else {
            bitDepth = 8
            paletteIndex = null
        }
        val channels = when (mode) {
            PngColorMode.GRAY, PngColorMode.PALETTE -> 1
            PngColorMode.GRAY_ALPHA -> 2
            PngColorMode.RGB -> 3
            PngColorMode.RGBA -> 4
        }
        val bitsPerRow = width.toLong() * channels * bitDepth
        val rb = (bitsPerRow + 7) / 8
        require(rb < Int.MAX_VALUE - 16) { "Image row is too wide for PNG" }
        rowBytes = rb.toInt()
        bytesPerPixel = maxOf(1, channels * bitDepth / 8)
        prev = ByteArray(rowBytes)
        cur = ByteArray(rowBytes)
        // PNG spec recommendation: no filtering for palette / sub-byte images, adaptive otherwise.
        adaptiveFilters = mode != PngColorMode.PALETTE && bitDepth == 8
        if (adaptiveFilters) for (i in 0 until 5) filtered[i] = ByteArray(rowBytes + 1)
        else filtered[0] = ByteArray(rowBytes + 1)

        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width); putInt(ihdr, 4, height)
        ihdr[8] = bitDepth.toByte(); ihdr[9] = mode.colorType.toByte()
        ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
        writeChunk("IHDR", ihdr, ihdr.size)
        if (writeSrgb) writeChunk("sRGB", byteArrayOf(0), 1)
        if (mode == PngColorMode.PALETTE && pal != null) {
            val plte = ByteArray(pal.size * 3)
            var lastTransparent = -1
            for (i in pal.indices) {
                val c = pal[i]
                plte[i * 3] = (c ushr 16).toByte(); plte[i * 3 + 1] = (c ushr 8).toByte(); plte[i * 3 + 2] = c.toByte()
                if ((c ushr 24) != 0xFF) lastTransparent = i
            }
            writeChunk("PLTE", plte, plte.size)
            if (lastTransparent >= 0) {
                val trns = ByteArray(lastTransparent + 1) { (pal[it] ushr 24).toByte() }
                writeChunk("tRNS", trns, trns.size)
            }
        }
    }

    val rowsRemaining: Int get() = height - rowsWritten

    /** Writes [rowCount] rows of non-premultiplied ARGB pixels. */
    fun writeRows(argb: IntArray, offset: Int, stride: Int, rowCount: Int) {
        check(!finished) { "PNG already finished" }
        require(rowsWritten + rowCount <= height) { "Too many rows for PNG" }
        for (r in 0 until rowCount) {
            packRow(argb, offset + r * stride)
            filterAndDeflate()
            val t = prev; prev = cur; cur = t
            rowsWritten++
        }
    }

    /** Writes rows of palette indices directly (PALETTE mode only). */
    fun writeIndexedRows(indices: ByteArray, offset: Int, stride: Int, rowCount: Int) {
        check(mode == PngColorMode.PALETTE)
        require(rowsWritten + rowCount <= height)
        for (r in 0 until rowCount) {
            packIndices(indices, offset + r * stride)
            filterAndDeflate()
            val t = prev; prev = cur; cur = t
            rowsWritten++
        }
    }

    fun finish() {
        if (finished) return
        check(rowsWritten == height) { "PNG incomplete: $rowsWritten of $height rows" }
        deflater.finish()
        while (!deflater.finished()) {
            val n = deflater.deflate(deflateBuf)
            appendIdat(deflateBuf, n)
        }
        flushIdat()
        deflater.end()
        writeChunk("IEND", ByteArray(0), 0)
        out.flush()
        finished = true
    }

    /** Releases native compressor memory if encoding is abandoned. */
    fun abort() {
        if (!finished) deflater.end()
        finished = true
    }

    private fun packRow(argb: IntArray, start: Int) {
        val row = cur
        when (mode) {
            PngColorMode.RGBA -> {
                var o = 0
                for (x in 0 until width) {
                    val c = argb[start + x]
                    row[o] = (c ushr 16).toByte(); row[o + 1] = (c ushr 8).toByte()
                    row[o + 2] = c.toByte(); row[o + 3] = (c ushr 24).toByte()
                    o += 4
                }
            }
            PngColorMode.RGB -> {
                var o = 0
                for (x in 0 until width) {
                    val c = argb[start + x]
                    row[o] = (c ushr 16).toByte(); row[o + 1] = (c ushr 8).toByte(); row[o + 2] = c.toByte()
                    o += 3
                }
            }
            PngColorMode.GRAY -> for (x in 0 until width) row[x] = argb[start + x].toByte() // blue == gray
            PngColorMode.GRAY_ALPHA -> {
                var o = 0
                for (x in 0 until width) {
                    val c = argb[start + x]
                    row[o] = c.toByte(); row[o + 1] = (c ushr 24).toByte()
                    o += 2
                }
            }
            PngColorMode.PALETTE -> {
                val map = paletteIndex!!
                if (bitDepth == 8) {
                    for (x in 0 until width) row[x] = lookup(map, argb[start + x]).toByte()
                } else {
                    java.util.Arrays.fill(row, 0.toByte())
                    val perByte = 8 / bitDepth
                    for (x in 0 until width) {
                        val idx = lookup(map, argb[start + x])
                        val shift = 8 - bitDepth * (x % perByte + 1)
                        val bi = x / perByte
                        row[bi] = (row[bi].toInt() or (idx shl shift)).toByte()
                    }
                }
            }
        }
    }

    private fun packIndices(indices: ByteArray, start: Int) {
        val row = cur
        if (bitDepth == 8) {
            System.arraycopy(indices, start, row, 0, width)
        } else {
            java.util.Arrays.fill(row, 0.toByte())
            val perByte = 8 / bitDepth
            for (x in 0 until width) {
                val idx = indices[start + x].toInt() and 0xFF
                val shift = 8 - bitDepth * (x % perByte + 1)
                val bi = x / perByte
                row[bi] = (row[bi].toInt() or (idx shl shift)).toByte()
            }
        }
    }

    private fun lookup(map: IntIntMap, color: Int): Int {
        val i = map.get(color)
        if (i < 0) throw IllegalArgumentException("Pixel colour not present in PNG palette")
        return i
    }

    private fun filterAndDeflate() {
        val row = cur
        val up = prev
        val bpp = bytesPerPixel
        val n = rowBytes
        val chosen: ByteArray
        if (!adaptiveFilters) {
            chosen = filtered[0]
            chosen[0] = 0
            System.arraycopy(row, 0, chosen, 1, n)
        } else {
            val f0 = filtered[0]; val f1 = filtered[1]; val f2 = filtered[2]; val f3 = filtered[3]; val f4 = filtered[4]
            f0[0] = 0; f1[0] = 1; f2[0] = 2; f3[0] = 3; f4[0] = 4
            var s0 = 0L; var s1 = 0L; var s2 = 0L; var s3 = 0L; var s4 = 0L
            for (i in 0 until n) {
                val x = row[i].toInt() and 0xFF
                val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val b = up[i].toInt() and 0xFF
                val c = if (i >= bpp) up[i - bpp].toInt() and 0xFF else 0
                val v0 = x
                val v1 = (x - a) and 0xFF
                val v2 = (x - b) and 0xFF
                val v3 = (x - ((a + b) ushr 1)) and 0xFF
                val p = a + b - c
                val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                val v4 = (x - pred) and 0xFF
                f0[i + 1] = v0.toByte(); f1[i + 1] = v1.toByte(); f2[i + 1] = v2.toByte()
                f3[i + 1] = v3.toByte(); f4[i + 1] = v4.toByte()
                s0 += if (v0 < 128) v0 else 256 - v0
                s1 += if (v1 < 128) v1 else 256 - v1
                s2 += if (v2 < 128) v2 else 256 - v2
                s3 += if (v3 < 128) v3 else 256 - v3
                s4 += if (v4 < 128) v4 else 256 - v4
            }
            var best = 0; var bestSum = s0
            if (s1 < bestSum) { best = 1; bestSum = s1 }
            if (s2 < bestSum) { best = 2; bestSum = s2 }
            if (s3 < bestSum) { best = 3; bestSum = s3 }
            if (s4 < bestSum) { best = 4 }
            chosen = filtered[best]
        }
        deflater.setInput(chosen, 0, n + 1)
        while (!deflater.needsInput()) {
            val k = deflater.deflate(deflateBuf)
            if (k <= 0) break
            appendIdat(deflateBuf, k)
        }
    }

    private fun appendIdat(src: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val k = minOf(len - off, idat.size - idatLen)
            System.arraycopy(src, off, idat, idatLen, k)
            idatLen += k; off += k
            if (idatLen == idat.size) flushIdat()
        }
    }

    private fun flushIdat() {
        if (idatLen > 0) {
            writeChunk("IDAT", idat, idatLen)
            idatLen = 0
        }
    }

    private fun writeChunk(type: String, data: ByteArray, len: Int) {
        val hdr = ByteArray(8)
        putInt(hdr, 0, len)
        for (i in 0 until 4) hdr[4 + i] = type[i].code.toByte()
        crc.reset()
        crc.update(hdr, 4, 4)
        crc.update(data, 0, len)
        out.write(hdr)
        out.write(data, 0, len)
        val c = ByteArray(4)
        putInt(c, 0, crc.value.toInt())
        out.write(c)
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        private fun putInt(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
        }
    }
}

/**
 * Collects colour statistics over streamed rows to pick the smallest exact PNG representation.
 */
class PngColorAnalyzer(private val maxPaletteColors: Int = 256) {
    var hasAlpha = false; private set
    var isGray = true; private set
    private val colors = IntIntMap(512)
    var tooManyColors = false; private set

    fun feed(argb: IntArray, offset: Int, stride: Int, width: Int, rows: Int) {
        for (r in 0 until rows) {
            val base = offset + r * stride
            for (x in 0 until width) {
                val c = argb[base + x]
                if (!hasAlpha && (c ushr 24) != 0xFF) hasAlpha = true
                if (isGray) {
                    val rr = (c ushr 16) and 0xFF; val g = (c ushr 8) and 0xFF; val b = c and 0xFF
                    if (rr != g || g != b) isGray = false
                }
                if (!tooManyColors && colors.get(c) < 0) {
                    if (colors.size >= maxPaletteColors) tooManyColors = true else colors.put(c, colors.size)
                }
            }
        }
    }

    /** Returns the chosen mode and, for palette mode, the palette (translucent entries first). */
    fun choose(): Pair<PngColorMode, IntArray?> {
        val n = colors.size
        if (!tooManyColors && n <= 16) return PngColorMode.PALETTE to palette()
        if (isGray && !hasAlpha) return PngColorMode.GRAY to null
        if (!tooManyColors) return PngColorMode.PALETTE to palette()
        if (isGray) return PngColorMode.GRAY_ALPHA to null
        return (if (hasAlpha) PngColorMode.RGBA else PngColorMode.RGB) to null
    }

    private fun palette(): IntArray {
        val all = colors.keys()
        val translucent = all.filter { (it ushr 24) != 0xFF }
        val opaque = all.filter { (it ushr 24) == 0xFF }
        return (translucent + opaque).toIntArray()
    }
}

/** Small open-addressing Int -> Int map (non-negative values) used for palette lookups. */
class IntIntMap(initialCapacity: Int) {
    private var keys: IntArray
    private var vals: IntArray
    private var used: BooleanArray
    var size = 0; private set

    init {
        var cap = 16
        while (cap < initialCapacity * 2) cap = cap shl 1
        keys = IntArray(cap); vals = IntArray(cap); used = BooleanArray(cap)
    }

    private fun slot(k: Int, mask: Int): Int {
        var h = k * -0x61c88647
        h = h xor (h ushr 16)
        return h and mask
    }

    fun get(k: Int): Int {
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (used[i]) {
            if (keys[i] == k) return vals[i]
            i = (i + 1) and mask
        }
        return -1
    }

    fun put(k: Int, v: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (used[i]) {
            if (keys[i] == k) { vals[i] = v; return }
            i = (i + 1) and mask
        }
        used[i] = true; keys[i] = k; vals[i] = v; size++
    }

    /** Keys ordered by their stored value (insertion index for palette use). */
    fun keys(): List<Int> {
        val list = ArrayList<Pair<Int, Int>>(size)
        for (i in keys.indices) if (used[i]) list.add(keys[i] to vals[i])
        list.sortBy { it.second }
        return list.map { it.first }
    }

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        keys = IntArray(ok.size * 2); vals = IntArray(ok.size * 2); used = BooleanArray(ok.size * 2)
        size = 0
        for (i in ok.indices) if (ou[i]) put(ok[i], ov[i])
    }
}
