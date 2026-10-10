package com.localmediatools.highlight

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.localmediatools.codec.audio.PcmProcessor
import com.localmediatools.highlight.core.Mixer
import com.localmediatools.highlight.core.Plan
import com.localmediatools.music.core.Composer
import com.localmediatools.music.core.Mood
import com.localmediatools.music.core.Score
import com.localmediatools.music.core.Synth
import com.localmediatools.music.core.TrioModel
import java.lang.ref.SoftReference
import java.nio.ByteOrder

/** AAC audio, encoded and waiting to be written next to the video. */
class EncodedAudio(val format: MediaFormat, val packets: List<Packet>) {
    class Packet(val data: ByteArray, val ptsUs: Long, val flags: Int)
}

/**
 * The highlight video's sound, made on the phone: background music written for this video by the
 * music model ([Composer], [Synth]: instrumental only), the clips' own sound, and the mix with the
 * music stepped back while a clip speaks ([Mixer]), encoded to AAC.
 */
object HighlightAudio {
    const val RATE = 48_000
    private var model: SoftReference<TrioModel>? = null

    @Synchronized fun model(ctx: Context): TrioModel =
        model?.get() ?: ctx.assets.open("models/trio_decoder.bin").buffered(1 shl 16).use { TrioModel.load(it) }.also { model = SoftReference(it) }

    fun score(ctx: Context, mood: Mood, seconds: Double, seed: Long): Score = Composer.compose(model(ctx), mood, seconds, seed)

    /** The music alone, [seconds] long (for listening before the export). */
    fun music(ctx: Context, mood: Mood, seconds: Double, seed: Long): FloatArray = Synth(RATE).render(score(ctx, mood, seconds, seed), seconds)

    /**
     * Everything for [plan]: music in [mood] (null = none) and, for clips that keep their sound, that
     * sound. Null when the video has no sound at all.
     */
    fun build(ctx: Context, plan: Plan, mood: Mood?, seed: Long, uris: (Int) -> Uri, progress: (Double) -> Unit, cancelled: () -> Boolean): EncodedAudio? {
        val sounded = plan.clips.filter { it.withSound }
        if (mood == null && sounded.isEmpty()) return null
        val seconds = plan.durationMs / 1000.0
        val frames = (plan.durationMs * RATE / 1000).toInt().coerceAtLeast(1)
        val music = mood?.let { music(ctx, it, seconds, seed) }
        progress(0.4)
        val parts = ArrayList<Mixer.Part>()
        for ((k, c) in plan.clips.withIndex()) {
            if (!c.withSound) continue
            if (cancelled()) return null
            val pcm = try { clipSound(ctx, uris(c.shot.id), c.sourceStartMs, c.sourceEndMs) } catch (t: Throwable) {
                android.util.Log.w("LMT", "highlight: sound of clip $k unavailable", t); null
            } ?: continue
            val next = plan.clips.getOrNull(k + 1)
            val overlap = if (next != null && next.startMs < c.endMs) c.endMs - next.startMs else 0L
            parts.add(Mixer.Part((c.startMs * RATE / 1000).toInt(), pcm, Mixer.levelGain(pcm),
                fadeInFrames = (c.fadeInMs * RATE / 1000).toInt(), fadeOutFrames = (overlap * RATE / 1000).toInt()))
            progress(0.4 + 0.3 * (k + 1) / plan.clips.size)
        }
        val mix = Mixer.mix(music, 1f, frames, RATE, parts, plan.soundIntervals(), fadeInMs = 0, fadeOutMs = plan.fadeOutMs)
        progress(0.75)
        return encode(mix, cancelled).also { progress(1.0) }
    }

