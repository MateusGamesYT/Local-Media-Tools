package com.localmediatools.docs.core

import com.localmediatools.docs.core.XmlReader.Event
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn

/**
 * Word documents (.docx, .docm, .dotx) as flowing text: paragraphs with their styles (headings, bold,
 * italic, underline, size, colour, highlight, alignment, indents), bulleted and numbered lists as
 * Word numbers them, tables (merged cells, shading), pictures, text boxes, links, footnotes and
 * endnotes (gathered at the end), fields (their result), tracked changes (as accepted) and equations
 * (their text). Headers, footers and comments are left out; charts and diagrams are placeholders.
 */
class DocxReader private constructor(private val parts: Parts, private val main: String) {
    companion object {
        fun read(parts: Parts): FlowDocument {
            val main = parts.mainPart() ?: listOf("word/document.xml").firstOrNull { parts.has(it) }
                ?: throw UnreadableException(tr("This isn't a Word document: its main part is missing."))
            return DocxReader(parts, main).read()
        }
    }

    private val rels = parts.rels(main)
    private val styles = WordStyles.read(parts, rels.values.firstOrNull { it.type.endsWith("/styles") }?.target, rels.values.firstOrNull { it.type.endsWith("/theme") }?.target)
    /** The page of the document's first section (or its only one). */
    private var page: PageSetup? = null
    private val numbering = WordNumbering.read(parts, rels.values.firstOrNull { it.type.endsWith("/numbering") }?.target)
    private val footnoteBodies by lazy { notesPart("/footnotes", "footnote") }
    private val endnoteBodies by lazy { notesPart("/endnotes", "endnote") }
    private val footnotes = ArrayList<Pair<Int, List<Block>>>()
    private val endnotes = ArrayList<Pair<Int, List<Block>>>()
    private var charts = 0
    private var diagrams = 0
    private var linked = 0
    private var embedded = 0
    private var inNote = false

    private fun read(): FlowDocument {
        val x = parts.xml(main) ?: throw UnreadableException(tr("This isn't a Word document: its main part is missing."))
        val blocks = ArrayList<Block>()
        while (x.next() != Event.EOF) {
            if (x.isStart(Ns.W, "body")) { body(x, blocks); break }
        }
        if (footnotes.isNotEmpty() || endnotes.isNotEmpty()) {
            blocks.add(PageBreak)
            blocks.add(Paragraph(listOf(Span(tr("Notes"), RunStyle(bold = true, sizePt = styles.baseSizePt * 1.3f))), heading = 2))
            for ((n, b) in footnotes + endnotes.map { (n, b) -> n to b }) {
                val first = b.firstOrNull() as? Paragraph
                if (first != null) {
                    blocks.add(Paragraph(listOf(Span("$n. ", RunStyle(sizePt = first.spans.firstOrNull()?.style?.sizePt ?: 0f))) + first.spans, align = first.align))
                    blocks.addAll(b.drop(1))
                } else {
                    blocks.add(Paragraph(listOf(Span("$n.")))); blocks.addAll(b)
                }
            }
        }
        val notes = ArrayList<String>()
        if (charts > 0) notes.add(trn(charts, "{0} chart isn't shown.", "{0} charts aren't shown."))
        if (diagrams > 0) notes.add(trn(diagrams, "{0} diagram isn't shown.", "{0} diagrams aren't shown."))
        if (linked > 0) notes.add(trn(linked, "{0} picture is linked from outside the file and isn't shown.", "{0} pictures are linked from outside the file and aren't shown."))
        if (embedded > 0) notes.add(trn(embedded, "{0} embedded object is shown only as its picture.", "{0} embedded objects are shown only as their pictures."))
        val outline = blocks.withIndex().mapNotNull { (i, b) -> (b as? Paragraph)?.takeIf { it.heading in 1..3 && it.text.isNotBlank() }?.let { it.text.trim().take(80) to i } }
        val title = coreTitle(parts) ?: (blocks.firstOrNull { it is Paragraph && it.heading == 1 && it.text.isNotBlank() } as? Paragraph)?.text?.trim()
        return FlowDocument(title, blocks, Media { ref -> parts.bytes(ref, 64L shl 20) }, notes, outline, styles.baseSizePt,
            defaultFont = styles.defaultFont, page = page?.takeIf { it.sane })
    }

