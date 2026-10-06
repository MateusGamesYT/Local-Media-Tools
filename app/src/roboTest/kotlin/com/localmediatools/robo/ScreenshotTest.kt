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

    @Test fun screenshots() {
        val dirName = System.getenv("LMT_SHOTS")
        assumeTrue(dirName != null)
        val dir = File(dirName!!).apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        Robo.resetUiState()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        shot(a, dir, "00_home")
        val inDir = File(app.cacheDir, "shot-in").apply { mkdirs() }
        for (t in listOf(ToolId.COMPRESS_VIDEO, ToolId.MERGE_IMAGES, ToolId.STITCH, ToolId.WATERMARK, ToolId.COMPRESS_GIF, ToolId.PDF_SCANNER, ToolId.CONVERT_IMAGES)) {
            if (t == ToolId.MERGE_IMAGES || t == ToolId.WATERMARK || t == ToolId.CONVERT_IMAGES) {
                val items = (0 until 3).map { k ->
                    val b = Bitmap.createBitmap(300 + k * 100, 400 - k * 60, Bitmap.Config.ARGB_8888)
                    b.eraseColor(intArrayOf(0xFF3B82F6.toInt(), 0xFFF59E0B.toInt(), 0xFF10B981.toInt())[k])
                    Robo.item(app, Robo.write(inDir, "photo_${k + 1}.png", Robo.encode(b, Bitmap.CompressFormat.PNG)))
                }
                Selection.of(t).add(items)
            }
            if (t == ToolId.WATERMARK) com.localmediatools.ui.tools.WatermarkState.text = "© Villa Real"
            a.navigator.push(ToolScreens.create(a, t))
            idle(1500)
            shot(a, dir, "${t.ordinal + 1}_${t.name.lowercase()}")
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
}
