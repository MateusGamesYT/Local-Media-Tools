package com.localmediatools.robo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.localmediatools.app.MainActivity
import com.localmediatools.codec.edit.AdjustKey
import com.localmediatools.codec.edit.Adjustments
import com.localmediatools.codec.edit.ColorPipeline
import com.localmediatools.codec.edit.CropRect
import com.localmediatools.codec.edit.FilterPreset
import com.localmediatools.codec.edit.FilterSpec
import com.localmediatools.codec.edit.Geometry
import com.localmediatools.core.MediaItem
import com.localmediatools.edit.EditState
import com.localmediatools.edit.Inpainter
import com.localmediatools.edit.Inpainters
import com.localmediatools.edit.PatchKind
import com.localmediatools.edit.PatchStore
import com.localmediatools.edit.Retouch
import com.localmediatools.edit.RetouchedSource
import com.localmediatools.edit.Stroke
import com.localmediatools.export.ExportManager
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.JobStatus
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.ImageSource
import com.localmediatools.tools.EditPhotoJob
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.editor.EditorCanvas
import com.localmediatools.ui.editor.EditorMode
import com.localmediatools.ui.editor.EditorScreen
import org.junit.After
import org.junit.Assert.assertEquals
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

/** Fills holes with magenta so tests can see exactly where the eraser acted. */
class FillInpainter : Inpainter {
    override val label = "test fill"
    override val isAi = false
    var calls = 0
    override fun inpaint(image: IntArray, hole: BooleanArray): IntArray { calls++; return IntArray(image.size) { if (hole[it]) 0xFFFF00FF.toInt() else image[it] } }
    override fun close() {}
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class EditorTest {
    private lateinit var app: Application
    private lateinit var inDir: File
    private lateinit var outputs: FileOutputs
    private val fill = FillInpainter()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Robo.resetUiState()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        inDir = File(app.cacheDir, "ed-in").apply { deleteRecursively(); mkdirs() }
        outputs = FileOutputs(File(app.cacheDir, "ed-out").apply { deleteRecursively() }).also { it.install() }
        Inpainters.factory = { fill }
    }

    @After fun tearDown() { outputs.uninstall(); Inpainters.factory = null }

