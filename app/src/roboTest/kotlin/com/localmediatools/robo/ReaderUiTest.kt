package com.localmediatools.robo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.MainShell
import com.localmediatools.ui.ToolLauncher
import com.localmediatools.ui.reader.DeckPages
import com.localmediatools.ui.reader.FlowView
import com.localmediatools.ui.reader.PagesView
import com.localmediatools.ui.reader.ReaderHomeScreen
import com.localmediatools.ui.reader.ReaderScreen
import com.localmediatools.ui.reader.ReaderSource
import com.localmediatools.ui.reader.RecentFiles
import com.localmediatools.ui.reader.SheetView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * The file reader end to end on real documents (test resources docs/, see CREDITS.md): opening from
 * the tool, from the file picker and from another app's "Open with"; text, spreadsheet, slide and
 * PDF views; search, the formula bar, speaker notes, recent files and locked files. With $LMT_SHOTS
 * set, each screen is also saved as a picture to look at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class ReaderUiTest {
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(a: MainActivity) = all(a.navigator.top!!.view).filterIsInstance<TextView>().map { it.text.toString() }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!cond() && System.currentTimeMillis() < deadline) { idle(50); Thread.sleep(15) }
        assertTrue("timed out waiting for $what", cond())
    }

    private fun render(a: MainActivity, name: String) {
        val root = a.navigator.top!!.view
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        idle(100)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        System.getenv("LMT_SHOTS")?.let { d ->
            val dir = File(d).apply { mkdirs() }
            File(dir, "reader_$name.png").outputStream().use { Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun fixture(name: String): File {
        val src = File(javaClass.getResource("/docs/$name")!!.toURI())
        // Like a file another app hands over: a copy outside the reader's own folders.
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "incoming").apply { mkdirs() }
        return File(dir, name).also { src.copyTo(it, overwrite = true) }
    }

    private fun activity(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }

    private fun open(a: MainActivity, name: String): ReaderScreen {
        val s = ReaderScreen(a, ReaderSource(Uri.fromFile(fixture(name)), name))
        a.navigator.push(s)
        waitFor("$name to open") { s.state != "opening" }
        idle(300)
        return s
    }

    @Before fun clean() {
        Robo.resetUiState()
        RecentFiles.clear(RuntimeEnvironment.getApplication())
    }

    @Test fun theToolOpensAFilePickedWithTheSystemPicker() {
        val a = activity()
        ToolLauncher.open(a, ToolId.READER)
        idle()
        assertTrue(a.navigator.top is ReaderHomeScreen)
        // The list of recent files is read in the background; the empty-list line comes when it is done.
        waitFor("the empty list of recent files") { texts(a).contains("Files you open show up here.") }
        render(a, "0_home_empty")
        assertTrue(texts(a).contains("Open a file"))
        all(a.navigator.top!!.view).first { it is TextView && it.text == "Open a file" }.let { (it.parent as View).performClick() }
        idle()
        val started = shadowOf(a).nextStartedActivityForResult
        assertNotNull("the system file picker is asked", started)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        shadowOf(a).receiveResult(started.intent, android.app.Activity.RESULT_OK, Intent().setData(Uri.fromFile(fixture("boreas.docx"))))
        waitFor("the reader") { (a.navigator.top as? ReaderScreen)?.state == "shown" }
        a.onBackPressed(); idle()
        assertTrue(a.navigator.top is ReaderHomeScreen)
        a.navigator.top!!.onShow()
        // The list is read off the main thread.
        waitFor("recent file listed") { texts(a).any { it == "boreas.docx" } }
        assertTrue(texts(a).joinToString("|"), texts(a).any { it.endsWith(" · Just now") })
        render(a, "0_home_recent")
    }

    @Test fun wordDocumentReadsSearchesAndShowsPictures() {
        val a = activity()
        val s = open(a, "boreas.docx")
        assertEquals("shown", s.state)
        val flow = s.content as FlowView
        render(a, "1_docx")
        val shown = all(flow).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(shown.contains("BOREAS HYD-04 Areal Snow Course Data"))
        s.searchNow("snow")
        waitFor("search") { s.searchStatus.toString().matches(Regex("1 of \\d+")) }
        val total = s.searchStatus.toString().substringAfter("of ").trim().toInt()
        assertTrue("snow found $total times", total >= 20)
        s.content!!.searchStep(true); idle()
        assertTrue(s.searchStatus.toString().startsWith("2 of"))
        render(a, "1_docx_search")
        // Zooming changes the text size and keeps the document.
        flow.zoomBy(1.5f); idle()
        assertTrue(flow.zoom > 1.4f)
    }

    @Test fun spreadsheetShowsFormulasAndFindsCells() {
        val a = activity()
        val s = open(a, "sugar.xlsx")
        val sheet = s.content as SheetView
        sheet.selectCell(7, 1); idle()
        assertEquals("B8", sheet.selectedRef.toString())
        assertEquals("=B9+B10", sheet.selectedText.toString())
        render(a, "2_xlsx_formula")
        sheet.selectCell(0, 0); idle()
        assertEquals("Table 26--Monthly estimates of fiscal 2009 U.S. sugar supply and use", sheet.selectedText.toString())
        render(a, "2_xlsx")
        s.searchNow("Beet sugar")
        waitFor("search") { s.searchStatus.toString().startsWith("1 of") }
        assertEquals("A9", sheet.selectedRef.toString())
        // Old Excel files show the same formulas.
        a.onBackPressed(); idle()
        val x = open(a, "sugar.xls")
        (x.content as SheetView).selectCell(9, 1); idle()
        assertEquals("=B11+B12+B13+B14+B15", (x.content as SheetView).selectedText.toString())
    }

    @Test fun slidesAreDrawnWithTheirNotes() {
        val a = activity()
        for (name in listOf("SampleShow.pptx", "SampleShow.ppt", "SampleShow.odp")) {
            val s = open(a, name)
            val pages = s.content as PagesView
            waitFor("$name slide 1 drawn") { render(a, "3_$name"); pages.isDrawn(0) }
            val notes = pages.contents()
            assertEquals(name, 2, notes.size)
            assertTrue("$name: ${notes[0].first}", notes[0].first.contains("Title of the first slide"))
            s.searchNow("slide")
            waitFor("$name search") { s.searchStatus.toString().matches(Regex("\\d+ of \\d+")) }
            a.onBackPressed(); idle()
        }
        val d = DeckPages(com.localmediatools.docs.core.DocFormats.open(fixture("SampleShow.pptx"), com.localmediatools.docs.core.DocFormat.PPTX) as com.localmediatools.docs.core.Deck)
        assertTrue(d.notes(0).contains("I am the notes of the first slide"))
        d.close()
    }

    @Test fun pdfPagesAreDrawnAndSearched() {
        val a = activity()
        val s = open(a, "glnpo.pdf")
        assertEquals("shown", s.state)
        val pages = s.content as PagesView
        waitFor("page 1 drawn") { render(a, "4_pdf"); pages.isDrawn(0) }
        s.searchNow("pesticides")
        waitFor("search") { s.searchStatus.toString().matches(Regex("\\d+ of \\d+")) }
        assertTrue(s.searchStatus.toString(), s.searchStatus.toString().startsWith("1 of"))
        // On to page 2 (page 1 starts with the author's address and phone, not for the README picture).
        repeat(8) { s.content!!.searchStep(true); idle() }
        assertTrue(s.searchStatus.toString(), s.searchStatus.toString().startsWith("9 of"))
        // Pages are asked for as they are drawn on screen.
        waitFor("pages 2 and 3 drawn") { render(a, "4_pdf_search"); pages.isDrawn(1) && pages.isDrawn(2) }
        render(a, "4_pdf_search")
    }

    @Test fun otherAppsOpenDocumentsWithOpenWith() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get(); idle()
        val f = fixture("sugar.csv")
        controller.newIntent(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), "text/csv"))
        waitFor("the reader") { (a.navigator.top as? ReaderScreen)?.state == "shown" }
        val s = a.navigator.top as ReaderScreen
        assertTrue(s.content is SheetView)
        // A file another app lent is kept for the recent list (it can't be read from there again).
        val recent = RecentFiles.list(a).first()
        assertEquals("sugar.csv", recent.name)
        assertNotNull(recent.kept)
        assertTrue(File(recent.kept!!).exists())
        a.onBackPressed(); idle()
        assertTrue(a.navigator.top is MainShell)
    }

    /** Draws any files listed in $LMT_READER_FILES (separated by ':') into $LMT_SHOTS, to look at; skipped otherwise. */
    @Test fun drawFilesToLookAt() {
        val files = System.getenv("LMT_READER_FILES") ?: return
        val a = activity()
        for ((k, path) in files.split(':').filter { it.isNotBlank() }.withIndex()) {
            val f = File(path)
            val s = ReaderScreen(a, ReaderSource(Uri.fromFile(f), f.name))
            a.navigator.push(s)
            waitFor(f.name) { s.state != "opening" }
            val pages = s.content as? PagesView
            if (pages != null) waitFor("${f.name} drawn") { render(a, "look_${k}_${f.nameWithoutExtension}"); pages.isDrawn(0) }
            idle(500); render(a, "look_${k}_${f.nameWithoutExtension}")
            a.onBackPressed(); idle()
        }
    }

    @Test fun lockedAndUnknownFilesSaySo() {
        val a = activity()
        val s = open(a, "password_tika_binaryrc4.doc")
        assertEquals("failed", s.state)
        assertTrue(texts(a).joinToString("|"), texts(a).any { it.contains("password") })
        render(a, "5_locked")
        a.onBackPressed(); idle()
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "incoming")
        val junk = File(dir, "photo.xyz").apply { writeBytes(ByteArray(2048) { (it * 31 % 253).toByte() }) }
        val u = ReaderScreen(a, ReaderSource(Uri.fromFile(junk), junk.name))
        a.navigator.push(u)
        waitFor("unknown file") { u.state != "opening" }
        assertEquals("failed", u.state)
    }
}
