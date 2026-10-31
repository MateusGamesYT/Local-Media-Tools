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
        // The central directory at the end: the number of files, the archive's comment, and its first file.
        val tail = f.bytes(maxOf(0, f.size - 65_557), 65_557)
        val s = String(tail, Charsets.ISO_8859_1)
        val e = s.lastIndexOf("PK\u0005\u0006")
        val cd = if (e >= 0 && e + 20 <= tail.size) B.u32le(tail, e + 16) else -1L
        // A local header leaves the sizes and CRC to a trailer when bit 3 is set; the directory always has them.
        // (A directory record is a local header with two more bytes in front: "version made by".)
        val (at, local) = if (cd in 0 until f.size && f.startsWith(cd, "PK\u0001\u0002")) cd to false else 0L to true
        if (!local || f.startsWith(0, "PK\u0003\u0004")) {
            val g = "ZIP"
            val h = f.bytes(at, 46)
            val o = if (local) 0 else 2
            val flags = B.u16le(h, 6 + o)
            out.add(g, "ZipRequiredVersion", "${B.u16le(h, 4 + o)}")
            out.add(g, "ZipBitFlag", "0x%04x".format(B.u16le(h, 6 + o)), "${B.u16le(h, 6 + o)}")
            val c = B.u16le(h, 8 + o)
            out.add(g, "ZipCompression", when (c) { 0 -> "None"; 8 -> "Deflated"; 12 -> "BZip2"; 14 -> "LZMA"; 93 -> "Zstandard"; else -> "$c" }, "$c")
            dosDate(B.u16le(h, 12 + o), B.u16le(h, 10 + o))?.let { out.add(g, "ZipModifyDate", it, it, Privacy.TIME) }
            out.add(g, "ZipCRC", "0x%08x".format(B.u32le(h, 14 + o)), "${B.u32le(h, 14 + o)}")
            out.add(g, "ZipCompressedSize", "${B.u32le(h, 18 + o)}"); out.add(g, "ZipUncompressedSize", "${B.u32le(h, 22 + o)}")
            val nl = B.u16le(h, 26 + o)
            val nameAt = if (local) 30L else cd + 46
            out.add(g, "ZipFileName", String(f.bytes(nameAt, nl), if (flags and 0x800 != 0) Charsets.UTF_8 else Charsets.ISO_8859_1))
        }
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
        // Only the main part's type says whether the file holds macros.
        val main = Regex("""PartName="/(?:word/document|xl/workbook|ppt/presentation)\.xml"\s+ContentType="([^"]+)"""").find(ct)?.groupValues?.get(1)
        val macro = main?.contains("macroEnabled") ?: ct.contains("macroEnabled")
        return when {
            ct.contains("wordprocessingml.document.main") || ct.contains("wordprocessingml.template.main") || parts.has("word/document.xml") -> if (macro) "DOCM" else "DOCX"
            ct.contains("spreadsheetml.sheet.main") || ct.contains("spreadsheetml.template.main") || ct.contains("ms-excel.sheet.macroEnabled") || parts.has("xl/workbook.xml") -> if (macro) "XLSM" else "XLSX"
            ct.contains("presentationml.presentation.main") || ct.contains("presentationml.slideshow.main") || ct.contains("presentationml.template.main") || parts.has("ppt/presentation.xml") -> if (macro) "PPTM" else "PPTX"
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
                    val t = v.toString().trim()
                    val shown = if (Regex("""^\d{4}-\d\d-\d\dT""").containsMatchIn(t)) XmpReader.exifDate(t) else t
                    out.add("XML", tagName(name), shown, shown, privacy = Privacies.of(tagName(name)) ?: Privacy.TEXT)
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

    private val OFFICE_EXT = setOf("doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "docx", "docm", "xlsx", "xlsm", "pptx", "pptm", "msg", "vsd")

    /** [ext]: the name's extension, which says which Office file a damaged or protected one is. */
    fun ole2(f: MetaInput, out: Fields, ext: String = "") {
        val byName = if (ext in OFFICE_EXT) ext.uppercase() else null
        val ole = try { Ole2(f.file) } catch (_: Exception) { out.add("File", "FileType", byName ?: "FPX"); out.note("The file is damaged: its contents can't be unpacked."); return }
        ole.use { o ->
            val kind = when {
                o.has("EncryptedPackage") -> byName ?: "Protected Office file"
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
                // PowerPoint keeps the name of whoever last saved it in its own stream.
                if (kind == "PPT") o.stream("Current User")?.let { cu ->
                    val n = B.u16le(cu, 20)
                    if (n in 1..255 && 28 + n <= cu.size) out.add("FlashPix", "CurrentUser", String(cu, 28, n, Charset.forName("windows-1252")).trimEnd('\u0000'), privacy = Privacy.PERSON)
                }
            } catch (_: Exception) { out.note("The document's properties are damaged: only part of them was read.") }
            if (kind == "DOC") try { wordDocument(o, out) } catch (_: Exception) { out.note("The Word document's own details are damaged: only part of them was read.") }
            if (o.has("EncryptedPackage")) out.note("This Office file is protected with a password: only the outer details can be read.")
        }
    }

    /**
     * What a Word 97–2003 file keeps in its own header and tables: who saved it and where (the last ten
     * saves, with the folder of each), who wrote its comments, and its statistics and dates.
     */
    private fun wordDocument(o: Ole2, out: Fields) {
        val wd = o.stream("WordDocument") ?: return
        if (B.u16le(wd, 0) != 0xA5EC) return
        val g = "MS-DOC"
        val flags = B.u16le(wd, 10)
        out.add(g, "Identification", "Word 8.0", "${0xA5EC}")
        B.u16le(wd, 6).let { lid -> out.add(g, "LanguageCode", java.util.Locale.forLanguageTag(LCID[lid] ?: "").displayName.ifEmpty { "0x%04x".format(lid) }, "$lid") }
        out.add(g, "DocFlags", DOC_FLAGS.filter { (bit, _) -> flags and (1 shl bit) != 0 }.joinToString(", ") { it.second }.ifEmpty { "(none)" }, "${flags and 0xFF0F}")
        out.add(g, "System", if (B.u16le(wd, 18) and 1 != 0) "Macintosh" else "Windows", "${B.u16le(wd, 18) and 1}")
        out.add(g, "Word97", if (B.u16le(wd, 18) and 0x10 != 0) "Yes" else "No", "${(B.u16le(wd, 18) shr 4) and 1}")
        var pos = 32
        pos += 2 + B.u16le(wd, pos) * 2
        pos += 2 + B.u16le(wd, pos) * 4
        val n = B.u16le(wd, pos); pos += 2
        if (pos + n * 8 > wd.size) return
        fun pair(off: Int): Pair<Int, Int>? = if (off + 8 > n * 8) null else B.u32le(wd, pos + off).toInt() to B.u32le(wd, pos + off + 4).toInt()
        if (0x2B8 + 8 <= n * 8) {
            val t = B.u32le(wd, pos + 0x2B8) or (B.u32le(wd, pos + 0x2BC) shl 32)
            fileTime(t)?.let { out.add(g, "ModifyDate", it, it, Privacy.TIME) }
        }
        val table = o.stream(if (flags and 0x200 != 0) "1Table" else "0Table") ?: return
        fun slice(p: Pair<Int, Int>?): ByteArray? = p?.takeIf { (off, len) -> len > 0 && off >= 0 && off + len <= table.size }?.let { (off, len) -> table.copyOfRange(off, off + len) }
        slice(pair(0xF8))?.let { dop ->
            fun dttm(o: Int): String? {
                val v = B.u32le(dop, o)
                if (v == 0L) return null
                return "%04d:%02d:%02d %02d:%02d:00".format(((v shr 20) and 0x1FF) + 1900, (v shr 16) and 15, (v shr 11) and 31, (v shr 6) and 31, v and 63)
            }
            dttm(20)?.let { out.add(g, "CreateDate", it, it, Privacy.TIME) }
            dttm(24)?.let { out.add(g, "ModifyDate", it, it, Privacy.TIME) }
            dttm(28)?.let { out.add(g, "LastPrinted", it, it, Privacy.TIME) }
            if (dop.size >= 60) {
                out.add(g, "RevisionNumber", "${B.u16le(dop, 32)}")
                B.u32le(dop, 34).let { out.add(g, "TotalEditTime", minutes(it * 60), "$it") }
                out.add(g, "Words", "${B.u32le(dop, 38)}"); out.add(g, "Characters", "${B.u32le(dop, 42)}")
                out.add(g, "Pages", "${B.u16le(dop, 46)}"); out.add(g, "Paragraphs", "${B.u32le(dop, 48)}"); out.add(g, "Lines", "${B.u32le(dop, 56)}")
            }
        }
        // The comments' authors: counted UTF-16 names.
        slice(pair(0x120))?.let { b ->
            val names = LinkedHashSet<String>()
            var p = 0
            while (p + 2 <= b.size) { val len = B.u16le(b, p); p += 2; if (p + len * 2 > b.size) break; names.add(String(b, p, len * 2, Charsets.UTF_16LE)); p += len * 2 }
            for (name in names) out.add(g, "CommentBy", name, privacy = Privacy.PERSON)
        }
        // The last saves: pairs of (who, which file).
        slice(pair(0x238))?.let { b ->
            if (b.size < 6) return@let
            var num = B.u16le(b, 2); var p = 6
            while (num >= 2 && p + 2 <= b.size) {
                var len = B.u16le(b, p); p += 2
                if (p + len * 2 > b.size) break
                val who = String(b, p, len * 2, Charsets.UTF_16LE); p += len * 2
                if (p + 2 > b.size) break
                len = B.u16le(b, p); p += 2
                if (p + len * 2 > b.size) break
                val path = String(b, p, len * 2, Charsets.UTF_16LE); p += len * 2
                out.add(g, "LastSavedBy", "$who ($path)", privacy = Privacy.PERSON)
                num -= 2
            }
        }
    }

    private val DOC_FLAGS = listOf(0 to "Template", 1 to "AutoText only", 2 to "Complex", 3 to "Has picture", 8 to "Encrypted", 9 to "1Table",
        10 to "Read only", 11 to "Passworded", 12 to "ExtChar", 13 to "Load override", 14 to "Far east", 15 to "Obfuscated")

    /** Windows language numbers of the usual languages. */
    private val LCID = mapOf(0x0409 to "en-US", 0x0809 to "en-GB", 0x0C09 to "en-AU", 0x1009 to "en-CA", 0x0407 to "de-DE", 0x040C to "fr-FR",
        0x0C0C to "fr-CA", 0x0410 to "it-IT", 0x0C0A to "es-ES", 0x040A to "es-ES", 0x080A to "es-MX", 0x0416 to "pt-BR", 0x0816 to "pt-PT",
        0x0413 to "nl-NL", 0x0419 to "ru-RU", 0x0411 to "ja-JP", 0x0412 to "ko-KR", 0x0804 to "zh-CN", 0x0404 to "zh-TW", 0x0415 to "pl-PL",
        0x041D to "sv-SE", 0x0406 to "da-DK", 0x0414 to "nb-NO", 0x040B to "fi-FI", 0x0405 to "cs-CZ", 0x040E to "hu-HU", 0x0408 to "el-GR",
        0x041F to "tr-TR", 0x0401 to "ar-SA", 0x040D to "he-IL", 0x0422 to "uk-UA")

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
            if (id == Int.MIN_VALUE) { (value(b, at + o, cs) as? Long)?.let { lcid -> out.add("FlashPix", "LocaleIndicator", LCID[lcid.toInt()]?.let { java.util.Locale.forLanguageTag(it).displayName } ?: "$lcid", "$lcid") }; continue }
            if (id == 0 || id == 1) continue
            val raw = if (custom) dict[id] ?: continue else null
            // Office's hidden custom properties: the links in the document and their base address.
            if (raw == "_PID_LINKBASE" || raw == "_PID_HLINKS") {
                val blob = value(b, at + o, cs) as? ByteArray ?: continue
                if (raw == "_PID_LINKBASE") String(blob, Charsets.UTF_16LE).trimEnd('\u0000').let { out.add("FlashPix", "HyperlinkBase", it) }
                else hyperlinks(blob, cs).takeIf { it.isNotEmpty() }?.let { out.add("FlashPix", "Hyperlinks", it.joinToString(", "), privacy = Privacy.TEXT) }
                continue
            }
            val name = if (custom) tagName(raw!!) else names[id] ?: continue
            val p = at + o
            if (p + 4 > b.size) continue
            val v = value(b, p, cs) ?: continue
            when {
                name == "TotalEditTime" && v is FileTime -> { val s = v.t / 10_000_000; out.add("FlashPix", name, minutes(s), "$s") }
                v is FileTime -> fileTime(v.t)?.let { out.add("FlashPix", name, it, it, Privacy.TIME) }
                name == "AppVersion" && v is Long -> "${v shr 16}.${"%04d".format(v and 0xFFFF)}".let { out.add("FlashPix", name, it, it) }
                v is Boolean -> out.add("FlashPix", name, if (v) "Yes" else "No", if (v) "1" else "0")
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
            11 -> B.u16le(b, d) != 0
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
            65 -> { val n = B.u32le(b, d).toInt(); if (n <= 0 || d + 4 + n > b.size) null else b.copyOfRange(d + 4, d + 4 + n) }
            else -> null
        }
    }

    /** The links list: six values per link, the fifth its address and the sixth the place inside it. */
    private fun hyperlinks(blob: ByteArray, cs: Charset): List<String> {
        val n = B.u32le(blob, 0).toInt().coerceIn(0, 6000)
        val values = ArrayList<Any?>()
        var q = 4
        for (i in 0 until n) {
            if (q + 4 > blob.size) break
            values.add(value(blob, q, cs, 1, B.u16le(blob, q)))
            q += size(blob, q, cs)
        }
        return values.chunked(6).mapNotNull { l ->
            val url = l.getOrNull(4)?.toString().orEmpty().trim(); val sub = l.getOrNull(5)?.toString().orEmpty().trim()
            if (url.isEmpty() && sub.isEmpty()) null else if (sub.isEmpty()) url else "$url#$sub"
        }
    }

    private fun stringSize(b: ByteArray, q: Int, t: Int, cs: Charset): Int {
        val n = B.u32le(b, q).toInt().coerceAtLeast(0)
        val bytes = if (t == 31) n * 2 else n
        // Office writes 8-bit strings of a list one after another, but pads UTF-16 ones to 4 bytes.
        return 4 + bytes + if (t == 31) (4 - bytes % 4) % 4 else 0
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
        // The page's own encoding (a meta charset), else UTF-8 when it is valid, else Windows' Latin.
        val bytes = f.bytes(0, 1 shl 20)
        val declared = Regex("""charset\s*=\s*["']?([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE).find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val cs = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: if (validUtf8(bytes)) Charsets.UTF_8 else Charset.forName("windows-1252")
        val head = String(bytes, cs).let { s -> s.substring(0, s.indexOf("</head", ignoreCase = true).takeIf { it > 0 } ?: s.length) }
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(head)?.let { out.add("HTML", "Title", entities(it.groupValues[1]).trim()) }
        // Repeated names (several authors) become one list.
        val metas = LinkedHashMap<Pair<String, String>, MutableList<String>>()
        for (m in Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE).findAll(head)) {
            val tag = m.value
            fun a(n: String) = Regex("""\b$n\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag)?.let { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }
            val content = a("content") ?: continue
            val equiv = a("http-equiv")
            val name = a("name") ?: a("property") ?: equiv ?: continue
            if (name.equals("viewport", true)) continue
            // "dc.creator" or "og:title": the part before the dot or colon is the group, as ExifTool groups them.
            val split = Regex("""^([A-Za-z]+)[.:](.+)$""").find(name)
            val key = when {
                equiv != null && a("name") == null -> "HTTP-equiv" to tagName(name)
                split != null -> "HTML-" + split.groupValues[1].lowercase() to tagName(split.groupValues[2])
                else -> "HTML" to tagName(name)
            }
            metas.getOrPut(key) { ArrayList() }.add(entities(content))
        }
        for ((k, v) in metas) out.add(k.first, k.second, v.joinToString(", "))
        // Word's own document properties, kept in pages it saves as HTML.
        Regex("""<o:DocumentProperties>(.*?)</o:DocumentProperties>""", RegexOption.DOT_MATCHES_ALL).find(head)?.let { dp ->
            for (p in Regex("""<o:(\w+)>([^<]*)</o:\1>""").findAll(dp.groupValues[1])) {
                val n = OFFICE_HTML[p.groupValues[1]] ?: p.groupValues[1]
                val v = entities(p.groupValues[2]).trim()
                if (n.endsWith("Date") || n == "LastPrinted") out.add("HTML-office", n, XmpReader.exifDate(v), XmpReader.exifDate(v)) else out.add("HTML-office", n, v)
            }
        }
    }

    private val OFFICE_HTML = mapOf("Created" to "CreateDate", "LastSaved" to "ModifyDate", "LastPrinted" to "LastPrinted", "Revision" to "RevisionNumber")

    private fun validUtf8(b: ByteArray): Boolean {
        val d = Charsets.UTF_8.newDecoder()
        return try { d.decode(java.nio.ByteBuffer.wrap(b, 0, maxOf(0, b.size - 4))); true } catch (_: Exception) { false }
    }

    /** An SVG drawing's size and version, from its root element. */
    fun svg(f: MetaInput, out: Fields) {
        val s = String(f.bytes(0, 1 shl 16), Charsets.UTF_8)
        val tag = Regex("""<svg\b[^>]*>""", RegexOption.IGNORE_CASE).find(s)?.value ?: return
        for ((attr, name) in listOf("width" to "ImageWidth", "height" to "ImageHeight", "version" to "SVGVersion", "xmlns" to "Xmlns")) {
            Regex("""\s$attr\s*=\s*["']([^"']*)["']""").find(tag)?.let { out.add("SVG", name, it.groupValues[1]) }
        }
        Regex("""<title[^>]*>([^<]*)</title>""").find(s)?.let { out.add("SVG", "Title", entities(it.groupValues[1]).trim()) }
        Regex("""<desc[^>]*>([^<]*)</desc>""").find(s)?.let { out.add("SVG", "Description", entities(it.groupValues[1]).trim()) }
    }

    /** Character references and named entities (HTML's own and the XML five). */
    private fun entities(s: String) = Regex("""&(#[xX]?[0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);""").replace(s) { m ->
        val e = m.groupValues[1]
        val cp = when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)
            e.startsWith("#") -> e.substring(1).toIntOrNull()
            else -> when (e) { "amp" -> '&'.code; "lt" -> '<'.code; "gt" -> '>'.code; "quot" -> '"'.code; "apos" -> '\''.code; else -> XmlReader.HTML_ENTITIES[e] }
        }
        if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
    }

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
