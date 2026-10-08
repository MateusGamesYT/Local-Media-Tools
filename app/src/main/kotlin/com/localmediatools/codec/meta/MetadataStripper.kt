package com.localmediatools.codec.meta

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** What was found and removed (for the results screen), plus what was deliberately kept. */
class StripReport {
    val removed = LinkedHashSet<String>()
    val kept = LinkedHashSet<String>()
    var orientation = 1
    var trailingBytes = 0L
    val clean get() = removed.isEmpty() && trailingBytes == 0L
}

class UnsupportedForStripping(message: String) : IOException(message)

/**
 * Removes metadata without touching image data: the compressed pixels are copied byte for byte.
 * Orientation is kept (as a minimal EXIF block) so photos still display upright, and colour
 * profiles are kept so colours don't shift.
 */
object MetadataStripper {

    // ------------------------------------------------------------------ EXIF inspection
    /** Reads which kinds of information an EXIF (TIFF) block holds. */
    fun inspectExif(tiff: ByteArray, off: Int, len: Int, report: StripReport) {
        if (len < 8) return
        val le = tiff[off] == 'I'.code.toByte()
        fun u16(p: Int): Int = if (p + 2 > off + len) 0 else if (le) (tiff[p].toInt() and 255) or ((tiff[p + 1].toInt() and 255) shl 8) else ((tiff[p].toInt() and 255) shl 8) or (tiff[p + 1].toInt() and 255)
        fun u32(p: Int): Long = if (p + 4 > off + len) 0 else if (le) (u16(p).toLong() or (u16(p + 2).toLong() shl 16)) else ((u16(p).toLong() shl 16) or u16(p + 2).toLong())
        val visited = HashSet<Long>()
        fun ifd(at: Long, kind: Int, depth: Int) {
            if (at <= 0 || at >= len || depth > 4 || !visited.add(at)) return
            val p = off + at.toInt()
            val n = u16(p)
            if (n > 1000) return
            for (i in 0 until n) {
                val e = p + 2 + i * 12
                val tag = u16(e); val type = u16(e + 2)
                when (tag) {
                    0x0112 -> if (kind == 0) report.orientation = (if (type == 3) u16(e + 8) else u32(e + 8).toInt()).let { if (it in 1..8) it else 1 }
                    0x8825 -> { report.removed.add("GPS location"); ifd(u32(e + 8), 2, depth + 1) }
                    0x8769 -> ifd(u32(e + 8), 1, depth + 1)
                    0x010F, 0x0110 -> report.removed.add("camera make & model")
                    0x0132, 0x9003, 0x9004 -> report.removed.add("date & time")
                    0x0131 -> report.removed.add("software")
                    0x013B, 0x8298, 0x9C9D -> report.removed.add("author & copyright")
                    0xA431, 0xA435 -> report.removed.add("camera serial number")
                    0xA434, 0xA433 -> report.removed.add("lens details")
                    0x927C -> report.removed.add("maker notes")
                    0x9286, 0x9C9C, 0x010E -> report.removed.add("descriptions & comments")
                    0x829A, 0x829D, 0x8827, 0x920A -> report.removed.add("shooting settings")
                    0x0201 -> report.removed.add("embedded thumbnail")
                }
            }
            if (kind == 0 && depth == 0) {
                val next = u32(p + 2 + n * 12)
                if (next > 0) { report.removed.add("embedded thumbnail"); ifd(next, 3, depth + 1) }
            }
        }
        if (len >= 8) ifd(u32(off + 4), 0, 0)
    }

    /** A complete TIFF block holding only the Orientation tag. */
    fun minimalTiff(orientation: Int): ByteArray = byteArrayOf(
        'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
        0, 1,
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
        0, 0, 0, 0,
    )

