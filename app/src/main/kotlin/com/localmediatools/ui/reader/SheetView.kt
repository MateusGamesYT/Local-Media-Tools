package com.localmediatools.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.TextView
import com.localmediatools.docs.core.Cell
import com.localmediatools.docs.core.CellKind
import com.localmediatools.docs.core.HAlign
import com.localmediatools.docs.core.Merge
import com.localmediatools.docs.core.Sheet
import com.localmediatools.docs.core.VAlign
import com.localmediatools.docs.core.Workbook
import com.localmediatools.docs.core.XlsxReader
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Spreadsheets as a grid like the desktop apps: column letters and row numbers, cell colours,
 * borders, merged cells, frozen rows and columns, text running into empty neighbours; a bar above
 * shows the tapped cell's content or formula, tabs below switch sheets. Pinch to zoom.
 */
@SuppressLint("ViewConstructor")
class SheetView(ctx: Context, private val book: Workbook, private val host: ReaderHost) : LinearLayout(ctx), ReaderContent {
    override val view: View get() = this
    private val shown: List<Int> = book.sheets.indices.filter { !book.sheets[it].hidden }.ifEmpty { book.sheets.indices.toList() }
    private val refView = UI.text(ctx, "", TextStyle.SUBTITLE).apply { gravity = Gravity.CENTER; minWidth = ctx.dp(64); setPadding(ctx.dp(8), 0, ctx.dp(8), 0) }
    private val valueView = UI.text(ctx, "", TextStyle.BODY).apply {
        setTextIsSelectable(true); maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
    }
    private val tabs = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6)) }
    private val tabScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabs); setBackgroundColor(Palette.BG_2) }
    private val grid = Grid(ctx)
    private val empty = UI.text(ctx, "", TextStyle.BODY_2, 0xFF6B7280.toInt()).apply { gravity = Gravity.CENTER; setPadding(ctx.dp(24), 0, ctx.dp(24), 0) }
    var sheetIndex = -1; private set

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        val bar = UI.horizontal(ctx).apply { setBackgroundColor(Palette.SURFACE) }
        bar.addView(refView, lp(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        bar.addView(View(ctx).apply { setBackgroundColor(Palette.STROKE_2) }, lp(ctx.dp(1), LayoutParams.MATCH_PARENT).apply { topMargin = ctx.dp(6); bottomMargin = ctx.dp(6) })
        bar.addView(valueView, lp(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(bar, lp())
        val frame = FrameLayout(ctx)
        frame.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(frame, lp(LayoutParams.MATCH_PARENT, 0, 1f))
        if (shown.size > 1 || book.sheets.size > 1) addView(tabScroll, lp())
        for (i in shown) {
            val s = book.sheets[i]
            tabs.addView(UI.text(ctx, s.name.ifBlank { tr("Sheet {0}", i + 1) }, TextStyle.BODY_2).apply {
                setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
                maxLines = 1
                tag = i
                setOnClickListener { showSheet(i) }
            }, lp(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = ctx.dp(6) })
        }
        showSheet(book.activeSheet.takeIf { it in shown } ?: shown.firstOrNull() ?: 0)
    }

    fun showSheet(i: Int) {
        if (i !in book.sheets.indices) return
        sheetIndex = i
        val s = book.sheets[i]
        grid.load(s)
        for (k in 0 until tabs.childCount) {
            val t = tabs.getChildAt(k) as TextView
            val on = t.tag == i
            t.background = if (on) Shapes.pill(context, Palette.ACCENT_DARK, Palette.ACCENT) else Shapes.pill(context, Palette.SURFACE_2)
            t.setTextColor(if (on) Palette.TEXT else Palette.TEXT_2)
            if (on) tabScroll.post { tabScroll.smoothScrollTo((t.left - context.dp(24)).coerceAtLeast(0), 0) }
        }
        empty.visibility = if (s.cells.count == 0) View.VISIBLE else View.GONE
        empty.text = if (s.drawings > 0) tr("This sheet holds a chart or pictures, which this app doesn't draw.") else tr("This sheet is empty.")
        select(0, 0)
    }

    private fun select(r: Int, c: Int) {
        grid.selR = r; grid.selC = c
        val s = book.sheets[sheetIndex]
        val m = grid.mergeAt(r, c)
        val cell = if (m != null) s.cells[m.r1, m.c1] else s.cells[r, c]
        refView.text = if (m != null) XlsxReader.columnName(m.c1) + (m.r1 + 1) else XlsxReader.columnName(c) + (r + 1)
        valueView.text = when {
            cell == null -> ""
            cell.formula != null -> if (cell.formula.startsWith("{")) cell.formula else "=" + cell.formula
            else -> cell.text
        }
        valueView.contentDescription = "${refView.text}: ${valueView.text}"
        grid.invalidate()
    }

    // ------------------------------------------------------------------ the grid
    private inner class Grid(ctx: Context) : View(ctx) {
        val density = resources.displayMetrics.density
        var sheet: Sheet = book.sheets.firstOrNull() ?: Sheet("", com.localmediatools.docs.core.CellGrid())
        var zoom = 1f
        val ppp get() = density * 1.33f * zoom
        var colX = FloatArray(1); var rowY = FloatArray(1)
        var cols = 0; var rows = 0
        var sx = 0f; var sy = 0f
        var selR = 0; var selC = 0
        private var merges: Map<Long, Merge> = emptyMap()
        private val scroller = OverScroller(ctx)
        private val gridPaint = Paint().apply { color = 0xFFE1E4E8.toInt(); strokeWidth = 1f }
        private val fill = Paint()
        private val line = Paint().apply { strokeWidth = max(1f, density) }
        private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F6670.toInt(); textAlign = Paint.Align.CENTER }
        private val headFill = Paint().apply { color = 0xFFF3F4F6.toInt() }
        private val headSel = Paint().apply { color = 0xFFDCE3F7.toInt() }
        private val selPaint = Paint().apply { color = 0xFF2F6FED.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.2f * resources.displayMetrics.density }
        private val freeze = Paint().apply { color = 0xFFB0B6BF.toInt(); strokeWidth = 2f * resources.displayMetrics.density }
        private val paints = HashMap<Long, TextPaint>()
        private val wrapped = object : LruCache<String, StaticLayout>(600) {}

        fun load(s: Sheet) {
            sheet = s
            cols = min(16384, max(s.cells.maxCol + 1, s.merges.maxOfOrNull { it.c2 + 1 } ?: 0) + 8).coerceAtLeast(26)
            rows = min(1_048_576 + 64, max(s.cells.maxRow + 1, s.merges.maxOfOrNull { it.r2 + 1 } ?: 0) + 40).coerceAtLeast(60)
            colX = FloatArray(cols + 1)
            for (c in 0 until cols) colX[c + 1] = colX[c] + if (c in s.hiddenCols) 0f else (s.colWidths[c] ?: s.defaultColWidthPt)
            rowY = FloatArray(rows + 1)
            for (r in 0 until rows) rowY[r + 1] = rowY[r] + if (r in s.hiddenRows) 0f else (s.rowHeights[r] ?: s.defaultRowHeightPt)
            val m = HashMap<Long, Merge>()
            for (mg in s.merges) {
                if ((mg.r2 - mg.r1 + 1).toLong() * (mg.c2 - mg.c1 + 1) > 20_000) { m[key(mg.r1, mg.c1)] = mg; continue }
                for (r in mg.r1..mg.r2) for (c in mg.c1..mg.c2) m[key(r, c)] = mg
            }
            merges = m
            sx = 0f; sy = 0f
            scroller.forceFinished(true)
            wrapped.evictAll()
            invalidate()
        }

        private fun key(r: Int, c: Int) = (r.toLong() shl 20) or c.toLong()
        fun mergeAt(r: Int, c: Int): Merge? = merges[key(r, c)]

        val fr get() = sheet.frozenRows.coerceIn(0, rows - 1)
        val fc get() = sheet.frozenCols.coerceIn(0, cols - 1)
        val headW get() = headPaint.measureText((min(rows, sheet.cells.maxRow + 41)).toString()) + 14 * density
        val headH get() = 22 * density * zoom.coerceIn(0.7f, 1.4f)
        private val fxPx get() = colX[fc] * ppp
        private val fyPx get() = rowY[fr] * ppp
        val maxSx get() = max(0f, (colX[cols] * ppp - fxPx) - (width - headW - fxPx))
        val maxSy get() = max(0f, (rowY[rows] * ppp - fyPx) - (height - headH - fyPx))

        private fun clamp() { sx = sx.coerceIn(0f, maxSx); sy = sy.coerceIn(0f, maxSy) }

        /** First index i with arr[i + 1] > v (the column or row at content position v, in points). */
        private fun at(arr: FloatArray, n: Int, v: Float): Int {
            var lo = 0; var hi = n - 1
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (arr[mid + 1] <= v) lo = mid + 1 else hi = mid }
            return lo.coerceIn(0, max(0, n - 1))
        }

        // Screen position of content x (points) for a column, frozen or scrolled.
        private fun xOf(c: Int) = headW + colX[c] * ppp - (if (c >= fc) sx else 0f)
        private fun yOf(r: Int) = headH + rowY[r] * ppp - (if (r >= fr) sy else 0f)

        fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
            if (x < headW || y < headH) return null
            val cx = if (x - headW < fxPx) (x - headW) / ppp else (x - headW + sx) / ppp
            val cy = if (y - headH < fyPx) (y - headH) / ppp else (y - headH + sy) / ppp
            return at(rowY, rows, cy) to at(colX, cols, cx)
        }

        fun reveal(r: Int, c: Int) {
            if (r >= fr) {
                val top = rowY[r] * ppp - fyPx; val bottom = rowY[r + 1] * ppp - fyPx
                val view = height - headH - fyPx
                if (top < sy || bottom > sy + view) sy = top - view * 0.3f
            }
            if (c >= fc) {
                val left = colX[c] * ppp - fxPx; val right = colX[c + 1] * ppp - fxPx
                val view = width - headW - fxPx
                if (left < sx || right > sx + view) sx = left - view * 0.2f
            }
            clamp(); invalidate()
        }

        private fun paintFor(styleIndex: Int, color: Int): TextPaint {
            val k = (styleIndex.toLong() shl 32) or (color.toLong() and 0xFFFFFFFFL)
            return paints.getOrPut(k) {
                val st = book.style(styleIndex)
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create(Typeface.DEFAULT, (if (st.bold) Typeface.BOLD else 0) or (if (st.italic) Typeface.ITALIC else 0))
                    this.color = when { color != 0 -> color or 0xFF000000.toInt(); st.color != 0 -> st.color or 0xFF000000.toInt(); else -> Color.BLACK }
                    isUnderlineText = st.underline; isStrikeThruText = st.strike
                }
            }.also { it.textSize = (book.style(styleIndex).sizePt.takeIf { s -> s > 0 } ?: 11f) * ppp }
        }

        // ------------------------------------------------------------------ drawing
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            headPaint.textSize = 11.5f * density * zoom.coerceIn(0.7f, 1.4f)
            val w = width.toFloat(); val h = height.toFloat()
            val fx = headW + fxPx; val fy = headH + fyPx
            // Four regions: frozen corner, frozen rows, frozen columns, the rest.
            region(canvas, 0, fr, 0, fc, headW, headH, fx, fy)
            region(canvas, 0, fr, fc, cols, fx, headH, w, fy)
            region(canvas, fr, rows, 0, fc, headW, fy, fx, h)
            region(canvas, fr, rows, fc, cols, fx, fy, w, h)
            headers(canvas, w, h)
            if (fc > 0) canvas.drawLine(fx, 0f, fx, h, freeze)
            if (fr > 0) canvas.drawLine(0f, fy, w, fy, freeze)
            // Selected cell (or merged area).
            val m = mergeAt(selR, selC)
            val r1 = m?.r1 ?: selR; val c1 = m?.c1 ?: selC; val r2 = m?.r2 ?: selR; val c2 = m?.c2 ?: selC
            if (r2 < rows && c2 < cols) {
                canvas.save()
                canvas.clipRect(headW, headH, w, h)
                canvas.drawRect(xOf(c1), yOf(r1), xOf(c1) + (colX[c2 + 1] - colX[c1]) * ppp, yOf(r1) + (rowY[r2 + 1] - rowY[r1]) * ppp, selPaint)
                canvas.restore()
            }
        }

        private fun region(canvas: Canvas, r0: Int, r1: Int, c0: Int, c1: Int, left: Float, top: Float, right: Float, bottom: Float) {
            if (r0 >= r1 || c0 >= c1 || right <= left || bottom <= top) return
            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            val ox = headW - (if (c0 >= fc && fc < cols) sx else 0f)
            val oy = headH - (if (r0 >= fr && fr < rows) sy else 0f)
            val cs = max(c0, at(colX, cols, (left - ox) / ppp)); val ce = min(c1 - 1, at(colX, cols, (right - ox) / ppp))
            val rs = max(r0, at(rowY, rows, (top - oy) / ppp)); val re = min(r1 - 1, at(rowY, rows, (bottom - oy) / ppp))
            if (cs > ce || rs > re) { canvas.restore(); return }
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
            if (sheet.gridLines) {
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
            // Text: rows on screen, plus text running in from cells just left of it.
            for ((r, row) in visibleRows) {
                if (r in sheet.hiddenRows) continue
                val from = row.headMap(cs).let { hm -> if (hm.isEmpty()) cs else max(c0, hm.lastKey()) }
                for ((c, cell) in row.subMap(from, ce + 1)) {
                    if (cell.text.isEmpty() || c in sheet.hiddenCols) continue
                    val mg = mergeAt(r, c)
                    if (mg != null) continue
                    text(canvas, r, c, cell, x(c), y(r), x(c + 1), y(r + 1), row, ox)
                }
            }
            for (mg in drawnMerges) {
                val cell = cells[mg.r1, mg.c1] ?: continue
                if (cell.text.isEmpty()) continue
                text(canvas, mg.r1, mg.c1, cell, x(mg.c1), y(mg.r1), x(mg.c2 + 1), y(mg.r2 + 1), null, ox)
            }
            canvas.restore()
        }

        private fun text(canvas: Canvas, r: Int, c: Int, cell: Cell, l: Float, t: Float, rr: Float, b: Float, row: java.util.SortedMap<Int, Cell>?, ox: Float) {
            val st = book.style(cell.style)
            val p = paintFor(cell.style, cell.color)
            val pad = 3 * ppp
            val indent = st.indent * 9f * ppp
            val align = when (st.hAlign) {
                HAlign.GENERAL -> when (cell.kind) { CellKind.NUMBER -> HAlign.RIGHT; CellKind.BOOLEAN, CellKind.ERROR -> HAlign.CENTER; else -> HAlign.LEFT }
                HAlign.FILL, HAlign.JUSTIFY -> HAlign.LEFT
                else -> st.hAlign
            }
            val cw = rr - l
            if (st.wrap && cw > 2 * pad) {
                val k = "$sheetIndex:$r:$c:${cw.toInt()}:${zoom}"
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
                    val next = row!!.tailMap(c + 1).let { tm -> if (tm.isEmpty()) cols else tm.firstKey() }
                    var e = c + 1
                    while (e < min(next, cols) && xOf(e) - l < tw + 2 * pad + indent && mergeAt(r, e) == null) e++
                    right = ox + colX[e] * ppp
                }
                if (align == HAlign.RIGHT || align == HAlign.CENTER) {
                    val prev = row!!.headMap(c).let { hm -> if (hm.isEmpty()) -1 else hm.lastKey() }
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

        private fun headers(canvas: Canvas, w: Float, h: Float) {
            val hw = headW; val hh = headH
            canvas.drawRect(0f, 0f, w, hh, headFill)
            canvas.drawRect(0f, 0f, hw, h, headFill)
            val fm = headPaint.fontMetrics
            // Column letters.
            fun cols(c0: Int, c1: Int, left: Float, right: Float) {
                if (c0 >= c1) return
                canvas.save(); canvas.clipRect(left, 0f, right, hh)
                val cs = max(c0, at(colX, cols, (left - xOf(c0) + colX[c0] * ppp) / ppp))
                var c = cs
                while (c < c1 && xOf(c) < right) {
                    val l = xOf(c); val r = xOf(c + 1)
                    if (r > l) {
                        if (c == selC) canvas.drawRect(l, 0f, r, hh, headSel)
                        canvas.drawLine(r, 0f, r, hh, gridPaint)
                        if (r - l > headPaint.textSize) canvas.drawText(XlsxReader.columnName(c), (l + r) / 2, hh / 2 - (fm.ascent + fm.descent) / 2, headPaint)
                    }
                    c++
                }
                canvas.restore()
            }
            cols(0, fc, hw, hw + fxPx)
            cols(fc, this.cols, hw + fxPx, w)
            fun rows(r0: Int, r1: Int, top: Float, bottom: Float) {
                if (r0 >= r1) return
                canvas.save(); canvas.clipRect(0f, top, hw, bottom)
                var r = max(r0, at(rowY, rows, (top - yOf(r0) + rowY[r0] * ppp) / ppp))
                while (r < r1 && yOf(r) < bottom) {
                    val t = yOf(r); val b = yOf(r + 1)
                    if (b > t) {
                        if (r == selR) canvas.drawRect(0f, t, hw, b, headSel)
                        canvas.drawLine(0f, b, hw, b, gridPaint)
                        if (b - t > headPaint.textSize * 0.8f) canvas.drawText((r + 1).toString(), hw / 2, (t + b) / 2 - (fm.ascent + fm.descent) / 2, headPaint)
                    }
                    r++
                }
                canvas.restore()
            }
            rows(0, fr, hh, hh + fyPx)
            rows(fr, this.rows, hh + fyPx, h)
            canvas.drawLine(hw, 0f, hw, h, gridPaint)
            canvas.drawLine(0f, hh, w, hh, gridPaint)
            canvas.drawRect(0f, 0f, hw, hh, headFill)
        }

        // ------------------------------------------------------------------ touch
        private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                sx += dx; sy += dy; clamp(); invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, maxSx.toInt(), 0, maxSy.toInt())
                postInvalidateOnAnimation()
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                cellAt(e.x, e.y)?.let { (r, c) -> select(r, c) }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { zoomAround(if (zoom < 1.5f) 2f / zoom else 1f / zoom, e.x, e.y); return true }
        })

        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { zoomAround(d.scaleFactor, d.focusX, d.focusY); return true }
        })

        fun zoomAround(f: Float, fx: Float, fy: Float) {
            val next = (zoom * f).coerceIn(0.3f, 4f)
            val k = next / zoom
            if (abs(k - 1f) < 0.001f) return
            val headWOld = headW; val headHOld = headH
            zoom = next
            sx = (sx + fx - headWOld) * k - (fx - headW)
            sy = (sy + fy - headHOld) * k - (fy - headH)
            clamp(); wrapped.evictAll(); invalidate()
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) { sx = scroller.currX.toFloat(); sy = scroller.currY.toFloat(); clamp(); postInvalidateOnAnimation() }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaler.onTouchEvent(e)
            if (!scaler.isInProgress) gestures.onTouchEvent(e)
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { clamp() }

        fun matchColor(r: Int, c: Int): Int {
            if (matchSet.isEmpty()) return 0
            val m = matches.getOrNull(current)
            if (m != null && m.sheet == sheetIndex && m.r == r && m.c == c) return RichText.CURRENT
            return if (key(r, c) in matchSet) RichText.MATCH else 0
        }
    }

    // ------------------------------------------------------------------ search, contents, zoom
    private class Hit(val sheet: Int, val r: Int, val c: Int)
    private var matches: List<Hit> = emptyList()
    private var matchSet: Set<Long> = emptySet()
    private var current = -1
    private var searchUpdate: ((SearchStatus) -> Unit)? = null
    private var searchThread: Thread? = null

    override fun search(query: String, update: (SearchStatus) -> Unit) {
        searchUpdate = update
        searchThread?.interrupt()
        if (query.isBlank()) { clearSearch(); update(SearchStatus(0, 0)); return }
        update(SearchStatus(0, 0, busy = true))
        val q = query.lowercase(I18n.locale)
        lateinit var t: Thread
        t = Thread {
            val found = ArrayList<Hit>()
            loop@ for (si in shown) {
                val s = book.sheets[si]
                for (r in s.cells.rowIndices()) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    for ((c, cell) in s.cells.row(r) ?: continue) {
                        if (cell.text.lowercase(I18n.locale).contains(q) || cell.formula?.lowercase(I18n.locale)?.contains(q) == true) found.add(Hit(si, r, c))
                        if (found.size >= 100_000) break@loop
                    }
                }
            }
            post {
                if (searchThread !== t) return@post
                matches = found
                current = found.indexOfFirst { it.sheet == sheetIndex }.let { if (it < 0 && found.isNotEmpty()) 0 else it }
                showMatch()
            }
        }
        searchThread = t
        t.start()
    }

    private fun showMatch() {
        val m = matches.getOrNull(current)
        if (m != null && m.sheet != sheetIndex) showSheet(m.sheet)
        matchSet = matches.filter { it.sheet == sheetIndex }.map { (it.r.toLong() shl 20) or it.c.toLong() }.toHashSet()
        if (m != null) { select(m.r, m.c); grid.reveal(m.r, m.c) }
        grid.invalidate()
        searchUpdate?.invoke(SearchStatus(if (current >= 0) current + 1 else 0, matches.size))
    }

    override fun searchStep(forward: Boolean) {
        if (matches.isEmpty()) return
        current = if (forward) (current + 1) % matches.size else (current - 1 + matches.size) % matches.size
        showMatch()
    }

    override fun clearSearch() {
        searchThread?.interrupt(); searchThread = null
        matches = emptyList(); matchSet = emptySet(); current = -1
        grid.invalidate()
    }

    override val contentsTitle: String get() = tr("Sheets")
    override fun contents() = shown.map { (book.sheets[it].name.ifBlank { tr("Sheet {0}", it + 1) }) to it }
    override fun goTo(target: Int) = showSheet(target)
    override fun zoomBy(factor: Float) = grid.zoomAround(factor, grid.width / 2f, grid.height / 2f)

    override fun facts(): List<String> {
        val hidden = book.sheets.count { it.hidden }
        val cells = book.sheets.sumOf { it.cells.count.toLong() }
        return listOfNotNull(
            trn(book.sheets.size, "{0} sheet", "{0} sheets"),
            if (hidden > 0 && shown.size < book.sheets.size) trn(hidden, "{0} hidden sheet (not shown)", "{0} hidden sheets (not shown)") else null,
            trn(cells.toInt(), "{0} cell with content", "{0} cells with content"),
        )
    }

    override fun close() { searchThread?.interrupt() }

    /** The cell shown in the bar (for tests). */
    val selectedText: CharSequence get() = valueView.text
    val selectedRef: CharSequence get() = refView.text
    fun selectCell(r: Int, c: Int) { select(r, c); grid.reveal(r, c) }
}
