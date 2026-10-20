package com.localmediatools.docs.core

import com.localmediatools.docs.core.XmlReader.Event
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn

/**
 * OpenDocument files (LibreOffice, OpenOffice and others): text (.odt) as flowing text with styles,
 * lists, tables, pictures and notes; spreadsheets (.ods) with the cells as their program showed them
 * (OpenDocument stores the shown text), styles, widths, merges and formulas; presentations (.odp)
 * with each slide's frames, shapes, pictures, text and notes.
 */
object OdfReader {
    // ------------------------------------------------------------------ styles
    private class St(
        val parent: String?, val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null, val strike: Boolean? = null,
        val size: Float? = null, val sizePercent: Float? = null, val color: Int? = null, val background: Int? = null, val font: String? = null,
        val vertical: Int? = null, val align: Align? = null, val marginLeft: Float? = null, val indent: Float? = null, val before: Float? = null,
        val after: Float? = null, val paraBackground: Int? = null, val outline: Int? = null, val colWidth: Float? = null, val rowHeight: Float? = null,
        val cellFill: Int? = null, val cellAlign: HAlign? = null, val cellVAlign: VAlign? = null, val wrap: Boolean? = null, val border: Int? = null,
        val fill: Int? = null, val noFill: Boolean = false, val stroke: Int? = null, val noStroke: Boolean = false, val strokeWidth: Float? = null,
        val listStyle: String? = null, val display: String? = null, val name: String = "",
    )

    private class ListLevel(val bullet: String?, val format: String?, val suffix: String, val prefix: String, val start: Int)

    private class Styles {
        val byName = HashMap<String, St>()
        val lists = HashMap<String, Map<Int, ListLevel>>()
        var pageW = 0f; var pageH = 0f
        val pageLayouts = HashMap<String, Pair<Float, Float>>()
        val masters = HashMap<String, String>()

        fun chain(name: String?): List<St> {
            val out = ArrayList<St>(); var cur = name; val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) { val s = byName[cur] ?: break; out.add(s); cur = s.parent }
            return out
        }

        inline fun <T> first(name: String?, f: (St) -> T?): T? { for (s in chain(name)) f(s)?.let { return it }; return null }

        fun run(name: String?, base: RunStyle): RunStyle {
            val size = first(name) { it.size } ?: first(name) { it.sizePercent }?.let { (if (base.sizePt > 0) base.sizePt else 12f) * it / 100f }
            return base.copy(
                bold = first(name) { it.bold } ?: base.bold, italic = first(name) { it.italic } ?: base.italic,
                underline = first(name) { it.underline } ?: base.underline, strike = first(name) { it.strike } ?: base.strike,
                sizePt = size ?: base.sizePt, color = first(name) { it.color } ?: base.color, highlight = first(name) { it.background } ?: base.highlight,
                font = first(name) { it.font } ?: base.font, monospace = Units.isMonospace(first(name) { it.font } ?: base.font) || base.monospace,
                vertical = first(name) { it.vertical } ?: base.vertical,
            )
        }

