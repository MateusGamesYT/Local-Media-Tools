package com.localmediatools.tools

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.localmediatools.codec.mp4.Mp4FastStart
import com.localmediatools.core.ExportCancelledException
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.core.OutputStore
import com.localmediatools.core.SkipItemException
import com.localmediatools.core.SniffedFormat
import com.localmediatools.core.UserFacingException
import com.localmediatools.edit.EditRenderer
import com.localmediatools.edit.EditState
import com.localmediatools.edit.PatchKind
import com.localmediatools.edit.PatchStore
import com.localmediatools.edit.Retouch
import com.localmediatools.edit.RetouchPatch
import com.localmediatools.edit.RetouchedSource
import com.localmediatools.edit.Stroke
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.i18n.N_
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import com.localmediatools.i18n.trn
import com.localmediatools.image.CountingStream
import com.localmediatools.image.EncodeSpec
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.ImagePipeline
import com.localmediatools.image.ImageSource
import com.localmediatools.video.ObscureRegion
import com.localmediatools.video.VideoProbe
import com.localmediatools.video.VideoTranscoder
import com.localmediatools.vision.FaceScanner
import com.localmediatools.vision.VisionOps
import com.localmediatools.vision.core.FaceEngine
import com.localmediatools.vision.core.FaceTrack
import com.localmediatools.vision.core.FaceTracker
import com.localmediatools.vision.core.Matting
import com.localmediatools.vision.core.PrivacyOptions
import com.localmediatools.vision.core.PrivacyShape
import com.localmediatools.vision.core.PrivateTrack
import com.localmediatools.vision.core.PrivateTracker
import com.localmediatools.vision.core.Quad
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a tool that keeps formats saves [item]: photos with transparency stay PNG, everything else is a
 * high-quality JPEG; a RAW photo takes [rawFormat], the other files' kind, at full quality ([RawFallback]).
 */
private fun photoOutput(tool: ToolId, item: MediaItem, rawFormat: ImageOutFormat): Pair<ImageOutFormat, EncodeSpec> =
    if (item.format.isRaw) rawFormat to RawFallback.spec(rawFormat)
    else (RawFallback.keptFormat(tool, item) ?: ImageOutFormat.JPEG).let { it to EncodeSpec(it, 95) }

// =========================================================================== Background remover
enum class CutoutBackground(private val labelEn: String, val color: Int?) {
    TRANSPARENT(N_("Transparent"), null),
    WHITE(N_("White"), 0xFFFFFFFF.toInt()),
    BLACK(N_("Black"), 0xFF000000.toInt()),
    GREY(N_("Light grey"), 0xFFEDEFF2.toInt());

    val label: String get() = tr(labelEn)
}

