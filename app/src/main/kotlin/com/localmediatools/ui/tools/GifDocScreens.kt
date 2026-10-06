package com.localmediatools.ui.tools

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.codec.gif.Dithering
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaProbe
import com.localmediatools.export.ExportJob
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.tools.CompressGifJob
import com.localmediatools.tools.ImagesToPdfJob
import com.localmediatools.tools.MergePdfsJob
import com.localmediatools.tools.OptimizeGifJob
import com.localmediatools.tools.PageOrientation
import com.localmediatools.tools.PdfImageQuality
import com.localmediatools.tools.PdfToImagesJob
import com.localmediatools.tools.ScanFilter
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.FlowLayout
import com.localmediatools.ui.Palette
import com.localmediatools.ui.SelectionReviewScreen
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.SliderField
import com.localmediatools.ui.StepBadge
import com.localmediatools.ui.TextField
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.Thumbs
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.WRAP
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import java.util.Locale

object DocPrefs {
    var gifWidth = 0
    var gifFps = 0
    var gifColors = 128
    var gifDither = Dithering.ORDERED
    var optWidth = 0
    var optFps = 0
    var optOnlySmaller = true
    var pdfFormat = ImageOutFormat.PNG
    var pdfQuality = 90
    var pdfScale = 200
    var pageOrientation = PageOrientation.AUTO
    var pdfImageQuality = PdfImageQuality.ORIGINAL
    var pdfName = ""
    var mergeName = ""
    var scanFilter = ScanFilter.DOCUMENT
    var scanOrientation = PageOrientation.PORTRAIT
    var scanName = ""
}

