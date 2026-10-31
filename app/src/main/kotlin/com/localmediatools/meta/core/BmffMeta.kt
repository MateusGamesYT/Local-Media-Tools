package com.localmediatools.meta.core

import com.localmediatools.i18n.tr

import java.util.Calendar
import java.util.TimeZone

/**
 * ISO media files — MP4, MOV, 3GP, M4A and M4V, HEIC and AVIF, Canon CR3: dates and duration, each
 * track's kind, codec, size, rotation and language, the place a phone recorded (Apple's and the
 * older ©xyz), iTunes tags, phone makers' keys, cover art, XMP, and HEIF's EXIF and XMP items.
 */
object BmffMeta {
    private class Box(val type: String, val at: Long, val headerSize: Int, val size: Long) {
        val body get() = at + headerSize
        val end get() = at + size
    }

    private const val XMP_UUID = "be7acfcb97a942e89c71999491e3afac"
    private const val CANON_UUID = "85c0b687820f11e08111f4ce462b6a48"

    fun read(f: MetaInput, out: Fields) {
        val top = boxes(f, 0, f.size)
        top.firstOrNull { it.type == "ftyp" }?.let { ftyp(f, it, out) }
        for (b in top) when (b.type) {
            "moov" -> moov(f, b, out)
            "mdat" -> { out.add("QuickTime", "MediaDataSize", "${b.end - b.body}"); out.add("QuickTime", "MediaDataOffset", "${b.body}") }
            "meta" -> heifMeta(f, b, out)
            "uuid" -> uuid(f, b, out)
        }
        // The average bit rate: the media data over the length.
        val size = out.list.firstOrNull { it.name == "MediaDataSize" }?.raw?.toDoubleOrNull()
        val secs = out.list.firstOrNull { it.group == "QuickTime" && it.name == "Duration" }?.raw?.toDoubleOrNull()
        if (size != null && size > 0 && secs != null && secs > 0) out.add("Composite", "AvgBitrate", "${Math.round(size * 8 / secs / 1000)} kbps", "${Math.round(size * 8 / secs)}")
    }

    /** The boxes between [from] and [to]; [anyType]: names needn't be letters (an item list's are numbers in phone files). */
    private fun boxes(f: MetaInput, from: Long, to: Long, anyType: Boolean = false): List<Box> {
        val list = ArrayList<Box>()
        var at = from
        while (at + 8 <= to && list.size < 10_000) {
            var size = f.u32be(at)
            val type = f.ascii(at + 4, 4)
            var header = 8
            if (size == 1L) { size = f.u64be(at + 8); header = 16 }
            if (size == 0L) size = to - at
            if (size < header || at + size > to + 8 || !anyType && !type.all { it.code in 32..126 || it == '©' }) break
            list.add(Box(type, at, header, minOf(size, to - at)))
            at += size
        }
        return list
    }

    private fun children(f: MetaInput, b: Box, skip: Int = 0) = boxes(f, b.body + skip, b.end)

    private fun ftyp(f: MetaInput, b: Box, out: Fields) {
        out.add("QuickTime", "MajorBrand", f.ascii(b.body, 4).trim())
        f.u32be(b.body + 4).let { v -> "%x.%x.%x".format(v shr 16, (v shr 8) and 0xFF, v and 0xFF).let { out.add("QuickTime", "MinorVersion", it, it) } }
        val n = ((b.size - b.headerSize - 8) / 4).coerceIn(0, 64).toInt()
        out.add("QuickTime", "CompatibleBrands", (0 until n).map { f.ascii(b.body + 8 + it * 4, 4).trim() }.filter { it.isNotEmpty() }.joinToString(", "))
    }