class RemoveBackgroundJob(inputs: List<MediaItem>, private val background: CutoutBackground, private val crop: Boolean) : ExportJob(ToolId.BACKGROUND_REMOVER, inputs) {
    override val title = trn(inputs.size, "Removing the background from {0} photo", "Removing the background from {0} photos")

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            ImageSource.open(ctx.app, item).use { src ->
                val budget = ctx.memoryBudget()
                // Two full copies (picture and result) must fit; very large photos are reduced.
                var sample = 1
                while (sample < 16 && (src.bytesFor(sample) * 3 > budget || max(src.width, src.height) / sample > 8000)) sample *= 2
                ctx.status(tr("Finding the subject"), item.name)
                val bmp = src.decode(sample, budget)
                try {
                    val (mask, mw, mh) = VisionOps.subjectMask(ctx.app, bmp)
                    ctx.unitProgress(index, 0.5)
                    val box = Matting.bounds(mask, mw, mh, 0.25f)
                    val covered = mask.count { it > 0.5f }.toDouble() / mask.size
                    if (box == null || covered < 0.003) throw SkipItemException(tr("No clear subject was found in this photo, so nothing was saved."))
                    if (covered > 0.97) throw SkipItemException(tr("The whole photo looks like the subject, so there was no background to remove."))
                    val w = bmp.width; val h = bmp.height
                    // Crop rectangle in output pixels, with a small margin around the subject.
                    var l = 0; var t = 0; var r = w; var b = h
                    if (crop) {
                        val m = (0.03 * max(w, h)).roundToInt()
                        l = (box[0].toLong() * w / mw - m).toInt().coerceAtLeast(0); t = (box[1].toLong() * h / mh - m).toInt().coerceAtLeast(0)
                        r = (box[2].toLong() * w / mw + m).toInt().coerceAtMost(w); b = (box[3].toLong() * h / mh + m).toInt().coerceAtMost(h)
                    }
                    val ow = r - l; val oh = b - t
                    val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                    try {
                        val row = IntArray(ow)
                        // Bilinear mask lookup: precomputed per column.
                        val x0 = IntArray(ow); val x1 = IntArray(ow); val tx = FloatArray(ow)
                        for (x in 0 until ow) {
                            val fx = ((l + x + 0.5f) * mw / w - 0.5f).coerceIn(0f, (mw - 1).toFloat())
                            x0[x] = fx.toInt(); x1[x] = min(x0[x] + 1, mw - 1); tx[x] = fx - x0[x]
                        }
                        val bg = background.color
                        for (y in 0 until oh) {
                            val fy = ((t + y + 0.5f) * mh / h - 0.5f).coerceIn(0f, (mh - 1).toFloat())
                            val y0 = fy.toInt(); val y1 = min(y0 + 1, mh - 1); val ty = fy - y0
                            bmp.getPixels(row, 0, ow, l, t + y, ow, 1)
                            for (x in 0 until ow) {
                                val a0 = mask[y0 * mw + x0[x]] + (mask[y0 * mw + x1[x]] - mask[y0 * mw + x0[x]]) * tx[x]
                                val a1 = mask[y1 * mw + x0[x]] + (mask[y1 * mw + x1[x]] - mask[y1 * mw + x0[x]]) * tx[x]
                                val a = a0 + (a1 - a0) * ty
                                val c = row[x]
                                row[x] = if (bg == null) {
                                    ((a * 255).roundToInt().coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)
                                } else {
                                    fun mix(sh: Int) = (((c shr sh) and 255) * a + ((bg shr sh) and 255) * (1 - a)).roundToInt().coerceIn(0, 255) shl sh
                                    (0xFF shl 24) or mix(16) or mix(8) or mix(0)
                                }
                            }
                            out.setPixels(row, 0, ow, 0, y, ow, 1)
                            if (y % 256 == 0) { ctx.throttle(); ctx.unitProgress(index, 0.5 + 0.3 * y / oh) }
                        }
                        val format = if (bg == null) ImageOutFormat.PNG else ImageOutFormat.JPEG
                        // A cut-out of a RAW photo on a plain colour: a JPEG like the others, at full quality.
                        val spec = if (item.format.isRaw) RawFallback.spec(format) else EncodeSpec(format, 95)
                        val (file, _) = Outputs.produce(ctx, tool.area, "${item.baseName}_cutout.${format.ext}", format.mime) { pending ->
                            CountingStream(pending.openStream().buffered(1 shl 16)).use { os -> ImagePipeline.encodeBitmap(out, spec, os) }
                            ImagePipeline.verify(ctx.app, pending, ow, oh)
                        }
                        ItemResult(item.name, ItemOutcome.SUCCESS, if (sample > 1) tr("Very large photo: saved at {0}×{1}.", w, h) else null, listOf(file),
                            "${ow}×$oh · ${if (bg == null) tr("transparent PNG") else tr("{0} background", background.label)} · ${Format.bytes(file.size)}")
                    } finally { out.recycle() }
                } finally { bmp.recycle() }
            }
        }
    }
}

// =========================================================================== Auto enhance
enum class EnhanceStrength(val value: Float) {
    SUBTLE(0.6f), NATURAL(1f), STRONG(1.35f);
    val label: String get() = when (this) { SUBTLE -> trc("strength", "Subtle"); NATURAL -> trc("strength", "Natural"); STRONG -> trc("strength", "Strong") }
}

class AutoEnhanceJob(inputs: List<MediaItem>, private val strength: EnhanceStrength) : ExportJob(ToolId.AUTO_ENHANCE, inputs) {
    override val title = trn(inputs.size, "Enhancing {0} photo", "Enhancing {0} photos")

