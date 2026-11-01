package com.localmediatools.video.core

import kotlin.math.max

/**
 * A clip's sound in the edited video: the part [fromMs]…[toMs] of its file, played from [startMs] of
 * the video at [speed] (the pitch kept), at [gain], fading in and out as its picture does.
 */
class SoundPart(val clip: Int, val startMs: Long, val fromMs: Long, val toMs: Long, val speed: Float, val gain: Float,
                val fadeInMs: Long, val fadeOutMs: Long)

/** Which clips' sound plays when in an edited video (the Android side decodes, stretches and mixes it). */
object EditSound {
    fun parts(tl: EditTimeline): List<SoundPart> {
        val out = ArrayList<SoundPart>()
        for ((i, p) in tl.placed.withIndex()) {
            val c = p.clip
            if (!c.hasSound) continue
            // A cross-fade into the next clip: this sound fades out over the overlap as the next fades in.
            val next = tl.placed.getOrNull(i + 1)
            val overlapOut = if (next != null && next.overlapIn) max(0L, p.endMs - next.startMs) else 0L
            out.add(SoundPart(i, p.startMs, c.inMs, c.outMs, c.speed, c.volume, p.fadeInMs, max(p.fadeOutMs, overlapOut)))
        }
        return out
    }

    /** When clips play their own sound (the music steps back then, if the project says so). */
    fun intervals(tl: EditTimeline): List<LongArray> =
        if (!tl.project.duckMusic) emptyList() else parts(tl).filter { it.gain > 0.05f }.map { longArrayOf(it.startMs + it.fadeInMs / 2, tl.placed[it.clip].endMs) }
}
