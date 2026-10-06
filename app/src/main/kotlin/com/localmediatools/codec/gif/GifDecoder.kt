package com.localmediatools.codec.gif

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class GifFormatException(message: String, val framesDecoded: Int = 0) : IOException(message)

/** One decoded frame. [canvas] is the fully composited image a viewer shows (shared buffer). */
class GifFrame(
    val index: Int,
    val canvas: IntArray,
    /** Delay exactly as stored in the file, in 1/100 s. */
    val rawDelayCs: Int,
    val left: Int, val top: Int, val width: Int, val height: Int,
    val disposal: Int,
    val transparentIndex: Int,
    val usedLocalPalette: Boolean,
) {
    /** Delay as browsers play it: values below 2 cs are shown for 10 cs. */
    val effectiveDelayCs: Int get() = if (rawDelayCs < 2) 10 else rawDelayCs
}

/**
 * Streaming GIF decoder producing fully composited frames (disposal methods 0–3, transparency,
 * interlacing, local/global palettes). Only the canvas (and a restore buffer for disposal 3) is held
 * in memory, so long animations can be processed frame by frame.
 */
class GifDecoder(input: InputStream, maxCanvasPixels: Long = 64L * 1024 * 1024) {
    private val ins = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
    val width: Int
    val height: Int
    /** NETSCAPE loop count: 0 = forever, null = no loop extension (plays once). */
    var loopCount: Int? = null; private set
    private val globalPalette: IntArray?
    private val canvas: IntArray
    private var restore: IntArray? = null
    private var frameIndex = 0
    private var pendingDisposal = 0
    private var pendingRect = IntArray(4)
    private var done = false
    var sawTrailer = false; private set

    init {
        val sig = String(readBytes(6), Charsets.ISO_8859_1)
        if (sig != "GIF87a" && sig != "GIF89a") throw GifFormatException("Not a GIF file")
        width = u16()
        height = u16()
        if (width <= 0 || height <= 0) throw GifFormatException("GIF has an empty canvas")
        if (width.toLong() * height > maxCanvasPixels) {
            throw GifFormatException("GIF canvas ${width}x$height is too large to process on this device")
        }
        val flags = u8()
        u8() // background colour index (viewers treat the initial canvas as transparent)
        u8() // pixel aspect ratio
        globalPalette = if (flags and 0x80 != 0) readPalette(2 shl (flags and 7)) else null
        canvas = IntArray(width * height)
    }

    /** Decodes the next frame, or returns null at the end of the stream. */
    fun nextFrame(): GifFrame? {
        if (done) return null
        var delay = 0
        var disposal = 0
        var transparent = -1
        while (true) {
            val b = ins.read()
            try {
            when (b) {
                -1 -> { done = true; return null } // missing trailer after complete frames: tolerated
                0x3B -> { done = true; sawTrailer = true; return null }
                0x21 -> {
                    val label = u8()
                    if (label == 0xF9) {
                        val size = u8()
                        val block = readBytes(size)
                        if (size >= 4) {
                            val packed = block[0].toInt() and 0xFF
                            disposal = (packed shr 2) and 7
                            delay = (block[1].toInt() and 0xFF) or ((block[2].toInt() and 0xFF) shl 8)
                            transparent = if (packed and 1 != 0) block[3].toInt() and 0xFF else -1
                        }
                        skipSubBlocks()
                    } else if (label == 0xFF) {
                        val size = u8()
                        val app = String(readBytes(size), Charsets.ISO_8859_1)
                        if (app.startsWith("NETSCAPE2.0") || app.startsWith("ANIMEXTS1.0")) {
                            while (true) {
                                val n = u8()
                                if (n == 0) break
                                val sub = readBytes(n)
                                if (n >= 3 && sub[0].toInt() == 1) {
                                    loopCount = (sub[1].toInt() and 0xFF) or ((sub[2].toInt() and 0xFF) shl 8)
                                }
                            }
                        } else {
                            skipSubBlocks()
                        }
                    } else {
                        skipSubBlocks()
                    }
                }
                0x2C -> return readImage(delay, disposal, transparent)
                0x00 -> continue // stray padding seen in some encoders
                else -> throw GifFormatException("Corrupt GIF block (0x${Integer.toHexString(b)}) after frame $frameIndex", frameIndex)
            }
            } catch (e: EOFException) {
                if (b == 0x2C) throw GifFormatException("GIF is truncated inside frame ${frameIndex + 1}", frameIndex)
                // The file ends inside trailing metadata after complete frames: nothing is lost.
                done = true
                return null
            }
        }
    }

