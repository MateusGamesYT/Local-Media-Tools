package com.localmediatools.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.localmediatools.codec.layout.Align
import com.localmediatools.codec.layout.MergeLayouts
import com.localmediatools.codec.layout.PackResult
import com.localmediatools.codec.png.PngColorMode
import com.localmediatools.codec.png.PngWriter
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.OutputStore
import com.localmediatools.core.SniffedFormat
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.N_
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import com.localmediatools.i18n.trn
import com.localmediatools.image.AspectGroups
import com.localmediatools.image.BitmapOps
import com.localmediatools.image.EncodeSpec
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.ImagePipeline
import com.localmediatools.image.ImageSource
import com.localmediatools.image.WatermarkPosition
import com.localmediatools.image.WatermarkRenderer
import com.localmediatools.image.WatermarkStyle
import com.localmediatools.stitch.OpenCvLoader
import com.localmediatools.stitch.SourceStitchImages
import com.localmediatools.stitch.TfliteEmbedder
import com.localmediatools.stitch.core.SceneMode
import com.localmediatools.stitch.core.StitchMonitor
import com.localmediatools.stitch.core.StitchOptions
import com.localmediatools.stitch.core.StitchSink
import com.localmediatools.stitch.core.Stitcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MergeLayout(private val labelEn: String, private val hintEn: String) {
    VERTICAL(N_("Vertical"), N_("One image below another")),
    HORIZONTAL(N_("Horizontal"), N_("Images side by side")),
    SMART(N_("Smart pack"), N_("Compact arrangement of different sizes"));

    val label: String get() = tr(labelEn)
    val hint: String get() = tr(hintEn)
}

enum class MergeBackground(private val labelEn: String, val color: Int) {
    TRANSPARENT(N_("Transparent"), Color.TRANSPARENT), WHITE(N_("White"), Color.WHITE), BLACK(N_("Black"), Color.BLACK);

    val label: String get() = tr(labelEn)
}

object MergePlanner {
    fun plan(sizes: List<Pair<Int, Int>>, layout: MergeLayout, spacing: Int, align: Align): PackResult = when (layout) {
        MergeLayout.VERTICAL -> MergeLayouts.vertical(sizes, spacing, align)
        MergeLayout.HORIZONTAL -> MergeLayouts.horizontal(sizes, spacing, align)
        MergeLayout.SMART -> MergeLayouts.smartPack(sizes, spacing)
    }
}

