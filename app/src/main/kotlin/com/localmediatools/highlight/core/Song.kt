package com.localmediatools.highlight.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The user's own song as a highlight video's music: where its beat is (so cuts fall on it, as with
 * the music the app writes), where to start (the first beat once the music has begun, not silence),
 * and fitted to the video: played from there, repeated on whole bars with a crossfade when it is
 * shorter than the video, at the level the app's own music has. Interleaved stereo floats.
 *
 * The beat: an onset strength envelope (rises of loudness in a low band — kick, bass — and a high
 * band — hi-hats, snares — and overall), its autocorrelation over 60–200 BPM weighted towards
 * 120 BPM (D. Ellis, "Beat tracking by dynamic programming", 2007, the same prior), then the phase
 * where the envelope's peaks line up. Songs without a clear beat get none and the video keeps its
 * own half-second rhythm. Measured in buildtools/highlight/song_eval.py.
 */
object Song {
    /** Envelope frames: 512 samples (10.7 ms at 48 kHz). */
    const val HOP = 512
    /** Each frame's loudness is taken over a Hann window this long (85 ms), centred [WINDOW] / 2 after the frame's start. */
    const val WINDOW = 8 * HOP
    /** Seconds of the song looked at for the beat (after its start). */
    const val ANALYSE_SECONDS = 60.0
    /**
     * Typical rise of log loudness per frame below which a band holds no attacks (its scale floor):
     * music has 0.035–0.19 in at least one band, a held chord 0.001–0.007 (HighlightSongTest).
     */
    const val MIN_RISE = 0.02
    /** Below this, the autocorrelation peak is not a beat. */
    @Volatile var MIN_CONFIDENCE = 0.08
    /** The level the app's own music has (RMS 0.10–0.13 over its moods, measured in HighlightSongTest). */
    var TARGET_RMS = 0.12f

    /** The confidence of the last beat that was turned down (for the evaluation). */
    @Volatile var lastConfidence = 0.0

    class Beat(val bpm: Double, /** Seconds from the start of the analysed audio to a beat. */ val phase: Double, val confidence: Double) {
        val seconds: Double get() = 60.0 / bpm
    }

    /** How the song is used: from [startFrame], with [beat] (null: none found), repeating every [loopFrames] (0: no need). */
    class Fit(val startFrame: Int, val beat: Beat?, val loopFrames: Int, val crossFrames: Int, val gain: Float) {
        /** The planner's beat (cuts fall on it); half a second without one. */
        val beatSeconds: Double get() = beat?.seconds ?: 0.5
    }

    /** First stereo frame of the music: where a 50 ms window first gets louder than −45 dBFS (0 for a song that starts at once). */
    fun soundStart(stereo: FloatArray, rate: Int): Int {
        val win = max(1, rate / 20)
        val frames = stereo.size / 2
        var i = 0
        while (i + win <= frames) {
            var s = 0.0
            for (k in i until i + win) { val m = (stereo[2 * k] + stereo[2 * k + 1]) * 0.5; s += m * m }
            if (sqrt(s / win) > 0.0056) return i
            i += win
        }
        return 0
    }

    /**
     * Where the song's ending starts: the end of the last half second still at least half as loud
     * (−6 dB) as the song's typical level (its median), so a fade-out or a final ringing chord is
     * not repeated.
     */
    fun soundEnd(stereo: FloatArray, rate: Int, from: Int): Int {
        val total = stereo.size / 2
        val win = rate / 2
        val levels = ArrayList<Double>()
        var i = from
        while (i + win <= total) { levels.add(rms(stereo, i, i + win)); i += win }
        if (levels.size < 4) return total
        val median = levels.sorted()[levels.size / 2]
        val last = levels.indexOfLast { it >= 0.5 * median }
        return if (last < 0) total else minOf(total, from + (last + 1) * win)
    }

