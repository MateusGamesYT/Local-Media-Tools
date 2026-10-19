package com.localmediatools.ui.reader

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View
import com.localmediatools.docs.core.Paragraph
import com.localmediatools.docs.core.RunStyle
import java.util.Locale

/** A tapped link; the screen decides what happens (the app itself never opens network addresses). */
class LinkSpan(val target: String, private val color: Int, private val onTap: (String) -> Unit) : ClickableSpan() {
    override fun onClick(widget: View) = onTap(target)
    override fun updateDrawState(ds: TextPaint) {
        if (color != 0) ds.color = color
        ds.isUnderlineText = true
    }
}

/** Search matches drawn behind the text. */
class MatchSpan(color: Int) : BackgroundColorSpan(color)

/** A typeface by family, for runs set in serif or fixed-width fonts. */
private class FamilySpan(private val face: Typeface) : MetricAffectingSpan() {
    private fun apply(p: TextPaint) {
        val old = p.typeface?.style ?: 0
        p.typeface = Typeface.create(face, old)
    }
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
}

/** Turns the readers' styled text into Android text with spans. */
object RichText {
    const val MATCH = 0xFFFFE066.toInt()
    const val CURRENT = 0xFFFF9F43.toInt()

    private val SERIF = Regex("times|georgia|cambria|garamond|book|serif|palatino|baskerville|minion|constantia|century|didot|bodoni|caslon|liberation serif|dejavu serif|noto serif", RegexOption.IGNORE_CASE)
    private val MONO = Regex("courier|consolas|mono|menlo|lucida console|andale|fixedsys|source code", RegexOption.IGNORE_CASE)

    fun family(font: String?): Typeface? = when {
        font == null -> null
        MONO.containsMatchIn(font) -> Typeface.MONOSPACE
        SERIF.containsMatchIn(font) && !font.contains("sans", true) -> Typeface.SERIF
        else -> null
    }

    /**
     * [p]'s text with its styles. Sizes given in the file become [pxPerPt] × size; others keep the
     * view's size. Colours too close to [background] (white text written for a dark page) become
     * [defaultColor], so nothing turns invisible; 0 keeps every colour.
     */
    fun paragraph(p: Paragraph, pxPerPt: Float, defaultColor: Int, background: Int, linkColor: Int, onLink: ((String) -> Unit)?, scale: Float = 1f): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (s in p.spans) {
            if (s.text.isEmpty()) continue
            val start = sb.length
            sb.append(s.text)
            style(sb, start, sb.length, s.style, pxPerPt, defaultColor, background, scale)
            if (s.link != null && onLink != null && !s.link.startsWith("#")) sb.setSpan(LinkSpan(s.link, if (s.style.color == 0) linkColor else 0, onLink), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else if (s.link != null && s.style.color == 0 && !s.link.startsWith("#")) sb.setSpan(ForegroundColorSpan(linkColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    fun style(sb: SpannableStringBuilder, start: Int, end: Int, st: RunStyle, pxPerPt: Float, defaultColor: Int, background: Int, scale: Float = 1f) {
        if (start >= end) return
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        when {
            st.bold && st.italic -> sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, flags)
            st.bold -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
            st.italic -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
        }
        if (st.underline) sb.setSpan(UnderlineSpan(), start, end, flags)
        if (st.strike) sb.setSpan(StrikethroughSpan(), start, end, flags)
        if (st.sizePt > 0f) sb.setSpan(AbsoluteSizeSpan((st.sizePt * pxPerPt * scale * (if (st.vertical != 0) 0.7f else 1f)).toInt().coerceAtLeast(1)), start, end, flags)
        else if (st.vertical != 0) sb.setSpan(android.text.style.RelativeSizeSpan(0.7f), start, end, flags)
        if (st.vertical > 0) sb.setSpan(SuperscriptSpan(), start, end, flags)
        if (st.vertical < 0) sb.setSpan(SubscriptSpan(), start, end, flags)
        val bg = if (st.highlight != 0) st.highlight else background
        if (st.color != 0) {
            val c = if (background != 0 && contrast(st.color, bg) < 1.5) defaultColor else st.color
            if (c != defaultColor) sb.setSpan(ForegroundColorSpan(c or 0xFF000000.toInt()), start, end, flags)
        }
        if (st.highlight != 0) sb.setSpan(BackgroundColorSpan(st.highlight or 0xFF000000.toInt()), start, end, flags)
        val face = if (st.monospace) Typeface.MONOSPACE else family(st.font)
        if (face != null) sb.setSpan(FamilySpan(face), start, end, flags)
    }

    /** WCAG contrast ratio of two colours (1 = the same, 21 = black on white). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    /** Where [query] occurs in [text], ignoring case. */
    fun find(text: CharSequence, query: String, locale: Locale): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val hay = text.toString().lowercase(locale)
        val q = query.lowercase(locale)
        // Lower-casing can change lengths (rare); then positions are looked up in the original text.
        if (hay.length != text.length) {
            return Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).map { it.range }.toList()
        }
        val out = ArrayList<IntRange>()
        var i = hay.indexOf(q)
        while (i >= 0 && out.size < 100_000) { out.add(i until i + q.length); i = hay.indexOf(q, i + q.length) }
        return out
    }

    /** Marks [ranges] in [sb]; [current] (an index into ranges) in a stronger colour. */
    fun mark(sb: android.text.Spannable, ranges: List<IntRange>, current: Int) {
        for (old in sb.getSpans(0, sb.length, MatchSpan::class.java)) sb.removeSpan(old)
        for ((k, r) in ranges.withIndex()) {
            val s = r.first.coerceIn(0, sb.length); val e = (r.last + 1).coerceIn(s, sb.length)
            if (e > s) sb.setSpan(MatchSpan(if (k == current) CURRENT else MATCH), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
