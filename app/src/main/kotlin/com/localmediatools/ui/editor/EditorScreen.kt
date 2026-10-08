package com.localmediatools.ui.editor

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.codec.edit.AdjustKey
import com.localmediatools.codec.edit.CropRect
import com.localmediatools.codec.edit.FilterPreset
import com.localmediatools.codec.edit.FilterSpec
import com.localmediatools.codec.edit.GeometryPlan
import com.localmediatools.core.Errors
import com.localmediatools.core.MediaItem
import com.localmediatools.core.SniffedFormat
import com.localmediatools.edit.EditRenderer
import com.localmediatools.edit.EditState
import com.localmediatools.edit.Inpainters
import com.localmediatools.edit.PatchKind
import com.localmediatools.edit.Stroke
import com.localmediatools.export.ExportManager
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.tools.EditPhotoJob
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.BipolarSlider
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.ChipRow
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.MATCH
import com.localmediatools.ui.Palette
import com.localmediatools.ui.ResultsScreen
import com.localmediatools.ui.Screen
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.SliderField
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.UI
import com.localmediatools.ui.WRAP
import com.localmediatools.ui.badge
import com.localmediatools.ui.dp
import com.localmediatools.ui.icon
import com.localmediatools.ui.lp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class EditorMode(val label: String, val icon: Int) {
    CROP("Crop", R.drawable.ic_crop),
    ADJUST("Adjust", R.drawable.ic_tune),
    FILTERS("Looks", R.drawable.ic_filter),
    ERASE("Erase", R.drawable.ic_wand),
    BLUR("Blur", R.drawable.ic_tool_redact),
}

