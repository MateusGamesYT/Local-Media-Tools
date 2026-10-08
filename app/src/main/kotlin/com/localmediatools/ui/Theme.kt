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
    const val BG = 0xFF08090D.toInt()
    const val BG_2 = 0xFF0C0E14.toInt()
    const val SURFACE = 0xFF11141B.toInt()
    const val SURFACE_2 = 0xFF181C25.toInt()
    const val SURFACE_3 = 0xFF222733.toInt()
    const val STROKE = 0xFF20252F.toInt()
    const val STROKE_2 = 0xFF2C3240.toInt()
    const val TEXT = 0xFFF3F5F9.toInt()
    const val TEXT_2 = 0xFFA8B0BF.toInt()
    const val TEXT_3 = 0xFF6C7486.toInt()
    const val ACCENT = 0xFF8C8CFF.toInt()
    const val ACCENT_DARK = 0xFF1F2147.toInt()
    const val ON_ACCENT = 0xFFFFFFFF.toInt()
    const val SUCCESS = 0xFF34D399.toInt()
    const val WARNING = 0xFFFBBF24.toInt()
    const val DANGER = 0xFFF87171.toInt()

    /** Brand gradient (violet → blue), also used by the app icon and primary buttons. */
    val BRAND = intArrayOf(0xFF7C5CFF.toInt(), 0xFF3D7BFF.toInt())
    /** On-device AI features. */
    val AI = intArrayOf(0xFFB15CFF.toInt(), 0xFFFF5C93.toInt())
    /** Secondary feature-card gradients. */
    val TEAL = intArrayOf(0xFF22C3A6.toInt(), 0xFF2D7DD2.toInt())
    val SUNSET = intArrayOf(0xFFFF8A4C.toInt(), 0xFFE5487A.toInt())

    fun section(s: ToolSection): Int = when (s) {
        ToolSection.EDIT -> 0xFFC084FC.toInt()
        ToolSection.IMAGES -> 0xFF2DD4BF.toInt()
        ToolSection.VIDEO -> 0xFFFB923C.toInt()
        ToolSection.GIF -> 0xFFFACC15.toInt()
        ToolSection.DOCUMENTS -> 0xFF60A5FA.toInt()
        ToolSection.PRIVACY -> 0xFF34D399.toInt()
    }

    /** Two-stop gradient for a section's icon tiles: a lighter top-left and a deeper bottom-right. */
    fun sectionGradient(s: ToolSection): IntArray = when (s) {
        ToolSection.EDIT -> AI
        ToolSection.IMAGES -> intArrayOf(0xFF34E3C5.toInt(), 0xFF0E9F8E.toInt())
        ToolSection.VIDEO -> intArrayOf(0xFFFFA65C.toInt(), 0xFFF0592C.toInt())
        ToolSection.GIF -> intArrayOf(0xFFFFD84A.toInt(), 0xFFF29A0C.toInt())
        ToolSection.DOCUMENTS -> intArrayOf(0xFF7DB8FF.toInt(), 0xFF3D6BFF.toInt())
        ToolSection.PRIVACY -> intArrayOf(0xFF4BE3A6.toInt(), 0xFF119E6E.toInt())
    }

    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, sh: Int) = (x shr sh) and 255
        fun m(sh: Int) = (ch(a, sh) + (ch(b, sh) - ch(a, sh)) * t).toInt().coerceIn(0, 255)
        return (m(24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}

object Icons {
    fun tool(t: ToolId): Int = when (t) {
        ToolId.PHOTO_EDITOR -> R.drawable.ic_tool_editor
        ToolId.MAGIC_ERASER -> R.drawable.ic_tool_eraser
        ToolId.BLUR_REDACT -> R.drawable.ic_tool_redact
        ToolId.BACKGROUND_REMOVER -> R.drawable.ic_tool_cutout
        ToolId.AUTO_ENHANCE -> R.drawable.ic_tool_enhance
        ToolId.FACE_BLUR -> R.drawable.ic_tool_faceblur
        ToolId.DUPLICATES -> R.drawable.ic_tool_duplicates
        ToolId.MERGE_VIDEOS -> R.drawable.ic_tool_video_merge
        ToolId.VIDEO_SPEED -> R.drawable.ic_tool_speed
        ToolId.SPLIT_VIDEO -> R.drawable.ic_tool_split
        ToolId.TRIM_VIDEO -> R.drawable.ic_tool_trim
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
        ToolId.EXTRACT_PDF_PAGES -> R.drawable.ic_tool_pdf_pages
        ToolId.PDF_SCANNER -> R.drawable.ic_tool_scanner
        ToolId.CONVERT_IMAGES -> R.drawable.ic_tool_convert
        ToolId.REMOVE_METADATA -> R.drawable.ic_tool_metadata
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

    /** Diagonal gradient (top-left → bottom-right). */
    fun gradient(ctx: Context, colors: IntArray, radiusDp: Float, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR): GradientDrawable =
        GradientDrawable(orientation, colors).apply { cornerRadius = ctx.dp(radiusDp).toFloat() }

    /** Background with a touch ripple clipped to the rounded shape. */
    fun clickable(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int? = null, ripple: Int = 0x26FFFFFF): Drawable {
        val content = rounded(ctx, color, radiusDp, strokeColor)
        val mask = rounded(ctx, Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(ripple), content, mask)
    }

    fun clickableGradient(ctx: Context, colors: IntArray, radiusDp: Float, ripple: Int = 0x33FFFFFF): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), gradient(ctx, colors, radiusDp), rounded(ctx, Color.WHITE, radiusDp))

    fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
}

enum class TextStyle(val sp: Float, val weight: Int, val color: Int, val letterSpacing: Float = 0f, val lineMult: Float = 1.2f) {
    HERO(32f, 760, Palette.TEXT, -0.025f, 1.08f),
    DISPLAY(26f, 720, Palette.TEXT, -0.02f, 1.12f),
    TITLE(20f, 680, Palette.TEXT, -0.012f, 1.15f),
    SUBTITLE(15.5f, 620, Palette.TEXT, -0.005f),
    BODY(15f, 430, Palette.TEXT),
    BODY_2(14f, 430, Palette.TEXT_2, 0f, 1.28f),
    CAPTION(12.5f, 450, Palette.TEXT_2, 0f, 1.25f),
    LABEL(11.5f, 680, Palette.TEXT_3, 0.09f),
    BUTTON(15f, 640, Palette.TEXT, 0f),
}

/** The bundled Inter variable font, falling back to the system sans-serif. */
object Fonts {
    private var assets: android.content.res.AssetManager? = null
    private val cache = HashMap<Int, Typeface>()

    fun init(ctx: Context) { assets = ctx.applicationContext.assets }

    fun get(weight: Int): Typeface = synchronized(cache) {
        cache.getOrPut(weight) {
            val w = weight.coerceIn(100, 900)
            val am = assets
            val inter = if (am != null) try {
                Typeface.Builder(am, "fonts/Inter.ttf").setFontVariationSettings("'wght' $w").setWeight(w).build()
            } catch (_: Exception) { null } else null
            inter ?: Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), w, false)
        }
    }
}

fun typeface(weight: Int): Typeface = Fonts.get(weight)

fun TextView.style(s: TextStyle, color: Int = s.color): TextView {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, s.sp)
    typeface = typeface(s.weight)
    setTextColor(color)
    letterSpacing = s.letterSpacing
    includeFontPadding = false
    setLineSpacing(0f, s.lineMult)
    return this
}