    // ------------------------------------------------------------------ JPEG
    fun stripJpeg(input: InputStream, out: OutputStream): StripReport {
        val r = StripReport()
        val d = DataInputStream(input.buffered(1 shl 16))
        if (d.readUnsignedByte() != 0xFF || d.readUnsignedByte() != 0xD8) throw IOException("Not a JPEG file")
        out.write(0xFF); out.write(0xD8)
        var wroteOrientation = false
        val pending = ArrayList<ByteArray>()
        fun marker(): Int {
            var b = d.readUnsignedByte()
            while (b != 0xFF) b = d.readUnsignedByte()
            var m = d.readUnsignedByte()
            while (m == 0xFF) m = d.readUnsignedByte()
            return m
        }
        while (true) {
            val m = marker()
            if (m == 0xD9) { out.write(0xFF); out.write(0xD9); break }
            if (m in 0xD0..0xD7 || m == 0x01) { out.write(0xFF); out.write(m); continue }
            val len = d.readUnsignedShort()
            if (len < 2) throw IOException("Damaged JPEG segment")
            val data = ByteArray(len - 2); d.readFully(data)
            fun id(s: String) = data.size >= s.length && s.indices.all { data[it] == s[it].code.toByte() }
            var keep = true
            when (m) {
                0xE0 -> if (id("JFIF\u0000")) {
                    // Keep the JFIF header but drop its thumbnail.
                    if (data.size > 14 && ((data[12].toInt() and 255) > 0 || (data[13].toInt() and 255) > 0)) r.removed.add("embedded thumbnail")
                    val head = data.copyOf(14); head[12] = 0; head[13] = 0
                    writeSegment(out, 0xE0, head); keep = false
                } else { if (id("JFXX")) r.removed.add("embedded thumbnail") else r.removed.add("other application data"); keep = false }
                0xE1 -> {
                    if (id("Exif\u0000\u0000")) inspectExif(data, 6, data.size - 6, r)
                    else if (id("http://ns.adobe.com/xap/1.0/") || id("http://ns.adobe.com/xmp/extension/")) r.removed.add("XMP metadata")
                    else r.removed.add("other application data")
                    keep = false
                }
                0xE2 -> if (id("ICC_PROFILE\u0000")) r.kept.add("colour profile") else {
                    if (id("MPF\u0000")) r.removed.add("extra images (depth, HDR or motion data)") else r.removed.add("other application data")
                    keep = false
                }
                0xEE -> if (id("Adobe")) r.kept.add("Adobe colour transform") else { r.removed.add("other application data"); keep = false }
                0xED -> { r.removed.add("IPTC / Photoshop data"); keep = false }
                in 0xE3..0xEF -> { r.removed.add("other application data"); keep = false }
                0xFE -> { r.removed.add("descriptions & comments"); keep = false }
            }
            if (!keep) continue
            if (!wroteOrientation && m !in 0xE0..0xEF) {
                // First non-APP segment: insert the orientation-only EXIF block if needed.
                if (r.orientation != 1) writeExifOrientation(out, r.orientation)
                wroteOrientation = true
            }
            writeSegment(out, m, data)
            if (m == 0xDA) {
                copyScan(d, out, r)
                return r
            }
        }
        return r
    }

    private fun writeSegment(out: OutputStream, m: Int, data: ByteArray) {
        out.write(0xFF); out.write(m)
        val len = data.size + 2
        out.write(len ushr 8); out.write(len and 255)
        out.write(data)
    }

    private fun writeExifOrientation(out: OutputStream, orientation: Int) {
        val tiff = minimalTiff(orientation)
        writeSegment(out, 0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff)
    }

    /** Copies entropy-coded data and any later scans up to the primary image's EOI; counts trailing bytes. */
    private fun copyScan(d: DataInputStream, out: OutputStream, r: StripReport) {
        val buf = ByteArrayOutputStream(1 shl 16)
        fun flush() { buf.writeTo(out); buf.reset() }
        while (true) {
            val b = try { d.readUnsignedByte() } catch (e: EOFException) { flush(); throw IOException("The JPEG file is truncated") }
            if (b != 0xFF) { buf.write(b); if (buf.size() >= 1 shl 16) flush(); continue }
            var m = d.readUnsignedByte()
            while (m == 0xFF) m = d.readUnsignedByte()
            when {
                m == 0x00 || m in 0xD0..0xD7 -> { buf.write(0xFF); buf.write(m) }
                m == 0xD9 -> {
                    buf.write(0xFF); buf.write(0xD9); flush()
                    var extra = 0L
                    val skip = ByteArray(1 shl 16)
                    while (true) { val n = d.read(skip); if (n < 0) break; extra += n }
                    if (extra > 0) { r.trailingBytes = extra; r.removed.add("extra images (depth, HDR or motion data)") }
                    return
                }
                else -> {
                    // Marker between progressive scans (DHT, SOS, DQT, DRI…): copy; drop metadata segments.
                    val len = d.readUnsignedShort()
                    val data = ByteArray(len - 2); d.readFully(data)
                    if (m in 0xE0..0xEF || m == 0xFE) { r.removed.add("other application data"); continue }
                    flush(); writeSegment(out, m, data)
                }
            }
        }
    }

