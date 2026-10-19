package com.localmediatools.docs.core

import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import java.nio.charset.Charset
import java.util.Locale

/**
 * Excel 97–2003 workbooks (.xls, BIFF8; values also from Excel 5/95 files): every sheet with its
 * cells as Excel shows them (number formats applied), fonts, fills, borders, alignment, column widths,
 * row heights, hidden rows and columns, merged cells and frozen panes. Formulas show the value Excel
 * saved. Encrypted workbooks say so.
 */
class XlsReader private constructor(private val data: ByteArray, private val locale: Locale) {
    companion object {
        fun read(ole: Ole2, locale: Locale): Workbook {
            val data = ole.stream("Workbook") ?: ole.stream("Book") ?: throw UnreadableException(tr("This Excel file is damaged: its sheets are missing."))
            return XlsReader(data, locale).read()
        }
        /** Excel 2–4 files: a bare stream of records holding one sheet. */
        fun readRaw(bytes: ByteArray, locale: Locale): Workbook = XlsReader(bytes, locale).readRawStream()

        private val ERRORS = mapOf(0x00 to "#NULL!", 0x07 to "#DIV/0!", 0x0F to "#VALUE!", 0x17 to "#REF!", 0x1D to "#NAME?", 0x24 to "#NUM!", 0x2A to "#N/A", 0x2B to "#GETTING_DATA")
    }

    private class Rec(val type: Int, val offset: Int, val len: Int)

