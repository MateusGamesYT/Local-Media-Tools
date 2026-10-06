package com.localmediatools.codec.image

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.Inflater

class SoftDecodeException(message: String) : IOException(message)

/** Receives decoded rows (ARGB, non-premultiplied) top to bottom. */
fun interface RowSink {
    fun rows(y: Int, count: Int, argb: IntArray, stride: Int)
}

/**
 * Pure-Kotlin decoders for formats Android cannot decode natively: TIFF (baseline + LZW/Deflate/
 * PackBits, strips or tiles), PSD/PSB (merged composite), QOI, PNM (PBM/PGM/PPM/PAM) and TGA.
 * Images are delivered row by row so callers can stream them into a native bitmap.
 */
object SoftImageDecoders {

    enum class Kind { TIFF, PSD, QOI, PNM, TGA }

    fun size(kind: Kind, data: ByteArray): Pair<Int, Int> = when (kind) {
        Kind.TIFF -> Tiff(data).firstIfd().let { it.width to it.height }
        Kind.PSD -> psdHeader(data).let { it[1] to it[0] }
        Kind.QOI -> qoiSize(data)
        Kind.PNM -> Pnm(data).header().let { it.w to it.h }
        Kind.TGA -> tgaHeader(data).let { it.w to it.h }
    }

    fun decode(kind: Kind, data: ByteArray, sink: RowSink): Pair<Int, Int> = when (kind) {
        Kind.TIFF -> Tiff(data).decode(sink)
        Kind.PSD -> decodePsd(data, sink)
        Kind.QOI -> decodeQoi(data, sink)
        Kind.PNM -> Pnm(data).decode(sink)
        Kind.TGA -> decodeTga(data, sink)
    }

