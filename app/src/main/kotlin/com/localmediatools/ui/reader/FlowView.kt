package com.localmediatools.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.localmediatools.docs.core.Align
import com.localmediatools.docs.core.Block
import com.localmediatools.docs.core.FlowDocument
import com.localmediatools.docs.core.PageBreak
import com.localmediatools.docs.core.Paragraph
import com.localmediatools.docs.core.Picture
import com.localmediatools.docs.core.Placeholder
import com.localmediatools.docs.core.Rule
import com.localmediatools.docs.core.Table
import com.localmediatools.docs.core.TableCell
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Word documents, e-books, RTF and text files as one column of reflowing text on a white page,
 * with tables, pictures and lists; pinch (or the menu) changes the text size. Only the paragraphs
 * on screen exist as views, so books of thousands of pages scroll smoothly.
 */
@SuppressLint("ViewConstructor")
class FlowView(ctx: Context, private val doc: FlowDocument, private val host: ReaderHost) : FrameLayout(ctx), ReaderContent {
    override val view: View get() = this

    companion object {
        const val PAGE = 0xFFFFFFFF.toInt()
        const val INK = 0xFF1F2328.toInt()
        const val LINK = 0xFF1A5FD0.toInt()
        const val LINE = 0xFFD0D4DA.toInt()
        const val SOFT = 0xFFF2F4F7.toInt()
        private const val CHUNK_ROWS = 24
        private val HEADING = floatArrayOf(1.6f, 1.4f, 1.25f, 1.12f, 1.05f, 1f)
    }

    private val density = resources.displayMetrics.density
    private val sp16 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
    var zoom = 1f; private set
    private val basePx get() = sp16 * zoom
    /** Document points to pixels: the document's normal text size becomes 16 sp (× zoom). */
    private val pxPerPt get() = basePx / doc.baseSizePt.coerceIn(6f, 30f)
    private val pad = (18 * density).toInt()

    // ------------------------------------------------------------------ items
    private sealed class Item(val block: Int)
    private class ParaItem(val p: Paragraph, block: Int) : Item(block)
    private class RowsItem(val geo: TableGeo, val from: Int, val to: Int, block: Int) : Item(block)
    private class PicItem(val p: Picture, block: Int) : Item(block)
    private class MarkItem(val kind: Int, val label: String?, block: Int) : Item(block)

    private val items: List<Item> = buildItems()

    private fun buildItems(): List<Item> {
        val out = ArrayList<Item>()
        for ((i, b) in doc.blocks.withIndex()) when (b) {
            is Paragraph -> out.add(ParaItem(b, i))
            is Table -> if (b.rows.isNotEmpty()) {
                val geo = TableGeo(b)
                var r = 0
                while (r < geo.rows) {
                    var end = min(geo.rows, r + CHUNK_ROWS)
                    // A cell spanning rows keeps its rows in one piece.
                    while (end < geo.rows && geo.spanEnd(r, end) > end) end = min(geo.rows, geo.spanEnd(r, end))
                    out.add(RowsItem(geo, r, end, i)); r = end
                }
            }
            is Picture -> out.add(PicItem(b, i))
            is PageBreak -> out.add(MarkItem(0, null, i))
            is Rule -> out.add(MarkItem(1, null, i))
            is Placeholder -> out.add(MarkItem(2, b.label, i))
        }
        return out
    }

