package com.localmediatools.tools

import android.os.ParcelFileDescriptor
import com.localmediatools.codec.mp4.Mp4FastStart
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.OutputArea
import com.localmediatools.core.OutputFile
import com.localmediatools.core.OutputStore
import com.localmediatools.core.SkipItemException
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.video.Container
import com.localmediatools.video.Remuxer
import com.localmediatools.video.TrackInfo
import com.localmediatools.video.VideoInfo
import com.localmediatools.video.VideoProbe
import com.localmediatools.video.VideoTranscoder
import java.io.File
import java.io.RandomAccessFile

/** Remux helper: tries suitable containers in order until the device's muxer accepts the tracks. */
internal fun remuxToOutput(
    ctx: JobContext, item: MediaItem, area: OutputArea, baseName: String, tracks: List<TrackInfo>,
    startUs: Long, endUs: Long, rotation: Int, speed: Double = 1.0, progress: (Double) -> Unit,
): OutputFile {
    val candidates = Container.candidates(tracks.map { it.mime })
    var last: Exception? = null
    for (c in candidates) {
        try {
            val (out, _) = Outputs.produce(ctx, area, "$baseName.${c.ext}", c.mime) { pending ->
                pending.openFd("rw").use { pfd ->
                    Remuxer(ctx.app, item.uri, speed).copy(pfd.fileDescriptor, c, tracks, startUs, endUs, rotation, { ctx.throttle() }, progress)
                }
                Outputs.verifyMedia(ctx.app, pending.uri, tracks.any { it.isVideo }, tracks.any { it.isAudio })
            }
            return out
        } catch (e: UserFacingException) {
            last = e
            if (e.message?.contains("can't be stored") != true) throw e
        }
    }
    throw last ?: UserFacingException("No suitable container for these tracks.")
}

internal fun avTracks(info: VideoInfo): List<TrackInfo> {
    val v = info.video ?: throw UserFacingException("This file has no video track.")
    return listOf(v) + info.audio
}

// =========================================================================== 1. Split
class SplitVideoJob(inputs: List<MediaItem>, private val segmentSeconds: Double) : ExportJob(ToolId.SPLIT_VIDEO, inputs) {
    override val title = "Splitting ${plural(inputs.size, "video")} into ${Format.seconds(segmentSeconds)} parts"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = VideoProbe.probe(ctx.app, item.uri)
            val v = info.video ?: throw UserFacingException("This file has no video track.")
            val segUs = (segmentSeconds * 1_000_000).toLong()
            if (info.durationUs <= segUs + 250_000) {
                throw SkipItemException("${Format.duration(info.durationUs / 1000)} long — already within one ${Format.seconds(segmentSeconds)} part, nothing to split.")
            }
            ctx.status("Finding keyframes", item.name)
            val syncs = Remuxer(ctx.app, item.uri).syncTimes(v.index) { ctx.throttle() }
            val cuts = computeCuts(syncs, segUs, info.durationUs)
            if (cuts.size < 2) throw SkipItemException("The video has no keyframes after the start, so it can't be split without re-encoding.")
            OutputStore.ensureSpace(item.size)
            val tracks = avTracks(info)
            val outputs = ArrayList<OutputFile>()
            var longest = 0L
            val digits = cuts.size.toString().length.coerceAtLeast(2)
            for (k in cuts.indices) {
                val start = cuts[k]
                val end = if (k + 1 < cuts.size) cuts[k + 1] else Long.MAX_VALUE
                longest = maxOf(longest, (if (end == Long.MAX_VALUE) info.durationUs else end) - start)
                ctx.status("Part ${k + 1} of ${cuts.size}", item.name)
                outputs.add(remuxToOutput(ctx, item, ToolId.SPLIT_VIDEO.area, "${item.baseName}_part${(k + 1).toString().padStart(digits, '0')}",
                    tracks, start, end, info.rotation) { f -> ctx.unitProgress(index, (k + f) / cuts.size) })
            }
            val over = longest > segUs + 500_000
            ItemResult(item.name, ItemOutcome.SUCCESS,
                if (over) "Some parts are longer than ${Format.seconds(segmentSeconds)} because the video has few keyframes (longest ${Format.duration(longest / 1000)})." else null,
                outputs, "${outputs.size} parts · cuts at keyframes · no re-encoding")
        }
        Outputs.cleanTemp(ctx)
    }

    companion object {
        /**
         * Cut points at keyframes so that no part exceeds the target length when keyframes allow it
         * (each cut is the last keyframe within the target); tiny tails are merged.
         */
        fun computeCuts(syncs: LongArray, segUs: Long, durationUs: Long): LongArray {
            if (syncs.isEmpty()) return LongArray(0)
            val cuts = arrayListOf(syncs[0])
            val minLen = minOf(500_000L, segUs / 4)
            // A final part shorter than this is merged into the previous one.
            val minTail = minOf(1_000_000L, segUs / 10)
            while (true) {
                val last = cuts.last()
                val target = last + segUs
                if (target >= durationUs - minTail) break
                var cand = -1L
                for (s in syncs) if (s > last + minLen && s <= target) cand = s
                val next = if (cand > 0) cand else syncs.firstOrNull { it > target } ?: break
                if (durationUs - next < minTail) break
                cuts.add(next)
            }
            return cuts.toLongArray()
        }
    }
}

