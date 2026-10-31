package com.localmediatools.meta.core

import com.localmediatools.i18n.tr

import java.util.Calendar
import java.util.TimeZone

/** Videos and sound in other containers: Matroska/WebM, MP3 (ID3), FLAC, Ogg (Vorbis, Opus), WAV and AVI. */
object AvMeta {
    // ------------------------------------------------------------------ Matroska and WebM
    private class El(val id: Long, val at: Long, val dataAt: Long, val size: Long) { val end get() = dataAt + size }

    private fun vint(f: MetaInput, at: Long, keepMarker: Boolean): Pair<Long, Int>? {
        val first = f.u8(at)
        if (first <= 0) return null
        var len = 1
        while (len <= 8 && first and (0x80 shr (len - 1)) == 0) len++
        if (len > 8) return null
        var v = if (keepMarker) first.toLong() else (first and (0xFF shr len)).toLong()
        for (i in 1 until len) v = (v shl 8) or f.u8(at + i).toLong()
        return v to len
    }

    private fun elements(f: MetaInput, from: Long, to: Long, limit: Int = 200_000): List<El> {
        val out = ArrayList<El>()
        var at = from
        while (at < to && out.size < limit) {
            val (id, il) = vint(f, at, true) ?: break
            val (size0, sl) = vint(f, at + il, false) ?: break
            val unknown = size0 == (1L shl (7 * sl)) - 1
            val dataAt = at + il + sl
            // An unknown size, or one past the end (a cut-off file), runs to the end of the parent.
            val size = if (unknown || dataAt + size0 > to) to - dataAt else size0
            if (size < 0) break
            out.add(El(id, at, dataAt, size))
            at = dataAt + size
        }
        return out
    }

    private fun uint(f: MetaInput, e: El): Long = f.bytes(e.dataAt, e.size.coerceIn(0, 8).toInt()).fold(0L) { v, b -> (v shl 8) or (b.toLong() and 0xFF) }
    private fun float(f: MetaInput, e: El): Double = when (e.size) {
        4L -> java.lang.Float.intBitsToFloat(f.u32be(e.dataAt).toInt()).toDouble()
        8L -> java.lang.Double.longBitsToDouble(f.u64be(e.dataAt))
        else -> 0.0
    }
    private fun str(f: MetaInput, e: El) = B.cstring(f.bytes(e.dataAt, e.size.coerceIn(0, 1 shl 16).toInt()), 0, 1 shl 16, Charsets.UTF_8)

    fun matroska(f: MetaInput, out: Fields) {
        for (e in elements(f, 0, f.size, 64)) when (e.id) {
            0x1A45DFA3L -> for (h in elements(f, e.dataAt, e.end)) when (h.id) {
                0x4282L -> { out.add("Matroska", "DocType", str(f, h)); out.add("File", "FileType", if (str(f, h) == "webm") "WEBM" else "MKV") }
                0x4287L -> out.add("Matroska", "DocTypeVersion", "${uint(f, h)}")
                0x4285L -> out.add("Matroska", "DocTypeReadVersion", "${uint(f, h)}")
                0x4286L -> out.add("Matroska", "EBMLVersion", "${uint(f, h)}")
                0x42F7L -> out.add("Matroska", "EBMLReadVersion", "${uint(f, h)}")
            }
            0x18538067L -> segment(f, e, out)
        }
    }