    private var biff8 = true
    /** 2, 3, 4 (bare record streams), 5 or 8. */
    private var version = 8
    private var charset: Charset = Charset.forName("windows-1252")
    private var date1904 = false
    private val formats = HashMap<Int, String>()
    private class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val size: Float, val color: Int)
    private val fonts = ArrayList<Font>()
    private val xfFont = ArrayList<Int>(); private val xfFormat = ArrayList<Int>(); private val xfStyle = ArrayList<Int>()
    private val styles = ArrayList<CellStyle>().apply { add(CellStyle.DEFAULT) }
    private val styleIndex = HashMap<CellStyle, Int>().apply { put(CellStyle.DEFAULT, 0) }
    private val palette = IntArray(56) { (0xFF000000L or NumberFormatter.PALETTE[it].toLong()).toInt() }
    private val sst = ArrayList<String>()
    private class SheetRef(val name: String, val pos: Int, val hidden: Boolean, val type: Int)
    private val sheetRefs = ArrayList<SheetRef>()
    private val pendingXf = ArrayList<ByteArray>()

    private fun record(at: Int): Rec? {
        if (at + 4 > data.size) return null
        val t = le16(data, at); val l = le16(data, at + 2)
        if (at + 4 + l > data.size) return null
        return Rec(t, at + 4, l)
    }

    private fun read(): Workbook {
        var at = 0
        var first = true
        // Workbook globals up to their EOF.
        while (true) {
            val r = record(at) ?: break
            at = r.offset + r.len
            when (r.type) {
                0x0809, 0x0409, 0x0209, 0x0009 -> if (first) { first = false; biff8 = le16(data, r.offset) == 0x0600; if (!biff8 && le16(data, r.offset) < 0x0500) throw UnreadableException(tr("This file was made by a very old Excel version, which this app can't read.")) }
                0x002F -> throw UnreadableException(tr("This file is protected with a password, which this app can't open."))
                0x0042 -> { val cp = le16(data, r.offset); if (cp != 1200) charset = try { Charset.forName(if (cp == 10000) "x-MacRoman" else "windows-$cp") } catch (_: Exception) { charset } }
                0x0022 -> date1904 = le16(data, r.offset) == 1
                0x041E, 0x001E -> format(r)
                0x0031 -> font(r)
                0x00E0 -> pendingXf.add(data.copyOfRange(r.offset, r.offset + r.len))
                0x0092 -> { val n = le16(data, r.offset); for (k in 0 until n.coerceAtMost(56)) { val o = r.offset + 2 + k * 4; palette[k] = (0xFF shl 24) or ((data[o].toInt() and 0xFF) shl 16) or ((data[o + 1].toInt() and 0xFF) shl 8) or (data[o + 2].toInt() and 0xFF) } }
                0x0085 -> boundSheet(r)
                0x00FC -> sst(r, at)
                0x000A -> break
            }
        }
        for (x in pendingXf) xf(x)
        val fmt = NumberFormatter(locale, date1904)
        val sheets = ArrayList<Sheet>()
        var charts = 0
        for (ref in sheetRefs) {
            if (ref.type == 2 || ref.type == 6) { charts++; sheets.add(Sheet(ref.name, CellGrid(), hidden = ref.hidden, drawings = 1)); continue }
            sheets.add(try { sheet(ref, fmt) } catch (e: Exception) { Sheet(ref.name, CellGrid(), hidden = ref.hidden) })
        }
        if (sheets.isEmpty()) throw UnreadableException(tr("This workbook has no sheets."))
        val notes = ArrayList<String>()
        if (charts > 0 || sheets.any { it.drawings > 0 }) notes.add(tr("Charts and pictures on the sheets aren't drawn."))
        return Workbook(null, sheets, styles, notes)
    }

    private fun readRawStream(): Workbook {
        version = when (le16(data, 0)) { 0x0009 -> 2; 0x0209 -> 3; 0x0409 -> 4; else -> if (le16(data, 4) == 0x0600) 8 else 5 }
        biff8 = version == 8
        var at = 0
        var formatCount = 0
        while (true) {
            val r = record(at) ?: break
            at = r.offset + r.len
            val o = r.offset
            when (r.type) {
                0x002F -> throw UnreadableException(tr("This file is protected with a password, which this app can't open."))
                0x0042 -> { val cp = le16(data, o); charset = try { Charset.forName("windows-$cp") } catch (_: Exception) { charset } }
                0x0022 -> date1904 = le16(data, o) == 1
                0x001E -> { val cch = data[o].toInt() and 0xFF; formats[formatCount++] = String(data, o + 1, cch.coerceAtMost(r.len - 1), charset) }
                0x041E -> format(r)
                0x0031, 0x0231 -> {
                    val g = le16(data, o + 2)
                    fonts.add(Font(g and 1 != 0, g and 2 != 0, g and 4 != 0, g and 8 != 0, le16(data, o) / 20f, if (r.type == 0x0231) color(le16(data, o + 4)) else 0))
                }
                0x0043 -> addXf(data[o].toInt() and 0xFF, data[o + 2].toInt() and 0x3F)
                0x0243, 0x0443 -> addXf(data[o].toInt() and 0xFF, data[o + 1].toInt() and 0xFF)
                0x000A -> break
            }
        }
        val fmt = NumberFormatter(locale, date1904)
        val sheet = sheet(SheetRef(tr("Sheet {0}", 1), 0, false, 0), fmt)
        return Workbook(null, listOf(sheet), styles)
    }

    private fun addXf(ifnt: Int, ifmt: Int) {
        val font = fonts.getOrNull(if (ifnt >= 4) ifnt - 1 else ifnt)
        val style = if (font != null) CellStyle(bold = font.bold, italic = font.italic, underline = font.underline, strike = font.strike, color = font.color) else CellStyle.DEFAULT
        xfFont.add(ifnt); xfFormat.add(ifmt)
        xfStyle.add(styleIndex.getOrPut(style) { styles.add(style); styles.size - 1 })
    }

    // ------------------------------------------------------------------ strings
    /** An XLUnicodeString (2-byte count) or, in BIFF5, an 8-bit string with a 1- or 2-byte count. */
    private fun string(o: Int, countBytes: Int): Pair<String, Int> {
        val cch = if (countBytes == 2) le16(data, o) else data[o].toInt() and 0xFF
        var p = o + countBytes
        if (!biff8) return String(data, p, cch.coerceAtMost(data.size - p), charset) to (p + cch)
        val flags = data[p].toInt(); p++
        var runs = 0; var ext = 0
        if (flags and 0x08 != 0) { runs = le16(data, p); p += 2 }
        if (flags and 0x04 != 0) { ext = le32(data, p); p += 4 }
        val wide = flags and 1 != 0
        val n = if (wide) cch * 2 else cch
        val s = if (wide) String(data, p, n.coerceAtMost(data.size - p), Charsets.UTF_16LE) else String(data, p, n.coerceAtMost(data.size - p), Charsets.ISO_8859_1)
        return s to (p + n + runs * 4 + ext)
    }

    /** The shared string table: strings may continue across CONTINUE records, each restating the width. */
    private fun sst(first: Rec, afterFirst: Int) {
        val segs = ArrayList<Pair<Int, Int>>()
        segs.add(first.offset to first.offset + first.len)
        var at = afterFirst
        while (true) {
            val r = record(at) ?: break
            if (r.type != 0x003C) break
            segs.add(r.offset to r.offset + r.len); at = r.offset + r.len
        }
        var seg = 0; var p = segs[0].first + 8
        val unique = le32(data, segs[0].first + 4)
        fun need(k: Int) { while (seg < segs.size && p + k > segs[seg].second && p >= segs[seg].second) { seg++; if (seg < segs.size) p = segs[seg].first } }
        fun byte(): Int { need(1); return if (p < data.size) data[p++].toInt() and 0xFF else 0 }
        fun u16(): Int = byte() or (byte() shl 8)
        fun u32(): Int = u16() or (u16() shl 16)
        repeat(unique.coerceIn(0, 10_000_000)) {
            if (seg >= segs.size) return
            val cch = u16()
            var flags = byte()
            var runs = 0; var ext = 0
            if (flags and 0x08 != 0) runs = u16()
            if (flags and 0x04 != 0) ext = u32()
            val sb = StringBuilder(cch)
            var left = cch
            while (left > 0 && seg < segs.size) {
                if (p >= segs[seg].second) { seg++; if (seg >= segs.size) break; p = segs[seg].first; flags = data[p++].toInt() and 0xFF }
                val wide = flags and 1 != 0
                val room = (segs[seg].second - p) / (if (wide) 2 else 1)
                val k = minOf(room, left)
                if (k <= 0) { p = segs[seg].second; continue }
                if (wide) { sb.append(String(data, p, k * 2, Charsets.UTF_16LE)); p += k * 2 } else { for (i in 0 until k) sb.append((data[p + i].toInt() and 0xFF).toChar()); p += k }
                left -= k
            }
            sst.add(sb.toString())
            // Skip formatting runs and phonetic data, which may also cross records.
            var skip = runs * 4 + ext
            while (skip > 0 && seg < segs.size) {
                val room = segs[seg].second - p
                if (room <= 0) { seg++; if (seg < segs.size) p = segs[seg].first; continue }
                val k = minOf(room, skip); p += k; skip -= k
            }
        }
    }

    private fun format(r: Rec) {
        val id = le16(data, r.offset)
        val (s, _) = if (biff8) string(r.offset + 2, 2) else string(r.offset + 2, 1)
        formats[id] = s
    }

    private fun font(r: Rec) {
        val o = r.offset
        val size = le16(data, o) / 20f
        val grbit = le16(data, o + 2)
        val icv = le16(data, o + 4)
        val bls = le16(data, o + 6)
        val uls = data.getOrNull(o + 10)?.toInt() ?: 0
        fonts.add(Font(bls >= 700, grbit and 0x02 != 0, uls != 0, grbit and 0x08 != 0, size, color(icv)))
    }

    private fun color(icv: Int): Int = when (icv) {
        in 8..63 -> palette[icv - 8].takeIf { it != 0xFF000000.toInt() } ?: 0
        in 0..7 -> intArrayOf(0, 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt())[icv]
        else -> 0
    }

    private fun xf(b: ByteArray) {
        val ifnt = le16(b, 0)
        val ifmt = le16(b, 2)
        // Font 4 doesn't exist: indexes from 4 on are one higher than the list position.
        val font = fonts.getOrNull(if (ifnt >= 4) ifnt - 1 else ifnt)
        var style = CellStyle.DEFAULT
        if (biff8 && b.size >= 20) {
            val align = b[6].toInt() and 0xFF
            val h = when (align and 7) { 1 -> HAlign.LEFT; 2, 6 -> HAlign.CENTER; 3 -> HAlign.RIGHT; 4 -> HAlign.FILL; 5, 7 -> HAlign.JUSTIFY; else -> HAlign.GENERAL }
            val v = when ((align shr 4) and 7) { 0 -> VAlign.TOP; 1 -> VAlign.CENTER; else -> VAlign.BOTTOM }
            val dg = le16(b, 10)
            val icvLR = le16(b, 12)
            val tb = le32(b, 14)
            val fill = le16(b, 18)
            val fls = (tb ushr 26) and 0x3F
            fun border(styleBits: Int, icv: Int) = if (styleBits != 0) (color(icv).takeIf { it != 0 } ?: 0xFF000000.toInt()) else 0
            style = CellStyle(
                bold = font?.bold == true, italic = font?.italic == true, underline = font?.underline == true, strike = font?.strike == true,
                sizePt = font?.size?.takeIf { it != (fonts.firstOrNull()?.size ?: 10f) } ?: 0f, color = font?.color ?: 0,
                fill = if (fls != 0) color(fill and 0x7F).takeIf { it != 0xFFFFFFFF.toInt() } ?: 0 else 0,
                hAlign = h, vAlign = v, wrap = align and 0x08 != 0,
                borderLeft = border(dg and 0xF, icvLR and 0x7F), borderRight = border((dg shr 4) and 0xF, (icvLR shr 7) and 0x7F),
                borderTop = border((dg shr 8) and 0xF, tb and 0x7F), borderBottom = border((dg shr 12) and 0xF, (tb shr 7) and 0x7F),
                indent = (b[8].toInt() and 0xF),
            )
        } else if (font != null) style = CellStyle(bold = font.bold, italic = font.italic, color = font.color)
        xfFont.add(ifnt); xfFormat.add(ifmt)
        xfStyle.add(styleIndex.getOrPut(style) { styles.add(style); styles.size - 1 })
    }

    private fun boundSheet(r: Rec) {
        val pos = le32(data, r.offset)
        val state = data[r.offset + 4].toInt() and 0x03
        val type = data[r.offset + 5].toInt() and 0xFF
        val (name, _) = if (biff8) string(r.offset + 6, 1) else string(r.offset + 6, 1)
        sheetRefs.add(SheetRef(name, pos, state != 0, type))
    }

    // ------------------------------------------------------------------ sheets
    private fun sheet(ref: SheetRef, fmt: NumberFormatter): Sheet {
        val grid = CellGrid()
        val widths = HashMap<Int, Float>(); val heights = HashMap<Int, Float>()
        val hiddenCols = HashSet<Int>(); val hiddenRows = HashSet<Int>()
        val merges = ArrayList<Merge>()
        var frozenR = 0; var frozenC = 0; var gridLines = true; var drawings = 0
        var defaultW = 48f; var defaultH = 12.75f
        var at = ref.pos
        var depth = 0
        var pendingFormula: Triple<Int, Int, Int>? = null
        fun put(r: Int, c: Int, xf: Int, cell: (Int, String?) -> Cell?) {
            if (grid.count >= 3_000_000) return
            val style = xfStyle.getOrElse(xf) { 0 }
            val code = xfFormat.getOrNull(xf)?.let { formats[it] ?: fmt.builtin(it) }
            cell(style, code)?.let { grid.put(r, c, it) }
        }
        fun number(v: Double, style: Int, code: String?): Cell = fmt.format(v, code).let { Cell(it.text, CellKind.NUMBER, style, null, it.color) }
        while (true) {
            val r = record(at) ?: break
            at = r.offset + r.len
            val o = r.offset
            when (r.type) {
                0x0809 -> depth++
                0x000A -> { depth--; if (depth <= 0) break }
                0x00FD -> put(le16(data, o), le16(data, o + 2), le16(data, o + 4)) { st, code -> sst.getOrNull(le32(data, o + 6))?.let { s -> fmt.formatText(s, code).let { Cell(it.text, CellKind.TEXT, st, null, it.color) } } }
                0x0204, 0x00D6 -> put(le16(data, o), le16(data, o + 2), le16(data, o + 4)) { st, _ -> Cell(string(o + 6, 2).first, CellKind.TEXT, st) }
                0x0203 -> put(le16(data, o), le16(data, o + 2), le16(data, o + 4)) { st, code -> number(le64d(data, o + 6), st, code) }
                0x027E -> put(le16(data, o), le16(data, o + 2), le16(data, o + 4)) { st, code -> number(rk(le32(data, o + 6)), st, code) }
                0x00BD -> {
                    val row = le16(data, o); val c0 = le16(data, o + 2)
                    val n = (r.len - 6) / 6
                    for (k in 0 until n) { val p = o + 4 + k * 6; put(row, c0 + k, le16(data, p)) { st, code -> number(rk(le32(data, p + 2)), st, code) } }
                }
                0x0201 -> put(le16(data, o), le16(data, o + 2), le16(data, o + 4)) { st, _ -> if (st != 0) Cell("", CellKind.TEXT, st) else null }
                0x00BE -> {
                    val row = le16(data, o); val c0 = le16(data, o + 2)
                    val n = (r.len - 6) / 2
                    for (k in 0 until n.coerceAtMost(256)) put(row, c0 + k, le16(data, o + 4 + k * 2)) { st, _ -> if (st != 0) Cell("", CellKind.TEXT, st) else null }
                }
                0x0205 -> put(le16(data, o), le16(data, o + 2), le16(data, o + 4)) { st, _ ->
                    val v = data[o + 6].toInt() and 0xFF; val isErr = data[o + 7].toInt() != 0
                    if (isErr) Cell(ERRORS[v] ?: "#ERR", CellKind.ERROR, st) else Cell(if (v != 0) trc("spreadsheet value", "TRUE") else trc("spreadsheet value", "FALSE"), CellKind.BOOLEAN, st)
                }
                0x0003, 0x0002 -> if (version == 2) put(le16(data, o), le16(data, o + 2), data[o + 4].toInt() and 0x3F) { st, code -> number(if (r.type == 0x0003) le64d(data, o + 7) else le16(data, o + 7).toDouble(), st, code) }
                0x0004 -> if (version == 2) put(le16(data, o), le16(data, o + 2), data[o + 4].toInt() and 0x3F) { st, _ -> Cell(String(data, o + 8, (data[o + 7].toInt() and 0xFF).coerceAtMost(r.len - 8), charset), CellKind.TEXT, st) }
                0x0007 -> if (version == 2) pendingFormula?.let { (row, col, xf) -> put(row, col, xf) { st, _ -> Cell(String(data, o + 1, (data[o].toInt() and 0xFF).coerceAtMost(r.len - 1), charset), CellKind.TEXT, st) }; pendingFormula = null }
                0x0006, 0x0206, 0x0406 -> if (version == 2 && r.type == 0x0006) {
                    put(le16(data, o), le16(data, o + 2), data[o + 4].toInt() and 0x3F) { st, code -> number(le64d(data, o + 7), st, code) }
                } else {
                    val row = le16(data, o); val col = le16(data, o + 2); val xf = le16(data, o + 4)
                    if (le16(data, o + 12) == 0xFFFF) {
                        when (data[o + 6].toInt()) {
                            0 -> pendingFormula = Triple(row, col, xf)
                            1 -> put(row, col, xf) { st, _ -> Cell(if (data[o + 8].toInt() != 0) trc("spreadsheet value", "TRUE") else trc("spreadsheet value", "FALSE"), CellKind.BOOLEAN, st) }
                            2 -> put(row, col, xf) { st, _ -> Cell(ERRORS[data[o + 8].toInt() and 0xFF] ?: "#ERR", CellKind.ERROR, st) }
                            3 -> Unit
                        }
                    } else put(row, col, xf) { st, code -> number(le64d(data, o + 6), st, code) }
                }
                0x0207 -> pendingFormula?.let { (row, col, xf) -> put(row, col, xf) { st, code -> fmt.formatText(string(o, 2).first, code).let { Cell(it.text, CellKind.TEXT, st, null, it.color) } }; pendingFormula = null }
                0x0208 -> {
                    val row = le16(data, o)
                    val h = le16(data, o + 6)
                    val flags = le16(data, o + 12)
                    if (h and 0x8000 == 0) heights[row] = (h and 0x7FFF) / 20f
                    if (flags and 0x20 != 0) hiddenRows.add(row)
                }
                0x007D -> {
                    val c0 = le16(data, o); val c1 = le16(data, o + 2).coerceAtMost(c0 + 256)
                    val w = le16(data, o + 4) / 256.0
                    val hidden = le16(data, o + 8) and 1 != 0
                    for (c in c0..c1) { widths[c] = ((w * 7 + 5) * 0.75).toFloat(); if (hidden) hiddenCols.add(c) }
                }
                0x0055 -> defaultW = ((le16(data, o) * 7 + 5) * 0.75).toFloat()
                0x0225 -> defaultH = le16(data, o + 2) / 20f
                0x00E5 -> {
                    val n = le16(data, o)
                    for (k in 0 until n) { val p = o + 2 + k * 8; merges.add(Merge(le16(data, p), le16(data, p + 4), le16(data, p + 2), le16(data, p + 6))) }
                }
                0x023E -> { val g = le16(data, o); gridLines = g and 0x0002 != 0; if (g and 0x0008 == 0) { frozenR = -1 } }
                0x0041 -> if (frozenR != -1) { frozenC = le16(data, o); frozenR = le16(data, o + 2) }
                0x00EC, 0x005D -> drawings++
            }
        }
        if (frozenR < 0) { frozenR = 0; frozenC = 0 }
        return Sheet(ref.name, grid, widths, heights, defaultW, defaultH, hiddenCols, hiddenRows, merges, frozenR, frozenC, ref.hidden, gridLines, if (drawings > 0) 1 else 0)
    }

    private fun rk(v: Int): Double {
        val d = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 1 != 0) d / 100 else d
    }
}