    // ------------------------------------------------------------------ PNG
    private val PNG_SIG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private val PNG_KEEP = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "bKGD", "pHYs", "hIST",
        "acTL", "fcTL", "fdAT", "cICP", "mDCv", "cLLi", "sPLT")

    fun stripPng(input: InputStream, out: OutputStream): StripReport {
        val r = StripReport()
        val d = DataInputStream(input.buffered(1 shl 16))
        val sig = ByteArray(8); d.readFully(sig)
        if (!sig.contentEquals(PNG_SIG)) throw IOException("Not a PNG file")
        out.write(sig)
        var orientationWritten = false
        while (true) {
            val len = d.readInt()
            val typeB = ByteArray(4); d.readFully(typeB)
            val type = String(typeB, Charsets.ISO_8859_1)
            if (len < 0) throw IOException("Damaged PNG chunk")
            val keep = type in PNG_KEEP || type[0].isUpperCase()
            if (!keep) {
                when (type) {
                    "eXIf" -> { val data = ByteArray(len); d.readFully(data); d.readInt(); inspectExif(data, 0, data.size, r); continue }
                    "tEXt", "zTXt", "iTXt" -> {
                        val data = ByteArray(len); d.readFully(data); d.readInt()
                        val key = String(data, 0, data.indexOfFirst { it == 0.toByte() }.let { if (it < 0) minOf(79, data.size) else it }, Charsets.ISO_8859_1)
                        r.removed.add(when {
                            key.startsWith("XML:com.adobe.xmp") -> "XMP metadata"
                            key.equals("Raw profile type exif", true) || key.equals("Raw profile type APP1", true) -> "EXIF data"
                            key.contains("Creation Time", true) -> "date & time"
                            key.equals("Software", true) -> "software"
                            key.equals("Author", true) || key.equals("Copyright", true) -> "author & copyright"
                            else -> "text notes (${key.take(24)})"
                        })
                        continue
                    }
                    "tIME" -> r.removed.add("date & time")
                    else -> r.removed.add("other application data")
                }
                d.skipFully(len.toLong() + 4)
                continue
            }
            if (!orientationWritten && (type == "IDAT" || type == "acTL")) {
                if (r.orientation != 1) writePngChunk(out, "eXIf", minimalTiff(r.orientation))
                orientationWritten = true
            }
            if (type == "iCCP") r.kept.add("colour profile")
            // Copy kept chunks as they are (streamed, IDAT can be huge).
            val header = ByteArray(8)
            header[0] = (len ushr 24).toByte(); header[1] = (len ushr 16).toByte(); header[2] = (len ushr 8).toByte(); header[3] = len.toByte()
            System.arraycopy(typeB, 0, header, 4, 4)
            out.write(header)
            copy(d, out, len.toLong() + 4)
            if (type == "IEND") {
                var extra = 0L
                val skip = ByteArray(8192)
                while (true) { val n = d.read(skip); if (n < 0) break; extra += n }
                if (extra > 0) { r.trailingBytes = extra; r.removed.add("hidden data after the image") }
                return r
            }
        }
    }

    private fun writePngChunk(out: OutputStream, type: String, data: ByteArray) {
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        val c = crc.value
        out.write(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
    }

    // ------------------------------------------------------------------ WebP
    /** WebP needs the final RIFF size up front, so the file is read twice through [open]. */
    fun stripWebp(open: () -> InputStream, out: OutputStream): StripReport {
        val r = StripReport()
        data class Chunk(val type: String, val len: Long, val keep: Boolean)
        val chunks = ArrayList<Chunk>()
        var vp8x = false
        open().use { input ->
            val d = DataInputStream(input.buffered(1 shl 16))
            val riff = ByteArray(12); d.readFully(riff)
            if (String(riff, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(riff, 8, 4, Charsets.ISO_8859_1) != "WEBP") throw IOException("Not a WebP file")
            while (true) {
                val h = ByteArray(8)
                val n = d.readNBytesCompat(h)
                if (n < 8) break
                val type = String(h, 0, 4, Charsets.ISO_8859_1)
                val len = (h[4].toLong() and 255) or ((h[5].toLong() and 255) shl 8) or ((h[6].toLong() and 255) shl 16) or ((h[7].toLong() and 255) shl 24)
                val padded = len + (len and 1)
                when (type) {
                    "VP8X" -> { vp8x = true; chunks.add(Chunk(type, len, true)); d.skipFully(padded) }
                    "EXIF" -> { val data = ByteArray(len.toInt()); d.readFully(data); if (len and 1 == 1L) d.skipFully(1)
                        val off = if (data.size > 6 && String(data, 0, 4, Charsets.ISO_8859_1) == "Exif") 6 else 0
                        inspectExif(data, off, data.size - off, r); chunks.add(Chunk(type, len, false)) }
                    "XMP " -> { r.removed.add("XMP metadata"); chunks.add(Chunk(type, len, false)); d.skipFully(padded) }
                    "VP8 ", "VP8L", "ALPH", "ANIM", "ANMF", "ICCP" -> { if (type == "ICCP") r.kept.add("colour profile"); chunks.add(Chunk(type, len, true)); d.skipFully(padded) }
                    else -> { r.removed.add("other application data"); chunks.add(Chunk(type, len, false)); d.skipFully(padded) }
                }
            }
        }
        val orientationChunk = if (r.orientation != 1 && vp8x) minimalTiff(r.orientation) else null
        var size = 4L
        for (c in chunks) if (c.keep) size += 8 + c.len + (c.len and 1)
        if (orientationChunk != null) size += 8 + orientationChunk.size
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1)); out.write(le32(size)); out.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        open().use { input ->
            val d = DataInputStream(input.buffered(1 shl 16))
            d.skipFully(12)
            for (c in chunks) {
                val h = ByteArray(8); d.readFully(h)
                val padded = c.len + (c.len and 1)
                if (!c.keep) { d.skipFully(padded); continue }
                if (c.type == "VP8X") {
                    val data = ByteArray(c.len.toInt()); d.readFully(data); if (c.len and 1 == 1L) d.skipFully(1)
                    // Flags: clear EXIF (0x08) and XMP (0x04); set EXIF again if orientation is kept.
                    var f = data[0].toInt() and 0xF3
                    if (orientationChunk != null) f = f or 0x08
                    data[0] = f.toByte()
                    out.write(h); out.write(data); if (c.len and 1 == 1L) out.write(0)
                } else {
                    out.write(h); copy(d, out, padded)
                }
            }
        }
        if (orientationChunk != null) { out.write("EXIF".toByteArray(Charsets.ISO_8859_1)); out.write(le32(orientationChunk.size.toLong())); out.write(orientationChunk) }
        return r
    }

    // ------------------------------------------------------------------ GIF
    fun stripGif(input: InputStream, out: OutputStream): StripReport {
        val r = StripReport()
        val d = DataInputStream(input.buffered(1 shl 16))
        val head = ByteArray(13); d.readFully(head)
        val sig = String(head, 0, 6, Charsets.ISO_8859_1)
        if (sig != "GIF87a" && sig != "GIF89a") throw IOException("Not a GIF file")
        out.write(head)
        val packed = head[10].toInt() and 255
        if (packed and 0x80 != 0) copy(d, out, 3L * (1 shl ((packed and 7) + 1)))
        fun readSubBlocks(): ByteArray {
            val b = ByteArrayOutputStream()
            while (true) { val n = d.readUnsignedByte(); b.write(n); if (n == 0) break; val data = ByteArray(n); d.readFully(data); b.write(data) }
            return b.toByteArray()
        }
        while (true) {
            when (val intro = d.readUnsignedByte()) {
                0x3B -> { out.write(0x3B); break }
                0x2C -> {
                    val desc = ByteArray(9); d.readFully(desc)
                    out.write(0x2C); out.write(desc)
                    val p = desc[8].toInt() and 255
                    if (p and 0x80 != 0) copy(d, out, 3L * (1 shl ((p and 7) + 1)))
                    out.write(d.readUnsignedByte()) // LZW minimum code size
                    while (true) { val n = d.readUnsignedByte(); out.write(n); if (n == 0) break; copy(d, out, n.toLong()) }
                }
                0x21 -> {
                    val label = d.readUnsignedByte()
                    val body = readSubBlocks()
                    val keep = when (label) {
                        0xF9, 0x01 -> true
                        0xFE -> { r.removed.add("descriptions & comments"); false }
                        0xFF -> {
                            val app = if (body.size > 11) String(body, 1, 11, Charsets.ISO_8859_1) else ""
                            if (app == "NETSCAPE2.0" || app == "ANIMEXTS1.0") true
                            else { r.removed.add(if (app.startsWith("XMP Data")) "XMP metadata" else "other application data"); false }
                        }
                        else -> { r.removed.add("other application data"); false }
                    }
                    if (keep) { out.write(0x21); out.write(label); out.write(body) }
                }
                else -> throw IOException("Damaged GIF (unexpected block 0x${Integer.toHexString(intro)})")
            }
        }
        return r
    }

    // ------------------------------------------------------------------ helpers
    private fun copy(d: DataInputStream, out: OutputStream, n: Long) {
        val buf = ByteArray(1 shl 16)
        var left = n
        while (left > 0) {
            val k = d.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (k < 0) throw IOException("The file is truncated")
            out.write(buf, 0, k); left -= k
        }
    }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val k = skip(left)
            if (k <= 0) { if (read() < 0) throw IOException("The file is truncated"); left-- } else left -= k
        }
    }

    private fun DataInputStream.readNBytesCompat(b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val k = read(b, n, b.size - n); if (k < 0) break; n += k }
        return n
    }
}
