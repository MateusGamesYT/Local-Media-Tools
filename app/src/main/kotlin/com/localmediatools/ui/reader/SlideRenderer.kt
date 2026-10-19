package com.localmediatools.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.util.LruCache
import com.localmediatools.docs.core.Align
import com.localmediatools.docs.core.Anchor
import com.localmediatools.docs.core.Block
import com.localmediatools.docs.core.Deck
import com.localmediatools.docs.core.Geometry
import com.localmediatools.docs.core.Paragraph
import com.localmediatools.docs.core.Shape
import com.localmediatools.docs.core.Slide
import com.localmediatools.docs.core.Table
import com.localmediatools.docs.core.TextBody
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Draws slides: backgrounds, shapes with their fill and outline, pictures (cropped as in the file),
 * tables and text boxes with bullets, alignment and the file's shrink-to-fit. Fonts are the phone's,
 * so lines can break a little differently than in PowerPoint.
 */
class SlideRenderer(private val deck: Deck) {
    private val pictures = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L shl 20)).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val unreadable = HashSet<String>()

    /** Search marks: the text searched and which hit is the current one (slide, hit on that slide). */
    @Volatile var query: String? = null
    @Volatile var current: Pair<Int, Int>? = null

    /** The slide's paragraphs in the order hits are counted. */
    fun paragraphs(slide: Slide): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        for (s in slide.shapes) {
            s.text?.paragraphs?.let { out.addAll(it) }
            s.table?.let { t -> for (r in t.rows) for (c in r.cells) collect(c.blocks, out) }
        }
        return out
    }

    private fun collect(blocks: List<Block>, out: MutableList<Paragraph>) {
        for (b in blocks) when (b) { is Paragraph -> out.add(b); is Table -> for (r in b.rows) for (c in r.cells) collect(c.blocks, out); else -> {} }
    }

    /** Hits of [q] on [slide] (per paragraph, in [paragraphs] order). */
    fun hits(slide: Slide, q: String): Int = paragraphs(slide).sumOf { RichText.find(label(it) + it.text, q, I18n.locale).size }

    private fun label(p: Paragraph) = p.listLabel?.let { "$it " } ?: ""

    fun draw(canvas: Canvas, index: Int, scale: Float) {
        val slide = deck.slides[index]
        val w = deck.widthPt * scale; val h = deck.heightPt * scale
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.drawColor(if (slide.background != 0) slide.background or 0xFF000000.toInt() else Color.WHITE)
        slide.backgroundImage?.let { ref -> picture(ref, w.toInt())?.let { b -> drawCover(canvas, b, RectF(0f, 0f, w, h)) } }
        // Where each paragraph's hits start, so the current one can be told apart.
        val q = query
        val offsets = IdentityHashMap<Paragraph, Int>()
        var currentHit = -1
        if (!q.isNullOrEmpty()) {
            var n = 0
            for (p in paragraphs(slide)) { offsets[p] = n; n += RichText.find(label(p) + p.text, q, I18n.locale).size }
            current?.let { (s, k) -> if (s == index) currentHit = k }
        }
        for (s in slide.shapes) shape(canvas, s, scale, q, offsets, currentHit)
        canvas.restore()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun shape(canvas: Canvas, s: Shape, k: Float, q: String?, offsets: IdentityHashMap<Paragraph, Int>, currentHit: Int) {
        val x = s.xPt * k; val y = s.yPt * k; val w = s.wPt * k; val h = s.hPt * k
        if (w < 0 || h < 0 || (w == 0f && h == 0f && s.geometry != Geometry.LINE)) return
        canvas.save()
        if (s.rotation != 0f || s.flipH || s.flipV) {
            canvas.rotate(s.rotation, x + w / 2, y + h / 2)
            canvas.scale(if (s.flipH) -1f else 1f, if (s.flipV) -1f else 1f, x + w / 2, y + h / 2)
        }
        val path = outline(s.geometry, x, y, w, h)
        if (s.fill != 0 && Color.alpha(s.fill) > 0) { fill.color = s.fill; canvas.drawPath(path, fill) }
        s.image?.let { ref ->
            val b = picture(ref, w.toInt())
            if (b != null) {
                val c = s.crop
                val src = if (c != null) Rect((b.width * c[0].coerceIn(0f, 0.99f)).toInt(), (b.height * c[1].coerceIn(0f, 0.99f)).toInt(),
                    (b.width * (1 - c[2].coerceIn(0f, 0.99f))).toInt(), (b.height * (1 - c[3].coerceIn(0f, 0.99f))).toInt()) else null
                canvas.save()
                if (s.geometry == Geometry.ELLIPSE || s.geometry == Geometry.ROUND_RECT) canvas.clipPath(path)
                if (src == null || src.width() > 0 && src.height() > 0) canvas.drawBitmap(b, src, RectF(x, y, x + w, y + h), bmpPaint)
                canvas.restore()
            } else placeholder(canvas, x, y, w, h, tr("Picture"), k)
        }
        if (s.lineColor != 0 && s.lineWidthPt > 0f && Color.alpha(s.lineColor) > 0) {
            stroke.color = s.lineColor; stroke.strokeWidth = max(1f, s.lineWidthPt * k)
            canvas.drawPath(path, stroke)
        }
        s.table?.let { table(canvas, it, s.tableText, x, y, w, h, k, q, offsets, currentHit) }
        s.placeholder?.let { placeholder(canvas, x, y, w, h, it, k) }
        // Text is never mirrored, even in a flipped shape.
        if (s.text != null) {
            if (s.flipH || s.flipV) canvas.scale(if (s.flipH) -1f else 1f, if (s.flipV) -1f else 1f, x + w / 2, y + h / 2)
            text(canvas, s.text, x, y, w, h, k, q, offsets, currentHit)
        }
        canvas.restore()
    }

    private fun outline(g: Geometry, x: Float, y: Float, w: Float, h: Float): Path = Path().apply {
        when (g) {
            Geometry.ELLIPSE -> addOval(RectF(x, y, x + w, y + h), Path.Direction.CW)
            Geometry.ROUND_RECT -> { val r = min(w, h) * 0.1667f; addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW) }
            Geometry.LINE -> { moveTo(x, y); lineTo(x + w, y + h) }
            Geometry.TRIANGLE -> { moveTo(x + w / 2, y); lineTo(x + w, y + h); lineTo(x, y + h); close() }
            Geometry.DIAMOND -> { moveTo(x + w / 2, y); lineTo(x + w, y + h / 2); lineTo(x + w / 2, y + h); lineTo(x, y + h / 2); close() }
            Geometry.RIGHT_ARROW -> {
                val head = min(h * 0.5f, w * 0.5f)
                moveTo(x, y + h * 0.25f); lineTo(x + w - head, y + h * 0.25f); lineTo(x + w - head, y); lineTo(x + w, y + h / 2)
                lineTo(x + w - head, y + h); lineTo(x + w - head, y + h * 0.75f); lineTo(x, y + h * 0.75f); close()
            }
            else -> addRect(RectF(x, y, x + w, y + h), Path.Direction.CW)
        }
    }

    private val phFill = Paint().apply { color = 0xFFF1F3F5.toInt() }
    private val phStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC4CAD3.toInt(); style = Paint.Style.STROKE }
    private val phText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7280.toInt(); textAlign = Paint.Align.CENTER }

    private fun placeholder(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, k: Float) {
        canvas.drawRect(x, y, x + w, y + h, phFill)
        phStroke.strokeWidth = max(1f, k * 0.75f)
        canvas.drawRect(x, y, x + w, y + h, phStroke)
        phText.textSize = (14 * k).coerceAtMost(h * 0.4f).coerceAtLeast(1f)
        canvas.drawText(label, x + w / 2, y + h / 2 + phText.textSize / 3, phText)
    }

    private fun drawCover(canvas: Canvas, b: Bitmap, dst: RectF) {
        val s = max(dst.width() / b.width, dst.height() / b.height)
        val sw = dst.width() / s; val sh = dst.height() / s
        val l = ((b.width - sw) / 2).toInt(); val t = ((b.height - sh) / 2).toInt()
        canvas.drawBitmap(b, Rect(l, t, (l + sw).toInt(), (t + sh).toInt()), dst, bmpPaint)
    }

    private fun picture(ref: String, wantW: Int): Bitmap? {
        if (ref in unreadable) return null
        pictures.get(ref)?.let { if (it.width >= min(wantW, 2048) * 0.6f || it.width >= 2048) return it }
        val bytes = try { deck.media.bytes(ref) } catch (_: Throwable) { null }
        val b = bytes?.let { bs ->
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bs, 0, bs.size, o)
                if (o.outWidth <= 0) null else {
                    var sample = 1
                    val target = min(max(wantW, 64), 2048)
                    while (o.outWidth / (sample * 2) >= target && sample < 64) sample *= 2
                    BitmapFactory.decodeByteArray(bs, 0, bs.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (_: Throwable) { null }
        }
        if (b == null) { unreadable.add(ref); return null }
        pictures.put(ref, b)
        return b
    }

    // ------------------------------------------------------------------ text
    private fun layouts(body: TextBody, width: Float, k: Float, q: String?, offsets: IdentityHashMap<Paragraph, Int>, currentHit: Int, scale: Float = body.fontScale): List<Pair<StaticLayout, Paragraph>> =
        body.paragraphs.map { p -> layout(p, body, width, k, scale, q, offsets, currentHit) to p }

    private fun layout(p: Paragraph, body: TextBody, width: Float, k: Float, scale: Float, q: String?, offsets: IdentityHashMap<Paragraph, Int>, currentHit: Int): StaticLayout {
        val sb = RichText.paragraph(p, k, body.defaultColor, 0, 0xFF1A5FD0.toInt(), null, scale)
        val sizePx = (p.spans.firstOrNull { it.style.sizePt > 0 }?.style?.sizePt ?: body.defaultSizePt) * k * scale
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (body.defaultSizePt * k * scale).coerceAtLeast(1f); color = body.defaultColor or 0xFF000000.toInt() }
        val indent = p.indentPt * k; val first = (p.indentPt + p.firstLinePt) * k
        if (p.listLabel != null) {
            val label = p.listLabel + " "
            sb.insert(0, label)
            // The bullet takes the size and colour of the paragraph's first run.
            p.spans.firstOrNull()?.let { s -> RichText.style(sb, 0, label.length, s.style.copy(underline = false, strike = false), k, body.defaultColor, 0, scale) }
            val lw = TextPaint(paint).apply { textSize = sizePx.coerceAtLeast(1f) }.measureText(label)
            sb.setSpan(LeadingMarginSpan.Standard(max(0f, min(first, indent)).toInt(), max(indent, max(0f, first) + lw).toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        } else if (indent > 0 || first > 0) sb.setSpan(LeadingMarginSpan.Standard(max(0f, first).toInt(), max(0f, indent).toInt()), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (!q.isNullOrEmpty()) {
            val found = RichText.find(sb, q, I18n.locale)
            if (found.isNotEmpty()) RichText.mark(sb, found, if (currentHit >= 0) currentHit - (offsets[p] ?: 0) else -1)
        }
        val text: CharSequence = if (sb.isEmpty()) SpannableStringBuilder(" ").also { e -> e.setSpan(android.text.style.AbsoluteSizeSpan(sizePx.toInt().coerceAtLeast(1)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } else sb
        val align = when (p.align) { Align.CENTER -> Layout.Alignment.ALIGN_CENTER; Align.END -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(align).setLineSpacing(0f, 1f).setIncludePad(false)
            .apply { if (p.align == Align.JUSTIFY) setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD) }
            .build()
    }

    private fun text(canvas: Canvas, body: TextBody, x: Float, y: Float, w: Float, h: Float, k: Float, q: String?, offsets: IdentityHashMap<Paragraph, Int>, currentHit: Int) {
        val l = x + body.insetLeftPt * k; val t = y + body.insetTopPt * k
        val iw = (w - (body.insetLeftPt + body.insetRightPt) * k).let { if (body.wrap) it else max(it, 4000f * k) }
        val ih = h - (body.insetTopPt + body.insetBottomPt) * k
        if (iw <= 1f) return
        val laid = layouts(body, iw, k, q, offsets, currentHit)
        val total = laid.sumOf { (lay, p) -> (lay.height + (p.spaceBeforePt + p.spaceAfterPt) * k * body.fontScale).toDouble() }.toFloat()
        var yy = when (body.anchor) { Anchor.TOP -> t; Anchor.MIDDLE -> t + (ih - total) / 2; Anchor.BOTTOM -> t + ih - total }
        val dx = if (!body.wrap) when (laid.firstOrNull()?.second?.align) {
            Align.CENTER -> (w - (body.insetLeftPt + body.insetRightPt) * k - iw) / 2
            Align.END -> (w - (body.insetLeftPt + body.insetRightPt) * k - iw)
            else -> 0f
        } else 0f
        for ((lay, p) in laid) {
            yy += p.spaceBeforePt * k * body.fontScale
            canvas.save(); canvas.translate(l + dx, yy); lay.draw(canvas); canvas.restore()
            yy += lay.height + p.spaceAfterPt * k * body.fontScale
        }
    }

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF7F8792.toInt() }

    private fun table(canvas: Canvas, t: Table, style: TextBody?, x: Float, y: Float, w: Float, h: Float, k: Float, q: String?, offsets: IdentityHashMap<Paragraph, Int>, currentHit: Int) {
        val cols = t.columns.coerceAtLeast(1)
        val body = style ?: TextBody(emptyList(), defaultSizePt = 14f)
        // Column widths from the first row's cells when given, else equal.
        val widths = FloatArray(cols) { w / cols }
        t.rows.firstOrNull()?.let { r ->
            var c = 0
            val given = r.cells.all { it.widthPt > 0 }
            if (given) for (cell in r.cells) { val span = cell.colSpan.coerceAtLeast(1); for (j in 0 until span) if (c + j < cols) widths[c + j] = cell.widthPt * k / span; c += span }
        }
        val pad = 5f * k
        val heights = FloatArray(t.rows.size) { h / t.rows.size.coerceAtLeast(1) }
        val cellLayouts = t.rows.map { r ->
            var c = 0
            r.cells.map { cell ->
                val span = cell.colSpan.coerceAtLeast(1)
                val cw = (c until min(cols, c + span)).sumOf { widths[it].toDouble() }.toFloat()
                val col = c; c += span
                val paras = ArrayList<Paragraph>().also { collect(cell.blocks, it) }
                Triple(col, cw, paras.map { p -> layout(p, body, (cw - 2 * pad).coerceAtLeast(1f), k, body.fontScale, q, offsets, currentHit) })
            }
        }
        for ((ri, row) in cellLayouts.withIndex()) for ((_, _, ls) in row) heights[ri] = max(heights[ri], ls.sumOf { it.height.toDouble() }.toFloat() + 2 * pad)
        grid.strokeWidth = max(1f, 0.75f * k)
        var yy = y
        for ((ri, row) in cellLayouts.withIndex()) {
            for ((ci, triple) in row.withIndex()) {
                val (col, cw, ls) = triple
                val cx = x + (0 until col.coerceAtMost(cols)).sumOf { widths[it].toDouble() }.toFloat()
                val cell = t.rows[ri].cells[ci]
                if (cell.shading != 0 && Color.alpha(cell.shading) > 0) { fill.color = cell.shading; canvas.drawRect(cx, yy, cx + cw, yy + heights[ri], fill) }
                var ty = yy + pad
                for (lay in ls) { canvas.save(); canvas.translate(cx + pad, ty); lay.draw(canvas); canvas.restore(); ty += lay.height }
                if (t.borders) canvas.drawRect(cx, yy, cx + cw, yy + heights[ri], grid)
            }
            yy += heights[ri]
        }
    }

    fun clear() = pictures.evictAll()
}

/** Slides as pages for [PagesView]. */
class DeckPages(private val deck: Deck) : PageSource, PageSearch {
    private val renderer = SlideRenderer(deck)
    override val count get() = deck.slides.size
    override fun widthPt(i: Int) = deck.widthPt
    override fun heightPt(i: Int) = deck.heightPt
    override fun render(i: Int, bitmap: Bitmap, scale: Float, dx: Float, dy: Float) {
        val c = Canvas(bitmap)
        c.translate(-dx, -dy)
        renderer.draw(c, i, scale)
    }
    override fun badge(i: Int): String? = if (deck.slides[i].hidden) tr("Hidden slide") else null

    override fun find(query: String, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): List<PageHit> {
        lastQuery = query
        val out = ArrayList<PageHit>()
        for ((i, s) in deck.slides.withIndex()) {
            if (cancelled()) break
            repeat(renderer.hits(s, query)) { out.add(PageHit(i, emptyList())) }
        }
        return out
    }

    override val drawsMarks get() = true

    override fun marks(hits: List<PageHit>, current: Int) {
        val h = hits.getOrNull(current)
        renderer.query = if (hits.isEmpty()) null else lastQuery
        renderer.current = h?.let { it.page to hits.subList(0, current).count { o -> o.page == it.page } }
    }

    @Volatile private var lastQuery: String? = null

    override fun outline(): List<Pair<String, Int>> = deck.slides.mapIndexed { i, s ->
        val title = renderer.paragraphs(s).firstOrNull { it.text.isNotBlank() }?.text?.trim()?.replace('\n', ' ')?.take(80)
        (if (title != null) tr("{0}. {1}", i + 1, title) else tr("Slide {0}", i + 1)) to i
    }

    fun notes(i: Int): String = deck.slides.getOrNull(i)?.notes?.joinToString("\n") { it.text }?.trim() ?: ""

    override fun close() { renderer.clear(); deck.close() }
}
