package com.localmediatools.audio

import android.content.Context
import android.net.Uri
import com.localmediatools.audio.core.AudioTags
import com.localmediatools.audio.core.LevelMeter
import com.localmediatools.audio.core.LoudnessMeter
import com.localmediatools.audio.core.Pcm
import com.localmediatools.audio.core.SincResampler
import com.localmediatools.core.OutputFile
import com.localmediatools.core.OutputArea
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.JobContext
import com.localmediatools.i18n.tr
import com.localmediatools.tools.Outputs
import java.io.Closeable

/** A file's sound at [rate] Hz and [channels] channels (resampled and mixed as needed), piece by piece. */
class AudioStream(ctx: Context, uri: Uri, val rate: Int, val channels: Int, startUs: Long = 0, endUs: Long = Long.MAX_VALUE) : Closeable {
    private val reader = AudioReader(ctx, uri, startUs, endUs)
    private val resampler = if (reader.rate != rate) SincResampler(reader.rate, rate, channels) else null
    private var ended = false
    val positionUs: Long get() = reader.positionUs

    /** The next samples (possibly empty), or null at the end. */
    fun next(): FloatArray? {
        if (ended) return null
        val x = reader.read()
        if (x == null) { ended = true; return resampler?.flush()?.takeIf { it.isNotEmpty() } }
        val c = Pcm.channels(x, x.size / reader.channels, reader.channels, channels)
        return resampler?.process(c) ?: c
    }

    override fun close() = reader.close()
}

/** A file's loudness and levels, from one pass over its sound. */
class AudioAnalysis(
    val loudness: LoudnessMeter.Result,
    val blocks: DoubleArray,
    val levelsDb: FloatArray,
    val peaks: FloatArray,
    /** Frames per level window. */
    val window: Int,
    val frames: Long,
    val rate: Int,
    val channels: Int,
)

object AudioEngine {
    /**
     * Analyses a file's sound at its own rate, as [channels] channels (its own, up to two, when 0):
     * loudness depends on the layout (a mono sound copied to both sides of a stereo file is 3 dB louder).
     */
    fun analyze(ctx: Context, info: AudioInfo, uri: Uri, progress: (Double) -> Unit = {}, check: () -> Unit = {}, channels: Int = 0): AudioAnalysis {
        val ch = if (channels in 1..2) channels else info.channels.coerceIn(1, 2)
        AudioStream(ctx, uri, info.rate, ch).use { s ->
            val lm = LoudnessMeter(info.rate, ch)
            val levels = LevelMeter(info.rate, ch)
            var frames = 0L
            while (true) {
                check()
                val x = s.next() ?: break
                lm.add(x); levels.add(x)
                frames += x.size / ch
                if (info.durationUs > 0) progress((s.positionUs.toDouble() / info.durationUs).coerceIn(0.0, 1.0))
            }
            if (frames == 0L) throw UserFacingException(tr("No sound could be read from this file."))
            return AudioAnalysis(lm.result(), lm.blockPowers(), levels.levelsDb(), levels.peaks(), levels.window, frames, info.rate, ch)
        }
    }

    /**
     * Writes one file: [produce] gets a sink for [out] at [rate] Hz and [channels] channels and feeds
     * it; the file is finished, checked and published (or removed if anything fails).
     */
    fun write(
        ctx: JobContext, area: OutputArea, name: String, out: AudioOutput, rate: Int, channels: Int, tags: AudioTags,
        sourceRate: Int = rate, produce: (AudioSink) -> Unit,
    ): OutputFile {
        val (file, _) = Outputs.produce(ctx, area, "$name.${out.format.ext}", out.format.mime) { pending ->
            pending.openFd("rw").use { pfd ->
                val sink = AudioSink.open(pfd, out, rate, channels, tags, sourceRate)
                try {
                    produce(sink)
                    if (sink.frames == 0L) throw UserFacingException(tr("There was no sound to save."))
                    sink.finish()
                } catch (t: Throwable) { sink.close(); throw t }
            }
            verify(ctx.app, pending.uri, out.format)
        }
        return file
    }

    /** Checks a written file starts as its format should (and, for M4A, that Android can open it). */
    fun verify(ctx: Context, uri: Uri, format: AudioFormatOut) {
        if (format == AudioFormatOut.AAC) { Outputs.verifyMedia(ctx, uri, false, true); return }
        val head = ByteArray(64)
        val n = ctx.contentResolver.openInputStream(uri)?.use { var got = 0; while (got < head.size) { val r = it.read(head, got, head.size - got); if (r < 0) break; got += r }; got } ?: 0
        fun at(o: Int, s: String) = n >= o + s.length && String(head, o, s.length, Charsets.ISO_8859_1) == s
        val ok = when (format) {
            AudioFormatOut.MP3 -> at(0, "ID3") || (n > 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0)
            AudioFormatOut.FLAC -> at(0, "fLaC")
            AudioFormatOut.WAV -> at(0, "RIFF") && at(8, "WAVE")
            AudioFormatOut.OPUS -> at(0, "OggS") && at(28, "OpusHead")
            AudioFormatOut.AAC -> true
        }
        if (!ok) throw UserFacingException(tr("The written file failed verification, so it was discarded."))
    }
}
