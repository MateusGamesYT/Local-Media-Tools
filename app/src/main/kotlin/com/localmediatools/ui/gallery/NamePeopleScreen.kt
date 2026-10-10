package com.localmediatools.ui.gallery

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.gallery.GFace
import com.localmediatools.gallery.GPerson
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceKind
import com.localmediatools.gallery.core.NameSuggestions
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.FlowLayout
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Screen
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.TopBar
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import com.localmediatools.ui.style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Names people quickly: the unnamed groups one after another, most photos first, with the keyboard
 * ready. Type a name and press Enter (an existing name adds the faces to that person), tap a
 * suggestion ("looks like Ana"), skip, or say it's no one to keep track of. Everything is saved at
 * once; faces are regrouped when leaving, not after every name.
 */
class NamePeopleScreen(activity: MainActivity) : Screen(activity) {
    private class Group(val person: GPerson, val kind: FaceKind)

    private var queue: List<Group> = emptyList()
    private var index = 0
    private var changed = false
    /** Named people by id, and the sum of their faces' embeddings per kind (for suggestions). */
    private val named = LinkedHashMap<Long, String>()
    private val sums = HashMap<Pair<Long, FaceKind>, NameSuggestions.Faces>()
    private var named0 = 0

    private lateinit var top: FrameLayout
    private lateinit var body: LinearLayout
    private lateinit var faces: LinearLayout
    private lateinit var seen: TextView
    private lateinit var suggestions: FlowLayout
    private lateinit var input: AutoCompleteTextView
    private lateinit var save: ButtonView

    override fun createView(): View {
        val root = UI.vertical(ctx).apply { setBackgroundColor(Palette.BG) }
        top = FrameLayout(ctx)
        root.addView(top)
        val scroll = ScrollView(ctx).apply { clipToPadding = false; isFillViewport = true }
        body = UI.vertical(ctx).apply { setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(40)) }
        scroll.addView(body)
        root.addView(scroll, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(UI.text(ctx, "Loading people…", TextStyle.BODY_2))
        scope.launch { load() }
        return root
    }

    private suspend fun load() {
        val (groups, people) = withContext(Dispatchers.IO) {
            val db = GalleryDb.get(ctx)
            val all = db.people(includeHidden = false)
            val faceRows = db.faces("f.emb IS NOT NULL AND f.ignored = 0 AND f.person_id IS NOT NULL", withEmb = true, order = "f.id")
            val byPerson = faceRows.groupBy { it.personId!! }
            for ((pid, fs) in byPerson) for ((kind, list) in fs.groupBy { it.kind })
                NameSuggestions.of(list.map { it.emb!! }, pid)?.let { sums[pid to kind] = it }
            val unnamed = all.filter { !it.named && it.faceCount >= 2 && it.mediaCount > 0 }.sortedByDescending { it.faceCount }
            unnamed.map { p -> Group(p, byPerson[p.id]?.groupingBy { it.kind }?.eachCount()?.maxByOrNull { it.value }?.key ?: FaceKind.AI) } to all.filter { it.named }
        }
        queue = groups
        for (p in people) named[p.id] = p.name!!
        named0 = named.size
        bind()
    }

    private fun current() = queue.getOrNull(index)

