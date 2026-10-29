package com.localmediatools.ui.reader

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.localmediatools.docs.core.Cell
import com.localmediatools.docs.core.CellKind
import com.localmediatools.docs.core.HAlign
import com.localmediatools.docs.core.Merge
import com.localmediatools.docs.core.Sheet
import com.localmediatools.docs.core.VAlign
import com.localmediatools.docs.core.Workbook
import kotlin.math.max
import kotlin.math.min

/**
 * A sheet's cells drawn as Excel shows them — fills, grid lines, merged areas, borders, and text that
 * is aligned, wrapped, runs into empty neighbours, and shrinks or turns into ### when a number doesn't
 * fit — for the reader's grid and for printed pages. Column [cols] and row [rows] positions are in
 * points ([colX], [rowY]); a drawing call says how many pixels a point is.
 */
class SheetPainter(private val book: Workbook, val sheet: Sheet, val cols: Int, val rows: Int) {
    val colX = FloatArray(cols + 1)
    val rowY = FloatArray(rows + 1)
    private val merges: Map<Long, Merge>
    private val paints = HashMap<Long, TextPaint>()
    private val wrapped = object : LruCache<String, StaticLayout>(600) {}
    private val fill = Paint()
    private val gridPaint = Paint().apply { color = 0xFFE1E4E8.toInt(); strokeWidth = 1f }
    private val line = Paint()

    init {
        for (c in 0 until cols) colX[c + 1] = colX[c] + if (c in sheet.hiddenCols) 0f else (sheet.colWidths[c] ?: sheet.defaultColWidthPt)
        for (r in 0 until rows) rowY[r + 1] = rowY[r] + if (r in sheet.hiddenRows) 0f else (sheet.rowHeights[r] ?: sheet.defaultRowHeightPt)
        val m = HashMap<Long, Merge>()
        for (mg in sheet.merges) {
            if ((mg.r2 - mg.r1 + 1).toLong() * (mg.c2 - mg.c1 + 1) > 20_000) { m[key(mg.r1, mg.c1)] = mg; continue }
            for (r in mg.r1..mg.r2) for (c in mg.c1..mg.c2) m[key(r, c)] = mg
        }
        merges = m
    }

    fun key(r: Int, c: Int) = (r.toLong() shl 20) or c.toLong()
    fun mergeAt(r: Int, c: Int): Merge? = merges[key(r, c)]

