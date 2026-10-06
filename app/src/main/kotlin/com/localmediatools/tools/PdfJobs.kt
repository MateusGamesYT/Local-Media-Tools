package com.localmediatools.tools

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.localmediatools.codec.image.ExifOrientationReader
import com.localmediatools.codec.image.JpegInfo
import com.localmediatools.codec.image.Orientation
import com.localmediatools.codec.jpeg.JpegWriter
import com.localmediatools.codec.pdf.PdfImageWriter
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.SniffedFormat
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.image.BitmapOps
import com.localmediatools.image.EncodeSpec
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.ImagePipeline
import com.localmediatools.image.ImageSource
import com.localmediatools.pdf.PdfFiles
import com.localmediatools.pdf.PdfMerge
import com.localmediatools.pdf.PdfPageRasterizer
import com.localmediatools.stitch.OpenCvLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timestampName(prefix: String) = "$prefix ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())}"

private fun verifyPdf(ctx: JobContext, uri: android.net.Uri, expectPages: Int) {
    val pfd = ctx.app.contentResolver.openFileDescriptor(uri, "r") ?: throw UserFacingException("The written PDF could not be read back.")
    pfd.use {
        val r = try { PdfRenderer(it) } catch (e: Exception) { throw UserFacingException("The written PDF failed verification, so it was discarded.", e) }
        r.use { rr -> if (rr.pageCount != expectPages) throw UserFacingException("The written PDF has ${rr.pageCount} pages instead of $expectPages, so it was discarded.") }
    }
}

// =========================================================================== 13. PDF → images
class PdfToImagesJob(
    inputs: List<MediaItem>,
    private val format: ImageOutFormat,  // PNG or JPEG
    private val quality: Int,
    private val scalePercent: Int,
) : ExportJob(ToolId.PDF_TO_IMAGES, inputs) {
    override val title = "Rendering ${plural(inputs.size, "PDF")} to ${format.label} at $scalePercent%"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val (pfd, tmp) = PdfFiles.openDescriptor(ctx.app, item.uri, Outputs.tempDir(ctx))
            try {
                PdfFiles.renderer(pfd).use { renderer ->
                    val pages = renderer.pageCount
                    if (pages <= 0) throw UserFacingException("The PDF has no pages.")
                    val digits = maxOf(3, pages.toString().length)
                    val outs = ArrayList<com.localmediatools.core.OutputFile>()
                    var maxDims = ""
                    for (p in 0 until pages) {
                        ctx.status("Page ${p + 1} of $pages", item.name)
                        val page = try { renderer.openPage(p) } catch (e: Exception) { throw UserFacingException("Page ${p + 1} could not be opened; the PDF may be damaged.", e) }
                        page.use { pg ->
                            val rast = PdfPageRasterizer(pg, scalePercent / 100.0)
                            val w = rast.width; val h = rast.height
                            if (w > format.maxDimension || h > format.maxDimension) {
                                throw UserFacingException("Page ${p + 1} would be ${w}×$h px at $scalePercent%, beyond ${format.label}'s limit. Use a lower scale.")
                            }
                            maxDims = "${w}×$h"
                            val budget = ctx.memoryBudget()
                            val rows = (budget / 6 / (w.toLong() * 8)).toInt().coerceIn(16, 1024)
                            val name = "${item.baseName}_p${(p + 1).toString().padStart(digits, '0')}.${format.ext}"
                            outs.add(Outputs.produce(ctx, ToolId.PDF_TO_IMAGES.area, name, format.mime) { pending ->
                                pending.openStream().buffered(1 shl 16).use { os ->
                                    val enc = ImagePipeline.stripEncoder(EncodeSpec(format, quality), w, h, false, os)
                                    rast.render(rows, { ctx.throttle() }) { _, n, argb -> enc.write(argb, 0, w, n) }
                                    enc.finish()
                                }
                                ImagePipeline.verify(ctx.app, pending, w, h)
                            }.first)
                        }
                        ctx.unitProgress(index, (p + 1).toDouble() / pages)
                    }
                    val dpi = 72 * scalePercent / 100
                    ItemResult(item.name, ItemOutcome.SUCCESS, null, outs, "$pages page images · $dpi DPI · last page $maxDims")
                }
            } finally {
                try { pfd.close() } catch (_: Exception) { }
                tmp?.delete()
            }
        }
        Outputs.cleanTemp(ctx)
    }
}

