package com.localmediatools.audio

import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import com.localmediatools.audio.core.AudioTags
import com.localmediatools.audio.core.FlacEncoder
import com.localmediatools.audio.core.Id3
import com.localmediatools.audio.core.Mp3Encoder
import com.localmediatools.audio.core.OggOpusWriter
import com.localmediatools.audio.core.PcmFormat
import com.localmediatools.audio.core.Quantizer
import com.localmediatools.audio.core.WavWriter
import com.localmediatools.core.UserFacingException
import com.localmediatools.i18n.tr
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The audio formats the app writes. */
enum class AudioFormatOut(val ext: String, val mime: String) {
    MP3("mp3", "audio/mpeg"),
    AAC("m4a", "audio/mp4"),
    OPUS("opus", "audio/ogg"),
    FLAC("flac", "audio/flac"),
    WAV("wav", "audio/x-wav");

    val label: String get() = when (this) { MP3 -> "MP3"; AAC -> "M4A (AAC)"; OPUS -> "Opus"; FLAC -> "FLAC"; WAV -> "WAV" }
    val lossless: Boolean get() = this == FLAC || this == WAV

    /** Bit rates offered (kbps), for the lossy formats. */
    val bitrates: List<Int> get() = when (this) {
        MP3 -> listOf(96, 128, 160, 192, 256, 320)
        AAC -> listOf(64, 96, 128, 160, 192, 256)
        OPUS -> listOf(48, 64, 96, 128, 160, 192)
        else -> emptyList()
    }
    val defaultKbps: Int get() = when (this) { MP3 -> 192; AAC -> 160; OPUS -> 128; else -> 0 }

    /** The sample rate this format will be written at for a source at [src] Hz. */
    fun outRate(src: Int): Int = when (this) {
        MP3 -> when {
            src % 11025 == 0 -> 44100
            src % 8000 == 0 && src <= 32000 -> 32000
            else -> 48000
        }
        AAC -> when {
            src in AAC_RATES -> src
            src > 48000 -> if (src % 11025 == 0) 44100 else 48000
            else -> AAC_RATES.first { it >= src }
        }
        OPUS -> 48000
        FLAC, WAV -> src
    }

    companion object {
        private val AAC_RATES = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

        /** The app's format closest to a source codec (for "same as the original"). */
        fun like(info: AudioInfo): AudioFormatOut = when (info.mime) {
            "audio/mpeg" -> MP3
            "audio/flac" -> FLAC
            "audio/wav", "audio/raw" -> WAV
            "audio/opus", "audio/vorbis" -> OPUS
            else -> AAC
        }
    }
}

/**
 * What to write: the format, its bit rate (lossy formats) or sample size (FLAC: 16 or 24 bits; WAV:
 * 16, 24 or 32 = float), the channels (0 = as the source, at most two) and the sample rate (0 = as
 * the source, where the format allows).
 */
data class AudioOutput(
    val format: AudioFormatOut,
    val kbps: Int = format.defaultKbps,
    val bits: Int = 16,
    val channels: Int = 0,
    val rate: Int = 0,
) {
    fun rateFor(src: Int): Int = format.outRate(if (rate > 0) rate else src)
    fun channelsFor(src: Int): Int = if (channels in 1..2) channels else src.coerceIn(1, 2)

    /** A short description: "MP3 192 kbps", "FLAC 24-bit"… */
    val label: String get() = when {
        format.lossless -> "${format.label} " + if (format == AudioFormatOut.WAV && bits == 32) tr("32-bit float") else tr("{0}-bit", bits)
        else -> "${format.label} $kbps kbps"
    }

    companion object {
        /** The same kind of file as [info] at a comparable quality (never below the original's bit rate, where it is known). */
        fun sameAs(info: AudioInfo): AudioOutput {
            val f = AudioFormatOut.like(info)
            if (f.lossless) return AudioOutput(f, bits = if (info.codec.contains("24") || info.codec.contains("32")) 24 else 16)
            val src = info.bitrate / 1000
            val kbps = if (src <= 0) f.defaultKbps else f.bitrates.firstOrNull { it >= src } ?: f.bitrates.last()
            return AudioOutput(f, kbps = kbps)
        }
    }
}

/** Where a format writes the samples it gets (interleaved floats at [rate] Hz, [channels] channels). */
abstract class AudioSink(val rate: Int, val channels: Int) {
    var frames = 0L; protected set
    abstract fun write(x: FloatArray)
    /** Ends the file (headers completed). */
    abstract fun finish()
    /** Releases everything after a failure. */
    open fun close() {}