    private fun segment(f: MetaInput, seg: El, out: Fields) {
        var scale = 1_000_000L
        var track = 0
        for (e in elements(f, seg.dataAt, seg.end)) when (e.id) {
            0x1549A966L -> {
                val info = elements(f, e.dataAt, e.end)
                info.firstOrNull { it.id == 0x2AD7B1L }?.let { scale = uint(f, it).takeIf { s -> s > 0 } ?: scale; out.add("Matroska", "TimecodeScale", ExifReader.num(scale / 1e9)) }
                for (i in info) when (i.id) {
                    0x4489L -> { val s = float(f, i) * scale / 1e9; out.add("Matroska", "Duration", ExifReader.num(s, 2) + " s", ExifReader.num(s)) }
                    0x4461L -> {
                        // Nanoseconds since 2001-01-01 UTC.
                        val ns = f.u64be(i.dataAt)
                        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.timeInMillis = 978307200000L + ns / 1_000_000
                        val v = "%04d:%02d:%02d %02d:%02d:%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
                        out.add("Matroska", "DateTimeOriginal", "$v UTC", v + "Z")
                    }
                    0x7BA9L -> out.add("Matroska", "Title", str(f, i))
                    0x4D80L -> out.add("Matroska", "MuxingApp", str(f, i))
                    0x5741L -> out.add("Matroska", "WritingApp", str(f, i))
                    0x73A4L -> out.add("Matroska", "SegmentUID", f.bytes(i.dataAt, i.size.coerceAtMost(16).toInt()).joinToString("") { "%02x".format(it) })
                }
            }
            0x1654AE6BL -> for (t in elements(f, e.dataAt, e.end)) if (t.id == 0xAEL) {
                track++
                val g = "Track$track"
                val kids = elements(f, t.dataAt, t.end)
                val type = kids.firstOrNull { it.id == 0x83L }?.let { uint(f, it).toInt() } ?: 0
                fun flag(v: Long) = if (v != 0L) "Yes" else "No"
                for (x in kids) when (x.id) {
                    0x83L -> out.add(g, "TrackType", when (type) { 1 -> "Video"; 2 -> "Audio"; 3 -> "Complex"; 16 -> "Logo"; 17 -> "Subtitle"; 18 -> "Buttons"; 32 -> "Control"; else -> "$type" }, "$type")
                    0xD7L -> out.add(g, "TrackNumber", "${uint(f, x)}")
                    0x73C5L -> out.add(g, "TrackUID", f.bytes(x.dataAt, x.size.coerceAtMost(16).toInt()).joinToString("") { "%02x".format(it) }, privacy = Privacy.IDENTIFIER)
                    0xB9L -> uint(f, x).let { out.add(g, "TrackUsed", flag(it), "$it") }
                    0x88L -> uint(f, x).let { out.add(g, "TrackDefault", flag(it), "$it") }
                    0x55AAL -> uint(f, x).let { out.add(g, "TrackForced", flag(it), "$it") }
                    0x9CL -> uint(f, x).let { out.add(g, "TrackLacing", flag(it), "$it") }
                    0x23314FL -> out.add(g, "TrackTimecodeScale", ExifReader.num(float(f, x)))
                    0xAAL -> uint(f, x).let { out.add(g, "CodecDecodeAll", flag(it), "$it") }
                    0x86L -> out.add(g, when (type) { 1 -> "VideoCodecID"; 2 -> "AudioCodecID"; 17 -> "SubtitleCodecID"; else -> "CodecID" }, str(f, x))
                    0x258688L -> out.add(g, when (type) { 1 -> "VideoCodecName"; 2 -> "AudioCodecName"; else -> "CodecName" }, str(f, x))
                    0x536EL -> out.add(g, "TrackName", str(f, x))
                    0x22B59CL -> out.add(g, "TrackLanguage", str(f, x))
                    0x22B59DL -> out.add(g, "TrackLanguageIETF", str(f, x))
                    0x23E383L -> {
                        val ns = uint(f, x)
                        out.add(g, "DefaultDuration", ExifReader.num(ns / 1e9))
                        if (type == 1 && ns > 0) out.add(g, "VideoFrameRate", ExifReader.num(1e9 / ns, 3), ExifReader.num(1e9 / ns))
                    }
                    0xE0L -> for (v in elements(f, x.dataAt, x.end)) when (v.id) {
                        0xB0L -> out.add(g, "ImageWidth", "${uint(f, v)}"); 0xBAL -> out.add(g, "ImageHeight", "${uint(f, v)}")
                        0x54B0L -> out.add(g, "DisplayWidth", "${uint(f, v)}"); 0x54BAL -> out.add(g, "DisplayHeight", "${uint(f, v)}")
                        0x9AL -> uint(f, v).let { out.add(g, "VideoScanType", if (it == 1L) "Interlaced" else "Progressive", "$it") }
                        0x54B2L -> uint(f, v).let { out.add(g, "DisplayUnit", when (it.toInt()) { 0 -> "Pixels"; 1 -> "cm"; 2 -> "inches"; 3 -> "Display Aspect Ratio"; else -> "$it" }, "$it") }
                    }
                    0xE1L -> for (a in elements(f, x.dataAt, x.end)) when (a.id) {
                        0xB5L -> out.add(g, "AudioSampleRate", ExifReader.num(float(f, a))); 0x9FL -> out.add(g, "AudioChannels", "${uint(f, a)}")
                        0x6264L -> out.add(g, "AudioBitsPerSample", "${uint(f, a)}")
                    }
                }
            }
            0x1254C367L -> for (tag in elements(f, e.dataAt, e.end)) if (tag.id == 0x7373L) for (st in elements(f, tag.dataAt, tag.end)) if (st.id == 0x67C8L) simpleTag(f, st, out)
        }
    }

