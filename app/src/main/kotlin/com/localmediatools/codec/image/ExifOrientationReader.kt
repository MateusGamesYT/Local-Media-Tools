package com.localmediatools.codec.image

/**
 * Reads the EXIF orientation tag from the leading bytes of an image file without decoding it.
 *
 * Supported containers: JPEG (APP1 "Exif"), PNG (eXIf chunk), WebP (RIFF "EXIF" chunk) and bare
 * TIFF/DNG headers. Returns null when the container is not one of these (the caller then falls back
 * to platform metadata readers) and [Orientation.NORMAL] when the container has no orientation tag.
 */
object ExifOrientationReader {

    fun read(head: ByteArray, length: Int = head.size): Orientation? {
        if (length < 12) return null
        return when {
            isJpeg(head) -> fromJpeg(head, length)
            isPng(head) -> fromPng(head, length)
            isWebp(head) -> fromWebp(head, length)
            isTiff(head, 0) -> Orientation.fromExif(tiffOrientation(head, 0, length) ?: 1)
            else -> null
        }
    }

    fun isJpeg(b: ByteArray) = b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
    fun isPng(b: ByteArray) = b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
        b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
    fun isWebp(b: ByteArray) = b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP"
    fun isTiff(b: ByteArray, off: Int) = b.size >= off + 4 &&
        ((b[off] == 'I'.code.toByte() && b[off + 1] == 'I'.code.toByte() && b[off + 2].toInt() == 42 && b[off + 3].toInt() == 0) ||
            (b[off] == 'M'.code.toByte() && b[off + 1] == 'M'.code.toByte() && b[off + 2].toInt() == 0 && b[off + 3].toInt() == 42))

    private fun fromJpeg(b: ByteArray, len: Int): Orientation {
        var p = 2
        while (p + 4 <= len) {
            if (b[p] != 0xFF.toByte()) return Orientation.NORMAL
            val marker = b[p + 1].toInt() and 0xFF
            if (marker == 0xFF) { p++; continue } // fill byte
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            if (marker == 0xDA || marker == 0xD9) return Orientation.NORMAL // start of scan: no EXIF before it
            val segLen = u16be(b, p + 2)
            if (segLen < 2) return Orientation.NORMAL
            val start = p + 4
            if (marker == 0xE1 && start + 6 <= len && ascii(b, start, 4) == "Exif" && b[start + 4].toInt() == 0) {
                val tiff = start + 6
                val end = minOf(len, p + 2 + segLen)
                return Orientation.fromExif(tiffOrientation(b, tiff, end) ?: 1)
            }
            p += 2 + segLen
        }
        return Orientation.NORMAL
    }

    private fun fromPng(b: ByteArray, len: Int): Orientation {
        var p = 8
        while (p + 8 <= len) {
            val chunkLen = u32be(b, p)
            val type = ascii(b, p + 4, 4)
            if (type == "eXIf") {
                val start = p + 8
                val end = minOf(len.toLong(), start + chunkLen).toInt()
                return Orientation.fromExif(tiffOrientation(b, start, end) ?: 1)
            }
            if (type == "IDAT" || type == "IEND") return Orientation.NORMAL
            val next = p.toLong() + 12 + chunkLen
            if (next > Int.MAX_VALUE) return Orientation.NORMAL
            p = next.toInt()
        }
        return Orientation.NORMAL
    }

    private fun fromWebp(b: ByteArray, len: Int): Orientation {
        var p = 12
        while (p + 8 <= len) {
            val type = ascii(b, p, 4)
            val chunkLen = u32le(b, p + 4)
            if (type == "EXIF") {
                var start = p + 8
                val end = minOf(len.toLong(), start + chunkLen).toInt()
                // Some writers prefix the TIFF block with "Exif\0\0".
                if (start + 6 <= end && ascii(b, start, 4) == "Exif") start += 6
                return Orientation.fromExif(tiffOrientation(b, start, end) ?: 1)
            }
            val next = p.toLong() + 8 + chunkLen + (chunkLen and 1)
            if (next > Int.MAX_VALUE) return Orientation.NORMAL
            p = next.toInt()
        }
        return Orientation.NORMAL
    }

    /** Parses IFD0 of a TIFF structure starting at [tiff] and returns tag 0x0112, if present. */
    fun tiffOrientation(b: ByteArray, tiff: Int, end: Int): Int? {
        if (tiff + 8 > end || !isTiff(b, tiff)) return null
        val le = b[tiff] == 'I'.code.toByte()
        fun u16(o: Int) = if (le) u16le(b, o) else u16be(b, o)
        fun u32(o: Int) = if (le) u32le(b, o) else u32be(b, o)
        val ifd = u32(tiff + 4)
        if (ifd < 8 || tiff + ifd + 2 > end) return null
        val ifdPos = (tiff + ifd).toInt()
        val count = u16(ifdPos)
        for (i in 0 until count) {
            val e = ifdPos + 2 + i * 12
            if (e + 12 > end) return null
            if (u16(e) == 0x0112) {
                val type = u16(e + 2)
                val value = when (type) {
                    3 -> u16(e + 8)            // SHORT, stored left-justified in the value field
                    4 -> u32(e + 8).toInt()     // LONG (non-standard but seen in the wild)
                    else -> return null
                }
                return if (value in 1..8) value else null
            }
        }
        return null
    }

    private fun ascii(b: ByteArray, off: Int, n: Int): String {
        if (off + n > b.size) return ""
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append((b[off + i].toInt() and 0xFF).toChar())
        return sb.toString()
    }

    private fun u16be(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u16le(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32be(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
}