    /** A video's sound from [fromMs] to [toMs] as 48 kHz stereo floats. */
    fun clipSound(ctx: Context, uri: Uri, fromMs: Long, toMs: Long): FloatArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            val wanted = ((toMs - fromMs) * RATE / 1000).toInt()
            val out = FloatArray(wanted * 2)
            var written = 0
            try {
                dec.configure(fmt, null, null, 0); dec.start()
                var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else RATE
                var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                var float = false
                var proc: PcmProcessor? = null
                fun put(s: ShortArray) {
                    val n = minOf(s.size, out.size - written * 2) / 2
                    for (i in 0 until n) { out[2 * (written + i)] = s[2 * i] / 32768f; out[2 * (written + i) + 1] = s[2 * i + 1] / 32768f }
                    written += n
                }
                val bi = MediaCodec.BufferInfo()
                var inDone = false; var outDone = false; var idle = 0
                while (!outDone && written < wanted && idle < 400) {
                    if (!inDone) {
                        val i = dec.dequeueInputBuffer(5_000)
                        if (i >= 0) {
                            val size = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                            if (size < 0 || ex.sampleTime > toMs * 1000 + 200_000) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(bi, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        proc = null
                    } else if (o >= 0) {
                        idle = 0
                        if (bi.size > 0) {
                            val buf = dec.getOutputBuffer(o)!!
                            buf.position(bi.offset); buf.limit(bi.offset + bi.size)
                            val bb = buf.slice().order(ByteOrder.nativeOrder())
                            var pcm = if (float) { val fb = bb.asFloatBuffer(); ShortArray(fb.remaining()) { (fb.get(it) * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() } }
                                else { val sb = bb.asShortBuffer(); ShortArray(sb.remaining()).also { sb.get(it) } }
                            // Drop what comes before the clip's first moment (decoding starts at a sync point before it).
                            val ch = channels.coerceAtLeast(1)
                            val startUs = bi.presentationTimeUs
                            val skip = if (startUs < fromMs * 1000) (((fromMs * 1000 - startUs) * rate / 1_000_000).toInt() * ch).coerceAtMost(pcm.size) else 0
                            if (skip > 0) pcm = pcm.copyOfRange(skip, pcm.size)
                            if (pcm.isNotEmpty()) {
                                val p = proc ?: PcmProcessor(rate, ch, RATE, 2).also { proc = it }
                                put(p.process(pcm))
                            }
                        }
                        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        dec.releaseOutputBuffer(o, false)
                    } else idle++
                }
                proc?.let { put(it.flush()) }
            } finally {
                try { dec.stop() } catch (_: Exception) { }
                dec.release()
            }
            return if (written == 0) null else if (written < wanted) out.copyOf(written * 2) else out
        } finally { ex.release() }
    }

    /** Stereo floats at [RATE] → AAC-LC packets. */
    fun encode(pcm: FloatArray, cancelled: () -> Boolean = { false }): EncodedAudio {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val packets = ArrayList<EncodedAudio.Packet>()
        var outFormat: MediaFormat? = null
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
            val bi = MediaCodec.BufferInfo()
            val total = pcm.size / 2
            var frame = 0
            var inDone = false; var outDone = false; var idle = 0
            while (!outDone && idle < 1000) {
                if (cancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
                if (!inDone) {
                    val i = enc.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val dst = enc.getInputBuffer(i)!!
                        dst.clear()
                        val n = minOf(total - frame, dst.remaining() / 4)
                        if (n > 0) dst.put(Mixer.pcm16(pcm, frame * 2, (frame + n) * 2))
                        val pts = frame * 1_000_000L / RATE
                        frame += n
                        val eos = frame >= total
                        enc.queueInputBuffer(i, 0, n * 4, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (eos) inDone = true
                    }
                }
                val o = enc.dequeueOutputBuffer(bi, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = enc.outputFormat
                else if (o >= 0) {
                    idle = 0
                    if (bi.size > 0 && bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val b = enc.getOutputBuffer(o)!!
                        b.position(bi.offset); b.limit(bi.offset + bi.size)
                        packets.add(EncodedAudio.Packet(ByteArray(bi.size).also { b.get(it) }, bi.presentationTimeUs, bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()))
                    }
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    enc.releaseOutputBuffer(o, false)
                } else idle++
            }
        } finally {
            try { enc.stop() } catch (_: Exception) { }
            enc.release()
        }
        return EncodedAudio(outFormat ?: throw IllegalStateException("the AAC encoder gave no format"), packets)
    }
}