    /** Seconds since 1904 (QuickTime's epoch) as "YYYY:MM:DD HH:MM:SS" (UTC); null for 0. */
    private fun date(s: Long): String? {
        if (s <= 0) return null
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = (s - 2082844800L) * 1000
        return "%04d:%02d:%02d %02d:%02d:%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    /** A 16.16 or 8.8 fixed-point number. */
    private fun fixed(v: Long, bits: Int) = ExifReader.num(v.toInt().toDouble() / (1 shl bits))

    /** The 3×3 display matrix: a, b, c, d, x, y as 16.16 and u, v, w as 2.30 numbers. */
    private fun matrix(f: MetaInput, at: Long) = (0 until 9).joinToString(" ") { i ->
        val v = f.u32be(at + i * 4).toInt().toDouble()
        ExifReader.num(if (i % 3 == 2) v / (1 shl 30) else v / 65536, 8)
    }

    private fun stamp(s: Long, g: String, name: String, out: Fields) {
        // QuickTime times are UTC (phones and cameras store them so).
        date(s)?.let { out.add(g, name, "$it UTC", it) }
    }

    private fun moov(f: MetaInput, moov: Box, out: Fields) {
        var track = 0
        var movieScale = 0L
        for (b in children(f, moov)) when (b.type) {
            "mvhd" -> {
                val v = f.u8(b.body)
                val (c, m, ts, d) = if (v == 1) listOf(f.u64be(b.body + 4), f.u64be(b.body + 12), f.u32be(b.body + 20), f.u64be(b.body + 24))
                    else listOf(f.u32be(b.body + 4), f.u32be(b.body + 8), f.u32be(b.body + 12), f.u32be(b.body + 16))
                movieScale = ts
                val g = "QuickTime"
                out.add(g, "MovieHeaderVersion", "$v")
                stamp(c, g, "CreateDate", out); stamp(m, g, "ModifyDate", out)
                out.add(g, "TimeScale", "$ts")
                if (ts > 0) out.add(g, "Duration", duration(d.toDouble() / ts), ExifReader.num(d.toDouble() / ts))
                val p = b.body + if (v == 1) 32 else 20
                out.add(g, "PreferredRate", fixed(f.u32be(p), 16)); out.add(g, "PreferredVolume", fixed(f.u16be(p + 4).toShort().toLong(), 8))
                out.add(g, "MatrixStructure", matrix(f, p + 16))
                if (ts > 0) listOf("PreviewTime", "PreviewDuration", "PosterTime", "SelectionTime", "SelectionDuration", "CurrentTime").forEachIndexed { i, n ->
                    out.add(g, n, ExifReader.num(f.u32be(p + 52 + i * 4).toDouble() / ts))
                }
                out.add(g, "NextTrackID", "${f.u32be(p + 76)}")
            }
            "trak" -> { track++; trak(f, b, "Track$track", movieScale, out) }
            "udta" -> udta(f, b, "UserData", out)
            "meta" -> meta(f, b, "Keys", out)
            "uuid" -> uuid(f, b, out)
        }
    }

    private fun duration(s: Double): String {
        val t = s.toLong()
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60) else if (t >= 60) "%d:%02d".format(t / 60, t % 60) else ExifReader.num(s, 2) + " s"
    }

