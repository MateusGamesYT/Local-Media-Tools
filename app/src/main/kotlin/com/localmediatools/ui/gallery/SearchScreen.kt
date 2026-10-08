package com.localmediatools.ui.gallery

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.gallery.GFace
import com.localmediatools.gallery.GPerson
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.gallery.GalleryRepo
import com.localmediatools.gallery.SearchResult
import com.localmediatools.gallery.core.Category
import com.localmediatools.gallery.core.SearchParser
import com.localmediatools.gallery.core.SearchTerm
import com.localmediatools.ui.Palette
import com.localmediatools.ui.SearchField
import com.localmediatools.ui.Screen
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gallery search: people by name ("Sophie", "John and Sophie"), things and places ("dog", "car",
 * "beach"), albums, dates ("June 2023", "last week") and kinds ("videos", "screenshots"),
 * combined freely. Everything matches on this phone.
 */
class SearchScreen(activity: MainActivity, private val initial: String? = null) : Screen(activity) {
    private lateinit var field: SearchField
    private lateinit var suggestions: ScrollView
    private lateinit var results: FrameLayout
    private lateinit var grid: MediaGrid
    private lateinit var resultHead: LinearLayout
    private var job: Job? = null
    private var data: Suggest? = null

    private class Suggest(val people: List<Pair<GPerson, GFace?>>, val cats: List<Pair<Category, Int>>, val albums: List<Pair<String, String>>)

