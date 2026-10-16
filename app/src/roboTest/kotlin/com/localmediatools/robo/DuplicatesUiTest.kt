package com.localmediatools.robo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.gallery.GalleryIndex
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.DupChoices
import com.localmediatools.ui.DuplicateReviewScreen
import com.localmediatools.ui.DuplicatesScreen
import com.localmediatools.ui.MainShell
import com.localmediatools.ui.PhotoCell
import com.localmediatools.ui.ProgressBarView
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.gallery.FaceSheet
import com.localmediatools.vision.DupResultGroup
import com.localmediatools.vision.DupScanState
import com.localmediatools.vision.DuplicateScanner
import com.localmediatools.vision.GalleryPhoto
import com.localmediatools.vision.core.AutoEnhance
import com.localmediatools.vision.core.DupKind
import com.localmediatools.vision.core.Duplicates
import com.localmediatools.vision.core.KeepRules
import com.localmediatools.vision.core.PhotoInfo
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.File
import java.time.Duration

/**
 * The duplicate finder's screens on real photos (test resources /duplicates, CC BY 2.0): groups made
 * by the app's own grouping from the photos (embeddings from the on-device model, golden.bin), keep
 * rules, quick choices on the list and the one-by-one review; and the "keep photos of…" rule with the
 * gallery's real people (test resources /people).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class DuplicatesUiTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun idle(ms: Long = 300) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun waitFor(what: String, detail: () -> String = { "" }, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what ${detail()}")
            idle(50); Thread.sleep(20)
        }
    }
    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)
    private fun texts(a: MainActivity) = all(a.navigator.top!!.view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun click(a: MainActivity, text: String) {
        var v: View? = all(a.navigator.top!!.view).filterIsInstance<TextView>().firstOrNull { it.text.toString() == text } ?: throw AssertionError("no \"$text\" in ${texts(a)}")
        while (v != null && !v.isClickable) v = v.parent as? View
        v!!.performClick()
    }
    private fun button(a: MainActivity, label: String) = all(a.navigator.top!!.view).filterIsInstance<ButtonView>().first { it.label == label }

    @Before fun setUp() {
        Robo.resetUiState()
        app.getSharedPreferences("duplicates", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        DupChoices.clear()
    }
    @After fun tearDown() {
        DuplicateScanner.show(DupScanState.Idle); DupChoices.clear()
        DuplicateScanner.source = null; DuplicateScanner.thumbnails = null; DuplicateScanner.embeddings = null
    }

    private fun golden(): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        DataInputStream(javaClass.getResourceAsStream("/duplicates/golden.bin")!!.buffered()).use { d ->
            repeat(d.readInt()) {
                val name = String(ByteArray(d.readUnsignedShort()).also { d.readFully(it) })
                val len = d.readInt(); val scale = d.readFloat()
                val q = ByteArray(len).also { d.readFully(it) }
                val e = FloatArray(len) { q[it] * scale }
                var s = 0f; for (v in e) s += v * v; val n = Math.sqrt(s.toDouble()).toFloat(); for (k in e.indices) e[k] /= n
                repeat(256) { d.readFloat() }; d.readFloat()
                out[name] = e
            }
        }
        return out
    }

    /** The scan's result for the fixture, grouped by the app's code as the scanner does (no feature check here). */
    private fun scan(rawName: String? = null): List<DupResultGroup> {
        val dir = File(app.cacheDir, "dups").apply { mkdirs() }
        val emb = golden()
        val names = emb.keys.sorted()
        val t0 = 1_700_000_000_000L
        val times = mapOf("court_1" to 0L, "court_2" to 3_000L, "bridge_1" to 600_000L, "bridge_2" to 604_000L, "cushion_1" to 1_200_000L, "cushion_2" to 1_209_000L,
            "sunset_1" to 1_800_000L, "sunset_2" to 1_820_000L, "copy_lowq_bridge_1" to 600_000L)
        val photos = ArrayList<GalleryPhoto>(); val infos = ArrayList<PhotoInfo>()
        for ((i, n) in names.withIndex()) {
            val bytes = javaClass.getResourceAsStream("/duplicates/$n.jpg")!!.readBytes()
            val fileName = if (n == rawName) "$n.dng" else "$n.jpg"
            val f = File(dir, fileName).also { it.writeBytes(bytes) }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val (grid, contrast) = Duplicates.grid(px, bmp.width, bmp.height)
            val tiny = Bitmap.createScaledBitmap(bmp, 9, 8, true)
            val tp = IntArray(72); tiny.getPixels(tp, 0, 9, 0, 0, 9, 8)
            val dh = Duplicates.dhash(IntArray(72) { val c = tp[it]; (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000 })
            val sharp = AutoEnhance.sharpness(px, bmp.width, bmp.height)
            val taken = times[n]?.let { t0 + it }
            // Sizes as a camera would have them: the originals 12 MP, copies smaller.
            val (w, h) = if (n.startsWith("copy_small")) 2000 to 1333 else if (bmp.width > bmp.height) 4000 to (4000 * bmp.height / bmp.width) else (4000 * bmp.width / bmp.height) to 4000
            photos.add(GalleryPhoto(1000L + i, Uri.fromFile(f), fileName, if (n.startsWith("copy")) 900_000 else 3_500_000L + i, w, h, taken, 1_000 + i.toLong(), if (n.startsWith("copy")) "WhatsApp Images" else "Camera",
                mime = if (n == rawName) "image/x-adobe-dng" else "image/jpeg"))
            infos.add(PhotoInfo(i, photos.last().size, w, h, dh, emb.getValue(n), grid, contrast, sharp, taken, null))
        }
        return Duplicates.group(infos).map { g ->
            val members = g.members.map { photos[it] }
            DupResultGroup(g.kind, members, photos[g.best], g.pictures.map { p -> p.map { photos[it] } }, g.members.associate { photos[it].uri to infos[it].sharpness })
        }
    }

    private fun open(groups: List<DupResultGroup>): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        DuplicateScanner.show(DupScanState.Done(groups, 2412, true))
        a.navigator.push(DuplicatesScreen(a)); idle(800)
        return a
    }

    /** Brings a group's row into view (the list only lays out the rows on screen). */
    private fun show(a: MainActivity, groups: List<DupResultGroup>, g: DupResultGroup) {
        val list = all(a.navigator.top!!.view).filterIsInstance<android.widget.ListView>().first()
        list.setSelection(list.headerViewsCount + DupChoices.visible(groups).indexOf(g)); idle()
    }

    private fun named(g: DupResultGroup) = g.photos.map { it.name.substringBeforeLast('.') }.toSet()

    @Test fun reviewingRealDuplicatesQuickly() {
        val groups = scan()
        // Same moments and copies, nothing else. (cushion_1 and _2 need the feature check, which runs on
        // the phone and in the JVM test, not here.)
        assertEquals(setOf(setOf("court_1", "court_2"), setOf("sunset_1", "sunset_2"),
            setOf("bridge_1", "bridge_2", "copy_small_bridge_1", "copy_lowq_bridge_1"), setOf("document_a", "copy_lowq_document_a")), groups.map(::named).toSet())
        val a = open(groups)
        // Copies are suggested: the two bridge copies and one of the document's; similar shots are left alone.
        val bridge = groups.first { "bridge_1" in named(it) }
        waitFor("selection bar", { "${texts(a)} remove=${DupChoices.remove.size}" }) { texts(a).any { it.startsWith("3 selected") } }
        assertEquals(setOf("copy_small_bridge_1", "copy_lowq_bridge_1"), bridge.photos.filter { it.uri in DupChoices.remove }.map { it.name.substringBeforeLast('.') }.toSet())
        shotIfAsked(a, "33_duplicates")
        // A tap changes only that photo: the cell is redrawn in place, not the whole list.
        show(a, groups, groups.first { "court_1" in named(it) })
        val cells = all(a.navigator.top!!.view).filterIsInstance<PhotoCell>()
        val court2 = cells.first { it.contentDescription.startsWith("court_2") }
        court2.performClick(); idle()
        assertSame(court2, all(a.navigator.top!!.view).filterIsInstance<PhotoCell>().first { it.contentDescription.startsWith("court_2") })
        assertTrue(court2.contentDescription.contains("will be moved to the trash"))
        waitFor("4 selected") { texts(a).any { it.startsWith("4 selected") } }
        // The last photo of a group can't go too.
        val court1 = all(a.navigator.top!!.view).filterIsInstance<PhotoCell>().first { it.contentDescription.startsWith("court_1") }
        court1.performClick(); idle()
        assertFalse(court1.contentDescription.contains("will be moved"))
        // Rules: similar shots too keeps the best of each burst and suggests the rest.
        all(a.navigator.top!!.view).filterIsInstance<android.widget.ListView>().first().setSelection(0); idle()
        click(a, "Keep rules"); idle()
        all(a.navigator.top!!.view).filterIsInstance<ToggleRow>().first { it.title == "Suggest similar shots too" }.performClick(); idle()
        assertTrue(DupChoices.rules.similarShots)
        // court was changed by hand, so it stays as it is; sunset and bridge follow the rule: one photo
        // stays (the sharper shot, and of its copies the best one).
        assertTrue(groups.filter { "sunset_1" in named(it) }.all { g -> g.photos.count { it.uri in DupChoices.remove } == 1 })
        assertEquals(1, bridge.photos.count { it.uri !in DupChoices.remove })
        // Reset makes every group follow the rules again.
        button(a, "Reset to suggestions").performClick(); idle()
        assertEquals(1, groups.first { "court_1" in named(it) }.photos.count { it.uri in DupChoices.remove })
        // Copies only.
        click(a, "Copies"); idle()
        waitFor("filtered") { all(a.navigator.top!!.view).filterIsInstance<PhotoCell>().filter { it.visibility == View.VISIBLE && it.isShown }.none { it.contentDescription.startsWith("court") } }
        click(a, "All"); idle()
        // One by one: "Keep all" decides the group and moves on; "Done" on the last goes back.
        button(a, "Review one by one").performClick(); idle(600)
        waitFor("review") { a.navigator.top is DuplicateReviewScreen && texts(a).any { it.startsWith("Group 1 of 4") } }
        shotIfAsked(a, "33b_duplicates_review")
        val first = DupChoices.visible(groups).first()
        button(a, "Keep all").performClick(); idle()
        assertTrue(first.photos.none { it.uri in DupChoices.remove })
        waitFor("group 2") { texts(a).any { it.startsWith("Group 2 of 4") } }
        repeat(2) { button(a, "Next").performClick(); idle() }
        waitFor("last") { texts(a).any { it.startsWith("Group 4 of 4") } && all(a.navigator.top!!.view).filterIsInstance<ButtonView>().any { it.label == "Done" } }
        button(a, "Done").performClick(); idle(600)
        assertTrue(a.navigator.top is DuplicatesScreen)
    }

    @Test fun rawFilesStayAndTheBestOtherCopyToo() {
        // bridge_1 as a camera RAW: kept, and the best JPEG copy stays next to it.
        val groups = scan(rawName = "bridge_1")
        val a = open(groups)
        val bridge = groups.first { g -> g.photos.any { it.name == "bridge_1.dng" } }
        waitFor("selection") { DupChoices.remove.isNotEmpty() }
        val gone = bridge.photos.filter { it.uri in DupChoices.remove }.map { it.name }
        assertFalse(gone.contains("bridge_1.dng"))
        assertEquals(1, gone.size)
        show(a, groups, bridge)
        waitFor("RAW badge", { texts(a).toString() }) { texts(a).any { it == "RAW" } }
        // Without the rule the RAW is just the best copy: both JPEG copies are suggested.
        DupChoices.setRules(app, DupChoices.rules.copy(keepRaw = false)); idle()
        assertEquals(2, bridge.photos.count { it.uri in DupChoices.remove })
    }

    @Test fun photosOfAChosenPersonAreNeverSuggested() {
        // The gallery finds and groups the real people of the fixture; Caroline is named.
        FakeGallery.install(app)
        try {
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            idle()
            (a.navigator.top as MainShell).show(MainShell.TAB_GALLERY)
            waitFor("indexing") { GalleryIndex.state.value.phase == GalleryIndex.Phase.DONE }
            idle(800)
            FakeGallery.learnOwners(app)
            val db = GalleryDb.get(app)
            val caroline = db.people().first { p -> db.faces("f.person_id = ?", arrayOf(p.id.toString())).any { FakeGallery.faceOwners[it.id] == 0 } }
            FaceSheet.nameGroup(a, caroline, "Caroline", emptyList()) {}
            waitFor("named") { db.people().any { it.name == "Caroline" } }
            idle(500)
            // A copy of a photo of Caroline (gallery item 1, alone) and of one with Ian (item 11).
            fun gp(id: Long, size: Long, name: String) = GalleryPhoto(id, com.localmediatools.gallery.GalleryLibrary.uriOf(id, false), name, size, 4000, 3000, null, id, "Camera")
            val g1 = listOf(gp(1, 3_000_000, "IMG_1.jpg"), gp(901, 2_000_000, "IMG_1-copy.jpg"))
            val g2 = listOf(gp(11, 3_000_000, "IMG_11.jpg"), gp(902, 2_000_000, "IMG_11-copy.jpg"))
            val groups = listOf(DupResultGroup(DupKind.NEAR_DUPLICATE, g1, g1[0], listOf(g1)), DupResultGroup(DupKind.NEAR_DUPLICATE, g2, g2[0], listOf(g2)))
            DuplicateScanner.show(DupScanState.Done(groups, 36, true))
            a.navigator.push(DuplicatesScreen(a)); idle(800)
            waitFor("people loaded") { DupChoices.names.values.contains("Caroline") }
            // Without the rule, the smaller copy of each is suggested.
            assertEquals(setOf(g1[1].uri, g2[1].uri), DupChoices.remove)
            // Keep Caroline: her photo is protected and shows why; the plain copy of it can still go.
            click(a, "Keep rules"); idle()
            click(a, "Caroline"); idle()
            assertEquals(setOf(caroline.id), DupChoices.rules.keepPeople)
            assertEquals("Caroline", DupChoices.protectedBy(groups[0], g1[0]))
            assertEquals(null, DupChoices.protectedBy(groups[1], g2[0]))
            // Preferring the newest file would now remove the photo of Caroline; the rule keeps it.
            DupChoices.setRules(app, DupChoices.rules.copy(prefer = com.localmediatools.vision.core.Prefer.NEWEST)); idle()
            assertFalse(g1[0].uri in DupChoices.remove)
            assertTrue(g2[0].uri in DupChoices.remove)
            assertEquals(KeepRules().keepRaw, DupChoices.rules.keepRaw)
        } finally { FakeGallery.uninstall() }
    }

    private fun shotIfAsked(a: MainActivity, name: String) {
        val dirName = System.getenv("LMT_SHOTS") ?: return
        val dir = File(dirName).apply { mkdirs() }
        // Let thumbnails land first.
        val t = System.currentTimeMillis(); while (System.currentTimeMillis() - t < 2500) { idle(50); Thread.sleep(20) }
        val root = a.window.decorView
        val w = a.resources.displayMetrics.widthPixels; val h = a.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF0B0D12.toInt())
        root.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The real scan, end to end: the library is read, every photo's thumbnail measured, the cache
     * written, identical files found by their bytes and the rest grouped, on the real photos (with an
     * exact copy of one and a file whose picture can't be read). Stand-ins replace only what
     * Robolectric lacks: MediaStore's list and thumbnails (decoded from the files, at most 256 px like
     * the system's) and the image model (its embeddings of these photos, golden.bin).
     */
    @Test fun theScanFindsCopiesAndShotsInARealLibrary() {
        File(app.filesDir, "duplicate-cache-v3.bin").delete()
        val dir = File(app.cacheDir, "dupscan").apply { deleteRecursively(); mkdirs() }
        val emb = golden()
        val t0 = 1_700_000_000_000L
        val times = mapOf("court_1" to 0L, "court_2" to 3_000L, "bridge_1" to 600_000L, "bridge_2" to 604_000L, "cushion_1" to 1_200_000L, "cushion_2" to 1_209_000L,
            "sunset_1" to 1_800_000L, "sunset_2" to 1_820_000L, "cemetery_1" to 2_400_000L, "cemetery_2" to 2_430_000L, "tank_1" to 3_000_000L, "tank_2" to 3_025_000L,
            "clouds_a" to 3_600_000L, "clouds_b" to 3_612_000L, "night_a" to 4_200_000L, "night_b" to 4_208_000L, "document_a" to 4_800_000L, "document_b" to 4_810_000L,
            "screen_a" to 5_400_000L, "screen_b" to 5_406_000L, "copy_lowq_bridge_1" to 600_000L)
        val photos = ArrayList<GalleryPhoto>()
        fun add(file: File, id: Long, taken: Long?, album: String) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            // Some phones report a turned photo's size the wrong way round; the copy must still be found.
            val swap = file.name == "copy_lowq_bridge_1.jpg"
            val w = maxOf(if (swap) o.outHeight else o.outWidth, 0); val h = maxOf(if (swap) o.outWidth else o.outHeight, 0)
            photos.add(GalleryPhoto(id, Uri.fromFile(file), file.name, file.length(), w, h, taken, 1_000 + id, album))
        }
        for ((i, n) in emb.keys.sorted().withIndex()) {
            val f = File(dir, "$n.jpg").also { it.writeBytes(javaClass.getResourceAsStream("/duplicates/$n.jpg")!!.readBytes()) }
            add(f, 100L + i, times[n]?.let { t0 + it }, if (n.startsWith("copy")) "WhatsApp Images" else "Camera")
        }
        // A byte-for-byte copy saved by another app (no capture time), and a damaged file.
        add(File(dir, "court_1 (1).jpg").also { it.writeBytes(File(dir, "court_1.jpg").readBytes()) }, 500L, null, "Download")
        add(File(dir, "broken.jpg").also { it.writeBytes(ByteArray(3000) { k -> (k * 31).toByte() }) }, 501L, null, "Download")
        DuplicateScanner.source = { photos }
        DuplicateScanner.thumbnails = { _, p ->
            val b = BitmapFactory.decodeFile(p.uri.path) ?: throw java.io.IOException("can't decode ${p.name}")
            val s = 256f / maxOf(b.width, b.height)
            if (s < 1f) Bitmap.createScaledBitmap(b, Math.round(b.width * s), Math.round(b.height * s), true) else b
        }
        DuplicateScanner.embeddings = { p -> emb[p.name.removeSuffix(".jpg").removeSuffix(" (1)")] }
        val seen = java.util.Collections.synchronizedList(ArrayList<DupScanState>())
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { DuplicateScanner.state.collect { seen.add(it) } }
        DuplicateScanner.start(app)
        waitFor("the scan", { DuplicateScanner.state.value.toString() }) { DuplicateScanner.state.value.let { it is DupScanState.Done || it is DupScanState.Failed } }
        watcher.cancel()
        // The progress on the way: the photos looked at, then the comparing, only ever moving forward.
        val running = synchronized(seen) { seen.filterIsInstance<DupScanState.Running>() }
        val shares = running.mapNotNull { it.fraction }
        assertTrue(shares.toString(), shares.zipWithNext().all { (a, b) -> b >= a } && shares.last() >= 0.8f)
        assertTrue(running.map { it.phase }.toString(), running.any { it.phase == "Looking at your photos" } && running.any { it.phase == "Comparing" })
        assertTrue(running.map { it.done }.toString(), running.filter { it.phase == "Looking at your photos" }.map { it.done }.zipWithNext().all { (a, b) -> b >= a })
        val s = DuplicateScanner.state.value as? DupScanState.Done ?: throw AssertionError((DuplicateScanner.state.value as DupScanState.Failed).message)
        assertEquals(photos.size, s.scanned)
        assertTrue(s.aiUsed)
        assertEquals("the damaged file", 1, s.unreadable)
        val named = s.groups.map { g -> g.photos.map { it.name.removeSuffix(".jpg") }.toSet() }
        fun groupOf(n: String) = named.firstOrNull { n in it } ?: emptySet()
        // The exact copy joins its photo's group as a copy of the same picture.
        assertTrue(named.toString(), "court_1 (1)" in groupOf("court_1"))
        val court = s.groups.first { g -> g.photos.any { it.name == "court_1.jpg" } }
        assertEquals(setOf("court_1.jpg", "court_1 (1).jpg"), court.pictures.first { p -> p.any { it.name == "court_1.jpg" } }.map { it.name }.toSet())
        assertEquals(DupKind.IDENTICAL, court.kind)
        assertEquals("the damaged file is in no group", emptySet<String>(), groupOf("broken"))
        // Everything else as the grouping finds it from the full-size photos (DuplicatesTest; here
        // without the feature check, which needs OpenCV's native code): the scan's thumbnails, cache
        // and bookkeeping change nothing.
        val names = emb.keys.sorted()
        val expected = Duplicates.group(names.mapIndexed { i, n ->
            val b = BitmapFactory.decodeFile(File(dir, "$n.jpg").path)
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val (grid, contrast) = Duplicates.grid(px, b.width, b.height)
            val tiny = Bitmap.createScaledBitmap(b, 9, 8, true)
            val tp = IntArray(72); tiny.getPixels(tp, 0, 9, 0, 0, 9, 8)
            val dh = Duplicates.dhash(IntArray(72) { val c = tp[it]; (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000 })
            PhotoInfo(i, File(dir, "$n.jpg").length(), b.width, b.height, dh, emb.getValue(n), grid, contrast, 0.0, times[n]?.let { t0 + it }, null)
        }).map { g -> g.members.map { names[it] }.toSet() }.toSet()
        val found = named.map { it - "court_1 (1)" }.filter { it.size > 1 }.toSet()
        assertEquals(expected, found)
        assertTrue(expected.toString(), setOf("bridge_1", "copy_small_bridge_1", "copy_lowq_bridge_1").all { it in groupOf("bridge_1") })
        assertTrue(expected.toString(), setOf("document_a", "copy_lowq_document_a") == groupOf("document_a"))
        assertTrue("cache written", File(app.filesDir, "duplicate-cache-v3.bin").length() > 0)

        // The screen shows the result, the unreadable file and the suggested copies.
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        a.navigator.push(DuplicatesScreen(a)); idle(800)
        val t = texts(a)
        assertTrue(t.toString(), t.any { it.startsWith("${s.groups.size} groups") })
        assertTrue(t.toString(), t.any { it.startsWith("1 photo couldn't be read") })

        // A second scan uses the cache and finds the same.
        DuplicateScanner.start(app)
        waitFor("the second scan") { DuplicateScanner.state.value is DupScanState.Done }
        assertEquals(named.toSet(), (DuplicateScanner.state.value as DupScanState.Done).groups.map { g -> g.photos.map { it.name.removeSuffix(".jpg") }.toSet() }.toSet())
    }

    /**
     * While the scan runs: one bar for the whole scan that moves on (the same view, not built anew at
     * every step), how many photos have been looked at, the share done and about how long is left.
     */
    @Test fun theScanShowsHowFarItHasCome() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        DuplicateScanner.show(DupScanState.Running("Reading your photo library", null))
        a.navigator.push(DuplicatesScreen(a)); idle(800)
        fun bars() = all(a.navigator.top!!.view).filterIsInstance<ProgressBarView>()
        val bar = bars().single()
        assertTrue(bar.indeterminate)
        assertTrue(texts(a).toString(), "Reading your photo library" in texts(a))
        DuplicateScanner.show(DupScanState.Running("Looking at your photos", 0.2f, 1500, 6000, 4 * 60_000L)); idle()
        var t = texts(a)
        assertTrue(t.toString(), "Looking at your photos" in t && "1500 of 6000 photos · 20% · about 4 minutes left" in t)
        assertTrue(t.toString(), "You can use the rest of the app meanwhile; the scan keeps going." in t)
        assertSame("the same bar moves on", bar, bars().single())
        assertFalse(bar.indeterminate)
        assertEquals("20 percent", bar.contentDescription)
        // Filled in the app's accent gradient (it used to come out black, hardly visible on the dark track).
        assertTrue("laid out", bar.width > 0 && bar.height > 0)
        val drawn = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888).also { bar.draw(Canvas(it)) }
        val px = drawn.getPixel(bar.width / 20, bar.height / 2)
        assertTrue(Integer.toHexString(px), android.graphics.Color.blue(px) > 200 && android.graphics.Color.red(px) in 90..160)
        shotIfAsked(a, "duplicates_progress")
        DuplicateScanner.show(DupScanState.Running("Looking at your photos", 0.7f, 5200, 6000, 20_000L)); idle()
        t = texts(a)
        assertTrue(t.toString(), "5200 of 6000 photos · 70% · less than a minute left" in t)
        DuplicateScanner.show(DupScanState.Running("Comparing", 0.9f, 6000, 6000)); idle()
        t = texts(a)
        assertTrue(t.toString(), "Comparing" in t && "90%" in t)
        assertSame(bar, bars().single())
        assertEquals("90 percent", bar.contentDescription)
        // Stop is there all along.
        assertTrue(all(a.navigator.top!!.view).filterIsInstance<ButtonView>().any { it.label == "Stop" })
    }

    /** When no photo can be read the scan says so, instead of "no duplicates". */
    @Test fun aScanThatCanReadNothingSaysSo() {
        File(app.filesDir, "duplicate-cache-v3.bin").delete()
        val dir = File(app.cacheDir, "dupscan2").apply { deleteRecursively(); mkdirs() }
        val photos = (0 until 3).map { i ->
            val f = File(dir, "p$i.jpg").also { it.writeBytes(ByteArray(1000 + i)) }
            GalleryPhoto(900L + i, Uri.fromFile(f), f.name, f.length(), 400, 300, null, 1L, "Camera")
        }
        DuplicateScanner.source = { photos }
        DuplicateScanner.thumbnails = { _, p -> throw SecurityException("no access to ${p.name}") }
        DuplicateScanner.start(app)
        waitFor("the scan") { DuplicateScanner.state.value.let { it is DupScanState.Done || it is DupScanState.Failed } }
        val f = DuplicateScanner.state.value as? DupScanState.Failed ?: throw AssertionError("expected a failure, got ${DuplicateScanner.state.value}")
        assertTrue(f.message, f.message.contains("none of your 3 photos could be read"))
    }
}
