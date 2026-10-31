package com.localmediatools.meta.core

import com.localmediatools.docs.core.Ole2
import com.localmediatools.docs.core.Parts
import com.localmediatools.docs.core.XmlReader
import com.localmediatools.docs.core.XmlReader.Event
import java.nio.charset.Charset
import java.util.Calendar
import java.util.TimeZone

/**
 * Office files and other documents: Word, Excel and PowerPoint (both the ZIP-based files and the
 * 97–2003 ones), OpenDocument, EPUB, RTF, HTML, plain text and ZIP archives.
 */
object OfficeMeta {
    // ------------------------------------------------------------------ ZIP and the files built on it
    /** The ZIP's first entry, as ExifTool lists it, and how many files it holds. */
    fun zip(f: MetaInput, out: Fields) {
        if (f.startsWith(0, "PK\u0003\u0004")) {
            val g = "ZIP"
            out.add(g, "ZipRequiredVersion", "${f.u16le(4)}")
            out.add(g, "ZipBitFlag", "0x%04x".format(f.u16le(6)), "${f.u16le(6)}")
            out.add(g, "ZipCompression", when (f.u16le(8)) { 0 -> "None"; 8 -> "Deflated"; 12 -> "BZip2"; 14 -> "LZMA"; 93 -> "Zstandard"; else -> "${f.u16le(8)}" }, "${f.u16le(8)}")
            dosDate(f.u16le(12), f.u16le(10))?.let { out.add(g, "ZipModifyDate", it, it, Privacy.TIME) }
            out.add(g, "ZipCRC", "0x%08x".format(f.u32le(14)), "${f.u32le(14)}")
            out.add(g, "ZipCompressedSize", "${f.u32le(18)}"); out.add(g, "ZipUncompressedSize", "${f.u32le(22)}")
            val nl = f.u16le(26)
            out.add(g, "ZipFileName", String(f.bytes(30, nl), if (f.u16le(6) and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1))
        }
        // The central directory at the end: the number of files and the archive's comment.
        val tail = f.bytes(maxOf(0, f.size - 65_557), 65_557)
        val s = String(tail, Charsets.ISO_8859_1)
        val e = s.lastIndexOf("PK\u0005\u0006")
        if (e >= 0 && e + 22 <= tail.size) {
            out.add("ZIP", "ZipFileCount", "${B.u16le(tail, e + 10)}")
            val cl = B.u16le(tail, e + 20)
            if (cl > 0) out.add("ZIP", "ZipComment", String(tail, e + 22, minOf(cl, tail.size - e - 22), Charsets.UTF_8), privacy = Privacy.TEXT)
        }
    }

    private fun dosDate(d: Int, t: Int): String? {
        if (d <= 0) return null
        return "%04d:%02d:%02d %02d:%02d:%02d".format(1980 + (d shr 9), (d shr 5) and 15, d and 31, t shr 11, (t shr 5) and 63, (t and 31) * 2)
    }

    /** Which ZIP-based document [f] is: "DOCX", "ODT", "EPUB"… or "ZIP". */
    fun zipKind(parts: Parts): String {
        val mime = parts.bytes("mimetype", 256)?.let { String(it, Charsets.US_ASCII).trim() }
        if (mime != null) {
            ODF_KINDS[mime]?.let { return it }
            if (mime == "application/epub+zip") return "EPUB"
        }
        val ct = parts.bytes("[Content_Types].xml", 1 shl 20)?.let { String(it, Charsets.UTF_8) } ?: ""
        return when {
            ct.contains("wordprocessingml.document.main") || ct.contains("wordprocessingml.template.main") || parts.has("word/document.xml") -> if (ct.contains("macroEnabled")) "DOCM" else "DOCX"
            ct.contains("spreadsheetml.sheet.main") || ct.contains("spreadsheetml.template.main") || ct.contains("ms-excel.sheet.macroEnabled") || parts.has("xl/workbook.xml") -> if (ct.contains("macroEnabled")) "XLSM" else "XLSX"
            ct.contains("presentationml.presentation.main") || ct.contains("presentationml.slideshow.main") || ct.contains("presentationml.template.main") || parts.has("ppt/presentation.xml") -> if (ct.contains("macroEnabled")) "PPTM" else "PPTX"
            parts.has("META-INF/container.xml") && parts.has("mimetype") -> "EPUB"
            parts.has("AndroidManifest.xml") && parts.has("classes.dex") -> "APK"
            parts.has("META-INF/MANIFEST.MF") -> "JAR"
            else -> "ZIP"
        }
    }

    private val ODF_KINDS = mapOf("application/vnd.oasis.opendocument.text" to "ODT", "application/vnd.oasis.opendocument.spreadsheet" to "ODS",
        "application/vnd.oasis.opendocument.presentation" to "ODP", "application/vnd.oasis.opendocument.graphics" to "ODG",
        "application/vnd.oasis.opendocument.text-template" to "OTT", "application/vnd.oasis.opendocument.formula" to "ODF",
        "application/vnd.oasis.opendocument.chart" to "ODC", "application/vnd.oasis.opendocument.text-master" to "ODM")

    fun zipDocument(f: MetaInput, out: Fields) {
        val parts = try { Parts(f.file) } catch (_: Exception) { out.add("File", "FileType", "ZIP"); zip(f, out); out.note("The archive is damaged: only its first file's details were read."); return }
        parts.use {
            val kind = zipKind(it)
            out.add("File", "FileType", kind)
            zip(f, out)
            when {
                kind in setOf("DOCX", "DOCM", "XLSX", "XLSM", "PPTX", "PPTM") -> ooxml(it, out)
                kind == "EPUB" -> epub(it, out)
                kind in ODF_KINDS.values -> odf(it, out)
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------ Office Open XML (docProps)
    private val CORE_NAMES = mapOf("creator" to "Creator", "lastModifiedBy" to "LastModifiedBy", "revision" to "RevisionNumber", "created" to "CreateDate",
        "modified" to "ModifyDate", "lastPrinted" to "LastPrinted", "contentStatus" to "ContentStatus")
    private val APP_NAMES = mapOf("TotalTime" to "TotalEditTime", "DocSecurity" to "DocSecurity")

    private fun ooxml(parts: Parts, out: Fields) {
        parts.xml("docProps/core.xml")?.let { x ->
            props(x, out) { local -> CORE_NAMES[local] ?: local.replaceFirstChar { it.uppercaseChar() } }
        }
        parts.xml("docProps/app.xml")?.let { x -> props(x, out) { local -> APP_NAMES[local] ?: local } }
        parts.xml("docProps/custom.xml")?.let { x ->
            try {
                while (x.next() != Event.EOF) {
                    if (x.event != Event.START || x.local != "property") continue
                    val name = x.attr("name") ?: continue
                    val v = StringBuilder()
                    x.children { c -> if (c.event == Event.TEXT) v.append(c.text) }
                    out.add("XML", tagName(name), v.toString().trim(), privacy = Privacies.of(tagName(name)) ?: Privacy.TEXT)
                }
            } catch (_: Exception) { out.note("The document's own properties are damaged: only part of them was read.") }
        }
        if (parts.has("docProps/thumbnail.jpeg") || parts.has("docProps/thumbnail.wmf") || parts.has("docProps/thumbnail.emf"))
            out.add("XML", "ThumbnailImage", "(a picture of the first page)", "1", Privacy.HIDDEN_IMAGE)
        if (parts.has("word/comments.xml")) out.add("XML", "HasComments", "Yes", privacy = Privacy.TEXT)
        if (parts.has("word/people.xml")) out.add("XML", "HasReviewers", "Yes", privacy = Privacy.PERSON)
    }

    /** The children of the root element: each a property; lists (vt:vector) joined. */
    private fun props(x: XmlReader, out: Fields, nameOf: (String) -> String) {
        try {
            while (x.next() != Event.EOF && x.event != Event.START) {}
            x.children { c ->
                if (c.event != Event.START) return@children
                val name = nameOf(c.local)
                val values = ArrayList<String>()
                val text = StringBuilder()
                c.children { v ->
                    if (v.event == Event.TEXT) text.append(v.text)
                    else if (v.event == Event.END && v.local != "vector" && text.isNotBlank()) { values.add(text.toString().trim()); text.setLength(0) }
                }
                if (text.isNotBlank()) values.add(text.toString().trim())
                if (values.isEmpty()) return@children
                val v = values.joinToString(", ")
                when {
                    name.endsWith("Date") || name == "LastPrinted" -> out.add("XML", name, XmpReader.exifDate(v), XmpReader.exifDate(v))
                    name == "TotalEditTime" -> v.toLongOrNull()?.let { m -> out.add("XML", name, minutes(m * 60), "$m") } ?: out.add("XML", name, v)
                    name == "DocSecurity" -> out.add("XML", name, when (v) { "0" -> "None"; "1" -> "Password protected"; "2" -> "Read-only recommended"; "4" -> "Read-only enforced"; "8" -> "Locked for annotations"; else -> v }, v)
                    else -> out.add("XML", name, v)
                }
            }
        } catch (_: Exception) { out.note("The document's properties are damaged: only part of them was read.") }
    }

    private fun minutes(s: Long): String = when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${s % 3600 / 60} min"
    }

    /** "Project number" → "ProjectNumber". */
    private fun tagName(s: String) = s.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }.ifEmpty { "Unnamed" }

    // ------------------------------------------------------------------ OpenDocument (meta.xml)
    private val ODF_NAMES = mapOf("initial-creator" to "InitialCreator", "creation-date" to "CreationDate", "editing-cycles" to "EditingCycles",
        "editing-duration" to "EditingDuration", "date" to "Date", "creator" to "Creator", "generator" to "Generator", "keyword" to "Keyword",
        "print-date" to "PrintDate", "printed-by" to "PrintedBy", "title" to "Title", "description" to "Description", "subject" to "Subject", "language" to "Language")

    private fun odf(parts: Parts, out: Fields) {
        val x = parts.xml("meta.xml") ?: return
        try {
            while (x.next() != Event.EOF) {
                if (x.event != Event.START) continue
                when (x.local) {
                    "document-statistic" -> for (i in 0 until x.attributeCount)
                        out.add("XMP-meta", "DocumentStatistic" + tagName(x.attributeLocal(i)), x.attributeValue(i))
                    "user-defined" -> {
                        val name = x.attr("name") ?: continue
                        out.add("XMP-meta", tagName(name), x.readText().trim(), privacy = Privacy.TEXT)
                    }
                    "template" -> x.attr("href")?.let { out.add("XMP-meta", "TemplateHref", it, privacy = Privacy.PERSON) }
                    else -> ODF_NAMES[x.local]?.let { name ->
                        val v = x.readText().trim()
                        val g = if (x.ns.startsWith("http://purl.org/dc/")) "XMP-dc" else "XMP-meta"
                        when {
                            name.endsWith("Date") || name == "Date" -> out.add(g, name, XmpReader.exifDate(v), XmpReader.exifDate(v))
                            name == "EditingDuration" -> out.add(g, name, isoDuration(v) ?: v, v)
                            else -> out.add(g, name, v)
                        }
                    }
                }
            }
        } catch (_: Exception) { out.note("The document's properties are damaged: only part of them was read.") }
        if (parts.has("Thumbnails/thumbnail.png")) out.add("XMP-meta", "ThumbnailImage", "(a picture of the first page)", "1", Privacy.HIDDEN_IMAGE)
    }

    /** "PT1H2M3S" → "1 h 2 min". */
    private fun isoDuration(s: String): String? {
        val m = Regex("""^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.\d+)?S)?$""").find(s) ?: return null
        val (d, h, mi, se) = m.destructured
        val total = (d.toLongOrNull() ?: 0) * 86400 + (h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (se.toLongOrNull() ?: 0)
        return minutes(total)
    }

    // ------------------------------------------------------------------ EPUB (the package's Dublin Core)
    private fun epub(parts: Parts, out: Fields) {
        val container = parts.xml("META-INF/container.xml") ?: return
        var opf: String? = null
        try { while (opf == null && container.next() != Event.EOF) if (container.event == Event.START && container.local == "rootfile") opf = container.attr("full-path") }
        catch (_: Exception) {}
        val x = parts.xml(opf ?: return) ?: return
        try {
            var inMeta = false
            while (x.next() != Event.EOF) {
                if (x.event == Event.END && x.local == "metadata") inMeta = false
                if (x.event != Event.START) continue
                if (x.local == "metadata") { inMeta = true; continue }
                if (x.local == "package") x.attr("version")?.let { out.add("EPUB", "EPUBVersion", it) }
                if (!inMeta) continue
                if (x.ns == "http://purl.org/dc/elements/1.1/") {
                    val role = x.attr("role")
                    val v = x.readText().trim()
                    val name = x.local.replaceFirstChar { it.uppercaseChar() }
                    when {
                        name == "Date" -> out.add("EPUB", "Date", XmpReader.exifDate(v), XmpReader.exifDate(v))
                        role != null && name in setOf("Creator", "Contributor") -> out.add("EPUB", name, "$v ($role)", v)
                        else -> out.add("EPUB", name, v)
                    }
                } else if (x.local == "meta") {
                    val n = x.attr("name"); val c = x.attr("content"); val prop = x.attr("property")
                    when {
                        n != null && c != null && n != "cover" -> out.add("EPUB", tagName(n.substringAfter(':')), c)
                        prop != null -> { val v = x.readText().trim(); if (prop == "dcterms:modified") out.add("EPUB", "ModifyDate", XmpReader.exifDate(v), XmpReader.exifDate(v)) else out.add("EPUB", tagName(prop.substringAfter(':')), v) }
                    }
                }
            }
        } catch (_: Exception) { out.note("The book's details are damaged: only part of them was read.") }
    }

    // ------------------------------------------------------------------ Office 97–2003 (OLE2 property sets)
    private val SUMMARY = mapOf(2 to "Title", 3 to "Subject", 4 to "Author", 5 to "Keywords", 6 to "Comments", 7 to "Template", 8 to "LastModifiedBy",
        9 to "RevisionNumber", 10 to "TotalEditTime", 11 to "LastPrinted", 12 to "CreateDate", 13 to "ModifyDate", 14 to "Pages", 15 to "Words",
        16 to "Characters", 17 to "ThumbnailClip", 18 to "Software", 19 to "Security")
    private val DOC_SUMMARY = mapOf(2 to "Category", 3 to "PresentationTarget", 4 to "Bytes", 5 to "Lines", 6 to "Paragraphs", 7 to "Slides",
        8 to "Notes", 9 to "HiddenSlides", 10 to "MMClips", 11 to "ScaleCrop", 12 to "HeadingPairs", 13 to "TitleOfParts", 14 to "Manager",
        15 to "Company", 16 to "LinksUpToDate", 17 to "CharCountWithSpaces", 19 to "SharedDoc", 22 to "HyperlinksChanged", 23 to "AppVersion",
        26 to "ContentType", 27 to "ContentStatus", 28 to "Language", 29 to "DocVersion")

    fun ole2(f: MetaInput, out: Fields) {
        val ole = try { Ole2(f.file) } catch (_: Exception) { out.add("File", "FileType", "FPX"); out.note("The file is damaged: its contents can't be unpacked."); return }
        ole.use { o ->
            val kind = when {
                o.has("EncryptedPackage") -> "Protected Office file"
                o.has("WordDocument") -> "DOC"
                o.has("Workbook") || o.has("Book") -> "XLS"
                o.has("PowerPoint Document") -> "PPT"
                o.has("__properties_version1.0") -> "MSG"
                o.has("VisioDocument") -> "VSD"
                else -> "FPX"
            }
            out.add("File", "FileType", kind)
            try {
                o.stream("\u0005SummaryInformation")?.let { propertySet(it, SUMMARY, out) }
                o.stream("\u0005DocumentSummaryInformation")?.let { propertySet(it, DOC_SUMMARY, out) }
                o.stream("\u0001CompObj")?.let { compObj(it, out) }
            } catch (_: Exception) { out.note("The document's properties are damaged: only part of them was read.") }
            if (kind == "Protected Office file") out.note("This Office file is protected with a password: only the outer details can be read.")
        }
    }

    private fun compObj(b: ByteArray, out: Fields) {
        if (b.size < 32) return
        val len = B.u32le(b, 28).toInt()
        if (len in 1..1024 && 32 + len <= b.size) out.add("FlashPix", "CompObjUserType", B.cstring(b, 32, len))
    }

    /** A property set stream: its first section by [names], the second (custom properties) by its own dictionary. */
    private fun propertySet(b: ByteArray, names: Map<Int, String>, out: Fields) {
        if (b.size < 28 || B.u16le(b, 0) != 0xFFFE) return
        val sections = B.u32le(b, 24).toInt().coerceIn(0, 4)
        for (s in 0 until sections) {
            val off = B.u32le(b, 28 + s * 20 + 16).toInt()
            if (off < 0 || off + 8 > b.size) continue
            section(b, off, if (s == 0) names else emptyMap(), custom = s > 0, out = out)
        }
    }

    private fun section(b: ByteArray, at: Int, names: Map<Int, String>, custom: Boolean, out: Fields) {
        val count = B.u32le(b, at + 4).toInt().coerceIn(0, 4096)
        val props = ArrayList<Pair<Int, Int>>()
        for (i in 0 until count) props.add(B.u32le(b, at + 8 + i * 8).toInt() to B.u32le(b, at + 12 + i * 8).toInt())
        // The code page (property 1) says how the 8-bit strings are written.
        var cs: Charset = Charsets.ISO_8859_1
        props.firstOrNull { it.first == 1 }?.let { (_, o) -> val cp = B.u16le(b, at + o + 4); charset(cp)?.let { cs = it }; if (!custom) out.add("FlashPix", "CodePage", codePageName(cp), "$cp") }
        val dict = HashMap<Int, String>()
        props.firstOrNull { it.first == 0 }?.let { (_, o) ->
            var p = at + o
            val n = B.u32le(b, p).toInt().coerceIn(0, 4096); p += 4
            for (k in 0 until n) {
                val id = B.u32le(b, p).toInt(); val len = B.u32le(b, p + 4).toInt(); p += 8
                if (len < 0 || p + len > b.size) break
                val wide = cs == Charsets.UTF_16LE
                val nameBytes = if (wide) len * 2 else len
                if (p + nameBytes > b.size) break
                dict[id] = String(b, p, nameBytes, cs).trimEnd('\u0000')
                p += nameBytes
                if (wide && nameBytes % 4 != 0) p += 4 - nameBytes % 4
            }
        }
        for ((id, o) in props) {
            if (id == 0 || id == 1 || id == Int.MIN_VALUE) continue
            val name = if (custom) dict[id]?.let { tagName(it) } ?: continue else names[id] ?: continue
            val p = at + o
            if (p + 4 > b.size) continue
            val v = value(b, p, cs) ?: continue
            when {
                name == "TotalEditTime" && v is FileTime -> { val s = v.t / 10_000_000; out.add("FlashPix", name, minutes(s), "$s") }
                v is FileTime -> fileTime(v.t)?.let { out.add("FlashPix", name, it, it, Privacy.TIME) }
                name == "ThumbnailClip" -> out.add("FlashPix", name, "(a picture of the first page)", "1", Privacy.HIDDEN_IMAGE)
                name == "Security" -> out.add("FlashPix", name, when (v.toString()) { "0" -> "None"; "1" -> "Password protected"; "2" -> "Read-only recommended"; "4" -> "Read-only enforced"; "8" -> "Locked for annotations"; else -> v.toString() }, v.toString())
                else -> out.add("FlashPix", name, v.toString(), privacy = if (custom) Privacies.of(name) ?: Privacy.TEXT else null)
            }
        }
    }

    private class FileTime(val t: Long)

    /** A typed property value; vectors become lists joined by ", ". */
    private fun value(b: ByteArray, p: Int, cs: Charset, depth: Int = 0): Any? {
        val type = B.u16le(b, p)
        val d = p + 4
        if (type and 0x1000 != 0 && depth == 0) {
            val n = B.u32le(b, d).toInt().coerceIn(0, 4096)
            val items = ArrayList<String>()
            var q = d + 4
            for (i in 0 until n) {
                val t = type and 0xFFF
                if (q + 4 > b.size) break
                val (v, len) = when (t) {
                    12 -> { val vv = value(b, q, cs, depth + 1); vv to size(b, q, cs) }
                    30, 31 -> { val vv = value(b, q - 4, cs, depth + 1, typeOverride = t); vv to (stringSize(b, q, t, cs)) }
                    else -> return null
                }
                v?.let { items.add(it.toString()) }
                q += len
            }
            return items.joinToString(", ")
        }
        return value(b, p, cs, depth, type)
    }

    private fun value(b: ByteArray, p: Int, cs: Charset, depth: Int, typeOverride: Int): Any? {
        val d = p + 4
        return when (typeOverride) {
            2 -> B.u16le(b, d).toShort().toLong()
            18 -> B.u16le(b, d).toLong()
            3, 22 -> B.u32le(b, d).toInt().toLong()
            19, 23 -> B.u32le(b, d)
            20, 21 -> (B.u32le(b, d) or (B.u32le(b, d + 4) shl 32))
            4 -> java.lang.Float.intBitsToFloat(B.u32le(b, d).toInt()).toDouble().let { ExifReader.num(it) }
            5 -> java.lang.Double.longBitsToDouble(B.u32le(b, d) or (B.u32le(b, d + 4) shl 32)).let { ExifReader.num(it) }
            11 -> if (B.u16le(b, d) != 0) "Yes" else "No"
            30 -> { val n = B.u32le(b, d).toInt(); if (n <= 0 || d + 4 + n > b.size) "" else String(b, d + 4, n, cs).trimEnd('\u0000') }
            31 -> { val n = B.u32le(b, d).toInt(); if (n <= 0 || d + 4 + n * 2 > b.size) "" else String(b, d + 4, n * 2, Charsets.UTF_16LE).trimEnd('\u0000') }
            64 -> FileTime(B.u32le(b, d) or (B.u32le(b, d + 4) shl 32))
            7 -> {
                // OLE date: days since 1899-12-30.
                val days = java.lang.Double.longBitsToDouble(B.u32le(b, d) or (B.u32le(b, d + 4) shl 32))
                val ms = ((days - 25569) * 86_400_000).toLong()
                stamp(ms)
            }
            12 -> if (depth < 2) value(b, d, cs, depth + 1) else null
            71 -> "(clipboard data)"
            else -> null
        }
    }

    private fun stringSize(b: ByteArray, q: Int, t: Int, cs: Charset): Int {
        val n = B.u32le(b, q).toInt().coerceAtLeast(0)
        val bytes = if (t == 31) n * 2 else n
        return 4 + bytes + (4 - bytes % 4) % 4
    }

    /** The size of a variant (type + value) at [q], padded to 4 bytes. */
    private fun size(b: ByteArray, q: Int, cs: Charset): Int = when (val t = B.u16le(b, q)) {
        2, 18, 11, 3, 22, 19, 23, 4 -> 8
        5, 20, 21, 64, 7 -> 12
        30, 31 -> 4 + stringSize(b, q + 4, t, cs)
        else -> 8
    }

    private fun charset(cp: Int): Charset? = when (cp) {
        1200 -> Charsets.UTF_16LE
        65001 -> Charsets.UTF_8
        10000 -> runCatching { Charset.forName("x-MacRoman") }.getOrNull()
        else -> runCatching { Charset.forName("windows-$cp") }.getOrNull() ?: runCatching { Charset.forName("Cp$cp") }.getOrNull()
    }

    private fun codePageName(cp: Int) = when (cp) {
        1252 -> "Windows Latin 1 (Western European)"; 1250 -> "Windows Latin 2 (Central European)"; 1251 -> "Windows Cyrillic"
        1253 -> "Windows Greek"; 1254 -> "Windows Turkish"; 932 -> "Windows Japanese (Shift-JIS)"; 936 -> "Windows Chinese (Simplified) (GB2312)"
        949 -> "Windows Korean (Unified Hangul)"; 950 -> "Windows Chinese (Traditional) (Big5)"; 65001 -> "Unicode (UTF-8)"; 1200 -> "Unicode UTF-16, little endian"
        10000 -> "Mac Roman (Western European)"; else -> "$cp"
    }

    /** A FILETIME (100 ns since 1601) as "YYYY:MM:DD hh:mm:ss" (UTC, as Office stores it). */
    private fun fileTime(t: Long): String? {
        if (t <= 0) return null
        val ms = t / 10_000 - 11_644_473_600_000L
        if (ms < -2_208_988_800_000L) return null // before 1900: a duration or nonsense
        return stamp(ms) + "Z"
    }

    private fun stamp(ms: Long): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.timeInMillis = ms
        return "%04d:%02d:%02d %02d:%02d:%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    // ------------------------------------------------------------------ RTF ({\info …})
    private val RTF_TEXT = mapOf("title" to "Title", "subject" to "Subject", "author" to "Author", "manager" to "Manager", "company" to "Company",
        "operator" to "LastModifiedBy", "category" to "Category", "keywords" to "Keywords", "comment" to "Comment", "doccomm" to "Comments", "hlinkbase" to "HyperlinkBase")
    private val RTF_DATES = mapOf("creatim" to "CreateDate", "revtim" to "ModifyDate", "printim" to "LastPrinted", "buptim" to "BackupTime")
    private val RTF_NUMS = mapOf("version" to "RevisionNumber", "vern" to "InternalVersionNumber", "edmins" to "TotalEditTime", "nofpages" to "Pages",
        "nofwords" to "Words", "nofchars" to "Characters", "nofcharsws" to "CharactersWithSpaces", "id" to "InternalIDNumber")

    fun rtf(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "RTF")
        val s = String(f.bytes(0, 4 shl 20), Charsets.ISO_8859_1)
        val cp = Regex("""\\ansicpg(\d+)""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 1252
        val cs = charset(cp) ?: Charsets.ISO_8859_1
        val i = s.indexOf("{\\info")
        if (i < 0) return
        // The info group, up to its closing brace.
        var depth = 0; var e = i
        while (e < s.length) { val c = s[e]; if (c == '\\') e++ else if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) break }; e++ }
        val info = s.substring(i + 6, minOf(e, s.length))
        var p = 0
        while (p < info.length) {
            if (info[p] == '{') {
                // A sub-group: {\title text} or {\creatim\yr2004\mo1…} or {\*\company text}.
                var d = 0; var q = p
                while (q < info.length) { val c = info[q]; if (c == '\\') q++ else if (c == '{') d++ else if (c == '}') { d--; if (d == 0) break }; q++ }
                val g = info.substring(p + 1, minOf(q, info.length))
                val word = Regex("""^(?:\\\*)?\\([a-z]+)""").find(g)
                if (word != null) {
                    val w = word.groupValues[1]
                    RTF_TEXT[w]?.let { out.add("RTF", it, rtfText(g.substring(word.range.last + 1), cs)) }
                    RTF_DATES[w]?.let {
                        fun n(k: String) = Regex("""\\$k(\d+)""").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (n("yr") > 0) { val v = "%04d:%02d:%02d %02d:%02d:%02d".format(n("yr"), n("mo"), n("dy"), n("hr"), n("min"), n("sec")); out.add("RTF", it, v, v) }
                    }
                }
                p = q + 1
                continue
            }
            if (info[p] == '\\') {
                val m = Regex("""^\\([a-z]+)(-?\d+)?""").find(info.substring(p, minOf(info.length, p + 40)))
                if (m != null) {
                    RTF_NUMS[m.groupValues[1]]?.let { name -> if (m.groupValues[2].isNotEmpty()) out.add("RTF", name, m.groupValues[2]) }
                    p += m.value.length
                    continue
                }
            }
            p++
        }
    }

    /** RTF text: \'hh bytes in the document's code page, \uN characters, escaped braces. */
    private fun rtfText(s: String, cs: Charset): String {
        val sb = StringBuilder()
        val bytes = java.io.ByteArrayOutputStream()
        fun flush() { if (bytes.size() > 0) { sb.append(String(bytes.toByteArray(), cs)); bytes.reset() } }
        var i = 0; var skip = 0; var uc = 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '\'' && i + 3 < s.length -> {
                    val v = s.substring(i + 2, i + 4).toIntOrNull(16)
                    if (skip > 0) skip-- else if (v != null) bytes.write(v)
                    i += 4; continue
                }
                c == '\\' && i + 1 < s.length && (s[i + 1] == '\\' || s[i + 1] == '{' || s[i + 1] == '}') -> { flush(); if (skip > 0) skip-- else sb.append(s[i + 1]); i += 2; continue }
                c == '\\' -> {
                    val m = Regex("""^\\([a-z]+)(-?\d+)? ?""").find(s.substring(i, minOf(s.length, i + 32)))
                    if (m == null) { i++; continue }
                    flush()
                    when (m.groupValues[1]) {
                        "u" -> { val n = m.groupValues[2].toIntOrNull() ?: 0; sb.append((if (n < 0) n + 65536 else n).toChar()); skip = uc }
                        "uc" -> uc = m.groupValues[2].toIntOrNull() ?: 1
                        "tab" -> sb.append('\t'); "line", "par" -> sb.append(' ')
                    }
                    i += m.value.length; continue
                }
                c == '{' || c == '}' || c == '\r' || c == '\n' -> {}
                skip > 0 -> skip--
                else -> { flush(); sb.append(c) }
            }
            i++
        }
        flush()
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ HTML and plain text
    fun html(f: MetaInput, out: Fields) {
        out.add("File", "FileType", "HTML")
        val head = String(f.bytes(0, 1 shl 20), Charsets.UTF_8).let { s -> s.substring(0, s.indexOf("</head", ignoreCase = true).takeIf { it > 0 } ?: s.length) }
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(head)?.let { out.add("HTML", "Title", entities(it.groupValues[1]).trim()) }
        for (m in Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE).findAll(head)) {
            val tag = m.value
            fun a(n: String) = Regex("""\b$n\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)?.let { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }
            val content = a("content") ?: continue
            val name = a("name") ?: a("property") ?: a("http-equiv")?.let { "HTTP-equiv " + it } ?: continue
            if (name.equals("viewport", true)) continue
            out.add("HTML", tagName(name), entities(content))
        }
    }

    private fun entities(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    /** A text file: its encoding, line endings and counts. */
    fun text(f: MetaInput, out: Fields, kind: String) {
        out.add("File", "FileType", kind)
        val first = f.bytes(0, 4)
        val enc = when {
            first.size >= 3 && first[0] == 0xEF.toByte() && first[1] == 0xBB.toByte() && first[2] == 0xBF.toByte() -> "utf-8"
            first.size >= 2 && first[0] == 0xFF.toByte() && first[1] == 0xFE.toByte() -> "utf-16le"
            first.size >= 2 && first[0] == 0xFE.toByte() && first[1] == 0xFF.toByte() -> "utf-16be"
            else -> null
        }
        var lines = 0L; var words = 0L; var cr = 0L; var lf = 0L; var crlf = 0L; var high = false; var validUtf8 = true
        var inWord = false; var lastCr = false; var cont = 0; var lastByte = -1
        var at = 0L
        val limit = minOf(f.size, 256L shl 20)
        while (at < limit) {
            val b = f.bytes(at, 1 shl 20)
            if (b.isEmpty()) break
            for (x in b) {
                val c = x.toInt() and 0xFF
                if (c >= 0x80) {
                    high = true
                    if (cont > 0) { if (c and 0xC0 != 0x80) validUtf8 = false; cont-- }
                    else cont = when { c and 0xE0 == 0xC0 -> 1; c and 0xF0 == 0xE0 -> 2; c and 0xF8 == 0xF0 -> 3; else -> { validUtf8 = false; 0 } }
                } else if (cont > 0) { validUtf8 = false; cont = 0 }
                if (enc?.startsWith("utf-16") == true) continue
                when (c) {
                    '\n'.code -> { if (lastCr) { crlf++; cr-- } else lf++ }
                    '\r'.code -> cr++
                }
                lastCr = c == '\r'.code
                val space = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code || c == 0x0C
                if (!space && !inWord) words++
                inWord = !space
                lastByte = c
            }
            at += b.size
        }
        lines = lf + cr + crlf + if (lastByte >= 0 && lastByte != '\n'.code && lastByte != '\r'.code) 1 else 0
        out.add("File", "MIMEEncoding", enc ?: if (!high) "us-ascii" else if (validUtf8) "utf-8" else "iso-8859-1")
        if (enc?.startsWith("utf-16") != true) {
            out.add("File", "Newlines", when {
                crlf >= lf && crlf >= cr && crlf > 0 -> "Windows CRLF"; lf >= cr && lf > 0 -> "Unix LF"; cr > 0 -> "Macintosh CR"; else -> "(none)"
            })
            out.add("File", "LineCount", "$lines"); out.add("File", "WordCount", "$words")
        }
    }
}
