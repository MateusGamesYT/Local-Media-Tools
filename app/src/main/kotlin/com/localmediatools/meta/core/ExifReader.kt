package com.localmediatools.meta.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Random access to bytes: a whole file, or a block of one (an EXIF segment). */
interface Bytes {
    val size: Long
    fun read(at: Long, len: Int): ByteArray
}

class ArrayBytes(private val a: ByteArray, private val off: Int = 0, private val len: Int = a.size - off) : Bytes {
    override val size get() = len.toLong()
    override fun read(at: Long, len: Int): ByteArray {
        if (at < 0 || at >= this.len || len <= 0) return ByteArray(0)
        val n = minOf(len.toLong(), this.len - at).toInt()
        return a.copyOfRange(off + at.toInt(), off + at.toInt() + n)
    }
}

class InputBytes(private val input: MetaInput, private val base: Long = 0, override val size: Long = input.size - base) : Bytes {
    override fun read(at: Long, len: Int): ByteArray = if (at < 0 || at >= size) ByteArray(0) else input.bytes(base + at, minOf(len.toLong(), size - at).toInt())
}

/**
 * EXIF and TIFF metadata: every directory (the main image's, EXIF, GPS, interoperability, the
 * thumbnail's and sub-images), each tag by its EXIF name, values as ExifTool reads them. Maker notes
 * are named with their size; their inside is each camera maker's own and isn't decoded.
 */
object ExifReader {
    private class Entry(val tag: Int, val type: Int, val count: Long, val valueAt: Long)

    /** What the walk found beyond the fields: where the thumbnail is, in the TIFF block's offsets. */
    class Found(var thumbOffset: Long = -1, var thumbLength: Long = -1, var makerNote: Int = 0, var xmp: ByteArray? = null, var iptc: ByteArray? = null, var icc: ByteArray? = null,
                var embeddedJpeg: ByteArray? = null, var embeddedJpegAt: Long = 0)

    /**
     * Reads the TIFF structure in [b] (starting with "II*" or "MM*"; BigTIFF too). The main
     * directory's fields go in [firstGroup] ("IFD0").
     */
    fun read(b: Bytes, out: Fields, firstGroup: String = "IFD0", base: Long = 0): Found {
        val found = Found()
        val h = b.read(0, 16)
        if (h.size < 8) return found
        val le = when { B.starts(h, 0, "II") -> true; B.starts(h, 0, "MM") -> false; else -> return found }
        val magic = if (le) B.u16le(h, 2) else B.u16be(h, 2)
        val r = Reader(b, le, base, panasonic = magic == 0x55)
        val big = magic == 43
        if (magic != 42 && !big && magic != 0x4F52 && magic != 0x5352 && magic != 0x55) {
            // Olympus ORF ("IIRO"/"IIRS"), Panasonic RW2 ("IIU") are TIFF with another number.
            return found
        }
        val first = if (big) r.u64(h, 8) else r.u32(h, 4)
        val seen = HashSet<Long>()
        var at = first
        var n = 0
        while (at > 0 && at < b.size && seen.add(at) && n < 8) {
            val group = when (n) { 0 -> firstGroup; else -> "IFD$n" }
            at = r.directory(at, group, big, out, found, seen, depth = 0)
            n++
        }
        return found
    }

    /** [base]: where the block starts in the file, so offsets are shown as positions in the file (as ExifTool shows them). */
    private class Reader(val b: Bytes, val le: Boolean, val base: Long, val panasonic: Boolean = false) {
        fun u16(a: ByteArray, o: Int) = if (le) B.u16le(a, o) else B.u16be(a, o)
        fun u32(a: ByteArray, o: Int) = if (le) B.u32le(a, o) else B.u32be(a, o)
        fun u64(a: ByteArray, o: Int): Long = if (le) (B.u32le(a, o) or (B.u32le(a, o + 4) shl 32)) else ((B.u32be(a, o) shl 32) or B.u32be(a, o + 4))

        fun typeSize(t: Int) = when (t) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11, 13 -> 4; 5, 10, 12, 16, 17, 18 -> 8; else -> 0 }