    private fun trak(f: MetaInput, trak: Box, g: String, movieScale: Long, out: Fields) {
        var mediaScale = 0L
        for (b in children(f, trak)) when (b.type) {
            "tkhd" -> {
                val v = f.u8(b.body)
                val base = b.body + if (v == 1) 32 else 20
                out.add(g, "TrackHeaderVersion", "$v")
                stamp(if (v == 1) f.u64be(b.body + 4) else f.u32be(b.body + 4), g, "TrackCreateDate", out)
                stamp(if (v == 1) f.u64be(b.body + 12) else f.u32be(b.body + 8), g, "TrackModifyDate", out)
                out.add(g, "TrackID", "${f.u32be(b.body + if (v == 1) 20 else 12)}")
                val td = if (v == 1) f.u64be(b.body + 28) else f.u32be(b.body + 20)
                if (movieScale > 0) out.add(g, "TrackDuration", duration(td.toDouble() / movieScale), ExifReader.num(td.toDouble() / movieScale))
                out.add(g, "TrackLayer", "${f.u16be(base + 12).toShort()}")
                out.add(g, "TrackVolume", fixed(f.u16be(base + 16).toShort().toLong(), 8))
                out.add(g, "MatrixStructure", matrix(f, base + 20))
                // The display matrix says how the picture is turned.
                val ma = base + 20
                val a = f.u32be(ma).toInt(); val bb = f.u32be(ma + 4).toInt()
                val w = f.u32be(ma + 36) / 65536.0; val h = f.u32be(ma + 40) / 65536.0
                if (w > 0 && h > 0) { out.add(g, "ImageWidth", ExifReader.num(w)); out.add(g, "ImageHeight", ExifReader.num(h)) }
                if (a != 0 || bb != 0) {
                    val deg = Math.toDegrees(kotlin.math.atan2(bb.toDouble(), a.toDouble())).let { ((it % 360) + 360) % 360 }.let { Math.round(it).toInt() }
                    if (w > 0) out.add(g, "Rotation", "$deg")
                }
            }
            "mdia" -> for (m in children(f, b)) when (m.type) {
                "mdhd" -> {
                    val v = f.u8(m.body)
                    val ts = if (v == 1) f.u32be(m.body + 20) else f.u32be(m.body + 12)
                    val d = if (v == 1) f.u64be(m.body + 24) else f.u32be(m.body + 16)
                    mediaScale = ts
                    out.add(g, "MediaHeaderVersion", "$v")
                    stamp(if (v == 1) f.u64be(m.body + 4) else f.u32be(m.body + 4), g, "MediaCreateDate", out)
                    stamp(if (v == 1) f.u64be(m.body + 12) else f.u32be(m.body + 8), g, "MediaModifyDate", out)
                    out.add(g, "MediaTimeScale", "$ts")
                    if (ts > 0) out.add(g, "MediaDuration", duration(d.toDouble() / ts), ExifReader.num(d.toDouble() / ts))
                    val lang = f.u16be(m.body + if (v == 1) 32 else 20)
                    if (lang in 1..0x7FFF) {
                        val code = String(charArrayOf((((lang shr 10) and 31) + 0x60).toChar(), (((lang shr 5) and 31) + 0x60).toChar(), ((lang and 31) + 0x60).toChar()))
                        if (code.all { it in 'a'..'z' }) out.add(g, "MediaLanguageCode", code)
                    }
                }
                "hdlr" -> {
                    val t = f.ascii(m.body + 8, 4)
                    f.ascii(m.body + 4, 4).let { if (it.isNotBlank() && it.all { c -> c in ' '..'~' } && it != "\u0000\u0000\u0000\u0000") out.add(g, "HandlerClass", it) }
                    f.ascii(m.body + 12, 4).let { if (it.isNotBlank() && it[0] != '\u0000') out.add(g, "HandlerVendorID", it) }
                    out.add(g, "HandlerType", when (t) { "vide" -> "Video Track"; "soun" -> "Audio Track"; "meta" -> "Metadata"; "text", "sbtl", "subt" -> "Subtitle"; "hint" -> "Hint Track"; else -> t }, t)
                    val name = B.cstring(f.bytes(m.body + 24, (m.end - m.body - 24).coerceIn(0, 256).toInt()), 0, 256, Charsets.UTF_8)
                    if (name.isNotEmpty()) out.add(g, "HandlerDescription", name)
                }
                "minf" -> for (x in children(f, m)) when (x.type) {
                    "vmhd" -> { out.add(g, "GraphicsMode", "${f.u16be(x.body + 4)}"); out.add(g, "OpColor", "${f.u16be(x.body + 6)} ${f.u16be(x.body + 8)} ${f.u16be(x.body + 10)}") }
                    "smhd" -> out.add(g, "Balance", fixed(f.u16be(x.body + 4).toShort().toLong(), 8))
                    "stbl" -> for (s in children(f, x)) when (s.type) {
                        "stsd" -> stsd(f, s, g, out)
                        "stts" -> if (mediaScale > 0) {
                            // Frames per second: the samples over their total duration.
                            val n = f.u32be(s.body + 4).coerceIn(0, 100_000).toInt()
                            var count = 0L; var total = 0L
                            val t = f.bytes(s.body + 8, n * 8)
                            for (i in 0 until t.size / 8) { val c = B.u32be(t, i * 8); count += c; total += c * B.u32be(t, i * 8 + 4) }
                            val video = out.list.any { it.group == g && it.name == "HandlerType" && it.raw == "vide" }
                            if (video && total > 0) out.add(g, "VideoFrameRate", ExifReader.num(count.toDouble() * mediaScale / total, 3), ExifReader.num(count.toDouble() * mediaScale / total))
                        }
                    }
                }
            }
            "udta" -> udta(f, b, g, out)
            "meta" -> meta(f, b, g, out)
        }
    }

