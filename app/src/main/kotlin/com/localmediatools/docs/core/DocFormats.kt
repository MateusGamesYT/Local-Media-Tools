package com.localmediatools.docs.core

import com.localmediatools.i18n.tr
import java.io.File
import java.util.Locale

/** The kinds of file the reader opens. PDF is drawn by Android itself; the rest by the readers here. */
enum class DocFormat(val label: String) {
    PDF("PDF"), DOCX("Word"), DOC("Word 97–2003"), XLSX("Excel"), XLS("Excel 97–2003"), PPTX("PowerPoint"), PPT("PowerPoint 97–2003"),
    ODT("OpenDocument Text"), ODS("OpenDocument Spreadsheet"), ODP("OpenDocument Presentation"), RTF("RTF"), EPUB("EPUB"),
    CSV("CSV"), TSV("TSV"), MARKDOWN("Markdown"), HTML("HTML"), JSON("JSON"), XML("XML"), TEXT("Text"),
}

object DocFormats {
    /** What [file] is, from its contents first and its [name] second; null when it isn't something the reader opens. */
    fun detect(file: File, name: String): DocFormat? {
        val head = ByteArray(8192)
        val n = file.inputStream().use { s -> var k = 0; while (k < head.size) { val r = s.read(head, k, head.size - k); if (r <= 0) break; k += r }; k }
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (n >= 5 && String(head, 0, minOf(n, 1024), Charsets.ISO_8859_1).contains("%PDF-")) return DocFormat.PDF
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()) return zipKind(file, ext)
        if (n >= 8 && head.copyOf(8).contentEquals(Ole2.SIGNATURE)) return Ole2.kind(file)
        // Excel 2–4 save a bare stream of records starting with BOF; Word 2 starts with its own mark.
        if (n >= 8 && head[1].toInt() in listOf(0, 2, 4, 8) && head[0].toInt() == 9 && le16(head, 2) in 4..20 && ext in setOf("xls", "xlw", "xlt", "")) return DocFormat.XLS
        if (n >= 2 && le16(head, 0) == 0xA5DB) return DocFormat.DOC
        val start = String(head, 0, n.coerceAtMost(64), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\r', '\n', '\t')
        if (start.startsWith("{\\rtf")) return DocFormat.RTF
        if (!TextDecode.looksLikeText(head, n)) return null
        return when (ext) {
            "csv" -> DocFormat.CSV
            "tsv", "tab" -> DocFormat.TSV
            "md", "markdown", "mdown", "mkd" -> DocFormat.MARKDOWN
            "htm", "html", "xhtml", "xht" -> DocFormat.HTML
            "json", "geojson", "jsonl" -> DocFormat.JSON
            "xml", "svg", "plist", "gpx", "kml", "rss", "atom", "xsd", "xsl", "csproj", "config" -> DocFormat.XML
            else -> {
                val t = start.lowercase(Locale.ROOT)
                when {
                    t.startsWith("<!doctype html") || t.startsWith("<html") -> DocFormat.HTML
                    t.startsWith("<?xml") -> DocFormat.XML
                    else -> DocFormat.TEXT
                }
            }
        }
    }

    private fun zipKind(file: File, ext: String): DocFormat? {
        Parts(file).use { p ->
            p.bytes("mimetype", 256)?.let { m ->
                val t = String(m, Charsets.US_ASCII).trim()
                when {
                    t.startsWith("application/vnd.oasis.opendocument.text") -> return DocFormat.ODT
                    t.startsWith("application/vnd.oasis.opendocument.spreadsheet") -> return DocFormat.ODS
                    t.startsWith("application/vnd.oasis.opendocument.presentation") -> return DocFormat.ODP
                    t.startsWith("application/epub+zip") -> return DocFormat.EPUB
                }
            }
            val main = p.mainPart()
            val type = main?.let { p.contentType(it) } ?: ""
            if (type.contains("sheet.binary") || main?.endsWith(".bin") == true && main.startsWith("xl/")) throw UnreadableException(tr("Excel binary workbooks (.xlsb) can't be opened yet. Saved as .xlsx in Excel, they can."))
            return when {
                type.contains("wordprocessingml") || type.contains("ms-word") -> DocFormat.DOCX
                type.contains("spreadsheetml") || type.contains("ms-excel") -> DocFormat.XLSX
                type.contains("presentationml") || type.contains("ms-powerpoint") -> DocFormat.PPTX
                p.has("word/document.xml") -> DocFormat.DOCX
                p.has("xl/workbook.xml") -> DocFormat.XLSX
                p.has("ppt/presentation.xml") -> DocFormat.PPTX
                p.has("META-INF/container.xml") -> DocFormat.EPUB
                p.has("content.xml") -> when (ext) { "ods", "ots" -> DocFormat.ODS; "odp", "otp" -> DocFormat.ODP; else -> DocFormat.ODT }
                else -> null
            }
        }
    }

