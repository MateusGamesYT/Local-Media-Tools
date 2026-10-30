package com.localmediatools.robo

import android.graphics.Paint
import android.graphics.Typeface
import com.localmediatools.ui.reader.DocFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The fonts documents are shown and printed with keep the character widths of the fonts documents
 * name: "Hello" measures what it does in Arial, Times New Roman and Courier New (their published
 * advance widths, in thousandths of the type size: Arial H 722 e 556 l 222 o 556; Times H 722 e 444
 * l 278 o 500; Courier 600 each), and bold and italic come from the families' own bold and italic.
 */
@RunWith(RobolectricTestRunner::class)
class DocFontsTest {
    private fun width(face: Typeface?, text: String, style: Int = Typeface.NORMAL): Float =
        Paint().apply { typeface = Typeface.create(face, style); textSize = 1000f; isSubpixelText = true }.measureText(text)

    @Test fun documentFontsHaveTheWidthsOfTheOnesNamed() {
        val arial = DocFonts.typeface("Arial"); val times = DocFonts.typeface("Times New Roman"); val courier = DocFonts.typeface("Courier New")
        val calibri = DocFonts.typeface("Calibri"); val cambria = DocFonts.typeface("Cambria")
        for ((n, f) in listOf("Arial" to arial, "Times" to times, "Courier" to courier, "Calibri" to calibri, "Cambria" to cambria)) assertNotNull(n, f)
        assertEquals(722f + 556 + 222 + 222 + 556, width(arial, "Hello"), 2f)
        assertEquals(722f + 444 + 278 + 278 + 500, width(times, "Hello"), 2f)
        assertEquals(5 * 600f, width(courier, "Hello"), 2f)
        // Not the phone's own fonts.
        assertTrue(kotlin.math.abs(width(Typeface.DEFAULT, "Hello") - width(arial, "Hello")) > 10f)
        assertTrue(kotlin.math.abs(width(calibri, "Hello") - width(arial, "Hello")) > 50f)
        // Bold has its own widths (Arial Bold: H 722 e 556 l 278 o 611).
        assertEquals(722f + 556 + 278 + 278 + 611, width(arial, "Hello", Typeface.BOLD), 2f)
        // Names are matched loosely, symbol fonts keep the phone's own.
        assertNotNull(DocFonts.typeface("Helvetica Neue")); assertNotNull(DocFonts.typeface("Calibri Light")); assertNotNull(DocFonts.typeface("Liberation Serif"))
        assertNull(DocFonts.typeface("Symbol")); assertNull(DocFonts.typeface("Wingdings")); assertNull(DocFonts.typeface(null))
    }
}