    /** First index i with arr[i + 1] > v (the column or row at position v, in points). */
    fun at(arr: FloatArray, n: Int, v: Float): Int {
        var lo = 0; var hi = n - 1
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (arr[mid + 1] <= v) lo = mid + 1 else hi = mid }
        return lo.coerceIn(0, max(0, n - 1))
    }

    /** Forgets wrapped text laid out at other sizes. */
    fun clearCache() = wrapped.evictAll()

    private fun paintFor(styleIndex: Int, color: Int, ppp: Float): TextPaint {
        val k = (styleIndex.toLong() shl 32) or (color.toLong() and 0xFFFFFFFFL)
        return paints.getOrPut(k) {
            val st = book.style(styleIndex)
            TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, (if (st.bold) Typeface.BOLD else 0) or (if (st.italic) Typeface.ITALIC else 0))
                this.color = when { color != 0 -> color or 0xFF000000.toInt(); st.color != 0 -> st.color or 0xFF000000.toInt(); else -> Color.BLACK }
                isUnderlineText = st.underline; isStrikeThruText = st.strike
            }
        }.also { it.textSize = (book.style(styleIndex).sizePt.takeIf { s -> s > 0 } ?: 11f) * ppp }
    }

    /**
     * Draws rows [rs]..[re] and columns [cs]..[ce], column c's left edge at [ox] + colX[c]·[ppp] and
     * row r's top at [oy] + rowY[r]·[ppp]; text may run in from cells as far left as column [c0].
     * [lineWidth] is the width of cell borders in pixels; [matchColor] marks search matches.
     */
    fun draw(canvas: Canvas, rs: Int, re: Int, cs: Int, ce: Int, c0: Int, ox: Float, oy: Float, ppp: Float,
             gridLines: Boolean, lineWidth: Float, gridWidth: Float = 1f, matchColor: (Int, Int) -> Int = { _, _ -> 0 }) {
        if (cs > ce || rs > re) return
        line.strokeWidth = lineWidth
        gridPaint.strokeWidth = gridWidth
        fun x(c: Int) = ox + colX[c] * ppp
        fun y(r: Int) = oy + rowY[r] * ppp
        val cells = sheet.cells
        val visibleRows = cells.rowsBetween(rs, re)
        // Fills and search marks.
        for ((r, row) in visibleRows) for ((c, cell) in row.subMap(cs, ce + 1)) {
            val st = book.style(cell.style)
            val mark = matchColor(r, c)
            val color = if (mark != 0) mark else st.fill
            if (color != 0 && mergeAt(r, c) == null) { fill.color = color or 0xFF000000.toInt(); canvas.drawRect(x(c), y(r), x(c + 1), y(r + 1), fill) }
        }
        if (gridLines) {
            for (c in cs..ce + 1) { val xx = x(c); canvas.drawLine(xx, y(rs), xx, y(re + 1), gridPaint) }
            for (r in rs..re + 1) { val yy = y(r); canvas.drawLine(x(cs), yy, x(ce + 1), yy, gridPaint) }
        }
        // Merged areas cover the grid lines inside them.
        val drawnMerges = HashSet<Merge>()
        for (mg in sheet.merges) {
            if (mg.r2 < rs || mg.r1 > re || mg.c2 < cs || mg.c1 > ce) continue
            drawnMerges.add(mg)
            val cell = cells[mg.r1, mg.c1]
            val mark = matchColor(mg.r1, mg.c1)
            val color = if (mark != 0) mark else cell?.let { book.style(it.style).fill } ?: 0
            fill.color = if (color != 0) color or 0xFF000000.toInt() else Color.WHITE
            canvas.drawRect(x(mg.c1) + 0.5f, y(mg.r1) + 0.5f, x(mg.c2 + 1) - 0.5f, y(mg.r2 + 1) - 0.5f, fill)
        }
        // Borders.
        for ((r, row) in visibleRows) for ((c, cell) in row.subMap(cs, ce + 1)) {
            val mg = mergeAt(r, c)
            if (mg != null && (mg.r1 != r || mg.c1 != c)) continue
            val st = book.style(cell.style)
            if (st.borderTop or st.borderRight or st.borderBottom or st.borderLeft == 0) continue
            val l = x(c); val t = y(r); val rr = x((mg?.c2 ?: c) + 1); val b = y((mg?.r2 ?: r) + 1)
            if (st.borderTop != 0) { line.color = st.borderTop or 0xFF000000.toInt(); canvas.drawLine(l, t, rr, t, line) }
            if (st.borderBottom != 0) { line.color = st.borderBottom or 0xFF000000.toInt(); canvas.drawLine(l, b, rr, b, line) }
            if (st.borderLeft != 0) { line.color = st.borderLeft or 0xFF000000.toInt(); canvas.drawLine(l, t, l, b, line) }
            if (st.borderRight != 0) { line.color = st.borderRight or 0xFF000000.toInt(); canvas.drawLine(rr, t, rr, b, line) }
        }
        // Text: the rows drawn, plus text running in from cells just left of them.
        for ((r, row) in visibleRows) {
            if (r in sheet.hiddenRows) continue
            val from = lastFilled(row, cs)?.let { max(c0, it) } ?: cs
            for ((c, cell) in row.subMap(from, ce + 1)) {
                if (cell.text.isEmpty() || c in sheet.hiddenCols) continue
                if (mergeAt(r, c) != null) continue
                text(canvas, r, c, cell, x(c), y(r), x(c + 1), y(r + 1), row, ox, ppp)
            }
        }
        for (mg in drawnMerges) {
            val cell = cells[mg.r1, mg.c1] ?: continue
            if (cell.text.isEmpty()) continue
            text(canvas, mg.r1, mg.c1, cell, x(mg.c1), y(mg.r1), x(mg.c2 + 1), y(mg.r2 + 1), null, ox, ppp)
        }
    }

    /** Whether any cell in rows [rs]..[re] has a border (sheets without any print with light grid lines). */
    fun hasBorders(rs: Int, re: Int): Boolean {
        for ((_, row) in sheet.cells.rowsBetween(rs, re)) for (cell in row.values) {
            val st = book.style(cell.style)
            if (st.borderTop or st.borderRight or st.borderBottom or st.borderLeft != 0) return true
        }
        return false
    }

    private fun text(canvas: Canvas, r: Int, c: Int, cell: Cell, l: Float, t: Float, rr: Float, b: Float, row: java.util.SortedMap<Int, Cell>?, ox: Float, ppp: Float) {
        val st = book.style(cell.style)
        val p = paintFor(cell.style, cell.color, ppp)
        val pad = 3 * ppp
        val indent = st.indent * 9f * ppp
        val align = when (st.hAlign) {
            HAlign.GENERAL -> when (cell.kind) { CellKind.NUMBER -> HAlign.RIGHT; CellKind.BOOLEAN, CellKind.ERROR -> HAlign.CENTER; else -> HAlign.LEFT }
            HAlign.FILL, HAlign.JUSTIFY -> HAlign.LEFT
            else -> st.hAlign
        }
        val cw = rr - l
        if (st.wrap && cw > 2 * pad) {
            val k = "$r:$c:${cw.toInt()}:$ppp"
            val lay = wrapped.get(k) ?: StaticLayout.Builder.obtain(cell.text, 0, cell.text.length, p, (cw - 2 * pad - indent).toInt().coerceAtLeast(1))
                .setAlignment(when (align) { HAlign.CENTER -> Layout.Alignment.ALIGN_CENTER; HAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL })
                .setIncludePad(false).build().also { wrapped.put(k, it) }
            val ty = when (st.vAlign) { VAlign.TOP -> t + pad * 0.5f; VAlign.CENTER -> t + (b - t - lay.height) / 2; VAlign.BOTTOM -> b - lay.height - pad * 0.5f }
            canvas.save(); canvas.clipRect(l, t, rr, b); canvas.translate(l + pad + indent, max(t, ty)); lay.draw(canvas); canvas.restore()
            return
        }
        var s = cell.text.replace('\n', ' ')
        var tw = p.measureText(s)
        var left = l; var right = rr
        val roomy = cell.kind == CellKind.TEXT && row != null
        if (tw + 2 * pad + indent > cw && roomy) {
            // Text runs into empty cells beside it, as in Excel.
            if (align == HAlign.LEFT || align == HAlign.CENTER) {
                // Only a cell with content stops it (one with just a border or fill doesn't).
                val next = row!!.tailMap(c + 1).entries.firstOrNull { it.value.text.isNotEmpty() }?.key ?: cols
                var e = c + 1
                while (e < min(next, cols) && ox + colX[e] * ppp - l < tw + 2 * pad + indent && mergeAt(r, e) == null) e++
                right = ox + colX[e] * ppp
            }
            if (align == HAlign.RIGHT || align == HAlign.CENTER) {
                val prev = lastFilled(row!!, c) ?: -1
                var s0 = c
                while (s0 - 1 > prev && rr - (ox + colX[s0] * ppp) < tw + 2 * pad && mergeAt(r, s0 - 1) == null) s0--
                left = ox + colX[s0] * ppp
            }
        }
        val size = p.textSize
        if (cell.kind == CellKind.NUMBER && tw + 2 * pad > cw) {
            // Numbers never run over: smaller if that's enough, ### if not (tap to read it in the bar).
            val f = (cw - 2 * pad) / tw
            if (f >= 0.7f) { p.textSize = size * f; tw = p.measureText(s) } else { s = "#".repeat(max(1, ((cw - 2 * pad) / p.measureText("#")).toInt())); tw = p.measureText(s) }
        }
        val fm = p.fontMetrics
        val th = fm.descent - fm.ascent
        val baseline = when (st.vAlign) {
            VAlign.TOP -> t + pad * 0.3f - fm.ascent
            VAlign.CENTER -> t + (b - t - th) / 2 - fm.ascent
            VAlign.BOTTOM -> b - pad * 0.4f - fm.descent
        }
        val tx = when (align) {
            HAlign.RIGHT -> right - pad - tw - indent
            HAlign.CENTER -> (left + right - tw) / 2
            else -> left + pad + indent
        }
        canvas.save(); canvas.clipRect(left, t, right, b)
        canvas.drawText(s, tx, baseline, p)
        canvas.restore()
        p.textSize = size
    }

    /** The nearest column left of [c] whose cell has content (a few dozen columns back at most). */
    fun lastFilled(row: java.util.SortedMap<Int, Cell>, c: Int): Int? {
        val left = (row as? java.util.NavigableMap<Int, Cell>)?.headMap(c, false)?.descendingMap() ?: return row.headMap(c).entries.lastOrNull { it.value.text.isNotEmpty() }?.key
        var n = 0
        for ((k, v) in left) { if (v.text.isNotEmpty()) return k; if (++n > 64) break }
        return null
    }
}
