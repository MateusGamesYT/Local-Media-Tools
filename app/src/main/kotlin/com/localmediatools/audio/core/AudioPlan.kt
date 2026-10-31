package com.localmediatools.audio.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A stretch of audio in frames: [start, end). */
data class Span(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/** Plans for the audio tools, from levels and lengths only (no audio needed). */
object AudioPlan {
    /**
     * The silent stretches in a file whose [levelsDb] (one value per window of [window] frames) stay
     * below [thresholdDb] for at least [minFrames], in frames. A stretch running to the end is cut at [total].
     */
    fun silences(levelsDb: FloatArray, window: Int, total: Long, thresholdDb: Double, minFrames: Long): List<Span> {
        val out = ArrayList<Span>()
        var start = -1
        for (i in 0..levelsDb.size) {
            val quiet = i < levelsDb.size && levelsDb[i] < thresholdDb
            if (quiet && start < 0) start = i
            if (!quiet && start >= 0) {
                val s = start.toLong() * window; val e = min(total, i.toLong() * window)
                if (e - s >= minFrames) out.add(Span(s, e))
                start = -1
            }
        }
        return out
    }

    /**
     * A level that separates sound from silence in this file: [aboveFloorDb] over its background
     * (the 10th percentile of the windows), but at least 18 dB under its loud part (the 95th
     * percentile) and never above −20 dBFS. Pauses in speech hold breath and room noise, so a fixed
     * level misses them; this follows each recording.
     */
    fun silenceThreshold(levelsDb: FloatArray, aboveFloorDb: Double = SENSITIVITY_PAUSES): Double {
        if (levelsDb.isEmpty()) return -60.0
        val sorted = levelsDb.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()].toDouble()
        return (pct(0.10) + aboveFloorDb).coerceAtMost(pct(0.95) - 18).coerceAtMost(-20.0).coerceAtLeast(-80.0)
    }

    /** Only near-digital silence and the background between tracks. */
    const val SENSITIVITY_SILENCE = 4.0
    /** Pauses in speech, with their breath and room noise. */
    const val SENSITIVITY_PAUSES = 10.0
    /** Also very soft passages. */
    const val SENSITIVITY_SOFT = 16.0

    /**
     * What remains when silence is removed: the sound between the [silences] of a file of [total]
     * frames. Silence at the start and end goes when [ends]; pauses inside go down to [keepPause]
     * frames (half of it kept on each side so speech still breathes) when [pauses], else stay.
     */
    fun keep(silences: List<Span>, total: Long, ends: Boolean, pauses: Boolean, keepPause: Long): List<Span> {
        val cuts = ArrayList<Span>()
        for (s in silences) {
            val atStart = s.start <= 0; val atEnd = s.end >= total
            when {
                atStart && atEnd -> return emptyList()
                atStart || atEnd -> if (ends) cuts.add(s)
                pauses && s.length > keepPause -> cuts.add(Span(s.start + keepPause / 2, s.end - (keepPause - keepPause / 2)))
            }
        }
        val out = ArrayList<Span>()
        var pos = 0L
        for (c in cuts.sortedBy { it.start }) {
            if (c.start > pos) out.add(Span(pos, c.start))
            pos = max(pos, c.end)
        }
        if (pos < total) out.add(Span(pos, total))
        return out
    }

    /**
     * Where to split a file of [total] frames into parts of about [partFrames] (when > 0) or into
     * [parts] equal parts. With [silences], each cut moves to the middle of the nearest silence within
     * [snap] frames, so words and notes aren't cut in half. Returns the parts.
     */
    fun split(total: Long, partFrames: Long, parts: Int, silences: List<Span>, snap: Long): List<Span> {
        // Nominal cuts; a last part shorter than a tenth of the others joins the one before.
        val nominal = if (partFrames > 0) generateSequence(partFrames) { it + partFrames }.takeWhile { it < total - partFrames / 10 }.toList()
            else (1 until max(1, parts)).map { (it * total.toDouble() / parts).roundToLong() }
        val cuts = ArrayList<Long>()
        for (c0 in nominal) {
            var cut = c0
            val near = silences.filter { it.end > c0 - snap && it.start < c0 + snap }.minByOrNull { abs((it.start + it.end) / 2 - c0) }
            if (near != null) cut = ((near.start + near.end) / 2).coerceIn(c0 - snap, c0 + snap)
            if (cut > (cuts.lastOrNull() ?: 0L) && cut < total) cuts.add(cut)
        }
        val out = ArrayList<Span>()
        var pos = 0L
        for (c in cuts) { out.add(Span(pos, c)); pos = c }
        out.add(Span(pos, total))
        return out
    }

    /**
     * Where each file starts in a merge of files of [lengths] frames: one after the other with
     * [gap] frames of silence between, or overlapping by [crossfade] frames (at most half the shorter
     * of two neighbours). Returns the starts and the total length.
     */
    fun merge(lengths: List<Long>, gap: Long, crossfade: Long): Pair<List<Long>, Long> {
        val starts = ArrayList<Long>()
        var pos = 0L
        for ((i, len) in lengths.withIndex()) {
            if (i > 0) {
                val overlap = if (crossfade > 0) min(crossfade, min(lengths[i - 1], len) / 2) else 0
                pos += if (overlap > 0) -overlap else gap
            }
            starts.add(pos)
            pos += len
        }
        return starts to max(0L, pos)
    }

    /** The overlap actually used between files [i]−1 and [i] of a merge (see [merge]). */
    fun overlap(lengths: List<Long>, i: Int, crossfade: Long): Long =
        if (i <= 0 || crossfade <= 0) 0 else min(crossfade, min(lengths[i - 1], lengths[i]) / 2)

    /**
     * The gain (dB) that brings a file measured at [lufs] to [targetLufs]. With [limit], peaks over
     * [ceilingDb] after the gain are left to the limiter; without, the gain is lowered so the true
     * peak ([truePeakDb]) stays under the ceiling.
     */
    fun normalizeGain(lufs: Double, truePeakDb: Double, targetLufs: Double, ceilingDb: Double, limit: Boolean): Double {
        if (lufs.isInfinite() || lufs.isNaN()) return 0.0
        val g = targetLufs - lufs
        return if (limit || truePeakDb.isInfinite()) g else min(g, ceilingDb - truePeakDb)
    }
}