/** Single-photo editor: crop & straighten, adjustments, looks, AI eraser and blur/pixelate brushes. */
class EditorScreen(activity: MainActivity, private val item: MediaItem, private val tool: ToolId, initial: EditorMode) : Screen(activity) {
    val session = EditorSession(activity.applicationContext, item)
    var mode = initial; private set
    private lateinit var canvas: EditorCanvas
    private lateinit var panel: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var undoBtn: ImageView
    private lateinit var redoBtn: ImageView
    private lateinit var saveBtn: ButtonView
    private lateinit var busyOverlay: LinearLayout
    private lateinit var busyText: TextView
    private var loaded = false
    @Volatile private var generation = 0
    private var renderJob: Job? = null
    private var adjustKey = AdjustKey.EXPOSURE
    private var blurKind = PatchKind.BLUR
    private var blurStrength = 0.55f
    private var brushSize = 0.35f
    /** Finished when the last background render has been applied (for tests). */
    @Volatile var renders = 0; private set

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val col = UI.vertical(ctx)
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // Top bar.
        val bar = UI.horizontal(ctx).apply { setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(10), ctx.dp(6)) }
        bar.addView(UI.iconButton(ctx, R.drawable.ic_close, "Close editor") { onBack() })
        bar.addView(UI.text(ctx, tool.title, TextStyle.SUBTITLE).apply { maxLines = 1; isAccessibilityHeading = true }, lp(0, WRAP, 1f).apply { leftMargin = ctx.dp(2) })
        undoBtn = UI.iconButton(ctx, R.drawable.ic_undo, "Undo") { undo() }
        redoBtn = UI.iconButton(ctx, R.drawable.ic_redo, "Redo") { redo() }
        val compare = UI.iconButton(ctx, R.drawable.ic_compare, "Hold to compare with the original") {}
        compare.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { canvas.comparing = true; v.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { canvas.comparing = false; v.isPressed = false; if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick() }
            }
            true
        }
        bar.addView(undoBtn); bar.addView(redoBtn); bar.addView(compare)
        saveBtn = UI.primaryButton(ctx, "Save") { showSaveSheet() }
        saveBtn.minimumHeight = ctx.dp(40)
        saveBtn.setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        bar.addView(saveBtn, LinearLayout.LayoutParams(WRAP, ctx.dp(40)).apply { leftMargin = ctx.dp(6) })
        col.addView(bar)

        // Canvas with a busy overlay.
        val stage = FrameLayout(ctx)
        canvas = EditorCanvas(ctx)
        stage.addView(canvas, FrameLayout.LayoutParams(MATCH, MATCH))
        busyOverlay = UI.vertical(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
            isClickable = true
            addView(ProgressBar(ctx).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT) }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
            busyText = UI.text(ctx, "Opening photo…", TextStyle.SUBTITLE, Color.WHITE).apply { setPadding(0, ctx.dp(12), 0, 0); gravity = Gravity.CENTER }
            addView(busyText)
        }
        stage.addView(busyOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        col.addView(stage, lp(MATCH, 0, 1f))

        // Panel + tabs.
        val bottom = UI.vertical(ctx).apply { background = Shapes.rounded(ctx, Palette.BG_2, 0f) }
        panel = FrameLayout(ctx).apply { setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(6)) }
        bottom.addView(panel, lp(MATCH, ctx.dp(158)))
        tabs = UI.horizontal(ctx).apply { setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(8)) }
        for (m in EditorMode.entries) {
            val cell = UI.vertical(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(8), 0, ctx.dp(6))
                isClickable = true; isFocusable = true
                tag = m
                contentDescription = m.label
                setOnClickListener { setMode(m) }
                addView(UI.iconView(ctx, m.icon, Palette.TEXT_3, 22))
                addView(UI.text(ctx, m.label, TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(4), 0, 0); textSize = 11.5f })
            }
            tabs.addView(cell, lp(0, WRAP, 1f))
        }
        bottom.addView(tabs)
        col.addView(bottom)

        canvas.onCropChanged = { c, done -> onCrop(c, done) }
        canvas.onStroke = { pts, r -> onStroke(pts, r) }
        refreshTabs(); refreshUndo()
        scope.launch {
            try {
                withContext(session.worker) { session.load() }
                loaded = true
                busy(null)
                setMode(mode, force = true)
            } catch (e: Throwable) {
                Toast.makeText(ctx, "This photo can't be edited: ${Errors.describe(e)}", Toast.LENGTH_LONG).show()
                pop()
            }
        }
        return root
    }

    private fun busy(text: String?) {
        busyOverlay.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) busyText.text = text
        canvas.busy = text != null
        saveBtn.isEnabled = text == null && loaded
    }

    // ------------------------------------------------------------------ modes
    fun setMode(m: EditorMode, force: Boolean = false) {
        if (!loaded) { mode = m; refreshTabs(); return }
        if (m == mode && !force) return
        // Leaving crop with an uncommitted drag: keep it.
        session.live?.let { session.commit(it) }
        mode = m
        refreshTabs()
        canvas.mode = when (m) { EditorMode.CROP -> EditorCanvas.Mode.CROP; EditorMode.ERASE, EditorMode.BLUR -> EditorCanvas.Mode.BRUSH; else -> EditorCanvas.Mode.VIEW }
        canvas.hint = when (m) {
            EditorMode.ERASE -> "Brush over what you want to remove"
            EditorMode.BLUR -> "Paint over what you want to hide"
            else -> null
        }
        canvas.brushColor = if (m == EditorMode.ERASE) 0x77FF3D7F else 0x773D8BFF
        buildPanel()
        requestRender(full = true)
        if (m == EditorMode.ERASE) scope.launch {
            val engine = withContext(session.worker) { session.ensureInpainter() }
            eraserLabel?.text = engine.label
            eraserBadge?.let { b -> b.text = if (engine.isAi) "ON-DEVICE AI" else "BASIC FILL"; b.background = badge(ctx, "", if (engine.isAi) Palette.AI else intArrayOf(Palette.SURFACE_3, Palette.SURFACE_3)).background }
        }
    }

    private fun refreshTabs() {
        for (i in 0 until tabs.childCount) {
            val cell = tabs.getChildAt(i) as LinearLayout
            val sel = cell.tag == mode
            (cell.getChildAt(0) as ImageView).drawable.setTint(if (sel) Palette.ACCENT else Palette.TEXT_3)
            (cell.getChildAt(1) as TextView).setTextColor(if (sel) Palette.TEXT else Palette.TEXT_3)
            cell.background = if (sel) Shapes.rounded(ctx, Palette.ACCENT_DARK, 16f) else null
            cell.contentDescription = (cell.tag as EditorMode).label + if (sel) ", selected" else ""
        }
    }

    private fun refreshUndo() {
        undoBtn.isEnabled = session.canUndo; undoBtn.alpha = if (session.canUndo) 1f else 0.35f
        redoBtn.isEnabled = session.canRedo; redoBtn.alpha = if (session.canRedo) 1f else 0.35f
    }

    private fun commit(s: EditState) {
        scope.launch {
            withContext(session.worker) { session.commit(s) }
            refreshUndo(); requestRender(full = true)
            if (mode == EditorMode.FILTERS || mode == EditorMode.ADJUST || mode == EditorMode.CROP) buildPanel(keepScroll = true)
        }
    }

    private fun undo() = scope.launch {
        if (withContext(session.worker) { session.undo() }) { refreshUndo(); buildPanel(keepScroll = true); requestRender(full = true) }
    }

    private fun redo() = scope.launch {
        if (withContext(session.worker) { session.redo() }) { refreshUndo(); buildPanel(keepScroll = true); requestRender(full = true) }
    }

    // ------------------------------------------------------------------ rendering
    private fun previewSide(full: Boolean) = if (full) 1600 else 900

    fun requestRender(full: Boolean) {
        if (!loaded) return
        val gen = ++generation
        val s = session.current
        val m = mode
        renderJob = scope.launch {
            val result = withContext(session.worker) {
                if (gen != generation) return@withContext null
                if (m == EditorMode.CROP) session.renderFrame(s.copy(geometry = s.geometry.copy(crop = CropRect())), previewSide(full)) else session.renderOutput(s, previewSide(full))
            } ?: return@launch
            if (gen != generation) return@launch
            canvas.setImage(result.first)
            shownState = s; shownScale = result.second
            if (m == EditorMode.CROP) {
                val plan = session.plan(s)
                canvas.frameW = plan.frameW; canvas.frameH = plan.frameH
                canvas.crop = s.geometry.crop
            }
            if (canvas.comparing.not() && !compareReady) loadCompare()
            renders++
        }
    }

    private var shownState: EditState? = null
    private var shownScale = 1.0
    private var compareReady = false
    private fun loadCompare() {
        compareReady = true
        scope.launch { canvas.setCompareImage(withContext(session.worker) { session.renderOriginal(1600) }) }
    }

    // ------------------------------------------------------------------ crop & retouch callbacks
    private fun onCrop(c: CropRect, done: Boolean) {
        val s = session.current
        val next = s.copy(geometry = s.geometry.copy(crop = c))
        if (done) commit(next) else session.live = next
        cropInfo?.text = cropLabel(next)
    }

    private fun onStroke(pts: FloatArray, radiusBitmap: Float) {
        if (mode != EditorMode.ERASE && mode != EditorMode.BLUR) return
        val s = shownState ?: return
        val plan = session.plan(s)
        val outScale = shownScale
        // Bitmap px → output px → full-resolution photo px.
        val inv = plan.inverse
        val src = FloatArray(pts.size)
        for (i in 0 until pts.size / 2) {
            val ox = pts[2 * i] / outScale; val oy = pts[2 * i + 1] / outScale
            src[2 * i] = inv.mapX(ox, oy).toFloat(); src[2 * i + 1] = inv.mapY(ox, oy).toFloat()
        }
        val stroke = Stroke(src, (radiusBitmap / outScale).toFloat())
        val kind = if (mode == EditorMode.ERASE) PatchKind.ERASE else blurKind
        busy(if (kind == PatchKind.ERASE) "Erasing…" else "Applying…")
        scope.launch {
            try {
                withContext(session.worker) { session.retouch(kind, listOf(stroke), blurStrength) }
                refreshUndo()
                requestRender(full = true)
            } catch (e: Throwable) {
                Toast.makeText(ctx, Errors.describe(e), Toast.LENGTH_LONG).show()
            } finally { busy(null) }
        }
    }

    // ------------------------------------------------------------------ panels
    private var cropInfo: TextView? = null
    private var eraserLabel: TextView? = null
    private var eraserBadge: TextView? = null
    private var scrollMemory = 0

    private fun buildPanel(keepScroll: Boolean = false) {
        val prevScroll = (panel.getChildAt(0) as? LinearLayout)?.let { col -> (0 until col.childCount).map { col.getChildAt(it) }.filterIsInstance<HorizontalScrollView>().firstOrNull()?.scrollX } ?: 0
        if (keepScroll) scrollMemory = prevScroll
        panel.removeAllViews()
        cropInfo = null; eraserLabel = null; eraserBadge = null
        val col = UI.vertical(ctx)
        when (mode) {
            EditorMode.CROP -> cropPanel(col)
            EditorMode.ADJUST -> adjustPanel(col)
            EditorMode.FILTERS -> filterPanel(col)
            EditorMode.ERASE -> erasePanel(col)
            EditorMode.BLUR -> blurPanel(col)
        }
        panel.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        if (keepScroll) (0 until col.childCount).map { col.getChildAt(it) }.filterIsInstance<HorizontalScrollView>().firstOrNull()?.let { h -> h.post { h.scrollTo(scrollMemory, 0) } }
    }

    private data class Aspect(val label: String, val ratio: Double?)
    private fun aspects(): List<Aspect> = listOf(Aspect("Free", null), Aspect("Original", -1.0), Aspect("Square", 1.0), Aspect("4:5", 0.8),
        Aspect("3:4", 0.75), Aspect("2:3", 2.0 / 3), Aspect("9:16", 9.0 / 16), Aspect("16:9", 16.0 / 9), Aspect("3:2", 1.5), Aspect("4:3", 4.0 / 3))

    private var aspectChoice: Aspect? = null

    private fun cropLabel(s: EditState): String {
        val p = GeometryPlan(session.srcW, session.srcH, s.geometry)
        val angle = s.geometry.straighten
        return "${p.outW} × ${p.outH} px" + if (angle != 0.0) " · ${"%.1f".format(angle)}°" else ""
    }

    private fun cropPanel(col: LinearLayout) {
        val list = aspects()
        val chosen = aspectChoice ?: list[0]
        col.addView(ChipRow(ctx, list, { it.label }, chosen) { a ->
            aspectChoice = a
            val s = session.current
            val plan = session.plan(s)
            val ratio = if (a.ratio == -1.0) {
                if (s.geometry.turns % 2 == 1) session.srcH.toDouble() / session.srcW else session.srcW.toDouble() / session.srcH
            } else a.ratio
            canvas.aspect = ratio
            if (ratio != null) {
                commit(s.copy(geometry = s.geometry.copy(crop = GeometryPlan.centeredCrop(plan.frameW, plan.frameH, ratio))))
            }
        })
        val straightenRow = UI.horizontal(ctx)
        straightenRow.addView(UI.text(ctx, "Straighten", TextStyle.CAPTION, Palette.TEXT_2), LinearLayout.LayoutParams(ctx.dp(84), WRAP))
        val slider = BipolarSlider(ctx, 45f) { v, done ->
            val s = session.current
            val next = s.copy(geometry = s.geometry.copy(straighten = (v * 10).roundToInt() / 10.0, crop = s.geometry.crop))
            if (done) commit(next) else { session.live = next; requestRender(full = false) }
            cropInfo?.text = cropLabel(next)
        }
        slider.set(session.current.geometry.straighten.toFloat())
        straightenRow.addView(slider, lp(0, WRAP, 1f))
        col.addView(straightenRow, lp().apply { topMargin = ctx.dp(6) })
        val actions = UI.horizontal(ctx)
        actions.addView(UI.secondaryButton(ctx, "Rotate", R.drawable.ic_rotate) {
            aspectChoice = null; canvas.aspect = null
            val s = session.current; commit(s.copy(geometry = s.geometry.rotatedClockwise()))
        }.apply { minimumHeight = ctx.dp(40) })
        actions.addView(UI.spacer(ctx, wDp = 8))
        actions.addView(UI.secondaryButton(ctx, "Flip", R.drawable.ic_flip) { val s = session.current; commit(s.copy(geometry = s.geometry.flippedHorizontally())) }.apply { minimumHeight = ctx.dp(40) })
        actions.addView(UI.flex(ctx))
        cropInfo = UI.text(ctx, cropLabel(session.current), TextStyle.CAPTION, Palette.TEXT_3)
        actions.addView(cropInfo)
        actions.addView(UI.ghostButton(ctx, "Reset") { aspectChoice = null; canvas.aspect = null; val s = session.current; commit(s.copy(geometry = com.localmediatools.codec.edit.Geometry())) }.apply { minimumHeight = ctx.dp(40) })
        col.addView(actions, lp().apply { topMargin = ctx.dp(2) })
    }

    private fun adjustPanel(col: LinearLayout) {
        val s = session.current
        col.addView(ChipRow(ctx, AdjustKey.entries, { it.label }, adjustKey, markOf = { s.adjust[it] != 0f }) { k -> adjustKey = k; buildPanel(keepScroll = true) })
        val row = UI.horizontal(ctx)
        val value = UI.text(ctx, "", TextStyle.SUBTITLE).apply { gravity = Gravity.END }
        fun show(v: Float) { value.text = (v * 100).roundToInt().let { if (it > 0) "+$it" else "$it" } }
        val slider = BipolarSlider(ctx, 100f) { v, done ->
            val cur = session.current
            val next = cur.copy(adjust = cur.adjust.with(adjustKey, v / 100f))
            show(v / 100f)
            if (done) commit(next) else { session.live = next; requestRender(full = false) }
        }
        slider.set(s.adjust[adjustKey] * 100f)
        show(s.adjust[adjustKey])
        row.addView(slider, lp(0, WRAP, 1f))
        row.addView(value, LinearLayout.LayoutParams(ctx.dp(48), WRAP))
        col.addView(row, lp().apply { topMargin = ctx.dp(14) })
        col.addView(UI.text(ctx, "Double-tap the slider to reset · a dot marks changed settings", TextStyle.CAPTION, Palette.TEXT_3).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = ctx.dp(4) })
    }

    private fun filterPanel(col: LinearLayout) {
        val s = session.current
        val strip = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = UI.horizontal(ctx)
        strip.addView(row)
        val thumbs = HashMap<FilterPreset, ImageView>()
        for (p in FilterPreset.entries) {
            val sel = s.filter.preset == p
            val cell = UI.vertical(ctx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true; isFocusable = true
                contentDescription = p.label + if (sel) ", selected" else ""
                setOnClickListener { val cur = session.current; commit(cur.copy(filter = FilterSpec(p, if (cur.filter.preset == p) cur.filter.intensity else 1f))) }
            }
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Shapes.rounded(ctx, Palette.SURFACE_2, 14f, if (sel) Palette.ACCENT else null, 2f)
                setPadding(if (sel) ctx.dp(3) else 0, if (sel) ctx.dp(3) else 0, if (sel) ctx.dp(3) else 0, if (sel) ctx.dp(3) else 0)
                clipToOutline = true
            }
            thumbs[p] = iv
            cell.addView(iv, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64)))
            cell.addView(UI.text(ctx, p.label, TextStyle.CAPTION, if (sel) Palette.TEXT else Palette.TEXT_2).apply { setPadding(0, ctx.dp(5), 0, 0); textSize = 11.5f })
            row.addView(cell, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = ctx.dp(10) })
        }
        col.addView(strip)
        if (s.filter.preset != FilterPreset.NONE) {
            val r = UI.horizontal(ctx)
            r.addView(UI.text(ctx, "Strength", TextStyle.CAPTION, Palette.TEXT_2), LinearLayout.LayoutParams(ctx.dp(70), WRAP))
            val slider = BipolarSlider(ctx, 100f) { v, done ->
                val cur = session.current
                val next = cur.copy(filter = cur.filter.copy(intensity = v / 100f))
                if (done) commit(next) else { session.live = next; requestRender(full = false) }
            }
            slider.bipolar = false
            slider.set(s.filter.intensity * 100f)
            r.addView(slider, lp(0, WRAP, 1f))
            col.addView(r, lp().apply { topMargin = ctx.dp(2) })
        }
        // Thumbnails: geometry + adjustments, then each look.
        val base = s.copy(filter = FilterSpec())
        scope.launch {
            val all = withContext(session.worker) {
                val small = session.renderSmall(base, 160)
                FilterPreset.entries.associateWith { p ->
                    small.copy(Bitmap.Config.ARGB_8888, true).also { EditRenderer.applyColors(it, base.copy(filter = FilterSpec(p)), sharpenScale = 0.2) }
                }
            }
            for ((p, b) in all) thumbs[p]?.setImageBitmap(b)
        }
    }

    private fun brushSizeRow(col: LinearLayout) {
        val r = UI.horizontal(ctx)
        r.addView(UI.text(ctx, "Brush size", TextStyle.CAPTION, Palette.TEXT_2), LinearLayout.LayoutParams(ctx.dp(84), WRAP))
        val slider = BipolarSlider(ctx, 100f) { v, _ -> brushSize = v / 100f; canvas.brushRadiusDp = 8f + 52f * brushSize; canvas.previewBrush() }
        slider.bipolar = false
        slider.set(brushSize * 100f)
        canvas.brushRadiusDp = 8f + 52f * brushSize
        r.addView(slider, lp(0, WRAP, 1f))
        col.addView(r)
    }

    private fun erasePanel(col: LinearLayout) {
        val (ai, why) = Inpainters.aiAvailability(ctx)
        val head = UI.horizontal(ctx)
        eraserBadge = badge(ctx, if (ai) "AI" else "BASIC FILL", if (ai) Palette.AI else intArrayOf(Palette.SURFACE_3, Palette.SURFACE_3))
        head.addView(eraserBadge)
        eraserLabel = UI.text(ctx, if (ai) "Loading the on-device model…" else why, TextStyle.CAPTION).apply { setPadding(ctx.dp(10), 0, 0, 0); maxLines = 2 }
        session.inpainterLabel?.let { eraserLabel?.text = it }
        head.addView(eraserLabel, lp(0, WRAP, 1f))
        col.addView(head)
        col.addView(UI.text(ctx, "Paint over a person or object, then lift your finger. Cover its shadow too. Zoom with two fingers for small details.", TextStyle.CAPTION, Palette.TEXT_3), lp().apply { topMargin = ctx.dp(8); bottomMargin = ctx.dp(4) })
        brushSizeRow(col)
    }

    private fun blurPanel(col: LinearLayout) {
        col.addView(ChoiceGroup(ctx, listOf(PatchKind.BLUR, PatchKind.PIXELATE), { it.label }, blurKind) { blurKind = it })
        val r = UI.horizontal(ctx)
        r.addView(UI.text(ctx, "Strength", TextStyle.CAPTION, Palette.TEXT_2), LinearLayout.LayoutParams(ctx.dp(84), WRAP))
        val strength = BipolarSlider(ctx, 100f) { v, _ -> blurStrength = (v / 100f).coerceAtLeast(0.05f) }
        strength.bipolar = false
        strength.set(blurStrength * 100f)
        r.addView(strength, lp(0, WRAP, 1f))
        col.addView(r, lp().apply { topMargin = ctx.dp(4) })
        brushSizeRow(col)
    }

    // ------------------------------------------------------------------ saving & leaving
    private fun defaultFormat(): ImageOutFormat = when {
        session.hasAlpha || item.format == SniffedFormat.PNG -> ImageOutFormat.PNG
        item.format == SniffedFormat.WEBP -> ImageOutFormat.WEBP
        else -> ImageOutFormat.JPEG
    }

    private fun showSaveSheet() {
        if (!loaded) return
        session.live?.let { s -> scope.launch { withContext(session.worker) { session.commit(s) } } }
        var format = defaultFormat()
        var quality = 92
        val body = UI.vertical(ctx, 20, 8)
        val plan = session.plan(session.current)
        body.addView(UI.text(ctx, "${plan.outW} × ${plan.outH} px · full resolution", TextStyle.BODY_2))
        val q = SliderField(ctx, "Quality", 50, 100, quality) { quality = it }
        val formats = listOf(ImageOutFormat.JPEG, ImageOutFormat.PNG, ImageOutFormat.WEBP)
        body.addView(ChoiceGroup(ctx, formats, { it.label }, format, badgeOf = { if (it == ImageOutFormat.PNG) "lossless" else null }) {
            format = it; q.visibility = if (it.lossless) View.GONE else View.VISIBLE
        }, lp().apply { topMargin = ctx.dp(12) })
        q.visibility = if (format.lossless) View.GONE else View.VISIBLE
        body.addView(q, lp().apply { topMargin = ctx.dp(12) })
        if (session.hasAlpha) body.addView(UI.note(ctx, "This photo has transparent areas: PNG or WebP keep them; JPEG fills them with white.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(10) })
        body.addView(UI.note(ctx, "A new copy is saved to ${tool.outputPath}. The original is not changed. Location and camera details are not copied.", UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(10) })
        AlertDialog.Builder(activity)
            .setTitle("Save a copy")
            .setView(ScrollView(ctx).apply { addView(body) })
            .setPositiveButton("Save") { _, _ -> save(format, quality) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun save(format: ImageOutFormat, quality: Int) {
        scope.launch {
            val state = withContext(session.worker) { session.live?.let { session.commit(it) }; session.state }
            val job = EditPhotoJob(tool, item, state, session.store, format, quality, session.hasAlpha)
            session.saved = true
            activity.ensureNotificationPermission {
                ExportManager.enqueue(job)
                Toast.makeText(ctx, "Saving in the background…", Toast.LENGTH_SHORT).show()
                pop()
                push(ResultsScreen(activity, job.id))
            }
        }
    }

    override fun onBack(): Boolean {
        if (loaded && session.changed && !session.saved) {
            AlertDialog.Builder(activity)
                .setTitle("Discard your edits?")
                .setMessage("Nothing has been saved yet. Your original photo is not affected.")
                .setPositiveButton("Discard") { _, _ -> session.saved = false; forceClose() }
                .setNegativeButton("Keep editing", null)
                .show()
            return true
        }
        forceClose(); return true
    }

    private fun forceClose() { pop() }

    override fun onDestroy() {
        super.onDestroy()
        try { session.close() } catch (_: Exception) { }
    }
}