    private fun stsd(f: MetaInput, b: Box, g: String, out: Fields) {
        val entries = boxes(f, b.body + 8, b.end)
        val e = entries.firstOrNull() ?: return
        val fourcc = e.type
        when {
            fourcc in setOf("avc1", "avc3", "hvc1", "hev1", "av01", "vp08", "vp09", "mp4v", "apcn", "apch", "apcs", "apco", "ap4h", "jpeg", "mjpa", "dvh1", "dvhe", "s263", "H263", "CRAW", "SVQ1", "SVQ3", "cvid", "rle ", "raw ", "dvc ", "dvcp", "dv5p", "xvid", "mjp2", "png ") -> {
                out.add(g, "CompressorID", fourcc, fourcc)
                f.ascii(e.body + 12, 4).let { if (it.isNotBlank() && it[0] != '\u0000') out.add(g, "VendorID", it) }
                out.add(g, "SourceImageWidth", "${f.u16be(e.body + 24)}"); out.add(g, "SourceImageHeight", "${f.u16be(e.body + 26)}")
                out.add(g, "XResolution", fixed(f.u32be(e.body + 28), 16)); out.add(g, "YResolution", fixed(f.u32be(e.body + 32), 16))
                val cn = f.u8(e.body + 42)
                if (cn in 1..31) out.add(g, "CompressorName", f.ascii(e.body + 43, cn).trim())
                out.add(g, "BitDepth", "${f.u16be(e.body + 74)}")
                sampleBoxes(f, e, 78, g, out)
            }
            fourcc in setOf("mp4a", "ac-3", "ec-3", "Opus", "alac", "fLaC", "samr", "sawb", "sowt", "twos", "lpcm", "ipcm", "mp3 ", ".mp3", "ulaw", "alaw") -> {
                out.add(g, "AudioFormat", fourcc.trim(), fourcc.trim())
                out.add(g, "AudioChannels", "${f.u16be(e.body + 16)}")
                out.add(g, "AudioBitsPerSample", "${f.u16be(e.body + 18)}")
                out.add(g, "AudioSampleRate", "${f.u32be(e.body + 24) shr 16}")
                sampleBoxes(f, e, 28, g, out)
            }
            else -> out.add(g, "SampleFormat", fourcc)
        }
    }

    /** The boxes inside a sample description, after its [header] bytes: pixel shape and bit rates. */
    private fun sampleBoxes(f: MetaInput, e: Box, header: Int, g: String, out: Fields) {
        for (c in boxes(f, e.body + header, e.end)) when (c.type) {
            "pasp" -> { val h = f.u32be(c.body); val v = f.u32be(c.body + 4); if (h > 0 && v > 0) out.add(g, "PixelAspectRatio", "$h:$v") }
            "btrt" -> { out.add(g, "BufferSize", "${f.u32be(c.body)}"); out.add(g, "MaxBitrate", "${f.u32be(c.body + 4)}"); out.add(g, "AverageBitrate", "${f.u32be(c.body + 8)}") }
        }
    }

    /** udta: QuickTime's ©-tags (©xyz location, ©nam…) and an iTunes metadata list inside it. */
    private fun udta(f: MetaInput, udta: Box, g: String, out: Fields) {
        for (b in children(f, udta)) {
            when {
                b.type == "meta" -> meta(f, b, "ItemList", out)
                b.type == "XMP_" -> XmpReader.read(f.bytes(b.body, (b.end - b.body).coerceAtMost(16L shl 20).toInt()), out)
                b.type.startsWith("©") -> {
                    // A QuickTime text: 2-byte length, 2-byte language, text.
                    val len = f.u16be(b.body)
                    val text = if (len in 1..(b.size - b.headerSize - 4)) String(f.bytes(b.body + 4, len), Charsets.UTF_8) else String(f.bytes(b.body, (b.end - b.body).coerceAtMost(4096).toInt()), Charsets.UTF_8)
                    val name = QT_NAMES[b.type.substring(1)] ?: continue
                    if (name == "GPSCoordinates") location(text, g, out) else out.add(g, name, text.trim('\u0000', ' '))
                }
                b.type == "auth" -> out.add(g, "Author", String(f.bytes(b.body + 6, (b.end - b.body - 6).coerceIn(0, 4096).toInt()), Charsets.UTF_8).trimEnd('\u0000'))
                b.type == "cprt" -> out.add(g, "Copyright", String(f.bytes(b.body + 6, (b.end - b.body - 6).coerceIn(0, 4096).toInt()), Charsets.UTF_8).trimEnd('\u0000'))
                b.type == "loci" -> loci(f, b, g, out)
                b.type == "SDLN" || b.type == "smta" -> out.add(g, "SamsungModel", tr("(Samsung's own data)"))
            }
        }
    }

