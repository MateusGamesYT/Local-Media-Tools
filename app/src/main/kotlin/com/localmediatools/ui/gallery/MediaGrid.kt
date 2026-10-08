package com.localmediatools.ui.gallery

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SectionIndexer
import android.widget.TextView
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.gallery.GMedia
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import java.util.Calendar
import java.util.Locale

/**
 * The photo grid: square thumbnails grouped by day or month, newest first. Pinch to show more or
 * fewer columns, drag the scrollbar to jump through time, long-press to select.
 */
class MediaGrid(
    ctx: Context,
    private val onOpen: (List<GMedia>, Int) -> Unit,
    /** Space kept free under the last row (for the floating bar). */
    bottomPad: Int = ctx.dp(120),
    topPad: Int = 0,
    private val header: View? = null,
) : FrameLayout(ctx) {
    private var items: List<GMedia> = emptyList()
    private var rows: List<Row> = emptyList()
    private var sectionList: List<Pair<String, Int>> = emptyList()
    val selected = LinkedHashSet<Long>()
    var selecting = false; private set
    var onSelection: ((Set<Long>) -> Unit)? = null
    var grouping = Grouping.DAY
    private val prefs = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)
    var columns = prefs.getInt("columns", 4).coerceIn(MIN_COLS, MAX_COLS); private set
    private val gap = ctx.dp(2)
    private val list = PinchList(ctx)
    private val adapter = GridAdapter()

    enum class Grouping { DAY, MONTH, NONE }

    private sealed class Row {
        class Header(val title: String, val sub: String) : Row()
        class Cells(val start: Int, val count: Int) : Row()
    }

    init {
        list.divider = null
        list.dividerHeight = 0
        list.selector = Shapes.rounded(ctx, Color.TRANSPARENT, 0f)
        list.clipToPadding = false
        list.setPadding(0, topPad, 0, bottomPad)
        list.isVerticalScrollBarEnabled = false
        list.isFastScrollEnabled = true
        list.overScrollMode = OVER_SCROLL_NEVER
        list.cacheColorHint = Color.TRANSPARENT
        header?.let { list.addHeaderView(it, null, false) }
        list.adapter = adapter
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setItems(list: List<GMedia>) {
        items = list
        selected.retainAll(list.mapTo(HashSet()) { it.id })
        rebuild()
    }

    fun items() = items

    fun scrollToTop() = list.setSelection(0)

    private fun rebuild() {
        val out = ArrayList<Row>()
        val secs = ArrayList<Pair<String, Int>>()
        if (grouping == Grouping.NONE) {
            var i = 0
            while (i < items.size) { val n = minOf(columns, items.size - i); out.add(Row.Cells(i, n)); i += n }
        } else {
            val cal = Calendar.getInstance()
            var i = 0
            var lastSection = ""
            while (i < items.size) {
                val key = groupKey(cal, items[i].taken)
                var j = i
                while (j < items.size && groupKey(cal, items[j].taken) == key) j++
                val (title, sub) = groupTitle(cal, items[i].taken, j - i)
                val month = monthLabel(cal, items[i].taken)
                if (month != lastSection) { secs.add(month to out.size); lastSection = month }
                out.add(Row.Header(title, sub))
                var k = i
                while (k < j) { val n = minOf(columns, j - k); out.add(Row.Cells(k, n)); k += n }
                i = j
            }
        }
        rows = out
        sectionList = secs
        adapter.notifyDataSetChanged()
    }

    private fun groupKey(cal: Calendar, t: Long): Int {
        cal.timeInMillis = t
        return if (grouping == Grouping.MONTH) cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH)
        else cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun monthLabel(cal: Calendar, t: Long): String {
        cal.timeInMillis = t
        return String.format(Locale.getDefault(), "%tb %d", cal, cal.get(Calendar.YEAR))
    }

    private fun groupTitle(cal: Calendar, t: Long, n: Int): Pair<String, String> {
        val count = "$n ${if (n == 1) "item" else "items"}"
        cal.timeInMillis = t
        if (grouping == Grouping.MONTH) return String.format(Locale.getDefault(), "%tB %d", cal, cal.get(Calendar.YEAR)) to count
        val now = Calendar.getInstance()
        val sameYear = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR)
        val today = sameYear && now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
        now.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
        val title = when {
            today -> "Today"
            yesterday -> "Yesterday"
            sameYear -> String.format(Locale.getDefault(), "%ta, %<te %<tb", cal)
            else -> String.format(Locale.getDefault(), "%te %<tb %<tY", cal)
        }
        return title to count
    }

    fun setColumns(n: Int) {
        val c = n.coerceIn(MIN_COLS, MAX_COLS)
        if (c == columns) return
        // Keep the first visible item in view.
        val first = firstVisibleItem()
        columns = c
        prefs.edit().putInt("columns", c).apply()
        rebuild()
        if (first >= 0) {
            val r = rows.indexOfFirst { it is Row.Cells && first in it.start until it.start + it.count }
            if (r >= 0) list.setSelection(r + list.headerViewsCount)
        }
    }

    private fun firstVisibleItem(): Int {
        val p = list.firstVisiblePosition - list.headerViewsCount
        for (k in maxOf(0, p) until rows.size) { val r = rows[k]; if (r is Row.Cells) return r.start }
        return -1
    }

    fun startSelecting(first: Long?) {
        selecting = true
        first?.let { selected.add(it) }
        adapter.notifyDataSetChanged()
        onSelection?.invoke(selected)
    }

    fun endSelecting() {
        selecting = false
        selected.clear()
        adapter.notifyDataSetChanged()
        onSelection?.invoke(selected)
    }

    fun selectAll() { items.forEach { selected.add(it.id) }; adapter.notifyDataSetChanged(); onSelection?.invoke(selected) }

    private fun toggle(m: GMedia) {
        if (!selected.remove(m.id)) selected.add(m.id)
        adapter.notifyDataSetChanged()
        onSelection?.invoke(selected)
    }

    private inner class PinchList(ctx: Context) : ListView(ctx) {
        private var acc = 1f
        private val detector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { acc = 1f; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                acc *= d.scaleFactor
                if (acc > 1.28f) { acc = 1f; if (columns > MIN_COLS) { setColumns(columns - 1); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
                else if (acc < 0.78f) { acc = 1f; if (columns < MAX_COLS) { setColumns(columns + 1); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } }
                return true
            }
        })

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            detector.onTouchEvent(ev)
            if (detector.isInProgress || ev.pointerCount > 1) {
                // Don't scroll or tap while pinching.
                if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                }
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private inner class GridAdapter : BaseAdapter(), SectionIndexer {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is Row.Header) 0 else 1
        override fun isEnabled(p: Int) = false

        override fun getSections(): Array<Any> = sectionList.map { it.first }.toTypedArray()
        override fun getPositionForSection(s: Int) = sectionList.getOrNull(s.coerceIn(0, maxOf(0, sectionList.size - 1)))?.second ?: 0
        override fun getSectionForPosition(p: Int): Int {
            var k = 0
            for ((i, s) in sectionList.withIndex()) if (s.second <= p) k = i else break
            return k
        }

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View = when (val r = rows[p]) {
            is Row.Header -> {
                val v = (convert as? LinearLayout) ?: UI.horizontal(context).apply {
                    setPadding(dp(16), dp(18), dp(16), dp(8))
                    addView(UI.text(context, "", TextStyle.SUBTITLE).apply { tag = "t" }, lp(0, LayoutParams.WRAP_CONTENT, 1f))
                    addView(UI.text(context, "", TextStyle.CAPTION, Palette.TEXT_3).apply { tag = "s" })
                }
                v.findViewWithTag<TextView>("t").text = r.title
                v.findViewWithTag<TextView>("s").text = r.sub
                v
            }
            is Row.Cells -> {
                val row = (convert as? CellRow)?.takeIf { it.cols == columns } ?: CellRow(context, columns)
                row.bind(r.start, r.count)
                row
            }
        }
    }

    private inner class CellRow(ctx: Context, val cols: Int) : LinearLayout(ctx) {
        private val cells = ArrayList<Cell>()

        init {
            orientation = HORIZONTAL
            for (i in 0 until cols) {
                val c = Cell(ctx)
                cells.add(c)
                addView(c, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = gap })
            }
            setPadding(0, 0, 0, gap)
        }

        fun bind(start: Int, count: Int) {
            for ((i, c) in cells.withIndex()) {
                if (i < count) { c.visibility = VISIBLE; c.bind(start + i) } else c.visibility = INVISIBLE
            }
        }
    }

    private inner class Cell(ctx: Context) : FrameLayout(ctx) {
        private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Palette.SURFACE_2) }
        private val badge = UI.text(ctx, "", TextStyle.CAPTION, Color.WHITE).apply {
            setShadowLayer(dp(3).toFloat(), 0f, 0f, 0x99000000.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        private val check = FrameLayout(ctx)
        private var index = -1

        init {
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
            check.addView(UI.iconView(ctx, R.drawable.ic_check, Color.WHITE, 14), LayoutParams(dp(14), dp(14), Gravity.CENTER))
            addView(check, LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); rightMargin = dp(6) })
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val m = items.getOrNull(index) ?: return@setOnClickListener
                if (selecting) toggle(m) else onOpen(items, index)
            }
            setOnLongClickListener {
                val m = items.getOrNull(index) ?: return@setOnLongClickListener false
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!selecting) startSelecting(m.id) else toggle(m)
                true
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
        }

        fun bind(i: Int) {
            index = i
            val m = items[i]
            val px = (resources.displayMetrics.widthPixels / columns)
            GalleryThumbs.load(context, m, px, image)
            badge.visibility = if (m.video || m.gif) VISIBLE else GONE
            badge.text = when { m.video -> "▶ " + Format.duration(m.durationMs); m.gif -> "GIF"; else -> "" }
            val sel = m.id in selected
            check.visibility = if (selecting) VISIBLE else GONE
            check.background = if (sel) Shapes.circle(Palette.ACCENT) else GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(0x33000000); setStroke(dp(2), Color.WHITE)
            }
            check.getChildAt(0).visibility = if (sel) VISIBLE else INVISIBLE
            image.alpha = if (selecting && sel) 0.72f else 1f
            image.scaleX = if (selecting && sel) 0.9f else 1f
            image.scaleY = image.scaleX
            contentDescription = (if (m.video) "Video" else "Photo") + ", " + android.text.format.DateFormat.getMediumDateFormat(context).format(m.taken) +
                (if (selecting) if (sel) ", selected" else ", not selected" else "")
        }
    }

    companion object {
        const val MIN_COLS = 2
        const val MAX_COLS = 7
    }
}
