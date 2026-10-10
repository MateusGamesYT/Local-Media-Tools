package com.localmediatools.gallery

import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceClustering
import com.localmediatools.gallery.core.FaceRec
import com.localmediatools.vision.core.FaceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.Base64
import kotlin.math.max
import kotlin.math.min

/**
 * People grouping on real photos (test resources /people, credits in CREDITS.tsv): nine public
 * figures photographed by different people over the years, with caps, visors and hats, glasses and
 * sunglasses, stage make-up, strong expressions, turned and profile faces and small faces, plus the
 * other people in the pictures. Faces are found and described by the app's own engine (YuNet +
 * MobileFaceNet through OpenCV, flip-averaged, as GalleryFaces does on the phone) and grouped with the
 * shipped rules; the same faces described by SFace (the app's recogniser until 1.7.0, kept in
 * buildtools) are grouped with SFace's rules for comparison.
 */
class RealPeopleTest {
    /** A face as the gallery stores it; [label]/[tags] from the verified annotations (null: someone else). */
    class Seen(val file: String, val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val eye: Float, val yaw: Float,
               val emb: FloatArray, var label: String? = null, var tags: String = "")

    companion object {
        private val models = File("app/src/main/assets/models")
        /** Every face found in every photo. */
        val seen = ArrayList<Seen>()
        /** The same faces described by SFace (same order), for comparison. */
        val sface = ArrayList<FloatArray>()
        /** Labelled faces that were not found again, and the lowest agreement with the stored embeddings. */
        var missing = ArrayList<String>()
        var minAgreement = 1f
        var labelled = 0
        lateinit var engine: FaceEngine
        lateinit var old: org.opencv.objdetect.FaceRecognizerSF
        /** Largest pixel difference and mean difference between FaceAlign's crops and OpenCV's alignCrop. */
        var alignMax = 0; var alignMean = 0.0; var aligned = 0

        @BeforeClass @JvmStatic fun analyse() {
            if (seen.isNotEmpty()) return  // already done (other tests use these faces too)
            nu.pattern.OpenCV.loadLocally()
            engine = FaceEngine(File(models, "face_detection_yunet_2023mar.onnx").path, File(models, "face_recognition_mbf_w600k_fp16.onnx").path)
            old = org.opencv.objdetect.FaceRecognizerSF.create(File("buildtools/gallery/people/models/face_recognition_sface_2021dec_int8.onnx").path, "")
            val rows = text("faces.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
            for (file in rows.map { it[0] }.distinct()) {
                val bytes = RealPeopleTest::class.java.getResourceAsStream("/people/$file")!!.readBytes()
                val img = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
                // The photos are at most 800 px: the app's 1,600 px working copy is the photo itself.
                val w = img.cols().toFloat(); val h = img.rows().toFloat()
                val mine = ArrayList<Seen>()
                for (f in engine.detect(img, minSize = 12f)) {
                    val (eye, yaw) = FaceEngine.eyesAndYaw(f.row)
                    val e = engine.embedTta(img, f) ?: continue
                    mine.add(Seen(file, f.x / w, f.y / h, f.w / w, f.h / h, f.score, eye, yaw, e))
                    sface.add(sfaceTta(old, img, f))
                    compareAlignment(img, f)
                }
                for (r in rows.filter { it[0] == file && it[9].isNotEmpty() }) {
                    labelled++
                    val box = FloatArray(4) { r[2 + it].toFloat() }
                    val s = mine.maxByOrNull { iou(it, box) }
                    if (s == null || iou(s, box) < 0.5f) { missing.add("$file ${r[9]}"); continue }
                    s.label = r[9]; s.tags = r[10]
                    val stored = java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(r[12])).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val ref = FloatArray(stored.remaining()) { stored.get(it) }
                    minAgreement = min(minAgreement, FaceEngine.cosine(s.emb, ref))
                }
                seen.addAll(mine)
            }
        }

        private fun compareAlignment(img: org.opencv.core.Mat, f: com.localmediatools.vision.core.Face) {
            val row = org.opencv.core.Mat(1, 15, org.opencv.core.CvType.CV_32F); row.put(0, 0, f.row)
            val ref = org.opencv.core.Mat(); old.alignCrop(img, row, ref)
            val mine = engine.align(img, f)
            val a = ByteArray(112 * 112 * 3); val b = ByteArray(112 * 112 * 3); ref.get(0, 0, a); mine.get(0, 0, b)
            var sum = 0L
            for (i in a.indices) { val d = kotlin.math.abs((a[i].toInt() and 255) - (b[i].toInt() and 255)); sum += d; alignMax = max(alignMax, d) }
            alignMean = (alignMean * aligned + sum.toDouble() / a.size) / (aligned + 1); aligned++
        }

        /** SFace's embedding as the app made it until 1.7.0 (OpenCV's alignment, face and mirror averaged). */
        private fun sfaceTta(rec: org.opencv.objdetect.FaceRecognizerSF, img: org.opencv.core.Mat, f: com.localmediatools.vision.core.Face): FloatArray {
            val row = org.opencv.core.Mat(1, 15, org.opencv.core.CvType.CV_32F); row.put(0, 0, f.row)
            val al = org.opencv.core.Mat(); val fl = org.opencv.core.Mat(); val a = org.opencv.core.Mat(); val b = org.opencv.core.Mat()
            rec.alignCrop(img, row, al); rec.feature(al, a); org.opencv.core.Core.flip(al, fl, 1); rec.feature(fl, b)
            fun floats(m: org.opencv.core.Mat) = FloatArray(m.total().toInt()).also { val c = org.opencv.core.Mat(); m.convertTo(c, org.opencv.core.CvType.CV_32F); c.get(0, 0, it) }
            val x = FaceEngine.normalize(floats(a)); val y = FaceEngine.normalize(floats(b))
            return FaceEngine.normalize(FloatArray(x.size) { x[it] + y[it] })
        }

        fun text(name: String) = RealPeopleTest::class.java.getResourceAsStream("/people/$name")!!.readBytes().toString(Charsets.UTF_8)

        private fun iou(s: Seen, b: FloatArray): Float {
            val iw = max(0f, min(s.x + s.w, b[0] + b[2]) - max(s.x, b[0])); val ih = max(0f, min(s.y + s.h, b[1] + b[3]) - max(s.y, b[1]))
            val i = iw * ih
            return i / (s.w * s.h + b[2] * b[3] - i)
        }

        /** The grouping rules of 1.4.0–1.4.1 (average similarity to a group's faces), for comparison. */
        val OLD = ClusterParams(join = 0.42f, merge = 0.40f, assign = 0.40f, low = 0.50f, margin = 0.05f,
            goodScore = 0.75f, goodYaw = 0.45f, goodEye = 24f)
    }

