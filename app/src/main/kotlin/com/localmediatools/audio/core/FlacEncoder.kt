package com.localmediatools.audio.core

import java.io.OutputStream
import java.security.MessageDigest

/**
 * A FLAC encoder (lossless): each block of [blockSize] frames is coded per channel as constant,
 * verbatim or with the fixed predictor of order 0–4 that leaves the smallest residual, the residual
 * Rice-coded in partitions; stereo picks the cheapest of left/right, left/side, side/right and
 * mid/side. The output is a complete .flac file (STREAMINFO with the samples' MD5, optional Vorbis
 * comments and picture), written to [out]; [finish] goes back to fill in the totals when [rewrite]
 * can seek.
 */
class FlacEncoder(
    private val out: OutputStream,
    private val rate: Int,
    private val channels: Int,
    private val bits: Int,
    private val blockSize: Int = 4096,
    comments: List<Pair<String, String>> = emptyList(),
    picture: Picture? = null,
) {
    class Picture(val type: Int, val mime: String, val description: String, val width: Int, val height: Int, val data: ByteArray)

    private val md5 = MessageDigest.getInstance("MD5")
    private val pending = IntArray(blockSize * channels)
    private var pendingFrames = 0
    private var frameNumber = 0L
    var totalSamples = 0L; private set
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0
    /** Where STREAMINFO starts in the file (for [finish]). */
    val streamInfoOffset = 8L
    private var written = 0L

    init {
        require(channels in 1..8 && bits in 8..24 && rate in 1..655_350)
        write("fLaC".toByteArray())
        val vendor = "Local Media Tools".toByteArray()
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        run {
            val b = java.io.ByteArrayOutputStream()
            fun le32(v: Int) { b.write(v); b.write(v shr 8); b.write(v shr 16); b.write(v shr 24) }
            le32(vendor.size); b.write(vendor)
            le32(comments.size)
            for ((k, v) in comments) { val e = "$k=$v".toByteArray(); le32(e.size); b.write(e) }
            blocks.add(4 to b.toByteArray())
        }
        picture?.let { p ->
            val b = java.io.ByteArrayOutputStream()
            fun be32(v: Int) { b.write(v ushr 24); b.write(v ushr 16); b.write(v ushr 8); b.write(v) }
            val mime = p.mime.toByteArray(); val desc = p.description.toByteArray()
            be32(p.type); be32(mime.size); b.write(mime); be32(desc.size); b.write(desc)
            be32(p.width); be32(p.height); be32(24); be32(0); be32(p.data.size); b.write(p.data)
            blocks.add(6 to b.toByteArray())
        }
        // STREAMINFO first (filled in again by finish), then the others; the last one flagged.
        writeBlock(0, streamInfo(), blocks.isEmpty())
        for ((i, blk) in blocks.withIndex()) writeBlock(blk.first, blk.second, i == blocks.size - 1)
    }

    private fun write(b: ByteArray) { out.write(b); written += b.size }

    private fun writeBlock(type: Int, data: ByteArray, last: Boolean) {
        write(byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
        write(data)
    }

    /** STREAMINFO's 34 bytes for the totals so far. */
    fun streamInfo(): ByteArray {
        val w = BitWriter()
        w.put(blockSize, 16); w.put(blockSize, 16)
        w.put(if (minFrame == Int.MAX_VALUE) 0 else minFrame, 24); w.put(maxFrame, 24)
        w.put(rate, 20); w.put(channels - 1, 3); w.put(bits - 1, 5)
        w.putLong(totalSamples, 36)
        val head = w.bytes()
        return head + (if (totalSamples > 0) md5Value ?: ByteArray(16) else ByteArray(16))
    }

    private var md5Value: ByteArray? = null

    /** Adds interleaved integer samples (in [bits] range). */
    fun add(samples: IntArray, n: Int = samples.size) {
        var i = 0
        val bytesPer = (bits + 7) / 8
        val raw = ByteArray(n * bytesPer)
        for (k in 0 until n) { var v = samples[k]; for (b in 0 until bytesPer) { raw[k * bytesPer + b] = v.toByte(); v = v shr 8 } }
        md5.update(raw)
        while (i < n) {
            val take = minOf(n - i, (blockSize - pendingFrames) * channels)
            System.arraycopy(samples, i, pending, pendingFrames * channels, take)
            pendingFrames += take / channels
            i += take
            if (pendingFrames == blockSize) { encodeFrame(pendingFrames); pendingFrames = 0 }
        }
    }

    /** Writes the last block; returns STREAMINFO with the final totals (to put at [streamInfoOffset] + 4). */
    fun finish(): ByteArray {
        if (pendingFrames > 0) { encodeFrame(pendingFrames); pendingFrames = 0 }
        md5Value = md5.digest()
        out.flush()
        return streamInfo()
    }

    private fun encodeFrame(frames: Int) {
        totalSamples += frames
        val ch = Array(channels) { c -> IntArray(frames) { pending[it * channels + c] } }
        val w = BitWriter()
        // Frame header.
        w.put(0x3FFE, 14); w.put(0, 1); w.put(0, 1)       // sync, reserved, fixed block size
        val bsCode = when (frames) { 192 -> 1; 576 -> 2; 1152 -> 3; 2304 -> 4; 4608 -> 5; 256 -> 8; 512 -> 9; 1024 -> 10; 2048 -> 11; 4096 -> 12; 8192 -> 13; 16384 -> 14; 32768 -> 15
            else -> if (frames <= 256) 6 else 7 }
        val rateCode = when (rate) { 88200 -> 1; 176400 -> 2; 192000 -> 3; 8000 -> 4; 16000 -> 5; 22050 -> 6; 24000 -> 7; 32000 -> 8; 44100 -> 9; 48000 -> 10; 96000 -> 11; else -> 0 }
        w.put(bsCode, 4); w.put(rateCode, 4)
        // Stereo: the cheapest of the four ways to code two channels.
        var assignment = channels - 1
        var subs: List<Pair<IntArray, Int>> = ch.map { it to bits }
        if (channels == 2) {
            val l = ch[0]; val r = ch[1]
            val side = IntArray(frames) { l[it] - r[it] }
            val mid = IntArray(frames) { (l[it] + r[it]) shr 1 }
            val cl = estimate(l); val cr = estimate(r); val cs = estimate(side); val cm = estimate(mid)
            val options = listOf(cl + cr to 1, cl + cs to 8, cs + cr to 9, cm + cs to 10)
            assignment = options.minBy { it.first }.second
            subs = when (assignment) { 8 -> listOf(l to bits, side to bits + 1); 9 -> listOf(side to bits + 1, r to bits); 10 -> listOf(mid to bits, side to bits + 1); else -> listOf(l to bits, r to bits) }
        }
        w.put(assignment, 4)
        w.put(when (bits) { 8 -> 1; 12 -> 2; 16 -> 4; 20 -> 5; 24 -> 6; else -> 0 }, 3); w.put(0, 1)
        utf8(w, frameNumber++)
        if (bsCode == 6) w.put(frames - 1, 8) else if (bsCode == 7) w.put(frames - 1, 16)
        // (Rate code 0: other rates are taken from STREAMINFO.)
        w.put(crc8(w.bytes()), 8)
        for ((x, b) in subs) subframe(w, x, b)
        w.align()
        val body = w.bytes()
        val crc = crc16(body)
        val frame = body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        write(frame)
        minFrame = minOf(minFrame, frame.size); maxFrame = maxOf(maxFrame, frame.size)
    }

    /** Bits a channel would take (best fixed predictor, Rice-coded), to choose the stereo coding. */
    private fun estimate(x: IntArray): Long = (0..4).minOf { o -> riceCost(residual(x, o)) }

    private fun residual(x: IntArray, order: Int): IntArray {
        val n = x.size
        if (n <= order) return IntArray(0)
        return IntArray(n - order) { j ->
            val i = j + order
            when (order) {
                0 -> x[i]
                1 -> x[i] - x[i - 1]
                2 -> x[i] - 2 * x[i - 1] + x[i - 2]
                3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
                else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
            }
        }
    }

    private fun subframe(w: BitWriter, x: IntArray, bps: Int) {
        val n = x.size
        if (x.all { it == x[0] }) { w.put(0, 1); w.put(0, 6); w.put(0, 1); w.putSigned(x[0], bps); return }
        var bestOrder = -1; var bestCost = n.toLong() * bps
        for (o in 0..minOf(4, n - 1)) {
            val c = o.toLong() * bps + riceCost(residual(x, o)) + 6
            if (c < bestCost) { bestCost = c; bestOrder = o }
        }
        if (bestOrder < 0) {
            w.put(0, 1); w.put(1, 6); w.put(0, 1)
            for (v in x) w.putSigned(v, bps)
            return
        }
        w.put(0, 1); w.put(8 or bestOrder, 6); w.put(0, 1)
        for (i in 0 until bestOrder) w.putSigned(x[i], bps)
        rice(w, residual(x, bestOrder), n, bestOrder)
    }

    /** About how many bits Rice coding takes for [r] in one partition (from the mean size of the values). */
    private fun riceCost(r: IntArray): Long {
        if (r.isEmpty()) return 0
        var sum = 0L
        for (v in r) sum += zig(v)
        val k = kFor(sum, r.size)
        return (sum ushr k) + r.size.toLong() * (1 + k) + 6
    }

    /** The Rice parameter for values summing to [sum] over [len] (log2 of their mean). */
    private fun kFor(sum: Long, len: Int): Int {
        if (len == 0) return 0
        val mean = sum / len
        var k = 0
        while (k < 14 && (1L shl (k + 1)) <= mean) k++
        return k
    }

    /** The exact cost of [len] values from [at] with parameter [k]. */
    private fun cost(r: IntArray, at: Int, len: Int, k: Int): Long {
        var c = len.toLong() * (1 + k)
        for (i in at until at + len) c += zig(r[i]) ushr k
        return c
    }

    private fun zig(v: Int): Long = if (v >= 0) 2L * v else -2L * v - 1

    private fun rice(w: BitWriter, r: IntArray, n: Int, order: Int) {
        // Up to 2^8 partitions while each holds at least 16 samples and n divides evenly.
        var bestP = 0; var bestCost = Long.MAX_VALUE; var bestKs = IntArray(1)
        var p = 0
        while (p <= 8 && (n shr p) > order && (n shr p) shl p == n && (p == 0 || (n shr p) >= 16)) {
            val parts = 1 shl p
            val ks = IntArray(parts)
            var cost = 0L
            var at = 0
            for (q in 0 until parts) {
                val len = (n shr p) - if (q == 0) order else 0
                var sum = 0L
                for (i in at until at + len) sum += zig(r[i])
                // The best parameter is next to log2 of the mean: try it and its neighbours.
                val k0 = kFor(sum, len)
                var bc = Long.MAX_VALUE; var bk = 0
                for (k in maxOf(0, k0 - 1)..minOf(14, k0 + 1)) {
                    val c = cost(r, at, len, k)
                    if (c < bc) { bc = c; bk = k }
                }
                ks[q] = bk; cost += bc + 4; at += len
            }
            if (cost < bestCost) { bestCost = cost; bestP = p; bestKs = ks }
            p++
        }
        w.put(0, 2)              // Rice coding with 4-bit parameters
        w.put(bestP, 4)
        var at = 0
        for (q in 0 until (1 shl bestP)) {
            val len = (n shr bestP) - if (q == 0) order else 0
            val k = bestKs[q]
            w.put(k, 4)
            for (i in at until at + len) {
                val u = zig(r[i])
                w.unary(u ushr k)
                if (k > 0) w.putLong(u and ((1L shl k) - 1), k)
            }
            at += len
        }
    }

    private fun utf8(w: BitWriter, v: Long) {
        when {
            v < 0x80 -> w.put(v.toInt(), 8)
            v < 0x800 -> { w.put(0xC0 or (v shr 6).toInt(), 8); w.put(0x80 or (v and 0x3F).toInt(), 8) }
            v < 0x10000 -> { w.put(0xE0 or (v shr 12).toInt(), 8); w.put(0x80 or ((v shr 6) and 0x3F).toInt(), 8); w.put(0x80 or (v and 0x3F).toInt(), 8) }
            v < 0x200000 -> { w.put(0xF0 or (v shr 18).toInt(), 8); for (s in intArrayOf(12, 6, 0)) w.put(0x80 or ((v shr s) and 0x3F).toInt(), 8) }
            v < 0x4000000 -> { w.put(0xF8 or (v shr 24).toInt(), 8); for (s in intArrayOf(18, 12, 6, 0)) w.put(0x80 or ((v shr s) and 0x3F).toInt(), 8) }
            else -> { w.put(0xFC or (v shr 30).toInt(), 8); for (s in intArrayOf(24, 18, 12, 6, 0)) w.put(0x80 or ((v shr s) and 0x3F).toInt(), 8) }
        }
    }

    companion object {
        fun crc8(b: ByteArray): Int {
            var c = 0
            for (x in b) { c = c xor (x.toInt() and 0xFF); repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF } }
            return c
        }

        fun crc16(b: ByteArray): Int {
            var c = 0
            for (x in b) { c = c xor ((x.toInt() and 0xFF) shl 8); repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF } }
            return c
        }
    }
}

/** Writes bits most significant first. */
class BitWriter {
    private var buf = ByteArray(1 shl 14)
    private var bytes = 0
    private var acc = 0L
    private var nbits = 0

    fun put(v: Int, n: Int) = putLong(v.toLong(), n)

    fun putLong(v: Long, n: Int) {
        var left = n
        while (left > 0) {
            val take = minOf(left, 32)
            left -= take
            acc = (acc shl take) or ((v ushr left) and ((1L shl take) - 1))
            nbits += take
            while (nbits >= 8) { nbits -= 8; byte((acc ushr nbits).toInt()) }
            acc = acc and ((1L shl nbits) - 1)
        }
    }

    fun putSigned(v: Int, n: Int) = putLong(v.toLong() and ((1L shl n) - 1), n)

    fun unary(q: Long) {
        var z = q
        while (z >= 32) { put(0, 32); z -= 32 }
        put(1, z.toInt() + 1)
    }

    fun align() { if (nbits > 0) put(0, 8 - nbits) }

    private fun byte(v: Int) {
        if (bytes == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[bytes++] = v.toByte()
    }

    /** The whole bytes written so far. */
    fun bytes(): ByteArray = buf.copyOf(bytes)
}