    override suspend fun run(ctx: JobContext) {
        val rawFormat = RawFallback.format(tool, inputs)
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            ImageSource.open(ctx.app, item).use { src ->
                ctx.status(tr("Looking at the photo"), item.name)
                val preview = src.preview(1024)
                val r = try { VisionOps.enhance(ctx.app, preview, strength.value) } finally { preview.recycle() }
                if (r.adjust.isIdentity) throw SkipItemException(tr("This photo already looks balanced; nothing to improve."))
                ctx.unitProgress(index, 0.15)
                val state = EditState(adjust = r.adjust)
                val (format, spec) = photoOutput(tool, item, rawFormat)
                var size = 0 to 0
                val (out, _) = Outputs.produce(ctx, tool.area, "${item.baseName}_enhanced.${format.ext}", format.mime) { pending ->
                    CountingStream(pending.openStream().buffered(1 shl 16)).use { os ->
                        size = EditRenderer.exportFull(ctx, RetouchedSource(src, null, emptyList()), state, spec, true, os) { f -> ctx.unitProgress(index, 0.15 + 0.85 * f) }
                    }
                    ImagePipeline.verify(ctx.app, pending, size.first, size.second)
                }
                ItemResult(item.name, ItemOutcome.SUCCESS, if (item.format.isRaw) RawFallback.note(format, item) else null, listOf(out),
                    tr("{0}: {1}", r.scene.label, r.shownNotes.ifBlank { tr("fine-tuned") }) + " · ${size.first}×${size.second}")
            }
        }
    }
}

// =========================================================================== Face blur
/**
 * What to hide. [tracks]: per file (by [MediaItem.key]), the face tracks of the people the user
 * picked in the face scan; [privates]: the plates, private text and codes picked. With [scanOthers]
 * (tool stacks, where the files are made by an earlier step) files without an entry are scanned when
 * the job runs: faces that look like a person in [keep] stay visible and every other face is hidden,
 * as is everything [privacy] asks for.
 */
class FaceBlurPlan(
    val tracks: Map<String, List<FaceTrack>>,
    val pixelate: Boolean,
    val strength: Float,
    val scanOthers: Boolean = false,
    val keep: List<FloatArray> = emptyList(),
    val hide: List<FloatArray> = emptyList(),
    val privates: Map<String, List<PrivateTrack>> = emptyMap(),
    val privacy: PrivacyOptions = PrivacyOptions(),
)

class FaceBlurJob(inputs: List<MediaItem>, private val plan: FaceBlurPlan) : ExportJob(ToolId.FACE_BLUR, inputs) {
    private val withPrivate = plan.privacy.any || plan.privates.isNotEmpty()
    override val title = when {
        withPrivate && plan.pixelate -> trn(inputs.size, "Pixelating faces and private details in {0} file", "Pixelating faces and private details in {0} files")
        withPrivate -> trn(inputs.size, "Blurring faces and private details in {0} file", "Blurring faces and private details in {0} files")
        plan.pixelate -> trn(inputs.size, "Pixelating faces in {0} file", "Pixelating faces in {0} files")
        else -> trn(inputs.size, "Blurring faces in {0} file", "Blurring faces in {0} files")
    }