    companion object {
        fun open(pfd: ParcelFileDescriptor, out: AudioOutput, rate: Int, channels: Int, tags: AudioTags, sourceRate: Int = rate): AudioSink = when (out.format) {
            AudioFormatOut.MP3 -> Mp3Sink(pfd, rate, channels, out.kbps, tags)
            AudioFormatOut.FLAC -> FlacSink(pfd, rate, channels, if (out.bits >= 24) 24 else 16, tags)
            AudioFormatOut.WAV -> WavSink(pfd, rate, channels, when { out.bits >= 32 -> PcmFormat.F32; out.bits >= 24 -> PcmFormat.S24; else -> PcmFormat.S16 })
            AudioFormatOut.AAC -> AacSink(pfd, rate, channels, out.kbps)
            AudioFormatOut.OPUS -> OpusSink(pfd, channels, out.kbps, tags, sourceRate)
        }
    }
}

/** A file descriptor written in order, with earlier bytes rewritten at the end (headers). */
private class FdOut(pfd: ParcelFileDescriptor) : OutputStream() {
    private val fos = FileOutputStream(pfd.fileDescriptor)
    private val channel = fos.channel
    private val buf = BufferedOutputStream(fos, 1 shl 16)
    override fun write(b: Int) = buf.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
    override fun flush() = buf.flush()
    fun rewrite(pos: Long, bytes: ByteArray) {
        buf.flush()
        val end = channel.position()
        channel.position(pos); channel.write(ByteBuffer.wrap(bytes)); channel.position(end)
    }
    fun end() { buf.flush(); channel.truncate(channel.position()); fos.fd.sync() }
}

private class Mp3Sink(pfd: ParcelFileDescriptor, rate: Int, channels: Int, kbps: Int, tags: AudioTags) : AudioSink(rate, channels) {
    private val out = FdOut(pfd)
    private val tag = Id3.v24(tags).also { out.write(it) }
    private val enc = Mp3Encoder(out, rate, channels, kbps)
    override fun write(x: FloatArray) { enc.add(x); frames += x.size / channels }
    override fun finish() { enc.finish { pos, b -> out.rewrite(tag.size + pos, b) }; out.end() }
}

private class FlacSink(pfd: ParcelFileDescriptor, rate: Int, channels: Int, bits: Int, tags: AudioTags) : AudioSink(rate, channels) {
    private val out = FdOut(pfd)
    private val enc = FlacEncoder(out, rate, channels, bits, comments = tags.vorbisComments(), picture = picture(tags))
    private val q = Quantizer(bits, dither = bits == 16)
    override fun write(x: FloatArray) {
        enc.add(IntArray(x.size) { q.quantize(x[it]) })
        frames += x.size / channels
    }
    override fun finish() { val info = enc.finish(); out.rewrite(enc.streamInfoOffset, info); out.end() }
}

private class WavSink(pfd: ParcelFileDescriptor, rate: Int, channels: Int, format: PcmFormat) : AudioSink(rate, channels) {
    private val out = FdOut(pfd)
    private val w = WavWriter(out, rate, channels, format)
    override fun write(x: FloatArray) {
        if ((w.frames + x.size / channels) * channels * (w.format.bits / 8) > WavWriter.MAX_DATA) throw UserFacingException(tr("WAV files can't be larger than 4 GB. Choose FLAC for very long recordings."))
        w.add(x); frames += x.size / channels
    }
    override fun finish() { out.rewrite(0, w.finish()); out.end() }
}

/** The cover as a FLAC picture with its size (players show it either way, but the standard asks for it). */
private fun picture(tags: AudioTags): FlacEncoder.Picture? {
    val c = tags.cover ?: return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try { BitmapFactory.decodeByteArray(c, 0, c.size, o) } catch (_: Exception) { }
    return tags.flacPicture(o.outWidth.coerceAtLeast(0), o.outHeight.coerceAtLeast(0))
}

/** Feeds 16-bit PCM to a MediaCodec encoder and hands each finished packet to [packet]. */
private class CodecFeeder(private val codec: MediaCodec, private val channels: Int, private val rate: Int, private val packet: (ByteBuffer, MediaCodec.BufferInfo) -> Unit, private val format: (MediaFormat) -> Unit) {
    private val bi = MediaCodec.BufferInfo()
    private val q = Quantizer(16, dither = true)
    private var pts = 0L
    private var pending = ShortArray(0)
    private var pendingN = 0

    fun write(x: FloatArray) {
        if (pending.size < pendingN + x.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingN + x.size))
        for (v in x) pending[pendingN++] = q.quantize(v).toShort()
        var at = 0
        while (pendingN - at > 0) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i < 0) { drain(false); continue }
            val buf = codec.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
            buf.clear()
            val n = minOf(pendingN - at, buf.remaining() / 2) / channels * channels
            buf.asShortBuffer().put(pending, at, n)
            codec.queueInputBuffer(i, 0, n * 2, pts * 1_000_000L / rate, 0)
            pts += n / channels; at += n
            drain(false)
        }
        pendingN = 0
    }

    fun end() {
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) { codec.queueInputBuffer(i, 0, 0, pts * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            drain(false)
        }
        drain(true)
    }

    private fun drain(untilEnd: Boolean) {
        var idle = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(bi, if (untilEnd) 10_000 else 0)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format(codec.outputFormat)
                o >= 0 -> {
                    idle = 0
                    val b = codec.getOutputBuffer(o)!!
                    b.position(bi.offset); b.limit(bi.offset + bi.size)
                    if (bi.size > 0) packet(b, bi)
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(o, false)
                    if (eos) return
                }
                else -> { if (!untilEnd) return; if (++idle > 500) throw UserFacingException(tr("The sound encoder stopped responding.")) }
            }
        }
    }
}

