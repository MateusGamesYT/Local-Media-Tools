package com.localmediatools.robo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.export.ExportManager
import com.localmediatools.highlight.core.ShotKind
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.video.EditTab
import com.localmediatools.ui.video.VideoEditorScreen
import com.localmediatools.video.EditDraft
import com.localmediatools.video.EditMedia
import com.localmediatools.video.ReframeAnalyzer
import com.localmediatools.video.core.Box
import com.localmediatools.video.core.EditSource
import com.localmediatools.video.core.Found
import com.localmediatools.video.core.Framing
import com.localmediatools.video.core.LookPreset
import com.localmediatools.video.core.OutShape
import com.localmediatools.video.core.Reframe
import com.localmediatools.video.core.SubjectKind
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
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration
import kotlin.math.abs

/**
 * The video editor on real footage: a Global Lives Project clip (CC BY 3.0, test resources /video,
 * credits in CREDITS.tsv; a man beside a horse cart in Cali) and a real photo of a person (/people).
 * The phone's decoders and models don't run here: frames come from the clip's frames every 2 s, and
 * auto reframe runs the app's own planning on what the app's detectors found in the clip
 * (buildtools/video/reframe_detect.py).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class VideoEditorTest {
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

    private val clipUri = Uri.parse("content://test/clip02.mp4")
    private val photoUri = Uri.parse("content://test/photo.jpg")
    private val photoFile = "02e3d0550a82ef46.jpg"
    private val frames: Map<Long, Bitmap> by lazy {
        val names = listOf(0, 2002, 4004, 6006, 8008, 10010, 12012, 14014, 16016, 18018, 20020, 22022, 24024, 26026, 28028, 30030)
        names.associate { ms -> ms.toLong() to javaClass.getResourceAsStream("/video/frame_%05d.jpg".format(ms))!!.use { BitmapFactory.decodeStream(it)!! } }
    }
    /** What the app's detectors found in the clip, by time. */
    private val found: Map<Long, List<Found>> by lazy {
        val out = HashMap<Long, MutableList<Found>>()
        javaClass.getResourceAsStream("/video/found.tsv")!!.bufferedReader().forEachLine { l ->
            val r = l.split('\t')
            out.getOrPut(r[0].toLong()) { ArrayList() }.add(Found(SubjectKind.valueOf(r[1]), Box(r[2].toFloat(), r[3].toFloat(), r[4].toFloat(), r[5].toFloat()), r[6].toFloat()))
        }
        out
    }

    @Before fun setUp() {
        Robo.resetUiState()
        EditDraft.clear(app)
        EditMedia.clearCache()
        EditMedia.describeOverride = { uri ->
            when (uri) {
                clipUri -> EditSource(uri.toString(), "clip02.mp4", ShotKind.VIDEO, 640, 360, 30_030, true)
                photoUri -> EditSource(uri.toString(), photoFile, ShotKind.PHOTO, 800, 1200)
                else -> null
            }
        }
        EditMedia.frameOverride = { s, ms, _ ->
            if (s.kind == ShotKind.PHOTO) javaClass.getResourceAsStream("/people/$photoFile")!!.use { BitmapFactory.decodeStream(it) }
            else frames[frames.keys.minBy { abs(it - ms) }]!!.copy(Bitmap.Config.ARGB_8888, false)
        }
        // The app's own planning on the app's detections of the clip (5 a second, as on the phone).
        ReframeAnalyzer.override = { _, from, to, aspect, zoom ->
            val chosen = LinkedHashMap<Long, List<Found>>()
            var next = from.toDouble()
            for (t in found.keys.sorted()) { if (t < from || t > to || t + 1e-6 < next) continue; chosen[t] = found[t]!!; next = t + 1000.0 / ReframeAnalyzer.RATE }
            val samples = ReframeAnalyzer.samples(chosen, emptyList())
            Reframe.path(samples, Reframe.subjects(samples, 16f / 9f, aspect), 16f / 9f, Reframe.Options(aspect, zoom))
        }
    }

    @After fun tearDown() {
        EditMedia.describeOverride = null; EditMedia.frameOverride = null; ReframeAnalyzer.override = null
        EditDraft.clear(app)
    }

    @Test fun editsAVideoFromStartToSave() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val s = VideoEditorScreen(a, listOf(clipUri, photoUri))
        a.navigator.push(s); idle(500)
        waitFor("clips") { s.project.clips.size == 2 }
        val root = a.navigator.top!!.view
        assertEquals(30_030L + 3_000L, s.timeline.durationMs)
        // Split the clip at 5 s; undo and redo.
        s.setPlayhead(5_000, follow = true); idle()
        click(root, "Split here"); idle()
        assertEquals(3, s.project.clips.size)
        assertEquals(5_000L, s.project.clips[0].outMs); assertEquals(5_000L, s.project.clips[1].inMs)
        s.undo(); idle(); assertEquals(2, s.project.clips.size)
        s.redo(); idle(); assertEquals(3, s.project.clips.size)
        // The first part twice as fast.
        click(root, "2×"); idle()
        assertEquals(2f, s.project.clips[s.selected].speed, 0f)
        assertEquals(2_500L, s.timeline.placed[0].clip.lengthMs)
        // Black & white.
        s.setTab(EditTab.LOOK); idle()
        click(root, "Black & white"); idle()
        assertEquals(LookPreset.MONO, s.project.clips[0].look.preset)
        // Portrait, and the rest of the clip following its subject.
        s.setTab(EditTab.SHAPE); idle()
        click(root, "Portrait 9:16"); idle()
        assertEquals(OutShape.PORTRAIT, s.project.shape)
        s.setPlayhead(s.timeline.placed[1].startMs + 100, follow = true); idle()
        assertEquals(1, s.selected)
        s.setTab(EditTab.FRAME); idle()
        click(a.navigator.top!!.view, "Auto reframe")
        waitFor("auto reframe") { s.project.clips[1].reframe != null }
        val c = s.project.clips[1]
        assertEquals(Framing.AUTO, c.framing)
        val keys = c.reframe!!
        assertEquals((9f / 16f) / (16f / 9f), keys.first().crop.w, 1e-3f)
        // The man the clip is about stays in the picture: the largest person found is inside the crop.
        var inside = 0; var seen = 0
        for (k in keys) {
            val p = found[k.ms]?.filter { it.kind == SubjectKind.PERSON }?.maxByOrNull { it.box.area } ?: continue
            seen++
            if (p.box.cx >= k.crop.x && p.box.cx <= k.crop.x + k.crop.w) inside++
        }
        assertTrue("$inside of $seen", seen > 20 && inside >= seen * 0.9)
        s.setPlayhead(s.timeline.placed[1].startMs + 9_000, follow = false); idle()
        render(a, "video-editor-reframe")
        // A title.
        s.setTab(EditTab.TEXT); idle()
        s.setPlayhead(s.timeline.placed[1].startMs + 500, follow = false)
        s.editText(null); idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        all(dialog.window!!.decorView).filterIsInstance<EditText>().first().setText("Cali, 12:30")
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertEquals("Cali, 12:30", s.project.texts.single().text)
        // The preview draws (Robolectric draws only when asked), and the draft is kept.
        val preview = all(a.navigator.top!!.view).filterIsInstance<com.localmediatools.ui.video.EditPreview>().single()
        waitFor("preview") { render(a, null); preview.drawn > 0 }
        waitFor("draft") { EditDraft.load(app)?.project?.texts?.size == 1 }
        val d = EditDraft.load(app)!!
        assertEquals(s.project.clips.map { it.copy() }, d.project.clips)
        assertEquals(s.project, d.project)
        render(a, "video-editor")
        // Saving starts the export.
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        s.startExport(720); idle()
        waitFor("export queued") { ExportManager.state.value.let { st -> (listOfNotNull(st.active) + st.queued + st.history).any { it.tool == ToolId.VIDEO_EDITOR } } }
        ExportManager.cancelAll()
    }

    @Test fun aDraftComesBackAndOldFilesAreLeftOut() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val s = VideoEditorScreen(a, listOf(clipUri))
        a.navigator.push(s); idle(500)
        waitFor("clip") { s.project.clips.size == 1 }
        waitFor("draft") { EditDraft.load(app) != null }
        a.navigator.pop(); idle()
        // Opened again from Tools: the same project.
        val again = VideoEditorScreen(a)
        a.navigator.push(again); idle(500)
        assertEquals(1, again.project.clips.size)
        assertNotNull(texts(a.navigator.top!!.view).firstOrNull { it.contains("clip02.mp4") })
    }

    /** A very long text is made smaller to fit inside the picture instead of failing. */
    @Test fun aLongTextFitsInThePicture() {
        val long = com.localmediatools.video.core.EditText(1, (1..60).joinToString(" ") { "word$it" }, 0, 3000, com.localmediatools.video.core.TextPlace.BOTTOM, 2.5f, true)
        for ((w, h) in listOf(1920 to 1080, 1080 to 1920, 1080 to 1080)) {
            val (bmp, f) = com.localmediatools.video.EditTextPainter.paint(long, w, h)
            assertTrue("$w×$h: $f", f.y >= 0f && f.y + f.h <= 1f + 1e-4f && f.x >= 0f && f.x + f.w <= 1f + 1e-4f)
            assertTrue(bmp.height <= h)
        }
    }

    /** A draft whose file was moved or deleted since: that clip is left out when the editor opens. */
    @Test fun aDraftsMissingFilesAreLeftOut() {
        val gone = Uri.parse("content://test/deleted.mp4")
        val missing = EditDraft.clipFor(EditSource(gone.toString(), "deleted.mp4", ShotKind.VIDEO, 640, 360, 10_000, true), 1)
        val kept = EditDraft.clipFor(EditSource(clipUri.toString(), "clip02.mp4", ShotKind.VIDEO, 640, 360, 30_030, true), 2)
        EditDraft.save(app, EditDraft.Saved(com.localmediatools.video.core.EditProject(listOf(missing, kept)), null, null, 1))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val s = VideoEditorScreen(a)
        a.navigator.push(s); idle(500)
        waitFor("missing file left out") { s.project.clips.size == 1 }
        assertEquals(clipUri.toString(), s.project.clips.single().source.uri)
    }

    /** Draws the screen (catching drawing errors); with LMT_SHOTS set, also saves it there. */
    private fun render(a: MainActivity, name: String?) {
        val dir = if (name == null) null else System.getenv("LMT_SHOTS")?.let { java.io.File(it).apply { mkdirs() } }
        if (dir != null) { val t = System.currentTimeMillis(); while (System.currentTimeMillis() - t < 2500) { idle(50); Thread.sleep(20) } }
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (dir != null) bmp.eraseColor(0xFF0B0D12.toInt())
        root.draw(Canvas(bmp))
        if (dir != null) java.io.File(dir, "$name.png").outputStream().use { Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
