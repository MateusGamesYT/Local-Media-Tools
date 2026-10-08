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
import com.localmediatools.codec.audio.PcmProcessor
import com.localmediatools.core.UserFacingException
import com.localmediatools.core.WorkloadProfile
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hardware transcoder: source video → decoder → GPU (scaling, optional effects) → H.264 encoder →
 * MP4. Audio is copied as-is when it is AAC and nothing about it changes, otherwise re-encoded to
 * AAC. By default the picture is not rotated: the source rotation is written as the container's
 * orientation hint, so it plays upright.
 */
class VideoTranscoder(
    private val ctx: Context,
    private val uri: Uri,
    private val info: VideoInfo,
) {
    data class Params(
        /** Coded (unrotated) size; the display-oriented size with [rotateToDisplay]. */
        val outWidth: Int,
        val outHeight: Int,
        val videoBitrate: Int,
        val audioBitrate: Int = 160_000,
        val keepAudio: Boolean = true,
        /** Playback speed (2.0 = twice as fast). Sound is re-encoded with its pitch kept. */
        val speed: Double = 1.0,
        /** Frames beyond this rate (after the speed change) are dropped; 0 keeps all. */
        val maxFps: Double = 0.0,
        /** Turns the picture upright instead of storing the rotation as a flag. */
        val rotateToDisplay: Boolean = false,
        /** Keeps the aspect ratio inside the output size, with black bars where it differs. */
        val fit: Boolean = false,
        /** Hides regions of each frame (face blur). */
        val effect: FrameEffect? = null,
        /** Re-encodes the sound to this sample rate / channel count (0 = as the source). */
        val audioRate: Int = 0,
        val audioChannels: Int = 0,
        /** Writes a silent sound track when the source has none (merged clips all need one). */
        val silenceIfNoAudio: Boolean = false,
        /** Frame rate announced to the encoder (merged clips need the same); 0 = from the source. */
        val headerFps: Double = 0.0,
    )

    /**
     * Regions to hide in each frame: [regions] gets the source timestamp and returns ellipses in
     * display-oriented fractions of the picture.
     */
    class FrameEffect(val pixelate: Boolean, val strength: Float, val regions: (Long) -> List<ObscureRegion>)

    class Result(val notes: List<String>, val frames: Long)

    private val timeoutUs = 10_000L

    fun run(out: FileDescriptor, p: Params, workload: WorkloadProfile, throttle: () -> Unit, progress: (Double) -> Unit): Result {
        val v = info.video ?: throw UserFacingException("The file has no video track.")
        val notes = ArrayList<String>()
        val audioTrack = if (p.keepAudio) info.audio.firstOrNull() else null
        val speed = p.speed.coerceIn(0.05, 100.0)

        val videoEx = VideoProbe.extractor(ctx, uri).apply { selectTrack(v.index) }
        val audioEx = audioTrack?.let { a -> VideoProbe.extractor(ctx, uri).apply { selectTrack(a.index) } }
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var egl: EglEnv? = null
        var renderer: ExternalTextureRenderer? = null
        var obscurer: RegionObscurer? = null
        var outSurface: DecoderOutputSurface? = null
        var audio: AudioPath? = null
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxing = false
        var frames = 0L
        try {
            // ---- encoder
            val (enc, _) = createEncoder(p, workload, speed)
            encoder = enc
            val inputSurface = enc.createInputSurface()
            egl = EglEnv(inputSurface)
            renderer = ExternalTextureRenderer()
            if (p.effect != null) obscurer = RegionObscurer(p.outWidth, p.outHeight)
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
            val changeSound = speed != 1.0 || p.audioRate > 0 || p.audioChannels > 0
            if (audioTrack != null && audioEx != null) {
                audio = if (audioTrack.isAac && !changeSound) AudioPassthrough(audioEx, audioTrack)
                else try {
                    AudioTranscode(audioEx, audioTrack, p.audioBitrate, p.audioRate, p.audioChannels, speed, 0).also {
                        if (!audioTrack.isAac) notes.add("${audioTrack.codecLabel} audio was converted to AAC.")
                    }
                } catch (e: Exception) {
                    notes.add("The ${audioTrack.codecLabel} audio couldn't be converted on this device, so the result has no sound.")
                    null
                }
            } else if (p.silenceIfNoAudio) {
                audio = AudioTranscode(null, null, p.audioBitrate, p.audioRate, p.audioChannels, 1.0, (info.durationUs / speed).toLong())
            }

            // ---- picture placement
            val rot = if (p.rotateToDisplay) info.rotation else 0
            val srcW = if (p.rotateToDisplay) info.displayWidth else info.codedWidth
            val srcH = if (p.rotateToDisplay) info.displayHeight else info.codedHeight
            var sx = 1f; var sy = 1f
            if (p.fit && srcW > 0 && srcH > 0) {
                val srcAspect = srcW.toDouble() / srcH; val outAspect = p.outWidth.toDouble() / p.outHeight
                if (srcAspect > outAspect * 1.005) sy = (outAspect / srcAspect).toFloat()
                else if (srcAspect < outAspect / 1.005) sx = (srcAspect / outAspect).toFloat()
            }
            val interval = if (p.maxFps > 0) 1_000_000.0 / p.maxFps else 0.0
            var nextDue = Double.NEGATIVE_INFINITY

            val bufInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var encoderFormat: MediaFormat? = null
            var videoTrackIndex = -1
            var lastVideoPts = 0L
            var decoded = 0L
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
                            val srcPts = bufInfo.presentationTimeUs
                            val outPts = (srcPts / speed).toLong()
                            var render = bufInfo.size != 0
                            if (render && interval > 0) {
                                // Keep frames about [interval] apart in the output (timelapse, high frame rates).
                                if (outPts + interval * 0.25 < nextDue) render = false
                                else nextDue = if (nextDue + interval < outPts) outPts + interval else nextDue + interval
                            }
                            decoder.releaseOutputBuffer(idx, render)
                            if (render) {
                                if (!outSurface.awaitFrame()) throw UserFacingException("The video decoder stopped delivering frames.")
                                val draw = { renderer.draw(outSurface.stMatrix, p.outWidth, p.outHeight, rot, false, sx, sy) }
                                val fx = p.effect
                                if (fx != null && obscurer != null) {
                                    val regions = fx.regions(srcPts).map { placeRegion(it, if (p.rotateToDisplay) 0 else info.rotation, sx, sy) }
                                    obscurer.render(draw, regions, fx.pixelate, fx.strength)
                                } else draw()
                                egl.setPresentationTime(outPts * 1000)
                                egl.swap()
                                frames++
                                lastVideoPts = outPts
                            }
                            if (++decoded % 15 == 0L) {
                                throttle()
                                progress((srcPts.toDouble() / duration).coerceIn(0.0, 0.99))
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
                    audio.blocking = encoderDone
                    if (audio.step(muxing, muxer, if (encoderDone) Long.MAX_VALUE else lastVideoPts + 500_000)) moved = true
                }
                // 5. Start the muxer once all output formats are known.
                if (!muxing && encoderFormat != null && (audio == null || audio.format != null)) {
                    videoTrackIndex = muxer.addTrack(encoderFormat)
                    audio?.let { a -> a.muxerTrack = muxer.addTrack(a.format!!) }
                    if (info.rotation != 0 && !p.rotateToDisplay) muxer.setOrientationHint(info.rotation)
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
            try { obscurer?.release() } catch (_: Exception) { }
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

    /**
     * Maps a display-oriented region to the output picture: undoes the rotation when the output
     * stays in coded orientation ([rotationCw] = the rotation still stored as a flag), then places
     * it inside the letterboxed picture.
     */
    private fun placeRegion(r: ObscureRegion, rotationCw: Int, sx: Float, sy: Float): ObscureRegion {
        val c = when (rotationCw) {
            90 -> ObscureRegion(r.cy, 1f - r.cx, r.ry, r.rx)
            180 -> ObscureRegion(1f - r.cx, 1f - r.cy, r.rx, r.ry)
            270 -> ObscureRegion(1f - r.cy, r.cx, r.ry, r.rx)
            else -> r
        }
        if (sx == 1f && sy == 1f) return c
        return ObscureRegion(0.5f + (c.cx - 0.5f) * sx, 0.5f + (c.cy - 0.5f) * sy, c.rx * sx, c.ry * sy)
    }

    private fun createEncoder(p: Params, workload: WorkloadProfile, speed: Double): Pair<MediaCodec, MediaFormat> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val fps = (if (p.headerFps > 0) p.headerFps else (info.frameRate * speed).let { if (p.maxFps > 0) minOf(it, p.maxFps) else it }).coerceIn(1.0, 120.0)
        fun format(withExtras: Boolean): MediaFormat = MediaFormat.createVideoFormat(mime, p.outWidth, p.outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, p.videoBitrate)
            setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (withExtras) {
                // Parameter sets before every keyframe: lets clips from separate runs be joined safely.
                if (p.headerFps > 0) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
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
        /** True when nothing else is running, so waiting for the codecs costs nothing. */
        var blocking = false
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

    /**
     * Decodes the sound (or makes [silenceUs] of silence when [ex] is null), converts it with a
     * [PcmProcessor] (channels, sample rate, tempo) and encodes AAC. Timestamps come from the
     * number of samples written, so they are exact whatever the speed.
     */
    private inner class AudioTranscode(
        val ex: MediaExtractor?, track: TrackInfo?, bitrate: Int,
        targetRate: Int, targetChannels: Int, private val speed: Double, silenceUs: Long,
    ) : AudioPath() {
        private val decoder: MediaCodec?
        private val encoder: MediaCodec
        private val outRate: Int
        private val outChannels: Int
        private var processor: PcmProcessor? = null
        private var floatPcm = false
        private var extractorDone = false
        private var sourceDone = false
        private var eosQueued = false
        private val queue = ArrayDeque<ShortArray>()
        private var headPos = 0
        private var queuedShorts = 0L
        private var framesToEncoder = 0L
        private val silenceFrames: Long
        private var silenceMade = 0L
        private val decInfo = MediaCodec.BufferInfo()
        private val encInfo = MediaCodec.BufferInfo()

        init {
            fun int(key: String, def: Int) = if (track != null && track.format.containsKey(key)) track.format.getInteger(key) else def
            val srcRate = int(MediaFormat.KEY_SAMPLE_RATE, 48000)
            val srcCh = int(MediaFormat.KEY_CHANNEL_COUNT, 2)
            outRate = if (targetRate > 0) targetRate else aacRate(srcRate)
            outChannels = if (targetChannels > 0) targetChannels else srcCh.coerceIn(1, 2)
            silenceFrames = silenceUs * outRate / 1_000_000
            decoder = track?.let { t ->
                MediaCodec.createDecoderByType(t.mime).also { d ->
                    try { d.configure(t.format, null, null, 0) } catch (e: Exception) { d.release(); throw e }
                }
            }
            val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, outChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (outChannels == 1) bitrate / 2 else bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                decoder?.release(); encoder.release()
                throw e
            }
            decoder?.start()
            encoder.start()
        }

        private fun newProcessor(f: MediaFormat): PcmProcessor {
            val rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else outRate
            val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else outChannels
            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
            return PcmProcessor(rate, ch.coerceAtLeast(1), outRate, outChannels, speed)
        }

        private fun enqueue(a: ShortArray) { if (a.isNotEmpty()) { queue.addLast(a); queuedShorts += a.size } }

        override fun step(muxing: Boolean, muxer: MediaMuxer, untilUs: Long): Boolean {
            if (!(format == null || muxing)) return false
            val wait = if (blocking) timeoutUs else 0L
            var did = false
            // Keep about a second of converted sound ready, no more.
            val room = queuedShorts < outRate.toLong() * outChannels
            if (decoder != null) {
                if (!extractorDone && room) {
                    val idx = decoder.dequeueInputBuffer(wait)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)!!
                        val size = ex!!.readSampleData(buf, 0)
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
                if (!sourceDone && room) {
                    val idx = decoder.dequeueOutputBuffer(decInfo, wait)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        processor = newProcessor(decoder.outputFormat)
                        did = true
                    } else if (idx >= 0) {
                        if (decInfo.size > 0) {
                            val proc = processor ?: newProcessor(decoder.outputFormat).also { processor = it }
                            val src = decoder.getOutputBuffer(idx)!!
                            src.position(decInfo.offset); src.limit(decInfo.offset + decInfo.size)
                            val bb = src.slice().order(ByteOrder.nativeOrder())
                            val pcm = if (floatPcm) {
                                val fb = bb.asFloatBuffer()
                                ShortArray(fb.remaining()) { (fb.get(it) * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
                            } else {
                                val sb = bb.asShortBuffer()
                                ShortArray(sb.remaining()).also { sb.get(it) }
                            }
                            enqueue(proc.process(pcm))
                        }
                        decoder.releaseOutputBuffer(idx, false)
                        if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            processor?.let { enqueue(it.flush()) }
                            sourceDone = true
                        }
                        did = true
                    }
                }
            } else if (!sourceDone) {
                if (silenceMade >= silenceFrames) sourceDone = true
                else if (room) {
                    val n = minOf(2048L, silenceFrames - silenceMade).toInt()
                    enqueue(ShortArray(n * outChannels)); silenceMade += n
                    did = true
                }
            }
            val nextPts = framesToEncoder * 1_000_000L / outRate
            val drained = sourceDone && queue.isEmpty()
            if (!eosQueued && (queue.isNotEmpty() || sourceDone) && (nextPts <= untilUs || drained)) {
                val ei = encoder.dequeueInputBuffer(wait)
                if (ei >= 0) {
                    val dst = encoder.getInputBuffer(ei)!!
                    dst.clear()
                    val sb = dst.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val max = (sb.remaining() / outChannels) * outChannels
                    var n = 0
                    while (queue.isNotEmpty() && n < max) {
                        val head = queue.first()
                        val take = minOf(head.size - headPos, max - n)
                        sb.put(head, headPos, take)
                        n += take; headPos += take
                        if (headPos >= head.size) { queue.removeFirst(); headPos = 0 }
                    }
                    queuedShorts -= n
                    val pts = framesToEncoder * 1_000_000L / outRate
                    framesToEncoder += n / outChannels
                    val eos = sourceDone && queue.isEmpty()
                    encoder.queueInputBuffer(ei, 0, n * 2, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (eos) eosQueued = true
                    did = true
                }
            }
            val oi = encoder.dequeueOutputBuffer(encInfo, wait)
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
                if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                encoder.releaseOutputBuffer(oi, false)
                did = true
            }
            return did
        }

        override fun release() {
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            try { encoder.stop() } catch (_: Exception) { }
            try { encoder.release() } catch (_: Exception) { }
        }
    }

    companion object {
        private val AAC_RATES = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

        /** The source sample rate when AAC supports it, otherwise the nearest common one. */
        fun aacRate(rate: Int) = when {
            rate in AAC_RATES -> rate
            rate > 48000 -> 48000
            rate > 32000 -> 44100
            else -> AAC_RATES.filter { it >= rate }.minOrNull() ?: 48000
        }

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
