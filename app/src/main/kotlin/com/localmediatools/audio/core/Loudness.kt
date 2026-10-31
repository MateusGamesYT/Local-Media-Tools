package com.localmediatools.audio.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Loudness as broadcasters and streaming services measure it (ITU-R BS.1770-4, EBU R128): integrated
 * loudness in LUFS, loudness range in LU, sample peak and true peak (4× oversampled) in dBFS.
 * Feed interleaved float samples (−1…1) with [add], then read [result].
 */
class LoudnessMeter(private val rate: Int, private val channels: Int) {
    class Result(val integratedLufs: Double, val rangeLu: Double, val samplePeakDb: Double, val truePeakDb: Double) {
        override fun toString() = "I=%.2f LUFS LRA=%.2f LU peak=%.2f dBFS true peak=%.2f dBTP".format(java.util.Locale.ROOT, integratedLufs, rangeLu, samplePeakDb, truePeakDb)
    }

    /** One biquad per channel: y = b0 x + b1 x1 + b2 x2 − a1 y1 − a2 y2. */
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double, n: Int) {
        val x1 = DoubleArray(n); val x2 = DoubleArray(n); val y1 = DoubleArray(n); val y2 = DoubleArray(n)
        fun run(c: Int, x: Double): Double {
            val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
            x2[c] = x1[c]; x1[c] = x; y2[c] = y1[c]; y1[c] = y
            return y
        }
    }

    // K-weighting for any sample rate, from the analog prototypes behind BS.1770's 48 kHz coefficients
    // (the same derivation as libebur128 and pyloudnorm).
    private val shelf = run {
        val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
        val k = tan(PI * f0 / rate); val vh = 10.0.pow(g / 20); val vb = vh.pow(0.4996667741545416)
        val a0 = 1 + k / q + k * k
        Biquad((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0, channels)
    }
    private val highPass = run {
        val f0 = 38.13547087602444; val q = 0.5003270373238773
        val k = tan(PI * f0 / rate)
        val a0 = 1 + k / q + k * k
        Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0, channels)
    }

    /** Surround channels count 1.41 times (BS.1770); the LFE of 5.1 (4th channel) not at all. */
    private val weight = DoubleArray(channels) { c ->
        when {
            channels == 6 && c == 3 -> 0.0
            channels == 6 && (c == 4 || c == 5) -> 1.41
            channels == 5 && (c == 3 || c == 4) -> 1.41
            else -> 1.0
        }
    }

    // 100 ms steps; a 400 ms block (momentary) is four steps, a 3 s one (short-term) thirty.
    private val step = rate / 10
    private val stepPower = ArrayList<Double>()
    private var acc = 0.0
    private var inStep = 0
    private var peak = 0.0
    private val truePeak = TruePeak(channels)

    fun add(samples: FloatArray, n: Int = samples.size) {
        val frames = n / channels
        for (i in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val x = samples[i * channels + c].toDouble()
                val a = abs(x); if (a > peak) peak = a
                val y = highPass.run(c, shelf.run(c, x))
                sum += weight[c] * y * y
            }
            truePeak.add(samples, i * channels)
            acc += sum
            if (++inStep == step) { stepPower.add(acc / step); acc = 0.0; inStep = 0 }
        }
    }

    private fun blocks(steps: Int): DoubleArray {
        val n = stepPower.size - steps + 1
        if (n <= 0) return DoubleArray(0)
        val out = DoubleArray(n)
        var s = 0.0
        for (i in 0 until steps) s += stepPower[i]
        out[0] = s / steps
        for (i in 1 until n) { s += stepPower[i + steps - 1] - stepPower[i - 1]; out[i] = s / steps }
        return out
    }

    private fun lufs(power: Double) = if (power <= 0) Double.NEGATIVE_INFINITY else -0.691 + 10 * log10(power)

    fun result(): Result {
        // Integrated: 400 ms blocks, gated at −70 LUFS, then at 10 LU below their mean.
        val momentary = blocks(4)
        val abs = momentary.filter { lufs(it) > -70.0 }
        val integrated = if (abs.isEmpty()) Double.NEGATIVE_INFINITY else {
            val rel = lufs(abs.average()) - 10
            val gated = abs.filter { lufs(it) > rel }
            if (gated.isEmpty()) Double.NEGATIVE_INFINITY else lufs(gated.average())
        }
        // Range: 3 s blocks, gated at −70 LUFS and 20 LU below their mean; the spread from the 10th to the 95th percentile.
        val short = blocks(30).filter { lufs(it) > -70.0 }
        val range = if (short.isEmpty()) 0.0 else {
            val rel = lufs(short.average()) - 20
            val l = short.map { lufs(it) }.filter { it > rel }.sorted()
            if (l.size < 2) 0.0 else percentile(l, 0.95) - percentile(l, 0.10)
        }
        return Result(integrated, range, db(peak), db(max(peak, truePeak.max)))
    }

    /** The power of each 400 ms block (75 % overlap), for loudness measured over several files ([integrated]). */
    fun blockPowers(): DoubleArray = blocks(4)

    companion object {
        /** Integrated loudness (LUFS) of several files together, from their [blockPowers]: an album's loudness. */
        fun integrated(blocks: List<DoubleArray>): Double {
            fun lufs(power: Double) = if (power <= 0) Double.NEGATIVE_INFINITY else -0.691 + 10 * log10(power)
            val abs = blocks.flatMap { b -> b.filter { lufs(it) > -70.0 } }
            if (abs.isEmpty()) return Double.NEGATIVE_INFINITY
            val rel = lufs(abs.average()) - 10
            val gated = abs.filter { lufs(it) > rel }
            return if (gated.isEmpty()) Double.NEGATIVE_INFINITY else lufs(gated.average())
        }
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        val x = p * (sorted.size - 1)
        val i = x.toInt(); val f = x - i
        return if (i + 1 < sorted.size) sorted[i] * (1 - f) + sorted[i + 1] * f else sorted[i]
    }

    private fun db(v: Double) = if (v <= 0) Double.NEGATIVE_INFINITY else 20 * log10(v)
}

/**
 * True peak: the highest level between samples, found by 4× oversampling with a 48-tap
 * windowed-sinc interpolator per phase (BS.1770-4 annex 2 asks for at least 4× and this accuracy).
 */
internal class TruePeak(private val ch: Int) {
    private val taps = 12
    private val phases = 4
    private val coef = Array(phases) { p ->
        DoubleArray(taps) { k ->
            // Sample at t = (k − taps/2 + 1) − p/4 relative to the newest input, sinc with a Kaiser-like (Hann) window.
            val t = (k - taps / 2 + 1) - p.toDouble() / phases
            val sinc = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)
            val w = 0.5 + 0.5 * kotlin.math.cos(PI * t / (taps / 2.0 + 0.5))
            sinc * w
        }
    }
    private val hist = Array(ch) { DoubleArray(taps) }
    private var pos = 0
    var max = 0.0; private set

    fun add(frame: FloatArray, at: Int) {
        pos = (pos + 1) % taps
        for (c in 0 until ch) hist[c][pos] = frame[at + c].toDouble()
        for (c in 0 until ch) {
            val h = hist[c]
            for (p in 1 until phases) {
                var s = 0.0
                for (k in 0 until taps) s += coef[p][k] * h[(pos - k + taps) % taps]
                val a = abs(s)
                if (a > max) max = a
            }
        }
    }
}

/** dB ↔ linear gain. */
object Gain {
    fun db(linear: Double) = 20 * log10(linear)
    fun linear(db: Double) = 10.0.pow(db / 20)
    fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / max(1, x.size))
}
