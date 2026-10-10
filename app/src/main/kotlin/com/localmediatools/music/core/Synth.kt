package com.localmediatools.music.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Plays a [Score] with the app's own instruments into stereo samples — oscillators, noise and
 * filters, no recordings and no voices: a plucked lead or electric piano or piano, a pad, a bass and
 * a synthesized drum kit, then a small room reverb and a limiter. Output: interleaved stereo floats
 * (−1..1) at [rate].
 *
 * Built to be light on a phone: notes are added straight into the output (one stereo buffer and a
 * mono reverb send, nothing else of the piece's length), envelopes decay by a constant factor per
 * sample and oscillators read a sine table, instead of calling exp() and sin() for every sample.
 */
class Synth(private val rate: Int = 48_000) {
    private val dt = 1.0 / rate

    fun render(score: Score, seconds: Double = score.seconds, fadeOut: Double = 2.5): FloatArray {
        val frames = (seconds * rate).toInt().coerceAtLeast(1)
        val out = FloatArray(frames * 2)
        val send = FloatArray(frames)   // mono reverb send
        val step = score.stepSeconds
        val mood = score.mood
        fun at(stepIndex: Int) = (stepIndex * step * rate).toInt()
        // Melody.
        for (n in score.melody) {
            val start = at(n.start); val len = ((n.end - n.start) * step * rate).toInt()
            when (mood) {
                Mood.UPBEAT -> voice(out, send, start, len, n.pitch, n.velocity * 0.45f, pan = 0.15f, sendAmt = 0.25f, kind = Kind.PLUCK)
                Mood.CHILL -> voice(out, send, start, len, n.pitch, n.velocity * 0.5f, pan = -0.1f, sendAmt = 0.3f, kind = Kind.EPIANO)
                Mood.CINEMATIC -> voice(out, send, start, len, n.pitch, n.velocity * 0.5f, pan = 0f, sendAmt = 0.45f, kind = Kind.PIANO)
            }
        }
        // Pad (chords).
        for (n in score.pads) {
            val start = at(n.start); val len = ((n.end - n.start) * step * rate).toInt()
            val g = when (mood) { Mood.UPBEAT -> 0.07f; Mood.CHILL -> 0.09f; Mood.CINEMATIC -> 0.1f }
            voice(out, send, start, len, n.pitch, g, pan = if (n.pitch % 2 == 0) -0.35f else 0.35f, sendAmt = 0.5f, kind = Kind.PAD)
        }
        // Bass.
        for (n in score.bass) {
            val start = at(n.start); val len = ((n.end - n.start) * step * rate).toInt()
            voice(out, send, start, len, n.pitch, n.velocity * if (mood == Mood.CINEMATIC) 0.2f else 0.24f, pan = 0f, sendAmt = 0.05f,
                kind = if (mood == Mood.UPBEAT) Kind.SAW_BASS else Kind.SINE_BASS)
        }
        // Drums.
        val drumGain = when (mood) { Mood.UPBEAT -> 0.55f; Mood.CHILL -> 0.4f; Mood.CINEMATIC -> 0.35f }
        var noise = 0x2545F491
        for (h in score.drums) noise = drum(out, send, at(h.step), h.drum, h.velocity * drumGain, noise)
        // Room reverb from the send, added to the mix.
        reverb(send, out, if (mood == Mood.CINEMATIC) 0.86f else 0.78f, 0.6f)
        // Fades.
        val fadeFrames = (fadeOut * rate).toInt().coerceAtMost(frames)
        val fadeIn = (0.02 * rate).toInt()
        var peak = 0f
        for (i in 0 until frames) {
            var g = 1f
            if (i >= frames - fadeFrames) g = ((frames - i).toFloat() / fadeFrames).pow(1.5f)
            if (i < fadeIn) g *= i.toFloat() / fadeIn
            val l = out[2 * i] * g; val r = out[2 * i + 1] * g
            out[2 * i] = l; out[2 * i + 1] = r
            peak = max(peak, max(abs(l), abs(r)))
        }
        // Normalise to −3 dBFS and keep peaks round (soft limiter).
        val norm = if (peak > 0f) 0.708f / peak else 1f
        val gain = min(norm * 1.4f, 4f)
        for (i in out.indices) out[i] = (softClip((out[i] * gain).toDouble()) * 0.85).toFloat()
        return out
    }

    /** tanh, with its series where the signal is quiet (most samples; within 2·10⁻⁵ there). */
    private fun softClip(y: Double): Double {
        if (abs(y) >= 0.3) return tanh(y)
        val y2 = y * y
        return y * (1 - y2 / 3 + 2 * y2 * y2 / 15)
    }

    private enum class Kind { PLUCK, EPIANO, PIANO, PAD, SAW_BASS, SINE_BASS }

    private fun hz(pitch: Int) = 440.0 * 2.0.pow((pitch - 69) / 12.0)

    /** The factor per sample that makes exp(−k·t). */
    private fun decay(k: Double) = exp(-k * dt)

    /** One note: an oscillator of [kind], its envelope, panned into [out] with some into [send]. */
    private fun voice(out: FloatArray, send: FloatArray, start: Int, length: Int, pitch: Int, gain: Float, pan: Float, sendAmt: Float, kind: Kind) {
        val frames = send.size
        if (start >= frames) return
        val f = hz(pitch)
        val inc = f * dt
        val release = when (kind) { Kind.PAD -> 0.6; Kind.PIANO, Kind.EPIANO -> 0.35; Kind.PLUCK -> 0.12; else -> 0.06 }
        val total = min(frames - start, length + (release * rate).toInt())
        val lg = gain * sqrt((1 - pan) / 2f) * 1.414f
        val rg = gain * sqrt((1 + pan) / 2f) * 1.414f
        val sg = gain * sendAmt
        val relFactor = decay(4 / release)
        var rel = 1.0
        // Envelope parts, each a decaying exponential updated by a constant factor.
        var d1 = 1.0; var d2 = 1.0; var d3 = 1.0
        val (m1, m2, m3) = when (kind) {
            Kind.PLUCK -> Triple(decay(4.5), decay(8.0), 1.0)
            Kind.EPIANO -> Triple(decay(2.2), decay(5.0), decay(3.0))
            Kind.PIANO -> Triple(decay(0.6), decay(3.0), decay(1.5))
            Kind.SAW_BASS, Kind.SINE_BASS -> Triple(decay(6.0), 1.0, 1.0)
            Kind.PAD -> Triple(1.0, 1.0, 1.0)
        }
        val attack = when (kind) { Kind.PLUCK -> 0.004; Kind.EPIANO -> 0.003; Kind.PIANO -> 0.002; Kind.PAD -> 0.35; else -> 0.006 }
        val attackFrames = attack * rate
        var p = 0.0; var p2 = 0.0; var p3 = 0.0
        var lp = 0.0; var lp2 = 0.0
        // Electric piano: the tine; piano: its partials (each with its own phase and decay).
        var tine = 0.0; var tineDecay = 1.0; val tineFactor = decay(14.0)
        val partials = if (kind == Kind.PIANO) (4..9).filter { f * it < 12_000 } else emptyList()
        val pInc = DoubleArray(partials.size) { inc * partials[it] * (1 + 0.0004 * partials[it] * partials[it]) }
        val pAmp = DoubleArray(partials.size) { 0.3 / (partials[it] * partials[it] / 4.0) }
        val pFac = DoubleArray(partials.size) { decay(2.0 + partials[it]) }
        val pPh = DoubleArray(partials.size); val pEnv = DoubleArray(partials.size) { 1.0 }
        for (k in 0 until total) {
            val a = if (k < attackFrames) k / attackFrames else 1.0
            val env = a * when (kind) {
                Kind.PLUCK -> d1
                Kind.EPIANO -> 0.35 + 0.65 * d1
                Kind.PIANO -> 0.25 * d1 + 0.75 * d2
                Kind.PAD -> 1.0
                Kind.SAW_BASS, Kind.SINE_BASS -> 0.7 + 0.3 * d1
            } * rel
            val s: Double = when (kind) {
                Kind.PLUCK -> {
                    // Two slightly detuned squares through a closing low-pass.
                    val raw = (if (p < 0.5) 0.5 else -0.5) + (if (p2 < 0.5) 0.5 else -0.5)
                    val cut = 0.07 + 0.3 * d2
                    lp += cut * (raw - lp); lp2 += cut * (lp - lp2)
                    p = wrap(p + inc); p2 = wrap(p2 + inc * 1.004)
                    lp2
                }
                Kind.EPIANO -> {
                    // Two-operator FM: a soft bell that mellows, plus a short high tine at the attack.
                    val index = 1.6 * d2 + 0.2
                    val v = SineTable.at(p + index * SineTable.at(p) / (2 * PI)) * 0.8 + SineTable.at(p2) * 0.08 * d3 + SineTable.at(tine) * 0.06 * tineDecay
                    p = wrap(p + inc); p2 = wrap(p2 + 2 * inc); tine = wrap(tine + 7.01 * inc); tineDecay *= tineFactor
                    v
                }
                Kind.PIANO -> {
                    // A few decaying harmonics, slightly stretched; the upper ones (the hammer's brightness) fade fast.
                    var h = SineTable.at(p) * 0.7 + SineTable.at(p2) * 0.3 * d3 + SineTable.at(p3) * 0.16 * d2
                    for (j in partials.indices) { h += SineTable.at(pPh[j]) * pAmp[j] * pEnv[j]; pPh[j] = wrap(pPh[j] + pInc[j]); pEnv[j] *= pFac[j] }
                    p = wrap(p + inc); p2 = wrap(p2 + 2.003 * inc); p3 = wrap(p3 + 3.01 * inc)
                    h
                }
                Kind.PAD -> {
                    // Three detuned saws, gently filtered.
                    val raw = (p + p2 + p3) * 2 / 3 - 1.0
                    lp += 0.06 * (raw - lp); lp2 += 0.06 * (lp - lp2)
                    p = wrap(p + inc); p2 = wrap(p2 + inc * 1.006); p3 = wrap(p3 + inc * 0.995)
                    lp2 * 1.4
                }
                Kind.SAW_BASS -> {
                    val raw = p * 2 - 1
                    lp += 0.1 * (raw - lp); lp2 += 0.1 * (lp - lp2)
                    val v = tanh(lp2 * 2.2) * 0.8 + SineTable.at(p) * 0.3
                    p = wrap(p + inc)
                    v
                }
                // Overtones carry the bass line on small speakers that can't play its fundamental.
                Kind.SINE_BASS -> { val v = SineTable.at(p) + 0.35 * SineTable.at(2 * p) + 0.15 * SineTable.at(3 * p); p = wrap(p + inc); v }
            }
            d1 *= m1; d2 *= m2; d3 *= m3
            if (k >= length) rel *= relFactor
            val v = (s * env).toFloat()
            val i = start + k
            out[2 * i] += v * lg; out[2 * i + 1] += v * rg
            send[i] += v * sg
        }
    }

    private fun wrap(x: Double) = if (x >= 1.0) x - floor(x) else x

    /** One drum hit (kick, snare, hats, toms, crash, ride). Returns the noise generator's next state. */
    private fun drum(out: FloatArray, send: FloatArray, start: Int, drum: Int, gain: Float, seed: Int): Int {
        val frames = send.size
        if (start >= frames) return seed
        var n = seed
        fun noise(): Double { n = n xor (n shl 13); n = n xor (n ushr 17); n = n xor (n shl 5); return n / 2147483648.0 }
        val dur = when (drum) { 0 -> 0.45; 1 -> 0.25; 2 -> 0.06; 3 -> 0.35; 4, 5, 6 -> 0.4; 7 -> 1.6; else -> 0.9 }
        val total = min(frames - start, (dur * rate).toInt())
        val pan = when (drum) { 2, 3 -> 0.3f; 4 -> -0.3f; 6 -> 0.25f; 7 -> -0.2f; 8 -> 0.35f; else -> 0f }
        val lg = gain * sqrt((1 - pan) / 2f) * 1.414f; val rg = gain * sqrt((1 + pan) / 2f) * 1.414f
        val sg = gain * if (drum == 0) 0.05f else 0.15f
        var phase = 0.0; var hp = 0.0; var last = 0.0; var bp = 0.0; var bp2 = 0.0
        var r1 = 0.0; var r2 = 0.0; var r3 = 0.0
        val tomHz = when (drum) { 4 -> 95.0; 5 -> 130.0; else -> 175.0 }
        // exp(−k·t) for the rates each drum uses.
        val (k1, k2) = when (drum) { 0 -> 35.0 to 7.0; 1 -> 16.0 to 25.0; 2 -> 60.0 to 0.0; 3 -> 9.0 to 0.0; 4, 5, 6 -> 20.0 to 8.0; 7 -> 2.2 to 0.0; else -> 4.0 to 0.0 }
        val f1 = decay(k1); val f2 = decay(k2); val f3 = decay(300.0)
        var e1 = 1.0; var e2 = 1.0; var e3 = 1.0
        for (k in 0 until total) {
            val s = when (drum) {
                0 -> { // kick: falling sine and a click
                    val v = SineTable.at(phase) * e2 + noise() * 0.15 * e3
                    phase = wrap(phase + (45 + 110 * e1) * dt)
                    v
                }
                1 -> { // snare: noise band and a tone
                    val x = noise(); bp += 0.35 * (x - bp)
                    val v = (x - bp) * 0.7 * e1 + SineTable.at(phase) * 0.45 * e2
                    phase = wrap(phase + 185 * dt)
                    v
                }
                2, 3 -> { // hats: high-passed noise
                    val x = noise(); hp = 0.9 * (hp + x - last); last = x
                    hp * 0.5 * e1
                }
                4, 5, 6 -> { val v = SineTable.at(phase) * e2; phase = wrap(phase + tomHz * (1 + 0.5 * e1) * dt); v }
                7 -> { // crash
                    val x = noise(); hp = 0.95 * (hp + x - last); last = x
                    hp * 0.45 * e1
                }
                else -> { // ride: metallic tones and a little noise
                    val m = SineTable.at(r1) * 0.3 + SineTable.at(r2) * 0.25 + SineTable.at(r3) * 0.2
                    r1 = wrap(r1 + 2400 * dt); r2 = wrap(r2 + 3530 * dt); r3 = wrap(r3 + 5170 * dt)
                    val x = noise(); bp2 += 0.6 * (x - bp2)
                    (m + (x - bp2) * 0.2) * 0.4 * e1
                }
            }
            e1 *= f1; e2 *= f2; e3 *= f3
            val v = s.toFloat()
            val i = start + k
            out[2 * i] += v * lg; out[2 * i + 1] += v * rg
            send[i] += v * sg
        }
        return n
    }

    /**
     * A small stereo room (Schroeder: four combs and two all-passes per side, decorrelated), run
     * over the mono [send] and added to [out] (interleaved stereo) at [wetGain].
     */
    private fun reverb(send: FloatArray, out: FloatArray, feedback: Float, wetGain: Float) {
        val scale = rate / 44_100.0
        for (ch in 0 until 2) {
            val combs = intArrayOf(1116, 1188, 1277, 1356).map { FloatArray(((it + ch * 23) * scale).toInt()) }
            val idx = IntArray(4)
            val damp = FloatArray(4)
            val aps = intArrayOf(556, 441).map { FloatArray(((it + ch * 17) * scale).toInt()) }
            val aIdx = IntArray(2)
            for (i in send.indices) {
                val x = send[i]
                var s = 0f
                for (c in 0 until 4) {
                    val b = combs[c]; val y = b[idx[c]]
                    damp[c] = y * 0.7f + damp[c] * 0.3f
                    b[idx[c]] = x + damp[c] * feedback
                    idx[c] = if (idx[c] + 1 == b.size) 0 else idx[c] + 1
                    s += y
                }
                var o = s * 0.25f
                for (a in 0 until 2) {
                    val b = aps[a]; val y = b[aIdx[a]]
                    b[aIdx[a]] = o + y * 0.5f
                    o = y - o * 0.5f
                    aIdx[a] = if (aIdx[a] + 1 == b.size) 0 else aIdx[a] + 1
                }
                out[2 * i + ch] += o * wetGain
            }
        }
    }

    /** sin(2π·x) for x in cycles, from a table with linear interpolation (error below 10⁻⁶). */
    private object SineTable {
        private const val N = 4096
        private val t = FloatArray(N + 1) { sin(2 * PI * it / N).toFloat() }
        fun at(cycles: Double): Double {
            val x = (cycles - floor(cycles)) * N
            val i = x.toInt()
            val f = x - i
            return t[i] + (t[i + 1] - t[i]) * f
        }
    }
}
