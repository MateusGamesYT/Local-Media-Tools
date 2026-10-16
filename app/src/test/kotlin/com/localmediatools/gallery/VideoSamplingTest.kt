package com.localmediatools.gallery

import com.localmediatools.gallery.core.VideoSampling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which frames of a video the gallery looks at ([VideoSampling]; the spacing is measured in buildtools/gallery/video_eval.py). */
class VideoSamplingTest {
    /** Keyframes every [gopMs] (as phones record, about one a second): a moment decodes from the nearest. */
    private fun gop(gopMs: Long): (Long) -> Long? = { t -> Math.round(t.toDouble() / gopMs) * gopMs }

    @Test
    fun spreadsMomentsEvenlyWithinLimits() {
        assertEquals(listOf(0L), VideoSampling.spread(1200))
        val short = VideoSampling.spread(4000)
        assertEquals(VideoSampling.MIN_FRAMES, short.size)
        val long = VideoSampling.spread(10 * 60_000)
        assertEquals(VideoSampling.MAX_FRAMES, long.size)
        // Evenly: the gaps are equal and the ends are half a gap in.
        val gaps = long.zipWithNext { a, b -> b - a }
        assertTrue(gaps.max() - gaps.min() <= 1)
        assertEquals(gaps[0] / 2.0, long[0].toDouble(), 1.0)
        val mid = VideoSampling.spread(30_000)
        assertEquals((30_000 / VideoSampling.SPACING_MS).toInt().coerceIn(VideoSampling.MIN_FRAMES, VideoSampling.MAX_FRAMES), mid.size)
    }

    @Test
    fun neverDecodesTheSameKeyframeTwice() {
        // Keyframes far apart (some cameras and edited videos): moments that share one are dropped.
        val sync = gop(10_000)
        val m = VideoSampling.moments(30_000, syncOf = sync)
        val keys = m.map { sync(it) }
        assertEquals(keys.toSet().size, keys.size)
        assertTrue(m.size in 2..4)
        // Unknown keyframes: every spread moment is kept.
        assertEquals(VideoSampling.spread(30_000), VideoSampling.moments(30_000) { null })
    }

    @Test
    fun framesWhereFacesWereFoundAreLookedAtAgain() {
        val keep = listOf(4_250L, 17_000L)
        val m = VideoSampling.moments(30_000, keep, gop(1000))
        assertTrue(m.containsAll(keep))
        // ...and no spread moment repeats their keyframes.
        val sync = gop(1000)
        assertEquals(m.map { sync(it) }.toSet().size, m.size)
        assertEquals(m.sorted(), m)
    }
}