    private fun bind() {
        top.removeAllViews()
        val g = current()
        top.addView(TopBar(this, "Name people", UI.text(ctx, if (g == null) "" else "${index + 1} of ${queue.size}", TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(0, 0, ctx.dp(10), 0) }))
        body.removeAllViews()
        if (g == null) { finished(); return }
        val card = UI.card(ctx)
        faces = UI.vertical(ctx)
        card.addView(faces)
        seen = UI.text(ctx, "", TextStyle.CAPTION, Palette.TEXT_3).apply { gravity = Gravity.CENTER }
        card.addView(seen, lp().apply { topMargin = ctx.dp(10) })
        body.addView(card)

        body.addView(UI.label(ctx, "Who is this?"), lp().apply { topMargin = ctx.dp(18); bottomMargin = ctx.dp(8) })
        val row = UI.horizontal(ctx)
        input = AutoCompleteTextView(ctx).apply {
            style(TextStyle.BODY)
            setTextColor(Palette.TEXT); setHintTextColor(Palette.TEXT_3)
            hint = "Type a name, then Enter"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            threshold = 1
            background = Shapes.rounded(ctx, Palette.SURFACE_2, 14f, Palette.STROKE_2)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, named.values.distinct().sorted()))
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_DONE || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { submit(); true } else false
            }
            setOnItemClickListener { _, _, _, _ -> submit() }
        }
        row.addView(input, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        save = UI.primaryButton(ctx, "Save") { submit() }
        row.addView(save, lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(10) })
        body.addView(row)

        suggestions = FlowLayout(ctx)
        body.addView(suggestions, lp().apply { topMargin = ctx.dp(12) })

        val actions = UI.horizontal(ctx)
        actions.addView(UI.secondaryButton(ctx, "Skip") { next() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ctx.dp(8) })
        actions.addView(UI.secondaryButton(ctx, "No one I know") { ignore(g) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(actions, lp().apply { topMargin = ctx.dp(18) })
        body.addView(UI.ghostButton(ctx, "Some of these aren't the same person…", R.drawable.ic_tool_faceblur) { push(FacesScreen(activity, g.person.id)) }, lp().apply { topMargin = ctx.dp(8) })

        loadFaces(g)
        renderSuggestions(g)
        input.requestFocus()
        input.post {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun loadFaces(g: Group) = scope.launch {
        val list = withContext(Dispatchers.IO) {
            GalleryDb.get(ctx).faces("f.person_id = ? AND f.ignored = 0", arrayOf(g.person.id.toString()), order = "f.good DESC, f.quality DESC", limit = 8)
        }
        if (current() !== g) return@launch
        faces.removeAllViews()
        for (chunk in list.chunked(4)) {
            val r = UI.horizontal(ctx, Gravity.TOP)
            for (f in chunk) r.addView(faceView(f), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(4); rightMargin = ctx.dp(4) })
            repeat(4 - chunk.size) { r.addView(View(ctx), lp(0, 1, 1f).apply { leftMargin = ctx.dp(4); rightMargin = ctx.dp(4) }) }
            faces.addView(r, lp().apply { bottomMargin = ctx.dp(8) })
        }
        seen.text = "${g.person.faceCount} faces in ${g.person.mediaCount} ${if (g.person.mediaCount == 1) "photo" else "photos"} · long-press a face to see its photo"
    }

    private fun faceView(f: GFace): View = object : ImageView(ctx) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
    }.apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Shapes.circle(Palette.SURFACE_2); clipToOutline = true
        GalleryThumbs.face(ctx, f, 192, this)
        contentDescription = "Face"
        isLongClickable = true
        setOnLongClickListener {
            scope.launch {
                val m = withContext(Dispatchers.IO) { GalleryDb.get(ctx).mediaById(f.mediaId) } ?: return@launch
                push(ViewerScreen(activity, listOf(m), 0, highlightFace = f.id))
            }
            true
        }
    }

    private fun renderSuggestions(g: Group) {
        suggestions.removeAllViews()
        val mine = sums[g.person.id to g.kind]
        val others = sums.filterKeys { it.second == g.kind && it.first in named }.values.toList()
        val similar = if (mine == null) emptyList() else NameSuggestions.forGroup(mine, others, ClusterParams.of(g.kind))
        for ((id, _) in similar) {
            val name = named[id] ?: continue
            suggestions.addView(chip("This is $name", true) { assign(g, id, name) })
        }
        // Then the most recently named people, for quick taps.
        for ((id, name) in named.entries.reversed().filter { e -> similar.none { it.first == e.key } }.take(6)) {
            suggestions.addView(chip(name, false) { assign(g, id, name) })
        }
    }

    private fun chip(text: String, strong: Boolean, onClick: () -> Unit): View = UI.horizontal(ctx).apply {
        if (strong) addView(UI.iconView(ctx, R.drawable.ic_check, Palette.SUCCESS, 16).apply { setPadding(0, 0, ctx.dp(6), 0) })
        addView(UI.text(ctx, text, TextStyle.BODY, if (strong) Palette.TEXT else Palette.TEXT_2))
        minimumHeight = ctx.dp(42)
        setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(16), ctx.dp(8))
        background = if (strong) Shapes.clickable(ctx, Palette.withAlpha(Palette.SUCCESS, 0x22), 100f, Palette.SUCCESS) else Shapes.clickable(ctx, Palette.SURFACE_2, 100f, Palette.STROKE_2)
        isClickable = true; isFocusable = true
        contentDescription = text
        setOnClickListener { onClick() }
    }

    private fun submit() {
        val g = current() ?: return
        val name = input.text.toString().trim().replace(Regex("\\s+"), " ").take(60)
        if (name.isEmpty()) { Toast.makeText(ctx, "Type a name, or tap Skip.", Toast.LENGTH_SHORT).show(); return }
        val existing = named.entries.firstOrNull { it.value.equals(name, ignoreCase = true) && it.key != g.person.id }
        if (existing != null) { assign(g, existing.key, existing.value); return }
        scope.launch {
            withContext(Dispatchers.IO) { GalleryDb.get(ctx).nameGroup(g.person.id, name) }
            changed = true
            named[g.person.id] = name
            Toast.makeText(ctx, "Named $name", Toast.LENGTH_SHORT).show()
            next()
        }
    }

    /** Adds the group's faces to a named person. */
    private fun assign(g: Group, personId: Long, name: String) {
        scope.launch {
            withContext(Dispatchers.IO) { GalleryDb.get(ctx).mergePeople(personId, g.person.id) }
            changed = true
            // Later groups can now be suggested as this person with these faces too.
            val a = sums.remove(g.person.id to g.kind); val b = sums[personId to g.kind]
            if (a != null) sums[personId to g.kind] = if (b == null) NameSuggestions.Faces(personId, a.sum, a.count)
                else NameSuggestions.Faces(personId, FloatArray(a.sum.size) { a.sum[it] + b.sum[it] }, a.count + b.count)
            Toast.makeText(ctx, "Added to $name", Toast.LENGTH_SHORT).show()
            next()
        }
    }

    private fun ignore(g: Group) {
        scope.launch {
            withContext(Dispatchers.IO) { GalleryDb.get(ctx).ignoreGroup(g.person.id) }
            changed = true
            sums.remove(g.person.id to g.kind)
            next()
        }
    }

    private fun next() { index++; bind() }

    private fun finished() {
        hideKeyboard()
        val n = named.size - named0
        body.addView(UI.vertical(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(8), ctx.dp(48), ctx.dp(8), 0)
            addView(UI.iconTile(ctx, R.drawable.ic_check, Palette.SUCCESS, 64, 32))
            addView(UI.text(ctx, if (queue.isEmpty()) "Everyone is named" else "All done", TextStyle.TITLE).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(16), 0, 0) })
            addView(UI.text(ctx, if (queue.isEmpty()) "There are no unnamed groups right now. New ones appear as more photos are looked at."
                else "You went through ${queue.size} ${if (queue.size == 1) "group" else "groups"}${if (n > 0) " and named $n ${if (n == 1) "person" else "people"}" else ""}. Faces are regrouped with your names now.", TextStyle.BODY_2).apply {
                gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, ctx.dp(20))
            })
            addView(UI.primaryButton(ctx, "Back to People") { pop() }, lp())
        })
    }

    private fun hideKeyboard() {
        if (::input.isInitialized) (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun onShow() {
        // Back from fixing a group's faces: it may have changed or gone.
        val g = current() ?: return
        scope.launch {
            val p = withContext(Dispatchers.IO) { GalleryDb.get(ctx).person(g.person.id) }
            if (p == null || p.named) next() else if (p.faceCount != g.person.faceCount) { queue = queue.toMutableList().also { it[index] = Group(p, g.kind) }; bind() }
        }
    }

    override fun onHide() {
        hideKeyboard()
        if (changed) { changed = false; GalleryIndex.regroupNow(ctx) }
    }
}