        /** Reads one directory and the ones it points to; returns where the next directory is (0: none). */
        fun directory(at: Long, group: String, big: Boolean, out: Fields, found: Found, seen: HashSet<Long>, depth: Int): Long {
            val head = b.read(at, 8)
            val count = (if (big) u64(head, 0) else u16(head, 0).toLong())
            if (count <= 0 || count > 4096) return 0
            val esize = if (big) 20 else 12
            val start = at + if (big) 8 else 2
            val raw = b.read(start, (count * esize).toInt() + (if (big) 8 else 4))
            val entries = ArrayList<Entry>()
            for (i in 0 until count.toInt()) {
                val o = i * esize
                if (o + esize > raw.size) break
                val tag = u16(raw, o); val type = u16(raw, o + 2)
                val n = if (big) u64(raw, o + 4) else u32(raw, o + 4)
                val sz = typeSize(type) * n
                val inline = if (big) 8 else 4
                val valueAt = if (sz in 0..inline.toLong()) start + o + (if (big) 12 else 8) else (if (big) u64(raw, o + 12) else u32(raw, o + 8))
                entries.add(Entry(tag, type, n, valueAt))
            }
            val next = if (big) u64(raw, (count * esize).toInt()) else u32(raw, (count * esize).toInt())
            val names = when { group == "GPS" -> ExifTags.gps; group == "InteropIFD" -> ExifTags.interop; panasonic && group == "IFD0" -> ExifTags.panasonicRaw; else -> ExifTags.main }
            var lat: Double? = null; var latRef: String? = null; var lon: Double? = null; var lonRef: String? = null
            for (e in entries) {
                val name = names[e.tag]
                when {
                    // Directories inside this one.
                    group != "GPS" && e.tag == 0x8769 && depth < 3 -> sub(e, "ExifIFD", big, out, found, seen, depth)
                    group != "GPS" && e.tag == 0x8825 && depth < 3 -> sub(e, "GPS", big, out, found, seen, depth)
                    group != "GPS" && e.tag == 0xA005 && depth < 3 -> sub(e, "InteropIFD", big, out, found, seen, depth)
                    group != "GPS" && e.tag == 0x014A && depth < 3 -> {
                        val offs = numbers(e, 16)
                        for ((k, o) in offs.withIndex()) if (o > 0 && seen.add(o.toLong())) directory(o.toLong(), if (offs.size == 1) "SubIFD" else "SubIFD${k + 1}", big, out, found, seen, depth + 1)
                    }
                    // Blocks read by their own readers.
                    e.tag == 0x02BC && group != "GPS" -> found.xmp = block(e)
                    e.tag == 0x83BB && group != "GPS" -> found.iptc = block(e)
                    e.tag == 0x8773 && group != "GPS" -> found.icc = block(e)
                    panasonic && group == "IFD0" && e.tag == 0x002E -> { found.embeddedJpeg = block(e); found.embeddedJpegAt = base + e.valueAt; out.add(group, "JpgFromRaw", "(${e.count} bytes, a full-size JPEG copy of the picture)", "${e.count}", Privacy.HIDDEN_IMAGE) }
                    e.tag == 0xC4A5 && group == "IFD0" -> block(e)?.let { p -> if (B.starts(p, 0, "PrintIM")) out.add("PrintIM", "PrintIMVersion", B.ascii(p, 8, 4)) }
                    e.tag == 0x927C && group == "ExifIFD" -> { found.makerNote = e.count.toInt(); out.add(group, "MakerNote", "(${e.count} bytes, the camera maker's own data)", "${e.count}") }
                    e.tag == 0x0201 && group.startsWith("IFD") && group != "IFD0" -> { found.thumbOffset = numbers(e, 1).firstOrNull()?.toLong() ?: -1; out.add(group, "ThumbnailOffset", "${found.thumbOffset + base}") }
                    e.tag == 0x0202 && group.startsWith("IFD") && group != "IFD0" -> { found.thumbLength = numbers(e, 1).firstOrNull()?.toLong() ?: -1; out.add(group, "ThumbnailLength", "${found.thumbLength}") }
                    name == null -> {}
                    e.tag in ExifTags.structural && group != "GPS" && group != "InteropIFD" -> {}
                    else -> {
                        val (shown, raw) = value(name, e) ?: continue
                        out.add(group, name, shown, raw)
                        if (group == "GPS") when (name) {
                            "GPSLatitude" -> lat = raw.toDoubleOrNull(); "GPSLongitude" -> lon = raw.toDoubleOrNull()
                            "GPSLatitudeRef" -> latRef = raw; "GPSLongitudeRef" -> lonRef = raw
                        }
                    }
                }
            }
            // The position with its sign (south and west negative), as ExifTool's composite tags give it.
            if (group == "GPS" && lat != null && lon != null) {
                val la = if (latRef == "S") -lat!! else lat!!
                val lo = if (lonRef == "W") -lon!! else lon!!
                out.add("Composite", "GPSLatitude", dms(la, true), num(la))
                out.add("Composite", "GPSLongitude", dms(lo, false), num(lo))
                out.add("Composite", "GPSPosition", "${dms(la, true)}, ${dms(lo, false)}", "${num(la)} ${num(lo)}")
            }
            if (group.startsWith("IFD") && group != "IFD0" && found.thumbOffset >= 0 && found.thumbLength > 0)
                out.add(group, "ThumbnailImage", "(${found.thumbLength} bytes, a smaller copy of the picture)", "${found.thumbLength}")
            return next
        }

