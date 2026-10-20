package com.localmediatools.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.OverScroller
import com.localmediatools.i18n.tr
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pages to show one under the other: PDF pages or slides. Sizes in points. */
interface PageSource : Closeable {
    val count: Int
    fun widthPt(i: Int): Float
    fun heightPt(i: Int): Float
    /** Draws page [i] into [bitmap] at [scale] pixels per point, moved left and up by [dx], [dy] pixels. Runs on the render thread. */
    fun render(i: Int, bitmap: Bitmap, scale: Float, dx: Float, dy: Float)
    /** A note drawn on the page ("Hidden slide"), or null. */
    fun badge(i: Int): String? = null
}

/** A search hit: on [page], at [rects] (points, from the page's top left; empty when the page draws its own marks). */
class PageHit(val page: Int, val rects: List<RectF>)

/** Finds text in a [PageSource]'s pages. */
interface PageSearch : Closeable {
    /** All hits of [query] in page order; [progress] gets pages done and the total. */
    fun find(query: String, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): List<PageHit>
    /** True when the pages draw the marks themselves (slides), so they are drawn again when [marks] changes. */
    val drawsMarks: Boolean get() = false
    /** Called on the render thread when the hits or the current one change. */
    fun marks(hits: List<PageHit>, current: Int) {}
    /** Bookmarks or slide titles: label and page. */
    fun outline(): List<Pair<String, Int>> = emptyList()
    override fun close() {}
}

/**
 * PDF pages and slides one under the other, zoomed by pinching or double-tapping. Every page is
 * drawn once at screen width; when zoomed in, the visible part is drawn again sharply once the
 * view stops moving, so memory stays bounded however large the pages are.
 */
@SuppressLint("ViewConstructor")
class PagesView(ctx: Context, private val source: PageSource, private val searcher: PageSearch?, private val host: ReaderHost, private val noun: PageNoun) : FrameLayout(ctx), ReaderContent {
    enum class PageNoun { PAGE, SLIDE }
    override val view: View get() = this
    private val column = Column(ctx)
    private val indicator = UI.text(ctx, "", TextStyle.CAPTION, Palette.TEXT).apply {
        background = Shapes.pill(ctx, 0xCC111418.toInt())
        setPadding(ctx.dp(12), ctx.dp(5), ctx.dp(12), ctx.dp(5))
        alpha = 0f
    }
    private val main = Handler(Looper.getMainLooper())