// =========================================================================== 7. Merge images
class MergeImagesJob(
    inputs: List<MediaItem>,
    private val layout: MergeLayout,
    private val background: MergeBackground,
    private val spacing: Int,
    private val align: Align,
) : ExportJob(ToolId.MERGE_IMAGES, inputs) {
    override val title = trn(inputs.size, "Merging {0} image ({1})", "Merging {0} images ({1})", layout.label.lowercase(I18n.locale))

    override suspend fun run(ctx: JobContext) {
        val sources = ArrayList<ImageSource>()
        try {
            for (item in inputs) {
                item.readError?.let { throw UserFacingException("\"${item.name}\": $it") }
                try { sources.add(ImageSource.open(ctx.app, item)) }
                catch (e: UserFacingException) { throw UserFacingException("\"${item.name}\": ${e.message}", e) }
            }
            val plan = MergePlanner.plan(sources.map { it.width to it.height }, layout, spacing, align)
            val w = plan.width; val h = plan.height
            if (w.toLong() * h > 2_000_000_000L) throw UserFacingException(tr("The merged image would be {0}×{1} pixels, which is too large to save.", w, h))
            OutputStore.ensureSpace(w.toLong() * h * 2)
            val budget = ctx.memoryBudget()
            // How many images can share a row: decide which sources are decoded once and kept.
            var concurrency = 1
            for (r in plan.positions) concurrency = maxOf(concurrency, plan.positions.count { it.y < r.bottom && r.y < it.bottom })
            val perImage = budget / (2L * concurrency)
            val cacheable = sources.map { it.bytesFor(1) <= perImage }
            val stripRows = (budget / 8 / (w.toLong() * 4)).toInt().coerceIn(16, 1024).coerceAtMost(h)
            val name = trc("default file name", "Merged") + " ${SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())}.png"
            val transparent = background == MergeBackground.TRANSPARENT
            val (out, _) = Outputs.produce(ctx, ToolId.MERGE_IMAGES.area, name, "image/png") { pending ->
                pending.openStream().buffered(1 shl 16).use { os ->
                    val png = PngWriter(os, w, h, if (transparent) PngColorMode.RGBA else PngColorMode.RGB, null, 6)
                    val strip = Bitmap.createBitmap(w, stripRows, Bitmap.Config.ARGB_8888)
                    val px = IntArray(w * stripRows)
                    val cache = HashMap<Int, Bitmap>()
                    val paint = Paint().apply { isFilterBitmap = false }
                    try {
                        var y = 0
                        while (y < h) {
                            ctx.throttle()
                            val rows = minOf(stripRows, h - y)
                            strip.eraseColor(background.color)
                            val c = Canvas(strip)
                            for ((i, r) in plan.positions.withIndex()) {
                                if (r.bottom <= y || r.y >= y + rows) {
                                    if (r.bottom <= y) cache.remove(i)?.recycle()
                                    continue
                                }
                                val top = maxOf(y, r.y); val bottom = minOf(y + rows, r.bottom)
                                if (cacheable[i]) {
                                    val full = cache.getOrPut(i) { sources[i].decode(1, budget) }
                                    val srcRect = Rect(0, top - r.y, r.w, bottom - r.y)
                                    val dstRect = Rect(r.x, top - y, r.x + r.w, bottom - y)
                                    c.drawBitmap(full, srcRect, dstRect, paint)
                                } else {
                                    val part = sources[i].decodeRegion(Rect(0, top - r.y, r.w, bottom - r.y), 1, budget)
                                    c.drawBitmap(part, r.x.toFloat(), (top - y).toFloat(), paint)
                                    part.recycle()
                                }
                            }
                            strip.getPixels(px, 0, w, 0, 0, w, rows)
                            png.writeRows(px, 0, w, rows)
                            y += rows
                            ctx.unitProgress(0, y.toDouble() / h)
                        }
                        png.finish()
                    } finally {
                        strip.recycle()
                        cache.values.forEach { it.recycle() }
                    }
                }
                ImagePipeline.verify(ctx.app, pending, w, h)
            }
            for (k in inputs.indices) ctx.unitDone(k)
            val note = if (w > 30000 || h > 30000) tr("The result is very large ({0}×{1}); some gallery apps may not be able to open it.", w, h) else null
            ctx.addResult(ItemResult(trn(inputs.size, "{0} image", "{0} images"), ItemOutcome.SUCCESS, note, listOf(out),
                "${w}×$h · ${layout.label} · " + tr("{0} background", background.label) + " · ${Format.bytes(out.size)}"))
        } catch (e: com.localmediatools.core.ExportCancelledException) {
            throw e
        } catch (e: Throwable) {
            ctx.addResult(ItemResult(trn(inputs.size, "{0} image", "{0} images"), ItemOutcome.FAILED, com.localmediatools.core.Errors.describe(e)))
        } finally {
            sources.forEach { it.close() }
        }
    }

    override val unitCount: Int get() = inputs.size
}

// =========================================================================== 8. Multi-shot stitcher
class StitchJob(
    inputs: List<MediaItem>,
    private val useAi: Boolean,
    private val mode: SceneMode,
    private val crop: Boolean,
) : ExportJob(ToolId.STITCH, inputs) {
    override val title = if (useAi) trn(inputs.size, "Stitching {0} photo (AI assisted)", "Stitching {0} photos (AI assisted)") else trn(inputs.size, "Stitching {0} photo", "Stitching {0} photos")
    override val unitCount get() = 1

    override suspend fun run(ctx: JobContext) {
        val notes = ArrayList<String>()
        try {
            for (item in inputs) item.readError?.let { throw UserFacingException("\"${item.name}\": $it") }
            OpenCvLoader.ensure()
            val budget = ctx.memoryBudget()
            var assist: TfliteEmbedder? = null
            if (useAi) {
                assist = try {
                    TfliteEmbedder.create(ctx.app, ctx.workload.parallelism.coerceIn(1, 4))
                } catch (e: Throwable) {
                    notes.add(tr("AI assistance could not start on this device ({0}); stitched without it.", e.javaClass.simpleName))
                    null
                }
            }
            val workSide = if (ctx.workload.level < 0.4) 1200 else 1500
            val options = StitchOptions(mode = mode, useAi = assist != null, cropToRectangle = crop, workMaxSide = workSide,
                maxOutputPixels = minOf(250_000_000L, budget * 6), memoryBudget = budget,
                aiOffered = TfliteEmbedder.availability(ctx.app).first)
            val monitor = object : StitchMonitor {
                override fun stage(text: String, fraction: Double) {
                    ctx.status(text)
                    ctx.unitProgress(0, fraction)
                }
                override fun checkpoint() = ctx.throttle()
            }
            try {
                SourceStitchImages(ctx.app, inputs, budget).use { images ->
                    val name = trc("default file name", "Stitched") + " ${SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())}.png"
                    var report: com.localmediatools.stitch.core.StitchReport? = null
                    val (out, _) = Outputs.produce(ctx, ToolId.STITCH.area, name, "image/png") { pending ->
                        pending.openStream().buffered(1 shl 16).use { os ->
                            var png: PngWriter? = null
                            val sink = object : StitchSink {
                                override fun begin(width: Int, height: Int, hasTransparency: Boolean) {
                                    OutputStore.ensureSpace(width.toLong() * height * 2)
                                    png = PngWriter(os, width, height, if (hasTransparency) PngColorMode.RGBA else PngColorMode.RGB, null, 6)
                                }
                                override fun rows(y: Int, count: Int, argb: IntArray) = png!!.writeRows(argb, 0, png!!.width, count)
                                override fun finish() = png!!.finish()
                            }
                            report = Stitcher(images, options, assist, monitor).run(sink)
                        }
                        ImagePipeline.verify(ctx.app, pending, report!!.width, report!!.height)
                    }
                    val r = report!!
                    notes.addAll(r.notes)
                    if (assist != null) notes.add(if (r.aiDecisions > 0) trn(r.aiDecisions, "AI resolved {0} alignment decision on-device.", "AI resolved {0} alignment decisions on-device.") else tr("AI assistance was on but no ambiguous decisions needed it."))
                    ctx.unitDone(0)
                    ctx.addResult(ItemResult(trn(inputs.size, "{0} photo", "{0} photos"), ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                        "${r.width}×${r.height} · " + trn(inputs.size, "{1} of {0} photo", "{1} of {0} photos", r.usedImages.size) + " · ${r.model}" +
                            (if (r.outputScale < 0.999) " · " + tr("{0}% scale", (r.outputScale * 100).toInt()) else "") + " · ${Format.bytes(out.size)}"))
                }
            } finally {
                assist?.close()
            }
        } catch (e: com.localmediatools.core.ExportCancelledException) {
            throw e
        } catch (e: Throwable) {
            ctx.addResult(ItemResult(trn(inputs.size, "{0} photo", "{0} photos"), ItemOutcome.FAILED, com.localmediatools.core.Errors.describe(e)))
        }
    }
}

