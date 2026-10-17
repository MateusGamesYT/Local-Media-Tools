package com.localmediatools.tools

import com.localmediatools.codec.meta.MetadataStripper
import com.localmediatools.codec.meta.StripReport
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.OutputArea
import com.localmediatools.core.OutputFile
import com.localmediatools.core.SkipItemException
import com.localmediatools.core.SniffedFormat
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.i18n.N_
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import com.localmediatools.i18n.trn
import com.localmediatools.image.EncodeSpec
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.ImagePipeline
import com.localmediatools.image.ImageSource
import com.localmediatools.ui.ToolRules
import com.localmediatools.video.Remuxer
import com.localmediatools.video.VideoProbe
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File

// =========================================================================== Remove metadata
class RemoveMetadataJob(inputs: List<MediaItem>) : ExportJob(ToolId.REMOVE_METADATA, inputs) {
    override val title = trn(inputs.size, "Removing metadata from {0} file", "Removing metadata from {0} files")

    override suspend fun run(ctx: JobContext) {
        val rawFormat = RawFallback.format(tool, inputs)
        ctx.forEachItem(inputs, parallel = true) { index, item ->
            when {
                item.kind == MediaKind.VIDEO -> video(ctx, index, item)
                item.format.isRaw -> raw(ctx, index, item, rawFormat)
                else -> image(ctx, index, item)
            }
        }
    }

    /**
     * A RAW file can't be cleaned in its own format, so a copy is made from its picture alone, in the
     * other files' kind at full quality ([RawFallback]): encoded pixels carry no metadata, and the
     * orientation is turned into the pixels so it stays upright.
     */
    private fun raw(ctx: JobContext, index: Int, item: MediaItem, format: ImageOutFormat): ItemResult = ImageSource.open(ctx.app, item).use { src ->
        val w = src.width; val h = src.height
        val (out, _) = Outputs.produce(ctx, OutputArea.CLEAN_IMAGES, "${item.baseName}_clean.${format.ext}", format.mime) { pending ->
            ImagePipeline.render(ctx, src, w, h, RawFallback.spec(format), pending, exactPixels = format.lossless) { f -> ctx.unitProgress(index, f) }
            ImagePipeline.verify(ctx.app, pending, w, h)
        }
        val label = ToolRules.rawLabel(item)
        ItemResult(item.name, ItemOutcome.SUCCESS,
            RawFallback.note(format, item) + " " + tr("None of its metadata was copied (location, camera, dates and the rest), and the picture is upright."),
            listOf(out), "$label → ${format.label} (" + (if (format.lossless) tr("lossless") else tr("quality {0}", RawFallback.JPEG_QUALITY)) + ") · ${w}×$h · ${Format.bytes(out.size)}")
    }

