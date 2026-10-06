package com.localmediatools.codec.jpeg

import java.io.OutputStream

/**
 * Streaming baseline JPEG encoder (JFIF, Huffman, 8-bit). Rows are pushed top to bottom and encoded
 * one MCU row at a time, so images far larger than available memory can be written.
 *
 * Used as the fallback when an image is too large for the platform encoder (which needs the whole
 * bitmap in memory). Quality scaling matches the IJG convention used by libjpeg.
 */
class JpegWriter(
    private val out: OutputStream,
    val width: Int,
    val height: Int,
    quality: Int,
    val subsampling420: Boolean = quality < 90,
    val grayscale: Boolean = false,
    private val background: Int = 0xFFFFFFFF.toInt(),
) {
    private val mcuW: Int
    private val mcuH: Int
    private val paddedW: Int
    private val yBuf: FloatArray
    private val cbBuf: FloatArray
    private val crBuf: FloatArray
    private var bufferedRows = 0
    private var rowsWritten = 0
    private var finished = false
    private val qLuma = FloatArray(64)
    private val qChroma = FloatArray(64)
    private val lumaQuantTable = IntArray(64)
    private val chromaQuantTable = IntArray(64)
    private val dcLuma = HuffTable(DC_LUMA_BITS, DC_VALUES)
    private val acLuma = HuffTable(AC_LUMA_BITS, AC_LUMA_VALUES)
    private val dcChroma = HuffTable(DC_CHROMA_BITS, DC_VALUES)
    private val acChroma = HuffTable(AC_CHROMA_BITS, AC_CHROMA_VALUES)
    private val bits = BitWriter(out)
    private var prevDcY = 0
    private var prevDcCb = 0
    private var prevDcCr = 0
    private val block = FloatArray(64)
    private val coef = IntArray(64)

    init {
        require(width in 1..65535 && height in 1..65535) { "JPEG supports at most 65535 x 65535 pixels" }
        val q = quality.coerceIn(1, 100)
        val scale = if (q < 50) 5000 / q else 200 - q * 2
        for (i in 0 until 64) {
            lumaQuantTable[i] = ((STD_LUMA_Q[i] * scale + 50) / 100).coerceIn(1, 255)
            chromaQuantTable[i] = ((STD_CHROMA_Q[i] * scale + 50) / 100).coerceIn(1, 255)
        }
        for (row in 0 until 8) for (col in 0 until 8) {
            val i = row * 8 + col
            val s = AAN[row] * AAN[col] * 8.0
            qLuma[i] = (1.0 / (lumaQuantTable[i] * s)).toFloat()
            qChroma[i] = (1.0 / (chromaQuantTable[i] * s)).toFloat()
        }
        val sub = subsampling420 && !grayscale
        mcuW = if (sub) 16 else 8
        mcuH = if (sub) 16 else 8
        paddedW = (width + mcuW - 1) / mcuW * mcuW
        yBuf = FloatArray(paddedW * mcuH)
        cbBuf = if (grayscale) FloatArray(0) else FloatArray(paddedW * mcuH)
        crBuf = if (grayscale) FloatArray(0) else FloatArray(paddedW * mcuH)
        writeHeaders()
    }

    /** Writes [rowCount] rows of ARGB pixels (alpha is composited over the background colour). */
    fun writeRows(argb: IntArray, offset: Int, stride: Int, rowCount: Int) {
        check(!finished)
        require(rowsWritten + rowCount <= height) { "Too many rows for JPEG" }
        val bgR = (background ushr 16) and 0xFF; val bgG = (background ushr 8) and 0xFF; val bgB = background and 0xFF
        for (r in 0 until rowCount) {
            val base = offset + r * stride
            val o = bufferedRows * paddedW
            for (x in 0 until paddedW) {
                val c = argb[base + minOf(x, width - 1)]
                var rr = (c ushr 16) and 0xFF; var gg = (c ushr 8) and 0xFF; var bb = c and 0xFF
                val a = c ushr 24
                if (a != 0xFF) {
                    rr = (rr * a + bgR * (255 - a) + 127) / 255
                    gg = (gg * a + bgG * (255 - a) + 127) / 255
                    bb = (bb * a + bgB * (255 - a) + 127) / 255
                }
                yBuf[o + x] = 0.299f * rr + 0.587f * gg + 0.114f * bb - 128f
                // Chroma is kept level-shifted (the +128 offset and the -128 shift cancel out).
                if (!grayscale) {
                    cbBuf[o + x] = -0.168736f * rr - 0.331264f * gg + 0.5f * bb
                    crBuf[o + x] = 0.5f * rr - 0.418688f * gg - 0.081312f * bb
                }
            }
            bufferedRows++
            rowsWritten++
            if (bufferedRows == mcuH) encodeMcuRow()
        }
    }

    fun finish() {
        if (finished) return
        check(rowsWritten == height) { "JPEG incomplete: $rowsWritten of $height rows" }
        if (bufferedRows > 0) {
            // Replicate the last row to fill the final MCU row.
            val last = (bufferedRows - 1) * paddedW
            for (r in bufferedRows until mcuH) {
                System.arraycopy(yBuf, last, yBuf, r * paddedW, paddedW)
                if (!grayscale) {
                    System.arraycopy(cbBuf, last, cbBuf, r * paddedW, paddedW)
                    System.arraycopy(crBuf, last, crBuf, r * paddedW, paddedW)
                }
            }
            bufferedRows = mcuH
            encodeMcuRow()
        }
        bits.flush()
        out.write(0xFF); out.write(0xD9)
        out.flush()
        finished = true
    }

    private fun encodeMcuRow() {
        val sub = mcuW == 16
        var mx = 0
        while (mx < paddedW) {
            if (sub) {
                for (by in 0 until 2) for (bx in 0 until 2) {
                    loadBlock(yBuf, mx + bx * 8, by * 8, 1)
                    prevDcY = encodeBlock(qLuma, prevDcY, dcLuma, acLuma)
                }
                loadBlock(cbBuf, mx, 0, 2)
                prevDcCb = encodeBlock(qChroma, prevDcCb, dcChroma, acChroma)
                loadBlock(crBuf, mx, 0, 2)
                prevDcCr = encodeBlock(qChroma, prevDcCr, dcChroma, acChroma)
            } else {
                loadBlock(yBuf, mx, 0, 1)
                prevDcY = encodeBlock(qLuma, prevDcY, dcLuma, acLuma)
                if (!grayscale) {
                    loadBlock(cbBuf, mx, 0, 1)
                    prevDcCb = encodeBlock(qChroma, prevDcCb, dcChroma, acChroma)
                    loadBlock(crBuf, mx, 0, 1)
                    prevDcCr = encodeBlock(qChroma, prevDcCr, dcChroma, acChroma)
                }
            }
            mx += mcuW
        }
        bufferedRows = 0
    }

    /** Loads an 8x8 block; factor 2 averages 2x2 neighbourhoods (chroma subsampling). */
    private fun loadBlock(src: FloatArray, x0: Int, y0: Int, factor: Int) {
        if (factor == 1) {
            for (y in 0 until 8) {
                val o = (y0 + y) * paddedW + x0
                for (x in 0 until 8) block[y * 8 + x] = src[o + x]
            }
        } else {
            for (y in 0 until 8) {
                val o1 = (y0 + y * 2) * paddedW + x0
                val o2 = o1 + paddedW
                for (x in 0 until 8) {
                    val sx = x * 2
                    block[y * 8 + x] = (src[o1 + sx] + src[o1 + sx + 1] + src[o2 + sx] + src[o2 + sx + 1]) * 0.25f
                }
            }
        }
    }

    private fun encodeBlock(q: FloatArray, prevDc: Int, dc: HuffTable, ac: HuffTable): Int {
        fdct(block)
        for (i in 0 until 64) {
            val v = block[ZIGZAG[i]] * q[ZIGZAG[i]]
            coef[i] = if (v >= 0) (v + 0.5f).toInt() else (v - 0.5f).toInt()
        }
        val dcVal = coef[0]
        val diff = dcVal - prevDc
        writeCoded(dc, 0, diff)
        var run = 0
        for (k in 1 until 64) {
            val v = coef[k]
            if (v == 0) {
                run++
            } else {
                while (run > 15) {
                    bits.write(ac.code[0xF0], ac.size[0xF0])
                    run -= 16
                }
                writeCoded(ac, run, v)
                run = 0
            }
        }
        if (run > 0) bits.write(ac.code[0x00], ac.size[0x00])
        return dcVal
    }

    private fun writeCoded(table: HuffTable, run: Int, value: Int) {
        var temp = value
        var temp2 = value
        if (temp < 0) {
            temp = -temp
            temp2 = value - 1
        }
        var nbits = 0
        while (temp != 0) { nbits++; temp = temp shr 1 }
        val symbol = (run shl 4) or nbits
        bits.write(table.code[symbol], table.size[symbol])
        if (nbits > 0) bits.write(temp2 and ((1 shl nbits) - 1), nbits)
    }

    private fun writeHeaders() {
        val o = out
        o.write(0xFF); o.write(0xD8)
        // APP0 JFIF
        o.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(),
            'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        // DQT
        val tables = if (grayscale) 1 else 2
        o.write(0xFF); o.write(0xDB)
        val dqtLen = 2 + tables * 65
        o.write(dqtLen shr 8); o.write(dqtLen and 0xFF)
        o.write(0)
        for (i in 0 until 64) o.write(lumaQuantTable[ZIGZAG[i]])
        if (!grayscale) {
            o.write(1)
            for (i in 0 until 64) o.write(chromaQuantTable[ZIGZAG[i]])
        }
        // SOF0
        val comps = if (grayscale) 1 else 3
        o.write(0xFF); o.write(0xC0)
        val sofLen = 8 + comps * 3
        o.write(sofLen shr 8); o.write(sofLen and 0xFF)
        o.write(8)
        o.write(height shr 8); o.write(height and 0xFF)
        o.write(width shr 8); o.write(width and 0xFF)
        o.write(comps)
        val sub = mcuW == 16
        o.write(1); o.write(if (sub) 0x22 else 0x11); o.write(0)
        if (!grayscale) {
            o.write(2); o.write(0x11); o.write(1)
            o.write(3); o.write(0x11); o.write(1)
        }
        // DHT
        writeDht(0x00, DC_LUMA_BITS, DC_VALUES)
        writeDht(0x10, AC_LUMA_BITS, AC_LUMA_VALUES)
        if (!grayscale) {
            writeDht(0x01, DC_CHROMA_BITS, DC_VALUES)
            writeDht(0x11, AC_CHROMA_BITS, AC_CHROMA_VALUES)
        }
        // SOS
        o.write(0xFF); o.write(0xDA)
        val sosLen = 6 + comps * 2
        o.write(sosLen shr 8); o.write(sosLen and 0xFF)
        o.write(comps)
        o.write(1); o.write(0x00)
        if (!grayscale) {
            o.write(2); o.write(0x11)
            o.write(3); o.write(0x11)
        }
        o.write(0); o.write(63); o.write(0)
    }

    private fun writeDht(classId: Int, bitsArr: IntArray, values: IntArray) {
        val len = 2 + 1 + 16 + values.size
        out.write(0xFF); out.write(0xC4)
        out.write(len shr 8); out.write(len and 0xFF)
        out.write(classId)
        for (b in bitsArr) out.write(b)
        for (v in values) out.write(v)
    }

    private class HuffTable(bitsArr: IntArray, values: IntArray) {
        val code = IntArray(256)
        val size = IntArray(256)

        init {
            var k = 0
            var c = 0
            for (len in 1..16) {
                for (i in 0 until bitsArr[len - 1]) {
                    val sym = values[k++]
                    code[sym] = c
                    size[sym] = len
                    c++
                }
                c = c shl 1
            }
        }
    }

    private class BitWriter(private val out: OutputStream) {
        private var acc = 0L
        private var count = 0
        private val buf = ByteArray(1 shl 16)
        private var len = 0

        fun write(value: Int, n: Int) {
            if (n == 0) return
            acc = (acc shl n) or (value.toLong() and ((1L shl n) - 1))
            count += n
            while (count >= 8) {
                val b = ((acc shr (count - 8)) and 0xFF).toInt()
                put(b)
                if (b == 0xFF) put(0)
                count -= 8
            }
            acc = acc and ((1L shl count) - 1)
        }

        private fun put(b: Int) {
            if (len == buf.size) { out.write(buf, 0, len); len = 0 }
            buf[len++] = b.toByte()
        }

        fun flush() {
            if (count > 0) write((1 shl (8 - count)) - 1, 8 - count)
            out.write(buf, 0, len)
            len = 0
        }
    }

    companion object {
        private val AAN = doubleArrayOf(1.0, 1.387039845, 1.306562965, 1.175875602, 1.0, 0.785694958, 0.541196100, 0.275899379)

        val ZIGZAG = intArrayOf(
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
        )

        private val STD_LUMA_Q = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99
        )
        private val STD_CHROMA_Q = intArrayOf(
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99
        )
        private val DC_LUMA_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        private val DC_CHROMA_BITS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
        private val DC_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        private val AC_LUMA_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
        private val AC_LUMA_VALUES = intArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa
        )
        private val AC_CHROMA_BITS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
        private val AC_CHROMA_VALUES = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa
        )

        /** In-place floating-point AAN forward DCT (jfdctflt). Output is scaled; quant divisors compensate. */
        fun fdct(d: FloatArray) {
            for (r in 0 until 8) {
                val o = r * 8
                val tmp0 = d[o] + d[o + 7]; val tmp7 = d[o] - d[o + 7]
                val tmp1 = d[o + 1] + d[o + 6]; val tmp6 = d[o + 1] - d[o + 6]
                val tmp2 = d[o + 2] + d[o + 5]; val tmp5 = d[o + 2] - d[o + 5]
                val tmp3 = d[o + 3] + d[o + 4]; val tmp4 = d[o + 3] - d[o + 4]
                var tmp10 = tmp0 + tmp3; val tmp13 = tmp0 - tmp3
                var tmp11 = tmp1 + tmp2; var tmp12 = tmp1 - tmp2
                d[o] = tmp10 + tmp11; d[o + 4] = tmp10 - tmp11
                val z1 = (tmp12 + tmp13) * 0.707106781f
                d[o + 2] = tmp13 + z1; d[o + 6] = tmp13 - z1
                tmp10 = tmp4 + tmp5; tmp11 = tmp5 + tmp6; tmp12 = tmp6 + tmp7
                val z5 = (tmp10 - tmp12) * 0.382683433f
                val z2 = 0.541196100f * tmp10 + z5
                val z4 = 1.306562965f * tmp12 + z5
                val z3 = tmp11 * 0.707106781f
                val z11 = tmp7 + z3; val z13 = tmp7 - z3
                d[o + 5] = z13 + z2; d[o + 3] = z13 - z2
                d[o + 1] = z11 + z4; d[o + 7] = z11 - z4
            }
            for (c in 0 until 8) {
                val tmp0 = d[c] + d[c + 56]; val tmp7 = d[c] - d[c + 56]
                val tmp1 = d[c + 8] + d[c + 48]; val tmp6 = d[c + 8] - d[c + 48]
                val tmp2 = d[c + 16] + d[c + 40]; val tmp5 = d[c + 16] - d[c + 40]
                val tmp3 = d[c + 24] + d[c + 32]; val tmp4 = d[c + 24] - d[c + 32]
                var tmp10 = tmp0 + tmp3; val tmp13 = tmp0 - tmp3
                var tmp11 = tmp1 + tmp2; var tmp12 = tmp1 - tmp2
                d[c] = tmp10 + tmp11; d[c + 32] = tmp10 - tmp11
                val z1 = (tmp12 + tmp13) * 0.707106781f
                d[c + 16] = tmp13 + z1; d[c + 48] = tmp13 - z1
                tmp10 = tmp4 + tmp5; tmp11 = tmp5 + tmp6; tmp12 = tmp6 + tmp7
                val z5 = (tmp10 - tmp12) * 0.382683433f
                val z2 = 0.541196100f * tmp10 + z5
                val z4 = 1.306562965f * tmp12 + z5
                val z3 = tmp11 * 0.707106781f
                val z11 = tmp7 + z3; val z13 = tmp7 - z3
                d[c + 40] = z13 + z2; d[c + 24] = z13 - z2
                d[c + 8] = z11 + z4; d[c + 56] = z11 - z4
            }
        }
    }
}