    // ------------------------------------------------------------------ list
    private val list = object : ListView(ctx) {
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            if (w != ow) post { this@FlowView.adapter.notifyDataSetChanged() }
        }
    }.apply {
        divider = null; dividerHeight = 0
        setBackgroundColor(PAGE)
        setPadding(0, (12 * density).toInt(), 0, (96 * density).toInt())
        clipToPadding = false
        isFastScrollEnabled = true
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        itemsCanFocus = true
        cacheColorHint = PAGE
    }
    private val adapter = Adapter()
    private val avail get() = (list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - 2 * pad

    init {
        setBackgroundColor(PAGE)
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        list.adapter = adapter
        if (items.isEmpty()) addView(TextView(ctx).apply {
            text = tr("This document has no text to show.")
            setTextColor(0xFF6B7280.toInt()); textSize = 15f; gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 4
        override fun getItemViewType(position: Int) = when (items[position]) { is ParaItem -> 0; is RowsItem -> 1; is PicItem -> 2; is MarkItem -> 3 }
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val it = items[position]) {
            is ParaItem -> bindPara(convertView as? TextView ?: newText(), it, position)
            is RowsItem -> bindRows(convertView as? RowsScroll ?: RowsScroll(context), it, position)
            is PicItem -> bindPicture(convertView as? PictureView ?: PictureView(context), it)
            is MarkItem -> bindMark(convertView as? TextView ?: TextView(context), it)
        }
    }

    // ------------------------------------------------------------------ paragraphs
    @SuppressLint("ClickableViewAccessibility")
    private fun newText() = TextView(context).apply {
        setTextColor(INK)
        setLinkTextColor(LINK)
        includeFontPadding = true
        setTextIsSelectable(true)
        // A tap on a link opens it; anything else is left to text selection.
        var downX = 0f; var downY = 0f; var downAt = 0L
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener { v, e ->
            val tv = v as TextView
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downAt = e.eventTime }
                MotionEvent.ACTION_UP -> if (Math.abs(e.x - downX) < slop && Math.abs(e.y - downY) < slop && e.eventTime - downAt < ViewConfiguration.getLongPressTimeout()) {
                    val layout = tv.layout
                    val text = tv.text as? Spanned
                    if (layout != null && text != null) {
                        val line = layout.getLineForVertical((e.y - tv.totalPaddingTop + tv.scrollY).toInt())
                        val off = layout.getOffsetForHorizontal(line, e.x - tv.totalPaddingLeft + tv.scrollX)
                        val links = text.getSpans(off, off, ClickableSpan::class.java)
                        if (links.isNotEmpty() && e.x - tv.totalPaddingLeft <= layout.getLineRight(line) + 4 * density) { links[0].onClick(tv); return@setOnTouchListener true }
                    }
                }
            }
            false
        }
    }

    private fun bindPara(tv: TextView, it: ParaItem, position: Int): TextView {
        val p = it.p
        val noSizes = p.spans.all { s -> s.style.sizePt == 0f }
        val heading = if (p.heading in 1..6 && noSizes) HEADING[p.heading - 1] else 1f
        val textPx = basePx * heading
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        tv.typeface = when {
            p.code -> Typeface.MONOSPACE
            p.heading in 1..6 && noSizes -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
        val bg = if (p.shading != 0) p.shading or 0xFF000000.toInt() else PAGE
        val sb = RichText.paragraph(p, pxPerPt, INK, bg, LINK, host::link)
        var prefix = 0
        val maxIndent = avail * 0.45f
        val indent = (if (p.indentPt != 0f) p.indentPt * pxPerPt * 0.75f else p.listLevel * 22 * density).coerceIn(0f, maxIndent)
        val first = (indent + p.firstLinePt * pxPerPt * 0.75f).coerceIn(0f, maxIndent)
        if (p.listLabel != null) {
            val label = p.listLabel + " "
            sb.insert(0, label); prefix = label.length
            val paint = TextPaint(tv.paint).apply { textSize = textPx }
            val labelW = paint.measureText(label)
            val start = if (p.indentPt != 0f) min(first, indent) else indent
            sb.setSpan(LeadingMarginSpan.Standard(start.toInt(), max(start + labelW, indent).toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        } else if (indent > 0 || first > 0) {
            sb.setSpan(LeadingMarginSpan.Standard(first.toInt(), indent.toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        if (p.quote) sb.setSpan(QuoteSpan(0xFFB8C0CC.toInt(), (3 * density).toInt(), (12 * density).toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        when (p.align) {
            Align.CENTER -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            Align.END -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            else -> {}
        }
        tv.justificationMode = if (p.align == Align.JUSTIFY && !p.code) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE
        matchesIn(position).let { ms -> if (ms.isNotEmpty()) RichText.mark(sb, ms.map { m -> (m.range.first + prefix)..(m.range.last + prefix) }, ms.indexOfFirst { m -> m.index == current }) }
        // An empty paragraph keeps its line (documents use them as spacing).
        tv.text = if (sb.isEmpty()) " " else sb
        val before = (p.spaceBeforePt * pxPerPt * 0.6f).coerceIn(0f, basePx * 2).toInt()
        val after = (p.spaceAfterPt * pxPerPt * 0.6f).coerceIn(0f, basePx * 2).toInt() + if (p.heading > 0) (basePx * 0.2f).toInt() else 0
        val inner = if (p.shading != 0) (8 * density).toInt() else 0
        tv.setPadding(pad + inner, before + inner, pad + inner, after + inner)
        tv.setBackgroundColor(if (p.shading != 0) bg else Color.TRANSPARENT)
        tv.setLineSpacing(0f, if (p.code) 1.05f else 1.18f)
        return tv
    }

    // ------------------------------------------------------------------ tables
    /** A table's cells placed on a grid, with column widths for the current width and text size. */
    private inner class TableGeo(val table: Table) {
        inner class Placed(val cell: TableCell, val row: Int, val col: Int, val index: Int) {
            val paragraphs: List<Paragraph> by lazy { flatten(cell.blocks) }
            val shading get() = cell.shading
        }

        val placed: List<List<Placed>>
        val cols: Int
        val rows = table.rows.size
        private val spanEndOf: IntArray
        var key = -1f
        var colX = FloatArray(1)
        val width get() = colX.last()
        var scrollX = 0

        init {
            val taken = HashMap<Int, java.util.BitSet>()
            val out = ArrayList<List<Placed>>()
            var maxCol = 0
            spanEndOf = IntArray(rows) { it + 1 }
            for ((r, row) in table.rows.withIndex()) {
                val busy = taken.getOrPut(r) { java.util.BitSet() }
                var c = 0
                val cells = ArrayList<Placed>()
                for ((k, cell) in row.cells.withIndex()) {
                    while (busy[c]) c++
                    val span = cell.colSpan.coerceIn(1, 64)
                    val rs = cell.rowSpan.coerceIn(1, rows - r)
                    for (rr in r until r + rs) taken.getOrPut(rr) { java.util.BitSet() }.set(c, c + span)
                    cells.add(Placed(cell, r, c, k))
                    if (r + rs > spanEndOf[r]) spanEndOf[r] = r + rs
                    c += span
                    maxCol = max(maxCol, c)
                }
                out.add(cells)
            }
            placed = out
            cols = max(1, maxCol)
        }

        /** The row a piece of rows from..to must reach so no cell is cut. */
        fun spanEnd(from: Int, to: Int): Int { var e = to; for (r in from until to) e = max(e, spanEndOf[r]); return e }

        fun ensure() {
            val k = avail.toFloat() * 1000 + zoom
            if (k == key) return
            key = k
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = basePx * 0.92f }
            val cellPad = 8 * density * 2
            val natural = FloatArray(cols); val minimum = FloatArray(cols); val hint = FloatArray(cols)
            // Widths from the first rows are enough to size columns (tables can have thousands of rows).
            for (row in placed.take(400)) for (pc in row) {
                if (pc.cell.colSpan != 1 || pc.col >= cols) continue
                var nat = 0f; var mn = 0f
                for (p in pc.paragraphs) for (line in p.text.split('\n')) {
                    nat = max(nat, paint.measureText(line))
                    for (w in line.split(' ')) if (w.length > 1) mn = max(mn, paint.measureText(w))
                }
                natural[pc.col] = max(natural[pc.col], nat + cellPad)
                minimum[pc.col] = max(minimum[pc.col], min(mn, avail * 0.5f) + cellPad)
                if (pc.cell.widthPt > 0) hint[pc.col] = max(hint[pc.col], pc.cell.widthPt)
            }
            val widths = FloatArray(cols) { c -> max(minimum[c], min(natural[c], avail * 0.85f)).coerceAtLeast(28 * density) }
            val minSum = (0 until cols).sumOf { max(minimum[it], 28f * density).toDouble() }.toFloat()
            val prefSum = widths.sum()
            val out = when {
                // Fits: widen to the page like the document's own table (by its column widths when it gives them).
                prefSum <= avail -> {
                    val hints = hint.sum()
                    if (hints > 0 && hint.all { it > 0 }) FloatArray(cols) { max(widths[it], avail * hint[it] / hints) }.let { w -> val s = w.sum(); if (s > avail) FloatArray(cols) { c -> widths[c] + (avail - prefSum) * (widths[c] / prefSum) } else w }
                    else FloatArray(cols) { widths[it] + (avail - prefSum) * (widths[it] / prefSum) }
                }
                minSum <= avail -> FloatArray(cols) { c -> val mn = max(minimum[c], 28 * density); mn + (avail - minSum) * ((widths[c] - mn) / (prefSum - minSum).coerceAtLeast(1f)) }
                else -> FloatArray(cols) { max(minimum[it], 28 * density) }
            }
            colX = FloatArray(cols + 1); for (c in 0 until cols) colX[c + 1] = colX[c] + out[c]
        }
    }

    /** Text of blocks inside a table cell (nested tables row by row). */
    private fun flatten(blocks: List<Block>): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        for (b in blocks) when (b) {
            is Paragraph -> out.add(b)
            is Table -> for (r in b.rows) out.add(Paragraph(r.cells.flatMap { c -> flatten(c.blocks).flatMap { it.spans } + com.localmediatools.docs.core.Span("   ") }))
            is Picture -> out.add(Paragraph(listOf(com.localmediatools.docs.core.Span(b.alt?.takeIf { it.isNotBlank() } ?: tr("[Picture]")))))
            is Placeholder -> out.add(Paragraph(listOf(com.localmediatools.docs.core.Span("[${b.label}]"))))
            else -> {}
        }
        return out
    }

    /** One piece of a table: its rows drawn as a grid, scrolled sideways together with the table's other pieces. */
    private inner class RowsScroll(ctx: Context) : HorizontalScrollView(ctx) {
        val grid = RowsGrid(ctx)
        var geo: TableGeo? = null
        init {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            addView(grid)
        }
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            val g = geo ?: return
            if (g.scrollX == l) return
            g.scrollX = l
            for (i in 0 until list.childCount) (list.getChildAt(i) as? RowsScroll)?.let { o -> if (o !== this && o.geo === g && o.scrollX != l) o.scrollTo(l, 0) }
        }
    }

    private fun bindRows(v: RowsScroll, it: RowsItem, position: Int): View {
        it.geo.ensure()
        v.geo = it.geo
        v.grid.bind(it, matchesIn(position))
        v.setPadding(pad, 0, pad, 0)
        v.clipToPadding = false
        v.post { if (v.scrollX != it.geo.scrollX) v.scrollTo(it.geo.scrollX, 0) }
        return v
    }

    private inner class RowsGrid(ctx: Context) : View(ctx) {
        private var item: RowsItem? = null
        private val rowY = ArrayList<Float>()
        private val laid = ArrayList<Laid>()
        private val fill = Paint()
        private val border = Paint().apply { color = LINE; strokeWidth = max(1f, density * 0.75f); style = Paint.Style.STROKE }
        private var total = 0f

        fun bind(it: RowsItem, matches: List<Match>) {
            item = it
            val geo = it.geo
            val cellPad = 8 * density
            laid.clear(); rowY.clear()
            val heights = FloatArray(it.to - it.from) { basePx * 1.5f }
            val pending = ArrayList<Pending>()
            for (r in it.from until it.to) for (pc in geo.placed[r]) {
                val c2 = min(geo.cols, pc.col + pc.cell.colSpan.coerceAtLeast(1))
                val w = (geo.colX[c2] - geo.colX[pc.col] - 2 * cellPad).coerceAtLeast(8f)
                val bg = if (pc.shading != 0) pc.shading or 0xFF000000.toInt() else PAGE
                val layouts = pc.paragraphs.withIndex().map { (k, p) ->
                    val sb = RichText.paragraph(p, pxPerPt * 0.92f, INK, bg, LINK, host::link)
                    if (p.listLabel != null) sb.insert(0, p.listLabel + " ")
                    val ms = matches.filter { m -> m.row == r && m.cell == pc.index && m.para == k }
                    if (ms.isNotEmpty()) RichText.mark(sb, ms.map { m -> m.range }, ms.indexOfFirst { m -> m.index == current })
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = basePx * 0.92f; color = INK; typeface = if (table(it).rows[r].header) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
                    val align = when (p.align) { Align.CENTER -> Layout.Alignment.ALIGN_CENTER; Align.END -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
                    StaticLayout.Builder.obtain(sb, 0, sb.length, paint, w.toInt()).setAlignment(align).setLineSpacing(0f, 1.1f).setIncludePad(true).build()
                }
                val h = layouts.sumOf { l -> l.height.toDouble() }.toFloat() + 2 * cellPad + (layouts.size - 1).coerceAtLeast(0) * 4 * density
                pending.add(Pending(pc.cell, pc.row, pc.col, layouts, h))
                if (pc.cell.rowSpan <= 1) heights[r - it.from] = max(heights[r - it.from], h)
            }
            // Cells spanning rows: the last of their rows grows if they need more room.
            for (pd in pending) if (pd.cell.rowSpan > 1) {
                val last = min(it.to, pd.row + pd.cell.rowSpan) - 1
                val have = (pd.row..last).sumOf { r -> heights[r - it.from].toDouble() }.toFloat()
                if (pd.h > have) heights[last - it.from] += pd.h - have
            }
            var y = 0f
            for (h in heights) { rowY.add(y); y += h }
            rowY.add(y); total = y
            for (pd in pending) {
                val c2 = min(geo.cols, pd.col + pd.cell.colSpan.coerceAtLeast(1))
                laid.add(Laid(geo.colX[pd.col], rowY[pd.row - it.from], geo.colX[c2] - geo.colX[pd.col], pd.layouts, pd.cell.shading, table(it).rows[pd.row].header))
            }
            spans.clear()
            for (pd in pending) spans.add(min(it.to, pd.row + pd.cell.rowSpan.coerceAtLeast(1)) - it.from)
            requestLayout(); invalidate()
        }

        private val spans = ArrayList<Int>()
        private fun table(it: RowsItem) = it.geo.table

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val geo = item?.geo
            setMeasuredDimension(((geo?.width ?: 0f) + 1).toInt(), (total + density * 6).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val it = item ?: return
            val cellPad = 8 * density
            for ((k, l) in laid.withIndex()) {
                val bottom = rowY[spans[k]]
                val h = bottom - l.y
                if (l.shading != 0) { fill.color = l.shading or 0xFF000000.toInt(); canvas.drawRect(l.x, l.y, l.x + l.w, l.y + h, fill) }
                else if (l.header) { fill.color = SOFT; canvas.drawRect(l.x, l.y, l.x + l.w, l.y + h, fill) }
                var y = l.y + cellPad
                for (lay in l.layouts) {
                    canvas.save(); canvas.translate(l.x + cellPad, y); lay.draw(canvas); canvas.restore()
                    y += lay.height + 4 * density
                }
                if (it.geo.table.borders) canvas.drawRect(l.x, l.y, l.x + l.w, l.y + h, border)
            }
        }
    }

    // ------------------------------------------------------------------ pictures
    private val pictures = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(96L shl 20)).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val sizes = HashMap<String, Pair<Int, Int>>()
    private val unreadable = HashSet<String>()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "reader-pictures").apply { priority = Thread.MIN_PRIORITY } }

    private inner class PictureView(ctx: Context) : LinearLayout(ctx) {
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = false }
        val caption = TextView(ctx).apply { setTextColor(0xFF6B7280.toInt()); textSize = 13f; gravity = Gravity.CENTER }
        var ref: String? = null
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(image, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun bindPicture(v: PictureView, it: PicItem): View {
        val p = it.p
        v.ref = p.ref
        v.setPadding(pad, (6 * density).toInt(), pad, (6 * density).toInt())
        val known = sizes[p.ref]
        val (w, h) = pictureBox(p, known)
        v.image.layoutParams = LinearLayout.LayoutParams(w, h)
        v.caption.visibility = View.GONE
        val bmp = pictures.get(p.ref)
        when {
            bmp != null -> v.image.setImageBitmap(bmp)
            p.ref in unreadable -> {
                v.image.setImageDrawable(null)
                v.image.layoutParams = LinearLayout.LayoutParams(0, 0)
                v.caption.visibility = View.VISIBLE
                v.caption.text = p.alt?.takeIf { a -> a.isNotBlank() }?.let { a -> tr("Picture: {0} (in a format this app can't draw)", a) } ?: tr("A picture in a format this app can't draw")
            }
            else -> {
                v.image.setImageDrawable(android.graphics.drawable.ColorDrawable(SOFT))
                val ref = p.ref
                loader.execute {
                    val loaded = decode(ref, w)
                    post {
                        if (loaded == null) unreadable.add(ref) else pictures.put(ref, loaded)
                        if (v.ref == ref) adapter.notifyDataSetChanged()
                    }
                }
            }
        }
        v.contentDescription = p.alt
        return v
    }

    private fun pictureBox(p: Picture, known: Pair<Int, Int>?): Pair<Int, Int> {
        val maxW = avail
        val wantW = when {
            p.widthPt > 0 -> p.widthPt * pxPerPt * 0.75f
            known != null -> known.first * density
            else -> maxW * 0.6f
        }
        val ratio = when {
            p.widthPt > 0 && p.heightPt > 0 -> p.heightPt / p.widthPt
            known != null && known.first > 0 -> known.second.toFloat() / known.first
            else -> 0.66f
        }
        val w = min(maxW.toFloat(), wantW).coerceAtLeast(16 * density)
        return w.toInt() to (w * ratio).toInt().coerceIn((8 * density).toInt(), (resources.displayMetrics.heightPixels * 1.5f).toInt())
    }

    private fun decode(ref: String, targetW: Int): Bitmap? = try {
        val bytes = doc.media.bytes(ref)
        if (bytes == null) null else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) null else {
                post { sizes[ref] = o.outWidth to o.outHeight }
                var sample = 1
                while (o.outWidth / (sample * 2) >= targetW.coerceAtLeast(64) && sample < 64) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: Throwable) { null }

    private fun bindMark(tv: TextView, it: MarkItem): View {
        tv.text = it.label?.let { l -> "[$l]" } ?: ""
        tv.setTextColor(0xFF6B7280.toInt())
        tv.textSize = 13f
        tv.gravity = Gravity.CENTER
        val gap = (if (it.kind == 0) 18 else 8) * density
        tv.setPadding(pad, gap.toInt(), pad, gap.toInt())
        tv.background = when (it.kind) {
            0, 1 -> object : android.graphics.drawable.Drawable() {
                val p = Paint().apply { color = if (it.kind == 0) LINE else 0xFFB8C0CC.toInt(); strokeWidth = density }
                override fun draw(c: Canvas) { val y = bounds.exactCenterY(); c.drawLine(bounds.left + pad.toFloat(), y, bounds.right - pad.toFloat(), y, p) }
                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            else -> null
        }
        tv.minHeight = (if (it.kind == 0) 36 else 16).let { d -> (d * density).toInt() }
        return tv
    }

    // ------------------------------------------------------------------ zoom
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        var pending = 1f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pending = 1f; list.pivotX = detector.focusX; list.pivotY = detector.focusY; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pending = (pending * detector.scaleFactor).coerceIn(0.5f / zoom, 3f / zoom)
            list.scaleX = pending; list.scaleY = pending
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { list.scaleX = 1f; list.scaleY = 1f; zoomBy(pending) }
    })
    private var scaling = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)
        if (scaler.isInProgress || ev.pointerCount > 1) scaling = true
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) scaling = false
        return scaling
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) scaling = false
        return true
    }

    override fun zoomBy(factor: Float) {
        val next = (zoom * factor).coerceIn(0.5f, 3f)
        if (Math.abs(next - zoom) < 0.01f) return
        val first = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        zoom = next
        pictures.evictAll()
        adapter.notifyDataSetChanged()
        list.setSelectionFromTop(first, (top * factor).toInt())
    }

    // ------------------------------------------------------------------ search
    /** A match: in item [item], paragraph text (or table [row], [cell], [para]) at [range]. */
    private class Match(val index: Int, val item: Int, val row: Int, val cell: Int, val para: Int, val range: IntRange)
    private var matches: List<Match> = emptyList()
    private var byItem: Map<Int, List<Match>> = emptyMap()
    private var current = -1
    private var searchUpdate: ((SearchStatus) -> Unit)? = null
    private var searchJob: Thread? = null

    private fun matchesIn(position: Int): List<Match> = byItem[position] ?: emptyList()

    override fun search(query: String, update: (SearchStatus) -> Unit) {
        searchUpdate = update
        searchJob?.interrupt()
        if (query.isBlank()) { clearSearch(); update(SearchStatus(0, 0)); return }
        update(SearchStatus(0, 0, busy = true))
        val locale = I18n.locale
        lateinit var t: Thread
        t = Thread {
            val found = ArrayList<Match>()
            for ((i, it) in items.withIndex()) {
                if (Thread.currentThread().isInterrupted) return@Thread
                when (it) {
                    is ParaItem -> for (r in RichText.find(it.p.text, query, locale)) found.add(Match(found.size, i, -1, -1, -1, r))
                    is RowsItem -> for (row in it.from until it.to) for (pc in it.geo.placed[row]) for ((k, p) in pc.paragraphs.withIndex()) {
                        val shift = p.listLabel?.let { l -> l.length + 1 } ?: 0
                        for (r in RichText.find(p.text, query, locale)) found.add(Match(found.size, i, row, pc.index, k, (r.first + shift)..(r.last + shift)))
                    }
                    is PicItem -> it.p.alt?.let { a -> if (RichText.find(a, query, locale).isNotEmpty()) found.add(Match(found.size, i, -1, -1, -1, 0..0)) }
                    else -> {}
                }
                if (found.size >= 50_000) break
            }
            post {
                if (searchJob !== t) return@post
                matches = found
                byItem = found.groupBy { m -> m.item }
                // Start from the first match at or after what's on screen.
                val from = list.firstVisiblePosition
                current = found.indexOfFirst { m -> m.item >= from }.let { k -> if (k < 0 && found.isNotEmpty()) 0 else k }
                adapter.notifyDataSetChanged()
                show()
            }
        }
        searchJob = t
        t.start()
    }

    private fun show() {
        searchUpdate?.invoke(SearchStatus(if (current >= 0) current + 1 else 0, matches.size))
        val m = matches.getOrNull(current) ?: return
        adapter.notifyDataSetChanged()
        val pos = m.item
        if (pos < list.firstVisiblePosition || pos > list.lastVisiblePosition - 1 || m.row >= 0) list.setSelectionFromTop(pos, (list.height * 0.25f).toInt())
        else list.post {
            // Long paragraphs: bring the line with the match into view.
            val v = list.getChildAt(pos - list.firstVisiblePosition) as? TextView ?: return@post
            val l = v.layout ?: return@post
            val prefix = (items[pos] as? ParaItem)?.p?.listLabel?.let { it.length + 1 } ?: 0
            val y = v.top + v.totalPaddingTop + l.getLineTop(l.getLineForOffset((m.range.first + prefix).coerceIn(0, v.text.length)))
            if (y < 0 || y > list.height * 0.8f) list.setSelectionFromTop(pos, v.top - y + (list.height * 0.25f).toInt())
        }
    }

    override fun searchStep(forward: Boolean) {
        if (matches.isEmpty()) return
        current = if (forward) (current + 1) % matches.size else (current - 1 + matches.size) % matches.size
        show()
    }

    override fun clearSearch() {
        searchJob?.interrupt(); searchJob = null
        matches = emptyList(); byItem = emptyMap(); current = -1
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ contents
    private val outline: List<Pair<String, Int>> by lazy {
        val fromDoc = doc.outline.mapNotNull { (label, block) ->
            val item = items.indexOfFirst { it.block >= block }
            if (item >= 0 && label.isNotBlank()) label.trim().take(120) to item else null
        }
        if (fromDoc.isNotEmpty()) return@lazy fromDoc
        items.withIndex().mapNotNull { (i, it) ->
            val p = (it as? ParaItem)?.p ?: return@mapNotNull null
            if (p.heading !in 1..3 || p.text.isBlank()) null else ("  ".repeat(p.heading - 1) + p.text.trim().replace('\n', ' ').take(120)) to i
        }.take(2000)
    }

    override fun contents() = outline

    override fun goTo(target: Int) { list.setSelectionFromTop(target.coerceIn(0, max(0, items.size - 1)), (8 * density).toInt()) }

    override fun facts(): List<String> {
        val words = doc.blocks.sumOf { b -> ((b as? Paragraph)?.text?.split(Regex("\\s+"))?.count { it.isNotEmpty() } ?: 0).toLong() }
        val pics = doc.blocks.count { it is Picture }
        val tables = doc.blocks.count { it is Table }
        return listOfNotNull(
            trn(words.toInt(), "About {0} word", "About {0} words"),
            if (tables > 0) trn(tables, "{0} table", "{0} tables") else null,
            if (pics > 0) trn(pics, "{0} picture", "{0} pictures") else null,
        )
    }

    /** Where the list is, so a rotated screen opens at the same place. */
    val position: Int get() = list.firstVisiblePosition

    override fun close() {
        searchJob?.interrupt()
        loader.shutdownNow()
        pictures.evictAll()
    }
}

/** A table cell laid out for drawing. */
private class Laid(val x: Float, val y: Float, val w: Float, val layouts: List<StaticLayout>, val shading: Int, val header: Boolean)

/** A table cell measured, before its row's height is known. */
private class Pending(val cell: TableCell, val row: Int, val col: Int, val layouts: List<StaticLayout>, val h: Float)
