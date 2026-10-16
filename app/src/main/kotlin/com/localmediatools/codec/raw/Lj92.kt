package com.localmediatools.codec.raw

import com.localmediatools.i18n.tr

/**
 * Lossless JPEG (ITU T.81 process 14, "LJ92"), the compression of Canon CR2 and most DNG files:
 * decoding to samples and encoding them back with the same frame (size, components, precision,
 * predictor, restarts) and Huffman tables fitted to the new data.
 */
class Lj92Frame internal constructor(
    /** Bits per sample. */
    val precision: Int,
    /** Samples per line, per component. */
    val width: Int,
    val height: Int,
    val components: Int,
    /** 1–7 (T.81 table H.1). */
    val predictor: Int,
    /** In whole lines' worth of samples, 0 = none (only restarts at line starts are supported). */
    val restartInterval: Int,
    /** The Huffman table each component uses (SOS order). */
    internal val tableOf: IntArray,
    /** height × width × components, components interleaved. */
    val samples: ShortArray,
    /** The header segments in their original order, re-emitted on encoding (DHT replaced). */
    internal val segments: List<Pair<Int, ByteArray>>,
) {
    /** Samples per line, all components. */
    val lineLength: Int get() = width * components
}

object Lj92 {
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOF3 = 0xC3
    private const val DHT = 0xC4
    private const val SOS = 0xDA
    private const val DRI = 0xDD

    private class BitReader(val d: ByteArray, var pos: Int, val end: Int) {
        private var acc = 0L
        private var n = 0
        var marker = false

        private fun fill() {
            while (n <= 56) {
                var b = 0
                if (!marker && pos < end) {
                    b = d[pos].toInt() and 0xFF
                    if (b == 0xFF) {
                        val nx = if (pos + 1 < end) d[pos + 1].toInt() and 0xFF else 0xD9
                        if (nx == 0) pos += 2 else { marker = true; b = 0 }
                    } else pos++
                }
                acc = (acc shl 8) or b.toLong()
                n += 8
            }
        }

        fun peek(k: Int): Int { if (n < k) fill(); return ((acc ushr (n - k)) and ((1L shl k) - 1)).toInt() }
        fun skip(k: Int) { n -= k }
        fun bits(k: Int): Int { if (k == 0) return 0; val v = peek(k); n -= k; return v }

        /** At a restart: drop the padding bits and step over the RSTn marker. */
        fun restart() {
            n = 0; acc = 0
            if (pos + 1 < end && (d[pos].toInt() and 0xFF) == 0xFF && (d[pos + 1].toInt() and 0xF8) == 0xD0) pos += 2
            else throw RawFormatException("missing restart marker")
            marker = false
        }
    }

    /** The frame's header only ([Lj92Frame.samples] empty). */
    fun header(data: ByteArray, off: Int = 0, length: Int = data.size - off): Lj92Frame = decode(data, off, length, headerOnly = true)

