package com.localmediatools.robo

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportManager
import com.localmediatools.export.JobStatus
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.HistoryScreen
import com.localmediatools.ui.MainShell
import com.localmediatools.ui.editor.EditorScreen
import com.localmediatools.ui.ResultsScreen
import com.localmediatools.ui.Selection
import com.localmediatools.ui.SelectionReviewScreen
import com.localmediatools.ui.ToolRules
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.WorkloadDialog
import com.localmediatools.ui.PickKind
import com.localmediatools.codec.gif.GifAnimationWriter
import org.junit.After
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration

/** Opens every screen like a user would (Galaxy A55-sized display), lays it out and draws it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class UiSmokeTest {
    private lateinit var app: Application
    private lateinit var outputs: FileOutputs
    private lateinit var inDir: File

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Robo.resetUiState()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        inDir = File(app.cacheDir, "ui-in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "ui-out").apply { deleteRecursively() }).also { it.install() }
    }

    @After fun tearDown() = outputs.uninstall()

    private fun idle(ms: Long = 400) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun render(a: MainActivity) {
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels
        val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        // Also lay out the full scroll content height (everything below the fold).
        all(root).filterIsInstance<android.widget.ScrollView>().forEach { sv ->
            val child = sv.getChildAt(0) ?: return@forEach
            child.measure(View.MeasureSpec.makeMeasureSpec(sv.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertTrue("content has height", child.measuredHeight > 0)
        }
        bmp.recycle()
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun visibleTop(a: MainActivity) = a.navigator.top

    private fun findDesc(a: MainActivity, pred: (String) -> Boolean): View? =
        all(a.navigator.top!!.view).firstOrNull { it.contentDescription?.toString()?.let(pred) == true }

    private fun texts(a: MainActivity) = all(a.navigator.top!!.view).filterIsInstance<TextView>().map { it.text.toString() }

    private fun sampleItems(t: ToolId): List<MediaItem> {
        fun img(n: String, w: Int, h: Int, c: Int) = Robo.item(app, Robo.write(inDir, n,
            Robo.encode(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }, Bitmap.CompressFormat.PNG)))
        return when (ToolRules.pickKind(t)) {
            PickKind.IMAGES -> listOf(img("${t.name}_a.png", 300, 200, Color.RED), img("${t.name}_b.png", 200, 300, Color.BLUE))
            PickKind.GIFS -> {
                val bos = ByteArrayOutputStream()
                GifAnimationWriter(bos, 32, 32, null, 0).apply {
                    addFrame(IntArray(32 * 32) { Color.RED }, 10); addFrame(IntArray(32 * 32) { Color.GREEN }, 10); finish()
                }
                listOf(Robo.item(app, Robo.write(inDir, "${t.name}.gif", bos.toByteArray())))
            }
            PickKind.PDFS -> listOf(Robo.item(app, Robo.write(inDir, "${t.name}_1.pdf", "%PDF-1.4\n%%EOF\n".toByteArray())),
                Robo.item(app, Robo.write(inDir, "${t.name}_2.pdf", "%PDF-1.4\n%%EOF\n".toByteArray())))
            PickKind.MEDIA -> listOf(img("${t.name}_a.png", 120, 80, Color.RED), Robo.item(app, Robo.write(inDir, "${t.name}.mp4", byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(64))))
            else -> listOf(Robo.item(app, Robo.write(inDir, "${t.name}.mp4", byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(64))))
        }
    }

    @Test fun everyToolScreenOpensFromHomeAndRenders() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        assertTrue(visibleTop(a) is MainShell)
        render(a)
        // Every tool card must actually be visible (regression: a lone card in a row collapsed to 0 px).
        for (t in ToolId.entries) {
            val card = findDesc(a) { it.startsWith(t.title + ".") }!!
            assertTrue("${t.title} card is visible (${card.width}×${card.height})", card.height > a.resources.displayMetrics.density * 56 && card.width > 100)
        }
        val editorTools = setOf(ToolId.PHOTO_EDITOR, ToolId.MAGIC_ERASER, ToolId.BLUR_REDACT)
        for (t in ToolId.entries) {
            val card = findDesc(a) { it.startsWith(t.title + ".") }
            assertNotNull("home card for ${t.title}", card)
            card!!.performClick()
            idle()
            if (t in editorTools) {
                // Editing tools ask the system photo picker for one photo; answer it like the picker would.
                val started = shadowOf(a).nextStartedActivityForResult
                assertNotNull("$t starts the photo picker", started)
                val photo = sampleItems(ToolId.COMPRESS_IMAGES).first()
                shadowOf(a).receiveResult(started.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(photo.uri))
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline && ((visibleTop(a) as? EditorScreen)?.renders ?: 0) == 0) { idle(50); Thread.sleep(20) }
                val ed = visibleTop(a) as EditorScreen
                assertTrue("$t editor rendered", ed.renders > 0)
                for (m in com.localmediatools.ui.editor.EditorMode.entries) { ed.setMode(m); idle(300); render(a) }
                @Suppress("DEPRECATION") a.onBackPressed(); idle()
                assertTrue("back to home after $t", visibleTop(a) is MainShell)
                continue
            }
            val top = visibleTop(a)
            assertTrue("$t opened", top is ToolScreen && top.tool == t)
            render(a)
            assertTrue("${t.title}: start disabled without files", texts(a).any { it.startsWith("Select") || it.startsWith("Capture or add") })

            if (t == ToolId.PDF_SCANNER) {
                Selection.of(t).add(sampleItems(ToolId.IMAGES_TO_PDF))
                idle(1500); render(a)
                assertTrue("scanner ready", texts(a).any { "ready" in it })
                Selection.of(t).clear()
            } else {
                Selection.of(t).add(sampleItems(t))
                idle(1500)
                render(a)
                if (t == ToolId.WATERMARK) {
                    assertTrue(texts(a).any { it.startsWith("Add watermark text") })
                    com.localmediatools.ui.tools.WatermarkState.text = "© Villa Real"
                    (visibleTop(a) as ToolScreen).refreshValidation()
                    idle(); render(a)
                    // Step through the per-shape placement screens (2 shapes: 3:2 and 2:3), then the summary.
                    findDesc(a) { it.startsWith("Set placement") }!!.performClick(); idle()
                    var steps = 0
                    while (visibleTop(a) is com.localmediatools.ui.tools.WatermarkPlacementScreen && steps < 10) {
                        render(a)
                        findDesc(a) { it == "Next shape" || it == "Review plan" || it == "Done" }!!.performClick(); idle()
                        steps++
                    }
                    assertTrue("placement steps: $steps", steps == 3)
                    assertTrue(visibleTop(a) is ToolScreen)
                }
                if (t != ToolId.STITCH && t != ToolId.TRIM_VIDEO) assertTrue("${t.title}: ready text ${texts(a).filter { "ready" in it || "Select" in it || "Add" in it }}", texts(a).any { "ready" in it })
                a.navigator.push(SelectionReviewScreen(a, Selection.of(t)))
                idle(); render(a)
                @Suppress("DEPRECATION") a.onBackPressed(); idle()
                Selection.of(t).clear()
            }
            @Suppress("DEPRECATION") a.onBackPressed()
            idle()
            assertTrue("back to home after $t", visibleTop(a) is MainShell)
        }
    }

    @Test fun historyWorkloadAndResultsScreensRender() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        a.navigator.push(HistoryScreen(a)); idle(); render(a)
        @Suppress("DEPRECATION") a.onBackPressed(); idle()
        val shell = visibleTop(a) as MainShell
        for (tab in listOf(1, 2, 0)) { shell.show(tab); idle(); render(a) }
        shell.show(2); idle()
        findDesc(a) { it.startsWith("Open-source licences") }?.performClick() ?: a.navigator.push(com.localmediatools.ui.LicensesScreen(a))
        idle(); render(a)
        assertTrue(texts(a).any { it.contains("MIT License") })
        @Suppress("DEPRECATION") a.onBackPressed(); idle()
        // Back from a secondary tab returns to Tools first.
        @Suppress("DEPRECATION") a.onBackPressed(); idle()
        assertEquals(0, shell.tab)
        WorkloadDialog.show(a); idle()
        assertNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        org.robolectric.shadows.ShadowDialog.getLatestDialog().dismiss()
        a.navigator.push(ResultsScreen(a, 12345L)); idle(); render(a)
    }

    @Test fun exportFromTheUiProducesFilesAndResults() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val t = ToolId.CONVERT_IMAGES
        findDesc(a) { it.startsWith(t.title + ".") }!!.performClick(); idle()
        Selection.of(t).add(sampleItems(t)); idle()
        render(a)
        val start = findDesc(a) { it == "Start export" }
        assertNotNull("start button", start)
        assertTrue(start!!.isEnabled)
        start.performClick()
        // Wait for the background export to finish.
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            idle(100)
            val h = ExportManager.state.value.history.firstOrNull { it.tool == t }
            if (h != null && h.status.finished) break
            Thread.sleep(50)
        }
        val snap = ExportManager.state.value.history.first { it.tool == t }
        assertEquals(snap.results.joinToString { "${it.inputName}: ${it.message}" }, JobStatus.SUCCEEDED, snap.status)
        assertEquals(2, snap.outputs.size)
        assertEquals(2, outputs.committed.size)
        idle(); render(a)
        a.navigator.push(ResultsScreen(a, snap.id)); idle(); render(a)
        assertTrue(texts(a).any { it.contains("2 files saved") || it.contains("saved") })
        Selection.of(t).clear()
    }
}
