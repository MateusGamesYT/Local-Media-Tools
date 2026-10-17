package com.localmediatools.tools

import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.SkipItemException
import com.localmediatools.core.SniffedFormat
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import com.localmediatools.image.EncodeSpec
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.ImagePipeline
import com.localmediatools.image.ImageSource

/** Facts about a source file that influence how it may be processed. */
object SourceChecks {
    fun head(ctx: JobContext, item: MediaItem, n: Int = 64): ByteArray {
        val b = ByteArray(n)
        MediaProbe.openInput(ctx.app.contentResolver, item.uri).use { s ->
            var off = 0
            while (off < n) { val k = s.read(b, off, n - off); if (k < 0) break; off += k }
        }
        return b
    }

    fun isAnimatedWebp(h: ByteArray): Boolean =
        String(h, 12, 4, Charsets.ISO_8859_1) == "VP8X" && (h[20].toInt() and 0x02) != 0

    fun pngBitDepth(h: ByteArray): Int = h[24].toInt() and 0xFF

    fun isAnimated(ctx: JobContext, item: MediaItem): Boolean = when (item.format) {
        SniffedFormat.GIF -> true // treated as possibly animated: first frame only
        SniffedFormat.WEBP -> isAnimatedWebp(head(ctx, item, 32))
        else -> false
    }
}

// =========================================================================== 5. Image compressor
class CompressImagesJob(
    inputs: List<MediaItem>,
    private val format: ImageOutFormat,
    private val quality: Int,
    private val maxWidth: Int,
) : ExportJob(ToolId.COMPRESS_IMAGES, inputs) {
    override val title = trn(inputs.size, "Compressing {0} image to {1} (quality {2})", "Compressing {0} images to {1} (quality {2})", format.label, quality)

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = true) { index, item ->
            ImageSource.open(ctx.app, item).use { src ->
                val (ow, oh) = ImagePipeline.fitWidth(src.width, src.height, maxWidth)
                val resized = ow != src.width
                val suffix = "_q$quality" + if (resized) "_${ow}w" else ""
                var flattened = false
                val (out, _) = Outputs.produce(ctx, ToolId.COMPRESS_IMAGES.area, "${item.baseName}$suffix.${format.ext}", format.mime) { pending ->
                    val r = ImagePipeline.render(ctx, src, ow, oh, EncodeSpec(format, quality), pending) { f -> ctx.unitProgress(index, f) }
                    ImagePipeline.verify(ctx.app, pending, ow, oh)
                    if (!resized && item.size > 0 && r.bytes >= item.size) {
                        throw SkipItemException(tr("Already compact: re-encoding at quality {0} would make it larger ({1} vs {2}). Nothing was saved.", quality, Format.bytes(r.bytes), Format.bytes(item.size)))
                    }
                    flattened = r.flattenedTransparency
                }
                val notes = ArrayList<String>()
                if (flattened) notes.add(tr("Transparent areas were filled with white because JPEG has no transparency."))
                if (SourceChecks.isAnimated(ctx, item)) notes.add(tr("Only the first frame of the animation was used."))
                ItemResult(item.name, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                    "${Outputs.sizeChange(item.size, out.size)} · ${ow}×$oh · ${format.label} q$quality")
            }
        }
    }
}

