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
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Photos with transparency stay PNG; everything else is saved as a high-quality JPEG. */
private fun photoFormat(item: MediaItem) = if (item.format == SniffedFormat.PNG || item.format == SniffedFormat.WEBP) ImageOutFormat.PNG else ImageOutFormat.JPEG

// =========================================================================== Background remover
enum class CutoutBackground(val label: String, val color: Int?) {
    TRANSPARENT("Transparent", null),
    WHITE("White", 0xFFFFFFFF.toInt()),
    BLACK("Black", 0xFF000000.toInt()),
    GREY("Light grey", 0xFFEDEFF2.toInt()),
}

class RemoveBackgroundJob(inputs: List<MediaItem>, private val background: CutoutBackground, private val crop: Boolean) : ExportJob(ToolId.BACKGROUND_REMOVER, inputs) {
    override val title = "Removing the background from ${plural(inputs.size, "photo")}"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            ImageSource.open(ctx.app, item).use { src ->
                val budget = ctx.memoryBudget()
                // Two full copies (picture and result) must fit; very large photos are reduced.
                var sample = 1
                while (sample < 16 && (src.bytesFor(sample) * 3 > budget || max(src.width, src.height) / sample > 8000)) sample *= 2
                ctx.status("Finding the subject", item.name)
                val bmp = src.decode(sample, budget)
                try {
                    val (mask, mw, mh) = VisionOps.subjectMask(ctx.app, bmp)
                    ctx.unitProgress(index, 0.5)
                    val box = Matting.bounds(mask, mw, mh, 0.25f)
                    val covered = mask.count { it > 0.5f }.toDouble() / mask.size
                    if (box == null || covered < 0.003) throw SkipItemException("No clear subject was found in this photo, so nothing was saved.")
                    if (covered > 0.97) throw SkipItemException("The whole photo looks like the subject, so there was no background to remove.")
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
                        val (file, _) = Outputs.produce(ctx, tool.area, "${item.baseName}_cutout.${format.ext}", format.mime) { pending ->
                            CountingStream(pending.openStream().buffered(1 shl 16)).use { os -> ImagePipeline.encodeBitmap(out, EncodeSpec(format, 95), os) }
                            ImagePipeline.verify(ctx.app, pending, ow, oh)
                        }
                        ItemResult(item.name, ItemOutcome.SUCCESS, if (sample > 1) "Very large photo: saved at ${w}×$h." else null, listOf(file),
                            "${ow}×$oh · ${if (bg == null) "transparent PNG" else "${background.label.lowercase()} background"} · ${Format.bytes(file.size)}")
                    } finally { out.recycle() }
                } finally { bmp.recycle() }
            }
        }
    }
}

// =========================================================================== Auto enhance
enum class EnhanceStrength(val label: String, val value: Float) { SUBTLE("Subtle", 0.6f), NATURAL("Natural", 1f), STRONG("Strong", 1.35f) }

class AutoEnhanceJob(inputs: List<MediaItem>, private val strength: EnhanceStrength) : ExportJob(ToolId.AUTO_ENHANCE, inputs) {
    override val title = "Enhancing ${plural(inputs.size, "photo")}"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            ImageSource.open(ctx.app, item).use { src ->
                ctx.status("Looking at the photo", item.name)
                val preview = src.preview(1024)
                val r = try { VisionOps.enhance(ctx.app, preview, strength.value) } finally { preview.recycle() }
                if (r.adjust.isIdentity) throw SkipItemException("This photo already looks balanced; nothing to improve.")
                ctx.unitProgress(index, 0.15)
                val state = EditState(adjust = r.adjust)
                val format = photoFormat(item)
                var size = 0 to 0
                val (out, _) = Outputs.produce(ctx, tool.area, "${item.baseName}_enhanced.${format.ext}", format.mime) { pending ->
                    CountingStream(pending.openStream().buffered(1 shl 16)).use { os ->
                        size = EditRenderer.exportFull(ctx, RetouchedSource(src, null, emptyList()), state, EncodeSpec(format, 95), true, os) { f -> ctx.unitProgress(index, 0.15 + 0.85 * f) }
                    }
                    ImagePipeline.verify(ctx.app, pending, size.first, size.second)
                }
                ItemResult(item.name, ItemOutcome.SUCCESS, null, listOf(out),
                    "${r.scene.label}: ${r.notes.joinToString(", ").ifBlank { "fine-tuned" }} · ${size.first}×${size.second}")
            }
        }
    }
}

// =========================================================================== Face blur
/**
 * What to hide. [tracks]: per file (by [MediaItem.key]), the face tracks of the people the user
 * picked in the face scan. With [scanOthers] (tool stacks, where the files are made by an earlier
 * step) files without an entry are scanned when the job runs: faces that look like a person in
 * [keep] stay visible and every other face is hidden.
 */
class FaceBlurPlan(
    val tracks: Map<String, List<FaceTrack>>,
    val pixelate: Boolean,
    val strength: Float,
    val scanOthers: Boolean = false,
    val keep: List<FloatArray> = emptyList(),
    val hide: List<FloatArray> = emptyList(),
)

