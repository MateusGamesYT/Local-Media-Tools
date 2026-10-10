package com.localmediatools.vision

import com.localmediatools.codec.edit.AdjustKey
import com.localmediatools.vision.core.AutoEnhance
import com.localmediatools.vision.core.Duplicates
import com.localmediatools.vision.core.DupKind
import com.localmediatools.vision.core.FaceBox
import com.localmediatools.vision.core.FaceEngine
import com.localmediatools.vision.core.FaceSample
import com.localmediatools.vision.core.FaceTrack
import com.localmediatools.vision.core.FaceTracker
import com.localmediatools.vision.core.Matting
import com.localmediatools.vision.core.PhotoInfo
import com.localmediatools.vision.core.SceneKind
import com.localmediatools.vision.core.SceneMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs

class VisionCoreTest {
    companion object {
        private val models = File("app/src/main/assets/models")
        lateinit var faces: FaceEngine

        @BeforeClass @JvmStatic fun load() {
            nu.pattern.OpenCV.loadLocally()
            faces = FaceEngine(File(models, "face_detection_yunet_2023mar.onnx").path, File(models, "face_recognition_sface_2021dec_int8.onnx").path)
        }

        fun photo(name: String): Mat {
            val bytes = VisionCoreTest::class.java.getResourceAsStream("/vision/$name")!!.readBytes()
            return Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
        }
    }

    @Test fun findsTheFaceAndRecognisesItAfterChanges() {
        val img = photo("astronaut.jpg")
        val found = faces.detect(img)
        assertEquals(1, found.size)
        val f = found[0]
        assertTrue("face box ${f.x},${f.y},${f.w},${f.h}", f.x in 150f..220f && f.y in 40f..110f && f.w in 70f..120f)
        val e1 = faces.embed(img, f)!!
        // Mirrored, smaller and brighter: same person.
        val other = Mat(); Core.flip(img, other, 1)
        Imgproc.resize(other, other, Size(img.cols() * 0.6, img.rows() * 0.6))
        other.convertTo(other, -1, 1.15, 12.0)
        val f2 = faces.detect(other).single()
        val e2 = faces.embed(other, f2)!!
        assertTrue("same person similarity ${FaceEngine.cosine(e1, e2)}", FaceEngine.cosine(e1, e2) > FaceEngine.SAME_PERSON)
    }

    @Test fun twoFacesInOneFrameStayTwoPeople() {
        val img = photo("astronaut.jpg")
        val face = Mat(img, org.opencv.core.Rect(140, 20, 180, 200))
        val canvas = Mat(260, 420, img.type(), org.opencv.core.Scalar(200.0, 200.0, 200.0))
        face.copyTo(Mat(canvas, org.opencv.core.Rect(10, 30, 180, 200)))
        face.copyTo(Mat(canvas, org.opencv.core.Rect(230, 30, 180, 200)))
        val dets = faces.detect(canvas)
        assertEquals(2, dets.size)
        // The same two faces over several frames, drifting right: two tracks, two people.
        val tracker = FaceTracker()
        for (k in 0 until 6) {
            val shift = k * 0.01f
            tracker.add(k * 100_000L, dets.map { d ->
                FaceSample(k * 100_000L, d.x / canvas.cols() + shift, d.y / canvas.rows(), d.w / canvas.cols(), d.h / canvas.rows(), d.score, faces.embed(canvas, d))
            })
        }
        assertEquals(2, tracker.tracks.size)
        val people = tracker.identities()
        assertEquals("co-occurring faces are never merged", 2, people.size)
        val regions = FaceTracker.regionsAt(people, 250_000)
        assertEquals(2, regions.size)
        assertTrue(regions.toString(), regions.all { it.rx > 0.1f && it.ry > 0.2f && it.rx < 0.3f })
        assertTrue(FaceTracker.regionsAt(people, 2_000_000).isEmpty())
    }

