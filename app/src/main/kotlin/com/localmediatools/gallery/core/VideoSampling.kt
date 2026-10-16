package com.localmediatools.gallery.core

/**
 * Which moments of a video the gallery looks at for faces and things: spread evenly over it, one
 * every [SPACING_MS] (at least [MIN_FRAMES], at most [MAX_FRAMES]), each moved to the keyframe it
 * decodes from, and only one per keyframe (two moments sharing a keyframe are the same picture).
 * Moments where faces were found before are always looked at again, so a face keeps its frame and
 * what the user said about it. Measured on real videos of people in buildtools/gallery/video_eval.py.
 */
object VideoSampling {
    @Volatile var SPACING_MS = 3000L
    @Volatile var MIN_FRAMES = 3
    @Volatile var MAX_FRAMES = 16

    /** Evenly spread moments (ms) of a video [durationMs] long; one at the start for a very short one. */
    fun spread(durationMs: Long): List<Long> {
        if (durationMs <= 1500) return listOf(0L)
        val n = (durationMs / SPACING_MS).toInt().coerceIn(MIN_FRAMES, MAX_FRAMES)
        return (0 until n).map { i -> (durationMs * (i + 0.5) / n).toLong() }
    }

    /**
     * The moments to decode: [keep] (faces were found there before) and those of [spread] whose
     * keyframe ([syncOf], ms; null when it can't be told) no other moment already decodes.
     */
    fun moments(durationMs: Long, keep: Collection<Long> = emptyList(), syncOf: (Long) -> Long? = { it }): List<Long> {
        val out = LinkedHashSet<Long>(keep)
        val seen = HashSet<Long>()
        for (t in keep) syncOf(t)?.let { seen.add(it) }
        for (t in spread(durationMs)) {
            val s = syncOf(t)
            if (s == null) { out.add(t); continue }
            if (seen.add(s)) out.add(t)
        }
        return out.sorted()
    }
}
