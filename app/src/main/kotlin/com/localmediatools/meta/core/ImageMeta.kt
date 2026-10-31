package com.localmediatools.meta.core

import com.localmediatools.i18n.tr

import java.util.zip.Inflater

/** Photos and pictures: JPEG, PNG, WebP, GIF, BMP, Photoshop, TIFF and camera RAW files. */
object ImageMeta {
    /** Reads EXIF ([exif], a TIFF block), and the XMP, IPTC and ICC it points to. */
    fun exifBlock(exif: Bytes, out: Fields, base: Long = 0): ExifReader.Found {
        val found = ExifReader.read(exif, out, base = base)
        found.xmp?.let { XmpReader.read(it, out) }
        found.iptc?.let { IptcReader.read(it, out) }
        found.icc?.let { IccReader.read(it, out) }
        if (found.makerNote > 0) out.note(tr("The camera maker's own notes ({0} bytes) are listed but not read: each maker writes them its own way.", found.makerNote))
        return found
    }

    /** The EXIF of a JPEG held inside another file (a RAW's preview); [base]: where the JPEG starts in the file. */
    private fun embeddedJpegExif(j: ByteArray, base: Long, out: Fields) {
        var p = 2
        while (p + 4 < j.size && j[p] == 0xFF.toByte()) {
            val m = j[p + 1].toInt() and 0xFF; val len = B.u16be(j, p + 2)
            if (m == 0xE1 && B.starts(j, p + 4, "Exif\u0000")) { exifBlock(ArrayBytes(j, p + 10, minOf(len - 8, j.size - p - 10)), out, base + p + 10); break }
            if (m == 0xDA || len < 2) break
            p += 2 + len
        }
    }