    @Test fun peopleAreMatchedAcrossPhotosAndVideos() {
        val img = photo("astronaut.jpg")
        val d = faces.detect(img).single()
        val feat = faces.embed(img, d)!!
        fun still(source: Int, x: Float, f: FloatArray) = FaceTrack(source, source, still = true).apply {
            samples.add(FaceSample(0, x, 0.2f, 0.2f, 0.25f, 0.7f, f))
        }
        // The same face in photo 0 and in a video (source 2); photo 1 shows it twice side by side.
        val video = FaceTracker(source = 2)
        val flipped = Mat(); Core.flip(img, flipped, 1)
        val fd = faces.detect(flipped).single()
        for (k in 0 until 4) video.add(k * 200_000L, listOf(FaceSample(k * 200_000L, 0.4f, 0.2f, 0.2f, 0.25f, fd.score, faces.embed(flipped, fd))))
        val twin = listOf(FaceTrack(10, 1, true).apply { samples.add(FaceSample(0, 0.1f, 0.2f, 0.2f, 0.25f, 0.7f, feat)) },
            FaceTrack(11, 1, true).apply { samples.add(FaceSample(0, 0.6f, 0.2f, 0.2f, 0.25f, 0.7f, feat)) })
        val people = FaceTracker.cluster(listOf(still(0, 0.3f, feat)) + twin + video.tracks)
        assertEquals("two faces in one photo stay two people", 2, people.size)
        assertTrue(people.all { p -> p.tracks.count { it.source == 1 } == 1 })
        // The video's person also contains the face from a photo.
        assertTrue(people.first { p -> p.tracks.any { it.source == 2 } }.tracks.any { it.still })
        assertEquals(4, people.sumOf { it.tracks.size })
        // A weak single detection in a video is noise; in a photo it counts.
        val noise = FaceTrack(20, 3).apply { samples.add(FaceSample(0, 0.5f, 0.5f, 0.1f, 0.1f, 0.65f, null)) }
        assertEquals(0, FaceTracker.cluster(listOf(noise)).size)
        assertEquals(1, FaceTracker.cluster(listOf(still(4, 0.5f, feat))).size)
    }

    @Test fun tracksThatLeaveAndReturnAreTheSamePerson() {
        val feat = FaceEngine.normalize(FloatArray(128) { (it % 7).toFloat() - 3f })
        val other = FaceEngine.normalize(FloatArray(128) { ((it * 5) % 11).toFloat() - 5f })
        val tracker = FaceTracker()
        // Person A for 1 s, gone for 2 s, back elsewhere for 1 s; person B overlaps the second visit.
        for (t in 0 until 10) tracker.add(t * 100_000L, listOf(FaceSample(t * 100_000L, 0.1f, 0.1f, 0.2f, 0.25f, 0.9f, feat)))
        for (t in 30 until 40) tracker.add(t * 100_000L, listOf(
            FaceSample(t * 100_000L, 0.6f, 0.5f, 0.2f, 0.25f, 0.9f, feat),
            FaceSample(t * 100_000L, 0.1f, 0.5f, 0.15f, 0.2f, 0.85f, other)))
        assertEquals(3, tracker.tracks.size)
        val people = tracker.identities()
        assertEquals(2, people.size)
        assertEquals(2, people.first { it.tracks.size == 2 }.tracks.size)
        // Interpolation between samples.
        val r = FaceTracker.regionsAt(listOf(people.first { it.tracks.size == 2 }), 50_000).single()
        assertEquals(0.2f, r.cx, 0.01f)
    }

    @Test fun mattingSeparatesSubjectFromBackground() {
        Matting(File(models, "u2netp.onnx").path).use { m ->
            val bgr = photo("coffee.jpg")
            val rgb = Mat(); Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
            val mask = m.predict(rgb)
            val s = Matting.SIZE
            // Cup centre is foreground; the table corner is background.
            assertTrue(mask[(s * 0.35).toInt() * s + (s * 0.45).toInt()] > 0.8f)
            assertTrue(mask[(s * 0.95).toInt() * s + (s * 0.03).toInt()] < 0.2f)
            // Refinement keeps that and stays in 0..1.
            val w = 120; val h = 80
            val coarse = Matting.resize(mask, s, s, w, h)
            val g = Mat(); Imgproc.cvtColor(bgr, g, Imgproc.COLOR_BGR2GRAY); Imgproc.resize(g, g, Size(w.toDouble(), h.toDouble()))
            val guide = FloatArray(w * h) { (g.get(it / w, it % w)[0] / 255.0).toFloat() }
            val a = Matting.refine(guide, coarse, w, h, 3)
            assertTrue(a.all { it in 0f..1f })
            assertTrue(a[(h * 0.35).toInt() * w + (w * 0.45).toInt()] > 0.9f)
            assertTrue(a[(h * 0.95).toInt() * w + (w * 0.03).toInt()] < 0.1f)
            val b = Matting.bounds(a, w, h)!!
            assertTrue(b[0] > 0 && b[2] <= w && b[2] - b[0] > w / 3)
        }
    }

