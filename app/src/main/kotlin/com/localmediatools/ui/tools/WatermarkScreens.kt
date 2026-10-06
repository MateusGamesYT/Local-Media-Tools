package com.localmediatools.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportJob
import com.localmediatools.image.AspectGroups
import com.localmediatools.image.WatermarkPosition
import com.localmediatools.image.WatermarkRenderer
import com.localmediatools.image.WatermarkStyle
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.WatermarkJob
import com.localmediatools.tools.WatermarkJobSpec
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.MATCH
import com.localmediatools.ui.Palette
import com.localmediatools.ui.PositionGrid
import com.localmediatools.ui.Screen
import com.localmediatools.ui.Selection
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.SliderField
import com.localmediatools.ui.TextField
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.Thumbs
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.TopBar
import com.localmediatools.ui.UI
import com.localmediatools.ui.WRAP
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import kotlinx.coroutines.launch

object WatermarkState {
    var text = ""
    var logo: MediaItem? = null
    var size = 20
    var opacity = 80
    var color = Color.WHITE
    var defaultPosition = WatermarkPosition.BOTTOM_RIGHT
    val perGroup = LinkedHashMap<String, WatermarkPosition>()

    fun positionFor(key: String) = perGroup[key] ?: defaultPosition
}

class AspectGroup(val key: String, val shape: String, val items: List<MediaItem>) {
    val label get() = "$key $shape"
}

/** Groups the selection by aspect ratio (needs probed sizes). */
fun aspectGroups(items: List<MediaItem>): List<AspectGroup> {
    val map = LinkedHashMap<String, MutableList<MediaItem>>()
    val shapes = HashMap<String, String>()
    for (i in items) {
        val d = Dims.known(i) ?: continue
        val key = AspectGroups.key(d[0], d[1]).first
        map.getOrPut(key) { ArrayList() }.add(i)
        shapes[key] = AspectGroups.shapeName(d[0], d[1])
    }
    return map.entries.sortedByDescending { it.value.size }.map { AspectGroup(it.key, shapes[it.key]!!, it.value) }
}

/** Live preview: a sample image with the watermark drawn at [position]. */
class WatermarkPreview(ctx: Context) : View(ctx) {
    private var base: Bitmap? = null
    private var logo: Bitmap? = null
    var position = WatermarkPosition.BOTTOM_RIGHT
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val empty = UI.text(ctx, "", TextStyle.CAPTION)

    init {
        background = Shapes.rounded(ctx, Palette.SURFACE_2, 16f, Palette.STROKE)
        contentDescription = "Watermark preview"
    }

    fun setSample(item: MediaItem?) {
        if (item == null) { base = null; invalidate(); return }
        Thumbs.get(context, item, context.dp(420)) { b -> base = b; invalidate() }
    }

    fun setLogo(item: MediaItem?) {
        if (item == null) { logo = null; invalidate(); return }
        Thumbs.get(context, item, 512) { b -> logo = b; invalidate() }
    }

    fun refresh() = invalidate()

    override fun onDraw(canvas: Canvas) {
        val b = base
        val pad = context.dp(10).toFloat()
        if (b == null) {
            paint.color = Palette.TEXT_3
            paint.textSize = context.dp(13).toFloat()
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Select images to preview", width / 2f, height / 2f, paint)
            return
        }
        val s = minOf((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        val w = b.width * s; val h = b.height * s
        val left = (width - w) / 2; val top = (height - h) / 2
        canvas.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF(left, top, left + w, top + h), paint)
        val style = WatermarkStyle(WatermarkState.text.ifBlank { null }, logo, WatermarkState.size, WatermarkState.opacity, WatermarkState.color)
        if (!style.hasContent) return
        canvas.save()
        canvas.translate(left, top)
        canvas.clipRect(0f, 0f, w, h)
        WatermarkRenderer(style).draw(canvas, w.toInt(), h.toInt(), position)
        canvas.restore()
    }
}

class WatermarkScreen(a: MainActivity) : ToolScreen(a, ToolId.WATERMARK) {
    private lateinit var preview: WatermarkPreview
    private lateinit var logoRow: LinearLayout
    private lateinit var groupsBox: LinearLayout
    private lateinit var defaultGrid: PositionGrid

