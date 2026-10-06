package com.localmediatools.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.codec.layout.Align
import com.localmediatools.codec.layout.PackResult
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportJob
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.ImageSource
import com.localmediatools.stitch.TfliteEmbedder
import com.localmediatools.stitch.core.SceneMode
import com.localmediatools.tools.CompressImagesJob
import com.localmediatools.tools.ConvertImagesJob
import com.localmediatools.tools.MergeBackground
import com.localmediatools.tools.MergeImagesJob
import com.localmediatools.tools.MergeLayout
import com.localmediatools.tools.MergePlanner
import com.localmediatools.tools.OptimizeImagesJob
import com.localmediatools.tools.StitchJob
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.SliderField
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.Thumbs
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import java.util.concurrent.Executors

/** Displayed (orientation-corrected) image sizes, probed in the background and cached. */
object Dims {
    private val cache = HashMap<String, IntArray?>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun known(item: MediaItem): IntArray? = synchronized(cache) { cache[item.key] }

    fun request(ctx: Context, items: List<MediaItem>, done: () -> Unit) {
        val missing = synchronized(cache) { items.filter { !cache.containsKey(it.key) } }
        if (missing.isEmpty()) { done(); return }
        val app = ctx.applicationContext
        exec.execute {
            for (i in missing) {
                val d = try { ImageSource.open(app, i).use { intArrayOf(it.width, it.height) } } catch (_: Throwable) { null }
                synchronized(cache) { cache[i.key] = d }
            }
            main.post(done)
        }
    }
}

object ImagePrefs {
    var compressFormat = ImageOutFormat.JPEG
    var compressQuality = 80
    var compressMaxWidth = 0
    var losslessFormat = ImageOutFormat.WEBP_LOSSLESS
    var losslessOnlySmaller = true
    var convertFormat = ImageOutFormat.PNG
    var convertQuality = 90
    var mergeLayout = MergeLayout.VERTICAL
    var mergeBackground = MergeBackground.WHITE
    var mergeAlign = Align.CENTER
    var mergeSpacing = 0
    var stitchAi = false
    var stitchMode = SceneMode.AUTO
    var stitchCrop = true
}

// =========================================================================== Image compressor
class CompressImagesScreen(a: MainActivity) : ToolScreen(a, ToolId.COMPRESS_IMAGES) {
    private lateinit var qualityHint: TextView
    private lateinit var maxWidth: SliderField

