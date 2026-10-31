package com.localmediatools.audio.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sample-rate conversion for any pair of rates with a Kaiser-windowed sinc filter (band-limited
 * interpolation, as in libsamplerate and SoX): flat to 95 % of the lower Nyquist frequency, about
 * 100 dB of attenuation above it. Streaming: interleaved float frames in, interleaved frames out.
 */
class SincResampler(private val inRate: Int, private val outRate: Int, private val ch: Int) {
    private val ratio = outRate.toDouble() / inRate          // output frames per input frame
    private val cutoff = 0.95 * min(1.0, ratio)               // as a fraction of the input's Nyquist frequency
    private val zeros = 36                                    // zero crossings each side (at the lower rate)
    private val res = 256                                     // table steps per zero crossing
    private val half: Int = ceil(zeros / cutoff).toInt()      // filter half-length in input samples
    private val table: FloatArray = run {
        val n = zeros * res + 2
        val beta = 10.0
        val i0b = besselI0(beta)
        FloatArray(n) { i ->
            val x = i.toDouble() / res                       // in zero crossings
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val r = x / zeros
            val w = if (r >= 1.0) 0.0 else besselI0(beta * sqrt(1 - r * r)) / i0b
            (sinc * w * cutoff).toFloat()
        }
    }

    private var buf = FloatArray(0)      // input frames not yet fully used (interleaved)
    private var bufStart = 0L            // index of buf's first frame in the whole input
    private var pos = 0.0                // input position (in frames) of the next output frame
    private var fed = 0L                 // input frames received
    private var made = 0L                // output frames produced
    private var primed = false

    /** Converts more input; returns the output frames ready (interleaved). */
    fun process(input: FloatArray): FloatArray {
        if (!primed) {
            // Silence before the start, so the first output frames have their full filter.
            buf = FloatArray(half * ch); bufStart = -half.toLong(); primed = true
        }
        buf = concat(buf, input)
        fed += input.size / ch
        return run(false)
    }

    /** Ends the stream: the remaining output, exactly round(input × ratio) frames in all. */
    fun flush(): FloatArray {
        if (!primed) return FloatArray(0)
        buf = concat(buf, FloatArray((half + 2) * ch))
        return run(true)
    }

    private fun run(end: Boolean): FloatArray {
        val total = Math.round(fed * ratio)
        val lastFrame = bufStart + buf.size / ch - 1
        val step = 1.0 / ratio
        var out = FloatArray(((max(0.0, (lastFrame - half - pos) / step) + 2).toInt()) * ch)
        var m = 0
        val scale = res * cutoff          // table steps per input sample
        val acc = DoubleArray(ch)
        while (true) {
            if (end && made >= total) break
            val center = floor(pos).toLong()
            if (center + half + 1 > lastFrame) break
            val frac = pos - center
            acc.fill(0.0)
            // Taps from center − half + 1 to center + half.
            for (k in -half + 1..half) {
                val d = abs(k - frac) * scale
                val di = d.toInt()
                if (di >= table.size - 1) continue
                val t = d - di
                val c = table[di] + (table[di + 1] - table[di]) * t
                val b = ((center + k - bufStart) * ch).toInt()
                for (j in 0 until ch) acc[j] += c * buf[b + j]
            }
            if (m + ch > out.size) out = out.copyOf(out.size * 2 + ch)
            for (j in 0 until ch) out[m++] = acc[j].toFloat()
            made++
            pos += step
        }
        // Keep what the next frames still need.
        val keepFrom = max(bufStart, floor(pos).toLong() - half - 1)
        val drop = (keepFrom - bufStart).toInt()
        if (drop > 0) { buf = buf.copyOfRange(drop * ch, buf.size); bufStart = keepFrom }
        return if (m == out.size) out else out.copyOf(m)
    }

    companion object {
        fun concat(a: FloatArray, b: FloatArray): FloatArray {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            return FloatArray(a.size + b.size).also { System.arraycopy(a, 0, it, 0, a.size); System.arraycopy(b, 0, it, a.size, b.size) }
        }

        /** The modified Bessel function of order 0 (for the Kaiser window). */
        fun besselI0(x: Double): Double {
            var sum = 1.0; var term = 1.0; var k = 1
            val q = x * x / 4
            while (term > 1e-12 * sum) { term *= q / (k.toDouble() * k); sum += term; k++ }
            return sum
        }
    }
}