    private val QT_NAMES = mapOf("xyz" to "GPSCoordinates", "nam" to "Title", "ART" to "Artist", "alb" to "Album", "day" to "ContentCreateDate",
        "cmt" to "Comment", "too" to "Encoder", "mak" to "Make", "mod" to "Model", "swr" to "SoftwareVersion", "des" to "Description",
        "inf" to "Information", "fmt" to "Format", "req" to "Requirements", "src" to "Source", "wrt" to "Composer", "gen" to "Genre",
        "lyr" to "Lyrics", "grp" to "Grouping", "dir" to "Director", "prd" to "Producer", "cpy" to "Copyright", "aut" to "Author", "enc" to "EncodedBy")

    /** 3GPP's location box: a place name, a role, and the position as fixed-point numbers. */
    private fun loci(f: MetaInput, b: Box, g: String, out: Fields) {
        val d = f.bytes(b.body, (b.end - b.body).coerceIn(0, 4096).toInt())
        var p = 6 // version and flags, language
        fun str(): String {
            if (p + 2 <= d.size && (d[p] == 0xFE.toByte() && d[p + 1] == 0xFF.toByte() || d[p] == 0xFF.toByte() && d[p + 1] == 0xFE.toByte())) {
                var e = p + 2
                while (e + 1 < d.size && !(d[e] == 0.toByte() && d[e + 1] == 0.toByte())) e += 2
                val s = String(d, p, e - p, Charsets.UTF_16); p = e + 2; return s
            }
            var e = p
            while (e < d.size && d[e] != 0.toByte()) e++
            val s = String(d, p, e - p, Charsets.UTF_8); p = e + 1; return s
        }
        val name = str()
        if (p + 13 > d.size) return
        val role = d[p].toInt(); p++
        fun fixed(o: Int) = B.u32be(d, o).toInt() / 65536.0
        val lon = fixed(p); val lat = fixed(p + 4); val alt = fixed(p + 8); p += 12
        val body = str(); val notes = str()
        val roleName = when (role) { 0 -> "shooting"; 1 -> "real"; 2 -> "fictional"; else -> "$role" }
        out.add(g, "LocationInformation", "${name.ifEmpty { tr("(none)") }} Role=$roleName Lat=${"%.5f".format(java.util.Locale.ROOT, lat)} Lon=${"%.5f".format(java.util.Locale.ROOT, lon)} Alt=${"%.2f".format(java.util.Locale.ROOT, alt)} Body=$body Notes=$notes", privacy = Privacy.LOCATION)
        if (lat != 0.0 || lon != 0.0) {
            out.add("Composite", "GPSLatitude", ExifReader.dms(lat, true), ExifReader.num(lat))
            out.add("Composite", "GPSLongitude", ExifReader.dms(lon, false), ExifReader.num(lon))
            out.add("Composite", "GPSPosition", "${ExifReader.dms(lat, true)}, ${ExifReader.dms(lon, false)}", "${ExifReader.num(lat)} ${ExifReader.num(lon)}")
        }
    }