    /** How a grouping did on the verified faces. */
    class Score(val precision: Double, val recall: Double, val mainShare: Map<String, Double>, val strangersWithSomeone: Int)

    private fun group(p: ClusterParams, emb: (Int) -> FloatArray = { seen[it].emb }): Score {
        val recs = seen.mapIndexed { i, s -> FaceRec(i.toLong(), emb(i), p.quality(s.score, s.yaw, s.eye), p.isGood(s.score, s.yaw, s.eye), usable = p.isUsable(s.score)) }
        val groupOf = IntArray(seen.size)
        val groups = FaceClustering.cluster(recs, p)
        for ((g, grp) in groups.withIndex()) for (id in grp.faces) groupOf[id.toInt()] = g
        val known = seen.indices.filter { seen[it].label != null }
        val count = known.groupingBy { seen[it].label!! }.eachCount()
        var prec = 0.0; var rec = 0.0
        for (i in known) {
            val mates = known.filter { groupOf[it] == groupOf[i] }
            val same = mates.count { seen[it].label == seen[i].label }
            prec += same.toDouble() / mates.size; rec += same.toDouble() / count.getValue(seen[i].label!!)
        }
        // Each person's main group (where most of their faces are), and the share of faces in it per condition.
        val main = count.keys.associateWith { who -> known.filter { seen[it].label == who }.groupingBy { groupOf[it] }.eachCount().maxBy { it.value }.key }
        fun share(sel: (Seen) -> Boolean): Double {
            val ii = known.filter { sel(seen[it]) }
            return ii.count { groupOf[it] == main.getValue(seen[it].label!!) }.toDouble() / ii.size
        }
        val shares = linkedMapOf(
            "headwear" to share { 'H' in it.tags }, "glasses" to share { 'G' in it.tags }, "make-up" to share { 'M' in it.tags },
            "expression" to share { 'E' in it.tags }, "frontal" to share { it.yaw < 0.25f }, "turned" to share { it.yaw >= 0.25f && it.yaw < 0.6f },
            "profile" to share { it.yaw >= 0.6f }, "small" to share { it.eye < 24f }, "all" to share { true })
        val labelledGroups = known.map { groupOf[it] }.toSet()
        val strangers = seen.indices.count { seen[it].label == null && groupOf[it] in labelledGroups }
        return Score(prec / known.size, rec / known.size, shares, strangers)
    }

