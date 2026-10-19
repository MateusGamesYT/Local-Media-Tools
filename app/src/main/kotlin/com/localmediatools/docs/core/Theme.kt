package com.localmediatools.docs.core

import com.localmediatools.docs.core.XmlReader.Event

/** An Office theme's colours (theme1.xml: dk1, lt1, accent1…) and its two fonts. */
class Theme(val colors: Map<String, Int>, val majorFont: String?, val minorFont: String?) {
    companion object {
        val DEFAULT = Theme(mapOf(
            "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(), "dk2" to 0xFF44546A.toInt(), "lt2" to 0xFFE7E6E6.toInt(),
            "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(), "accent3" to 0xFFA5A5A5.toInt(), "accent4" to 0xFFFFC000.toInt(),
            "accent5" to 0xFF5B9BD5.toInt(), "accent6" to 0xFF70AD47.toInt(), "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt(),
        ), null, null)

        fun read(parts: Parts, part: String?): Theme {
            val x = part?.let { parts.xml(it) } ?: return DEFAULT
            val colors = HashMap(DEFAULT.colors)
            var major: String? = null; var minor: String? = null
            while (x.next() != Event.EOF) {
                if (x.event != Event.START || x.ns != Ns.A) continue
                when (x.local) {
                    "clrScheme" -> x.children { c ->
                        if (c.event != Event.START) return@children
                        val name = c.local
                        c.children { v ->
                            if (v.event == Event.START) {
                                when (v.local) {
                                    "srgbClr" -> Units.hexColor(v.attr("val"))?.let { colors[name] = it }
                                    "sysClr" -> Units.hexColor(v.attr("lastClr"))?.let { colors[name] = it }
                                }
                                v.skip()
                            }
                        }
                    }
                    "majorFont" -> x.children { c -> if (c.event == Event.START) { if (c.local == "latin") major = c.attr("typeface"); c.skip() } }
                    "minorFont" -> x.children { c -> if (c.event == Event.START) { if (c.local == "latin") minor = c.attr("typeface"); c.skip() } }
                }
            }
            return Theme(colors, major, minor)
        }
    }

    /** SpreadsheetML's theme colour index: 0 lt1, 1 dk1, 2 lt2, 3 dk2, 4–9 accents, 10 hlink, 11 folHlink. */
    fun byIndex(i: Int): Int? = when (i) {
        0 -> colors["lt1"]; 1 -> colors["dk1"]; 2 -> colors["lt2"]; 3 -> colors["dk2"]
        in 4..9 -> colors["accent${i - 3}"]; 10 -> colors["hlink"]; 11 -> colors["folHlink"]; else -> null
    }
}

/** Colour arithmetic Office uses: tints and shades in HSL, luminance changes, transparency. */
object Colors {
    private fun toHsl(c: Int): FloatArray {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
        val h = when (max) { r -> ((g - b) / d + (if (g < b) 6 else 0)); g -> ((b - r) / d + 2); else -> ((r - g) / d + 4) } / 6
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float, alpha: Int): Int {
        fun hue(p: Float, q: Float, t0: Float): Float {
            var t = t0; if (t < 0) t += 1; if (t > 1) t -= 1
            return when { t < 1f / 6 -> p + (q - p) * 6 * t; t < 0.5f -> q; t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6; else -> p }
        }
        val r: Float; val g: Float; val b: Float
        if (s == 0f) { r = l; g = l; b = l } else {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            r = hue(p, q, h + 1f / 3); g = hue(p, q, h); b = hue(p, q, h - 1f / 3)
        }
        fun ch(v: Float) = (v * 255 + 0.5f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** SpreadsheetML tint (−1 darker … +1 lighter). */
    fun tint(c: Int, tint: Double): Int {
        if (tint == 0.0) return c
        val hsl = toHsl(c)
        val l = if (tint < 0) hsl[2] * (1 + tint.toFloat()) else hsl[2] * (1 - tint.toFloat()) + tint.toFloat()
        return fromHsl(hsl[0], hsl[1], l.coerceIn(0f, 1f), c ushr 24)
    }

    /** DrawingML lumMod / lumOff (fractions). */
    fun lum(c: Int, mod: Float, off: Float): Int {
        val hsl = toHsl(c)
        return fromHsl(hsl[0], hsl[1], (hsl[2] * mod + off).coerceIn(0f, 1f), c ushr 24)
    }

    /** DrawingML shade (towards black) and tint (towards white), as fractions of the colour kept. */
    fun shade(c: Int, f: Float): Int = mix(c, 0xFF000000.toInt(), 1 - f)
    fun lighten(c: Int, f: Float): Int = mix(c, 0xFFFFFFFF.toInt(), 1 - f)

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 255) * (1 - t) + ((b shr sh) and 255) * t + 0.5f).toInt().coerceIn(0, 255)
        return (a and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun alpha(c: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255 + 0.5f).toInt() shl 24) or (c and 0xFFFFFF)
}
