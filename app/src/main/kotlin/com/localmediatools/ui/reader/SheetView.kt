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
            cell.formula != null -> cell.formula
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
        var painter = SheetPainter(book, sheet, 26, 60)
        val colX get() = painter.colX
        val rowY get() = painter.rowY
        val cols get() = painter.cols
        val rows get() = painter.rows
        var sx = 0f; var sy = 0f
        var selR = 0; var selC = 0
        private val scroller = OverScroller(ctx)
        private val gridPaint = Paint().apply { color = 0xFFE1E4E8.toInt(); strokeWidth = 1f }
        private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F6670.toInt(); textAlign = Paint.Align.CENTER }
        private val headFill = Paint().apply { color = 0xFFF3F4F6.toInt() }
        private val headSel = Paint().apply { color = 0xFFDCE3F7.toInt() }
        private val selPaint = Paint().apply { color = 0xFF2F6FED.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.2f * resources.displayMetrics.density }
        private val freeze = Paint().apply { color = 0xFFB0B6BF.toInt(); strokeWidth = 2f * resources.displayMetrics.density }

        fun load(s: Sheet) {
            sheet = s
            val cols = min(16384, max(s.cells.maxCol + 1, s.merges.maxOfOrNull { it.c2 + 1 } ?: 0) + 8).coerceAtLeast(26)
            val rows = min(1_048_576 + 64, max(s.cells.maxRow + 1, s.merges.maxOfOrNull { it.r2 + 1 } ?: 0) + 40).coerceAtLeast(60)
            painter = SheetPainter(book, s, cols, rows)
            sx = 0f; sy = 0f
            scroller.forceFinished(true)
            invalidate()
        }

        private fun key(r: Int, c: Int) = painter.key(r, c)
        fun mergeAt(r: Int, c: Int): Merge? = painter.mergeAt(r, c)

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
        private fun at(arr: FloatArray, n: Int, v: Float): Int = painter.at(arr, n, v)

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
            painter.draw(canvas, rs, re, cs, ce, c0, ox, oy, ppp, sheet.gridLines, max(1f, density), 1f, ::matchColor)
            canvas.restore()
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
            clamp(); painter.clearCache(); invalidate()
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
        val q = query
        lateinit var t: Thread
        t = Thread {
            val found = ArrayList<Hit>()
            loop@ for (si in shown) {
                val s = book.sheets[si]
                for (r in s.cells.rowIndices()) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    for ((c, cell) in s.cells.row(r) ?: continue) {
                        if (cell.text.contains(q, ignoreCase = true) || cell.formula?.contains(q, ignoreCase = true) == true) found.add(Hit(si, r, c))
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