    // ------------------------------------------------------------------ block level
    private fun body(x: XmlReader, out: MutableList<Block>) {
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.ns == Ns.W && c.local == "p" -> paragraph(c, out)
                c.ns == Ns.W && c.local == "tbl" -> out.add(table(c))
                c.ns == Ns.W && (c.local == "sdt") -> sdt(c, out)
                c.ns == Ns.W && (c.local == "customXml" || c.local == "ins" || c.local == "moveTo") -> body(c, out)
                c.ns == Ns.W && c.local == "sdtContent" -> body(c, out)
                c.ns == Ns.W && c.local == "sectPr" -> { val sect = WordStyles.readSect(c); if (page == null) page = sect.page }
                c.ns == Ns.W && (c.local == "del" || c.local == "moveFrom") -> c.skip()
                c.ns == Ns.MC && c.local == "AlternateContent" -> alternate(c) { body(it, out) }
                c.ns == Ns.M && c.local == "oMathPara" -> out.add(Paragraph(listOf(Span(c.readText(), RunStyle(italic = true))), align = Align.CENTER))
                else -> c.skip()
            }
        }
    }

    private fun sdt(x: XmlReader, out: MutableList<Block>) {
        x.children { c ->
            if (c.event != Event.START) return@children
            if (c.ns == Ns.W && c.local == "sdtContent") body(c, out) else c.skip()
        }
    }

    /** mc:AlternateContent: the first Choice (DrawingML), never the Fallback (old VML copy of the same thing). */
    private inline fun alternate(x: XmlReader, inside: (XmlReader) -> Unit) {
        var done = false
        x.children { c ->
            if (c.event != Event.START) return@children
            if (!done && c.ns == Ns.MC && c.local == "Choice") { done = true; inside(c) }
            else if (!done && c.ns == Ns.MC && c.local == "Fallback") { done = true; inside(c) }
            else c.skip()
        }
    }

    // ------------------------------------------------------------------ paragraphs
    private class Fields { var depth = 0; var inResult = BooleanArray(16); var instr = Array(16) { StringBuilder() }; var link = arrayOfNulls<String>(16) }

    private inner class Builder(val props: WordStyles.PProps, val markStyle: WordStyles.RProps) {
        val spans = ArrayList<Span>()
        val trailing = ArrayList<Block>()
        val fields = Fields()
        var pageBreakAfter = false

        fun add(text: String, style: RunStyle, link: String?) {
            if (text.isEmpty()) return
            if (fields.depth > 0 && !fields.inResult[fields.depth - 1]) { fields.instr[fields.depth - 1].append(text); return }
            val l = link ?: (fields.depth - 1 downTo 0).firstNotNullOfOrNull { fields.link[it] }
            val last = spans.lastOrNull()
            if (last != null && last.style == style && last.link == l) spans[spans.size - 1] = Span(last.text + text, style, l)
            else spans.add(Span(text, style, l))
        }
    }

    private fun paragraph(x: XmlReader, out: MutableList<Block>) {
        var pPr = WordStyles.PProps()
        var mark = WordStyles.RProps()
        var b: Builder? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            if (c.ns == Ns.W && c.local == "pPr") {
                val (p, r) = styles.readPPr(c); pPr = p; mark = r
                return@children
            }
            val builder = b ?: Builder(pPr, mark).also { b = it }
            inline(c, builder, null)
        }
        val builder = b ?: Builder(pPr, mark)
        emit(builder, out)
    }

    private fun emit(b: Builder, out: MutableList<Block>) {
        val p = styles.effectiveP(b.props)
        if (p.pageBreakBefore && out.isNotEmpty()) out.add(PageBreak)
        val label = if (inNote) null else p.numId?.let { numbering.label(it, p.ilvl ?: 0) }
        val heading = p.outline?.let { if (it in 0..5) it + 1 else 0 } ?: styles.headingOf(b.props.styleId)
        val numIndent = p.numId?.let { numbering.indent(it, p.ilvl ?: 0) }
        val indent = p.indLeft ?: numIndent ?: 0f
        val spans = b.spans.toMutableList()
        // Trailing whitespace-only text is noise; drop an empty run at the end.
        while (spans.isNotEmpty() && spans.last().text.isEmpty()) spans.removeAt(spans.size - 1)
        val hasText = spans.any { it.text.isNotBlank() }
        if (hasText || label != null || b.trailing.isEmpty()) {
            out.add(Paragraph(spans, heading, p.align ?: Align.START, indent, p.firstLine ?: 0f, p.before ?: 0f, p.after ?: 0f,
                label?.takeIf { it.isNotEmpty() }, p.ilvl ?: 0, p.shading ?: 0, lineSpacing = p.lineMult ?: 0f, lineHeightPt = p.lineExact ?: 0f))
        }
        out.addAll(b.trailing)
        if (b.pageBreakAfter) out.add(PageBreak)
        // A section ending here: the next starts on a new page unless it is a continuous one.
        b.props.section?.let { sect -> if (page == null) page = sect.page; if (!sect.continuous) out.add(PageBreak) }
    }

    /** Run-level content: runs, links, fields, changes, content controls… */
    private fun inline(c: XmlReader, b: Builder, link: String?) {
        if (c.ns == Ns.W) when (c.local) {
            "r" -> { run(c, b, link); return }
            "hyperlink" -> {
                val target = c.attr(Ns.R, "id")?.let { rels[it]?.target } ?: c.attr(Ns.W, "anchor")?.let { "#$it" }
                c.children { k -> if (k.event == Event.START) inline(k, b, target ?: link) }
                return
            }
            "fldSimple" -> {
                val instr = c.attr(Ns.W, "instr") ?: ""
                val l = hyperlinkOf(instr) ?: link
                c.children { k -> if (k.event == Event.START) inline(k, b, l) }
                return
            }
            "ins", "moveTo", "smartTag", "customXml", "dir", "bdo", "sdtContent" -> { c.children { k -> if (k.event == Event.START) inline(k, b, link) }; return }
            "sdt" -> { c.children { k -> if (k.event == Event.START && k.ns == Ns.W && k.local == "sdtContent") inline(k, b, link) else if (k.event == Event.START) k.skip() }; return }
            "del", "moveFrom", "proofErr", "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd", "permStart", "permEnd" -> { c.skip(); return }
        }
        if (c.ns == Ns.M && (c.local == "oMath" || c.local == "oMathPara")) { b.add(c.readText(), RunStyle(italic = true), link); return }
        if (c.ns == Ns.MC && c.local == "AlternateContent") { alternate(c) { a -> a.children { k -> if (k.event == Event.START) inline(k, b, link) } }; return }
        c.skip()
    }

    private fun hyperlinkOf(instr: String): String? {
        val m = Regex("""^\s*HYPERLINK\s+(?:\\l\s+)?"([^"]*)"""").find(instr) ?: Regex("""^\s*HYPERLINK\s+(\S+)""").find(instr) ?: return null
        return m.groupValues[1]
    }

    private fun run(x: XmlReader, b: Builder, link: String?) {
        var rPr = WordStyles.RProps()
        x.children { c ->
            if (c.event != Event.START) return@children
            if (c.ns != Ns.W) {
                when {
                    c.ns == Ns.MC && c.local == "AlternateContent" -> alternate(c) { a -> a.children { k -> if (k.event == Event.START) runChild(k, b, rPr, link) } }
                    else -> c.skip()
                }
                return@children
            }
            if (c.local == "rPr") { rPr = styles.readRPr(c); return@children }
            runChild(c, b, rPr, link)
        }
    }

    private fun runChild(c: XmlReader, b: Builder, rPr: WordStyles.RProps, link: String?) {
        val eff = styles.effectiveR(b.props, rPr)
        if (c.ns == Ns.W) when (c.local) {
            "t" -> { val t = c.readText(); if (eff.vanish != true) b.add(if (eff.caps == true) t.uppercase() else t, eff.toStyle(styles.baseSizePt), link); return }
            "tab", "ptab" -> { b.add("\t", eff.toStyle(styles.baseSizePt), link); c.skip(); return }
            "br" -> {
                val type = c.attr(Ns.W, "type")
                if (type == "page") b.pageBreakAfter = true else b.add("\n", eff.toStyle(styles.baseSizePt), link)
                c.skip(); return
            }
            "cr" -> { b.add("\n", eff.toStyle(styles.baseSizePt), link); c.skip(); return }
            "noBreakHyphen" -> { b.add("‑", eff.toStyle(styles.baseSizePt), link); c.skip(); return }
            "sym" -> {
                val ch = c.attr(Ns.W, "char")?.toIntOrNull(16)?.toChar()
                if (ch != null) b.add(Units.symbolChar(ch, c.attr(Ns.W, "font")), eff.toStyle(styles.baseSizePt), link)
                c.skip(); return
            }
            "fldChar" -> {
                val f = b.fields
                when (c.attr(Ns.W, "fldCharType")) {
                    "begin" -> if (f.depth < 16) { f.instr[f.depth].setLength(0); f.inResult[f.depth] = false; f.link[f.depth] = null; f.depth++ }
                    "separate" -> if (f.depth > 0) { f.inResult[f.depth - 1] = true; f.link[f.depth - 1] = hyperlinkOf(f.instr[f.depth - 1].toString()) }
                    "end" -> if (f.depth > 0) f.depth--
                }
                c.skip(); return
            }
            "instrText" -> { val t = c.readText(); if (b.fields.depth > 0) b.fields.instr[b.fields.depth - 1].append(t); return }
            "delText", "delInstrText", "lastRenderedPageBreak", "softHyphen", "annotationRef", "commentReference", "separator", "continuationSeparator" -> { c.skip(); return }
            "footnoteReference", "endnoteReference" -> {
                val id = c.attr(Ns.W, "id"); c.skip()
                if (id == null || inNote) return
                val foot = c.local == "footnoteReference"
                val list = if (foot) footnotes else endnotes
                val body = (if (foot) footnoteBodies else endnoteBodies)[id] ?: return
                val n = footnotes.size + endnotes.size + 1
                list.add(n to body)
                b.add(n.toString(), eff.toStyle(styles.baseSizePt).copy(vertical = 1), link)
                return
            }
            "footnoteRef", "endnoteRef" -> { c.skip(); return }
            "drawing" -> { drawing(c, b); return }
            "pict", "object" -> { vml(c, b, c.local == "object"); return }
        }
        c.skip()
    }

    // ------------------------------------------------------------------ pictures and text boxes
    private fun drawing(x: XmlReader, b: Builder) {
        var w = 0f; var h = 0f; var alt: String? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.ns == Ns.WP && c.local == "extent" -> { w = Units.emuToPt(c.attr("cx")?.toLongOrNull() ?: 0); h = Units.emuToPt(c.attr("cy")?.toLongOrNull() ?: 0) }
                c.ns == Ns.WP && c.local == "docPr" -> alt = c.attr("descr")?.takeIf { it.isNotBlank() } ?: c.attr("title")?.takeIf { it.isNotBlank() }
                c.ns == Ns.A && c.local == "graphicData" -> graphic(c, b, w, h, alt)
                c.ns == Ns.WP || c.ns == Ns.A -> Unit // descend: inline/anchor/graphic
                else -> c.skip()
            }
        }
    }

    private fun graphic(x: XmlReader, b: Builder, w: Float, h: Float, alt: String?) {
        val uri = x.attr("uri") ?: ""
        when {
            uri.endsWith("/chart") || uri.contains("chart") -> { charts++; b.trailing.add(Placeholder(tr("Chart"))); x.skip(); return }
            uri.endsWith("/diagram") -> { diagrams++; b.trailing.add(Placeholder(tr("Diagram"))); x.skip(); return }
        }
        var found = false
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.local == "blip" && c.ns == Ns.A -> {
                    val embed = c.attr(Ns.R, "embed")
                    val rel = embed?.let { rels[it] }
                    if (rel != null && !rel.external) { b.trailing.add(Picture(rel.target, w, h, alt)); found = true }
                    else if (c.attr(Ns.R, "link") != null || rel?.external == true) { linked++; b.trailing.add(Placeholder(alt ?: tr("Linked picture"))); found = true }
                    c.skip()
                }
                c.ns == Ns.W && c.local == "txbxContent" -> { val inner = ArrayList<Block>(); body(c, inner); b.trailing.addAll(inner); found = true }
                else -> Unit // descend into pic:pic, wps:wsp, wpg:wgp, blipFill…
            }
        }
        if (!found && alt != null) b.trailing.add(Placeholder(alt))
    }

    private fun vml(x: XmlReader, b: Builder, isObject: Boolean) {
        if (isObject) embedded++
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.ns == Ns.V && c.local == "imagedata" -> {
                    val rel = (c.attr(Ns.R, "id") ?: c.attr(Ns.R, "pict"))?.let { rels[it] }
                    if (rel != null && !rel.external) b.trailing.add(Picture(rel.target, 0f, 0f, c.attr("title")))
                    c.skip()
                }
                c.ns == Ns.W && c.local == "txbxContent" -> { val inner = ArrayList<Block>(); body(c, inner); b.trailing.addAll(inner) }
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ tables
    private class CellB(val blocks: List<Block>, val span: Int, val shading: Int, val width: Float) { var rowSpan = 1 }

    private var tableDepth = 0

    private fun table(x: XmlReader): Block {
        // Tables nested beyond reason (a crafted file) are left out rather than followed down.
        if (tableDepth >= 24) { x.skip(); return Placeholder(tr("Table")) }
        tableDepth++
        try { return tableBody(x) } finally { tableDepth-- }
    }

    private fun tableBody(x: XmlReader): Table {
        val rows = ArrayList<Pair<List<CellB>, Boolean>>()
        val merges = ArrayList<List<Pair<Int, String?>>>() // per row: (grid column, vMerge) of each cell
        var styleBorders: Boolean? = null
        var direct: Boolean? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.ns == Ns.W && c.local == "tblPr" -> c.children { p ->
                    if (p.event != Event.START) return@children
                    when (p.local) {
                        "tblStyle" -> styleBorders = styles.tableHasBorders(p.attr(Ns.W, "val"))
                        "tblBorders" -> direct = bordersVisible(p)
                        else -> p.skip()
                    }
                }
                c.ns == Ns.W && c.local == "tr" -> rows.add(row(c, merges))
                c.ns == Ns.W && (c.local == "sdt" || c.local == "customXml") -> c.children { k ->
                    if (k.event == Event.START && k.local == "sdtContent") k.children { r -> if (r.event == Event.START && r.local == "tr") rows.add(row(r, merges)) else if (r.event == Event.START) r.skip() }
                    else if (k.event == Event.START && k.local == "tr") rows.add(row(k, merges))
                    else if (k.event == Event.START) k.skip()
                }
                else -> c.skip()
            }
        }
        // Vertical merges: a "continue" cell adds a row to the cell above it in the same grid column.
        val origin = HashMap<Int, CellB>()
        val kept = rows.mapIndexed { r, (cells, header) ->
            val keep = ArrayList<TableCell>()
            val info = merges[r]
            for ((k, cell) in cells.withIndex()) {
                val (col, vm) = info[k]
                if (vm == "continue") { origin[col]?.let { it.rowSpan++ }; continue }
                if (vm == "restart") origin[col] = cell else origin.remove(col)
                keep.add(TableCell(cell.blocks, cell.span, 0, cell.shading, cell.width).also { holder[it] = cell })
            }
            TableRow(keep, header)
        }
        val finalRows = kept.map { r -> TableRow(r.cells.map { tc -> holder[tc]?.let { cb -> TableCell(tc.blocks, tc.colSpan, cb.rowSpan, tc.shading, tc.widthPt) } ?: tc }, r.header) }
        holder.clear()
        return Table(finalRows, direct ?: styleBorders ?: false)
    }

    private val holder = java.util.IdentityHashMap<TableCell, CellB>()

    private fun bordersVisible(x: XmlReader): Boolean {
        var any = false
        x.children { c -> if (c.event == Event.START) { val v = c.attr(Ns.W, "val"); if (v != null && v != "nil" && v != "none") any = true; c.skip() } }
        return any
    }

    private fun row(x: XmlReader, merges: MutableList<List<Pair<Int, String?>>>): Pair<List<CellB>, Boolean> {
        val cells = ArrayList<CellB>()
        val info = ArrayList<Pair<Int, String?>>()
        var header = false
        var col = 0
        x.children { c ->
            if (c.event != Event.START) return@children
            when {
                c.ns == Ns.W && c.local == "trPr" -> c.children { p -> if (p.event == Event.START) { if (p.local == "tblHeader" && Units.onOff(p.attr(Ns.W, "val"))) header = true; if (p.local == "gridBefore") col += p.attr(Ns.W, "val")?.toIntOrNull() ?: 0; p.skip() } }
                c.ns == Ns.W && c.local == "tc" -> { val (cell, vm) = cell(c); info.add(col to vm); cells.add(cell); col += cell.span }
                c.ns == Ns.W && (c.local == "sdt" || c.local == "customXml") -> c.children { k ->
                    if (k.event == Event.START && k.local == "sdtContent") k.children { t -> if (t.event == Event.START && t.local == "tc") { val (cell, vm) = cell(t); info.add(col to vm); cells.add(cell); col += cell.span } else if (t.event == Event.START) t.skip() }
                    else if (k.event == Event.START) k.skip()
                }
                else -> c.skip()
            }
        }
        merges.add(info)
        return cells to header
    }

    private fun cell(x: XmlReader): Pair<CellB, String?> {
        var span = 1; var vm: String? = null; var shading = 0; var width = 0f
        val blocks = ArrayList<Block>()
        x.children { c ->
            if (c.event != Event.START) return@children
            if (c.ns == Ns.W && c.local == "tcPr") {
                c.children { p ->
                    if (p.event != Event.START) return@children
                    when (p.local) {
                        "gridSpan" -> span = (p.attr(Ns.W, "val")?.toIntOrNull() ?: 1).coerceIn(1, 64)
                        "vMerge" -> vm = p.attr(Ns.W, "val") ?: "continue"
                        "shd" -> shading = Units.hexColor(p.attr(Ns.W, "fill")) ?: 0
                        "tcW" -> if (p.attr(Ns.W, "type") == "dxa") width = Units.twipsToPt(p.attr(Ns.W, "w")?.toIntOrNull() ?: 0)
                    }
                    p.skip()
                }
                return@children
            }
            when {
                c.ns == Ns.W && c.local == "p" -> paragraph(c, blocks)
                c.ns == Ns.W && c.local == "tbl" -> blocks.add(table(c))
                c.ns == Ns.W && (c.local == "sdt" || c.local == "customXml") -> sdt(c, blocks)
                else -> c.skip()
            }
        }
        if (shading == 0xFFFFFFFF.toInt()) shading = 0
        return CellB(blocks, span, shading, width) to vm
    }

    // ------------------------------------------------------------------ notes
    private fun notesPart(typeSuffix: String, element: String): Map<String, List<Block>> {
        val part = rels.values.firstOrNull { it.type.endsWith(typeSuffix) && !it.external }?.target ?: return emptyMap()
        val x = parts.xml(part) ?: return emptyMap()
        val out = HashMap<String, List<Block>>()
        val saved = inNote
        inNote = true
        try {
            while (x.next() != Event.EOF) {
                if (x.isStart(Ns.W, element)) {
                    val type = x.attr(Ns.W, "type")
                    val id = x.attr(Ns.W, "id")
                    if (id == null || (type != null && type != "normal")) { x.skip(); continue }
                    val blocks = ArrayList<Block>()
                    body(x, blocks)
                    out[id] = blocks.filter { it !is Paragraph || !it.isEmpty() }
                }
            }
        } finally { inNote = saved }
        return out
    }
}

/** The document title from docProps/core.xml (dc:title), when there is one. */
fun coreTitle(parts: Parts): String? {
    val x = parts.xml("docProps/core.xml") ?: return null
    while (x.next() != Event.EOF) if (x.isStart(Ns.DC, "title")) return x.readText().trim().takeIf { it.isNotEmpty() }
    return null
}

/** Word's styles (word/styles.xml): defaults, paragraph and character styles, with inheritance. */
class WordStyles private constructor() {
    class RProps(
        var bold: Boolean? = null, var italic: Boolean? = null, var underline: Boolean? = null, var strike: Boolean? = null,
        var sizePt: Float? = null, var color: Int? = null, var highlight: Int? = null, var font: String? = null,
        var vertical: Int? = null, var caps: Boolean? = null, var vanish: Boolean? = null, var styleId: String? = null,
    ) {
        /** These properties with [o]'s set ones on top. */
        fun over(o: RProps?): RProps = if (o == null) this else RProps(
            o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.strike ?: strike, o.sizePt ?: sizePt, o.color ?: color,
            o.highlight ?: highlight, o.font ?: font, o.vertical ?: vertical, o.caps ?: caps, o.vanish ?: vanish, o.styleId ?: styleId,
        ).also { it.themeFonts = themeFonts ?: o.themeFonts }

        fun toStyle(base: Float) = RunStyle(
            bold = bold == true, italic = italic == true, underline = underline == true, strike = strike == true,
            sizePt = sizePt ?: base, color = color ?: 0, highlight = highlight ?: 0, monospace = Units.isMonospace(font),
            vertical = vertical ?: 0, font = font?.let { themeFonts?.get(it) ?: it.takeUnless { f -> f.startsWith("@") } },
        )

        /** The theme's fonts, for "@minor" and "@major" (set by [WordStyles]). */
        internal var themeFonts: Map<String, String>? = null
    }

    class PProps(
        var styleId: String? = null, var align: Align? = null, var indLeft: Float? = null, var firstLine: Float? = null,
        var before: Float? = null, var after: Float? = null, var numId: String? = null, var ilvl: Int? = null,
        var outline: Int? = null, var shading: Int? = null, var pageBreakBefore: Boolean = false, var section: Sect? = null,
        var lineMult: Float? = null, var lineExact: Float? = null,
    ) {
        fun over(o: PProps?): PProps = if (o == null) this else PProps(
            o.styleId ?: styleId, o.align ?: align, o.indLeft ?: indLeft, o.firstLine ?: firstLine, o.before ?: before, o.after ?: after,
            o.numId ?: numId, o.ilvl ?: ilvl, o.outline ?: outline, o.shading ?: shading, o.pageBreakBefore || pageBreakBefore, o.section,
            if (o.lineMult != null || o.lineExact != null) o.lineMult else lineMult, if (o.lineMult != null || o.lineExact != null) o.lineExact else lineExact,
        )
    }

    /** A section's properties: whether it follows on the same page, and its page. */
    class Sect(val continuous: Boolean, val page: PageSetup?)

    private class Style(val type: String, val name: String, val basedOn: String?, val r: RProps?, val p: PProps?, val tableBorders: Boolean?)

    private val byId = HashMap<String, Style>()
    private var defaultR = RProps()
    private var defaultP = PProps()
    private var defaultParaStyle: String? = null
    var baseSizePt = 10f; private set
    /** The font of text that names none. */
    var defaultFont: String? = null; private set
    private var themeFonts: Map<String, String> = emptyMap()

    companion object {
        fun read(parts: Parts, part: String?, themePart: String? = null): WordStyles {
            val s = WordStyles()
            s.themeFonts = readTheme(parts, themePart ?: "word/theme/theme1.xml")
            val x = part?.let { parts.xml(it) } ?: parts.xml("word/styles.xml")
            if (x != null) s.load(x)
            val base = s.effectiveR(PProps(), RProps())
            s.baseSizePt = base.sizePt ?: 10f
            s.defaultFont = base.toStyle(s.baseSizePt).font
            return s
        }

        /** The theme's heading ("@major") and body ("@minor") fonts. */
        private fun readTheme(parts: Parts, part: String): Map<String, String> {
            val x = parts.xml(part) ?: return emptyMap()
            val out = HashMap<String, String>()
            var which: String? = null
            while (x.next() != Event.EOF) {
                if (x.event == Event.START && x.ns == Ns.A) when (x.local) {
                    "majorFont" -> which = "@major"
                    "minorFont" -> which = "@minor"
                    "latin" -> which?.let { w -> x.attr("typeface")?.takeIf { it.isNotBlank() }?.let { if (w !in out) out[w] = it } }
                }
                if (x.event == Event.END && x.ns == Ns.A && (x.local == "majorFont" || x.local == "minorFont")) which = null
            }
            return out
        }

        /** A w:sectPr: its break type and page size and margins (twips). */
        fun readSect(x: XmlReader): Sect {
            var continuous = false
            var w = 0; var h = 0; var top = -1; var bottom = -1; var left = -1; var right = -1
            x.children { c ->
                if (c.event != Event.START) return@children
                if (c.ns == Ns.W) when (c.local) {
                    "type" -> continuous = c.attr(Ns.W, "val") == "continuous"
                    "pgSz" -> { w = c.attr(Ns.W, "w")?.toIntOrNull() ?: 0; h = c.attr(Ns.W, "h")?.toIntOrNull() ?: 0 }
                    "pgMar" -> {
                        top = c.attr(Ns.W, "top")?.toIntOrNull()?.let { kotlin.math.abs(it) } ?: -1
                        bottom = c.attr(Ns.W, "bottom")?.toIntOrNull()?.let { kotlin.math.abs(it) } ?: -1
                        left = (c.attr(Ns.W, "left") ?: c.attr(Ns.W, "start"))?.toIntOrNull() ?: -1
                        right = (c.attr(Ns.W, "right") ?: c.attr(Ns.W, "end"))?.toIntOrNull() ?: -1
                    }
                }
                c.skip()
            }
            val page = if (w > 0 && h > 0 && top >= 0 && bottom >= 0 && left >= 0 && right >= 0)
                PageSetup(Units.twipsToPt(w), Units.twipsToPt(h), Units.twipsToPt(top), Units.twipsToPt(bottom), Units.twipsToPt(left), Units.twipsToPt(right)) else null
            return Sect(continuous, page)
        }
    }

    private fun load(x: XmlReader) {
        while (x.next() != Event.EOF) {
            if (x.event != Event.START || x.ns != Ns.W) continue
            when (x.local) {
                "rPrDefault" -> x.children { c -> if (c.event == Event.START && c.local == "rPr") defaultR = readRPr(c) else if (c.event == Event.START) c.skip() }
                "pPrDefault" -> x.children { c -> if (c.event == Event.START && c.local == "pPr") defaultP = readPPr(c).first else if (c.event == Event.START) c.skip() }
                "style" -> style(x)
            }
        }
    }

    private fun style(x: XmlReader) {
        val type = x.attr(Ns.W, "type") ?: "paragraph"
        val id = x.attr(Ns.W, "styleId") ?: run { x.skip(); return }
        if (Units.onOff(x.attr(Ns.W, "default")) && x.attr(Ns.W, "default") != null && type == "paragraph") defaultParaStyle = id
        var name = id; var basedOn: String? = null; var r: RProps? = null; var p: PProps? = null; var borders: Boolean? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "name" -> { name = c.attr(Ns.W, "val") ?: id; c.skip() }
                "basedOn" -> { basedOn = c.attr(Ns.W, "val"); c.skip() }
                "rPr" -> r = readRPr(c)
                "pPr" -> p = readPPr(c).first
                "tblPr" -> c.children { t ->
                    if (t.event == Event.START && t.local == "tblBorders") {
                        var any = false
                        t.children { b -> if (b.event == Event.START) { val v = b.attr(Ns.W, "val"); if (v != null && v != "nil" && v != "none") any = true; b.skip() } }
                        borders = any
                    } else if (t.event == Event.START) t.skip()
                }
                else -> c.skip()
            }
        }
        byId[id] = Style(type, name, basedOn, r, p, borders)
    }

    fun readRPr(x: XmlReader): RProps {
        val r = RProps().also { it.themeFonts = themeFonts }
        x.children { c ->
            if (c.event != Event.START || c.ns != Ns.W) { if (c.event == Event.START) c.skip(); return@children }
            val v = c.attr(Ns.W, "val")
            when (c.local) {
                "b" -> r.bold = Units.onOff(v)
                "i" -> r.italic = Units.onOff(v)
                "u" -> r.underline = v != null && v != "none" || v == null
                "strike", "dstrike" -> r.strike = Units.onOff(v)
                "sz" -> r.sizePt = v?.toFloatOrNull()?.div(2f)
                "color" -> r.color = Units.hexColor(v) ?: 0
                "highlight" -> r.highlight = Units.highlightColor(v) ?: 0
                "shd" -> Units.hexColor(c.attr(Ns.W, "fill"))?.let { if (it != 0xFFFFFFFF.toInt()) r.highlight = it }
                "rFonts" -> r.font = c.attr(Ns.W, "ascii") ?: c.attr(Ns.W, "hAnsi")
                    ?: (c.attr(Ns.W, "asciiTheme") ?: c.attr(Ns.W, "hAnsiTheme"))?.let { t -> if (t.startsWith("major")) "@major" else "@minor" }
                    ?: c.attr(Ns.W, "cs")
                "vertAlign" -> r.vertical = when (v) { "superscript" -> 1; "subscript" -> -1; else -> 0 }
                "caps" -> r.caps = Units.onOff(v)
                "vanish" -> r.vanish = Units.onOff(v)
                "rStyle" -> r.styleId = v
            }
            c.skip()
        }
        return r
    }

    /** A paragraph's own properties and the properties of its mark. */
    fun readPPr(x: XmlReader): Pair<PProps, RProps> {
        val p = PProps()
        var mark = RProps()
        x.children { c ->
            if (c.event != Event.START || c.ns != Ns.W) { if (c.event == Event.START) c.skip(); return@children }
            val v = c.attr(Ns.W, "val")
            when (c.local) {
                "pStyle" -> { p.styleId = v; c.skip() }
                "jc" -> { p.align = when (v) { "center" -> Align.CENTER; "right", "end" -> Align.END; "both", "distribute", "lowKashida", "mediumKashida", "highKashida" -> Align.JUSTIFY; else -> Align.START }; c.skip() }
                "ind" -> {
                    (c.attr(Ns.W, "left") ?: c.attr(Ns.W, "start"))?.toIntOrNull()?.let { p.indLeft = Units.twipsToPt(it) }
                    c.attr(Ns.W, "firstLine")?.toIntOrNull()?.let { p.firstLine = Units.twipsToPt(it) }
                    c.attr(Ns.W, "hanging")?.toIntOrNull()?.let { p.firstLine = -Units.twipsToPt(it) }
                    c.skip()
                }
                "spacing" -> {
                    c.attr(Ns.W, "before")?.toIntOrNull()?.let { p.before = Units.twipsToPt(it) }
                    c.attr(Ns.W, "after")?.toIntOrNull()?.let { p.after = Units.twipsToPt(it) }
                    // Line spacing: 240ths of single ("auto"), else a height in twips (exact or at least).
                    c.attr(Ns.W, "line")?.toIntOrNull()?.takeIf { it > 0 }?.let { line ->
                        when (c.attr(Ns.W, "lineRule") ?: "auto") {
                            "auto" -> { p.lineMult = line / 240f; p.lineExact = null }
                            else -> { p.lineExact = Units.twipsToPt(line); p.lineMult = null }
                        }
                    }
                    c.skip()
                }
                "numPr" -> c.children { n ->
                    if (n.event == Event.START) {
                        when (n.local) { "numId" -> p.numId = n.attr(Ns.W, "val"); "ilvl" -> p.ilvl = n.attr(Ns.W, "val")?.toIntOrNull() }
                        n.skip()
                    }
                }
                "outlineLvl" -> { p.outline = v?.toIntOrNull(); c.skip() }
                "shd" -> { p.shading = Units.hexColor(c.attr(Ns.W, "fill"))?.takeIf { it != 0xFFFFFFFF.toInt() }; c.skip() }
                "pageBreakBefore" -> { p.pageBreakBefore = Units.onOff(v); c.skip() }
                "sectPr" -> p.section = readSect(c)
                "rPr" -> mark = readRPr(c)
                else -> c.skip()
            }
        }
        return p to mark
    }

    private fun chain(id: String?): List<Style> {
        val out = ArrayList<Style>()
        var cur = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val s = byId[cur] ?: break
            out.add(s)
            cur = s.basedOn
        }
        return out.asReversed()
    }

    /** The paragraph's properties after its style and the document defaults. */
    fun effectiveP(direct: PProps): PProps {
        var p = defaultP
        for (s in chain(direct.styleId ?: defaultParaStyle)) p = p.over(s.p)
        // A numId of "0" in a style or the paragraph switches numbering off.
        val e = p.over(direct)
        if (e.numId == "0") e.numId = null
        return e
    }

    fun effectiveR(para: PProps, direct: RProps): RProps {
        var r = defaultR
        for (s in chain(para.styleId ?: defaultParaStyle)) r = r.over(s.r)
        for (s in chain(direct.styleId)) r = r.over(s.r)
        return r.over(direct)
    }

    /** 1–6 when the paragraph style (or one it is based on) is a heading or a title. */
    fun headingOf(styleId: String?): Int {
        for (s in chain(styleId).asReversed()) {
            val n = s.name.lowercase()
            if (n == "title") return 1
            if (n == "subtitle") return 2
            Regex("""^heading\s*([1-9])$""").find(n)?.let { return it.groupValues[1].toInt().coerceAtMost(6) }
            s.p?.outline?.let { if (it in 0..5) return it + 1 }
        }
        return 0
    }

    fun tableHasBorders(id: String?): Boolean? {
        for (s in chain(id).asReversed()) s.tableBorders?.let { return it }
        return id?.let { it.contains("Grid", ignoreCase = true) || it.contains("TableNormal").not() && it.contains("Table", ignoreCase = true) }
    }
}

/** Word's list numbering (word/numbering.xml), counted as the document goes. */
class WordNumbering private constructor() {
    private class Lvl(val fmt: String, val text: String, val start: Int, val indent: Float?, val font: String?)
    private val abstracts = HashMap<String, HashMap<Int, Lvl>>()
    private val nums = HashMap<String, Pair<String, Map<Int, Int>>>()
    private val counters = HashMap<String, IntArray>()

    companion object {
        fun read(parts: Parts, part: String?): WordNumbering {
            val n = WordNumbering()
            val x = part?.let { parts.xml(it) } ?: parts.xml("word/numbering.xml")
            if (x != null) n.load(x)
            return n
        }
    }

    private fun load(x: XmlReader) {
        while (x.next() != Event.EOF) {
            if (x.event != Event.START || x.ns != Ns.W) continue
            when (x.local) {
                "abstractNum" -> {
                    val id = x.attr(Ns.W, "abstractNumId") ?: continue
                    val levels = HashMap<Int, Lvl>()
                    x.children { c -> if (c.event == Event.START && c.local == "lvl") { val i = c.attr(Ns.W, "ilvl")?.toIntOrNull() ?: 0; levels[i] = lvl(c) } else if (c.event == Event.START) c.skip() }
                    abstracts[id] = levels
                }
                "num" -> {
                    val id = x.attr(Ns.W, "numId") ?: continue
                    var abs: String? = null
                    val starts = HashMap<Int, Int>()
                    x.children { c ->
                        if (c.event != Event.START) return@children
                        when (c.local) {
                            "abstractNumId" -> { abs = c.attr(Ns.W, "val"); c.skip() }
                            "lvlOverride" -> {
                                val i = c.attr(Ns.W, "ilvl")?.toIntOrNull() ?: 0
                                c.children { o -> if (o.event == Event.START) { if (o.local == "startOverride") o.attr(Ns.W, "val")?.toIntOrNull()?.let { starts[i] = it }; o.skip() } }
                            }
                            else -> c.skip()
                        }
                    }
                    abs?.let { nums[id] = it to starts }
                }
            }
        }
    }

    private fun lvl(x: XmlReader): Lvl {
        var fmt = "decimal"; var text = "%1."; var start = 1; var indent: Float? = null; var font: String? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "numFmt" -> { fmt = c.attr(Ns.W, "val") ?: fmt; c.skip() }
                "lvlText" -> { text = c.attr(Ns.W, "val") ?: ""; c.skip() }
                "start" -> { start = c.attr(Ns.W, "val")?.toIntOrNull() ?: 1; c.skip() }
                "pPr" -> c.children { p -> if (p.event == Event.START) { if (p.local == "ind") (p.attr(Ns.W, "left") ?: p.attr(Ns.W, "start"))?.toIntOrNull()?.let { indent = Units.twipsToPt(it) }; p.skip() } }
                "rPr" -> c.children { r -> if (r.event == Event.START) { if (r.local == "rFonts") font = r.attr(Ns.W, "ascii") ?: r.attr(Ns.W, "hAnsi"); r.skip() } }
                else -> c.skip()
            }
        }
        return Lvl(fmt, text, start, indent, font)
    }

    fun indent(numId: String, ilvl: Int): Float? = nums[numId]?.let { abstracts[it.first]?.get(ilvl)?.indent }

    /** The mark for the next paragraph of list [numId] at level [ilvl] ("•", "2.", "1.3"), counting it. */
    fun label(numId: String, ilvl: Int): String? {
        val (abs, starts) = nums[numId] ?: return null
        val levels = abstracts[abs] ?: return null
        val lvl = levels[ilvl] ?: return null
        val key = if (starts.isNotEmpty()) "n$numId" else "a$abs"
        val c = counters.getOrPut(key) { IntArray(10) { -1 } }
        val l = ilvl.coerceIn(0, 9)
        c[l] = if (c[l] < 0) (starts[l] ?: lvl.start) else c[l] + 1
        for (k in l + 1 until 10) c[k] = -1
        if (lvl.fmt == "bullet") return lvl.text.map { Units.symbolChar(it, lvl.font) }.joinToString("").ifEmpty { "•" }
        if (lvl.fmt == "none") return lvl.text.replace(Regex("%[1-9]"), "").ifBlank { null }
        return Regex("%([1-9])").replace(lvl.text) { m ->
            val k = m.groupValues[1].toInt() - 1
            val lv = levels[k] ?: lvl
            val n = if (c[k] < 0) (starts[k] ?: lv.start) else c[k]
            Units.formatNumber(n, if (k == l) lvl.fmt else lv.fmt)
        }
    }
}
