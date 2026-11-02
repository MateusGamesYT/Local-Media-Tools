package com.localmediatools.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.localmediatools.highlight.core.Frame
import com.localmediatools.ui.typeface
import com.localmediatools.video.core.EditText
import com.localmediatools.video.core.TextPlace
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A text of the video editor drawn once for an output of [outW] × [outH]: white, bold, with a soft
 * shadow (or on a dark box), centred, wrapped to fit, at the top, middle or bottom, clear of the edges.
 * The preview draws texts with this same code.
 */
object EditTextPainter {
    fun paint(t: EditText, outW: Int, outH: Int): Pair<Bitmap, Frame> {
        val short = min(outW, outH).toFloat()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = typeface(700)
            textSize = short * 0.062f * t.size.coerceIn(0.5f, 2.5f)
            if (!t.box) setShadowLayer(short * 0.012f, 0f, short * 0.003f, 0xB0000000.toInt())
        }
        val maxW = (outW * 0.86f).toInt().coerceAtLeast(1)
        val text = t.text.ifBlank { " " }
        fun build() = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxW).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).build()
        var layout = build()
        // A long text is made smaller until it fits in the picture (with room around it).
        var tries = 0
        while (layout.height > outH * 0.86f && tries++ < 12) { paint.textSize *= 0.85f; layout = build() }
        var lw = 0f
        for (i in 0 until layout.lineCount) lw = max(lw, layout.getLineWidth(i))
        val pad = (short * if (t.box) 0.025f else 0.012f).toInt()
        val w = (ceil(lw).toInt() + 2 * pad).coerceAtLeast(1)
        val h = (layout.height + 2 * pad).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        if (t.box) cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), short * 0.02f, short * 0.02f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA6000000.toInt() })
        cv.save(); cv.translate(pad - (maxW - lw) / 2f, pad.toFloat()); layout.draw(cv); cv.restore()
        val fw = w.toFloat() / outW; val fh = h.toFloat() / outH
        val margin = short * 0.07f / outH
        val y = when (t.place) { TextPlace.TOP -> margin; TextPlace.MIDDLE -> (1 - fh) / 2; TextPlace.BOTTOM -> 1 - fh - margin }
        return bmp to Frame((1 - fw) / 2, y.coerceIn(0f, max(0f, 1f - fh)), fw, fh)
    }
}