    // ------------------------------------------------------------------ JPEG
    fun jpeg(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "JPEG")
        var at = 2L
        val icc = java.io.ByteArrayOutputStream()
        val ext = HashMap<String, ByteArray>()
        var mainXmp = false
        var sof = false
        var segments = 0
        while (at + 4 <= f.size && segments < 4000) {
            segments++
            if (f.u8(at) != 0xFF) { out.note(tr("The JPEG file is damaged after {0} bytes.", at)); break }
            val m = f.u8(at + 1)
            if (m == 0xFF) { at++; continue }
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { at += 2; continue }
            if (m == 0xD9) { at += 2; break }
            val len = f.u16be(at + 2)
            if (len < 2) break
            val data = if (m == 0xDA || len > (1 shl 20) && m != 0xE1) ByteArray(0) else f.bytes(at + 4, len - 2)
            when {
                m == 0xE0 && B.starts(data, 0, "JFIF\u0000") && data.size >= 14 -> {
                    val major = data[5].toInt() and 0xFF; val minor = data[6].toInt() and 0xFF
                    out.add("JFIF", "JFIFVersion", "$major.${"%02d".format(minor)}", "$major $minor")
                    out.add("JFIF", "ResolutionUnit", when (data[7].toInt()) { 0 -> "None"; 1 -> "inches"; 2 -> "cm"; else -> "${data[7]}" }, "${data[7]}")
                    out.add("JFIF", "XResolution", "${B.u16be(data, 8)}"); out.add("JFIF", "YResolution", "${B.u16be(data, 10)}")
                }
                m == 0xE1 && B.starts(data, 0, "Exif\u0000") -> exifBlock(ArrayBytes(data, 6), out, at + 10)
                m == 0xE1 && B.starts(data, 0, "http://ns.adobe.com/xap/1.0/\u0000") -> { XmpReader.read(data.copyOfRange(29, data.size), out); mainXmp = true }
                m == 0xE1 && B.starts(data, 0, "http://ns.adobe.com/xmp/extension/\u0000") && data.size > 35 + 40 -> {
                    // Extended XMP: chunks of one packet, by its GUID.
                    val guid = B.ascii(data, 35, 32)
                    val full = B.u32be(data, 67).toInt(); val off = B.u32be(data, 71).toInt()
                    if (full in 1..(32 shl 20) && off in 0 until full) {
                        val buf = ext.getOrPut(guid) { ByteArray(full) }
                        val chunk = data.copyOfRange(75, data.size)
                        System.arraycopy(chunk, 0, buf, off, minOf(chunk.size, full - off))
                    }
                }
                m == 0xE2 && B.starts(data, 0, "ICC_PROFILE\u0000") && data.size > 14 -> icc.write(data, 14, data.size - 14)
                m == 0xE2 && B.starts(data, 0, "MPF\u0000") -> mpf(data, at + 8, out)
                m == 0xED && B.starts(data, 0, "Photoshop 3.0\u0000") -> {
                    val ps = PhotoshopReader.read(data, 14, out)
                    ps.iptc?.let { IptcReader.read(it, out) }
                    if (!mainXmp) ps.xmp?.let { XmpReader.read(it, out) }
                }
                m == 0xEE && B.starts(data, 0, "Adobe") && data.size >= 12 -> {
                    out.add("Adobe", "DCTEncodeVersion", "${B.u16be(data, 5)}")
                    out.add("Adobe", "APP14Flags0", "${B.u16be(data, 7)}"); out.add("Adobe", "APP14Flags1", "${B.u16be(data, 9)}")
                    out.add("Adobe", "ColorTransform", when (data[11].toInt()) { 0 -> "Unknown (RGB or CMYK)"; 1 -> "YCbCr"; 2 -> "YCCK"; else -> "${data[11]}" }, "${data[11]}")
                }
                m == 0xEB && (B.starts(data, 0, "JP") || data.size > 16) -> out.note(tr("This picture carries Content Credentials (C2PA), a signed record of how it was made, which isn't read here."))
                m == 0xFE -> out.add("File", "Comment", String(data, Charsets.UTF_8).trimEnd('\u0000'))
                m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC && !sof && data.size >= 6 -> {
                    sof = true
                    out.add("File", "ImageWidth", "${B.u16be(data, 3)}"); out.add("File", "ImageHeight", "${B.u16be(data, 1)}")
                    out.add("File", "BitsPerSample", "${data[0].toInt() and 0xFF}")
                    val comps = data[5].toInt() and 0xFF
                    out.add("File", "ColorComponents", "$comps")
                    out.add("File", "EncodingProcess", PROCESS[m] ?: "SOF${m - 0xC0}", "${m - 0xC0}")
                    if (comps == 3 && data.size >= 6 + 9) {
                        val h = (data[7].toInt() shr 4) and 0xF; val v = data[7].toInt() and 0xF
                        out.add("File", "YCbCrSubSampling", when { h == 2 && v == 2 -> "YCbCr4:2:0 (2 2)"; h == 2 && v == 1 -> "YCbCr4:2:2 (2 1)"; h == 1 && v == 1 -> "YCbCr4:4:4 (1 1)"; else -> "$h $v" }, "$h $v")
                    }
                }
            }
            if (m == 0xDA) {
                // Image data until EOI; anything after it is another file stuck on (a motion photo's video, a second picture).
                val end = findEoi(f, at + 2 + len)
                if (end > 0 && end + 8 < f.size) {
                    val rest = f.size - end
                    val kind = when {
                        f.startsWith(end + 4, "ftyp") -> "a video (a motion photo)"
                        f.u16be(end) == 0xFFD8 -> "another picture"
                        else -> null
                    }
                    if (kind != null) { out.add("File", "TrailerData", tr("({0} bytes: {1})", rest, kind), "$rest", Privacy.HIDDEN_IMAGE) }
                }
                break
            }
            at += 2 + len
        }
        if (icc.size() > 0) IccReader.read(icc.toByteArray(), out)
        for (x in ext.values) XmpReader.read(x, out)
    }

    private val PROCESS = mapOf(0xC0 to "Baseline DCT, Huffman coding", 0xC1 to "Extended sequential DCT, Huffman coding", 0xC2 to "Progressive DCT, Huffman coding",
        0xC3 to "Lossless, Huffman coding", 0xC9 to "Extended sequential DCT, arithmetic coding", 0xCA to "Progressive DCT, arithmetic coding", 0xCB to "Lossless, arithmetic coding")

    /** Where the JPEG's data ends (after its FF D9), searching from [from]; -1 when it doesn't. */
    private fun findEoi(f: MetaInput, from: Long): Long {
        var at = from
        val chunk = 1 shl 16
        var prevFF = false
        while (at < f.size) {
            val b = f.bytes(at, chunk)
            if (b.isEmpty()) break
            for (i in b.indices) {
                val v = b[i].toInt() and 0xFF
                if (prevFF && v == 0xD9) return at + i + 1
                prevFF = v == 0xFF
            }
            at += b.size
        }
        return -1
    }

    /** CIPA's multi-picture format: how many pictures the file holds (previews, depth maps, the other eye of a 3D pair). */
    private fun mpf(d: ByteArray, base: Long, out: Fields) {
        // A small TIFF directory after "MPF\0", with its own tag numbers.
        val le = B.starts(d, 4, "II")
        fun u16(o: Int) = if (le) B.u16le(d, o) else B.u16be(d, o)
        fun u32(o: Int) = if (le) B.u32le(d, o) else B.u32be(d, o)
        val ifd = u32(8).toInt() + 4
        val n = u16(ifd)
        for (k in 0 until minOf(n, 64)) {
            val e = ifd + 2 + k * 12
            val count = u32(e + 4).toInt()
            when (u16(e)) {
                0xB000 -> out.add("MPF0", "MPFVersion", B.ascii(d, e + 8, 4))
                0xB001 -> {
                    out.add("MPF0", "NumberOfImages", "${u32(e + 8)}")
                    if (u32(e + 8) > 1) out.add("MPF0", "MPImage", tr("({0} more pictures inside: a preview, an HDR gain map, a depth map or the like)", u32(e + 8) - 1), "${u32(e + 8) - 1}", Privacy.HIDDEN_IMAGE)
                }
                0xB002 -> {
                    val at = u32(e + 8).toInt() + 4
                    for (i in 0 until minOf(count / 16, 64)) {
                        val p = at + i * 16
                        if (p + 16 > d.size) break
                        val attr = u32(p); val g = "MPImage${i + 1}"
                        val flags = (attr shr 27) and 0x1F
                        out.add(g, "MPImageFlags", listOfNotNull(if (flags and 4L != 0L) "Representative image" else null, if (flags and 8L != 0L) "Dependent child image" else null,
                            if (flags and 16L != 0L) "Dependent parent image" else null).joinToString(", ").ifEmpty { tr("(none)") }, "$flags")
                        out.add(g, "MPImageFormat", if ((attr shr 24) and 7 == 0L) "JPEG" else "${(attr shr 24) and 7}", "${(attr shr 24) and 7}")
                        val type = (attr and 0xFFFFFF).toInt()
                        out.add(g, "MPImageType", MP_TYPES[type] ?: "0x%06x".format(type), "$type", Privacy.HIDDEN_IMAGE)
                        out.add(g, "MPImageLength", "${u32(p + 4)}")
                        val start = u32(p + 8)
                        out.add(g, "MPImageStart", "${if (start == 0L) 0 else start + base}")
                        out.add(g, "DependentImage1EntryNumber", "${u16(p + 12)}"); out.add(g, "DependentImage2EntryNumber", "${u16(p + 14)}")
                    }
                }
                0xB004 -> out.add("MPF0", "TotalFrames", "${u32(e + 8)}")
            }
        }
    }

    private val MP_TYPES = mapOf(0x030000 to "Baseline MP Primary Image", 0x010001 to "Large Thumbnail (VGA equivalent)", 0x010002 to "Large Thumbnail (full HD equivalent)",
        0x010003 to "Large Thumbnail (4K equivalent)", 0x010004 to "Large Thumbnail (8K equivalent)", 0x010005 to "Large Thumbnail (16K equivalent)",
        0x020001 to "Multi-frame Panorama", 0x020002 to "Multi-frame Disparity", 0x020003 to "Multi-angle", 0x000000 to "Undefined")

    // ------------------------------------------------------------------ PNG
    fun png(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "PNG")
        var at = 8L
        var frames = 0
        var n = 0
        while (at + 12 <= f.size && n < 100_000) {
            n++
            val len = f.u32be(at); val type = f.ascii(at + 4, 4)
            if (len < 0 || at + 12 + len > f.size + 4) { out.note(tr("The PNG file is damaged.")); break }
            val read = type != "IDAT" && type != "fdAT" && len <= (16 shl 20)
            val d = if (read) f.bytes(at + 8, len.toInt()) else ByteArray(0)
            when (type) {
                "IHDR" -> {
                    out.add("PNG", "ImageWidth", "${B.u32be(d, 0)}"); out.add("PNG", "ImageHeight", "${B.u32be(d, 4)}")
                    out.add("PNG", "BitDepth", "${d.getOrElse(8) { 0 }}")
                    val ct = d.getOrElse(9) { 0 }.toInt()
                    out.add("PNG", "ColorType", when (ct) { 0 -> "Grayscale"; 2 -> "RGB"; 3 -> "Palette"; 4 -> "Grayscale with Alpha"; 6 -> "RGB with Alpha"; else -> "$ct" }, "$ct")
                    out.add("PNG", "Interlace", if (d.getOrElse(12) { 0 }.toInt() == 1) "Adam7 Interlace" else "Noninterlaced", "${d.getOrElse(12) { 0 }}")
                }
                "tEXt" -> { val k = B.cstring(d, 0, d.size); val v = String(d, k.length + 1, maxOf(0, d.size - k.length - 1), Charsets.ISO_8859_1); text(k, v, out) }
                "zTXt" -> { val k = B.cstring(d, 0, d.size); inflate(d, k.length + 2)?.let { text(k, String(it, Charsets.ISO_8859_1), out) } }
                "iTXt" -> {
                    val k = B.cstring(d, 0, d.size); var p = k.length + 1
                    val compressed = d.getOrElse(p) { 0 }.toInt() == 1; p += 2
                    val lang = B.cstring(d, p, d.size - p); p += lang.length + 1
                    val translated = B.cstring(d, p, d.size - p, Charsets.UTF_8); p += translated.toByteArray().size + 1
                    val body = if (compressed) inflate(d, p) else d.copyOfRange(minOf(p, d.size), d.size)
                    body?.let { text(k, String(it, Charsets.UTF_8), out) }
                }
                "eXIf" -> exifBlock(ArrayBytes(if (B.starts(d, 0, "Exif\u0000")) d.copyOfRange(6, d.size) else d), out)
                "iCCP" -> { val k = B.cstring(d, 0, d.size); out.add("PNG", "ProfileName", k); inflate(d, k.length + 2)?.let { IccReader.read(it, out) } }
                "pHYs" -> if (d.size >= 9) {
                    out.add("PNG-pHYs", "PixelsPerUnitX", "${B.u32be(d, 0)}"); out.add("PNG-pHYs", "PixelsPerUnitY", "${B.u32be(d, 4)}")
                    out.add("PNG-pHYs", "PixelUnits", if (d[8].toInt() == 1) "meters" else "Unknown", "${d[8]}")
                }
                "tIME" -> if (d.size >= 7) out.add("PNG", "ModifyDate", "%04d:%02d:%02d %02d:%02d:%02d".format(B.u16be(d, 0), d[2], d[3], d[4], d[5], d[6]))
                "gAMA" -> if (d.size >= 4) out.add("PNG", "Gamma", ExifReader.num(100000.0 / B.u32be(d, 0)))
                "sRGB" -> if (d.isNotEmpty()) out.add("PNG", "SRGBRendering", when (d[0].toInt()) { 0 -> "Perceptual"; 1 -> "Relative Colorimetric"; 2 -> "Saturation"; 3 -> "Absolute Colorimetric"; else -> "${d[0]}" }, "${d[0]}")
                "acTL" -> if (d.size >= 8) { out.add("PNG", "AnimationFrames", "${B.u32be(d, 0)}"); out.add("PNG", "AnimationPlays", if (B.u32be(d, 4) == 0L) "inf" else "${B.u32be(d, 4)}") }
                "fcTL" -> frames++
                "IEND" -> break
            }
            at += 12 + len
        }
    }

    private fun text(key: String, value: String, out: Fields) {
        if (key == "XML:com.adobe.xmp") { XmpReader.read(value.toByteArray(Charsets.UTF_8), out); return }
        if (key.startsWith("Raw profile type")) {
            // ImageMagick's hex dumps of EXIF, IPTC or XMP.
            val hex = value.trim().lines().drop(2).joinToString("").filter { it.isLetterOrDigit() }
            val bytes = runCatching { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull() ?: return
            when (key.removePrefix("Raw profile type ").lowercase()) {
                "exif" -> exifBlock(ArrayBytes(if (B.starts(bytes, 0, "Exif\u0000")) bytes.copyOfRange(6, bytes.size) else bytes), out)
                "iptc" -> IptcReader.read(bytes, out)
                "xmp" -> XmpReader.read(bytes, out)
                "icc", "icm" -> IccReader.read(bytes, out)
            }
            return
        }
        // ExifTool names PNG text by its keyword without spaces ("Creation Time" → CreationTime).
        out.add("PNG", key.replace(" ", ""), value)
    }

    private fun inflate(d: ByteArray, from: Int): ByteArray? {
        if (from >= d.size) return null
        val inf = Inflater()
        return try {
            inf.setInput(d, from, d.size - from)
            val o = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1 shl 14)
            while (!inf.finished() && o.size() < (16 shl 20)) { val k = inf.inflate(buf); if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break; o.write(buf, 0, k) }
            o.toByteArray()
        } catch (_: Exception) { null } finally { inf.end() }
    }

    // ------------------------------------------------------------------ WebP
    fun webp(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "WEBP")
        var at = 12L
        var frames = 0
        while (at + 8 <= f.size) {
            val type = f.ascii(at, 4); val len = f.u32le(at + 4)
            if (len < 0) break
            val d = if (type == "VP8 " || type == "VP8L" || type == "VP8X" || type == "ANIM" || len <= (16 shl 20) && type in setOf("EXIF", "XMP ", "ICCP")) f.bytes(at + 8, minOf(len, 16L shl 20).toInt()) else ByteArray(0)
            when (type) {
                "VP8 " -> if (d.size >= 10) { out.add("RIFF", "ImageWidth", "${B.u16le(d, 6) and 0x3FFF}"); out.add("RIFF", "ImageHeight", "${B.u16le(d, 8) and 0x3FFF}") }
                "VP8L" -> if (d.size >= 5) {
                    val v = B.u32le(d, 1)
                    out.add("RIFF", "ImageWidth", "${(v and 0x3FFF) + 1}"); out.add("RIFF", "ImageHeight", "${((v shr 14) and 0x3FFF) + 1}")
                }
                "VP8X" -> if (d.size >= 10) {
                    out.add("RIFF", "ImageWidth", "${(d[4].toInt() and 0xFF or ((d[5].toInt() and 0xFF) shl 8) or ((d[6].toInt() and 0xFF) shl 16)) + 1}")
                    out.add("RIFF", "ImageHeight", "${(d[7].toInt() and 0xFF or ((d[8].toInt() and 0xFF) shl 8) or ((d[9].toInt() and 0xFF) shl 16)) + 1}")
                }
                "EXIF" -> exifBlock(ArrayBytes(if (B.starts(d, 0, "Exif\u0000")) d.copyOfRange(6, d.size) else d), out)
                "XMP " -> XmpReader.read(d, out)
                "ICCP" -> IccReader.read(d, out)
                "ANIM" -> if (d.size >= 6) out.add("RIFF", "AnimationLoopCount", "${B.u16le(d, 4)}")
                "ANMF" -> frames++
            }
            at += 8 + len + (len and 1)
        }
        if (frames > 0) out.add("RIFF", "FrameCount", "$frames")
    }

    // ------------------------------------------------------------------ GIF
    fun gif(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "GIF")
        out.add("GIF", "GIFVersion", f.ascii(3, 3))
        out.add("GIF", "ImageWidth", "${f.u16le(6)}"); out.add("GIF", "ImageHeight", "${f.u16le(8)}")
        val flags = f.u8(10)
        out.add("GIF", "HasColorMap", if (flags and 0x80 != 0) "Yes" else "No", if (flags and 0x80 != 0) "1" else "0")
        out.add("GIF", "ColorResolutionDepth", "${((flags shr 4) and 7) + 1}")
        out.add("GIF", "BitsPerPixel", "${(flags and 7) + 1}")
        out.add("GIF", "BackgroundColor", "${f.u8(11)}")
        f.u8(12).let { out.add("GIF", "PixelAspectRatio", if (it == 0) "1" else ExifReader.num((it + 15) / 64.0)) }
        var at = 13L + if (flags and 0x80 != 0) 3 * (1 shl ((flags and 7) + 1)) else 0
        var frames = 0; var delay = 0L
        val comments = ArrayList<String>()
        var guard = 0
        while (at < f.size && guard++ < 200_000) {
            when (f.u8(at)) {
                0x21 -> {
                    val label = f.u8(at + 1)
                    var p = at + 2
                    val first = f.u8(p)
                    if (label == 0xF9 && first >= 4) delay += f.u16le(p + 2) * 10L
                    if (label == 0xFF && first == 11) {
                        val app = f.ascii(p + 1, 11)
                        if (app == "NETSCAPE2.0" && f.u8(p + 12) >= 3) out.add("GIF", "AnimationIterations", f.u16le(p + 14).let { if (it == 0) "Infinite" else "$it" }, "${f.u16le(p + 14)}")
                        if (app == "XMP DataXMP") {
                            // XMP written as raw bytes up to its "magic trailer".
                            val raw = f.bytes(p + 12, 1 shl 20)
                            val end = String(raw, Charsets.ISO_8859_1).indexOf("<?xpacket end").let { if (it < 0) raw.size else String(raw, Charsets.ISO_8859_1).indexOf("?>", it).let { e -> if (e < 0) raw.size else e + 2 } }
                            XmpReader.read(raw.copyOfRange(0, end), out)
                        }
                    }
                    val sb = StringBuilder()
                    val icc = if (label == 0xFF && first == 11 && f.ascii(p + 1, 11) == "ICCRGBG1012") java.io.ByteArrayOutputStream() else null
                    var sub = f.u8(p)
                    var block = 0
                    while (sub > 0 && p < f.size) {
                        if (label == 0xFE) sb.append(f.ascii(p + 1, sub))
                        if (icc != null && block > 0 && icc.size() < (16 shl 20)) icc.write(f.bytes(p + 1, sub))
                        p += 1 + sub; sub = f.u8(p); block++
                    }
                    if (label == 0xFE && sb.isNotBlank()) comments.add(sb.toString())
                    icc?.let { IccReader.read(it.toByteArray(), out) }
                    at = p + 1
                }
                0x2C -> {
                    frames++
                    val lf = f.u8(at + 9)
                    var p = at + 10 + if (lf and 0x80 != 0) 3 * (1 shl ((lf and 7) + 1)) else 0
                    p++ // LZW minimum code size
                    var sub = f.u8(p)
                    while (sub > 0 && p < f.size) { p += 1 + sub; sub = f.u8(p) }
                    at = p + 1
                }
                0x3B -> break
                else -> break
            }
        }
        out.add("GIF", "FrameCount", "$frames")
        if (frames > 1) out.add("GIF", "Duration", ExifReader.num(delay / 1000.0, 2) + " s", ExifReader.num(delay / 1000.0))
        for (c in comments) out.add("GIF", "Comment", c)
    }

    private val RAF_TAGS = mapOf(0x100 to "RawImageFullSize", 0x110 to "RawImageCropTopLeft", 0x111 to "RawImageCroppedSize", 0x121 to "RawImageSize",
        0x130 to "FujiLayout", 0x2000 to "WB_GRGBLevelsAuto", 0x2100 to "WB_GRGBLevelsDaylight", 0x2200 to "WB_GRGBLevelsCloudy",
        0x2300 to "WB_GRGBLevelsDaylightFluor", 0x2301 to "WB_GRGBLevelsDayWhiteFluor", 0x2302 to "WB_GRGBLevelsWhiteFluorescent",
        0x2310 to "WB_GRGBLevelsWarmWhiteFluor", 0x2311 to "WB_GRGBLevelsLivingRoomWarmWhiteFluor", 0x2400 to "WB_GRGBLevelsTungsten",
        0x2FF0 to "WB_GRGBLevels", 0x9200 to "RelativeExposure", 0x9650 to "RawExposureBias")

    // ------------------------------------------------------------------ BMP and Photoshop
    fun bmp(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "BMP")
        val h = f.bytes(14, 40)
        out.add("File", "ImageWidth", "${B.u32le(h, 4).toInt()}"); out.add("File", "ImageHeight", "${kotlin.math.abs(B.u32le(h, 8).toInt())}")
        out.add("File", "BitDepth", "${B.u16le(h, 14)}")
        val c = B.u32le(h, 16).toInt()
        out.add("File", "Compression", when (c) { 0 -> "None"; 1 -> "8-Bit RLE"; 2 -> "4-Bit RLE"; 3 -> "Bitfields"; 4 -> "JPEG"; 5 -> "PNG"; else -> "$c" }, "$c")
        if (B.u32le(h, 24) > 0) out.add("File", "PixelsPerMeterX", "${B.u32le(h, 24)}")
        if (B.u32le(h, 28) > 0) out.add("File", "PixelsPerMeterY", "${B.u32le(h, 28)}")
    }

    fun psd(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "PSD")
        out.add("Photoshop", "NumChannels", "${f.u16be(12)}")
        out.add("Photoshop", "ImageHeight", "${f.u32be(14)}"); out.add("Photoshop", "ImageWidth", "${f.u32be(18)}")
        out.add("Photoshop", "BitDepth", "${f.u16be(22)}")
        val mode = f.u16be(24)
        out.add("Photoshop", "ColorMode", when (mode) { 0 -> "Bitmap"; 1 -> "Grayscale"; 2 -> "Indexed"; 3 -> "RGB"; 4 -> "CMYK"; 7 -> "Multichannel"; 8 -> "Duotone"; 9 -> "Lab"; else -> "$mode" }, "$mode")
        val cmLen = f.u32be(26)
        val resAt = 30 + cmLen
        val resLen = f.u32be(resAt)
        if (resLen in 1..(64L shl 20)) {
            val d = f.bytes(resAt + 4, resLen.toInt())
            val ps = PhotoshopReader.read(d, 0, out)
            ps.exif?.let { exifBlock(ArrayBytes(it), out) }
            ps.iptc?.let { IptcReader.read(it, out) }
            ps.xmp?.let { XmpReader.read(it, out) }
            ps.icc?.let { IccReader.read(it, out) }
        }
    }

    /** TIFF and the camera RAW files built like it (DNG, NEF, CR2, ARW, ORF, RW2, PEF…). */
    fun tiff(f: MetaInput, out: Fields, kind: String) {
        out.add("File", "FileType", kind)
        val found = exifBlock(InputBytes(f), out)
        found.embeddedJpeg?.let { embeddedJpegExif(it, found.embeddedJpegAt, out) }
    }

    /** Fujifilm RAF: a header, then a JPEG preview holding the EXIF. */
    fun raf(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "RAF")
        out.add("RAF", "RAFVersion", f.ascii(60, 4))
        out.add("RAF", "CameraModel", B.cstring(f.bytes(28, 32), 0, 32))
        val jpegAt = f.u32be(84); val jpegLen = f.u32be(88)
        if (jpegAt > 0 && jpegLen > 0 && jpegAt + jpegLen <= f.size) {
            embeddedJpegExif(f.bytes(jpegAt, minOf(jpegLen, 1L shl 20).toInt()), jpegAt, out)
        }
        // The RAW's own directory: sizes, the sensor layout and white balance.
        val dirAt = f.u32be(92); val dirLen = f.u32be(96)
        if (dirAt > 0 && dirLen in 4..(1L shl 20) && dirAt + dirLen <= f.size) {
            val d = f.bytes(dirAt, dirLen.toInt())
            val n = B.u32be(d, 0).toInt()
            var p = 4
            for (k in 0 until minOf(n, 500)) {
                if (p + 4 > d.size) break
                val tag = B.u16be(d, p); val size = B.u16be(d, p + 2)
                val v = d.copyOfRange(p + 4, minOf(d.size, p + 4 + size))
                fun shorts(max: Int = 64) = (0 until minOf(v.size / 2, max)).joinToString(" ") { "${B.u16be(v, it * 2)}" }
                RAF_TAGS[tag]?.let { name ->
                    when {
                        tag == 0x130 -> out.add("RAF", name, v.take(4).joinToString(" ") { "${it.toInt() and 0xFF}" })
                        // Sizes are stored height first.
                        tag in 0x100..0x121 && v.size >= 4 -> "${B.u16be(v, 2)} ${B.u16be(v, 0)}".let { out.add("RAF", name, it.replace(" ", " × "), it) }
                        tag in 0x2000..0x2FF0 -> out.add("RAF", name, shorts(4))
                        tag == 0x9200 || tag == 0x9650 -> if (v.size >= 4) {
                            // Two signed 16-bit numbers: numerator and denominator.
                            val num = B.u16be(v, 0).toShort().toDouble(); val den = B.u16be(v, 2).toShort().toDouble()
                            out.add("RAF", name, ExifReader.num(if (den == 0.0) 0.0 else num / den, 3))
                        }
                        else -> out.add("RAF", name, shorts())
                    }
                }
                p += 4 + size
            }
        }
    }
}
