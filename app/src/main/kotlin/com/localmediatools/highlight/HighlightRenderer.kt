package com.localmediatools.highlight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.localmediatools.highlight.core.Caption
import com.localmediatools.highlight.core.Frame
import com.localmediatools.highlight.core.Plan
import com.localmediatools.highlight.core.PlanComposition
import com.localmediatools.ui.typeface
import com.localmediatools.video.CaptionPainter
import com.localmediatools.video.CompositionRenderer
import java.io.FileDescriptor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a highlight [Plan] into an H.264 MP4 on the GPU ([CompositionRenderer], as the Timeline
 * describes it), with its captions: the title in the middle, each moment's name at the bottom left.
 */
class HighlightRenderer(
    private val ctx: Context,
    private val plan: Plan,
    /** The file of each shot (by [com.localmediatools.highlight.core.Shot.id]) and its name. */
    private val sources: (Int) -> Pair<Uri, String>,
    private val audio: EncodedAudio?,
) {
    fun run(out: FileDescriptor, outWidth: Int, outHeight: Int, bitrate: Int, throttle: () -> Unit, cancelled: () -> Boolean, progress: (Double) -> Unit): CompositionRenderer.Result =
        CompositionRenderer(ctx, PlanComposition(plan), sources, audio, CaptionPainter { i, w, h -> paint(plan.captions[i], w, h) })
            .run(out, outWidth, outHeight, bitrate, throttle, cancelled, progress)

    companion object {
        /** A caption drawn once: the title in the middle, a moment's name at the bottom left. */
        fun paint(c: Caption, outW: Int, outH: Int): Pair<Bitmap, Frame> {
            val short = min(outW, outH).toFloat()
            val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; typeface = typeface(if (c.title) 700 else 650)
                textSize = short * if (c.title) 0.072f else 0.05f
                setShadowLayer(short * 0.012f, 0f, short * 0.003f, 0xB0000000.toInt())
            }
            val sub = Paint(main).apply { typeface = typeface(500); textSize = main.textSize * if (c.title) 0.46f else 0.56f; color = 0xE6FFFFFF.toInt() }
            // Long titles are made smaller to fit.
            val maxW = outW * 0.86f
            while (main.measureText(c.text) > maxW && main.textSize > 8f) main.textSize *= 0.92f
            // A long line is shortened by whole characters (never half of an emoji or other surrogate pair).
            val subText = c.sub?.let { s ->
                var t = s; var end = s.length
                while (sub.measureText(t) > maxW && end > 1) { end = s.offsetByCodePoints(end, -1); t = s.substring(0, end).trimEnd() + "…" }
                t
            }
            val pad = (short * 0.03f).toInt()
            val gap = (main.textSize * 0.25f).toInt()
            val r = Rect(); main.getTextBounds("Ag", 0, 2, r)
            val w = (max(main.measureText(c.text), subText?.let { sub.measureText(it) } ?: 0f) + 2 * pad).toInt().coerceAtLeast(1)
            val h = (main.textSize * 1.25f + (if (subText != null) sub.textSize * 1.3f + gap else 0f) + 2 * pad).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val centre = c.title
            fun x(p: Paint, s: String) = if (centre) (w - p.measureText(s)) / 2 else pad.toFloat()
            var y = pad + main.textSize
            cv.drawText(c.text, x(main, c.text), y, main)
            if (subText != null) { y += gap + sub.textSize * 1.15f; cv.drawText(subText, x(sub, subText), y, sub) }
            val fw = w.toFloat() / outW; val fh = h.toFloat() / outH
            // Moments' names sit at the bottom left, clear of the edges.
            val margin = short * 0.05f
            val dst = if (centre) Frame((1 - fw) / 2, (1 - fh) / 2, fw, fh) else Frame(margin / outW, 1f - fh - margin * 1.2f / outH, fw, fh)
            return bmp to dst
        }
    }
}
