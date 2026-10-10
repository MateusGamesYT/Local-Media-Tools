package com.localmediatools.highlight

import com.localmediatools.gallery.RealPeopleTest
import com.localmediatools.highlight.core.Aspect
import com.localmediatools.highlight.core.CaptureTime
import com.localmediatools.highlight.core.FaceBox
import com.localmediatools.highlight.core.Frame
import com.localmediatools.highlight.core.Mixer
import com.localmediatools.highlight.core.Plan
import com.localmediatools.highlight.core.PlanOptions
import com.localmediatools.highlight.core.Planner
import com.localmediatools.highlight.core.Shot
import com.localmediatools.highlight.core.ShotKind
import com.localmediatools.highlight.core.Timeline
import com.localmediatools.highlight.core.VideoWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.TimeZone
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * The highlight video's planning: capture times from photos and videos, moments and their names,
 * shot choice, the timeline on the beat, framing that keeps real people's faces in view (test
 * resources /people), and the sound mix with the music stepped back under the clips' own sound.
 */
class HighlightTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private fun utc(s: String) = java.time.Instant.parse(s).toEpochMilli()

    // ------------------------------------------------------------------ capture times
    @Test fun photoAndVideoTimesLineUp() {
        // A photo says 12:15:30 local time (no zone in the file); a video taken at the same moment says 10:15:30 UTC.
        val photo = CaptureTime.exif("2024:07:14 12:15:30", zone = paris)
        val video = CaptureTime.video("20240714T101530.000Z")
        assertEquals(utc("2024-07-14T10:15:30Z"), photo)
        assertEquals(photo, video)
        // With the zone written in the file (OffsetTimeOriginal), the phone's zone doesn't matter.
        assertEquals(utc("2024-07-14T10:15:30.120Z"), CaptureTime.exif("2024:07:14 12:15:30", "12", "+02:00", TimeZone.getTimeZone("Asia/Tokyo")))
        assertEquals(utc("2024-07-14T17:45:30Z"), CaptureTime.exif("2024:07:14 12:15:30", offset = "-05:30"))
        assertEquals(utc("2024-07-14T10:15:30Z"), CaptureTime.video("20240714T121530.000+0200"))
        assertEquals(utc("2024-07-14T10:15:30Z"), CaptureTime.video("20240714T101530Z"))
        // Placeholders and unset times are unknown, not 1904 or year 0.
        assertNull(CaptureTime.exif("0000:00:00 00:00:00"))
        assertNull(CaptureTime.exif("    "))
        assertNull(CaptureTime.video("19040101T000000.000Z"))
        assertNull(CaptureTime.video("garbage"))
        assertEquals(5L, CaptureTime.best(null, 0, 5))
        assertEquals(7L, CaptureTime.best(null, 7, 5))
        assertEquals(3L, CaptureTime.best(3, 7, 5))
        assertEquals(-19_800_000, CaptureTime.parseOffset("-0530"))
        assertEquals(7_200_000, CaptureTime.parseOffset("+02"))
    }

    // ------------------------------------------------------------------ a day out
    private var nextId = 0
    private fun photo(at: String, vararg tags: Pair<String, Float>, faces: Int = 0, sharp: Double = 100.0, features: FloatArray? = null, fav: Boolean = false) =
        Shot(nextId++, ShotKind.PHOTO, utc(at), 4000, 3000, sharpness = sharp, tags = tags.toMap(),
            faces = List(faces) { FaceBox(0.2f + 0.2f * it, 0.3f, 0.1f, 0.13f) }, features = features, favorite = fav)

    private fun video(at: String, seconds: Int, vararg tags: Pair<String, Float>, loud: Float = 0.4f, features: FloatArray? = null) =
        Shot(nextId++, ShotKind.VIDEO, utc(at), 1920, 1080, durationMs = seconds * 1000L, sharpness = 100.0, tags = tags.toMap(), hasAudio = true, features = features,
            windows = (0 until seconds).map { s ->
                // Something happens in the middle of the video (people come into the picture, wave and laugh).
                val busy = s in seconds / 2 - 2..seconds / 2 + 1
                VideoWindow(s * 1000L, s * 1000L + 1000, if (busy) 0.8f else 0.1f, 100.0, if (busy) 2 else 0, if (busy) 0.05f else 0f, if (busy) loud else 0.05f)
            })

    private fun scene(k: Int) = FloatArray(16) { if (it == k) 1f else 0.05f }.also { com.localmediatools.vision.core.FaceEngine.normalize(it) }

    /** A summer day out on the coast (times UTC; the phone in Paris, UTC+2). */
    private fun dayOut(): List<Shot> {
        nextId = 0
        val s = ArrayList<Shot>()
        // 08:10 local: setting off by car.
        s += photo("2024-07-14T06:10:00Z", "car" to 0.8f, faces = 2, features = scene(0))
        s += photo("2024-07-14T06:12:30Z", "car" to 0.7f, "street" to 0.4f, features = scene(0))
        s += photo("2024-07-14T06:14:00Z", "car" to 0.6f, faces = 1, features = scene(0))
        // 12:30: lunch, a burst of five of the same plate, one blurry.
        s += photo("2024-07-14T10:30:00Z", "food" to 0.9f, "wine" to 0.6f, faces = 3, features = scene(1))
        for (k in 0 until 5) s += photo("2024-07-14T10:34:0${k}Z", "food" to 0.8f, features = scene(2), sharp = if (k == 2) 160.0 else 90.0)
        s += photo("2024-07-14T10:41:00Z", "food" to 0.6f, faces = 4, features = scene(1), sharp = 5.0)
        s += photo("2024-07-14T10:52:00Z", "dessert" to 0.7f, "food" to 0.7f, features = scene(1))
        // 14:00–16:00: the beach, photos now and then (up to 40 minutes apart) and two videos.
        for ((k, at) in listOf("12:02", "12:09", "12:15", "12:31", "12:48", "13:05", "13:45", "14:01").withIndex())
            s += photo("2024-07-14T$at:00Z", "beach" to 0.9f, "sea" to 0.8f, faces = k % 3, features = scene(3), fav = k == 5)
        s += video("2024-07-14T12:40:00Z", 14, "beach" to 0.8f, features = scene(3))
        s += video("2024-07-14T13:25:00Z", 9, "sea" to 0.7f, loud = 0.6f, features = scene(3))
        // 21:10: sunset.
        for (k in 0 until 3) s += photo("2024-07-14T19:1${k}:00Z", "sunset" to 0.9f, "sky" to 0.8f, features = scene(4))
        // 22:00: dinner.
        s += photo("2024-07-14T20:00:00Z", "food" to 0.8f, faces = 5, features = scene(5))
        s += photo("2024-07-14T20:05:00Z", "pizza" to 0.9f, features = scene(5))
        s += photo("2024-07-14T20:31:00Z", "drink" to 0.7f, faces = 2, features = scene(5))
        return s.shuffled(java.util.Random(4))  // the order they were picked in doesn't matter
    }

    @Test fun findsTheMomentsOfTheDayAndNamesThem() {
        val m = Planner.moments(dayOut(), paris)
        assertEquals(listOf("Setting off", "Lunch", "At the beach", "Sunset", "Dinner"), m.map { it.label })
        assertEquals(listOf(3, 8, 10, 3, 3), m.map { it.shots.size })
        // Chronological, within and between moments.
        val all = m.flatMap { it.shots }
        assertEquals(all.sortedBy { it.takenMs }, all)
        // Two days: each day's first moment says which day.
        nextId = 100
        val two = dayOut() + listOf(photo("2024-07-15T07:00:00Z", "coffee" to 0.8f, features = scene(6)), photo("2024-07-15T07:05:00Z", "food" to 0.8f, faces = 2, features = scene(6)))
        val labels = Planner.moments(two, paris).map { it.label }
        assertEquals("Day 1 · Setting off", labels.first())
        assertEquals("Day 2 · Breakfast", labels.last())
        assertEquals("Monday, 15 July 2024", Planner.title(two.filter { it.takenMs > utc("2024-07-15T00:00:00Z") }, paris))
        assertEquals("14–15 July 2024", Planner.title(two, paris))
    }

    @Test fun plansAChronologicalEditOnTheBeat() {
        val shots = dayOut()
        val beat = 0.5
        val blurry = shots.first { it.sharpness == 5.0 }
        val burst = shots.filter { it.tags["food"] == 0.8f && it.faces.isEmpty() && it.takenMs < utc("2024-07-14T10:40:00Z") }
        assertEquals(5, burst.size)
        val unwanted = shots.first { it.tags.containsKey("pizza") }
        val wanted = shots.first { it.tags["sunset"] != null }
        val plan = Planner.plan(shots, PlanOptions(seconds = 30.0, beatSeconds = beat, zone = paris, exclude = setOf(unwanted.id), include = setOf(wanted.id)))
        val clips = plan.clips
        // About as long as asked, in time order, every moment in it.
        assertTrue("${plan.durationMs}", abs(plan.durationMs - 30_000) <= 3_000)
        assertEquals(clips.sortedBy { it.shot.takenMs }.map { it.shot.id }, clips.map { it.shot.id })
        assertEquals((0 until 5).toSet(), clips.map { it.moment }.toSet())
        // Cuts on the beat (cross-fades are one beat long), no gaps.
        for ((i, c) in clips.withIndex()) {
            assertEquals(0L, c.startMs % 500)
            if (i > 0) assertEquals(clips[i - 1].endMs, c.startMs + c.fadeInMs)
            if (i > 0) assertEquals(if (c.moment != clips[i - 1].moment) 500L else 0L, c.fadeInMs)
        }
        // The user's choices hold; the burst counts once (its sharpest shot); the blurry photo isn't used.
        assertTrue(clips.none { it.shot === unwanted })
        assertTrue(clips.any { it.shot === wanted })
        assertTrue(clips.count { it.shot in burst } <= 1)
        clips.firstOrNull { it.shot in burst }?.let { assertEquals(160.0, it.shot.sharpness, 0.0) }
        assertTrue(clips.none { it.shot === blurry })
        // Videos show their lively middle with their own sound; the music steps back meanwhile.
        val v = clips.filter { it.shot.kind == ShotKind.VIDEO }
        assertTrue(v.isNotEmpty())
        for (c in v) {
            val mid = c.shot.durationMs / 2
            assertTrue("${c.sourceStartMs}..${c.sourceEndMs}", c.sourceStartMs <= mid && c.sourceEndMs >= mid - 1000)
            assertTrue(c.withSound)
        }
        assertEquals(v.size, plan.soundIntervals().size)
        // Captions: the title first, then each moment's name as it starts.
        assertEquals("Sunday, 14 July 2024", plan.captions[0].text)
        assertTrue(plan.captions[0].sub!!.startsWith("Setting off · Lunch"))
        assertEquals(listOf("Lunch", "At the beach", "Sunset", "Dinner"), plan.captions.drop(1).map { it.text })
        // Without captions or clip sound.
        val quiet = Planner.plan(shots, PlanOptions(seconds = 20.0, captions = false, clipSound = false, zone = paris))
        assertTrue(quiet.captions.isEmpty() && quiet.soundIntervals().isEmpty())
        // Automatic length: enough for the best of each moment, within 15–90 s.
        val auto = Planner.plan(shots, PlanOptions(zone = paris))
        assertTrue("${auto.durationMs}", auto.durationMs in 15_000..90_000)
    }

    // ------------------------------------------------------------------ framing real faces
    @Test fun slowZoomKeepsRealPeoplesFacesInView() {
        RealPeopleTest.analyse()
        val byFile = RealPeopleTest.seen.groupBy { it.file }
        var zoomed = 0; var checked = 0
        for ((file, faces) in byFile) {
            val img = ImageIO.read(javaClass.getResourceAsStream("/people/$file")!!)
            val boxes = faces.map { FaceBox(it.x, it.y, it.w, it.h).clipped() }
            val shot = Shot(0, ShotKind.PHOTO, 0, img.width, img.height, faces = boxes)
            val (fx, fy) = Planner.faceFocus(shot)!!
            assertTrue(fx >= boxes.minOf { it.x } && fx <= boxes.maxOf { it.x + it.w })
            assertTrue(fy >= boxes.minOf { it.y } && fy <= boxes.maxOf { it.y + it.h })
            for (aspect in Aspect.values()) for (n in 0..1) {
                val (from, to) = Planner.kenBurns(shot, aspect.ratio, n)
                for (f in listOf(from, to)) {
                    // The output's shape, inside the picture.
                    assertEquals("$file $aspect", aspect.ratio, f.w * img.width / (f.h * img.height), 0.01f)
                    assertTrue(f.x >= -1e-4f && f.y >= -1e-4f && f.x + f.w <= 1.0001f && f.y + f.h <= 1.0001f)
                }
                val (wide, close) = if (from.w >= to.w) from to to else to to from
                // Every face shown in the wide view stays in the close one.
                for (b in boxes) if (wide.contains(b)) assertTrue("$file $aspect face cut off", close.contains(b))
                // One person: their face is in the picture whenever it fits.
                if (boxes.size == 1 && boxes[0].w <= wide.w && boxes[0].h <= wide.h) assertTrue("$file $aspect", wide.contains(boxes[0]))
                checked++
                if (close.w < wide.w * 0.99f) zoomed++
            }
        }
        // Most pictures still get some movement.
        assertTrue("$zoomed of $checked", checked >= 300 && zoomed >= checked / 2)
        println("highlight: Ken Burns zoom on $zoomed of $checked framings of ${byFile.size} real photos")
    }

    // ------------------------------------------------------------------ frames
    @Test fun eachFrameShowsTheRightPicturesAndFades() {
        val shots = dayOut()
        val plan = Planner.plan(shots, PlanOptions(seconds = 30.0, zone = paris))
        val clips = plan.clips
        // The start fades in from black; the end fades out.
        assertEquals(0f, Timeline.at(plan, 0.0).layers.single().alpha, 0f)
        assertEquals(1f, Timeline.at(plan, 400.0).layers.single().alpha, 0f)
        assertEquals(0f, Timeline.at(plan, 0.0).fade, 0f)
        assertEquals(1f, Timeline.at(plan, plan.durationMs - 0.001).fade, 0.01f)
        // A cross-fade between moments: both pictures, the new one on top, half way through.
        val c = clips.first { it.fadeInMs > 0 && it.startMs > 0 }
        val mid = Timeline.at(plan, c.startMs + c.fadeInMs / 2.0)
        assertEquals(2, mid.layers.size)
        assertEquals(clips.indexOf(c), mid.layers.last().clip)
        assertEquals(0.5f, mid.layers.last().alpha, 0.01f)
        // A cut within a moment: one picture at a time.
        val cut = clips.zipWithNext().first { (a, b) -> a.moment == b.moment }.second
        assertEquals(1, Timeline.at(plan, cut.startMs + 1.0).layers.size)
        // Videos play their chosen stretch in step with the timeline.
        val v = clips.first { it.shot.kind == ShotKind.VIDEO }
        assertEquals(v.sourceStartMs + 1000, Timeline.at(plan, v.startMs + 1000.0).layers.last().sourceMs)
        // Captions fade in and out.
        val title = plan.captions[0]
        assertEquals(0f, Timeline.at(plan, title.startMs.toDouble()).captions.single().alpha, 0f)
        assertEquals(1f, Timeline.at(plan, title.startMs + 1000.0).captions.single().alpha, 0f)
        assertTrue(Timeline.at(plan, title.endMs + 1.0).captions.none { it.caption == 0 })
        assertEquals(Math.ceil(plan.durationMs * 30 / 1000.0).toInt(), Timeline.frameCount(plan))
        // Landscape photos in a portrait video are shown whole over a blurred copy.
        val tall = Planner.plan(shots, PlanOptions(seconds = 20.0, zone = paris, aspect = Aspect.PORTRAIT))
        assertTrue(tall.clips.all { it.fit })
        val l = Timeline.at(tall, tall.clips[1].startMs + 10.0).layers.last()
        assertTrue(l.backdrop)
        assertEquals(1f, l.dst.w, 1e-4f)
        assertEquals(0.5625f / (4f / 3f), l.dst.h, 0.001f)
        assertEquals(Aspect.LANDSCAPE, Planner.aspectFor(shots))
    }

    @Test fun textureCoordinatesForPhotosAndRotatedVideos() {
        fun near(e: Pair<Float, Float>, a: Pair<Float, Float>) { assertEquals(e.first, a.first, 1e-5f); assertEquals(e.second, a.second, 1e-5f) }
        val src = Frame(0.1f, 0.2f, 0.5f, 0.4f)
        // Bitmaps: the quad's top-left (0, 1) samples the top-left of the shown part.
        val b = Timeline.bitmapMatrix(src)
        near(0.1f to 0.2f, Timeline.apply(b, 0f, 1f))
        near(0.6f to 0.6f, Timeline.apply(b, 1f, 0f))
        // Videos: the displayed top-left corner, for each way the phone was held.
        // Stored with 90°: the stored picture's bottom-left is shown at the top-left (texture origin at the bottom: (0, 0)).
        val whole = Frame(0f, 0f, 1f, 1f)
        near(0f to 1f, Timeline.apply(Timeline.videoMatrix(whole, 0, Timeline.IDENTITY), 0f, 1f))
        near(0f to 0f, Timeline.apply(Timeline.videoMatrix(whole, 90, Timeline.IDENTITY), 0f, 1f))
        near(1f to 0f, Timeline.apply(Timeline.videoMatrix(whole, 180, Timeline.IDENTITY), 0f, 1f))
        near(1f to 1f, Timeline.apply(Timeline.videoMatrix(whole, 270, Timeline.IDENTITY), 0f, 1f))
        // And the displayed top-right with 90°: the stored top-left.
        near(0f to 1f, Timeline.apply(Timeline.videoMatrix(whole, 90, Timeline.IDENTITY), 1f, 1f))
        // The SurfaceTexture's transform is applied last (here: a vertical flip some decoders report).
        val flipST = Timeline.affine(1f, 0f, 0f, 0f, -1f, 1f)
        near(0f to 0f, Timeline.apply(Timeline.videoMatrix(whole, 0, flipST), 0f, 1f))
        near(0.5f to 0.5f, Timeline.apply(Timeline.mul(Timeline.IDENTITY, Timeline.affine(0.5f, 0f, 0f, 0f, 0.5f, 0f)), 1f, 1f))
    }

    /**
     * Real photos of real people through the whole chain — planner, timeline and a reference drawing
     * of each frame — in all three shapes: faces stay in the picture on every frame. With LMT_SHOTS
     * set, contact sheets of the frames are written there for a look.
     */
    @Test fun realPhotosOfPeopleStayInFrame() {
        RealPeopleTest.analyse()
        val files = RealPeopleTest.seen.groupBy { it.file }.entries.sortedBy { it.key }.take(24)
        val images = HashMap<String, BufferedImage>()
        val shots = files.mapIndexed { i, (file, faces) ->
            val img = ImageIO.read(javaClass.getResourceAsStream("/people/$file")!!).also { images[file] = it }
            // Three outings an afternoon apart, a photo every couple of minutes.
            val t = utc("2024-05-04T09:00:00Z") + (i / 8) * 4 * 3_600_000L + (i % 8) * 130_000L
            Shot(i, ShotKind.PHOTO, t, img.width, img.height, sharpness = 100.0, faces = faces.map { FaceBox(it.x, it.y, it.w, it.h) })
        }
        val dir = System.getenv("LMT_SHOTS")?.let { File(it).apply { mkdirs() } }
        var checked = 0
        for (aspect in Aspect.values()) {
            val plan = Planner.plan(shots, PlanOptions(seconds = 40.0, zone = paris, aspect = aspect))
            assertEquals(3, plan.moments.size)
            for (c in plan.clips) {
                val biggest = c.shot.faces.map { it.clipped() }.maxBy { it.area }
                for (k in 0..4) {
                    val t = c.startMs + (c.durationMs - 1) * k / 4.0
                    val l = Timeline.at(plan, t).layers.first { plan.clips[it.clip] === c }
                    // The biggest face, where it lands in the output.
                    val fx0 = l.dst.x + (biggest.x - l.src.x) / l.src.w * l.dst.w; val fx1 = l.dst.x + (biggest.x + biggest.w - l.src.x) / l.src.w * l.dst.w
                    val fy0 = l.dst.y + (biggest.y - l.src.y) / l.src.h * l.dst.h; val fy1 = l.dst.y + (biggest.y + biggest.h - l.src.y) / l.src.h * l.dst.h
                    val fits = biggest.w * c.shot.width <= l.src.w * c.shot.width / l.dst.w + 1 && biggest.h <= l.src.h
                    val cover = Planner.cover(c.shot, aspect.ratio, 1f, Planner.faceFocus(c.shot))
                    if (fits && cover.contains(biggest)) {
                        assertTrue("${files[c.shot.id].key} $aspect at $t: face at $fx0..$fx1 × $fy0..$fy1",
                            fx0 >= -1e-3f && fx1 <= 1.001f && fy0 >= -1e-3f && fy1 <= 1.001f)
                        checked++
                    }
                }
            }
            if (dir != null) sheet(plan, shots.associate { it.id to images.getValue(files[it.id].key) }, File(dir, "highlight_${aspect.name.lowercase()}.png"))
        }
        assertTrue("$checked", checked > 100)
    }

    /** Draws frames of [plan] the way the renderer does (an independent drawing with Java 2D) into a contact sheet. */
    private fun sheet(plan: Plan, images: Map<Int, BufferedImage>, out: File) {
        val w = if (plan.aspect.width >= plan.aspect.height) 320 else 180
        val h = (w / plan.aspect.ratio).toInt()
        val n = 24; val cols = 6
        val sheet = BufferedImage(cols * (w + 6), (n + cols - 1) / cols * (h + 6), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = Color(30, 30, 30); g.fillRect(0, 0, sheet.width, sheet.height)
        for (k in 0 until n) {
            val t = plan.durationMs * (k + 0.5) / n
            val f = Timeline.at(plan, t)
            val frame = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val fg = frame.createGraphics()
            fg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            for (l in f.layers) {
                val img = images.getValue(plan.clips[l.clip].shot.id)
                fg.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, l.alpha)
                if (l.backdrop) {
                    val b = l.backdropSrc
                    val tiny = BufferedImage(24, (24 / plan.aspect.ratio).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
                    tiny.createGraphics().drawImage(img, 0, 0, tiny.width, tiny.height, (b.x * img.width).toInt(), (b.y * img.height).toInt(), ((b.x + b.w) * img.width).toInt(), ((b.y + b.h) * img.height).toInt(), null)
                    fg.drawImage(tiny, 0, 0, w, h, null)
                    fg.color = Color(0, 0, 0, (0.45f * 255 * l.alpha).toInt()); fg.fillRect(0, 0, w, h)
                }
                val s = l.src; val d = l.dst
                fg.drawImage(img, (d.x * w).toInt(), (d.y * h).toInt(), ((d.x + d.w) * w).toInt(), ((d.y + d.h) * h).toInt(),
                    (s.x * img.width).toInt(), (s.y * img.height).toInt(), ((s.x + s.w) * img.width).toInt(), ((s.y + s.h) * img.height).toInt(), null)
            }
            fg.composite = AlphaComposite.SrcOver
            for (c in f.captions) {
                val cap = plan.captions[c.caption]
                fg.color = Color(1f, 1f, 1f, c.alpha)
                fg.font = fg.font.deriveFont(if (cap.title) 13f else 10f)
                fg.drawString(cap.text, if (cap.title) 10 else 8, if (cap.title) h / 2 else h - 10)
            }
            if (f.fade > 0) { fg.color = Color(0f, 0f, 0f, f.fade); fg.fillRect(0, 0, w, h) }
            g.drawImage(frame, (k % cols) * (w + 6) + 3, (k / cols) * (h + 6) + 3, null)
        }
        ImageIO.write(sheet, "png", out)
    }

    // ------------------------------------------------------------------ sound
    @Test fun musicStepsBackWhileClipsSpeak() {
        val merged = Mixer.merge(listOf(longArrayOf(5000, 8000), longArrayOf(1000, 3000), longArrayOf(3500, 4500), longArrayOf(12000, 13000)))
        assertEquals(listOf(listOf(1000L, 8000L), listOf(12000L, 13000L)), merged.map { it.toList() })
        assertEquals(1f, Mixer.duckGain(merged, 500.0), 0f)
        assertEquals(Mixer.DUCK, Mixer.duckGain(merged, 1000.0), 1e-6f)
        assertEquals(Mixer.DUCK, Mixer.duckGain(merged, 7999.0), 1e-6f)
        assertEquals(1f, Mixer.duckGain(merged, 8000.0 + Mixer.UP_MS), 1e-6f)
        // Smooth both ways.
        var last = 2f
        for (t in 700..1000 step 10) { val g = Mixer.duckGain(merged, t.toDouble()); assertTrue(g <= last + 1e-6f); last = g }
        for (t in 8000..8400 step 10) { val g = Mixer.duckGain(merged, t.toDouble()); assertTrue(g >= last - 1e-6f); last = g }

        val rate = 8000; val frames = 4 * rate
        val music = FloatArray(frames * 2) { 0.5f * kotlin.math.sin(it / 2 * 2 * Math.PI * 220 / rate).toFloat() }
        val voice = FloatArray(rate * 2) { 0.05f * kotlin.math.sin(it / 2 * 2 * Math.PI * 440 / rate).toFloat() }
        val gain = Mixer.levelGain(voice)
        assertTrue("$gain", gain > 2f)  // a quiet clip is brought up
        val out = Mixer.mix(music, 1f, frames, rate, listOf(Mixer.Part(rate, voice, gain)), listOf(longArrayOf(1000, 2000)), fadeInMs = 0, fadeOutMs = 500)
        fun rms(a: Int, b: Int): Double { var s = 0.0; for (i in a * 2 until b * 2) s += out[i] * out[i]; return Math.sqrt(s / ((b - a) * 2)) }
        val before = rms(rate / 4, rate * 3 / 4); val during = rms(rate * 5 / 4, rate * 7 / 4); val after = rms(rate * 5 / 2, rate * 3)
        assertEquals(0.5 / Math.sqrt(2.0), before, 0.01)
        assertEquals(before, after, 0.01)
        // During the clip: the music at 22 %, the clip's sound on top.
        val musicDuring = 0.5 * Mixer.DUCK / Math.sqrt(2.0); val voiceLevel = 0.05 * gain / Math.sqrt(2.0)
        assertEquals(Math.sqrt(musicDuring * musicDuring + voiceLevel * voiceLevel), during, 0.01)
        // Fades out to nothing; nothing reaches full scale even when everything is loud.
        assertTrue(abs(out[out.size - 1]) < 0.01f)
        val loud = Mixer.mix(FloatArray(800) { 0.9f }, 1f, 400, rate, listOf(Mixer.Part(0, FloatArray(800) { 0.9f })), emptyList(), fadeOutMs = 0)
        assertTrue(loud.all { it < 1f && it > 0.95f } || loud.drop(2 * 60).all { it < 1f })
        val pcm = Mixer.pcm16(floatArrayOf(1f, -1f, 0f, 0.5f))
        assertEquals(listOf(0xFF, 0x7F, 0x01, 0x80, 0, 0, 0xFF, 0x3F), pcm.map { it.toInt() and 0xFF })
    }
}
