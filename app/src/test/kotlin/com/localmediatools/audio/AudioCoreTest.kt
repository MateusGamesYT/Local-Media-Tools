package com.localmediatools.audio

import com.localmediatools.audio.core.AudioPlan
import com.localmediatools.audio.core.AudioTags
import com.localmediatools.audio.core.FadeCurve
import com.localmediatools.audio.core.FadeShaper
import com.localmediatools.audio.core.Joiner
import com.localmediatools.audio.core.SpanFilter
import com.localmediatools.audio.core.FlacEncoder
import com.localmediatools.audio.core.Id3
import com.localmediatools.audio.core.LevelMeter
import com.localmediatools.audio.core.Limiter
import com.localmediatools.audio.core.LoudnessMeter
import com.localmediatools.audio.core.Mp3Encoder
import com.localmediatools.audio.core.OggOpusWriter
import com.localmediatools.audio.core.Pcm
import com.localmediatools.audio.core.PcmFileReader
import com.localmediatools.audio.core.PcmFormat
import com.localmediatools.audio.core.SincResampler
import com.localmediatools.audio.core.Span
import com.localmediatools.audio.core.SpeedPitch
import com.localmediatools.audio.core.WavWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * The audio core on real recordings (test resources audio/, see CREDITS.md: music, speech and a
 * snare drum). The encoders' output is checked here for structure; decoding by FFmpeg and the
 * comparisons with LAME and flac are in buildtools/audio.
 */
class AudioCoreTest {
    private class Pcm32(val rate: Int, val ch: Int, val x: FloatArray) { val frames get() = x.size / ch }

    private fun load(name: String): Pcm32 {
        val r = PcmFileReader.open(File(AudioCoreTest::class.java.getResource("/audio/$name")!!.toURI()).inputStream().buffered())!!
        val out = ArrayList<FloatArray>()
        val buf = FloatArray(4096 * r.channels)
        while (true) { val n = r.read(buf); if (n == 0) break; out.add(buf.copyOf(n * r.channels)) }
        return Pcm32(r.rate, r.channels, Pcm.concat(out))
    }

    private fun loudness(p: Pcm32) = LoudnessMeter(p.rate, p.ch).apply { add(p.x) }.result()

    // ------------------------------------------------------------------ files
    @Test
    fun wavFilesRoundTripIn16And24BitAndFloat() {
        val music = load("music.wav")
        assertEquals(44100, music.rate); assertEquals(2, music.ch); assertEquals(4.0, music.frames / 44100.0, 0.01)
        for (f in PcmFormat.entries) {
            val bytes = ByteArrayOutputStream()
            val w = WavWriter(bytes, music.rate, music.ch, f)
            w.add(music.x)
            val all = bytes.toByteArray()
            w.finish().copyInto(all)
            val back = PcmFileReader.open(ByteArrayInputStream(all))!!
            assertEquals(music.frames.toLong(), back.frames); assertEquals(f.bits, back.bits)
            val y = FloatArray(music.x.size); var got = 0
            while (true) { val tmp = FloatArray(10000); val n = back.read(tmp); if (n == 0) break; System.arraycopy(tmp, 0, y, got * 2, n * 2); got += n }
            assertEquals(music.frames, got)
            var err = 0.0; for (i in y.indices) err = maxOf(err, abs((y[i] - music.x[i]).toDouble()))
            // The source is 16-bit: 16-bit output differs only by the dither (±1 LSB), the others not at all.
            val limit = if (f == PcmFormat.S16) 2.5 / 32768 else 1e-6
            assertTrue("$f max error $err", err <= limit)
        }
    }

