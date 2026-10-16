package com.localmediatools.vision.core

/**
 * How long a long task still needs, from how far it has come: the whole task is expected to take
 * elapsed / f, and what is left of that is told. It is told only once there is something to go on
 * (at least [MIN_SHARE] done and [MIN_ELAPSED_MS] passed), and the expected total is smoothed, so the
 * figure settles instead of jumping with every slow or quick step, and still counts down evenly while
 * the work goes at a steady pace. [now] gives the time in milliseconds.
 */
class TimeLeft(private val now: () -> Long = System::currentTimeMillis) {
    private val start = now()
    private var total: Double? = null

    /** The time left (ms) at share [f] (0..1) done, or null while it can't be told yet. */
    fun at(f: Double): Long? {
        val elapsed = (now() - start).toDouble()
        if (f >= 1.0) return 0L
        if (f < MIN_SHARE || elapsed < MIN_ELAPSED_MS) return null
        val raw = elapsed / f
        val t = total?.let { it + 0.3 * (raw - it) } ?: raw
        total = t
        return (t - elapsed).coerceAtLeast(0.0).toLong()
    }

    companion object {
        const val MIN_SHARE = 0.02
        const val MIN_ELAPSED_MS = 5_000L
    }
}