// =========================================================================== 9. Bulk watermark
data class WatermarkJobSpec(
    val text: String?,
    val logoUri: Uri?,
    val logoName: String?,
    val sizePercent: Int,
    val opacityPercent: Int,
    val textColor: Int,
    /** Placement per aspect-ratio group key (see [AspectGroups.key]). */
    val placements: Map<String, WatermarkPosition>,
    val defaultPosition: WatermarkPosition,
)

class WatermarkJob(inputs: List<MediaItem>, private val spec: WatermarkJobSpec) : ExportJob(ToolId.WATERMARK, inputs) {
    override val title = trn(inputs.size, "Watermarking {0} image", "Watermarking {0} images")

    override suspend fun run(ctx: JobContext) {
        val logo: Bitmap? = spec.logoUri?.let { uri ->
            try {
                ImageSource.open(ctx.app, uri, spec.logoName ?: "logo").use { it.preview(1600) }
            } catch (e: Exception) {
                throw UserFacingException(tr("The logo image could not be loaded: {0}", com.localmediatools.core.Errors.describe(e)))
            }
        }
        val style = WatermarkStyle(spec.text, logo, spec.sizePercent, spec.opacityPercent, spec.textColor)
        if (!style.hasContent) throw UserFacingException(tr("Add watermark text or a logo first."))
        val renderer = WatermarkRenderer(style)
        val rawFormat = RawFallback.format(tool, inputs)
        try {
            ctx.forEachItem(inputs, parallel = true) { index, item ->
                ImageSource.open(ctx.app, item).use { src ->
                    val w = src.width; val h = src.height
                    val group = AspectGroups.key(w, h).first
                    val pos = spec.placements[group] ?: spec.defaultPosition
                    // A RAW photo takes the other files' kind, at full quality.
                    val raw = item.format.isRaw
                    val format = if (raw) rawFormat else RawFallback.keptFormat(tool, item) ?: ImageOutFormat.PNG
                    val (out, _) = Outputs.produce(ctx, ToolId.WATERMARK.area, "${item.baseName}_wm.${format.ext}", format.mime) { pending ->
                        ImagePipeline.render(ctx, src, w, h, if (raw) RawFallback.spec(format) else EncodeSpec(format, 95), pending, renderer.overlay(pos)) { f -> ctx.unitProgress(index, f) }
                        ImagePipeline.verify(ctx.app, pending, w, h)
                    }
                    ItemResult(item.name, ItemOutcome.SUCCESS, if (raw) RawFallback.note(format, item) else null, listOf(out), "$group · ${pos.label} · ${w}×$h · ${format.label}")
                }
            }
        } finally {
            logo?.recycle()
        }
    }
}

@Suppress("unused")
private fun unusedFlatten(b: Bitmap) = BitmapOps.flatten(b, Color.WHITE, false)
