package com.localmediatools.meta.core

import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Reads everything a file says about itself. The kind of file is recognised from its first bytes
 * (the name's extension only breaks ties), so a renamed file is still read right.
 */
object MetaReader {
    fun read(file: File, name: String = file.name, modifiedMs: Long = file.lastModified()): MetaReport =
        MetaInput(file).use { read(it, name, modifiedMs) }

    fun read(f: MetaInput, name: String, modifiedMs: Long): MetaReport {
        val out = Fields()
        out.add("File", "FileName", name, privacy = Privacy.TEXT)
        out.add("File", "FileSize", size(f.size), "${f.size}")
        if (modifiedMs > 0) stamp(modifiedMs).let { out.add("File", "FileModifyDate", it, it, Privacy.TIME) }
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = detect(f, ext)
        try {
            when (kind) {
                "JPEG" -> ImageMeta.jpeg(f, out)
                "PNG" -> ImageMeta.png(f, out)
                "WEBP" -> ImageMeta.webp(f, out)
                "GIF" -> ImageMeta.gif(f, out)
                "BMP" -> ImageMeta.bmp(f, out)
                "PSD" -> ImageMeta.psd(f, out)
                "RAF" -> ImageMeta.raf(f, out)
                "TIFF", "DNG", "CR2", "NEF", "NRW", "ARW", "SR2", "SRF", "PEF", "ORF", "RW2", "SRW", "ERF", "3FR", "IIQ", "MOS", "MEF", "KDC", "DCR", "X3F" -> ImageMeta.tiff(f, out, kind)
                "BMFF" -> { BmffMeta.read(f, out); if (out.list.none { it.name == "FileType" }) out.add("File", "FileType", bmffType(f)) }
                "MKV" -> AvMeta.matroska(f, out)
                "MP3" -> AvMeta.mp3(f, out)
                "FLAC" -> AvMeta.flac(f, out)
                "OGG" -> AvMeta.ogg(f, out)
                "RIFF" -> AvMeta.riff(f, out)
                "PDF" -> PdfMeta.read(f, out)
                "ZIP" -> OfficeMeta.zipDocument(f, out)
                "OLE2" -> OfficeMeta.ole2(f, out, ext)
                "RTF" -> OfficeMeta.rtf(f, out)
                "HTML" -> OfficeMeta.html(f, out)
                "XML", "SVG" -> { OfficeMeta.text(f, out, kind); if (kind == "SVG") OfficeMeta.svg(f, out); XmpReader.read(f.bytes(0, 16 shl 20), out) }
                "TXT" -> OfficeMeta.text(f, out, if (ext in TEXT_KINDS) ext.uppercase() else "TXT")
                else -> {}
            }
        } catch (e: Exception) {
            out.note("Part of the file couldn't be read: it may be damaged.")
        } catch (e: StackOverflowError) {
            out.note("Part of the file couldn't be read: it may be damaged.")
        } catch (e: OutOfMemoryError) {
            out.note("Part of the file is too big to read on this phone.")
        }
        if (out.list.none { it.group == "File" && it.name == "FileType" }) out.add("File", "FileType", if (kind == "Unknown") "(not recognised)" else kind)
        val type = out.list.first { it.group == "File" && it.name == "FileType" }.value
        MIME[type]?.let { out.add("File", "MIMEType", it) }
        imageSize(out)
        // The same field read twice (a reader and a fallback) shows once; a derived value (Composite) once per name.
        val seen = HashSet<String>()
        val fields = out.list.filter { seen.add(if (it.group == "Composite") "Composite\u0000${it.name}" else "${it.group}\u0000${it.name}\u0000${it.value}") }
        return MetaReport(type, fields, out.notes)
    }

    private val TEXT_KINDS = setOf("csv", "tsv", "md", "json", "log", "ini", "yaml", "yml", "srt", "vtt", "kt", "java", "py", "js", "css", "sh")

    private val MIME = mapOf("JPEG" to "image/jpeg", "PNG" to "image/png", "WEBP" to "image/webp", "GIF" to "image/gif", "BMP" to "image/bmp",
        "PSD" to "application/vnd.adobe.photoshop", "TIFF" to "image/tiff", "DNG" to "image/x-adobe-dng", "CR2" to "image/x-canon-cr2",
        "CR3" to "image/x-canon-cr3", "NEF" to "image/x-nikon-nef", "ARW" to "image/x-sony-arw", "ORF" to "image/x-olympus-orf",
        "RW2" to "image/x-panasonic-rw2", "RAF" to "image/x-fujifilm-raf", "PEF" to "image/x-pentax-pef", "HEIC" to "image/heic", "HEIF" to "image/heif", "AVIF" to "image/avif",
        "MP4" to "video/mp4", "MOV" to "video/quicktime", "3GP" to "video/3gpp", "M4A" to "audio/mp4", "M4V" to "video/x-m4v", "MKV" to "video/x-matroska",
        "WEBM" to "video/webm", "MP3" to "audio/mpeg", "FLAC" to "audio/flac", "OGG" to "audio/ogg", "OPUS" to "audio/ogg", "WAV" to "audio/x-wav",
        "AVI" to "video/x-msvideo", "PDF" to "application/pdf", "DOCX" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "XLSX" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "PPTX" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "DOC" to "application/msword", "DOCM" to "application/vnd.ms-word.document.macroEnabled.12", "XLSM" to "application/vnd.ms-excel.sheet.macroEnabled.12",
        "PPTM" to "application/vnd.ms-powerpoint.presentation.macroEnabled.12", "XLS" to "application/vnd.ms-excel", "PPT" to "application/vnd.ms-powerpoint", "ODT" to "application/vnd.oasis.opendocument.text",
        "ODS" to "application/vnd.oasis.opendocument.spreadsheet", "ODP" to "application/vnd.oasis.opendocument.presentation", "EPUB" to "application/epub+zip",
        "RTF" to "text/rtf", "HTML" to "text/html", "TXT" to "text/plain", "CSV" to "text/csv", "ZIP" to "application/zip", "XML" to "application/xml",
        "SVG" to "image/svg+xml", "APK" to "application/vnd.android.package-archive")

    /** What the file is, from its first bytes. */
    fun detect(f: MetaInput, ext: String): String {
        val h = f.bytes(0, 4096)
        fun s(o: Int, t: String) = B.starts(h, o, t)
        fun u(o: Int) = if (o < h.size) h[o].toInt() and 0xFF else -1
        return when {
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> "JPEG"
            s(0, "\u0089PNG\r\n") -> "PNG"
            s(0, "GIF8") -> "GIF"
            s(0, "RIFF") && s(8, "WEBP") -> "WEBP"
            s(0, "RIFF") || s(0, "RF64") -> "RIFF"
            s(0, "8BPS") -> "PSD"
            s(0, "FUJIFILMCCD-RAW") -> "RAF"
            s(0, "IIRO") || s(0, "IIRS") || s(0, "MMOR") -> "ORF"
            s(0, "IIU\u0000") -> "RW2"
            s(0, "II*\u0000") || s(0, "MM\u0000*") || s(0, "II+\u0000") || s(0, "MM\u0000+") -> when {
                s(8, "CR") -> "CR2"
                ext.uppercase() in setOf("DNG", "NEF", "NRW", "ARW", "SR2", "SRF", "PEF", "SRW", "ERF", "3FR", "IIQ", "MOS", "MEF", "KDC", "DCR", "ORF") -> ext.uppercase()
                else -> "TIFF"
            }
            s(4, "ftyp") || s(4, "moov") || s(4, "mdat") || s(4, "wide") || s(4, "free") || s(4, "skip") || s(4, "pnot") -> "BMFF"
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> "MKV"
            s(0, "fLaC") -> "FLAC"
            s(0, "ID3") -> {
                var at = 0L
                while (f.startsWith(at, "ID3")) { val n = (0 until 4).fold(0L) { v, k -> (v shl 7) or (f.u8(at + 6 + k).toLong() and 0x7F) }; at += 10 + n }
                if (f.startsWith(at, "fLaC")) "FLAC" else "MP3"
            }
            s(0, "OggS") -> "OGG"
            String(h, Charsets.ISO_8859_1).take(1024).contains("%PDF-") -> "PDF"
            s(0, "PK\u0003\u0004") || s(0, "PK\u0005\u0006") -> "ZIP"
            h.size >= 8 && (0 until 8).all { h[it] == com.localmediatools.docs.core.Ole2.SIGNATURE[it] } -> "OLE2"
            s(0, "{\\rtf") -> "RTF"
            s(0, "BM") && h.size >= 18 && (B.u32le(h, 2) == f.size || B.u32le(h, 14) in setOf(12L, 40L, 52L, 56L, 64L, 108L, 124L)) -> "BMP"
            u(0) == 0xFF && (u(1) and 0xE0) == 0xE0 && (u(1) shr 1 and 3) != 0 && ext in setOf("mp3", "mp2", "mpga", "mpa") -> "MP3"
            else -> {
                val t = String(h, Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
                val lower = t.take(512).lowercase()
                val utf16 = s(0, "ÿþ") || s(0, "þÿ")
                when {
                    lower.startsWith("<!doctype html") || lower.startsWith("<html") || (lower.startsWith("<?xml") && lower.contains("<html")) -> "HTML"
                    lower.startsWith("<svg") || (lower.startsWith("<?xml") && lower.contains("<svg")) -> "SVG"
                    lower.startsWith("<?xml") -> "XML"
                    utf16 || h.isNotEmpty() && h.none { it == 0.toByte() } -> "TXT"
                    else -> "Unknown"
                }
            }
        }
    }

    /** MP4 family: what ExifTool calls it, from the brand. */
    private fun bmffType(f: MetaInput): String {
        if (!f.startsWith(4, "ftyp")) return "MOV"
        val major = f.ascii(8, 4)
        val n = ((f.u32be(0) - 16) / 4).coerceIn(0, 64).toInt()
        val brands = (0 until n).map { f.ascii(16L + it * 4, 4) } + major
        return when {
            major == "qt  " -> "MOV"
            major == "crx " -> "CR3"
            major == "avif" || major == "avis" || (major == "mif1" && "avif" in brands) -> "AVIF"
            major in setOf("heic", "heix", "heim", "heis", "hevc", "hevx") -> "HEIC"
            major == "mif1" || major == "msf1" -> "HEIF"
            major.startsWith("3g2") -> "3G2"
            major.startsWith("3gp") || major.startsWith("3ge") || major.startsWith("3gg") -> "3GP"
            major == "M4A " || major == "M4B " || major == "M4P " -> major.trim()
            major == "M4V " || major == "M4VH" || major == "M4VP" -> "M4V"
            else -> "MP4"
        }
    }

    /** "4032 × 3024" from the first width and height found. */
    private fun imageSize(out: Fields) {
        val w = out.list.firstOrNull { it.name == "ImageWidth" || it.name == "ExifImageWidth" || it.name == "PixelXDimension" }?.raw?.toLongOrNull() ?: return
        val h = out.list.firstOrNull { it.name == "ImageHeight" || it.name == "ExifImageHeight" || it.name == "PixelYDimension" }?.raw?.toLongOrNull() ?: return
        if (w <= 0 || h <= 0) return
        out.add("Composite", "ImageSize", "$w × $h", "$w $h")
        val mp = w * h / 1e6
        out.add("Composite", "Megapixels", ExifReader.num(mp, if (mp < 1) 3 else 1), mp.toString())
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> ExifReader.num(bytes / 1024.0, 1) + " kB"
        bytes < 1024L * 1024 * 1024 -> ExifReader.num(bytes / 1048576.0, 1) + " MB"
        else -> ExifReader.num(bytes / 1073741824.0, 2) + " GB"
    }

    private fun stamp(ms: Long): String {
        val c = Calendar.getInstance(TimeZone.getDefault()); c.timeInMillis = ms
        val off = c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)
        val sign = if (off < 0) "-" else "+"
        val a = Math.abs(off) / 60000
        return "%04d:%02d:%02d %02d:%02d:%02d%s%02d:%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), sign, a / 60, a % 60)
    }
}
