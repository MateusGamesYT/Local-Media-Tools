package com.localmediatools.codec.edit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Crop rectangle as fractions (0..1) of the straightened frame. */
data class CropRect(val l: Double = 0.0, val t: Double = 0.0, val r: Double = 1.0, val b: Double = 1.0) {
    val isFull: Boolean get() = l <= 1e-9 && t <= 1e-9 && r >= 1 - 1e-9 && b >= 1 - 1e-9
    val width: Double get() = r - l
    val height: Double get() = b - t

    fun normalized(minSize: Double = 0.02): CropRect {
        var nl = l.coerceIn(0.0, 1.0); var nt = t.coerceIn(0.0, 1.0)
        var nr = r.coerceIn(0.0, 1.0); var nb = b.coerceIn(0.0, 1.0)
        if (nr - nl < minSize) { val c = ((nl + nr) / 2).coerceIn(minSize / 2, 1 - minSize / 2); nl = c - minSize / 2; nr = c + minSize / 2 }
        if (nb - nt < minSize) { val c = ((nt + nb) / 2).coerceIn(minSize / 2, 1 - minSize / 2); nt = c - minSize / 2; nb = c + minSize / 2 }
        return CropRect(nl, nt, nr, nb)
    }
}

/**
 * Geometric edits, applied in this order to the upright (EXIF-oriented) photo:
 * quarter turns clockwise, horizontal flip, straighten (clockwise degrees, auto-zoomed so no empty
 * corners appear), then crop.
 */
data class Geometry(
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val straighten: Double = 0.0,
    val crop: CropRect = CropRect(),
) {
    val turns: Int get() = ((quarterTurns % 4) + 4) % 4
    val isIdentity: Boolean get() = turns == 0 && !flipH && abs(straighten) < 1e-9 && crop.isFull

    /**
     * Rotates the whole result 90° clockwise, keeping the same visual crop. Rotation commutes with
     * straightening; after a flip, turning the result clockwise means one turn less of the source.
     */
    fun rotatedClockwise(): Geometry =
        copy(quarterTurns = if (flipH) (turns + 3) % 4 else (turns + 1) % 4, crop = CropRect(1 - crop.b, crop.l, 1 - crop.t, crop.r))

    /** Mirrors the whole result left-to-right (the straighten angle mirrors too). */
    fun flippedHorizontally(): Geometry = copy(flipH = !flipH, crop = CropRect(1 - crop.r, crop.t, 1 - crop.l, crop.b), straighten = -straighten)
}

/** Planar affine transform: x' = a·x + b·y + c, y' = d·x + e·y + f. */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun mapX(x: Double, y: Double) = a * x + b * y + c
    fun mapY(x: Double, y: Double) = d * x + e * y + f

    /** This transform followed by [o]. */
    fun then(o: Affine) = Affine(
        o.a * a + o.b * d, o.a * b + o.b * e, o.a * c + o.b * f + o.c,
        o.d * a + o.e * d, o.d * b + o.e * e, o.d * c + o.e * f + o.f,
    )

    fun inverse(): Affine {
        val det = a * e - b * d
        require(abs(det) > 1e-12) { "singular transform" }
        val ia = e / det; val ib = -b / det; val id = -d / det; val ie = a / det
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }

    /** Values in android.graphics.Matrix order (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, 0, 0, 1). */
    fun toMatrixValues() = floatArrayOf(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat(), e.toFloat(), f.toFloat(), 0f, 0f, 1f)

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        fun translate(tx: Double, ty: Double) = Affine(1.0, 0.0, tx, 0.0, 1.0, ty)
        fun scale(sx: Double, sy: Double = sx) = Affine(sx, 0.0, 0.0, 0.0, sy, 0.0)
        /** Clockwise on screen (y axis pointing down). */
        fun rotate(rad: Double) = Affine(cos(rad), -sin(rad), 0.0, sin(rad), cos(rad), 0.0)
    }
}

