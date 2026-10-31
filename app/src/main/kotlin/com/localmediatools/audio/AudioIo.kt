package com.localmediatools.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.localmediatools.audio.core.AudioTags
import com.localmediatools.audio.core.Pcm
import com.localmediatools.audio.core.PcmFileReader
import com.localmediatools.audio.core.SincResampler
import com.localmediatools.core.UserFacingException
import com.localmediatools.i18n.tr
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteOrder

/** What an audio (or video) file's sound is. */
data class AudioInfo(
    val durationUs: Long,
    val rate: Int,
    val channels: Int,
    /** "MP3", "AAC", "FLAC", "WAV (24-bit)"… */
    val codec: String,
    val mime: String,
    /** Bits per second; 0 when not known. */
    val bitrate: Int,
    val tags: AudioTags,
    /** A video file (its sound is used). */
    val video: Boolean,
    /** Read by the app itself (WAV, AIFF), not by Android's decoders. */
    val pcmFile: Boolean,
) {
    val frames: Long get() = durationUs * rate / 1_000_000
}

object AudioProbe {
    /** Stands in for Android's media reading where it can't run (UI tests), for files the app doesn't read itself. */
    @Volatile var override: ((Uri) -> AudioInfo?)? = null

    fun probe(ctx: Context, uri: Uri): AudioInfo {
        pcm(ctx, uri)?.let { return it }
        override?.invoke(uri)?.let { return it }
        val ex = MediaExtractor()
        try {
            try { ex.setDataSource(ctx, uri, null) } catch (e: Exception) { throw UserFacingException(tr("This file couldn't be read as audio.")) }
            var video = false
            var track = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) video = true
                if (m.startsWith("audio/") && track < 0) track = i
            }
            if (track < 0) throw UserFacingException(if (video) tr("This video has no sound.") else tr("This file has no sound that can be read."))
            val f = ex.getTrackFormat(track)
            val mime = f.getString(MediaFormat.KEY_MIME)!!
            val rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            var br = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE) else 0
            val tags = tags(ctx, uri)
            val mmr = tags.second
            if (dur <= 0) dur = mmr.first
            if (br <= 0 && !video && dur > 0) br = (mmr.second * 8_000_000L / dur).toInt().takeIf { it in 8_000..3_000_000 } ?: 0
            return AudioInfo(dur, rate, ch, codecLabel(mime), mime, br, tags.first, video, false)
        } finally { ex.release() }
    }

    private fun pcm(ctx: Context, uri: Uri): AudioInfo? {
        val r = try { ctx.contentResolver.openInputStream(uri)?.buffered(1 shl 16)?.use { PcmFileReader.open(it)?.let { p -> Triple(p.rate, p.channels, p.frames to p.bits) } } } catch (_: Exception) { null } ?: return null
        val (rate, ch, fb) = r
        val frames = if (fb.first >= 0) fb.first else 0
        val size = try { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1 } catch (_: Exception) { -1L }
        val dur = if (frames > 0) frames * 1_000_000 / rate else if (size > 0) size * 8_000_000 / (rate.toLong() * ch * fb.second) else 0
        return AudioInfo(dur, rate, ch, tr("WAV/AIFF ({0}-bit)", fb.second), "audio/wav", rate * ch * fb.second, AudioTags(), video = false, pcmFile = true)
    }

    /** The file's tags and cover, with (duration µs, size bytes) from the metadata reader. */
    private fun tags(ctx: Context, uri: Uri): Pair<AudioTags, Pair<Long, Long>> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            fun k(key: Int) = try { mmr.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
            val year = k(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: k(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)?.takeIf { it.all(Char::isDigit) }
            val t = AudioTags(
                title = k(MediaMetadataRetriever.METADATA_KEY_TITLE), artist = k(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = k(MediaMetadataRetriever.METADATA_KEY_ALBUM), albumArtist = k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                year = year, track = k(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER), genre = k(MediaMetadataRetriever.METADATA_KEY_GENRE),
                cover = try { mmr.embeddedPicture?.takeIf { it.size < 8 shl 20 } } catch (_: Exception) { null },
            )
            val dur = (k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            val size = try { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1 } catch (_: Exception) { -1L }
            t to (dur to size)
        } catch (_: Exception) { AudioTags() to (0L to -1L) } finally { try { mmr.release() } catch (_: Exception) { } }
    }

    fun codecLabel(mime: String): String = when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        "audio/flac" -> "FLAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/raw" -> "PCM"
        "audio/3gpp" -> "AMR-NB"
        "audio/amr-wb" -> "AMR-WB"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/g711-alaw", "audio/g711-mlaw" -> "G.711"
        else -> mime.removePrefix("audio/").uppercase(java.util.Locale.ROOT)
    }
}

