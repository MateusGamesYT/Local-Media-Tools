package com.localmediatools.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import com.localmediatools.i18n.trn
import com.localmediatools.tools.ToolId
import com.localmediatools.vision.DupResultGroup
import com.localmediatools.vision.DupScanState
import com.localmediatools.vision.DuplicateScanner
import com.localmediatools.vision.GalleryPhoto
import com.localmediatools.vision.core.DupKind
import com.localmediatools.vision.core.Prefer
import kotlinx.coroutines.launch

/**
 * Duplicate finder: scans the photo library (with permission), groups copies and similar shots,
 * suggests what to remove under the user's keep rules (RAWs, favourites, people, which copy), and
 * moves the chosen extras to the system trash. The list recycles its rows and a tap redraws only
 * the photo tapped; groups can also be reviewed one at a time, full screen.
 */
class DuplicatesScreen(activity: MainActivity) : Screen(activity) {
    private val tool = ToolId.DUPLICATES
    private lateinit var list: ListView
    private lateinit var statusCard: LinearLayout
    private lateinit var rulesCard: LinearLayout
    private lateinit var toolsCard: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var trashButton: ButtonView
    private lateinit var barText: TextView
    private var groups: List<DupResultGroup> = emptyList()
    private var shown: List<DupResultGroup> = emptyList()
    private val adapter = Adapter()
    private var rulesOpen = false