// =========================================================================== GIF compressor
class CompressGifScreen(a: MainActivity) : ToolScreen(a, ToolId.COMPRESS_GIF) {
    override fun buildOptions(container: LinearLayout) {
        val width = SliderField(ctx, "Output width", 0, 1280, DocPrefs.gifWidth, " px", "0 keeps the original width") { DocPrefs.gifWidth = it }
        container.addView(width)
        val wp = listOf(0, 480, 360, 240)
        container.addView(ChoiceGroup(ctx, wp, { if (it == 0) "Original" else "$it px" }, wp.firstOrNull { it == DocPrefs.gifWidth }) { width.setValue(it, false) }, lp().apply { topMargin = ctx.dp(6) })
        val fps = SliderField(ctx, "Frame rate", 0, 30, DocPrefs.gifFps, " fps", "0 keeps the original timing; lower drops frames") { DocPrefs.gifFps = it }
        container.addView(fps, lp().apply { topMargin = ctx.dp(14) })
        val fp = listOf(0, 15, 10, 8, 5)
        container.addView(ChoiceGroup(ctx, fp, { if (it == 0) "Original" else "$it fps" }, fp.firstOrNull { it == DocPrefs.gifFps }) { fps.setValue(it, false) }, lp().apply { topMargin = ctx.dp(6) })
        section(container, "Palette size", "Fewer colours = smaller file, less detail")
        val colors = listOf(32, 64, 128, 256)
        container.addView(ChoiceGroup(ctx, colors, { "$it colours" }, DocPrefs.gifColors) { DocPrefs.gifColors = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.text(ctx, "32: tiny files, visible banding · 64: good for simple graphics · 128: balanced · 256: most detail", TextStyle.CAPTION), lp().apply { topMargin = ctx.dp(8) })
        section(container, "Dithering")
        container.addView(ChoiceGroup(ctx, Dithering.entries, { when (it) { Dithering.ORDERED -> "Pattern"; Dithering.NONE -> "None"; Dithering.DIFFUSION -> "Diffusion" } }, DocPrefs.gifDither) { DocPrefs.gifDither = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, "Size, frame rate and colours all trade detail for file size. Results that wouldn't be smaller than the original aren't saved.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })
    }

    override fun outputNaming() = "name_compressed.gif"
    override fun createJob(items: List<MediaItem>): ExportJob = CompressGifJob(items, DocPrefs.gifWidth, DocPrefs.gifFps.toDouble(), DocPrefs.gifColors, DocPrefs.gifDither)
}

// =========================================================================== GIF optimizer
class OptimizeGifScreen(a: MainActivity) : ToolScreen(a, ToolId.OPTIMIZE_GIF) {
    override fun buildOptions(container: LinearLayout) {
        container.addView(SliderField(ctx, "Output width", 0, 1280, DocPrefs.optWidth, " px", "0 keeps every pixel identical; other widths sample pixels without blending") { DocPrefs.optWidth = it })
        container.addView(SliderField(ctx, "Playback frame rate", 0, 30, DocPrefs.optFps, " fps", "0 keeps the exact original timing") { DocPrefs.optFps = it }, lp().apply { topMargin = ctx.dp(14) })
        container.addView(ToggleRow(ctx, "Only keep smaller results", null, DocPrefs.optOnlySmaller) { DocPrefs.optOnlySmaller = it }, lp().apply { topMargin = ctx.dp(8) })
        container.addView(UI.note(ctx, "Re-encodes the GIF more efficiently (changed areas only, exact palettes, frame merging) while every displayed pixel stays identical. This is only possible when each frame fits GIF's limits: up to 256 colours including transparency, and on/off transparency only. Frames that don't fit are reported and the file is rejected rather than altered.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
    }

    override fun outputNaming() = "name_optimized.gif"
    override fun createJob(items: List<MediaItem>): ExportJob = OptimizeGifJob(items, DocPrefs.optWidth, DocPrefs.optFps.toDouble(), DocPrefs.optOnlySmaller)
}

// =========================================================================== PDF → images
class PdfToImagesScreen(a: MainActivity) : ToolScreen(a, ToolId.PDF_TO_IMAGES) {
    private lateinit var quality: SliderField
    private lateinit var scaleInfo: TextView

    override fun buildOptions(container: LinearLayout) {
        section(container, "Image format", null, top = 4)
        container.addView(ChoiceGroup(ctx, listOf(ImageOutFormat.PNG, ImageOutFormat.JPEG), { it.label }, DocPrefs.pdfFormat,
            badgeOf = { if (it == ImageOutFormat.PNG) "lossless, crisp text" else "smaller files" }) { DocPrefs.pdfFormat = it; updateQuality() }, lp().apply { topMargin = ctx.dp(10) })
        quality = SliderField(ctx, "JPEG quality", 30, 100, DocPrefs.pdfQuality) { DocPrefs.pdfQuality = it }
        container.addView(quality, lp().apply { topMargin = ctx.dp(14) })
        val scale = SliderField(ctx, "Render scale", 50, 400, DocPrefs.pdfScale, "%", "100% = 72 DPI") { DocPrefs.pdfScale = it; updateScale() }
        container.addView(scale, lp().apply { topMargin = ctx.dp(14) })
        val presets = listOf(100, 150, 200, 300, 400)
        container.addView(ChoiceGroup(ctx, presets, { "$it%" }, presets.firstOrNull { it == DocPrefs.pdfScale }) { scale.setValue(it, false) }, lp().apply { topMargin = ctx.dp(6) })
        scaleInfo = UI.text(ctx, "", TextStyle.CAPTION)
        container.addView(scaleInfo, lp().apply { topMargin = ctx.dp(6) })
        container.addView(UI.note(ctx, "Every page becomes one image that looks exactly like the page on screen (white background). Text isn't extracted; links and selectable text don't carry over. Pages of any size are rendered in strips, so large posters work too.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
        updateQuality(); updateScale()
    }

    private fun updateQuality() {
        if (!::quality.isInitialized) return
        val on = DocPrefs.pdfFormat == ImageOutFormat.JPEG
        quality.alpha = if (on) 1f else 0.4f
        setEnabledDeep(quality, on)
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    private fun updateScale() {
        if (!::scaleInfo.isInitialized) return
        val s = DocPrefs.pdfScale / 100.0
        scaleInfo.text = String.format(Locale.US, "≈ %d DPI · an A4 page becomes %d × %d px", (72 * s).toInt(), (595 * s).toInt(), (842 * s).toInt())
    }

    override fun outputNaming() = "name_p001.${DocPrefs.pdfFormat.ext}, name_p002… one image per page"
    override fun createJob(items: List<MediaItem>): ExportJob = PdfToImagesJob(items, DocPrefs.pdfFormat, DocPrefs.pdfQuality, DocPrefs.pdfScale)
}

// =========================================================================== Images → PDF
class ImagesToPdfScreen(a: MainActivity) : ToolScreen(a, ToolId.IMAGES_TO_PDF) {
    override val startLabel get() = "Create PDF"

    override fun buildOptions(container: LinearLayout) {
        section(container, "Page orientation", "All pages are A4 with a small margin", top = 4)
        container.addView(ChoiceGroup(ctx, PageOrientation.entries, { if (it == PageOrientation.AUTO) "Auto (match image)" else it.label }, DocPrefs.pageOrientation) { DocPrefs.pageOrientation = it }, lp().apply { topMargin = ctx.dp(10) })
        section(container, "Image quality")
        container.addView(ChoiceGroup(ctx, PdfImageQuality.entries, { it.label }, DocPrefs.pdfImageQuality,
            badgeOf = { if (it == PdfImageQuality.ORIGINAL) "no recompression" else "smaller PDF" }) { DocPrefs.pdfImageQuality = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.text(ctx, "Original embeds JPEG photos byte-for-byte and other images losslessly. Compact resizes to print resolution (300 DPI on A4) as JPEG.", TextStyle.CAPTION), lp().apply { topMargin = ctx.dp(8) })
        container.addView(TextField(ctx, "File name", "Images <date>", DocPrefs.pdfName) { DocPrefs.pdfName = it }, lp().apply { topMargin = ctx.dp(16) })
        container.addView(UI.note(ctx, "One image per page, in the order shown above (use Review & order to change it). Pictures are fitted without distortion and keep their displayed orientation. This makes an image PDF — text in photos is not made searchable (no OCR).", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
    }

    override fun outputNaming() = "${DocPrefs.pdfName.ifBlank { "Images <date>" }}.pdf"
    override fun createJob(items: List<MediaItem>): ExportJob = ImagesToPdfJob(ToolId.IMAGES_TO_PDF, items, DocPrefs.pageOrientation, DocPrefs.pdfImageQuality, DocPrefs.pdfName)
}

// =========================================================================== Merge PDFs
class MergePdfsScreen(a: MainActivity) : ToolScreen(a, ToolId.MERGE_PDFS) {
    override val startLabel get() = "Merge PDFs"

    override fun buildOptions(container: LinearLayout) {
        container.addView(TextField(ctx, "File name", "Merged <date>", DocPrefs.mergeName) { DocPrefs.mergeName = it })
        container.addView(UI.note(ctx, "Documents are joined in the order shown above, page by page. Their real content is kept — text stays selectable, vector graphics and links keep working, and every page keeps its own size. Password-protected or damaged PDFs are rejected with the file name.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
    }

    override fun outputNaming() = "${DocPrefs.mergeName.ifBlank { "Merged <date>" }}.pdf"
    override fun createJob(items: List<MediaItem>): ExportJob = MergePdfsJob(items, DocPrefs.mergeName)
}

// =========================================================================== Scanner
class ScannerScreen(a: MainActivity) : ToolScreen(a, ToolId.PDF_SCANNER) {
    private lateinit var pages: FlowLayout
    private lateinit var count: TextView
    private lateinit var cameraBtn: ButtonView
    private lateinit var clearBtn: ButtonView
    private lateinit var reviewBtn: ButtonView
    private val listener = { renderPages() }
    override val startLabel get() = "Save PDF"

    override fun buildSelection(container: LinearLayout) {
        val c = UI.card(ctx)
        val head = UI.horizontal(ctx)
        head.addView(StepBadge(ctx, 1))
        head.addView(UI.text(ctx, "Pages", TextStyle.SUBTITLE).apply { setPadding(ctx.dp(10), 0, 0, 0) }, lp(0, WRAP, 1f))
        count = UI.text(ctx, "", TextStyle.BODY_2)
        head.addView(count)
        c.addView(head)
        val actions = FlowLayout(ctx)
        val camera = activity.cameraAvailability()
        cameraBtn = UI.primaryButton(ctx, "Capture page") { activity.capturePhoto { uri -> addCaptured(uri) } }
        cameraBtn.isEnabled = camera.first
        actions.addView(cameraBtn)
        actions.addView(UI.secondaryButton(ctx, "Add from gallery", R.drawable.ic_gallery) {
            activity.pickMedia(com.localmediatools.ui.PickKind.IMAGES) { uris -> selection.addUris(activity, uris) }
        })
        reviewBtn = UI.secondaryButton(ctx, "Reorder", R.drawable.ic_reorder) { push(SelectionReviewScreen(activity, selection)) }
        clearBtn = UI.ghostButton(ctx, "Clear pages", R.drawable.ic_trash) {
            android.app.AlertDialog.Builder(activity).setTitle("Remove all pages?")
                .setMessage("Captured pages are deleted from the app's private storage; gallery photos aren't affected.")
                .setPositiveButton("Remove") { _, _ -> clearPages() }
                .setNegativeButton("Cancel", null).show()
        }
        actions.addView(reviewBtn)
        actions.addView(clearBtn)
        c.addView(actions, lp().apply { topMargin = ctx.dp(12) })
        if (!camera.first) c.addView(UI.note(ctx, camera.second, UI.NoteKind.WARN), lp().apply { topMargin = ctx.dp(10) })
        pages = FlowLayout(ctx)
        c.addView(pages, lp().apply { topMargin = ctx.dp(14) })
        container.addView(c, lp().apply { topMargin = ctx.dp(14) })
        selection.listen(listener)
        renderPages()
    }

    private fun addCaptured(uri: android.net.Uri) {
        scope.launchIo({ MediaProbe.describe(activity, uri) }) { item -> selection.add(listOf(item.copy(name = "Page ${selection.items.size + 1}.jpg"))) }
    }

    private fun clearPages() {
        for (i in selection.items) if (i.uri.authority == com.localmediatools.core.CaptureProvider.AUTHORITY) {
            try { java.io.File(com.localmediatools.core.CaptureProvider.dir(ctx), i.uri.lastPathSegment!!).delete() } catch (_: Exception) { }
        }
        selection.clear()
    }

    private fun renderPages() {
        if (!::pages.isInitialized) return
        val items = selection.items
        count.text = if (items.isEmpty()) "No pages yet" else "${items.size} page${if (items.size == 1) "" else "s"}"
        clearBtn.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        reviewBtn.visibility = if (items.size < 2) View.GONE else View.VISIBLE
        pages.removeAllViews()
        if (items.isEmpty()) {
            pages.addView(UI.text(ctx, "Capture pages with the camera or add photos. Pages appear here in order.", TextStyle.CAPTION))
            return
        }
        for ((i, it) in items.withIndex()) {
            val f = FrameLayout(ctx)
            f.background = Shapes.rounded(ctx, Palette.SURFACE_2, 10f, Palette.STROKE)
            f.clipToOutline = true
            val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            f.addView(iv, FrameLayout.LayoutParams(com.localmediatools.ui.MATCH, com.localmediatools.ui.MATCH))
            Thumbs.load(ctx, it, ctx.dp(160), iv)
            f.addView(UI.text(ctx, "${i + 1}", TextStyle.CAPTION, android.graphics.Color.WHITE).apply {
                background = Shapes.pill(ctx, 0xCC000000.toInt()); setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2))
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(ctx.dp(4), ctx.dp(4), 0, 0) })
            f.contentDescription = "Page ${i + 1}"
            pages.addView(f, android.view.ViewGroup.LayoutParams(ctx.dp(76), ctx.dp(104)))
        }
    }

    override fun buildOptions(container: LinearLayout) {
        section(container, "Enhancement", "Makes photographed pages look like scans", top = 4)
        container.addView(ChoiceGroup(ctx, ScanFilter.entries, { it.label }, DocPrefs.scanFilter) { DocPrefs.scanFilter = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.text(ctx, "Clean document evens out shadows and boosts contrast (greyscale). Black & white gives crisp text and the smallest files.", TextStyle.CAPTION), lp().apply { topMargin = ctx.dp(8) })
        section(container, "Page orientation")
        container.addView(ChoiceGroup(ctx, listOf(PageOrientation.PORTRAIT, PageOrientation.AUTO), { if (it == PageOrientation.AUTO) "Auto (match photo)" else "Portrait" }, DocPrefs.scanOrientation) { DocPrefs.scanOrientation = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(TextField(ctx, "File name", "Scan <date>", DocPrefs.scanName) { DocPrefs.scanName = it }, lp().apply { topMargin = ctx.dp(16) })
        container.addView(UI.note(ctx, "Each page becomes an A4 page with a small margin, upright as you see it. This is an image scanner: text stays part of the picture and is not searchable or selectable (no OCR).", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
    }

    override fun validate(): String? = when {
        selection.loading > 0 -> "Adding pages…"
        selection.usable.isEmpty() -> "Capture or add at least one page"
        else -> null
    }

    override fun outputNaming() = "${DocPrefs.scanName.ifBlank { "Scan <date>" }}.pdf"
    override fun createJob(items: List<MediaItem>): ExportJob = ImagesToPdfJob(ToolId.PDF_SCANNER, items, DocPrefs.scanOrientation, PdfImageQuality.COMPACT, DocPrefs.scanName, DocPrefs.scanFilter)

    override fun onDestroy() {
        selection.unlisten(listener)
        super.onDestroy()
    }
}

object ToolScreens {
    fun create(a: MainActivity, t: ToolId): ToolScreen = when (t) {
        ToolId.SPLIT_VIDEO -> SplitScreen(a)
        ToolId.OPTIMIZE_VIDEO -> OptimizeVideoScreen(a)
        ToolId.COMPRESS_VIDEO -> CompressVideoScreen(a)
        ToolId.REMOVE_AUDIO -> RemoveAudioScreen(a)
        ToolId.COMPRESS_IMAGES -> CompressImagesScreen(a)
        ToolId.OPTIMIZE_IMAGES -> OptimizeImagesScreen(a)
        ToolId.MERGE_IMAGES -> MergeScreen(a)
        ToolId.STITCH -> StitchScreen(a)
        ToolId.WATERMARK -> WatermarkScreen(a)
        ToolId.VIDEO_TO_GIF -> VideoToGifScreen(a)
        ToolId.COMPRESS_GIF -> CompressGifScreen(a)
        ToolId.OPTIMIZE_GIF -> OptimizeGifScreen(a)
        ToolId.PDF_TO_IMAGES -> PdfToImagesScreen(a)
        ToolId.IMAGES_TO_PDF -> ImagesToPdfScreen(a)
        ToolId.MERGE_PDFS -> MergePdfsScreen(a)
        ToolId.PDF_SCANNER -> ScannerScreen(a)
        ToolId.CONVERT_IMAGES -> ConvertScreen(a)
        ToolId.EXTRACT_AUDIO -> ExtractAudioScreen(a)
    }
}