/**
 * Decodes a file's sound to float samples (interleaved, −1…1) from [startUs] to [endUs], exactly
 * (decoding starts at the sync point before and drops what comes first). WAV and AIFF are read by
 * the app itself; everything else (MP3, AAC, FLAC, Ogg, Opus, AMR, the sound of videos) by Android's
 * decoders. [rate] and [channels] stay as they were at the start even if the decoder changes them.
 */
class AudioReader(private val ctx: Context, private val uri: Uri, private val startUs: Long = 0, private val endUs: Long = Long.MAX_VALUE) : Closeable {
    var rate = 0; private set
    var channels = 0; private set
    /** How far the reading has got (µs from the file's start). */
    @Volatile var positionUs = startUs; private set

    private var pcm: PcmFileReader? = null
    private var pcmStream: InputStream? = null
    private var pcmLeft = Long.MAX_VALUE
    private var ex: MediaExtractor? = null
    private var dec: MediaCodec? = null
    private var first: FloatArray? = null
    private var done = false
    private var float = false
    private var decRate = 0; private var decCh = 0
    private var convert: SincResampler? = null
    private var inDone = false
    private var outEnded = false
    private val bi = MediaCodec.BufferInfo()

    init {
        val s = try { ctx.contentResolver.openInputStream(uri)?.buffered(1 shl 16) } catch (_: Exception) { null }
        val p = s?.let { PcmFileReader.open(it) }
        if (p != null) {
            pcm = p; pcmStream = s; rate = p.rate; channels = p.channels
            var skip = startUs * rate / 1_000_000
            val buf = FloatArray(8192 * channels)
            while (skip > 0) { val n = p.read(if (skip * channels < buf.size) FloatArray((skip * channels).toInt()) else buf); if (n == 0) break; skip -= n }
            if (endUs != Long.MAX_VALUE) pcmLeft = ((endUs - startUs) * rate / 1_000_000).coerceAtLeast(0)
        } else {
            try { s?.close() } catch (_: Exception) { }
            openCodec()
        }
    }

    private fun openCodec() {
        val e = MediaExtractor()
        ex = e
        try { e.setDataSource(ctx, uri, null) } catch (x: Exception) { throw UserFacingException(tr("This file couldn't be read as audio.")) }
        val track = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw UserFacingException(tr("This file has no sound that can be read."))
        e.selectTrack(track)
        val fmt = e.getTrackFormat(track)
        if (startUs > 0) e.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        decRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        decCh = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val d = try { MediaCodec.createDecoderByType(mime) } catch (x: Exception) {
            throw UserFacingException(tr("This phone can't decode {0} sound.", AudioProbe.codecLabel(mime)))
        }
        dec = d
        try {
            fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            d.configure(fmt, null, null, 0)
        } catch (_: Exception) {
            fmt.removeKey(MediaFormat.KEY_PCM_ENCODING)
            d.configure(fmt, null, null, 0)
        }
        d.start()
        // The first samples settle the rate and channels.
        first = decodeNext()
        rate = decRate.coerceAtLeast(1); channels = decCh.coerceIn(1, 8)
    }

