package com.localmediatools.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.localmediatools.highlight.core.Frame
import com.localmediatools.video.EditMedia
import com.localmediatools.video.EditTextPainter
import com.localmediatools.video.core.EditTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The editor's picture at one moment of the project, drawn with the same rules as the saved video
 * ([EditTimeline]: which clips, which part of each, turned, coloured, faded, over a blurred copy where
 * the shapes differ, and the texts as [EditTextPainter] draws them), from frames read for it.
 */
class EditPreview(ctx: Context, private val scope: CoroutineScope) : View(ctx) {
    var timeline: EditTimeline? = null
        set(v) { field = v; textCache.clear(); requestFrames(); invalidate() }
    var timeMs: Long = 0
        set(v) { field = v; requestFrames(); invalidate() }
    /** While a video plays on top, only the texts are drawn here. */
    var textsOnly = false
        set(v) { field = v; invalidate() }

    /** The output's rectangle inside the view (where the video is). */
    val box = RectF()
    /** The last frame read for each clip (by its id, not its place: clips move and are removed): its file, time and picture. */
    private class Shown(val uri: String, val ms: Long, val bmp: Bitmap)
    private val frames = HashMap<Long, Shown>()
    private val loading = HashMap<Long, Job>()
    private val textCache = HashMap<String, Pair<Bitmap, Frame>>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val black = Paint().apply { color = Color.BLACK }
    /** Frames drawn (for tests). */
    var drawn = 0; private set

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { textCache.clear(); requestFrames() }

    private fun layoutBox() {
        val tl = timeline
        val a = tl?.outAspect ?: (16f / 9f)
        val pad = 0f
        val w = width - 2 * pad; val h = height - 2 * pad
        if (w <= 0 || h <= 0) { box.setEmpty(); return }
        val bw: Float; val bh: Float
        if (w / h > a) { bh = h; bw = h * a } else { bw = w; bh = w / a }
        box.set((width - bw) / 2, (height - bh) / 2, (width + bw) / 2, (height + bh) / 2)
    }

    /** Reads the frames the current moment needs (the last one of each clip stays up meanwhile). */
    private fun requestFrames() {
        val tl = timeline ?: return
        if (width == 0) return
        val desc = tl.at(timeMs.toDouble())
        val side = (max(width, height) * 0.9f).toInt().coerceIn(320, 1280)
        for (l in desc.layers) {
            val c = tl.project.clips.getOrNull(l.clip) ?: continue
            val have = frames[c.id]
            if (have != null && have.uri == c.source.uri && (c.isPhoto || kotlin.math.abs(have.ms - l.sourceMs) < 40)) continue
            loading[c.id]?.cancel()
            val ms = l.sourceMs
            loading[c.id] = scope.launch {
                val b = withContext(Dispatchers.IO) { EditMedia.frame(context, c.source, ms, side) } ?: return@launch
                frames[c.id] = Shown(c.source.uri, ms, b)
                invalidate()
            }
        }
        // Clips no longer in the project.
        val ids = tl.project.clips.mapTo(HashSet()) { it.id }
        frames.keys.retainAll(ids)
    }

    /** Frames of clips that changed (trimmed, replaced) are read again. */
    fun forget() { frames.clear(); requestFrames() }

