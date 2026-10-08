package com.localmediatools.ui

import android.graphics.Color
import android.net.Uri
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
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.SniffedFormat
import com.localmediatools.tools.ToolId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PickKind(val mimes: Array<String>, val noun: String, val galleryType: String?) {
    IMAGES(arrayOf("image/*"), "images", "image/*"),
    VIDEOS(arrayOf("video/*"), "videos", "video/*"),
    GIFS(arrayOf("image/gif"), "GIFs", "image/gif"),
    PDFS(arrayOf("application/pdf"), "PDFs", null),
    MEDIA(arrayOf("image/*", "video/*"), "photos or videos", "*/*"),
}

/** Which files each tool accepts and how selection behaves. */
object ToolRules {
    fun pickKind(t: ToolId): PickKind = when (t) {
        ToolId.SPLIT_VIDEO, ToolId.TRIM_VIDEO, ToolId.OPTIMIZE_VIDEO, ToolId.COMPRESS_VIDEO, ToolId.REMOVE_AUDIO, ToolId.VIDEO_TO_GIF, ToolId.EXTRACT_AUDIO -> PickKind.VIDEOS
        ToolId.COMPRESS_GIF, ToolId.OPTIMIZE_GIF -> PickKind.GIFS
        ToolId.PDF_TO_IMAGES, ToolId.MERGE_PDFS, ToolId.EXTRACT_PDF_PAGES -> PickKind.PDFS
        ToolId.REMOVE_METADATA -> PickKind.MEDIA
        else -> PickKind.IMAGES
    }

    /** Tools that work on one file at a time. */
    fun single(t: ToolId) = t == ToolId.TRIM_VIDEO

    fun minItems(t: ToolId) = when (t) { ToolId.MERGE_IMAGES, ToolId.STITCH, ToolId.MERGE_PDFS -> 2; else -> 1 }

    fun orderMatters(t: ToolId) = t in setOf(ToolId.MERGE_IMAGES, ToolId.STITCH, ToolId.IMAGES_TO_PDF, ToolId.MERGE_PDFS, ToolId.PDF_SCANNER)

    /** Null when the item can be processed by [t], otherwise a short reason. */
    fun issue(t: ToolId, item: MediaItem): String? {
        item.readError?.let { return it }
        return when (pickKind(t)) {
            PickKind.VIDEOS -> if (item.kind == MediaKind.VIDEO || (item.format == SniffedFormat.UNKNOWN && item.mime?.startsWith("video/") == true)) null else "Not a video file"
            PickKind.GIFS -> if (item.format == SniffedFormat.GIF) null else "Not a GIF file"
            PickKind.PDFS -> if (item.kind == MediaKind.PDF) null else "Not a PDF file"
            PickKind.MEDIA -> if (item.kind == MediaKind.IMAGE || item.kind == MediaKind.GIF || item.kind == MediaKind.VIDEO) null else "Not a photo or video"
            PickKind.IMAGES -> when {
                item.kind == MediaKind.IMAGE -> null
                item.kind == MediaKind.GIF -> if (t == ToolId.OPTIMIZE_IMAGES) "Use the GIF optimizer for GIFs" else null
                else -> "Not an image file"
            }
        }
    }
}

/** The files chosen for one tool, in the user's order. Kept while the app runs. */
class Selection(val tool: ToolId) {
    val items = ArrayList<MediaItem>()
    private val listeners = ArrayList<() -> Unit>()
    var loading = 0; private set

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun changed() = listeners.toList().forEach { it() }

    val usable: List<MediaItem> get() = items.filter { ToolRules.issue(tool, it) == null }
    val unsupported: List<MediaItem> get() = items.filter { ToolRules.issue(tool, it) != null }

    fun add(list: List<MediaItem>) {
        val keys = items.map { it.key }.toHashSet()
        for (i in list) if (keys.add(i.key)) items.add(i)
        changed()
    }

