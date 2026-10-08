package com.localmediatools.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.localmediatools.codec.edit.Affine
import com.localmediatools.codec.edit.ColorPipeline
import com.localmediatools.codec.edit.GeometryPlan
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.JobContext
import com.localmediatools.image.BitmapOps
import com.localmediatools.image.EncodeSpec
import com.localmediatools.image.ImagePipeline
import com.localmediatools.image.ImageSource
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The upright photo with retouch patches applied, readable in regions at full resolution. */
class RetouchedSource(val src: ImageSource, private val store: PatchStore?, private val patches: List<RetouchPatch>) {
    val width get() = src.width
    val height get() = src.height

    /** Region [rect] (upright-photo pixels) at 1/[sample] scale, patches included. Always a mutable ARGB_8888 bitmap. */
    fun decode(rect: Rect, sample: Int = 1, budget: Long = Long.MAX_VALUE): Bitmap {
        val raw = BitmapOps.ensureArgb8888(src.decodeRegion(rect, sample, budget))
        val bmp = if (raw.isMutable) raw else raw.copy(Bitmap.Config.ARGB_8888, true).also { raw.recycle() }
            ?: throw OutOfMemoryError()
        val hits = patches.filter { it.intersects(rect.left, rect.top, rect.right, rect.bottom) }
        if (hits.isNotEmpty() && store != null) {
            val c = Canvas(bmp)
            val sx = bmp.width.toFloat() / rect.width(); val sy = bmp.height.toFloat() / rect.height()
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (p in hits) {
                val pb = store.load(p)
                val dst = RectF((p.left - rect.left) * sx, (p.top - rect.top) * sy, (p.right - rect.left) * sx, (p.bottom - rect.top) * sy)
                c.drawBitmap(pb, null, dst, paint)
            }
        }
        return bmp
    }

    /** Whole photo scaled down by [sample] with patches (used for the editor's working copy). */
    fun decodeWhole(sample: Int): Bitmap = decode(Rect(0, 0, width, height), sample)
}

object EditRenderer {
    /** Source → output mapping as an android Matrix. */
    fun matrix(a: Affine) = Matrix().apply { setValues(a.toMatrixValues()) }

