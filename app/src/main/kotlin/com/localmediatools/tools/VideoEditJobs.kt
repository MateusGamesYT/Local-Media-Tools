package com.localmediatools.tools

import android.os.ParcelFileDescriptor
import com.localmediatools.codec.mp4.Mp4FastStart
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.OutputStore
import com.localmediatools.core.SkipItemException
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.video.ConcatClip
import com.localmediatools.video.Concatenator
import com.localmediatools.video.VideoProbe
import com.localmediatools.video.VideoTranscoder
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private fun openRw(f: File): ParcelFileDescriptor =
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)

private fun <T> withFd(f: File, block: (FileDescriptor) -> T): T = openRw(f).use { block(it.fileDescriptor) }

private fun even(x: Double) = (Math.round(x / 2) * 2).toInt().coerceAtLeast(2)

// =========================================================================== Merge videos
class MergeVideosJob(inputs: List<MediaItem>, private val keepSound: Boolean) : ExportJob(ToolId.MERGE_VIDEOS, inputs) {
    override val title = "Merging ${plural(inputs.size, "video")}"
    override val unitCount: Int get() = 1

    override suspend fun run(ctx: JobContext) {
        val label = "${inputs.size} videos"
        try {
            val r = ctx.runItem(inputs.first().copy(name = label, readError = null)) { merge(ctx, label) }
            if (r != null) ctx.addResult(r)
            ctx.unitDone(0)
        } finally {
            Outputs.cleanTemp(ctx)
        }
    }

    private fun merge(ctx: JobContext, label: String): ItemResult {
        for (i in inputs) i.readError?.let { throw UserFacingException("\"${i.name}\": $it") }
        ctx.status("Reading the clips")
        val infos = inputs.map { VideoProbe.probe(ctx.app, it.uri) }
        infos.forEachIndexed { k, inf -> if (inf.video == null) throw UserFacingException("\"${inputs[k].name}\" has no video track.") }
        val withAudio = keepSound && infos.any { it.hasAudio }
        OutputStore.ensureSpace(inputs.sumOf { it.size.coerceAtLeast(0) } * 2)
        val dir = Outputs.tempDir(ctx)
        val joined = File(dir, "merged.mp4")
        val notes = ArrayList<String>()
        val reason = Concatenator.incompatibility(infos, withAudio)
        var detail: String
        if (reason == null) {
            ctx.status("Joining without re-encoding")
            withFd(joined) { fd ->
                Concatenator(ctx.app).join(fd, inputs.zip(infos).map { ConcatClip(it.first.uri, it.second) }, withAudio, { ctx.throttle() }) { f -> ctx.unitProgress(0, f * 0.9) }
            }
            detail = "joined without re-encoding"
        } else {
            notes.add("The clips couldn't be joined directly ($reason), so they were converted to one size and format first.")
            val first = infos[0]
            val shortSide = infos.maxOf { min(it.displayWidth, it.displayHeight) }.coerceIn(240, 1080)
            val scale = shortSide.toDouble() / min(first.displayWidth, first.displayHeight).coerceAtLeast(1)
            val fpsMax = infos.maxOf { it.frameRate }
            val fps = when { fpsMax > 45 -> 60.0; fpsMax > 27 -> 30.0; fpsMax > 24.5 -> 25.0; else -> 24.0 }
            val (ow, oh) = VideoTranscoder.outputSize(even(first.displayWidth * scale), even(first.displayHeight * scale), 0, fps)
            val bitrate = VideoTranscoder.bitrateFor(88, ow, oh, fps, 0)
            val rates = infos.flatMap { it.audio.take(1) }.mapNotNull { t -> if (t.format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) t.format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) else null }
            val audioRate = if (rates.isNotEmpty() && rates.all { it == 44100 }) 44100 else 48000
            val total = infos.sumOf { it.durationUs.coerceAtLeast(1) }.toDouble()
            var before = 0.0
            val parts = ArrayList<ConcatClip>()
            for ((k, item) in inputs.withIndex()) {
                ctx.status("Converting clip ${k + 1} of ${inputs.size}", item.name)
                val part = File(dir, "part-$k.mp4")
                val info = infos[k]
                val res = withFd(part) { fd ->
                    VideoTranscoder(ctx.app, item.uri, info).run(fd, VideoTranscoder.Params(ow, oh, bitrate,
                        keepAudio = withAudio, rotateToDisplay = true, fit = true, maxFps = fps, headerFps = fps,
                        audioRate = audioRate, audioChannels = 2, silenceIfNoAudio = withAudio), ctx.workload, { ctx.throttle() }) { f ->
                        ctx.unitProgress(0, (before + f * info.durationUs.coerceAtLeast(1)) / total * 0.8)
                    }
                }
                notes.addAll(res.notes.filter { it !in notes })
                before += info.durationUs.coerceAtLeast(1)
                parts.add(ConcatClip(android.net.Uri.fromFile(part), VideoProbe.probe(ctx.app, android.net.Uri.fromFile(part))))
            }
            Concatenator.incompatibility(parts.map { it.info }, withAudio)?.let {
                throw UserFacingException("The converted clips still can't be joined on this device ($it).")
            }
            ctx.status("Joining")
            withFd(joined) { fd -> Concatenator(ctx.app).join(fd, parts, withAudio, { ctx.throttle() }) { f -> ctx.unitProgress(0, 0.8 + f * 0.1) } }
            parts.forEach { File(it.uri.path!!).delete() }
            detail = "H.264 ${ow}×$oh · ${fps.roundToInt()} fps"
        }
        ctx.status("Finishing")
        val out = Outputs.produce(ctx, tool.area, "${inputs.first().baseName}_merged.mp4", "video/mp4") { pending ->
            RandomAccessFile(joined, "r").use { raf -> pending.openStream().buffered(1 shl 20).use { os -> Mp4FastStart.process(raf, os) } }
            Outputs.verifyMedia(ctx.app, pending.uri, true, withAudio)
        }.first
        joined.delete()
        val duration = infos.sumOf { it.durationUs.coerceAtLeast(0) }
        if (!keepSound && infos.any { it.hasAudio }) notes.add("Sound was left out, as chosen.")
        return ItemResult(label, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
            "${inputs.size} clips · ${Format.duration(duration / 1000)} · $detail · ${Format.bytes(out.size)}")
    }
}

