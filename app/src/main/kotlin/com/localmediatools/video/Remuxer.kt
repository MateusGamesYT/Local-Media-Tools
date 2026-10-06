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

enum class Container(val muxerFormat: Int, val ext: String, val mime: String) {
    MP4(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", "video/mp4"),
    THREE_GP(MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP, "3gp", "video/3gpp"),
    WEBM(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", "video/webm"),
    M4A(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a", "audio/mp4");

    companion object {
        private val WEBM_CODECS = setOf(MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS)
        private val MP4_VIDEO = setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_MPEG4,
            MediaFormat.MIMETYPE_VIDEO_H263, MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)

        /** Picks containers to try, best first, for a set of track codecs. */
        fun candidates(mimes: List<String>): List<Container> {
            val list = ArrayList<Container>()
            if (mimes.isNotEmpty() && mimes.all { it in WEBM_CODECS }) list.add(WEBM)
            if (mimes.filter { it.startsWith("video/") }.all { it in MP4_VIDEO }) list.add(MP4)
            if (mimes.any { it == MediaFormat.MIMETYPE_AUDIO_AMR_NB || it == MediaFormat.MIMETYPE_AUDIO_AMR_WB }) list.add(THREE_GP)
            if (list.isEmpty()) list.add(MP4)
            return list.distinct()
        }
    }
}

class RemuxStats(val samples: Long, val bytes: Long, val firstUs: Long, val lastUs: Long)

/**
 * Copies compressed samples from a source into a new container with MediaExtractor/MediaMuxer.
 * Nothing is decoded or re-encoded, so quality is untouched. Samples from all tracks are
 * interleaved by timestamp so the muxer never has to buffer large amounts of data.
 */
class Remuxer(private val ctx: Context, private val uri: Uri) {

    fun interface Opener { fun open(): FileDescriptor }

    /**
     * Copies [tracks] in presentation range [startUs, endUs). The video track must start at a sync
     * sample at [startUs]; video stops at the first sync sample at or after [endUs].
     */
    fun copy(
        out: FileDescriptor,
        container: Container,
        tracks: List<TrackInfo>,
        startUs: Long,
        endUs: Long,
        rotation: Int,
        throttle: () -> Unit,
        progress: (Double) -> Unit,
    ): RemuxStats {
        val muxer = MediaMuxer(out, container.muxerFormat)
        val readers = ArrayList<Reader>()
        var started = false
        try {
            for (t in tracks) {
                val idx = try {
                    muxer.addTrack(t.format)
                } catch (e: Exception) {
                    throw UserFacingException("${t.codecLabel} ${if (t.isVideo) "video" else "audio"} can't be stored in a ${container.ext.uppercase()} file on this device.", e)
                }
                readers.add(Reader(t, idx, startUs))
            }
            if (tracks.any { it.isVideo } && rotation != 0) muxer.setOrientationHint(rotation)
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var samples = 0L; var bytes = 0L
            var first = Long.MAX_VALUE; var last = 0L
            val span = (if (endUs == Long.MAX_VALUE) readers.maxOf { it.track.durationUs }.coerceAtLeast(1) + 0 else endUs) - startUs
            var lastProgress = 0L
            while (true) {
                // Next sample in time order (video wins ties so cuts happen at video sync points).
                var pick: Reader? = null
                for (r in readers) {
                    if (r.done) continue
                    if (pick == null || r.time < pick.time || (r.time == pick.time && r.track.isVideo)) pick = r
                }
                if (pick == null) break
                val r = pick
                val pts = r.time
                val sync = (r.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                if (pts >= endUs && (!r.track.isVideo || sync || r.written == 0L)) {
                    r.done = true
                    continue
                }
                if (pts < startUs) { r.advance(); continue }
                val size = r.read()
                if (size < 0) { r.done = true; continue }
                info.set(0, size, pts - startUs, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(r.muxerIndex, r.buffer, info)
                r.written++
                samples++; bytes += size
                if (pts < first) first = pts
                if (pts > last) last = pts
                r.advance()
                if (samples - lastProgress >= 64) {
                    lastProgress = samples
                    throttle()
                    progress(((pts - startUs).toDouble() / span).coerceIn(0.0, 1.0))
                }
            }
            if (readers.any { it.track.isVideo && it.written == 0L } || samples == 0L) {
                throw UserFacingException("No media samples were found in this part of the file.")
            }
            muxer.stop()
            started = false
            return RemuxStats(samples, bytes, first, last)
        } finally {
            readers.forEach { it.close() }
            try { if (started) muxer.stop() } catch (_: Exception) { }
            try { muxer.release() } catch (_: Exception) { }
        }
    }

    private inner class Reader(val track: TrackInfo, val muxerIndex: Int, startUs: Long) {
        val ex: MediaExtractor = VideoProbe.extractor(ctx, uri)
        var buffer: ByteBuffer
        var done = false
        var written = 0L

        init {
            ex.selectTrack(track.index)
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val max = if (track.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) track.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            buffer = ByteBuffer.allocateDirect(maxOf(max, if (track.isVideo) 2 shl 20 else 256 shl 10))
            if (ex.sampleTime < 0) done = true
        }

        val time: Long get() = ex.sampleTime

        fun read(): Int {
            val needed = ex.sampleSize
            if (needed > buffer.capacity()) buffer = ByteBuffer.allocateDirect((needed * 1.25).toInt())
            buffer.clear()
            return ex.readSampleData(buffer, 0)
        }

        fun advance() {
            if (!ex.advance() || ex.sampleTime < 0) done = true
        }

        fun close() = try { ex.release() } catch (_: Exception) { }
    }

    /** Presentation times of all sync samples (keyframes) of the video track, in decode order. */
    fun syncTimes(videoTrack: Int, throttle: () -> Unit): LongArray {
        val ex = VideoProbe.extractor(ctx, uri)
        try {
            ex.selectTrack(videoTrack)
            val list = ArrayList<Long>()
            var n = 0
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) list.add(t)
                if (!ex.advance()) break
                if (++n % 2000 == 0) throttle()
            }
            return list.sorted().toLongArray()
        } finally {
            ex.release()
        }
    }
}