    /**
     * Onset strength, one value per [HOP] samples of [stereo] from frame [from] for [count] frames;
     * value t describes the moment t × [HOP] + [WINDOW] / 2.
     */
    fun onsets(stereo: FloatArray, rate: Int, from: Int = 0, count: Int = stereo.size / 2 - from): FloatArray {
        val n = (count - WINDOW) / HOP + 1
        if (n < 2) return FloatArray(0)
        // One-pole filters: low band below ~150 Hz, high band above ~2 kHz; their power per sample.
        val aLow = (1 - exp(-2 * PI * 150 / rate)).toFloat()
        val aHigh = (1 - exp(-2 * PI * 2000 / rate)).toFloat()
        var low = 0f; var smooth = 0f
        val m = (n - 1) * HOP + WINDOW
        val pl = FloatArray(m); val ph = FloatArray(m); val pf = FloatArray(m)
        for (k in 0 until m) {
            val i = from + k
            val x = (stereo[2 * i] + stereo[2 * i + 1]) * 0.5f
            low += aLow * (x - low)
            smooth += aHigh * (x - smooth)
            val high = x - smooth
            pl[k] = low * low; ph[k] = high * high; pf[k] = x * x
        }
        val win = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2 * PI * (it + 0.5) / WINDOW)).toFloat() }
        val bands = Array(3) { DoubleArray(n) }
        for (t in 0 until n) {
            var el = 0.0; var eh = 0.0; var ef = 0.0
            val o = t * HOP
            for (k in 0 until WINDOW) { val w = win[k]; el += w * pl[o + k]; eh += w * ph[o + k]; ef += w * pf[o + k] }
            bands[0][t] = ln(1 + 2000 * el / WINDOW); bands[1][t] = ln(1 + 2000 * eh / WINDOW); bands[2][t] = ln(1 + 2000 * ef / WINDOW)
        }
        val env = FloatArray(n)
        var anyAttacks = false
        for (b in bands) {
            val d = DoubleArray(n) { t -> if (t == 0) 0.0 else max(0.0, b[t] - b[t - 1]) }
            // Each band on the same footing, but a band with no real attacks (only ripples) stays small.
            val sd = sqrt(d.sumOf { it * it } / n)
            if (sd >= MIN_RISE) anyAttacks = true
            for (t in 0 until n) env[t] += (d[t] / max(sd, MIN_RISE)).toFloat()
        }
        // No band rises like an attack (a held chord, a drone): nothing to keep time with.
        if (!anyAttacks) return FloatArray(n)
        // Take out the slow part (the mean over about half a second around each frame), keep the rises.
        val w = 24
        val prefix = DoubleArray(n + 1)
        for (t in 0 until n) prefix[t + 1] = prefix[t] + env[t]
        return FloatArray(n) { t ->
            val a = max(0, t - w); val b = min(n, t + w + 1)
            max(0f, env[t] - ((prefix[b] - prefix[a]) / (b - a)).toFloat())
        }
    }

    /** The beat of [env] (frames of [HOP] samples at [rate]), or null when there is none clear enough. */
    fun beat(env: FloatArray, rate: Int): Beat? {
        val fps = rate.toDouble() / HOP
        val minLag = floor(fps * 60 / 200).toInt(); val maxLag = (fps * 60 / 60).roundToInt()
        if (env.size < maxLag * 4) return null
        val mean = env.average()
        val e = DoubleArray(env.size) { env[it] - mean }
        val r0 = e.sumOf { it * it }.takeIf { it > 1e-9 } ?: return null
        fun ac(lag: Int): Double { var s = 0.0; for (t in lag until e.size) s += e[t] * e[t - lag]; return s / r0 * e.size / (e.size - lag) }
        val r = DoubleArray(4 * maxLag + 2) { if (it >= minLag - 1 && it < e.size / 2) ac(it) else 0.0 }
        // A beat repeats: two, three and four beats on line up too (a dotted or triplet pattern doesn't).
        fun at(x: Double): Double { val i = x.toInt(); val f = x - i; return if (i + 1 < r.size) r[i] * (1 - f) + r[i + 1] * f else 0.0 }
        var best = -1; var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val oct = ln(lag / fps / 0.5) / ln(2.0)
            val comb = (r[lag] + at(2.0 * lag) / 2 + at(3.0 * lag) / 3 + at(4.0 * lag) / 4) / (1 + 1.0 / 2 + 1.0 / 3 + 1.0 / 4)
            val score = comb * exp(-0.5 * oct * oct / (0.9 * 0.9))
            if (r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1] && score > bestScore) { bestScore = score; best = lag }
        }
        if (best < 0 || r[best] < MIN_CONFIDENCE) { lastConfidence = if (best < 0) 0.0 else r[best]; return null }
        // Between frames: the parabola through the peak and its neighbours, then the period and phase
        // that line the envelope's peaks up best over the whole stretch (a small error in the period
        // would otherwise add up over a minute of beats).
        val den = r[best - 1] - 2 * r[best] + r[best + 1]
        val p0 = best + if (abs(den) > 1e-12) (0.5 * (r[best - 1] - r[best + 1]) / den).coerceIn(-0.5, 0.5) else 0.0
        fun line(period: Double, phase: Double): Double {
            var s = 0.0; var t = phase
            while (t < env.size - 1) {
                val i = t.toInt(); val f = t - i
                s += env[i] * (1 - f) + env[i + 1] * f
                t += period
            }
            return s
        }
        var period = p0; var bestPhase = 0.0; var bestSum = -1.0
        var q = p0 * 0.985
        while (q <= p0 * 1.015) {
            var ph = 0.0
            while (ph < q) {
                val v = line(q, ph)
                if (v > bestSum) { bestSum = v; period = q; bestPhase = ph }
                ph += 0.5
            }
            q += 0.02
        }
        return Beat(60 * fps / period, bestPhase / fps, r[best])
    }

    /** RMS of frames [from] until [to] of [stereo]. */
    fun rms(stereo: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in 2 * from until 2 * to) s += stereo[i] * stereo[i]
        return if (to > from) sqrt(s / (2 * (to - from))) else 0.0
    }

    /** How [stereo] (the decoded song, at [rate]) is played under a video of [frames] frames. */
    fun fit(stereo: FloatArray, rate: Int, frames: Int): Fit {
        val total = stereo.size / 2
        val sound = soundStart(stereo, rate)
        val count = min(total - sound, (ANALYSE_SECONDS * rate).toInt())
        val beat = beat(onsets(stereo, rate, sound, count), rate)
        var start = sound
        if (beat != null) {
            // The first beat once the music has begun (10 ms early: the cut lands as the beat sounds).
            val ph = (beat.phase * rate).roundToInt() + WINDOW / 2
            start = (sound + ph - rate / 100).coerceIn(0, total)
        }
        val left = total - start
        var loop = 0; var cross = 0
        if (left < frames) {
            // Repeated: only the part before the song's ending, which then runs on through each crossfade.
            val end = soundEnd(stereo, rate, start)
            val usable = end - start

            cross = if (beat != null) min((beat.seconds * rate).roundToInt(), rate) else rate / 2
            val bar = beat?.let { it.seconds * 4 * rate }
            loop = if (bar != null && usable - cross >= bar) (floor((usable - cross) / bar) * bar).roundToInt() else usable - cross
            if (loop < rate / 2) { loop = left; cross = min(cross, left / 4) }   // a very short sound: repeat it whole
            cross = min(cross, left - loop)
        }
        val used = if (loop > 0) loop else min(left, frames)
        val level = rms(stereo, start, start + used)
        val gain = if (level > 1e-5) (TARGET_RMS / level).toFloat().coerceIn(0.25f, 4f) else 1f
        return Fit(start, beat, loop, cross, gain)
    }

    /** The song for [frames] frames of video, as [fit] says (a new buffer). */
    fun render(stereo: FloatArray, fit: Fit, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val total = stereo.size / 2
        val loop = fit.loopFrames; val xf = fit.crossFrames
        for (k in 0 until frames) {
            var l: Float; var r: Float
            if (loop <= 0) {
                val i = fit.startFrame + k
                if (i >= total) break
                l = stereo[2 * i]; r = stereo[2 * i + 1]
            } else {
                val pass = k / loop; val j = k % loop
                val i = fit.startFrame + j
                l = stereo[2 * i]; r = stereo[2 * i + 1]
                if (pass > 0 && j < xf) {
                    // The end of the previous pass runs on past the loop point while this one comes in.
                    val tail = fit.startFrame + loop + j
                    val a = sin(PI / 2 * j / xf).toFloat(); val b = cos(PI / 2 * j / xf).toFloat()
                    val tl = if (tail < total) stereo[2 * tail] else 0f; val tr = if (tail < total) stereo[2 * tail + 1] else 0f
                    l = l * a + tl * b; r = r * a + tr * b
                }
            }
            out[2 * k] = l * fit.gain; out[2 * k + 1] = r * fit.gain
        }
        return out
    }
}
