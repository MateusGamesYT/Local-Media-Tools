package com.localmediatools.highlight.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The highlight video's sound: the background music, stepped back ("ducked") while a video clip
 * plays its own sound, plus the clips' sound, levelled and with soft edges, and a fade-out at the
 * end. Everything is interleaved stereo floats (−1..1) at one sample rate.
 */
object Mixer {
    /** Music level while a clip's own sound plays (about −13 dB): the voices stay clear, the music still carries. */
    const val DUCK = 0.22f
    const val DOWN_MS = 250L
    const val UP_MS = 400L
    /** Clips whose sound is less than this apart are one stretch (the music doesn't bob up between them). */
    const val MERGE_MS = 1000L
    /** Fade at the edges of each clip's sound, so cuts don't click. */
    const val EDGE_MS = 60L

    /** A clip's sound placed on the timeline: [samples] interleaved stereo from [startFrame]. */
    class Part(val startFrame: Int, val samples: FloatArray, val gain: Float = 1f, val fadeInFrames: Int = 0, val fadeOutFrames: Int = 0)

    /** Sorted intervals (ms) joined where they overlap or are less than [gapMs] apart. */
    fun merge(intervals: List<LongArray>, gapMs: Long = MERGE_MS): List<LongArray> {
        val out = ArrayList<LongArray>()
        for (iv in intervals.filter { it[1] > it[0] }.sortedBy { it[0] }) {
            val last = out.lastOrNull()
            if (last != null && iv[0] - last[1] < gapMs) last[1] = max(last[1], iv[1]) else out.add(longArrayOf(iv[0], iv[1]))
        }
        return out
    }

    /**
     * The music's gain at [ms] for merged [intervals]: down to [DUCK] over [DOWN_MS] before each
     * interval starts, back up over [UP_MS] after it ends (smooth steps, no clicks).
     */
    fun duckGain(intervals: List<LongArray>, ms: Double): Float {
        var g = 1.0
        for (iv in intervals) {
            val a = iv[0].toDouble(); val b = iv[1].toDouble()
            val v = when {
                ms < a - DOWN_MS || ms > b + UP_MS -> 1.0
                ms < a -> 1.0 - (1.0 - DUCK) * smooth((ms - (a - DOWN_MS)) / DOWN_MS)
                ms <= b -> DUCK.toDouble()
                else -> DUCK + (1.0 - DUCK) * smooth((ms - b) / UP_MS)
            }
            g = min(g, v)
        }
        return g.toFloat()
    }

    private fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    /** The gain that brings a clip's sound to a common level (RMS about −20 dBFS), without boosting noise or clipping. */
    fun levelGain(samples: FloatArray): Float {
        if (samples.isEmpty()) return 1f
        var sum = 0.0; var peak = 0f
        for (v in samples) { sum += v * v; peak = max(peak, abs(v)) }
        val rms = sqrt(sum / samples.size)
        if (rms < 1e-4) return 1f
        val g = (0.1 / rms).coerceIn(0.5, 3.0)
        return min(g, 0.95 / max(peak, 1e-6f).toDouble()).toFloat()
    }

    /**
     * Mixes [frames] stereo frames at [rate]: [music] (may be shorter, or null) ducked under
     * [soundIntervals] (ms, as [Plan.soundIntervals] gives them), the clips' [parts], a fade-in of
     * [fadeInMs] and a fade-out of [fadeOutMs] at the end, then a soft limiter. The mix is made in
     * [music]'s own buffer when it is long enough (it is overwritten), so a long video's sound needs
     * one buffer, not two.
     */
    fun mix(music: FloatArray?, musicGain: Float, frames: Int, rate: Int, parts: List<Part>, soundIntervals: List<LongArray>, fadeInMs: Long = 0, fadeOutMs: Long = 1000): FloatArray {
        val out = if (music != null && music.size >= frames * 2) music else FloatArray(frames * 2)
        if (music != null) {
            val ivs = merge(soundIntervals)
            val n = min(frames, music.size / 2)
            // The envelope changes slowly: compute it every 64 frames and interpolate.
            val block = 64
            var prev = duckGain(ivs, 0.0)
            var i = 0
            while (i < n) {
                val end = min(n, i + block)
                val next = duckGain(ivs, end * 1000.0 / rate)
                for (k in i until end) {
                    val g = (prev + (next - prev) * (k - i).toFloat() / (end - i)) * musicGain
                    out[2 * k] = music[2 * k] * g; out[2 * k + 1] = music[2 * k + 1] * g
                }
                prev = next; i = end
            }
        }
        val edge = (EDGE_MS * rate / 1000).toInt()
        for (p in parts) {
            val len = p.samples.size / 2
            val fin = max(edge, p.fadeInFrames); val fout = max(edge, p.fadeOutFrames)
            for (k in 0 until len) {
                val o = p.startFrame + k
                if (o < 0) continue
                if (o >= frames) break
                var g = p.gain
                if (k < fin) g *= k.toFloat() / fin
                if (k >= len - fout) g *= (len - k).toFloat() / fout
                out[2 * o] += p.samples[2 * k] * g; out[2 * o + 1] += p.samples[2 * k + 1] * g
            }
        }
        val fi = (fadeInMs * rate / 1000).toInt().coerceAtMost(frames)
        val fo = (fadeOutMs * rate / 1000).toInt().coerceAtMost(frames)
        for (k in 0 until frames) {
            var g = 1f
            if (k < fi) g *= k.toFloat() / fi
            if (k >= frames - fo) g *= (frames - k).toFloat() / fo
            out[2 * k] = limit(out[2 * k] * g); out[2 * k + 1] = limit(out[2 * k + 1] * g)
        }
        return if (out.size == frames * 2) out else out.copyOf(frames * 2)
    }

    /** Untouched below 0.8; above, rounded off so the output never reaches full scale. */
    fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.19f * tanh(((a - 0.8f) / 0.19f).toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    /** Floats to interleaved 16-bit little-endian PCM (what the AAC encoder takes). */
    fun pcm16(samples: FloatArray, from: Int = 0, to: Int = samples.size): ByteArray {
        val out = ByteArray((to - from) * 2)
        for (i in from until to) {
            val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[2 * (i - from)] = v.toByte(); out[2 * (i - from) + 1] = (v shr 8).toByte()
        }
        return out
    }
}