    fun remove(i: Int) { if (i in items.indices) { items.removeAt(i); changed() } }
    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        val x = items.removeAt(from); items.add(to, x); changed()
    }
    fun clear() { items.clear(); changed() }

    /** Describes picked URIs off the main thread, then appends them in the picked order. */
    fun addUris(activity: MainActivity, uris: List<Uri>) {
        if (uris.isEmpty()) return
        loading++
        changed()
        activity.lifecycleScope.launch {
            val described = withContext(Dispatchers.IO) { uris.map { MediaProbe.describe(activity, it) } }
            loading--
            add(described)
        }
    }

    companion object {
        private val store = HashMap<ToolId, Selection>()
        fun of(t: ToolId) = store.getOrPut(t) { Selection(t) }
    }
}

/** Selection card used at the top of every tool screen. */
class SelectionPanel(
    private val screen: Screen,
    private val selection: Selection,
    private val title: String = "Select files",
) : LinearLayout(screen.ctx) {
    private val kind = ToolRules.pickKind(selection.tool)
    private val summary = UI.text(context, "", TextStyle.BODY_2)
    private val strip = LinearLayout(context)
    private val stripScroll = HorizontalScrollView(context)
    private val empty: View
    private val issues = LinearLayout(context).apply { orientation = VERTICAL }
    private val actions = FlowLayout(context)
    private val reviewBtn: ButtonView
    private val clearBtn: ButtonView
    private val listener = { refresh() }

    init {
        orientation = VERTICAL
        background = Shapes.rounded(context, Palette.SURFACE, 22f, Palette.STROKE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        val head = UI.horizontal(context)
        head.addView(StepBadge(context, 1))
        head.addView(UI.text(context, title, TextStyle.SUBTITLE).apply { setPadding(dp(10), 0, 0, 0) }, lp(0, WRAP, 1f))
        addView(head)
        addView(summary.apply { setPadding(0, dp(8), 0, 0) })

        empty = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = Shapes.clickable(context, Palette.SURFACE_2, 16f, Palette.STROKE)
            setPadding(dp(16), dp(22), dp(16), dp(22))
            addView(UI.iconView(context, when (kind) { PickKind.VIDEOS -> R.drawable.ic_video; PickKind.PDFS -> R.drawable.ic_file; else -> R.drawable.ic_gallery }, Palette.ACCENT, 30))
            val one = ToolRules.single(selection.tool)
            addView(UI.text(context, if (one) "Choose a ${kind.noun.removeSuffix("s")}" else "Choose ${kind.noun}", TextStyle.SUBTITLE).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(2)) })
            addView(UI.text(context, when {
                one -> "One at a time"
                ToolRules.orderMatters(selection.tool) -> "They'll be used in the order you select them"
                else -> "Select as many as you like"
            }, TextStyle.CAPTION).apply { gravity = Gravity.CENTER })
            isClickable = true; isFocusable = true
            contentDescription = "Choose ${kind.noun}"
            setOnClickListener { if (kind.galleryType != null) pickGallery() else pickFiles() }
        }
        addView(empty, lp().apply { topMargin = dp(12) })

        strip.orientation = HORIZONTAL
        stripScroll.isHorizontalScrollBarEnabled = false
        stripScroll.addView(strip)
        addView(stripScroll, lp().apply { topMargin = dp(12) })
        addView(issues, lp().apply { topMargin = dp(8) })

        if (kind.galleryType != null) actions.addView(UI.secondaryButton(context, "Gallery", R.drawable.ic_gallery) { pickGallery() })
        actions.addView(UI.secondaryButton(context, "Files", R.drawable.ic_file) { pickFiles() })
        if (!ToolRules.single(selection.tool)) actions.addView(UI.secondaryButton(context, "Folder", R.drawable.ic_folder) { pickFolder() })
        reviewBtn = UI.secondaryButton(context, if (ToolRules.orderMatters(selection.tool)) "Review & order" else "Review", R.drawable.ic_reorder) {
            screen.push(SelectionReviewScreen(screen.activity, selection))
        }
        clearBtn = UI.ghostButton(context, "Clear", R.drawable.ic_trash) { selection.clear() }
        actions.addView(reviewBtn)
        actions.addView(clearBtn)
        addView(actions, lp().apply { topMargin = dp(12) })
        selection.listen(listener)
        refresh()
    }

    fun detach() = selection.unlisten(listener)

    private val single get() = ToolRules.single(selection.tool)
    private fun picked(uris: List<android.net.Uri>) {
        if (single) { if (uris.isNotEmpty()) { selection.clear(); selection.addUris(screen.activity, uris.take(1)) } }
        else selection.addUris(screen.activity, uris)
    }
    private fun pickGallery() = screen.activity.pickMedia(kind, multiple = !single) { picked(it) }
    private fun pickFiles() = screen.activity.pickDocuments(kind, multiple = !single) { picked(it) }
    private fun pickFolder() = screen.activity.pickFolder(kind) { uris -> selection.addUris(screen.activity, uris) }

    fun refresh() {
        val items = selection.items
        val usable = selection.usable
        val bad = selection.unsupported
        val total = usable.sumOf { it.size.coerceAtLeast(0) }
        summary.text = when {
            selection.loading > 0 -> "Reading selected files…"
            items.isEmpty() -> "Nothing selected yet."
            else -> buildString {
                append("${usable.size} ${if (usable.size == 1) kind.noun.removeSuffix("s") else kind.noun} selected")
                if (total > 0) append(" · ${Format.bytes(total)}")
                if (bad.isNotEmpty()) append(" · ${bad.size} can't be used")
            }
        }
        empty.visibility = if (items.isEmpty()) VISIBLE else GONE
        stripScroll.visibility = if (items.isEmpty()) GONE else VISIBLE
        reviewBtn.visibility = if (items.isEmpty()) GONE else VISIBLE
        clearBtn.visibility = if (items.isEmpty()) GONE else VISIBLE
        strip.removeAllViews()
        val show = items.take(40)
        for ((i, item) in show.withIndex()) strip.addView(thumb(item, i), LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(8) })
        if (items.size > show.size) {
            strip.addView(UI.text(context, "+${items.size - show.size}", TextStyle.SUBTITLE, Palette.TEXT_2).apply {
                gravity = Gravity.CENTER
                background = Shapes.clickable(context, Palette.SURFACE_2, 14f)
                setOnClickListener { screen.push(SelectionReviewScreen(screen.activity, selection)) }
            }, LinearLayout.LayoutParams(dp(72), dp(72)))
        }
        issues.removeAllViews()
        if (bad.isNotEmpty()) {
            val names = bad.take(3).joinToString { "${it.name} (${ToolRules.issue(selection.tool, it)})" }
            issues.addView(UI.note(context, "${bad.size} file(s) will be skipped: $names${if (bad.size > 3) "…" else ""}", UI.NoteKind.WARN))
        }
    }

    private fun thumb(item: MediaItem, index: Int): View {
        val f = FrameLayout(context)
        val bad = ToolRules.issue(selection.tool, item) != null
        f.background = Shapes.rounded(context, Palette.SURFACE_2, 14f, if (bad) Palette.DANGER else null, if (bad) 2f else 1f)
        f.clipToOutline = true
        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        Thumbs.load(context, item, dp(144), iv) { b ->
            if (b == null) iv.setImageDrawable(context.icon(iconFor(item), Palette.TEXT_3)).also { iv.scaleType = ImageView.ScaleType.CENTER_INSIDE; iv.setPadding(dp(20), dp(20), dp(20), dp(20)) }
        }
        if (ToolRules.orderMatters(selection.tool)) {
            f.addView(UI.text(context, "${index + 1}", TextStyle.CAPTION, Color.WHITE).apply {
                background = Shapes.pill(context, 0xCC000000.toInt())
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
        }
        if (bad) f.addView(UI.iconView(context, R.drawable.ic_warning, Palette.DANGER, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(5), dp(5)) })
        f.contentDescription = "${index + 1}: ${item.name}${if (bad) ", can't be used" else ""}"
        return f
    }

    companion object {
        fun iconFor(item: MediaItem) = when (item.kind) {
            MediaKind.VIDEO -> R.drawable.ic_video
            MediaKind.PDF -> R.drawable.ic_file
            else -> R.drawable.ic_image
        }
    }
}

