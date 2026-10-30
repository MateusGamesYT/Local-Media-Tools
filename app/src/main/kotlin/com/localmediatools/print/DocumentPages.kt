package com.localmediatools.print

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.util.LruCache
import com.localmediatools.docs.core.Align
import com.localmediatools.docs.core.Block
import com.localmediatools.docs.core.Deck
import com.localmediatools.docs.core.DocFormat
import com.localmediatools.docs.core.DocFormats
import com.localmediatools.docs.core.FlowDocument
import com.localmediatools.docs.core.PageBreak
import com.localmediatools.docs.core.Paragraph
import com.localmediatools.docs.core.Picture
import com.localmediatools.docs.core.Placeholder
import com.localmediatools.docs.core.Rule
import com.localmediatools.docs.core.Span
import com.localmediatools.docs.core.Table
import com.localmediatools.docs.core.TableCell
import com.localmediatools.docs.core.UnreadableException
import com.localmediatools.docs.core.Workbook
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.localmediatools.print.core.MediaSize
import com.localmediatools.ui.reader.RichText
import com.localmediatools.ui.reader.SheetPainter
import com.localmediatools.ui.reader.SlideRenderer
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** A document laid out on pages for printing: page i is [widthPt]×[heightPt] points, drawn at any scale. */
interface DocumentPages : Closeable {
    val count: Int
    fun widthPt(i: Int): Float
    fun heightPt(i: Int): Float
    /** Draws page [i] with its top-left corner at the canvas origin, [pxPerPt] pixels to a point (white paper assumed). */
    fun draw(canvas: Canvas, i: Int, pxPerPt: Float)
}

/** Word, Excel, PowerPoint, OpenDocument, RTF, EPUB, HTML and text files, put on pages to print. */
object DocumentPrint {
    /**
     * [file] (named [name]) laid out on [paper]; null when it isn't a document the readers open (or
     * is a PDF, which is printed as it is). Throws [UnreadableException] with the reason when it is
     * one but can't be read.
     */
    fun open(file: File, name: String, paper: MediaSize): DocumentPages? = onBigStack {
        val fmt = DocFormats.detect(file, name)
        if (fmt == null || fmt == DocFormat.PDF) return@onBigStack null
        val doc = DocFormats.open(file, fmt, I18n.locale)
        try {
            when (doc) {
                is FlowDocument -> FlowPages(doc, paper)
                is Workbook -> SheetPages(doc, paper)
                is Deck -> SlidePages(doc)
            }.also { if (it.count == 0) throw UnreadableException(tr("{0} has nothing to print.", name)) }
        } catch (e: Throwable) { doc.close(); throw e }
    }

    /**
     * Runs [block] on a thread with a large stack and waits for it: documents nest (tables in tables)
     * and the readers recurse, so a deep but valid file must not overflow a small stack.
     */
    private fun <T> onBigStack(block: () -> T): T {
        var result: Result<T>? = null
        val t = Thread(null, {
            result = try { Result.success(block()) } catch (e: StackOverflowError) {
                Result.failure(UnreadableException(tr("This file is built too deeply to be shown.")))
            } catch (e: OutOfMemoryError) {
                Result.failure(UnreadableException(tr("This file is too large to show with the memory this phone has free.")))
            } catch (e: Throwable) { Result.failure(e) }
        }, "document-pages", 64L shl 20)
        t.start()
        try { t.join() } catch (e: InterruptedException) { t.interrupt(); throw e }
        return result!!.getOrThrow()
    }

    internal fun pt(mm: Float) = mm / 25.4f * 72f
}

// ====================================================================== text documents
/**
 * A text document (Word, OpenDocument text, RTF, e-book, HTML, Markdown, text) on pages: the page and
 * margins the document was written for when it says (the page is then fitted to the paper), else
 * [paper] with 20 mm margins. Paragraphs keep their styles, fonts (with the widths of the ones named,
 * see [com.localmediatools.ui.reader.DocFonts]), line spacing, lists and indents; headings stay with
 * the text after them; no single line of a paragraph is left alone at the foot or top of a page;
 * tables repeat their header rows on each page; pictures fit the page; page numbers go in the foot.
 */