    /** ISO 6709 ("+40.7128-074.0060+010.000/"): signed degrees and an optional altitude. */
    private fun location(s: String, g: String, out: Fields) {
        val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)?""").find(s) ?: return
        val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble(); val alt = m.groupValues[3].toDoubleOrNull()
        out.add(g, "GPSCoordinates", "${ExifReader.dms(lat, true)}, ${ExifReader.dms(lon, false)}" + (alt?.let { ", ${ExifReader.num(it, 1)} m" } ?: ""),
            listOfNotNull(lat, lon, alt).joinToString(" ") { ExifReader.num(it) })
        out.add("Composite", "GPSLatitude", ExifReader.dms(lat, true), ExifReader.num(lat))
        out.add("Composite", "GPSLongitude", ExifReader.dms(lon, false), ExifReader.num(lon))
        out.add("Composite", "GPSPosition", "${ExifReader.dms(lat, true)}, ${ExifReader.dms(lon, false)}", "${ExifReader.num(lat)} ${ExifReader.num(lon)}")
        alt?.let { out.add("Composite", "GPSAltitude", ExifReader.num(it, 1) + " m", ExifReader.num(it)) }
    }

    /** ISO 6709 text anywhere (Matroska tags, other boxes): the position, when it is one. */
    fun iso6709(s: String, g: String, out: Fields): Boolean {
        if (!Regex("""^\s*[+-]\d+(\.\d+)?[+-]\d+(\.\d+)?""").containsMatchIn(s)) return false
        location(s, g, out); return true
    }

    /** A meta box: phone keys (mdta handler: keys + ilst) or iTunes tags (mdir: ilst). */
    private fun meta(f: MetaInput, meta: Box, g: String, out: Fields) {
        // QuickTime's meta has no version field; ISO's does.
        val skip = if (f.ascii(meta.body + 4, 4) == "hdlr") 0 else 4
        val kids = children(f, meta, skip)
        val keys = ArrayList<String>()
        kids.firstOrNull { it.type == "keys" }?.let { k ->
            val n = f.u32be(k.body + 4).toInt()
            var at = k.body + 8
            repeat(n.coerceAtMost(1000)) {
                val sz = f.u32be(at).toInt()
                if (sz < 8) return@repeat
                keys.add(f.ascii(at + 8, sz - 8))
                at += sz
            }
        }
        kids.firstOrNull { it.type == "hdlr" }?.let { h ->
            val hg = if (g == "ItemList" || g == "Keys") "QuickTime" else g
            out.add(hg, "HandlerType", f.ascii(h.body + 8, 4))
            f.ascii(h.body + 12, 4).let { if (it.isNotBlank() && it[0] != '\u0000') out.add(hg, "HandlerVendorID", it) }
        }
        val ilst = kids.firstOrNull { it.type == "ilst" } ?: return
        for (item in boxes(f, ilst.body, ilst.end, anyType = true)) {
            val data = children(f, item).firstOrNull { it.type == "data" } ?: continue
            val type = f.u32be(data.body).toInt() and 0xFFFFFF
            val len = (data.end - data.body - 8).coerceIn(0, 1L shl 20).toInt()
            val bytes = f.bytes(data.body + 8, len)
            val keyIndex = if (keys.isNotEmpty()) (f.u32be(item.at + 4) - 1).toInt() else -1
            if (keyIndex in keys.indices) {
                val key = keys[keyIndex]
                val name = KEY_NAMES[key] ?: key.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() }
                val v = when (type) { 1 -> String(bytes, Charsets.UTF_8); 21, 22 -> number(bytes).toString(); 23 -> ExifReader.num(java.lang.Float.intBitsToFloat(B.u32be(bytes, 0).toInt()).toDouble()); else -> String(bytes, Charsets.UTF_8) }
                if (name == "GPSCoordinates") location(v, "Keys", out) else out.add("Keys", name, v)
                continue
            }
            val t = item.type
            val name = ITUNES[t] ?: continue
            when {
                t == "covr" -> out.add(g, "CoverArt", tr("({0} bytes of picture)", bytes.size), "${bytes.size}")
                t == "trkn" || t == "disk" -> if (bytes.size >= 6) out.add(g, name, "${B.u16be(bytes, 2)} of ${B.u16be(bytes, 4)}", "${B.u16be(bytes, 2)} ${B.u16be(bytes, 4)}")
                type == 21 || type == 0 && bytes.size in 1..8 && t in setOf("tmpo", "cpil", "pgap", "rtng", "stik") -> out.add(g, name, number(bytes).toString())
                else -> out.add(g, name, String(bytes, Charsets.UTF_8))
            }
        }
    }

    private fun number(b: ByteArray): Long = b.fold(0L) { v, x -> (v shl 8) or (x.toLong() and 0xFF) }

    private val ITUNES = mapOf("©nam" to "Title", "©ART" to "Artist", "aART" to "AlbumArtist", "©alb" to "Album", "©gen" to "Genre", "gnre" to "Genre",
        "©day" to "ContentCreateDate", "©wrt" to "Composer", "©cmt" to "Comment", "©too" to "Encoder", "©lyr" to "Lyrics", "©grp" to "Grouping",
        "trkn" to "TrackNumber", "disk" to "DiskNumber", "tmpo" to "BeatsPerMinute", "cpil" to "Compilation", "covr" to "CoverArt", "cprt" to "Copyright",
        "desc" to "Description", "ldes" to "LongDescription", "©enc" to "EncodedBy", "pgap" to "PlayGap", "rtng" to "Rating", "stik" to "MediaType",
        "©xyz" to "GPSCoordinates", "©mak" to "Make", "©mod" to "Model", "©swr" to "SoftwareVersion", "soal" to "SortAlbum", "soar" to "SortArtist", "sonm" to "SortName")

    private val KEY_NAMES = mapOf("com.apple.quicktime.location.ISO6709" to "GPSCoordinates", "com.apple.quicktime.make" to "Make",
        "com.apple.quicktime.model" to "Model", "com.apple.quicktime.software" to "Software", "com.apple.quicktime.creationdate" to "CreationDate",
        "com.apple.quicktime.content.identifier" to "ContentIdentifier", "com.apple.quicktime.location.accuracy.horizontal" to "LocationAccuracyHorizontal",
        "com.android.version" to "AndroidVersion", "com.android.capture.fps" to "AndroidCaptureFPS", "com.android.manufacturer" to "AndroidManufacturer",
        "com.android.model" to "AndroidModel", "com.apple.quicktime.title" to "DisplayName", "com.apple.quicktime.description" to "Description",
        "com.apple.quicktime.author" to "Author", "com.apple.quicktime.artist" to "Artist", "com.apple.quicktime.album" to "Album",
        "com.apple.quicktime.copyright" to "Copyright", "com.apple.quicktime.keywords" to "Keywords", "com.apple.quicktime.comment" to "Comment",
        "location" to "GPSCoordinates", "com.apple.quicktime.location.name" to "LocationName", "date" to "DateTimeOriginal")

    private fun uuid(f: MetaInput, b: Box, out: Fields) {
        val id = f.bytes(b.body, 16).joinToString("") { "%02x".format(it) }
        when (id) {
            XMP_UUID -> XmpReader.read(f.bytes(b.body + 16, (b.end - b.body - 16).coerceIn(0, 16L shl 20).toInt()), out)
            CANON_UUID -> for (c in boxes(f, b.body + 16, b.end)) when (c.type) {
                // CR3: CMT1 is IFD0, CMT2 the EXIF directory, CMT4 GPS (each a TIFF block of its own).
                "CMT1" -> ExifReader.read(InputBytes(f, c.body, c.end - c.body), out, "IFD0")
                "CMT2" -> ExifReader.read(InputBytes(f, c.body, c.end - c.body), out, "ExifIFD")
                "CMT3" -> out.add("MakerNotes", "MakerNote", tr("({0} bytes, the camera maker's own data)", c.end - c.body), "${c.end - c.body}")
                "CMT4" -> ExifReader.read(InputBytes(f, c.body, c.end - c.body), out, "GPS")
            }
        }
    }

    // ------------------------------------------------------------------ HEIF and AVIF
    private class Item(val id: Int, val type: String, val contentType: String?) { var offset = -1L; var length = 0L; var method = 0 }

    private fun heifMeta(f: MetaInput, meta: Box, out: Fields) {
        val kids = children(f, meta, 4)
        val items = HashMap<Int, Item>()
        var primary = -1
        kids.firstOrNull { it.type == "pitm" }?.let { primary = if (f.u8(it.body) == 0) f.u16be(it.body + 4) else f.u32be(it.body + 4).toInt() }
        kids.firstOrNull { it.type == "iinf" }?.let { iinf ->
            val v = f.u8(iinf.body)
            for (e in boxes(f, iinf.body + if (v == 0) 6 else 8, iinf.end)) {
                if (e.type != "infe") continue
                val ev = f.u8(e.body)
                if (ev < 2) continue
                val id = if (ev == 2) f.u16be(e.body + 4) else f.u32be(e.body + 4).toInt()
                val p = e.body + if (ev == 2) 8 else 10
                val type = f.ascii(p, 4)
                val rest = f.bytes(p + 4, (e.end - p - 4).coerceIn(0, 512).toInt())
                val name = B.cstring(rest, 0, rest.size)
                val content = if (type == "mime") B.cstring(rest, name.length + 1, rest.size - name.length - 1) else null
                items[id] = Item(id, type, content)
            }
        }
        kids.firstOrNull { it.type == "hdlr" }?.let { h -> f.ascii(h.body + 8, 4).let { t -> out.add("QuickTime", "HandlerType", if (t == "pict") "Picture" else t, t) } }
        kids.firstOrNull { it.type == "iloc" }?.let { iloc(f, it, items) }
        kids.firstOrNull { it.type == "iprp" }?.let { iprp ->
            val ipco = children(f, iprp).firstOrNull { it.type == "ipco" } ?: return@let
            val props = children(f, ipco)
            // The primary picture's size: the first ispe (good enough for single-picture files).
            props.firstOrNull { it.type == "ispe" }?.let {
                out.add("QuickTime", "ImageSpatialExtent", "${f.u32be(it.body + 4)} × ${f.u32be(it.body + 8)}", "${f.u32be(it.body + 4)} ${f.u32be(it.body + 8)}")
                out.add("QuickTime", "ImageWidth", "${f.u32be(it.body + 4)}"); out.add("QuickTime", "ImageHeight", "${f.u32be(it.body + 8)}")
            }
            props.firstOrNull { it.type == "irot" }?.let { out.add("QuickTime", "Rotation", "${(f.u8(it.body) and 3) * 90}") }
            props.firstOrNull { it.type == "colr" && f.ascii(it.body, 4).let { t -> t == "prof" || t == "rICC" } }?.let { IccReader.read(f.bytes(it.body + 4, (it.end - it.body - 4).coerceIn(0, 4L shl 20).toInt()), out) }
        }
        if (primary >= 0) items[primary]?.let { out.add("QuickTime", "PrimaryItemType", it.type) }
        val pictures = items.values.count { it.type in setOf("hvc1", "av01", "jpeg", "grid", "iovl", "iden") }
        if (pictures > 1) out.add("QuickTime", "Pictures", "$pictures", "$pictures")
        for (it in items.values) {
            if (it.offset < 0 || it.length <= 0 || it.method != 0) continue
            if (it.type == "Exif" && it.length > 4) {
                val skip = f.u32be(it.offset) + 4
                if (skip < it.length) ImageMeta.exifBlock(InputBytes(f, it.offset + skip, it.length - skip), out)
            }
            if (it.type == "mime" && it.contentType?.contains("rdf+xml") == true) XmpReader.read(f.bytes(it.offset, it.length.coerceAtMost(16L shl 20).toInt()), out)
        }
    }

    private fun iloc(f: MetaInput, b: Box, items: Map<Int, Item>) {
        val v = f.u8(b.body)
        val sizes = f.u16be(b.body + 4)
        val offSize = (sizes shr 12) and 15; val lenSize = (sizes shr 8) and 15; val baseSize = (sizes shr 4) and 15
        val idxSize = if (v == 1 || v == 2) sizes and 15 else 0
        var p = b.body + 6
        val count = if (v < 2) f.u16be(p).also { p += 2 } else f.u32be(p).toInt().also { p += 4 }
        fun read(n: Int): Long { val r = when (n) { 0 -> 0L; 4 -> f.u32be(p); 8 -> f.u64be(p); else -> 0L }; p += n; return r }
        repeat(count.coerceAtMost(10_000)) {
            val id = if (v < 2) f.u16be(p).toInt().also { p += 2 } else f.u32be(p).toInt().also { p += 4 }
            var method = 0
            if (v == 1 || v == 2) { method = f.u16be(p) and 15; p += 2 }
            p += 2 // data reference index
            val base = read(baseSize)
            val extents = f.u16be(p); p += 2
            var first = -1L; var total = 0L
            repeat(extents.coerceAtMost(1000)) {
                if (idxSize > 0) read(idxSize)
                val off = read(offSize); val len = read(lenSize)
                if (first < 0) first = base + off
                total += len
            }
            items[id]?.let { it.offset = first; it.length = total; it.method = method }
        }
    }
}