    /** Opens [file] (anything but PDF) with its reader. Number formats and month names follow [locale]. */
    fun open(file: File, format: DocFormat, locale: Locale = Locale.getDefault()): Opened = when (format) {
        DocFormat.DOCX -> Parts(file).let { p -> try { DocxReader.read(p).also { it.closer = p } } catch (e: Throwable) { p.close(); throw e } }
        DocFormat.XLSX -> Parts(file).use { XlsxReader.read(it, locale) }
        DocFormat.PPTX -> Parts(file).let { p -> try { PptxReader.read(p).also { it.closer = p } } catch (e: Throwable) { p.close(); throw e } }
        DocFormat.ODT -> Parts(file).let { p -> try { OdfReader.text(p).also { it.closer = p } } catch (e: Throwable) { p.close(); throw e } }
        DocFormat.ODS -> Parts(file).use { OdfReader.spreadsheet(it) }
        DocFormat.ODP -> Parts(file).let { p -> try { OdfReader.presentation(p).also { it.closer = p } } catch (e: Throwable) { p.close(); throw e } }
        DocFormat.EPUB -> Parts(file).let { p -> try { EpubReader.read(p).also { it.closer = p } } catch (e: Throwable) { p.close(); throw e } }
        DocFormat.DOC -> if (!isOle(file)) throw UnreadableException(tr("This file was made by a very old Word version, which this app can't read.")) else Ole2(file).use { DocReader.read(it) }
        DocFormat.XLS -> if (isOle(file)) Ole2(file).use { XlsReader.read(it, locale) } else XlsReader.readRaw(file.readBytes(), locale)
        DocFormat.PPT -> Ole2(file).use { PptReader.read(it) }
        DocFormat.RTF -> RtfReader.read(file.readBytes())
        DocFormat.CSV, DocFormat.TSV -> CsvReader.read(file, if (format == DocFormat.TSV) '\t' else null)
        DocFormat.MARKDOWN -> capped(file) { TextReaders.markdown(TextDecode.decode(it)) }
        DocFormat.HTML -> capped(file) { HtmlReader.read(it, file.name) }
        DocFormat.JSON -> capped(file) { TextReaders.json(TextDecode.decode(it)) }
        DocFormat.XML, DocFormat.TEXT -> capped(file) { TextReaders.plain(TextDecode.decode(it), file.name) }
        DocFormat.PDF -> throw UnreadableException(tr("PDF files are shown by the PDF viewer."))
    }
}

private fun isOle(file: File): Boolean = file.inputStream().use { s -> ByteArray(8).also { s.read(it) } }.contentEquals(Ole2.SIGNATURE)

/** Text files are read up to [TEXT_LIMIT] bytes (a phone's memory); longer ones say so. */
const val TEXT_LIMIT = 24 shl 20

/** The first [limit] bytes of [file]. */
fun headOf(file: File, limit: Int): ByteArray {
    if (file.length() <= limit) return file.readBytes()
    return file.inputStream().use { s -> ByteArray(limit).also { b -> var n = 0; while (n < b.size) { val k = s.read(b, n, b.size - n); if (k <= 0) break; n += k } } }
}

private inline fun capped(file: File, read: (ByteArray) -> FlowDocument): FlowDocument {
    val doc = read(headOf(file, TEXT_LIMIT))
    if (file.length() <= TEXT_LIMIT) return doc
    return FlowDocument(doc.title, doc.blocks, doc.media, doc.notes + tr("This file is very long: only its first {0} MB are shown.", TEXT_LIMIT shr 20), doc.outline, doc.baseSizePt)
}

/** All the text of an opened file, in reading order: for search, and to check the readers against others. */
fun Opened.plainText(limit: Int = 16 shl 20): String {
    val sb = StringBuilder()
    fun blocks(list: List<Block>) {
        for (b in list) when (b) {
            is Paragraph -> { if (sb.length > limit) return; b.listLabel?.let { sb.append(it).append(' ') }; sb.append(b.text).append('\n') }
            is Table -> for (r in b.rows) { for (c in r.cells) { blocks(c.blocks); sb.append('\t') }; sb.append('\n') }
            is Placeholder, is Picture, PageBreak, Rule -> Unit
        }
    }
    when (this) {
        is FlowDocument -> blocks(blocks)
        is Workbook -> for (s in sheets) {
            sb.append(s.name).append('\n')
            for (r in s.cells.rowIndices()) { if (sb.length > limit) break; s.cells.row(r)?.values?.forEach { sb.append(it.text.take(32768)).append('\t') }; sb.append('\n') }
        }
        is Deck -> for (s in slides) {
            for (sh in s.shapes) { sh.text?.let { blocks(it.paragraphs) }; sh.table?.let { blocks(listOf(it)) } }
            blocks(s.notes)
        }
    }
    return sb.toString()
}
