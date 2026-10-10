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
 * other people in the pictures. Faces are found and described by the app's own engine (YuNet + SFace
 * through OpenCV, flip-averaged, as GalleryFaces does on the phone) and grouped with the shipped rules.
 */
class RealPeopleTest {
    /** A face as the gallery stores it; [label]/[tags] from the verified annotations (null: someone else). */
    class Seen(val file: String, val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val eye: Float, val yaw: Float,
               val emb: FloatArray, var label: String? = null, var tags: String = "")

    companion object {
        private val models = File("app/src/main/assets/models")
        /** Every face found in every photo. */
        val seen = ArrayList<Seen>()
        /** Labelled faces that were not found again, and the lowest agreement with the stored embeddings. */
        var missing = ArrayList<String>()
        var minAgreement = 1f
        var labelled = 0

        @BeforeClass @JvmStatic fun analyse() {
            if (seen.isNotEmpty()) return  // already done (other tests use these faces too)
            nu.pattern.OpenCV.loadLocally()
            val engine = FaceEngine(File(models, "face_detection_yunet_2023mar.onnx").path, File(models, "face_recognition_sface_2021dec_int8.onnx").path)
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
                }
                for (r in rows.filter { it[0] == file && it[9].isNotEmpty() }) {
                    labelled++
                    val box = FloatArray(4) { r[2 + it].toFloat() }
                    val s = mine.maxByOrNull { iou(it, box) }
                    if (s == null || iou(s, box) < 0.5f) { missing.add("$file ${r[9]}"); continue }
                    s.label = r[9]; s.tags = r[10]
                    val stored = java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(r[12])).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val ref = FloatArray(128) { stored.get(it) }
                    minAgreement = min(minAgreement, FaceEngine.cosine(s.emb, ref))
                }
                seen.addAll(mine)
            }
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

    private fun group(p: ClusterParams): Score {
        val recs = seen.mapIndexed { i, s -> FaceRec(i.toLong(), s.emb, p.quality(s.score, s.yaw, s.eye), p.isGood(s.score, s.yaw, s.eye), usable = p.isUsable(s.score)) }
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
        // The embeddings stored for the Robolectric gallery came from the same models through OpenCV for
        // Python (4.11; this is 4.9, phones run 4.12): builds differ slightly in decoding and arithmetic.
        assertTrue("stored embeddings differ: $minAgreement", minAgreement > 0.95f)
        assertTrue("other people in the photos: ${seen.size - labelled}", seen.size - labelled >= 20)
    }

    @Test fun groupsRealPeopleDespiteHatsMakeupExpressionsAndAngles() {
        val now = group(ClusterParams.SFACE)
        val before = group(OLD)
        println("real photos, ${seen.size} faces: shipped rules precision %.3f recall %.3f %s | 1.4 rules precision %.3f recall %.3f %s".format(
            now.precision, now.recall, now.mainShare.mapValues { "%.2f".format(it.value) }, before.precision, before.recall, before.mainShare.mapValues { "%.2f".format(it.value) }))
        // Nobody is put with someone else, and no stranger in the background joins a person.
        assertTrue("precision ${now.precision}", now.precision >= 0.97)
        assertEquals(0, now.strangersWithSomeone)
        // Most of each person's faces end up together, whatever they wear or do. (Measured 0.77 here,
        // 0.83 with OpenCV for Python: a few borderline faces move between builds, hence the margins.)
        assertTrue("recall ${now.recall}", now.recall >= 0.74)
        val s = now.mainShare
        assertTrue("$s", s.getValue("all") >= 0.8)
        assertTrue("$s", s.getValue("headwear") >= 0.9 && s.getValue("glasses") >= 0.9 && s.getValue("turned") >= 0.85 && s.getValue("frontal") >= 0.85)
        assertTrue("$s", s.getValue("make-up") >= 0.7 && s.getValue("expression") >= 0.7)
        assertTrue("$s", s.getValue("profile") >= 0.5 && s.getValue("small") >= 0.6)
        // The rules before 1.5.0 split people far more often.
        assertTrue("1.4 recall ${before.recall} vs ${now.recall}", now.recall >= before.recall + 0.2)
    }
}
