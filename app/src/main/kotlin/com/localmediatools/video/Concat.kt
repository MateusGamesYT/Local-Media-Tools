package com.localmediatools.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.localmediatools.core.UserFacingException
import java.io.FileDescriptor
import java.nio.ByteBuffer

/** One clip to join: where to read it and what it contains. */
class ConcatClip(val uri: Uri, val info: VideoInfo)

/**
 * Joins clips end to end into one MP4 by copying their compressed samples (the video track and,
 * with sound, the first audio track). Nothing is re-encoded, so this only works when the clips'
 * streams are interchangeable; [incompatibility] says when they are not.
 */
class Concatenator(private val ctx: Context) {

    /** Writes [clips] one after the other; returns the total duration in µs. */
    fun join(out: FileDescriptor, clips: List<ConcatClip>, withAudio: Boolean, throttle: () -> Unit, progress: (Double) -> Unit): Long {
        val first = clips.first().info
        val firstVideo = first.video ?: throw UserFacingException("A clip has no video track.")
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val videoIndex = muxer.addTrack(firstVideo.format)
            val audioIndex = if (withAudio) first.audio.firstOrNull()?.let { muxer.addTrack(it.format) } ?: -1 else -1
            if (first.rotation != 0) muxer.setOrientationHint(first.rotation)
            muxer.start()
            started = true
            val total = clips.sumOf { it.info.durationUs.coerceAtLeast(1) }.toDouble()
            val bi = MediaCodec.BufferInfo()
            var buffer = ByteBuffer.allocateDirect(2 shl 20)
            var offset = 0L
            var before = 0L
            var samples = 0L
            for (clip in clips) {
                val v = clip.info.video ?: throw UserFacingException("A clip has no video track.")
                val a = if (audioIndex >= 0) clip.info.audio.firstOrNull() else null
                val tracks = listOfNotNull(v to videoIndex, a?.let { it to audioIndex })
                val base = tracks.minOf { (t, _) -> earliestTime(clip.uri, t) }
                val readers = tracks.map { (t, idx) -> Triple(t, idx, VideoProbe.extractor(ctx, clip.uri).apply { selectTrack(t.index) }) }
                val ends = LongArray(readers.size) { offset }
                try {
                    val done = BooleanArray(readers.size) { readers[it].third.sampleTime < 0 }
                    while (true) {
                        var k = -1
                        for (i in readers.indices) if (!done[i] && (k < 0 || readers[i].third.sampleTime < readers[k].third.sampleTime)) k = i
                        if (k < 0) break
                        val (t, idx, ex) = readers[k]
                        val time = ex.sampleTime
                        val need = ex.sampleSize
                        if (need > buffer.capacity()) buffer = ByteBuffer.allocateDirect((need * 1.25).toInt())
                        buffer.clear()
                        val size = ex.readSampleData(buffer, 0)
                        if (size < 0) { done[k] = true; continue }
                        val pts = time - base + offset
                        val sync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        bi.set(0, size, pts, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(idx, buffer, bi)
                        ends[k] = maxOf(ends[k], pts + sampleDuration(t, clip.info))
                        samples++
                        if (!ex.advance() || ex.sampleTime < 0) done[k] = true
                        if (samples % 64 == 0L) {
                            throttle()
                            progress(((before + (time - base)) / total).coerceIn(0.0, 1.0))
                        }
                    }
                } finally {
                    readers.forEach { try { it.third.release() } catch (_: Exception) { } }
                }
                // The next clip starts after the longest track of this one, so every track's
                // timestamps keep increasing.
                offset = ends.max()
                before += clip.info.durationUs.coerceAtLeast(1)
            }
            if (samples == 0L) throw UserFacingException("No media samples were found in these clips.")
            muxer.stop()
            started = false
            progress(1.0)
            return offset
        } finally {
            try { if (started) muxer.stop() } catch (_: Exception) { }
            try { muxer.release() } catch (_: Exception) { }
        }
    }

    /** Earliest presentation time among the first samples of a track (B-frames can precede the first keyframe's time). */
    private fun earliestTime(uri: Uri, t: TrackInfo): Long {
        val ex = VideoProbe.extractor(ctx, uri)
        try {
            ex.selectTrack(t.index)
            var min = Long.MAX_VALUE
            var n = 0
            while (n < 48) {
                val time = ex.sampleTime
                if (time < 0) break
                if (time < min) min = time
                if (!ex.advance()) break
                n++
            }
            return if (min == Long.MAX_VALUE) 0 else min
        } finally {
            ex.release()
        }
    }

    private fun sampleDuration(t: TrackInfo, info: VideoInfo): Long = if (t.isVideo) {
        (1_000_000.0 / info.frameRate.coerceIn(1.0, 240.0)).toLong()
    } else {
        val rate = if (t.format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) t.format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
        1024L * 1_000_000 / rate.coerceAtLeast(1)
    }

    companion object {
        /** Null when [clips] can be joined without re-encoding, otherwise the reason (shown to the user). */
        fun incompatibility(clips: List<VideoInfo>, withAudio: Boolean): String? {
            val first = clips.first()
            val v0 = first.video ?: return "a clip has no video"
            if (v0.mime != MediaFormat.MIMETYPE_VIDEO_AVC && v0.mime != MediaFormat.MIMETYPE_VIDEO_HEVC) return "${v0.codecLabel} video can't be joined directly"
            for (c in clips) {
                val v = c.video ?: return "a clip has no video"
                if (v.mime != v0.mime) return "the clips use different video formats"
                if (v.width != v0.width || v.height != v0.height) return "the clips have different picture sizes"
                if (c.rotation != first.rotation) return "the clips have different orientations"
                if (!sameSetup(v.format, v0.format)) return "the clips were recorded with different encoder settings"
            }
            if (withAudio) {
                val a0 = first.audio.firstOrNull()
                for (c in clips) {
                    val a = c.audio.firstOrNull()
                    if ((a == null) != (a0 == null)) return "some clips have sound and others don't"
                    if (a == null || a0 == null) continue
                    if (a.mime != MediaFormat.MIMETYPE_AUDIO_AAC || a.mime != a0.mime) return "the clips' sound uses different formats"
                    if (int(a.format, MediaFormat.KEY_SAMPLE_RATE) != int(a0.format, MediaFormat.KEY_SAMPLE_RATE) ||
                        int(a.format, MediaFormat.KEY_CHANNEL_COUNT) != int(a0.format, MediaFormat.KEY_CHANNEL_COUNT) ||
                        !sameSetup(a.format, a0.format)) return "the clips' sound uses different settings"
                }
            }
            return null
        }

        private fun int(f: MediaFormat, key: String) = if (f.containsKey(key)) f.getInteger(key) else -1

        /** Same codec setup data (H.264 SPS/PPS, AAC config…). */
        private fun sameSetup(a: MediaFormat, b: MediaFormat): Boolean {
            for (k in listOf("csd-0", "csd-1", "csd-2")) {
                val x = if (a.containsKey(k)) a.getByteBuffer(k) else null
                val y = if (b.containsKey(k)) b.getByteBuffer(k) else null
                if ((x == null) != (y == null)) return false
                if (x != null && y != null) {
                    val xd = x.duplicate().apply { rewind() }; val yd = y.duplicate().apply { rewind() }
                    if (xd != yd) return false
                }
            }
            return true
        }
    }
}
