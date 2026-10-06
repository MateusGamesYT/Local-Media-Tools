package com.localmediatools.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.localmediatools.app.R
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.ToolSection

object Palette {
    const val BG = 0xFF0B0F14.toInt()
    const val SURFACE = 0xFF141A22.toInt()
    const val SURFACE_2 = 0xFF1A222D.toInt()
    const val SURFACE_3 = 0xFF222C39.toInt()
    const val STROKE = 0xFF263142.toInt()
    const val TEXT = 0xFFEEF2F7.toInt()
    const val TEXT_2 = 0xFFA3ADBD.toInt()
    const val TEXT_3 = 0xFF6F7B8F.toInt()
    const val ACCENT = 0xFF4DA3FF.toInt()
    const val ACCENT_DARK = 0xFF16324F.toInt()
    const val ON_ACCENT = 0xFF06121F.toInt()
    const val SUCCESS = 0xFF3DDC97.toInt()
    const val WARNING = 0xFFFFB547.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()

    fun section(s: ToolSection): Int = when (s) {
        ToolSection.VIDEO -> 0xFFFF7A59.toInt()
        ToolSection.IMAGES -> 0xFF2EC4B6.toInt()
        ToolSection.GIF -> 0xFFFFB547.toInt()
        ToolSection.DOCUMENTS -> 0xFF4DA3FF.toInt()
        ToolSection.UTILITIES -> 0xFFA78BFA.toInt()
    }

    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
}

object Icons {
    fun tool(t: ToolId): Int = when (t) {
        ToolId.SPLIT_VIDEO -> R.drawable.ic_tool_split
        ToolId.OPTIMIZE_VIDEO -> R.drawable.ic_tool_video_optimize
        ToolId.COMPRESS_VIDEO -> R.drawable.ic_tool_video_compress
        ToolId.REMOVE_AUDIO -> R.drawable.ic_tool_mute
        ToolId.COMPRESS_IMAGES -> R.drawable.ic_tool_image_compress
        ToolId.OPTIMIZE_IMAGES -> R.drawable.ic_tool_lossless
        ToolId.MERGE_IMAGES -> R.drawable.ic_tool_merge
        ToolId.STITCH -> R.drawable.ic_tool_stitch
        ToolId.WATERMARK -> R.drawable.ic_tool_watermark
        ToolId.VIDEO_TO_GIF -> R.drawable.ic_tool_video_gif
        ToolId.COMPRESS_GIF -> R.drawable.ic_tool_gif_compress
        ToolId.OPTIMIZE_GIF -> R.drawable.ic_tool_gif_optimize
        ToolId.PDF_TO_IMAGES -> R.drawable.ic_tool_pdf_images
        ToolId.IMAGES_TO_PDF -> R.drawable.ic_tool_images_pdf
        ToolId.MERGE_PDFS -> R.drawable.ic_tool_merge_pdf
        ToolId.PDF_SCANNER -> R.drawable.ic_tool_scanner
        ToolId.CONVERT_IMAGES -> R.drawable.ic_tool_convert
        ToolId.EXTRACT_AUDIO -> R.drawable.ic_tool_audio
    }
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun View.dp(v: Int): Int = context.dp(v)

fun Context.icon(res: Int, tint: Int): Drawable =
    getDrawable(res)!!.mutate().apply { setTint(tint) }

object Shapes {
    fun rounded(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(ctx.dp(strokeDp).coerceAtLeast(1), strokeColor)
        }

    fun pill(ctx: Context, color: Int, strokeColor: Int? = null) = rounded(ctx, color, 100f, strokeColor)

    /** Background with a touch ripple clipped to the rounded shape. */
    fun clickable(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int? = null, ripple: Int = 0x33FFFFFF): Drawable {
        val content = rounded(ctx, color, radiusDp, strokeColor)
        val mask = rounded(ctx, Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(ripple), content, mask)
    }

    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
}

enum class TextStyle(val sp: Float, val weight: Int, val color: Int, val letterSpacing: Float = 0f) {
    DISPLAY(28f, 700, Palette.TEXT, -0.01f),
    TITLE(20f, 650, Palette.TEXT),
    SUBTITLE(16f, 600, Palette.TEXT),
    BODY(14.5f, 400, Palette.TEXT),
    BODY_2(14f, 400, Palette.TEXT_2),
    CAPTION(12.5f, 400, Palette.TEXT_2),
    LABEL(12f, 650, Palette.TEXT_3, 0.08f),
    BUTTON(15f, 650, Palette.TEXT),
}

private val typefaceCache = HashMap<Int, Typeface>()

fun typeface(weight: Int): Typeface = typefaceCache.getOrPut(weight) {
    Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight.coerceIn(100, 900), false)
}

fun TextView.style(s: TextStyle, color: Int = s.color): TextView {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, s.sp)
    typeface = typeface(s.weight)
    setTextColor(color)
    letterSpacing = s.letterSpacing
    includeFontPadding = false
    setLineSpacing(0f, 1.18f)
    return this
}
