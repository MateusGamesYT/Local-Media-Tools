package com.localmediatools.music

import com.localmediatools.music.core.Composer
import com.localmediatools.music.core.Mood
import com.localmediatools.music.core.Part
import com.localmediatools.music.core.Synth
import com.localmediatools.music.core.Trio
import com.localmediatools.music.core.TrioModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The background music generator: the Kotlin decoder of MusicVAE "trio_4bar" against magenta.js
 * (the model's own JavaScript implementation, buildtools/music/README.md), then what the app makes
 * of it.
 */
class MusicTest {
    companion object {
        val model: TrioModel by lazy { File("app/src/main/assets/models/trio_decoder.bin").inputStream().use { TrioModel.load(it) } }

        /** The reference: latent codes and what magenta.js decoded from them (most likely event at every step). */
        val reference: List<Pair<FloatArray, List<IntArray>>> by lazy {
            val text = MusicTest::class.java.getResourceAsStream("/music/magenta_reference.json")!!.readBytes().toString(Charsets.UTF_8)
            // Tiny parser for [{"z":[…],"steps":[[m,b,d],…]},…].
            val out = ArrayList<Pair<FloatArray, List<IntArray>>>()
            var i = 0
            fun numbers(end: Char): List<Double> {
                val list = ArrayList<Double>()
                val sb = StringBuilder()
                while (text[i] != end) {
                    val ch = text[i]
                    if (ch == ',' ) { if (sb.isNotEmpty()) { list.add(sb.toString().toDouble()); sb.clear() } } else if (ch != '[' && ch != ' ') sb.append(ch)
                    i++
                }
                if (sb.isNotEmpty()) list.add(sb.toString().toDouble())
                i++
                return list
            }
            while (true) {
                val zi = text.indexOf("\"z\":[", i); if (zi < 0) break
                i = zi + 5
                val z = numbers(']').map { it.toFloat() }.toFloatArray()
                i = text.indexOf("\"steps\":[", i) + 9
                val steps = ArrayList<IntArray>()
                while (text[i] == '[' || text[i] == ',') {
                    if (text[i] == ',') { i++; continue }
                    i++
                    steps.add(numbers(']').map { it.toInt() }.toIntArray())
                }
                out.add(z to steps)
            }
            out
        }
    }

    @Test fun decodesExactlyLikeMagentaJs() {
        assertEquals(6, reference.size)
        var mismatched = 0
        for ((z, steps) in reference) {
            val t = model.decode(z)
            assertEquals(64, steps.size)
            for (s in 0 until 64) {
                if (t.melody[s] != steps[s][0] || t.bass[s] != steps[s][1] || t.drums[s] != steps[s][2]) mismatched++
            }
        }
        // Every one of 384 steps × 3 tracks the same as the model's own implementation.
        assertEquals(0, mismatched)
    }

    @Test fun samplingVariesWithTheSeedAndIsRepeatable() {
        val z = FloatArray(model.zDims) { (Math.sin(it * 1.7) * 0.8).toFloat() }
        fun rng(seed: Long): () -> Double { val r = java.util.Random(seed); return { r.nextDouble() } }
        val a = model.decode(z, 0.5f, rng(1)); val b = model.decode(z, 0.5f, rng(1)); val c = model.decode(z, 0.5f, rng(2))
        assertTrue(a.melody.contentEquals(b.melody) && a.drums.contentEquals(b.drums))
        assertTrue(!a.melody.contentEquals(c.melody) || !a.drums.contentEquals(c.drums) || !a.bass.contentEquals(c.bass))
        assertTrue(a.melody.all { it in 0 until 90 } && a.drums.all { it in 0 until 512 })
        assertEquals(36, Trio.DRUM_PITCH[0])
    }

    @Test fun composesRepeatablyInOneKeyForTheWholeVideo() {
        for (mood in Mood.values()) {
            val a = Composer.compose(model, mood, 45.0, seed = 7)
            val b = Composer.compose(model, mood, 45.0, seed = 7)
            // The same seed, the same piece (the timeline can be re-rendered identically).
            assertEquals(a.melody.map { it.start to it.pitch }, b.melody.map { it.start to it.pitch })
            assertEquals(a.drums.map { it.step * 16 + it.drum }, b.drums.map { it.step * 16 + it.drum })
            assertTrue(a.melody.map { it.pitch } != Composer.compose(model, mood, 45.0, seed = 8).melody.map { it.pitch })
            // Long enough, with a beginning and an end.
            assertTrue("$mood ${a.seconds}", a.seconds >= 45.0 && a.seconds < 45.0 + 4 * 16 * a.stepSeconds + 1.0)
            assertEquals(Part.INTRO, a.sections.first().part)
            assertEquals(Part.OUTRO, a.sections.last().part)
            assertEquals(60.0 / mood.bpm, a.beats()[1] - a.beats()[0], 1e-9)
            // In one key (notes outside it move to the nearest note of the scale), in comfortable ranges.
            val scale = a.key.scale.toSet()
            val notes = a.melody + a.bass
            val inKey = notes.filter { it.pitch % 12 in scale }.sumOf { it.end - it.start }.toDouble() / notes.sumOf { it.end - it.start }
            assertEquals("$mood in key", 1.0, inKey, 0.0)
            assertTrue(a.melody.all { it.pitch in 60..84 } && a.bass.all { it.pitch in 36..55 })
            assertTrue(a.melody.size >= 20 && a.bass.isNotEmpty() && a.pads.isNotEmpty())
            // Upbeat has a beat; cinematic stays sparse.
            if (mood == Mood.UPBEAT) assertTrue(a.drums.count { it.drum == 0 } >= 24)
            if (mood == Mood.CINEMATIC) assertTrue(a.drums.size < a.melody.size * 2)
        }
    }

    @Test fun rendersCleanInstrumentalSound() {
        // Only the app's own oscillators and noise play: there are no recordings, so no voices.
        val out = System.getenv("LMT_WAV")
        for (mood in Mood.values()) {
            val score = Composer.compose(model, mood, 20.0, seed = 3)
            val t = System.nanoTime()
            val pcm = Synth().render(score, 20.0)
            val ms = (System.nanoTime() - t) / 1_000_000
            assertEquals(20 * 48_000 * 2, pcm.size)
            var peak = 0f; var sum = 0.0; var dc = 0.0
            for (v in pcm) { assertTrue(v.isFinite()); peak = maxOf(peak, Math.abs(v)); sum += v * v; dc += v }
            val rms = Math.sqrt(sum / pcm.size)
            // Never clips; a steady level (measured RMS 0.09–0.13 at a peak of 0.64); no offset.
            assertTrue("$mood peak $peak", peak in 0.4f..0.86f)
            assertTrue("$mood rms $rms", rms in 0.05..0.2)
            assertTrue(Math.abs(dc / pcm.size) < 0.005)
            // Fades out to silence.
            val tail = pcm.copyOfRange(pcm.size - 2 * 2400, pcm.size)
            assertTrue(tail.all { Math.abs(it) < 0.02f })
            println("music: $mood ${score.key} rendered 20 s in $ms ms")
            if (out != null) wav(File(out, "${mood.name.lowercase()}.wav"), pcm, 48_000)
        }
    }

    /** Writing and playing 90 s (the longest video) on the build machine: how long, and how much memory the sound takes. */
    @Test fun aLongPieceIsQuickAndLean() {
        Composer.compose(model, Mood.UPBEAT, 20.0, seed = 1)  // warm-up (JIT)
        val t0 = System.nanoTime()
        val score = Composer.compose(model, Mood.UPBEAT, 90.0, seed = 2)
        val t1 = System.nanoTime()
        val rt = Runtime.getRuntime()
        System.gc(); val before = rt.totalMemory() - rt.freeMemory()
        val peak = java.util.concurrent.atomic.AtomicLong(0)
        val watch = Thread { while (!Thread.currentThread().isInterrupted) { peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory()) { a, b -> maxOf(a, b) }; try { Thread.sleep(2) } catch (_: InterruptedException) { break } } }
        watch.start()
        val pcm = Synth().render(score, 90.0)
        val t2 = System.nanoTime()
        watch.interrupt(); watch.join()
        val extraMb = (peak.get() - before) / 1e6
        println("music: 90 s compose ${(t1 - t0) / 1_000_000} ms, render ${(t2 - t1) / 1_000_000} ms, peak extra memory ${"%.0f".format(extraMb)} MB (the result itself ${pcm.size * 4 / 1_000_000} MB)")
    }

    private fun wav(f: File, pcm: FloatArray, rate: Int) {
        val data = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { val v = (pcm[i] * 32767).toInt().coerceIn(-32768, 32767); data[2 * i] = v.toByte(); data[2 * i + 1] = (v shr 8).toByte() }
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
            .putInt(rate).putInt(rate * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(data.size)
        f.outputStream().use { it.write(b.array()); it.write(data) }
    }
}
