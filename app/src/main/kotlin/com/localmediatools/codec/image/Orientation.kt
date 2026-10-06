package com.localmediatools.codec.image

/**
 * The eight EXIF orientations. Each value describes how the *stored* (raw) pixel grid must be
 * transformed to obtain the image as it should be *displayed*.
 *
 * Coordinates are continuous (pixel edges), so a raw pixel square maps exactly onto a display
 * pixel square for every orientation; this keeps all transforms pixel-exact.
 */
enum class Orientation(val exifValue: Int) {
    NORMAL(1),
    FLIP_HORIZONTAL(2),
    ROTATE_180(3),
    FLIP_VERTICAL(4),
    TRANSPOSE(5),
    ROTATE_90(6),
    TRANSVERSE(7),
    ROTATE_270(8);

    /** True when the displayed image has width and height swapped relative to the raw image. */
    val swapsDimensions: Boolean get() = exifValue >= 5

    val isIdentity: Boolean get() = this == NORMAL

    fun displayWidth(rawWidth: Int, rawHeight: Int) = if (swapsDimensions) rawHeight else rawWidth
    fun displayHeight(rawWidth: Int, rawHeight: Int) = if (swapsDimensions) rawWidth else rawHeight

    /**
     * Affine matrix [a, b, c, d, tx, ty] mapping raw coordinates (x, y) to display coordinates:
     * X = a*x + b*y + tx, Y = c*x + d*y + ty.
     */
    fun rawToDisplay(rawWidth: Int, rawHeight: Int): DoubleArray {
        val w = rawWidth.toDouble()
        val h = rawHeight.toDouble()
        return when (this) {
            NORMAL -> doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            FLIP_HORIZONTAL -> doubleArrayOf(-1.0, 0.0, 0.0, 1.0, w, 0.0)
            ROTATE_180 -> doubleArrayOf(-1.0, 0.0, 0.0, -1.0, w, h)
            FLIP_VERTICAL -> doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, h)
            TRANSPOSE -> doubleArrayOf(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            ROTATE_90 -> doubleArrayOf(0.0, -1.0, 1.0, 0.0, h, 0.0)
            TRANSVERSE -> doubleArrayOf(0.0, -1.0, -1.0, 0.0, h, w)
            ROTATE_270 -> doubleArrayOf(0.0, 1.0, -1.0, 0.0, 0.0, w)
        }
    }

    /** Inverse of [rawToDisplay]: maps display coordinates back to raw coordinates. */
    fun displayToRaw(rawWidth: Int, rawHeight: Int): DoubleArray {
        val m = rawToDisplay(rawWidth, rawHeight)
        // The 2x2 part is orthogonal with entries in {-1, 0, 1}, so its inverse is its transpose.
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]
        val ia = a; val ib = c; val ic = b; val id = d
        val itx = -(ia * m[4] + ib * m[5])
        val ity = -(ic * m[4] + id * m[5])
        return doubleArrayOf(ia, ib, ic, id, itx, ity)
    }

    /** Maps a display-space rectangle (left, top, right, bottom) to the raw-space rectangle. */
    fun displayRectToRaw(left: Int, top: Int, right: Int, bottom: Int, rawWidth: Int, rawHeight: Int): IntArray {
        val m = displayToRaw(rawWidth, rawHeight)
        val x1 = m[0] * left + m[1] * top + m[4]
        val y1 = m[2] * left + m[3] * top + m[5]
        val x2 = m[0] * right + m[1] * bottom + m[4]
        val y2 = m[2] * right + m[3] * bottom + m[5]
        return intArrayOf(
            Math.round(minOf(x1, x2)).toInt(), Math.round(minOf(y1, y2)).toInt(),
            Math.round(maxOf(x1, x2)).toInt(), Math.round(maxOf(y1, y2)).toInt()
        )
    }

    /** Orientation obtained by applying this transform and then [next]. */
    fun then(next: Orientation): Orientation {
        // Compose using a probe on a 2x3 grid: deterministic and avoids a lookup table.
        val w = 2; val h = 3
        val m1 = rawToDisplay(w, h)
        val w1 = displayWidth(w, h); val h1 = displayHeight(w, h)
        val m2 = next.rawToDisplay(w1, h1)
        val composed = compose(m2, m1)
        return entries.first { o ->
            val m = o.rawToDisplay(w, h)
            m.indices.all { kotlin.math.abs(m[it] - composed[it]) < 1e-9 }
        }
    }

    /** Orientation that undoes this one. */
    fun inverse(): Orientation = entries.first { this.then(it) == NORMAL }

    companion object {
        fun fromExif(value: Int): Orientation = entries.firstOrNull { it.exifValue == value } ?: NORMAL

        /** Maps a clockwise rotation in degrees (0/90/180/270) to an orientation. */
        fun fromRotation(degrees: Int, mirrored: Boolean = false): Orientation {
            val r = ((degrees % 360) + 360) % 360
            val base = when (r) {
                90 -> ROTATE_90
                180 -> ROTATE_180
                270 -> ROTATE_270
                else -> NORMAL
            }
            return if (mirrored) FLIP_HORIZONTAL.then(base) else base
        }

        /** m = a ∘ b (apply b first). Both are [a, b, c, d, tx, ty]. */
        fun compose(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
            a[0] * b[0] + a[1] * b[2],
            a[0] * b[1] + a[1] * b[3],
            a[2] * b[0] + a[3] * b[2],
            a[2] * b[1] + a[3] * b[3],
            a[0] * b[4] + a[1] * b[5] + a[4],
            a[2] * b[4] + a[3] * b[5] + a[5]
        )
    }
}

