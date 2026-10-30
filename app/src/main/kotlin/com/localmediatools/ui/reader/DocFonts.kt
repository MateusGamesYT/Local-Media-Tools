package com.localmediatools.ui.reader

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import com.localmediatools.app.LmtApp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * The fonts documents are shown and printed with: free fonts with the same character widths as the
 * ones Office documents name — Carlito for Calibri, Caladea for Cambria, Liberation Sans, Serif and
 * Mono for Arial, Times New Roman and Courier New — so lines and pages break where they do on a
 * computer. Other fonts get the nearest of these by kind (serif, fixed-width, sans-serif); symbol and
 * unknown fonts keep the phone's own. Letters these fonts lack (Chinese, say) come from the phone's.
 */
object DocFonts {
    private enum class Face(val file: String, val fallback: String) {
        CARLITO("Carlito", "sans-serif"), CALADEA("Caladea", "serif"),
        SANS("LiberationSans", "sans-serif"), SERIF("LiberationSerif", "serif"), MONO("LiberationMono", "monospace"),
    }

    private val loaded = HashMap<Face, Typeface?>()

    private val MONO = Regex("courier|consolas|mono|menlo|lucida console|andale|fixedsys|source code|cousine", RegexOption.IGNORE_CASE)
    private val SERIF = Regex("times|georgia|garamond|book|serif|palatino|baskerville|minion|constantia|century schoolbook|didot|bodoni|caslon|tinos|bookman|cochin|perpetua|rockwell", RegexOption.IGNORE_CASE)
    private val SANS = Regex("sans|arial|helvetica|arimo|verdana|tahoma|segoe|aptos|trebuchet|century gothic|franklin|gill|futura|roboto|open sans|lato|corbel|candara|myriad|frutiger|univers|avenir|gotham|montserrat|source sans|inter\\b", RegexOption.IGNORE_CASE)

    /** The typeface for a document's font name; null when the name says nothing usable (the phone's default then). */
    fun typeface(font: String?): Typeface? {
        val f = face(font ?: return null) ?: return null
        return synchronized(loaded) { if (f in loaded) loaded[f] else load(f).also { loaded[f] = it } }
    }

    /** Whether [font] is shown with one of these fonts (for tests). */
    fun known(font: String?): Boolean = font != null && face(font) != null

    private fun face(name: String): Face? {
        val n = name.lowercase(Locale.ROOT).trim()
        return when {
            n.isEmpty() || n.contains("symbol") || n.contains("wingding") || n.contains("webding") || n.contains("dingbat") -> null
            // Calibri Light has nearly Calibri's widths.
            n.startsWith("calibri") || n.startsWith("carlito") -> Face.CARLITO
            n.startsWith("cambria") || n.startsWith("caladea") -> Face.CALADEA
            MONO.containsMatchIn(n) -> Face.MONO
            n.contains("arial") || n.contains("helvetica") || n.contains("liberation sans") || n.contains("arimo") -> Face.SANS
            n.contains("times") || n.contains("liberation serif") || n.contains("tinos") -> Face.SERIF
            SERIF.containsMatchIn(n) && !n.contains("sans") -> Face.SERIF
            SANS.containsMatchIn(n) -> Face.SANS
            else -> null
        }
    }

    private fun load(f: Face): Typeface? = try {
        val assets = LmtApp.instance.assets
        fun font(style: String, weight: Int, italic: Boolean): Font {
            val bytes = assets.open("fonts/docs/${f.file}-$style.ttf").use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes); buf.flip()
            return Font.Builder(buf).setWeight(weight).setSlant(if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT).build()
        }
        val family = FontFamily.Builder(font("Regular", 400, false))
            .addFont(font("Bold", 700, false)).addFont(font("Italic", 400, true)).addFont(font("BoldItalic", 700, true)).build()
        Typeface.CustomFallbackBuilder(family).setSystemFallback(f.fallback).build()
    } catch (_: Throwable) { null }
}