/** AAC-LC in an .m4a file, by the phone's encoder. */
private class AacSink(pfd: ParcelFileDescriptor, rate: Int, channels: Int, kbps: Int) : AudioSink(rate, channels) {
    private val codec: MediaCodec
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private val feeder: CodecFeeder

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        codec = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { it.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); it.start() } }
            catch (e: Exception) { muxer.release(); throw UserFacingException(tr("This phone's AAC encoder can't write {0} Hz, {1} channels.", rate, channels)) }
        feeder = CodecFeeder(codec, channels, rate, { b, info ->
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) muxer.writeSampleData(track, b, info)
        }, { f -> if (track < 0) { track = muxer.addTrack(f); muxer.start() } })
    }

    override fun write(x: FloatArray) { feeder.write(x); frames += x.size / channels }
    override fun finish() {
        feeder.end()
        if (track < 0) throw UserFacingException(tr("The AAC encoder gave no sound."))
        muxer.stop(); close()
    }
    override fun close() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { muxer.release() } catch (_: Exception) { }
    }
}

/** Opus in an Ogg file (.opus), by the phone's encoder, at 48 kHz. */
private class OpusSink(pfd: ParcelFileDescriptor, channels: Int, kbps: Int, private val tags: AudioTags, private val sourceRate: Int) : AudioSink(48000, channels) {
    private val out = FdOut(pfd)
    private val codec: MediaCodec
    private var writer: OggOpusWriter? = null
    private var preSkip = 312
    private val feeder: CodecFeeder
    private val early = ArrayList<ByteArray>()

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        codec = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also { it.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); it.start() } }
            catch (e: Exception) { throw UserFacingException(tr("This phone has no Opus encoder. Choose M4A (AAC) or MP3 instead.")) }
        feeder = CodecFeeder(codec, channels, 48000, { b, info ->
            val bytes = ByteArray(b.remaining()).also { b.get(it) }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) preSkip = preSkipOf(bytes) ?: preSkip
            else writerOrQueue(bytes)
        }, { f -> f.getByteBuffer("csd-0")?.let { bb -> val a = ByteArray(bb.remaining()).also { bb.duplicate().get(it) }; preSkip = preSkipOf(a) ?: preSkip } })
    }

    private fun writerOrQueue(packet: ByteArray) {
        val w = writer ?: run {
            val comments = tags.vorbisComments().toMutableList()
            picture(tags)?.let { comments.add("METADATA_BLOCK_PICTURE" to java.util.Base64.getEncoder().encodeToString(pictureBlock(it))) }
            OggOpusWriter(out, channels, preSkip, sourceRate, comments).also { writer = it }
        }
        w.addPacket(packet)
    }

    override fun write(x: FloatArray) { feeder.write(x); frames += x.size / channels }
    override fun finish() {
        feeder.end()
        val w = writer ?: throw UserFacingException(tr("The Opus encoder gave no sound."))
        w.finish(samples48k = frames)
        out.end(); close()
    }
    override fun close() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
    }

    companion object {
        /** The encoder's delay from its header: an OpusHead's pre-skip, or Android's "AOPUSDLY" (nanoseconds). */
        fun preSkipOf(b: ByteArray): Int? {
            val s = String(b, Charsets.ISO_8859_1)
            val h = s.indexOf("OpusHead")
            if (h >= 0 && h + 12 <= b.size) return (b[h + 10].toInt() and 0xFF) or ((b[h + 11].toInt() and 0xFF) shl 8)
            val d = s.indexOf("AOPUSDLY")
            if (d >= 0 && d + 24 <= b.size) {
                var ns = 0L; for (k in 0 until 8) ns = ns or ((b[d + 16 + k].toLong() and 0xFF) shl (8 * k))
                return (ns * 48000 / 1_000_000_000L).toInt().takeIf { it in 0..10_000 }
            }
            return null
        }

        fun pictureBlock(p: FlacEncoder.Picture): ByteArray {
            val b = java.io.ByteArrayOutputStream()
            fun be32(v: Int) { b.write(v ushr 24); b.write(v ushr 16); b.write(v ushr 8); b.write(v) }
            val mime = p.mime.toByteArray(); val desc = p.description.toByteArray()
            be32(p.type); be32(mime.size); b.write(mime); be32(desc.size); b.write(desc)
            be32(p.width); be32(p.height); be32(24); be32(0); be32(p.data.size); b.write(p.data)
            return b.toByteArray()
        }
    }
}
