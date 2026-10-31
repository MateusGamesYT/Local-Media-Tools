package com.localmediatools.robo

import android.app.Application
import com.localmediatools.audio.AudioFormatOut
import com.localmediatools.audio.AudioOutput
import com.localmediatools.audio.core.LoudnessMeter
import com.localmediatools.audio.core.Pcm
import com.localmediatools.audio.core.PcmFileReader
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.tools.AudioSpeedJob
import com.localmediatools.tools.ConvertAudioJob
import com.localmediatools.tools.MergeAudioJob
import com.localmediatools.tools.RemoveSilenceJob
import com.localmediatools.tools.SplitAudioJob
import com.localmediatools.tools.SplitMode
import com.localmediatools.tools.TrimAudioJob
import com.localmediatools.tools.VolumeJob
import com.localmediatools.tools.VolumeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The audio tools from start to finish on real recordings (test resources audio/: speech, music,
 * snare; CC BY, see CREDITS.md): decoded by the app's WAV reader, processed, written as WAV (read back
 * and measured), MP3 or FLAC (checked for structure). Android's own decoders and encoders (MP3 and AAC
 * input, M4A and Opus output) don't run here; the app reads and writes everything these tests use itself.
 */
@RunWith(RobolectricTestRunner::class)
class AudioToolsTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs
    private val wav16 = AudioOutput(AudioFormatOut.WAV, bits = 16)
    private val wavF = AudioOutput(AudioFormatOut.WAV, bits = 32)

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        inDir = File(app.cacheDir, "in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "out").apply { deleteRecursively() }).also { it.install() }
    }

    @After fun tearDown() = outputs.uninstall()

    private fun input(name: String, as_: String = name): MediaItem = Robo.item(app, Robo.write(inDir, as_, javaClass.getResourceAsStream("/audio/$name")!!.readBytes()))

    private class Pcm32(val rate: Int, val ch: Int, val x: FloatArray) { val seconds get() = x.size / ch / rate.toDouble() }

    private fun read(r: ItemResult, k: Int = 0): Pcm32 = readFile(File(r.outputs[k].uri.path!!))

    private fun readFile(f: File): Pcm32 {
        val p = PcmFileReader.open(f.inputStream().buffered())!!
        val parts = ArrayList<FloatArray>(); val buf = FloatArray(8192 * p.channels)
        while (true) { val n = p.read(buf); if (n == 0) break; parts.add(buf.copyOf(n * p.channels)) }
        return Pcm32(p.rate, p.channels, Pcm.concat(parts))
    }

    private fun loud(p: Pcm32) = LoudnessMeter(p.rate, p.ch).apply { add(p.x) }.result()
    private fun ok(rs: List<ItemResult>): ItemResult { val r = rs.single(); assertEquals(r.message, ItemOutcome.SUCCESS, r.outcome); return r }

    @Test fun trimKeepsThePartWithFades() {
        val speech = input("speech.wav")
        val r = ok(Robo.runJob(app, TrimAudioJob(listOf(speech), 0.25, 0.75, removeMiddle = false, fadeInMs = 1000, fadeOutMs = 500, out = wav16)))
        val y = read(r)
        assertEquals(16000, y.rate); assertEquals(1, y.ch)
        assertEquals(4.0, y.seconds, 0.02)
        // The fade-in: the first 50 ms are far quieter than the middle.
        val head = y.x.copyOfRange(0, 800).maxOf { kotlin.math.abs(it) }
        val mid = y.x.copyOfRange(16000, 48000).maxOf { kotlin.math.abs(it) }
        assertTrue("fade in: $head vs $mid", head < mid * 0.1)
        assertTrue(kotlin.math.abs(y.x.last()) < 0.01f)
        // Cutting the middle out instead leaves the other half (less the short crossfade at the join).
        val r2 = ok(Robo.runJob(app, TrimAudioJob(listOf(speech), 0.25, 0.75, removeMiddle = true, fadeInMs = 0, fadeOutMs = 0, out = wav16)))
        assertEquals(4.0 - 0.01, read(r2).seconds, 0.02)
    }

    @Test fun mergeJoinsDifferentRatesBackToBackWithPausesOrCrossfades() {
        val music = input("music.wav"); val speech = input("speech.wav")
        val back = read(ok(Robo.runJob(app, MergeAudioJob(listOf(music, speech), 0, 0, evenOut = false, out = wavF))))
        assertEquals(44100, back.rate); assertEquals(2, back.ch)
        assertEquals(12.0, back.seconds, 0.03)
        val gap = read(ok(Robo.runJob(app, MergeAudioJob(listOf(music, speech), 1000, 0, evenOut = false, out = wavF))))
        assertEquals(13.0, gap.seconds, 0.03)
        // The pause is silent.
        val s = (4.2 * 44100).toInt() * 2
        assertTrue(gap.x.copyOfRange(s, s + 44100).all { it == 0f })
        val xf = read(ok(Robo.runJob(app, MergeAudioJob(listOf(music, speech), 0, 1000, evenOut = false, out = wavF))))
        assertEquals(11.0, xf.seconds, 0.03)
        // Evened out: both parts end up about equally loud.
        val even = read(ok(Robo.runJob(app, MergeAudioJob(listOf(music, speech), 500, 0, evenOut = true, out = wavF))))
        val a = loud(Pcm32(44100, 2, even.x.copyOfRange(0, 4 * 44100 * 2)))
        val b = loud(Pcm32(44100, 2, even.x.copyOfRange((4.5 * 44100).toInt() * 2, even.x.size)))
        assertEquals(a.integratedLufs, b.integratedLufs, 1.5)
    }

    @Test fun convertToMp3AndFlacKeepsTheLength() {
        val speech = input("speech.wav")
        val mp3 = ok(Robo.runJob(app, ConvertAudioJob(listOf(speech), AudioOutput(AudioFormatOut.MP3, kbps = 64))))
        val bytes = File(mp3.outputs.single().uri.path!!).readBytes()
        assertTrue(mp3.outputs.single().displayName.endsWith(".mp3"))
        // 16 kHz isn't an MPEG-1 rate: written at 32 kHz. Frames of 1152 samples cover the 8 s (and the Info frame comes first).
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals("Info", String(bytes, 4 + 17, 4))
        val frames = ((bytes[4 + 17 + 8].toInt() and 0xFF) shl 24) or ((bytes[4 + 17 + 9].toInt() and 0xFF) shl 16) or ((bytes[4 + 17 + 10].toInt() and 0xFF) shl 8) or (bytes[4 + 17 + 11].toInt() and 0xFF)
        assertEquals(8.0, (frames - 1) * 1152 / 32000.0, 0.12)
        val flac = ok(Robo.runJob(app, ConvertAudioJob(listOf(speech), AudioOutput(AudioFormatOut.FLAC, bits = 24))))
        val fb = File(flac.outputs.single().uri.path!!).readBytes()
        assertEquals("fLaC", String(fb, 0, 4))
        // STREAMINFO: 24 bits per sample, 8 s × 16000 samples.
        val bits = (((fb[8 + 12].toInt() and 1) shl 4) or ((fb[8 + 13].toInt() and 0xF0) shr 4)) + 1
        assertEquals(24, bits)
        var total = (fb[8 + 13].toLong() and 0x0F)
        for (k in 14..17) total = (total shl 8) or (fb[8 + k].toLong() and 0xFF)
        assertEquals(128_000L, total)
    }

    @Test fun volumeReachesLoudnessAndPeakTargets() {
        val speech = input("speech.wav")
        val l = read(ok(Robo.runJob(app, VolumeJob(listOf(speech), VolumeMode.LOUDNESS, -16.0, -1.0, 0.0, limit = true, album = false, out = wavF))))
        val m = loud(l)
        assertEquals(-16.0, m.integratedLufs, 0.5)
        assertTrue(m.truePeakDb <= -0.9)
        val p = read(ok(Robo.runJob(app, VolumeJob(listOf(speech), VolumeMode.PEAK, -16.0, -3.0, 0.0, limit = false, album = false, out = wavF))))
        assertEquals(-3.0, loud(p).truePeakDb, 0.3)
        // Album mode: one gain for both files, so their difference stays.
        val music = input("music.wav")
        val before = loud(readFile(File(inDir, "music.wav"))).integratedLufs - loud(readFile(File(inDir, "speech.wav"))).integratedLufs
        val rs = Robo.runJob(app, VolumeJob(listOf(music, speech), VolumeMode.LOUDNESS, -20.0, -1.0, 0.0, limit = false, album = true, out = wavF))
        assertEquals(2, rs.size)
        val after = loud(read(rs.first { it.inputName.contains("music") })).integratedLufs - loud(read(rs.first { it.inputName.contains("speech") })).integratedLufs
        assertEquals(before, after, 0.3)
    }

    @Test fun speedAndPitch() {
        val speech = input("speech.wav")
        assertEquals(8.0 / 1.5, read(ok(Robo.runJob(app, AudioSpeedJob(listOf(speech), 1.5, 0.0, wav16)))).seconds, 0.01)
        assertEquals(8.0, read(ok(Robo.runJob(app, AudioSpeedJob(listOf(speech), 1.0, 3.0, wav16)))).seconds, 0.01)
        assertEquals("1.25x-2st", AudioSpeedJob.label(1.25, -2.0))
    }

    @Test fun splitAtQuietMomentsAndRemoveSilence() {
        val speech = input("speech.wav")
        val r = ok(Robo.runJob(app, SplitAudioJob(listOf(speech), SplitMode.PARTS, 0.0, 2, 2000, snap = true, out = wav16)))
        assertEquals(2, r.outputs.size)
        assertTrue(r.outputs[0].displayName.endsWith("_part01.wav"))
        val a = read(r, 0); val b = read(r, 1)
        assertEquals(8.0, a.seconds + b.seconds, 0.02)
        // The cut moved off the exact middle, into a pause.
        assertTrue("cut at ${a.seconds}", kotlin.math.abs(a.seconds - 4.0) > 0.05)
        val s = read(ok(Robo.runJob(app, RemoveSilenceJob(listOf(speech), ends = true, pauses = true, keepPauseMs = 200, minPauseMs = 400, sensitivity = 10.0, out = wav16))))
        assertTrue("shorter: ${s.seconds}", s.seconds < 7.6 && s.seconds > 5.0)
    }
}