    override fun buildOptions(container: LinearLayout) {
        section(container, "Format", null, top = 4)
        container.addView(ChoiceGroup(ctx, listOf(ImageOutFormat.JPEG, ImageOutFormat.WEBP), { it.label }, ImagePrefs.compressFormat,
            badgeOf = { if (it == ImageOutFormat.JPEG) "most compatible" else "smaller" }) { ImagePrefs.compressFormat = it; updateHint() }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.text(ctx, "JPEG works everywhere but has no transparency. WebP is typically 25–35% smaller at similar quality and keeps transparency.", TextStyle.CAPTION), lp().apply { topMargin = ctx.dp(8) })
        container.addView(SliderField(ctx, "Quality", 10, 100, ImagePrefs.compressQuality, subtitle = "Lower = smaller file, more artefacts") { ImagePrefs.compressQuality = it; updateHint() }, lp().apply { topMargin = ctx.dp(16) })
        qualityHint = UI.text(ctx, "", TextStyle.CAPTION)
        container.addView(qualityHint)
        maxWidth = SliderField(ctx, "Maximum width", 0, 8000, ImagePrefs.compressMaxWidth, " px", "0 keeps the original size; never upscales") { ImagePrefs.compressMaxWidth = it; refreshValidation() }
        container.addView(maxWidth, lp().apply { topMargin = ctx.dp(16) })
        val presets = listOf(0, 3840, 2560, 1920, 1280, 1080)
        container.addView(ChoiceGroup(ctx, presets, { if (it == 0) "Original" else "$it px" }, presets.firstOrNull { it == ImagePrefs.compressMaxWidth }) {
            maxWidth.setValue(it, fromField = false)
        }, lp().apply { topMargin = ctx.dp(6) })
        container.addView(UI.note(ctx, "Aspect ratio and displayed orientation are always preserved. Metadata such as GPS location is not copied. If a result isn't smaller than the original (and wasn't resized), it isn't saved.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })
        updateHint()
    }

    private fun updateHint() {
        if (!::qualityHint.isInitialized) return
        val q = ImagePrefs.compressQuality
        qualityHint.text = when {
            q >= 90 -> "Visually lossless for most photos; modest savings."
            q >= 75 -> "Recommended: strong savings with little visible difference."
            q >= 50 -> "Small files; artefacts visible on close inspection."
            else -> "Very small files; noticeable artefacts and blur."
        }
        qualityHint.setTextColor(if (q < 40) Palette.WARNING else Palette.TEXT_2)
    }

    override fun validate(): String? {
        super.validate()?.let { return it }
        val w = ImagePrefs.compressMaxWidth
        if (w in 1 until 16) return "Maximum width must be 0 (original) or at least 16 px"
        return null
    }

    override fun outputNaming() = "name_q${ImagePrefs.compressQuality}.${ImagePrefs.compressFormat.ext} (plus _1920w when resized)"
    override fun createJob(items: List<MediaItem>): ExportJob = CompressImagesJob(items, ImagePrefs.compressFormat, ImagePrefs.compressQuality, ImagePrefs.compressMaxWidth)
}

// =========================================================================== Lossless optimizer
class OptimizeImagesScreen(a: MainActivity) : ToolScreen(a, ToolId.OPTIMIZE_IMAGES) {
    override fun buildOptions(container: LinearLayout) {
        section(container, "Lossless format", null, top = 4)
        container.addView(ChoiceGroup(ctx, listOf(ImageOutFormat.WEBP_LOSSLESS, ImageOutFormat.PNG), { if (it == ImageOutFormat.PNG) "PNG" else "WebP" },
            ImagePrefs.losslessFormat, badgeOf = { if (it == ImageOutFormat.PNG) "most compatible" else "usually smallest" }) { ImagePrefs.losslessFormat = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.text(ctx, "PNG output picks the smallest exact colour mode (palette, greyscale, RGB or RGBA) and maximum compression.", TextStyle.CAPTION), lp().apply { topMargin = ctx.dp(8) })
        container.addView(ToggleRow(ctx, "Only keep smaller results", "Discard a result that isn't smaller than its original", ImagePrefs.losslessOnlySmaller) { ImagePrefs.losslessOnlySmaller = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, "Every pixel is preserved exactly — this is not lossy compression. Smaller files are not guaranteed: screenshots and graphics often shrink, while JPEG/HEIC photos usually get larger in a lossless format. 16-bit PNGs and animations are rejected rather than altered.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
    }

    override fun outputNaming() = "name_lossless.${ImagePrefs.losslessFormat.ext} (in the same folder as the image compressor's results)"
    override fun createJob(items: List<MediaItem>): ExportJob = OptimizeImagesJob(items, ImagePrefs.losslessFormat, ImagePrefs.losslessOnlySmaller)
}

// =========================================================================== Convert
class ConvertScreen(a: MainActivity) : ToolScreen(a, ToolId.CONVERT_IMAGES) {
    private lateinit var quality: SliderField

    override fun buildOptions(container: LinearLayout) {
        section(container, "Convert to", null, top = 4)
        val formats = listOf(ImageOutFormat.PNG, ImageOutFormat.JPEG, ImageOutFormat.WEBP, ImageOutFormat.WEBP_LOSSLESS)
        container.addView(ChoiceGroup(ctx, formats, { if (it == ImageOutFormat.WEBP_LOSSLESS) "WebP" else it.label }, ImagePrefs.convertFormat,
            badgeOf = { if (it.lossless) "lossless" else "lossy" }) { ImagePrefs.convertFormat = it; updateQuality() }, lp().apply { topMargin = ctx.dp(10) })
        quality = SliderField(ctx, "Quality (lossy formats)", 10, 100, ImagePrefs.convertQuality, subtitle = "90 keeps photos visually identical") { ImagePrefs.convertQuality = it }
        container.addView(quality, lp().apply { topMargin = ctx.dp(16) })
        container.addView(UI.note(ctx, "Reads JPEG, PNG, WebP, HEIC/HEIF, AVIF, GIF, BMP, ICO, WBMP, DNG and other RAW previews (when the phone supports them), plus TIFF, PSD (flattened), QOI, PPM/PGM/PBM/PAM and TGA. Animated files use their first frame. Size, aspect ratio and displayed orientation are kept.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })
        updateQuality()
    }

    private fun updateQuality() {
        if (!::quality.isInitialized) return
        val lossy = !ImagePrefs.convertFormat.lossless
        quality.alpha = if (lossy) 1f else 0.4f
        setEnabledDeep(quality, lossy)
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    override fun outputNaming() = "name.${ImagePrefs.convertFormat.ext} (same name, new format)"
    override fun createJob(items: List<MediaItem>): ExportJob = ConvertImagesJob(items, ImagePrefs.convertFormat, ImagePrefs.convertQuality)
}

// =========================================================================== Merge
class MergeScreen(a: MainActivity) : ToolScreen(a, ToolId.MERGE_IMAGES) {
    private lateinit var preview: MergePreview
    private lateinit var sizeLine: TextView
    private var alignGroup: ChoiceGroup<Align>? = null
    private lateinit var alignBox: LinearLayout

    override fun buildOptions(container: LinearLayout) {
        section(container, "Layout", null, top = 4)
        container.addView(ChoiceGroup(ctx, MergeLayout.entries, { it.label }, ImagePrefs.mergeLayout) { ImagePrefs.mergeLayout = it; rebuildAlign(); updatePreview() }, lp().apply { topMargin = ctx.dp(10) })
        alignBox = UI.vertical(ctx)
        container.addView(alignBox)
        section(container, "Background", "Fills space around images of different sizes")
        container.addView(ChoiceGroup(ctx, MergeBackground.entries, { it.label }, ImagePrefs.mergeBackground) { ImagePrefs.mergeBackground = it; updatePreview() }, lp().apply { topMargin = ctx.dp(10) })
        section(container, "Spacing between images")
        val sp = listOf("None" to 0, "Small" to 16, "Medium" to 40, "Large" to 80)
        container.addView(ChoiceGroup(ctx, sp, { it.first }, sp.firstOrNull { it.second == ImagePrefs.mergeSpacing }) { ImagePrefs.mergeSpacing = it.second; updatePreview() }, lp().apply { topMargin = ctx.dp(10) })
        section(container, "Preview", null)
        preview = MergePreview(ctx)
        container.addView(preview, lp(com.localmediatools.ui.MATCH, ctx.dp(240)).apply { topMargin = ctx.dp(10) })
        sizeLine = UI.text(ctx, "", TextStyle.CAPTION)
        container.addView(sizeLine, lp().apply { topMargin = ctx.dp(6) })
        container.addView(UI.note(ctx, "Images are never scaled or cropped — each keeps its exact pixels and correct orientation. The result is a lossless PNG; very large results are written in strips, so they don't need to fit in memory.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
        rebuildAlign()
        updatePreview()
    }

    private fun rebuildAlign() {
        alignBox.removeAllViews()
        if (ImagePrefs.mergeLayout == MergeLayout.SMART) return
        val labels = if (ImagePrefs.mergeLayout == MergeLayout.VERTICAL) listOf("Left", "Center", "Right") else listOf("Top", "Center", "Bottom")
        section(alignBox, "Alignment", "For images narrower or shorter than the widest one")
        alignGroup = ChoiceGroup(ctx, Align.entries, { labels[it.ordinal] }, ImagePrefs.mergeAlign) { ImagePrefs.mergeAlign = it; updatePreview() }
        alignBox.addView(alignGroup, lp().apply { topMargin = ctx.dp(10) })
    }

    override fun onSelectionChanged() = updatePreview()

    private fun updatePreview() {
        if (!::preview.isInitialized) return
        val items = selection.usable
        if (items.size < 2) { preview.set(null, emptyList(), ImagePrefs.mergeBackground); sizeLine.text = "Select at least two images to see the layout."; return }
        Dims.request(ctx, items) {
            val dims = items.map { Dims.known(it) }
            if (dims.any { it == null }) { sizeLine.text = "Some images couldn't be read."; return@request }
            val plan = MergePlanner.plan(dims.map { it!![0] to it[1] }, ImagePrefs.mergeLayout, ImagePrefs.mergeSpacing, ImagePrefs.mergeAlign)
            preview.set(plan, items, ImagePrefs.mergeBackground)
            val mp = plan.width.toLong() * plan.height / 1e6
            sizeLine.text = "Result: ${plan.width} × ${plan.height} px (${String.format(java.util.Locale.US, "%.1f", mp)} MP)"
        }
    }

    override fun outputNaming() = "Merged <date and time>.png"
    override fun createJob(items: List<MediaItem>): ExportJob = MergeImagesJob(items, ImagePrefs.mergeLayout, ImagePrefs.mergeBackground, ImagePrefs.mergeSpacing, ImagePrefs.mergeAlign)
}

/** Draws the merge plan with real thumbnails. */
class MergePreview(ctx: Context) : View(ctx) {
    private var plan: PackResult? = null
    private var items: List<MediaItem> = emptyList()
    private var bg = MergeBackground.WHITE
    private val thumbs = HashMap<String, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val checker = Paint().apply { color = 0xFF2A3240.toInt() }

    init {
        background = Shapes.rounded(ctx, Palette.SURFACE_2, 16f, Palette.STROKE)
        contentDescription = "Merge layout preview"
    }

    fun set(p: PackResult?, its: List<MediaItem>, b: MergeBackground) {
        plan = p; items = its; bg = b
        for (i in its) if (!thumbs.containsKey(i.key)) {
            thumbs[i.key] = null
            Thumbs.get(context, i, context.dp(160)) { bmp -> thumbs[i.key] = bmp; invalidate() }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = plan ?: return
        val pad = context.dp(12).toFloat()
        val s = minOf((width - 2 * pad) / p.width, (height - 2 * pad) / p.height)
        val ox = (width - p.width * s) / 2; val oy = (height - p.height * s) / 2
        val canvasRect = RectF(ox, oy, ox + p.width * s, oy + p.height * s)
        if (bg == MergeBackground.TRANSPARENT) {
            val cell = context.dp(6).toFloat()
            canvas.save(); canvas.clipRect(canvasRect)
            var y = canvasRect.top; var row = 0
            while (y < canvasRect.bottom) {
                var x = canvasRect.left + if (row % 2 == 0) 0f else cell
                while (x < canvasRect.right) { canvas.drawRect(x, y, x + cell, y + cell, checker); x += 2 * cell }
                y += cell; row++
            }
            canvas.restore()
        } else {
            paint.color = bg.color; canvas.drawRect(canvasRect, paint)
        }
        for ((i, r) in p.positions.withIndex()) {
            val dst = RectF(ox + r.x * s, oy + r.y * s, ox + (r.x + r.w) * s, oy + (r.y + r.h) * s)
            val b = items.getOrNull(i)?.let { thumbs[it.key] }
            if (b != null) canvas.drawBitmap(b, Rect(0, 0, b.width, b.height), dst, paint)
            else { paint.color = Palette.SURFACE_3; canvas.drawRect(dst, paint) }
        }
    }
}

// =========================================================================== Stitcher
class StitchScreen(a: MainActivity) : ToolScreen(a, ToolId.STITCH) {
    override fun buildOptions(container: LinearLayout) {
        val (available, reason) = TfliteEmbedder.availability(ctx)
        val ai = ToggleRow(ctx, "AI Assisted Alignment", reason, ImagePrefs.stitchAi && available) { ImagePrefs.stitchAi = it }
        ai.setAvailable(available)
        if (!available) ImagePrefs.stitchAi = false
        container.addView(ai)
        container.addView(UI.note(ctx, "Runs a small image model entirely on this phone — no image is ever uploaded. It settles ambiguous alignments (repetitive patterns, weak matches) and helps join low-texture photos. Stitching works without it.", UI.NoteKind.TIP), lp().apply { topMargin = ctx.dp(6) })
        section(container, "Scene type", "Auto detects how your photos were taken")
        container.addView(ChoiceGroup(ctx, SceneMode.entries, { when (it) { SceneMode.AUTO -> "Auto"; SceneMode.FLAT -> "Flat surface"; SceneMode.PANORAMA -> "Panorama" } }, ImagePrefs.stitchMode) { ImagePrefs.stitchMode = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.text(ctx, "Flat surface: documents, whiteboards, maps, screenshots (camera moved parallel). Panorama: camera turned from one spot — horizontal, vertical or in a grid.", TextStyle.CAPTION), lp().apply { topMargin = ctx.dp(8) })
        container.addView(ToggleRow(ctx, "Crop to clean edges", "Trim to the largest rectangle fully covered by photos (otherwise empty corners stay transparent)", ImagePrefs.stitchCrop) { ImagePrefs.stitchCrop = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, "For best results: overlap neighbouring shots by about a third, keep exposure steady and avoid moving subjects. Featureless walls, sky-only frames and repetitive textures (tiles, fences) are hard to align. Any arrangement and selection order works; photos that don't connect are reported.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
    }

    override fun outputNaming() = "Stitched <date and time>.png · lossless, at the photos' native resolution when practical"
    override fun createJob(items: List<MediaItem>): ExportJob = StitchJob(items, ImagePrefs.stitchAi, ImagePrefs.stitchMode, ImagePrefs.stitchCrop)
}
