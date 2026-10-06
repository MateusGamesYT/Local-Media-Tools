package com.localmediatools.codec.mp4

import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class Mp4FormatException(message: String) : IOException(message)

/**
 * Rewrites an MP4/MOV so the movie header (`moov`) comes before the media data ("fast start"),
 * dropping padding boxes (`free`, `skip`, `wide`). Media samples are copied byte-for-byte; only the
 * chunk offset tables are adjusted. This is the "optimized / web-ready" packaging step of the
 * lossless video optimizer and the compressor.
 */
object Mp4FastStart {

    data class TopBox(val type: String, val offset: Long, val size: Long)

    data class Result(val relocatedMoov: Boolean, val droppedBytes: Long, val outputSize: Long)

    private val PADDING = setOf("free", "skip", "wide")
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex")

    fun scan(file: RandomAccessFile): List<TopBox> {
        val len = file.length()
        val boxes = ArrayList<TopBox>()
        var pos = 0L
        val hdr = ByteArray(16)
        while (pos < len) {
            if (len - pos < 8) {
                // Trailing garbage shorter than a box header: ignore it (common after aborted writes).
                break
            }
            file.seek(pos)
            file.readFully(hdr, 0, 8)
            var size = u32(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                if (len - pos < 16) throw Mp4FormatException("Truncated MP4 box header")
                file.readFully(hdr, 8, 8)
                size = ByteBuffer.wrap(hdr, 8, 8).long
            } else if (size == 0L) {
                size = len - pos
            }
            if (size < 8 || pos + size > len) throw Mp4FormatException("MP4 box '$type' at $pos is truncated or corrupt")
            boxes.add(TopBox(type, pos, size))
            pos += size
        }
        return boxes
    }

    /** True when the file already has `moov` before the first `mdat` and no padding boxes. */
    fun isOptimized(boxes: List<TopBox>): Boolean {
        val moov = boxes.indexOfFirst { it.type == "moov" }
        val mdat = boxes.indexOfFirst { it.type == "mdat" }
        return moov >= 0 && (mdat < 0 || moov < mdat) && boxes.none { it.type in PADDING }
    }

    fun process(input: RandomAccessFile, out: OutputStream): Result {
        val boxes = scan(input)
        val moovBox = boxes.firstOrNull { it.type == "moov" } ?: throw Mp4FormatException("The file has no movie header (moov box)")
        if (boxes.none { it.type == "mdat" }) throw Mp4FormatException("The file has no media data (mdat box)")
        if (boxes.any { it.type == "moof" }) {
            // Fragmented MP4 is streamable already and uses absolute offsets elsewhere: copy as-is.
            var total = 0L
            for (b in boxes) { copyRange(input, b.offset, b.size, out); total += b.size }
            return Result(false, 0, total)
        }
        if (moovBox.size > 512L * 1024 * 1024) throw Mp4FormatException("Movie header is unexpectedly large")
        val moovBytes = ByteArray(moovBox.size.toInt())
        input.seek(moovBox.offset); input.readFully(moovBytes)

        val ordered = ArrayList<TopBox>()
        boxes.filter { it.type == "ftyp" }.let { ordered.addAll(it) }
        ordered.add(moovBox)
        for (b in boxes) if (b.type != "ftyp" && b.type != "moov" && b.type !in PADDING) ordered.add(b)
        val dropped = boxes.filter { it.type in PADDING }.sumOf { it.size }
        val relocated = boxes.indexOf(moovBox) > boxes.indexOfFirst { it.type == "mdat" }

        // Lay out; if 32-bit chunk offsets overflow, switch every stco to co64 and lay out again.
        var useCo64 = false
        var moovOut: ByteArray
        while (true) {
            // Always start from the original header so a failed attempt leaves no partial patches.
            val moov = parseBox(moovBytes, 0, moovBytes.size)
            if (useCo64) convertToCo64(moov)
            val moovSize = serializedSize(moov)
            val newOffsets = HashMap<TopBox, Long>()
            var pos = 0L
            for (b in ordered) {
                newOffsets[b] = pos
                pos += if (b === moovBox) moovSize else b.size
            }
            val overflow = patchOffsets(moov, boxes.filter { it.type != "moov" && it.type !in PADDING }, newOffsets)
            if (overflow && !useCo64) { useCo64 = true; continue }
            if (overflow) throw Mp4FormatException("Chunk offsets do not fit even in 64-bit tables")
            moovOut = serialize(moov)
            break
        }
        var total = 0L
        for (b in ordered) {
            if (b === moovBox) { out.write(moovOut); total += moovOut.size }
            else { copyRange(input, b.offset, b.size, out); total += b.size }
        }
        out.flush()
        return Result(relocated, dropped, total)
    }