// =========================================================================== 14 & 16. Images → PDF / scanner
enum class PageOrientation(val label: String) { AUTO("Auto"), PORTRAIT("Portrait"), LANDSCAPE("Landscape") }
enum class PdfImageQuality(val label: String) { ORIGINAL("Original"), COMPACT("Compact") }
enum class ScanFilter(val label: String) { NONE("Original colour"), DOCUMENT("Clean document"), BLACK_WHITE("Black & white") }

class ImagesToPdfJob(
    tool: ToolId,
    inputs: List<MediaItem>,
    private val orientation: PageOrientation,
    private val quality: PdfImageQuality,
    private val fileName: String,
    private val filter: ScanFilter = ScanFilter.NONE,
) : ExportJob(tool, inputs) {
    override val title = "Creating a ${inputs.size}-page PDF"

    private var reducedPages = 0

    companion object {
        const val MARGIN_PT = 18.0 // ≈ 6.4 mm
        private const val COMPACT_MAX_SIDE = 2480 // A4 at 300 DPI
    }

    override suspend fun run(ctx: JobContext) {
        val name = Format.safeFileName(fileName.ifBlank { timestampName(if (tool == ToolId.PDF_SCANNER) "Scan" else "Images") })
        val notes = ArrayList<String>()
        var pagesWritten = 0
        var directJpeg = 0
        reducedPages = 0
        try {
            val (out, _) = Outputs.produce(ctx, tool.area, if (name.endsWith(".pdf", true)) name else "$name.pdf", "application/pdf") { pending ->
                pending.openStream().buffered(1 shl 16).use { os ->
                    val pdf = PdfImageWriter(os)
                    for ((i, item) in inputs.withIndex()) {
                        ctx.status("Page ${i + 1} of ${inputs.size}", item.name)
                        ctx.throttle()
                        item.readError?.let { throw UserFacingException("\"${item.name}\": $it") }
                        try {
                            if (addPage(ctx, pdf, item)) directJpeg++
                        } catch (e: UserFacingException) {
                            throw UserFacingException("Page ${i + 1} (\"${item.name}\"): ${e.message}", e)
                        }
                        pagesWritten++
                        ctx.unitDone(i)
                    }
                    pdf.finish(title = name.removeSuffix(".pdf"), creationDate = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date()))
                }
                verifyPdf(ctx, pending.uri, inputs.size)
            }
            if (directJpeg > 0) notes.add("$directJpeg JPEG photo(s) were embedded without recompression.")
            if (reducedPages > 0) notes.add("$reducedPages very large image(s) were scaled down to fit in memory.")
            if (tool == ToolId.PDF_SCANNER) notes.add("Pages are images; the text is not searchable or selectable.")
            ctx.addResult(ItemResult("${inputs.size} images", ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                "$pagesWritten A4 pages · ${Format.bytes(out.size)}"))
        } catch (e: com.localmediatools.core.ExportCancelledException) {
            throw e
        } catch (e: Throwable) {
            ctx.addResult(ItemResult("${inputs.size} images", ItemOutcome.FAILED, com.localmediatools.core.Errors.describe(e)))
        }
    }

    private fun page(displayW: Int, displayH: Int): PdfImageWriter.PageBox = when (orientation) {
        PageOrientation.PORTRAIT -> PdfImageWriter.PageBox.A4_PORTRAIT
        PageOrientation.LANDSCAPE -> PdfImageWriter.PageBox.A4_LANDSCAPE
        PageOrientation.AUTO -> if (displayW > displayH * 1.05) PdfImageWriter.PageBox.A4_LANDSCAPE else PdfImageWriter.PageBox.A4_PORTRAIT
    }

    /** Adds one page; returns true when the original JPEG bytes were embedded. */
    private fun addPage(ctx: JobContext, pdf: PdfImageWriter, item: MediaItem): Boolean {
        val resolver = ctx.app.contentResolver
        if (filter == ScanFilter.NONE && quality == PdfImageQuality.ORIGINAL && item.format == SniffedFormat.JPEG) {
            val info = MediaProbe.openInput(resolver, item.uri).buffered().use { JpegInfo.parse(it) }
            if (info != null && info.embeddableInPdf) {
                val head = ByteArray(256 * 1024)
                val n = MediaProbe.openInput(resolver, item.uri).use { s ->
                    var off = 0
                    while (off < head.size) { val k = s.read(head, off, head.size - off); if (k < 0) break; off += k }
                    off
                }
                // Same orientation source as everywhere else in the app.
                val o = ExifOrientationReader.read(head.copyOf(n)) ?: Orientation.NORMAL
                ImageSource.open(ctx.app, item).close() // integrity check (truncated files are rejected)
                pdf.addJpegPage({ MediaProbe.openInput(resolver, item.uri).buffered(1 shl 16) }, info, o,
                    page(o.displayWidth(info.width, info.height), o.displayHeight(info.width, info.height)), MARGIN_PT)
                return true
            }
        }
        ImageSource.open(ctx.app, item).use { src ->
            val budget = ctx.memoryBudget()
            val longest = maxOf(src.width, src.height)
            var maxSide = if (quality == PdfImageQuality.COMPACT || filter != ScanFilter.NONE) minOf(COMPACT_MAX_SIDE, longest) else longest
            // Keep the decoded page (plus a working copy) within the memory budget.
            fun bytesAt(side: Int): Long { val s = side.toDouble() / longest; return (src.width * s).toLong() * (src.height * s).toLong() * 4 * 3 }
            while (bytesAt(maxSide) > budget && maxSide > 1200) maxSide = (maxSide * 0.85).toInt()
            if (maxSide < longest && quality == PdfImageQuality.ORIGINAL && filter == ScanFilter.NONE) {
                reducedPages++
            }
            val scale = if (longest > maxSide) maxSide.toDouble() / longest else 1.0
            val w = maxOf(1, Math.round(src.width * scale).toInt()); val h = maxOf(1, Math.round(src.height * scale).toInt())
            var bmp = src.decode(ImagePipeline.sampleFor(1 / scale), budget)
            if (bmp.width != w || bmp.height != h) bmp = BitmapOps.scale(bmp, w, h, recycleSource = true)
            try {
                val box = page(w, h)
                when {
                    filter != ScanFilter.NONE -> {
                        val gray = enhance(bmp, filter)
                        if (filter == ScanFilter.BLACK_WHITE) {
                            pdf.addRawPage(grayRows(gray), box, MARGIN_PT, 9)
                        } else {
                            pdf.addJpegPageBytes(jpegGray(gray, 85), box)
                        }
                    }
                    quality == PdfImageQuality.ORIGINAL && item.format.isLosslessSource() -> {
                        pdf.addRawPage(bitmapRows(bmp, BitmapOps.hasTransparency(bmp)), box, MARGIN_PT)
                    }
                    else -> {
                        val q = if (quality == PdfImageQuality.ORIGINAL) 92 else 82
                        val flat = BitmapOps.flatten(bmp, android.graphics.Color.WHITE, recycleSource = false)
                        val bytes = ByteArrayOutputStream()
                        flat.compress(Bitmap.CompressFormat.JPEG, q, bytes)
                        if (flat !== bmp) flat.recycle()
                        pdf.addJpegPageBytes(bytes.toByteArray(), box)
                    }
                }
            } finally {
                bmp.recycle()
            }
        }
        return false
    }

    private fun SniffedFormat.isLosslessSource() = this in setOf(SniffedFormat.PNG, SniffedFormat.GIF, SniffedFormat.BMP, SniffedFormat.TIFF,
        SniffedFormat.PSD, SniffedFormat.QOI, SniffedFormat.PNM, SniffedFormat.TGA, SniffedFormat.ICO, SniffedFormat.WBMP)

    private fun PdfImageWriter.addJpegPageBytes(bytes: ByteArray, box: PdfImageWriter.PageBox) {
        val info = JpegInfo.parse(bytes.inputStream()) ?: throw UserFacingException("Internal JPEG encoding failed.")
        addJpegPage({ bytes.inputStream() }, info, Orientation.NORMAL, box, MARGIN_PT)
    }

    private fun bitmapRows(b: Bitmap, alpha: Boolean) = object : PdfImageWriter.RowSource {
        override val width = b.width
        override val height = b.height
        override val hasAlpha = alpha
        override val grayscale = false
        override fun readRows(y: Int, count: Int, dst: IntArray) = b.getPixels(dst, 0, b.width, 0, y, b.width, count)
    }

    private fun grayRows(g: GrayImage) = object : PdfImageWriter.RowSource {
        override val width = g.w
        override val height = g.h
        override val hasAlpha = false
        override val grayscale = true
        override fun readRows(y: Int, count: Int, dst: IntArray) {
            for (r in 0 until count) for (x in 0 until g.w) {
                val v = g.px[(y + r) * g.w + x].toInt() and 0xFF
                dst[r * g.w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    class GrayImage(val w: Int, val h: Int, val px: ByteArray)

    /**
     * Scanner enhancement with OpenCV: illumination flattening (divide by a blurred background),
     * contrast stretch, and for black & white an adaptive threshold.
     */
    private fun enhance(bmp: Bitmap, f: ScanFilter): GrayImage {
        OpenCvLoader.ensure()
        val rgba = Mat(); Utils.bitmapToMat(bmp, rgba)
        val gray = Mat(); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); rgba.release()
        val k = (maxOf(gray.cols(), gray.rows()) / 25) or 1
        val bg = Mat()
        Imgproc.morphologyEx(gray, bg, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0)))
        Imgproc.GaussianBlur(bg, bg, Size(k.toDouble(), k.toDouble()), 0.0)
        val g32 = Mat(); val b32 = Mat(); gray.convertTo(g32, CvType.CV_32F); bg.convertTo(b32, CvType.CV_32F)
        Core.max(b32, org.opencv.core.Scalar(1.0), b32)
        val norm = Mat(); Core.divide(g32, b32, norm, 255.0)
        norm.convertTo(gray, CvType.CV_8U)
        g32.release(); b32.release(); norm.release(); bg.release()
        val out = Mat()
        if (f == ScanFilter.BLACK_WHITE) {
            Imgproc.adaptiveThreshold(gray, out, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 12.0)
        } else {
            Core.normalize(gray, out, 0.0, 255.0, Core.NORM_MINMAX)
        }
        gray.release()
        val px = ByteArray(out.cols() * out.rows())
        out.get(0, 0, px)
        val res = GrayImage(out.cols(), out.rows(), px)
        out.release()
        return res
    }

    private fun jpegGray(g: GrayImage, q: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val jw = JpegWriter(bos, g.w, g.h, q, grayscale = true)
        val row = IntArray(g.w)
        for (y in 0 until g.h) {
            for (x in 0 until g.w) { val v = g.px[y * g.w + x].toInt() and 0xFF; row[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            jw.writeRows(row, 0, g.w, 1)
        }
        jw.finish()
        return bos.toByteArray()
    }
}

// =========================================================================== 15. Merge PDFs
class MergePdfsJob(inputs: List<MediaItem>, private val fileName: String) : ExportJob(ToolId.MERGE_PDFS, inputs) {
    override val title = "Merging ${plural(inputs.size, "PDF")}"

    override suspend fun run(ctx: JobContext) {
        val name = Format.safeFileName(fileName.ifBlank { timestampName("Merged") })
        try {
            for (i in inputs) i.readError?.let { throw UserFacingException("\"${i.name}\": $it") }
            com.localmediatools.core.OutputStore.ensureSpace(inputs.sumOf { it.size.coerceAtLeast(0) } * 2)
            val tmp = File(Outputs.tempDir(ctx), "merged.pdf")
            ctx.status("Reading documents")
            val result = PdfMerge.merge(ctx.app, inputs.map { it.name to it.uri }, tmp, Outputs.tempDir(ctx), { ctx.throttle() }) { done ->
                ctx.status("Added ${done} of ${inputs.size}")
                ctx.unitProgress(0, done.toDouble() / inputs.size * 0.8)
            }
            val (out, _) = Outputs.produce(ctx, ToolId.MERGE_PDFS.area, if (name.endsWith(".pdf", true)) name else "$name.pdf", "application/pdf") { pending ->
                tmp.inputStream().use { input -> pending.openStream().use { os -> Outputs.copy(input, os, tmp.length(), ctx) } }
                verifyPdf(ctx, pending.uri, result.pages)
            }
            tmp.delete()
            for (k in inputs.indices) ctx.unitDone(k)
            ctx.addResult(ItemResult("${inputs.size} PDFs", ItemOutcome.SUCCESS, result.notes.joinToString(" ").ifBlank { null }, listOf(out),
                "${result.pages} pages · ${Format.bytes(out.size)} · text, links and page sizes kept"))
        } catch (e: com.localmediatools.core.ExportCancelledException) {
            throw e
        } catch (e: Throwable) {
            ctx.addResult(ItemResult("${inputs.size} PDFs", ItemOutcome.FAILED, com.localmediatools.core.Errors.describe(e)))
        } finally {
            Outputs.cleanTemp(ctx)
        }
    }
}

@Suppress("unused")
private fun ParcelFileDescriptor.sizeOrUnknown() = statSize