    override fun buildOptions(container: LinearLayout) {
        container.addView(TextField(ctx, "Watermark text", "e.g. © Your Name 2026", WatermarkState.text) { WatermarkState.text = it; changed() })
        section(container, "Logo image", "Optional — PNG with transparency works best")
        logoRow = UI.horizontal(ctx)
        container.addView(logoRow, lp().apply { topMargin = ctx.dp(8) })
        rebuildLogo()
        container.addView(SliderField(ctx, "Size", 10, 50, WatermarkState.size, "%", "Of each image's shorter side; proportions never stretch") { WatermarkState.size = it; changed() }, lp().apply { topMargin = ctx.dp(16) })
        container.addView(SliderField(ctx, "Opacity", 10, 100, WatermarkState.opacity, "%") { WatermarkState.opacity = it; changed() }, lp().apply { topMargin = ctx.dp(14) })
        section(container, "Text colour")
        val colors = listOf("White" to Color.WHITE, "Black" to Color.BLACK, "Grey" to 0xFF9AA3B2.toInt(), "Gold" to 0xFFFFC857.toInt())
        container.addView(ChoiceGroup(ctx, colors, { it.first }, colors.firstOrNull { it.second == WatermarkState.color }) { WatermarkState.color = it.second; changed() }, lp().apply { topMargin = ctx.dp(10) })

        section(container, "Preview")
        preview = WatermarkPreview(ctx)
        container.addView(preview, lp(MATCH, ctx.dp(240)).apply { topMargin = ctx.dp(10) })

        section(container, "Default position", "Used for every image shape you don't set separately")
        defaultGrid = PositionGrid(ctx, WatermarkState.defaultPosition) { p -> WatermarkState.defaultPosition = p; preview.position = p; rebuildGroups() }
        container.addView(defaultGrid, lp().apply { topMargin = ctx.dp(10) })

        section(container, "Placement by image shape", "Different shapes can use different positions")
        groupsBox = UI.vertical(ctx)
        container.addView(groupsBox, lp().apply { topMargin = ctx.dp(8) })
        preview.position = WatermarkState.defaultPosition
        WatermarkState.logo?.let { preview.setLogo(it) }
        onSelectionChanged()
    }

    private fun changed() { preview.refresh(); refreshValidation() }

    private fun rebuildLogo() {
        logoRow.removeAllViews()
        val logo = WatermarkState.logo
        if (logo == null) {
            logoRow.addView(UI.secondaryButton(ctx, "Choose logo", R.drawable.ic_gallery) {
                activity.pickMedia(com.localmediatools.ui.PickKind.IMAGES, multiple = false) { uris -> setLogo(uris.firstOrNull()) }
            })
            logoRow.addView(UI.ghostButton(ctx, "From files", R.drawable.ic_file) {
                activity.pickDocuments(com.localmediatools.ui.PickKind.IMAGES, multiple = false) { uris -> setLogo(uris.firstOrNull()) }
            })
        } else {
            val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = Shapes.rounded(ctx, Palette.SURFACE_3, 10f) }
            Thumbs.load(ctx, logo, 160, iv)
            logoRow.addView(iv, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
            logoRow.addView(UI.text(ctx, logo.name, TextStyle.BODY).apply { setPadding(ctx.dp(12), 0, ctx.dp(8), 0); maxLines = 2 }, lp(0, WRAP, 1f))
            logoRow.addView(UI.iconButton(ctx, R.drawable.ic_close, "Remove logo", Palette.TEXT_2) {
                WatermarkState.logo = null; preview.setLogo(null); rebuildLogo(); changed()
            })
        }
    }

    private fun setLogo(uri: android.net.Uri?) {
        uri ?: return
        scope.launchIo({ com.localmediatools.core.MediaProbe.describe(activity, uri) }) { item ->
            if (item.kind != com.localmediatools.core.MediaKind.IMAGE && item.kind != com.localmediatools.core.MediaKind.GIF) {
                android.widget.Toast.makeText(ctx, "That file isn't an image.", android.widget.Toast.LENGTH_LONG).show()
                return@launchIo
            }
            WatermarkState.logo = item
            preview.setLogo(item)
            rebuildLogo()
            changed()
        }
    }