/** Small circled step number. */
class StepBadge(ctx: android.content.Context, n: Int) : TextView(ctx) {
    init {
        text = n.toString()
        style(TextStyle.CAPTION, Palette.ACCENT)
        typeface = typeface(700)
        gravity = Gravity.CENTER
        background = Shapes.circle(Palette.ACCENT_DARK)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

/** Full list of selected files with reorder / remove, backed by a recycling ListView. */
class SelectionReviewScreen(activity: MainActivity, private val selection: Selection) : Screen(activity) {
    private lateinit var adapter: BaseAdapter
    private lateinit var header: TextView
    private val listener = { adapter.notifyDataSetChanged(); updateHeader() }

    override fun createView(): View {
        val root = UI.vertical(ctx)
        root.setBackgroundColor(Palette.BG)
        root.addView(TopBar(this, "Selected files"))
        header = UI.text(ctx, "", TextStyle.BODY_2).apply { setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(20), ctx.dp(8)) }
        root.addView(header)
        val order = ToolRules.orderMatters(selection.tool)
        if (order) root.addView(UI.note(ctx, "Files are processed from top to bottom. Use the arrows to change the order.", UI.NoteKind.INFO).apply { margins(16, 0, 16, 8) })
        val list = ListView(ctx).apply {
            divider = null
            dividerHeight = 0
            setPadding(ctx.dp(12), 0, ctx.dp(12), ctx.dp(24))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = selection.items.size
            override fun getItem(p: Int) = selection.items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val row = (convert as? ReviewRow) ?: ReviewRow()
                row.bind(p, selection.items[p], order)
                return row
            }
        }
        list.adapter = adapter
        root.addView(list, lp(MATCH, 0, 1f))
        selection.listen(listener)
        updateHeader()
        return root
    }