// =========================================================================== 6. Lossless optimizer
class OptimizeImagesJob(
    inputs: List<MediaItem>,
    private val format: ImageOutFormat,  // PNG or WEBP_LOSSLESS
    private val keepOnlyIfSmaller: Boolean,
) : ExportJob(ToolId.OPTIMIZE_IMAGES, inputs) {
    override val title = trn(inputs.size, "Optimizing {0} image losslessly ({1})", "Optimizing {0} images losslessly ({1})", format.label)

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = true) { index, item ->
            when (item.format) {
                SniffedFormat.GIF -> throw SkipItemException(tr("GIFs are optimized with the GIF optimizer tool (this tool would keep only one frame)."))
                SniffedFormat.WEBP -> if (SourceChecks.isAnimatedWebp(SourceChecks.head(ctx, item, 32))) {
                    throw SkipItemException(tr("Animated WebP can't be optimized here without losing its animation."))
                }
                SniffedFormat.PNG -> if (SourceChecks.pngBitDepth(SourceChecks.head(ctx, item, 32)) == 16) {
                    throw SkipItemException(tr("16-bit-per-channel PNG can't be kept pixel-identical (Android decodes 8 bits per channel). Nothing was saved."))
                }
                SniffedFormat.TIFF, SniffedFormat.PSD -> Unit
                else -> Unit
            }
            // RAW photos, which can't be written back as RAW, always become PNG.
            val format = if (item.format.isRaw) ImageOutFormat.PNG else format
            ImageSource.open(ctx.app, item).use { src ->
                val w = src.width; val h = src.height
                val (out, _) = Outputs.produce(ctx, ToolId.OPTIMIZE_IMAGES.area, "${item.baseName}_lossless.${format.ext}", format.mime) { pending ->
                    val r = ImagePipeline.render(ctx, src, w, h, EncodeSpec(format, 100, optimizePng = true), pending, exactPixels = true) { f -> ctx.unitProgress(index, f) }
                    ImagePipeline.verify(ctx.app, pending, w, h)
                    if (keepOnlyIfSmaller && item.size > 0 && r.bytes >= item.size) {
                        val why = if (item.format == SniffedFormat.JPEG || item.format == SniffedFormat.HEIF || item.format == SniffedFormat.AVIF)
                            " " + tr("{0} photos are already lossy-compressed; storing their pixels losslessly takes more space.", item.format.label) else ""
                        throw SkipItemException(tr("Not smaller than the original ({0} vs {1}), so nothing was saved.", Format.bytes(r.bytes), Format.bytes(item.size)) + why)
                    }
                }
                val oriented = if (!src.orientation.isIdentity) " · " + tr("orientation applied to pixels") else ""
                ItemResult(item.name, ItemOutcome.SUCCESS, null, listOf(out),
                    "${Outputs.sizeChange(item.size, out.size)} · ${w}×$h · " + tr("pixel-identical") + oriented)
            }
        }
    }
}

// =========================================================================== 17. Convert
class ConvertImagesJob(
    inputs: List<MediaItem>,
    private val format: ImageOutFormat,
    private val quality: Int,
) : ExportJob(ToolId.CONVERT_IMAGES, inputs) {
    override val title = trn(inputs.size, "Converting {0} image to {1}", "Converting {0} images to {1}", format.label)

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = true) { index, item ->
            ImageSource.open(ctx.app, item).use { src ->
                val w = src.width; val h = src.height
                if (w > format.maxDimension || h > format.maxDimension) {
                    throw UserFacingException(tr("{0} supports at most {1} px per side; this image is {2}×{3}. Choose PNG or JPEG.", format.label, format.maxDimension, w, h))
                }
                var flattened = false
                val (out, _) = Outputs.produce(ctx, ToolId.CONVERT_IMAGES.area, "${item.baseName}.${format.ext}", format.mime) { pending ->
                    val r = ImagePipeline.render(ctx, src, w, h, EncodeSpec(format, quality, optimizePng = format == ImageOutFormat.PNG), pending,
                        exactPixels = format.lossless) { f -> ctx.unitProgress(index, f) }
                    ImagePipeline.verify(ctx.app, pending, w, h)
                    flattened = r.flattenedTransparency
                }
                val notes = ArrayList<String>()
                if (flattened) notes.add(tr("Transparent areas were filled with white because JPEG has no transparency."))
                if (SourceChecks.isAnimated(ctx, item)) notes.add(tr("Only the first frame of the animation was converted."))
                val q = if (format.lossless) tr("lossless") else tr("quality {0}", quality)
                ItemResult(item.name, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                    "${item.format.label} → ${format.label} ($q) · ${w}×$h · ${Format.bytes(out.size)}")
            }
        }
    }
}