class FlowPages(private val doc: FlowDocument, paper: MediaSize) : DocumentPages {
    private companion object {
        /** Layout units per point (text laid out at 4× its size, so small sizes keep their exact widths). */
        const val K = 4f
        const val INK = 0xFF1F2328.toInt()
        const val LINK = 0xFF1A5FD0.toInt()
        const val LINE = 0xFF9AA1AB.toInt()
        const val SOFT = 0xFFF2F4F7.toInt()
        const val GREY = 0xFF6B7280.toInt()
        val HEADING = floatArrayOf(1.6f, 1.4f, 1.25f, 1.12f, 1.05f, 1f)
        const val FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
    }

    private val setup = doc.page?.takeIf { it.sane }
    private val pageW = setup?.widthPt ?: DocumentPrint.pt(paper.widthMm)
    private val pageH = setup?.heightPt ?: DocumentPrint.pt(paper.heightMm)
    private val mLeft = setup?.leftPt ?: DocumentPrint.pt(20f)
    private val mRight = setup?.rightPt ?: DocumentPrint.pt(20f)
    private val mTop = setup?.topPt ?: DocumentPrint.pt(20f)
    private val mBottom = setup?.bottomPt ?: DocumentPrint.pt(20f)
    private val width = ((pageW - mLeft - mRight) * K).toInt()
    private val height = (pageH - mTop - mBottom) * K
    private val base = doc.baseSizePt.coerceIn(6f, 30f) * K
    /** The document's own font for text that names none (else the phone's). */
    private val face: Typeface = com.localmediatools.ui.reader.DocFonts.typeface(doc.defaultFont) ?: Typeface.DEFAULT

    // ------------------------------------------------------------------ what goes on a page
    private sealed class Ink
    /** Lines [from] until [to] of a paragraph laid out at [width] (rebuilt when drawn), its first line's top at [y]. */
    private class TextInk(val p: Paragraph, val width: Int, val scale: Float, val x: Float, val y: Float, val from: Int, val to: Int) : Ink()
    private class BoxInk(val l: Float, val t: Float, val r: Float, val b: Float, val color: Int, val stroke: Float) : Ink()
    private class PicInk(val ref: String, val l: Float, val t: Float, val w: Float, val h: Float) : Ink()
    private class LabelInk(val text: String, val l: Float, val t: Float, val w: Float, val size: Float) : Ink()
    /** Rows [r0] until [r1] of a table, the part from [from] to [to] (units below the rows' top) drawn at [y]. */
    private class RowsInk(val t: TableGeo, val r0: Int, val r1: Int, val y: Float, val from: Float, val to: Float) : Ink()

    private val pages = ArrayList<ArrayList<Ink>>()
    private var page = ArrayList<Ink>()
    private var y = 0f

    override val count: Int get() = pages.size
    override fun widthPt(i: Int) = pageW
    override fun heightPt(i: Int) = pageH

    private fun newPage() { page = ArrayList(); pages.add(page); y = 0f }

    /** Starts a new page unless [h] units still fit on this one (or it is empty). */
    private fun need(h: Float) { if (y > 0f && y + h > height) newPage() }

    // ------------------------------------------------------------------ paragraphs
    private class Built(val layout: StaticLayout, val before: Float, val after: Float, val pad: Float, val shading: Int)

    private val layouts = object : LruCache<Triple<Paragraph, Int, Float>, Built>(400) {}

