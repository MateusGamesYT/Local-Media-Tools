package com.localmediatools.docs

import com.localmediatools.docs.core.Deck
import com.localmediatools.docs.core.DocFormat
import com.localmediatools.docs.core.DocFormats
import com.localmediatools.docs.core.FlowDocument
import com.localmediatools.docs.core.Opened
import com.localmediatools.docs.core.Paragraph
import com.localmediatools.docs.core.Picture
import com.localmediatools.docs.core.UnreadableException
import com.localmediatools.docs.core.Workbook
import com.localmediatools.docs.core.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The readers on real documents (test resources docs/, see CREDITS.md): a NASA report, a USDA table
 * and an EPA summary (public domain) in their original Office 97–2003 files and re-saved by
 * LibreOffice in the newer formats, and Apache POI's samples made by Microsoft Office. The same
 * content read from each format must agree. The measurements on about 2,000 files are in
 * buildtools/docs (compare.py against Apache Tika, cells.py against LibreOffice).
 */
class DocReadersTest {
    private fun res(name: String) = File(DocReadersTest::class.java.getResource("/docs/$name")!!.toURI())

    private fun open(name: String): Opened {
        val f = res(name)
        val fmt = DocFormats.detect(f, f.name) ?: error("$name not recognised")
        return DocFormats.open(f, fmt, Locale.US)
    }

    private fun words(t: String) = t.split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun isImage(b: ByteArray?) = b != null && b.size > 8 &&
        ((b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) || (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) || (b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()))

    @Test fun formatsComeFromContentsNotNames() {
        val tmp = File.createTempFile("renamed", ".bin").apply { deleteOnExit() }
        for ((name, format) in listOf("boreas.docx" to DocFormat.DOCX, "boreas.doc" to DocFormat.DOC, "sugar.xls" to DocFormat.XLS, "sugar.xlsx" to DocFormat.XLSX,
            "boreas.odt" to DocFormat.ODT, "sugar.ods" to DocFormat.ODS, "SampleShow.odp" to DocFormat.ODP, "SampleShow.ppt" to DocFormat.PPT,
            "SampleShow.pptx" to DocFormat.PPTX, "boreas.rtf" to DocFormat.RTF, "boreas.epub" to DocFormat.EPUB, "glnpo.pdf" to DocFormat.PDF)) {
            res(name).copyTo(tmp, overwrite = true)
            assertEquals(name, format, DocFormats.detect(tmp, "attachment.bin"))
        }
        assertEquals(DocFormat.CSV, DocFormats.detect(res("sugar.csv"), "sugar.csv"))
        tmp.writeBytes(ByteArray(4096) { (it * 37 % 251).toByte() })
        assertNull("random bytes are no document", DocFormats.detect(tmp, "x.doc"))
    }

    @Test fun theSameReportReadsAlikeInEveryFormat() {
        val counts = HashMap<String, Int>()
        for (name in listOf("boreas.doc", "boreas.docx", "boreas.odt", "boreas.rtf", "boreas.epub")) {
            val d = open(name) as FlowDocument
            val text = d.plainText()
            assertTrue(name, text.contains("BOREAS HYD-04 Areal Snow Course Data"))
            assertTrue(name, text.contains("Knowledge of snow cover and its variability in the boreal forest is fundamental"))
            counts[name] = words(text)
            // The report's two figures, as pictures the phone can draw.
            val pics = d.blocks.filterIsInstance<Picture>()
            assertEquals("$name pictures", 2, pics.size)
            for (p in pics) assertTrue("$name ${p.ref} is a PNG, JPEG or BMP", isImage(d.media.bytes(p.ref)))
            d.close()
        }
        val ref = counts.getValue("boreas.doc")
        for ((name, n) in counts) assertTrue("$name: $n words vs $ref in the .doc", Math.abs(n - ref) <= ref / 100)
    }

    @Test fun wordFormattingIsKept() {
        val d = open("SampleDoc.docx") as FlowDocument
        assertEquals("Test Document", d.title)
        val spans = d.blocks.filterIsInstance<Paragraph>().flatMap { it.spans }
        val black = spans.first { it.text.contains("Arial Black in 16 point") }
        assertEquals(16f, black.style.sizePt)
        val blue = spans.first { it.text.contains("also in blue") }
        val c = blue.style.color
        assertTrue("blue text: %08x".format(c), (c and 0xFF) > 0x80 && (c shr 16 and 0xFF) < 0x60)
    }

