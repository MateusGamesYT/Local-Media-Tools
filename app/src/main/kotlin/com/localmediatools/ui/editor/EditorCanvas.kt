package com.localmediatools.ui.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.localmediatools.codec.edit.CropRect
import com.localmediatools.ui.Palette
import com.localmediatools.ui.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the photo being edited. Pinch to zoom and two-finger pan in every mode; in crop mode the
 * whole straightened frame is shown with draggable crop handles; in brush modes one finger paints.
 */
class EditorCanvas(ctx: Context) : View(ctx) {
    enum class Mode { VIEW, CROP, BRUSH }

    var mode = Mode.VIEW
        set(v) { field = v; if (v == Mode.CROP) resetZoom(animate = false); invalidate() }
    private var image: Bitmap? = null
    private var compare: Bitmap? = null
    var comparing = false
        set(v) { field = v; invalidate() }

    // Crop state: fractions of the frame bitmap; [aspect] is output width / height, or null for free.
    var crop = CropRect()
    var aspect: Double? = null
    var frameW = 1.0
    var frameH = 1.0
    var onCropChanged: ((CropRect, Boolean) -> Unit)? = null

    // Brush state.
    var brushRadiusDp = 22f
    var brushColor = 0x66FF3B6B
    var onStroke: ((FloatArray, Float) -> Unit)? = null
    var hint: String? = null
        set(v) { field = v; invalidate() }

