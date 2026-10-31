package com.localmediatools.audio.core

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Sample formats of the WAV files the app writes. */
enum class PcmFormat(val bits: Int, val float: Boolean) { S16(16, false), S24(24, false), F32(32, true) }

/**
 * Float samples (−1…1) to integers of [bits] bits, with TPDF dither (two uniform random values, ±1
 * least significant bit in all) when [dither]: the rounding error becomes a steady, very quiet hiss
 * instead of distortion that follows the music. Values beyond full scale are clipped.
 */
class Quantizer(private val bits: Int, private val dither: Boolean) {
    private val scale = 2.0.pow(bits - 1)
    private val maxV = scale - 1
    private var seed = 0x2545F491L

    private fun uniform(): Double {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
        return ((seed ushr 11) and 0xFFFFF).toDouble() / 0x100000 - 0.5
    }

    fun quantize(x: Float): Int {
        var v = x * scale
        if (dither) v += uniform() + uniform()
        return floor(v + 0.5).coerceIn(-scale, maxV).toInt()
    }
}

/**
 * A WAV writer: interleaved float samples as 16- or 24-bit PCM (16-bit dithered) or 32-bit float.
 * The header goes first with the sizes left open; [finish] gives the final header, to be written
 * over the first [headerSize] bytes. WAV can't hold more than 4 GiB of samples.
 */
class WavWriter(private val out: OutputStream, val rate: Int, val channels: Int, val format: PcmFormat) {
    init { require(channels in 1..2 && rate > 0) }
    private val quant = if (format.float) null else Quantizer(format.bits, dither = format == PcmFormat.S16)
    private val bytesPerSample = format.bits / 8
    var frames = 0L; private set
    private var buf = ByteArray(0)
    val headerSize: Int get() = header(0).size

    init { out.write(header(0)) }

    fun add(samples: FloatArray, n: Int = samples.size) {
        val bytes = n * bytesPerSample
        if ((frames * channels + n) * bytesPerSample > MAX_DATA) throw IllegalStateException("WAV files can't hold more than 4 GB")
        if (buf.size < bytes) buf = ByteArray(bytes)
        var p = 0
        for (i in 0 until n) {
            when (format) {
                PcmFormat.S16 -> { val v = quant!!.quantize(samples[i]); buf[p++] = v.toByte(); buf[p++] = (v shr 8).toByte() }
                PcmFormat.S24 -> { val v = quant!!.quantize(samples[i]); buf[p++] = v.toByte(); buf[p++] = (v shr 8).toByte(); buf[p++] = (v shr 16).toByte() }
                PcmFormat.F32 -> { val v = java.lang.Float.floatToRawIntBits(samples[i]); buf[p++] = v.toByte(); buf[p++] = (v shr 8).toByte(); buf[p++] = (v shr 16).toByte(); buf[p++] = (v shr 24).toByte() }
            }
        }
        out.write(buf, 0, bytes)
        frames += n / channels
    }

    /** The header with the final sizes. */
    fun finish(): ByteArray = header(frames)

    private fun header(frames: Long): ByteArray {
        val data = frames * channels * bytesPerSample
        val b = java.io.ByteArrayOutputStream()
        fun le16(v: Int) { b.write(v); b.write(v shr 8) }
        fun le32(v: Long) { b.write(v.toInt()); b.write((v shr 8).toInt()); b.write((v shr 16).toInt()); b.write((v shr 24).toInt()) }
        val fmtLen = if (format.float) 18 else 16
        val factLen = if (format.float) 12 else 0
        b.write("RIFF".toByteArray()); le32(4 + 8 + fmtLen + factLen + 8 + data + (data and 1)); b.write("WAVE".toByteArray())
        b.write("fmt ".toByteArray()); le32(fmtLen.toLong())
        le16(if (format.float) 3 else 1); le16(channels); le32(rate.toLong()); le32(rate.toLong() * channels * bytesPerSample)
        le16(channels * bytesPerSample); le16(format.bits)
        if (format.float) { le16(0); b.write("fact".toByteArray()); le32(4); le32(frames) }
        b.write("data".toByteArray()); le32(data)
        return b.toByteArray()
    }

    companion object { const val MAX_DATA = 0xFFFFFFFFL - 64 }
}

