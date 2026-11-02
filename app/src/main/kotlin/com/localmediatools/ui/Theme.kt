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

/**
 * One set of the app's colours: the light or the dark theme. Text colours meet WCAG AA (4.5:1) on the
 * backgrounds and surfaces they are used on (ThemeContrastTest checks every pair).
 */
class Scheme(
    val dark: Boolean,
    val bg: Int, val bg2: Int, val surface: Int, val surface2: Int, val surface3: Int,
    val stroke: Int, val stroke2: Int,
    val text: Int, val text2: Int, val text3: Int,
    val accent: Int, val accentContainer: Int, val onAccent: Int,
    val success: Int, val warning: Int, val danger: Int,
    /** Brand gradient (violet → blue): primary buttons, progress, the app icon. */
    val brand: IntArray,
    /** Each tool section's colour (markers, tinted icons). */
    val sections: IntArray,
)

object Palette {
    val DARK = Scheme(
        dark = true,
        bg = 0xFF0B0C10.toInt(), bg2 = 0xFF101218.toInt(), surface = 0xFF14161D.toInt(), surface2 = 0xFF1B1E27.toInt(), surface3 = 0xFF262A35.toInt(),
        stroke = 0xFF22262F.toInt(), stroke2 = 0xFF2F3441.toInt(),
        text = 0xFFF2F4F8.toInt(), text2 = 0xFFB0B7C4.toInt(), text3 = 0xFF8C94A6.toInt(),
        accent = 0xFF9C96FF.toInt(), accentContainer = 0xFF27245A.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        success = 0xFF3DD9A0.toInt(), warning = 0xFFFBC02D.toInt(), danger = 0xFFFF8080.toInt(),
        brand = intArrayOf(0xFF7357FF.toInt(), 0xFF3B6CF0.toInt()),
        sections = intArrayOf(0xFF60D5FA.toInt(), 0xFFC084FC.toInt(), 0xFF2DD4BF.toInt(), 0xFFFB923C.toInt(), 0xFFFACC15.toInt(),
            0xFF60A5FA.toInt(), 0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFF34D399.toInt()),
    )
    val LIGHT = Scheme(
        dark = false,
        bg = 0xFFF5F6FA.toInt(), bg2 = 0xFFFFFFFF.toInt(), surface = 0xFFFFFFFF.toInt(), surface2 = 0xFFEEF0F5.toInt(), surface3 = 0xFFE3E6EE.toInt(),
        stroke = 0xFFE4E7EE.toInt(), stroke2 = 0xFFD2D7E1.toInt(),
        text = 0xFF12141A.toInt(), text2 = 0xFF464D5C.toInt(), text3 = 0xFF5D6577.toInt(),
        accent = 0xFF5144E0.toInt(), accentContainer = 0xFFE8E6FF.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        success = 0xFF0B7A54.toInt(), warning = 0xFF9A5B00.toInt(), danger = 0xFFC42B3F.toInt(),
        brand = intArrayOf(0xFF7357FF.toInt(), 0xFF3B6CF0.toInt()),
        sections = intArrayOf(0xFF0891B2.toInt(), 0xFF9333EA.toInt(), 0xFF0D9488.toInt(), 0xFFEA580C.toInt(), 0xFFA16207.toInt(),
            0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), 0xFF059669.toInt()),
    )

    /** The theme in use (set by [AppTheme] before any screen is built). */
    @Volatile var scheme: Scheme = DARK
    val isDark: Boolean get() = scheme.dark

    val BG get() = scheme.bg
    val BG_2 get() = scheme.bg2
    val SURFACE get() = scheme.surface
    val SURFACE_2 get() = scheme.surface2
    val SURFACE_3 get() = scheme.surface3
    val STROKE get() = scheme.stroke
    val STROKE_2 get() = scheme.stroke2
    val TEXT get() = scheme.text
    val TEXT_2 get() = scheme.text2
    val TEXT_3 get() = scheme.text3
    val ACCENT get() = scheme.accent
    /** A soft accent background (selected chips, the current tab), with [TEXT] or [ACCENT] on it. */
    val ACCENT_DARK get() = scheme.accentContainer
    val ON_ACCENT get() = scheme.onAccent
    val SUCCESS get() = scheme.success
    val WARNING get() = scheme.warning
    val DANGER get() = scheme.danger

    /** Brand gradient (violet → blue), also used by the app icon and primary buttons. */
    val BRAND: IntArray get() = scheme.brand
    /** On-device AI features. */
    val AI = intArrayOf(0xFF9C45EB.toInt(), 0xFFD8336F.toInt())
    /** Secondary feature-card gradients. */
    val TEAL = intArrayOf(0xFF118C79.toInt(), 0xFF2560B8.toInt())
    val SUNSET = intArrayOf(0xFFD95A20.toInt(), 0xFFC22E5E.toInt())

    fun section(s: ToolSection): Int = scheme.sections[s.ordinal]

    /**
     * Two-stop gradient for a section's icon tiles: a lighter top-left and a deeper bottom-right, both
     * dark enough for the white glyph on them (3:1, WCAG's measure for icons).
     */
    fun sectionGradient(s: ToolSection): IntArray = when (s) {
        ToolSection.STACKS -> intArrayOf(0xFF1A9BC4.toInt(), 0xFF3157D6.toInt())
        ToolSection.EDIT -> intArrayOf(0xFF9B47F0.toInt(), 0xFFD63B6E.toInt())
        ToolSection.IMAGES -> intArrayOf(0xFF12998A.toInt(), 0xFF0B7568.toInt())
        ToolSection.VIDEO -> intArrayOf(0xFFE8642A.toInt(), 0xFFC73E1A.toInt())
        ToolSection.GIF -> intArrayOf(0xFFCF7A00.toInt(), 0xFFB85F00.toInt())
        ToolSection.DOCUMENTS -> intArrayOf(0xFF3E7BF2.toInt(), 0xFF2A4FD6.toInt())
        ToolSection.AUDIO -> intArrayOf(0xFF8B63F5.toInt(), 0xFF6328D9.toInt())
        ToolSection.PRINT -> intArrayOf(0xFFDB3D8A.toInt(), 0xFFB51C5E.toInt())
        ToolSection.PRIVACY -> intArrayOf(0xFF13916A.toInt(), 0xFF0A7553.toInt())
    }

    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, sh: Int) = (x shr sh) and 255
        fun m(sh: Int) = (ch(a, sh) + (ch(b, sh) - ch(a, sh)) * t).toInt().coerceIn(0, 255)
        return (m(24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** WCAG contrast ratio of two opaque colours (1–21). */
    fun contrast(a: Int, b: Int): Double {
        fun lin(c: Int): Double { val x = c / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        fun lum(c: Int) = 0.2126 * lin((c shr 16) and 255) + 0.7152 * lin((c shr 8) and 255) + 0.0722 * lin(c and 255)
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

object Icons {
    fun tool(t: ToolId): Int = when (t) {
        ToolId.TOOL_STACK -> R.drawable.ic_tool_stack
        ToolId.PHOTO_EDITOR -> R.drawable.ic_tool_editor
        ToolId.MAGIC_ERASER -> R.drawable.ic_tool_eraser
        ToolId.BLUR_REDACT -> R.drawable.ic_tool_redact
        ToolId.BACKGROUND_REMOVER -> R.drawable.ic_tool_cutout
        ToolId.AUTO_ENHANCE -> R.drawable.ic_tool_enhance
        ToolId.DENOISE -> R.drawable.ic_tool_denoise
        ToolId.FACE_BLUR -> R.drawable.ic_tool_faceblur
        ToolId.DUPLICATES -> R.drawable.ic_tool_duplicates
        ToolId.MERGE_VIDEOS -> R.drawable.ic_tool_video_merge
        ToolId.HIGHLIGHT_VIDEO -> R.drawable.ic_tool_highlight
        ToolId.VIDEO_EDITOR -> R.drawable.ic_tool_video_editor
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
        ToolId.READER -> R.drawable.ic_tool_reader
        ToolId.PDF_TO_IMAGES -> R.drawable.ic_tool_pdf_images
        ToolId.IMAGES_TO_PDF -> R.drawable.ic_tool_images_pdf
        ToolId.MERGE_PDFS -> R.drawable.ic_tool_merge_pdf
        ToolId.EXTRACT_PDF_PAGES -> R.drawable.ic_tool_pdf_pages
        ToolId.PDF_SCANNER -> R.drawable.ic_tool_scanner
        ToolId.CONVERT_IMAGES -> R.drawable.ic_tool_convert
        ToolId.REMOVE_METADATA -> R.drawable.ic_tool_metadata
        ToolId.METADATA -> R.drawable.ic_tool_inspect
        ToolId.EXTRACT_AUDIO -> R.drawable.ic_tool_audio
        ToolId.TRIM_AUDIO -> R.drawable.ic_tool_audio_trim
        ToolId.MERGE_AUDIO -> R.drawable.ic_tool_audio_merge
        ToolId.CONVERT_AUDIO -> R.drawable.ic_tool_audio_convert
        ToolId.AUDIO_VOLUME -> R.drawable.ic_tool_volume
        ToolId.AUDIO_SPEED -> R.drawable.ic_tool_audio_speed
        ToolId.SPLIT_AUDIO -> R.drawable.ic_tool_audio_split
        ToolId.REMOVE_SILENCE -> R.drawable.ic_tool_silence
        ToolId.PRINT -> R.drawable.ic_tool_print
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

enum class TextStyle(val sp: Float, val weight: Int, private val tone: Int, val letterSpacing: Float = 0f, val lineMult: Float = 1.2f) {
    HERO(32f, 760, 1, -0.025f, 1.08f),
    DISPLAY(26f, 720, 1, -0.02f, 1.12f),
    TITLE(20f, 680, 1, -0.012f, 1.15f),
    SUBTITLE(15.5f, 620, 1, -0.005f),
    BODY(15f, 430, 1),
    BODY_2(14f, 430, 2, 0f, 1.28f),
    CAPTION(12.5f, 450, 2, 0f, 1.25f),
    LABEL(11.5f, 680, 3, 0.09f),
    BUTTON(15f, 640, 1, 0f);

    /** The style's text colour in the current theme. */
    val color: Int get() = when (tone) { 1 -> Palette.TEXT; 2 -> Palette.TEXT_2; else -> Palette.TEXT_3 }
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
    // Line breaks and hyphens follow the app's language, which can differ from the phone's: long words
    // (German compounds, "automaticamente") are hyphenated instead of being cut anywhere.
    textLocale = com.localmediatools.i18n.I18n.locale
    if (s != TextStyle.LABEL) hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
    return this
}