    override fun createView(): View {
        DupChoices.load(ctx)
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        list = ListView(ctx).apply {
            divider = null; dividerHeight = 0; selector = Shapes.rounded(ctx, Color.TRANSPARENT, 0f)
            clipToPadding = false; setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(150))
            isVerticalScrollBarEnabled = false
        }
        val head = UI.vertical(ctx)
        head.addView(TopBar(this, tool.title).apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) })
        val hero = UI.card(ctx)
        val row = UI.horizontal(ctx, Gravity.TOP)
        row.addView(UI.toolTile(ctx, tool, 52, 28))
        row.addView(UI.text(ctx, tool.longDescription, TextStyle.BODY_2).apply { setPadding(ctx.dp(14), 0, 0, 0) }, lp(0, WRAP, 1f))
        hero.addView(row)
        hero.addView(UI.note(ctx, tr("Photos are compared on this phone. Nothing is deleted until you confirm, and removed photos go to the system trash, where they can be restored for 30 days."), UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(12) })
        head.addView(hero)
        statusCard = UI.card(ctx)
        head.addView(statusCard, lp().apply { topMargin = ctx.dp(14) })
        rulesCard = UI.card(ctx, 14)
        head.addView(rulesCard, lp().apply { topMargin = ctx.dp(14) })
        toolsCard = UI.vertical(ctx)
        head.addView(toolsCard, lp().apply { topMargin = ctx.dp(14) })
        list.addHeaderView(head, null, false)
        list.adapter = adapter
        root.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))

        bar = UI.vertical(ctx, 16, 12)
        bar.background = Shapes.rounded(ctx, Palette.SURFACE, 24f, Palette.STROKE)
        bar.elevation = ctx.dp(8).toFloat()
        barText = UI.text(ctx, "", TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ctx.dp(8)) }
        bar.addView(barText)
        trashButton = UI.primaryButton(ctx, tr("Move to trash")) { trash() }
        bar.addView(trashButton, lp())
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(ctx.dp(10), 0, ctx.dp(10), ctx.dp(10)) })
        bar.visibility = View.GONE

        scope.launch { DuplicateScanner.state.collect { render(it) } }
        return root
    }

    private fun render(s: DupScanState) {
        statusCard.removeAllViews()
        when (s) {
            is DupScanState.Idle -> {
                statusCard.addView(UI.text(ctx, tr("Find duplicates in your photos"), TextStyle.SUBTITLE))
                statusCard.addView(UI.text(ctx, if (activity.hasPhotoAccess()) tr("Scans every photo this app can see. The first scan takes a while (about a minute per thousand photos); later scans only look at new photos.")
                    else tr("The app needs permission to see your photos for this. It never uploads them."), TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(6) })
                statusCard.addView(UI.primaryButton(ctx, tr("Scan my photos")) { start() }, lp().apply { topMargin = ctx.dp(14) })
                partialAccessNote()
                show(null)
            }
            is DupScanState.Running -> {
                statusCard.addView(UI.text(ctx, s.phase, TextStyle.SUBTITLE))
                statusCard.addView(UI.text(ctx, if (s.total > 0) trn(s.total, "{1} of {0} photo", "{1} of {0} photos", s.done) else tr("Starting…"), TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(6) })
                statusCard.addView(ProgressBarView(ctx).apply { if (s.total > 0) setProgress(s.done.toFloat() / s.total) else indeterminate = true }, LinearLayout.LayoutParams(MATCH, ctx.dp(8)).apply { topMargin = ctx.dp(12) })
                statusCard.addView(UI.ghostButton(ctx, tr("Stop")) { DuplicateScanner.cancel() }, lp().apply { topMargin = ctx.dp(8) })
                show(null)
            }
            is DupScanState.Failed -> {
                statusCard.addView(UI.note(ctx, tr("The scan stopped: {0}", s.message), UI.NoteKind.WARN))
                statusCard.addView(UI.secondaryButton(ctx, tr("Try again")) { start() }, lp().apply { topMargin = ctx.dp(12) })
                show(null)
            }
            is DupScanState.Done -> {
                val extra = s.groups.sumOf { it.photos.size - 1 }
                statusCard.addView(UI.text(ctx, if (s.groups.isEmpty()) tr("No duplicates found") else trn(s.groups.size, "{0} group", "{0} groups") + " · " + trn(extra, "{0} photo you could remove", "{0} photos you could remove"), TextStyle.SUBTITLE))
                statusCard.addView(UI.text(ctx, if (s.groups.isEmpty()) trn(s.scanned, "Checked {0} photo. Nice and tidy!", "Checked {0} photos. Nice and tidy!")
                    else trn(s.scanned, "Checked {0} photo. Copies of a picture are suggested for removal (the best one stays); similar shots are up to you, or let the rules below pick.", "Checked {0} photos. Copies of a picture are suggested for removal (the best one stays); similar shots are up to you, or let the rules below pick."), TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(6) })
                if (!s.aiUsed) statusCard.addView(UI.note(ctx, tr("Similar-shot detection needs the on-device AI model, which isn't available on this phone; exact and re-saved copies are still found."), UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(10) })
                if (s.unreadable > 0) statusCard.addView(UI.note(ctx, trn(s.unreadable, "{0} photo couldn't be read, so it was only checked for identical copies.", "{0} photos couldn't be read, so they were only checked for identical copies."), UI.NoteKind.WARN), lp().apply { topMargin = ctx.dp(10) })
                partialAccessNote()
                statusCard.addView(UI.ghostButton(ctx, tr("Scan again"), R.drawable.ic_history) { start() }, lp().apply { topMargin = ctx.dp(8) })
                show(s.groups)
            }
        }
    }

    /** Android 14+: with "selected photos" access only those can be checked; offers access to all. */
    private fun partialAccessNote() {
        if (!activity.hasPartialMediaAccess()) return
        statusCard.addView(UI.note(ctx, tr("This app may see only the photos you selected, so only those are checked. Allow access to all photos to check your whole library."), UI.NoteKind.WARN), lp().apply { topMargin = ctx.dp(12) })
        statusCard.addView(UI.secondaryButton(ctx, tr("Allow all photos")) {
            activity.ensurePhotoAccess { render(DuplicateScanner.state.value) }
        }, lp().apply { topMargin = ctx.dp(8) })
    }

    private fun start() {
        activity.ensurePhotoAccess { ok ->
            if (ok) DuplicateScanner.start(ctx)
            else Toast.makeText(ctx, tr("Without access to your photos the duplicate finder can't look for copies."), Toast.LENGTH_LONG).show()
        }
    }

    private fun show(gs: List<DupResultGroup>?) {
        if (gs == null) {
            groups = emptyList(); DupChoices.clear()
            rulesCard.visibility = View.GONE; toolsCard.visibility = View.GONE
            refreshList(); return
        }
        val fresh = gs !== groups
        groups = gs
        DupChoices.results(gs)
        rulesCard.visibility = if (gs.isEmpty()) View.GONE else View.VISIBLE
        toolsCard.visibility = if (gs.isEmpty()) View.GONE else View.VISIBLE
        renderRules(); renderTools(); refreshList()
        if (fresh && gs.isNotEmpty()) DupChoices.loadPeople(ctx, scope, gs) { renderRules(); refreshList() }
    }

    private fun refreshList() {
        shown = DupChoices.visible(groups)
        adapter.notifyDataSetChanged()
        updateBar()
    }

    // ------------------------------------------------------------------ rules
    private fun renderRules() {
        rulesCard.removeAllViews()
        val r = DupChoices.rules
        val head = UI.horizontal(ctx)
        val summary = listOfNotNull(if (r.keepRaw) tr("RAWs") else null, if (r.keepFavorites) tr("favourites") else null,
            if (r.keepPeople.isNotEmpty()) trn(r.keepPeople.size, "{0} person", "{0} people") else null)
        head.addView(UI.titled(ctx, tr("Keep rules"), (if (summary.isEmpty()) tr("Nothing always kept") else tr("Always kept: {0}", summary.joinToString(trc("between the items of a list", ", ")))) +
            " · " + r.prefer.keeps + if (r.similarShots) " · " + tr("picks the best similar shot") else ""), lp(0, WRAP, 1f))
        head.addView(UI.iconView(ctx, R.drawable.ic_chevron, Palette.TEXT_3, 18).apply { rotation = if (rulesOpen) 270f else 90f })
        head.isClickable = true; head.isFocusable = true
        head.contentDescription = if (rulesOpen) tr("Keep rules, expanded") else tr("Keep rules, collapsed")
        head.setOnClickListener { rulesOpen = !rulesOpen; renderRules() }
        rulesCard.addView(head)
        if (!rulesOpen) return
        fun set(n: com.localmediatools.vision.core.KeepRules) { DupChoices.setRules(ctx, n); renderRules(); refreshList() }
        rulesCard.addView(ToggleRow(ctx, tr("Always keep RAW photos"), tr("DNG, CR2, NEF, ARW and other camera RAW files; the best other copy stays next to them"), r.keepRaw) { set(DupChoices.rules.copy(keepRaw = it)) }, lp().apply { topMargin = ctx.dp(8) })
        rulesCard.addView(ToggleRow(ctx, tr("Always keep favourites"), tr("Photos you starred in your gallery"), r.keepFavorites) { set(DupChoices.rules.copy(keepFavorites = it)) })
        rulesCard.addView(ToggleRow(ctx, tr("Suggest similar shots too"), tr("Keeps the best shot of each burst and suggests the rest"), r.similarShots) { set(DupChoices.rules.copy(similarShots = it)) })
        rulesCard.addView(UI.label(ctx, tr("Which copy to keep")), lp().apply { topMargin = ctx.dp(12); bottomMargin = ctx.dp(8) })
        rulesCard.addView(ChoiceGroup(ctx, Prefer.entries, { it.label }, r.prefer) { set(DupChoices.rules.copy(prefer = it)) })
        rulesCard.addView(UI.text(ctx, r.prefer.explain, TextStyle.CAPTION, Palette.TEXT_3), lp().apply { topMargin = ctx.dp(6) })
        rulesCard.addView(UI.label(ctx, tr("Always keep photos of")), lp().apply { topMargin = ctx.dp(14); bottomMargin = ctx.dp(8) })
        val names = DupChoices.names
        if (names.isEmpty()) {
            rulesCard.addView(UI.text(ctx, tr("Name people in the Gallery's People tab to keep every photo they're in."), TextStyle.CAPTION, Palette.TEXT_3))
        } else {
            val flow = FlowLayout(ctx)
            for ((id, name) in names.entries.sortedBy { it.value.lowercase() }) {
                val on = id in r.keepPeople
                flow.addView(UI.text(ctx, (if (on) "✓ " else "") + name, TextStyle.BODY, if (on) Palette.TEXT else Palette.TEXT_2).apply {
                    gravity = Gravity.CENTER; minHeight = ctx.dp(40)
                    setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
                    background = if (on) Shapes.clickable(ctx, Palette.ACCENT_DARK, 100f, Palette.ACCENT) else Shapes.clickable(ctx, Palette.SURFACE_2, 100f, Palette.STROKE_2)
                    isClickable = true; isFocusable = true
                    contentDescription = if (on) tr("{0}, always kept", name) else tr("{0}, not protected", name)
                    setOnClickListener { set(DupChoices.rules.copy(keepPeople = if (on) DupChoices.rules.keepPeople - id else DupChoices.rules.keepPeople + id)) }
                })
            }
            rulesCard.addView(flow)
        }
        rulesCard.addView(UI.text(ctx, tr("Rules apply to every group you haven't changed by hand."), TextStyle.CAPTION, Palette.TEXT_3), lp().apply { topMargin = ctx.dp(12) })
    }

    // ------------------------------------------------------------------ sort, filter, bulk
    private fun renderTools() {
        toolsCard.removeAllViews()
        val actions = UI.horizontal(ctx)
        actions.addView(UI.primaryButton(ctx, tr("Review one by one")) { review(0) }, lp(0, WRAP, 1f))
        actions.addView(View(ctx), lp(ctx.dp(10), 1))
        actions.addView(UI.secondaryButton(ctx, tr("Reset to suggestions")) {
            DupChoices.apply(groups, resetTouched = true); refreshList()
            Toast.makeText(ctx, tr("Every group follows the keep rules again."), Toast.LENGTH_SHORT).show()
        }, lp(0, WRAP, 1f))
        toolsCard.addView(actions)
        toolsCard.addView(ChipRow(ctx, DupChoices.Sort.entries, { it.label }, DupChoices.sort) { DupChoices.sort = it; refreshList() }, lp().apply { topMargin = ctx.dp(12) })
        toolsCard.addView(ChipRow(ctx, DupChoices.Filter.entries, { it.label }, DupChoices.filter) { DupChoices.filter = it; refreshList() }, lp().apply { topMargin = ctx.dp(8) })
    }

    private fun review(start: Int) {
        if (shown.isEmpty()) return
        push(DuplicateReviewScreen(activity, shown, start))
    }

    // ------------------------------------------------------------------ list
    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = DupChoices.key(shown[p])
        override fun hasStableIds() = true
        override fun isEnabled(p: Int) = false
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? GroupRow) ?: GroupRow(ctx)
            row.bind(shown[p], p)
            return row
        }
    }

    private inner class GroupRow(ctx: Context) : LinearLayout(ctx) {
        private val card = UI.card(ctx, 14)
        private val title = UI.text(ctx, "", TextStyle.SUBTITLE)
        private val sub = UI.text(ctx, "", TextStyle.CAPTION, Palette.TEXT_3)
        private val strip = LinearLayout(ctx)
        private val cells = ArrayList<PhotoCell>()
        private var group: DupResultGroup? = null
        private val keepAll = UI.ghostButton(ctx, tr("Keep all")) { group?.let { DupChoices.keepAll(it); bindCells(); updateBar() } }
        private val suggested = UI.ghostButton(ctx, tr("Suggested")) { group?.let { DupChoices.keepSuggested(it); bindCells(); updateBar() } }

        init {
            orientation = VERTICAL
            setPadding(0, ctx.dp(12), 0, 0)
            val head = UI.horizontal(ctx)
            head.addView(UI.vertical(ctx).apply { addView(title); addView(sub) }, lp(0, WRAP, 1f))
            head.addView(keepAll); head.addView(suggested)
            card.addView(head)
            card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, lp().apply { topMargin = ctx.dp(10) })
            card.isClickable = true
            addView(card)
        }

        fun bind(g: DupResultGroup, index: Int) {
            group = g
            title.text = g.kind.label
            sub.text = trn(g.photos.size, "{0} photo", "{0} photos") + " · ${g.kind.explain}"
            card.setOnLongClickListener { review(index); true }
            while (cells.size < g.photos.size) PhotoCell(context, 112).also { cells.add(it); strip.addView(it, LayoutParams(context.dp(112), context.dp(112)).apply { rightMargin = context.dp(8) }) }
            for ((i, c) in cells.withIndex()) c.visibility = if (i < g.photos.size) VISIBLE else GONE
            bindCells()
        }

        private fun bindCells() {
            val g = group ?: return
            val suggestion = DupChoices.suggestion(g)
            for ((i, p) in g.photos.withIndex()) cells[i].bind(this@DuplicatesScreen, g, p, suggestion) { bindCells(); updateBar() }
            suggested.alpha = if (g.photos.all { (it.uri in DupChoices.remove) == (it.uri in suggestion) }) 0.45f else 1f
            keepAll.alpha = if (g.photos.none { it.uri in DupChoices.remove }) 0.45f else 1f
        }
    }

    fun updateBar() {
        val photos = groups.flatMap { it.photos }.filter { it.uri in DupChoices.remove }
        if (photos.isEmpty()) { bar.visibility = View.GONE; return }
        bar.visibility = View.VISIBLE
        val touchedGroups = groups.count { g -> g.photos.any { it.uri in DupChoices.remove } }
        barText.text = trn(touchedGroups, "{1} selected in {0} group · frees {2}", "{1} selected in {0} groups · frees {2}", photos.size, Format.bytes(photos.sumOf { it.size }))
        trashButton.label = trn(photos.size, "Move {0} to trash", "Move {0} to trash")
    }

    private fun trash() = trashPhotos(activity, DupChoices.remove.toList()) { refreshList() }

    override fun onShow() {
        // Permission may have been granted from settings meanwhile; the review may have changed choices.
        if (::statusCard.isInitialized) {
            if (DuplicateScanner.state.value is DupScanState.Idle) render(DuplicateScanner.state.value)
            else refreshList()
        }
    }

    companion object {
        /** Moves [uris] to the system trash after the system's confirmation, then updates the results. */
        fun trashPhotos(activity: MainActivity, uris: List<Uri>, after: () -> Unit) {
            val ctx = activity
            if (uris.isEmpty()) return
            if (Build.VERSION.SDK_INT < 30) {
                Toast.makeText(ctx, tr("Moving photos to the trash needs Android 11 or newer. Long-press a photo to open it in your gallery and delete it there."), Toast.LENGTH_LONG).show()
                return
            }
            val pi = try { MediaStore.createTrashRequest(ctx.contentResolver, uris, true) } catch (e: Exception) {
                Toast.makeText(ctx, tr("These photos can't be moved to the trash: {0}", e.message), Toast.LENGTH_LONG).show(); return
            }
            activity.launchIntentSender(pi.intentSender) { rc ->
                if (rc == android.app.Activity.RESULT_OK) {
                    val set = uris.toHashSet()
                    DupChoices.remove.removeAll(set)
                    DuplicateScanner.removed(set)
                    Toast.makeText(ctx, trn(uris.size, "Moved {0} photo to the trash. You can restore it from your gallery's trash for 30 days.", "Moved {0} photos to the trash. You can restore them from your gallery's trash for 30 days."), Toast.LENGTH_LONG).show()
                    after()
                }
            }
        }

        fun openPhoto(activity: MainActivity, p: GalleryPhoto) {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(p.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) { }
        }
    }
}

