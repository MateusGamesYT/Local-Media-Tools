package com.localmediatools.ui.gallery

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.localmediatools.app.R
import com.localmediatools.gallery.GMedia
import com.localmediatools.gallery.GPerson
import com.localmediatools.gallery.EngineMode
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.gallery.GalleryRepo
import com.localmediatools.gallery.core.Category
import com.localmediatools.gallery.core.MediaFilter
import com.localmediatools.ui.MainShell
import com.localmediatools.ui.Palette
import com.localmediatools.ui.ProgressBarView
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Gallery tab: every photo and video on the phone, with albums, people and things found by
 * the on-device AI, and search ("dog", "Sophie and John", "beach 2023").
 */
class GalleryTab(private val shell: MainShell) {
    private val activity = shell.activity
    private val ctx = shell.ctx
    private lateinit var root: FrameLayout
    private lateinit var body: FrameLayout
    private lateinit var segments: Segments
    private lateinit var status: IndexStatusBar
    private var selectBar: SelectionBar? = null
    private var pane = 0
    private var grid: MediaGrid? = null
    private var loadJob: Job? = null
    private val panes = HashMap<Int, View>()
    private lateinit var partial: View

    fun build(): View {
        root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val col = UI.vertical(ctx)
        // Header: title, search and menu.
        val head = UI.horizontal(ctx).apply { setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(8), ctx.dp(4)) }
        head.addView(UI.text(ctx, "Gallery", TextStyle.DISPLAY).apply { isAccessibilityHeading = true }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(UI.iconButton(ctx, R.drawable.ic_search, "Search photos") { openSearch() })
        head.addView(UI.iconButton(ctx, R.drawable.ic_more, "Gallery options") { menu() })
        col.addView(head)
        // A search pill too: search is the point of all this.
        col.addView(UI.horizontal(ctx).apply {
            background = Shapes.clickable(ctx, Palette.SURFACE, 18f, Palette.STROKE_2)
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            minimumHeight = ctx.dp(46)
            addView(UI.iconView(ctx, R.drawable.ic_search, Palette.TEXT_3, 18))
            addView(UI.text(ctx, "Search people, things, places…", TextStyle.BODY, Palette.TEXT_3).apply { setPadding(ctx.dp(10), 0, 0, 0) })
            isClickable = true; isFocusable = true
            contentDescription = "Search photos"
            setOnClickListener { openSearch() }
        }, lp().apply { leftMargin = ctx.dp(18); rightMargin = ctx.dp(18); topMargin = ctx.dp(6) })
        segments = Segments(ctx, listOf("Photos", "Albums", "People", "Things")) { show(it) }
        col.addView(segments, lp().apply { leftMargin = ctx.dp(18); rightMargin = ctx.dp(18); topMargin = ctx.dp(12) })
        status = IndexStatusBar(ctx) { statusDetails() }
        col.addView(status, lp().apply { leftMargin = ctx.dp(18); rightMargin = ctx.dp(18); topMargin = ctx.dp(10) })
        // Android 14+: the user may have allowed only some photos; offer to choose more.
        partial = UI.note(ctx, "Showing only the photos you allowed. Tap to allow more.", UI.NoteKind.INFO).apply {
            isClickable = true; isFocusable = true
            contentDescription = "Allow more photos"
            setOnClickListener { activity.ensureMediaAccess { ok -> if (ok) GalleryIndex.start(ctx) } }
            visibility = View.GONE
        }
        col.addView(partial, lp().apply { leftMargin = ctx.dp(18); rightMargin = ctx.dp(18); topMargin = ctx.dp(8) })
        body = FrameLayout(ctx)
        col.addView(body, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ctx.dp(6) })
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        observe()
        show(0)
        return root
    }

    /** The index changed while the tab wasn't in front; reload when it is again. */
    private var dirty = false

    private fun inFront() = activity.started.value && activity.navigator.top === shell && shell.tab == MainShell.TAB_GALLERY

    private fun changed() { if (inFront()) reload() else dirty = true }

    @OptIn(FlowPreview::class)
    private fun observe() {
        shell.scope.launch { GalleryIndex.state.collect { status.render(it) } }
        shell.scope.launch { GalleryIndex.changes.debounce(400).collect { changed() } }
        // Finishing a pass may change nothing (an empty library) but still decides what to show.
        shell.scope.launch { GalleryIndex.state.map { it.phase == GalleryIndex.Phase.DONE }.distinctUntilChanged().collect { if (it) changed() } }
        shell.scope.launch { activity.started.collect { if (it && dirty && inFront()) { dirty = false; reload() } } }
    }

    /** Called when the tab becomes visible: check access, then refresh the index. */
    fun onShow() {
        partial.visibility = if (activity.hasPartialMediaAccess()) View.VISIBLE else View.GONE
        if (!activity.hasMediaAccess()) { showPermission(); return }
        if (panes.isEmpty() || body.childCount == 0 || body.getChildAt(0).tag == "permission") show(pane)
        else if (dirty) { dirty = false; reload() }
        GalleryIndex.refresh(ctx)
    }

    private fun show(i: Int) {
        pane = i
        if (segments.selected != i) segments.select(i)
        if (!activity.hasMediaAccess()) { showPermission(); return }
        body.removeAllViews()
        val v = panes.getOrPut(i) {
            when (i) {
                0 -> MediaGrid(ctx, { list, idx -> open(list, idx) }).also { g ->
                    grid = g
                    selectBar?.let { root.removeView(it) }
                    selectBar = SelectionBar(shell, g).also { bar ->
                        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                            setMargins(ctx.dp(14), 0, ctx.dp(14), ctx.dp(96))
                        })
                    }
                }
                1 -> ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
                2 -> ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
                else -> ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
            }
        }
        (v.parent as? FrameLayout)?.removeView(v)
        body.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (i != 0) grid?.takeIf { it.selecting }?.endSelecting()
        reload()
    }

    private fun reload() {
        if (!activity.hasMediaAccess()) return
        loadJob?.cancel()
        val p = pane
        loadJob = shell.scope.launch {
            when (p) {
                0 -> {
                    val items = withContext(Dispatchers.IO) { GalleryRepo.all(ctx) }
                    grid?.setItems(items)
                    // Only say "no photos" once the library has been read; until then the grid stays.
                    val ph = GalleryIndex.state.value.phase
                    showEmptyPhotos(items.isEmpty() && (ph == GalleryIndex.Phase.DONE || ph == GalleryIndex.Phase.PAUSED))
                }
                1 -> {
                    val data = withContext(Dispatchers.IO) { albumData() }
                    (panes[1] as? ScrollView)?.let { renderAlbums(it, data) }
                }
                2 -> {
                    val data = withContext(Dispatchers.IO) { peopleData() }
                    (panes[2] as? ScrollView)?.let { renderPeople(it, data) }
                }
                3 -> {
                    val data = withContext(Dispatchers.IO) { thingsData() }
                    (panes[3] as? ScrollView)?.let { renderThings(it, data) }
                }
            }
        }
    }

    /** Shows or removes the "no photos" message over the (empty) grid. */
    private fun showEmptyPhotos(empty: Boolean) {
        val g = grid ?: return
        if (pane != 0) return
        val current = (0 until body.childCount).map { body.getChildAt(it) }.firstOrNull { it.tag == "empty" }
        if (empty && current == null) {
            body.addView(emptyState(R.drawable.ic_image, "No photos or videos yet", "Photos and videos you take or save on this phone will show up here.").apply { tag = "empty" })
        } else if (!empty && current != null) body.removeView(current)
        g.visibility = if (empty) View.INVISIBLE else View.VISIBLE
    }

    private fun emptyState(icon: Int, title: String, text: String): View = UI.vertical(ctx).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ctx.dp(32), ctx.dp(64), ctx.dp(32), ctx.dp(32))
        addView(UI.iconTile(ctx, icon, Palette.ACCENT, 56, 28))
        addView(UI.text(ctx, title, TextStyle.TITLE).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(16), 0, 0) })
        addView(UI.text(ctx, text, TextStyle.BODY_2).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, 0) })
    }

    private fun showPermission() {
        body.removeAllViews()
        panes.clear(); grid = null
        val col = UI.vertical(ctx).apply { tag = "permission"; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ctx.dp(28), ctx.dp(48), ctx.dp(28), ctx.dp(140)) }
        col.addView(UI.gradientTile(ctx, R.drawable.ic_gallery, Palette.BRAND, 64, 32))
        col.addView(UI.text(ctx, "Your photos, organised on this phone", TextStyle.TITLE).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(18), 0, 0) })
        col.addView(UI.text(ctx, "Allow access to see your photos and videos here. The app finds people, pets, places and things in them so you can search for \"dog\" or \"Sophie\" — entirely on this phone. Nothing is uploaded: the app never connects to servers on the internet.", TextStyle.BODY_2).apply {
            gravity = Gravity.CENTER; setPadding(0, ctx.dp(10), 0, 0)
        })
        col.addView(UI.primaryButton(ctx, "Allow access") {
            activity.ensureMediaAccess { ok -> if (ok) { show(pane); GalleryIndex.start(ctx) } }
        }, lp().apply { topMargin = ctx.dp(22) })
        body.addView(ScrollView(ctx).apply { tag = "permission"; addView(col) })
    }

    private fun open(list: List<GMedia>, index: Int) = shell.push(ViewerScreen(activity, list, index))

    private fun openSearch(initial: String? = null) = shell.push(SearchScreen(activity, initial))

    /** Back closes selection mode first. */
    fun onBack(): Boolean {
        if (grid?.selecting == true) { grid?.endSelecting(); return true }
        return false
    }

    // ------------------------------------------------------------------ albums
    private class AlbumData(val albums: List<Triple<String, String, Pair<Int, GMedia?>>>, val special: List<Triple<MediaFilter, Int, GMedia?>>)

    private fun albumData(): AlbumData {
        val db = GalleryDb.get(ctx)
        val albums = db.albums().map { (id, name, info) -> Triple(id, name, info.first to db.mediaById(info.second)) }
        val special = listOf(MediaFilter.VIDEOS, MediaFilter.FAVOURITES, MediaFilter.SCREENSHOTS).mapNotNull { k ->
            val l = GalleryRepo.byKind(ctx, k)
            if (l.isEmpty()) null else Triple(k, l.size, l.first())
        }
        return AlbumData(albums, special)
    }

    private fun renderAlbums(scroll: ScrollView, d: AlbumData) {
        scroll.removeAllViews()
        val col = UI.vertical(ctx).apply { setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(130)) }
        if (d.albums.isEmpty() && d.special.isEmpty()) { col.addView(emptyState(R.drawable.ic_folder, "No albums yet", "Folders with photos or videos appear here.")); scroll.addView(col); return }
        val tiles = ArrayList<View>()
        for ((k, n, cover) in d.special) {
            val name = when (k) { MediaFilter.VIDEOS -> "Videos"; MediaFilter.FAVOURITES -> "Favourites"; else -> "Screenshots" }
            tiles.add(AlbumTile(ctx, name, n, cover) { shell.push(MediaListScreen(activity, name, MediaListScreen.Source.Kind(k))) })
        }
        for ((id, name, info) in d.albums) {
            tiles.add(AlbumTile(ctx, name, info.first, info.second) { shell.push(MediaListScreen(activity, name, MediaListScreen.Source.Album(id))) })
        }
        gridOf(col, tiles, 2)
        scroll.addView(col)
    }

    // ------------------------------------------------------------------ people
    private class PeopleData(val people: List<Pair<GPerson, com.localmediatools.gallery.GFace?>>, val faces: Int)

    private fun peopleData(): PeopleData {
        val db = GalleryDb.get(ctx)
        val people = db.people().filter { it.mediaCount > 0 && (it.named || it.faceCount >= 2) }
        val faces = db.visibleFaceCount()
        return PeopleData(people.map { it to GalleryRepo.coverFace(ctx, it) }, faces)
    }

    private fun renderPeople(scroll: ScrollView, d: PeopleData) {
        scroll.removeAllViews()
        val col = UI.vertical(ctx).apply { setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(130)) }
        col.addView(UI.horizontal(ctx).apply {
            background = Shapes.clickable(ctx, Palette.SURFACE, 18f, Palette.STROKE)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            addView(UI.iconTile(ctx, R.drawable.ic_tool_faceblur, Palette.ACCENT, 38, 20))
            addView(UI.titled(ctx, "All faces", "${d.faces} faces found · named, unnamed and on their own").apply { setPadding(ctx.dp(12), 0, 0, 0) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(UI.iconView(ctx, R.drawable.ic_chevron, Palette.TEXT_3, 18))
            isClickable = true; isFocusable = true
            setOnClickListener { shell.push(FacesScreen(activity)) }
        }, lp().apply { bottomMargin = ctx.dp(14); leftMargin = ctx.dp(4); rightMargin = ctx.dp(4) })
        val named = d.people.filter { it.first.named }
        val unnamed = d.people.filter { !it.first.named }
        if (unnamed.isNotEmpty()) {
            // The quick way through every unnamed group, keyboard ready.
            col.addView(UI.horizontal(ctx).apply {
                background = Shapes.clickable(ctx, Palette.ACCENT_DARK, 18f, Palette.ACCENT)
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(12), ctx.dp(12))
                addView(UI.iconTile(ctx, R.drawable.ic_add, Palette.ACCENT, 38, 20))
                addView(UI.titled(ctx, "Name people", "${unnamed.size} ${if (unnamed.size == 1) "group" else "groups"} to name, one after another").apply { setPadding(ctx.dp(12), 0, 0, 0) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(UI.iconView(ctx, R.drawable.ic_chevron, Palette.TEXT_2, 18))
                isClickable = true; isFocusable = true
                contentDescription = "Name people, ${unnamed.size} groups to name"
                setOnClickListener { shell.push(NamePeopleScreen(activity)) }
            }, lp().apply { bottomMargin = ctx.dp(14); leftMargin = ctx.dp(4); rightMargin = ctx.dp(4) })
        }
        if (d.people.isEmpty()) {
            val st = GalleryIndex.state.value
            col.addView(emptyState(R.drawable.ic_tool_faceblur, if (st.working) "Finding people…" else "No people yet",
                if (st.working) "Faces are being found and grouped on this phone. People appear here as soon as the same face shows up in a few photos."
                else "When the same person appears in several photos, they show up here so you can name them."))
        }
        if (named.isNotEmpty()) {
            col.addView(UI.label(ctx, "Named"), lp().apply { leftMargin = ctx.dp(6); bottomMargin = ctx.dp(8) })
            gridOf(col, named.map { (p, f) -> PersonTile(ctx, p, f) { shell.push(PersonScreen(activity, p.id)) } }, 3)
        }
        if (unnamed.isNotEmpty()) {
            col.addView(UI.label(ctx, "Add names"), lp().apply { leftMargin = ctx.dp(6); topMargin = ctx.dp(18); bottomMargin = ctx.dp(8) })
            gridOf(col, unnamed.map { (p, f) -> PersonTile(ctx, p, f) { shell.push(PersonScreen(activity, p.id)) } }, 3)
        }
        scroll.addView(col)
    }

    // ------------------------------------------------------------------ things
    private class ThingsData(val groups: List<Pair<String, List<Triple<Category, Int, GMedia?>>>>)

    private fun thingsData(): ThingsData {
        val counts = GalleryRepo.categoryCounts(ctx)
        val cats = GalleryRepo.categories(ctx).filter { (counts[it.key] ?: 0) > 0 }
        val groups = cats.groupBy { it.group }.map { (g, list) ->
            g to list.sortedByDescending { counts[it.key] ?: 0 }.map { Triple(it, counts[it.key] ?: 0, GalleryRepo.cover(ctx, it.key)) }
        }.sortedByDescending { g -> g.second.sumOf { it.second } }
        return ThingsData(groups)
    }

    private fun renderThings(scroll: ScrollView, d: ThingsData) {
        scroll.removeAllViews()
        val col = UI.vertical(ctx).apply { setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(130)) }
        if (d.groups.isEmpty()) {
            val st = GalleryIndex.state.value
            col.addView(emptyState(R.drawable.ic_sparkle, if (st.working) "Looking at your photos…" else "Nothing recognised yet",
                if (st.working) "Pets, food, places and things appear here as photos are analysed on this phone."
                else "Pets, food, vehicles, places and more appear here when photos show them."))
        }
        for ((g, list) in d.groups) {
            col.addView(UI.label(ctx, g), lp().apply { leftMargin = ctx.dp(6); topMargin = ctx.dp(14); bottomMargin = ctx.dp(8) })
            gridOf(col, list.map { (c, n, cover) -> AlbumTile(ctx, c.name, n, cover) { shell.push(MediaListScreen(activity, c.name, MediaListScreen.Source.Category(c.key))) } }, 3)
        }
        scroll.addView(col)
    }

    private fun gridOf(col: LinearLayout, tiles: List<View>, n: Int) {
        for (chunk in tiles.chunked(n)) {
            val row = UI.horizontal(ctx, Gravity.TOP)
            for (t in chunk) row.addView(t, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(4); rightMargin = ctx.dp(4) })
            repeat(n - chunk.size) { row.addView(View(ctx), lp(0, 1, 1f).apply { leftMargin = ctx.dp(4); rightMargin = ctx.dp(4) }) }
            col.addView(row, lp().apply { bottomMargin = ctx.dp(12) })
        }
    }

    // ------------------------------------------------------------------ menu and status
    private fun menu() {
        val paused = GalleryIndex.isPausedByUser(ctx)
        val items = arrayOf(if (paused) "Resume organising" else "Pause organising", "About recognition", "Start over (forget people and names)")
        AlertDialog.Builder(activity).setItems(items) { _, w ->
            when (w) {
                0 -> GalleryIndex.setPaused(ctx, !paused)
                1 -> statusDetails()
                2 -> AlertDialog.Builder(activity).setTitle("Start over?")
                    .setMessage("Names, people and everything found in your photos are forgotten and the photos are analysed again. Your photos themselves are not touched.")
                    .setPositiveButton("Start over") { _, _ -> resetIndex() }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun resetIndex() {
        shell.scope.launch {
            GalleryIndex.stop()
            withContext(Dispatchers.IO) { GalleryDb.get(ctx).reset(); GalleryThumbs.clearFaces(ctx) }
            GalleryIndex.notifyChanged()
            GalleryIndex.start(ctx)
        }
    }

    private fun statusDetails() {
        val s = GalleryIndex.state.value
        fun mode(m: EngineMode?) = when (m) {
            EngineMode.AI -> "on-device AI"
            EngineMode.BASIC -> "basic mode (the AI model couldn't run on this phone)"
            EngineMode.OFF -> "unavailable"
            null -> "starts with the first photo"
        }
        val progress = if (s.total > 0) "${s.done} of ${s.total} photos and videos analysed." else "Reading your library."
        val update = if (s.updatingFaces) "\n\nFaces found before are being looked at again with the newer, more accurate face model. Names, confirmations and corrections stay; people are regrouped when it finishes." else ""
        val msg = "$progress$update\n\nObjects and scenes: ${mode(s.objectMode)}.\nFaces and people: ${mode(s.faceMode)}.\n\n" +
            "Everything runs on this phone at low priority. It pauses when the battery is low or the phone is hot, and continues where it left off."
        AlertDialog.Builder(activity).setTitle("Organising your gallery").setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton(if (GalleryIndex.isPausedByUser(ctx)) "Resume" else "Pause") { _, _ -> GalleryIndex.setPaused(ctx, !GalleryIndex.isPausedByUser(ctx)) }
            .show()
    }
}

/** Pill-style segmented control. */
class Segments(ctx: android.content.Context, private val labels: List<String>, private val onSelect: (Int) -> Unit) : LinearLayout(ctx) {
    var selected = 0; private set
    private val views = ArrayList<TextView>()

    init {
        orientation = HORIZONTAL
        background = Shapes.pill(ctx, Palette.SURFACE, Palette.STROKE)
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        for ((i, l) in labels.withIndex()) {
            val t = UI.text(ctx, l, TextStyle.SUBTITLE).apply {
                gravity = Gravity.CENTER
                textSize = 14f
                minHeight = ctx.dp(36)
                isClickable = true; isFocusable = true
                setOnClickListener { select(i); onSelect(i) }
            }
            views.add(t)
            addView(t, lp(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        select(0)
    }

    fun select(i: Int) {
        selected = i
        for ((k, v) in views.withIndex()) {
            val on = k == i
            v.background = if (on) Shapes.pill(context, Palette.ACCENT_DARK, Palette.ACCENT) else null
            v.setTextColor(if (on) Palette.TEXT else Palette.TEXT_2)
            v.contentDescription = labels[k] + if (on) ", selected" else ""
        }
    }
}

/** "Organising 1,234 of 5,678" strip shown while photos are analysed. */
class IndexStatusBar(ctx: android.content.Context, onClick: () -> Unit) : LinearLayout(ctx) {
    private val text = UI.text(ctx, "", TextStyle.CAPTION, Palette.TEXT_2)
    private val bar = ProgressBarView(ctx)

    init {
        orientation = VERTICAL
        background = Shapes.clickable(ctx, Palette.SURFACE, 14f, Palette.STROKE)
        setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(10))
        addView(UI.horizontal(ctx).apply {
            addView(UI.iconView(ctx, R.drawable.ic_sparkle, Palette.ACCENT, 15))
            addView(text, lp(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(8) })
        })
        addView(bar, lp(LayoutParams.MATCH_PARENT, ctx.dp(4)).apply { topMargin = ctx.dp(7) })
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        visibility = GONE
    }

    fun render(s: GalleryIndex.State) {
        val show = s.working || s.phase == GalleryIndex.Phase.PAUSED
        visibility = if (show && (s.total > 0 || s.phase == GalleryIndex.Phase.READING)) VISIBLE else GONE
        val left = (s.total - s.done).coerceAtLeast(0)
        text.text = when {
            s.phase == GalleryIndex.Phase.PAUSED -> "${s.pausedWhy ?: "Paused"} · $left left"
            s.phase == GalleryIndex.Phase.READING -> "Reading your library…"
            s.phase == GalleryIndex.Phase.GROUPING -> "Grouping faces into people…"
            s.updatingFaces -> "Updating face recognition · ${s.done} of ${s.total} · names are kept"
            else -> "Finding people and things · ${s.done} of ${s.total} · on this phone"
        }
        bar.setProgress(s.fraction, if (s.phase == GalleryIndex.Phase.PAUSED) Palette.WARNING else Palette.ACCENT)
        contentDescription = text.text
    }
}

/** Album / category tile: square cover, name and count. */
class AlbumTile(ctx: android.content.Context, name: String, count: Int, cover: GMedia?, onClick: () -> Unit) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        val img = object : ImageView(ctx) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
        }.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Shapes.rounded(ctx, Palette.SURFACE_2, 18f)
            clipToOutline = true
        }
        if (cover != null) GalleryThumbs.load(ctx, cover, 384, img)
        addView(img, lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(UI.text(ctx, name, TextStyle.SUBTITLE).apply { textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(ctx.dp(2), ctx.dp(7), 0, 0) })
        addView(UI.text(ctx, "$count", TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(ctx.dp(2), ctx.dp(1), 0, 0) })
        isClickable = true; isFocusable = true
        contentDescription = "$name, $count items"
        setOnClickListener { onClick() }
    }
}

/** Round face with a name (or "Add name") and photo count. */
class PersonTile(ctx: android.content.Context, p: GPerson, face: com.localmediatools.gallery.GFace?, onClick: () -> Unit) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val img = object : ImageView(ctx) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
        }.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Shapes.circle(Palette.SURFACE_2)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        if (face != null) GalleryThumbs.face(ctx, face, 256, img)
        addView(img, lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(6); rightMargin = ctx.dp(6) })
        addView(UI.text(ctx, p.name ?: "Add name", TextStyle.SUBTITLE, if (p.named) Palette.TEXT else Palette.ACCENT).apply {
            textSize = 14f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, ctx.dp(7), 0, 0)
        })
        addView(UI.text(ctx, "${p.mediaCount} ${if (p.mediaCount == 1) "photo" else "photos"}", TextStyle.CAPTION, Palette.TEXT_3).apply { gravity = Gravity.CENTER })
        isClickable = true; isFocusable = true
        contentDescription = (p.name ?: "Unnamed person") + ", ${p.mediaCount} photos"
        setOnClickListener { onClick() }
    }
}
