package com.localmediatools.tools

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.localmediatools.codec.gif.ColorHistogram
import com.localmediatools.codec.gif.Dithering
import com.localmediatools.codec.gif.GifAnimationWriter
import com.localmediatools.codec.gif.GifDecoder
import com.localmediatools.codec.gif.PaletteMapper
import com.localmediatools.codec.image.Resample
import com.localmediatools.codec.png.IntIntMap
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.SkipItemException
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.image.CountingStream
import com.localmediatools.video.FrameGrabber
import com.localmediatools.video.VideoProbe
import kotlin.math.roundToLong

/** Turns frame timestamps (µs) into GIF delays (1/100 s) without drift. */
private class DelayClock {
    fun cs(us: Long) = (us + 5_000) / 10_000
    fun delay(fromUs: Long, toUs: Long) = (cs(toUs) - cs(fromUs)).coerceAtLeast(2).toInt()
}

// =========================================================================== 10. Video → GIF
class VideoToGifJob(
    inputs: List<MediaItem>,
    private val fps: Double?,          // null = source frame rate (capped at 50)
    private val width: Int?,           // null = source width
    private val startSec: Double,
    private val maxSeconds: Double,    // 0 = until the end
    private val dithering: Dithering,
) : ExportJob(ToolId.VIDEO_TO_GIF, inputs) {
    override val title = "Converting ${plural(inputs.size, "video")} to GIF"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = VideoProbe.probe(ctx.app, item.uri)
            info.video ?: throw UserFacingException("This file has no video track.")
            val dw = info.displayWidth; val dh = info.displayHeight
            if (dw <= 0 || dh <= 0) throw UserFacingException("The video's dimensions could not be read.")
            val outW = (width ?: dw).coerceIn(16, dw)
            val outH = Math.round(dh.toDouble() * outW / dw).toInt().coerceAtLeast(1)
            val notes = ArrayList<String>()
            val targetFps = (fps ?: info.frameRate).coerceIn(1.0, 50.0)
            if (fps == null && info.frameRate > 50.5) notes.add("Frame rate limited to 50 fps, the fastest rate GIF viewers play.")
            val startUs = (startSec * 1_000_000).toLong().coerceIn(0, maxOf(0, info.durationUs - 1))
            val endUs = if (maxSeconds > 0) minOf(info.durationUs, startUs + (maxSeconds * 1_000_000).toLong()) else info.durationUs
            if (endUs <= startUs) throw UserFacingException("The chosen start time is beyond the end of the video.")

            // 1. Adaptive global palette from frames sampled across the clip.
            ctx.status("Analysing colours", item.name)
            val hist = ColorHistogram()
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx.app, item.uri)
                val samples = 16
                for (k in 0 until samples) {
                    ctx.throttle()
                    val t = startUs + (endUs - startUs) * k / samples
                    val bmp: Bitmap = mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, outW, outH) ?: continue
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    bmp.recycle()
                    hist.add(px, step = 2)
                }
            } catch (_: Exception) {
            } finally { mmr.release() }
            val haveHist = hist.total > 0
            var globalPalette: IntArray? = if (haveHist) hist.palette(255) else null
            val baselineMse = DoubleArray(1) { -1.0 }

            // 2. Decode, map and write.
            var bytes = 0L
            var frames = 0
            var localFrames = 0
            val stepUs = 1_000_000.0 / targetFps
            val (out, _) = Outputs.produce(ctx, ToolId.VIDEO_TO_GIF.area, "${item.baseName}.gif", "image/gif") { pending ->
                val counting = CountingStream(pending.openStream().buffered(1 shl 16))
                counting.use { os ->
                    var writer: GifAnimationWriter? = null
                    var pending1: IntArray? = null
                    var pendingSlot = 0L
                    val clock = DelayClock()
                    var nextSlot = startUs.toDouble()
                    var lastEmitPts = Long.MIN_VALUE
                    var localPalette: IntArray? = null
                    var localMapper: PaletteMapper? = null
                    var globalMapper = globalPalette?.let { PaletteMapper(it) }
                    val mapped = IntArray(outW * outH)
                    fun emit(px: IntArray, slotUs: Long) {
                        val w = writer ?: GifAnimationWriter(os, outW, outH, globalPalette, 0).also { writer = it }
                        val prev = pending1
                        if (prev != null) w.addFrame(prev, clock.delay(pendingSlot, slotUs))
                        pending1 = px.copyOf()
                        pendingSlot = slotUs
                        frames++
                    }
                    FrameGrabber(ctx.app, item.uri, info, outW, outH).run(startUs, endUs, ctx.workload.codecOperatingRate,
                        want = { pts ->
                            if (fps == null) pts - lastEmitPts >= 19_500 || lastEmitPts == Long.MIN_VALUE
                            else pts + stepUs / 2 >= nextSlot
                        },
                        sink = { argb, pts ->
                            ctx.checkCancelled()
                            if (globalMapper == null) {
                                val h = ColorHistogram(); h.add(argb, step = 1)
                                globalPalette = h.palette(255); globalMapper = PaletteMapper(globalPalette!!)
                            }
                            var mse = globalMapper!!.map(argb, mapped, outW, outH, dithering)
                            if (baselineMse[0] < 0) baselineMse[0] = mse
                            val limit = maxOf(90.0, baselineMse[0] * 3)
                            if (mse > limit) {
                                // Scene with colours the global palette can't represent: adaptive local palette.
                                val lm = localMapper
                                var ok = false
                                if (lm != null) {
                                    val m2 = lm.map(argb, mapped, outW, outH, dithering)
                                    if (m2 <= limit) { ok = true; mse = m2 }
                                }
                                if (!ok) {
                                    val h = ColorHistogram(); h.add(argb, step = 1)
                                    localPalette = h.palette(255)
                                    localMapper = PaletteMapper(localPalette!!)
                                    mse = localMapper!!.map(argb, mapped, outW, outH, dithering)
                                }
                                localFrames++
                            }
                            val slot = if (fps == null) pts else Math.round(nextSlot).coerceAtLeast(startUs)
                            emit(mapped, slot)
                            lastEmitPts = pts
                            if (fps != null) while (nextSlot <= pts + stepUs / 2) nextSlot += stepUs
                            ctx.unitProgress(index, ((pts - startUs).toDouble() / (endUs - startUs)).coerceIn(0.0, 0.99))
                            true
                        },
                        throttle = { ctx.throttle() })
                    val w = writer ?: throw UserFacingException("No frames could be decoded from the selected part of the video.")
                    val lastDelay = if (fps == null) clock.delay(pendingSlot, minOf(endUs, pendingSlot + (1_000_000 / info.frameRate).roundToLong()))
                    else clock.delay(pendingSlot, (pendingSlot + stepUs).roundToLong())
                    w.addFrame(pending1!!, lastDelay)
                    w.finish()
                }
                bytes = counting.count
                GifVerify.check(ctx, pending.uri, outW, outH)
            }
            if (localFrames > 0) notes.add("$localFrames frame(s) used their own colour palette for scenes with different colours.")
            ItemResult(item.name, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                "${outW}×$outH · ${"%.1f".format(targetFps)} fps · $frames frames · ${Format.duration((endUs - startUs) / 1000)} · ${Format.bytes(bytes)}")
        }
    }
}