    private fun image(ctx: JobContext, index: Int, item: MediaItem): ItemResult {
        val resolver = ctx.app.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        MediaProbe.openInput(resolver, item.uri).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val ext = when (item.format) { SniffedFormat.JPEG -> "jpg"; SniffedFormat.PNG -> "png"; SniffedFormat.WEBP -> "webp"; SniffedFormat.GIF -> "gif"; else -> null }
            ?: throw SkipItemException(tr("{0} files can't be cleaned without re-encoding. Use Convert images to make a clean JPEG or PNG copy (converted files carry no metadata).", item.format.label))
        val mime = when (ext) { "jpg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/gif" }
        var report: StripReport? = null
        val (out, _) = Outputs.produce(ctx, OutputArea.CLEAN_IMAGES, "${item.baseName}_clean.$ext", mime) { pending ->
            pending.openStream().buffered(1 shl 16).use { os ->
                val open = { MediaProbe.openInput(resolver, item.uri) }
                report = try {
                    when (item.format) {
                        SniffedFormat.JPEG -> open().use { MetadataStripper.stripJpeg(it, os) }
                        SniffedFormat.PNG -> open().use { MetadataStripper.stripPng(it, os) }
                        SniffedFormat.WEBP -> MetadataStripper.stripWebp(open, os)
                        else -> open().use { MetadataStripper.stripGif(it, os) }
                    }
                } catch (e: java.io.IOException) {
                    throw UserFacingException(tr("The file is damaged or uses an unusual layout ({0}); nothing was saved.", e.message), e)
                }
            }
            if (report!!.clean) throw SkipItemException(tr("No metadata found — this file is already clean. Nothing was saved."))
            // The cleaned copy must decode to the same picture size.
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(pending.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            if (bounds.outWidth > 0 && (o.outWidth != bounds.outWidth || o.outHeight != bounds.outHeight)) {
                throw UserFacingException(tr("The cleaned copy failed verification, so it was discarded."))
            }
        }
        ctx.unitProgress(index, 1.0)
        val r = report!!
        val kept = buildList { if (r.orientation != 1) add(tr("orientation")); addAll(r.kept) }
        return ItemResult(item.name, ItemOutcome.SUCCESS,
            tr("Removed: {0}.", r.removed.joinToString(trc("between the items of a list", ", "))) + if (kept.isNotEmpty()) " " + tr("Kept: {0}.", kept.joinToString(trc("between the items of a list", ", "))) else "",
            listOf(out), "${Outputs.sizeChange(item.size, out.size)} · " + tr("image data copied unchanged"))
    }

    private fun video(ctx: JobContext, index: Int, item: MediaItem): ItemResult {
        val info = VideoProbe.probe(ctx.app, item.uri)
        val out = remuxToOutput(ctx, item, OutputArea.CLEAN_VIDEO, "${item.baseName}_clean", avTracks(info), 0, Long.MAX_VALUE, info.rotation) { f -> ctx.unitProgress(index, f) }
        return ItemResult(item.name, ItemOutcome.SUCCESS,
            tr("Location, camera, date and other container metadata were not copied. Rotation is kept."), listOf(out),
            "${Outputs.sizeChange(item.size, out.size)} · " + tr("video and audio copied bit-for-bit"))
    }
}

// =========================================================================== Trim & rotate video
class TrimVideoJob(
    items: List<MediaItem>,
    private val startAt: Long,
    private val endAt: Long,
    /** Extra clockwise quarter turns. */
    private val turns: Int,
    /** In a tool stack the range is kept as fractions of each video's length (start, end). */
    private val fractions: Pair<Double, Double>? = null,
) : ExportJob(ToolId.TRIM_VIDEO, items) {
    constructor(item: MediaItem, startUs: Long, endUs: Long, turns: Int) : this(listOf(item), startUs, endUs, turns)

    override val title = if (items.size == 1) tr("Trimming {0}", items[0].name) else trn(items.size, "Trimming {0} video", "Trimming {0} videos")

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, it ->
            val info = VideoProbe.probe(ctx.app, it.uri)
            val v = info.video ?: throw UserFacingException(tr("This file has no video track."))
            val startUs = fractions?.let { f -> (f.first * info.durationUs).toLong() } ?: startAt
            val endUs = fractions?.let { f -> if (f.second >= 0.9995) Long.MAX_VALUE else (f.second * info.durationUs).toLong() } ?: endAt
            val end = endUs.coerceAtMost(info.durationUs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)
            if (end - startUs < 100_000) throw UserFacingException(tr("The selected part is too short."))
            ctx.status(tr("Finding keyframes"), it.name)
            val syncs = Remuxer(ctx.app, it.uri).syncTimes(v.index) { ctx.throttle() }
            val start = TrimMath.keyframeAtOrBefore(syncs, startUs)
            val rotation = ((info.rotation + 90 * turns) % 360 + 360) % 360
            val whole = start <= 0 && end >= info.durationUs - 50_000
            if (whole && turns % 4 == 0) throw SkipItemException(tr("Nothing to change: the whole video is selected and it isn't rotated."))
            val out = remuxToOutput(ctx, it, ToolId.TRIM_VIDEO.area, "${it.baseName}_trim", avTracks(info), start, end, rotation) { f -> ctx.unitProgress(index, f) }
            val notes = ArrayList<String>()
            if (startUs - start > 50_000) notes.add(tr("Starts {0} earlier than chosen, at the previous keyframe (cutting elsewhere would need re-encoding).", Format.seconds((startUs - start) / 1e6)))
            if (turns % 4 != 0) notes.add(tr("Turned {0}° clockwise (players read the rotation flag; pixels are untouched).", (turns % 4) * 90))
            ItemResult(it.name, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                "${Format.duration(start / 1000)} – ${Format.duration(end / 1000)} · ${Format.duration((end - start) / 1000)} · " + tr("no re-encoding"))
        }
    }
}

object TrimMath {
    /** Last keyframe at or before [t] (or the first keyframe when none is). */
    fun keyframeAtOrBefore(syncs: LongArray, t: Long): Long {
        if (syncs.isEmpty()) return 0
        var best = syncs[0]
        for (s in syncs) if (s <= t) best = s else break
        return best
    }
}

// =========================================================================== Extract PDF pages
enum class PageMode(private val en: String) { ONE_FILE(N_("One PDF")), EACH_PAGE(N_("One PDF per page")); val label: String get() = tr(en) }