    /** The next samples, or null at the end. */
    fun read(): FloatArray? {
        if (done) return null
        pcm?.let { p ->
            if (pcmLeft <= 0) { done = true; return null }
            val buf = FloatArray((minOf(8192L, pcmLeft) * channels).toInt())
            val n = p.read(buf)
            if (n == 0) { done = true; return null }
            pcmLeft -= n
            positionUs += n * 1_000_000L / rate
            return if (n * channels == buf.size) buf else buf.copyOf(n * channels)
        }
        first?.let { first = null; return it }
        return decodeNext().also { if (it == null) done = true }
    }

    private fun decodeNext(): FloatArray? {
        val d = dec ?: return null
        if (outEnded) return null
        val e = ex!!
        var idle = 0
        while (idle < 2000) {
            if (!inDone) {
                val i = d.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val size = e.readSampleData(d.getInputBuffer(i)!!, 0)
                    if (size < 0 || (endUs != Long.MAX_VALUE && e.sampleTime > endUs + 500_000)) { d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { d.queueInputBuffer(i, 0, size, e.sampleTime, 0); e.advance() }
                }
            }
            val o = d.dequeueOutputBuffer(bi, 5_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = d.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) decRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) decCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (rate > 0 && decRate != rate) convert = SincResampler(decRate, rate, channels) else if (decRate == rate) convert = null
                }
                o >= 0 -> {
                    idle = 0
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var out: FloatArray? = null
                    if (bi.size > 0) {
                        val buf = d.getOutputBuffer(o)!!
                        buf.position(bi.offset); buf.limit(bi.offset + bi.size)
                        val bb = buf.slice().order(ByteOrder.nativeOrder())
                        var x = if (float) FloatArray(bb.remaining() / 4).also { bb.asFloatBuffer().get(it) }
                            else { val sb = bb.asShortBuffer(); FloatArray(sb.remaining()) { sb.get(it) / 32768f } }
                        val ch = decCh.coerceAtLeast(1)
                        val ptsUs = bi.presentationTimeUs
                        val frames = x.size / ch
                        // Drop what's before the start and after the end.
                        val from = if (ptsUs < startUs) ((startUs - ptsUs) * decRate / 1_000_000).toInt().coerceIn(0, frames) else 0
                        val to = if (endUs != Long.MAX_VALUE) ((endUs - ptsUs) * decRate / 1_000_000).coerceIn(0, frames.toLong()).toInt() else frames
                        if (to > from) {
                            if (from > 0 || to < frames) x = x.copyOfRange(from * ch, to * ch)
                            positionUs = ptsUs + to * 1_000_000L / decRate
                            if (rate > 0 && ch != channels) x = Pcm.channels(x, x.size / ch, ch, channels)
                            convert?.let { x = it.process(x) }
                            out = x
                        } else if (endUs != Long.MAX_VALUE && ptsUs >= endUs) {
                            d.releaseOutputBuffer(o, false)
                            return null
                        }
                    }
                    d.releaseOutputBuffer(o, false)
                    if (eos) {
                        outEnded = true
                        val tail = convert?.flush()
                        convert = null
                        val all = if (tail != null && tail.isNotEmpty()) SincResampler.concat(out ?: FloatArray(0), tail) else out
                        return all?.takeIf { it.isNotEmpty() }
                    }
                    if (out != null && out.isNotEmpty()) return out
                }
                else -> idle++
            }
        }
        throw UserFacingException(tr("The sound decoder stopped responding."))
    }

    override fun close() {
        try { pcmStream?.close() } catch (_: Exception) { }
        dec?.let { try { it.stop() } catch (_: Exception) { }; try { it.release() } catch (_: Exception) { } }
        try { ex?.release() } catch (_: Exception) { }
        dec = null; ex = null
    }
}