// =========================================================================== 2. Optimize
class OptimizeVideoJob(inputs: List<MediaItem>) : ExportJob(ToolId.OPTIMIZE_VIDEO, inputs) {
    override val title = "Optimizing ${plural(inputs.size, "video")} (lossless)"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = VideoProbe.probe(ctx.app, item.uri)
            val tracks = avTracks(info)
            val dropped = info.tracks.size - tracks.size
            val container = Container.candidates(tracks.map { it.mime }).first()
            OutputStore.ensureSpace(item.size * 2)
            val notes = ArrayList<String>()
            if (dropped > 0) notes.add("$dropped non-audio/video track(s) (subtitles or metadata) were not copied.")
            val out: OutputFile
            if (container == Container.MP4) {
                val tmp = File(Outputs.tempDir(ctx), "opt-$index.mp4")
                try {
                    ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { pfd ->
                        Remuxer(ctx.app, item.uri).copy(pfd.fileDescriptor, Container.MP4, tracks, 0, Long.MAX_VALUE, info.rotation, { ctx.throttle() }) { f -> ctx.unitProgress(index, f * 0.7) }
                    }
                    ctx.status("Moving the index to the front", item.name)
                    out = Outputs.produce(ctx, ToolId.OPTIMIZE_VIDEO.area, "${item.baseName}_optimized.mp4", "video/mp4") { pending ->
                        RandomAccessFile(tmp, "r").use { raf -> pending.openStream().buffered(1 shl 20).use { os -> Mp4FastStart.process(raf, os) } }
                        Outputs.verifyMedia(ctx.app, pending.uri, true, info.hasAudio)
                    }.first
                } finally {
                    tmp.delete()
                }
                notes.add("Fast start: the video index is at the beginning, so it starts playing and uploading sooner.")
            } else {
                out = remuxToOutput(ctx, item, ToolId.OPTIMIZE_VIDEO.area, "${item.baseName}_optimized", tracks, 0, Long.MAX_VALUE, info.rotation) { f -> ctx.unitProgress(index, f) }
            }
            ItemResult(item.name, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                "${Outputs.sizeChange(item.size, out.size)} · streams copied bit-for-bit")
        }
        Outputs.cleanTemp(ctx)
    }
}

