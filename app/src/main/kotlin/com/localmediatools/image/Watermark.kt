package com.localmediatools.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The nine placement options. */
enum class WatermarkPosition(val row: Int, val col: Int, val label: String) {
    TOP_LEFT(0, 0, "Top left"), TOP_CENTER(0, 1, "Top center"), TOP_RIGHT(0, 2, "Top right"),
    CENTER_LEFT(1, 0, "Center left"), CENTER(1, 1, "Center"), CENTER_RIGHT(1, 2, "Center right"),
    BOTTOM_LEFT(2, 0, "Bottom left"), BOTTOM_CENTER(2, 1, "Bottom center"), BOTTOM_RIGHT(2, 2, "Bottom right");

    companion object { fun at(row: Int, col: Int) = entries.first { it.row == row && it.col == col } }
}

data class WatermarkStyle(
    val text: String?,
    val logo: Bitmap?,             // display-oriented logo, already decoded
    val sizePercent: Int,          // 10..50 of the image's shorter side
    val opacityPercent: Int,       // 10..100
    val textColor: Int,
    val bold: Boolean = true,
    val shadow: Boolean = true,
) {
    val hasContent get() = !text.isNullOrBlank() || logo != null
}

/**
 * Lays out and draws a watermark "stamp" (logo and/or text). The stamp's longer side is
 * [WatermarkStyle.sizePercent] of the image's shorter dimension; its proportions are never distorted.
 */
class WatermarkRenderer(private val wm: WatermarkStyle) {

    class Placement(val rect: RectF, val logoRect: RectF?, val textX: Float, val textBaseline: Float, val textSize: Float)

    private fun textPaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = wm.textColor
        textSize = size
        typeface = if (wm.bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }

    fun place(imageW: Int, imageH: Int, pos: WatermarkPosition): Placement {
        val short = min(imageW, imageH).toFloat()
        val target = short * wm.sizePercent.coerceIn(5, 60) / 100f
        val margin = short * 0.03f
        val text = wm.text?.trim()?.takeIf { it.isNotEmpty() }
        val logo = wm.logo
        // Layout at a reference size, then scale to the target.
        val ref = 100f
        val p = textPaint(ref)
        val fm = p.fontMetrics
        val textW = if (text != null) p.measureText(text) else 0f
        val textH = if (text != null) (fm.descent - fm.ascent) else 0f
        var logoW = 0f; var logoH = 0f
        if (logo != null) {
            logoH = if (text != null) textH * 1.6f else ref
            logoW = logoH * logo.width / logo.height.toFloat()
        }
        val gap = if (logo != null && text != null) textH * 0.35f else 0f
        val stampW = logoW + gap + textW
        val stampH = max(logoH, textH)
        val s = target / max(stampW, stampH)
        val w = stampW * s; val h = stampH * s
        val x = when (pos.col) { 0 -> margin; 1 -> (imageW - w) / 2f; else -> imageW - margin - w }
        val y = when (pos.row) { 0 -> margin; 1 -> (imageH - h) / 2f; else -> imageH - margin - h }
        val rect = RectF(x, y, x + w, y + h)
        val logoRect = if (logo != null) RectF(x, y + (h - logoH * s) / 2f, x + logoW * s, y + (h - logoH * s) / 2f + logoH * s) else null
        val textSize = ref * s
        val tx = x + (logoW + gap) * s
        val textTop = y + (h - textH * s) / 2f
        val baseline = textTop - fm.ascent * s
        return Placement(rect, logoRect, tx, baseline, textSize)
    }

    fun draw(canvas: Canvas, imageW: Int, imageH: Int, pos: WatermarkPosition) {
        val pl = place(imageW, imageH, pos)
        val alpha = (wm.opacityPercent.coerceIn(5, 100) * 255 / 100)
        wm.logo?.let { logo ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
            canvas.drawBitmap(logo, null, pl.logoRect!!, paint)
        }
        val text = wm.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val p = textPaint(pl.textSize)
        p.alpha = alpha
        if (wm.shadow) {
            val dark = luminance(wm.textColor) > 0.5
            p.setShadowLayer(pl.textSize * 0.08f, 0f, pl.textSize * 0.03f, if (dark) 0x99000000.toInt() else 0x99FFFFFF.toInt())
        }
        canvas.drawText(text, pl.textX, pl.textBaseline, p)
    }

    fun overlay(pos: WatermarkPosition) = Overlay { canvas, _, outW, outH -> draw(canvas, outW, outH, pos) }

    private fun luminance(c: Int): Double {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255
    }
}

/** Groups images by displayed aspect ratio so each shape can get its own watermark position. */
object AspectGroups {
    private val common = listOf(
        "1:1" to 1.0, "4:3" to 4.0 / 3, "3:4" to 3.0 / 4, "3:2" to 1.5, "2:3" to 2.0 / 3,
        "16:9" to 16.0 / 9, "9:16" to 9.0 / 16, "5:4" to 1.25, "4:5" to 0.8, "21:9" to 21.0 / 9, "9:21" to 9.0 / 21,
        "2:1" to 2.0, "1:2" to 0.5, "20:9" to 20.0 / 9, "9:20" to 9.0 / 20,
    )

    /** Stable key and human label for an image size. */
    fun key(w: Int, h: Int): Pair<String, String> {
        val r = w.toDouble() / h
        for ((label, v) in common) if (abs(r - v) / v < 0.025) return label to label
        val rounded = Math.round(r * 20) / 20.0
        val label = if (rounded >= 1) String.format(java.util.Locale.US, "%.2f:1", rounded) else String.format(java.util.Locale.US, "1:%.2f", 1 / rounded)
        return label to label
    }

    fun shapeName(w: Int, h: Int): String = when {
        abs(w - h) <= max(w, h) * 0.02 -> "Square"
        w > h -> "Landscape"
        else -> "Portrait"
    }
}
