package com.localmediatools.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.localmediatools.core.UserFacingException

data class TrackInfo(
    val index: Int,
    val mime: String,
    val format: MediaFormat,
) {
    val isVideo get() = mime.startsWith("video/")
    val isAudio get() = mime.startsWith("audio/")
    val isAac get() = mime == MediaFormat.MIMETYPE_AUDIO_AAC
    val durationUs: Long get() = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1
    val width get() = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
    val height get() = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
    val codecLabel: String get() = codecName(mime)

    companion object {
        fun codecName(mime: String) = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265/HEVC"
            MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
            MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
            MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "MPEG-4"
            MediaFormat.MIMETYPE_VIDEO_H263 -> "H.263"
            MediaFormat.MIMETYPE_VIDEO_MPEG2 -> "MPEG-2"
            MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
            MediaFormat.MIMETYPE_AUDIO_MPEG -> "MP3"
            MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
            MediaFormat.MIMETYPE_AUDIO_VORBIS -> "Vorbis"
            MediaFormat.MIMETYPE_AUDIO_AMR_NB -> "AMR-NB"
            MediaFormat.MIMETYPE_AUDIO_AMR_WB -> "AMR-WB"
            MediaFormat.MIMETYPE_AUDIO_AC3 -> "AC-3"
            MediaFormat.MIMETYPE_AUDIO_EAC3 -> "E-AC-3"
            MediaFormat.MIMETYPE_AUDIO_FLAC -> "FLAC"
            MediaFormat.MIMETYPE_AUDIO_RAW -> "PCM"
            else -> mime.substringAfter('/')
        }
    }
}

data class VideoInfo(
    val durationUs: Long,
    val tracks: List<TrackInfo>,
    /** Clockwise display rotation stored in the container. */
    val rotation: Int,
    val frameRate: Double,
    val bitrate: Int,
    val hdr: Boolean,
) {
    val video: TrackInfo? get() = tracks.firstOrNull { it.isVideo }
    val audio: List<TrackInfo> get() = tracks.filter { it.isAudio }
    val hasAudio get() = audio.isNotEmpty()
    val codedWidth get() = video?.width ?: 0
    val codedHeight get() = video?.height ?: 0
    val displayWidth get() = if (rotation % 180 == 0) codedWidth else codedHeight
    val displayHeight get() = if (rotation % 180 == 0) codedHeight else codedWidth
}

object VideoProbe {
    fun extractor(ctx: Context, uri: Uri): MediaExtractor {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
        } catch (e: Exception) {
            ex.release()
            throw UserFacingException("This file can't be read as a video. It may be damaged or use an unsupported container.", e)
        }
        return ex
    }

    fun probe(ctx: Context, uri: Uri): VideoInfo {
        val ex = extractor(ctx, uri)
        try {
            val tracks = (0 until ex.trackCount).map { i ->
                val f = ex.getTrackFormat(i)
                TrackInfo(i, f.getString(MediaFormat.KEY_MIME) ?: "unknown", f)
            }
            if (tracks.isEmpty()) throw UserFacingException("The file contains no audio or video tracks.")
            var rotation = 0
            var frameRate = 0.0
            var bitrate = 0
            var duration = tracks.maxOf { it.durationUs }
            val v = tracks.firstOrNull { it.isVideo }
            if (v != null) {
                if (v.format.containsKey(MediaFormat.KEY_ROTATION)) rotation = v.format.getInteger(MediaFormat.KEY_ROTATION)
                if (v.format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    frameRate = try { v.format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() } catch (e: ClassCastException) { v.format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
                }
            }
            val hdr = v != null && v.format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                v.format.getInteger(MediaFormat.KEY_COLOR_TRANSFER).let { it == MediaFormat.COLOR_TRANSFER_ST2084 || it == MediaFormat.COLOR_TRANSFER_HLG }
            try {
                MediaMetadataRetriever().apply {
                    try {
                        setDataSource(ctx, uri)
                        if (rotation == 0) extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { rotation = it }
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { bitrate = it }
                        if (duration <= 0) extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration = it * 1000 }
                        if (frameRate <= 0 && v != null) {
                            val frames = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                            if (frames != null && duration > 0) frameRate = frames * 1_000_000.0 / duration
                        }
                    } finally { release() }
                }
            } catch (_: Exception) {
            }
            if (frameRate <= 0 || frameRate > 480) frameRate = 30.0
            rotation = ((rotation % 360) + 360) % 360
            if (rotation % 90 != 0) rotation = 0
            return VideoInfo(duration, tracks, rotation, frameRate, bitrate, hdr)
        } finally {
            ex.release()
        }
    }
}
