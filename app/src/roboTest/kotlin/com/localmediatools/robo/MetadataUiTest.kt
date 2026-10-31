package com.localmediatools.robo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.Selection
import com.localmediatools.ui.ToolLauncher
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.meta.MetadataScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The metadata viewer end to end on the test files (resources meta/ and docs/, see their CREDITS.md):
 * picking a file, the summary of what it reveals, every field, search, and handing a photo to Remove
 * metadata. With $LMT_SHOTS set, each screen is also saved as a picture to look at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class MetadataUiTest {
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(a: MainActivity) = all(a.navigator.top!!.view).filterIsInstance<TextView>().filter { it.isShown || it.visibility == View.VISIBLE }.map { it.text.toString() }

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
            File(dir, "metadata_$name.png").outputStream().use { Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun fixture(path: String): File {
        val src = File(javaClass.getResource("/$path")!!.toURI())
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "incoming").apply { mkdirs() }
        return File(dir, src.name).also { src.copyTo(it, overwrite = true) }
    }

    private fun activity(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }

    private fun open(a: MainActivity, path: String): MetadataScreen {
        val f = fixture(path)
        val s = MetadataScreen(a, MetadataScreen.Source(Uri.fromFile(f), null, f.name))
        a.navigator.push(s)
        waitFor("$path to be read") { texts(a).contains("What this file tells others") }
        idle(200)
        return s
    }

    @Before fun clean() { Robo.resetUiState() }

    @Test fun theToolReadsAFilePickedWithTheSystemPicker() {
        val a = activity()
        ToolLauncher.open(a, ToolId.METADATA)
        idle()
        assertTrue(a.navigator.top is MetadataScreen)
        render(a, "0_start")
        all(a.navigator.top!!.view).first { it is TextView && it.text == "Choose a file" }.let { (it.parent as View).performClick() }
        idle()
        val started = shadowOf(a).nextStartedActivityForResult
        assertNotNull("the system file picker is asked", started)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        shadowOf(a).receiveResult(started.intent, android.app.Activity.RESULT_OK, Intent().setData(Uri.fromFile(fixture("meta/song.flac"))))
        waitFor("the song's details") { texts(a).contains("What this file tells others") }
        assertTrue(texts(a).joinToString("|"), texts(a).any { it.startsWith("FLAC audio") })
        assertTrue(texts(a).contains("Band"))
        assertTrue(texts(a).contains("Pictures hidden inside"))
    }

    @Test fun aPhotoShowsWhatItRevealsAndEveryField() {
        val a = activity()
        open(a, "meta/photo.jpg")
        val t = texts(a)
        // The summary: place, people, device, time, programs, words.
        for (s in listOf("Where it was made", "Who made it or owns it", "The camera, phone or computer", "When it was made or changed", "The programs used"))
            assertTrue("$s in ${t.joinToString("|")}", t.contains(s))
        assertTrue(t.any { it.contains("22° 57' 6.84\" S") })
        assertTrue(t.contains("Open in a maps app"))
        assertTrue(t.contains("Make a copy without them"))
        // Every field, in plain words where common.
        assertTrue(t.contains("Camera maker")); assertTrue(t.contains("TestCam"))
        assertTrue(t.contains("Serial number")); assertTrue(t.contains("SN-123456"))
        assertTrue(t.contains("LOCATION (GPS) · 9") || t.any { it.startsWith("LOCATION (GPS)") })
        render(a, "1_photo")
        // Search keeps only the matching fields.
        val search = all(a.navigator.top!!.view).filterIsInstance<EditText>().first()
        search.setText("serial")
        idle()
        assertTrue(texts(a).contains("SN-123456"))
        assertFalse(texts(a).contains("TestCam One"))
        render(a, "2_search")
        search.setText("")
        idle()
        // Remove metadata gets this photo.
        all(a.navigator.top!!.view).first { it is TextView && it.text == "Make a copy without them" }.let { (it.parent as View).performClick() }
        waitFor("Remove metadata") { (a.navigator.top as? ToolScreen)?.tool == ToolId.REMOVE_METADATA }
        waitFor("the photo in its selection") { Selection.of(ToolId.REMOVE_METADATA).items.size == 1 }
    }

    @Test fun videosDocumentsAndProtectedPdfs() {
        val a = activity()
        open(a, "meta/clip.mp4")
        assertTrue(texts(a).contains("Where it was made"))
        assertTrue(texts(a).contains("Holiday"))
        render(a, "3_video")
        a.onBackPressed(); idle()
        open(a, "meta/props.docx")
        val t = texts(a)
        assertTrue(t.contains("Author's e-mail")); assertTrue(t.contains("ana@example.com"))
        assertTrue(t.contains("Last saved by")); assertTrue(t.contains("Bruno Lima"))
        render(a, "4_docx")
        a.onBackPressed(); idle()
        open(a, "docs/boreas.doc")
        assertTrue(texts(a).contains("Saved by (with the file's folder)"))
        render(a, "5_doc")
        a.onBackPressed(); idle()
        open(a, "meta/password.pdf")
        assertTrue(texts(a).any { it.contains("needs a password") })
    }
}