    override fun createView(): View {
        val root = UI.vertical(ctx).apply { setBackgroundColor(Palette.BG) }
        val bar = UI.horizontal(ctx).apply { setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(14), ctx.dp(8)) }
        bar.addView(UI.iconButton(ctx, R.drawable.ic_back, "Back") { pop() })
        field = SearchField(ctx, "People, things, places, dates") { q -> onQuery(q) }
        bar.addView(field, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        val body = FrameLayout(ctx)
        suggestions = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        resultHead = UI.vertical(ctx).apply { setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(4)) }
        grid = MediaGrid(ctx, { list, i -> push(ViewerScreen(activity, list, i)) }, bottomPad = ctx.dp(110), header = resultHead)
        results = FrameLayout(ctx).apply { addView(grid); visibility = View.GONE }
        body.addView(suggestions)
        body.addView(results)
        root.addView(body, lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val sel = SelectionBar(this, grid)
        results.addView(sel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(ctx.dp(14), 0, ctx.dp(14), ctx.dp(16))
        })
        loadSuggestions()
        if (!initial.isNullOrBlank()) field.edit.setText(initial)
        else field.edit.post {
            field.edit.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field.edit, 0)
        }
        watch.start()
        return root
    }

    private val watch = IndexWatch(this, 0) { if (field.edit.text.isNotBlank()) onQuery(field.edit.text.toString(), 600) }

    override fun onShow() { watch.shown() }

    private fun loadSuggestions() = scope.launch {
        val s = withContext(Dispatchers.IO) {
            val db = GalleryDb.get(ctx)
            val people = db.people().filter { it.named && it.mediaCount > 0 }.map { it to GalleryRepo.coverFace(ctx, it) }
            val counts = GalleryRepo.categoryCounts(ctx)
            val cats = GalleryRepo.categories(ctx).mapNotNull { c -> counts[c.key]?.takeIf { it > 0 }?.let { c to it } }.sortedByDescending { it.second }
            val albums = db.albums().take(12).map { it.first to it.second }
            Suggest(people, cats, albums)
        }
        data = s
        renderSuggestions("")
    }

    private fun onQuery(q: String, delayMs: Long = 220) {
        job?.cancel()
        val text = q.trim()
        if (text.isEmpty()) { results.visibility = View.GONE; suggestions.visibility = View.VISIBLE; renderSuggestions(""); return }
        renderSuggestions(text)
        job = scope.launch {
            delay(delayMs)
            val r = withContext(Dispatchers.IO) { GalleryRepo.search(ctx, text) }
            showResults(r)
        }
    }

    private fun showResults(r: SearchResult) {
        if (r.query.isEmpty) return
        // Plain words that matched nothing known: keep showing suggestions while typing.
        val onlyText = r.query.terms.all { it is SearchTerm.Text }
        resultHead.removeAllViews()
        val chips = UI.horizontal(ctx)
        for (t in r.query.terms) chips.addView(termChip(t), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ctx.dp(8) })
        resultHead.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        if (r.query.corrections.isNotEmpty()) {
            resultHead.addView(UI.text(ctx, "Showing results for " + r.query.corrections.joinToString(", ") { "\"${it.second}\"" } + " (you typed " +
                r.query.corrections.joinToString(", ") { "\"${it.first}\"" } + ")", TextStyle.CAPTION).apply { setPadding(0, ctx.dp(8), 0, 0) })
        }
        val n = r.items.size
        val st = GalleryIndex.state.value
        val tail = if (st.working && st.total > 0) " · still analysing ${st.total - st.done} more" else ""
        resultHead.addView(UI.text(ctx, (if (n == 0) "No matches" else "$n ${if (n == 1) "result" else "results"}") + tail, TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(4)) })
        if (n == 0) {
            val why = when {
                onlyText -> "Try a name you gave someone, or things like dog, car, beach, food, sunset or document."
                r.query.terms.size > 1 -> "No photo has all of these. Remove a word to see more."
                else -> "Nothing found yet."
            }
            resultHead.addView(UI.text(ctx, why, TextStyle.BODY_2).apply { setPadding(0, ctx.dp(8), 0, 0) })
        }
        grid.setItems(r.items)
        if (onlyText && n == 0) { results.visibility = View.GONE; suggestions.visibility = View.VISIBLE; return }
        grid.scrollToTop()
        results.visibility = View.VISIBLE
        suggestions.visibility = View.GONE
    }

    private fun termChip(t: SearchTerm): View = UI.horizontal(ctx).apply {
        background = Shapes.pill(ctx, Palette.ACCENT_DARK, Palette.ACCENT)
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        val icon = when (t) {
            is SearchTerm.Person -> R.drawable.ic_tool_faceblur
            is SearchTerm.Category -> R.drawable.ic_sparkle
            is SearchTerm.Album -> R.drawable.ic_folder
            is SearchTerm.Time -> R.drawable.ic_clock
            is SearchTerm.Kind -> R.drawable.ic_image
            is SearchTerm.Text -> R.drawable.ic_search
        }
        addView(UI.iconView(ctx, icon, Palette.ACCENT, 15))
        addView(UI.text(ctx, t.label, TextStyle.CAPTION, Palette.TEXT).apply { setPadding(ctx.dp(6), 0, 0, 0) })
    }

    private fun renderSuggestions(prefix: String) {
        val s = data ?: return
        suggestions.removeAllViews()
        val col = UI.vertical(ctx).apply { setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(120)) }
        val p = SearchParser.normalize(prefix)
        val lastWord = p.substringAfterLast(' ')
        fun matches(vararg words: String) = lastWord.isEmpty() || words.any { w -> SearchParser.normalize(w).split(' ').any { it.startsWith(lastWord) } }
        val people = s.people.filter { matches(it.first.name ?: "") }
        val cats = s.cats.filter { (c, _) -> matches(c.name, *c.words.toTypedArray()) }
        val albums = s.albums.filter { matches(it.second) }
        if (people.isNotEmpty()) {
            col.addView(UI.label(ctx, "People"), lp().apply { bottomMargin = ctx.dp(8) })
            val strip = UI.horizontal(ctx)
            for ((person, face) in people) strip.addView(personChip(person, face), LinearLayout.LayoutParams(ctx.dp(76), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ctx.dp(8) })
            col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, lp().apply { bottomMargin = ctx.dp(18) })
        }
        if (cats.isNotEmpty()) {
            col.addView(UI.label(ctx, if (lastWord.isEmpty()) "Things in your photos" else "Things"), lp().apply { bottomMargin = ctx.dp(8) })
            col.addView(flow(cats.take(if (lastWord.isEmpty()) 24 else 12).map { (c, n) -> chip(c.name, "$n") { pick(c.name) } }), lp().apply { bottomMargin = ctx.dp(18) })
        }
        if (albums.isNotEmpty()) {
            col.addView(UI.label(ctx, "Albums"), lp().apply { bottomMargin = ctx.dp(8) })
            col.addView(flow(albums.map { (_, name) -> chip(name, null) { pick(name) } }), lp().apply { bottomMargin = ctx.dp(18) })
        }
        if (lastWord.isEmpty()) {
            col.addView(UI.label(ctx, "Try"), lp().apply { bottomMargin = ctx.dp(8) })
            col.addView(flow(listOf("Videos", "Screenshots", "Last month", "This year").map { chip(it, null) { pick(it) } }))
        }
        if (people.isEmpty() && cats.isEmpty() && albums.isEmpty() && lastWord.isNotEmpty()) {
            col.addView(UI.text(ctx, "Press search to look for \"$prefix\".", TextStyle.BODY_2))
        }
        if (s.people.isEmpty() && lastWord.isEmpty()) {
            col.addView(UI.note(ctx, "Name people in Gallery › People to search for them, like \"Sophie\" or \"John and Sophie\".", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(18) })
        }
        suggestions.addView(col)
    }

    /** Replaces the word being typed with [word] (keeps earlier words, so "sophie be" + Beach works). */
    private fun pick(word: String) {
        val cur = field.edit.text.toString()
        val base = if (cur.endsWith(' ') || cur.isBlank()) cur else cur.substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " }
        val text = (base + word).trim() + " "
        field.edit.setText(text)
        field.edit.setSelection(text.length)
    }

    private fun personChip(p: GPerson, f: GFace?): View = UI.vertical(ctx).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Shapes.circle(Palette.SURFACE_2); clipToOutline = true }
        f?.let { GalleryThumbs.face(ctx, it, 192, img) }
        addView(img, LinearLayout.LayoutParams(ctx.dp(60), ctx.dp(60)))
        addView(UI.text(ctx, p.name ?: "", TextStyle.CAPTION, Palette.TEXT).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, ctx.dp(5), 0, 0) })
        isClickable = true; isFocusable = true
        contentDescription = p.name
        setOnClickListener { pick(p.name ?: return@setOnClickListener) }
    }

    private fun chip(label: String, count: String?, onClick: () -> Unit): View = UI.horizontal(ctx).apply {
        background = Shapes.clickable(ctx, Palette.SURFACE_2, 100f, Palette.STROKE_2)
        setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        addView(UI.text(ctx, label, TextStyle.BODY))
        if (count != null) addView(UI.text(ctx, count, TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(ctx.dp(6), 0, 0, 0) })
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun flow(views: List<View>): View {
        val box = com.localmediatools.ui.FlowLayout(ctx, 8, 8)
        views.forEach { box.addView(it) }
        return box
    }

    override fun onBack(): Boolean {
        if (grid.selecting) { grid.endSelecting(); return true }
        return false
    }
}