        fun headingLevel(name: String?): Int = first(name) { it.outline } ?: chain(name).firstNotNullOfOrNull { s ->
            Regex("""^(?:Heading|Überschrift|Título|Titre|Titolo)[ _]?(?:_20_)?([1-6])$""", RegexOption.IGNORE_CASE).find(s.name)?.groupValues?.get(1)?.toInt()
                ?: if (s.name.equals("Title", true)) 1 else null
        } ?: 0
    }

    private fun readStyles(x: XmlReader, into: Styles) {
        var inMasterStyles = false
        while (x.next() != Event.EOF) {
            if (x.event == Event.END && x.local == "master-styles") inMasterStyles = false
            if (x.event != Event.START) continue
            when {
                x.ns == Ns.OFFICE && x.local == "master-styles" -> inMasterStyles = true
                x.ns == Ns.OFFICE && (x.local == "body") -> return
                x.ns == Ns.STYLE && (x.local == "style" || x.local == "default-style") -> style(x)?.let { (n, s) -> into.byName[n] = s }
                x.ns == Ns.TEXT && x.local == "list-style" -> x.attr(Ns.STYLE, "name")?.let { n -> into.lists[n] = listStyle(x) }
                x.ns == Ns.STYLE && x.local == "page-layout" -> {
                    val n = x.attr(Ns.STYLE, "name") ?: continue
                    x.children { c -> if (c.event == Event.START) { if (c.local == "page-layout-properties") into.pageLayouts[n] = (Units.lengthToPt(c.attr(Ns.FO, "page-width")) ?: 0f) to (Units.lengthToPt(c.attr(Ns.FO, "page-height")) ?: 0f); c.skip() } }
                }
                x.ns == Ns.STYLE && x.local == "master-page" -> {
                    val n = x.attr(Ns.STYLE, "name"); val pl = x.attr(Ns.STYLE, "page-layout-name")
                    if (n != null && pl != null) into.masters[n] = pl
                    if (!inMasterStyles) x.skip() else x.skip()
                }
            }
        }
    }

    private fun style(x: XmlReader): Pair<String, St>? {
        val name = x.attr(Ns.STYLE, "name") ?: (x.attr(Ns.STYLE, "family")?.let { "__default_$it" }) ?: run { x.skip(); return null }
        val parent = x.attr(Ns.STYLE, "parent-style-name")
        val outline = x.attr(Ns.STYLE, "default-outline-level")?.toIntOrNull()
        val listStyle = x.attr(Ns.STYLE, "list-style-name")
        val display = x.attr(Ns.STYLE, "display-name") ?: name
        var bold: Boolean? = null; var italic: Boolean? = null; var underline: Boolean? = null; var strike: Boolean? = null
        var size: Float? = null; var sizePct: Float? = null; var color: Int? = null; var bg: Int? = null; var font: String? = null; var vert: Int? = null
        var align: Align? = null; var ml: Float? = null; var ind: Float? = null; var before: Float? = null; var after: Float? = null; var pbg: Int? = null
        var colW: Float? = null; var rowH: Float? = null; var cellFill: Int? = null; var cellAlign: HAlign? = null; var cellV: VAlign? = null; var wrap: Boolean? = null; var border: Int? = null
        var fill: Int? = null; var noFill = false; var stroke: Int? = null; var noStroke = false; var strokeW: Float? = null
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local) {
                "text-properties" -> {
                    c.attr(Ns.FO, "font-weight")?.let { bold = it == "bold" || (it.toIntOrNull() ?: 400) >= 600 }
                    c.attr(Ns.FO, "font-style")?.let { italic = it == "italic" || it == "oblique" }
                    c.attr(Ns.STYLE, "text-underline-style")?.let { underline = it != "none" }
                    c.attr(Ns.STYLE, "text-line-through-style")?.let { strike = it != "none" }
                    c.attr(Ns.FO, "font-size")?.let { v -> if (v.endsWith("%")) sizePct = v.removeSuffix("%").toFloatOrNull() else size = Units.lengthToPt(v) }
                    c.attr(Ns.FO, "color")?.let { color = Units.hexColor(it) }
                    c.attr(Ns.FO, "background-color")?.let { if (it != "transparent") bg = Units.hexColor(it) }
                    (c.attr(Ns.STYLE, "font-name") ?: c.attr(Ns.FO, "font-family"))?.let { font = it.trim('\'') }
                    c.attr(Ns.STYLE, "text-position")?.let { vert = when { it.startsWith("super") || it.startsWith("33%") -> 1; it.startsWith("sub") || it.startsWith("-33%") -> -1; else -> 0 } }
                    c.skip()
                }
                "paragraph-properties" -> {
                    align = when (c.attr(Ns.FO, "text-align")) { "center" -> Align.CENTER; "end", "right" -> Align.END; "justify" -> Align.JUSTIFY; "start", "left" -> Align.START; else -> null }
                    ml = Units.lengthToPt(c.attr(Ns.FO, "margin-left")); ind = Units.lengthToPt(c.attr(Ns.FO, "text-indent"))
                    before = Units.lengthToPt(c.attr(Ns.FO, "margin-top")); after = Units.lengthToPt(c.attr(Ns.FO, "margin-bottom"))
                    c.attr(Ns.FO, "background-color")?.let { if (it != "transparent") pbg = Units.hexColor(it) }
                    c.skip()
                }
                "table-column-properties" -> { colW = Units.lengthToPt(c.attr(Ns.STYLE, "column-width")); c.skip() }
                "table-row-properties" -> { rowH = Units.lengthToPt(c.attr(Ns.STYLE, "row-height") ?: c.attr(Ns.STYLE, "min-row-height")); c.skip() }
                "table-cell-properties" -> {
                    c.attr(Ns.FO, "background-color")?.let { if (it != "transparent") cellFill = Units.hexColor(it) }
                    cellV = when (c.attr(Ns.STYLE, "vertical-align")) { "top" -> VAlign.TOP; "middle" -> VAlign.CENTER; "bottom" -> VAlign.BOTTOM; else -> null }
                    c.attr(Ns.FO, "wrap-option")?.let { wrap = it == "wrap" }
                    (c.attr(Ns.FO, "border") ?: c.attr(Ns.FO, "border-bottom"))?.let { b -> if (b != "none") border = Regex("#[0-9a-fA-F]{6}").find(b)?.value?.let { Units.hexColor(it) } ?: 0xFF000000.toInt() }
                    c.skip()
                }
                "graphic-properties", "drawing-page-properties" -> {
                    when (c.attr(Ns.DRAW, "fill")) { "none" -> noFill = true; "solid" -> fill = Units.hexColor(c.attr(Ns.DRAW, "fill-color")) }
                    if (c.attr(Ns.DRAW, "fill") == null) c.attr(Ns.DRAW, "fill-color")?.let { fill = Units.hexColor(it) }
                    when (c.attr(Ns.DRAW, "stroke")) { "none" -> noStroke = true; else -> c.attr(Ns.SVG, "stroke-color")?.let { stroke = Units.hexColor(it) } }
                    strokeW = Units.lengthToPt(c.attr(Ns.SVG, "stroke-width"))
                    c.skip()
                }
                else -> c.skip()
            }
        }
        // Cell alignment comes from the paragraph alignment in spreadsheets.
        cellAlign = when (align) { Align.CENTER -> HAlign.CENTER; Align.END -> HAlign.RIGHT; Align.START -> HAlign.LEFT; Align.JUSTIFY -> HAlign.JUSTIFY; else -> null }
        return name to St(parent, bold, italic, underline, strike, size, sizePct, color, bg, font, vert, align, ml, ind, before, after, pbg, outline,
            colW, rowH, cellFill, cellAlign, cellV, wrap, border, fill, noFill, stroke, noStroke, strokeW, listStyle, display, display)
    }

    private fun listStyle(x: XmlReader): Map<Int, ListLevel> {
        val out = HashMap<Int, ListLevel>()
        x.children { c ->
            if (c.event != Event.START) return@children
            val lvl = (c.attr(Ns.TEXT, "level")?.toIntOrNull() ?: 1) - 1
            when (c.local) {
                "list-level-style-bullet" -> out[lvl] = ListLevel(c.attr(Ns.TEXT, "bullet-char")?.map { Units.symbolChar(it, null) }?.joinToString("") ?: "•", null, "", "", 1)
                "list-level-style-number" -> out[lvl] = ListLevel(null, c.attr(Ns.STYLE, "num-format") ?: "1", c.attr(Ns.STYLE, "num-suffix") ?: "", c.attr(Ns.STYLE, "num-prefix") ?: "", c.attr(Ns.TEXT, "start-value")?.toIntOrNull() ?: 1)
                "list-level-style-image" -> out[lvl] = ListLevel("•", null, "", "", 1)
            }
            c.skip()
        }
        return out
    }

    private fun loadStyles(parts: Parts): Styles {
        val s = Styles()
        parts.xml("styles.xml")?.let { readStyles(it, s) }
        parts.xml("content.xml")?.let { readStyles(it, s) }
        return s
    }

    // ------------------------------------------------------------------ text documents
    fun text(parts: Parts): FlowDocument {
        val styles = loadStyles(parts)
        val x = parts.xml("content.xml") ?: throw UnreadableException(tr("This OpenDocument file is damaged: its content is missing."))
        val t = TextBuilder(styles, parts)
        while (x.next() != Event.EOF) {
            if (x.isStart(Ns.OFFICE, "text")) { t.body(x, t.blocks); break }
        }
        if (t.notes.isNotEmpty()) {
            t.blocks.add(PageBreak)
            t.blocks.add(Paragraph(listOf(Span(tr("Notes"), RunStyle(bold = true))), heading = 2))
            for ((label, b) in t.notes) {
                val first = b.firstOrNull() as? Paragraph
                if (first != null) { t.blocks.add(Paragraph(listOf(Span("$label. ")) + first.spans)); t.blocks.addAll(b.drop(1)) } else t.blocks.addAll(b)
            }
        }
        val title = metaTitle(parts)
        return FlowDocument(title ?: (t.blocks.firstOrNull { it is Paragraph && it.heading == 1 } as? Paragraph)?.text, t.blocks, Media { parts.bytes(it, 64L shl 20) },
            t.noteList(), HtmlReader.outlineOf(t.blocks), 12f)
    }

    private fun metaTitle(parts: Parts): String? {
        val x = parts.xml("meta.xml") ?: return null
        while (x.next() != Event.EOF) if (x.isStart(Ns.DC, "title")) return x.readText().trim().ifEmpty { null }
        return null
    }

    private class TextBuilder(val styles: Styles, val parts: Parts) {
        /** In a presentation, the number of the slide being read (page-number fields show it). */
        var pageNumber = 0

        val blocks = ArrayList<Block>()
        val notes = ArrayList<Pair<String, List<Block>>>()
        var charts = 0; var objects = 0
        private val listCounters = HashMap<String, IntArray>()

        fun noteList(): List<String> = buildList {
            if (charts > 0) add(trn(charts, "{0} chart isn't shown.", "{0} charts aren't shown."))
            if (objects > 0) add(trn(objects, "{0} embedded object isn't shown.", "{0} embedded objects aren't shown."))
        }

        fun body(x: XmlReader, out: MutableList<Block>, list: Pair<String?, Int>? = null) {
            x.children { c ->
                if (c.event != Event.START) return@children
                block(c, out, list)
            }
        }

        private var depth = 0

        fun block(c: XmlReader, out: MutableList<Block>, list: Pair<String?, Int>?) {
            if (depth > 120) { c.skip(); return }
            depth++
            try { blockBody(c, out, list) } finally { depth-- }
        }

        private fun blockBody(c: XmlReader, out: MutableList<Block>, list: Pair<String?, Int>?) {
            when {
                c.ns == Ns.TEXT && (c.local == "p" || c.local == "h") -> paragraph(c, out, list)
                c.ns == Ns.TEXT && c.local == "list" -> {
                    val style = c.attr(Ns.TEXT, "style-name") ?: list?.first
                    val level = if (list == null) 0 else list.second + 1
                    if (c.attr(Ns.TEXT, "continue-numbering") != "true" && list == null) style?.let { listCounters.remove(it) }
                    c.children { item ->
                        if (item.event != Event.START) return@children
                        if (item.local == "list-item" || item.local == "list-header") {
                            var first = item.local == "list-item"
                            item.children { k ->
                                if (k.event != Event.START) return@children
                                if (k.ns == Ns.TEXT && (k.local == "p" || k.local == "h")) { paragraph(k, out, (style to level), label = if (first) nextLabel(style, level) else ""); first = false }
                                else block(k, out, style to level)
                            }
                        } else item.skip()
                    }
                }
                c.ns == Ns.TABLE && c.local == "table" -> out.add(table(c))
                c.ns == Ns.TEXT && (c.local == "section" || c.local == "index-body" || c.local == "table-of-content" || c.local == "alphabetical-index" || c.local == "illustration-index" || c.local == "bibliography") -> body(c, out, list)
                c.ns == Ns.TEXT && c.local == "index-title" -> body(c, out, list)
                c.ns == Ns.DRAW && (c.local == "frame" || c.local == "a" || c.local == "custom-shape" || c.local == "g") -> {
                    val trailing = ArrayList<Block>(); frame(c, trailing); out.addAll(trailing)
                }
                else -> c.skip()
            }
        }

        private fun nextLabel(style: String?, level: Int): String {
            val lv = style?.let { styles.lists[it]?.get(level) } ?: return if (level % 2 == 0) "•" else "◦"
            if (lv.bullet != null) return lv.bullet
            val ctr = listCounters.getOrPut(style) { IntArray(10) { -1 } }
            ctr[level] = if (ctr[level] < 0) lv.start else ctr[level] + 1
            for (k in level + 1 until 10) ctr[k] = -1
            return lv.prefix + Units.formatNumber(ctr[level], lv.format ?: "1") + lv.suffix
        }

        fun paragraph(x: XmlReader, out: MutableList<Block>, list: Pair<String?, Int>?, label: String? = null) {
            val styleName = x.attr(Ns.TEXT, "style-name")
            val heading = if (x.local == "h") (x.attr(Ns.TEXT, "outline-level")?.toIntOrNull() ?: styles.headingLevel(styleName).coerceAtLeast(1)) else styles.headingLevel(styleName)
            val base = styles.run(styleName, RunStyle(sizePt = 12f, bold = heading > 0 && styles.first(styleName) { it.bold } == null && false))
            val spans = ArrayList<Span>()
            val trailing = ArrayList<Block>()
            inline(x, base, null, spans, trailing)
            val align = styles.first(styleName) { it.align } ?: Align.START
            val indent = (styles.first(styleName) { it.marginLeft } ?: 0f) + (list?.let { 18f * (it.second + 1) } ?: 0f)
            val text = spans.joinToString("") { it.text }
            if (text.isNotBlank() || label != null || trailing.isEmpty()) {
                out.add(Paragraph(merge(spans), heading.coerceIn(0, 6), align, indent, styles.first(styleName) { it.indent } ?: 0f,
                    styles.first(styleName) { it.before } ?: 0f, styles.first(styleName) { it.after } ?: 0f,
                    label?.takeIf { it.isNotEmpty() }, list?.second ?: 0, styles.first(styleName) { it.paraBackground } ?: 0))
            }
            out.addAll(trailing)
        }

        private fun merge(spans: List<Span>): List<Span> {
            val out = ArrayList<Span>()
            for (s in spans) { val l = out.lastOrNull(); if (l != null && l.style == s.style && l.link == s.link) out[out.size - 1] = Span(l.text + s.text, s.style, s.link) else out.add(s) }
            return out
        }

        fun inline(x: XmlReader, style: RunStyle, link: String?, spans: MutableList<Span>, trailing: MutableList<Block>) {
            x.children { c ->
                when (c.event) {
                    Event.TEXT -> spans.add(Span(c.text.replace(Regex("[\\n\\t ]+"), " "), style, link))
                    Event.START -> when {
                        c.ns == Ns.TEXT && c.local == "span" -> inline(c, styles.run(c.attr(Ns.TEXT, "style-name"), style), link, spans, trailing)
                        c.ns == Ns.TEXT && c.local == "a" -> inline(c, style.copy(underline = true, color = if (style.color == 0) 0xFF1A5FB4.toInt() else style.color), c.attr(Ns.XLINK, "href") ?: link, spans, trailing)
                        c.ns == Ns.TEXT && c.local == "s" -> { spans.add(Span(" ".repeat((c.attr(Ns.TEXT, "c")?.toIntOrNull() ?: 1).coerceIn(1, 200)), style, link)); c.skip() }
                        c.ns == Ns.TEXT && c.local == "tab" -> { spans.add(Span("\t", style, link)); c.skip() }
                        c.ns == Ns.TEXT && c.local == "line-break" -> { spans.add(Span("\n", style, link)); c.skip() }
                        c.ns == Ns.TEXT && c.local == "note" -> {
                            var citation = ""; val body = ArrayList<Block>()
                            c.children { n -> if (n.event == Event.START) { when (n.local) { "note-citation" -> citation = n.readText(); "note-body" -> body(n, body); else -> n.skip() } } }
                            val label = citation.ifEmpty { (notes.size + 1).toString() }
                            notes.add(label to body)
                            spans.add(Span(label, style.copy(vertical = 1), link))
                        }
                        c.ns == Ns.DRAW && (c.local == "frame" || c.local == "a" || c.local == "custom-shape" || c.local == "g") -> frame(c, trailing)
                        c.ns == Ns.OFFICE && c.local == "annotation" -> c.skip()
                        c.ns == Ns.TEXT && (c.local == "tracked-changes" || c.local == "deletion" || c.local == "bookmark" || c.local == "bookmark-start" || c.local == "bookmark-end" || c.local == "soft-page-break" || c.local == "change" || c.local == "change-start" || c.local == "change-end" || c.local == "reference-mark") -> c.skip()
                        c.ns == Ns.TEXT && c.local == "page-number" && pageNumber > 0 -> { spans.add(Span(pageNumber.toString(), style, link)); c.skip() }
                        // Stand-ins like "<date/time>" or "<number>" that some programs save in place of a field's value.
                        (c.ns == Ns.TEXT || c.ns == Ns.PRESENTATION) && c.local in setOf("page-number", "date", "time", "date-time", "header", "footer") -> {
                            val t = c.readText()
                            if (!(t.startsWith("<") && t.endsWith(">"))) spans.add(Span(t, style, link))
                        }
                        else -> inline(c, style, link, spans, trailing) // fields, sequences, ruby…: their text
                    }
                    else -> Unit
                }
            }
        }

        fun frame(x: XmlReader, out: MutableList<Block>) {
            val w = Units.lengthToPt(x.attr(Ns.SVG, "width")) ?: 0f
            val h = Units.lengthToPt(x.attr(Ns.SVG, "height")) ?: 0f
            var alt: String? = null
            x.children { c ->
                if (c.event != Event.START) return@children
                when {
                    c.ns == Ns.DRAW && c.local == "image" -> {
                        val href = c.attr(Ns.XLINK, "href")
                        val part = href?.takeIf { !it.startsWith("http") && !it.startsWith("//") }?.let { Parts.normalise(Parts.decodePercent(it.removePrefix("./"))) }
                        if (part != null && parts.has(part)) out.add(Picture(part, w, h, alt)) else c.children { k -> if (k.event == Event.START && k.local == "p") paragraph(k, out, null) else if (k.event == Event.START) k.skip() }
                        if (part != null && parts.has(part)) c.skip()
                    }
                    c.ns == Ns.DRAW && c.local == "text-box" -> body(c, out)
                    c.ns == Ns.DRAW && c.local == "object" || c.local == "object-ole" -> { if (c.attr(Ns.XLINK, "href")?.contains("Object") == true) charts++ else objects++; c.skip() }
                    c.ns == Ns.SVG && (c.local == "desc" || c.local == "title") -> alt = c.readText().ifBlank { null }
                    c.ns == Ns.TEXT && (c.local == "p" || c.local == "h" || c.local == "list") -> block(c, out, null)
                    c.ns == Ns.DRAW && (c.local == "frame" || c.local == "custom-shape" || c.local == "g" || c.local == "a") -> frame(c, out)
                    else -> c.skip()
                }
            }
        }

        fun table(x: XmlReader): Table {
            val rows = ArrayList<TableRow>()
            fun row(r: XmlReader, header: Boolean) {
                val cells = ArrayList<TableCell>()
                r.children { c ->
                    if (c.event != Event.START) return@children
                    if (c.local == "table-cell") {
                        val inner = ArrayList<Block>()
                        val fill = styles.first(c.attr(Ns.TABLE, "style-name")) { it.cellFill } ?: 0
                        body(c, inner)
                        cells.add(TableCell(inner, c.attr(Ns.TABLE, "number-columns-spanned")?.toIntOrNull() ?: 1, c.attr(Ns.TABLE, "number-rows-spanned")?.toIntOrNull() ?: 1, if (fill == 0xFFFFFFFF.toInt()) 0 else fill))
                    } else c.skip()
                }
                rows.add(TableRow(cells, header))
            }
            x.children { c ->
                if (c.event != Event.START) return@children
                when (c.local) {
                    "table-row" -> row(c, false)
                    "table-header-rows" -> c.children { r -> if (r.event == Event.START && r.local == "table-row") row(r, true) else if (r.event == Event.START) r.skip() }
                    "table-rows", "table-row-group" -> c.children { r -> if (r.event == Event.START && r.local == "table-row") row(r, false) else if (r.event == Event.START) r.skip() }
                    else -> c.skip()
                }
            }
            return Table(rows, true)
        }
    }

    // ------------------------------------------------------------------ spreadsheets
    fun spreadsheet(parts: Parts): Workbook {
        val styles = loadStyles(parts)
        val x = parts.xml("content.xml") ?: throw UnreadableException(tr("This OpenDocument file is damaged: its content is missing."))
        val cellStyles = ArrayList<CellStyle>().apply { add(CellStyle.DEFAULT) }
        val styleIndex = HashMap<String, Int>()
        fun cellStyle(name: String?): Int {
            if (name == null) return 0
            return styleIndex.getOrPut(name) {
                val r = styles.run(name, RunStyle())
                val cs = CellStyle(r.bold, r.italic, r.underline, r.strike, r.sizePt.takeIf { it > 0 && it != 10f } ?: 0f, r.color.takeIf { it != 0xFF000000.toInt() } ?: 0,
                    styles.first(name) { it.cellFill }?.takeIf { it != 0xFFFFFFFF.toInt() } ?: 0, styles.first(name) { it.cellAlign } ?: HAlign.GENERAL,
                    styles.first(name) { it.cellVAlign } ?: VAlign.BOTTOM, styles.first(name) { it.wrap } ?: false,
                    styles.first(name) { it.border } ?: 0, styles.first(name) { it.border } ?: 0, styles.first(name) { it.border } ?: 0, styles.first(name) { it.border } ?: 0)
                if (cs == CellStyle.DEFAULT) 0 else { cellStyles.add(cs); cellStyles.size - 1 }
            }
        }
        fun visible(st: Int) = cellStyles.getOrNull(st)?.let { it.fill != 0 || it.borderTop != 0 || it.borderBottom != 0 || it.borderLeft != 0 || it.borderRight != 0 } ?: false
        val sheets = ArrayList<Sheet>()
        var cells = 0
        val max = 3_000_000
        while (x.next() != Event.EOF) {
            if (!x.isStart(Ns.TABLE, "table")) continue
            val name = x.attr(Ns.TABLE, "name") ?: tr("Sheet {0}", sheets.size + 1)
            val grid = CellGrid(); val widths = HashMap<Int, Float>(); val heights = HashMap<Int, Float>()
            val hiddenCols = HashSet<Int>(); val hiddenRows = HashSet<Int>(); val merges = ArrayList<Merge>()
            var col = 0; var row = 0; var drawings = 0
            val colDefaults = HashMap<Int, String>()
            fun rowEl(r: XmlReader) {
                val repeat = (r.attr(Ns.TABLE, "number-rows-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                val rh = styles.first(r.attr(Ns.TABLE, "style-name")) { it.rowHeight }
                val hidden = r.attr(Ns.TABLE, "visibility").let { it == "collapse" || it == "filter" }
                val start = row
                var c0 = 0
                val rowCells = ArrayList<Triple<Int, Cell, Int>>()
                r.children { c ->
                    if (c.event != Event.START) return@children
                    if (c.local != "table-cell" && c.local != "covered-table-cell") { c.skip(); return@children }
                    val rep = (c.attr(Ns.TABLE, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    if (c.local == "covered-table-cell") { c0 += rep; c.skip(); return@children }
                    val type = c.attr(Ns.OFFICE, "value-type")
                    val formula = c.attr(Ns.TABLE, "formula")?.let { f -> "=" + f.substringAfter(':', f).removePrefix("=").replace(Regex("""\[\.?([A-Z$]+[0-9$]+)(?::\.?([A-Z$]+[0-9$]+))?]""")) { m -> m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "") } }
                    val st = cellStyle(c.attr(Ns.TABLE, "style-name") ?: colDefaults[c0])
                    val cs = c.attr(Ns.TABLE, "number-columns-spanned")?.toIntOrNull() ?: 1
                    val rs = c.attr(Ns.TABLE, "number-rows-spanned")?.toIntOrNull() ?: 1
                    val sb = StringBuilder()
                    c.children { p ->
                        if (p.event != Event.START) return@children
                        if (p.ns == Ns.TEXT && p.local == "p") {
                            if (sb.isNotEmpty()) sb.append('\n')
                            p.children { t ->
                                when (t.event) {
                                    Event.TEXT -> sb.append(t.text)
                                    Event.START -> when (t.local) {
                                        "s" -> { sb.append(" ".repeat((t.attr(Ns.TEXT, "c")?.toIntOrNull() ?: 1).coerceIn(1, 100))); t.skip() }
                                        "tab" -> { sb.append('\t'); t.skip() }
                                        "line-break" -> { sb.append('\n'); t.skip() }
                                        "annotation" -> t.skip()
                                        else -> Unit
                                    }
                                    else -> Unit
                                }
                            }
                        } else if (p.ns == Ns.DRAW) { drawings++; p.skip() } else if (p.ns == Ns.OFFICE && p.local == "annotation") p.skip() else p.skip()
                    }
                    val text = sb.toString()
                    val kind = when (type) { "float", "percentage", "currency", "date", "time" -> CellKind.NUMBER; "boolean" -> CellKind.BOOLEAN; else -> CellKind.TEXT }
                    // Empty cells matter only when they show something (a fill or a border); LibreOffice
                    // writes styled empty cells down to row 1,048,576.
                    if (text.isNotEmpty() || formula != null || visible(st)) {
                        val cell = Cell(text, kind, st, formula)
                        for (k in 0 until rep.coerceAtMost(if (text.isEmpty()) 64 else 1024)) rowCells.add(Triple(c0 + k, cell, 0))
                    }
                    if (cs > 1 || rs > 1) merges.add(Merge(start, c0, start + rs - 1, c0 + cs - 1))
                    c0 += rep
                }
                val reps = if (rowCells.isEmpty()) repeat else repeat.coerceAtMost(if (rowCells.all { it.second.text.isEmpty() }) 64 else 256)
                for (k in 0 until reps) {
                    val rr = start + k
                    if (rowCells.isNotEmpty() && cells < max) { for ((cc, cell) in rowCells) { grid.put(rr, cc, cell); cells++ } }
                    if (rh != null) heights[rr] = rh
                    if (hidden) hiddenRows.add(rr)
                    if (rowCells.isEmpty() && k > 4096) break
                }
                row = start + repeat
            }
            x.children { c ->
                if (c.event != Event.START) return@children
                when (c.local) {
                    "table-column" -> {
                        val rep = (c.attr(Ns.TABLE, "number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 16384)
                        val w = styles.first(c.attr(Ns.TABLE, "style-name")) { it.colWidth }
                        val hidden = c.attr(Ns.TABLE, "visibility") == "collapse"
                        val def = c.attr(Ns.TABLE, "default-cell-style-name")
                        for (k in 0 until rep.coerceAtMost(1024)) { if (w != null) widths[col + k] = w; if (hidden) hiddenCols.add(col + k); if (def != null) colDefaults[col + k] = def }
                        col += rep; c.skip()
                    }
                    "table-columns", "table-header-columns", "table-column-group" -> Unit
                    "table-row" -> rowEl(c)
                    "table-rows", "table-header-rows", "table-row-group" -> Unit
                    "shapes" -> { drawings++; c.skip() }
                    else -> c.skip()
                }
            }
            // Widths of columns with no cells after the last used one don't matter; drop a huge tail.
            sheets.add(Sheet(name, grid, widths.filterKeys { it <= grid.maxCol + 1 }, heights.filterKeys { it <= grid.maxRow + 1 }, 48f, 12.8f,
                hiddenCols.filter { it <= grid.maxCol }.toSet(), hiddenRows.filter { it <= grid.maxRow }.toSet(), merges, drawings = drawings))
        }
        if (sheets.isEmpty()) throw UnreadableException(tr("This workbook has no sheets."))
        val notes = if (sheets.any { it.drawings > 0 }) listOf(tr("Charts and pictures on the sheets aren't drawn.")) else emptyList()
        return Workbook(metaTitle(parts), sheets, cellStyles, notes)
    }

    // ------------------------------------------------------------------ presentations
    fun presentation(parts: Parts): Deck {
        val styles = loadStyles(parts)
        val x = parts.xml("content.xml") ?: throw UnreadableException(tr("This OpenDocument file is damaged: its content is missing."))
        val tb = TextBuilder(styles, parts)
        val slides = ArrayList<Slide>()
        var w = 0f; var h = 0f
        while (x.next() != Event.EOF) {
            if (!x.isStart(Ns.DRAW, "page")) continue
            tb.pageNumber = slides.size + 1
            val master = x.attr(Ns.DRAW, "master-page-name")
            if (w == 0f) master?.let { styles.masters[it] }?.let { styles.pageLayouts[it] }?.let { (pw, ph) -> w = pw; h = ph }
            val pageStyle = x.attr(Ns.DRAW, "style-name")
            val bg = styles.first(pageStyle) { it.fill } ?: 0xFFFFFFFF.toInt()
            val shapes = ArrayList<Shape>()
            val notes = ArrayList<Paragraph>()
            x.children { c ->
                if (c.event != Event.START) return@children
                if (c.ns == Ns.PRESENTATION && c.local == "notes") {
                    c.children { f ->
                        if (f.event == Event.START && f.local == "frame") f.children { t ->
                            if (t.event == Event.START && t.local == "text-box") { val b = ArrayList<Block>(); tb.body(t, b); notes.addAll(b.filterIsInstance<Paragraph>().filter { !it.isEmpty() }) }
                            else if (t.event == Event.START) t.skip()
                        } else if (f.event == Event.START) f.skip()
                    }
                } else drawShape(c, styles, tb, parts, shapes)
            }
            slides.add(Slide(shapes, bg, null, notes))
        }
        if (w == 0f) { w = 792f; h = 612f; styles.pageLayouts.values.firstOrNull { it.first > 0 }?.let { (pw, ph) -> w = pw; h = ph } }
        return Deck(metaTitle(parts), slides, w, h, Media { parts.bytes(it, 64L shl 20) }, tb.noteList())
    }

    private fun drawShape(x: XmlReader, styles: Styles, tb: TextBuilder, parts: Parts, out: MutableList<Shape>) {
        if (x.ns != Ns.DRAW) { x.skip(); return }
        val st = x.attr(Ns.DRAW, "style-name") ?: x.attr(Ns.PRESENTATION, "style-name")
        val cls = x.attr(Ns.PRESENTATION, "class")
        val sx = Units.lengthToPt(x.attr(Ns.SVG, "x")) ?: 0f; val sy = Units.lengthToPt(x.attr(Ns.SVG, "y")) ?: 0f
        val sw = Units.lengthToPt(x.attr(Ns.SVG, "width")) ?: 0f; val sh = Units.lengthToPt(x.attr(Ns.SVG, "height")) ?: 0f
        val fill = if (styles.first(st) { if (it.noFill) 0 else it.fill } == 0) 0 else styles.first(st) { it.fill } ?: 0
        val stroke = if (styles.first(st) { if (it.noStroke) 0 else it.stroke } == 0) 0 else styles.first(st) { it.stroke } ?: 0
        val strokeW = styles.first(st) { it.strokeWidth } ?: 0.75f
        val defaultSize = when (cls) { "title" -> 40f; "subtitle" -> 28f; "outline" -> 24f; else -> 18f }
        fun body(container: XmlReader): TextBody? {
            val b = ArrayList<Block>()
            container.children { k -> if (k.event == Event.START) tb.block(k, b, null) }
            val paras = b.filterIsInstance<Paragraph>().map { p -> Paragraph(p.spans.map { s -> Span(s.text, s.style.copy(sizePt = if (s.style.sizePt == 12f) defaultSize else s.style.sizePt), s.link) }, p.heading, p.align, p.indentPt, p.firstLinePt, p.spaceBeforePt, p.spaceAfterPt, p.listLabel, p.listLevel) }
            return if (paras.any { !it.isEmpty() }) TextBody(paras, anchor = if (cls == "title") Anchor.MIDDLE else Anchor.TOP, defaultSizePt = defaultSize) else null
        }
        when (x.local) {
            "frame" -> {
                var shape: Shape? = null
                x.children { c ->
                    if (c.event != Event.START) return@children
                    when (c.local) {
                        "text-box" -> { val t = body(c); if (t != null) shape = Shape(sx, sy, sw, sh, fill = fill, lineColor = stroke, lineWidthPt = if (stroke != 0) strokeW else 0f, text = t) }
                        "image" -> {
                            val href = c.attr(Ns.XLINK, "href")?.let { Parts.normalise(Parts.decodePercent(it.removePrefix("./"))) }
                            if (shape == null && href != null && parts.has(href)) shape = Shape(sx, sy, sw, sh, image = href)
                            c.skip()
                        }
                        "object", "object-ole" -> { tb.charts++; if (shape == null) shape = Shape(sx, sy, sw, sh, lineColor = 0xFFBBBBBB.toInt(), lineWidthPt = 0.75f, placeholder = tr("Chart")); c.skip() }
                        "table" -> { shape = Shape(sx, sy, sw, sh, table = tb.table(c)) }
                        else -> c.skip()
                    }
                }
                shape?.let { out.add(it) }
            }
            "custom-shape", "rect", "ellipse", "circle", "polygon", "path", "regular-polygon" -> {
                var geom = when (x.local) { "ellipse", "circle" -> Geometry.ELLIPSE; "rect" -> Geometry.RECT; else -> Geometry.OTHER }
                val texts = ArrayList<Block>()
                x.children { c ->
                    if (c.event != Event.START) return@children
                    if (c.local == "enhanced-geometry") {
                        geom = when (c.attr(Ns.DRAW, "type")) { "rectangle" -> Geometry.RECT; "round-rectangle" -> Geometry.ROUND_RECT; "ellipse", "circle" -> Geometry.ELLIPSE; "isosceles-triangle", "right-triangle" -> Geometry.TRIANGLE; "right-arrow" -> Geometry.RIGHT_ARROW; "diamond" -> Geometry.DIAMOND; null -> geom; else -> Geometry.OTHER }
                        c.skip()
                    } else tb.block(c, texts, null)
                }
                val paras = texts.filterIsInstance<Paragraph>()
                out.add(Shape(sx, sy, sw, sh, geometry = geom, fill = fill, lineColor = stroke, lineWidthPt = if (stroke != 0) strokeW else 0f,
                    text = paras.takeIf { p -> p.any { !it.isEmpty() } }?.let { TextBody(it, anchor = Anchor.MIDDLE, defaultSizePt = 18f) }))
            }
            "line", "connector" -> {
                val x1 = Units.lengthToPt(x.attr(Ns.SVG, "x1")) ?: 0f; val y1 = Units.lengthToPt(x.attr(Ns.SVG, "y1")) ?: 0f
                val x2 = Units.lengthToPt(x.attr(Ns.SVG, "x2")) ?: 0f; val y2 = Units.lengthToPt(x.attr(Ns.SVG, "y2")) ?: 0f
                out.add(Shape(minOf(x1, x2), minOf(y1, y2), abs(x2 - x1), abs(y2 - y1), flipH = x2 < x1, flipV = y2 < y1, geometry = Geometry.LINE, lineColor = if (stroke == 0) 0xFF000000.toInt() else stroke, lineWidthPt = strokeW))
                x.skip()
            }
            "g" -> x.children { c -> if (c.event == Event.START) drawShape(c, styles, tb, parts, out) }
            else -> x.skip()
        }
    }

    private fun abs(f: Float) = kotlin.math.abs(f)
}
