package com.localmediatools.ui.gallery

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.gallery.GFace
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.ui.ChipRow
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

/**
 * Every face found in the gallery — named, in unnamed groups, on their own (including small or
 * blurry ones that are never grouped automatically) and ignored ones — or, with [personId], the
 * faces of one person for review. Tap a face to name or correct it; long-press to see its photo.
 */
class FacesScreen(activity: MainActivity, private val personId: Long? = null) : Screen(activity) {
    enum class Filter(val label: String) { ALL("All"), NAMED("Named"), UNNAMED("Unnamed groups"), ALONE("On their own"), IGNORED("Ignored") }

    private var filter = Filter.ALL
    private var faces: List<GFace> = emptyList()
    private var names: Map<Long, String> = emptyMap()
    private lateinit var list: ListView
    private lateinit var count: TextView
    private val adapter = Adapter()
    private val cols = 4

    override fun createView(): View {
        val root = UI.vertical(ctx).apply { setBackgroundColor(Palette.BG) }
        count = UI.text(ctx, "", TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(0, 0, ctx.dp(10), 0) }
        root.addView(TopBar(this, if (personId == null) "All faces" else "Review faces", count))
        if (personId == null) {
            root.addView(ChipRow(ctx, Filter.entries, { it.label }, filter) { filter = it; load() }.apply {
                setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            }, lp().apply { bottomMargin = ctx.dp(8) })
        } else {
            root.addView(UI.text(ctx, "Tap a face that isn't this person to remove it.", TextStyle.CAPTION).apply { setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(8)) })
        }
        list = ListView(ctx).apply {
            divider = null; dividerHeight = 0; selector = Shapes.rounded(ctx, Color.TRANSPARENT, 0f)
            clipToPadding = false; setPadding(ctx.dp(10), 0, ctx.dp(10), ctx.dp(40))
            adapter = this@FacesScreen.adapter
        }
        root.addView(list, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        observe()
        return root
    }

    @OptIn(FlowPreview::class)
    private val watch = IndexWatch(this) { load() }
    private var loadJob: kotlinx.coroutines.Job? = null

    private fun observe() = watch.start()

    override fun onShow() { watch.shown() }

    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        val f = filter
        val (list, n) = withContext(Dispatchers.IO) {
            val db = GalleryDb.get(ctx)
            val named = db.people(includeHidden = true).filter { it.named }.associate { it.id to it.name!! }
            val where = when {
                personId != null -> "f.person_id = $personId AND f.ignored = 0"
                f == Filter.ALL -> "f.ignored = 0"
                f == Filter.NAMED -> "f.ignored = 0 AND f.person_id IN (SELECT id FROM people WHERE name IS NOT NULL AND name != '')"
                f == Filter.UNNAMED -> "f.ignored = 0 AND f.person_id IN (SELECT id FROM people WHERE name IS NULL OR name = '')"
                f == Filter.ALONE -> "f.ignored = 0 AND f.person_id IS NULL"
                else -> "f.ignored = 1"
            }
            db.faces(where) to named
        }
        faces = list; names = n
        count.text = "${list.size}"
        adapter.notifyDataSetChanged()
    }

    private fun open(f: GFace) {
        if (f.ignored) {
            android.app.AlertDialog.Builder(activity).setTitle("Show this face in People again?")
                .setPositiveButton("Show") { _, _ ->
                    scope.launch { withContext(Dispatchers.IO) { GalleryDb.get(ctx).setIgnored(f.id, false) }; GalleryIndex.regroupNow(ctx) }
                }.setNegativeButton("Cancel", null).show()
            return
        }
        FaceSheet.show(activity, f) { load() }
    }

    private fun openPhoto(f: GFace) = scope.launch {
        val m = withContext(Dispatchers.IO) { GalleryDb.get(ctx).mediaById(f.mediaId) } ?: return@launch
        push(ViewerScreen(activity, listOf(m), 0, highlightFace = f.id))
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = (faces.size + cols - 1) / cols
        override fun getItem(p: Int) = p
        override fun getItemId(p: Int) = p.toLong()
        override fun isEnabled(p: Int) = false
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? Row) ?: Row(ctx)
            row.bind(p * cols)
            return row
        }
    }

    private inner class Row(ctx: Context) : LinearLayout(ctx) {
        private val cells = List(cols) { Cell(ctx) }
        init {
            orientation = HORIZONTAL
            for (c in cells) addView(c, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
            setPadding(0, dp(4), 0, dp(8))
        }
        fun bind(start: Int) { for ((i, c) in cells.withIndex()) c.bind(faces.getOrNull(start + i)) }
    }

    private inner class Cell(ctx: Context) : LinearLayout(ctx) {
        private val img = object : ImageView(ctx) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w) }.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = Shapes.circle(Palette.SURFACE_2); clipToOutline = true
        }
        private val label = UI.text(ctx, "", TextStyle.CAPTION).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val ring = FrameLayout(ctx)
        private var face: GFace? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            ring.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            isClickable = true; isFocusable = true
            setOnClickListener { face?.let { open(it) } }
            setOnLongClickListener { face?.let { openPhoto(it) }; true }
        }

        fun bind(f: GFace?) {
            face = f
            visibility = if (f == null) INVISIBLE else VISIBLE
            if (f == null) return
            GalleryThumbs.face(context, f, 192, img)
            val name = f.personId?.let { names[it] }
            label.text = when {
                f.ignored -> "Ignored"
                name != null -> name
                f.personId != null -> "Unnamed"
                !f.good -> "Small or blurry"
                else -> "On their own"
            }
            label.setTextColor(if (name != null) Palette.TEXT else Palette.TEXT_3)
            ring.setPadding(dp(2), dp(2), dp(2), dp(2))
            ring.background = Shapes.circle(if (name != null) Palette.ACCENT else Color.TRANSPARENT)
            contentDescription = "${label.text}. Tap to name or correct, long-press to open the photo."
        }
    }
}