    override fun onSelectionChanged() {
        if (!::preview.isInitialized) return
        val items = selection.usable
        preview.setSample(items.firstOrNull())
        Dims.request(ctx, items) { rebuildGroups() }
    }

    override fun onShow() {
        super.onShow()
        if (::groupsBox.isInitialized) rebuildGroups()
    }

    private fun rebuildGroups() {
        groupsBox.removeAllViews()
        val groups = aspectGroups(selection.usable)
        if (groups.isEmpty()) {
            groupsBox.addView(UI.text(ctx, "Select images to see the shapes in your batch.", TextStyle.CAPTION))
            return
        }
        for (g in groups) {
            val row = UI.horizontal(ctx)
            row.setPadding(0, ctx.dp(6), 0, ctx.dp(6))
            row.addView(UI.text(ctx, g.label, TextStyle.BODY), lp(0, WRAP, 1f))
            row.addView(UI.text(ctx, "${g.items.size} · ${WatermarkState.positionFor(g.key).label}", TextStyle.CAPTION, if (WatermarkState.perGroup.containsKey(g.key)) Palette.ACCENT else Palette.TEXT_2))
            groupsBox.addView(row)
        }
        val btn = UI.secondaryButton(ctx, if (groups.size > 1) "Set placement for each shape (${groups.size})" else "Set placement for this shape", R.drawable.ic_tune) {
            push(WatermarkPlacementScreen(activity, selection))
        }
        groupsBox.addView(btn, lp().apply { topMargin = ctx.dp(8) })
        if (WatermarkState.perGroup.isNotEmpty()) {
            groupsBox.addView(UI.ghostButton(ctx, "Reset to default position") { WatermarkState.perGroup.clear(); rebuildGroups() }, lp().apply { topMargin = ctx.dp(4) })
        }
    }

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (WatermarkState.text.isBlank() && WatermarkState.logo == null) return "Add watermark text or choose a logo"
        return null
    }

    override fun outputNaming() = "name_wm.jpg / .png / .webp (same type of format as each original)"

    override fun createJob(items: List<MediaItem>): ExportJob {
        val groups = aspectGroups(items)
        val placements = HashMap<String, WatermarkPosition>()
        for (g in groups) placements[g.key] = WatermarkState.positionFor(g.key)
        return WatermarkJob(items, WatermarkJobSpec(WatermarkState.text.ifBlank { null }, WatermarkState.logo?.uri, WatermarkState.logo?.name,
            WatermarkState.size, WatermarkState.opacity, WatermarkState.color, placements, WatermarkState.defaultPosition))
    }
}

