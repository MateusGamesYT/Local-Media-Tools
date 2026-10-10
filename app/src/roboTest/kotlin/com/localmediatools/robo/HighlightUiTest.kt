package com.localmediatools.robo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.core.MediaItem
import com.localmediatools.highlight.HighlightAnalyzer
import com.localmediatools.highlight.ShotInfo
import com.localmediatools.highlight.core.Aspect
import com.localmediatools.highlight.core.FaceBox
import com.localmediatools.highlight.core.ShotKind
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.Selection
import com.localmediatools.ui.tools.HighlightPrefs
import com.localmediatools.ui.tools.HighlightScreen
import com.localmediatools.ui.tools.ToolScreens
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
 * The highlight video's timeline on real photos of real people (test resources /people, credits in
 * CREDITS.tsv): three outings of one day become three moments, the draft edit is marked, and every
 * choice — a shot always in or left out, the shape, a moment's name, the music — changes it.
 * What the phone's models would find is given (faces from the fixture's verified boxes), since the
 * models don't run here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class HighlightUiTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            idle(50); Thread.sleep(20)
        }
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(v: View, text: String) {
        var x: View? = all(v).filterIsInstance<TextView>().firstOrNull { it.text.toString() == text } ?: throw AssertionError("no \"$text\" in ${texts(v)}")
        while (x != null && !x.isClickable) x = x.parent as? View
        x!!.performClick()
    }
    private fun cells(v: View) = all(v).filter { it.contentDescription?.contains(".jpg, ") == true }

    @Before fun setUp() { Robo.resetUiState(); HighlightPrefs.reset() }
    @After fun tearDown() { HighlightPrefs.reset() }

    /** Twelve real photos as three outings of a Saturday (09:00, 15:00, 19:00 local), with their people's faces. */
    private fun photos(): List<MediaItem> {
        val rows = javaClass.getResourceAsStream("/people/faces.tsv")!!.readBytes().toString(Charsets.UTF_8).lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        val files = rows.map { it[0] }.distinct().sorted().take(12)
        val dir = File(app.cacheDir, "highlight").apply { mkdirs() }
        val zone = java.util.TimeZone.getDefault()
        val day = java.util.Calendar.getInstance(zone).apply { clear(); set(2024, 4, 4, 9, 0, 0) }.timeInMillis
        return files.mapIndexed { i, f ->
            val bytes = javaClass.getResourceAsStream("/people/$f")!!.readBytes()
            val item = Robo.item(app, Robo.write(dir, f, bytes))
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            val faces = rows.filter { it[0] == f }.map { r -> FaceBox(r[2].toFloat(), r[3].toFloat(), r[4].toFloat(), r[5].toFloat()) }
            val taken = day + listOf(0, 6, 10)[i / 4] * 3_600_000L + (i % 4) * 150_000L
            HighlightAnalyzer.remember(item, ShotInfo(ShotKind.PHOTO, taken, o.outWidth, o.outHeight, 0, 80.0 + i * 3, 1f, faces, emptyMap(), null, false, emptyList(), false))
            item
        }
    }

    private fun open(items: List<MediaItem>): Pair<MainActivity, HighlightScreen> {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        Selection.of(ToolId.HIGHLIGHT_VIDEO).add(items)
        val s = ToolScreens.create(a, ToolId.HIGHLIGHT_VIDEO) as HighlightScreen
        a.navigator.push(s); idle(600)
        return a to s
    }

    @Test fun reviewingAndChangingTheDraftEdit() {
        val items = photos()
        val (_, s) = open(items)
        waitFor("moments") { texts(s.view).any { it.startsWith("3 moments") } }
        // Morning, afternoon and evening: three moments in time order, each with its shots.
        val shown = texts(s.view)
        val names = listOf("Morning", "Afternoon", "Evening")
        assertTrue(shown.toString(), shown.containsAll(names))
        assertTrue(names.map { shown.indexOf(it) }.zipWithNext().all { (x, y) -> x < y })
        assertEquals(12, cells(s.view).size)
        val used = cells(s.view).filter { it.contentDescription.endsWith("in the video") }
        assertTrue("${used.size}", used.size in 3..12)
        shotIfAsked(s.view, "36_highlight")
        // A shot not in the draft can be brought in; then left out.
        val spare = cells(s.view).firstOrNull { it.contentDescription.endsWith("not used") } ?: used.first()
        val key = items.first { spare.contentDescription.startsWith(it.name) }.key
        spare.performClick(); idle()
        assertTrue(spare.contentDescription.toString(), spare.contentDescription.endsWith("always in"))
        assertTrue(key in HighlightPrefs.settings().include)
        spare.performClick(); idle()
        assertTrue(spare.contentDescription.endsWith("left out"))
        assertTrue(key in HighlightPrefs.settings().exclude && key !in HighlightPrefs.settings().include)
        // The photos are mostly portrait, so the video is too unless chosen otherwise.
        assertTrue(texts(s.view).any { it.startsWith("Auto (portrait") })
        click(s.view, "Landscape 16:9"); idle()
        assertEquals(Aspect.LANDSCAPE, HighlightPrefs.settings().aspect)
        assertTrue(texts(s.view).any { it.startsWith("3 moments") && it.endsWith("Landscape 16:9") })
        click(s.view, "30 s"); idle()
        assertEquals(30.0, HighlightPrefs.settings().seconds!!, 0.0)
        // A moment's own name.
        all(s.view).filterIsInstance<ButtonView>().first { it.label == "Rename" }.performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        all(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("Breakfast at the club")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(texts(s.view).contains("Breakfast at the club"))
        assertEquals("Breakfast at the club", HighlightPrefs.settings().plan(items, items.map { HighlightAnalyzer.cached(it)!! }).moments[0].label)
        // Music choices.
        click(s.view, "Chill"); idle()
        assertEquals("Chill", HighlightPrefs.settings().mood?.label)
        val seed = HighlightPrefs.seed
        click(s.view, "Another tune"); idle()
        assertEquals(seed + 1, HighlightPrefs.seed)
        click(s.view, "No music"); idle()
        assertEquals(null, HighlightPrefs.settings().mood)
        assertTrue(texts(s.view).any { it.contains("no music") })
    }

    @Test fun listeningToTheMusicBeforeSaving() {
        val (_, s) = open(photos())
        waitFor("moments") { texts(s.view).any { it.startsWith("3 moments") } }
        val listen = all(s.view).filterIsInstance<ButtonView>().first { it.label == "Listen" }
        listen.performClick(); idle()
        // The music model runs on the phone (here: in the test) and plays what it wrote: up to 30 s of it.
        waitFor("played") { s.previewFrames > 0 && listen.label == "Listen" }
        assertTrue("${s.previewFrames}", s.previewFrames in 10 * 48_000..30 * 48_000)
    }

    @Test fun newPicturesStartAFreshTimeline() {
        val items = photos()
        val (_, s) = open(items)
        waitFor("moments") { texts(s.view).any { it.startsWith("3 moments") } }
        cells(s.view).first().performClick(); idle()
        assertFalse(HighlightPrefs.include.isEmpty())
        Selection.of(ToolId.HIGHLIGHT_VIDEO).clear()
        Selection.of(ToolId.HIGHLIGHT_VIDEO).add(items.take(8))
        waitFor("two moments") { texts(s.view).any { it.startsWith("2 moments") } }
        assertTrue(HighlightPrefs.include.isEmpty())
    }

    private fun shotIfAsked(root: View, name: String) {
        val dirName = System.getenv("LMT_SHOTS") ?: return
        val dir = File(dirName).apply { mkdirs() }
        val t = System.currentTimeMillis(); while (System.currentTimeMillis() - t < 2500) { idle(50); Thread.sleep(20) }
        val w = app.resources.displayMetrics.widthPixels
        var h = app.resources.displayMetrics.heightPixels
        val content = all(root).filterIsInstance<ScrollView>().firstOrNull()?.getChildAt(0)
        if (content != null) {
            content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            h = maxOf(h, content.measuredHeight)
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF0B0D12.toInt())
        root.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