    private fun argb(img: Mat): Triple<IntArray, Int, Int> {
        val small = Mat(); Imgproc.resize(img, small, Size(192.0, 192.0 * img.rows() / img.cols()))
        val w = small.cols(); val h = small.rows()
        val px = IntArray(w * h) { i -> val v = small.get(i / w, i % w); (0xFF shl 24) or (v[2].toInt() shl 16) or (v[1].toInt() shl 8) or v[0].toInt() }
        return Triple(px, w, h)
    }

    @Test fun autoEnhanceFixesADarkBlueishPortrait() {
        val img = photo("astronaut.jpg")
        // Make it dark and blue.
        val dark = Mat(); img.convertTo(dark, -1, 0.45, 0.0)
        val ch = ArrayList<Mat>(); Core.split(dark, ch); ch[0].convertTo(ch[0], -1, 1.35, 10.0); Core.merge(ch, dark)
        val f = faces.detect(dark).single()
        val (px, w, h) = argb(dark)
        val box = FaceBox(f.x / dark.cols(), f.y / dark.rows(), f.w / dark.cols(), f.h / dark.rows())
        val r = AutoEnhance.analyze(px, w, h, listOf(box), SceneKind.GENERAL)
        assertEquals(SceneKind.PORTRAIT, r.scene)
        assertTrue("exposure ${r.adjust[AdjustKey.EXPOSURE]}", r.adjust[AdjustKey.EXPOSURE] > 0.15f)
        assertTrue("warmth ${r.adjust[AdjustKey.WARMTH]}", r.adjust[AdjustKey.WARMTH] > 0.05f)
        assertTrue(r.notes.contains("brightened the faces"))
        // A well-exposed neutral photo needs little change.
        val (px2, w2, h2) = argb(photo("chelsea.jpg"))
        val r2 = AutoEnhance.analyze(px2, w2, h2, emptyList(), SceneKind.ANIMAL)
        assertTrue(abs(r2.adjust[AdjustKey.EXPOSURE]) < 0.25f)
        // Strength scales everything.
        val half = AutoEnhance.analyze(px, w, h, listOf(box), SceneKind.GENERAL, strength = 0.5f)
        assertEquals(r.adjust[AdjustKey.EXPOSURE] / 2, half.adjust[AdjustKey.EXPOSURE], 0.01f)
    }

    @Test fun sceneMapping() {
        assertEquals(SceneKind.FOOD, SceneMapper.fromCategories(mapOf("coffee" to 0.84f, "people" to 0.3f)).first)
        assertEquals(SceneKind.ANIMAL, SceneMapper.fromCategories(mapOf("cat" to 0.79f)).first)
        assertEquals(SceneKind.LANDSCAPE, SceneMapper.fromCategories(mapOf("beach" to 0.6f, "sea" to 0.55f)).first)
        assertEquals(SceneKind.NIGHT, SceneMapper.fromCategories(mapOf("city" to 0.7f, "night" to 0.68f)).first)
        assertEquals(SceneKind.GENERAL, SceneMapper.fromCategories(mapOf("dog" to 0.3f)).first)
    }

    @Test fun duplicateHash() {
        // dHash of a gradient.
        val grad = IntArray(72) { (it % 9) * 10 }
        assertEquals(-1L, Duplicates.dhash(grad))
        assertEquals(0, Duplicates.hamming(5L, 5L))
    }
}
