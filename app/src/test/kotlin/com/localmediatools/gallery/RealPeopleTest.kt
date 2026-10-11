package com.localmediatools.gallery

import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceClustering
import com.localmediatools.gallery.core.FaceRec
import com.localmediatools.vision.core.FaceEngine
import com.localmediatools.vision.core.FaceModel
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
 * other people in the pictures. Faces are found by the app's own engine (YuNet through OpenCV). The
 * Gallery describes them with ResNet-50 through TensorFlow Lite, which can't run here: those
 * embeddings come from the fixture (made by the evaluation's pipeline, buildtools/gallery/face_model.py
 * --check compares them with the app's model file), and this test checks that the app prepares faces
 * for it exactly as that pipeline does. MobileFaceNet (face blur, 1.8's Gallery) and SFace (until
 * 1.7.0, kept in buildtools) describe the same faces here through OpenCV for comparison.
 */
class RealPeopleTest {
    /** A face as the gallery stores it; [label]/[tags] from the verified annotations (null: someone else). */
    class Seen(val file: String, val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val eye: Float, val yaw: Float,
               val emb: FloatArray, val mbf: FloatArray, var label: String? = null, var tags: String = "")

    companion object {
        private val models = File("app/src/main/assets/models")
        /** Every face found in every photo. */
        val seen = ArrayList<Seen>()
        /** The same faces described by SFace (same order), for comparison. */
        val sface = ArrayList<FloatArray>()
        /** Labelled faces that were not found again; how far found faces are from their stored boxes. */
        var missing = ArrayList<String>()
        var worstBoxIou = 1f
        var labelled = 0
        /** Largest difference between the app's TensorFlow Lite input for a face and OpenCV's blob of it. */
        var inputDiff = 0f
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
                // The fixture's faces of this photo, in the detector's order (largest first), with the Gallery's embeddings.
                val stored = rows.filter { it[0] == file }.sortedBy { it[1].toInt() }
                for ((k, f) in engine.detect(img, minSize = 12f).withIndex()) {
                    val (eye, yaw) = FaceEngine.eyesAndYaw(f.row)
                    val r = stored.getOrNull(k) ?: continue
                    val box = FloatArray(4) { r[2 + it].toFloat() }
                    val s = Seen(file, f.x / w, f.y / h, f.w / w, f.h / h, f.score, eye, yaw, embedding(r[12]), engine.embedTta(img, f)!!)
                    worstBoxIou = min(worstBoxIou, iou(s, box))
                    if (r[9].isNotEmpty()) { s.label = r[9]; s.tags = r[10] }
                    mine.add(s)
                    sface.add(sfaceTta(old, img, f))
                    compareAlignment(img, f)
                    engine.align(img, f).let { compareInput(it); it.release() }
                }
                for (r in stored.filter { it[9].isNotEmpty() }) {
                    labelled++
                    if (mine.none { it.label == r[9] && it.file == file }) missing.add("$file ${r[9]}")
                }
                seen.addAll(mine)
            }
        }

        private fun embedding(b64: String): FloatArray {
            val fb = java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()) { fb.get(it) }
        }

        /** FaceInput.nhwc (what the app feeds TensorFlow Lite) against OpenCV's blob of the same face (NCHW, RGB, −1…1). */
        private fun compareInput(aligned: org.opencv.core.Mat) {
            val mine = com.localmediatools.vision.core.FaceInput.nhwc(aligned)
            val blob = org.opencv.dnn.Dnn.blobFromImage(aligned, 1.0 / 127.5, org.opencv.core.Size(112.0, 112.0), org.opencv.core.Scalar(127.5, 127.5, 127.5), true, false)
            val ref = FloatArray(3 * 112 * 112); blob.reshape(1, 1).get(0, 0, ref)
            for (c in 0 until 3) for (i in 0 until 112 * 112) inputDiff = max(inputDiff, kotlin.math.abs(mine[i * 3 + c] - ref[c * 112 * 112 + i]))
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
        // Every face the fixture holds is found again where it says (OpenCV 4.9 here, 4.11 for the fixture).
        assertTrue("boxes moved: $worstBoxIou", worstBoxIou > 0.95f)
        assertEquals(text("faces.tsv").lines().count { it.isNotBlank() && !it.startsWith("#") }, seen.size)
        assertTrue("other people in the photos: ${seen.size - labelled}", seen.size - labelled >= 20)
    }

    @Test fun preparesFacesForTensorFlowLiteAsTheEvaluationDid() {
        // RGB, (x − 127.5) / 127.5, in the right places: what OpenCV's blob holds for the same face.
        assertEquals(88, aligned)
        assertEquals(0f, inputDiff, 1e-6f)
        val r50 = FaceModel.R50.expected
        assertEquals(512, r50.size); assertEquals(1f, FaceEngine.cosine(r50, r50), 1e-4f)
    }

    @Test fun groupsRealPeopleDespiteHatsMakeupExpressionsAndAngles() {
        val now = group(ClusterParams.R50)
        val mbf = group(ClusterParams.MBF) { seen[it].mbf }
        val sfaceNow = group(ClusterParams.SFACE) { sface[it] }
        val before = group(OLD) { sface[it] }
        fun show(name: String, x: Score) = "$name precision %.3f recall %.3f %s".format(x.precision, x.recall, x.mainShare.mapValues { "%.2f".format(it.value) })
        println("real photos, ${seen.size} faces: ${show("ResNet-50", now)} | ${show("MobileFaceNet (1.8)", mbf)} | ${show("SFace (1.5–1.7)", sfaceNow)} | ${show("SFace, 1.4 rules", before)}")
        // Nobody is put with someone else, and no stranger in the background joins a person.
        assertTrue("precision ${now.precision}", now.precision >= 0.97)
        assertEquals(0, now.strangersWithSomeone)
        // Most of each person's faces end up together, whatever they wear or do. Measured here: recall
        // 0.83, main group 0.86 of all faces; headwear and turned 1.00, glasses 0.86, expression 0.85,
        // frontal 0.83, profile 0.75, small 0.74, heavy make-up 0.63 (each person's six hardest faces:
        // Lady Gaga's stage looks are the hardest; on the full set of 885 faces ResNet-50 keeps more of
        // every condition together, buildtools/gallery/README.md). Margins: a few borderline faces move
        // between OpenCV builds.
        assertTrue("recall ${now.recall}", now.recall >= 0.80)
        val s = now.mainShare
        assertTrue("$s", s.getValue("all") >= 0.83)
        assertTrue("$s", s.getValue("headwear") >= 0.9 && s.getValue("turned") >= 0.9 && s.getValue("glasses") >= 0.8 && s.getValue("frontal") >= 0.8)
        assertTrue("$s", s.getValue("make-up") >= 0.55 && s.getValue("expression") >= 0.8)
        assertTrue("$s", s.getValue("profile") >= 0.7 && s.getValue("small") >= 0.7)
        // Better than MobileFaceNet (0.79 here) and SFace (0.77) with their own tuned rules, far better
        // than the rules before 1.5.0.
        assertTrue("MobileFaceNet recall ${mbf.recall} vs ${now.recall}", now.recall >= mbf.recall + 0.02)
        assertTrue("SFace recall ${sfaceNow.recall} vs ${now.recall}", now.recall >= sfaceNow.recall + 0.03)
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
        assertEquals(1f, FaceModel.half(0x3C00), 0f); assertEquals(-2f, FaceModel.half(0xC000), 0f)
        assertEquals(65504f, FaceModel.half(0x7BFF), 0f); assertEquals(5.9604645e-8f, FaceModel.half(0x0001), 1e-12f)
        assertEquals(0.333251953125f, FaceModel.half(0x3555), 0f)
    }
}
