package com.localmediatools.ui.gallery

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.gallery.GMedia
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.gallery.GalleryRepo
import com.localmediatools.gallery.core.MediaFilter
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Screen
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.TopBar
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A grid of photos from one album, category or kind, with selection actions. */
class MediaListScreen(activity: MainActivity, private val title: String, private val source: Source) : Screen(activity) {
    sealed class Source {
        class Album(val id: String) : Source()
        class Category(val key: String) : Source()
        class Kind(val kind: MediaFilter) : Source()
    }

    private lateinit var grid: MediaGrid
    private lateinit var count: TextView
    private lateinit var bar: SelectionBar

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val col = UI.vertical(ctx)
        count = UI.text(ctx, "", TextStyle.CAPTION, Palette.TEXT_3)
        col.addView(TopBar(this, title, count.apply { setPadding(0, 0, ctx.dp(10), 0) }))
        val note = if (source is Source.Category) UI.text(ctx, "Found on this phone by on-device AI.", TextStyle.CAPTION, Palette.TEXT_3).apply {
            setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(4))
        } else null
        grid = MediaGrid(ctx, { list, i -> push(ViewerScreen(activity, list, i)) }, bottomPad = ctx.dp(110), header = note)
        if (source is Source.Album) grid.grouping = MediaGrid.Grouping.DAY
        col.addView(grid, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col)
        bar = SelectionBar(this, grid)
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(ctx.dp(14), 0, ctx.dp(14), ctx.dp(16))
        })
        observe()
        return root
    }

    @OptIn(FlowPreview::class)
    private fun observe() = scope.launch { GalleryIndex.changes.debounce(500).collect { load() } }

    private fun load() = scope.launch {
        val items = withContext(Dispatchers.IO) {
            when (val s = source) {
                is Source.Album -> GalleryRepo.album(ctx, s.id)
                is Source.Category -> GalleryRepo.byCategory(ctx, s.key)
                is Source.Kind -> GalleryRepo.byKind(ctx, s.kind)
            }
        }
        grid.setItems(items)
        count.text = "${items.size}"
    }

    override fun onBack(): Boolean {
        if (grid.selecting) { grid.endSelecting(); return true }
        return false
    }
}

/** Floating actions for selected grid items: share, use in a tool, move to trash. */
class SelectionBar(private val screen: Screen, private val grid: MediaGrid) : LinearLayout(screen.ctx) {
    private val label = UI.text(context, "", TextStyle.SUBTITLE)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Shapes.rounded(context, Palette.SURFACE_2, 22f, Palette.STROKE_2)
        elevation = dp(10).toFloat()
        setPadding(dp(6), dp(6), dp(6), dp(6))
        addView(UI.iconButton(context, R.drawable.ic_close, "Cancel selection") { grid.endSelecting() })
        addView(label, lp(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(UI.iconButton(context, R.drawable.ic_check, "Select all") { grid.selectAll() })
        addView(UI.iconButton(context, R.drawable.ic_share, "Share") { GalleryActions.share(screen.activity, chosen()) })
        addView(UI.iconButton(context, R.drawable.ic_wand, "Use in a tool") { GalleryActions.chooseTool(screen.activity, chosen()) })
        addView(UI.iconButton(context, R.drawable.ic_trash, "Move to trash") { GalleryActions.trash(screen.activity, chosen()) { grid.endSelecting() } })
        visibility = GONE
        grid.onSelection = { sel ->
            visibility = if (grid.selecting) VISIBLE else GONE
            label.text = if (sel.isEmpty()) "Select items" else "${sel.size} selected"
        }
    }

    private fun chosen(): List<GMedia> = grid.items().filter { it.id in grid.selected }
}
