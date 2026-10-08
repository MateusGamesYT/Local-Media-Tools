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
import com.localmediatools.video.Remuxer
import com.localmediatools.video.VideoProbe
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File

// =========================================================================== Remove metadata
class RemoveMetadataJob(inputs: List<MediaItem>) : ExportJob(ToolId.REMOVE_METADATA, inputs) {
    override val title = "Removing metadata from ${plural(inputs.size, "file")}"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = true) { index, item ->
            if (item.kind == MediaKind.VIDEO) video(ctx, index, item) else image(ctx, index, item)
        }
    }

    private fun image(ctx: JobContext, index: Int, item: MediaItem): ItemResult {
        val resolver = ctx.app.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        MediaProbe.openInput(resolver, item.uri).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val ext = when (item.format) { SniffedFormat.JPEG -> "jpg"; SniffedFormat.PNG -> "png"; SniffedFormat.WEBP -> "webp"; SniffedFormat.GIF -> "gif"; else -> null }
            ?: throw SkipItemException("${item.format.label} files can't be cleaned without re-encoding. Use Convert images to make a clean JPEG or PNG copy (converted files carry no metadata).")
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
                    throw UserFacingException("The file is damaged or uses an unusual layout (${e.message}); nothing was saved.", e)
                }
            }
            if (report!!.clean) throw SkipItemException("No metadata found — this file is already clean. Nothing was saved.")
            // The cleaned copy must decode to the same picture size.
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(pending.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            if (bounds.outWidth > 0 && (o.outWidth != bounds.outWidth || o.outHeight != bounds.outHeight)) {
                throw UserFacingException("The cleaned copy failed verification, so it was discarded.")
            }
        }
        ctx.unitProgress(index, 1.0)
        val r = report!!
        val kept = buildList { if (r.orientation != 1) add("orientation"); addAll(r.kept) }
        return ItemResult(item.name, ItemOutcome.SUCCESS,
            "Removed: ${r.removed.joinToString(", ")}." + if (kept.isNotEmpty()) " Kept: ${kept.joinToString(", ")}." else "",
            listOf(out), "${Outputs.sizeChange(item.size, out.size)} · image data copied unchanged")
    }

    private fun video(ctx: JobContext, index: Int, item: MediaItem): ItemResult {
        val info = VideoProbe.probe(ctx.app, item.uri)
        val out = remuxToOutput(ctx, item, OutputArea.CLEAN_VIDEO, "${item.baseName}_clean", avTracks(info), 0, Long.MAX_VALUE, info.rotation) { f -> ctx.unitProgress(index, f) }
        return ItemResult(item.name, ItemOutcome.SUCCESS,
            "Location, camera, date and other container metadata were not copied. Rotation is kept.", listOf(out),
            "${Outputs.sizeChange(item.size, out.size)} · video and audio copied bit-for-bit")
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

    override val title = if (items.size == 1) "Trimming ${items[0].name}" else "Trimming ${plural(items.size, "video")}"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, it ->
            val info = VideoProbe.probe(ctx.app, it.uri)
            val v = info.video ?: throw UserFacingException("This file has no video track.")
            val startUs = fractions?.let { f -> (f.first * info.durationUs).toLong() } ?: startAt
            val endUs = fractions?.let { f -> if (f.second >= 0.9995) Long.MAX_VALUE else (f.second * info.durationUs).toLong() } ?: endAt
            val end = endUs.coerceAtMost(info.durationUs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)
            if (end - startUs < 100_000) throw UserFacingException("The selected part is too short.")
            ctx.status("Finding keyframes", it.name)
            val syncs = Remuxer(ctx.app, it.uri).syncTimes(v.index) { ctx.throttle() }
            val start = TrimMath.keyframeAtOrBefore(syncs, startUs)
            val rotation = ((info.rotation + 90 * turns) % 360 + 360) % 360
            val whole = start <= 0 && end >= info.durationUs - 50_000
            if (whole && turns % 4 == 0) throw SkipItemException("Nothing to change: the whole video is selected and it isn't rotated.")
            val out = remuxToOutput(ctx, it, ToolId.TRIM_VIDEO.area, "${it.baseName}_trim", avTracks(info), start, end, rotation) { f -> ctx.unitProgress(index, f) }
            val notes = ArrayList<String>()
            if (startUs - start > 50_000) notes.add("Starts ${Format.seconds((startUs - start) / 1e6)} earlier than chosen, at the previous keyframe (cutting elsewhere would need re-encoding).")
            if (turns % 4 != 0) notes.add("Turned ${(turns % 4) * 90}° clockwise (players read the rotation flag; pixels are untouched).")
            ItemResult(it.name, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                "${Format.duration(start / 1000)} – ${Format.duration(end / 1000)} · ${Format.duration((end - start) / 1000)} · no re-encoding")
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
enum class PageMode(val label: String) { ONE_FILE("One PDF"), EACH_PAGE("One PDF per page") }

object PageRanges {
    /**
     * Parses "1-3, 5, 8-" style lists into 0-based page indices in the order written. "-4" means
     * 1–4 and "8-" means 8 to the end. Pages past the end are reported, not silently dropped.
     */
    fun parse(spec: String, pageCount: Int): List<Int> {
        val out = ArrayList<Int>()
        val parts = spec.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw UserFacingException("Enter the pages to keep, like 1-3, 5")
        for (p in parts) {
            val m = Regex("^(\\d*)\\s*[-–]\\s*(\\d*)$").find(p)
            if (m != null) {
                val a = m.groupValues[1].ifEmpty { "1" }.toInt()
                val b = m.groupValues[2].ifEmpty { pageCount.toString() }.toInt()
                if (a < 1 || b < a) throw UserFacingException("\"$p\" isn't a valid page range")
                if (a > pageCount) throw UserFacingException("Page $a doesn't exist; the PDF has $pageCount page${if (pageCount == 1) "" else "s"}")
                for (i in a..minOf(b, pageCount)) out.add(i - 1)
            } else {
                val n = p.toIntOrNull() ?: throw UserFacingException("\"$p\" isn't a page number")
                if (n < 1 || n > pageCount) throw UserFacingException("Page $n doesn't exist; the PDF has $pageCount page${if (pageCount == 1) "" else "s"}")
                out.add(n - 1)
            }
        }
        return out
    }

    /** Light check before running (page count unknown yet). */
    fun validateSyntax(spec: String): String? = try { parse(spec, Int.MAX_VALUE / 2); null } catch (e: UserFacingException) { e.message }
}

class ExtractPagesJob(inputs: List<MediaItem>, private val spec: String, private val mode: PageMode) : ExportJob(ToolId.EXTRACT_PDF_PAGES, inputs) {
    override val title = "Extracting pages from ${plural(inputs.size, "PDF")}"

    override suspend fun run(ctx: JobContext) {
        PDFBoxResourceLoader.init(ctx.app)
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val tmpDir = File(Outputs.tempDir(ctx), "pages-$index").apply { mkdirs() }
            val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(tmpDir)
            val src = try {
                MediaProbe.openInput(ctx.app.contentResolver, item.uri).use { PDDocument.load(it, memory) }
            } catch (e: InvalidPasswordException) {
                throw UserFacingException("This PDF is password-protected. Remove the password first.")
            } catch (e: Exception) {
                throw UserFacingException("Not a valid PDF or damaged (${e.message ?: e.javaClass.simpleName}).", e)
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
                    "${pages.size} of ${src.numberOfPages} pages · ${if (mode == PageMode.ONE_FILE) "1 PDF" else "${outputs.size} PDFs"} · text and links kept")
            } finally {
                src.close()
                tmpDir.deleteRecursively()
            }
        }
        Outputs.cleanTemp(ctx)
    }
}
