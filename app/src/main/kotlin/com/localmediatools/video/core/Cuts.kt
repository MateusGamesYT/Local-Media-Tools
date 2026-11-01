package com.localmediatools.video.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the cuts in a video (where one shot ends and another starts) from tiny grey copies of its
 * frames ([W] × [H]): a frame is a cut when it differs from the one before by at least [MIN_DIFF]
 * (mean difference, 0–255) and [RATIO] times what frames differ by just before it, so a pan or a
 * walk (which changes every frame a little) is not taken for one. Measured on real videos joined end
 * to end (buildtools/video/cuts_eval.py).
 */
object Cuts {
    const val W = 32
    const val H = 18
    @Volatile var MIN_DIFF = 28f
    @Volatile var RATIO = 3.5f
    /** How many frames before a cut set what is usual. */
    const val WINDOW = 12
    /** No two cuts closer than this (a flash or a quick fade counts once). */
    const val MIN_GAP_MS = 400L

    fun diff(a: ByteArray, b: ByteArray): Float {
        var s = 0L
        for (i in a.indices) s += abs((a[i].toInt() and 255) - (b[i].toInt() and 255))
        return s.toFloat() / a.size
    }

    /** Indices of the frames that start a new shot, for frames at [ms] with grey copies [thumbs]. */
    fun find(thumbs: List<ByteArray>, ms: LongArray): List<Int> {
        val out = ArrayList<Int>()
        val d = FloatArray(thumbs.size)
        for (i in 1 until thumbs.size) d[i] = diff(thumbs[i], thumbs[i - 1])
        var last = Long.MIN_VALUE / 2
        for (i in 1 until thumbs.size) {
            if (d[i] < MIN_DIFF) continue
            val from = max(1, i - WINDOW)
            val before = (from until i).map { d[it] }.sorted()
            val usual = if (before.isEmpty()) 0f else before[before.size / 2]
            if (d[i] < RATIO * max(usual, 2f)) continue
            if (ms[i] - last < MIN_GAP_MS) continue
            out.add(i); last = ms[i]
        }
        return out
    }
}
