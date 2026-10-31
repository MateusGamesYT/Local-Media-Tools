package com.localmediatools.audio.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** How a fade's level changes over its length. */
enum class FadeCurve {
    /** Straight from silence to full level. */
    LINEAR,
    /** Starts and ends gently (half a cosine): sounds the most natural for fades in and out. */
    SMOOTH,
    /** Constant power (quarter sine): for crossfades between two sounds, with no dip in the middle. */
    EQUAL_POWER;

    /** The gain at [t] (0 = start of a fade-in, 1 = full level). */
    fun gain(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return when (this) { LINEAR -> x; SMOOTH -> 0.5 - 0.5 * cos(PI * x); EQUAL_POWER -> sin(PI / 2 * x) }
    }
}

/** Interleaved float buffers: channel layouts and gain. */
object Pcm {
    /**
     * [frames] frames of [input] (interleaved, [inCh] channels) as [outCh] channels: mono is copied to
     * both sides, stereo averaged to mono, and more channels (5.1 and up, in Android's order: front
     * left, right, centre, LFE, back left, right…) mixed down with the usual weights (centre and
     * surrounds at −3 dB, LFE left out).
     */
    fun channels(input: FloatArray, frames: Int, inCh: Int, outCh: Int): FloatArray {
        if (inCh == outCh) return if (input.size == frames * inCh) input else input.copyOf(frames * inCh)
        val out = FloatArray(frames * outCh)
        val k = 0.70710677f
        for (i in 0 until frames) {
            val b = i * inCh
            var l: Float; var r: Float
            when (inCh) {
                1 -> { l = input[b]; r = l }
                2 -> { l = input[b]; r = input[b + 1] }
                3 -> { l = input[b] + k * input[b + 2]; r = input[b + 1] + k * input[b + 2] }
                else -> {
                    l = input[b] + k * input[b + 2]; r = input[b + 1] + k * input[b + 2]
                    var c = 4
                    while (c + 1 < inCh) { l += k * input[b + c]; r += k * input[b + c + 1]; c += 2 }
                }
            }
            if (outCh == 1) out[i] = 0.5f * (l + r) else { out[2 * i] = l; out[2 * i + 1] = r }
        }
        return out
    }

    fun gain(x: FloatArray, n: Int, g: Float) { if (g != 1f) for (i in 0 until n) x[i] *= g }

    fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size }); var p = 0
        for (a in parts) { System.arraycopy(a, 0, out, p, a.size); p += a.size }
        return out
    }
}

/**
 * The highest level between samples (as [TruePeak] measures it) for each frame: a 4× oversampling
 * interpolator, [DELAY] frames behind its input.
 */
internal class PeakTracker(private val ch: Int) {
    private val taps = 12
    private val phases = 4
    private val coef = Array(phases) { p ->
        DoubleArray(taps) { k ->
            val t = (k - taps / 2 + 1) - p.toDouble() / phases
            val sinc = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)
            sinc * (0.5 + 0.5 * cos(PI * t / (taps / 2.0 + 0.5)))
        }
    }
    private val hist = Array(ch) { DoubleArray(taps) }
    private var pos = 0

    /** Takes the frame at [at] and returns the peak around the frame [DELAY] earlier. */
    fun add(x: FloatArray, at: Int): Double {
        pos = (pos + 1) % taps
        for (c in 0 until ch) hist[c][pos] = x[at + c].toDouble()
        var m = 0.0
        for (c in 0 until ch) {
            val h = hist[c]
            m = max(m, abs(h[(pos - DELAY + taps) % taps]))
            for (p in 1 until phases) {
                var s = 0.0
                for (k in 0 until taps) s += coef[p][k] * h[(pos - k + taps) % taps]
                m = max(m, abs(s))
            }
        }
        return m
    }

    companion object { const val DELAY = 6 }
}

/**
 * A look-ahead peak limiter: keeps the signal's true peak (between samples too) under [ceilingDb]
 * without clipping. The gain goes down smoothly over [lookaheadMs] before a peak arrives and comes
 * back over about [releaseMs]; between peaks the sound is untouched. Output is delayed internally
 * but [process] and [flush] together return exactly as many frames as went in.
 */
class Limiter(rate: Int, private val ch: Int, ceilingDb: Double = -1.0, lookaheadMs: Double = 3.0, releaseMs: Double = 80.0) {
    private val ceiling = 10.0.pow(ceilingDb / 20)
    private val w = max(16, (rate * lookaheadMs / 1000).roundToInt())
    private val delay = PeakTracker.DELAY + w - 1
    private val tracker = PeakTracker(ch)
    private val release = 1 - exp(-1.0 / (rate * releaseMs / 1000))
    // Ring buffer of input frames (for the delay) and of hold values (for the moving average).
    private val ring = FloatArray((delay + 1) * ch)
    private var t = 0L                         // input frames taken
    private var emitted = 0L
    // Sliding minimum of the target gains over w frames (monotonic deque of (index, value)).
    private val dqIdx = LongArray(w + 1); private val dqVal = DoubleArray(w + 1)
    private var dqHead = 0; private var dqSize = 0
    private val holds = DoubleArray(w) { 1.0 }
    private var holdSum = w.toDouble()
    private var holdPos = 0
    private var g = 1.0
    /** The lowest gain applied so far (for reports). */
    var minGain = 1.0; private set

