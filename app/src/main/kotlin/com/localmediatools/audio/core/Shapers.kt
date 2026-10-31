package com.localmediatools.audio.core

import kotlin.math.max
import kotlin.math.min

/**
 * Gain and fades on a stream whose end isn't known in advance: the last [fadeOut] frames are held
 * back until [finish], which fades them out. [gain] applies throughout.
 */
class FadeShaper(private val ch: Int, private val fadeIn: Long, private val fadeOut: Int, private val curve: FadeCurve = FadeCurve.SMOOTH, private val gain: Float = 1f) {
    private var pos = 0L
    private var held = FloatArray(0)

    fun process(x: FloatArray): FloatArray {
        for (i in 0 until x.size / ch) {
            val f = pos + i
            var g = gain
            if (f < fadeIn) g *= curve.gain((f + 0.5) / fadeIn).toFloat()
            if (g != 1f) for (c in 0 until ch) x[i * ch + c] *= g
        }
        pos += x.size / ch
        if (fadeOut <= 0) return x
        val all = if (held.isEmpty()) x else SincResampler.concat(held, x)
        val keep = min(all.size / ch, fadeOut)
        held = all.copyOfRange(all.size - keep * ch, all.size)
        return all.copyOf(all.size - keep * ch)
    }

    fun finish(): FloatArray {
        val n = held.size / ch
        for (i in 0 until n) {
            val g = curve.gain((n - i - 0.5) / max(1, n)).toFloat()
            for (c in 0 until ch) held[i * ch + c] *= g
        }
        return held.also { held = FloatArray(0) }
    }
}

/**
 * Keeps only the frames inside [spans] (sorted, not overlapping) of a stream, with a short fade
 * ([edge] frames) on each side of every cut so the joins don't click. The file's own start and end
 * aren't faded.
 */
class SpanFilter(private val ch: Int, private val spans: List<Span>, private val total: Long, private val edge: Int) {
    private var pos = 0L
    private var k = 0
    var kept = 0L; private set

    fun process(x: FloatArray): FloatArray {
        val n = x.size / ch
        val out = FloatArray(x.size)
        var o = 0
        for (i in 0 until n) {
            val f = pos + i
            while (k < spans.size && spans[k].end <= f) k++
            if (k >= spans.size) break
            val s = spans[k]
            if (f < s.start) continue
            var g = 1.0
            if (s.start > 0 && f - s.start < edge) g = FadeCurve.SMOOTH.gain((f - s.start + 0.5) / edge)
            if (s.end < total && s.end - f <= edge) g = min(g, FadeCurve.SMOOTH.gain((s.end - f - 0.5) / edge))
            for (c in 0 until ch) out[o + c] = (x[i * ch + c] * g).toFloat()
            o += ch
        }
        pos += n
        kept += o / ch
        return if (o == out.size) out else out.copyOf(o)
    }
}

/**
 * Joins sounds one after another, overlapping each with the one before by [overlaps] frames
 * (equal-power crossfade) or separating them by [gaps] frames of silence. Feed each sound with
 * [add] (in pieces) and call [next] between sounds and [finish] at the end; what can be written
 * comes back from each call. The last [maxOverlap] frames of a sound are held until the next
 * starts.
 */
class Joiner(private val ch: Int, private val maxOverlap: Int) {
    private var tail = FloatArray(0)          // held end of the sound so far
    private var overlap = 0                   // frames of the new sound to mix into the tail
    private var mixed = 0

    fun add(x: FloatArray): FloatArray {
        var input = x
        val out = ArrayList<FloatArray>()
        if (overlap > 0 && mixed < overlap) {
            // Crossfade: the old tail fades out as the new sound fades in.
            val n = min(overlap - mixed, input.size / ch)
            val start = (tail.size / ch) - (overlap - mixed)
            for (i in 0 until n) {
                val t = (mixed + i + 0.5) / overlap
                val a = FadeCurve.EQUAL_POWER.gain(1 - t).toFloat(); val b = FadeCurve.EQUAL_POWER.gain(t).toFloat()
                for (c in 0 until ch) tail[(start + i) * ch + c] = tail[(start + i) * ch + c] * a + input[i * ch + c] * b
            }
            mixed += n
            input = input.copyOfRange(n * ch, input.size)
            if (mixed < overlap) return FloatArray(0)
        }
        val all = SincResampler.concat(tail, input)
        val keep = min(all.size / ch, maxOverlap)
        tail = all.copyOfRange(all.size - keep * ch, all.size)
        out.add(all.copyOf(all.size - keep * ch))
        return Pcm.concat(out)
    }

    /** The next sound starts: overlapping by [overlapFrames] (≤ what's held), or after [gapFrames] of silence. */
    fun next(overlapFrames: Int, gapFrames: Int): FloatArray {
        // A crossfade the last sound didn't finish (it was shorter than the overlap) is dropped.
        overlap = 0; mixed = 0
        val o = min(overlapFrames, tail.size / ch)
        if (o > 0) {
            val release = tail.copyOf(tail.size - o * ch)
            tail = tail.copyOfRange(tail.size - o * ch, tail.size)
            overlap = o
            return release
        }
        val release = tail
        tail = FloatArray(0)
        return SincResampler.concat(release, FloatArray(max(0, gapFrames) * ch))
    }

    fun finish(): FloatArray = tail.also { tail = FloatArray(0) }
}