    private fun simpleTag(f: MetaInput, st: El, out: Fields) {
        val kids = elements(f, st.dataAt, st.end)
        val name = kids.firstOrNull { it.id == 0x45A3L }?.let { str(f, it) } ?: return
        val value = kids.firstOrNull { it.id == 0x4487L }?.let { str(f, it) } ?: return
        if (name.uppercase() in setOf("LOCATION", "RECORDING_LOCATION") && BmffMeta.iso6709(value, "Matroska", out)) { for (k in kids) if (k.id == 0x67C8L) simpleTag(f, k, out); return }
        out.add("Matroska", name.lowercase().split('_', ' ').joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }, value)
        for (k in kids) if (k.id == 0x67C8L) simpleTag(f, k, out)
    }

    // ------------------------------------------------------------------ MP3: ID3 and the MPEG audio frames
    private val ID3_NAMES = mapOf(
        "TIT2" to "Title", "TPE1" to "Artist", "TALB" to "Album", "TYER" to "Year", "TDRC" to "RecordingTime", "TRCK" to "Track", "TCON" to "Genre",
        "TPE2" to "Band", "TCOM" to "Composer", "TENC" to "EncodedBy", "TSSE" to "EncoderSettings", "TCOP" to "Copyright", "TPOS" to "PartOfSet",
        "TPUB" to "Publisher", "TDOR" to "OriginalReleaseTime", "TBPM" to "BeatsPerMinute", "TLEN" to "Length", "TIT1" to "Grouping",
        "TIT3" to "Subtitle", "TPE3" to "Conductor", "TPE4" to "InterpretedBy", "TEXT" to "Lyricist", "TOAL" to "OriginalAlbum",
        "TOPE" to "OriginalArtist", "TLAN" to "Language", "TKEY" to "InitialKey", "TMED" to "Media", "TSRC" to "ISRC", "TDAT" to "Date",
        "TIME" to "Time", "TDEN" to "EncodingTime", "TDRL" to "ReleaseTime", "TDTG" to "TaggingTime", "TOWN" to "FileOwner",
        "TRSN" to "InternetRadioStationName", "TFLT" to "FileType", "TSOA" to "AlbumSortOrder", "TSOP" to "PerformerSortOrder", "TSOT" to "TitleSortOrder",
        "TCMP" to "Compilation", "TPE5" to "Performer",
        // ID3v2.2's three-letter names.
        "TT2" to "Title", "TP1" to "Artist", "TAL" to "Album", "TYE" to "Year", "TRK" to "Track", "TCO" to "Genre", "TP2" to "Band",
        "TCM" to "Composer", "TEN" to "EncodedBy", "TSS" to "EncoderSettings", "TCR" to "Copyright", "TPA" to "PartOfSet", "TPB" to "Publisher",
        "TBP" to "BeatsPerMinute", "TLE" to "Length", "TT1" to "Grouping", "TT3" to "Subtitle", "TXT" to "Lyricist",
    )

    /** Reads an ID3v2 tag at [at]; returns where it ends. */
    fun id3v2(f: MetaInput, at: Long, out: Fields): Long {
        if (!f.startsWith(at, "ID3")) return at
        val major = f.u8(at + 3); val flags = f.u8(at + 5)
        val size = syncsafe(f.bytes(at + 6, 4))
        val g = "ID3v2_$major"
        var data = f.bytes(at + 10, size.toInt().coerceAtMost(64 shl 20))
        if (major < 4 && flags and 0x80 != 0) data = unsync(data)
        var p = 0
        if (flags and 0x40 != 0 && major >= 3) p += if (major == 4) syncsafe(data.copyOfRange(0, 4)).toInt() else (B.u32be(data, 0) + 4).toInt()
        val idLen = if (major == 2) 3 else 4
        val hdr = if (major == 2) 6 else 10
        while (p + hdr <= data.size) {
            val id = B.ascii(data, p, idLen)
            if (id.isEmpty() || id[0] == '\u0000' || !id.all { it.isLetterOrDigit() }) break
            val len = when (major) { 2 -> (B.u32be(data, p + 3) shr 8).toInt(); 4 -> syncsafe(data.copyOfRange(p + 4, p + 8)).toInt(); else -> B.u32be(data, p + 4).toInt() }
            val fflags = if (major == 2) 0 else B.u16be(data, p + 8)
            val start = p + hdr
            if (len <= 0 || start + len > data.size) break
            var body = data.copyOfRange(start, start + len)
            if (major == 4 && fflags and 0x02 != 0) body = unsync(body)
            if (major == 4 && fflags and 0x01 != 0 && body.size >= 4) body = body.copyOfRange(4, body.size)
            frame(id, body, g, out)
            p = start + len
        }
        return at + 10 + size + (if (flags and 0x10 != 0) 10 else 0)
    }

    private fun frame(id: String, b: ByteArray, g: String, out: Fields) {
        if (b.isEmpty()) return
        when {
            id == "TXXX" || id == "TXX" -> {
                val (desc, rest) = text(b, 1, b[0].toInt(), splitFirst = true)
                out.add(g, "UserDefinedText", if (desc.isNotEmpty()) "($desc) $rest" else rest)
            }
            id.startsWith("T") -> {
                val name = ID3_NAMES[id] ?: return
                var v = text(b, 1, b[0].toInt()).second.replace('\u0000', '/')
                if (name == "Genre") v = genre(v)
                out.add(g, name, v)
            }
            id == "COMM" || id == "COM" || id == "USLT" || id == "ULT" -> {
                if (b.size < 4) return
                val (desc, text) = text(b, 4, b[0].toInt(), splitFirst = true)
                out.add(g, if (id.startsWith("U")) "Lyrics" else "Comment", if (desc.isNotEmpty() && !desc.startsWith("iTun")) "($desc) $text" else text)
            }
            id == "APIC" || id == "PIC" -> out.add(g, "Picture", tr("({0} bytes of picture)", b.size), "${b.size}", Privacy.HIDDEN_IMAGE)
            id == "PRIV" -> out.add(g, "Private", "(" + B.cstring(b, 0, b.size) + ", ${b.size} bytes)")
            id == "WXXX" -> out.add(g, "UserDefinedURL", text(b, 1, b[0].toInt(), splitFirst = true).second)
            id.startsWith("W") && id.length == 4 -> out.add(g, "URL", B.cstring(b, 0, b.size))
            id == "GEOB" -> out.add(g, "GeneralEncapsulatedObject", tr("({0} bytes)", b.size))
            id == "POPM" -> out.add(g, "Popularimeter", B.cstring(b, 0, b.size))
            id == "UFID" -> out.add(g, "UniqueFileID", B.cstring(b, 0, b.size), privacy = Privacy.IDENTIFIER)
        }
    }

    /** ID3 text in its encoding ([enc]: 0 Latin-1, 1 UTF-16 with BOM, 2 UTF-16BE, 3 UTF-8); with [splitFirst], (description, text). */
    private fun text(b: ByteArray, from: Int, enc: Int, splitFirst: Boolean = false): Pair<String, String> {
        val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        val wide = enc == 1 || enc == 2
        if (!splitFirst) return "" to String(b, from, maxOf(0, b.size - from), cs).trimEnd('\u0000').trim()
        var e = from
        if (wide) { while (e + 1 < b.size && !(b[e] == 0.toByte() && b[e + 1] == 0.toByte())) e += 2 }
        else while (e < b.size && b[e] != 0.toByte()) e++
        val desc = String(b, from, e - from, cs).trim()
        val rest = (e + if (wide) 2 else 1).coerceAtMost(b.size)
        // UTF-16 text after the description has its own byte-order mark.
        return desc to String(b, rest, b.size - rest, if (enc == 1) Charsets.UTF_16 else cs).trimEnd('\u0000').trim()
    }

    private fun genre(v: String): String {
        val m = Regex("""^\((\d+)\)(.*)$""").find(v) ?: return v.toIntOrNull()?.let { GENRES.getOrNull(it) } ?: v
        return m.groupValues[2].ifEmpty { GENRES.getOrNull(m.groupValues[1].toInt()) ?: v }
    }

    private val GENRES = listOf("Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies",
        "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack",
        "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game",
        "Sound Clip", "Gospel", "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock",
        "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult",
        "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock")

    private fun syncsafe(b: ByteArray): Long = b.fold(0L) { v, x -> (v shl 7) or (x.toLong() and 0x7F) }

    private fun unsync(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) { o.write(b[i].toInt()); if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++; i++ }
        return o.toByteArray()
    }

    fun mp3(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "MP3")
        var at = 0L
        while (f.startsWith(at, "ID3")) at = id3v2(f, at, out)
        val v1 = f.size >= 128 && f.startsWith(f.size - 128, "TAG")
        if (at > 0 || v1) out.add("File", "ID3Size", "${at + if (v1) 128 else 0} bytes", "${at + if (v1) 128 else 0}")
        // The first MPEG frame: what the sound is, and from a Xing/VBRI header how long.
        var p = at; var tries = 0
        while (p + 4 < f.size && tries < 65536) {
            if (f.u8(p) == 0xFF && f.u8(p + 1) and 0xE0 == 0xE0) { if (mpegFrame(f, p, f.size - p - if (v1) 128 else 0, out)) break }
            p++; tries++
        }
        if (v1) {
            val t = f.bytes(f.size - 128, 128)
            val g = "ID3v1"
            out.add(g, "Title", B.cstring(t, 3, 30)); out.add(g, "Artist", B.cstring(t, 33, 30)); out.add(g, "Album", B.cstring(t, 63, 30))
            out.add(g, "Year", B.cstring(t, 93, 4))
            if (t[125] == 0.toByte() && t[126] != 0.toByte()) { out.add(g, "Comment", B.cstring(t, 97, 28)); out.add(g, "Track", "${t[126].toInt() and 0xFF}") }
            else out.add(g, "Comment", B.cstring(t, 97, 30))
            (t[127].toInt() and 0xFF).let { if (it < GENRES.size) out.add(g, "Genre", GENRES[it], "$it") }
        }
    }

    private val RATES = arrayOf(intArrayOf(11025, 12000, 8000), intArrayOf(0, 0, 0), intArrayOf(22050, 24000, 16000), intArrayOf(44100, 48000, 32000))
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val BITRATES_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val BITRATES_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** Reads the frame header at [at]; [audio] is how many bytes of sound follow. */
    private fun mpegFrame(f: MetaInput, at: Long, audio: Long, out: Fields): Boolean {
        val h = f.u32be(at).toInt()
        val ver = (h shr 19) and 3; val layer = (h shr 17) and 3; val br = (h shr 12) and 15; val sr = (h shr 10) and 3
        if (ver == 1 || layer == 0 || br == 0 || br == 15 || sr == 3) return false
        val rate = RATES[ver][sr]
        val kbps = when { ver == 3 && layer == 1 -> BITRATES_V1_L3[br]; ver == 3 && layer == 2 -> BITRATES_V1_L2[br]; ver == 3 -> BITRATES_V1_L1[br]; layer == 3 -> BITRATES_V2_L1[br]; else -> BITRATES_V2[br] }
        val mode = (h shr 6) and 3
        val g = "MPEG"
        // Shown decoded; the raw values are the header's bits, as ExifTool gives them.
        out.add(g, "MPEGAudioVersion", when (ver) { 3 -> "1"; 2 -> "2"; else -> "2.5" }, "$ver")
        out.add(g, "AudioLayer", "${4 - layer}", "$layer")
        out.add(g, "SampleRate", "$rate", "$sr")
        out.add(g, "ChannelMode", when (mode) { 0 -> "Stereo"; 1 -> "Joint Stereo"; 2 -> "Dual Channel"; else -> "Single Channel" }, "$mode")
        if (layer == 1) {
            out.add(g, "MSStereo", if ((h shr 5) and 1 != 0) "On" else "Off", "${(h shr 5) and 1}")
            out.add(g, "IntensityStereo", if ((h shr 4) and 1 != 0) "On" else "Off", "${(h shr 4) and 1}")
        }
        out.add(g, "CopyrightFlag", if ((h shr 3) and 1 != 0) "True" else "False", "${(h shr 3) and 1}")
        out.add(g, "OriginalMedia", if ((h shr 2) and 1 != 0) "True" else "False", "${(h shr 2) and 1}")
        out.add(g, "Emphasis", when (h and 3) { 0 -> "None"; 1 -> "50/15 ms"; 2 -> "reserved"; else -> "CCIT J.17" }, "${h and 3}")
        // Xing/Info (VBR) or VBRI headers carry the frame count.
        val side = if (ver == 3) (if (mode == 3) 17 else 32) else (if (mode == 3) 9 else 17)
        val x = f.bytes(at + 4 + side, 16)
        val samplesPerFrame = if (layer == 3) 384 else if (layer == 2 || ver == 3) 1152 else 576
        if (B.starts(x, 0, "Xing") || B.starts(x, 0, "Info")) {
            val flags = B.u32be(x, 4)
            if (flags and 1L != 0L) {
                val frames = B.u32be(x, 8)
                out.add(g, "VBRFrames", "$frames")
                val s = frames.toDouble() * samplesPerFrame / rate
                out.add("Composite", "Duration", ExifReader.num(s, 2) + " s", ExifReader.num(s))
            }
            out.add(g, "AudioBitrate", "$kbps kbps", "${kbps * 1000}")
            if (flags and 2L != 0L) {
                val bytes = B.u32be(x, if (flags and 1L != 0L) 12 else 8)
                out.add(g, "VBRBytes", "$bytes")
                val frames = if (flags and 1L != 0L) B.u32be(x, 8) else 0L
                if (frames > 0 && B.starts(x, 0, "Xing")) {
                    val avg = bytes * 8.0 / (frames.toDouble() * samplesPerFrame / rate)
                    out.add("Composite", "AudioBitrate", tr("about {0} kbps (variable)", Math.round(avg / 1000)), ExifReader.num(avg))
                }
            }
        } else {
            out.add(g, "AudioBitrate", "$kbps kbps", "${kbps * 1000}")
            if (kbps > 0) { val s = audio * 8.0 / (kbps * 1000); out.add("Composite", "Duration", ExifReader.num(s, 2) + " s (approx)", ExifReader.num(s)) }
        }
        return true
    }

    // ------------------------------------------------------------------ FLAC and Ogg
    fun flac(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "FLAC")
        var at = 0L
        while (f.startsWith(at, "ID3")) at = id3v2(f, at, out)
        if (!f.startsWith(at, "fLaC")) return
        at += 4
        var last = false; var n = 0
        while (!last && at + 4 <= f.size && n++ < 1000) {
            val h = f.u32be(at)
            last = (h shr 31) == 1L
            val type = ((h shr 24) and 0x7F).toInt(); val len = (h and 0xFFFFFF).toInt()
            val d = if (type == 6) f.bytes(at + 4, minOf(len, 4096)) else f.bytes(at + 4, len)
            when (type) {
                0 -> streamInfo(d, out)
                4 -> vorbisComments(d, 0, "Vorbis", out)
                6 -> picture(f.bytes(at + 4, minOf(len, 1 shl 20)), out)
            }
            at += 4 + len
        }
    }

    /** A FLAC picture block (also base64 in Ogg files' comments): what the cover picture is. */
    private fun picture(b: ByteArray, out: Fields) {
        val g = "FLAC"
        val type = B.u32be(b, 0).toInt()
        out.add(g, "PictureType", PICTURE_TYPES.getOrNull(type) ?: "$type", "$type")
        var p = 4
        val ml = B.u32be(b, p).toInt(); p += 4
        if (ml !in 0..256 || p + ml > b.size) return
        out.add(g, "PictureMIMEType", String(b, p, ml, Charsets.US_ASCII)); p += ml
        val dl = B.u32be(b, p).toInt(); p += 4
        if (dl !in 0..(1 shl 16) || p + dl > b.size) return
        out.add(g, "PictureDescription", String(b, p, dl, Charsets.UTF_8), privacy = Privacy.TEXT); p += dl
        out.add(g, "PictureWidth", "${B.u32be(b, p)}"); out.add(g, "PictureHeight", "${B.u32be(b, p + 4)}")
        out.add(g, "PictureBitsPerPixel", "${B.u32be(b, p + 8)}"); out.add(g, "PictureIndexedColors", "${B.u32be(b, p + 12)}")
        val len = B.u32be(b, p + 16)
        out.add(g, "PictureLength", "$len"); out.add(g, "Picture", tr("({0} bytes of picture)", len), "$len", Privacy.HIDDEN_IMAGE)
    }

    private val PICTURE_TYPES = listOf("Other", "32x32 PNG Icon", "Other Icon", "Front Cover", "Back Cover", "Leaflet", "Media", "Lead Artist",
        "Artist", "Conductor", "Band", "Composer", "Lyricist", "Recording Studio or Location", "Recording Session", "Performance", "Capture from Movie or Video",
        "Bright(ly) Colored Fish", "Illustration", "Band Logo", "Publisher Logo")

    /** FLAC's stream description (the STREAMINFO block's 34 bytes). */
    private fun streamInfo(d: ByteArray, out: Fields) {
        if (d.size < 18) return
        val g = "FLAC"
        out.add(g, "BlockSizeMin", "${B.u16be(d, 0)}"); out.add(g, "BlockSizeMax", "${B.u16be(d, 2)}")
        out.add(g, "FrameSizeMin", "${B.u32be(d, 4) shr 8}"); out.add(g, "FrameSizeMax", "${B.u32be(d, 7) shr 8}")
        val v = B.u64be(d, 10)
        val rate = (v ushr 44).toInt(); val ch = ((v ushr 41) and 7).toInt() + 1; val bits = ((v ushr 36) and 31).toInt() + 1; val total = v and 0xFFFFFFFFFL
        out.add(g, "SampleRate", "$rate"); out.add(g, "Channels", "$ch"); out.add(g, "BitsPerSample", "$bits"); out.add(g, "TotalSamples", "$total")
        out.add(g, "MD5Signature", d.copyOfRange(18, minOf(34, d.size)).joinToString("") { "%02x".format(it) })
        if (rate > 0 && total > 0) { val s = total.toDouble() / rate; out.add("Composite", "Duration", ExifReader.num(s, 2) + " s", ExifReader.num(s)) }
    }

    /** Vorbis comments: a vendor string and KEY=value pairs (FLAC, Ogg Vorbis, Opus). */
    private fun vorbisComments(d: ByteArray, from: Int, g: String, out: Fields) {
        var p = from
        val vl = B.u32le(d, p).toInt(); p += 4
        if (vl < 0 || p + vl > d.size) return
        out.add(g, "Vendor", String(d, p, vl, Charsets.UTF_8)); p += vl
        val n = B.u32le(d, p).toInt(); p += 4
        for (k in 0 until n.coerceAtMost(10_000)) {
            val l = B.u32le(d, p).toInt(); p += 4
            if (l < 0 || p + l > d.size) break
            val c = String(d, p, l, Charsets.UTF_8); p += l
            val key = c.substringBefore('=')
            val value = c.substringAfter('=', "")
            if (key.equals("METADATA_BLOCK_PICTURE", true)) {
                val b = try { java.util.Base64.getMimeDecoder().decode(value) } catch (_: Exception) { null }
                if (b != null) picture(b, out) else out.add(g, "Picture", tr("({0} bytes of picture)", value.length * 3 / 4), "${value.length * 3 / 4}", Privacy.HIDDEN_IMAGE)
                continue
            }
            if (key.equals("COVERART", true)) { out.add(g, "CoverArt", tr("({0} bytes of picture)", value.length * 3 / 4), "${value.length * 3 / 4}", Privacy.HIDDEN_IMAGE); continue }
            out.add(g, VORBIS[key.uppercase()] ?: key.lowercase().split('_', ' ').joinToString("") { it.replaceFirstChar { ch -> ch.uppercaseChar() } }, value)
        }
    }

    private val VORBIS = mapOf("TRACKNUMBER" to "TrackNumber", "DISCNUMBER" to "DiscNumber", "ALBUMARTIST" to "AlbumArtist", "ENCODEDBY" to "EncodedBy",
        "ENCODER" to "Encoder", "TOTALTRACKS" to "TotalTracks", "TRACKTOTAL" to "TrackTotal", "REPLAYGAIN_TRACK_GAIN" to "ReplayGainTrackGain",
        "REPLAYGAIN_TRACK_PEAK" to "ReplayGainTrackPeak", "REPLAYGAIN_ALBUM_GAIN" to "ReplayGainAlbumGain", "REPLAYGAIN_ALBUM_PEAK" to "ReplayGainAlbumPeak",
        "COVERARTMIME" to "CoverArtMIMEType", "COVERART" to "CoverArt", "DATE" to "Date", "TITLE" to "Title", "ARTIST" to "Artist", "ALBUM" to "Album",
        "GENRE" to "Genre", "COMMENT" to "Comment", "DESCRIPTION" to "Description", "COPYRIGHT" to "Copyright", "LICENSE" to "License",
        "ORGANIZATION" to "Organization", "LOCATION" to "Location", "CONTACT" to "Contact", "ISRC" to "ISRC", "PERFORMER" to "Performer",
        "COMPOSER" to "Composer", "LYRICS" to "Lyrics", "VERSION" to "Version", "DISCTOTAL" to "DiscTotal", "TOTALDISCS" to "TotalDiscs")

    fun ogg(f: MetaInput, out: Fields) {
        // The first packets (identification and comments), put together from the first pages.
        val packets = ArrayList<ByteArray>()
        var at = 0L; var current = java.io.ByteArrayOutputStream(); var pages = 0
        while (at + 27 <= f.size && packets.size < 3 && pages++ < 200 && f.startsWith(at, "OggS")) {
            val segs = f.u8(at + 26)
            val table = f.bytes(at + 27, segs)
            var p = at + 27 + segs
            for (s in table) {
                val len = s.toInt() and 0xFF
                if (current.size() < (16 shl 20)) current.write(f.bytes(p, len))
                p += len
                if (len < 255) { packets.add(current.toByteArray()); current = java.io.ByteArrayOutputStream(); if (packets.size >= 3) break }
            }
            at = p
        }
        val id = packets.firstOrNull() ?: return
        var rate = 0L; var preSkip = 0
        when {
            B.starts(id, 0, "\u0001vorbis") -> {
                out.add("File", "FileType", "OGG")
                out.add("Vorbis", "VorbisVersion", "${B.u32le(id, 7)}"); out.add("Vorbis", "AudioChannels", "${id.getOrElse(11) { 0 }.toInt() and 0xFF}")
                rate = B.u32le(id, 12); out.add("Vorbis", "SampleRate", "$rate")
                out.add("Vorbis", "NominalBitrate", "${B.u32le(id, 20)}")
                packets.getOrNull(1)?.takeIf { B.starts(it, 0, "\u0003vorbis") }?.let { vorbisComments(it, 7, "Vorbis", out) }
            }
            B.starts(id, 0, "OpusHead") -> {
                out.add("File", "FileType", "OPUS")
                out.add("Opus", "OpusVersion", "${id.getOrElse(8) { 0 }}"); out.add("Opus", "AudioChannels", "${id.getOrElse(9) { 0 }}")
                preSkip = B.u16le(id, 10); rate = 48000; out.add("Opus", "SampleRate", "${B.u32le(id, 12)}")
                val gain = B.u16le(id, 16).toShort().toInt()
                out.add("Opus", "OutputGain", ExifReader.num(gain / 256.0, 2) + " dB", ExifReader.num(Math.pow(10.0, gain / 5120.0)))
                packets.getOrNull(1)?.takeIf { B.starts(it, 0, "OpusTags") }?.let { vorbisComments(it, 8, "Vorbis", out) }
            }
            B.starts(id, 0, "\u007FFLAC") && B.starts(id, 9, "fLaC") -> {
                // FLAC in Ogg: the stream description follows a short header; the comments are the next packet.
                out.add("File", "FileType", "OGG")
                streamInfo(id.copyOfRange(17, id.size), out)
                packets.getOrNull(1)?.takeIf { it.isNotEmpty() && it[0].toInt() and 0x7F == 4 }?.let { vorbisComments(it, 4, "Vorbis", out) }
            }
            B.starts(id, 0, "\u0080theora") -> { out.add("File", "FileType", "OGV"); out.add("Theora", "ImageWidth", "${(B.u32be(id, 14) shr 8)}"); out.add("Theora", "ImageHeight", "${(B.u32be(id, 17) shr 8)}") }
            else -> out.add("File", "FileType", "OGG")
        }
        // Duration from the last page's position.
        if (rate > 0) {
            val tail = f.bytes(maxOf(0, f.size - 65536), 65536)
            val i = String(tail, Charsets.ISO_8859_1).lastIndexOf("OggS")
            if (i >= 0 && i + 14 <= tail.size) {
                val granule = (0 until 8).fold(0L) { v, k -> v or ((tail[i + 6 + k].toLong() and 0xFF) shl (8 * k)) }
                if (granule > 0) { val s = (granule - preSkip).toDouble() / rate; out.add("Composite", "Duration", ExifReader.num(s, 2) + " s", ExifReader.num(s)) }
            }
        }
    }

    // ------------------------------------------------------------------ WAV and AVI (RIFF)
    private val INFO = mapOf("INAM" to "Title", "IART" to "Artist", "ICRD" to "DateCreated", "ISFT" to "Software", "ICMT" to "Comment", "ICOP" to "Copyright",
        "IGNR" to "Genre", "IPRD" to "Product", "IENG" to "Engineer", "ITCH" to "Technician", "ISBJ" to "Subject", "IKEY" to "Keywords",
        "ISRC" to "Source", "ITRK" to "TrackNumber", "IPRT" to "Part", "ILNG" to "Language", "ISMP" to "TimeCode", "IDIT" to "DateTimeOriginal",
        "IARL" to "ArchivalLocation", "ICMS" to "Commissioned", "ISTR" to "Starring", "IMED" to "Medium", "ICNT" to "Country")

    fun riff(f: MetaInput, out: Fields) {
        val form = f.ascii(8, 4)
        out.add("File", "FileType", if (form == "WAVE") "WAV" else if (form == "AVI ") "AVI" else form.trim())
        var dataSize = 0L; var byteRate = 0L
        var stream = ""
        fun waveFormat(body: Long) {
            val enc = f.u16le(body)
            out.add("RIFF", "Encoding", when (enc) { 1 -> "Microsoft PCM"; 2 -> "Microsoft ADPCM"; 3 -> "IEEE Float"; 6 -> "A-Law"; 7 -> "u-Law"; 0x11 -> "IMA ADPCM"; 0x55 -> "MP3"; 0xFF -> "AAC"; 0x2000 -> "AC-3"; 0xFFFE -> "Extensible"; else -> "$enc" }, "$enc")
            out.add("RIFF", "NumChannels", "${f.u16le(body + 2)}"); out.add("RIFF", "SampleRate", "${f.u32le(body + 4)}")
            byteRate = f.u32le(body + 8); out.add("RIFF", "AvgBytesPerSec", "$byteRate"); out.add("RIFF", "BitsPerSample", "${f.u16le(body + 14)}")
        }
        fun chunks(from: Long, to: Long, depth: Int) {
            var at = from; var n = 0
            while (at + 8 <= to && n++ < 10_000) {
                val id = f.ascii(at, 4); val len = f.u32le(at + 4)
                if (len < 0) break
                val body = at + 8
                when (id) {
                    "fmt " -> waveFormat(body)
                    "strf" -> when (stream) {
                        "auds" -> waveFormat(body)
                        "vids" -> {
                            // A bitmap header: the video frames' size and compression.
                            out.add("RIFF", "BMPVersion", "${f.u32le(body)}"); out.add("RIFF", "Planes", "${f.u16le(body + 12)}")
                            out.add("RIFF", "BitDepth", "${f.u16le(body + 14)}")
                            f.ascii(body + 16, 4).let { c -> out.add("RIFF", "Compression", if (c.all { it in ' '..'~' }) c.trim() else "${f.u32le(body + 16)}") }
                            out.add("RIFF", "ImageLength", "${f.u32le(body + 20)}")
                            out.add("RIFF", "PixelsPerMeterX", "${f.u32le(body + 24)}"); out.add("RIFF", "PixelsPerMeterY", "${f.u32le(body + 28)}")
                            out.add("RIFF", "NumColors", "${f.u32le(body + 32)}"); out.add("RIFF", "NumImportantColors", "${f.u32le(body + 36)}")
                        }
                    }
                    "IDIT" -> {
                        // "Mon Mar 10 15:04:43 2003".
                        val s = String(f.bytes(body, len.toInt().coerceAtMost(64)), Charsets.ISO_8859_1).trimEnd('\u0000', '\n').trim()
                        val m = Regex("""\w+\s+(\w{3})\s+(\d+)\s+(\d+:\d+:\d+)\s+(\d{4})""").find(s)
                        val mon = m?.let { listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").indexOf(it.groupValues[1]) + 1 } ?: 0
                        if (m != null && mon > 0) "%s:%02d:%02d %s".format(m.groupValues[4], mon, m.groupValues[2].toInt(), m.groupValues[3]).let { out.add("RIFF", "DateTimeOriginal", it, it) }
                        else out.add("RIFF", "DateTimeOriginal", s)
                    }
                    "_PMX" -> XmpReader.read(f.bytes(body, len.toInt().coerceAtMost(16 shl 20)), out)
                    "data" -> dataSize = len
                    "LIST" -> {
                        val type = f.ascii(body, 4)
                        if (depth < 4 && (type == "INFO" || type == "hdrl" || type == "strl")) chunks(body + 4, body + len, depth + 1)
                    }
                    "bext" -> {
                        val d = f.bytes(body, minOf(len.toInt(), 602))
                        out.add("RIFF", "Description", B.cstring(d, 0, 256)); out.add("RIFF", "Originator", B.cstring(d, 256, 32), privacy = Privacy.PERSON)
                        out.add("RIFF", "OriginatorReference", B.cstring(d, 288, 32)); out.add("RIFF", "DateTimeOriginal", (B.cstring(d, 320, 10) + " " + B.cstring(d, 330, 8)).trim())
                    }
                    "id3 ", "ID3 " -> id3v2(f, body, out)
                    "avih" -> {
                        val us = f.u32le(body)
                        if (us > 0) out.add("RIFF", "FrameRate", ExifReader.num(1e6 / us, 3), ExifReader.num(1e6 / us))
                        out.add("RIFF", "MaxDataRate", "${f.u32le(body + 4)}")
                        out.add("RIFF", "FrameCount", "${f.u32le(body + 16)}"); out.add("RIFF", "StreamCount", "${f.u32le(body + 24)}")
                        out.add("RIFF", "ImageWidth", "${f.u32le(body + 32)}"); out.add("RIFF", "ImageHeight", "${f.u32le(body + 36)}")
                        if (us > 0) { val s = f.u32le(body + 16) * us / 1e6; out.add("Composite", "Duration", ExifReader.num(s, 2) + " s", ExifReader.num(s)) }
                    }
                    "strh" -> {
                        val type = f.ascii(body, 4); val handler = f.ascii(body + 4, 4)
                        stream = type
                        out.add("RIFF", "StreamType", when (type) { "vids" -> "Video"; "auds" -> "Audio"; "txts" -> "Text"; "mids" -> "MIDI"; else -> type }, type)
                        if (handler.isNotBlank() && handler[0] != '\u0000') out.add("RIFF", if (type == "vids") "VideoCodec" else if (type == "auds") "AudioCodec" else "Codec", handler.trim())
                        val scale = f.u32le(body + 20); val rate = f.u32le(body + 24); val length = f.u32le(body + 32)
                        if (scale > 0 && type == "vids") { out.add("RIFF", "VideoFrameRate", ExifReader.num(rate.toDouble() / scale, 3), ExifReader.num(rate.toDouble() / scale)); out.add("RIFF", "VideoFrameCount", "$length") }
                        if (scale > 0 && type == "auds") { out.add("RIFF", "AudioSampleRate", ExifReader.num(rate.toDouble() / scale, 3), ExifReader.num(rate.toDouble() / scale)); out.add("RIFF", "AudioSampleCount", "$length") }
                        f.u32le(body + 40).let { q -> out.add("RIFF", "Quality", if (q == 0xFFFFFFFFL) tr("(default)") else "$q", "$q") }
                        out.add("RIFF", "SampleSize", "${f.u32le(body + 44)}")
                    }
                    else -> INFO[id]?.let { name -> out.add("RIFF", name, String(f.bytes(body, len.toInt().coerceAtMost(1 shl 16)), Charsets.UTF_8).trimEnd('\u0000')) }
                }
                at = body + len + (len and 1)
            }
        }
        chunks(12, f.size, 0)
        if (form == "WAVE" && byteRate > 0 && dataSize > 0) { val s = dataSize.toDouble() / byteRate; out.add("Composite", "Duration", ExifReader.num(s, 2) + " s", ExifReader.num(s)) }
    }
}