    fun process(input: FloatArray, n: Int = input.size): FloatArray {
        val frames = n / ch
        val out = FloatArray(frames * ch)
        var o = 0
        for (i in 0 until frames) { if (step(input, i * ch, out, o)) o += ch }
        return if (o == out.size) out else out.copyOf(o)
    }

    /** The last [delay] frames. */
    fun flush(): FloatArray {
        val zero = FloatArray(ch)
        val left = (t - emitted).toInt()
        val out = FloatArray(left * ch)
        var o = 0
        val inputs = t
        while (emitted < inputs) { if (step(zero, 0, out, o)) o += ch }
        return out
    }

    private fun step(x: FloatArray, at: Int, out: FloatArray, o: Int): Boolean {
        val slot = ((t % (delay + 1)) * ch).toInt()
        System.arraycopy(x, at, ring, slot, ch)
        val peak = tracker.add(x, at)
        val j = t - PeakTracker.DELAY                // the frame this peak belongs to
        t++
        val target = if (peak * 1.0 > ceiling) ceiling / peak else 1.0
        // Sliding minimum over [j − w + 1, j].
        while (dqSize > 0 && dqVal[(dqHead + dqSize - 1) % (w + 1)] >= target) dqSize--
        dqIdx[(dqHead + dqSize) % (w + 1)] = j; dqVal[(dqHead + dqSize) % (w + 1)] = target; dqSize++
        while (dqIdx[dqHead] <= j - w) { dqHead = (dqHead + 1) % (w + 1); dqSize-- }
        val hold = dqVal[dqHead]                     // min of targets j−w+1..j: the hold for frame j−w+1
        holdSum += hold - holds[holdPos]; holds[holdPos] = hold; holdPos = (holdPos + 1) % w
        val n = j - w + 1                            // the frame whose gain is now known
        if (n < 0) return false
        val box = holdSum / w
        g = if (box <= g) box else g + (box - g) * release
        if (g < minGain) minGain = g
        val src = ((n % (delay + 1)) * ch).toInt()
        for (c in 0 until ch) out[o + c] = (ring[src + c] * g).toFloat().coerceIn(-ceiling.toFloat(), ceiling.toFloat())
        emitted++
        return true
    }
}

/** Loudness of short windows (RMS, dBFS), for waveforms and finding silence. */
class LevelMeter(rate: Int, private val ch: Int, windowMs: Double = 10.0) {
    val window = max(1, (rate * windowMs / 1000).roundToInt())
    private var acc = 0.0
    private var peak = 0f
    private var inWin = 0
    private var levels = FloatArray(1024)
    private var peaks = FloatArray(1024)
    var count = 0; private set

    fun add(x: FloatArray, n: Int = x.size) {
        for (i in 0 until n / ch) {
            var s = 0.0
            for (c in 0 until ch) { val v = x[i * ch + c]; s += v.toDouble() * v; val a = abs(v); if (a > peak) peak = a }
            acc += s / ch
            if (++inWin == window) push()
        }
    }

    private fun push() {
        if (count == levels.size) { levels = levels.copyOf(count * 2); peaks = peaks.copyOf(count * 2) }
        levels[count] = (10 * log10(max(acc / max(inWin, 1), 1e-12))).toFloat()
        peaks[count] = peak
        count++
        acc = 0.0; inWin = 0; peak = 0f
    }

    /** RMS per window in dBFS (the last, partial window included). */
    fun levelsDb(): FloatArray { if (inWin > 0) push(); return levels.copyOf(count) }
    /** Sample peak per window (0…1). */
    fun peaks(): FloatArray { if (inWin > 0) push(); return peaks.copyOf(count) }
}

/**
 * WSOLA time stretching (Verhelst & Roelands): the input is cut into overlapping 40 ms pieces taken
 * at the stretched rate, each moved by up to 12 ms to where its waveform best continues the piece
 * before (normalised cross-correlation, found coarsely then exactly), and overlap-added with Hann
 * windows. The length changes by [factor] (2 = twice as long) and the pitch stays the same.
 */