/**
 * Reads uncompressed audio files: WAV (and RF64; 8-, 16-, 24- and 32-bit integers, 32- and 64-bit
 * floats, plain or "extensible") and AIFF/AIFF-C (integers, little-endian "sowt", floats). [open]
 * returns null for anything else (compressed WAV, other files), which the caller decodes otherwise.
 * [frames] is −1 when the file doesn't say (it is then read to its end).
 */
class PcmFileReader private constructor(
    private val inp: InputStream, val rate: Int, val channels: Int, val bits: Int,
    private val float: Boolean, private val bigEndian: Boolean, private val unsigned8: Boolean, val frames: Long,
) {
    private val bytesPerSample = bits / 8
    private var left = if (frames >= 0) frames else Long.MAX_VALUE
    private var raw = ByteArray(0)

    /** Reads up to dst.size / channels frames into [dst] (interleaved, −1…1); returns the frames read, 0 at the end. */
    fun read(dst: FloatArray): Int {
        val want = min(left, (dst.size / channels).toLong()).toInt()
        if (want <= 0) return 0
        val bytes = want * channels * bytesPerSample
        if (raw.size < bytes) raw = ByteArray(bytes)
        var got = 0
        while (got < bytes) { val r = inp.read(raw, got, bytes - got); if (r < 0) break; got += r }
        val n = got / (channels * bytesPerSample)
        var p = 0
        for (i in 0 until n * channels) {
            dst[i] = sample(p); p += bytesPerSample
        }
        left -= n
        if (n < want) left = 0
        return n
    }

    private fun b(i: Int) = raw[i].toInt() and 0xFF

    private fun sample(p: Int): Float {
        if (float) {
            return if (bits == 32) {
                val v = if (bigEndian) (b(p) shl 24) or (b(p + 1) shl 16) or (b(p + 2) shl 8) or b(p + 3) else (b(p + 3) shl 24) or (b(p + 2) shl 16) or (b(p + 1) shl 8) or b(p)
                java.lang.Float.intBitsToFloat(v)
            } else {
                var v = 0L
                for (k in 0 until 8) v = v or ((b(if (bigEndian) p + 7 - k else p + k).toLong()) shl (8 * k))
                java.lang.Double.longBitsToDouble(v).toFloat()
            }
        }
        return when (bits) {
            8 -> if (unsigned8) (b(p) - 128) / 128f else raw[p] / 128f
            16 -> (if (bigEndian) ((raw[p].toInt() shl 8) or b(p + 1)) else ((raw[p + 1].toInt() shl 8) or b(p))) / 32768f
            24 -> (if (bigEndian) ((raw[p].toInt() shl 16) or (b(p + 1) shl 8) or b(p + 2)) else ((raw[p + 2].toInt() shl 16) or (b(p + 1) shl 8) or b(p))) / 8388608f
            else -> ((if (bigEndian) (b(p) shl 24) or (b(p + 1) shl 16) or (b(p + 2) shl 8) or b(p + 3) else (b(p + 3) shl 24) or (b(p + 2) shl 16) or (b(p + 1) shl 8) or b(p)) / 2147483648.0).toFloat()
        }
    }

    companion object {
        fun open(inp: InputStream): PcmFileReader? = try { parse(inp) } catch (_: EOFException) { null }

        private fun parse(inp: InputStream): PcmFileReader? {
            val head = readN(inp, 12)
            val id = String(head, 0, 4, Charsets.ISO_8859_1)
            val form = String(head, 8, 4, Charsets.ISO_8859_1)
            return when {
                (id == "RIFF" || id == "RF64") && form == "WAVE" -> wav(inp, id == "RF64")
                id == "FORM" && (form == "AIFF" || form == "AIFC") -> aiff(inp, form == "AIFC")
                else -> null
            }
        }

        private fun wav(inp: InputStream, rf64: Boolean): PcmFileReader? {
            var tag = 0; var ch = 0; var rate = 0; var bits = 0; var block = 0
            var ds64Data = -1L
            while (true) {
                val h = readN(inp, 8)
                val cid = String(h, 0, 4, Charsets.ISO_8859_1)
                val len = le32(h, 4)
                when (cid) {
                    "ds64" -> { val d = readN(inp, len.toInt()); ds64Data = le64(d, 8); if (len and 1L == 1L) skip(inp, 1) }
                    "fmt " -> {
                        val f = readN(inp, len.toInt())
                        tag = le16(f, 0); ch = le16(f, 2); rate = le32(f, 4).toInt(); block = le16(f, 12); bits = le16(f, 14)
                        if (tag == 0xFFFE && f.size >= 26) tag = le16(f, 24)        // extensible: the sub-format's tag
                        if (len and 1L == 1L) skip(inp, 1)
                    }
                    "data" -> {
                        if (ch !in 1..8 || rate <= 0) return null
                        val float = tag == 3
                        if (!(tag == 1 && bits in setOf(8, 16, 24, 32)) && !(float && (bits == 32 || bits == 64))) return null
                        if (block != ch * bits / 8) return null
                        val size = if (rf64 && ds64Data >= 0 && len == 0xFFFFFFFFL) ds64Data else len
                        val frames = if (size == 0L || size == 0xFFFFFFFFL) -1L else size / block
                        return PcmFileReader(inp, rate, ch, bits, float, false, true, frames)
                    }
                    else -> skip(inp, len + (len and 1L))
                }
            }
        }

        private fun aiff(inp: InputStream, aifc: Boolean): PcmFileReader? {
            var ch = 0; var frames = 0L; var bits = 0; var rate = 0.0
            var float = false; var little = false
            while (true) {
                val h = readN(inp, 8)
                val cid = String(h, 0, 4, Charsets.ISO_8859_1)
                val len = be32(h, 4)
                when (cid) {
                    "COMM" -> {
                        val c = readN(inp, len.toInt())
                        ch = be16(c, 0); frames = be32(c, 2); bits = be16(c, 6); rate = extended(c, 8)
                        if (aifc && c.size >= 22) {
                            when (String(c, 18, 4, Charsets.ISO_8859_1)) {
                                "NONE", "twos" -> {}
                                "sowt" -> little = true
                                "fl32", "FL32" -> { float = true; bits = 32 }
                                "fl64", "FL64" -> { float = true; bits = 64 }
                                else -> return null
                            }
                        }
                        if (len and 1L == 1L) skip(inp, 1)
                    }
                    "SSND" -> {
                        if (ch !in 1..8 || rate <= 0) return null
                        val s = readN(inp, 8)
                        skip(inp, be32(s, 0))
                        val stored = ((bits + 7) / 8) * 8
                        if (!float && stored !in setOf(8, 16, 24, 32)) return null
                        // AIFF stores samples left-justified in whole bytes: reading them as the stored width keeps the level.
                        return PcmFileReader(inp, rate.toInt(), ch, if (float) bits else stored, float, !little, false, frames)
                    }
                    else -> skip(inp, len + (len and 1L))
                }
            }
        }

        /** An 80-bit IEEE extended number (AIFF's sample rate). */
        private fun extended(b: ByteArray, at: Int): Double {
            val exp = ((b[at].toInt() and 0x7F) shl 8) or (b[at + 1].toInt() and 0xFF)
            var mant = 0L
            for (k in 0 until 8) mant = (mant shl 8) or (b[at + 2 + k].toLong() and 0xFF)
            if (exp == 0 && mant == 0L) return 0.0
            // value = mantissa (64 bits, integer bit included) × 2^(exponent − 16383 − 63)
            val m = (mant ushr 1).toDouble() * 2.0
            val v = m * 2.0.pow(exp - 16383 - 63)
            return if (b[at].toInt() and 0x80 != 0) -v else v
        }

        private fun readN(inp: InputStream, n: Int): ByteArray {
            val b = ByteArray(max(0, n)); var got = 0
            while (got < n) { val r = inp.read(b, got, n - got); if (r < 0) throw EOFException(); got += r }
            return b
        }
        private fun skip(inp: InputStream, n: Long) {
            var left = n
            while (left > 0) {
                val s = inp.skip(left)
                if (s <= 0) { if (inp.read() < 0) throw EOFException(); left-- } else left -= s
            }
        }
        private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, at: Int) = (le16(b, at).toLong()) or (le16(b, at + 2).toLong() shl 16)
        private fun le64(b: ByteArray, at: Int) = le32(b, at) or (le32(b, at + 4) shl 32)
        private fun be16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        private fun be32(b: ByteArray, at: Int) = (be16(b, at).toLong() shl 16) or be16(b, at + 2).toLong()
    }
}