/** Resolved geometry for a source of size [srcW]×[srcH]: output size and the source→output mapping. */
class GeometryPlan(val srcW: Int, val srcH: Int, val geometry: Geometry) {
    val rotW: Int = if (geometry.turns % 2 == 1) srcH else srcW
    val rotH: Int = if (geometry.turns % 2 == 1) srcW else srcH
    /** Size of the straightened frame (the largest same-shape rectangle inside the rotated photo). */
    val frameW: Double
    val frameH: Double
    val outW: Int
    val outH: Int
    /** Source (upright photo) pixel coordinates → output pixel coordinates. */
    val forward: Affine
    /** Source → straightened frame coordinates (before cropping); used by the crop overlay. */
    val toFrame: Affine

    init {
        val w = srcW.toDouble(); val h = srcH.toDouble()
        var t = when (geometry.turns) {
            1 -> Affine(0.0, -1.0, h, 1.0, 0.0, 0.0)
            2 -> Affine(-1.0, 0.0, w, 0.0, -1.0, h)
            3 -> Affine(0.0, 1.0, 0.0, -1.0, 0.0, w)
            else -> Affine.IDENTITY
        }
        val rw = rotW.toDouble(); val rh = rotH.toDouble()
        if (geometry.flipH) t = t.then(Affine(-1.0, 0.0, rw, 0.0, 1.0, 0.0))
        val theta = Math.toRadians(geometry.straighten.coerceIn(-45.0, 45.0))
        // Straightened: shrink the frame by a pixel so no antialiased (part-transparent) corner survives.
        val s = straightenScale(rw, rh, theta).let { if (theta != 0.0) it - 2.0 / min(rw, rh) else it }
        frameW = rw * s; frameH = rh * s
        val cx = rw / 2; val cy = rh / 2
        t = t.then(Affine.translate(-cx, -cy)).then(Affine.rotate(theta)).then(Affine.translate(frameW / 2, frameH / 2))
        toFrame = t
        val c = geometry.crop.normalized()
        forward = t.then(Affine.translate(-c.l * frameW, -c.t * frameH))
        outW = max(1, (c.width * frameW).roundToInt())
        outH = max(1, (c.height * frameH).roundToInt())
    }

    val inverse: Affine by lazy { forward.inverse() }

    /** Bounding box (left, top, right, bottom; whole pixels, clamped) in the source for an output rectangle. */
    fun sourceBounds(x0: Double, y0: Double, x1: Double, y1: Double, pad: Int = 2): IntArray {
        val inv = inverse
        val xs = doubleArrayOf(inv.mapX(x0, y0), inv.mapX(x1, y0), inv.mapX(x0, y1), inv.mapX(x1, y1))
        val ys = doubleArrayOf(inv.mapY(x0, y0), inv.mapY(x1, y0), inv.mapY(x0, y1), inv.mapY(x1, y1))
        val l = (floor(xs.min()).toInt() - pad).coerceIn(0, srcW - 1)
        val tp = (floor(ys.min()).toInt() - pad).coerceIn(0, srcH - 1)
        val r = (ceil(xs.max()).toInt() + pad).coerceIn(l + 1, srcW)
        val b = (ceil(ys.max()).toInt() + pad).coerceIn(tp + 1, srcH)
        return intArrayOf(l, tp, r, b)
    }

    companion object {
        /** Scale of the largest rectangle with the same proportions that fits inside a w×h rectangle rotated by [theta]. */
        fun straightenScale(w: Double, h: Double, theta: Double): Double {
            val c = abs(cos(theta)); val s = abs(sin(theta))
            if (s < 1e-12) return 1.0
            return min(w / (w * c + h * s), h / (w * s + h * c))
        }

        /** Crop rectangle (fractions of the frame) with aspect [ratio] (w/h in output pixels), centred and as large as possible. */
        fun centeredCrop(frameW: Double, frameH: Double, ratio: Double): CropRect {
            val frameRatio = frameW / frameH
            return if (ratio > frameRatio) {
                val hFrac = frameRatio / ratio
                CropRect(0.0, (1 - hFrac) / 2, 1.0, (1 + hFrac) / 2)
            } else {
                val wFrac = ratio / frameRatio
                CropRect((1 - wFrac) / 2, 0.0, (1 + wFrac) / 2, 1.0)
            }
        }
    }
}