    @Test fun findsEveryVerifiedFaceLikeTheFixtureSays() {
        assertEquals("not found again: $missing", 63, labelled)
        assertTrue("not found again: $missing", missing.isEmpty())
        // The embeddings stored for the Robolectric gallery came from the evaluation's pipeline in Python
        // (OpenCV 4.11; this is 4.9, phones run 4.12): the app's alignment and model give the same (0.99999).
        assertTrue("stored embeddings differ: $minAgreement", minAgreement > 0.999f)
        assertTrue("other people in the photos: ${seen.size - labelled}", seen.size - labelled >= 20)
    }

    @Test fun groupsRealPeopleDespiteHatsMakeupExpressionsAndAngles() {
        val now = group(ClusterParams.MBF)
        val sfaceNow = group(ClusterParams.SFACE) { sface[it] }
        val before = group(OLD) { sface[it] }
        fun show(name: String, x: Score) = "$name precision %.3f recall %.3f %s".format(x.precision, x.recall, x.mainShare.mapValues { "%.2f".format(it.value) })
        println("real photos, ${seen.size} faces: ${show("MobileFaceNet", now)} | ${show("SFace (1.5–1.7)", sfaceNow)} | ${show("SFace, 1.4 rules", before)}")
        // Nobody is put with someone else, and no stranger in the background joins a person.
        assertTrue("precision ${now.precision}", now.precision >= 0.97)
        assertEquals(0, now.strangersWithSomeone)
        // Most of each person's faces end up together, whatever they wear or do. Measured here (OpenCV 4.9):
        // recall 0.79, main group 0.83 of all faces; headwear and turned 1.00, glasses 0.86, frontal 0.79,
        // expression 0.78, profile 0.69, small 0.68, heavy make-up 0.56 (a few borderline faces move
        // between OpenCV builds, hence the margins). These are each person's six hardest faces: Lady
        // Gaga's six stage looks stay apart with MobileFaceNet, where SFace grouped five of them; on the
        // full set of 885 faces it keeps more of every condition together (make-up 0.88 → 0.92 and
        // 0.69 → 0.74 on the two halves, buildtools/gallery/README.md).
        assertTrue("recall ${now.recall}", now.recall >= 0.76)
        val s = now.mainShare
        assertTrue("$s", s.getValue("all") >= 0.8)
        assertTrue("$s", s.getValue("headwear") >= 0.9 && s.getValue("turned") >= 0.9 && s.getValue("glasses") >= 0.8 && s.getValue("frontal") >= 0.75)
        assertTrue("$s", s.getValue("make-up") >= 0.5 && s.getValue("expression") >= 0.7)
        assertTrue("$s", s.getValue("profile") >= 0.6 && s.getValue("small") >= 0.6)
        // At least as good as SFace with its own tuned rules (0.77 here), far better than the rules before 1.5.0.
        assertTrue("SFace recall ${sfaceNow.recall} vs ${now.recall}", now.recall >= sfaceNow.recall)
        assertTrue("1.4 recall ${before.recall} vs ${now.recall}", now.recall >= before.recall + 0.2)
    }

    @Test fun alignsFacesAsOpenCvDoes() {
        // FaceAlign replaces OpenCV's FaceRecognizerSF.alignCrop (which needs the SFace model): the same crops.
        println("alignment: $aligned faces, mean difference %.4f, largest %d".format(alignMean, alignMax))
        assertTrue("$aligned", aligned >= 80)
        assertTrue("mean $alignMean", alignMean < 0.05)
        assertTrue("largest $alignMax", alignMax <= 16)
    }

    @Test fun recognizerPassesItsSelfCheck() {
        assertTrue(engine.recognizerWorks())
        assertEquals(1f, FaceEngine.half(0x3C00), 0f); assertEquals(-2f, FaceEngine.half(0xC000), 0f)
        assertEquals(65504f, FaceEngine.half(0x7BFF), 0f); assertEquals(5.9604645e-8f, FaceEngine.half(0x0001), 1e-12f)
        assertEquals(0.333251953125f, FaceEngine.half(0x3555), 0f)
    }
}