    private val fit = Matrix()
    private val display = Matrix()
    private val inverse = Matrix()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint().apply { color = 0xB0000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.5f).toFloat() }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(3.5f).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = ctx.dp(1).toFloat() }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.5f).toFloat() }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ctx.dp(13).toFloat(); textAlign = Paint.Align.CENTER; typeface = com.localmediatools.ui.typeface(560) }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }

    fun setImage(b: Bitmap, keepView: Boolean = true) {
        val sizeChanged = image?.let { it.width != b.width || it.height != b.height } ?: true
        image = b
        if (sizeChanged) { computeFit(); if (!keepView) resetZoom(false) }
        clampPan(); invalidate()
    }

    fun setCompareImage(b: Bitmap?) { compare = b; invalidate() }

    val hasImage get() = image != null

    /** Image-bitmap coordinates of a view point. */
    fun toBitmap(x: Float, y: Float): FloatArray { val p = floatArrayOf(x, y); inverse.mapPoints(p); return p }

    private val scaleNow: Float get() { val v = FloatArray(9); display.getValues(v); return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y]) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { computeFit(); invalidate() }

    private fun computeFit() {
        val b = image ?: return
        if (width == 0 || height == 0) return
        val pad = context.dp(if (mode == Mode.CROP) 26 else 12).toFloat()
        val s = min((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        fit.setScale(s, s)
        fit.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
        updateDisplay()
    }

    private fun updateDisplay() {
        display.set(fit)
        display.postScale(zoom, zoom, width / 2f, height / 2f)
        display.postTranslate(panX, panY)
        display.invert(inverse)
    }

    fun resetZoom(animate: Boolean = true) {
        if (!animate) { zoom = 1f; panX = 0f; panY = 0f; updateDisplay(); invalidate(); return }
        val z0 = zoom; val x0 = panX; val y0 = panY
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { val t = it.animatedValue as Float; zoom = z0 + (1 - z0) * t; panX = x0 * (1 - t); panY = y0 * (1 - t); updateDisplay(); invalidate() }
            start()
        }
    }

    private fun clampPan() {
        val b = image ?: return
        updateDisplay()
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()); display.mapRect(r)
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2 - r.left else { if (r.left > 0) dx = -r.left; if (r.right < width) dx = width - r.right }
        if (r.height() <= height) dy = (height - r.height()) / 2 - r.top else { if (r.top > 0) dy = -r.top; if (r.bottom < height) dy = height - r.bottom }
        if (zoom <= 1.001f) { panX = 0f; panY = 0f } else { panX += dx; panY += dy }
        updateDisplay()
    }

    private fun cropRectView(b: Bitmap): RectF {
        val r = RectF((crop.l * b.width).toFloat(), (crop.t * b.height).toFloat(), (crop.r * b.width).toFloat(), (crop.b * b.height).toFloat())
        display.mapRect(r); return r
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val b = (if (comparing) compare else null) ?: image ?: return
        if (comparing && compare != null) {
            // The original has its own size: fit it on its own.
            val pad = context.dp(12).toFloat()
            val s = min((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
            val m = Matrix().apply { setScale(s, s); postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2) }
            c.drawBitmap(b, m, paint)
            drawHint(c, "Original")
            return
        }
        c.drawBitmap(b, display, paint)
        if (mode == Mode.CROP) {
            val r = cropRectView(b)
            val full = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()); display.mapRect(full)
            c.drawRect(full.left, full.top, full.right, r.top, scrim)
            c.drawRect(full.left, r.bottom, full.right, full.bottom, scrim)
            c.drawRect(full.left, r.top, r.left, r.bottom, scrim)
            c.drawRect(r.right, r.top, full.right, r.bottom, scrim)
            if (dragging != Hit.NONE) for (i in 1..2) {
                val x = r.left + r.width() * i / 3; val y = r.top + r.height() * i / 3
                c.drawLine(x, r.top, x, r.bottom, grid); c.drawLine(r.left, y, r.right, y, grid)
            }
            c.drawRect(r, border)
            val L = context.dp(18).toFloat()
            for ((x, sx) in listOf(r.left to 1, r.right to -1)) for ((y, sy) in listOf(r.top to 1, r.bottom to -1)) {
                c.drawLine(x, y, x + L * sx, y, handle); c.drawLine(x, y, x, y + L * sy, handle)
            }
        }
        if (mode == Mode.BRUSH && strokePts.size >= 2) {
            strokePaint.color = brushColor
            strokePaint.strokeWidth = context.dp(brushRadiusDp) * 2f
            val path = Path()
            val p = FloatArray(2)
            for (i in 0 until strokePts.size / 2) {
                p[0] = strokePts[2 * i]; p[1] = strokePts[2 * i + 1]; display.mapPoints(p)
                if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
            }
            if (strokePts.size == 2) path.lineTo(p[0] + 0.1f, p[1])
            c.drawPath(path, strokePaint)
        }
        if (mode == Mode.BRUSH && cursorVisible) c.drawCircle(cursorX, cursorY, context.dp(brushRadiusDp).toFloat(), cursor)
        hint?.let { if (!busy) drawHint(c, it) }
    }

    var busy = false
        set(v) { field = v; invalidate() }

    private fun drawHint(c: Canvas, text: String) {
        val w = hintPaint.measureText(text) + context.dp(28)
        val h = context.dp(32).toFloat()
        val r = RectF(width / 2f - w / 2, context.dp(12).toFloat(), width / 2f + w / 2, context.dp(12) + h)
        c.drawRoundRect(r, h / 2, h / 2, hintBg)
        c.drawText(text, width / 2f, r.centerY() + hintPaint.textSize * 0.36f, hintPaint)
    }

    /** Briefly shows the brush size in the middle (while its slider moves). */
    fun previewBrush() { cursorX = width / 2f; cursorY = height / 2f; cursorVisible = true; invalidate(); removeCallbacks(hideCursor); postDelayed(hideCursor, 700) }
    private val hideCursor = Runnable { cursorVisible = false; invalidate() }

    // ------------------------------------------------------------------ touch
    private enum class Hit { NONE, MOVE, L, T, R, B, TL, TR, BL, BR }
    private var dragging = Hit.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var strokePts = FloatArrayList()
    private var drawing = false
    private var cursorVisible = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var multiTouch = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var fx = 0f; private var fy = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { fx = d.focusX; fy = d.focusY; return mode != Mode.CROP }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nz = (zoom * d.scaleFactor).coerceIn(1f, 8f)
            val f = nz / zoom
            // Keep the focus point fixed while zooming, and follow the fingers.
            panX = (panX - (d.focusX - width / 2f)) * f + (d.focusX - width / 2f) + (d.focusX - fx)
            panY = (panY - (d.focusY - height / 2f)) * f + (d.focusY - height / 2f) + (d.focusY - fy)
            fx = d.focusX; fy = d.focusY
            zoom = nz
            clampPan(); invalidate(); return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (mode != Mode.VIEW) return false
            if (zoom > 1.05f) resetZoom() else { zoom = 2.5f; panX = (width / 2f - e.x) * 1.5f; panY = (height / 2f - e.y) * 1.5f; clampPan(); invalidate() }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (mode != Mode.VIEW || zoom <= 1.001f) return false
            panX -= dx; panY -= dy; clampPan(); invalidate(); return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (busy || comparing) return true
        scaler.onTouchEvent(e)
        if (e.pointerCount > 1) {
            multiTouch = true
            if (drawing) { drawing = false; strokePts.clear(); cursorVisible = false; invalidate() }
            dragging = Hit.NONE
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
        if (multiTouch) { if (e.actionMasked == MotionEvent.ACTION_UP) multiTouch = false; return true }
        when (mode) {
            Mode.VIEW -> gestures.onTouchEvent(e)
            Mode.CROP -> cropTouch(e)
            Mode.BRUSH -> brushTouch(e)
        }
        return true
    }

    private fun brushTouch(e: MotionEvent) {
        cursorX = e.x; cursorY = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                drawing = true; cursorVisible = true; strokePts.clear()
                val p = toBitmap(e.x, e.y); strokePts.add(p[0], p[1])
            }
            MotionEvent.ACTION_MOVE -> if (drawing) {
                for (h in 0 until e.historySize) { val p = toBitmap(e.getHistoricalX(h), e.getHistoricalY(h)); addPoint(p[0], p[1]) }
                val p = toBitmap(e.x, e.y); addPoint(p[0], p[1])
            }
            MotionEvent.ACTION_UP -> if (drawing) {
                drawing = false; cursorVisible = false
                val pts = strokePts.toArray()
                strokePts.clear()
                if (pts.isNotEmpty()) onStroke?.invoke(pts, context.dp(brushRadiusDp) / scaleNow)
            }
            MotionEvent.ACTION_CANCEL -> { drawing = false; cursorVisible = false; strokePts.clear() }
        }
        invalidate()
    }

    private fun addPoint(x: Float, y: Float) {
        val n = strokePts.size
        if (n >= 2) {
            val dx = x - strokePts[n - 2]; val dy = y - strokePts[n - 1]
            // Skip points closer than ~1.5 screen px.
            if (dx * dx + dy * dy < (1.5f / scaleNow).let { it * it }) return
        }
        strokePts.add(x, y)
    }

    private fun cropTouch(e: MotionEvent) {
        val b = image ?: return
        val r = cropRectView(b)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = hitTest(r, e.x, e.y)
                lastX = e.x; lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging != Hit.NONE) {
                val s = scaleNow
                val dx = ((e.x - lastX) / (b.width * s)).toDouble()
                val dy = ((e.y - lastY) / (b.height * s)).toDouble()
                lastX = e.x; lastY = e.y
                crop = applyDrag(crop, dragging, dx, dy)
                onCropChanged?.invoke(crop, false)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging != Hit.NONE) {
                dragging = Hit.NONE
                onCropChanged?.invoke(crop, true)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                invalidate()
            }
        }
    }

    private fun hitTest(r: RectF, x: Float, y: Float): Hit {
        val t = context.dp(30).toFloat()
        val nearL = abs(x - r.left) < t; val nearR = abs(x - r.right) < t
        val nearT = abs(y - r.top) < t; val nearB = abs(y - r.bottom) < t
        val inX = x > r.left - t && x < r.right + t; val inY = y > r.top - t && y < r.bottom + t
        return when {
            nearL && nearT -> Hit.TL; nearR && nearT -> Hit.TR; nearL && nearB -> Hit.BL; nearR && nearB -> Hit.BR
            nearL && inY -> Hit.L; nearR && inY -> Hit.R; nearT && inX -> Hit.T; nearB && inX -> Hit.B
            r.contains(x, y) -> Hit.MOVE
            else -> Hit.NONE
        }
    }

    /** Moves crop edges by (dx, dy) frame fractions, keeping inside the frame, a minimum size and the aspect. */
    private fun applyDrag(c: CropRect, h: Hit, dx: Double, dy: Double): CropRect {
        val minW = 0.06; val minH = 0.06
        if (h == Hit.MOVE) {
            val w = c.width; val hh = c.height
            val l = (c.l + dx).coerceIn(0.0, 1 - w); val t = (c.t + dy).coerceIn(0.0, 1 - hh)
            return CropRect(l, t, l + w, t + hh)
        }
        var l = c.l; var t = c.t; var r = c.r; var b = c.b
        when (h) {
            Hit.L, Hit.TL, Hit.BL -> l = (l + dx).coerceIn(0.0, r - minW)
            Hit.R, Hit.TR, Hit.BR -> r = (r + dx).coerceIn(l + minW, 1.0)
            else -> {}
        }
        when (h) {
            Hit.T, Hit.TL, Hit.TR -> t = (t + dy).coerceIn(0.0, b - minH)
            Hit.B, Hit.BL, Hit.BR -> b = (b + dy).coerceIn(t + minH, 1.0)
            else -> {}
        }
        val a = aspect ?: return CropRect(l, t, r, b)
        // Locked aspect: width/height in frame fractions must equal ratioNorm.
        val ratioNorm = a * frameH / frameW
        val horizontalLead = h == Hit.L || h == Hit.R || ((h == Hit.TL || h == Hit.TR || h == Hit.BL || h == Hit.BR) && abs(dx) >= abs(dy))
        var w = r - l; var hh = b - t
        if (horizontalLead) hh = w / ratioNorm else w = hh * ratioNorm
        // Fit inside the frame, shrinking both if needed.
        val anchorX = when (h) { Hit.L, Hit.TL, Hit.BL -> r; Hit.R, Hit.TR, Hit.BR -> l; else -> (l + r) / 2 }
        val anchorY = when (h) { Hit.T, Hit.TL, Hit.TR -> b; Hit.B, Hit.BL, Hit.BR -> t; else -> (t + b) / 2 }
        val maxW = when (h) { Hit.L, Hit.TL, Hit.BL -> anchorX; Hit.R, Hit.TR, Hit.BR -> 1 - anchorX; else -> 2 * min(anchorX, 1 - anchorX) }
        val maxH = when (h) { Hit.T, Hit.TL, Hit.TR -> anchorY; Hit.B, Hit.BL, Hit.BR -> 1 - anchorY; else -> 2 * min(anchorY, 1 - anchorY) }
        val k = min(1.0, min(maxW / w, maxH / hh))
        w *= k; hh *= k
        if (w < minW || hh < minH) return c
        l = when (h) { Hit.L, Hit.TL, Hit.BL -> anchorX - w; Hit.R, Hit.TR, Hit.BR -> anchorX; else -> anchorX - w / 2 }
        t = when (h) { Hit.T, Hit.TL, Hit.TR -> anchorY - hh; Hit.B, Hit.BL, Hit.BR -> anchorY; else -> anchorY - hh / 2 }
        return CropRect(l, t, l + w, t + hh)
    }

    /** Growable float list without boxing. */
    private class FloatArrayList {
        private var a = FloatArray(256)
        var size = 0; private set
        operator fun get(i: Int) = a[i]
        fun add(x: Float, y: Float) { if (size + 2 > a.size) a = a.copyOf(a.size * 2); a[size++] = x; a[size++] = y }
        fun clear() { size = 0 }
        fun toArray() = a.copyOf(size)
    }
}