// =========================================================================== 3. Compress
class CompressVideoJob(inputs: List<MediaItem>, private val quality: Int, private val maxShortSide: Int) : ExportJob(ToolId.COMPRESS_VIDEO, inputs) {
    override val title = "Compressing ${plural(inputs.size, "video")} (quality $quality)"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = VideoProbe.probe(ctx.app, item.uri)
            val v = info.video ?: throw UserFacingException("This file has no video track.")
            val (w, h) = VideoTranscoder.outputSize(info.codedWidth, info.codedHeight, maxShortSide, info.frameRate)
            val audioBits = info.audio.sumOf { t -> if (t.format.containsKey(android.media.MediaFormat.KEY_BIT_RATE)) t.format.getInteger(android.media.MediaFormat.KEY_BIT_RATE) else 128_000 }
            val srcVideoBits = if (info.bitrate > 0) (info.bitrate - audioBits).coerceAtLeast(info.bitrate / 2) else
                if (info.durationUs > 0) (item.size * 8_000_000L / info.durationUs).toInt() else 0
            val bitrate = VideoTranscoder.bitrateFor(quality, w, h, info.frameRate, srcVideoBits)
            val estimate = (bitrate.toLong() + 160_000) * info.durationUs / 8_000_000
            OutputStore.ensureSpace(estimate * 2)
            val tmp = File(Outputs.tempDir(ctx), "cmp-$index.mp4")
            try {
                val result = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { pfd ->
                    VideoTranscoder(ctx.app, item.uri, info).run(pfd.fileDescriptor, VideoTranscoder.Params(w, h, bitrate),
                        ctx.workload, { ctx.throttle() }) { f -> ctx.unitProgress(index, f * 0.95) }
                }
                if (item.size > 0 && tmp.length() >= item.size) {
                    throw SkipItemException("The compressed video would be larger than the original (${Format.bytes(tmp.length())} vs ${Format.bytes(item.size)}), so nothing was saved. Try a lower quality or resolution.")
                }
                val out = Outputs.produce(ctx, ToolId.COMPRESS_VIDEO.area, "${item.baseName}_compressed.mp4", "video/mp4") { pending ->
                    RandomAccessFile(tmp, "r").use { raf -> pending.openStream().buffered(1 shl 20).use { os -> Mp4FastStart.process(raf, os) } }
                    Outputs.verifyMedia(ctx.app, pending.uri, true, false)
                }.first
                val dispW = if (info.rotation % 180 == 0) w else h; val dispH = if (info.rotation % 180 == 0) h else w
                ItemResult(item.name, ItemOutcome.SUCCESS, result.notes.joinToString(" ").ifBlank { null }, listOf(out),
                    "${Outputs.sizeChange(item.size, out.size)} · ${dispW}×$dispH · H.264 ${bitrate / 1000} kbps · from ${v.codecLabel}")
            } finally {
                tmp.delete()
            }
        }
        Outputs.cleanTemp(ctx)
    }
}

// =========================================================================== 4. Remove audio
class RemoveAudioJob(inputs: List<MediaItem>) : ExportJob(ToolId.REMOVE_AUDIO, inputs) {
    override val title = "Removing audio from ${plural(inputs.size, "video")}"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = VideoProbe.probe(ctx.app, item.uri)
            val v = info.video ?: throw UserFacingException("This file has no video track.")
            if (!info.hasAudio) throw SkipItemException("This video already has no audio track.")
            OutputStore.ensureSpace(item.size)
            val out = remuxToOutput(ctx, item, ToolId.REMOVE_AUDIO.area, "${item.baseName}_noaudio", listOf(v), 0, Long.MAX_VALUE, info.rotation) { f -> ctx.unitProgress(index, f) }
            ItemResult(item.name, ItemOutcome.SUCCESS, null, listOf(out), "${Outputs.sizeChange(item.size, out.size)} · video copied as-is")
        }
    }
}

// =========================================================================== 18. Extract audio
class ExtractAudioJob(inputs: List<MediaItem>) : ExportJob(ToolId.EXTRACT_AUDIO, inputs) {
    override val title = "Extracting audio from ${plural(inputs.size, "video")}"

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = VideoProbe.probe(ctx.app, item.uri)
            val audio = info.audio
            if (audio.isEmpty()) throw SkipItemException("This video has no audio track.")
            val aac = audio.filter { it.isAac }
            if (aac.isEmpty()) {
                throw SkipItemException("No compatible AAC audio track (this video's audio is ${audio.joinToString { it.codecLabel }}). Nothing was extracted.")
            }
            val outs = ArrayList<OutputFile>()
            for ((k, t) in aac.withIndex()) {
                val name = if (aac.size == 1) "${item.baseName}_audio.m4a" else "${item.baseName}_audio${k + 1}.m4a"
                outs.add(Outputs.produce(ctx, ToolId.EXTRACT_AUDIO.area, name, "audio/mp4") { pending ->
                    pending.openFd("rw").use { pfd ->
                        Remuxer(ctx.app, item.uri).copy(pfd.fileDescriptor, Container.M4A, listOf(t), 0, Long.MAX_VALUE, 0, { ctx.throttle() }) { f ->
                            ctx.unitProgress(index, (k + f) / aac.size)
                        }
                    }
                    Outputs.verifyMedia(ctx.app, pending.uri, false, true)
                }.first)
            }
            val skippedOther = audio.size - aac.size
            ItemResult(item.name, ItemOutcome.SUCCESS,
                if (skippedOther > 0) "$skippedOther non-AAC audio track(s) were not extracted." else null,
                outs, outs.joinToString { Format.bytes(it.size) } + " · AAC copied without re-encoding")
        }
    }
}
