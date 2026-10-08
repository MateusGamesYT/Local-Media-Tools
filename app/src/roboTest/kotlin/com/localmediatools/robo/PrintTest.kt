package com.localmediatools.robo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportManager
import com.localmediatools.export.JobStatus
import com.localmediatools.print.FakePrinter
import com.localmediatools.print.Printers
import com.localmediatools.print.RasterReader
import com.localmediatools.print.SavedPrinter
import com.localmediatools.print.core.FoundPrinter
import com.localmediatools.print.core.Fit
import com.localmediatools.print.core.PrinterAddress
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.Selection
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.gallery.GalleryActions
import com.localmediatools.ui.print.PrintPrefs
import com.localmediatools.ui.print.PrintScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

/**
 * Printing from the app's own screen to a simulated Epson L3250 on the local network, with real
 * photos of people (test resources /people): finding the printer, its state and ink, the paper and
 * layout options, the preview, and the job the printer receives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class PrintTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var printer: FakePrinter

    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 90_000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            idle(50); Thread.sleep(20)
        }
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().map { it.text.toString() }
    /** Clicks the text's nearest clickable ancestor (a row, a chip, a button). */
    private fun click(root: View, text: String) {
        var v: View? = all(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == text } ?: throw AssertionError("no \"$text\" in ${texts(root)}")
        while (v != null && !v.isClickable) v = v.parent as? View
        v!!.performClick()
    }

    private val fixtures = listOf("02e3d0550a82ef46.jpg", "6c8e99aa5fc6d9b1.jpg", "0aed7fafa7ade4c7.jpg")
    private fun photos(): List<MediaItem> {
        val dir = File(app.cacheDir, "photos").apply { mkdirs() }
        return fixtures.map { n ->
            val f = File(dir, n)
            f.writeBytes(PrintTest::class.java.getResourceAsStream("/people/$n")!!.readBytes())
            Robo.item(app, f)
        }
    }

    @Before fun setUp() {
        Robo.resetUiState()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        PrintPrefs.paper = null; PrintPrefs.mediaType = null; PrintPrefs.perSheet = 1; PrintPrefs.fit = Fit.FIT; PrintPrefs.borderless = false
        PrintPrefs.color = true; PrintPrefs.quality = 4; PrintPrefs.copies = 1; PrintPrefs.pages = ""; PrintPrefs.actualSize = false
        printer = FakePrinter()
        Printers.discoverOverride = { listOf(FoundPrinter("EPSON L3250 Series", PrinterAddress.parse(printer.uri), mapOf("ty" to "EPSON L3250 Series", "UUID" to "cfe92100-67c4-11d4-a45f-e0bb9e1a2b3c"))) }
    }

    @After fun tearDown() { printer.close(); Printers.discoverOverride = null }

    private fun open(): Pair<MainActivity, PrintScreen> {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        Selection.of(ToolId.PRINT).add(photos())
        val s = PrintScreen(a)
        a.navigator.push(s); idle(600)
        return a to s
    }

    @Test fun findsThePrinterAndPrintsRealPhotosBorderless() {
        val (a, s) = open()
        click(s.view, "Find printers"); idle()
        val dialog = ShadowDialog.getLatestDialog()!!
        waitFor("printer found") { texts(dialog.window!!.decorView).contains("EPSON L3250 Series") }
        shotIfAsked(dialog.window!!.decorView, "51_print_printers", dialog = true)
        click(dialog.window!!.decorView, "EPSON L3250 Series")
        waitFor("connected") { texts(s.view).any { it == "Ready" } }
        // The printer's own paper sizes and types, and its ink.
        val shown = texts(s.view)
        assertTrue(shown.toString(), shown.containsAll(listOf("A4", "10 × 15 cm (4 × 6 in)", "Glossy photo paper", "Plain paper", "62%", "35%")))
        click(s.view, "10 × 15 cm (4 × 6 in)"); idle()
        click(s.view, "Glossy photo paper"); idle()
        click(s.view, "2 per sheet"); idle()
        click(s.view, "Fill (trims edges)"); idle()
        val borderless = all(s.view).filterIsInstance<ToggleRow>().single { it.title == "Borderless" }
        assertTrue(borderless.switch.isEnabled)
        borderless.performClick(); idle()
        waitFor("preview") { texts(s.view).any { it.startsWith("2 sheets") } }
        shotIfAsked(s.view, "50_print", dialog = false)
        // Photo paper chose the best quality.
        assertEquals(5, PrintPrefs.quality)
        all(s.view).filterIsInstance<ButtonView>().first { it.label == "Print" }.performClick()
        waitFor("printed") { ExportManager.state.value.history.any { it.tool == ToolId.PRINT && it.status.finished } }
        val snap = ExportManager.state.value.history.first { it.tool == ToolId.PRINT }
        assertEquals(snap.summary(), JobStatus.SUCCEEDED, snap.status)
        assertEquals("Printed 2 sheets", snap.summary())
        // What reached the printer.
        val job = printer.jobs.single()
        assertEquals("image/pwg-raster", job.format)
        val col = job.attributes!!["media-col"]!!.collections.single()
        assertEquals(10160, col.collection("media-size")?.int("x-dimension"))
        assertEquals(0, col.int("media-top-margin"))
        assertEquals("photographic-glossy", col.string("media-type"))
        assertEquals(5, job.attributes!!["print-quality"]?.int)
        val pages = RasterReader.read(job.document)
        assertEquals(2, pages.size)
        for (p in pages) { assertEquals(2880, p.width); assertEquals(4320, p.height); assertEquals(720, p.dpi) }
        // Borderless fill: photos right up to the corners (the second sheet has one photo, at the top).
        assertTrue(pages[0].at(2, 2) != -1 && pages[0].at(2877, 4317) != -1 && pages[1].at(2877, 2) != -1)
        assertEquals(-1, pages[1].at(1440, 4317))
        assertEquals("EPSON L3250 Series", Printers.all(app).single().name)
        a.navigator.pop()
    }

    @Test fun aPrinterAddedByAddressShowsItsProblems() {
        printer.reasons = listOf("media-empty-error", "marker-supply-low-warning")
        printer.markerLevels = listOf(8, 48, 35, 80)
        // Typed as an IP address: encryption is tried first, then the printer's plain IPP.
        val p = SavedPrinter.manual("127.0.0.1:${printer.port}")
        assertTrue(p.uri.startsWith("ipps://")); assertTrue(p.fallbackUri!!.startsWith("ipp://"))
        Printers.save(app, p)
        val (_, s) = open()
        waitFor("status") { texts(s.view).any { it == "Add paper" } || texts(s.view).any { it.startsWith("Couldn't") || it.startsWith("Can't") }.also { if (it) throw AssertionError(texts(s.view).toString()) } }
        assertTrue(texts(s.view).contains("8%"))
        assertTrue(texts(s.view).any { it.startsWith("Some ink is low") })
        // Printing is still allowed: it continues once paper is added.
        assertFalse(texts(s.view).any { it == "Choose a printer" })
    }

    @Test fun filesSharedFromOtherAppsOpenPrinting() {
        val pdf = File(app.cacheDir, "outside").apply { mkdirs() }.let { d -> File(d, fixtures[0]).also { it.writeBytes(PrintTest::class.java.getResourceAsStream("/people/${fixtures[0]}")!!.readBytes()) } }
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/jpeg").putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.fromFile(pdf))
        val a = Robolectric.buildActivity(MainActivity::class.java, share).setup().get()
        waitFor("print screen") { a.navigator.top is PrintScreen && Selection.of(ToolId.PRINT).usable.size == 1 }
        // A private copy: printing keeps working after the sharing app takes its permission back.
        assertTrue(Selection.of(ToolId.PRINT).items.single().uri.path!!.contains("/shared/"))
    }

    @Test fun galleryOffersPrintingForPhotosNotVideos() {
        val media = FakeGallery.media()
        assertTrue(ToolId.PRINT in GalleryActions.toolsFor(media.filter { !it.video }.take(3)))
        assertFalse(ToolId.PRINT in GalleryActions.toolsFor(media.filter { it.video }))
    }

    private fun shotIfAsked(root: View, name: String, dialog: Boolean) {
        val dirName = System.getenv("LMT_SHOTS") ?: return
        val dir = File(dirName).apply { mkdirs() }
        val w = app.resources.displayMetrics.widthPixels
        var h = app.resources.displayMetrics.heightPixels
        if (dialog) {
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            h = root.measuredHeight.coerceAtLeast(1)
        } else {
            val content = all(root).filterIsInstance<ScrollView>().firstOrNull()?.getChildAt(0)
            if (content != null) {
                content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                h = maxOf(h, content.measuredHeight)
            }
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF0B0D12.toInt())
        root.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