/**
 * One photo of a duplicate group: its thumbnail, whether it goes to the trash (red frame, dimmed),
 * why it stays (best, RAW, favourite, a person), its size. Tap toggles, long-press opens it.
 */
class PhotoCell(ctx: Context, private val sizeDp: Int) : FrameLayout(ctx) {
    private val iv = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Shapes.rounded(ctx, Palette.SURFACE_3, 11f)
        clipToOutline = true
    }
    private val tag1 = UI.text(ctx, "", TextStyle.LABEL, Color.WHITE).apply { setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2)); textSize = 9.5f }
    private val trashIcon = UI.iconView(ctx, R.drawable.ic_trash, Color.WHITE, 16).apply {
        background = Shapes.circle(Palette.DANGER); setPadding(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
    }
    private val info = UI.text(ctx, "", TextStyle.CAPTION, Color.WHITE).apply {
        textSize = 10.5f; maxLines = 2
        setBackgroundColor(0x99000000.toInt()); setPadding(ctx.dp(6), ctx.dp(3), ctx.dp(6), ctx.dp(3))
    }

    init {
        setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(tag1, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(ctx.dp(6), ctx.dp(6), 0, 0) })
        addView(trashIcon, LayoutParams(ctx.dp(28), ctx.dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, ctx.dp(6), ctx.dp(6), 0) })
        addView(info, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        isClickable = true; isFocusable = true
    }

    fun bind(screen: Screen, g: DupResultGroup, p: GalleryPhoto, suggestion: Set<Uri>, changed: () -> Unit) {
        val marked = p.uri in DupChoices.remove
        val why = DupChoices.protectedBy(g, p)
        val kept = !marked && p.uri !in suggestion && g.photos.any { it.uri in suggestion }
        val green = !marked && (why != null || p === g.best)
        background = Shapes.rounded(context, Palette.SURFACE_2, 14f, if (marked) Palette.DANGER else if (green) Palette.SUCCESS else Palette.STROKE, if (marked || green) 2.5f else 1f)
        iv.alpha = if (marked) 0.5f else 1f
        DupChoices.thumb(context, screen.scope, p.uri, iv, if (sizeDp > 150) 512 else 320)
        val label = when {
            why != null -> why.uppercase(I18n.locale)
            p === g.best && !marked -> trc("badge on the photo to keep", "Best").uppercase(I18n.locale)
            kept -> trc("badge on a photo that is kept", "Keep").uppercase(I18n.locale)
            else -> null
        }
        tag1.text = label ?: ""
        tag1.visibility = if (label != null) VISIBLE else GONE
        tag1.background = Shapes.rounded(context, if (why != null) 0xFF2E7D57.toInt() else Palette.SUCCESS, 100f)
        trashIcon.visibility = if (marked) VISIBLE else GONE
        val mp = p.width.toLong() * p.height / 1_000_000.0
        val res = if (mp >= 1) tr("{0} MP", String.format(I18n.locale, "%.0f", mp)) else "${p.width}×${p.height}"
        info.text = buildString {
            append(res); append(" · "); append(Format.bytes(p.size))
            if (p.raw && why != "RAW") append(" · RAW")
            if (p.favorite && why != com.localmediatools.vision.core.KeepPlanner.favourite) append(" · ★")
            if (sizeDp > 150) { p.album?.let { append("\n"); append(it) } }
        }
        contentDescription = (if (marked) tr("{0}, will be moved to the trash", p.name) else tr("{0}, kept", p.name)) + (if (why != null) tr(", always kept: {0}", why) else "") + if (p === g.best) tr(", best photo") else ""
        setOnClickListener {
            if (!DupChoices.toggle(g, p)) Toast.makeText(context, tr("Keep at least one photo of each group."), Toast.LENGTH_SHORT).show()
            changed()
        }
        setOnLongClickListener { (screen.activity).let { DuplicatesScreen.openPhoto(it, p) }; true }
    }
}
