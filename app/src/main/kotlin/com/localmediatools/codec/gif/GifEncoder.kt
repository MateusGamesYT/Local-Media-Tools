package com.localmediatools.codec.gif

import java.io.OutputStream

/** Thrown when a frame cannot be represented in GIF without altering pixels. */
class GifUnrepresentableException(message: String) : IllegalArgumentException(message)

/**
 * Low-level GIF89a writer: header, optional global colour table, loop extension, frames.
 * Palettes are given as ARGB; entries are padded to the next power of two.
 */
class GifEncoder(
    private val out: OutputStream,
    val width: Int,
    val height: Int,
    globalPalette: IntArray?,
    loopCount: Int?, // null = play once, 0 = forever
) {
    private val globalSizeBits: Int
    private var finished = false

    init {
        require(width in 1..65535 && height in 1..65535) { "GIF supports at most 65535 x 65535 pixels" }
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        writeU16(width); writeU16(height)
        if (globalPalette != null) {
            require(globalPalette.size in 1..256)
            globalSizeBits = tableBits(globalPalette.size)
            out.write(0x80 or 0x70 or (globalSizeBits - 1)) // global table, 8-bit colour resolution
            out.write(0) // background index
            out.write(0) // aspect
            writePalette(globalPalette, globalSizeBits)
        } else {
            globalSizeBits = 0
            out.write(0x70); out.write(0); out.write(0)
        }
        if (loopCount != null) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
            out.write("NETSCAPE2.0".toByteArray(Charsets.ISO_8859_1))
            out.write(byteArrayOf(3, 1, (loopCount and 0xFF).toByte(), ((loopCount shr 8) and 0xFF).toByte(), 0))
        }
    }

    /**
     * Writes one frame. [indices] holds w*h palette indices for the frame rectangle.
     * [localPalette] null means the global palette is used.
     */
    fun writeFrame(
        indices: ByteArray, x: Int, y: Int, w: Int, h: Int,
        localPalette: IntArray?, transparentIndex: Int, delayCs: Int, disposal: Int,
    ) {
        check(!finished)
        require(w > 0 && h > 0 && x >= 0 && y >= 0 && x + w <= width && y + h <= height) { "Frame outside canvas" }
        // Graphic control extension.
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
        val packed = ((disposal and 7) shl 2) or (if (transparentIndex >= 0) 1 else 0)
        out.write(packed)
        writeU16(delayCs.coerceIn(0, 65535))
        out.write(if (transparentIndex >= 0) transparentIndex else 0)
        out.write(0)
        // Image descriptor.
        out.write(0x2C)
        writeU16(x); writeU16(y); writeU16(w); writeU16(h)
        val bits: Int
        if (localPalette != null) {
            require(localPalette.size in 1..256)
            bits = tableBits(localPalette.size)
            out.write(0x80 or (bits - 1))
            writePalette(localPalette, bits)
        } else {
            require(globalSizeBits > 0) { "No global palette" }
            bits = globalSizeBits
            out.write(0)
        }
        val minCodeSize = maxOf(2, bits)
        out.write(minCodeSize)
        LzwEncoder(minCodeSize).encode(indices, w * h, out)
        out.write(0) // block terminator
    }

    fun finish() {
        if (finished) return
        out.write(0x3B)
        out.flush()
        finished = true
    }

    private fun writePalette(p: IntArray, bits: Int) {
        val n = 1 shl bits
        val b = ByteArray(n * 3)
        for (i in p.indices) {
            val c = p[i]
            b[i * 3] = (c shr 16).toByte(); b[i * 3 + 1] = (c shr 8).toByte(); b[i * 3 + 2] = c.toByte()
        }
        out.write(b)
    }

    private fun writeU16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    companion object {
        fun tableBits(size: Int): Int {
            var bits = 1
            while ((1 shl bits) < size) bits++
            return bits
        }
    }
}

/** GIF-flavoured LZW compressor (variable code width, clear on full table, 255-byte sub-blocks). */
class LzwEncoder(private val minCodeSize: Int) {
    private val hashSize = 9973
    private val hashKeys = IntArray(hashSize)
    private val hashCodes = IntArray(hashSize)
    private val block = ByteArray(256)
    private var blockLen = 0
    private var acc = 0
    private var accBits = 0
    private lateinit var sink: OutputStream

    fun encode(pixels: ByteArray, count: Int, out: OutputStream) {
        sink = out
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var next = clear + 2
        java.util.Arrays.fill(hashKeys, -1)
        emit(clear, codeSize)
        if (count == 0) {
            emit(eoi, codeSize); flushBits(); return
        }
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until count) {
            val c = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var h = ((c shl 12) xor prefix) % hashSize
            if (h < 0) h += hashSize
            var found = -1
            while (hashKeys[h] != -1) {
                if (hashKeys[h] == key) { found = hashCodes[h]; break }
                h++
                if (h == hashSize) h = 0
            }
            if (found >= 0) {
                prefix = found
                continue
            }
            emit(prefix, codeSize)
            if (next < 4096) {
                hashKeys[h] = key
                hashCodes[h] = next
                // The decoder grows its code width once `next` reaches 2^codeSize.
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                next++
            } else {
                emit(clear, codeSize)
                java.util.Arrays.fill(hashKeys, -1)
                codeSize = minCodeSize + 1
                next = clear + 2
            }
            prefix = c
        }
        emit(prefix, codeSize)
        emit(eoi, codeSize)
        flushBits()
    }

    private fun emit(code: Int, size: Int) {
        acc = acc or (code shl accBits)
        accBits += size
        while (accBits >= 8) {
            byte(acc and 0xFF)
            acc = acc ushr 8
            accBits -= 8
        }
    }

    private fun flushBits() {
        if (accBits > 0) {
            byte(acc and 0xFF)
            acc = 0; accBits = 0
        }
        if (blockLen > 0) {
            sink.write(blockLen)
            sink.write(block, 0, blockLen)
            blockLen = 0
        }
    }

    private fun byte(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == 255) {
            sink.write(255)
            sink.write(block, 0, 255)
            blockLen = 0
        }
    }
}
