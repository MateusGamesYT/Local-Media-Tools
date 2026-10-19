package com.localmediatools.docs.core

import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Rich Text Format (.rtf): text with bold, italic, underline, strike, size, colour and highlight;
 * paragraph alignment and indents; tables; list marks; headings (by style name); PNG and JPEG
 * pictures; field results (links' text) and Unicode, in the file's code pages (Western, Central
 * European, Cyrillic, Greek, Turkish, Japanese, Chinese, Korean…). Headers, footers and older
 * metafile pictures are left out.
 */
class RtfReader private constructor(private val b: ByteArray) {
    companion object {
        fun read(bytes: ByteArray): FlowDocument = RtfReader(bytes).parse()

        private val CHARSETS = mapOf(0 to 1252, 1 to 1252, 2 to 1252, 77 to 10000, 128 to 932, 129 to 949, 130 to 1361, 134 to 936, 136 to 950,
            161 to 1253, 162 to 1254, 163 to 1258, 177 to 1255, 178 to 1256, 186 to 1257, 204 to 1251, 222 to 874, 238 to 1250, 254 to 437, 255 to 850)

        /** Destinations whose text is read (tables, title, list marks, field instructions) though not shown. */
        private val TABLES = setOf("fonttbl", "colortbl", "stylesheet", "title", "listtext", "pntext", "fldinst")

        private val SKIP = setOf("fonttbl", "colortbl", "stylesheet", "info", "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
            "listtable", "listoverridetable", "revtbl", "rsidtbl", "xmlnstbl", "themedata", "colorschememapping", "datastore", "latentstyles",
            "generator", "pgdsctbl", "nonshppict", "object", "objdata", "bkmkstart", "bkmkend", "pnseclvl", "filetbl", "mmathPr", "wgrffmtfilter",
            "xmlopen", "xmlattrname", "xmlattrvalue", "docvar", "template", "userprops", "ftnsep", "ftnsepc", "aftnsep", "aftnsepc", "annotation", "atnid", "atnauthor")
    }

    private class Chr(
        var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false, var strike: Boolean = false,
        var size: Float = 12f, var color: Int = 0, var highlight: Int = 0, var font: Int = -1, var vertical: Int = 0, var hidden: Boolean = false,
    ) { fun copy() = Chr(bold, italic, underline, strike, size, color, highlight, font, vertical, hidden) }

    private class Par(var align: Align = Align.START, var left: Float = 0f, var first: Float = 0f, var before: Float = 0f, var after: Float = 0f, var inTable: Boolean = false, var style: Int = 0) {
        fun copy() = Par(align, left, first, before, after, inTable, style)
    }

    private class State(var chr: Chr, var par: Par, var skip: Boolean, var dest: String?, var uc: Int)

    private var pos = 0
    private var state = State(Chr(), Par(), false, null, 1)
    private val stack = ArrayList<State>()
    private var codePage = 1252
    private val fonts = HashMap<Int, Pair<Int, String>>() // number → (code page, name)
    private val colors = ArrayList<Int>()
    private val styleNames = HashMap<Int, String>()
    private var title: String? = null
    private val bytesPending = ByteArrayOutputStream()
    private var skipChars = 0

    private val blocks = ArrayList<Block>()
    private val spans = ArrayList<Span>()
    private val rows = ArrayList<TableRow>()
    private val cells = ArrayList<TableCell>()
    private val cellBlocks = ArrayList<Block>()
    private var fieldLink: String? = null
    private var listLabel: String? = null
    private val images = HashMap<String, ByteArray>()
    private var metafiles = 0

    private fun parse(): FlowDocument {
        while (pos < b.size) {
            val c = b[pos].toInt() and 0xFF
            when (c) {
                '{'.code -> { flushBytes(); pos++; stack.add(state); state = State(state.chr.copy(), state.par.copy(), state.skip, state.dest, state.uc) }
                '}'.code -> { flushBytes(); pos++; endGroup() }
                '\\'.code -> control()
                '\r'.code, '\n'.code -> pos++
                else -> { pos++; if (skipChars > 0) skipChars-- else if (!state.skip || state.dest in TABLES) { flushBytes(); text(c.toChar().toString()) } }
            }
        }
        flushBytes()
        endParagraph(false)
        flushTable()
        val notes = ArrayList<String>()
        if (metafiles > 0) notes.add(trn(metafiles, "{0} picture in an older format isn't shown.", "{0} pictures in an older format aren't shown."))
        return FlowDocument(title, blocks, Media { images[it] }, notes, HtmlReader.outlineOf(blocks), 12f)
    }

    private fun endGroup() {
        val ending = state
        if (stack.isEmpty()) return
        state = stack.removeAt(stack.size - 1)
        when (ending.dest) {
            "fonttbl-entry" -> Unit
            "fldrslt" -> if (state.dest != "fldrslt") fieldLink = null
        }
        if (ending.dest == "pict") finishPicture()
        if (ending.dest == "listtext" || ending.dest == "pntext") listLabel = listText.toString().trim().ifEmpty { null }.also { listText.setLength(0) }
    }

    private val listText = StringBuilder()
    private val fldInst = StringBuilder()

    // ------------------------------------------------------------------ control words
    private fun control() {
        pos++ // backslash
        if (pos >= b.size) return
        val c = b[pos].toInt() and 0xFF
        if (!(c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code)) {
            pos++
            when (c.toChar()) {
                '\'' -> {
                    if (pos + 2 <= b.size) {
                        val v = String(b, pos, 2, Charsets.US_ASCII).toIntOrNull(16)
                        pos += 2
                        if (v != null) { if (skipChars > 0) skipChars-- else if (!state.skip || state.dest == "pict" || state.dest in TABLES) bytesPending.write(v) }
                    }
                }
                '*' -> { state.skip = true; state.dest = "*" }
                '~' -> symbol(" ")
                '_' -> symbol("‑")
                '-' -> Unit
                '\\', '{', '}' -> symbol(c.toChar().toString())
                '\n', '\r' -> symbol("\n").also { endParagraph(true) }
                '|', ':' -> Unit
                else -> Unit
            }
            return
        }
        val start = pos
        while (pos < b.size && (b[pos].toInt().toChar().isLetter())) pos++
        val word = String(b, start, pos - start, Charsets.US_ASCII)
        var param: Int? = null
        if (pos < b.size && (b[pos] == '-'.code.toByte() || b[pos].toInt().toChar().isDigit())) {
            val ps = pos
            pos++
            while (pos < b.size && b[pos].toInt().toChar().isDigit()) pos++
            param = String(b, ps, pos - ps, Charsets.US_ASCII).toIntOrNull()
        }
        if (pos < b.size && b[pos] == ' '.code.toByte()) pos++
        if (word != "u" && word != "bin") flushBytes()
        word(word, param)
    }

    private fun word(w: String, p: Int?) {
        val chr = state.chr; val par = state.par
        when (w) {
            "rtf", "ansi", "mac", "pc", "pca" -> Unit
            "ansicpg" -> codePage = p ?: codePage
            "uc" -> state.uc = p ?: 1
            "u" -> {
                flushBytes()
                val cp = (p ?: 0).let { if (it < 0) it + 65536 else it }
                if (!state.skip || state.dest in TABLES) text(String(Character.toChars(cp)))
                skipChars = state.uc
            }
            "bin" -> { val n = (p ?: 0).coerceAtLeast(0); if (state.dest == "pict") pictBytes.write(b, pos, minOf(n, b.size - pos)); pos += n }
            "par", "sect" -> endParagraph(false)
            "line" -> symbol("\n")
            "page" -> { endParagraph(false); if (!state.skip) blocks.add(PageBreak) }
            "tab" -> symbol("\t")
            "emdash" -> symbol("—"); "endash" -> symbol("–"); "bullet" -> symbol("•")
            "lquote" -> symbol("‘"); "rquote" -> symbol("’"); "ldblquote" -> symbol("“"); "rdblquote" -> symbol("”")
            "emspace", "enspace", "qmspace" -> symbol(" ")
            "cell", "nestcell" -> { endParagraph(true); if (!state.skip) { cells.add(TableCell(cellBlocks.toList())); cellBlocks.clear() } }
            "row", "nestrow" -> { if (!state.skip) { if (cellBlocks.isNotEmpty()) { cells.add(TableCell(cellBlocks.toList())); cellBlocks.clear() }; if (cells.isNotEmpty()) rows.add(TableRow(cells.toList())); cells.clear() } }
            "intbl" -> par.inTable = true
            "trowd" -> Unit
            "pard" -> state.par = Par()
            "plain" -> state.chr = Chr()
            "b" -> chr.bold = p != 0
            "i" -> chr.italic = p != 0
            "ul", "uld", "uldb", "ulw", "ulth", "uldash" -> chr.underline = p != 0
            "ulnone" -> chr.underline = false
            "strike", "striked" -> chr.strike = p != 0
            "fs" -> chr.size = (p ?: 24) / 2f
            "cf" -> chr.color = colors.getOrElse(p ?: 0) { 0 }
            "highlight", "chcbpat", "cb" -> chr.highlight = colors.getOrElse(p ?: 0) { 0 }.let { if (it == 0xFFFFFFFF.toInt()) 0 else it }
            "f" -> if (state.dest == "fonttbl") { currentFont = p ?: 0; currentFontCharset = -1; fontName.setLength(0) } else chr.font = p ?: -1
            "fcharset" -> if (state.dest == "fonttbl") currentFontCharset = p ?: 0
            "super" -> chr.vertical = 1
            "sub" -> chr.vertical = -1
            "nosupersub" -> chr.vertical = 0
            "v" -> chr.hidden = p != 0
            "ql" -> par.align = Align.START; "qc" -> par.align = Align.CENTER; "qr" -> par.align = Align.END; "qj", "qd" -> par.align = Align.JUSTIFY
            "li" -> par.left = (p ?: 0) / 20f
            "fi" -> par.first = (p ?: 0) / 20f
            "sb" -> par.before = (p ?: 0) / 20f
            "sa" -> par.after = (p ?: 0) / 20f
            "s" -> if (state.dest == "stylesheet") currentStyle = p ?: 0 else par.style = p ?: 0
            "red" -> red = p ?: 0; "green" -> green = p ?: 0; "blue" -> blue = p ?: 0
            // Destinations.
            "fonttbl" -> { state.skip = true; state.dest = "fonttbl" }
            "colortbl" -> { state.skip = true; state.dest = "colortbl"; red = 0; green = 0; blue = 0 }
            "stylesheet" -> { state.skip = true; state.dest = "stylesheet"; styleText.setLength(0) }
            "info" -> { state.skip = true; state.dest = "info" }
            "title" -> if (state.dest == "info") { state.dest = "title" }
            "fldinst" -> { state.skip = true; state.dest = "fldinst"; fldInst.setLength(0) }
            "fldrslt" -> {
                state.skip = false; state.dest = "fldrslt"
                fieldLink = Regex("""HYPERLINK\s+"([^"]+)"""").find(fldInst)?.groupValues?.get(1)
            }
            "pict" -> { state.skip = true; state.dest = "pict"; pictBytes.reset(); pictType = null; pictW = 0f; pictH = 0f }
            "pngblip" -> pictType = "png"; "jpegblip" -> pictType = "jpg"
            "wmetafile", "emfblip", "macpict", "pmmetafile", "dibitmap", "wbitmap" -> pictType = "meta"
            "picwgoal" -> pictW = (p ?: 0) / 20f
            "pichgoal" -> pictH = (p ?: 0) / 20f
            "listtext", "pntext" -> { state.skip = true; state.dest = w; listText.setLength(0) }
            "shppict" -> { state.skip = false; state.dest = "shppict" }
            "result" -> { state.skip = false; state.dest = "result" }
            "footnote" -> { state.skip = true; state.dest = "footnote" }
            "field" -> Unit
            else -> if (w in SKIP) { state.skip = true; state.dest = w }
        }
    }

    // Font, colour and style tables.
    private var currentFont = 0
    private var currentFontCharset = -1
    private val fontName = StringBuilder()
    private var red = 0; private var green = 0; private var blue = 0
    private var currentStyle = 0
    private val styleText = StringBuilder()

    // Pictures.
    private val pictBytes = ByteArrayOutputStream()
    private var pictType: String? = null
    private var pictW = 0f; private var pictH = 0f

    private fun finishPicture() {
        val data = pictBytes.toByteArray().let { raw -> if (raw.isNotEmpty() && raw.all { (it in '0'.code..'9'.code) || (it in 'a'.code..'f'.code) || (it in 'A'.code..'F'.code) || it == ' '.code.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() }) hex(raw) else raw }
        when (pictType) {
            "png", "jpg" -> if (data.isNotEmpty()) {
                val ref = "rtf-image-${images.size}.$pictType"
                images[ref] = data
                endParagraph(true)
                (if (state.par.inTable) cellBlocks else blocks).add(Picture(ref, pictW, pictH))
            }
            "meta" -> metafiles++
        }
        pictBytes.reset()
    }

    private fun hex(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 2)
        var hi = -1
        for (b in raw) {
            val v = Character.digit(b.toInt().toChar(), 16)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write(hi * 16 + v); hi = -1 }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ text
    private fun charsetFor(font: Int): Int = fonts[font]?.first?.takeIf { it > 0 } ?: codePage

    private fun flushBytes() {
        if (bytesPending.size() == 0) return
        val bytes = bytesPending.toByteArray(); bytesPending.reset()
        if (state.dest == "pict") { pictBytes.write(bytes); return }
        val cs = try { Charset.forName(if (charsetFor(state.chr.font) == 10000) "x-MacRoman" else "windows-${charsetFor(state.chr.font)}") } catch (_: Exception) {
            try { Charset.forName("Cp${charsetFor(state.chr.font)}") } catch (_: Exception) { Charset.forName("windows-1252") }
        }
        text(String(bytes, cs))
    }

    private fun symbol(s: String) {
        if (skipChars > 0) { skipChars--; return }
        if (!state.skip || state.dest in TABLES) text(s)
    }

    private fun text(s: String) {
        when (state.dest) {
            "fonttbl" -> { fontName.append(s); if (s.contains(';')) { fonts[currentFont] = (CHARSETS[currentFontCharset] ?: -1) to fontName.toString().substringBefore(';').trim(); fontName.setLength(0) }; return }
            "colortbl" -> { if (s.contains(';')) { colors.add(if (colors.isEmpty() && red == 0 && green == 0 && blue == 0 && s.trim() == ";") 0 else (0xFF shl 24) or (red shl 16) or (green shl 8) or blue); red = 0; green = 0; blue = 0 }; return }
            "stylesheet" -> { styleText.append(s); if (s.contains(';')) { styleNames[currentStyle] = styleText.toString().substringBefore(';').trim(); styleText.setLength(0); currentStyle = 0 }; return }
            "title" -> { title = (title ?: "") + s; return }
            "fldinst" -> { fldInst.append(s); return }
            "listtext", "pntext" -> {
                val f = fonts[state.chr.font]?.second
                listText.append(if (f != null && (f.lowercase().startsWith("symbol") || f.lowercase().startsWith("wingdings"))) s.map { Units.symbolChar(it, f) }.joinToString("") else s)
                return
            }
        }
        if (state.skip) return
        val c = state.chr
        if (c.hidden) return
        val fontName = fonts[c.font]?.second
        val style = RunStyle(c.bold, c.italic, c.underline, c.strike, c.size, c.color, c.highlight, Units.isMonospace(fontName), c.vertical, fontName)
        val t = if (fonts[c.font]?.second?.lowercase()?.startsWith("symbol") == true) s.map { Units.symbolChar(it, "Symbol") }.joinToString("") else s
        val last = spans.lastOrNull()
        if (last != null && last.style == style && last.link == fieldLink) spans[spans.size - 1] = Span(last.text + t, style, fieldLink) else spans.add(Span(t, style, fieldLink))
    }

    private fun endParagraph(inCell: Boolean) {
        if (state.skip && state.dest != "fldrslt") return
        val p = state.par
        val heading = styleNames[p.style]?.lowercase()?.let { n -> Regex("""heading\s*([1-6])""").find(n)?.groupValues?.get(1)?.toInt() ?: if (n == "title") 1 else 0 } ?: 0
        val content = spans.toList(); spans.clear()
        val para = Paragraph(content, heading, p.align, p.left, p.first, p.before, p.after, listLabel)
        listLabel = null
        if (p.inTable || inCell) { if (content.isNotEmpty() || para.listLabel != null) cellBlocks.add(para); return }
        flushTable()
        blocks.add(para)
    }

    private fun flushTable() {
        if (cellBlocks.isNotEmpty() || cells.isNotEmpty()) { if (cellBlocks.isNotEmpty()) cells.add(TableCell(cellBlocks.toList())); cellBlocks.clear(); rows.add(TableRow(cells.toList())); cells.clear() }
        if (rows.isNotEmpty()) { blocks.add(Table(rows.toList())); rows.clear() }
    }

    init { if (b.isEmpty()) throw UnreadableException(tr("The file is empty.")) }
}
