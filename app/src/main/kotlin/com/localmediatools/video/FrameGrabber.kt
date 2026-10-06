package com.localmediatools.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES20
import android.os.Build
import com.localmediatools.core.UserFacingException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes video frames and hands them out as display-oriented ARGB pixels at a target size.
 * Decoding goes through the GPU (SurfaceTexture → offscreen buffer), which converts any decoder
 * output format (8/10-bit, any colour standard) consistently. Frames that aren't wanted are
 * dropped without being rendered.
 */
class FrameGrabber(
    private val ctx: Context,
    private val uri: Uri,
    private val info: VideoInfo,
    /** Display-oriented output size. */
    val outWidth: Int,
    val outHeight: Int,
) {
    /** Receives a frame; return false to stop decoding early. */
    fun interface FrameSink {
        fun frame(argb: IntArray, ptsUs: Long): Boolean
    }

    /**
     * Decodes from [startUs] to [endUs]. [want] decides from a frame's timestamp whether it should
     * be rendered and delivered (rendering is the expensive part).
     */
    fun run(startUs: Long, endUs: Long, operatingRate: Int?, want: (Long) -> Boolean, sink: FrameSink, throttle: () -> Unit) {
        val v = info.video ?: throw UserFacingException("The file has no video track.")
        val ex = VideoProbe.extractor(ctx, uri)
        ex.selectTrack(v.index)
        if (startUs > 0) ex.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val egl = EglEnv(null, outWidth, outHeight)
        val renderer = ExternalTextureRenderer()
        val surface = DecoderOutputSurface(renderer.textureId)
        var decoder: MediaCodec? = null
        val pixels = ByteBuffer.allocateDirect(outWidth * outHeight * 4).order(ByteOrder.nativeOrder())
        val argb = IntArray(outWidth * outHeight)
        try {
            val fmt = MediaFormat(v.format)
            fmt.setInteger(MediaFormat.KEY_ROTATION, 0)
            if (info.hdr && Build.VERSION.SDK_INT >= 33) {
                fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 1)
            operatingRate?.let { fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, it) }
            val name = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(v.format) } catch (_: Exception) { null }
            val mime = v.mime
            val dec = try { if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime) }
            catch (e: Exception) { throw UserFacingException("This device has no decoder for ${v.codecLabel} video.", e) }
            decoder = dec
            try {
                dec.configure(fmt, surface.surface, null, 0)
            } catch (e: Exception) {
                dec.reset()
                dec.configure(MediaFormat(v.format).apply { setInteger(MediaFormat.KEY_ROTATION, 0) }, surface.surface, null, 0)
            }
            dec.start()
            val bi = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastActivity = System.currentTimeMillis()
            var n = 0
            while (!outputDone) {
                var moved = false
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val size = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (size < 0 || (t > endUs + 1_000_000 && (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0)) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, t, 0)
                            ex.advance()
                        }
                        moved = true
                    }
                }
                val idx = dec.dequeueOutputBuffer(bi, 10_000)
                if (idx >= 0) {
                    moved = true
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val pts = bi.presentationTimeUs
                    val inRange = bi.size != 0 && pts >= startUs && pts <= endUs
                    val render = inRange && want(pts)
                    dec.releaseOutputBuffer(idx, render)
                    if (render) {
                        if (!surface.awaitFrame()) throw UserFacingException("The video decoder stopped delivering frames.")
                        renderer.draw(surface.stMatrix, outWidth, outHeight, info.rotation, flipY = true)
                        pixels.clear()
                        GLES20.glReadPixels(0, 0, outWidth, outHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                        pixels.rewind()
                        // RGBA bytes → ARGB ints.
                        val ib = pixels.asIntBuffer()
                        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                        for (i in argb.indices) {
                            val c = ib.get(i)
                            argb[i] = if (little) {
                                (0xFF shl 24) or ((c and 0xFF) shl 16) or (c and 0xFF00) or ((c shr 16) and 0xFF)
                            } else {
                                (0xFF shl 24) or (c ushr 8)
                            }
                        }
                        if (!sink.frame(argb, pts)) outputDone = true
                    }
                    if (eos || pts > endUs) outputDone = true
                    if (++n % 10 == 0) throttle()
                }
                if (moved) lastActivity = System.currentTimeMillis()
                else if (System.currentTimeMillis() - lastActivity > 15_000) throw UserFacingException("Video decoding stalled.")
            }
        } finally {
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            try { surface.release() } catch (_: Exception) { }
            try { renderer.release() } catch (_: Exception) { }
            try { egl.release() } catch (_: Exception) { }
            ex.release()
        }
    }
}
