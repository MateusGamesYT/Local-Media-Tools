package com.localmediatools.codec

import com.localmediatools.codec.audio.PcmProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioPcmTest {
    private fun sine(rate: Int, ch: Int, seconds: Double, hz: Double): ShortArray {
        val n = (rate * seconds).toInt()
        return ShortArray(n * ch) { i -> (sin(2 * PI * hz * (i / ch) / rate) * 12000).toInt().toShort() }
    }

    /** Feeds [input] in uneven chunks, like a decoder would, and returns the whole output. */
    private fun run(p: PcmProcessor, input: ShortArray, ch: Int): ShortArray {
        val out = ArrayList<ShortArray>()
        var pos = 0
        var k = 0
        while (pos < input.size) {
            val frames = intArrayOf(1024, 777, 2048, 333)[k++ % 4]
            val n = minOf(frames * ch, input.size - pos)
            out.add(p.process(input.copyOfRange(pos, pos + n)))
            pos += n
        }
        out.add(p.flush())
        val all = ShortArray(out.sumOf { it.size })
        var o = 0
        for (a in out) { System.arraycopy(a, 0, all, o, a.size); o += a.size }
        return all
    }

    /** Dominant frequency of channel [c] estimated from zero crossings in the middle of the signal. */
    private fun frequency(s: ShortArray, ch: Int, c: Int, rate: Int): Double {
        val frames = s.size / ch
        val from = frames / 5; val to = frames * 4 / 5
        var crossings = 0
        for (i in from + 1 until to) {
            val a = s[(i - 1) * ch + c]; val b = s[i * ch + c]
            if ((a < 0 && b >= 0)) crossings++
        }
        return crossings * rate.toDouble() / (to - from)
    }

    @Test
    fun resamplingKeepsPitchAndDuration() {
        val input = sine(44100, 2, 2.0, 440.0)
        val out = run(PcmProcessor(44100, 2, 48000, 2), input, 2)
        assertEquals(96000.0, out.size / 2.0, 2.0)
        assertEquals(440.0, frequency(out, 2, 0, 48000), 4.0)
        assertEquals(440.0, frequency(out, 2, 1, 48000), 4.0)
    }

    @Test
    fun channelMixing() {
        val mono = sine(48000, 1, 0.5, 300.0)
        val stereo = run(PcmProcessor(48000, 1, 48000, 2), mono, 1)
        assertEquals(mono.size * 2, stereo.size)
        for (i in mono.indices step 97) { assertTrue(abs(mono[i] - stereo[i * 2]) <= 1 && abs(mono[i] - stereo[i * 2 + 1]) <= 1) }
        val six = ShortArray(48000 * 6) { 1000 }
        val down = run(PcmProcessor(48000, 6, 48000, 2), six, 6)
        assertEquals(48000 * 2, down.size)
        assertTrue(down.all { abs(it - 1000) <= 2 })
    }

    @Test
    fun tempoChangeKeepsPitch() {
        for (speed in listOf(2.0, 1.5, 0.5, 4.0)) {
            val input = sine(48000, 2, 3.0, 440.0)
            val out = run(PcmProcessor(48000, 2, 48000, 2, speed), input, 2)
            val expected = 3.0 * 48000 / speed
            assertEquals("length at ${speed}×", expected, out.size / 2.0, 2.0)
            assertEquals("pitch at ${speed}×", 440.0, frequency(out, 2, 0, 48000), 12.0)
            // No clicks: neighbouring samples never jump more than a sine of this amplitude allows.
            var maxStep = 0
            for (i in 2 until out.size step 2) maxStep = maxOf(maxStep, abs(out[i] - out[i - 2]))
            assertTrue("smooth at ${speed}× (max step $maxStep)", maxStep < 1400)
        }
    }

    @Test
    fun speedAndRateTogether() {
        val input = sine(22050, 1, 2.0, 500.0)
        val out = run(PcmProcessor(22050, 1, 44100, 2, 2.0), input, 1)
        assertEquals(44100.0, out.size / 2.0, 2.0)
        assertEquals(500.0, frequency(out, 2, 0, 44100), 15.0)
    }
}
