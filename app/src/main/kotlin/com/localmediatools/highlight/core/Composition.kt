package com.localmediatools.highlight.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One picture of a [Composition]: a photo, or a stretch of a video, and when it is on screen.
 * [width]/[height]: its displayed size before [rotation] (a further turn, clockwise, in 90° steps).
 */
class CompClip(
    val kind: ShotKind,
    /** Which file (the renderer asks for it by this key). */
    val source: Int,
    val width: Int, val height: Int,
    val startMs: Long, val endMs: Long,
    /** For videos: the part of the file played (at [speed]). */
    val sourceStartMs: Long, val sourceEndMs: Long,
    /** Shown whole over a blurred copy of itself. */
    val fit: Boolean,
    /** For decoding photos sharp enough: the narrowest share of the picture's width shown, and the share of the output's width it covers. */
    val minShownW: Float, val dstW: Float,
    val rotation: Int = 0,
) {
    val aspect: Float get() = if (rotation % 180 != 0) height.toFloat() / max(1, width) else width.toFloat() / max(1, height)
}

/**
 * What a video made from pictures shows at every moment (the renderer only draws what this says):
 * highlight videos ([PlanComposition]) and the video editor (com.localmediatools.video.core.EditTimeline)
 * both describe their videos this way.
 */
interface Composition {
    val durationMs: Long
    /** The output's width over its height. */
    val outAspect: Float
    val clips: List<CompClip>
    /** How many captions or texts; the renderer has each drawn once ([CaptionPainter]) and shows it as [FrameDesc.captions] says. */
    val captionCount: Int
    fun at(t: Double): FrameDesc

    fun frameCount(): Int = max(1, ceil(durationMs * Timeline.FPS / 1000.0).toInt())
    fun timeOf(frame: Int): Double = frame * 1000.0 / Timeline.FPS
}

/** A highlight video's [Plan] as a [Composition] (what [Timeline] says it shows). */
class PlanComposition(val plan: Plan) : Composition {
    override val durationMs get() = plan.durationMs
    override val outAspect get() = plan.aspect.ratio
    override val captionCount get() = plan.captions.size
    override val clips: List<CompClip> = plan.clips.map { c ->
        CompClip(c.shot.kind, c.shot.id, c.shot.width, c.shot.height, c.startMs, c.endMs, c.sourceStartMs, c.sourceEndMs, c.fit,
            minShownW = min(c.from.w, c.to.w), dstW = if (c.fit) Timeline.fitRect(c.shot.aspect, plan.aspect.ratio).w else 1f)
    }
    override fun at(t: Double) = Timeline.at(plan, t)
}