object PageRanges {
    /**
     * Parses "1-3, 5, 8-" style lists into 0-based page indices in the order written. "-4" means
     * 1–4 and "8-" means 8 to the end. Pages past the end are reported, not silently dropped.
     */
    fun parse(spec: String, pageCount: Int): List<Int> {
        val out = ArrayList<Int>()
        val parts = spec.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw UserFacingException(tr("Enter the pages to keep, like 1-3, 5"))
        for (p in parts) {
            val m = Regex("^(\\d*)\\s*[-–]\\s*(\\d*)$").find(p)
            if (m != null) {
                val a = m.groupValues[1].ifEmpty { "1" }.toInt()
                val b = m.groupValues[2].ifEmpty { pageCount.toString() }.toInt()
                if (a < 1 || b < a) throw UserFacingException(tr("\"{0}\" isn't a valid page range", p))
                if (a > pageCount) throw UserFacingException(trn(pageCount, "Page {1} doesn't exist; the PDF has {0} page", "Page {1} doesn't exist; the PDF has {0} pages", a))
                for (i in a..minOf(b, pageCount)) out.add(i - 1)
            } else {
                val n = p.toIntOrNull() ?: throw UserFacingException(tr("\"{0}\" isn't a page number", p))
                if (n < 1 || n > pageCount) throw UserFacingException(trn(pageCount, "Page {1} doesn't exist; the PDF has {0} page", "Page {1} doesn't exist; the PDF has {0} pages", n))
                out.add(n - 1)
            }
        }
        return out
    }

    /** Light check before running (page count unknown yet). */
    fun validateSyntax(spec: String): String? = try { parse(spec, Int.MAX_VALUE / 2); null } catch (e: UserFacingException) { e.message }
}

class ExtractPagesJob(inputs: List<MediaItem>, private val spec: String, private val mode: PageMode) : ExportJob(ToolId.EXTRACT_PDF_PAGES, inputs) {
    override val title = trn(inputs.size, "Extracting pages from {0} PDF", "Extracting pages from {0} PDFs")

    override suspend fun run(ctx: JobContext) {
        PDFBoxResourceLoader.init(ctx.app)
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val tmpDir = File(Outputs.tempDir(ctx), "pages-$index").apply { mkdirs() }
            val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(tmpDir)
            val src = try {
                MediaProbe.openInput(ctx.app.contentResolver, item.uri).use { PDDocument.load(it, memory) }
            } catch (e: InvalidPasswordException) {
                throw UserFacingException(tr("This PDF is password-protected. Remove the password first."))
            } catch (e: Exception) {
                throw UserFacingException(tr("Not a valid PDF or damaged ({0}).", e.message ?: e.javaClass.simpleName), e)
            }
            try {
                if (src.isEncrypted) src.isAllSecurityToBeRemoved = true
                val pages = PageRanges.parse(spec, src.numberOfPages)
                val groups = if (mode == PageMode.ONE_FILE) listOf(pages) else pages.map { listOf(it) }
                val outputs = ArrayList<OutputFile>()
                for ((g, group) in groups.withIndex()) {
                    ctx.throttle()
                    val dest = PDDocument(memory)
                    try {
                        for (i in group) dest.importPage(src.getPage(i))
                        dest.documentInformation.producer = "Local Media Tools"
                        val tmp = File(tmpDir, "out-$g.pdf")
                        dest.save(tmp)
                        val name = if (mode == PageMode.ONE_FILE) "${item.baseName}_pages.pdf" else "${item.baseName}_p${group[0] + 1}.pdf"
                        val (out, _) = Outputs.produce(ctx, ToolId.EXTRACT_PDF_PAGES.area, name, "application/pdf") { pending ->
                            tmp.inputStream().use { input -> pending.openStream().use { os -> Outputs.copy(input, os, tmp.length(), ctx) } }
                            verifyPdf(ctx, pending.uri, group.size)
                        }
                        tmp.delete()
                        outputs.add(out)
                    } finally { dest.close() }
                    ctx.unitProgress(index, (g + 1).toDouble() / groups.size)
                }
                ItemResult(item.name, ItemOutcome.SUCCESS, null, outputs,
                    trn(src.numberOfPages, "{1} of {0} page", "{1} of {0} pages", pages.size) + " · " + trn(outputs.size, "{0} PDF", "{0} PDFs") + " · " + tr("text and links kept"))
            } finally {
                src.close()
                tmpDir.deleteRecursively()
            }
        }
        Outputs.cleanTemp(ctx)
    }
}
