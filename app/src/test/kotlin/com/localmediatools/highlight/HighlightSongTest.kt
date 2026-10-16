package com.localmediatools.highlight

import com.localmediatools.highlight.core.Song
import com.localmediatools.music.MusicTest
import com.localmediatools.music.core.Composer
import com.localmediatools.music.core.Mood
import com.localmediatools.music.core.Synth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The user's own song as the highlight video's music ([Song]): its beat found (on music of known
 * tempo: the app's own pieces), where it starts, repeated on whole bars when too short, and its level.
 * Real songs are checked in buildtools/highlight/song_eval.py.
 */
class HighlightSongTest {
    private val rate = 48_000

    private fun piece(mood: Mood, seconds: Double, seed: Long): FloatArray =
        Synth(rate).render(Composer.compose(MusicTest.model, mood, seconds, seed), seconds)

    /** Within 3 %, or exactly double or half (a beat and its half or double both carry cuts well). */
    private fun near(bpm: Double, truth: Double) = listOf(1.0, 2.0, 0.5).any { abs(bpm / (truth * it) - 1) < 0.03 }

    @Test
    fun findsTheBeatOfMusicOfKnownTempo() {
        var exact = 0; var n = 0
        val levels = ArrayList<Double>()
        for (mood in Mood.entries) for (seed in 1L..3L) {
            val pcm = piece(mood, 40.0, seed)
            levels.add(Song.rms(pcm, 0, pcm.size / 2))
            val fit = Song.fit(pcm, rate, 30 * rate)
            val beat = fit.beat
            assertTrue("$mood/$seed: no beat found", beat != null)
            println("$mood/$seed: bpm ${"%.2f".format(beat!!.bpm)} confidence ${"%.3f".format(beat.confidence)} start ${fit.startFrame}")
            assertTrue("$mood/$seed: ${beat!!.bpm} vs ${mood.bpm}", near(beat.bpm, mood.bpm.toDouble()))
            if (abs(beat.bpm / mood.bpm - 1) < 0.03) exact++
            n++
            // The piece's beats are at multiples of the beat from its start: the song starts on one (or on
            // a half beat when the tempo was found doubled, whose grid has those too).
            val period = 60.0 / maxOf(beat.bpm.roundToInt(), mood.bpm) * rate
            val off = fit.startFrame % period
            assertTrue("$mood/$seed: starts ${fit.startFrame} frames in, ${off.roundToInt()} off the beat", minOf(off, period - off) < 0.03 * rate)
        }
        assertTrue("exact tempo in $exact of $n", exact >= n * 2 / 3)
        println("app music RMS: " + levels.joinToString { "%.3f".format(it) })
    }

    @Test
    fun skipsSilenceAtTheStart() {
        val pcm = piece(Mood.UPBEAT, 20.0, 5)
        val lead = 3 * rate
        val padded = FloatArray(pcm.size + 2 * lead).also { System.arraycopy(pcm, 0, it, 2 * lead, pcm.size) }
        val fit = Song.fit(padded, rate, 15 * rate)
        assertTrue("start ${fit.startFrame}", fit.startFrame >= lead - Song.HOP && fit.startFrame < lead + rate / 2)
    }

    @Test
    fun aShortSongRepeatsOnWholeBarsWithoutGapsAtTheAppsLevel() {
        val pcm = piece(Mood.UPBEAT, 9.0, 2)   // 9 s of music for a 25 s video
        val frames = 25 * rate
        val fit = Song.fit(pcm, rate, frames)
        assertTrue(fit.loopFrames > 0)
        val bar = 4 * 60.0 / Mood.UPBEAT.bpm * rate
        val bars = fit.loopFrames / bar
        assertEquals("loop of $bars bars", bars.roundToInt().toDouble(), bars, 0.02)
        val out = Song.render(pcm, fit, frames)
        // No silent stretch anywhere (100 ms windows), and the level the app's music has.
        val win = rate / 10
        for (w in 0 until frames / win - 1) assertTrue("silent at ${w * 100} ms", Song.rms(out, w * win, (w + 1) * win) > 0.01)
        val level = Song.rms(out, 0, frames)
        assertEquals("level $level", Song.TARGET_RMS.toDouble(), level, Song.TARGET_RMS * 0.15)
    }

    @Test
    fun soundWithoutABeatGetsNone() {
        val rnd = Random(3)
        // A held chord with slow swells and a little noise: nothing to cut on.
        val n = 30 * rate
        val pcm = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val swell = 0.6 + 0.4 * sin(2 * Math.PI * t / 7.3)
            val v = (swell * (0.2 * sin(2 * Math.PI * 220 * t) + 0.15 * sin(2 * Math.PI * 277.2 * t) + 0.12 * sin(2 * Math.PI * 329.6 * t)) + 0.01 * rnd.nextGaussian()).toFloat()
            pcm[2 * i] = v; pcm[2 * i + 1] = v
        }
        val fit = Song.fit(pcm, rate, 20 * rate)
        println("no beat: confidence ${fit.beat?.confidence ?: Song.lastConfidence}")
        assertNull("beat ${fit.beat?.bpm}", fit.beat)
        assertEquals(0.5, fit.beatSeconds, 1e-9)
    }
}
