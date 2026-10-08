package com.localmediatools.gallery

import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.Detection
import com.localmediatools.gallery.core.EfficientDetDecoder
import com.localmediatools.gallery.core.FaceClustering
import com.localmediatools.gallery.core.FaceRec
import com.localmediatools.gallery.core.Lbp
import com.localmediatools.gallery.core.MediaFilter
import com.localmediatools.gallery.core.SceneHead
import com.localmediatools.gallery.core.SearchParser
import com.localmediatools.gallery.core.SearchTerm
import com.localmediatools.gallery.core.SearchVocabulary
import com.localmediatools.gallery.core.Taxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Random
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class GalleryCoreTest {
    // ------------------------------------------------------------------ detector post-processing
    @Test fun anchorsMatchTheModelMetadata() {
        val a = EfficientDetDecoder.anchors
        assertEquals(37629 * 4, a.size)
        // Values read from the EfficientDet-Lite2 model's metadata (x, y, w, h).
        val expected = mapOf(
            0 to floatArrayOf(0.008928572f, 0.008928572f, 0.05357143f, 0.05357143f),
            1 to floatArrayOf(0.00892857f, 0.00892857f, 0.07576144f, 0.03788072f),
            8 to floatArrayOf(0.008928573f, 0.008928573f, 0.0601319f, 0.12026379f),
            9 to floatArrayOf(0.026785715f, 0.008928572f, 0.05357143f, 0.05357143f),
            28224 to floatArrayOf(0.017857144f, 0.017857144f, 0.10714287f, 0.10714287f),
            37628 to floatArrayOf(0.875f, 0.875f, 0.84184647f, 1.6836932f),
        )
        for ((i, e) in expected) for (k in 0 until 4) assertEquals("anchor $i[$k]", e[k], a[i * 4 + k], 1e-5f)
    }

    @Test fun decodesBoxesAndSuppressesDuplicates() {
        val n = 37629; val c = EfficientDetDecoder.CLASSES
        val boxes = FloatArray(n * 4)
        val scores = FloatArray(n * c)
        // Anchor 28224 is a level-4 square around (0.018, 0.018): shift it and give it a "dog" score.
        val i = 28224
        boxes[i * 4] = 2f; boxes[i * 4 + 1] = 2f   // dy, dx in anchor units
        scores[i * c + 17] = 0.9f
        // A neighbour predicting the same box with a lower score must be suppressed.
        val j = 28225
        val a = EfficientDetDecoder.anchors
        boxes[j * 4] = (a[i * 4 + 1] + 2f * a[i * 4 + 3] - a[j * 4 + 1]) / a[j * 4 + 3]
        boxes[j * 4 + 1] = (a[i * 4] + 2f * a[i * 4 + 2] - a[j * 4]) / a[j * 4 + 2]
        boxes[j * 4 + 2] = kotlin.math.ln(a[i * 4 + 3] / a[j * 4 + 3]); boxes[j * 4 + 3] = kotlin.math.ln(a[i * 4 + 2] / a[j * 4 + 2])
        scores[j * c + 17] = 0.6f
        val d = EfficientDetDecoder.decode(boxes, scores, 0.2f)
        assertEquals(1, d.size)
        assertEquals("dog", EfficientDetDecoder.LABELS[d[0].cls])
        val cx = (d[0].x0 + d[0].x1) / 2
        assertEquals(0.017857144f + 2 * 0.10714287f, cx, 1e-4f)
        // Letterboxed input: the photo filled the left half, so x doubles.
        val half = EfficientDetDecoder.decode(boxes, scores, 0.2f, contentW = 0.5f)
        assertEquals(cx * 2, (half[0].x0 + half[0].x1) / 2, 1e-4f)
    }

    // ------------------------------------------------------------------ people grouping
    private fun unit(v: FloatArray): FloatArray { var s = 0f; for (x in v) s += x * x; val n = sqrt(s); return FloatArray(v.size) { v[it] / n } }

    /** Synthetic identities: faces scatter around a centre (same person ≈ 0.6–0.8 cosine, others ≈ 0). */
    private fun gallery(rnd: Random, people: Int, perPerson: IntArray, noise: Float): Pair<List<FaceRec>, IntArray> {
        val centres = List(people) { unit(FloatArray(128) { rnd.nextGaussian().toFloat() }) }
        val faces = ArrayList<FaceRec>(); val truth = ArrayList<Int>()
        var id = 1L
        for (p in 0 until people) repeat(perPerson[p % perPerson.size]) {
            val e = unit(FloatArray(128) { centres[p][it] + noise * rnd.nextGaussian().toFloat() / sqrt(128f) })
            faces.add(FaceRec(id++, e, 30f + rnd.nextFloat(), true)); truth.add(p)
        }
        return faces to truth.toIntArray()
    }

    @Test fun groupsFacesIntoPeopleWithoutMixingThem() {
        val rnd = Random(7)
        val (faces, truth) = gallery(rnd, 10, intArrayOf(3, 8, 20, 40), noise = 0.75f)
        val groups = FaceClustering.cluster(faces)
        val byId = faces.withIndex().associate { it.value.id to truth[it.index] }
        // Every group holds one person only.
        for (g in groups) assertEquals("mixed group", 1, g.faces.map { byId[it] }.distinct().size)
        // And each person ends up (almost entirely) in one group.
        for (p in 0 until 10) {
            val sizes = groups.map { g -> g.faces.count { byId[it] == p } }.filter { it > 0 }
            val total = sizes.sum()
            assertTrue("person $p split into $sizes", sizes.max() >= total * 0.8)
        }
    }

    @Test fun namedPeopleAnchorGroupsAndRejectionsAreRespected() {
        val rnd = Random(11)
        val (faces, truth) = gallery(rnd, 3, intArrayOf(12), noise = 0.75f)
        // The user named person 0 ("Sophie" = 100) on two faces and said face #3 of person 0 is not Sophie.
        val p0 = faces.indices.filter { truth[it] == 0 }
        val named = faces.mapIndexed { i, f ->
            when (i) {
                p0[0], p0[1] -> FaceRec(f.id, f.emb, f.quality, f.good, person = 100L)
                p0[2] -> FaceRec(f.id, f.emb, f.quality, f.good, notPeople = longArrayOf(100L))
                else -> f
            }
        }
        val groups = FaceClustering.cluster(named)
        val sophie = groups.single { it.person == 100L }
        val ids = sophie.faces.toSet()
        for (i in p0) if (i != p0[2]) assertTrue("face ${faces[i].id} of Sophie missing", faces[i].id in ids)
        assertTrue("rejected face joined Sophie", faces[p0[2]].id !in ids)
        for (i in faces.indices) if (truth[i] != 0) assertTrue(faces[i].id !in ids)
    }

    @Test fun lowQualityFacesOnlyJoinClearMatches() {
        val rnd = Random(5)
        val (faces, truth) = gallery(rnd, 2, intArrayOf(10), noise = 0.7f)
        // A blurry face halfway between the two people must stay on its own.
        val a = faces[truth.indexOfFirst { it == 0 }].emb; val b = faces[truth.indexOfFirst { it == 1 }].emb
        val between = FaceRec(999, unit(FloatArray(128) { a[it] + b[it] }), 1f, good = false)
        val groups = FaceClustering.cluster(faces + between)
        assertTrue(groups.any { it.faces.contentEquals(longArrayOf(999)) })
        // Two different named people never merge, however similar.
        val twin = faces.map { FaceRec(it.id, it.emb, it.quality, it.good, person = if (it.id <= 2) 1L else if (it.id in 3L..4L) 2L else null) }
        val g2 = FaceClustering.cluster(twin, ClusterParams.SFACE)
        assertEquals(1, g2.count { it.person == 1L }); assertEquals(1, g2.count { it.person == 2L })
    }

    @Test fun placesNewFacesIntoExistingGroups() {
        val rnd = Random(3)
        val (faces, truth) = gallery(rnd, 2, intArrayOf(8), noise = 0.7f)
        val sums = (0..1).map { p -> val s = FloatArray(128); faces.indices.filter { truth[it] == p }.forEach { i -> for (k in 0 until 128) s[k] += faces[i].emb[k] }; Triple(s, 8, null as Long?) }
        val (more, t2) = gallery(Random(3), 2, intArrayOf(9), noise = 0.7f)
        val probe = more.last()
        assertEquals(t2.last(), FaceClustering.place(probe, sums))
        val stranger = FaceRec(77, unit(FloatArray(128) { rnd.nextGaussian().toFloat() }), 30f, true)
        assertNull(FaceClustering.place(stranger, sums))
    }

    // ------------------------------------------------------------------ search
    private val vocab = SearchVocabulary(
        people = listOf(1L to "John", 2L to "Sophie", 3L to "Anna Maria"),
        categories = listOf(Triple("dog", "Dogs", listOf("dog", "dogs", "puppy")), Triple("beach", "Beach", listOf("beach", "seaside")),
            Triple("car", "Cars", listOf("car", "auto"))),
        albums = listOf("b1" to "WhatsApp Images", "b2" to "Camera"),
    )

    @Test fun parsesPeopleThingsAndTypos() {
        val q = SearchParser.parse("Jonh and Sophie", vocab)
        assertEquals(listOf("p:1", "p:2"), q.terms.map { SearchParser.termKey(it) })
        assertEquals(listOf("jonh" to "john"), q.corrections)
        val q2 = SearchParser.parse("photos of dogs at the beach", vocab)
        assertEquals(setOf("c:dog", "c:beach", "k:PHOTOS"), q2.terms.map { SearchParser.termKey(it) }.toSet())
        val q3 = SearchParser.parse("anna maria whatsapp images videos", vocab)
        assertEquals(listOf("p:3", "a:b1", "k:VIDEOS"), q3.terms.map { SearchParser.termKey(it) })
        // Unknown words are kept as text to look for in file and album names.
        val q4 = SearchParser.parse("receipts car", vocab)
        assertTrue(q4.terms.any { it is SearchTerm.Category && it.key == "car" })
        assertTrue(q4.terms.any { it is SearchTerm.Text && it.text == "receipts" })
        assertEquals(MediaFilter.SCREENSHOTS, (SearchParser.parse("screenshots", vocab).terms.single() as SearchTerm.Kind).kind)
    }

    @Test fun parsesDates() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = Calendar.getInstance(utc).apply { set(2026, Calendar.OCTOBER, 8, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun at(y: Int, m: Int, d: Int) = Calendar.getInstance(utc).apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val june = SearchParser.parse("Sophie june 2023", vocab, now, utc).terms
        val t = june.filterIsInstance<SearchTerm.Time>().single()
        assertEquals(at(2023, Calendar.JUNE, 1), t.fromMs); assertEquals(at(2023, Calendar.JULY, 1), t.toMs)
        // A month alone means the latest one (December of last year when it is October).
        val dec = SearchParser.parse("december", vocab, now, utc).terms.single() as SearchTerm.Time
        assertEquals(at(2025, Calendar.DECEMBER, 1), dec.fromMs)
        val y = SearchParser.parse("2024 dogs", vocab, now, utc).terms
        assertEquals(at(2024, 0, 1), (y[0] as SearchTerm.Time).fromMs)
        val last = SearchParser.parse("last month", vocab, now, utc).terms.single() as SearchTerm.Time
        assertEquals(at(2026, Calendar.SEPTEMBER, 1), last.fromMs); assertEquals(at(2026, Calendar.OCTOBER, 1), last.toMs)
        // "may" alone is a word, not a month.
        assertTrue(SearchParser.parse("may", vocab, now, utc).terms.none { it is SearchTerm.Time })
    }

    @Test fun editDistanceCountsSwapsOnce() {
        assertEquals(1, SearchParser.editDistance("jonh", "john"))
        assertEquals(2, SearchParser.editDistance("sofie", "sophie"))
        assertEquals(0, SearchParser.editDistance("dog", "dog"))
    }

    // ------------------------------------------------------------------ categories
    @Test fun fusesCalibratedScores() {
        val tsv = listOf(
            "# key name group words det detThr detArea scene sceneThr probe probeThr",
            listOf("dog", "Dogs", "Animals", "dog,puppy", "17", "0.5", "0", "0 1", "0.2", "-1", "0").joinToString("\t"),
            listOf("sunset", "Sunsets", "Nature", "sunset", "", "0", "0", "", "0", "0", "0.6").joinToString("\t"),
        ).joinToString("\n")
        val cats = Taxonomy.parse(tsv)
        assertEquals(2, cats.size)
        val dog = cats[0]
        assertEquals(listOf("dog", "puppy"), dog.words)
        // Detector at its threshold → 0.5; classifier rows 0+1 = 0.6 (threshold 0.2) → 0.5 + 0.5·0.4/0.8 = 0.75.
        assertEquals(0.5f, Taxonomy.score(dog, listOf(Detection(17, 0.5f, 0f, 0f, 1f, 1f)), null, null), 1e-5f)
        assertEquals(0.75f, Taxonomy.score(dog, emptyList(), floatArrayOf(0.4f, 0.2f), null), 1e-5f)
        assertEquals(0f, Taxonomy.score(dog, listOf(Detection(16, 0.99f, 0f, 0f, 1f, 1f)), null, null), 1e-6f)
        assertEquals(0.25f, Taxonomy.score(cats[1], emptyList(), null, floatArrayOf(0.3f)), 1e-5f)
        val s = Taxonomy.scores(cats, emptyList(), floatArrayOf(0.4f, 0.2f), floatArrayOf(0.1f))
        assertEquals(setOf("dog"), s.keys)
    }

    @Test fun sceneHeadDecodesFourBitWeightsAndSoftmax() {
        val rows = 2; val dim = 4; val group = 2; val probes = 1
        val b = ByteBuffer.allocate(4 + 20 + rows * (dim / group) * 2 + rows * 4 + rows * dim / 2 + probes * dim * 4 + probes * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("LMTH".toByteArray()); b.putInt(2); b.putInt(rows); b.putInt(dim); b.putInt(group); b.putInt(probes)
        // Half-float scales: 0.5 = 0x3800, 1.0 = 0x3C00, 2.0 = 0x4000.
        b.putShort(0x3800.toShort()); b.putShort(0x3C00.toShort()); b.putShort(0x4000.toShort()); b.putShort(0x3C00.toShort())
        b.putFloat(0f); b.putFloat(1f)
        // Row 0 weights (2, -1 | 0, 3), row 1 (1, 0 | -8, 7), two per byte, low nibble first.
        fun pack(lo: Int, hi: Int) = ((lo and 15) or ((hi and 15) shl 4)).toByte()
        b.put(pack(2, -1)); b.put(pack(0, 3)); b.put(pack(1, 0)); b.put(pack(-8, 7))
        b.putFloat(1f); b.putFloat(-1f); b.putFloat(0f); b.putFloat(0f); b.putFloat(0.5f)
        val head = SceneHead.read(b.array())
        val f = floatArrayOf(1f, 2f, 0.5f, -1f)
        val l0 = 0.5f * (2 * 1f - 1 * 2f) + 1f * (0 * 0.5f + 3 * -1f) + 0f    // = -3
        val l1 = 2f * (1 * 1f + 0 * 2f) + 1f * (-8 * 0.5f + 7 * -1f) + 1f     // = -8
        val p = head.probabilities(f)
        val z = exp(l0.toDouble()) + exp(l1.toDouble())
        assertEquals((exp(l0.toDouble()) / z).toFloat(), p[0], 1e-5f)
        assertEquals((exp(l1.toDouble()) / z).toFloat(), p[1], 1e-5f)
        assertEquals((1 / (1 + exp(-(-1.0 + 0.5)))).toFloat(), head.probes(f)[0], 1e-5f)
        assertEquals(-2f, SceneHead.half(0xC000.toShort()), 0f)
        assertEquals(6.1035156e-5f, SceneHead.half(0x0400.toShort()), 1e-9f)
    }

    @Test fun shippedSceneHeadReproducesTheCalibration() {
        val head = SceneHead.read(java.io.File("app/src/main/assets/gallery/scene_head.bin").readBytes())
        val cats = Taxonomy.parse(java.io.File("app/src/main/assets/gallery/categories.tsv").readText())
        assertEquals(21843, head.rows)
        val g = ByteBuffer.wrap(javaClass.getResourceAsStream("/gallery/scene_golden.bin")!!.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val n = g.int; val dim = g.int; val nc = g.int
        assertEquals(head.dim, dim); assertEquals(cats.size, nc)
        var tags = 0
        repeat(n) { r ->
            val f = FloatArray(dim) { g.float }
            val want = FloatArray(nc) { g.float }
            val probs = head.probabilities(f); val probes = head.probes(f)
            for ((j, c) in cats.withIndex()) {
                val got = Taxonomy.score(c, emptyList(), probs, probes)
                assertEquals("photo $r, ${c.key}", want[j], got, 2e-3f)
                if (kotlin.math.abs(want[j] - Taxonomy.TAGGED) > 2e-3f) assertEquals("photo $r, ${c.key}", want[j] >= Taxonomy.TAGGED, got >= Taxonomy.TAGGED)
                if (got >= Taxonomy.TAGGED) tags++
            }
        }
        assertTrue("only $tags tags", tags >= 60)
    }

    // ------------------------------------------------------------------ fallback face descriptor
    @Test fun lbpDescriptorIsStableUnderSmallChanges() {
        val w = 120; val h = 120
        val rnd = Random(1)
        val img = FloatArray(w * h) { 128f + 60f * kotlin.math.sin(it % w / 7.0).toFloat() + 40f * kotlin.math.cos(it / w / 5.0).toFloat() + rnd.nextFloat() * 20 }
        val brighter = FloatArray(w * h) { img[it] * 1.1f + 8f }
        val other = FloatArray(w * h) { 128f + 60f * rnd.nextGaussian().toFloat() }
        val a = Lbp.describe(img, w, h, 60f, 55f, 30f)
        val b = Lbp.describe(brighter, w, h, 61f, 55f, 30.5f)
        val c = Lbp.describe(other, w, h, 60f, 55f, 30f)
        assertEquals(Lbp.DIM, a.size)
        var n = 0f; for (x in a) n += x * x
        assertEquals(1f, n, 1e-3f)
        assertTrue("same face ${Lbp.similarity(a, b)} vs other ${Lbp.similarity(a, c)}", Lbp.similarity(a, b) > Lbp.similarity(a, c) + 0.1f)
        assertTrue(abs(Lbp.similarity(a, a) - 1f) < 1e-4f)
        assertNotNull(ClusterParams.of(com.localmediatools.gallery.core.FaceKind.LBP))
    }
}