/** Decodes a written GIF's header and first frame to make sure it is valid before publishing. */
object GifVerify {
    fun check(ctx: JobContext, uri: android.net.Uri, w: Int, h: Int) {
        MediaProbe.openInput(ctx.app.contentResolver, uri).use { s ->
            val d = GifDecoder(s)
            if (d.width != w || d.height != h) throw UserFacingException("The written GIF failed verification, so it was discarded.")
            d.nextFrame() ?: throw UserFacingException("The written GIF has no frames, so it was discarded.")
        }
    }
}

/** Shared timeline resampling for GIF → GIF tools. */
private class GifTimeline(val targetFps: Double?) {
    // Source frames arrive with their display start time; output keeps a frame per slot.
    private var nextSlotUs = 0.0
    fun keep(startUs: Long, durUs: Long): Boolean {
        val f = targetFps ?: return true
        val step = 1_000_000.0 / f
        // Keep the frame if a slot falls inside its display interval.
        if (nextSlotUs < startUs + durUs) {
            while (nextSlotUs < startUs + durUs) nextSlotUs += step
            return true
        }
        return false
    }
}

// =========================================================================== 11. GIF compressor
class CompressGifJob(
    inputs: List<MediaItem>,
    private val width: Int,      // 0 = keep
    private val fps: Double,     // 0 = keep
    private val colors: Int,
    private val dithering: Dithering,
) : ExportJob(ToolId.COMPRESS_GIF, inputs) {
    override val title = "Compressing ${plural(inputs.size, "GIF")} ($colors colours)"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = true) { index, item ->
            val maxPixels = (ctx.memoryBudget(ctx.workload.parallelism) / 4 / 8).coerceAtLeast(1L shl 20)
            // Pass 1: dimensions, transparency, histogram, frame count.
            ctx.status("Analysing", item.name)
            var sw = 0; var sh = 0; var frames = 0; var hasTransparency = false
            var srcFps = 0.0; var totalCs = 0L
            val hist = ColorHistogram()
            var loop: Int? = 0
            MediaProbe.openInput(ctx.app.contentResolver, item.uri).use { s ->
                val d = GifDecoder(s, maxPixels)
                sw = d.width; sh = d.height
                while (true) {
                    ctx.throttle()
                    val f = d.nextFrame() ?: break
                    frames++
                    totalCs += f.effectiveDelayCs
                    if (!hasTransparency) for (c in f.canvas) if ((c ushr 24) == 0) { hasTransparency = true; break }
                    hist.add(f.canvas, step = maxOf(1, f.canvas.size / 60_000))
                }
                loop = d.loopCount
            }
            if (frames == 0) throw UserFacingException("The GIF contains no frames.")
            if (totalCs > 0) srcFps = frames * 100.0 / totalCs
            val ow = if (width in 1 until sw) width else sw
            val oh = if (ow == sw) sh else Math.round(sh.toDouble() * ow / sw).toInt().coerceAtLeast(1)
            val palette = hist.palette(minOf(colors, 255)) // one slot stays free for transparency
            val mapper = PaletteMapper(palette)
            val timeline = GifTimeline(if (fps > 0 && fps < srcFps - 0.01) fps else null)
            var kept = 0
            var bytes = 0L
            val (out, _) = Outputs.produce(ctx, ToolId.COMPRESS_GIF.area, "${item.baseName}_compressed.gif", "image/gif") { pending ->
                val counting = CountingStream(pending.openStream().buffered(1 shl 16))
                counting.use { os ->
                    val writer = GifAnimationWriter(os, ow, oh, palette, loop)
                    var pendingFrame: IntArray? = null
                    var pendingStart = 0L
                    var t = 0L
                    val clock = DelayClock()
                    val mapped = IntArray(ow * oh)
                    MediaProbe.openInput(ctx.app.contentResolver, item.uri).use { s ->
                        val d = GifDecoder(s, maxPixels)
                        var i = 0
                        while (true) {
                            ctx.throttle()
                            val f = d.nextFrame() ?: break
                            val dur = f.effectiveDelayCs * 10_000L
                            if (timeline.keep(t, dur)) {
                                val scaled = if (ow == sw) f.canvas.copyOf() else Resample.area(f.canvas, sw, sh, ow, oh)
                                Resample.binarizeAlpha(scaled)
                                mapper.map(scaled, mapped, ow, oh, dithering)
                                pendingFrame?.let { writer.addFrame(it, clock.delay(pendingStart, t)) }
                                pendingFrame = mapped.copyOf(); pendingStart = t
                                kept++
                            }
                            t += dur
                            i++
                            ctx.unitProgress(index, i.toDouble() / frames)
                        }
                    }
                    writer.addFrame(pendingFrame!!, clock.delay(pendingStart, t))
                    writer.finish()
                }
                bytes = counting.count
                if (item.size > 0 && bytes >= item.size) {
                    throw SkipItemException("The result would not be smaller (${Format.bytes(bytes)} vs ${Format.bytes(item.size)}), so nothing was saved. Try fewer colours, a smaller width or a lower frame rate.")
                }
                GifVerify.check(ctx, pending.uri, ow, oh)
            }
            ItemResult(item.name, ItemOutcome.SUCCESS, null, listOf(out),
                "${Outputs.sizeChange(item.size, out.size)} · ${ow}×$oh · ${palette.size} colours · $kept of $frames frames")
        }
    }
}