/** Pixel-exact orientation transforms on packed ARGB buffers. */
object PixelTransforms {
    /**
     * Copies the display-space rows [displayTop, displayTop + rows) of an oriented image into [dst].
     *
     * [raw] holds the raw-space rectangle [rawLeft, rawTop, rawLeft + rawW, rawTop + rawH) of an
     * image with full raw size [fullRawW] x [fullRawH]; it must cover every raw pixel needed.
     * The destination row stride is the full display width.
     */
    fun orientRows(
        raw: IntArray, rawLeft: Int, rawTop: Int, rawW: Int, rawH: Int,
        fullRawW: Int, fullRawH: Int, orientation: Orientation,
        displayTop: Int, rows: Int, dst: IntArray, dstOffset: Int = 0
    ) {
        val dw = orientation.displayWidth(fullRawW, fullRawH)
        for (r in 0 until rows) {
            val dy = displayTop + r
            val rowBase = dstOffset + r * dw
            for (dx in 0 until dw) {
                // Pixel centres: display (dx + .5, dy + .5) -> raw centre; integer forms below.
                val rx: Int
                val ry: Int
                when (orientation) {
                    Orientation.NORMAL -> { rx = dx; ry = dy }
                    Orientation.FLIP_HORIZONTAL -> { rx = fullRawW - 1 - dx; ry = dy }
                    Orientation.ROTATE_180 -> { rx = fullRawW - 1 - dx; ry = fullRawH - 1 - dy }
                    Orientation.FLIP_VERTICAL -> { rx = dx; ry = fullRawH - 1 - dy }
                    Orientation.TRANSPOSE -> { rx = dy; ry = dx }
                    Orientation.ROTATE_90 -> { rx = dy; ry = fullRawH - 1 - dx }
                    Orientation.TRANSVERSE -> { rx = fullRawW - 1 - dy; ry = fullRawH - 1 - dx }
                    Orientation.ROTATE_270 -> { rx = fullRawW - 1 - dy; ry = dx }
                }
                dst[rowBase + dx] = raw[(ry - rawTop) * rawW + (rx - rawLeft)]
            }
        }
    }

    /** Fully transforms a raw image into display orientation. */
    fun orient(raw: IntArray, w: Int, h: Int, orientation: Orientation): IntArray {
        if (orientation.isIdentity) return raw
        val out = IntArray(w * h)
        orientRows(raw, 0, 0, w, h, w, h, orientation, 0, orientation.displayHeight(w, h), out)
        return out
    }
}