    fun decode(data: ByteArray, off: Int = 0, length: Int = data.size - off, headerOnly: Boolean = false): Lj92Frame {
        val end = off + length
        var p = off
        fun u8(): Int { if (p >= end) throw RawFormatException("lossless JPEG cut short"); return data[p++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        if (u8() != 0xFF || u8() != SOI) throw RawFormatException("not a JPEG stream")
        val tables = arrayOfNulls<HuffTable>(4)
        val segments = ArrayList<Pair<Int, ByteArray>>()
        var precision = 0; var width = 0; var height = 0; var ncomp = 0
        var compIds = IntArray(0)
        var restart = 0
        while (true) {
            var m = u8()
            if (m != 0xFF) throw RawFormatException("JPEG marker expected")
            while (m == 0xFF) m = u8()
            if (m == SOS) {
                val segStart = p
                val len = u16()
                val ns = u8()
                if (ns != ncomp || ncomp == 0) throw RawUnsupportedException("scan with $ns of $ncomp components")
                val tableOf = IntArray(ns)
                for (i in 0 until ns) {
                    val cs = u8(); val t = u8() ushr 4
                    if (cs != compIds[i]) throw RawUnsupportedException("components out of order")
                    if (t > 3 || tables[t] == null) throw RawFormatException("missing Huffman table")
                    tableOf[i] = t
                }
                val predictor = u8(); u8() /* Se */; val pt = u8() and 0x0F
                if (predictor !in 1..7) throw RawUnsupportedException("not lossless JPEG (predictor $predictor)")
                if (pt != 0) throw RawUnsupportedException("point transform $pt")
                p = segStart + len
                segments.add(SOS to data.copyOfRange(segStart, segStart + len))
                if (restart != 0) {
                    if (restart % width != 0) throw RawUnsupportedException("restart interval not whole lines")
                    if (predictor != 1) throw RawUnsupportedException("restarts with predictor $predictor")
                }
                val samples = if (headerOnly) ShortArray(0) else ShortArray(Math.multiplyExact(Math.multiplyExact(width, height), ns))
                val frame = Lj92Frame(precision, width, height, ns, predictor, restart / width, tableOf, samples, segments)
                if (!headerOnly) decodeScan(frame, tables.requireNoNulls2(tableOf), BitReader(data, p, end))
                return frame
            }
            if (m == SOI || m in 0xD0..0xD7) continue
            if (m == EOI) throw RawFormatException("no scan in the JPEG stream")
            val segStart = p
            val len = u16()
            if (len < 2 || segStart + len > end) throw RawFormatException("bad JPEG segment")
            val segEnd = segStart + len
            when (m) {
                SOF3 -> {
                    precision = u8(); height = u16(); width = u16(); ncomp = u8()
                    if (precision !in 2..16 || width == 0 || height == 0 || ncomp !in 1..4) throw RawUnsupportedException("frame $width×$height×$ncomp, $precision bits")
                    compIds = IntArray(ncomp)
                    for (i in 0 until ncomp) {
                        compIds[i] = u8(); val hv = u8(); u8()
                        if (hv != 0x11) throw RawUnsupportedException("subsampled components (sRAW/mRAW)", tr("Canon sRAW/mRAW"))
                    }
                }
                0xC0, 0xC1, 0xC2, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> throw RawUnsupportedException("not lossless JPEG (SOF ${"%02X".format(m)})")
                DHT -> while (p < segEnd) {
                    val tc = u8(); val id = tc and 0x0F
                    if (tc ushr 4 != 0 || id > 3) throw RawFormatException("bad Huffman table")
                    val bits = IntArray(17); var total = 0
                    for (i in 1..16) { bits[i] = u8(); total += bits[i] }
                    if (total > 256) throw RawFormatException("bad Huffman table")
                    val vals = IntArray(total) { u8() }
                    tables[id] = HuffTable(bits, vals)
                }
                DRI -> restart = u16()
                else -> {}
            }
            p = segEnd
            segments.add(m to data.copyOfRange(segStart, segEnd))
        }
    }

    private fun Array<HuffTable?>.requireNoNulls2(tableOf: IntArray): Array<HuffTable> = Array(tableOf.size) { this[tableOf[it]]!! }

    private fun decodeScan(f: Lj92Frame, huff: Array<HuffTable>, br: BitReader) {
        val nc = f.components; val line = f.lineLength; val s = f.samples
        val initial = 1 shl (f.precision - 1)
        val restartLines = f.restartInterval
        val mask = (1 shl f.precision) - 1
        for (row in 0 until f.height) {
            val fresh = row == 0 || (restartLines > 0 && row % restartLines == 0)
            if (fresh && row > 0) br.restart()
            val base = row * line
            for (i in 0 until line) {
                val c = i % nc
                val diff = readDiff(br, huff[c])
                val idx = base + i
                val pred = if (i < nc) {
                    if (fresh) initial else s[idx - line].toInt() and 0xFFFF
                } else if (fresh) {
                    s[idx - nc].toInt() and 0xFFFF
                } else predict(f.predictor, s[idx - nc].toInt() and 0xFFFF, s[idx - line].toInt() and 0xFFFF, s[idx - line - nc].toInt() and 0xFFFF)
                val v = (pred + diff) and 0xFFFF
                if (v > mask) throw RawFormatException("sample out of range")
                s[idx] = v.toShort()
            }
        }
    }

    private fun predict(psv: Int, ra: Int, rb: Int, rc: Int): Int = when (psv) {
        1 -> ra
        2 -> rb
        3 -> rc
        4 -> ra + rb - rc
        5 -> ra + ((rb - rc) shr 1)
        6 -> rb + ((ra - rc) shr 1)
        else -> (ra + rb) shr 1
    }

    private fun readDiff(br: BitReader, h: HuffTable): Int {
        val look = h.look[br.peek(HuffTable.LOOK)]
        val sym: Int
        if (look >= 0) {
            br.skip(look ushr 8); sym = look and 0xFF
        } else {
            var len = HuffTable.LOOK + 1
            var code = br.bits(len)
            while (len <= 16 && code > h.maxCode[len]) { code = (code shl 1) or br.bits(1); len++ }
            if (len > 16) throw RawFormatException("bad Huffman code")
            sym = h.vals[h.valPtr[len] + code - h.minCode[len]]
        }
        return when {
            sym == 0 -> 0
            sym == 16 -> 32768
            sym > 16 -> throw RawFormatException("bad difference category $sym")
            else -> {
                val v = br.bits(sym)
                if (v < (1 shl (sym - 1))) v - (1 shl sym) + 1 else v
            }
        }
    }

    /** Category (number of extra bits) of a difference already wrapped to −32768..32767. */
    private fun category(d: Int): Int = if (d == 0) 0 else if (d == -32768) 16 else 32 - Integer.numberOfLeadingZeros(Math.abs(d))

    private inline fun forEachDiff(f: Lj92Frame, s: ShortArray, action: (c: Int, diff: Int, row: Int, i: Int) -> Unit) {
        val nc = f.components; val line = f.lineLength
        val initial = 1 shl (f.precision - 1)
        val restartLines = f.restartInterval
        for (row in 0 until f.height) {
            val fresh = row == 0 || (restartLines > 0 && row % restartLines == 0)
            val base = row * line
            for (i in 0 until line) {
                val idx = base + i
                val pred = if (i < nc) {
                    if (fresh) initial else s[idx - line].toInt() and 0xFFFF
                } else if (fresh) {
                    s[idx - nc].toInt() and 0xFFFF
                } else predict(f.predictor, s[idx - nc].toInt() and 0xFFFF, s[idx - line].toInt() and 0xFFFF, s[idx - line - nc].toInt() and 0xFFFF)
                val d = ((s[idx].toInt() and 0xFFFF) - pred).toShort().toInt()
                action(i % nc, d, row, i)
            }
        }
    }

    /**
     * Encodes [samples] (laid out like [Lj92Frame.samples]) with [f]'s frame and segments, with
     * Huffman tables fitted to them. Samples must fit [Lj92Frame.precision] bits.
     */
    fun encode(f: Lj92Frame, samples: ShortArray = f.samples): ByteArray {
        require(samples.size == f.samples.size)
        val maxV = (1 shl f.precision) - 1
        for (v in samples) if ((v.toInt() and 0xFFFF) > maxV) throw IllegalArgumentException("sample over ${f.precision} bits")
        // Pass 1: how often each difference category occurs. One table serves every component and
        // is written under each table number the scan uses: dcraw and LibRaw pick tables by
        // component position rather than by the scan's selectors, so tables must not differ.
        val used = f.tableOf.toSet().sorted()
        val freq = LongArray(257)
        forEachDiff(f, samples) { _, d, _, _ -> freq[category(d)]++ }
        val shared = optimalTable(freq)
        val huffs = arrayOfNulls<HuffTable>(4)
        for (t in used) huffs[t] = shared
        // Header: the original segments, with Huffman tables replaced by the new ones.
        val w = BitWriter(samples.size + 4096)
        w.byte(0xFF); w.byte(SOI)
        var dhtDone = false
        fun segment(marker: Int, payload: ByteArray) {
            w.byte(0xFF); w.byte(marker)
            for (b in payload) w.byte(b.toInt() and 0xFF)
        }
        fun dhtPayload(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(0); out.write(0)
            for (t in used) {
                val h = huffs[t]!!
                out.write(t)
                for (i in 1..16) out.write(h.bits[i])
                for (v in h.vals) out.write(v)
            }
            val b = out.toByteArray()
            b[0] = (b.size ushr 8).toByte(); b[1] = b.size.toByte()
            return b
        }
        for ((marker, payload) in f.segments) {
            if (marker == DHT) { if (!dhtDone) { segment(DHT, dhtPayload()); dhtDone = true }; continue }
            if (marker == SOS && !dhtDone) { segment(DHT, dhtPayload()); dhtDone = true }
            segment(marker, payload)
        }
        // Pass 2: the entropy-coded data.
        val restartLines = f.restartInterval
        var rst = 0
        val tableOf = f.tableOf
        var lastRow = 0
        forEachDiff(f, samples) { c, d, row, i ->
            if (i == 0 && row > 0 && restartLines > 0 && row % restartLines == 0 && row != lastRow) {
                w.flush(); w.byte(0xFF); w.byte(0xD0 + (rst and 7)); rst++
            }
            lastRow = row
            val h = huffs[tableOf[c]]!!
            val cat = category(d)
            w.put(h.code[cat], h.size[cat])
            if (cat in 1..15) w.put(if (d < 0) d - 1 else d, cat)
        }
        w.flush()
        w.byte(0xFF); w.byte(EOI)
        return w.buf.copyOf(w.len)
    }

    /** JPEG's optimal Huffman table for these category counts (T.81 Annex K.2), codes up to 16 bits. */
    private fun optimalTable(counts: LongArray): HuffTable {
        val freq = counts.copyOf(257)
        freq[256] = 1   // reserves one code so that no real code is all 1-bits
        val codeSize = IntArray(257)
        val others = IntArray(257) { -1 }
        while (true) {
            var c1 = -1; var v = Long.MAX_VALUE
            for (i in 0..256) if (freq[i] != 0L && freq[i] <= v) { v = freq[i]; c1 = i }
            var c2 = -1; v = Long.MAX_VALUE
            for (i in 0..256) if (freq[i] != 0L && freq[i] <= v && i != c1) { v = freq[i]; c2 = i }
            if (c2 < 0) break
            freq[c1] += freq[c2]; freq[c2] = 0
            codeSize[c1]++
            var a = c1
            while (others[a] >= 0) { a = others[a]; codeSize[a]++ }
            others[a] = c2
            codeSize[c2]++
            var b = c2
            while (others[b] >= 0) { b = others[b]; codeSize[b]++ }
        }
        val bits = IntArray(33)
        for (i in 0..256) if (codeSize[i] > 0) bits[codeSize[i]]++
        for (i in 32 downTo 17) {
            while (bits[i] > 0) {
                var j = i - 2
                while (bits[j] == 0) j--
                bits[i] -= 2; bits[i - 1]++; bits[j + 1] += 2; bits[j]--
            }
        }
        var i = 16
        while (bits[i] == 0) i--
        bits[i]--   // drop the reserved code
        val vals = ArrayList<Int>()
        for (len in 1..32) for (s in 0..255) if (codeSize[s] == len) vals.add(s)
        return HuffTable(IntArray(17) { if (it == 0) 0 else bits[it] }, vals.toIntArray())
    }
}

/** A canonical Huffman code (JPEG DHT layout): code counts per length 1–16 and the symbols. */
internal class HuffTable(val bits: IntArray /* 1..16 */, val vals: IntArray) {
    // Decoding: canonical codes per length (T.81 Annex F.2.2.3) and a 9-bit lookahead table.
    val maxCode = IntArray(18) { -1 }
    val valPtr = IntArray(17)
    val minCode = IntArray(17)
    val look = IntArray(1 shl LOOK) { -1 }   // (len shl 8) or symbol
    // Encoding.
    val code = IntArray(17)
    val size = IntArray(17)

    init {
        var c = 0; var k = 0
        for (len in 1..16) {
            valPtr[len] = k; minCode[len] = c
            for (i in 0 until bits[len]) {
                val sym = vals[k]
                if (sym in 0..16) { code[sym] = c; size[sym] = len }
                if (len <= LOOK) {
                    val shift = LOOK - len
                    for (j in 0 until (1 shl shift)) look[(c shl shift) or j] = (len shl 8) or sym
                }
                c++; k++
            }
            maxCode[len] = if (bits[len] > 0) c - 1 else -1
            c = c shl 1
        }
        maxCode[17] = Int.MAX_VALUE
    }

    companion object { const val LOOK = 9 }
}

/** Writes bits most significant first; with [stuffing], a 0 byte follows every 0xFF (JPEG). */
internal class BitWriter(capacity: Int, private val stuffing: Boolean = true) {
    var buf = ByteArray(maxOf(capacity, 1024))
    var len = 0
    private var acc = 0L
    private var n = 0

    fun byte(b: Int) {
        if (len == buf.size) buf = buf.copyOf(buf.size + buf.size / 2 + 1024)
        buf[len++] = b.toByte()
    }

    fun put(code: Int, size: Int) {
        if (size == 0) return
        acc = (acc shl size) or (code.toLong() and ((1L shl size) - 1))
        n += size
        while (n >= 8) {
            val b = ((acc ushr (n - 8)) and 0xFF).toInt()
            byte(b); if (stuffing && b == 0xFF) byte(0)
            n -= 8
        }
        acc = acc and ((1L shl n) - 1)
    }

    /** Pads the last byte with 1-bits (T.81 F.1.2.3). */
    fun flush() { if (n > 0) put((1 shl (8 - n)) - 1, 8 - n) }
}
