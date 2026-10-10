package com.localmediatools.highlight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import com.localmediatools.core.UserFacingException
import com.localmediatools.highlight.core.Caption
import com.localmediatools.highlight.core.Clip
import com.localmediatools.highlight.core.Frame
import com.localmediatools.highlight.core.Plan
import com.localmediatools.highlight.core.ShotKind
import com.localmediatools.highlight.core.Timeline
import com.localmediatools.image.BitmapOps
import com.localmediatools.image.ImageSource
import com.localmediatools.ui.typeface
import com.localmediatools.video.DecoderOutputSurface
import com.localmediatools.video.EglEnv
import com.localmediatools.video.ExternalTextureRenderer
import com.localmediatools.video.VideoInfo
import com.localmediatools.video.VideoProbe
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a highlight [Plan] into an H.264 MP4 on the GPU, frame by frame as [Timeline] describes
 * it: photos as textures (decoded ahead on another thread), videos decoded in step with the
 * timeline, cross-fades, blurred backdrops for pictures of another shape, captions, and the fade at
 * the end; the prepared sound ([EncodedAudio]) is written alongside.
 */
class HighlightRenderer(
    private val ctx: Context,
    private val plan: Plan,
    /** The file of each shot (by [com.localmediatools.highlight.core.Shot.id]) and its name. */
    private val sources: (Int) -> Pair<Uri, String>,
    private val audio: EncodedAudio?,
) {
    class Result(val width: Int, val height: Int, val frames: Int)

    private val fps = Timeline.FPS

    fun run(out: FileDescriptor, outWidth: Int, outHeight: Int, bitrate: Int, throttle: () -> Unit, cancelled: () -> Boolean, progress: (Double) -> Unit): Result {
        var muxerRef: MediaMuxer? = null
        var muxing = false
        var encoderRef: MediaCodec? = null
        var surface: android.view.Surface? = null
        var egl: EglEnv? = null
        var gl: Gl? = null
        val pictures = HashMap<Int, Picture>()
        val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "lmt-highlight-photos").apply { priority = Thread.NORM_PRIORITY - 1 } }
        val pending = HashMap<Int, Future<Bitmap?>>()
        val captions = HashMap<Int, CaptionTex>()
        /** Clips whose picture couldn't be read or decoded: left out (black) instead of retried on every frame. */
        val failed = HashSet<Int>()
        try {
            val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxerRef = it }
            val encoder = createEncoder(outWidth, outHeight, bitrate).also { encoderRef = it }
            surface = encoder.createInputSurface()
            egl = EglEnv(surface)
            gl = Gl()
            encoder.start()
            val bi = MediaCodec.BufferInfo()
            var videoTrack = -1; var audioTrack = -1
            var audioNext = 0
            var lastPts = 0L
            fun writeAudio(untilUs: Long) {
                val a = audio ?: return
                if (!muxing) return
                while (audioNext < a.packets.size && a.packets[audioNext].ptsUs <= untilUs) {
                    val p = a.packets[audioNext++]
                    val info = MediaCodec.BufferInfo().apply { set(0, p.data.size, p.ptsUs, p.flags) }
                    muxer.writeSampleData(audioTrack, ByteBuffer.wrap(p.data), info)
                }
            }
            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(bi, if (end) 10_000 else 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end || ++idle > 300) break else continue
                    }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // A second announcement changes nothing the muxer needs (as in VideoTranscoder).
                        if (muxing) continue
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audio != null) audioTrack = muxer.addTrack(audio.format)
                        muxer.start(); muxing = true
                        continue
                    }
                    if (idx < 0) continue
                    idle = 0
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                    if (bi.size > 0 && muxing) {
                        val data = encoder.getOutputBuffer(idx)!!
                        data.position(bi.offset); data.limit(bi.offset + bi.size)
                        muxer.writeSampleData(videoTrack, data, bi)
                        lastPts = bi.presentationTimeUs
                    }
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(idx, false)
                    writeAudio(lastPts + 500_000)
                    if (eos) break
                }
            }

            val frames = Timeline.frameCount(plan)
            for (n in 0 until frames) {
                if (cancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
                val t = Timeline.timeOf(n)
                // Start decoding photos a few seconds ahead; free what has finished.
                for ((i, c) in plan.clips.withIndex()) {
                    if (c.shot.kind == ShotKind.PHOTO && t >= c.startMs - 4000 && t < c.endMs && i !in pictures && i !in pending && i !in failed) {
                        pending[i] = loader.submit<Bitmap?> { decodePhoto(c, outWidth, outHeight, gl.maxTexture) }
                    }
                    if (t >= c.endMs && i in pictures) pictures.remove(i)?.release()
                }
                for ((k, c) in plan.captions.withIndex()) if (t >= c.endMs) captions.remove(k)?.release()
                val desc = Timeline.at(plan, t)
                GLES20.glViewport(0, 0, outWidth, outHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                for (l in desc.layers) {
                    if (l.clip in failed) continue
                    val c = plan.clips[l.clip]
                    val pic = pictures[l.clip] ?: run {
                        val p = if (c.shot.kind == ShotKind.PHOTO) {
                            val bmp = (pending.remove(l.clip) ?: loader.submit<Bitmap?> { decodePhoto(c, outWidth, outHeight, gl.maxTexture) }).get()
                            bmp?.let { PhotoPicture(it, c.fit) }
                        } else try { VideoPicture(c) } catch (e: Exception) { android.util.Log.w("LMT", "highlight: video unavailable", e); null }
                        if (p == null) failed.add(l.clip) else pictures[l.clip] = p
                        p
                    } ?: continue
                    if (!pic.prepare(l.sourceMs)) {
                        if (pic.broken) { failed.add(l.clip); pictures.remove(l.clip)?.release() }
                        continue
                    }
                    if (l.backdrop) pic.drawBackdrop(gl, l.backdropSrc, l.alpha, outWidth, outHeight)
                    pic.draw(gl, l.src, l.dst, l.alpha)
                }
                for (cl in desc.captions) {
                    val tex = captions.getOrPut(cl.caption) { CaptionTex(plan.captions[cl.caption], outWidth, outHeight) }
                    gl.drawBitmap(tex.texture, Timeline.bitmapMatrix(Frame(0f, 0f, 1f, 1f)), tex.dst, cl.alpha, 1f)
                }
                if (desc.fade > 0f) gl.fill(desc.fade)
                egl.setPresentationTime(n * 1_000_000_000L / fps)
                if (!egl.swap()) throw UserFacingException("The video encoder stopped taking frames.")
                drain(false)
                if (n % 10 == 0) { throttle(); progress(n.toDouble() / frames) }
            }
            encoder.signalEndOfInputStream()
            drain(true)
            if (!muxing) throw UserFacingException("The video encoder produced nothing.")
            writeAudio(Long.MAX_VALUE)
            muxer.stop(); muxing = false
            progress(1.0)
            return Result(outWidth, outHeight, frames)
        } finally {
            // Photos not decoded yet are cancelled (waiting on them after shutdownNow would never end).
            for (f in pending.values) if (!f.cancel(true)) try { f.get()?.recycle() } catch (_: Exception) { }
            loader.shutdownNow()
            pictures.values.forEach { it.release() }
            captions.values.forEach { it.release() }
            try { gl?.release() } catch (_: Exception) { }
            try { if (muxing) muxerRef?.stop() } catch (_: Exception) { }
            try { muxerRef?.release() } catch (_: Exception) { }
            try { encoderRef?.stop() } catch (_: Exception) { }
            try { encoderRef?.release() } catch (_: Exception) { }
            try { egl?.release() } catch (_: Exception) { }
            surface?.release()
        }
    }

    // ------------------------------------------------------------------ photos
    /** The photo at the size its framing needs (no more than twice what the output shows). */
    private fun decodePhoto(c: Clip, outW: Int, outH: Int, maxTex: Int): Bitmap? = try {
        val (uri, name) = sources(c.shot.id)
        ImageSource.open(ctx, uri, name).use { src ->
            // Pixels needed across the picture: the output's width over the narrowest shown part.
            val shown = min(c.from.w, c.to.w).coerceAtLeast(0.05f)
            val dstW = if (c.fit) Timeline.fitRect(c.shot.aspect, plan.aspect.ratio).w else 1f
            val needW = (outW * dstW / shown).coerceAtMost(src.width.toFloat())
            val scale = min(1f, min(needW / src.width, maxTex.toFloat() / max(src.width, src.height)))
            val tw = max(1, (src.width * scale).roundToInt()); val th = max(1, (src.height * scale).roundToInt())
            var bmp = src.decode(src.sampleSizeFor(tw, th).coerceAtLeast(1))
            if (bmp.width > tw * 1.05 || bmp.height > th * 1.05) bmp = BitmapOps.scale(bmp, tw, th, recycleSource = true)
            if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp
        }
    } catch (e: Exception) { android.util.Log.w("LMT", "highlight: photo unavailable", e); null }

    private interface Picture {
        /** Makes the picture for [sourceMs] ready; false when there is nothing to show. */
        fun prepare(sourceMs: Long): Boolean
        /** True once the picture can't be shown at all (a decoder that stopped). */
        val broken: Boolean get() = false
        fun draw(gl: Gl, src: Frame, dst: Frame, alpha: Float)
        fun drawBackdrop(gl: Gl, src: Frame, alpha: Float, outW: Int, outH: Int)
        fun release()
    }

    private class PhotoPicture(bmp: Bitmap, fit: Boolean) : Picture {
        private val texture = Gl.upload(bmp)
        /** A tiny copy, drawn large and dimmed: the blurred backdrop. */
        private val small = if (fit) Gl.upload(tiny(bmp)) else 0
        init { bmp.recycle() }
        override fun prepare(sourceMs: Long) = true
        override fun draw(gl: Gl, src: Frame, dst: Frame, alpha: Float) = gl.drawBitmap(texture, Timeline.bitmapMatrix(src), dst, alpha, 1f)
        override fun drawBackdrop(gl: Gl, src: Frame, alpha: Float, outW: Int, outH: Int) {
            if (small != 0) gl.drawBitmap(small, Timeline.bitmapMatrix(src), Frame(0f, 0f, 1f, 1f), alpha, BACKDROP_DIM)
        }
        override fun release() { GLES20.glDeleteTextures(if (small != 0) 2 else 1, intArrayOf(texture, small), 0) }

        companion object {
            /** Scaled down in halves (each step averages), so the result is smooth rather than aliased. */
            fun tiny(src: Bitmap): Bitmap {
                var b = src
                while (max(b.width, b.height) > 64) {
                    val n = Bitmap.createScaledBitmap(b, max(1, b.width / 2), max(1, b.height / 2), true)
                    if (b !== src) b.recycle()
                    b = n
                }
                return if (b === src) src.copy(Bitmap.Config.ARGB_8888, false) else b
            }
        }
    }

    // ------------------------------------------------------------------ videos
    /** A video decoded in step with the timeline onto a SurfaceTexture. */
    private inner class VideoPicture(private val clip: Clip) : Picture {
        private val uri = sources(clip.shot.id).first
        private val info: VideoInfo = VideoProbe.probe(ctx, uri)
        private val ex: MediaExtractor
        private val oes: ExternalTextureRenderer  // owns the external texture
        private val out: DecoderOutputSurface
        private val dec: MediaCodec
        private var inputDone = false
        private var outputDone = false
        private var shownUs = Long.MIN_VALUE
        private val bi = MediaCodec.BufferInfo()
        override var broken = false
        /** For the blurred backdrop: the frame drawn at half size, then halved again and again (each step averages). */
        private var pyramid: List<Gl.Target>? = null

        init {
            val v = info.video ?: throw UserFacingException("no video track")
            var e: MediaExtractor? = null; var o: ExternalTextureRenderer? = null; var s: DecoderOutputSurface? = null; var d: MediaCodec? = null
            try {
                e = VideoProbe.extractor(ctx, uri)
                e.selectTrack(v.index)
                e.seekTo(clip.sourceStartMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                o = ExternalTextureRenderer()
                s = DecoderOutputSurface(o.textureId)
                val fmt = MediaFormat(v.format)
                fmt.setInteger(MediaFormat.KEY_ROTATION, 0)
                if (info.hdr && Build.VERSION.SDK_INT >= 33) fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                val name = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(v.format) } catch (_: Exception) { null }
                d = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(v.mime)
                try { d.configure(fmt, s.surface, null, 0) } catch (x: Exception) {
                    d.reset(); d.configure(MediaFormat(v.format).apply { setInteger(MediaFormat.KEY_ROTATION, 0) }, s.surface, null, 0)
                }
                d.start()
            } catch (t: Throwable) {
                // Nothing half-made is left behind.
                try { d?.release() } catch (_: Exception) { }
                try { s?.release() } catch (_: Exception) { }
                try { o?.release() } catch (_: Exception) { }
                try { e?.release() } catch (_: Exception) { }
                throw t
            }
            ex = e; oes = o; out = s; dec = d
        }

        override fun prepare(sourceMs: Long): Boolean {
            if (broken) return false
            val target = sourceMs * 1000
            // Half an output frame of slack: the frame nearest in time.
            val slack = 500_000L / fps
            var lastProgress = System.currentTimeMillis()
            while (shownUs < target - slack && !outputDone) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(2_000)
                    if (i >= 0) {
                        lastProgress = System.currentTimeMillis()
                        val size = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (size < 0 || ex.sampleTime > clip.sourceEndMs * 1000 + 1_000_000) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(bi, 5_000)
                if (o >= 0) {
                    lastProgress = System.currentTimeMillis()
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val pts = bi.presentationTimeUs
                    // Frames before the one wanted are skipped (not drawn), except the very first: if the
                    // video ends before the wanted time, the picture stays on a frame rather than black.
                    val render = bi.size > 0 && (pts >= target - slack || shownUs == Long.MIN_VALUE)
                    dec.releaseOutputBuffer(o, render)
                    if (render) {
                        if (!out.awaitFrame()) { broken = true; return false }
                        shownUs = pts
                    }
                    if (eos) outputDone = true
                } else if (System.currentTimeMillis() - lastProgress > 10_000) {
                    // The decoder stopped responding: leave this clip out rather than hang the export.
                    broken = true
                    return false
                }
            }
            return shownUs != Long.MIN_VALUE
        }

        override fun draw(gl: Gl, src: Frame, dst: Frame, alpha: Float) =
            gl.drawExternal(oes.textureId, Timeline.videoMatrix(src, info.rotation, out.stMatrix), dst, alpha, 1f)

        override fun drawBackdrop(gl: Gl, src: Frame, alpha: Float, outW: Int, outH: Int) {
            val levels = pyramid ?: ArrayList<Gl.Target>().apply {
                var w = max(2, outW / 2); var h = max(2, outH / 2)
                while (true) { add(Gl.Target(w, h)); if (w <= 48 || h <= 48) break; w = max(2, w / 2); h = max(2, h / 2) }
            }.also { pyramid = it }
            val whole = Frame(0f, 0f, 1f, 1f)
            levels[0].bind()
            gl.drawExternal(oes.textureId, Timeline.videoMatrix(src, info.rotation, out.stMatrix), whole, 1f, 1f, blend = false)
            for (k in 1 until levels.size) { levels[k].bind(); gl.drawTexture(levels[k - 1].tex, Timeline.IDENTITY, whole, 1f, 1f, blend = false) }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, outW, outH)
            gl.drawTexture(levels.last().tex, Timeline.IDENTITY, whole, alpha, BACKDROP_DIM)
        }

        override fun release() {
            try { dec.stop() } catch (_: Exception) { }
            try { dec.release() } catch (_: Exception) { }
            try { out.release() } catch (_: Exception) { }
            try { oes.release() } catch (_: Exception) { }
            pyramid?.forEach { it.release() }
            ex.release()
        }
    }

    // ------------------------------------------------------------------ captions
    /** A caption drawn once into a texture: the title in the middle, a moment's name at the bottom left. */
    private class CaptionTex(c: Caption, outW: Int, outH: Int) {
        val texture: Int
        val dst: Frame

        init {
            val short = min(outW, outH).toFloat()
            val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; typeface = typeface(if (c.title) 700 else 650)
                textSize = short * if (c.title) 0.072f else 0.05f
                setShadowLayer(short * 0.012f, 0f, short * 0.003f, 0xB0000000.toInt())
            }
            val sub = Paint(main).apply { typeface = typeface(500); textSize = main.textSize * if (c.title) 0.46f else 0.56f; color = 0xE6FFFFFF.toInt() }
            // Long titles are made smaller to fit.
            val maxW = outW * 0.86f
            while (main.measureText(c.text) > maxW && main.textSize > 8f) main.textSize *= 0.92f
            val subText = c.sub?.let { s -> var t = s; while (sub.measureText(t) > maxW && t.length > 4) t = t.dropLast(2) + "…"; t }
            val pad = (short * 0.03f).toInt()
            val gap = (main.textSize * 0.25f).toInt()
            val r = Rect(); main.getTextBounds("Ag", 0, 2, r)
            val w = (max(main.measureText(c.text), subText?.let { sub.measureText(it) } ?: 0f) + 2 * pad).toInt().coerceAtLeast(1)
            val h = (main.textSize * 1.25f + (if (subText != null) sub.textSize * 1.3f + gap else 0f) + 2 * pad).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val centre = c.title
            fun x(p: Paint, s: String) = if (centre) (w - p.measureText(s)) / 2 else pad.toFloat()
            var y = pad + main.textSize
            cv.drawText(c.text, x(main, c.text), y, main)
            if (subText != null) { y += gap + sub.textSize * 1.15f; cv.drawText(subText, x(sub, subText), y, sub) }
            texture = Gl.upload(bmp)
            bmp.recycle()
            val fw = w.toFloat() / outW; val fh = h.toFloat() / outH
            // Moments' names sit at the bottom left, clear of the edges.
            val margin = short * 0.05f
            dst = if (centre) Frame((1 - fw) / 2, (1 - fh) / 2, fw, fh) else Frame(margin / outW, 1f - fh - margin * 1.2f / outH, fw, fh)
        }

        fun release() = GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    // ------------------------------------------------------------------ encoder
    private fun createEncoder(w: Int, h: Int, bitrate: Int): MediaCodec {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        fun format(rich: Boolean) = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (rich) {
                setInteger(MediaFormat.KEY_PRIORITY, 1)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format(false))
            ?: throw UserFacingException("This device has no H.264 encoder for ${w}×$h.")
        val codec = MediaCodec.createByCodecName(name)
        val caps = codec.codecInfo.getCapabilitiesForType(mime)
        val rich = format(true)
        caps.encoderCapabilities?.let { if (it.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) rich.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) }
        val attempts = ArrayList<MediaFormat>()
        caps.profileLevels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }.maxByOrNull { it.level }?.let { hp ->
            attempts.add(MediaFormat(rich).apply { setInteger(MediaFormat.KEY_PROFILE, hp.profile); setInteger(MediaFormat.KEY_LEVEL, hp.level) })
        }
        attempts.add(rich); attempts.add(format(false))
        var last: Exception? = null
        for (f in attempts) {
            try { codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); return codec } catch (e: Exception) { last = e; try { codec.reset() } catch (_: Exception) { } }
        }
        codec.release()
        throw UserFacingException("The H.264 encoder rejected ${w}×$h.", last)
    }

    // ------------------------------------------------------------------ GL
    /** The few shaders the renderer needs: a picture (bitmap or video frame) into a rectangle, and a black veil. */
    private class Gl {
        val maxTexture: Int = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0].coerceIn(2048, 8192)
        private val p2d = Program(FRAG_2D)
        private val pOes = Program(FRAG_OES)
        private val pFill = Program(FRAG_FILL)
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

        private class Program(frag: String) {
            val id = ExternalTextureRenderer.link(VERTEX, frag)
            val aPos = GLES20.glGetAttribLocation(id, "aPos")
            val uDst = GLES20.glGetUniformLocation(id, "uDst")
            val uTex = GLES20.glGetUniformLocation(id, "uTex")
            val uAlpha = GLES20.glGetUniformLocation(id, "uAlpha")
            val uDim = GLES20.glGetUniformLocation(id, "uDim")
            val uSampler = GLES20.glGetUniformLocation(id, "sTexture")
        }

        /** An offscreen texture to draw into. */
        class Target(val w: Int, val h: Int) {
            val tex: Int; val fbo: Int
            init {
                val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                params(GLES20.GL_TEXTURE_2D)
                val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0); fbo = f[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
            fun bind() { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo); GLES20.glViewport(0, 0, w, h) }
            fun release() { GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); GLES20.glDeleteTextures(1, intArrayOf(tex), 0) }
        }

        // Every shader writes premultiplied colour, so one blend mode composes pictures, captions and the veil.
        /** Draws a bitmap texture (first row at the top; [m] from [Timeline.bitmapMatrix]). */
        fun drawBitmap(tex: Int, m: FloatArray, dst: Frame, alpha: Float, dim: Float) = draw(p2d, GLES20.GL_TEXTURE_2D, tex, m, dst, alpha, dim, true)
        /** Draws a texture rendered by GL itself (origin at the bottom). */
        fun drawTexture(tex: Int, m: FloatArray, dst: Frame, alpha: Float, dim: Float, blend: Boolean = true) = draw(p2d, GLES20.GL_TEXTURE_2D, tex, m, dst, alpha, dim, blend)
        fun drawExternal(tex: Int, m: FloatArray, dst: Frame, alpha: Float, dim: Float, blend: Boolean = true) = draw(pOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex, m, dst, alpha, dim, blend)

        fun fill(alpha: Float) {
            GLES20.glUseProgram(pFill.id)
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUniform4f(pFill.uDst, 0f, 0f, 1f, 1f)
            GLES20.glUniform1f(pFill.uAlpha, alpha)
            GLES20.glUniformMatrix4fv(pFill.uTex, 1, false, Timeline.IDENTITY, 0)
            quadDraw(pFill)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun draw(p: Program, target: Int, tex: Int, m: FloatArray, dst: Frame, alpha: Float, dim: Float, blend: Boolean) {
            if (alpha <= 0f) return
            GLES20.glUseProgram(p.id)
            if (blend) { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) } else GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, tex)
            GLES20.glUniform1i(p.uSampler, 0)
            // Rectangles are given y down; GL's y goes up.
            GLES20.glUniform4f(p.uDst, dst.x, 1f - dst.y - dst.h, dst.w, dst.h)
            GLES20.glUniformMatrix4fv(p.uTex, 1, false, m, 0)
            GLES20.glUniform1f(p.uAlpha, alpha)
            GLES20.glUniform1f(p.uDim, dim)
            quadDraw(p)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun quadDraw(p: Program) {
            quad.position(0)
            GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
            GLES20.glEnableVertexAttribArray(p.aPos)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(p.aPos)
        }

        fun release() { GLES20.glDeleteProgram(p2d.id); GLES20.glDeleteProgram(pOes.id); GLES20.glDeleteProgram(pFill.id) }

        companion object {
            fun params(target: Int) {
                GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }

            /** Uploads a bitmap (premultiplied, first row at t = 0) and returns the texture. */
            fun upload(bmp: Bitmap): Int {
                val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
                params(GLES20.GL_TEXTURE_2D)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                return t[0]
            }

            private const val VERTEX = """
                attribute vec2 aPos;
                uniform vec4 uDst;
                uniform mat4 uTex;
                varying vec2 vTex;
                void main() {
                    vec2 p = uDst.xy + aPos * uDst.zw;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                    vTex = (uTex * vec4(aPos, 0.0, 1.0)).xy;
                }
            """
            // Texture coordinates need high precision: at medium precision, slow zooms over big photos move in visible steps.
            private const val FRAG_2D = """
                #ifdef GL_FRAGMENT_PRECISION_HIGH
                precision highp float;
                #else
                precision mediump float;
                #endif
                varying vec2 vTex;
                uniform sampler2D sTexture;
                uniform float uAlpha;
                uniform float uDim;
                void main() {
                    vec4 c = texture2D(sTexture, vTex);
                    gl_FragColor = vec4(c.rgb * uDim * uAlpha, c.a * uAlpha);
                }
            """
            private const val FRAG_OES = """
                #extension GL_OES_EGL_image_external : require
                #ifdef GL_FRAGMENT_PRECISION_HIGH
                precision highp float;
                #else
                precision mediump float;
                #endif
                varying vec2 vTex;
                uniform samplerExternalOES sTexture;
                uniform float uAlpha;
                uniform float uDim;
                void main() {
                    vec4 c = texture2D(sTexture, vTex);
                    gl_FragColor = vec4(c.rgb * uDim * uAlpha, uAlpha);
                }
            """
            private const val FRAG_FILL = """
                precision mediump float;
                varying vec2 vTex;
                uniform float uAlpha;
                void main() { gl_FragColor = vec4(0.0, 0.0, 0.0, uAlpha); }
            """
        }
    }

    companion object {
        /** How dark the blurred backdrop is drawn. */
        const val BACKDROP_DIM = 0.55f
    }
}
