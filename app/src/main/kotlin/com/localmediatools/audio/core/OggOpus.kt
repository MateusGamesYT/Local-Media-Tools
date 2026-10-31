package com.localmediatools.audio.core

import java.io.OutputStream

/**
 * Writes Opus packets (from an encoder) as an .opus file: an Ogg stream (RFC 3533) with the
 * OpusHead and OpusTags headers (RFC 7845). Each page holds whole packets; its granule position is
 * the 48 kHz sample count at the end of its last packet, counted from the start including the
 * encoder's [preSkip] samples, so players drop the encoder's delay and, via [finish], the padding
 * at the end.
 */
class OggOpusWriter(
    private val out: OutputStream,
    private val channels: Int,
    private val preSkip: Int,
    inputRate: Int,
    tags: List<Pair<String, String>> = emptyList(),
    vendor: String = "Local Media Tools",
    private val serial: Int = 0x4C4D5431,
) {
    private var sequence = 0
    private var granule = 0L
    private val segments = java.io.ByteArrayOutputStream()
    private val lacing = ArrayList<Int>()
    private var pageGranule = -1L
    var bytesWritten = 0L; private set

    init {
        require(channels in 1..2)
        val head = java.io.ByteArrayOutputStream()
        head.write("OpusHead".toByteArray()); head.write(1); head.write(channels)
        le16(head, preSkip); le32(head, inputRate.toLong()); le16(head, 0); head.write(0)      // gain 0, mapping family 0
        page(listOf(head.toByteArray()), 0, BOS)
        val t = java.io.ByteArrayOutputStream()
        val v = vendor.toByteArray()
        t.write("OpusTags".toByteArray()); le32(t, v.size.toLong()); t.write(v)
        le32(t, tags.size.toLong())
        for ((k, value) in tags) { val e = "$k=$value".toByteArray(); le32(t, e.size.toLong()); t.write(e) }
        // Long tags (a cover picture) span pages: a packet continues while its last segment is 255.
        writeLong(t.toByteArray())
        granule = 0
    }

    /** Adds one Opus packet (its duration is read from its first byte). */
    fun addPacket(packet: ByteArray) {
        val n = packet.size / 255 + 1
        if (lacing.size + n > 255) flushPage(0)
        var left = packet.size
        while (left >= 255) { lacing.add(255); left -= 255 }
        lacing.add(left)
        segments.write(packet)
        granule += samples(packet)
        pageGranule = granule
        if (segments.size() >= 4096) flushPage(0)
    }

    /**
     * Ends the stream. [samples48k]: the real length of the audio at 48 kHz (the last page's granule
     * position then marks where it ends, so players drop the encoder's padding).
     */
    fun finish(samples48k: Long? = null) {
        if (samples48k != null) pageGranule = preSkip + samples48k
        else if (pageGranule < 0) pageGranule = granule
        flushPage(EOS)
        out.flush()
    }

    private fun flushPage(flags: Int) {
        if (lacing.isEmpty() && flags and EOS == 0) return
        writePage(lacing.toIntArray(), segments.toByteArray(), if (pageGranule < 0) granule else pageGranule, flags)
        lacing.clear(); segments.reset()
    }

    private fun page(packets: List<ByteArray>, granulePos: Long, flags: Int) {
        val lace = ArrayList<Int>(); val data = java.io.ByteArrayOutputStream()
        for (p in packets) { var left = p.size; while (left >= 255) { lace.add(255); left -= 255 }; lace.add(left); data.write(p) }
        writePage(lace.toIntArray(), data.toByteArray(), granulePos, flags)
    }

    private fun writeLong(packet: ByteArray) {
        var at = 0
        var continued = false
        while (true) {
            val lace = ArrayList<Int>()
            val start = at
            var done = false
            while (lace.size < 255) {
                val left = packet.size - at
                if (left >= 255) { lace.add(255); at += 255 } else { lace.add(left); at += left; done = true; break }
            }
            writePage(lace.toIntArray(), packet.copyOfRange(start, at), if (done) 0 else -1, if (continued) CONTINUED else 0)
            if (done) return
            continued = true
        }
    }

    private fun writePage(lace: IntArray, data: ByteArray, granulePos: Long, flags: Int) {
        val h = ByteArray(27 + lace.size)
        "OggS".toByteArray().copyInto(h)
        h[4] = 0; h[5] = flags.toByte()
        for (k in 0 until 8) h[6 + k] = (granulePos ushr (8 * k)).toByte()
        for (k in 0 until 4) h[14 + k] = (serial ushr (8 * k)).toByte()
        for (k in 0 until 4) h[18 + k] = (sequence ushr (8 * k)).toByte()
        h[26] = lace.size.toByte()
        for (i in lace.indices) h[27 + i] = lace[i].toByte()
        var crc = 0
        crc = crc(crc, h, h.size); crc = crc(crc, data, data.size)
        for (k in 0 until 4) h[22 + k] = (crc ushr (8 * k)).toByte()
        out.write(h); out.write(data)
        bytesWritten += h.size + data.size
        sequence++
    }

    companion object {
        private const val CONTINUED = 1
        private const val BOS = 2
        private const val EOS = 4

        /** An Opus packet's length in 48 kHz samples, from its table-of-contents byte (RFC 6716 §3.1). */
        fun samples(packet: ByteArray): Int {
            if (packet.isEmpty()) return 0
            val toc = packet[0].toInt() and 0xFF
            val config = toc shr 3
            val frame = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]   // SILK: 10, 20, 40, 60 ms
                config < 16 -> intArrayOf(480, 960)[config and 1]               // hybrid: 10, 20 ms
                else -> intArrayOf(120, 240, 480, 960)[config and 3]            // CELT: 2.5, 5, 10, 20 ms
            }
            val count = when (toc and 3) { 0 -> 1; 1, 2 -> 2; else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 0 }
            return frame * count
        }

        private val TABLE = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

        /** Ogg's CRC-32 (polynomial 0x04C11DB7, not reflected, starting at 0). */
        fun crc(start: Int, b: ByteArray, n: Int): Int {
            var c = start
            for (i in 0 until n) c = (c shl 8) xor TABLE[((c ushr 24) xor (b[i].toInt() and 0xFF)) and 0xFF]
            return c
        }

        private fun le16(o: java.io.ByteArrayOutputStream, v: Int) { o.write(v); o.write(v shr 8) }
        private fun le32(o: java.io.ByteArrayOutputStream, v: Long) { for (k in 0 until 4) o.write((v shr (8 * k)).toInt()) }
    }
}
