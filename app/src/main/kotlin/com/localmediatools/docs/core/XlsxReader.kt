package com.localmediatools.docs.core

import com.localmediatools.docs.core.XmlReader.Event
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import com.localmediatools.i18n.trn
import java.util.Locale

/**
 * Excel workbooks (.xlsx, .xlsm, .xltx): every sheet with its cells as Excel shows them (number
 * formats applied: dates, currency, percentages…), cell styles (bold, italic, colours, fills,
 * borders, alignment, wrapping), column widths and row heights, hidden rows and columns, merged
 * cells, frozen panes and formulas (the value Excel saved, and the formula itself). Charts and
 * pictures on sheets are counted but not drawn.
 */
object XlsxReader {
    private const val MAX_CELLS = 3_000_000

    fun read(parts: Parts, locale: Locale): Workbook {
        val main = parts.mainPart()?.takeIf { parts.has(it) } ?: "xl/workbook.xml"
        val wb = parts.xml(main) ?: throw UnreadableException(tr("This isn't an Excel workbook: its main part is missing."))
        val rels = parts.rels(main)
        class SheetRef(val name: String, val part: String?, val hidden: Boolean, val chart: Boolean)
        val refs = ArrayList<SheetRef>()
        var date1904 = false
        var active = 0
        while (wb.next() != Event.EOF) {
            if (wb.event != Event.START) continue
            when (wb.local) {
                "workbookPr" -> date1904 = wb.attr("date1904")?.let { Units.onOff(it) } ?: false
                "workbookView" -> active = wb.attr("activeTab")?.toIntOrNull() ?: 0
                "sheet" -> {
                    val rel = wb.attr(Ns.R, "id")?.let { rels[it] }
                    refs.add(SheetRef(wb.attr("name") ?: tr("Sheet {0}", refs.size + 1), rel?.target, wb.attr("state").let { it == "hidden" || it == "veryHidden" }, rel?.type?.endsWith("/chartsheet") == true))
                }
            }
        }
        val strings = sharedStrings(parts, rels.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.target ?: "xl/sharedStrings.xml")
        val theme = Theme.read(parts, rels.values.firstOrNull { it.type.endsWith("/theme") }?.target)
        val fmt = NumberFormatter(locale, date1904)
        val styles = Styles(parts, rels.values.firstOrNull { it.type.endsWith("/styles") }?.target ?: "xl/styles.xml", theme, fmt)
        val sheets = ArrayList<Sheet>()
        var cells = 0
        var truncated = false
        var charts = 0
        for (r in refs) {
            if (r.chart || r.part == null) { sheets.add(Sheet(r.name, CellGrid(), hidden = r.hidden, drawings = 1)); charts++; continue }
            val s = SheetParser(parts, r.part, r.name, r.hidden, strings, styles, fmt, MAX_CELLS - cells).parse()
            cells += s.cells.count
            if (cells >= MAX_CELLS) truncated = true
            sheets.add(s)
        }
        if (sheets.isEmpty()) throw UnreadableException(tr("This workbook has no sheets."))
        val notes = ArrayList<String>()
        val drawn = sheets.sumOf { it.drawings } - charts
        if (charts > 0) notes.add(trn(charts, "{0} chart sheet isn't drawn.", "{0} chart sheets aren't drawn."))
        if (drawn > 0) notes.add(tr("Charts and pictures on the sheets aren't drawn."))
        if (truncated) notes.add(tr("This workbook is very large: only its first {0} cells are shown.", MAX_CELLS))
        return Workbook(coreTitle(parts), sheets, styles.list, notes, active.coerceIn(0, sheets.size - 1))
    }

    private fun sharedStrings(parts: Parts, part: String): List<String> {
        val x = parts.xml(part) ?: return emptyList()
        val out = ArrayList<String>()
        while (x.next() != Event.EOF) {
            if (x.event == Event.START && x.local == "si") {
                val sb = StringBuilder()
                x.children { c ->
                    if (c.event != Event.START) return@children
                    when (c.local) {
                        "t" -> sb.append(c.readText())
                        "rPh", "phoneticPr" -> c.skip()
                        "r" -> Unit
                        "rPr" -> c.skip()
                        else -> Unit
                    }
                }
                out.add(unescape(sb.toString()))
            }
        }
        return out
    }