class Wsola(rate: Int, private val ch: Int, private val factor: Double) {
    private val n = (rate * 0.040).roundToInt() / 2 * 2
    private val hs = n / 2
    private val ha = hs / factor
    private val radius = (rate * 0.012).roundToInt()
    private val dec = 4
    private val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
    private var inb = FloatArray(1 shl 16)
    private var inBase = 0L                   // absolute frame index of inb[0]
    private var inLen = 0                     // frames held
    private var inTotal = 0L
    private val acc = DoubleArray(n * ch)
    private var k = 0L
    private var prev = 0L                     // input position of the last piece
    private var emitted = 0L
    private var ended = false

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        append(input, count / ch)
        return run()
    }

    fun flush(): FloatArray {
        ended = true
        val tail = (n + radius * 2 + ha.toInt() + 2)
        append(FloatArray(tail * ch), tail)
        val out = run()
        // Exactly the stretched length.
        val want = (inTotal * factor).roundToLong()
        val have = emitted
        val extra = (have - want).toInt().coerceAtLeast(0)
        val keep = (out.size / ch - extra).coerceAtLeast(0)
        emitted -= extra
        return out.copyOf(keep * ch)
    }

    private fun append(x: FloatArray, frames: Int) {
        if ((inLen + frames) * ch > inb.size) inb = inb.copyOf(max(inb.size * 2, (inLen + frames) * ch))
        System.arraycopy(x, 0, inb, inLen * ch, frames * ch)
        inLen += frames
        if (!ended) inTotal += frames
    }

    private fun run(): FloatArray {
        val parts = ArrayList<FloatArray>()
        while (true) {
            val nominal = (k * ha).roundToLong()
            val need = max(nominal + radius + n, prev + hs + n)
            if (need > inBase + inLen) break
            val pos = if (k == 0L) 0L else best(nominal)
            val piece = FloatArray(hs * ch)
            for (i in 0 until n) {
                val w = if (k == 0L && i < hs) 1.0 else win[i]
                val src = ((pos + i - inBase) * ch).toInt()
                for (c in 0 until ch) acc[i * ch + c] += w * inb[src + c]
            }
            for (i in 0 until hs * ch) piece[i] = acc[i].toFloat()
            System.arraycopy(acc, hs * ch, acc, 0, (n - hs) * ch)
            java.util.Arrays.fill(acc, (n - hs) * ch, n * ch, 0.0)
            parts.add(piece); emitted += hs
            prev = pos; k++
            // Forget input no later piece can reach.
            val keepFrom = min((k * ha).roundToLong() - radius, prev + hs) - 1
            val drop = (keepFrom - inBase).toInt()
            if (drop > 4096) {
                System.arraycopy(inb, drop * ch, inb, 0, (inLen - drop) * ch)
                inLen -= drop; inBase += drop
            }
        }
        return Pcm.concat(parts)
    }

    private fun mono(at: Long): Double {
        val p = ((at - inBase) * ch).toInt()
        var s = 0.0
        for (c in 0 until ch) s += inb[p + c]
        return s
    }

    /** The piece start near [nominal] that best continues the last piece. */
    private fun best(nominal: Long): Long {
        val tpl = prev + hs                    // where the last piece would naturally continue
        val len = hs
        val lo = max(inBase, nominal - radius)
        // Coarse: every [dec]th offset, every [dec]th sample.
        var bestPos = nominal; var bestScore = -Double.MAX_VALUE
        var p = lo
        while (p <= nominal + radius) {
            var cr = 0.0; var en = 1e-12
            var i = 0
            while (i < len) { val a = mono(tpl + i); val b = mono(p + i); cr += a * b; en += b * b; i += dec }
            val s = cr / sqrt(en)
            if (s > bestScore) { bestScore = s; bestPos = p }
            p += dec
        }
        // Fine: each offset around the coarse best, every other sample.
        val c0 = bestPos
        bestScore = -Double.MAX_VALUE
        for (q in max(lo, c0 - dec)..min(nominal + radius, c0 + dec)) {
            var cr = 0.0; var en = 1e-12
            var i = 0
            while (i < len) { val a = mono(tpl + i); val b = mono(q + i); cr += a * b; en += b * b; i += 2 }
            val s = cr / sqrt(en)
            if (s > bestScore) { bestScore = s; bestPos = q }
        }
        return bestPos
    }
}

/**
 * Speed (tempo, 1 = unchanged) and pitch (semitones) changed independently: WSOLA stretches the
 * time by pitch ÷ tempo, then resampling by the pitch factor brings the length back while moving
 * the pitch. [process] and [flush] together give input ÷ tempo frames.
 */
class SpeedPitch(private val rate: Int, private val ch: Int, val tempo: Double, val semitones: Double) {
    private val pitch = 2.0.pow(semitones / 12)
    private val stretch = if (abs(pitch / tempo - 1) < 1e-6) null else Wsola(rate, ch, pitch / tempo)
    private val resampler = if (abs(pitch - 1) < 1e-6) null else SincResampler((rate * pitch).roundToInt(), rate, ch)
    private var inFrames = 0L
    private var outFrames = 0L

    fun process(input: FloatArray, n: Int = input.size): FloatArray {
        inFrames += n / ch
        val a = stretch?.process(input, n) ?: input.copyOf(n)
        return (resampler?.process(a) ?: a).also { outFrames += it.size / ch }
    }

    fun flush(): FloatArray {
        val a = stretch?.flush() ?: FloatArray(0)
        val b = resampler?.let { SincResampler.concat(it.process(a), it.flush()) } ?: a
        // Exactly input ÷ tempo frames in all.
        val want = (inFrames / tempo).roundToLong()
        val frames = (want - outFrames).coerceAtLeast(0).toInt()
        val out = FloatArray(frames * ch)
        System.arraycopy(b, 0, out, 0, min(b.size, out.size))
        outFrames += frames
        return out
    }
}
