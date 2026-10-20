package com.localmediatools.docs

import com.localmediatools.docs.core.NumberFormatter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Excel number formats as Excel shows them. Most cases come from differences found by checking 2.9
 * million real cells against LibreOffice (buildtools/docs/cells.py); each stands for a bug fixed.
 */
class NumberFormatTest {
    private val us = NumberFormatter(Locale.US)
    private fun f(v: Double, code: String) = us.format(v, code).text

    @Test fun roundingLikeExcel() {
        // 30.395 is stored as 30.39499999…; Excel takes 15 significant digits first.
        assertEquals("30.40", f(30.395, "0.00"))
        assertEquals("2.8124", f(2.81235, "0.0000"))
        assertEquals("1,234,567.89", f(1234567.891, "#,##0.00"))
        assertEquals("26%", f(0.256, "0%"))
        assertEquals("1.23E+04", f(12345.0, "0.00E+00"))
    }

    @Test fun general() {
        assertEquals("11111111111", f(11111111111.0, "General"))
        assertEquals("3.141592654", f(Math.PI, "General"))
        assertEquals("0.3", f(0.1 + 0.2, "General"))
        assertEquals("1E+12", f(1e12, "General"))
        assertEquals("-1.5", f(-1.5, "General"))
        // General inside a longer code, with codes this app can't apply ([DBNum1]) ignored.
        assertEquals("12323", f(12323.0, "[DBNum1][\$-804]General"))
        assertEquals("12 kg", f(12.0, "General\" kg\""))
    }

    @Test fun sectionsColoursAndText() {
        val neg = us.format(-1.5, "[Blue]0.00;[Red](0.00)")
        assertEquals("(1.50)", neg.text)
        assertEquals(0xFFFF0000.toInt(), neg.color)
        assertEquals("1.50", f(1.5, "[Blue]0.00;[Red](0.00)"))
        assertEquals("xabc", us.formatText("abc", "0;0;0;\"x\"@").text)
        val accounting = "_(* #,##0.00_);_(* (#,##0.00);_(* \"-\"??_);_(@_)"
        assertEquals(" 1,234.50 ", f(1234.5, accounting))
        assertEquals(" -   ", f(0.0, accounting))
        assertEquals(" (2.00)", f(-2.0, accounting))
    }

    @Test fun datesAndTimes() {
        assertEquals("07/04/2008", f(39545.0, "dd/mm/yyyy"))
        assertEquals("April 7, 2008", f(39545.0, "mmmm d, yyyy"))
        assertEquals("36:00", f(1.5, "[h]:mm"))
        assertEquals("6:00 PM", f(0.75, "h:mm AM/PM"))
        // The system's long date code once showed the date twice (its fallback pattern too).
        assertEquals("Sunday, March 21, 2010", f(40258.0, "[\$-F800]dddd\\,\\ mmmm\\ dd\\,\\ yyyy"))
        assertEquals("7:55:12 AM", f(0.33, "[\$-F400]h:mm:ss\\ AM/PM"))
    }

    @Test fun fractions() {
        assertEquals("1 1/4", f(1.25, "# ?/?"))
        assertEquals("3/8", f(0.375, "?/?"))
        assertEquals("2 1/3", f(2.3333, "# ?/?"))
    }

    @Test fun separatorsFollowTheLanguage() {
        val br = NumberFormatter(Locale.forLanguageTag("pt-BR"))
        assertEquals("1.234,50", br.format(1234.5, "#,##0.00").text)
        val de = NumberFormatter(Locale.GERMANY)
        assertEquals("März", de.format(40258.0, "mmmm").text)
    }
}