    private fun built(p: Paragraph, w: Int, scale: Float): Built {
        val key = Triple(p, w, scale)
        layouts.get(key)?.let { return it }
        val noSizes = p.spans.all { it.style.sizePt == 0f }
        val heading = if (p.heading in 1..6 && noSizes) HEADING[p.heading - 1] else 1f
        val textPx = base * heading * scale
        val bg = if (p.shading != 0) p.shading or 0xFF000000.toInt() else Color.WHITE
        val sb: SpannableStringBuilder = RichText.paragraph(p, K * scale, INK, bg, LINK, null)
        val maxIndent = w * 0.45f
        val indent = (if (p.indentPt != 0f) p.indentPt * K * scale else p.listLevel * 18f * K * scale).coerceIn(0f, maxIndent)
        val first = (indent + p.firstLinePt * K * scale).coerceIn(0f, maxIndent)
        val paint = TextPaint(FLAGS).apply {
            textSize = textPx; color = INK
            typeface = when { p.code -> Typeface.MONOSPACE; p.heading in 1..6 && noSizes -> Typeface.create(face, Typeface.BOLD); else -> face }
        }
        if (p.listLabel != null) {
            val label = p.listLabel + " "
            sb.insert(0, label)
            val start = if (p.indentPt != 0f) min(first, indent) else indent
            sb.setSpan(LeadingMarginSpan.Standard(start.toInt(), max(start + paint.measureText(label), indent).toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        } else if (indent > 0 || first > 0) {
            sb.setSpan(LeadingMarginSpan.Standard(first.toInt(), indent.toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        if (p.quote) sb.setSpan(QuoteSpan(0xFFB8C0CC.toInt(), (2 * K).toInt(), (8 * K).toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        when (p.align) {
            Align.CENTER -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            Align.END -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            else -> {}
        }
        val text: CharSequence = if (sb.isEmpty()) " " else sb
        val pad = if (p.shading != 0) 4 * K * scale else 0f
        // Line spacing as Word sets it: single is the font's own line height (with its line gap), times the
        // document's multiple; or a fixed height.
        val fm = paint.fontMetrics
        val natural = fm.descent - fm.ascent
        val gap = fm.leading.coerceAtLeast(0f)
        val (mult, add) = when {
            p.lineHeightPt > 0f -> (p.lineHeightPt * K * scale / natural).coerceIn(0.5f, 4f) to 0f
            else -> { val m = (if (p.lineSpacing > 0f) p.lineSpacing else 1f).coerceIn(0.5f, 4f); m to gap * m }
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (w - 2 * pad).toInt().coerceAtLeast(1))
            .setLineSpacing(add, mult)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setJustificationMode(if (p.align == Align.JUSTIFY && !p.code) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .build()
        val before = (p.spaceBeforePt * K * scale).coerceIn(0f, base * 3)
        val after = (p.spaceAfterPt * K * scale).coerceIn(0f, base * 3) + if (p.heading > 0) base * 0.25f else 0f
        return Built(layout, before, after, pad, if (p.shading != 0) bg else 0).also { layouts.put(key, it) }
    }

    private fun paragraph(p: Paragraph, next: Block?) {
        val b = built(p, width, 1f)
        val lay = b.layout
        val before = if (y > 0f) b.before else 0f
        val whole = before + lay.height + 2 * b.pad
        // A heading stays with what follows it: room for it and two lines more, or a new page.
        // Other paragraphs are split where the page ends.
        if (p.heading > 0 && next != null) need(whole + 2.6f * base)
        y += if (y > 0f) b.before else 0f
        var line = 0
        val n = lay.lineCount
        var firstPiece = true
        while (line < n) {
            val top = lay.getLineTop(line).toFloat()
            val room = height - y - (if (firstPiece) b.pad else 0f)
            var end = line
            while (end < n && lay.getLineBottom(end) - top <= room) end++
            if (end < n && n >= 4) {
                // No paragraph leaves one line alone at the foot of a page or the top of the next.
                if (end - line == 1 && line == 0) end = line
                else if (n - end == 1 && end - line > 2) end--
            }
            if (end == line) {
                if (y > 0f) { newPage(); continue }
                end = line + 1 // a line taller than a page still prints, cut off
            }
            val h = (lay.getLineBottom(end - 1) - top) + (if (firstPiece) b.pad else 0f) + (if (end == n) b.pad else 0f)
            if (b.shading != 0) page.add(BoxInk(0f, y, width.toFloat(), y + h, b.shading, 0f))
            page.add(TextInk(p, width, 1f, b.pad, y + (if (firstPiece) b.pad else 0f), line, end))
            y += h
            line = end
            firstPiece = false
            if (line < n) newPage()
        }
        y += b.after
    }

    private fun label(text: String) {
        val h = base * 1.6f
        need(h)
        page.add(LabelInk(text, 0f, y, width.toFloat(), base * 0.9f))
        y += h
    }

    // ------------------------------------------------------------------ pictures
    private val unreadable = HashSet<String>()

    private fun picture(p: Picture) {
        val bytes = try { doc.media.bytes(p.ref) } catch (_: Exception) { null }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (bytes == null || o.outWidth <= 0 || o.outHeight <= 0) {
            unreadable.add(p.ref)
            label(p.alt?.takeIf { it.isNotBlank() }?.let { tr("Picture: {0} (in a format this app can't draw)", it) } ?: tr("A picture in a format this app can't draw"))
            return
        }
        // The size the document gives it, else its own at 96 pixels to the inch; never wider than the page or taller than one.
        var w = if (p.widthPt > 0) p.widthPt * K else o.outWidth * 0.75f * K
        var h = if (p.widthPt > 0 && p.heightPt > 0) p.heightPt * K else w * o.outHeight / o.outWidth
        val fit = min(1f, min(width / w, height / h))
        w *= fit; h *= fit
        val gap = 4 * K
        need(h + gap)
        page.add(PicInk(p.ref, (width - w) / 2, y + gap / 2, w, h))
        y += h + gap
    }

    private val bitmaps = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L shl 20)).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun bitmap(ref: String, wantW: Int): Bitmap? {
        val bytes = try { doc.media.bytes(ref) } catch (_: Exception) { null } ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= wantW.coerceAtLeast(16) && sample < 64) sample *= 2
        val key = "$ref@$sample"
        bitmaps.get(key)?.let { return it }
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { bitmaps.put(key, it) } } catch (_: OutOfMemoryError) { null }
    }

    // ------------------------------------------------------------------ tables
    /** A table's cells on a grid, with column widths that fit the page (its text made smaller if it must). */
    private inner class TableGeo(val table: Table) {
        inner class Placed(val cell: TableCell, val row: Int, val col: Int) {
            val paragraphs: List<Paragraph> by lazy { flatten(cell.blocks) }
        }

        val rows = table.rows.size
        val placed: List<List<Placed>>
        val cols: Int
        val spanEnd: IntArray
        val colX: FloatArray
        val scale: Float
        val pad = 3f * K
        /** Leading header rows, repeated on every page the table continues on. */
        val headerRows: Int

        init {
            val taken = HashMap<Int, java.util.BitSet>()
            val out = ArrayList<List<Placed>>()
            var maxCol = 0
            spanEnd = IntArray(rows) { it + 1 }
            for ((r, row) in table.rows.withIndex()) {
                val busy = taken.getOrPut(r) { java.util.BitSet() }
                var c = 0
                val cells = ArrayList<Placed>()
                for (cell in row.cells) {
                    while (busy[c]) c++
                    val span = cell.colSpan.coerceIn(1, 64)
                    val rs = cell.rowSpan.coerceIn(1, rows - r)
                    for (rr in r until r + rs) taken.getOrPut(rr) { java.util.BitSet() }.set(c, c + span)
                    cells.add(Placed(cell, r, c))
                    if (r + rs > spanEnd[r]) spanEnd[r] = r + rs
                    c += span
                    maxCol = max(maxCol, c)
                }
                out.add(cells)
            }
            placed = out
            cols = max(1, maxCol)
            headerRows = table.rows.takeWhile { it.header }.size.coerceAtMost(3)
            // Column widths: as wide as their text wants within the page; if even the narrowest
            // words don't fit, the table's text gets smaller (down to half size).
            val paint = TextPaint(FLAGS).apply { textSize = base * 0.92f }
            val natural = FloatArray(cols); val minimum = FloatArray(cols); val hint = FloatArray(cols)
            for (row in placed.take(400)) for (pc in row) {
                if (pc.cell.colSpan != 1 || pc.col >= cols) continue
                var nat = 0f; var mn = 0f
                for (p in pc.paragraphs) for (l in p.text.split('\n')) {
                    nat = max(nat, paint.measureText(l))
                    for (word in l.split(' ')) if (word.length > 1) mn = max(mn, paint.measureText(word))
                }
                natural[pc.col] = max(natural[pc.col], nat + 2 * pad)
                minimum[pc.col] = max(minimum[pc.col], min(mn, width * 0.5f) + 2 * pad)
                if (pc.cell.widthPt > 0) hint[pc.col] = max(hint[pc.col], pc.cell.widthPt * K)
            }
            val floor = 18f * K
            val mins = FloatArray(cols) { max(minimum[it], floor) }
            val minSum = mins.sum()
            scale = if (minSum > width) max(0.5f, width / minSum) else 1f
            val avail = width / scale
            val hints = hint.sum()
            val given = table.columnsPt.takeIf { it.size == cols && it.all { c -> c > 0f } }
            val w = when {
                // The table's own grid when the file gives it (fitted to the page if it is wider).
                given != null -> { val total = given.sum() * K; val f = if (total > avail) avail / total else 1f; FloatArray(cols) { given[it] * K * f } }
                // The document's own column widths when it gives them all and they fit.
                hints > 0 && hint.all { it > 0 } && hints <= avail * 1.02f -> FloatArray(cols) { max(mins[it], hint[it] * min(1f, avail / hints)) }
                else -> {
                    val pref = FloatArray(cols) { max(mins[it], min(natural[it], avail * 0.85f)) }
                    val prefSum = pref.sum()
                    when {
                        prefSum <= avail -> FloatArray(cols) { pref[it] + (avail - prefSum) * (pref[it] / prefSum) }
                        minSum <= avail -> FloatArray(cols) { c -> mins[c] + (avail - minSum) * ((pref[c] - mins[c]) / (prefSum - minSum).coerceAtLeast(1f)) }
                        else -> mins
                    }
                }
            }
            // Never wider than the page.
            val total = w.sum()
            val fitW = if (total * scale > width) width / (total * scale) else 1f
            colX = FloatArray(cols + 1); for (c in 0 until cols) colX[c + 1] = colX[c] + w[c] * scale * fitW
        }

        /** The row a group of rows starting at [from] must reach so no cell spanning rows is cut. */
        fun groupEnd(from: Int): Int { var e = from + 1; var r = from; while (r < e) { e = max(e, spanEnd[r]); r++ }; return min(e, rows) }
    }

    /** Text of blocks inside a table cell (nested tables row by row). */
    private fun flatten(blocks: List<Block>): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        for (b in blocks) when (b) {
            is Paragraph -> out.add(b)
            is Table -> for (r in b.rows) out.add(Paragraph(r.cells.flatMap { c -> flatten(c.blocks).flatMap { it.spans } + Span("   ") }))
            is Picture -> out.add(Paragraph(listOf(Span(b.alt?.takeIf { it.isNotBlank() } ?: tr("[Picture]")))))
            is Placeholder -> out.add(Paragraph(listOf(Span("[${b.label}]"))))
            else -> {}
        }
        return out
    }

    /** Rows [r0] until [r1] laid out: their heights, and each cell's place and text. */
    private class LaidRows(val rowY: FloatArray, val cells: List<LaidCell>)
    private class LaidCell(val x: Float, val y: Float, val w: Float, val h: Float, val layouts: List<StaticLayout>, val shading: Int, val header: Boolean)

    private val laidRows = object : LruCache<Triple<TableGeo, Int, Int>, LaidRows>(64) {}

    private fun laid(g: TableGeo, r0: Int, r1: Int): LaidRows {
        val key = Triple(g, r0, r1)
        laidRows.get(key)?.let { return it }
        val heights = FloatArray(r1 - r0) { base * g.scale * 1.4f }
        class Pending(val pc: TableGeo.Placed, val layouts: List<StaticLayout>, val h: Float)
        val pending = ArrayList<Pending>()
        for (r in r0 until r1) for (pc in g.placed[r]) {
            val c2 = min(g.cols, pc.col + pc.cell.colSpan.coerceAtLeast(1))
            val w = (g.colX[c2] - g.colX[pc.col] - 2 * g.pad).coerceAtLeast(8f).toInt()
            val header = g.table.rows[r].header
            val ls = pc.paragraphs.map { p ->
                val b = built(if (header && p.spans.none { it.style.bold }) Paragraph(p.spans.map { s -> s.copy(style = s.style.copy(bold = true)) }, align = p.align, listLabel = p.listLabel) else p, w, g.scale * 0.92f)
                b.layout
            }
            val h = ls.sumOf { it.height.toDouble() }.toFloat() + 2 * g.pad + (ls.size - 1).coerceAtLeast(0) * 2 * K
            pending.add(Pending(pc, ls, h))
            if (pc.cell.rowSpan <= 1) heights[r - r0] = max(heights[r - r0], h)
        }
        // Cells spanning rows: the last of their rows grows if they need more room.
        for (pd in pending) if (pd.pc.cell.rowSpan > 1) {
            val last = min(r1, pd.pc.row + pd.pc.cell.rowSpan) - 1
            val have = (pd.pc.row..last).sumOf { heights[it - r0].toDouble() }.toFloat()
            if (pd.h > have) heights[last - r0] += pd.h - have
        }
        val rowY = FloatArray(r1 - r0 + 1); for (i in heights.indices) rowY[i + 1] = rowY[i] + heights[i]
        val cells = pending.map { pd ->
            val c2 = min(g.cols, pd.pc.col + pd.pc.cell.colSpan.coerceAtLeast(1))
            val rEnd = min(r1, pd.pc.row + pd.pc.cell.rowSpan.coerceAtLeast(1))
            LaidCell(g.colX[pd.pc.col], rowY[pd.pc.row - r0], g.colX[c2] - g.colX[pd.pc.col], rowY[rEnd - r0] - rowY[pd.pc.row - r0],
                pd.layouts, pd.pc.cell.shading, g.table.rows[pd.pc.row].header)
        }
        return LaidRows(rowY, cells).also { laidRows.put(key, it) }
    }

    private fun table(t: Table) {
        val g = TableGeo(t)
        val gap = 4 * K
        var r = 0
        // The header rows are drawn once at the top; then again on each page the table goes on to.
        val head = if (g.headerRows in 1 until g.rows) laid(g, 0, g.headerRows) else null
        val headH = head?.rowY?.last() ?: 0f
        y += if (y > 0f) gap / 2 else 0f
        while (r < g.rows) {
            val end = g.groupEnd(r)
            val rows = laid(g, r, end)
            val h = rows.rowY.last()
            val repeat = head != null && r >= g.headerRows
            if (y > 0f && y + h > height) {
                newPage()
                if (repeat) { page.add(RowsInk(g, 0, g.headerRows, y, 0f, headH)); y += headH }
            }
            // A group of rows taller than a page goes on over the next pages, cut where they end.
            var from = 0f
            while (from < h) {
                // (Header rows filling a page leave no room: then the rows go on a page of their own.)
                if (height - y < base) newPage()
                val take = min(h - from, height - y)
                page.add(RowsInk(g, r, end, y, from, from + take))
                y += take; from += take
                if (from < h) { newPage(); if (repeat) { page.add(RowsInk(g, 0, g.headerRows, y, 0f, headH)); y += headH } }
            }
            r = end
        }
        y += gap / 2 + base * 0.3f
    }

    // ------------------------------------------------------------------ drawing
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC4CAD2.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.6f * K }
    private val footer = TextPaint(FLAGS).apply { color = GREY; textAlign = Paint.Align.CENTER }

    override fun draw(canvas: Canvas, i: Int, pxPerPt: Float) {
        val list = pages[i]
        canvas.save()
        canvas.scale(pxPerPt / K, pxPerPt / K)
        if (pages.size > 1) {
            footer.textSize = 9f * K
            canvas.drawText((i + 1).toString(), pageW * K / 2, (pageH - (mBottom * 0.5f).coerceIn(10f, 36f)) * K, footer)
        }
        canvas.translate(mLeft * K, mTop * K)
        for (ink in list) when (ink) {
            is BoxInk -> {
                boxPaint.color = ink.color
                if (ink.stroke > 0f) { boxPaint.style = Paint.Style.STROKE; boxPaint.strokeWidth = ink.stroke; canvas.drawLine(ink.l, ink.t, ink.r, ink.b, boxPaint) }
                else { boxPaint.style = Paint.Style.FILL; canvas.drawRect(ink.l, ink.t, ink.r, ink.b, boxPaint) }
            }
            is TextInk -> {
                val lay = built(ink.p, ink.width, ink.scale).layout
                val top = lay.getLineTop(ink.from).toFloat()
                val bottom = lay.getLineBottom(ink.to - 1).toFloat()
                canvas.save()
                canvas.clipRect(-ink.width.toFloat(), ink.y, 2f * ink.width, ink.y + bottom - top)
                canvas.translate(ink.x, ink.y - top)
                lay.draw(canvas)
                canvas.restore()
            }
            is PicInk -> {
                val b = bitmap(ink.ref, (ink.w * pxPerPt / K).toInt())
                if (b != null) canvas.drawBitmap(b, null, RectF(ink.l, ink.t, ink.l + ink.w, ink.t + ink.h), bmpPaint)
            }
            is LabelInk -> {
                val tp = TextPaint(FLAGS).apply { color = GREY; textSize = ink.size }
                val lay = StaticLayout.Builder.obtain(ink.text, 0, ink.text.length, tp, ink.w.toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
                canvas.save(); canvas.translate(ink.l, ink.t + (base * 1.6f - lay.height) / 2); lay.draw(canvas); canvas.restore()
            }
            is RowsInk -> rows(canvas, ink)
        }
        canvas.restore()
    }

    private fun rows(canvas: Canvas, ink: RowsInk) {
        val g = ink.t
        val laid = laid(g, ink.r0, ink.r1)
        canvas.save()
        canvas.clipRect(-1f * K, ink.y, width + 1f * K, ink.y + ink.to - ink.from)
        canvas.translate(0f, ink.y - ink.from)
        for (c in laid.cells) {
            if (c.y + c.h < ink.from || c.y > ink.to) continue
            val shade = if (c.shading != 0) c.shading or 0xFF000000.toInt() else if (c.header) SOFT else 0
            if (shade != 0) { boxPaint.style = Paint.Style.FILL; boxPaint.color = shade; canvas.drawRect(c.x, c.y, c.x + c.w, c.y + c.h, boxPaint) }
            var ty = c.y + g.pad
            for (l in c.layouts) { canvas.save(); canvas.translate(c.x + g.pad, ty); l.draw(canvas); canvas.restore(); ty += l.height + 2 * K }
            if (g.table.borders) canvas.drawRect(c.x, c.y, c.x + c.w, c.y + c.h, border)
        }
        canvas.restore()
    }

    // The layout pass comes after every property it uses: Kotlin runs initialisers in order.
    init {
        pages.add(page)
        val blocks = doc.blocks
        for ((i, b) in blocks.withIndex()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            when (b) {
                is Paragraph -> paragraph(b, blocks.getOrNull(i + 1))
                is Table -> if (b.rows.isNotEmpty()) table(b)
                is Picture -> picture(b)
                is PageBreak -> if (y > 0f) newPage()
                is Rule -> { need(12 * K); page.add(BoxInk(0f, y + 6 * K, width.toFloat(), y + 6 * K, LINE, 0.75f * K)); y += 12 * K }
                is Placeholder -> label("[${b.label}]")
            }
        }
        // A document ending in a page break leaves no empty page behind.
        if (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.size - 1)
    }

    override fun close() { bitmaps.evictAll(); layouts.evictAll(); laidRows.evictAll(); doc.close() }
}

// ====================================================================== spreadsheets
/**
 * A workbook's sheets on pages, as Excel prints them: each sheet's filled area from A1, 15 mm
 * margins; wider than the page, it turns to landscape and then shrinks (to half size at most) before
 * its columns go on over more pages. Frozen rows are repeated at the top of each page as titles.
 * Sheets with no borders get light grid lines so their cells can be told apart. Hidden and empty
 * sheets are left out.
 */
class SheetPages(private val book: Workbook, paper: MediaSize) : DocumentPages {
    private class Pg(val painter: SheetPainter, val landscape: Boolean, val scale: Float, val rs: Int, val re: Int, val cs: Int, val ce: Int,
                     val titleRows: Int, val grid: Boolean, val label: String)

    private val pw = DocumentPrint.pt(paper.widthMm)
    private val ph = DocumentPrint.pt(paper.heightMm)
    private val margin = DocumentPrint.pt(15f)
    private val foot = 12f
    private val pages = ArrayList<Pg>()

    override val count get() = pages.size
    override fun widthPt(i: Int) = if (pages[i].landscape) ph else pw
    override fun heightPt(i: Int) = if (pages[i].landscape) pw else ph

    init {
        for ((si, s) in book.sheets.withIndex()) {
            if (s.hidden || (s.cells.count == 0 && s.merges.isEmpty())) continue
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val rows = max(s.cells.maxRow + 1, s.merges.maxOfOrNull { it.r2 + 1 } ?: 0).coerceIn(1, 1_048_576)
            val cols = max(s.cells.maxCol + 1, s.merges.maxOfOrNull { it.c2 + 1 } ?: 0).coerceIn(1, 16384)
            val painter = SheetPainter(book, s, cols, rows)
            val totalW = painter.colX[cols]
            val portraitW = pw - 2 * margin
            val landscape = totalW > portraitW
            val w = if (landscape) ph - 2 * margin else portraitW
            val h = (if (landscape) pw else ph) - 2 * margin - foot
            val scale = if (totalW <= w) 1f else max(0.5f, w / totalW)
            val grid = s.gridLines && !painter.hasBorders(0, rows - 1)
            val name = s.name.ifBlank { tr("Sheet {0}", si + 1) }
            val fr = s.frozenRows.coerceIn(0, rows)
            val titleRows = if (fr > 0 && painter.rowY[fr] * scale < h * 0.25f) fr else 0
            val titleH = painter.rowY[titleRows] * scale
            // Columns that fit across a page, then rows down it ("down, then over", as Excel does).
            var c = 0
            while (c < cols) {
                var ce = c
                while (ce + 1 < cols && (painter.colX[ce + 2] - painter.colX[c]) * scale <= w) ce++
                var r = 0
                var first = true
                while (r < rows) {
                    val room = h - (if (!first && titleRows > 0) titleH else 0f)
                    var re = r
                    while (re + 1 < rows && (painter.rowY[re + 2] - painter.rowY[r]) * scale <= room) re++
                    pages.add(Pg(painter, landscape, scale, r, re, c, ce, if (first) 0 else titleRows, grid, name))
                    r = re + 1; first = false
                }
                c = ce + 1
            }
        }
    }

    private val footer = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = 0xFF6B7280.toInt(); textAlign = Paint.Align.CENTER }

    override fun draw(canvas: Canvas, i: Int, pxPerPt: Float) {
        val p = pages[i]
        val k = pxPerPt
        val ppp = p.scale * k
        val left = margin * k; var top = margin * k
        val w = widthPt(i) - 2 * margin; val h = heightPt(i) - 2 * margin - foot
        canvas.save()
        canvas.clipRect(left, top, left + w * k, top + h * k)
        val cx = p.painter.colX
        val lineW = max(1f, 0.75f * k); val gridW = max(1f, 0.4f * k)
        if (p.titleRows > 0) {
            p.painter.draw(canvas, 0, p.titleRows - 1, p.cs, p.ce, p.cs, left - cx[p.cs] * ppp, top, ppp, p.grid, lineW, gridW)
            top += p.painter.rowY[p.titleRows] * ppp
        }
        canvas.save()
        canvas.clipRect(left, top, left + w * k, margin * k + h * k)
        p.painter.draw(canvas, p.rs, p.re, p.cs, p.ce, p.cs, left - cx[p.cs] * ppp, top - p.painter.rowY[p.rs] * ppp, ppp, p.grid, lineW, gridW)
        canvas.restore()
        canvas.restore()
        footer.textSize = 8f * k
        val sameSheet = pages.count { it.painter === p.painter }
        val n = pages.subList(0, i).count { it.painter === p.painter } + 1
        canvas.drawText(if (sameSheet > 1) "${p.label} · $n" else p.label, widthPt(i) * k / 2, (heightPt(i) - margin * 0.6f) * k, footer)
    }

    override fun close() = book.close()
}

// ====================================================================== slides
/** A presentation's slides, one to a page at the slide's own shape; hidden slides are left out. */
class SlidePages(private val deck: Deck) : DocumentPages {
    private val renderer = SlideRenderer(deck)
    private val shown = deck.slides.indices.filter { !deck.slides[it].hidden }

    override val count get() = shown.size
    override fun widthPt(i: Int) = deck.widthPt
    override fun heightPt(i: Int) = deck.heightPt
    override fun draw(canvas: Canvas, i: Int, pxPerPt: Float) = renderer.draw(canvas, shown[i], pxPerPt)
    override fun close() { renderer.clear(); deck.close() }
}