    // ------------------------------------------------------------------ box tree
    private class Box(val type: String, var payload: ByteArray?, val children: MutableList<Box>?)

    private fun parseBox(b: ByteArray, off: Int, len: Int): Box {
        val size0 = u32(b, off)
        val type = String(b, off + 4, 4, Charsets.ISO_8859_1)
        var header = 8
        var size = size0
        if (size0 == 1L) { size = ByteBuffer.wrap(b, off + 8, 8).long; header = 16 }
        else if (size0 == 0L) size = len.toLong()
        if (size < header || size > len) throw Mp4FormatException("Corrupt '$type' box in movie header")
        val end = off + size.toInt()
        return if (type in CONTAINERS) {
            val kids = ArrayList<Box>()
            var p = off + header
            while (p + 8 <= end) {
                val child = parseBox(b, p, end - p)
                kids.add(child)
                p += childSize(b, p, end - p)
            }
            Box(type, null, kids)
        } else {
            Box(type, b.copyOfRange(off + header, end), null)
        }
    }

    private fun childSize(b: ByteArray, off: Int, len: Int): Int {
        val s = u32(b, off)
        return when (s) {
            1L -> ByteBuffer.wrap(b, off + 8, 8).long.toInt()
            0L -> len
            else -> s.toInt()
        }
    }

    private fun serializedSize(box: Box): Long {
        val content = box.payload?.size?.toLong() ?: box.children!!.sumOf { serializedSize(it) }
        return 8 + content
    }

    private fun serialize(box: Box): ByteArray {
        val size = serializedSize(box)
        require(size <= Int.MAX_VALUE)
        val buf = ByteBuffer.allocate(size.toInt())
        write(box, buf)
        return buf.array()
    }

    private fun write(box: Box, buf: ByteBuffer) {
        buf.putInt(serializedSize(box).toInt())
        buf.put(box.type.toByteArray(Charsets.ISO_8859_1))
        val p = box.payload
        if (p != null) buf.put(p) else box.children!!.forEach { write(it, buf) }
    }

    private fun forEachBox(box: Box, fn: (Box) -> Unit) {
        fn(box)
        box.children?.forEach { forEachBox(it, fn) }
    }

    private fun convertToCo64(moov: Box) {
        forEachBox(moov) { box ->
            box.children?.let { kids ->
                for (i in kids.indices) {
                    val k = kids[i]
                    if (k.type == "stco") {
                        val p = k.payload!!
                        val n = u32(p, 4).toInt()
                        val nb = ByteBuffer.allocate(8 + n * 8)
                        nb.put(p, 0, 4); nb.putInt(n)
                        for (j in 0 until n) nb.putLong(u32(p, 8 + j * 4))
                        kids[i] = Box("co64", nb.array(), null)
                    }
                }
            }
        }
    }

    /** Rewrites chunk offsets; returns true if a 32-bit table overflowed. */
    private fun patchOffsets(moov: Box, retained: List<TopBox>, newOffsets: Map<TopBox, Long>): Boolean {
        var overflow = false
        val sorted = retained.sortedBy { it.offset }
        fun remap(old: Long): Long {
            // Binary search for the box containing the offset.
            var lo = 0; var hi = sorted.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val b = sorted[mid]
                when {
                    old < b.offset -> hi = mid - 1
                    old >= b.offset + b.size -> lo = mid + 1
                    else -> return old - b.offset + newOffsets.getValue(b)
                }
            }
            throw Mp4FormatException("A media chunk points outside the file's data boxes")
        }
        forEachBox(moov) { box ->
            val p = box.payload ?: return@forEachBox
            if (box.type == "stco") {
                val n = u32(p, 4).toInt()
                if (8L + n * 4L > p.size) throw Mp4FormatException("Corrupt chunk offset table")
                val bb = ByteBuffer.wrap(p)
                for (j in 0 until n) {
                    val v = remap(u32(p, 8 + j * 4))
                    if (v > 0xFFFFFFFFL) overflow = true
                    bb.putInt(8 + j * 4, v.toInt())
                }
            } else if (box.type == "co64") {
                val n = u32(p, 4).toInt()
                if (8L + n * 8L > p.size) throw Mp4FormatException("Corrupt chunk offset table")
                val bb = ByteBuffer.wrap(p)
                for (j in 0 until n) bb.putLong(8 + j * 8, remap(bb.getLong(8 + j * 8)))
            }
        }
        return overflow
    }

    private fun copyRange(f: RandomAccessFile, offset: Long, size: Long, out: OutputStream) {
        val buf = ByteArray(1 shl 20)
        f.seek(offset)
        var left = size
        while (left > 0) {
            val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw Mp4FormatException("Unexpected end of file")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
}