// =========================================================================== Speed & timelapse
class VideoSpeedJob(inputs: List<MediaItem>, private val speed: Double, private val keepSound: Boolean) : ExportJob(ToolId.VIDEO_SPEED, inputs) {
    override val title = "Changing the speed of ${plural(inputs.size, "video")} to ${label(speed)}"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = VideoProbe.probe(ctx.app, item.uri)
            val v = info.video ?: throw SkipItemException("This file has no video track.")
            val sound = keepSound && info.hasAudio && speed <= MAX_SOUND_SPEED
            val outFps = info.frameRate * speed
            val notes = ArrayList<String>()
            if (keepSound && info.hasAudio && !sound) notes.add("Sound was left out: above ${label(MAX_SOUND_SPEED)} it can't be followed.")
            if (speed < 1 && outFps < 20) notes.add("Slow motion looks smoothest with high-frame-rate recordings (this one has ${info.frameRate.roundToInt()} fps).")
            val name = "${item.baseName}_${label(speed).replace("×", "x")}"
            if (!sound && outFps <= 120.5) {
                // Only the timestamps change: no re-encoding, no quality loss.
                OutputStore.ensureSpace(item.size)
                val out = remuxToOutput(ctx, item, tool.area, name, listOf(v), 0, Long.MAX_VALUE, info.rotation, speed) { f -> ctx.unitProgress(index, f) }
                ItemResult(item.name, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                    "${label(speed)} · ${Format.duration((info.durationUs / speed / 1000).toLong())} · no re-encoding · ${Format.bytes(out.size)}")
            } else {
                val maxFps = if (speed >= 4) 30.0 else 60.0
                val fps = min(outFps, maxFps)
                val (w, h) = VideoTranscoder.outputSize(info.codedWidth, info.codedHeight, 0, fps)
                val bitrate = keepQualityBitrate(info, item.size, w, h, fps)
                OutputStore.ensureSpace((bitrate.toLong() + 192_000) * (info.durationUs / speed).toLong() / 8_000_000 * 2)
                val tmp = File(Outputs.tempDir(ctx), "speed-$index.mp4")
                try {
                    val res = withFd(tmp) { fd ->
                        VideoTranscoder(ctx.app, item.uri, info).run(fd, VideoTranscoder.Params(w, h, bitrate, keepAudio = sound, speed = speed, maxFps = maxFps),
                            ctx.workload, { ctx.throttle() }) { f -> ctx.unitProgress(index, f * 0.95) }
                    }
                    notes.addAll(res.notes)
                    val out = Outputs.produce(ctx, tool.area, "$name.mp4", "video/mp4") { pending ->
                        RandomAccessFile(tmp, "r").use { raf -> pending.openStream().buffered(1 shl 20).use { os -> Mp4FastStart.process(raf, os) } }
                        Outputs.verifyMedia(ctx.app, pending.uri, true, sound)
                    }.first
                    ItemResult(item.name, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                        "${label(speed)} · ${Format.duration((info.durationUs / speed / 1000).toLong())} · ${fps.roundToInt()} fps${if (sound) " · natural-pitch sound" else ""} · ${Format.bytes(out.size)}")
                } finally {
                    tmp.delete()
                }
            }
        }
        Outputs.cleanTemp(ctx)
    }

    companion object {
        const val MAX_SOUND_SPEED = 4.0

        fun label(s: Double): String = if (s == Math.floor(s)) "${s.toInt()}×" else String.format(Locale.US, "%s×", s.toString().trimEnd('0'))
    }
}