class FaceBlurJob(inputs: List<MediaItem>, private val plan: FaceBlurPlan) : ExportJob(ToolId.FACE_BLUR, inputs) {
    override val title = "${if (plan.pixelate) "Pixelating" else "Blurring"} faces in ${plural(inputs.size, "file")}"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val tracks = plan.tracks[item.key] ?: (if (plan.scanOthers) findNow(ctx, index, item) else null).orEmpty()
            if (tracks.isEmpty()) throw SkipItemException(if (plan.scanOthers && plan.tracks[item.key] == null) "No faces to hide were found in this file." else "None of the chosen people appear in this file, so it was left as it is.")
            if (item.kind == MediaKind.VIDEO) video(ctx, index, item, tracks) else photo(ctx, index, item, tracks)
        }
        Outputs.cleanTemp(ctx)
    }

    /** Scans a file now and returns the tracks to hide (everyone except people to keep visible). */
    private fun findNow(ctx: JobContext, index: Int, item: MediaItem): List<FaceTrack> {
        ctx.status("Finding faces", item.name)
        val res = try {
            FaceScanner.scan(ctx.app, listOf(item), { ctx.cancelled }) { f, _ -> ctx.unitProgress(index, f * 0.3) }
        } catch (e: FaceScanner.Cancelled) { throw ExportCancelledException() }
        try {
            res.problems.firstOrNull()?.let { throw UserFacingException("Faces couldn't be found: ${it.second}") }
            return res.people.filter { p ->
                val f = FaceTrack(-1).apply { p.tracks.forEach { samples.addAll(it.samples) } }.meanFeature() ?: return@filter true
                val k = plan.keep.maxOfOrNull { FaceEngine.cosine(it, f) } ?: -1f
                val h = plan.hide.maxOfOrNull { FaceEngine.cosine(it, f) } ?: -1f
                !(k >= FaceEngine.SAME_PERSON && k > h)
            }.flatMap { it.tracks }
        } finally {
            res.release()
        }
    }

    private fun photo(ctx: JobContext, index: Int, item: MediaItem, tracks: List<FaceTrack>): ItemResult {
        val store = PatchStore.newSession(ctx.app)
        try {
            ImageSource.open(ctx.app, item).use { src ->
                val w = src.width; val h = src.height
                val budget = ctx.memoryBudget()
                val patches = ArrayList<RetouchPatch>()
                val regions = FaceTracker.trackRegionsAt(tracks, 0)
                for ((k, r) in regions.withIndex()) {
                    val cx = r.cx * w; val cy = r.cy * h; val rx = r.rx * w; val ry = r.ry * h
                    // A capsule that covers the ellipse.
                    val stroke = if (ry >= rx) Stroke(floatArrayOf(cx, cy - (ry - rx), cx, cy + (ry - rx)), rx)
                        else Stroke(floatArrayOf(cx - (rx - ry), cy, cx + (rx - ry), cy), ry)
                    val rs = RetouchedSource(src, store, patches.toList())
                    patches.add(Retouch.obscure(rs, listOf(stroke), if (plan.pixelate) PatchKind.PIXELATE else PatchKind.BLUR, plan.strength, store, budget, 2 * min(rx, ry) / 1.45f))
                    ctx.unitProgress(index, 0.4 * (k + 1) / regions.size)
                }
                val format = photoFormat(item)
                var size = 0 to 0
                val (out, _) = Outputs.produce(ctx, tool.area, "${item.baseName}_blurred.${format.ext}", format.mime) { pending ->
                    CountingStream(pending.openStream().buffered(1 shl 16)).use { os ->
                        size = EditRenderer.exportFull(ctx, RetouchedSource(src, store, patches), EditState(patches = patches), EncodeSpec(format, 95), true, os) { f -> ctx.unitProgress(index, 0.4 + 0.6 * f) }
                    }
                    ImagePipeline.verify(ctx.app, pending, size.first, size.second)
                }
                return ItemResult(item.name, ItemOutcome.SUCCESS, "Photo metadata (location, camera, dates) is not copied.", listOf(out),
                    "${plural(regions.size, "face")} ${if (plan.pixelate) "pixelated" else "blurred"} · ${size.first}×${size.second} · ${Format.bytes(out.size)}")
            }
        } finally {
            store.deleteAll()
        }
    }

    private fun video(ctx: JobContext, index: Int, item: MediaItem, tracks: List<FaceTrack>): ItemResult {
        val info = VideoProbe.probe(ctx.app, item.uri)
        val v = info.video ?: throw SkipItemException("This file has no video track.")
        val (w, h) = VideoTranscoder.outputSize(info.codedWidth, info.codedHeight, 0, info.frameRate)
        val bitrate = keepQualityBitrate(info, item.size, w, h)
        OutputStore.ensureSpace((bitrate.toLong() + 192_000) * info.durationUs / 8_000_000 * 2)
        val tmp = File(Outputs.tempDir(ctx), "faces-$index.mp4")
        try {
            ctx.status("Blurring faces frame by frame", item.name)
            val effect = VideoTranscoder.FrameEffect(plan.pixelate, plan.strength) { pts ->
                FaceTracker.trackRegionsAt(tracks, pts).map { ObscureRegion(it.cx, it.cy, it.rx, it.ry) }
            }
            val result = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { pfd ->
                VideoTranscoder(ctx.app, item.uri, info).run(pfd.fileDescriptor, VideoTranscoder.Params(w, h, bitrate, effect = effect),
                    ctx.workload, { ctx.throttle() }) { f -> ctx.unitProgress(index, f * 0.95) }
            }
            val out = Outputs.produce(ctx, ToolId.FACE_BLUR.videoArea!!, "${item.baseName}_blurred.mp4", "video/mp4") { pending ->
                RandomAccessFile(tmp, "r").use { raf -> pending.openStream().buffered(1 shl 20).use { os -> Mp4FastStart.process(raf, os) } }
                Outputs.verifyMedia(ctx.app, pending.uri, true, false)
            }.first
            val people = tracks.size
            return ItemResult(item.name, ItemOutcome.SUCCESS, result.notes.joinToString(" ").ifBlank { null }, listOf(out),
                "${plural(people, "face track")} ${if (plan.pixelate) "pixelated" else "blurred"} · ${info.displayWidth}×${info.displayHeight} · H.264 from ${v.codecLabel} · ${Format.bytes(out.size)}")
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