/** Step-by-step placement for each aspect-ratio group, ending with a summary of the plan. */
class WatermarkPlacementScreen(activity: MainActivity, private val selection: Selection) : Screen(activity) {
    private val groups = aspectGroups(selection.usable)
    private var step = 0
    private lateinit var body: LinearLayout
    private lateinit var back: ButtonView
    private lateinit var next: ButtonView

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Palette.BG) }
        val col = UI.vertical(ctx)
        col.addView(TopBar(this, "Watermark placement"))
        body = UI.vertical(ctx, 16, 0)
        col.addView(ScrollView(ctx).apply { addView(body); clipToPadding = false; setPadding(0, 0, 0, ctx.dp(110)) }, lp(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        val bar = UI.horizontal(ctx)
        bar.background = Shapes.rounded(ctx, Palette.SURFACE, 24f, Palette.STROKE)
        bar.setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        back = UI.secondaryButton(ctx, "Back") { if (step > 0) { step--; render() } else pop() }
        next = UI.primaryButton(ctx, "Next") { if (step < groups.size) { step++; render() } else pop() }
        bar.addView(back, lp(0, WRAP, 1f).apply { rightMargin = ctx.dp(6) })
        bar.addView(next, lp(0, WRAP, 1.6f).apply { leftMargin = ctx.dp(6) })
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(ctx.dp(10), 0, ctx.dp(10), ctx.dp(10)) })
        render()
        return root
    }

    private fun render() {
        body.removeAllViews()
        if (groups.isEmpty()) {
            body.addView(UI.text(ctx, "No image sizes are known yet. Go back and wait for the selection to load.", TextStyle.BODY_2))
            next.label = "Done"
            return
        }
        if (step < groups.size) {
            val g = groups[step]
            body.addView(UI.text(ctx, "Shape ${step + 1} of ${groups.size}", TextStyle.LABEL), lp().apply { topMargin = ctx.dp(4) })
            body.addView(UI.text(ctx, g.label, TextStyle.TITLE).apply { setPadding(0, ctx.dp(4), 0, 0) })
            body.addView(UI.text(ctx, "${g.items.size} image(s) with this shape", TextStyle.BODY_2).apply { setPadding(0, ctx.dp(4), 0, 0) })
            val prev = WatermarkPreview(ctx)
            WatermarkState.logo?.let { prev.setLogo(it) }
            prev.setSample(g.items.first())
            prev.position = WatermarkState.positionFor(g.key)
            body.addView(prev, lp(MATCH, ctx.dp(280)).apply { topMargin = ctx.dp(14) })
            body.addView(UI.text(ctx, "Choose where the watermark goes", TextStyle.SUBTITLE), lp().apply { topMargin = ctx.dp(16) })
            body.addView(PositionGrid(ctx, WatermarkState.positionFor(g.key)) { p ->
                WatermarkState.perGroup[g.key] = p
                prev.position = p
            }, lp().apply { topMargin = ctx.dp(10) })
            if (g.items.size > 1) {
                val strip = UI.horizontal(ctx)
                for (it in g.items.take(8)) {
                    val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Shapes.rounded(ctx, Palette.SURFACE_2, 8f); clipToOutline = true }
                    Thumbs.load(ctx, it, ctx.dp(96), iv)
                    strip.addView(iv, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { rightMargin = ctx.dp(6) })
                }
                body.addView(strip, lp().apply { topMargin = ctx.dp(14) })
            }
            back.label = if (step == 0) "Cancel" else "Back"
            next.label = if (step == groups.size - 1) "Review plan" else "Next shape"
        } else {
            body.addView(UI.text(ctx, "Placement plan", TextStyle.TITLE), lp().apply { topMargin = ctx.dp(4) })
            body.addView(UI.text(ctx, "This is how the watermark will be placed when you export.", TextStyle.BODY_2).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(10)) })
            for (g in groups) {
                val c = UI.card(ctx, 14)
                val row = UI.horizontal(ctx)
                val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Shapes.rounded(ctx, Palette.SURFACE_2, 10f); clipToOutline = true }
                Thumbs.load(ctx, g.items.first(), ctx.dp(112), iv)
                row.addView(iv, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))
                row.addView(UI.vertical(ctx).apply {
                    setPadding(ctx.dp(12), 0, 0, 0)
                    addView(UI.text(ctx, g.label, TextStyle.SUBTITLE))
                    addView(UI.text(ctx, "${g.items.size} image(s) → ${WatermarkState.positionFor(g.key).label}", TextStyle.CAPTION, Palette.ACCENT).apply { setPadding(0, ctx.dp(3), 0, 0) })
                }, lp(0, WRAP, 1f))
                c.addView(row)
                body.addView(c, lp().apply { bottomMargin = ctx.dp(10) })
            }
            body.addView(UI.note(ctx, "Size ${WatermarkState.size}% of the shorter side · opacity ${WatermarkState.opacity}%. Images whose shape isn't listed use the default position (${WatermarkState.defaultPosition.label}).", UI.NoteKind.INFO))
            back.label = "Back"
            next.label = "Done"
        }
    }
}

/** Runs [io] on a background thread, then [ui] on the main thread. */
fun <T> kotlinx.coroutines.CoroutineScope.launchIo(io: () -> T, ui: (T) -> Unit) {
    launch {
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io() }
        ui(r)
    }
}

@Suppress("unused")
private fun unusedText(t: TextView) = t