    override suspend fun run(ctx: JobContext) {
        val rawFormat = RawFallback.format(tool, inputs)
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            var faces = plan.tracks[item.key]
            var privs = plan.privates[item.key]
            val scanned = plan.scanOthers && (faces == null || (privs == null && plan.privacy.any))
            if (scanned) {
                val (f, p) = findNow(ctx, index, item)
                if (faces == null) faces = f
                if (privs == null) privs = p
            }
            val hideFaces = faces.orEmpty(); val hidePrivate = privs.orEmpty()
            if (hideFaces.isEmpty() && hidePrivate.isEmpty()) throw SkipItemException(when {
                scanned && plan.privacy.any -> tr("Nothing to hide was found in this file.")
                scanned -> tr("No faces to hide were found in this file.")
                withPrivate -> tr("Nothing chosen to hide is in this file, so it was left as it is.")
                else -> tr("None of the chosen people appear in this file, so it was left as it is.")
            })
            if (item.kind == MediaKind.VIDEO) video(ctx, index, item, hideFaces, hidePrivate) else photo(ctx, index, item, hideFaces, hidePrivate, rawFormat)
        }
        Outputs.cleanTemp(ctx)
    }

    /** Scans a file now: the face tracks to hide (everyone except people to keep visible) and what [FaceBlurPlan.privacy] asks for. */
    private fun findNow(ctx: JobContext, index: Int, item: MediaItem): Pair<List<FaceTrack>, List<PrivateTrack>> {
        ctx.status(if (plan.privacy.any) tr("Finding faces and private details") else tr("Finding faces"), item.name)
        val res = try {
            FaceScanner.scan(ctx.app, listOf(item), { ctx.cancelled }, plan.privacy) { f, _ -> ctx.unitProgress(index, f * 0.3) }
        } catch (e: FaceScanner.Cancelled) { throw ExportCancelledException() }
        try {
            res.problems.firstOrNull()?.let { throw UserFacingException(tr("Faces couldn't be found: {0}", it.second)) }
            val faces = res.people.filter { p ->
                val f = FaceTrack(-1).apply { p.tracks.forEach { samples.addAll(it.samples) } }.meanFeature() ?: return@filter true
                val k = plan.keep.maxOfOrNull { FaceEngine.cosine(it, f) } ?: -1f
                val h = plan.hide.maxOfOrNull { FaceEngine.cosine(it, f) } ?: -1f
                !(k >= FaceEngine.SAME_PERSON && k > h)
            }.flatMap { it.tracks }
            return faces to res.privates
        } finally {
            res.release()
        }
    }

    private fun summary(faces: Int, privates: Int, video: Boolean): String = listOfNotNull(
        if (faces == 0) null else if (video) {
            if (plan.pixelate) trn(faces, "{0} face track pixelated", "{0} face tracks pixelated") else trn(faces, "{0} face track blurred", "{0} face tracks blurred")
        } else {
            if (plan.pixelate) trn(faces, "{0} face pixelated", "{0} faces pixelated") else trn(faces, "{0} face blurred", "{0} faces blurred")
        },
        if (privates == 0) null else if (plan.pixelate) trn(privates, "{0} private detail pixelated", "{0} private details pixelated")
            else trn(privates, "{0} private detail blurred", "{0} private details blurred"),
    ).joinToString(" · ")

    private fun photo(ctx: JobContext, index: Int, item: MediaItem, tracks: List<FaceTrack>, privates: List<PrivateTrack>, rawFormat: ImageOutFormat): ItemResult {
        val store = PatchStore.newSession(ctx.app)
        try {
            ImageSource.open(ctx.app, item).use { src ->
                val w = src.width; val h = src.height
                val budget = ctx.memoryBudget()
                val patches = ArrayList<RetouchPatch>()
                val regions = FaceTracker.trackRegionsAt(tracks, 0)
                val kind = if (plan.pixelate) PatchKind.PIXELATE else PatchKind.BLUR
                val total = regions.size + privates.size
                for ((k, r) in regions.withIndex()) {
                    val cx = r.cx * w; val cy = r.cy * h; val rx = r.rx * w; val ry = r.ry * h
                    // A capsule that covers the ellipse.
                    val stroke = if (ry >= rx) Stroke(floatArrayOf(cx, cy - (ry - rx), cx, cy + (ry - rx)), rx)
                        else Stroke(floatArrayOf(cx - (rx - ry), cy, cx + (rx - ry), cy), ry)
                    val rs = RetouchedSource(src, store, patches.toList())
                    patches.add(Retouch.obscure(rs, listOf(stroke), kind, plan.strength, store, budget, 2 * min(rx, ry) / 1.45f))
                    ctx.unitProgress(index, 0.4 * (k + 1) / total)
                }
                for ((k, t) in privates.withIndex()) {
                    val q = t.samples.first().quad
                    val quad = Quad(FloatArray(8) { if (it % 2 == 0) q[it] * w else q[it] * h })
                    val c = PrivacyShape.capsule(quad)
                    val rs = RetouchedSource(src, store, patches.toList())
                    patches.add(Retouch.obscure(rs, listOf(Stroke(floatArrayOf(c.x0, c.y0, c.x1, c.y1), c.radius)), kind, plan.strength, store, budget, c.featurePx))
                    ctx.unitProgress(index, 0.4 * (regions.size + k + 1) / total)
                }
                val (format, spec) = photoOutput(tool, item, rawFormat)
                var size = 0 to 0
                val (out, _) = Outputs.produce(ctx, tool.area, "${item.baseName}_blurred.${format.ext}", format.mime) { pending ->
                    CountingStream(pending.openStream().buffered(1 shl 16)).use { os ->
                        size = EditRenderer.exportFull(ctx, RetouchedSource(src, store, patches), EditState(patches = patches), spec, true, os) { f -> ctx.unitProgress(index, 0.4 + 0.6 * f) }
                    }
                    ImagePipeline.verify(ctx.app, pending, size.first, size.second)
                }
                return ItemResult(item.name, ItemOutcome.SUCCESS, listOfNotNull(if (item.format.isRaw) RawFallback.note(format, item) else null, tr("Photo metadata (location, camera, dates) is not copied.")).joinToString(" "), listOf(out),
                    summary(regions.size, privates.size, false) + " · ${size.first}×${size.second} · ${Format.bytes(out.size)}")
            }
        } finally {
            store.deleteAll()
        }
    }

    private fun video(ctx: JobContext, index: Int, item: MediaItem, tracks: List<FaceTrack>, privates: List<PrivateTrack>): ItemResult {
        val info = VideoProbe.probe(ctx.app, item.uri)
        val v = info.video ?: throw SkipItemException(tr("This file has no video track."))
        val (w, h) = VideoTranscoder.outputSize(info.codedWidth, info.codedHeight, 0, info.frameRate)
        val bitrate = keepQualityBitrate(info, item.size, w, h)
        OutputStore.ensureSpace((bitrate.toLong() + 192_000) * info.durationUs / 8_000_000 * 2)
        val tmp = File(Outputs.tempDir(ctx), "faces-$index.mp4")
        try {
            ctx.status(if (privates.isEmpty()) tr("Blurring faces frame by frame") else tr("Blurring faces and private details frame by frame"), item.name)
            val hold = FaceScanner.privacyHoldUs(info.durationUs)
            val aspect = info.displayWidth.coerceAtLeast(1).toFloat() / info.displayHeight.coerceAtLeast(1)
            val effect = VideoTranscoder.FrameEffect(plan.pixelate, plan.strength) { pts ->
                FaceTracker.trackRegionsAt(tracks, pts).map { ObscureRegion(it.cx, it.cy, it.rx, it.ry) } +
                    PrivateTracker.regionsAt(privates, pts, hold, aspect).map { ObscureRegion(it.cx, it.cy, it.rx, it.ry, rect = true) }
            }
            val result = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { pfd ->
                VideoTranscoder(ctx.app, item.uri, info).run(pfd.fileDescriptor, VideoTranscoder.Params(w, h, bitrate, effect = effect),
                    ctx.workload, { ctx.throttle() }) { f -> ctx.unitProgress(index, f * 0.95) }
            }
            val out = Outputs.produce(ctx, ToolId.FACE_BLUR.videoArea!!, "${item.baseName}_blurred.mp4", "video/mp4") { pending ->
                RandomAccessFile(tmp, "r").use { raf -> pending.openStream().buffered(1 shl 20).use { os -> Mp4FastStart.process(raf, os) } }
                Outputs.verifyMedia(ctx.app, pending.uri, true, false)
            }.first
            return ItemResult(item.name, ItemOutcome.SUCCESS, result.notes.joinToString(" ").ifBlank { null }, listOf(out),
                summary(tracks.size, privates.size, true) + " · ${info.displayWidth}×${info.displayHeight} · " + tr("H.264 from {0}", v.codecLabel) + " · ${Format.bytes(out.size)}")
        } finally {
            tmp.delete()
        }
    }
}

/** A bitrate that keeps a re-encoded video close to the original's quality. */
internal fun keepQualityBitrate(info: com.localmediatools.video.VideoInfo, fileSize: Long, w: Int, h: Int, fps: Double = info.frameRate): Int {
    val audioBits = info.audio.sumOf { t -> if (t.format.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) t.format.getInteger(android.media.MediaFormat.KEY_BIT_RATE) else 128_000 }
    val src = if (info.bitrate > 0) (info.bitrate - audioBits).coerceAtLeast(info.bitrate / 2)
        else if (info.durationUs > 0) (fileSize * 8_000_000L / info.durationUs).toInt() else 0
    val byQuality = VideoTranscoder.bitrateFor(92, w, h, fps, 0)
    // H.264 needs a bit more than HEVC/AV1 sources for the same look.
    val target = if (src > 0) min(byQuality.toLong(), (src * 1.25).toLong()).toInt() else byQuality
    return target.coerceIn(1_000_000, 60_000_000)
}