    @Test
    fun aiffAndUnsupportedFiles() {
        // A 2-frame stereo 16-bit AIFF: COMM then SSND (big-endian samples).
        val b = ByteArrayOutputStream()
        fun be32(v: Int) { b.write(v ushr 24); b.write(v ushr 16); b.write(v ushr 8); b.write(v) }
        fun be16(v: Int) { b.write(v ushr 8); b.write(v) }
        b.write("FORM".toByteArray()); be32(4 + 26 + 8 + 16); b.write("AIFF".toByteArray())
        b.write("COMM".toByteArray()); be32(18); be16(2); be32(2); be16(16)
        b.write(byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0))   // 44100 as 80-bit extended
        b.write("SSND".toByteArray()); be32(8 + 8); be32(0); be32(0)
        be16(16384); be16(-16384 and 0xFFFF); be16(0); be16(32767)
        val r = PcmFileReader.open(ByteArrayInputStream(b.toByteArray()))!!
        assertEquals(44100, r.rate); assertEquals(2, r.channels); assertEquals(2L, r.frames)
        val x = FloatArray(4); assertEquals(2, r.read(x))
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0f, 32767 / 32768f), x, 1e-6f)
        assertNull(PcmFileReader.open(ByteArrayInputStream("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000xxxxxxxxxx".toByteArray())))
    }

    @Test
    fun flacStreamInfoDescribesTheSamples() {
        val speech = load("speech.wav")
        val out = ByteArrayOutputStream()
        val enc = FlacEncoder(out, speech.rate, 1, 16, comments = AudioTags(title = "Chapter 18", artist = "Heather Barnett").vorbisComments())
        enc.add(IntArray(speech.x.size) { (speech.x[it] * 32768).toInt() }, speech.x.size)
        val info = enc.finish()
        assertEquals(speech.frames.toLong(), enc.totalSamples)
        // STREAMINFO: rate (20 bits), channels − 1 (3), bits − 1 (5), total samples (36) at bytes 10–17.
        val rate = ((info[10].toInt() and 0xFF) shl 12) or ((info[11].toInt() and 0xFF) shl 4) or ((info[12].toInt() and 0xFF) shr 4)
        assertEquals(16000, rate)
        val file = out.toByteArray()
        assertEquals("fLaC", String(file, 0, 4))
        assertTrue("lossless but smaller than 16-bit PCM", file.size < speech.frames * 2 * 0.7)
        assertTrue(String(file, Charsets.ISO_8859_1).contains("ARTIST=Heather Barnett"))
    }

    @Test
    fun id3TagsWithCover() {
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val tag = Id3.v24(AudioTags(title = "Vibe Ace", artist = "Kevin MacLeod", track = "3", cover = cover))
        assertEquals(tag.size, Id3.size(tag))
        val s = String(tag, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("ID3\u0004")); assertTrue(s.contains("TIT2")); assertTrue(s.contains("Vibe Ace")); assertTrue(s.contains("APIC")); assertTrue(s.contains("image/jpeg"))
        assertEquals(0, Id3.v24(AudioTags()).size)
    }

    @Test
    fun oggOpusPagesAreValid() {
        val out = ByteArrayOutputStream()
        val bigCover = ByteArray(200_000) { (it * 31).toByte() }
        val w = OggOpusWriter(out, 2, 312, 44100, listOf("TITLE" to "Vibe Ace", "METADATA_BLOCK_PICTURE" to java.util.Base64.getEncoder().encodeToString(bigCover)))
        // 20 ms CELT packets (config 31: TOC 0xF8 with one frame).
        repeat(150) { w.addPacket(byteArrayOf(0xFC.toByte()) + ByteArray(100 + it % 50)) }
        w.finish(samples48k = 150 * 960L - 500)
        val b = out.toByteArray()
        var p = 0; var pages = 0; var lastGranule = -1L; var seq = 0
        var sawHead = false; var eos = false
        while (p < b.size) {
            assertEquals("OggS", String(b, p, 4, Charsets.ISO_8859_1))
            val flags = b[p + 5].toInt()
            var g = 0L; for (k in 0 until 8) g = g or ((b[p + 6 + k].toLong() and 0xFF) shl (8 * k))
            var s = 0; for (k in 0 until 4) s = s or ((b[p + 18 + k].toInt() and 0xFF) shl (8 * k))
            assertEquals(seq++, s)
            val nseg = b[p + 26].toInt() and 0xFF
            var len = 0; for (i in 0 until nseg) len += b[p + 27 + i].toInt() and 0xFF
            val page = b.copyOfRange(p, p + 27 + nseg + len)
            var crc = 0; for (k in 0 until 4) crc = crc or ((page[22 + k].toInt() and 0xFF) shl (8 * k))
            for (k in 0 until 4) page[22 + k] = 0
            assertEquals("page $pages CRC", crc, OggOpusWriter.crc(0, page, page.size))
            if (pages == 0) { assertEquals(2, flags); assertEquals("OpusHead", String(b, p + 28, 8)); sawHead = true; assertEquals(2, b[p + 28 + 9].toInt()) }
            if (g >= 0 && pages > 1) { assertTrue(g >= lastGranule); lastGranule = g }
            if (flags and 4 != 0) eos = true
            p += page.size; pages++
        }
        assertTrue(sawHead); assertTrue(eos)
        assertTrue("the cover spans several pages", pages > 6)
        assertEquals(312 + 150 * 960L - 500, lastGranule)
        assertEquals(960, OggOpusWriter.samples(byteArrayOf(0xFC.toByte())))
        assertEquals(2 * 480, OggOpusWriter.samples(byteArrayOf(0x01, 0)))   // SILK 10 ms, two frames (code 1)
    }

    // ------------------------------------------------------------------ MP3
    private class Mp3Frame(val offset: Int, val length: Int, val mainDataBegin: Int, val mainBits: Int, val sideBytes: Int)

    /** Walks the frames of an MPEG-1 Layer III file, reading each header and the side information's bit counts. */
    private fun mp3Frames(b: ByteArray): List<Mp3Frame> {
        val rates = intArrayOf(44100, 48000, 32000)
        val kbps = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val out = ArrayList<Mp3Frame>()
        var p = 0
        while (p + 4 <= b.size) {
            val h = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            assertEquals("sync at $p", 0xFFFA, h ushr 16 and 0xFFFE)
            val br = kbps[h ushr 12 and 15]; val rate = rates[h ushr 10 and 3]; val pad = h ushr 9 and 1
            val mono = (h ushr 6 and 3) == 3
            val len = 144000 * br / rate + pad
            val side = if (mono) 17 else 32
            // Side information: main_data_begin (9 bits), private bits, scfsi, then per granule and channel part2_3_length (12 bits) …
            var bit = (p + 4) * 8L
            fun read(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or ((b[(bit / 8).toInt()].toInt() shr (7 - (bit % 8).toInt())) and 1); bit++ }; return v }
            val mdb = read(9); read(if (mono) 5 else 3); read(if (mono) 4 else 8)
            var bits = 0
            repeat(if (mono) 2 else 4) { bits += read(12); read(9 + 8 + 4); val ws = read(1); read(if (ws == 1) 22 else 22); read(3) }
            out.add(Mp3Frame(p, len, mdb, bits, side))
            p += len
        }
        assertEquals("the last frame ends at the end of the file", b.size, p)
        return out
    }

    private fun checkMp3(file: ByteArray, frames: Long) {
        val fr = mp3Frames(file)
        // The Info frame first: "Info" after the side information, with the number of frames.
        val info = fr[0]
        assertEquals("Info", String(file, info.offset + 4 + info.sideBytes, 4))
        val n = ((file[info.offset + 4 + info.sideBytes + 8].toLong() and 0xFF) shl 24) or ((file[info.offset + 4 + info.sideBytes + 9].toLong() and 0xFF) shl 16) or
            ((file[info.offset + 4 + info.sideBytes + 10].toLong() and 0xFF) shl 8) or (file[info.offset + 4 + info.sideBytes + 11].toLong() and 0xFF)
        assertEquals(fr.size.toLong(), n)
        // Enough frames for the samples and the delay; each frame's main data fits in what came before plus its own slot.
        assertTrue((fr.size - 1) * 1152L >= frames + Mp3Encoder.DELAY - Mp3Encoder.DECODER_DELAY)
        var available = 0L
        for (f in fr.drop(1)) {
            assertTrue("main_data_begin ${f.mainDataBegin} ≤ ${minOf(511L, available)}", f.mainDataBegin <= minOf(511L, available))
            val slot = f.length - 4 - f.sideBytes
            assertTrue("main data fits", (f.mainBits + 7) / 8 <= f.mainDataBegin + slot)
            available = (f.mainDataBegin + slot - (f.mainBits + 7) / 8).toLong()
        }
    }

    private fun mp3(p: Pcm32, kbps: Int, allShort: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val enc = Mp3Encoder(out, p.rate, p.ch, kbps)
        enc.allShort = allShort
        var i = 0
        while (i < p.x.size) { val n = minOf(5000 * p.ch, p.x.size - i); enc.add(p.x.copyOfRange(i, i + n)); i += n }
        var info: ByteArray? = null
        enc.finish { _, b -> info = b }
        val file = out.toByteArray()
        info!!.copyInto(file)
        return file
    }

    @Test
    fun mp3FramesAreWellFormedAtSeveralRates() {
        val music = load("music.wav")
        for (kbps in listOf(64, 128, 320)) checkMp3(mp3(music, kbps), music.frames.toLong())
        // Constant bit rate: at 44.1 kHz frames are 417 bytes, or 418 with padding.
        for (f in mp3Frames(mp3(music, 128)).drop(1)) assertTrue(f.length == 417 || f.length == 418)
        // Short blocks everywhere still make a valid stream.
        checkMp3(mp3(music, 192, allShort = true), music.frames.toLong())
        // Mono speech, resampled to 32 kHz.
        val speech = load("speech.wav")
        val rs = SincResampler(16000, 32000, 1)
        val up = Pcm32(32000, 1, SincResampler.concat(rs.process(speech.x), rs.flush()))
        checkMp3(mp3(up, 48), up.frames.toLong())
    }

    @Test
    fun mp3UsesShortBlocksOnDrumHits() {
        val snare = load("snare.wav")
        val rs = SincResampler(22050, 44100, 1)
        val up = Pcm32(44100, 1, SincResampler.concat(rs.process(snare.x), rs.flush()))
        val out = ByteArrayOutputStream()
        val enc = Mp3Encoder(out, 44100, 1, 128)
        enc.add(up.x); enc.finish()
        // The excerpt has two hits (at 0.73 s and 2.52 s): each gets short blocks, and little else does.
        assertTrue("short granules on the attacks: ${enc.shortGranules}", enc.shortGranules in 2..12)
        val music = load("music.wav")
        val e2 = Mp3Encoder(ByteArrayOutputStream(), 44100, 2, 128); e2.add(music.x); e2.finish()
        assertTrue("few short granules in music: ${e2.shortGranules}", e2.shortGranules < 40)
    }

    // ------------------------------------------------------------------ processing
    @Test
    fun limiterKeepsTruePeaksUnderTheCeiling() {
        val snare = load("snare.wav")
        val loud = snare.x.copyOf().also { Pcm.gain(it, it.size, 4f) }       // +12 dB: hits far over full scale
        val before = loudness(Pcm32(snare.rate, 1, loud))
        assertTrue(before.truePeakDb > 6)
        val lim = Limiter(snare.rate, 1, ceilingDb = -1.0)
        val y = Pcm.concat(listOf(lim.process(loud), lim.flush()))
        assertEquals(loud.size, y.size)
        val after = loudness(Pcm32(snare.rate, 1, y))
        assertTrue("true peak ${after.truePeakDb}", after.truePeakDb <= -0.9)
        // Between hits the sound is left alone: the quiet parts come out unchanged (after the delay).
        var same = 0; var quiet = 0
        for (i in 0 until loud.size) if (abs(loud[i]) < 0.05f) { quiet++; if (abs(y[i] - loud[i]) < 1e-4f) same++ }
        assertTrue("$same of $quiet quiet samples unchanged", same > quiet * 0.5)
    }

    @Test
    fun normalizingMusicReachesTheTarget() {
        val music = load("music.wav")
        val m = loudness(music)
        val gain = AudioPlan.normalizeGain(m.integratedLufs, m.truePeakDb, -14.0, -1.0, limit = true)
        val x = music.x.copyOf().also { Pcm.gain(it, it.size, Math.pow(10.0, gain / 20).toFloat()) }
        val lim = Limiter(music.rate, 2)
        val y = Pcm.concat(listOf(lim.process(x), lim.flush()))
        val r = loudness(Pcm32(music.rate, 2, y))
        assertEquals(-14.0, r.integratedLufs, 0.7)
        assertTrue(r.truePeakDb <= -0.9)
        // Without the limiter the gain stops at the ceiling.
        val g2 = AudioPlan.normalizeGain(m.integratedLufs, m.truePeakDb, -5.0, -1.0, limit = false)
        assertEquals(-1.0 - m.truePeakDb, g2, 1e-9)
    }

    private fun tone(rate: Int, hz: Double, seconds: Double) = FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    /** The strongest frequency (FFT peak, interpolated). */
    private fun frequency(x: FloatArray, rate: Int): Double {
        val n = 1 shl 15
        val re = DoubleArray(n) { if (it < x.size) x[x.size / 2 - n / 2 + it].toDouble() * (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / n)) else 0.0 }
        val im = DoubleArray(n)
        Mp3Encoder.fft(re, im)
        var best = 1; var bm = 0.0
        for (k in 1 until n / 2) { val m = re[k] * re[k] + im[k] * im[k]; if (m > bm) { bm = m; best = k } }
        fun mag(k: Int) = log10(re[k] * re[k] + im[k] * im[k] + 1e-30)
        val a = mag(best - 1); val b = mag(best); val c = mag(best + 1)
        val d = 0.5 * (a - c) / (a - 2 * b + c)
        return (best + d) * rate / n
    }

    @Test
    fun speedKeepsPitchAndPitchKeepsLength() {
        val rate = 44100
        val x = tone(rate, 440.0, 3.0)
        for (tempo in listOf(0.5, 0.8, 1.5, 2.0)) {
            val sp = SpeedPitch(rate, 1, tempo, 0.0)
            val y = Pcm.concat(listOf(sp.process(x), sp.flush()))
            assertEquals("tempo $tempo length", x.size / tempo, y.size.toDouble(), 2.0)
            assertEquals("tempo $tempo pitch", 440.0, frequency(y, rate), 440 * 0.005)
        }
        for (semi in listOf(-12.0, -3.0, 5.0, 12.0)) {
            val sp = SpeedPitch(rate, 1, 1.0, semi)
            val y = Pcm.concat(listOf(sp.process(x), sp.flush()))
            assertEquals("pitch $semi length", x.size.toDouble(), y.size.toDouble(), 2.0)
            assertEquals("pitch $semi", 440 * Math.pow(2.0, semi / 12), frequency(y, rate), 440 * 0.006)
        }
    }

    @Test
    fun speedChangesOnRealSpeechKeepTheLevel() {
        val speech = load("speech.wav")
        val l0 = loudness(speech).integratedLufs
        for (tempo in listOf(0.75, 1.25, 1.5)) {
            val sp = SpeedPitch(speech.rate, 1, tempo, 0.0)
            val chunks = ArrayList<FloatArray>()
            var i = 0
            while (i < speech.x.size) { val n = minOf(1234, speech.x.size - i); chunks.add(sp.process(speech.x.copyOfRange(i, i + n))); i += n }
            chunks.add(sp.flush())
            val y = Pcm.concat(chunks)
            assertEquals(speech.frames / tempo, y.size.toDouble(), 2.0)
            assertEquals("level at $tempo×", l0, loudness(Pcm32(speech.rate, 1, y)).integratedLufs, 1.0)
        }
    }

    // ------------------------------------------------------------------ plans
    @Test
    fun silenceInRealSpeech() {
        val speech = load("speech.wav")
        val lm = LevelMeter(speech.rate, 1)
        lm.add(speech.x)
        val levels = lm.levelsDb()
        val th = AudioPlan.silenceThreshold(levels)
        // Its pauses hold room noise around −45 dBFS, over a background near −52.
        assertTrue("threshold $th", th in -46.0..-38.0)
        val sil = AudioPlan.silences(levels, lm.window, speech.frames.toLong(), th, (0.25 * speech.rate).toLong())
        assertTrue("pauses between phrases: $sil", sil.isNotEmpty())
        val keep = AudioPlan.keep(sil, speech.frames.toLong(), ends = true, pauses = true, keepPause = (0.2 * speech.rate).toLong())
        val kept = keep.sumOf { it.length }
        assertTrue("shorter: $kept of ${speech.frames}", kept < speech.frames && kept > speech.frames * 0.6)
        // The speech itself stays: every loud window is inside a kept span.
        for ((i, v) in levels.withIndex()) if (v > th + 15) {
            val f = i.toLong() * lm.window
            assertTrue("loud window at $f kept", keep.any { f >= it.start - lm.window && f < it.end })
        }
        // Music without pauses has no silence at the strictest setting.
        val music = load("music.wav")
        val mm = LevelMeter(music.rate, 2).apply { add(music.x) }
        val ml = mm.levelsDb()
        assertEquals(emptyList<Span>(), AudioPlan.silences(ml, mm.window, music.frames.toLong(), AudioPlan.silenceThreshold(ml, AudioPlan.SENSITIVITY_SILENCE), (0.25 * music.rate).toLong()))
        // Splitting near 4 s moves the cut into a pause.
        val parts = AudioPlan.split(speech.frames.toLong(), 0, 2, sil, (1.5 * speech.rate).toLong())
        assertEquals(2, parts.size)
        val cut = parts[0].end
        assertTrue("cut at $cut is in a pause", sil.any { cut >= it.start && cut <= it.end })
    }

    @Test
    fun plansForSplitAndMerge() {
        val parts = AudioPlan.split(100_000, 30_000, 0, emptyList(), 0)
        assertEquals(listOf(Span(0, 30_000), Span(30_000, 60_000), Span(60_000, 90_000), Span(90_000, 100_000)), parts)
        // A tail under a tenth of a part joins the last part.
        assertEquals(3, AudioPlan.split(92_000, 30_000, 0, emptyList(), 0).size)
        val (starts, total) = AudioPlan.merge(listOf(1000, 2000, 500), gap = 100, crossfade = 0)
        assertEquals(listOf(0L, 1100L, 3200L), starts); assertEquals(3700L, total)
        val (s2, t2) = AudioPlan.merge(listOf(1000, 2000, 500), gap = 0, crossfade = 400)
        assertEquals(listOf(0L, 600L, 2350L), s2); assertEquals(2850L, t2)
        assertEquals(250L, AudioPlan.overlap(listOf(1000, 2000, 500), 2, 400))
        assertEquals(listOf(Span(0, 1000)), AudioPlan.keep(listOf(Span(1000, 2000)), 2000, ends = true, pauses = false, keepPause = 0))
        assertEquals(emptyList<Span>(), AudioPlan.keep(listOf(Span(0, 2000)), 2000, ends = true, pauses = true, keepPause = 10))
    }

    @Test
    fun fadesAndChannels() {
        for (c in FadeCurve.entries) {
            assertEquals(0.0, c.gain(0.0), 1e-12); assertEquals(1.0, c.gain(1.0), 1e-12)
            var last = -1.0
            for (k in 0..100) { val g = c.gain(k / 100.0); assertTrue(g >= last); last = g }
        }
        // Equal power: the two halves of a crossfade add up to constant power.
        for (k in 0..10) { val t = k / 10.0; val a = FadeCurve.EQUAL_POWER.gain(t); val b = FadeCurve.EQUAL_POWER.gain(1 - t); assertEquals(1.0, a * a + b * b, 1e-9) }
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, -1f, -1f), Pcm.channels(floatArrayOf(0.5f, -1f), 2, 1, 2), 0f)
        assertArrayEquals(floatArrayOf(0.25f), Pcm.channels(floatArrayOf(0.5f, 0f), 1, 2, 1), 0f)
        val six = Pcm.channels(floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f), 1, 6, 2)     // left + centre; LFE left out
        assertEquals(1.7071f, six[0], 1e-4f); assertEquals(0.7071f, six[1], 1e-4f)
        assertNotNull(AudioTags(cover = byteArrayOf(0x89.toByte(), 'P'.code.toByte())).flacPicture())
        assertEquals("image/png", AudioTags(cover = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())).coverMime)
    }

    @Test
    fun joinsFadesAndCutsKeepTheRightFrames() {
        // Two constant "sounds" (1 and 2), crossfaded over 100 frames: 300 + 300 − 100 frames, constant power through the join.
        val j = Joiner(1, 100)
        val out = ArrayList<FloatArray>()
        for (k in 0 until 3) out.add(j.add(FloatArray(100) { 1f }))
        out.add(j.next(100, 0))
        for (k in 0 until 6) out.add(j.add(FloatArray(50) { 2f }))
        out.add(j.finish())
        val y = Pcm.concat(out)
        assertEquals(500, y.size)
        assertEquals(1f, y[150], 0f); assertEquals(2f, y[450], 0f)
        for (i in 200 until 300) { val t = (i - 200 + 0.5) / 100; val a = FadeCurve.EQUAL_POWER.gain(1 - t); val b = FadeCurve.EQUAL_POWER.gain(t); assertEquals(a + 2 * b, y[i].toDouble(), 1e-5) }
        // A gap instead: silence between.
        val g = Joiner(1, 0)
        val z = Pcm.concat(listOf(g.add(FloatArray(10) { 1f }), g.next(0, 5), g.add(FloatArray(10) { 1f }), g.finish()))
        assertEquals(25, z.size); assertTrue(z.copyOfRange(10, 15).all { it == 0f })
        // Fades on a stream whose end isn't known in advance.
        val f = FadeShaper(1, 10, 10, FadeCurve.LINEAR)
        val w = Pcm.concat(listOf(f.process(FloatArray(25) { 1f }), f.process(FloatArray(25) { 1f }), f.finish()))
        assertEquals(50, w.size)
        assertEquals(0.05f, w[0], 1e-6f); assertEquals(1f, w[25], 0f); assertEquals(0.05f, w[49], 1e-6f)
        // Keeping spans: cut edges are faded, the file's own start and end are not.
        val sf = SpanFilter(1, listOf(Span(0, 40), Span(60, 100)), 100, 5)
        val k = Pcm.concat(listOf(sf.process(FloatArray(50) { 1f }), sf.process(FloatArray(50) { 1f })))
        assertEquals(80, k.size); assertEquals(1f, k[0], 0f); assertEquals(1f, k[79], 0f)
        assertTrue(k[39] < 0.2f && k[40] < 0.2f)
    }

}
