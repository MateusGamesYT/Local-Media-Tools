package com.localmediatools.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.localmediatools.core.UserFacingException
import com.localmediatools.core.WorkloadProfile
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Hardware transcoder: source video → decoder → GPU (scaling) → H.264 encoder → MP4.
 * Audio is copied as-is when it is AAC, otherwise re-encoded to AAC. The picture is never rotated;
 * the source rotation is written as the container's orientation hint, so it plays upright.
 */
class VideoTranscoder(
    private val ctx: Context,
    private val uri: Uri,
    private val info: VideoInfo,
) {
    data class Params(
        val outWidth: Int,   // coded (unrotated) size
        val outHeight: Int,
        val videoBitrate: Int,
        val audioBitrate: Int = 160_000,
        val keepAudio: Boolean = true,
    )

    class Result(val notes: List<String>, val frames: Long)

    private val timeoutUs = 10_000L

    fun run(out: FileDescriptor, p: Params, workload: WorkloadProfile, throttle: () -> Unit, progress: (Double) -> Unit): Result {
        val v = info.video ?: throw UserFacingException("The file has no video track.")
        val notes = ArrayList<String>()
        val audioTrack = if (p.keepAudio) info.audio.firstOrNull() else null

        val videoEx = VideoProbe.extractor(ctx, uri).apply { selectTrack(v.index) }
        val audioEx = audioTrack?.let { a -> VideoProbe.extractor(ctx, uri).apply { selectTrack(a.index) } }
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var egl: EglEnv? = null
        var renderer: ExternalTextureRenderer? = null
        var outSurface: DecoderOutputSurface? = null
        var audio: AudioPath? = null
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxing = false
        var frames = 0L
        try {
            // ---- encoder
            val (enc, encFormat) = createEncoder(p, workload)
            encoder = enc
            val inputSurface = enc.createInputSurface()
            egl = EglEnv(inputSurface)
            renderer = ExternalTextureRenderer()
            outSurface = DecoderOutputSurface(renderer.textureId)
            enc.start()

            // ---- decoder (rotation removed so frames stay in coded orientation)
            val decFormat = MediaFormat(v.format)
            decFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            if (info.hdr && Build.VERSION.SDK_INT >= 33) {
                decFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                notes.add("HDR video was converted to standard dynamic range (SDR).")
            } else if (info.hdr) {
                notes.add("HDR video: colours may look different after conversion on this Android version.")
            }
            decFormat.setInteger(MediaFormat.KEY_PRIORITY, 1)
            workload.codecOperatingRate?.let { decFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, it) }
            decoder = createDecoder(v.format, decFormat, outSurface.surface)
            decoder.start()

            // ---- audio
            if (audioTrack != null && audioEx != null) {
                audio = if (audioTrack.isAac) AudioPassthrough(audioEx, audioTrack)
                else try {
                    AudioTranscode(audioEx, audioTrack, p.audioBitrate).also { notes.add("${audioTrack.codecLabel} audio was converted to AAC.") }
                } catch (e: Exception) {
                    notes.add("The ${audioTrack.codecLabel} audio couldn't be converted on this device, so the result has no sound.")
                    null
                }
            }

            val bufInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var encoderFormat: MediaFormat? = null
            var videoTrackIndex = -1
            var lastVideoPts = 0L
            var lastActivity = System.currentTimeMillis()
            val duration = info.durationUs.coerceAtLeast(1)

            while (!encoderDone || (audio != null && !audio.done)) {
                var moved = false
                val videoGate = encoderFormat == null || muxing
                // 1. Feed the video decoder.
                if (!extractorDone && videoGate) {
                    val idx = decoder.dequeueInputBuffer(timeoutUs)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)!!
                        val size = videoEx.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, size, videoEx.sampleTime, 0)
                            videoEx.advance()
                        }
                        moved = true
                    }
                }
                // 2. Decoder output → GPU → encoder input surface.
                if (!decoderDone && videoGate) {
                    val idx = decoder.dequeueOutputBuffer(bufInfo, timeoutUs)
                    if (idx >= 0) {
                        moved = true
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoder.releaseOutputBuffer(idx, false)
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        } else {
                            val render = bufInfo.size != 0
                            decoder.releaseOutputBuffer(idx, render)
                            if (render) {
                                if (!outSurface.awaitFrame()) throw UserFacingException("The video decoder stopped delivering frames.")
                                renderer.draw(outSurface.stMatrix, p.outWidth, p.outHeight)
                                egl.setPresentationTime(bufInfo.presentationTimeUs * 1000)
                                egl.swap()
                                frames++
                                lastVideoPts = bufInfo.presentationTimeUs
                                if (frames % 15 == 0L) {
                                    throttle()
                                    progress((lastVideoPts.toDouble() / duration).coerceIn(0.0, 0.99))
                                }
                            }
                        }
                    }
                }
                // 3. Encoder output → muxer.
                if (!encoderDone && videoGate) {
                    val idx = encoder.dequeueOutputBuffer(bufInfo, timeoutUs)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        encoderFormat = encoder.outputFormat
                        moved = true
                    } else if (idx >= 0) {
                        moved = true
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufInfo.size = 0
                        }
                        if (bufInfo.size > 0) {
                            check(muxing) { "encoder produced data before its format" }
                            val data = encoder.getOutputBuffer(idx)!!
                            data.position(bufInfo.offset); data.limit(bufInfo.offset + bufInfo.size)
                            muxer.writeSampleData(videoTrackIndex, data, bufInfo)
                        }
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                        encoder.releaseOutputBuffer(idx, false)
                    }
                }
                // 4. Audio.
                if (audio != null && !audio.done) {
                    if (audio.step(muxing, muxer, if (encoderDone) Long.MAX_VALUE else lastVideoPts + 500_000)) moved = true
                }
                // 5. Start the muxer once all output formats are known.
                if (!muxing && encoderFormat != null && (audio == null || audio.format != null)) {
                    videoTrackIndex = muxer.addTrack(encoderFormat)
                    audio?.let { a -> a.muxerTrack = muxer.addTrack(a.format!!) }
                    if (info.rotation != 0) muxer.setOrientationHint(info.rotation)
                    muxer.start()
                    muxing = true
                    moved = true
                }
                if (moved) lastActivity = System.currentTimeMillis()
                else if (System.currentTimeMillis() - lastActivity > 15_000) {
                    throw UserFacingException("Video processing stalled (the device codec stopped responding).")
                }
            }
            if (frames == 0L) throw UserFacingException("No video frames could be decoded from this file.")
            muxer.stop()
            muxing = false
            progress(1.0)
            return Result(notes, frames)
        } finally {
            try { if (muxing) muxer.stop() } catch (_: Exception) { }
            try { muxer.release() } catch (_: Exception) { }
            audio?.release()
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            try { encoder?.stop() } catch (_: Exception) { }
            try { encoder?.release() } catch (_: Exception) { }
            try { outSurface?.release() } catch (_: Exception) { }
            try { renderer?.release() } catch (_: Exception) { }
            try { egl?.release() } catch (_: Exception) { }
            videoEx.release()
            audioEx?.release()
        }
    }

    private fun createDecoder(trackFormat: MediaFormat, decFormat: MediaFormat, surface: android.view.Surface): MediaCodec {
        val probe = MediaFormat(trackFormat).apply { if (Build.VERSION.SDK_INT <= 21) setString(MediaFormat.KEY_FRAME_RATE, null) }
        val name = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe) } catch (_: Exception) { null }
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw UserFacingException("This device has no decoder for ${TrackInfo.codecName(mime)} video.", e)
        }
        try {
            codec.configure(decFormat, surface, null, 0)
        } catch (e: Exception) {
            // Retry without optional hints.
            try {
                val plain = MediaFormat(trackFormat).apply { setInteger(MediaFormat.KEY_ROTATION, 0) }
                codec.reset()
                codec.configure(plain, surface, null, 0)
            } catch (e2: Exception) {
                codec.release()
                throw UserFacingException("The ${TrackInfo.codecName(mime)} decoder rejected this video (${info.codedWidth}×${info.codedHeight}).", e2)
            }
        }
        return codec
    }

    private fun createEncoder(p: Params, workload: WorkloadProfile): Pair<MediaCodec, MediaFormat> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val fps = info.frameRate.coerceIn(1.0, 120.0)
        fun format(withExtras: Boolean): MediaFormat = MediaFormat.createVideoFormat(mime, p.outWidth, p.outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, p.videoBitrate)
            setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (withExtras) {
                setInteger(MediaFormat.KEY_PRIORITY, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
        }
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = list.findEncoderForFormat(format(false))
            ?: throw UserFacingException("This device has no H.264 encoder that supports ${p.outWidth}×${p.outHeight}.")
        val codec = MediaCodec.createByCodecName(name)
        val caps = codec.codecInfo.getCapabilitiesForType(mime)
        val attempts = ArrayList<MediaFormat>()
        val rich = format(true)
        caps.encoderCapabilities?.let { ec ->
            if (ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                rich.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }
        val high = caps.profileLevels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }.maxByOrNull { it.level }
        if (high != null) {
            attempts.add(MediaFormat(rich).apply {
                setInteger(MediaFormat.KEY_PROFILE, high.profile)
                setInteger(MediaFormat.KEY_LEVEL, high.level)
            })
        }
        attempts.add(rich)
        attempts.add(format(false))
        var last: Exception? = null
        for (f in attempts) {
            try {
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec to f
            } catch (e: Exception) {
                last = e
                try { codec.reset() } catch (_: Exception) { }
            }
        }
        codec.release()
        throw UserFacingException("The H.264 encoder rejected the output settings (${p.outWidth}×${p.outHeight}).", last)
    }

    // ------------------------------------------------------------------ audio paths
    private abstract inner class AudioPath {
        var format: MediaFormat? = null
        var muxerTrack = -1
        var done = false
        /** Moves audio forward; writes to the muxer up to [untilUs] once muxing. Returns true if it did work. */
        abstract fun step(muxing: Boolean, muxer: MediaMuxer, untilUs: Long): Boolean
        open fun release() {}
    }

    private inner class AudioPassthrough(val ex: MediaExtractor, track: TrackInfo) : AudioPath() {
        private var buf = ByteBuffer.allocateDirect(256 * 1024)
        private val info = MediaCodec.BufferInfo()

        init { format = track.format }

        override fun step(muxing: Boolean, muxer: MediaMuxer, untilUs: Long): Boolean {
            if (!muxing) return false
            var did = false
            var n = 0
            while (n < 64) {
                val t = ex.sampleTime
                if (t < 0) { done = true; return true }
                if (t > untilUs) break
                val need = ex.sampleSize
                if (need > buf.capacity()) buf = ByteBuffer.allocateDirect((need * 1.5).toInt())
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) { done = true; return true }
                info.set(0, size, t, if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(muxerTrack, buf, info)
                ex.advance()
                did = true
                n++
            }
            return did
        }
    }

    private inner class AudioTranscode(val ex: MediaExtractor, track: TrackInfo, bitrate: Int) : AudioPath() {
        private val decoder: MediaCodec
        private val encoder: MediaCodec
        private var extractorDone = false
        private var decoderDone = false
        private var pendingDecoderOut = -1
        private val decInfo = MediaCodec.BufferInfo()
        private val encInfo = MediaCodec.BufferInfo()
        private var encoderGotEos = false

        init {
            val mime = track.mime
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(track.format, null, null, 0)
            val rate = if (track.format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) track.format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (track.format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) track.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) bitrate / 2 else bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                decoder.release(); encoder.release()
                throw e
            }
            decoder.start()
            encoder.start()
        }

        override fun step(muxing: Boolean, muxer: MediaMuxer, untilUs: Long): Boolean {
            var did = false
            val gate = format == null || muxing
            if (!gate) return false
            if (!extractorDone) {
                val idx = decoder.dequeueInputBuffer(timeoutUs)
                if (idx >= 0) {
                    val buf = decoder.getInputBuffer(idx)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorDone = true
                    } else {
                        decoder.queueInputBuffer(idx, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                    did = true
                }
            }
            if (!decoderDone && pendingDecoderOut < 0) {
                val idx = decoder.dequeueOutputBuffer(decInfo, timeoutUs)
                if (idx >= 0) {
                    if (decInfo.size == 0 && decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                        decoder.releaseOutputBuffer(idx, false)
                    } else pendingDecoderOut = idx
                    did = true
                }
            }
            if (pendingDecoderOut >= 0) {
                val ei = encoder.dequeueInputBuffer(timeoutUs)
                if (ei >= 0) {
                    val dst = encoder.getInputBuffer(ei)!!
                    val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (decInfo.size > 0) {
                        val src = decoder.getOutputBuffer(pendingDecoderOut)!!
                        src.position(decInfo.offset)
                        val n = minOf(decInfo.size, dst.remaining())
                        src.limit(decInfo.offset + n)
                        dst.put(src)
                        encoder.queueInputBuffer(ei, 0, n, decInfo.presentationTimeUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    } else {
                        encoder.queueInputBuffer(ei, 0, 0, decInfo.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    decoder.releaseOutputBuffer(pendingDecoderOut, false)
                    pendingDecoderOut = -1
                    if (eos) decoderDone = true
                    did = true
                }
            }
            val oi = encoder.dequeueOutputBuffer(encInfo, timeoutUs)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                format = encoder.outputFormat
                did = true
            } else if (oi >= 0) {
                if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                if (encInfo.size > 0 && muxing) {
                    val data = encoder.getOutputBuffer(oi)!!
                    data.position(encInfo.offset); data.limit(encInfo.offset + encInfo.size)
                    muxer.writeSampleData(muxerTrack, data, encInfo)
                }
                if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encoderGotEos = true; done = true }
                encoder.releaseOutputBuffer(oi, false)
                did = true
            }
            return did
        }

        override fun release() {
            try { decoder.stop() } catch (_: Exception) { }
            try { decoder.release() } catch (_: Exception) { }
            try { encoder.stop() } catch (_: Exception) { }
            try { encoder.release() } catch (_: Exception) { }
        }
    }

    companion object {
        /**
         * Target video bitrate for a 20–100 quality value: bits per pixel per frame grows with
         * quality, and the result never exceeds the source's own bitrate.
         */
        fun bitrateFor(quality: Int, width: Int, height: Int, fps: Double, sourceVideoBitrate: Int): Int {
            val x = ((quality.coerceIn(20, 100) - 20) / 80.0)
            val bpp = 0.02 + 0.16 * Math.pow(x, 1.5)
            var br = (width.toDouble() * height * fps.coerceIn(10.0, 60.0) * bpp).toLong()
            if (sourceVideoBitrate > 0) br = minOf(br, (sourceVideoBitrate * 0.92).toLong())
            return br.coerceIn(150_000L, 80_000_000L).toInt()
        }

        /**
         * Output coded size for a maximum short side (0 = original), even dimensions, adjusted to
         * what the device's H.264 encoder supports.
         */
        fun outputSize(codedW: Int, codedH: Int, maxShortSide: Int, fps: Double): Pair<Int, Int> {
            var w = codedW.toDouble(); var h = codedH.toDouble()
            val short = minOf(w, h)
            if (maxShortSide in 1 until short.toInt()) { val s = maxShortSide / short; w *= s; h *= s }
            fun even(x: Double) = (Math.round(x / 2) * 2).toInt().coerceAtLeast(2)
            var ow = even(w); var oh = even(h)
            val caps = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                    ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
            } catch (_: Exception) { null } ?: return ow to oh
            var guard = 0
            while (guard++ < 40) {
                val okSize = try { caps.isSizeSupported(ow, oh) } catch (_: Exception) { true }
                val okRate = try { caps.areSizeAndRateSupported(ow, oh, fps.coerceIn(1.0, 120.0)) } catch (_: Exception) { okSize }
                if (okSize && okRate) break
                // Align to 16 first, then scale down gradually.
                val aw = (ow / 16) * 16; val ah = (oh / 16) * 16
                if (guard == 1 && aw > 0 && ah > 0 && (aw != ow || ah != oh)) { ow = aw; oh = ah; continue }
                ow = even(ow * 0.9); oh = even(oh * 0.9)
            }
            return ow to oh
        }
    }
}
