package com.localmediatools.ui.gallery

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.min

/**
 * Photo view with pinch and double-tap zoom, panning and fling. Fits the photo by default; while
 * zoomed in it keeps horizontal drags for itself until an edge is reached, so the pager only
 * changes photos when you are not looking at a detail.
 */
class ZoomImageView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        set(v) { field = v; reset(); invalidate() }
    var onTap: ((Float, Float) -> Unit)? = null
    var onZoomChanged: ((Boolean) -> Unit)? = null
    /** Extra drawing on top of the photo, in view coordinates (face boxes). */
    var overlay: ((Canvas, RectF) -> Unit)? = null

    private val m = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scroller = OverScroller(ctx)
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private val bounds = RectF()

    val zoomed get() = scale > 1.02f

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(scale * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { if (scale < 1f) animateTo(1f, width / 2f, height / 2f) }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed) animateTo(1f, e.x, e.y) else animateTo(2.6f, e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed) return false
            tx -= dx; ty -= dy; clamp(); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!zoomed) return false
            val r = fitted()
            val w = r.width() * scale; val h = r.height() * scale
            // Translation range that keeps the photo covering the view.
            val maxX = (-r.left * scale).toInt(); val minX = min(maxX.toFloat(), width - w - r.left * scale).toInt()
            val maxY = (-r.top * scale).toInt(); val minY = min(maxY.toFloat(), height - h - r.top * scale).toInt()
            scroller.fling(tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(), minX, max(minX, maxX), minY, max(minY, maxY))
            postInvalidateOnAnimation()
            return true
        }
    })

    init { isClickable = true }

    fun reset() { scale = 1f; tx = 0f; ty = 0f; onZoomChanged?.invoke(false) }

    /** The bitmap fitted into the view, unzoomed. */
    private fun fitted(): RectF {
        val b = bitmap ?: return RectF()
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * s; val h = b.height * s
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun zoomAround(newScale: Float, fx: Float, fy: Float) {
        val ns = newScale.coerceIn(0.85f, 8f)
        // Keep the point under the fingers fixed.
        tx = fx - (fx - tx) * (ns / scale)
        ty = fy - (fy - ty) * (ns / scale)
        val was = zoomed
        scale = ns
        clamp()
        if (was != zoomed) onZoomChanged?.invoke(zoomed)
        invalidate()
    }

    private fun animateTo(target: Float, fx: Float, fy: Float) {
        val start = scale
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { a -> zoomAround(start + (target - start) * (a.animatedValue as Float), fx, fy) }
            start()
        }
    }

    private fun clamp() {
        val r = fitted()
        if (scale <= 1f) { tx = (1 - scale) * width / 2; ty = (1 - scale) * height / 2; return }
        val w = r.width() * scale; val h = r.height() * scale
        val left = r.left * scale + tx; val top = r.top * scale + ty
        tx += when { w <= width -> (width - w) / 2 - left; left > 0 -> -left; left + w < width -> width - (left + w); else -> 0f }
        ty += when { h <= height -> (height - h) / 2 - top; top > 0 -> -top; top + h < height -> height - (top + h); else -> 0f }
    }

    /** True when a horizontal drag in direction [dx] (>0 = finger moving right) would still pan the photo. */
    fun canPan(dx: Float): Boolean {
        if (!zoomed) return false
        val r = fitted()
        val left = r.left * scale + tx; val right = left + r.width() * scale
        return if (dx > 0) left < -1f else right > width + 1f
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { tx = scroller.currX.toFloat(); ty = scroller.currY.toFloat(); clamp(); postInvalidateOnAnimation() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { reset() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        if (zoomed || e.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        val r = fitted()
        m.reset()
        m.setRectToRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), r, Matrix.ScaleToFit.FILL)
        m.postScale(scale, scale)
        m.postTranslate(tx, ty)
        c.drawBitmap(b, m, paint)
        bounds.set(r.left * scale + tx, r.top * scale + ty, r.right * scale + tx, r.bottom * scale + ty)
        overlay?.invoke(c, bounds)
    }

    /** Where the photo is drawn now (view coordinates). */
    fun photoBounds(): RectF = RectF(bounds)
}