    init {
        setBackgroundColor(0xFF2B2F36.toInt())
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(indicator, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ctx.dp(20) })
    }

    private val hideIndicator = Runnable { indicator.animate().alpha(0f).setDuration(400).start() }

    private fun showIndicator(page: Int) {
        indicator.text = when (noun) {
            PageNoun.PAGE -> tr("Page {0} of {1}", page + 1, source.count)
            PageNoun.SLIDE -> tr("Slide {0} of {1}", page + 1, source.count)
        }
        indicator.animate().cancel(); indicator.alpha = 1f
        main.removeCallbacks(hideIndicator); main.postDelayed(hideIndicator, 1200)
    }

    val currentPage: Int get() = column.pageAtCenter()

    // ------------------------------------------------------------------ the column of pages
    private inner class Column(ctx: Context) : View(ctx) {
        val density = resources.displayMetrics.density
        val margin = 12 * density
        var zoom = 1f
        var ox = 0f; var oy = 0f
        private var fit = 1f
        private var tops = FloatArray(1)
        private var laidW = -1
        private val scroller = OverScroller(ctx)
        private val pagePaint = Paint().apply { color = Color.WHITE }
        private val shade = Paint().apply { color = 0x33000000 }
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val markPaint = Paint().apply { color = 0x66FFD400 }
        private val curPaint = Paint().apply { color = 0x88FF7A00.toInt() }
        private val badgePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12 * density }
        private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC6B7280.toInt() }

        // Rendering: one thread (PDF renderers can't be shared between threads), newest requests first.
        private val renderer = Executors.newSingleThreadExecutor { r -> Thread(r, "reader-pages") }
        private val generation = AtomicInteger()
        private val base = object : LruCache<Int, Bitmap>(((Runtime.getRuntime().maxMemory() / 5).coerceAtMost(80L shl 20)).toInt()) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
        private val pendingBase = HashSet<Int>()
        private var details: List<Detail> = emptyList()
        private var detailRequested = false

        fun layoutPages() {
            val w = width
            if (w <= 0) return
            laidW = w
            val maxW = (0 until source.count).maxOfOrNull { source.widthPt(it) } ?: 1f
            fit = (w - 2 * margin) / maxW.coerceAtLeast(1f)
            tops = FloatArray(source.count + 1)
            var y = margin
            for (i in 0 until source.count) { tops[i] = y; y += source.heightPt(i) * fit + margin }
            tops[source.count] = y
            base.evictAll(); details = emptyList()
            clamp()
        }

        val contentH get() = tops.last() * zoom
        val contentW get() = width * zoom
        private fun clamp() { ox = ox.coerceIn(0f, max(0f, contentW - width)); oy = oy.coerceIn(0f, max(0f, contentH - height)) }

        fun pageRect(i: Int, out: RectF): RectF {
            val pw = source.widthPt(i) * fit; val ph = source.heightPt(i) * fit
            val left = (width - pw) / 2
            out.set(left * zoom - ox, tops[i] * zoom - oy, (left + pw) * zoom - ox, (tops[i] + ph) * zoom - oy)
            return out
        }

        fun pageAt(y: Float): Int {
            val cy = (y + oy) / zoom
            var lo = 0; var hi = source.count - 1
            while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (tops[mid] <= cy) lo = mid else hi = mid - 1 }
            return lo.coerceIn(0, max(0, source.count - 1))
        }

        fun pageAtCenter() = if (source.count == 0) 0 else pageAt(height / 2f)

        fun scrollToPage(i: Int, rectInPage: RectF? = null) {
            if (laidW != width) layoutPages()
            val top = tops[i.coerceIn(0, source.count - 1)] * zoom
            oy = if (rectInPage == null) top - margin else {
                val y = top + rectInPage.top * fit * zoom
                val x = ((width - source.widthPt(i) * fit) / 2 + rectInPage.left * fit) * zoom
                if (x - ox < 0 || x - ox > width * 0.8f) ox = x - width * 0.2f
                y - height * 0.35f
            }
            clamp(); invalidate(); showIndicator(i)
        }

        private val tmp = RectF()
        private val src = Rect()

        override fun onDraw(canvas: Canvas) {
            if (source.count == 0) return
            if (laidW != width) layoutPages()
            val first = pageAt(0f); val last = pageAt(height.toFloat())
            for (i in first..last) {
                val r = pageRect(i, tmp)
                canvas.drawRect(r.left + 2 * density, r.top + 3 * density, r.right + 2 * density, r.bottom + 3 * density, shade)
                canvas.drawRect(r, pagePaint)
                val b = base.get(i)
                if (b != null) canvas.drawBitmap(b, null, r, bmpPaint) else requestBase(i)
                for (d in details) if (d.page == i && abs(d.zoom - zoom) < 0.001f) {
                    canvas.drawBitmap(d.bitmap, d.left - ox, d.top - oy, null)
                }
                val h = hits.withIndex().filter { it.value.page == i }
                for ((k, hit) in h) for (rr in hit.rects) {
                    val s = fit * zoom
                    canvas.drawRect(r.left + rr.left * s, r.top + rr.top * s, r.left + rr.right * s, r.top + rr.bottom * s, if (k == current) curPaint else markPaint)
                }
                source.badge(i)?.let { label ->
                    val tw = badgePaint.measureText(label)
                    val bx = r.left + 8 * density; val by = r.top + 8 * density
                    canvas.drawRoundRect(bx, by, bx + tw + 16 * density, by + 22 * density, 11 * density, 11 * density, badgeFill)
                    canvas.drawText(label, bx + 8 * density, by + 15.5f * density, badgePaint)
                }
            }
            if (zoom > 1.05f && !detailRequested && scroller.isFinished && !scaling) {
                detailRequested = true
                main.removeCallbacks(detailTask); main.postDelayed(detailTask, 140)
            }
        }

        private fun requestBase(i: Int) {
            if (!pendingBase.add(i)) return
            val gen = generation.get()
            val w = (source.widthPt(i) * fit).toInt().coerceAtLeast(1)
            val h = (source.heightPt(i) * fit).toInt().coerceAtLeast(1)
            queue {
                // Pages scrolled past before their turn are skipped.
                if (gen != generation.get() || !visible(i)) { main.post { pendingBase.remove(i) }; return@queue }
                val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE); source.render(i, it, fit, 0f, 0f) } } catch (_: Throwable) { null }
                main.post {
                    pendingBase.remove(i)
                    if (bmp != null && gen == generation.get()) { base.put(i, bmp); invalidate() }
                }
            }
        }

        @Volatile private var visibleRange = 0..0
        private fun visible(i: Int) = i in (visibleRange.first - 2)..(visibleRange.last + 2)

        private val detailTask = Runnable {
            detailRequested = false
            if (zoom <= 1.05f || source.count == 0) { details = emptyList(); return@Runnable }
            val gen = generation.get()
            val z = zoom
            val jobs = ArrayList<Triple<Int, RectF, RectF>>()
            for (i in pageAt(0f)..pageAt(height.toFloat())) {
                val r = RectF(pageRect(i, tmp))
                val vis = RectF(max(r.left, 0f), max(r.top, 0f), min(r.right, width.toFloat()), min(r.bottom, height.toFloat()))
                if (vis.width() < 1 || vis.height() < 1) continue
                jobs.add(Triple(i, r, vis))
            }
            val ox0 = ox; val oy0 = oy
            queue {
                val out = ArrayList<Detail>()
                for ((i, r, vis) in jobs) {
                    if (gen != generation.get()) return@queue
                    try {
                        val bmp = Bitmap.createBitmap(vis.width().toInt().coerceAtLeast(1), vis.height().toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        source.render(i, bmp, fit * z, vis.left - r.left, vis.top - r.top)
                        out.add(Detail(i, bmp, z, vis.left + ox0, vis.top + oy0))
                    } catch (_: Throwable) { }
                }
                main.post { if (gen == generation.get()) { details = out; invalidate() } }
            }
        }

        /** Something moved or zoomed: sharp parts drawn before no longer match. */
        private fun moved() {
            visibleRange = pageAt(0f)..pageAt(height.toFloat())
            if (details.isNotEmpty() && details.any { abs(it.zoom - zoom) > 0.001f }) details = emptyList()
            invalidate()
        }

        fun refresh() { generation.incrementAndGet(); pendingBase.clear(); base.evictAll(); details = emptyList(); invalidate() }

        // ------------------------------------------------------------------ touch
        var scaling = false
        private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                ox += dx; oy += dy; clamp(); moved(); showIndicator(pageAtCenter()); return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                scroller.fling(ox.toInt(), oy.toInt(), -vx.toInt(), -vy.toInt(), 0, max(0f, contentW - width).toInt(), 0, max(0f, contentH - height).toInt())
                postInvalidateOnAnimation(); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { zoomAround(if (zoom < 1.5f) 2.5f / zoom else 1f / zoom, e.x, e.y); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { showIndicator(pageAt(e.y)); return true }
        })
        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean { zoomAround(d.scaleFactor, d.focusX, d.focusY); return true }
            override fun onScaleEnd(d: ScaleGestureDetector) { scaling = false; invalidate() }
        })

        fun zoomAround(f: Float, fx: Float, fy: Float) {
            val next = (zoom * f).coerceIn(1f, 6f)
            val k = next / zoom
            if (abs(k - 1f) < 0.0005f) return
            zoom = next
            ox = (ox + fx) * k - fx; oy = (oy + fy) * k - fy
            clamp(); moved()
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) { ox = scroller.currX.toFloat(); oy = scroller.currY.toFloat(); clamp(); moved(); postInvalidateOnAnimation() }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaler.onTouchEvent(e)
            if (!scaler.isInProgress) gestures.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) invalidate()
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val page = if (oldh > 0) pageAt(oldh / 2f) else 0
            layoutPages()
            visibleRange = pageAt(0f)..pageAt(h.toFloat())
            if (oldh > 0) scrollToPage(page)
        }

        /**
         * Stops drawing and runs [last] (closing the pages) on the render thread after the page being
         * drawn, if any: a PDF renderer must not be closed while it draws, and the screen mustn't wait.
         */
        fun shutdown(last: () -> Unit) {
            generation.incrementAndGet()
            queue(last)
            renderer.shutdown()
            base.evictAll(); details = emptyList()
        }

        /** Queues [block] on the render thread; ignored once the view is closed. */
        private fun queue(block: () -> Unit) { try { renderer.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) { } }

        fun drawn(i: Int) = base.get(i) != null

        /** Runs [block] after pages already queued are drawn (on the render thread, for the source's own state). */
        fun onRenderThread(block: () -> Unit) = queue(block)
    }

    // ------------------------------------------------------------------ ReaderContent
    private var hits: List<PageHit> = emptyList()
    private var current = -1
    private var searchUpdate: ((SearchStatus) -> Unit)? = null
    private var searchThread: Thread? = null

    override fun search(query: String, update: (SearchStatus) -> Unit) {
        searchUpdate = update
        searchThread?.interrupt()
        val s = searcher
        if (s == null) { update(SearchStatus(0, 0)); host.toast(tr("This file has no text to search.")); return }
        if (query.isBlank()) { clearSearch(); update(SearchStatus(0, 0)); return }
        update(SearchStatus(0, 0, busy = true))
        lateinit var t: Thread
        t = Thread {
            val found = try {
                s.find(query, { done, total -> main.post { if (searchThread === t) update(SearchStatus(0, 0, busy = true, progress = tr("Searching page {0} of {1}…", done, total))) } }, { t.isInterrupted })
            } catch (e: Throwable) { emptyList() }
            main.post {
                if (searchThread !== t) return@post
                hits = found
                val page = column.pageAtCenter()
                current = found.indexOfFirst { it.page >= page }.let { if (it < 0 && found.isNotEmpty()) 0 else it }
                showHit()
            }
        }
        searchThread = t
        t.start()
    }

    private fun showHit() {
        searcher?.let { s -> column.onRenderThread { s.marks(hits, current) } }
        if (searcher?.drawsMarks == true) column.refresh()
        hits.getOrNull(current)?.let { h -> column.scrollToPage(h.page, h.rects.firstOrNull()) }
        column.invalidate()
        searchUpdate?.invoke(SearchStatus(if (current >= 0) current + 1 else 0, hits.size))
    }

    override fun searchStep(forward: Boolean) {
        if (hits.isEmpty()) return
        current = if (forward) (current + 1) % hits.size else (current - 1 + hits.size) % hits.size
        showHit()
    }

    override fun clearSearch() {
        searchThread?.interrupt(); searchThread = null
        val had = hits.isNotEmpty()
        hits = emptyList(); current = -1
        searcher?.let { s -> column.onRenderThread { s.marks(emptyList(), -1) } }
        if (had && searcher?.drawsMarks == true) column.refresh()
        column.invalidate()
    }

    private val outline: List<Pair<String, Int>> by lazy {
        val o = try { searcher?.outline() ?: emptyList() } catch (_: Throwable) { emptyList() }
        o.ifEmpty { (0 until source.count).map { (if (noun == PageNoun.SLIDE) tr("Slide {0}", it + 1) else tr("Page {0}", it + 1)) to it } }
    }

    override val contentsTitle: String get() = if (noun == PageNoun.SLIDE) tr("Slides") else tr("Contents")
    override fun contents() = outline
    override fun goTo(target: Int) = column.scrollToPage(target)
    override fun zoomBy(factor: Float) = column.zoomAround(factor, width / 2f, height / 2f)
    override fun facts() = listOf(if (noun == PageNoun.SLIDE) com.localmediatools.i18n.trn(source.count, "{0} slide", "{0} slides") else com.localmediatools.i18n.trn(source.count, "{0} page", "{0} pages"))

    override fun close() {
        searchThread?.interrupt()
        main.removeCallbacksAndMessages(null)
        val s = searcher
        column.shutdown {
            try { s?.close() } catch (_: Exception) { }
            try { source.close() } catch (_: Exception) { }
        }
    }

    /** Whether page [i] has been drawn (for tests). */
    fun isDrawn(i: Int): Boolean = column.drawn(i)
}

/** Part of a page drawn sharply at a zoom, at [left], [top] in the zoomed column. */
private class Detail(val page: Int, val bitmap: Bitmap, val zoom: Float, val left: Float, val top: Float)