// =========================================================================== 12. GIF optimizer (pixel-preserving)
class OptimizeGifJob(
    inputs: List<MediaItem>,
    private val width: Int,      // 0 = keep (only then is the result pixel-identical)
    private val fps: Double,     // 0 = keep original timing
    private val keepOnlyIfSmaller: Boolean,
) : ExportJob(ToolId.OPTIMIZE_GIF, inputs) {
    override val title = "Optimizing ${plural(inputs.size, "GIF")} (pixel-preserving)"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = true) { index, item ->
            val maxPixels = (ctx.memoryBudget(ctx.workload.parallelism) / 4 / 8).coerceAtLeast(1L shl 20)
            ctx.status("Analysing", item.name)
            var sw = 0; var sh = 0; var frames = 0; var totalCs = 0L
            var loop: Int? = 0
            val union = IntIntMap(512)
            var unionOverflow = false
            MediaProbe.openInput(ctx.app.contentResolver, item.uri).use { s ->
                val d = GifDecoder(s, maxPixels)
                sw = d.width; sh = d.height
                while (true) {
                    ctx.throttle()
                    val f = d.nextFrame() ?: break
                    frames++
                    totalCs += f.effectiveDelayCs
                    if (!unionOverflow) for (c in f.canvas) {
                        if ((c ushr 24) == 0) continue
                        if (union.get(c) < 0) { if (union.size >= 255) { unionOverflow = true; break }; union.put(c, union.size) }
                    }
                }
                loop = d.loopCount
            }
            if (frames == 0) throw UserFacingException("The GIF contains no frames.")
            val srcFps = if (totalCs > 0) frames * 100.0 / totalCs else 10.0
            val ow = if (width in 1 until sw) width else sw
            val oh = if (ow == sw) sh else Math.round(sh.toDouble() * ow / sw).toInt().coerceAtLeast(1)
            val global = if (!unionOverflow) union.keys().toIntArray() else null
            val retime = fps > 0 && fps < srcFps - 0.01
            val timeline = GifTimeline(if (retime) fps else null)
            var kept = 0
            val (out, _) = Outputs.produce(ctx, ToolId.OPTIMIZE_GIF.area, "${item.baseName}_optimized.gif", "image/gif") { pending ->
                val counting = CountingStream(pending.openStream().buffered(1 shl 16))
                counting.use { os ->
                    val writer = GifAnimationWriter(os, ow, oh, global, loop)
                    var pendingFrame: IntArray? = null
                    var pendingDelay = 0
                    var pendingStart = 0L
                    var t = 0L
                    val clock = DelayClock()
                    MediaProbe.openInput(ctx.app.contentResolver, item.uri).use { s ->
                        val d = GifDecoder(s, maxPixels)
                        var i = 0
                        while (true) {
                            ctx.throttle()
                            val f = d.nextFrame() ?: break
                            val dur = f.effectiveDelayCs * 10_000L
                            if (timeline.keep(t, dur)) {
                                val px = if (ow == sw) f.canvas.copyOf() else Resample.nearest(f.canvas, sw, sh, ow, oh)
                                pendingFrame?.let { writer.addFrame(it, if (retime) clock.delay(pendingStart, t) else pendingDelay) }
                                pendingFrame = px; pendingStart = t; pendingDelay = f.rawDelayCs
                                kept++
                            } else if (!retime) {
                                pendingDelay += f.rawDelayCs
                            }
                            t += dur
                            i++
                            ctx.unitProgress(index, i.toDouble() / frames)
                        }
                    }
                    writer.addFrame(pendingFrame!!, if (retime) clock.delay(pendingStart, t) else pendingDelay)
                    writer.finish()
                }
                if (keepOnlyIfSmaller && item.size > 0 && counting.count >= item.size) {
                    throw SkipItemException("This GIF is already efficiently encoded (optimized: ${Format.bytes(counting.count)} vs ${Format.bytes(item.size)}), so nothing was saved.")
                }
                GifVerify.check(ctx, pending.uri, ow, oh)
            }
            val exact = if (ow == sw) "pixel-identical frames" else "resized with nearest-neighbour sampling (no new colours)"
            ItemResult(item.name, ItemOutcome.SUCCESS, null, listOf(out),
                "${Outputs.sizeChange(item.size, out.size)} · $exact · $kept of $frames frames")
        }
    }
}