        private fun sub(e: Entry, group: String, big: Boolean, out: Fields, found: Found, seen: HashSet<Long>, depth: Int) {
            val at = numbers(e, 1).firstOrNull()?.toLong() ?: return
            if (at > 0 && at < b.size && seen.add(at)) directory(at, group, big, out, found, seen, depth + 1)
        }

        private fun block(e: Entry): ByteArray? {
            val n = (typeSize(e.type) * e.count).toInt()
            return if (n in 1..(16 shl 20)) b.read(e.valueAt, n) else null
        }

        private fun data(e: Entry, max: Int = 1 shl 16): ByteArray {
            val n = (typeSize(e.type).toLong() * e.count).coerceAtMost(max.toLong()).toInt()
            return b.read(e.valueAt, n)
        }

        /** Integer values of [e] (at most [max]). */
        fun numbers(e: Entry, max: Int = 256): List<Long> {
            val d = data(e, max * 8)
            val n = minOf(e.count, max.toLong()).toInt()
            val out = ArrayList<Long>(n)
            for (i in 0 until n) out.add(when (e.type) {
                1, 7 -> (d.getOrNull(i)?.toLong() ?: 0) and 0xFF
                6 -> d.getOrNull(i)?.toLong() ?: 0
                3 -> u16(d, i * 2).toLong()
                8 -> u16(d, i * 2).toShort().toLong()
                4, 13 -> u32(d, i * 4)
                9 -> u32(d, i * 4).toInt().toLong()
                16, 18 -> u64(d, i * 8)
                17 -> u64(d, i * 8)
                else -> return out
            })
            return out
        }

