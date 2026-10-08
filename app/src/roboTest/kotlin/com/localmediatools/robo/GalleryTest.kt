package com.localmediatools.robo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.gallery.GalleryRepo
import com.localmediatools.gallery.core.SearchTerm
import com.localmediatools.ui.MainShell
import com.localmediatools.ui.gallery.FaceSheet
import com.localmediatools.ui.gallery.FacesScreen
import com.localmediatools.ui.gallery.MediaGrid
import com.localmediatools.ui.gallery.PersonScreen
import com.localmediatools.ui.gallery.SearchScreen
import com.localmediatools.ui.gallery.ViewerScreen
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class GalleryTest {
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
    private fun texts(a: MainActivity) = all(a.navigator.top!!.view).filterIsInstance<TextView>().map { it.text.toString() }

    private fun render(a: MainActivity) {
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        root.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
    }

    @Before fun setUp() { Robo.resetUiState(); FakeGallery.install(app) }
    @After fun tearDown() { FakeGallery.uninstall() }

    private fun openGallery(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        (a.navigator.top as MainShell).show(MainShell.TAB_GALLERY)
        waitFor("indexing") { GalleryIndex.state.value.phase == GalleryIndex.Phase.DONE }
        idle(800)
        FakeGallery.learnOwners(app)
        return a
    }

    @Test fun indexesTheLibraryAndGroupsPeople() {
        val a = openGallery()
        val db = GalleryDb.get(app)
        val (done, total) = db.counts(GalleryIndex.ANALYZER_VERSION)
        assertEquals(36, total); assertEquals(36, done)
        // Photos tab: every item in the grid, newest first.
        val grid = all(a.navigator.top!!.view).filterIsInstance<MediaGrid>().single()
        waitFor("photos grid") { grid.items().size == 36 }
        assertEquals(1L, grid.items().first().id)
        render(a)
        // Three people with several photos each; the blurry face stays on its own.
        val groups = db.people().filter { it.faceCount >= 2 }
        assertEquals(3, groups.size)
        val sizes = groups.map { it.mediaCount }.sorted()
        assertEquals(listOf(3, 10, 12), sizes)
        val blurry = db.faces("f.media_id = 30").single()
        assertEquals(null, blurry.personId)
        // Each group is one identity only.
        for (g in groups) {
            val owners = db.faces("f.person_id = ?", arrayOf(g.id.toString())).map { FakeGallery.faceOwners[it.id] }.distinct()
            assertEquals("group ${g.id} mixes $owners", 1, owners.size)
        }
        // People tab renders the groups as "Add name" tiles.
        (a.navigator.top as MainShell).show(MainShell.TAB_GALLERY)
        val segments = all(a.navigator.top!!.view).filterIsInstance<TextView>().first { it.text == "People" }
        segments.performClick()
        waitFor("people tiles") { texts(a).count { it == "Add name" } >= 3 }
        all(a.navigator.top!!.view).filterIsInstance<TextView>().first { it.text == "Things" }.performClick()
        waitFor("things") { texts(a).contains("Dogs") && texts(a).contains("Beach") }
        render(a)
    }

    @Test fun namingSearchingAndCorrectingPeople() {
        val a = openGallery()
        val db = GalleryDb.get(app)
        fun groupOf(owner: Int) = db.people().first { p -> db.faces("f.person_id = ?", arrayOf(p.id.toString())).any { FakeGallery.faceOwners[it.id] == owner } }
        // Name the two groups.
        FaceSheet.nameGroup(a, groupOf(0), "Sophie", emptyList()) {}
        waitFor("naming") { db.people().any { it.name == "Sophie" } }
        FaceSheet.nameGroup(a, groupOf(1), "John", db.people().filter { it.named }) {}
        waitFor("naming") { db.people().any { it.name == "John" } }
        idle(800)
        val sophie = db.people().single { it.name == "Sophie" }
        assertEquals(10, GalleryRepo.person(app, sophie.id).size)
        // Search: both people (with a typo), a person at a place, things.
        val both = GalleryRepo.search(app, "Jonh and Sophie")
        assertEquals(listOf("jonh" to "john"), both.query.corrections)
        assertEquals((3L..10L).toSet(), both.items.map { it.id }.toSet())
        val beach = GalleryRepo.search(app, "sophie at the beach")
        assertEquals(setOf(6L, 7L, 8L), beach.items.map { it.id }.toSet())
        assertEquals(setOf(2L, 6L, 12L, 18L), GalleryRepo.search(app, "dogs").items.map { it.id }.toSet())
        assertEquals(setOf(7L, 21L), GalleryRepo.search(app, "videos").items.map { it.id }.toSet())
        assertEquals(3, GalleryRepo.search(app, "screenshots").items.size)
        assertTrue(GalleryRepo.search(app, "whatsapp images").query.terms.single() is SearchTerm.Album)
        // The search screen shows the results with the understood terms.
        a.navigator.push(SearchScreen(a, "Sophie John")); idle(500)
        val grid = all(a.navigator.top!!.view).filterIsInstance<MediaGrid>().single()
        waitFor("search results") { grid.items().size == 8 && texts(a).any { it == "8 results" } }
        render(a)
        a.navigator.pop(); idle()
        // The viewer outlines faces with names.
        val photo = db.mediaById(4)!!
        a.navigator.push(ViewerScreen(a, listOf(photo), 0)); idle(800)
        waitFor("faces button") { all(a.navigator.top!!.view).any { it.contentDescription == "Show faces" } }
        all(a.navigator.top!!.view).first { it.contentDescription == "Show faces" }.performClick()
        waitFor("face names") { texts(a).containsAll(listOf("Sophie", "John")) }
        render(a)
        a.navigator.pop(); idle()
        // "This isn't Sophie" takes a face out for good, also after regrouping.
        val wrong = db.faces("f.person_id = ? AND f.media_id = 4", arrayOf(sophie.id.toString())).single()
        db.reject(wrong.id, sophie.id)
        GalleryIndex.regroupNow(app)
        waitFor("regroup") { GalleryIndex.state.value.phase != GalleryIndex.Phase.GROUPING && db.faceById(wrong.id)?.personId != sophie.id }
        idle(500)
        assertTrue(db.faces("f.person_id = ?", arrayOf(sophie.id.toString())).none { it.id == wrong.id })
        assertEquals(9, GalleryRepo.person(app, sophie.id).size)
        // Person and all-faces screens render.
        a.navigator.push(PersonScreen(a, sophie.id))
        waitFor("person screen") { texts(a).contains("Sophie") }
        render(a)
        a.navigator.pop(); idle()
        a.navigator.push(FacesScreen(a))
        waitFor("all faces") { texts(a).contains("Small or blurry") }
        render(a)
    }

    @Test fun pausingStopsIndexingAndRemovedPhotosLeaveTheIndex() {
        val a = openGallery()
        val db = GalleryDb.get(app)
        // A photo deleted from the library disappears with its faces.
        val before = db.faces("1").size
        val keep = FakeGallery.media().filter { it.id != 5L }
        com.localmediatools.gallery.GalleryLibrary.source = { keep }
        GalleryIndex.start(app)
        waitFor("sync") { db.mediaById(5) == null && GalleryIndex.state.value.phase == GalleryIndex.Phase.DONE }
        assertEquals(before - 2, db.faces("1").size)
        GalleryIndex.setPaused(app, true)
        assertTrue(GalleryIndex.state.value.pausedByUser)
        GalleryIndex.setPaused(app, false)
        waitFor("resume") { GalleryIndex.state.value.phase == GalleryIndex.Phase.DONE }
        render(a)
    }
}