    /**
     * Draws [base] (the upright photo at [baseScale] of full size) transformed by [toOut] (full-size
     * source → output coordinates) and scaled by [outScale], then applies colours. Used for previews.
     */
    fun preview(base: Bitmap, baseScale: Double, toOut: Affine, outW: Int, outH: Int, outScale: Double, state: EditState, colors: Boolean = true): Bitmap {
        val w = max(1, (outW * outScale).roundToInt()); val h = max(1, (outH * outScale).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Affine.scale(1 / baseScale).then(toOut).then(Affine.scale(outScale))
        Canvas(out).drawBitmap(base, matrix(m), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        if (colors) applyColors(out, state, sharpenScale = outScale)
        return out
    }

    /** Colour pipeline (and sharpening) in place on a whole bitmap. */
    fun applyColors(bmp: Bitmap, state: EditState, sharpenScale: Double = 1.0) {
        val pipe = ColorPipeline(state.adjust, state.filter)
        val sharp = pipe.sharpness * min(1.0, sharpenScale * 2.5).toFloat()
        if (pipe.isIdentity && kotlin.math.abs(sharp) < 0.01f) return
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val res = if (kotlin.math.abs(sharp) >= 0.01f) {
            val pad = ColorPipeline.SHARPEN_PAD
            val src = IntArray(w * (h + 2 * pad))
            for (r in 0 until h + 2 * pad) System.arraycopy(px, (r - pad).coerceIn(0, h - 1) * w, src, r * w, w)
            IntArray(w * h).also { ColorPipeline.sharpenRows(src, w, h + 2 * pad, pad, sharp, it, 0) }
        } else px
        pipe.apply(res, 0, w, h, 0, w, h)
        bmp.setPixels(res, 0, w, 0, 0, w, h)
    }

    /**
     * Full-resolution export in horizontal strips, so memory stays bounded whatever the photo size:
     * each strip decodes only the source band it needs (patches applied), transforms it, sharpens,
     * applies colours and streams the rows to the encoder.
     */
    fun exportFull(ctx: JobContext, rs: RetouchedSource, state: EditState, spec: EncodeSpec, keepAlpha: Boolean, out: OutputStream, onProgress: (Double) -> Unit): Pair<Int, Int> {
        val plan = GeometryPlan(rs.width, rs.height, state.geometry)
        val outW = plan.outW; val outH = plan.outH
        if (outW > spec.format.maxDimension || outH > spec.format.maxDimension) {
            throw UserFacingException("${spec.format.label} supports at most ${spec.format.maxDimension} px per side; the edited photo is ${outW}×$outH. Choose PNG or JPEG.")
        }
        val pipe = ColorPipeline(state.adjust, state.filter)
        val sharp = pipe.sharpness
        val pad = if (kotlin.math.abs(sharp) >= 0.01f) ColorPipeline.SHARPEN_PAD else 0
        val budget = ctx.memoryBudget()
        // Rows per strip: the source band of one output row can be wide when straightened.
        val probe = plan.sourceBounds(0.0, 0.0, outW.toDouble(), 1.0)
        val bandPerRow = ((probe[2] - probe[0]).toLong() * 4 * 2)
        val extraRows = (probe[3] - probe[1]).toLong()
        var stripRows = ((budget / 3) / max(1L, bandPerRow) - extraRows).toInt()
        stripRows = stripRows.coerceIn(8, 1024).coerceAtMost(outH)
        if ((probe[2] - probe[0]).toLong() * (extraRows + stripRows) * 4 > budget) {
            throw UserFacingException("This photo is too large to edit with the memory available right now. Close other apps or raise the export workload and try again.")
        }
        val streaming = spec.format.streamable
        val hasAlpha = spec.format.supportsAlpha && keepAlpha
        val whole: Bitmap? = if (streaming) null else {
            if (outW.toLong() * outH * 4 * 2 > budget) throw UserFacingException("${spec.format.label} can't be written in pieces and this photo is too large for memory; choose JPEG or PNG.")
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        }
        val enc = if (streaming) ImagePipeline.stripEncoder(spec, outW, outH, hasAlpha, out) else null
        val strip = Bitmap.createBitmap(outW, stripRows + 2 * pad, Bitmap.Config.ARGB_8888)
        val src = IntArray(outW * (stripRows + 2 * pad))
        val res = IntArray(outW * stripRows)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        try {
            var y = 0
            while (y < outH) {
                ctx.throttle()
                val rows = min(stripRows, outH - y)
                val top = y - pad
                val bottom = y + rows + pad
                val sb = plan.sourceBounds(0.0, top.toDouble(), outW.toDouble(), bottom.toDouble())
                val region = rs.decode(Rect(sb[0], sb[1], sb[2], sb[3]), 1, budget)
                strip.eraseColor(Color.TRANSPARENT)
                val m = Affine.translate(sb[0].toDouble(), sb[1].toDouble()).then(plan.forward).then(Affine.translate(0.0, -top.toDouble()))
                Canvas(strip).drawBitmap(region, matrix(m), paint)
                region.recycle()
                val srcRows = rows + 2 * pad
                strip.getPixels(src, 0, outW, 0, 0, outW, srcRows)
                if (pad > 0) {
                    // Rows outside the picture: replicate the nearest edge row.
                    for (r in 0 until srcRows) {
                        val yy = top + r
                        if (yy < 0) System.arraycopy(src, (-top) * outW, src, r * outW, outW)
                        else if (yy >= outH) System.arraycopy(src, (outH - 1 - top) * outW, src, r * outW, outW)
                    }
                    ColorPipeline.sharpenRows(src, outW, srcRows, pad, sharp, res, 0)
                } else {
                    System.arraycopy(src, 0, res, 0, outW * rows)
                }
                pipe.apply(res, 0, outW, rows, y, outW, outH)
                if (enc != null) enc.write(res, 0, outW, rows) else whole!!.setPixels(res, 0, outW, 0, y, outW, rows)
                y += rows
                onProgress(y.toDouble() / outH)
            }
            if (enc != null) enc.finish() else ImagePipeline.encodeBitmap(whole!!, spec, out)
        } finally {
            strip.recycle()
            whole?.recycle()
        }
        return outW to outH
    }
}