    private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

    /** Characters XML can't hold are written as _xHHHH_ ("_x000D_" for a carriage return). */
    fun unescape(s: String): String {
        if (!s.contains("_x")) return s
        return ESCAPE.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }.replace("\r\n", "\n").replace('\r', '\n')
    }

    /** "AB12" → (row 11, column 27); null when it isn't a cell reference. */
    fun cellRef(ref: String): Pair<Int, Int>? {
        var col = 0; var i = 0
        while (i < ref.length && ref[i].isLetter()) { col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1); i++ }
        if (i == 0 || i == ref.length) return null
        val row = ref.substring(i).toIntOrNull() ?: return null
        return (row - 1) to (col - 1)
    }

    fun columnName(c: Int): String {
        var n = c + 1; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return sb.toString()
    }

    /** A shared formula moved from its first cell by (dr, dc): relative references shift, $-fixed ones stay. */
    fun shiftFormula(f: String, dr: Int, dc: Int): String {
        if (dr == 0 && dc == 0) return f
        val sb = StringBuilder()
        var i = 0; var inStr = false
        val ref = Regex("""\G(\$?)([A-Za-z]{1,3})(\$?)(\d{1,7})(?![A-Za-z0-9_(])""")
        while (i < f.length) {
            val c = f[i]
            if (c == '"') { inStr = !inStr; sb.append(c); i++; continue }
            if (!inStr && (i == 0 || !(f[i - 1].isLetterOrDigit() || f[i - 1] == '_' || f[i - 1] == '.'))) {
                val m = ref.find(f, i)
                if (m != null && m.range.first == i) {
                    val (cAbs, colS, rAbs, rowS) = m.destructured
                    var col = 0; for (ch in colS) col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
                    val row = rowS.toInt()
                    val nc = if (cAbs.isEmpty()) col + dc else col
                    val nr = if (rAbs.isEmpty()) row + dr else row
                    if (nc >= 1 && nr >= 1) { sb.append(cAbs).append(columnName(nc - 1)).append(rAbs).append(nr); i = m.range.last + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** styles.xml: number formats and the look of cells, by xf index. */
    private class Styles(parts: Parts, part: String, private val theme: Theme, private val fmt: NumberFormatter) {
        val list = ArrayList<CellStyle>().apply { add(CellStyle.DEFAULT) }
        private val index = HashMap<CellStyle, Int>().apply { put(CellStyle.DEFAULT, 0) }
        /** Per xf: (style index, number format code). */
        val xfs = ArrayList<Pair<Int, String?>>()
        private val customFormats = HashMap<Int, String>()
        private var indexed: IntArray? = null

        private class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val size: Float, val color: Int)
        private class Border(val l: Int, val r: Int, val t: Int, val b: Int)

        init {
            val fonts = ArrayList<Font>(); val fills = ArrayList<Int>(); val borders = ArrayList<Border>()
            parts.xml(part)?.let { x ->
                var section = ""
                while (x.next() != Event.EOF) {
                    if (x.event == Event.END && x.local in setOf("cellXfs", "cellStyleXfs")) section = ""
                    if (x.event != Event.START) continue
                    when (x.local) {
                        "numFmt" -> x.attr("numFmtId")?.toIntOrNull()?.let { id -> x.attr("formatCode")?.let { customFormats[id] = it } }
                        "indexedColors" -> {
                            val list = ArrayList<Int>()
                            x.children { c -> if (c.event == Event.START) { if (c.local == "rgbColor") list.add(Units.hexColor(c.attr("rgb")) ?: 0); c.skip() } }
                            indexed = list.toIntArray()
                        }
                        "font" -> fonts.add(font(x))
                        "fill" -> fills.add(fill(x))
                        "border" -> borders.add(border(x))
                        "cellStyleXfs" -> section = "styleXfs"
                        "cellXfs" -> section = "xfs"
                        "xf" -> if (section == "xfs") xfs.add(xf(x, fonts, fills, borders)) else x.skip()
                        "dxfs", "tableStyles", "extLst", "cellStyles" -> x.skip()
                    }
                }
            }
        }

        private fun color(x: XmlReader): Int? {
            Units.hexColor(x.attr("rgb"))?.let { return it.applyTint(x) }
            x.attr("theme")?.toIntOrNull()?.let { t -> theme.byIndex(t)?.let { return it.applyTint(x) } }
            x.attr("indexed")?.toIntOrNull()?.let { i -> return indexedColor(i)?.applyTint(x) }
            return null
        }

        private fun Int.applyTint(x: XmlReader): Int = x.attr("tint")?.toDoubleOrNull()?.let { Colors.tint(this, it) } ?: this

        fun indexedColor(i: Int): Int? {
            indexed?.getOrNull(i)?.let { return it }
            return when (i) {
                in 0..7 -> intArrayOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF)[i]
                in 8..63 -> NumberFormatter.PALETTE[i - 8]
                64 -> 0x000000
                else -> null
            }?.let { (0xFF000000L or it.toLong()).toInt() }
        }

        private fun font(x: XmlReader): Font {
            var b = false; var i = false; var u = false; var s = false; var sz = 11f; var color = 0
            x.children { c ->
                if (c.event != Event.START) return@children
                val v = c.attr("val")
                when (c.local) {
                    "b" -> b = Units.onOff(v); "i" -> i = Units.onOff(v); "u" -> u = v != "none"; "strike" -> s = Units.onOff(v)
                    "sz" -> sz = v?.toFloatOrNull() ?: sz
                    "color" -> color = color(c)?.takeIf { it != 0xFF000000.toInt() } ?: 0
                }
                c.skip()
            }
            return Font(b, i, u, s, sz, color)
        }

        private fun fill(x: XmlReader): Int {
            var color = 0
            x.children { c ->
                if (c.event != Event.START) return@children
                if (c.local == "patternFill") {
                    val type = c.attr("patternType") ?: "none"
                    c.children { f ->
                        if (f.event == Event.START) {
                            if (f.local == "fgColor" && type != "none" && type != "gray125") color = color(f) ?: 0
                            f.skip()
                        }
                    }
                } else if (c.local == "gradientFill") {
                    c.children { g -> if (g.event == Event.START && g.local == "color" && color == 0) color = color(g) ?: 0 }
                } else c.skip()
            }
            return if (color == 0xFFFFFFFF.toInt()) 0 else color
        }

        private fun border(x: XmlReader): Border {
            val c = IntArray(4)
            x.children { e ->
                if (e.event != Event.START) return@children
                val side = when (e.local) { "left", "start" -> 0; "right", "end" -> 1; "top" -> 2; "bottom" -> 3; else -> -1 }
                if (side < 0) { e.skip(); return@children }
                val style = e.attr("style")
                var col = 0xFF000000.toInt()
                e.children { k -> if (k.event == Event.START) { if (k.local == "color") col = color(k) ?: col; k.skip() } }
                if (style != null && style != "none") c[side] = col
            }
            return Border(c[0], c[1], c[2], c[3])
        }

        private fun xf(x: XmlReader, fonts: List<Font>, fills: List<Int>, borders: List<Border>): Pair<Int, String?> {
            val numId = x.attr("numFmtId")?.toIntOrNull() ?: 0
            val f = fonts.getOrNull(x.attr("fontId")?.toIntOrNull() ?: 0)
            val fill = fills.getOrNull(x.attr("fillId")?.toIntOrNull() ?: 0) ?: 0
            val b = borders.getOrNull(x.attr("borderId")?.toIntOrNull() ?: 0)
            var h = HAlign.GENERAL; var v = VAlign.BOTTOM; var wrap = false; var indent = 0
            x.children { c ->
                if (c.event != Event.START) return@children
                if (c.local == "alignment") {
                    h = when (c.attr("horizontal")) { "left" -> HAlign.LEFT; "center", "centerContinuous", "distributed" -> HAlign.CENTER; "right" -> HAlign.RIGHT; "justify" -> HAlign.JUSTIFY; "fill" -> HAlign.FILL; else -> HAlign.GENERAL }
                    v = when (c.attr("vertical")) { "top" -> VAlign.TOP; "center", "justify", "distributed" -> VAlign.CENTER; else -> VAlign.BOTTOM }
                    wrap = Units.onOff(c.attr("wrapText") ?: "0")
                    indent = c.attr("indent")?.toIntOrNull() ?: 0
                }
                c.skip()
            }
            val base = fonts.firstOrNull()?.size ?: 11f
            val style = CellStyle(
                bold = f?.bold == true, italic = f?.italic == true, underline = f?.underline == true, strike = f?.strike == true,
                sizePt = f?.size?.takeIf { it != base } ?: 0f, color = f?.color ?: 0, fill = fill, hAlign = h, vAlign = v, wrap = wrap,
                borderTop = b?.t ?: 0, borderRight = b?.r ?: 0, borderBottom = b?.b ?: 0, borderLeft = b?.l ?: 0, indent = indent,
            )
            val idx = index.getOrPut(style) { list.add(style); list.size - 1 }
            val code = customFormats[numId] ?: fmt.builtin(numId)
            return idx to code
        }
    }

    private class SheetParser(
        val parts: Parts, val part: String, val name: String, val hidden: Boolean, val strings: List<String>,
        val styles: Styles, val fmt: NumberFormatter, val budget: Int,
    ) {
        val grid = CellGrid()
        val widths = HashMap<Int, Float>(); val heights = HashMap<Int, Float>()
        val hiddenCols = HashSet<Int>(); val hiddenRows = HashSet<Int>()
        val merges = ArrayList<Merge>()
        var defaultW = 48f; var defaultH = 15f
        var frozenRows = 0; var frozenCols = 0; var grid0 = true; var drawings = 0
        val shared = HashMap<String, Triple<Int, Int, String>>()

        fun charsToPt(w: Double) = ((w * 7 + 5) * 0.75).toFloat()

        fun parse(): Sheet {
            val x = parts.xml(part) ?: return Sheet(name, grid, hidden = hidden)
            var row = -1
            while (x.next() != Event.EOF) {
                if (x.event != Event.START) continue
                when (x.local) {
                    "sheetFormatPr" -> {
                        x.attr("defaultColWidth")?.toDoubleOrNull()?.let { defaultW = (it * 7 * 0.75).toFloat() }
                            ?: x.attr("baseColWidth")?.toDoubleOrNull()?.let { defaultW = charsToPt(it) }
                        x.attr("defaultRowHeight")?.toFloatOrNull()?.let { defaultH = it }
                    }
                    "sheetView" -> if (x.attr("showGridLines") == "0" || x.attr("showGridLines") == "false") grid0 = false
                    "pane" -> if (x.attr("state")?.startsWith("frozen") == true) {
                        frozenCols = x.attr("xSplit")?.toDoubleOrNull()?.toInt() ?: 0
                        frozenRows = x.attr("ySplit")?.toDoubleOrNull()?.toInt() ?: 0
                    }
                    "col" -> {
                        val min = (x.attr("min")?.toIntOrNull() ?: 1) - 1
                        val max = ((x.attr("max")?.toIntOrNull() ?: 1) - 1).coerceAtMost(min + 16384)
                        val w = x.attr("width")?.toDoubleOrNull()
                        val hid = Units.onOff(x.attr("hidden") ?: "0")
                        for (c in min..max) { if (w != null) widths[c] = charsToPt(w).coerceAtLeast(0f); if (hid) hiddenCols.add(c) }
                    }
                    "row" -> {
                        row = (x.attr("r")?.toIntOrNull() ?: (row + 2)) - 1
                        x.attr("ht")?.toFloatOrNull()?.let { if (x.attr("customHeight")?.let { c -> Units.onOff(c) } != false || it != defaultH) heights[row] = it }
                        if (Units.onOff(x.attr("hidden") ?: "0")) hiddenRows.add(row)
                        var col = -1
                        x.children { c ->
                            if (c.event == Event.START && c.local == "c") {
                                val ref = c.attr("r")?.let { cellRef(it) }
                                col = ref?.second ?: (col + 1)
                                if (grid.count < budget) cell(c, ref?.first ?: row, col) else c.skip()
                            } else if (c.event == Event.START) c.skip()
                        }
                    }
                    "mergeCell" -> x.attr("ref")?.let { ref ->
                        val a = ref.split(':')
                        val p = cellRef(a[0]); val q = cellRef(a.getOrElse(1) { a[0] })
                        if (p != null && q != null) merges.add(Merge(p.first, p.second, q.first, q.second))
                    }
                    "drawing", "legacyDrawing" -> drawings++
                    "extLst", "conditionalFormatting", "dataValidations", "hyperlinks", "pageSetup", "headerFooter" -> x.skip()
                }
            }
            return Sheet(name, grid, widths, heights, defaultW, defaultH, hiddenCols, hiddenRows, merges, frozenRows, frozenCols, hidden, grid0, drawings)
        }

        private fun cell(x: XmlReader, r: Int, c: Int) {
            val type = x.attr("t") ?: "n"
            val (style, code) = styles.xfs.getOrNull(x.attr("s")?.toIntOrNull() ?: 0) ?: (0 to null)
            var v: String? = null; var formula: String? = null; var inline: String? = null
            x.children { k ->
                if (k.event != Event.START) return@children
                when (k.local) {
                    "v" -> v = k.readText()
                    "f" -> {
                        val ft = k.attr("t"); val si = k.attr("si")
                        val text = k.readText()
                        formula = when {
                            ft == "shared" && si != null && text.isNotEmpty() -> { shared[si] = Triple(r, c, text); text }
                            ft == "shared" && si != null -> shared[si]?.let { (r0, c0, f) -> shiftFormula(f, r - r0, c - c0) }
                            else -> text.ifEmpty { null }
                        }
                    }
                    "is" -> { val sb = StringBuilder(); k.children { t -> if (t.event == Event.START) { if (t.local == "t") sb.append(t.readText()) else if (t.local == "rPh") t.skip() } }; inline = unescape(sb.toString()) }
                    else -> k.skip()
                }
            }
            val f = formula?.let { "=$it" }
            val cell: Cell? = when (type) {
                "s" -> v?.toIntOrNull()?.let { strings.getOrNull(it) }?.let { s -> fmt.formatText(s, code).let { Cell(it.text, CellKind.TEXT, style, f, it.color) } }
                "inlineStr" -> inline?.let { s -> Cell(s, CellKind.TEXT, style, f) }
                "str" -> v?.let { s -> fmt.formatText(s, code).let { Cell(it.text, CellKind.TEXT, style, f, it.color) } }
                "b" -> v?.let { Cell(if (it == "1" || it.equals("true", true)) trc("spreadsheet value", "TRUE") else trc("spreadsheet value", "FALSE"), CellKind.BOOLEAN, style, f) }
                "e" -> v?.let { Cell(it, CellKind.ERROR, style, f) }
                "d" -> v?.let { iso -> isoToSerial(iso)?.let { s -> fmt.format(s, code?.takeIf { fmt.isDate(it) } ?: "yyyy-mm-dd hh:mm:ss").let { Cell(it.text, CellKind.NUMBER, style, f, it.color) } } ?: Cell(iso, CellKind.TEXT, style, f) }
                else -> v?.toDoubleOrNull()?.let { d -> fmt.format(d, code).let { Cell(it.text, CellKind.NUMBER, style, f, it.color) } }
            }
            when {
                cell != null -> grid.put(r, c, cell)
                style != 0 || f != null -> grid.put(r, c, Cell("", CellKind.TEXT, style, f))
            }
        }

        /** "2021-03-04T10:30:00" → Excel serial (1900 system), or null. */
        private fun isoToSerial(iso: String): Double? = try {
            val dt = if (iso.contains('T')) java.time.LocalDateTime.parse(iso.removeSuffix("Z").substringBefore('+')) else java.time.LocalDate.parse(iso).atStartOfDay()
            val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(1899, 12, 30), dt.toLocalDate())
            days + dt.toLocalTime().toSecondOfDay() / 86400.0
        } catch (_: Exception) { null }
    }
}
