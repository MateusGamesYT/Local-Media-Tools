package com.localmediatools.ui.gallery

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.gallery.GFace
import com.localmediatools.gallery.GMedia
import com.localmediatools.gallery.GPerson
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.gallery.GalleryRepo
import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceClustering
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
 * One person: their photos, their name, and tools to keep them right — rename, merge with someone,
 * review every face, hide, and "is this also them?" suggestions for similar unnamed groups.
 */
class PersonScreen(activity: MainActivity, private val personId: Long) : Screen(activity) {
    private lateinit var grid: MediaGrid
    private lateinit var header: LinearLayout
    private lateinit var topBar: FrameLayout
    private var person: GPerson? = null

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val col = UI.vertical(ctx)
        topBar = FrameLayout(ctx)
        col.addView(topBar)
        header = UI.vertical(ctx).apply { setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(6)) }
        grid = MediaGrid(ctx, { list, i -> push(ViewerScreen(activity, list, i)) }, bottomPad = ctx.dp(110), header = header)
        col.addView(grid, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col)
        root.addView(SelectionBar(this, grid), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(ctx.dp(14), 0, ctx.dp(14), ctx.dp(16))
        })
        observe()
        return root
    }

    @OptIn(FlowPreview::class)
    private val watch = IndexWatch(this) { load() }
    private var loadJob: kotlinx.coroutines.Job? = null
    /** This person no longer exists (merged or regrouped away) while another screen was on top. */
    private var gone = false

    private fun observe() = watch.start()

    override fun onShow() {
        // Close only when this screen is the visible one, never whatever happens to be on top.
        if (gone) { pop(); return }
        watch.shown()
    }

    private class Data(val person: GPerson?, val cover: GFace?, val items: List<GMedia>, val similar: List<Pair<GPerson, GFace?>>)

    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        val d = withContext(Dispatchers.IO) {
            val db = GalleryDb.get(ctx)
            val p = db.person(personId)
            if (p == null) Data(null, null, emptyList(), emptyList())
            else Data(p, GalleryRepo.coverFace(ctx, p), GalleryRepo.person(ctx, personId), if (p.named) similarGroups(db, p) else emptyList())
        }
        val p = d.person
        if (p == null) {
            if (activity.navigator.top === this) pop() else gone = true
            return
        }
        person = p
        topBar.removeAllViews()
        topBar.addView(TopBar(this@PersonScreen, p.name ?: "Unnamed person", UI.iconButton(ctx, R.drawable.ic_more, "Options") { menu() }))
        renderHeader(d)
        grid.setItems(d.items)
    }

    /** Unnamed groups that look like [p] (average similarity at or above the suggestion threshold). */
    private fun similarGroups(db: GalleryDb, p: GPerson): List<Pair<GPerson, GFace?>> {
        val mine = db.faces("f.person_id = ? AND f.emb IS NOT NULL AND f.ignored = 0", arrayOf(p.id.toString()), withEmb = true)
        if (mine.isEmpty()) return emptyList()
        val kind = mine[0].kind
        val params = ClusterParams.of(kind)
        val same = mine.filter { it.kind == kind }
        val sumA = FloatArray(same[0].emb!!.size)
        for (f in same) for (k in sumA.indices) sumA[k] += f.emb!![k]
        val rejected = db.notPeople()
        val out = ArrayList<Pair<GPerson, Float>>()
        for (o in db.people(includeHidden = false).filter { !it.named && it.faceCount >= 1 }) {
            val fs = db.faces("f.person_id = ? AND f.emb IS NOT NULL AND f.kind = ?", arrayOf(o.id.toString(), kind.code.toString()), withEmb = true)
            if (fs.isEmpty()) continue
            // Skip groups already said not to be this person.
            if (fs.any { f -> rejected[f.id]?.contains(p.id) == true }) continue
            val sumB = FloatArray(sumA.size)
            for (f in fs) for (k in sumB.indices) sumB[k] += f.emb!![k]
            val s = FaceClustering.linkage(sumA, same.size, sumB, fs.size, params)
            if (s >= params.suggestGroup) out.add(o to s)
        }
        return out.sortedByDescending { it.second }.take(6).map { it.first to GalleryRepo.coverFace(ctx, it.first) }
    }

    private fun renderHeader(d: Data) {
        val p = d.person ?: return
        header.removeAllViews()
        val row = UI.horizontal(ctx)
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Shapes.circle(Palette.SURFACE_2); clipToOutline = true }
        d.cover?.let { GalleryThumbs.face(ctx, it, 256, img) }
        row.addView(img, LinearLayout.LayoutParams(ctx.dp(76), ctx.dp(76)))
        val info = UI.vertical(ctx).apply { setPadding(ctx.dp(14), 0, 0, 0) }
        info.addView(UI.text(ctx, p.name ?: "Who is this?", TextStyle.TITLE))
        val n = d.items.size
        info.addView(UI.text(ctx, "$n ${if (n == 1) "photo or video" else "photos and videos"} · ${p.faceCount} ${if (p.faceCount == 1) "face" else "faces"}", TextStyle.CAPTION).apply { setPadding(0, ctx.dp(3), 0, 0) })
        row.addView(info, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(row)
        val actions = UI.horizontal(ctx).apply { setPadding(0, ctx.dp(12), 0, 0) }
        if (!p.named) actions.addView(UI.primaryButton(ctx, "Add name") { rename() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ctx.dp(8) })
        actions.addView(UI.secondaryButton(ctx, "Review faces", R.drawable.ic_tool_faceblur) { push(FacesScreen(activity, personId)) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actions)
        if (d.similar.isNotEmpty()) {
            header.addView(UI.label(ctx, "Also ${p.name}?"), lp().apply { topMargin = ctx.dp(18); bottomMargin = ctx.dp(6) })
            header.addView(UI.text(ctx, "These groups look similar. Confirm the ones that are ${p.name}.", TextStyle.CAPTION), lp().apply { bottomMargin = ctx.dp(8) })
            val strip = UI.horizontal(ctx)
            for ((o, f) in d.similar) strip.addView(suggestion(p, o, f), LinearLayout.LayoutParams(ctx.dp(112), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ctx.dp(10) })
            header.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
        header.addView(View(ctx), lp(1, ctx.dp(8)))
    }

    private fun suggestion(p: GPerson, o: GPerson, f: GFace?): View = UI.vertical(ctx).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        background = Shapes.rounded(ctx, Palette.SURFACE, 16f, Palette.STROKE)
        setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(8))
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Shapes.circle(Palette.SURFACE_2); clipToOutline = true }
        f?.let { GalleryThumbs.face(ctx, it, 192, img) }
        img.setOnClickListener { push(PersonScreen(activity, o.id)) }
        img.contentDescription = "Open this group"
        addView(img, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64)))
        addView(UI.text(ctx, "${o.mediaCount} ${if (o.mediaCount == 1) "photo" else "photos"}", TextStyle.CAPTION).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) })
        val row = UI.horizontal(ctx)
        row.addView(UI.iconButton(ctx, R.drawable.ic_close, "Not ${p.name}", Palette.DANGER) { notSame(p, o) })
        row.addView(UI.iconButton(ctx, R.drawable.ic_check, "Yes, ${p.name}", Palette.SUCCESS) { merge(p.id, o.id) })
        addView(row)
    }

    private fun notSame(p: GPerson, o: GPerson) = scope.launch {
        withContext(Dispatchers.IO) { GalleryDb.get(ctx).rejectGroup(o.id, p.id) }
        GalleryIndex.notifyChanged()
    }

    private fun merge(into: Long, from: Long) = scope.launch {
        withContext(Dispatchers.IO) { GalleryDb.get(ctx).mergePeople(into, from) }
        GalleryIndex.regroupNow(ctx)
    }

    private fun rename() {
        val p = person ?: return
        scope.launch {
            val people = withContext(Dispatchers.IO) { GalleryDb.get(ctx).people(includeHidden = true).filter { it.named } }
            FaceSheet.askName(activity, people.filter { it.id != p.id }, p.name) { name ->
                if (p.named) {
                    val other = people.firstOrNull { it.id != p.id && it.name.equals(name, ignoreCase = true) }
                    if (other != null) { askMerge(p, other); return@askName }
                    scope.launch { withContext(Dispatchers.IO) { GalleryDb.get(ctx).renamePerson(p.id, name) }; GalleryIndex.notifyChanged() }
                } else FaceSheet.nameGroup(activity, p, name, people) { }
            }
        }
    }

    private fun askMerge(p: GPerson, other: GPerson) {
        AlertDialog.Builder(activity).setTitle("Merge with ${other.name}?")
            .setMessage("${other.name} already exists. Merge ${p.name} into ${other.name}? All their photos will be under ${other.name}.")
            .setPositiveButton("Merge") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { GalleryDb.get(ctx).mergePeople(other.id, p.id) }
                    GalleryIndex.regroupNow(ctx); pop(); push(PersonScreen(activity, other.id))
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun menu() {
        val p = person ?: return
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts.add((if (p.named) "Rename" else "Add name") to { rename() })
        opts.add("Merge with…" to { pickMerge(p) })
        opts.add("Review faces" to { push(FacesScreen(activity, personId)) })
        opts.add((if (p.hidden) "Show in People" else "Hide from People") to {
            scope.launch {
                withContext(Dispatchers.IO) { GalleryDb.get(ctx).setHidden(p.id, !p.hidden) }
                GalleryIndex.notifyChanged()
                Toast.makeText(ctx, if (p.hidden) "Shown in People" else "Hidden from People", Toast.LENGTH_SHORT).show()
            }
        })
        if (p.named) opts.add("Remove name" to {
            AlertDialog.Builder(activity).setTitle("Remove ${p.name}?")
                .setMessage("The name is removed and these faces become unnamed again. Photos are not affected.")
                .setPositiveButton("Remove") { _, _ ->
                    scope.launch { withContext(Dispatchers.IO) { GalleryDb.get(ctx).deletePerson(p.id) }; GalleryIndex.regroupNow(ctx); pop() }
                }.setNegativeButton("Cancel", null).show()
        })
        AlertDialog.Builder(activity).setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }.show()
    }

    private fun pickMerge(p: GPerson) = scope.launch {
        val others = withContext(Dispatchers.IO) { GalleryDb.get(ctx).people(includeHidden = true).filter { it.id != p.id && (it.named || it.faceCount >= 2) } }
        if (others.isEmpty()) { Toast.makeText(ctx, "There is no one else to merge with yet.", Toast.LENGTH_SHORT).show(); return@launch }
        val labels = others.map { (it.name ?: "Unnamed") + " · ${it.mediaCount} photos" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Same person as…").setItems(labels) { _, w ->
            val o = others[w]
            // Keep the named one (this one when both or neither are named).
            val (into, from) = if (!p.named && o.named) o.id to p.id else p.id to o.id
            scope.launch {
                withContext(Dispatchers.IO) { GalleryDb.get(ctx).mergePeople(into, from) }
                GalleryIndex.regroupNow(ctx)
                if (into != personId) { pop(); push(PersonScreen(activity, into)) }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    override fun onBack(): Boolean {
        if (grid.selecting) { grid.endSelecting(); return true }
        return false
    }
}
