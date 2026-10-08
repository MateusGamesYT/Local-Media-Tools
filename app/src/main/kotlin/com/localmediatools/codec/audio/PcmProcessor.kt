package com.localmediatools.codec.audio

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Streaming PCM conversion for re-encoded sound: channel mixing, sample-rate conversion and
 * tempo change without changing pitch (WSOLA, as in SoundTouch's TDStretch). Input and output are
 * interleaved 16-bit samples.
 */
class PcmProcessor(
    private val inRate: Int,
    private val inChannels: Int,
    val outRate: Int,
    val outChannels: Int,
    /** Playback speed: 2.0 plays twice as fast (half the duration), 0.5 half as fast. */
    private val speed: Double = 1.0,
) {
    private val resampler = if (inRate != outRate) Resampler(inRate.toDouble() / outRate, outChannels) else null
    private val stretcher = if (kotlin.math.abs(speed - 1.0) > 1e-3) TimeStretch(outRate, outChannels, speed) else null
    private var framesIn = 0L
    private var framesOut = 0L

    /** Converts [n] interleaved input samples; returns interleaved output samples (possibly empty). */
    fun process(input: ShortArray, n: Int = input.size): ShortArray {
        val frames = n / inChannels
        framesIn += frames
        var f = mix(input, frames)
        resampler?.let { f = it.process(f) }
        stretcher?.let { f = it.process(f) }
        return emit(f)
    }

    /** Ends the stream: returns what is still buffered, trimmed to the exact expected length. */
    fun flush(): ShortArray {
        var f = FloatArray(0)
        resampler?.let { f = it.flush() }
        stretcher?.let { s -> f = concat(s.process(f), s.flush()) }
        val expected = (framesIn * outRate.toDouble() / inRate / speed).roundToLong()
        val room = (expected - framesOut).coerceAtLeast(0)
        if (f.size / outChannels > room) f = f.copyOf((room * outChannels).toInt())
        return emit(f)
    }

    private fun emit(f: FloatArray): ShortArray {
        framesOut += f.size / outChannels
        return ShortArray(f.size) { i -> (f[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
    }

    /** Interleaved shorts → interleaved floats with [outChannels] channels. */
    private fun mix(input: ShortArray, frames: Int): FloatArray {
        val out = FloatArray(frames * outChannels)
        val s = 1f / 32768f
        for (i in 0 until frames) {
            val b = i * inChannels
            when {
                inChannels == outChannels -> for (c in 0 until outChannels) out[i * outChannels + c] = input[b + c] * s
                outChannels == 1 -> { var sum = 0f; for (c in 0 until inChannels) sum += input[b + c]; out[i] = sum * s / inChannels }
                inChannels == 1 -> for (c in 0 until outChannels) out[i * outChannels + c] = input[b] * s
                else -> {
                    // Stereo out from multichannel: front left/right plus the rest shared equally.
                    var l = input[b].toFloat(); var r = input[b + 1].toFloat()
                    for (c in 2 until inChannels) { val v = input[b + c] * 0.5f; l += v; r += v }
                    val norm = 1f / (1f + 0.5f * (inChannels - 2))
                    out[i * outChannels] = l * s * norm; out[i * outChannels + 1] = r * s * norm
                    for (c in 2 until outChannels) out[i * outChannels + c] = 0f
                }
            }
        }
        return out
    }

    companion object {
        fun concat(a: FloatArray, b: FloatArray): FloatArray {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            return FloatArray(a.size + b.size).also { System.arraycopy(a, 0, it, 0, a.size); System.arraycopy(b, 0, it, a.size, b.size) }
        }
    }
}

/** Linear-interpolation resampler; [step] = input frames per output frame. */
internal class Resampler(private val step: Double, private val ch: Int) {
    private var pos = 0.0                    // position of the next output frame, relative to [prev]
    private var prev = FloatArray(ch)        // last frame of the previous buffer (index -1)
    private var havePrev = false

    fun process(input: FloatArray): FloatArray {
        val n = input.size / ch
        if (n == 0) return FloatArray(0)
        // Frame k of the virtual stream: k = -1 → prev, k ≥ 0 → input.
        fun at(k: Int, c: Int) = if (k < 0) prev[c] else input[k * ch + c]
        if (!havePrev) { for (c in 0 until ch) prev[c] = input[c]; havePrev = true; pos = 1.0 }
        var out = FloatArray((((n + 1) / step) + 2).toInt() * ch)
        var m = 0
        // pos is measured from frame -1.
        while (pos <= n) {
            val k = kotlin.math.floor(pos).toInt() - 1
            val t = (pos - kotlin.math.floor(pos)).toFloat()
            if (m + ch > out.size) out = out.copyOf(out.size * 2)
            for (c in 0 until ch) {
                val a = at(k, c); val b = if (k + 1 < n) at(k + 1, c) else a
                out[m++] = a + (b - a) * t
            }
            pos += step
        }
        pos -= n
        for (c in 0 until ch) prev[c] = input[(n - 1) * ch + c]
        return out.copyOf(m)
    }

    fun flush(): FloatArray = FloatArray(0)
}

/**
 * WSOLA tempo change: sequences of [seqMs] are cut from the input at the nominal rate, each one
 * shifted by up to [seekMs] to where it best continues the previous one, and cross-faded over
 * [overlapMs]. Pitch stays the same.
 */
internal class TimeStretch(rate: Int, private val ch: Int, private val speed: Double, seqMs: Int = 40, seekMs: Int = 15, overlapMs: Int = 8) {
    private val overlap = max(16, rate * overlapMs / 1000)
    private val seq = max(overlap * 2 + 8, rate * seqMs / 1000)
    private val seek = max(8, rate * seekMs / 1000)
    private val nominalSkip = (seq - overlap) * speed
    private var buf = FloatArray(0)          // pending input frames (interleaved)
    private var skipFrac = 0.0
    private var mid = FloatArray(0)          // tail of the previous sequence (overlap frames)

    private val needed get() = max((nominalSkip + 0.999).toInt() + overlap, seek + seq)

    fun process(input: FloatArray): FloatArray {
        buf = PcmProcessor.concat(buf, input)
        val out = ArrayList<FloatArray>()
        while (buf.size / ch >= needed) out.add(step())
        return join(out)
    }

    fun flush(): FloatArray {
        // Pad with silence so the remaining input is consumed; the caller trims the excess.
        buf = PcmProcessor.concat(buf, FloatArray(needed * ch * 2))
        val out = ArrayList<FloatArray>()
        while (buf.size / ch >= needed) out.add(step())
        if (mid.isNotEmpty()) out.add(mid)
        mid = FloatArray(0)
        return join(out)
    }

    private fun step(): FloatArray {
        val off = if (mid.isEmpty()) 0 else bestOffset()
        val outLen = seq - overlap
        val res = FloatArray(outLen * ch)
        if (mid.isEmpty()) {
            System.arraycopy(buf, off * ch, res, 0, outLen * ch)
        } else {
            for (i in 0 until overlap) {
                val t = i.toFloat() / overlap
                for (c in 0 until ch) res[i * ch + c] = mid[i * ch + c] * (1 - t) + buf[(off + i) * ch + c] * t
            }
            System.arraycopy(buf, (off + overlap) * ch, res, overlap * ch, (outLen - overlap) * ch)
        }
        mid = buf.copyOfRange((off + outLen) * ch, (off + seq) * ch)
        // Advance by the nominal skip (fractional part carried over).
        skipFrac += nominalSkip
        val skip = skipFrac.toInt()
        skipFrac -= skip
        buf = buf.copyOfRange(minOf(skip * ch, buf.size), buf.size)
        return res
    }

    /**
     * Offset in [0, seek) where the input best matches [mid] (normalised cross-correlation on the
     * channel mix, every other sample: plenty for finding the period and four times cheaper).
     */
    private fun bestOffset(): Int {
        val refLen = overlap / 2
        val ref = FloatArray(refLen); val w = FloatArray(refLen)
        for (j in 0 until refLen) {
            val i = j * 2
            var v = 0f; for (c in 0 until ch) v += mid[i * ch + c]
            // Weight the middle of the overlap more (it matters most for the cross-fade).
            w[j] = (i * (overlap - i)).toFloat() / (overlap * overlap)
            ref[j] = v * w[j]
        }
        var best = 0; var bestScore = -Double.MAX_VALUE
        val stepSize = if (seek > 256) 2 else 1
        var o = 0
        while (o < seek) {
            var corr = 0.0; var norm = 1e-9
            for (j in 0 until refLen) {
                val b = (o + j * 2) * ch
                var x = 0f; for (c in 0 until ch) x += buf[b + c]
                corr += x * ref[j]; norm += x * x * w[j]
            }
            val score = corr / kotlin.math.sqrt(norm)
            if (score > bestScore) { bestScore = score; best = o }
            o += stepSize
        }
        return best
    }

    private fun join(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var p = 0
        for (a in parts) { System.arraycopy(a, 0, out, p, a.size); p += a.size }
        return out
    }
}