    private val q = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW) // TL, TR, BL, BR

    private fun quadrants(w: Int, h: Int, name: String = "q.png"): MediaItem {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { i -> q[(if (i / w < h / 2) 0 else 2) + (if (i % w < w / 2) 0 else 1)] }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return Robo.item(app, Robo.write(inDir, name, Robo.encode(b, Bitmap.CompressFormat.PNG)))
    }

    private fun save(item: MediaItem, state: EditState, store: PatchStore, fmt: ImageOutFormat = ImageOutFormat.PNG): Bitmap {
        val r = Robo.runJob(app, EditPhotoJob(ToolId.PHOTO_EDITOR, item, state, store, fmt, 95, false)).single()
        assertEquals(r.message, ItemOutcome.SUCCESS, r.outcome)
        assertTrue("retouch files cleaned up", !store.dir.exists())
        return Robo.decode(File(r.outputs.single().uri.path!!))
    }

    private fun assertColor(b: Bitmap, x: Int, y: Int, c: Int, tol: Int = 24) =
        assertTrue("($x,$y) expected ${Integer.toHexString(c)} got ${Integer.toHexString(b.getPixel(x, y))}", Robo.diff(b.getPixel(x, y), c) <= tol)

    @Test fun rotationFlipAndCropAreAppliedAtFullResolution() {
        val item = quadrants(400, 300)
        // One clockwise turn: the left edge becomes the top.
        var out = save(item, EditState(geometry = Geometry(quarterTurns = 1)), PatchStore.newSession(app))
        assertEquals(300, out.width); assertEquals(400, out.height)
        assertColor(out, 40, 40, Color.BLUE); assertColor(out, 260, 40, Color.RED)
        assertColor(out, 40, 360, Color.YELLOW); assertColor(out, 260, 360, Color.GREEN)
        // Flip + crop to the right half of the flipped image (which is the source's left half).
        out = save(item, EditState(geometry = Geometry(flipH = true, crop = CropRect(0.5, 0.0, 1.0, 1.0))), PatchStore.newSession(app))
        assertEquals(200, out.width); assertEquals(300, out.height)
        assertColor(out, 100, 50, Color.RED); assertColor(out, 100, 250, Color.BLUE)
        // Straighten: smaller, same shape, centre unchanged.
        out = save(item, EditState(geometry = Geometry(straighten = 8.0)), PatchStore.newSession(app))
        assertTrue(out.width < 400 && out.height < 300)
        assertEquals(400.0 / 300, out.width.toDouble() / out.height, 0.03)
        assertColor(out, out.width / 4, out.height / 4, Color.RED)
    }

    @Test fun exportColoursMatchThePipeline() {
        val g = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 110, 130)) }
        val item = Robo.item(app, Robo.write(inDir, "g.png", Robo.encode(g, Bitmap.CompressFormat.PNG)))
        val adj = Adjustments().with(AdjustKey.EXPOSURE, 0.4f).with(AdjustKey.WARMTH, 0.5f).with(AdjustKey.SATURATION, 0.3f)
        val filter = FilterSpec(FilterPreset.CINEMA, 0.7f)
        val out = save(item, EditState(adjust = adj, filter = filter), PatchStore.newSession(app))
        val expect = intArrayOf(Color.rgb(90, 110, 130))
        ColorPipeline(adj, filter).apply(expect, 0, 1, 1, 0, 1, 1)
        assertColor(out, 32, 24, expect[0], tol = 1)
    }

    @Test fun eraserReplacesOnlyTheBrushedAreaAtFullResolution() {
        // Large enough that the model window is scaled down (2000 px → 512).
        val item = quadrants(2000, 1500)
        val store = PatchStore.newSession(app)
        ImageSource.open(app, item).use { src ->
            val rs = RetouchedSource(src, store, emptyList())
            val stroke = Stroke(floatArrayOf(300f, 300f, 700f, 300f), 60f)
            val patch = Retouch.erase(rs, listOf(stroke), fill, store, 256L shl 20)
            assertEquals(PatchKind.ERASE, patch.kind)
            assertTrue(patch.left <= 240 && patch.right >= 760)
            val out = save(item, EditState(patches = listOf(patch)), store)
            assertColor(out, 500, 300, 0xFFFF00FF.toInt(), tol = 30)
            assertColor(out, 300, 300, 0xFFFF00FF.toInt(), tol = 30)
            assertColor(out, 500, 500, Color.RED, tol = 4)    // below the stroke: untouched
            assertColor(out, 1500, 300, Color.GREEN, tol = 0) // other quadrant: untouched
        }
        assertEquals(1, fill.calls)
    }

    @Test fun pixelateAndBlurStayInsideTheirStrokes() {
        val w = 600; val h = 400
        val rnd = java.util.Random(5)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(IntArray(w * h) { Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }, 0, w, 0, 0, w, h)
        val item = Robo.item(app, Robo.write(inDir, "noise.png", Robo.encode(b, Bitmap.CompressFormat.PNG)))
        val store = PatchStore.newSession(app)
        ImageSource.open(app, item).use { src ->
            val rs = RetouchedSource(src, store, emptyList())
            val pix = Retouch.obscure(rs, listOf(Stroke(floatArrayOf(150f, 200f), 60f)), PatchKind.PIXELATE, 0.5f, store, 256L shl 20)
            val blur = Retouch.obscure(rs, listOf(Stroke(floatArrayOf(450f, 200f), 50f)), PatchKind.BLUR, 0.6f, store, 256L shl 20)
            val out = save(item, EditState(patches = listOf(pix, blur)), store)
            // Pixelated: neighbouring pixels inside a block are identical.
            assertEquals(out.getPixel(150, 200), out.getPixel(151, 201))
            // Blurred: much smoother than the noise.
            fun roughness(cx: Int, cy: Int): Int { var s = 0; for (x in cx - 5 until cx + 5) s += Robo.diff(out.getPixel(x, cy), out.getPixel(x + 1, cy)); return s }
            assertTrue(roughness(450, 200) < roughness(300, 50) / 4)
            // Far from both strokes: identical to the source.
            assertEquals(b.getPixel(20, 20), out.getPixel(20, 20))
            assertEquals(b.getPixel(580, 380), out.getPixel(580, 380))
        }
    }

    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun layout(a: MainActivity) {
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!cond()) { assertTrue("timed out: $what", System.currentTimeMillis() < deadline); idle(50); Thread.sleep(20) }
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    @Test fun editorScreenErasesUndoesAndSaves() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val item = quadrants(800, 600, "ui.png")
        val screen = EditorScreen(a, item, ToolId.MAGIC_ERASER, EditorMode.ERASE)
        a.navigator.push(screen); idle()
        layout(a)
        waitFor("first render") { screen.renders > 0 }
        layout(a)
        val canvas = all(screen.view).filterIsInstance<EditorCanvas>().single()
        assertTrue(canvas.width > 0 && canvas.hasImage)
        // Brush across the middle of the top-left (red) quadrant.
        val p0 = canvas.toBitmap(0f, 0f)
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, y: Float) = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, x, y, 0)
        val y = canvas.height / 2f - canvas.height * 0.12f
        val x0 = canvas.width * 0.18f; val x1 = canvas.width * 0.32f
        canvas.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, x0, y))
        canvas.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, (x0 + x1) / 2, y))
        canvas.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x1, y))
        waitFor("erase") { screen.session.state.patches.size == 1 && !canvas.busy }
        assertTrue(p0.size == 2)
        assertEquals(1, fill.calls)
        // Undo / redo through the history.
        all(screen.view).first { it.contentDescription == "Undo" }.performClick()
        waitFor("undo") { screen.session.state.patches.isEmpty() }
        all(screen.view).first { it.contentDescription == "Redo" }.performClick()
        waitFor("redo") { screen.session.state.patches.size == 1 }
        // Switch tools, adjust, rotate; then save.
        screen.setMode(EditorMode.ADJUST); idle(300)
        screen.setMode(EditorMode.CROP); idle(300)
        all(screen.view).first { it.contentDescription == "Rotate" }.performClick()
        waitFor("rotate") { screen.session.state.geometry.turns == 1 }
        screen.save(ImageOutFormat.PNG, 100)
        waitFor("export") { ExportManager.state.value.history.any { it.tool == ToolId.MAGIC_ERASER && it.status.finished } }
        val snap = ExportManager.state.value.history.first { it.tool == ToolId.MAGIC_ERASER }
        assertEquals(snap.results.joinToString { "${it.message}" }, JobStatus.SUCCEEDED, snap.status)
        val out = Robo.decode(File(snap.outputs.single().uri.path!!))
        assertEquals(600, out.width); assertEquals(800, out.height)
        // The erased band (left quadrant, rotated) shows the fill colour somewhere; the rest stays put.
        var magenta = 0
        for (yy in 0 until out.height step 4) for (xx in 0 until out.width step 4) if (Robo.diff(out.getPixel(xx, yy), 0xFFFF00FF.toInt()) < 40) magenta++
        assertTrue("erased pixels found: $magenta", magenta > 50)
        assertColor(out, 150, 600, Color.YELLOW, tol = 0)
        assertColor(out, 450, 600, Color.GREEN, tol = 0)
    }
}
