package com.localmediatools.robo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.localmediatools.app.MainActivity
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.MainShell
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
        shell.show(MainShell.TAB_ACTIVITY); idle(); shot(a, dir, "01_activity")
        shell.show(MainShell.TAB_SETTINGS); idle(); shot(a, dir, "02_settings")
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
        newToolShots(a, app, inDir, dir)
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

    @Test fun galleryScreenshots() {
        val dirName = System.getenv("LMT_SHOTS")
        assumeTrue(dirName != null)
        val dir = File(dirName!!).apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        Robo.resetUiState()
        FakeGallery.install(app)
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            idle()
            val shell = a.navigator.top as MainShell
            shell.show(MainShell.TAB_GALLERY)
            waitFor { com.localmediatools.gallery.GalleryIndex.state.value.phase == com.localmediatools.gallery.GalleryIndex.Phase.DONE }
            idle(800)
            FakeGallery.learnOwners(app)
            val db = com.localmediatools.gallery.GalleryDb.get(app)
            fun groupOf(owner: Int) = db.people().first { p -> db.faces("f.person_id = ?", arrayOf(p.id.toString())).any { FakeGallery.faceOwners[it.id] == owner } }
            com.localmediatools.ui.gallery.FaceSheet.nameGroup(a, groupOf(0), "Caroline", emptyList()) {}
            waitFor { db.people().any { it.name == "Caroline" } }
            com.localmediatools.ui.gallery.FaceSheet.nameGroup(a, groupOf(1), "Ian", db.people().filter { it.named }) {}
            waitFor { db.people().any { it.name == "Ian" } }
            idle(1200)
            fun texts() = all(a.navigator.top!!.view).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
            fun tab(label: String, ready: String) {
                all(a.navigator.top!!.view).filterIsInstance<android.widget.TextView>().first { it.text == label }.performClick()
                waitFor { texts().any { it.startsWith(ready) } }; idle(800)
            }
            tab("Photos", "Today"); shot(a, dir, "40_gallery_photos")
            tab("People", "Caroline"); shot(a, dir, "41_gallery_people")
            tab("Things", "Dogs"); shot(a, dir, "42_gallery_things")
            a.navigator.push(com.localmediatools.ui.gallery.SearchScreen(a, "Caroline at the beach")); idle(500)
            waitFor { all(a.navigator.top!!.view).filterIsInstance<android.widget.TextView>().any { it.text.endsWith("results") } }
            idle(800); shot(a, dir, "43_gallery_search")
            a.navigator.pop(); idle()
            a.navigator.push(com.localmediatools.ui.gallery.ViewerScreen(a, listOf(db.mediaById(4)!!), 0)); idle(800)
            waitFor { all(a.navigator.top!!.view).any { it.contentDescription == "Show faces" } }
            all(a.navigator.top!!.view).first { it.contentDescription == "Show faces" }.performClick(); idle(800)
            shot(a, dir, "44_gallery_viewer_faces")
            a.navigator.pop(); idle()
            val caroline = db.people().single { it.name == "Caroline" }
            a.navigator.push(com.localmediatools.ui.gallery.PersonScreen(a, caroline.id))
            waitFor { texts().contains("Caroline") }; idle(1000)
            shot(a, dir, "45_gallery_person")
            a.navigator.pop(); idle()
            a.navigator.push(com.localmediatools.ui.gallery.FacesScreen(a))
            waitFor { texts().contains("Caroline") }; idle(1000)
            shot(a, dir, "46_gallery_all_faces")
        } finally { FakeGallery.uninstall() }
    }

    /** A simple cartoon face for the stand-in face thumbnails. */
    private fun avatar(skin: Int, hair: Int): Bitmap {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF3A4152.toInt())
        val c = Canvas(b); val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = hair; c.drawCircle(80f, 70f, 52f, p)
        p.color = skin; c.drawOval(38f, 44f, 122f, 140f, p)
        p.color = 0xFF2B2B2B.toInt(); c.drawCircle(64f, 86f, 5f, p); c.drawCircle(96f, 86f, 5f, p)
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 4f; c.drawArc(62f, 98f, 98f, 122f, 20f, 140f, false, p)
        return b
    }

    private fun newToolShots(a: MainActivity, app: android.app.Application, inDir: File, dir: File) {
        // Background remover: a "product" photo; the model is replaced by the known subject shape.
        val prod = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        run {
            val c = Canvas(prod); val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.shader = android.graphics.LinearGradient(0f, 0f, 900f, 900f, 0xFFD9CBB8.toInt(), 0xFF9E8F7E.toInt(), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, 900f, 900f, p); p.shader = null
            p.color = 0xFFE4572E.toInt(); c.drawRoundRect(300f, 330f, 600f, 760f, 40f, 40f, p)
            p.color = 0xFFF2F2F2.toInt(); c.drawOval(300f, 290f, 600f, 370f, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 34f; p.color = 0xFFE4572E.toInt(); c.drawArc(540f, 420f, 700f, 640f, -80f, 160f, false, p)
        }
        com.localmediatools.vision.VisionOps.maskOverride = { b ->
            val w = b.width; val h = b.height
            FloatArray(w * h) { i -> val x = (i % w) * 900f / w; val y = (i / w) * 900f / h
                val top = ((x - 450f) / 150f).let { it * it } + ((y - 330f) / 40f).let { it * it } <= 1f
                val body = x in 300f..600f && y in 330f..760f
                val ring = ((x - 620f) / 80f).let { it * it } + ((y - 530f) / 110f).let { it * it }
                if (top || body || (ring in 0.72f..1.3f && x > 600f)) 1f else 0f }
        }
        Selection.of(ToolId.BACKGROUND_REMOVER).add(listOf(Robo.item(app, Robo.write(inDir, "mug.jpg", Robo.encode(prod, Bitmap.CompressFormat.JPEG, 92)))))
        open(a, ToolId.BACKGROUND_REMOVER, dir, "30_background_remover") { a.navigator.top!!.view.let { v -> all(v).filterIsInstance<android.widget.TextView>().any { it.text.startsWith("Preview of") } } }
        com.localmediatools.vision.VisionOps.maskOverride = null

        Selection.of(ToolId.AUTO_ENHANCE).add(listOf(photo(app, inDir)))
        open(a, ToolId.AUTO_ENHANCE, dir, "31_auto_enhance") { all(a.navigator.top!!.view).filterIsInstance<android.widget.TextView>().any { it.text.startsWith("Landscape:") || it.text.startsWith("General:") } }

        // Face blur: two people found in a photo and a video (stand-in for the face models).
        val faces = listOf(avatar(0xFFF1C27D.toInt(), 0xFF4A3426.toInt()), avatar(0xFFC68642.toInt(), 0xFF1E1E1E.toInt()), avatar(0xFFFFDBAC.toInt(), 0xFFB5651D.toInt()))
        com.localmediatools.vision.FaceScanner.override = { items ->
            val fake = Robo.fakeFaces(items)
            val people = fake.people
            val thumbs = java.util.IdentityHashMap<com.localmediatools.vision.core.FaceTrack, Bitmap>()
            for ((k, p) in people.withIndex()) for (t in p.tracks) thumbs[t] = faces[k % faces.size].copy(Bitmap.Config.ARGB_8888, false)
            com.localmediatools.vision.FaceScanResult(items, people, thumbs, emptyList())
        }
        val vid = Robo.item(app, Robo.write(inDir, "party.mp4", byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(64)))
        Selection.of(ToolId.FACE_BLUR).add(listOf(photo(app, inDir), vid))
        open(a, ToolId.FACE_BLUR, dir, "32_face_blur") { all(a.navigator.top!!.view).filterIsInstance<android.widget.TextView>().any { it.text.startsWith("Found") } }
        com.localmediatools.vision.FaceScanner.override = null

        // The duplicate finder is rendered by DuplicatesUiTest, from real photos.


        val clips = (1..3).map { Robo.item(app, Robo.write(inDir, "clip_$it.mp4", byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(64))) }
        Selection.of(ToolId.MERGE_VIDEOS).add(clips)
        open(a, ToolId.MERGE_VIDEOS, dir, "34_merge_videos") { true }
        Selection.of(ToolId.VIDEO_SPEED).add(clips.take(1))
        open(a, ToolId.VIDEO_SPEED, dir, "35_video_speed") { true }
        stackShots(a, app, inDir, dir, faces)
    }

    private fun texts(a: MainActivity) = all(a.navigator.top!!.view).filterIsInstance<android.widget.TextView>().map { it.text.toString() }

    private fun click(a: MainActivity, pred: (String) -> Boolean) {
        val v = all(a.navigator.top!!.view).firstOrNull { v -> (v.contentDescription?.toString() ?: (v as? android.widget.TextView)?.text?.toString())?.let(pred) == true }
            ?: throw AssertionError("nothing to click among ${texts(a)}")
        var c: View? = v
        while (c != null && !c.isClickable) c = c.parent as? View
        c!!.performClick(); idle(600)
    }

    /** Builds "Blur faces → Video compressor → Video → GIF → GIF optimizer" through the real screens. */
    private fun stackShots(a: MainActivity, app: android.app.Application, inDir: File, dir: File, faces: List<Bitmap>) {
        com.localmediatools.vision.FaceScanner.override = { items ->
            val fake = Robo.fakeFaces(items)
            val thumbs = java.util.IdentityHashMap<com.localmediatools.vision.core.FaceTrack, Bitmap>()
            for ((k, p) in fake.people.withIndex()) for (t in p.tracks) thumbs[t] = faces[k % faces.size].copy(Bitmap.Config.ARGB_8888, false)
            com.localmediatools.vision.FaceScanResult(items, fake.people, thumbs, emptyList())
        }
        com.localmediatools.ui.ToolStackState.steps.clear()
        com.localmediatools.ui.ToolStackState.selection.clear()
        com.localmediatools.ui.ToolStackState.selection.add(listOf(Robo.item(app, Robo.write(inDir, "holiday.mp4", byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(64)))))
        com.localmediatools.ui.tools.ToolPrefs.compressQuality = 60
        a.navigator.push(com.localmediatools.ui.StackScreen(a)); idle(800)
        for ((k, t) in listOf(ToolId.FACE_BLUR, ToolId.COMPRESS_VIDEO, ToolId.VIDEO_TO_GIF, ToolId.OPTIMIZE_GIF).withIndex()) {
            layoutRoot(a)
            click(a) { it == "Add the first step" || it == "Add a step" }
            layoutRoot(a)
            if (t == ToolId.OPTIMIZE_GIF) shot(a, dir, "42_stack_picker")
            click(a) { it == t.title }
            val deadline = System.currentTimeMillis() + 15_000
            if (t == ToolId.FACE_BLUR) while (System.currentTimeMillis() < deadline && texts(a).none { it.startsWith("Found") }) { idle(50); Thread.sleep(20); layoutRoot(a) }
            idle(500)
            if (t == ToolId.COMPRESS_VIDEO) shot(a, dir, "41_stack_step")
            layoutRoot(a)
            click(a) { it == "Add to stack" }
            idle(800)
        }
        com.localmediatools.vision.FaceScanner.override = null
        layoutRoot(a)
        shot(a, dir, "40_tool_stack")
        @Suppress("DEPRECATION") a.onBackPressed(); idle()
    }

    private fun open(a: MainActivity, t: ToolId, dir: File, name: String, ready: () -> Boolean) {
        a.navigator.push(ToolScreens.create(a, t))
        idle(1500)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !ready()) { idle(50); Thread.sleep(20) }
        idle(300)
        shot(a, dir, name)
        @Suppress("DEPRECATION") a.onBackPressed(); idle()
    }

    /** Full-length shot including the sticky bottom bar. */
    private fun shotWindowFull(a: MainActivity, dir: File, name: String) = shot(a, dir, name)

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
