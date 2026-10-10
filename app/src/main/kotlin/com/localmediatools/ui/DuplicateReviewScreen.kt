package com.localmediatools.ui

import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.core.Format
import com.localmediatools.vision.DupResultGroup
import com.localmediatools.vision.DuplicateScanner
import kotlin.math.abs

/**
 * Duplicate groups one at a time, photos large: tap a photo to keep or remove it, then "Next"
 * (or swipe). "Keep all" and "Suggested" decide the group and move on.
 */
class DuplicateReviewScreen(activity: MainActivity, private val groups: List<DupResultGroup>, private var index: Int) : Screen(activity) {
    private lateinit var top: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var grid: LinearLayout
    private lateinit var status: TextView
    private lateinit var next: ButtonView
    private val cells = ArrayList<PhotoCell>()

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val col = UI.vertical(ctx)
        top = FrameLayout(ctx)
        col.addView(top)
        status = UI.text(ctx, "", TextStyle.CAPTION, Palette.TEXT_2).apply { setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(8)) }
        col.addView(status)
        scroll = ScrollView(ctx).apply { clipToPadding = false; setPadding(ctx.dp(12), 0, ctx.dp(12), ctx.dp(120)) }
        grid = UI.vertical(ctx)
        scroll.addView(grid)
        col.addView(scroll, lp(MATCH, 0, 1f))
        root.addView(col)
        val bar = UI.horizontal(ctx).apply {
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            background = Shapes.rounded(ctx, Palette.SURFACE, 24f, Palette.STROKE)
            elevation = ctx.dp(8).toFloat()
        }
        bar.addView(UI.ghostButton(ctx, "Keep all") { current()?.let { DupChoices.keepAll(it) }; advance() }, lp(0, WRAP, 1f))
        bar.addView(UI.ghostButton(ctx, "Suggested") { current()?.let { DupChoices.keepSuggested(it) }; advance() }, lp(0, WRAP, 1f))
        next = UI.primaryButton(ctx, "Next") { advance() }
        bar.addView(next, lp(0, WRAP, 1.2f))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(ctx.dp(10), 0, ctx.dp(10), ctx.dp(10)) })
        // Swipe left for the next group, right for the previous one.
        val swipe = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || abs(vx) < abs(vy) * 1.5f || abs(e2.x - e1.x) < ctx.dp(80)) return false
                if (vx < 0) advance() else back()
                return true
            }
        })
        scroll.setOnTouchListener { _, e -> swipe.onTouchEvent(e); false }
        bind()
        return root
    }

    private fun current() = groups.getOrNull(index)

    private fun advance() {
        if (index >= groups.size - 1) { pop(); return }
        index++; bind()
    }

    private fun back() { if (index > 0) { index--; bind() } }

    private fun bind() {
        val g = current() ?: run { pop(); return }
        top.removeAllViews()
        top.addView(TopBar(this, "Group ${index + 1} of ${groups.size}"))
        val sw = activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density
        val cellDp = ((sw - 24 - 12) / 2).toInt().coerceAtLeast(120)
        while (cells.size < g.photos.size) cells.add(PhotoCell(ctx, cellDp))
        grid.removeAllViews()
        for (row in g.photos.indices.chunked(2)) {
            val r = UI.horizontal(ctx, Gravity.TOP)
            for (i in row) {
                // Cells are reused from group to group: take each out of the previous group's row first.
                (cells[i].parent as? android.view.ViewGroup)?.removeView(cells[i])
                r.addView(cells[i], LinearLayout.LayoutParams(0, ctx.dp(cellDp), 1f).apply { leftMargin = ctx.dp(3); rightMargin = ctx.dp(3) })
            }
            if (row.size == 1) r.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(r, lp().apply { bottomMargin = ctx.dp(6) })
        }
        grid.addView(UI.text(ctx, "Tap a photo to keep or remove it · long-press to open it · swipe for the next group", TextStyle.CAPTION, Palette.TEXT_3).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = ctx.dp(8) })
        bindCells()
        scroll.scrollTo(0, 0)
        next.label = if (index >= groups.size - 1) "Done" else "Next"
    }

    private fun bindCells() {
        val g = current() ?: return
        val suggestion = DupChoices.suggestion(g)
        for ((i, p) in g.photos.withIndex()) cells[i].bind(this, g, p, suggestion) { bindCells() }
        val going = g.photos.filter { it.uri in DupChoices.remove }
        val all = groups.flatMap { it.photos }.filter { it.uri in DupChoices.remove }
        status.text = "${g.kind.label} · ${g.photos.size} photos · ${if (going.isEmpty()) "all kept" else "${going.size} to remove (${Format.bytes(going.sumOf { it.size })})"}" +
            "  —  ${all.size} selected in total"
    }

    override fun onShow() {
        // Photos may have gone to the trash meanwhile.
        if (::grid.isInitialized && (DuplicateScanner.state.value as? com.localmediatools.vision.DupScanState.Done)?.groups?.none { DupChoices.key(it) == current()?.let(DupChoices::key) } == true) pop()
    }
}
