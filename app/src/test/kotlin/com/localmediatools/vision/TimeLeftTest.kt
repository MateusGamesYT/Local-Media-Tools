package com.localmediatools.vision

import com.localmediatools.vision.core.TimeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The duplicate finder's time left: told only once there is something to go on, then settling on the real figure. */
class TimeLeftTest {
    @Test fun saysNothingUntilItCanTell() {
        var now = 1_000L
        val left = TimeLeft { now }
        now += 2_000
        assertNull("too early", left.at(0.5))
        now += 10_000
        assertNull("too little done", left.at(0.01))
        assertEquals(0L, left.at(1.0))
    }

    @Test fun settlesOnTheTimeTheRestTakes() {
        // 1 % of the work every second: 100 s in all.
        var now = 0L
        val left = TimeLeft { now }
        var last: Long? = null
        for (s in 1..60) {
            now = s * 1_000L
            last = left.at(s / 100.0) ?: last
        }
        assertTrue("$last", last != null && last in 39_500L..40_500L)
    }

    @Test fun aSlowStretchMovesTheFigureGradually() {
        var now = 0L
        val left = TimeLeft { now }
        for (s in 1..20) { now = s * 1_000L; left.at(s / 100.0) }
        val before = left.at(0.2)!!
        // A photo that takes 20 s on its own: the raw estimate doubles, the figure moves only part of the way.
        now += 20_000
        val after = left.at(0.21)!!
        val raw = now * (1 - 0.21) / 0.21
        assertTrue("$before → $after (raw $raw)", after > before && after < raw)
    }
}