    private fun updateHeader() {
        val bad = selection.unsupported.size
        header.text = "${selection.items.size} file(s)" + if (bad > 0) " · $bad will be skipped" else ""
    }

    override fun onDestroy() {
        selection.unlisten(listener)
        super.onDestroy()
    }

    private inner class ReviewRow : LinearLayout(ctx) {
        val thumb = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val name = UI.text(ctx, "", TextStyle.BODY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        val info = UI.text(ctx, "", TextStyle.CAPTION)
        val num = UI.text(ctx, "", TextStyle.CAPTION, Palette.TEXT_3).apply { minWidth = ctx.dp(24) }
        var index = 0
        val up = UI.iconButton(ctx, R.drawable.ic_up, "Move up") { selection.move(index, index - 1) }
        val down = UI.iconButton(ctx, R.drawable.ic_down, "Move down") { selection.move(index, index + 1) }
        val remove = UI.iconButton(ctx, R.drawable.ic_close, "Remove", Palette.TEXT_2) { selection.remove(index) }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(4), ctx.dp(6))
            addView(num)
            val tf = FrameLayout(ctx).apply { background = Shapes.rounded(ctx, Palette.SURFACE_2, 10f); clipToOutline = true; addView(thumb, FrameLayout.LayoutParams(MATCH, MATCH)) }
            addView(tf, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)))
            val texts = UI.vertical(ctx).apply { setPadding(ctx.dp(12), 0, ctx.dp(4), 0); addView(name); addView(info.apply { setPadding(0, ctx.dp(3), 0, 0) }) }
            addView(texts, lp(0, WRAP, 1f))
            addView(up); addView(down); addView(remove)
            layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
        }

        fun bind(i: Int, item: MediaItem, order: Boolean) {
            index = i
            num.text = "${i + 1}"
            name.text = item.name
            val issue = ToolRules.issue(selection.tool, item)
            info.text = if (issue != null) issue else "${item.format.label} · ${Format.bytes(item.size)}"
            info.setTextColor(if (issue != null) Palette.DANGER else Palette.TEXT_2)
            up.visibility = if (order) VISIBLE else GONE
            down.visibility = if (order) VISIBLE else GONE
            up.isEnabled = i > 0; up.alpha = if (i > 0) 1f else 0.3f
            down.isEnabled = i < selection.items.size - 1; down.alpha = if (down.isEnabled) 1f else 0.3f
            Thumbs.load(ctx, item, ctx.dp(104), thumb) { b ->
                if (b == null) thumb.setImageDrawable(ctx.icon(SelectionPanel.iconFor(item), Palette.TEXT_3))
            }
            contentDescription = "${i + 1}. ${item.name}. ${info.text}"
        }
    }
}