    private fun readImage(delay: Int, disposal: Int, transparent: Int): GifFrame {
        applyPendingDisposal()
        val fx = u16(); val fy = u16(); val fw = u16(); val fh = u16()
        val flags = u8()
        val local = if (flags and 0x80 != 0) readPalette(2 shl (flags and 7)) else null
        val interlaced = flags and 0x40 != 0
        val palette = local ?: globalPalette ?: throw GifFormatException("GIF frame $frameIndex has no colour table", frameIndex)
        val minCodeSize = u8()
        if (minCodeSize < 1 || minCodeSize > 11) throw GifFormatException("GIF frame $frameIndex has invalid LZW code size", frameIndex)

        if (disposal == 3) {
            restore = canvas.copyOf()
        }
        val pixels = fw.toLong() * fh
        if (pixels > Int.MAX_VALUE / 2) throw GifFormatException("GIF frame $frameIndex is too large", frameIndex)
        val indices = ByteArray(pixels.toInt())
        val produced = try {
            lzwDecode(minCodeSize, indices)
        } catch (e: EOFException) {
            throw GifFormatException("GIF is truncated inside frame ${frameIndex + 1}", frameIndex)
        }
        if (produced < indices.size && produced < indices.size - fw) {
            // Some encoders end a frame a few pixels early; anything more is real corruption.
            throw GifFormatException("GIF frame ${frameIndex + 1} is missing image data", frameIndex)
        }
        // Composite.
        val rowOrder = if (interlaced) interlaceRows(fh) else null
        for (r in 0 until fh) {
            val y = fy + (rowOrder?.get(r) ?: r)
            if (y < 0 || y >= height) continue
            val srcBase = r * fw
            val dstBase = y * width
            for (c in 0 until fw) {
                val x = fx + c
                if (x >= width || srcBase + c >= produced) break
                val idx = indices[srcBase + c].toInt() and 0xFF
                if (idx == transparent) continue
                val color = if (idx < palette.size) palette[idx] else 0xFF000000.toInt()
                canvas[dstBase + x] = color
            }
        }
        pendingDisposal = disposal
        pendingRect[0] = fx; pendingRect[1] = fy; pendingRect[2] = fw; pendingRect[3] = fh
        val frame = GifFrame(frameIndex, canvas, delay, fx, fy, fw, fh, disposal, transparent, local != null)
        frameIndex++
        return frame
    }

    private fun applyPendingDisposal() {
        if (frameIndex == 0) return
        when (pendingDisposal) {
            2 -> {
                val (fx, fy, fw, fh) = pendingRect.toList()
                for (y in maxOf(0, fy) until minOf(height, fy + fh)) {
                    val base = y * width
                    for (x in maxOf(0, fx) until minOf(width, fx + fw)) canvas[base + x] = 0
                }
            }
            3 -> restore?.let { System.arraycopy(it, 0, canvas, 0, canvas.size) }
        }
        if (pendingDisposal != 3) restore = null
    }

    private fun interlaceRows(h: Int): IntArray {
        val order = IntArray(h)
        var i = 0
        for ((start, step) in arrayOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var y = start
            while (y < h) { order[i++] = y; y += step }
        }
        return order
    }

    // --- LZW -------------------------------------------------------------------------------------
    private val prefix = ShortArray(4096)
    private val suffix = ByteArray(4096)
    private val stack = ByteArray(4097)

    /** Decodes into [out]; returns the number of pixels produced. Consumes all sub-blocks. */
    private fun lzwDecode(minCodeSize: Int, out: ByteArray): Int {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1
        var available = clear + 2
        var oldCode = -1
        var first = 0
        var bits = 0
        var datum = 0
        var produced = 0
        for (i in 0 until clear) { prefix[i] = 0; suffix[i] = i.toByte() }
        var blockLeft = 0
        var finished = false
        while (true) {
            if (blockLeft == 0) {
                blockLeft = u8()
                if (blockLeft == 0) break
            }
            if (finished) { // drain remaining sub-blocks
                skipBytes(blockLeft); blockLeft = 0; continue
            }
            datum = datum or (u8() shl bits)
            bits += 8
            blockLeft--
            while (bits >= codeSize && !finished) {
                var code = datum and codeMask
                datum = datum ushr codeSize
                bits -= codeSize
                if (code == clear) {
                    codeSize = minCodeSize + 1
                    codeMask = (1 shl codeSize) - 1
                    available = clear + 2
                    oldCode = -1
                    continue
                }
                if (code == end) { finished = true; break }
                if (oldCode == -1) {
                    if (code >= clear) { finished = true; break } // invalid first code
                    if (produced < out.size) out[produced++] = suffix[code]
                    oldCode = code
                    first = code
                    continue
                }
                val inCode = code
                var top = 0
                if (code >= available) {
                    if (code > available) { finished = true; break } // corrupt stream
                    stack[top++] = first.toByte()
                    code = oldCode
                }
                while (code >= clear) {
                    stack[top++] = suffix[code]
                    code = prefix[code].toInt()
                }
                first = suffix[code].toInt() and 0xFF
                stack[top++] = first.toByte()
                if (available < 4096) {
                    prefix[available] = oldCode.toShort()
                    suffix[available] = first.toByte()
                    available++
                    if ((available and codeMask) == 0 && available < 4096) {
                        codeSize++
                        codeMask = (1 shl codeSize) - 1
                    }
                }
                oldCode = inCode
                while (top > 0) {
                    top--
                    if (produced < out.size) out[produced++] = stack[top]
                }
            }
        }
        return produced
    }

    // --- primitive readers ----------------------------------------------------------------------
    private fun readPalette(n: Int): IntArray {
        val b = readBytes(n * 3)
        return IntArray(n) { i ->
            (0xFF shl 24) or ((b[i * 3].toInt() and 0xFF) shl 16) or ((b[i * 3 + 1].toInt() and 0xFF) shl 8) or (b[i * 3 + 2].toInt() and 0xFF)
        }
    }

    private fun skipSubBlocks() {
        while (true) {
            val n = u8()
            if (n == 0) return
            skipBytes(n)
        }
    }

    private fun skipBytes(n: Int) {
        var left = n.toLong()
        while (left > 0) {
            val s = ins.skip(left)
            if (s <= 0) { if (ins.read() < 0) throw EOFException(); left-- } else left -= s
        }
    }

    private fun u8(): Int {
        val b = ins.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun u16(): Int = u8() or (u8() shl 8)

    private fun readBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val k = ins.read(b, off, n - off)
            if (k < 0) throw GifFormatException("GIF is truncated", frameIndex)
            off += k
        }
        return b
    }
}