        private fun reals(e: Entry, max: Int = 256): List<Double> {
            val d = data(e, max * 8)
            val n = minOf(e.count, max.toLong()).toInt()
            return (0 until n).mapNotNull { i ->
                when (e.type) {
                    5 -> { val num = u32(d, i * 8); val den = u32(d, i * 8 + 4); if (den == 0L) (if (num == 0L) Double.NaN else Double.POSITIVE_INFINITY) else num.toDouble() / den }
                    10 -> { val num = u32(d, i * 8).toInt(); val den = u32(d, i * 8 + 4).toInt(); if (den == 0) (if (num == 0) Double.NaN else if (num > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY) else num.toDouble() / den }
                    11 -> java.lang.Float.intBitsToFloat(u32(d, i * 4).toInt()).toDouble()
                    12 -> java.lang.Double.longBitsToDouble(u64(d, i * 8))
                    else -> numbers(e, max).getOrNull(i)?.toDouble()
                }
            }
        }

        /** The value as shown and as a bare value (ExifTool's -n), or null when it isn't worth listing. */
        fun value(name: String, e: Entry): Pair<String, String>? {
            when (e.type) {
                2 -> { val s = B.cstring(data(e), 0, e.count.toInt(), Charsets.UTF_8); return s to s }
                7 -> return undefined(name, e)
            }
            if (name.startsWith("XP") && e.type == 1) {
                // Windows' own tags: UCS-2 text in bytes.
                val d = data(e)
                val s = String(d, Charsets.UTF_16LE).trimEnd('\u0000').trim()
                return s to s
            }
            if (e.type == 5 || e.type == 10 || e.type == 11 || e.type == 12) {
                val v = reals(e)
                if (v.isEmpty()) return null
                return when (name) {
                    "GPSLatitude", "GPSLongitude", "GPSDestLatitude", "GPSDestLongitude" -> {
                        val deg = v.getOrElse(0) { 0.0 } + v.getOrElse(1) { 0.0 } / 60 + v.getOrElse(2) { 0.0 } / 3600
                        dms(deg, null) to num(deg)
                    }
                    "GPSTimeStamp" -> {
                        val w = v.map { if (it.isNaN()) 0.0 else it }
                        val t = "%02d:%02d:%s".format(w.getOrElse(0) { 0.0 }.toInt(), w.getOrElse(1) { 0.0 }.toInt(), seconds(w.getOrElse(2) { 0.0 }))
                        t to t
                    }
                    "ExposureTime" -> exposure(v[0]) to num(v[0])
                    "ShutterSpeedValue" -> { val s = 2.0.pow(-v[0]); exposure(s) to num(s) }
                    "ApertureValue", "MaxApertureValue" -> { val f = 2.0.pow(v[0] / 2); "f/" + num(f, 2) to num(f) }
                    "FNumber" -> "f/" + num(v[0], 2) to num(v[0])
                    "FocalLength" -> num(v[0], 2) + " mm" to num(v[0])
                    "GPSAltitude" -> num(v[0], 2) + " m" to num(v[0])
                    "SubjectDistance" -> num(v[0], 3) + " m" to num(v[0])
                    "ExposureCompensation" -> num(v[0], 2) to num(v[0])
                    "LensInfo", "DNGLensInfo" -> lens(v) to v.joinToString(" ") { num(it) }
                    else -> v.joinToString(" ") { num(it) }.let { it to it }
                }
            }
            if (e.type == 13 || e.type == 18) return null
            val v = numbers(e)
            if (v.isEmpty()) return null
            if (v.size == 1) ExifTags.choice(name, v[0])?.let { return it to v[0].toString() }
            if (name == "CR2CFAPattern" && v.size == 1) when (v[0].toInt()) { 1 -> "0 1 1 2"; 2 -> "1 0 2 1"; 3 -> "2 1 1 0"; 4 -> "1 2 0 1"; else -> null }?.let { return it to it }
            return when (name) {
                "GPSVersionID", "DNGVersion", "DNGBackwardVersion" -> v.joinToString(".") to v.joinToString(" ")
                "ISO" -> v.joinToString(", ") to v.joinToString(" ")
                else -> if (v.size > 32) "(${v.size} values)" to "(${v.size} values)" else v.joinToString(" ").let { it to it }
            }
        }

        private fun undefined(name: String, e: Entry): Pair<String, String>? {
            val d = data(e)
            return when (name) {
                "ExifVersion", "FlashpixVersion", "InteropVersion" -> B.cstring(d, 0, d.size).let { it to it }
                "ComponentsConfiguration" -> d.joinToString(" ") { (it.toInt() and 0xFF).toString() }.let { raw ->
                    d.toList().mapNotNull { when (it.toInt()) { 1 -> "Y"; 2 -> "Cb"; 3 -> "Cr"; 4 -> "R"; 5 -> "G"; 6 -> "B"; else -> null } }.joinToString(", ") to raw
                }
                "UserComment", "GPSProcessingMethod", "GPSAreaInformation" -> comment(d).let { it to it }
                "FileSource", "SceneType" -> { val v = d.firstOrNull()?.toLong()?.and(0xFF) ?: return null; (ExifTags.choice(name, v) ?: "$v") to "$v" }
                "GPSVersionID" -> d.joinToString(".") { (it.toInt() and 0xFF).toString() } to d.joinToString(" ") { (it.toInt() and 0xFF).toString() }
                "RawDataUniqueID", "ImageUniqueID" -> d.joinToString("") { "%02X".format(it) }.let { it to it }
                else -> when {
                    d.isEmpty() -> null
                    // Short text kept as bytes ("0300"), or a few numbers.
                    d.size <= 64 && d.all { it in 32..126 || it == 0.toByte() } && d.any { it != 0.toByte() } -> B.cstring(d, 0, d.size).let { it to it }
                    d.size <= 32 -> d.joinToString(" ") { "${it.toInt() and 0xFF}" }.let { it to it }
                    else -> "(${e.count} bytes)" to "(Binary data ${e.count} bytes)"
                }
            }
        }
    }

