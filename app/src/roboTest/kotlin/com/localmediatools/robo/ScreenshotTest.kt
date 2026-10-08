package com.localmediatools.robo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.localmediatools.app.MainActivity
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.Selection
import com.localmediatools.ui.tools.ToolScreens
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/** Renders full-length screenshots of key screens into $LMT_SHOTS (skipped when unset). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    private fun idle(ms: Long = 500) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun shot(a: MainActivity, dir: File, name: String) {
        val root = a.navigator.top!!.view
        val w = a.resources.displayMetrics.widthPixels
        val sv = all(root).filterIsInstance<ScrollView>().firstOrNull()
        val content = sv?.getChildAt(0)
        var h = a.resources.displayMetrics.heightPixels
        if (content != null) {
            content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            h = maxOf(h, content.measuredHeight)
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val small = Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true)
        File(dir, "$name.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!cond() && System.currentTimeMillis() < deadline) { idle(50); Thread.sleep(20) }
    }

    private fun photo(app: android.app.Application, dir: File): com.localmediatools.core.MediaItem {
        // A synthetic "landscape": sky gradient, sun, hills and a person-like blob.
        val w = 1200; val h = 900
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.6f, 0xFF5B8DEF.toInt(), 0xFFF6C99B.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = 0xFFFFE08A.toInt(); c.drawCircle(w * 0.72f, h * 0.28f, 70f, p)
        p.color = 0xFF3E7D4F.toInt(); c.drawOval(-200f, h * 0.55f, w * 0.7f, h * 1.3f, p)
        p.color = 0xFF2F6640.toInt(); c.drawOval(w * 0.35f, h * 0.6f, w * 1.3f, h * 1.4f, p)
        p.color = 0xFF263238.toInt(); c.drawRoundRect(w * 0.44f, h * 0.48f, w * 0.5f, h * 0.74f, 30f, 30f, p); c.drawCircle(w * 0.47f, h * 0.45f, 28f, p)
        return Robo.item(app, Robo.write(dir, "landscape.png", Robo.encode(b, Bitmap.CompressFormat.PNG)))
    }

    @Test fun screenshots() {
        val dirName = System.getenv("LMT_SHOTS")
        assumeTrue(dirName != null)
        val dir = File(dirName!!).apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        Robo.resetUiState()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        shot(a, dir, "00_home")
        val shell = a.navigator.top as com.localmediatools.ui.MainShell
        shell.show(1); idle(); shot(a, dir, "01_activity")
        shell.show(2); idle(); shot(a, dir, "02_settings")
        shell.show(0); idle()
        val inDir = File(app.cacheDir, "shot-in").apply { mkdirs() }
        val land = photo(app, inDir)
        com.localmediatools.edit.Inpainters.factory = { FillInpainter() }
        for (m in com.localmediatools.ui.editor.EditorMode.entries) {
            val ed = com.localmediatools.ui.editor.EditorScreen(a, land, com.localmediatools.tools.ToolId.PHOTO_EDITOR, m)
            a.navigator.push(ed); idle()
            layoutRoot(a)
            waitFor { ed.renders > 0 }
            if (m == com.localmediatools.ui.editor.EditorMode.ADJUST) {
                ed.session.commit(ed.session.state.copy(adjust = com.localmediatools.codec.edit.Adjustments().with(com.localmediatools.codec.edit.AdjustKey.WARMTH, 0.4f)))
                ed.requestRender(true); waitFor { ed.renders > 1 }
            }
            idle(600)
            shotWindow(a, dir, "1${m.ordinal}_editor_${m.name.lowercase()}")
            ed.session.saved = false
            a.navigator.pop(); idle(400)
        }
        com.localmediatools.edit.Inpainters.factory = null
        for (t in listOf(ToolId.TRIM_VIDEO, ToolId.REMOVE_METADATA, ToolId.MERGE_IMAGES, ToolId.WATERMARK, ToolId.EXTRACT_PDF_PAGES)) {
            if (t == ToolId.MERGE_IMAGES || t == ToolId.WATERMARK) {
                val items = (0 until 3).map { k ->
                    val b = Bitmap.createBitmap(300 + k * 100, 400 - k * 60, Bitmap.Config.ARGB_8888)
                    b.eraseColor(intArrayOf(0xFF7C5CFF.toInt(), 0xFFFB923C.toInt(), 0xFF2DD4BF.toInt())[k])
                    Robo.item(app, Robo.write(inDir, "photo_${k + 1}.png", Robo.encode(b, Bitmap.CompressFormat.PNG)))
                }
                Selection.of(t).add(items)
            }
            if (t == ToolId.WATERMARK) com.localmediatools.ui.tools.WatermarkState.text = "© Villa Real"
            a.navigator.push(ToolScreens.create(a, t))
            idle(1500)
            shot(a, dir, "2${t.ordinal.toString().padStart(2, '0')}_${t.name.lowercase()}")
            @Suppress("DEPRECATION") a.onBackPressed(); idle()
        }
        com.localmediatools.ui.WorkloadDialog.show(a); idle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val dv = d.window!!.decorView
        val w = a.resources.displayMetrics.widthPixels
        dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        dv.layout(0, 0, w, dv.measuredHeight)
        val bmp = Bitmap.createBitmap(w, dv.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF000000.toInt())
        dv.draw(Canvas(bmp))
        File(dir, "99_workload.png").outputStream().use { Bitmap.createScaledBitmap(bmp, w / 2, bmp.height / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun layoutRoot(a: MainActivity) {
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
    }

    /** Screen-sized shot of the window (for full-screen UIs like the editor). */
    private fun shotWindow(a: MainActivity, dir: File, name: String) {
        layoutRoot(a)
        val root = a.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { Bitmap.createScaledBitmap(bmp, root.width / 2, root.height / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
