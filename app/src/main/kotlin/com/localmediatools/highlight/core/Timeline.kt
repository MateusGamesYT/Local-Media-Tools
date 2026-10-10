package com.localmediatools.highlight.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One picture drawn into an output frame: the part [src] of the clip's picture (fractions of its
 * displayed size) goes to [dst] (fractions of the output, y down) at [alpha]. With [backdrop] a
 * blurred, darkened copy of [backdropSrc] fills the whole frame behind it first.
 */
class Layer(val clip: Int, val src: Frame, val dst: Frame, val alpha: Float, val backdrop: Boolean, val backdropSrc: Frame, val sourceMs: Long)

class CaptionLayer(val caption: Int, val alpha: Float)

/** Everything in one output frame, bottom to top, and how far it has faded to black. */
class FrameDesc(val layers: List<Layer>, val captions: List<CaptionLayer>, val fade: Float)

/**
 * What the highlight video shows at any moment of its [Plan]: which clips, which part of each
 * picture, how faded, which captions. The renderer only draws what this says, so everything about
 * timing and framing is decided (and tested) here.
 */
object Timeline {
    const val FPS = 30
    const val CAPTION_FADE_MS = 350.0

    fun frameCount(plan: Plan): Int = max(1, ceil(plan.durationMs * FPS / 1000.0).toInt())
    fun timeOf(frame: Int): Double = frame * 1000.0 / FPS

    fun at(plan: Plan, t: Double): FrameDesc {
        val out = plan.aspect.ratio
        val layers = ArrayList<Layer>()
        for ((i, c) in plan.clips.withIndex()) {
            if (t < c.startMs || t >= c.endMs) continue
            val alpha = if (c.fadeInMs > 0) ((t - c.startMs) / c.fadeInMs).toFloat().coerceIn(0f, 1f) else 1f
            val p = ((t - c.startMs) / max(1L, c.durationMs)).toFloat().coerceIn(0f, 1f)
            val src = lerp(c.from, c.to, p)
            val dst = if (c.fit) fitRect(src.w * c.shot.width / (src.h * c.shot.height), out) else Frame(0f, 0f, 1f, 1f)
            val backdrop = if (c.fit) Planner.cover(c.shot, out, 1.05f, null) else src
            val sourceMs = if (c.shot.kind == ShotKind.VIDEO) (c.sourceStartMs + (t - c.startMs)).toLong().coerceIn(c.sourceStartMs, max(c.sourceStartMs, c.sourceEndMs - 1)) else 0L
            layers.add(Layer(i, src, dst, alpha, c.fit, backdrop, sourceMs))
        }
        val caps = ArrayList<CaptionLayer>()
        for ((i, c) in plan.captions.withIndex()) {
            if (t < c.startMs || t >= c.endMs) continue
            val a = min(min(1.0, (t - c.startMs) / CAPTION_FADE_MS), (c.endMs - t) / CAPTION_FADE_MS).toFloat().coerceIn(0f, 1f)
            caps.add(CaptionLayer(i, a))
        }
        val fade = if (plan.fadeOutMs > 0) ((t - (plan.durationMs - plan.fadeOutMs)) / plan.fadeOutMs).toFloat().coerceIn(0f, 1f) else 0f
        return FrameDesc(layers, caps, fade)
    }

    fun lerp(a: Frame, b: Frame, p: Float) = Frame(a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p, a.w + (b.w - a.w) * p, a.h + (b.h - a.h) * p)

    /** Where a picture of shape [aspect] goes, whole and centred, in an output of shape [out] (fractions, y down). */
    fun fitRect(aspect: Float, out: Float): Frame =
        if (aspect > out) { val h = out / aspect; Frame(0f, (1 - h) / 2, 1f, h) } else { val w = aspect / out; Frame((1 - w) / 2, 0f, w, 1f) }

    // ------------------------------------------------------------------ texture coordinates
    // The renderer draws each layer as a quad whose corners q run 0..1 (x right, y up, as GL draws).
    // These matrices (column-major 4×4, as GLES expects) take q to the texture coordinates to sample.

    /** For a bitmap uploaded with GLUtils (its first row at t = 0): q → the [src] part of the picture. */
    fun bitmapMatrix(src: Frame): FloatArray {
        // s = src.x + q.x·src.w; t = src.y + (1 − q.y)·src.h
        return affine(src.w, 0f, src.x, 0f, -src.h, src.y + src.h)
    }

    /**
     * For a decoded video frame on a SurfaceTexture: q → the [src] part of the *displayed* picture,
     * for a video stored [rotationCw] degrees from upright, through the SurfaceTexture's own
     * transform [st] (row 0 of the stored picture at the top, texture origin at the bottom).
     */
    fun videoMatrix(src: Frame, rotationCw: Int, st: FloatArray): FloatArray {
        // Displayed point (u, v) (v down) from q.
        val display = affine(src.w, 0f, src.x, 0f, -src.h, src.y + src.h)
        // Stored point (x, y) (y down) from the displayed one.
        val unrotate = when (((rotationCw % 360) + 360) % 360) {
            90 -> affine(0f, 1f, 0f, -1f, 0f, 1f)     // x = v, y = 1 − u
            180 -> affine(-1f, 0f, 1f, 0f, -1f, 1f)   // x = 1 − u, y = 1 − v
            270 -> affine(0f, -1f, 1f, 1f, 0f, 0f)    // x = 1 − v, y = u
            else -> affine(1f, 0f, 0f, 0f, 1f, 0f)
        }
        // Texture coordinates with the origin at the bottom.
        val flip = affine(1f, 0f, 0f, 0f, -1f, 1f)
        return mul(st, mul(flip, mul(unrotate, display)))
    }

    /** A 2-D affine map (x' = a·x + b·y + c, y' = d·x + e·y + f) as a column-major 4×4 matrix. */
    fun affine(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) = floatArrayOf(
        a, d, 0f, 0f,
        b, e, 0f, 0f,
        0f, 0f, 1f, 0f,
        c, f, 0f, 1f,
    )

    /** Column-major 4×4 product l·r (as android.opengl.Matrix.multiplyMM). */
    fun mul(l: FloatArray, r: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += l[k * 4 + row] * r[col * 4 + k]
            o[col * 4 + row] = s
        }
        return o
    }

    /** Applies a column-major 4×4 matrix to the point (x, y, 0, 1). */
    fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> = (m[0] * x + m[4] * y + m[12]) to (m[1] * x + m[5] * y + m[13])

    val IDENTITY = affine(1f, 0f, 0f, 0f, 1f, 0f)
}