    /** An EXIF comment: an 8-byte character code, then the text. */
    fun comment(d: ByteArray): String {
        if (d.size < 8) return B.cstring(d, 0, d.size)
        val code = B.ascii(d, 0, 8)
        val body = d.copyOfRange(8, d.size)
        return when {
            code.startsWith("UNICODE") -> {
                // UCS-2, in the order the file's TIFF header says; a byte-order mark wins.
                val be = body.size >= 2 && body[0] == 0.toByte() && body[1] != 0.toByte()
                String(body, if (be) Charsets.UTF_16BE else Charsets.UTF_16LE).trimEnd('\u0000', ' ')
            }
            code.startsWith("JIS") -> String(body, charset("Shift_JIS")).trimEnd('\u0000', ' ')
            code.startsWith("ASCII") || code.all { it == '\u0000' } -> B.cstring(body, 0, body.size, Charsets.UTF_8)
            else -> B.cstring(d, 0, d.size, Charsets.UTF_8)
        }.trim()
    }

    /** A number as ExifTool prints it (-n): up to 10 significant digits, no trailing zeros. */
    fun num(v: Double, maxDecimals: Int = 10): String {
        // 0/0 is how cameras write "not known"; ExifTool says "undef".
        if (v.isNaN() || v.isInfinite()) return if (v.isNaN()) "undef" else if (v > 0) "inf" else "-inf"
        if (v == v.roundToLong().toDouble() && abs(v) < 1e15) return v.roundToLong().toString()
        val s = java.math.BigDecimal(v).round(java.math.MathContext(10)).setScale(maxDecimals, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    private fun seconds(s: Double): String = if (s == s.toInt().toDouble()) "%02d".format(s.toInt()) else (if (s < 10) "0" else "") + num(s, 3)

    /** "1/250" for short times, else seconds. */
    fun exposure(s: Double): String = when {
        s <= 0 -> num(s)
        s < 0.25001 -> "1/" + num(1 / s, 0)
        else -> num(s, 1) + " s"
    }

    private fun lens(v: List<Double>): String {
        if (v.size < 4) return v.joinToString(" ") { num(it) }
        val focal = if (v[0] == v[1]) num(v[0], 1) + " mm" else num(v[0], 1) + "–" + num(v[1], 1) + " mm"
        if (v[2].isNaN() || v[2] == 0.0) return focal
        val ap = if (v[2] == v[3] || v[3] == 0.0 || v[3].isNaN()) "f/" + num(v[2], 1) else "f/" + num(v[2], 1) + "–" + num(v[3], 1)
        return "$focal $ap"
    }

    /** 40° 42' 46.08" N (or without a side when [lat] is null: the reference tag says it). */
    fun dms(deg: Double, lat: Boolean?): String {
        val a = abs(deg)
        val d = a.toInt(); val m = ((a - d) * 60).toInt(); val s = ((a - d) * 60 - m) * 60
        val side = when (lat) { true -> if (deg < 0) " S" else " N"; false -> if (deg < 0) " W" else " E"; null -> "" }
        return "$d° $m' ${"%.2f".format(java.util.Locale.ROOT, s)}\"$side"
    }
}