    @Test fun theSameTableReadsAlikeInEveryFormat() {
        fun cells(name: String): Map<Pair<Int, Int>, String> {
            val w = open(name) as Workbook
            val s = w.sheets[0]
            val out = HashMap<Pair<Int, Int>, String>()
            for (r in s.cells.rowIndices()) for ((c, cell) in s.cells.row(r)!!) if (cell.text.isNotEmpty()) out[r to c] = cell.text
            return out
        }
        val xls = cells("sugar.xls")
        assertEquals("Table 26--Monthly estimates of fiscal 2009 U.S. sugar supply and use", xls[0 to 0])
        assertEquals("8,115", xls[7 to 1])
        assertEquals(278, xls.size)
        for (other in listOf("sugar.xlsx", "sugar.ods", "sugar.csv")) {
            val o = cells(other)
            val differ = (xls.keys + o.keys).filter { xls[it]?.trim() != o[it]?.trim() }
            assertTrue("$other differs from the .xls in ${differ.size} cells: ${differ.take(5).map { "$it ${xls[it]} / ${o[it]}" }}", differ.isEmpty())
        }
    }

    @Test fun formulasAreShownAsExcelWritesThem() {
        for (name in listOf("sugar.xls", "sugar.xlsx", "sugar.ods")) {
            val s = (open(name) as Workbook).sheets[0]
            assertEquals(name, "=B9+B10", s.cells[7, 1]?.formula)
            assertEquals(name, "=B11+B12+B13+B14+B15", s.cells[9, 1]?.formula)
            assertEquals(name, "8,115", s.cells[7, 1]?.text)
        }
    }

    @Test fun cellStylesSheetsAndNames() {
        val w = open("SampleSS.xlsx") as Workbook
        assertEquals(listOf("First Sheet", "Sheet Number 2", "Sheet3"), w.sheets.map { it.name })
        val s = w.sheets[1]
        var found = false
        for (r in s.cells.rowIndices()) for ((_, cell) in s.cells.row(r)!!) if (cell.text.startsWith("I'm in bold blue, on a yellow background")) {
            val st = w.style(cell.style)
            assertTrue("bold", st.bold)
            assertTrue("yellow fill %08x".format(st.fill), (st.fill shr 16 and 0xFF) > 0xC0 && (st.fill shr 8 and 0xFF) > 0xC0 && (st.fill and 0xFF) < 0x80)
            found = true
        }
        assertTrue(found)
    }

    @Test fun slidesInEveryFormat() {
        for (name in listOf("SampleShow.pptx", "SampleShow.ppt", "SampleShow.odp")) {
            val d = open(name) as Deck
            assertEquals(name, 2, d.slides.size)
            assertEquals(name, 720f, d.widthPt); assertEquals(name, 540f, d.heightPt)
            val first = d.slides[0].shapes.mapNotNull { it.text }.flatMap { it.paragraphs }.joinToString("\n") { it.text }
            assertTrue("$name: $first", first.contains("Title of the first slide") && first.contains("Subtitle of the first slide"))
            assertTrue(name, d.slides[0].notes.any { it.text.contains("I am the notes of the first slide") })
            val second = d.slides[1].shapes.mapNotNull { it.text }.flatMap { it.paragraphs }
            assertTrue(name, second.any { it.text.contains("This is the second slide") })
            assertTrue("$name: bullets", second.count { it.listLabel != null } >= 2)
            // Placeholder stand-ins never show as text.
            assertTrue(name, d.plainText().lines().none { it.trim() == "<number>" || it.trim() == "<date/time>" })
            d.close()
        }
    }

    @Test fun lockedFilesSaySo() {
        for (name in listOf("password_tika_binaryrc4.doc", "protected_passtika.xlsx")) {
            try {
                open(name)
                fail("$name opened")
            } catch (e: UnreadableException) {
                assertTrue("$name: ${e.message}", e.message!!.contains("password"))
            }
        }
    }

    @Test fun damagedFilesStopWithAMessage() {
        val tmp = File.createTempFile("cut", ".docx").apply { deleteOnExit() }
        for (name in listOf("boreas.docx", "boreas.doc", "sugar.xls", "sugar.xlsx", "SampleShow.pptx", "SampleShow.ppt", "boreas.odt", "boreas.epub")) {
            val bytes = res(name).readBytes()
            // Cut in half: either something is still shown or the reader says the file is damaged.
            tmp.writeBytes(bytes.copyOf(bytes.size / 2))
            try {
                val fmt = DocFormats.detect(tmp, name) ?: continue
                DocFormats.open(tmp, fmt, Locale.US).close()
            } catch (e: UnreadableException) {
                assertNotNull(e.message)
            } catch (e: Exception) {
                fail("$name cut in half: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }
}