    fun readAll(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw SoftDecodeException("File is too large to decode in memory on this device")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun checkDims(w: Int, h: Int) {
        if (w <= 0 || h <= 0) throw SoftDecodeException("Image has invalid dimensions")
        if (w.toLong() * h > 400_000_000L) throw SoftDecodeException("Image is too large (${w}x$h)")
    }

    // ================================================================== TIFF
    private class Ifd(
        val width: Int, val height: Int, val bitsPerSample: IntArray, val samplesPerPixel: Int,
        val compression: Int, val photometric: Int, val planar: Int, val predictor: Int,
        val stripOffsets: LongArray, val stripByteCounts: LongArray, val rowsPerStrip: Int,
        val tileWidth: Int, val tileHeight: Int, val colorMap: IntArray?, val extraSamples: IntArray,
        val sampleFormat: Int, val fillOrder: Int,
    )

    private class Tiff(val d: ByteArray) {
        val le: Boolean
        init {
            if (d.size < 8) throw SoftDecodeException("Not a TIFF file")
            le = when {
                d[0] == 'I'.code.toByte() && d[1] == 'I'.code.toByte() -> true
                d[0] == 'M'.code.toByte() && d[1] == 'M'.code.toByte() -> false
                else -> throw SoftDecodeException("Not a TIFF file")
            }
            if (u16(2) == 43) throw SoftDecodeException("BigTIFF files are not supported")
            if (u16(2) != 42) throw SoftDecodeException("Not a TIFF file")
        }

        fun u8(o: Int) = d[o].toInt() and 0xFF
        fun u16(o: Int): Int { chk(o, 2); return if (le) u8(o) or (u8(o + 1) shl 8) else (u8(o) shl 8) or u8(o + 1) }
        fun u32(o: Int): Long {
            chk(o, 4)
            return if (le) (u8(o).toLong() or (u8(o + 1).toLong() shl 8) or (u8(o + 2).toLong() shl 16) or (u8(o + 3).toLong() shl 24))
            else ((u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong())
        }
        fun chk(o: Int, n: Int) { if (o < 0 || o + n > d.size) throw SoftDecodeException("TIFF file is truncated") }

        fun values(entry: Int): LongArray {
            val type = u16(entry + 2)
            val count = u32(entry + 4)
            if (count > 50_000_000) throw SoftDecodeException("Corrupt TIFF tag")
            val size = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9 -> 4; 5, 10 -> 8; 16, 17, 18 -> 8; else -> 1 }
            val total = size * count
            val base = if (total <= 4) entry + 8 else u32(entry + 8).toInt()
            return LongArray(count.toInt()) { i ->
                when (type) {
                    1, 2, 6, 7 -> u8(base + i).toLong()
                    3, 8 -> u16(base + i * 2).toLong()
                    4, 9 -> u32(base + i * 4)
                    5, 10 -> { val n = u32(base + i * 8); val dd = u32(base + i * 8 + 4); if (dd == 0L) 0 else n / dd }
                    else -> 0L
                }
            }
        }

        fun firstIfd(): Ifd {
            val off = u32(4).toInt()
            val n = u16(off)
            val tags = HashMap<Int, LongArray>()
            for (i in 0 until n) {
                val e = off + 2 + i * 12
                tags[u16(e)] = values(e)
            }
            fun one(t: Int, def: Long) = tags[t]?.firstOrNull() ?: def
            val w = one(256, 0).toInt(); val h = one(257, 0).toInt()
            checkDims(w, h)
            val spp = one(277, 1).toInt()
            val bps = tags[258]?.map { it.toInt() }?.toIntArray() ?: IntArray(spp) { 1 }
            val cmap = tags[320]?.let { m ->
                val k = m.size / 3
                IntArray(k) { i -> (0xFF shl 24) or ((m[i].toInt() shr 8) shl 16) or ((m[k + i].toInt() shr 8) shl 8) or (m[2 * k + i].toInt() shr 8) }
            }
            val tw = one(322, 0).toInt(); val th = one(323, 0).toInt()
            val offsets = (if (tw > 0) tags[324] else tags[273]) ?: throw SoftDecodeException("TIFF has no image data")
            val counts = (if (tw > 0) tags[325] else tags[279]) ?: LongArray(offsets.size) { -1 }
            return Ifd(w, h, bps, spp, one(259, 1).toInt(), one(262, if (spp >= 3) 2 else 1).toInt(), one(284, 1).toInt(),
                one(317, 1).toInt(), offsets, counts, one(278, h.toLong()).toInt().coerceAtLeast(1), tw, th, cmap,
                tags[338]?.map { it.toInt() }?.toIntArray() ?: IntArray(0), one(339, 1).toInt(), one(266, 1).toInt())
        }

        fun decode(sink: RowSink): Pair<Int, Int> {
            val f = firstIfd()
            val bits = f.bitsPerSample[0]
            if (f.bitsPerSample.any { it != bits }) throw SoftDecodeException("TIFF with mixed bit depths is not supported")
            if (bits !in intArrayOf(1, 2, 4, 8, 16)) throw SoftDecodeException("TIFF with $bits-bit samples is not supported")
            if (f.sampleFormat == 3) throw SoftDecodeException("Floating-point TIFF is not supported")
            if (f.compression !in intArrayOf(1, 5, 8, 32946, 32773)) {
                val name = when (f.compression) { 6, 7 -> "JPEG"; 2, 3, 4 -> "CCITT fax"; 34712 -> "JPEG 2000"; else -> "#${f.compression}" }
                throw SoftDecodeException("TIFF compression $name is not supported")
            }
            if (f.photometric !in intArrayOf(0, 1, 2, 3, 5)) throw SoftDecodeException("TIFF colour model ${f.photometric} is not supported")
            val spp = f.samplesPerPixel
            val planes = if (f.planar == 2) spp else 1
            val samplesPerUnit = if (f.planar == 2) 1 else spp
            val w = f.width; val h = f.height
            val hasAlpha = f.extraSamples.isNotEmpty() && (f.photometric == 2 && spp >= 4 || f.photometric <= 1 && spp >= 2)
            val alphaPremult = f.extraSamples.firstOrNull() == 1
            val tiled = f.tileWidth > 0 && f.tileHeight > 0
            val blockW = if (tiled) f.tileWidth else w
            val blockH = if (tiled) f.tileHeight else f.rowsPerStrip.coerceAtMost(h)
            val blocksAcross = (w + blockW - 1) / blockW
            val blocksDown = (h + blockH - 1) / blockH
            val rowBytes = (blockW.toLong() * samplesPerUnit * bits + 7) / 8
            // Buffer one band (blockH rows) of 16-bit samples per plane.
            val band = Array(planes) { IntArray(w * blockH * samplesPerUnit) }
            val out = IntArray(w * blockH)
            for (by in 0 until blocksDown) {
                val rowsInBand = minOf(blockH, h - by * blockH)
                for (p in 0 until planes) for (bx in 0 until blocksAcross) {
                    val idx = p * blocksAcross * blocksDown + by * blocksAcross + bx
                    if (idx >= f.stripOffsets.size) throw SoftDecodeException("TIFF is missing image data")
                    val off = f.stripOffsets[idx].toInt()
                    val cnt = f.stripByteCounts.getOrElse(idx) { -1 }.let { if (it < 0) d.size - off.toLong() else it }.toInt()
                    if (off < 0 || off.toLong() + cnt > d.size) throw SoftDecodeException("TIFF file is truncated")
                    val expected = (rowBytes * blockH).toInt()
                    val raw = when (f.compression) {
                        1 -> d.copyOfRange(off, off + minOf(cnt, expected))
                        5 -> lzw(d, off, cnt, expected)
                        8, 32946 -> inflate(d, off, cnt, expected)
                        32773 -> packBits(d, off, cnt, expected)
                        else -> throw SoftDecodeException("Unsupported TIFF compression")
                    }
                    if (f.fillOrder == 2) for (i in raw.indices) raw[i] = (Integer.reverse(raw[i].toInt()) ushr 24).toByte()
                    // Unpack samples of this block into the band buffer.
                    val dst = band[p]
                    for (r in 0 until rowsInBand) {
                        val rowStart = (r * rowBytes).toInt()
                        val samples = IntArray(blockW * samplesPerUnit)
                        unpack(raw, rowStart, bits, samples, le)
                        if (f.predictor == 2) {
                            val mask = if (bits == 16) 0xFFFF else 0xFF
                            for (i in samplesPerUnit until samples.size) samples[i] = (samples[i] + samples[i - samplesPerUnit]) and mask
                        }
                        val x0 = bx * blockW
                        val cols = minOf(blockW, w - x0)
                        System.arraycopy(samples, 0, dst, (r * w + x0) * samplesPerUnit, cols * samplesPerUnit)
                    }
                }
                // Convert the band to ARGB.
                val maxV = (1 shl bits) - 1
                fun s8(v: Int) = if (bits == 8) v else if (bits == 16) v ushr 8 else v * 255 / maxV
                for (r in 0 until rowsInBand) for (x in 0 until w) {
                    fun sample(c: Int): Int = if (planes > 1) band[c][r * w + x] else band[0][(r * w + x) * spp + c]
                    var a = 255
                    val argb = when (f.photometric) {
                        0, 1 -> {
                            var g = s8(sample(0))
                            if (f.photometric == 0) g = 255 - g
                            if (hasAlpha) a = s8(sample(1))
                            (g shl 16) or (g shl 8) or g
                        }
                        2 -> {
                            var rr = s8(sample(0)); var gg = s8(sample(1)); var bb = s8(sample(2))
                            if (hasAlpha) {
                                a = s8(sample(3))
                                if (alphaPremult && a in 1..254) { rr = minOf(255, rr * 255 / a); gg = minOf(255, gg * 255 / a); bb = minOf(255, bb * 255 / a) }
                            }
                            (rr shl 16) or (gg shl 8) or bb
                        }
                        3 -> (f.colorMap ?: throw SoftDecodeException("Palette TIFF without colour map")).getOrElse(sample(0)) { 0 } and 0xFFFFFF
                        5 -> {
                            val c = s8(sample(0)); val m = s8(sample(1)); val y = s8(sample(2)); val k = s8(sample(3))
                            val rr = (255 - c) * (255 - k) / 255; val gg = (255 - m) * (255 - k) / 255; val bb = (255 - y) * (255 - k) / 255
                            (rr shl 16) or (gg shl 8) or bb
                        }
                        else -> 0
                    }
                    out[r * w + x] = (a shl 24) or argb
                }
                sink.rows(by * blockH, rowsInBand, out, w)
            }
            return w to h
        }
    }

    private fun unpack(raw: ByteArray, start: Int, bits: Int, out: IntArray, le: Boolean) {
        when (bits) {
            8 -> for (i in out.indices) out[i] = if (start + i < raw.size) raw[start + i].toInt() and 0xFF else 0
            16 -> for (i in out.indices) {
                val o = start + i * 2
                out[i] = if (o + 1 < raw.size) {
                    if (le) (raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8)
                    else ((raw[o].toInt() and 0xFF) shl 8) or (raw[o + 1].toInt() and 0xFF)
                } else 0
            }
            else -> {
                val perByte = 8 / bits
                val mask = (1 shl bits) - 1
                for (i in out.indices) {
                    val o = start + i / perByte
                    val shift = 8 - bits * (i % perByte + 1)
                    out[i] = if (o < raw.size) (raw[o].toInt() shr shift) and mask else 0
                }
            }
        }
    }

    private fun inflate(d: ByteArray, off: Int, len: Int, expected: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(d, off, len)
        val out = ByteArray(expected)
        var n = 0
        try {
            while (n < expected && !inf.finished()) {
                val k = inf.inflate(out, n, expected - n)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw SoftDecodeException("Corrupt compressed TIFF data")
        } finally { inf.end() }
        return out
    }

    private fun packBits(d: ByteArray, off: Int, len: Int, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var i = off; var o = 0
        val end = off + len
        while (i < end && o < expected) {
            val n = d[i++].toInt()
            if (n >= 0) {
                val c = minOf(n + 1, expected - o, end - i)
                System.arraycopy(d, i, out, o, c); i += n + 1; o += c
            } else if (n != -128) {
                val c = minOf(1 - n, expected - o)
                if (i < end) java.util.Arrays.fill(out, o, o + c, d[i])
                i++; o += c
            }
        }
        return out
    }

    /** TIFF LZW (MSB-first codes, early change). */
    private fun lzw(d: ByteArray, off: Int, len: Int, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var o = 0
        val prefix = IntArray(4096); val suffix = ByteArray(4096); val length = IntArray(4096)
        for (i in 0 until 256) { suffix[i] = i.toByte(); length[i] = 1; prefix[i] = -1 }
        var next = 258; var codeLen = 9
        var bitPos = off.toLong() * 8
        val endBit = (off + len).toLong() * 8
        var old = -1
        val stack = ByteArray(4097)
        fun readCode(): Int {
            if (bitPos + codeLen > endBit) return 257
            var v = 0
            for (k in 0 until codeLen) {
                val byte = d[(bitPos ushr 3).toInt()].toInt()
                v = (v shl 1) or ((byte shr (7 - (bitPos and 7).toInt())) and 1)
                bitPos++
            }
            return v
        }
        fun emit(code: Int) {
            var c = code; var top = 0
            while (c >= 0 && top < stack.size) { stack[top++] = suffix[c]; c = prefix[c] }
            while (top > 0 && o < expected) out[o++] = stack[--top]
        }
        fun first(code: Int): Byte { var c = code; while (prefix[c] >= 0) c = prefix[c]; return suffix[c] }
        while (o < expected) {
            val code = readCode()
            if (code == 257) break
            if (code == 256) { next = 258; codeLen = 9; old = -1; continue }
            if (old == -1) {
                if (code > 255) break
                emit(code); old = code; continue
            }
            if (code < next) {
                emit(code)
                if (next < 4096) { prefix[next] = old; suffix[next] = first(code); length[next] = length[old] + 1; next++ }
            } else {
                if (next < 4096) { prefix[next] = old; suffix[next] = first(old); length[next] = length[old] + 1; next++ }
                emit(next - 1)
            }
            old = code
            codeLen = when { next + 1 >= 2048 -> 12; next + 1 >= 1024 -> 11; next + 1 >= 512 -> 10; else -> 9 }
        }
        return out
    }

    // ================================================================== PSD
    /** [height, width, channels, depth, mode, version] */
    private fun psdHeader(d: ByteArray): IntArray {
        if (d.size < 26 || String(d, 0, 4, Charsets.ISO_8859_1) != "8BPS") throw SoftDecodeException("Not a PSD file")
        fun u16(o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)
        val version = u16(4)
        val channels = u16(12); val h = u32(14); val w = u32(18); val depth = u16(22); val mode = u16(24)
        checkDims(w, h)
        return intArrayOf(h, w, channels, depth, mode, version)
    }

    private fun decodePsd(d: ByteArray, sink: RowSink): Pair<Int, Int> {
        val hd = psdHeader(d)
        val h = hd[0]; val w = hd[1]; val channels = hd[2]; val depth = hd[3]; val mode = hd[4]; val version = hd[5]
        if (depth != 8 && depth != 16) throw SoftDecodeException("$depth-bit PSD is not supported")
        if (mode !in intArrayOf(1, 3, 4)) throw SoftDecodeException("PSD colour mode $mode is not supported (use RGB, Grayscale or CMYK)")
        var p = 26
        fun u16(o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
        fun u32(o: Int): Long = (((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)).toLong() and 0xFFFFFFFFL
        fun skipSection(longLen: Boolean) {
            val len = if (longLen) (u32(p) shl 32) or u32(p + 4) else u32(p)
            p += (if (longLen) 8 else 4) + len.toInt()
            if (p > d.size) throw SoftDecodeException("PSD file is truncated")
        }
        skipSection(false) // colour mode data
        skipSection(false) // image resources
        skipSection(version == 2) // layer and mask info
        if (p + 2 > d.size) throw SoftDecodeException("PSD file has no merged image")
        val compression = u16(p); p += 2
        val bps = depth / 8
        val used = minOf(channels, if (mode == 4) 5 else if (mode == 3) 4 else 2)
        val planes = Array(used) { ByteArray(w * h) }
        if (compression == 0) {
            for (c in 0 until channels) for (y in 0 until h) for (x in 0 until w) {
                if (p + bps > d.size) throw SoftDecodeException("PSD file is truncated")
                if (c < used) planes[c][y * w + x] = d[p]
                p += bps
            }
        } else if (compression == 1) {
            val countSize = if (version == 2) 4 else 2
            val counts = IntArray(channels * h) { i -> if (countSize == 2) u16(p + i * 2) else u32(p + i * 4).toInt() }
            p += channels * h * countSize
            for (c in 0 until channels) for (y in 0 until h) {
                val n = counts[c * h + y]
                if (p + n > d.size) throw SoftDecodeException("PSD file is truncated")
                if (c < used) {
                    val row = packBits(d, p, n, w * bps)
                    for (x in 0 until w) planes[c][y * w + x] = row[x * bps]
                }
                p += n
            }
        } else {
            throw SoftDecodeException("PSD with ZIP-compressed composite is not supported")
        }
        val out = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                fun ch(c: Int) = planes[c][i].toInt() and 0xFF
                out[x] = when (mode) {
                    1 -> { val g = ch(0); val a = if (used > 1) ch(1) else 255; (a shl 24) or (g shl 16) or (g shl 8) or g }
                    3 -> { val a = if (used > 3) ch(3) else 255; (a shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2) }
                    else -> {
                        // PSD CMYK stores inverted values (0 = full ink).
                        val c = 255 - ch(0); val m = 255 - ch(1); val yy = 255 - ch(2); val k = 255 - ch(3)
                        val r = (255 - c) * (255 - k) / 255; val g = (255 - m) * (255 - k) / 255; val b = (255 - yy) * (255 - k) / 255
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            sink.rows(y, 1, out, w)
        }
        return w to h
    }

    // ================================================================== QOI
    private fun qoiSize(d: ByteArray): Pair<Int, Int> {
        if (d.size < 14 || String(d, 0, 4, Charsets.ISO_8859_1) != "qoif") throw SoftDecodeException("Not a QOI file")
        val w = ((d[4].toInt() and 0xFF) shl 24) or ((d[5].toInt() and 0xFF) shl 16) or ((d[6].toInt() and 0xFF) shl 8) or (d[7].toInt() and 0xFF)
        val h = ((d[8].toInt() and 0xFF) shl 24) or ((d[9].toInt() and 0xFF) shl 16) or ((d[10].toInt() and 0xFF) shl 8) or (d[11].toInt() and 0xFF)
        checkDims(w, h)
        return w to h
    }

    private fun decodeQoi(d: ByteArray, sink: RowSink): Pair<Int, Int> {
        val (w, h) = qoiSize(d)
        val index = IntArray(64)
        var r = 0; var g = 0; var b = 0; var a = 255
        var p = 14
        var run = 0
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (run > 0) { run-- } else {
                    if (p >= d.size) throw SoftDecodeException("QOI file is truncated")
                    val b1 = d[p++].toInt() and 0xFF
                    when {
                        b1 == 0xFE -> { r = d[p].toInt() and 0xFF; g = d[p + 1].toInt() and 0xFF; b = d[p + 2].toInt() and 0xFF; p += 3 }
                        b1 == 0xFF -> { r = d[p].toInt() and 0xFF; g = d[p + 1].toInt() and 0xFF; b = d[p + 2].toInt() and 0xFF; a = d[p + 3].toInt() and 0xFF; p += 4 }
                        (b1 and 0xC0) == 0x00 -> { val c = index[b1]; a = c ushr 24; r = (c shr 16) and 0xFF; g = (c shr 8) and 0xFF; b = c and 0xFF }
                        (b1 and 0xC0) == 0x40 -> { r = (r + ((b1 shr 4) and 3) - 2) and 0xFF; g = (g + ((b1 shr 2) and 3) - 2) and 0xFF; b = (b + (b1 and 3) - 2) and 0xFF }
                        (b1 and 0xC0) == 0x80 -> {
                            val b2 = d[p++].toInt() and 0xFF
                            val vg = (b1 and 0x3F) - 32
                            r = (r + vg - 8 + ((b2 shr 4) and 0x0F)) and 0xFF
                            g = (g + vg) and 0xFF
                            b = (b + vg - 8 + (b2 and 0x0F)) and 0xFF
                        }
                        else -> run = b1 and 0x3F
                    }
                    index[(r * 3 + g * 5 + b * 7 + a * 11) % 64] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                row[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            sink.rows(y, 1, row, w)
        }
        return w to h
    }

    // ================================================================== PNM
    private class Pnm(val d: ByteArray) {
        class Header(val type: Char, val w: Int, val h: Int, val maxV: Int, val depth: Int, val dataStart: Int, val tupl: String)
        private var p = 0
        private fun skipWs() {
            while (p < d.size) {
                val c = d[p].toInt().toChar()
                if (c == '#') { while (p < d.size && d[p].toInt() != '\n'.code) p++ }
                else if (c.isWhitespace()) p++ else break
            }
        }
        private fun token(): String { skipWs(); val s = p; while (p < d.size && !d[p].toInt().toChar().isWhitespace()) p++; return String(d, s, p - s, Charsets.ISO_8859_1) }
        private fun int(): Int = token().toIntOrNull() ?: throw SoftDecodeException("Corrupt PNM header")

        fun header(): Header {
            p = 0
            val magic = token()
            if (magic.length != 2 || magic[0] != 'P') throw SoftDecodeException("Not a PNM file")
            val t = magic[1]
            if (t == '7') {
                var w = 0; var h = 0; var depth = 0; var maxV = 255; var tupl = ""
                while (true) {
                    val k = token()
                    when (k) {
                        "WIDTH" -> w = int(); "HEIGHT" -> h = int(); "DEPTH" -> depth = int(); "MAXVAL" -> maxV = int()
                        "TUPLTYPE" -> tupl = token()
                        "ENDHDR" -> break
                        "" -> throw SoftDecodeException("Corrupt PAM header")
                    }
                }
                p++ // single whitespace
                checkDims(w, h)
                return Header(t, w, h, maxV, depth, p, tupl)
            }
            val w = int(); val h = int()
            val maxV = if (t == '1' || t == '4') 1 else int()
            checkDims(w, h)
            if (t in '4'..'6') p++ // single whitespace before raster
            val depth = when (t) { '1', '2', '4', '5' -> 1; else -> 3 }
            return Header(t, w, h, maxV, depth, p, "")
        }

        fun decode(sink: RowSink): Pair<Int, Int> {
            val hd = header()
            if (hd.maxV <= 0 || hd.maxV > 65535) throw SoftDecodeException("Corrupt PNM header")
            val w = hd.w; val h = hd.h
            val row = IntArray(w)
            val bytesPer = if (hd.maxV > 255) 2 else 1
            p = hd.dataStart
            val ascii = hd.type in '1'..'3'
            fun sample(): Int {
                if (ascii) {
                    if (hd.type == '1') { skipWs(); if (p >= d.size) throw SoftDecodeException("PNM file is truncated"); return d[p++].toInt() - '0'.code }
                    return int()
                }
                if (p + bytesPer > d.size) throw SoftDecodeException("PNM file is truncated")
                val v = if (bytesPer == 2) ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF) else d[p].toInt() and 0xFF
                p += bytesPer
                return v
            }
            fun s8(v: Int) = (v * 255 + hd.maxV / 2) / hd.maxV
            for (y in 0 until h) {
                if (hd.type == '4') {
                    val rb = (w + 7) / 8
                    if (p + rb > d.size) throw SoftDecodeException("PBM file is truncated")
                    for (x in 0 until w) {
                        val bit = (d[p + x / 8].toInt() shr (7 - x % 8)) and 1
                        row[x] = if (bit == 1) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                    }
                    p += rb
                } else {
                    for (x in 0 until w) {
                        row[x] = when {
                            hd.type == '1' -> if (sample() == 1) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                            hd.depth == 1 || hd.depth == 2 -> {
                                val g = s8(sample()); val a = if (hd.depth == 2) s8(sample()) else 255
                                (a shl 24) or (g shl 16) or (g shl 8) or g
                            }
                            else -> {
                                val r = s8(sample()); val g = s8(sample()); val b = s8(sample())
                                var a = 255
                                if (hd.depth >= 4) { a = s8(sample()); repeat(hd.depth - 4) { sample() } }
                                (a shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                }
                sink.rows(y, 1, row, w)
            }
            return w to h
        }
    }

    // ================================================================== TGA
    private class TgaHeader(val idLen: Int, val cmapType: Int, val type: Int, val cmapFirst: Int, val cmapLen: Int, val cmapBits: Int,
                            val w: Int, val h: Int, val bpp: Int, val desc: Int)

    private fun tgaHeader(d: ByteArray): TgaHeader {
        if (d.size < 18) throw SoftDecodeException("Not a TGA file")
        fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
        val hd = TgaHeader(d[0].toInt() and 0xFF, d[1].toInt() and 0xFF, d[2].toInt() and 0xFF, u16(3), u16(5), d[7].toInt() and 0xFF,
            u16(12), u16(14), d[16].toInt() and 0xFF, d[17].toInt() and 0xFF)
        if (hd.type !in intArrayOf(1, 2, 3, 9, 10, 11)) throw SoftDecodeException("Not a supported TGA file")
        checkDims(hd.w, hd.h)
        return hd
    }

    private fun decodeTga(d: ByteArray, sink: RowSink): Pair<Int, Int> {
        val hd = tgaHeader(d)
        var p = 18 + hd.idLen
        fun px(bits: Int, o: Int): Int = when (bits) {
            8 -> { val g = d[o].toInt() and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            15, 16 -> {
                val v = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
                val r = ((v shr 10) and 31) * 255 / 31; val g = ((v shr 5) and 31) * 255 / 31; val b = (v and 31) * 255 / 31
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            24 -> (0xFF shl 24) or ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o].toInt() and 0xFF)
            32 -> ((d[o + 3].toInt() and 0xFF) shl 24) or ((d[o + 2].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o].toInt() and 0xFF)
            else -> throw SoftDecodeException("Unsupported TGA pixel depth $bits")
        }
        var cmap: IntArray? = null
        if (hd.cmapType == 1) {
            val eb = (hd.cmapBits + 7) / 8
            cmap = IntArray(hd.cmapFirst + hd.cmapLen) { 0xFF000000.toInt() }
            for (i in 0 until hd.cmapLen) { cmap[hd.cmapFirst + i] = px(hd.cmapBits, p); p += eb }
        }
        val bytes = (hd.bpp + 7) / 8
        val w = hd.w; val h = hd.h
        val pixels = IntArray(w * h)
        val rle = hd.type >= 9
        var i = 0
        fun read(): Int {
            if (p + bytes > d.size) throw SoftDecodeException("TGA file is truncated")
            val v = if (hd.type == 1 || hd.type == 9) {
                val idx = if (bytes == 2) (d[p].toInt() and 0xFF) or ((d[p + 1].toInt() and 0xFF) shl 8) else d[p].toInt() and 0xFF
                cmap?.getOrNull(idx) ?: throw SoftDecodeException("TGA colour index out of range")
            } else px(if (hd.bpp == 16 && (hd.desc and 0x0F) == 0) 15 else hd.bpp, p)
            p += bytes
            return v
        }
        while (i < w * h) {
            if (rle) {
                if (p >= d.size) throw SoftDecodeException("TGA file is truncated")
                val c = d[p++].toInt() and 0xFF
                val n = (c and 0x7F) + 1
                if (c and 0x80 != 0) { val v = read(); for (k in 0 until n) if (i < pixels.size) pixels[i++] = v }
                else for (k in 0 until n) if (i < pixels.size) pixels[i++] = read()
            } else pixels[i++] = read()
        }
        // Without an alpha channel descriptor, 32-bit TGAs are treated as opaque.
        if (hd.bpp == 32 && (hd.desc and 0x0F) == 0) for (k in pixels.indices) pixels[k] = pixels[k] or (0xFF shl 24)
        val topDown = hd.desc and 0x20 != 0
        val rightLeft = hd.desc and 0x10 != 0
        val row = IntArray(w)
        for (y in 0 until h) {
            val sy = if (topDown) y else h - 1 - y
            for (x in 0 until w) row[x] = pixels[sy * w + (if (rightLeft) w - 1 - x else x)]
            sink.rows(y, 1, row, w)
        }
        return w to h
    }
}