    override fun onDraw(canvas: Canvas) {
        layoutBox()
        val tl = timeline ?: return
        if (box.isEmpty) return
        val desc = tl.at(timeMs.toDouble())
        if (!textsOnly) {
            canvas.drawRect(box, black)
            for (l in desc.layers) {
                val c = tl.project.clips.getOrNull(l.clip) ?: continue
                val bmp = frames[c.id]?.takeIf { it.uri == c.source.uri }?.bmp ?: continue
                val cf = l.color?.let { ColorMatrixColorFilter(colorMatrix(it)) }
                if (l.backdrop) {
                    // A blurred, darkened copy behind: the frame drawn tiny and stretched.
                    val tiny = Bitmap.createScaledBitmap(bmp, max(1, bmp.width / 24), max(1, bmp.height / 24), true)
                    paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.55f, 0.55f, 0.55f, 1f) })
                    paint.alpha = (255 * l.alpha).toInt()
                    draw(canvas, tiny, l.backdropSrc, Frame(0f, 0f, 1f, 1f), c.rotation)
                    tiny.recycle()
                }
                paint.colorFilter = cf
                paint.alpha = (255 * l.alpha).toInt()
                draw(canvas, bmp, l.src, l.dst, c.rotation)
            }
            paint.colorFilter = null; paint.alpha = 255
        }
        for (cl in desc.captions) {
            val t = tl.project.texts.getOrNull(cl.caption) ?: continue
            val key = "${t.id}:${t.text}:${t.place}:${t.size}:${t.box}:${box.width().toInt()}x${box.height().toInt()}"
            val (bmp, dst) = textCache.getOrPut(key) { EditTextPainter.paint(t, box.width().toInt().coerceAtLeast(2), box.height().toInt().coerceAtLeast(2)) }
            paint.alpha = (255 * cl.alpha).toInt()
            canvas.drawBitmap(bmp, null, RectF(box.left + dst.x * box.width(), box.top + dst.y * box.height(),
                box.left + (dst.x + dst.w) * box.width(), box.top + (dst.y + dst.h) * box.height()), paint)
            paint.alpha = 255
        }
        if (desc.fade > 0f && !textsOnly) canvas.drawRect(box, Paint().apply { color = Color.BLACK; alpha = (255 * desc.fade).toInt() })
        drawn++
    }

    /** Draws the part [src] of [bmp] (fractions of the picture as shown, turned [rotation]) into [dst] (fractions of the box). */
    private fun draw(canvas: Canvas, bmp: Bitmap, src: Frame, dst: Frame, rotation: Int) {
        val d = RectF(box.left + dst.x * box.width(), box.top + dst.y * box.height(), box.left + (dst.x + dst.w) * box.width(), box.top + (dst.y + dst.h) * box.height())
        canvas.save()
        canvas.clipRect(d)
        canvas.concat(matrix(bmp.width, bmp.height, rotation, src, d))
        canvas.drawBitmap(bmp, 0f, 0f, paint)
        canvas.restore()
    }

    companion object {
        /**
         * From a picture of [bw] × [bh] px (as stored) to the screen: turned [rotation] degrees clockwise,
         * then its part [src] (fractions of the turned picture) onto [dst] (px).
         */
        fun matrix(bw: Int, bh: Int, rotation: Int, src: Frame, dst: RectF): Matrix {
            val r = ((rotation % 360) + 360) % 360
            val m = Matrix()
            m.setRotate(r.toFloat())
            when (r) { 90 -> m.postTranslate(bh.toFloat(), 0f); 180 -> m.postTranslate(bw.toFloat(), bh.toFloat()); 270 -> m.postTranslate(0f, bw.toFloat()) }
            val rw = if (r % 180 != 0) bh else bw; val rh = if (r % 180 != 0) bw else bh
            m.postTranslate(-src.x * rw, -src.y * rh)
            m.postScale(dst.width() / max(1e-6f, src.w * rw), dst.height() / max(1e-6f, src.h * rh))
            m.postTranslate(dst.left, dst.top)
            return m
        }

        /** The renderer's colour matrix (column-major 4×4 on 0–1) as Android's (4×5 on 0–255). */
        fun colorMatrix(m: FloatArray): ColorMatrix = ColorMatrix(floatArrayOf(
            m[0], m[4], m[8], 0f, m[12] * 255f,
            m[1], m[5], m[9], 0f, m[13] * 255f,
            m[2], m[6], m[10], 0f, m[14] * 255f,
            0f, 0f, 0f, 1f, 0f,
        ))

        /**
         * For video playing on a TextureView of the box's size (which stretches the picture as shown over
         * it): turned [rotation] degrees, its part [src] onto [dst] (fractions of the box).
         */
        fun textureMatrix(w: Float, h: Float, rotation: Int, src: Frame, dst: Frame): Matrix {
            val r = ((rotation % 360) + 360) % 360
            val m = Matrix()
            m.setScale(1f / w, 1f / h)
            m.postRotate(r.toFloat())
            when (r) { 90 -> m.postTranslate(1f, 0f); 180 -> m.postTranslate(1f, 1f); 270 -> m.postTranslate(0f, 1f) }
            m.postTranslate(-src.x, -src.y)
            m.postScale(dst.w * w / max(1e-6f, src.w), dst.h * h / max(1e-6f, src.h))
            m.postTranslate(dst.x * w, dst.y * h)
            return m
        }
    }
}
