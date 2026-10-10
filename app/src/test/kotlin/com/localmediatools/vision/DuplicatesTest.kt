package com.localmediatools.vision

import com.localmediatools.vision.core.DupKind
import com.localmediatools.vision.core.Duplicates
import com.localmediatools.vision.core.FeatureCheck
import com.localmediatools.vision.core.KeepCandidate
import com.localmediatools.vision.core.KeepPlanner
import com.localmediatools.vision.core.KeepRules
import com.localmediatools.vision.core.PhotoInfo
import com.localmediatools.vision.core.Prefer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.DataInputStream
import javax.imageio.ImageIO

/**
 * The duplicate finder on real photos (test resources /duplicates, Open Images, CC BY 2.0, see
 * CREDITS.md): same-moment shots and re-saved copies are grouped; other angles of the same place
 * and the hardest look-alikes of the evaluation (clouds, night skies, printed pages, screenshots,
 * each pair taken seconds apart) are not. Embeddings come from the on-device model (golden.bin,
 * buildtools/duplicates/make_fixture.py); everything else is computed here by the app's code.
 */
class DuplicatesTest {
    companion object {
        @JvmStatic @BeforeClass fun load() { nu.pattern.OpenCV.loadLocally() }
    }

    private class Golden(val emb: FloatArray, val grid: FloatArray, val contrast: Float)

    private val golden: Map<String, Golden> by lazy {
        val out = HashMap<String, Golden>()
        DataInputStream(javaClass.getResourceAsStream("/duplicates/golden.bin")!!.buffered()).use { d ->
            repeat(d.readInt()) {
                val name = String(ByteArray(d.readUnsignedShort()).also { d.readFully(it) })
                val len = d.readInt(); val scale = d.readFloat()
                val q = ByteArray(len).also { d.readFully(it) }
                val e = FloatArray(len) { q[it] * scale }
                var s = 0f; for (v in e) s += v * v; val n = Math.sqrt(s.toDouble()).toFloat(); for (k in e.indices) e[k] /= n
                val g = FloatArray(256) { d.readFloat() }
                out[name] = Golden(e, g, d.readFloat())
            }
        }
        out
    }

    private class Pic(val w: Int, val h: Int, val argb: IntArray)

    private fun pic(name: String): Pic {
        val img = ImageIO.read(javaClass.getResourceAsStream("/duplicates/$name.jpg")!!)
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return Pic(img.width, img.height, px)
    }

    private fun grey(c: Int) = (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000

    /** Like Bitmap.createScaledBitmap(…, 9, 8, true): bilinear samples, then the difference hash. */
    private fun dhash(p: Pic): Long {
        val g = IntArray(72)
        for (y in 0 until 8) for (x in 0 until 9) {
            val sx = ((x + 0.5) * p.w / 9 - 0.5).coerceIn(0.0, p.w - 1.0); val sy = ((y + 0.5) * p.h / 8 - 0.5).coerceIn(0.0, p.h - 1.0)
            val x0 = sx.toInt(); val y0 = sy.toInt(); val x1 = minOf(x0 + 1, p.w - 1); val y1 = minOf(y0 + 1, p.h - 1)
            val fx = sx - x0; val fy = sy - y0
            fun v(xx: Int, yy: Int) = grey(p.argb[yy * p.w + xx]).toDouble()
            g[y * 9 + x] = ((v(x0, y0) * (1 - fx) + v(x1, y0) * fx) * (1 - fy) + (v(x0, y1) * (1 - fx) + v(x1, y1) * fx) * fy).toInt()
        }
        return Duplicates.dhash(g)
    }

    private fun greyMat(p: Pic): Mat {
        val m = Mat(p.h, p.w, CvType.CV_8UC1)
        m.put(0, 0, ByteArray(p.w * p.h) { grey(p.argb[it]).toByte() })
        return m
    }

    private val t0 = 1_700_000_000_000L
    /** Capture times: each pair a few seconds apart (the hard look-alikes too); copies saved from apps have none. */
    private val times = mapOf(
        "court_1" to 0L, "court_2" to 3_000L, "bridge_1" to 600_000L, "bridge_2" to 604_000L, "cushion_1" to 1_200_000L, "cushion_2" to 1_209_000L,
        "sunset_1" to 1_800_000L, "sunset_2" to 1_820_000L, "cemetery_1" to 2_400_000L, "cemetery_2" to 2_430_000L, "tank_1" to 3_000_000L, "tank_2" to 3_025_000L,
        "clouds_a" to 3_600_000L, "clouds_b" to 3_612_000L, "night_a" to 4_200_000L, "night_b" to 4_208_000L, "document_a" to 4_800_000L, "document_b" to 4_810_000L,
        "screen_a" to 5_400_000L, "screen_b" to 5_406_000L, "copy_lowq_bridge_1" to 600_000L,
    )

    @Test fun layoutMatchesTheEvaluation() {
        for ((name, g) in golden) {
            val p = pic(name)
            val (grid, contrast) = Duplicates.grid(p.argb, p.w, p.h)
            var dot = 0f; for (k in grid.indices) dot += grid[k] * g.grid[k]
            // Same picture through two JPEG decoders: the 16×16 averages agree almost exactly.
            assertTrue("$name: $dot", dot > 0.999f)
            assertEquals(name, g.contrast, contrast, g.contrast * 0.02f + 0.05f)
        }
    }

    @Test fun groupsRealDuplicatesAndNothingElse() {
        val names = golden.keys.sorted()
        val pics = names.associateWith { pic(it) }
        val items = names.mapIndexed { i, n ->
            val p = pics.getValue(n)
            val (grid, contrast) = Duplicates.grid(p.argb, p.w, p.h)
            PhotoInfo(i, p.argb.size.toLong(), p.w, p.h, dhash(p), golden.getValue(n).emb, grid, contrast, 0.0, times[n]?.let { t0 + it }, null)
        }
        val check = FeatureCheck()
        val feats = names.map { n -> greyMat(pics.getValue(n)).let { m -> check.features(m).also { m.release() } } }
        val asked = ArrayList<String>()
        val groups = Duplicates.group(items, verify = { a, b -> asked.add("${names[a]}~${names[b]}"); check.compare(feats[a], feats[b]) })
        val named = groups.map { g -> g.members.map { names[it] }.toSet() }
        fun groupOf(n: String) = named.firstOrNull { n in it } ?: emptySet()
        // Same moments.
        assertEquals(setOf("court_1", "court_2"), groupOf("court_1"))
        assertEquals(setOf("cushion_1", "cushion_2"), groupOf("cushion_1"))
        assertEquals(setOf("sunset_1", "sunset_2"), groupOf("sunset_1"))
        // A shot of the same moment and its re-saved copies, a screenshot of it included.
        assertEquals(setOf("bridge_1", "bridge_2", "copy_small_bridge_1", "copy_lowq_bridge_1", "copy_screenshot_bridge_1"), groupOf("bridge_1"))
        val bridge = groups.first { names.indexOf("bridge_1") in it.members }
        val bridgePicture = bridge.pictures.first { names.indexOf("bridge_1") in it }.map { names[it] }.toSet()
        assertEquals(setOf("bridge_1", "copy_small_bridge_1", "copy_lowq_bridge_1"), bridgePicture)
        // A page of text (no layout to compare) and its copy.
        assertEquals(setOf("document_a", "copy_lowq_document_a"), groupOf("document_a"))
        assertEquals(DupKind.NEAR_DUPLICATE, groups.first { names.indexOf("document_a") in it.members }.kind)
        // Other angles of the same place, and look-alikes taken seconds apart: never.
        for (n in listOf("cemetery_1", "cemetery_2", "tank_1", "tank_2", "clouds_a", "clouds_b", "night_a", "night_b", "document_b", "screen_a", "screen_b"))
            assertEquals(n, emptySet<String>(), groupOf(n))
        assertEquals(named.toString(), 5, groups.size)
        feats.forEach { it.release() }
    }

    @Test fun withoutTheModelOnlyCopiesAreFound() {
        val names = golden.keys.sorted()
        val items = names.mapIndexed { i, n ->
            val p = pic(n)
            val (grid, contrast) = Duplicates.grid(p.argb, p.w, p.h)
            PhotoInfo(i, p.argb.size.toLong(), p.w, p.h, dhash(p), null, grid, contrast, 0.0, times[n]?.let { t0 + it }, null)
        }
        val named = Duplicates.group(items).map { g -> g.members.map { names[it] }.toSet() }
        // Re-saved copies (the screenshot of a photo needs the model) and nothing else.
        assertEquals(setOf(setOf("bridge_1", "copy_lowq_bridge_1", "copy_small_bridge_1"), setOf("document_a", "copy_lowq_document_a")), named.toSet())
    }

    @Test fun identicalFilesAndFlatPictures() {
        val grid = FloatArray(256) { if (it % 2 == 0) 0.0625f else -0.0625f }
        fun item(i: Int, digest: String?, contrast: Float, emb: FloatArray? = null) = PhotoInfo(i, 1000, 400, 300, 0L, emb, grid, contrast, 0.0, null, digest)
        // Two blank (flat) pictures with the same hash are not duplicates unless the files are identical.
        val g = Duplicates.group(listOf(item(0, "a", 0.5f), item(1, "a", 0.5f), item(2, "b", 0.5f), item(3, "c", 0.5f)))
        assertEquals(1, g.size)
        assertEquals(DupKind.IDENTICAL, g[0].kind)
        assertEquals(listOf(0, 1), g[0].members)
        assertEquals(setOf(if (g[0].best == 0) 1 else 0), g[0].copies)
    }

    @Test fun keepRules() {
        fun c(bytes: Long, px: Long, modified: Long, raw: Boolean = false, fav: Boolean = false, people: Set<Long> = emptySet(), sharp: Double = 1.0) =
            KeepCandidate(bytes, px, sharp, modified, raw, fav, people)
        // A JPEG, its RAW, a re-saved small copy (one picture), and a second shot of the moment.
        val members = listOf(c(4_000_000, 12_000_000, 100), c(25_000_000, 12_000_000, 100, raw = true), c(300_000, 2_000_000, 500), c(3_900_000, 12_000_000, 101, sharp = 0.5))
        val pictures = listOf(listOf(0, 1, 2), listOf(3))
        // Default: keep RAWs and the best copy; the small copy goes; the other shot stays.
        assertEquals(setOf(2), KeepPlanner.suggest(members, pictures, KeepRules()))
        // RAWs not protected: the RAW is the kept copy (same pixels, ties broken by bytes).
        assertEquals(setOf(0, 2), KeepPlanner.suggest(members, pictures, KeepRules(keepRaw = false)))
        // Similar shots too: only the best picture stays.
        assertEquals(setOf(2, 3), KeepPlanner.suggest(members, pictures, KeepRules(similarShots = true)))
        // Keep the original (oldest) file: the JPEG (next to its RAW) is older than the small copy.
        assertEquals(setOf(2), KeepPlanner.suggest(members, pictures, KeepRules(prefer = Prefer.ORIGINAL)))
        // A person to keep in the other shot protects it even when similar shots are removed.
        val withPeople = members.toMutableList().also { it[3] = c(3_900_000, 12_000_000, 101, people = setOf(7L), sharp = 0.5) }
        assertEquals(setOf(2), KeepPlanner.suggest(withPeople, pictures, KeepRules(similarShots = true, keepPeople = setOf(7L))))
        assertEquals("Ana", KeepPlanner.protectedBy(withPeople[3], KeepRules(keepPeople = setOf(7L)), mapOf(7L to "Ana")))
        // Favourites.
        val fav = listOf(c(100, 100, 1), c(200, 200, 2, fav = true))
        // The favourite stays (even though a smaller copy is preferred), so its plain copy can go.
        assertEquals(setOf(0), KeepPlanner.suggest(fav, listOf(listOf(0, 1)), KeepRules(prefer = Prefer.SMALLEST)))
        assertEquals(setOf(1), KeepPlanner.suggest(fav, listOf(listOf(0, 1)), KeepRules(prefer = Prefer.SMALLEST, keepFavorites = false)))
        // Copies of one picture: the bigger file stays even if compression noise made the other look sharper;
        // between different shots, the sharper one is best.
        val copies = listOf(c(4_000_000, 12_000_000, 1, sharp = 1.0), c(1_000_000, 12_000_000, 2, sharp = 2.0))
        assertEquals(setOf(1), KeepPlanner.suggest(copies, listOf(listOf(0, 1)), KeepRules()))
        assertEquals(setOf(0), KeepPlanner.suggest(copies, listOf(listOf(0), listOf(1)), KeepRules(similarShots = true)))
        assertNotNull(KeepPlanner.comparator(Prefer.NEWEST))
        assertFalse(KeepPlanner.suggest(listOf(c(1, 1, 1)), listOf(listOf(0)), KeepRules()).isNotEmpty())
    }

    @Test fun largeLibrariesAreComparedQuickly() {
        // 20,000 photos, a fifth without a capture time (compared with everything): the comparison
        // itself must stay well under the time the scan spends reading thumbnails.
        val rnd = java.util.Random(1)
        val n = 20_000
        val items = (0 until n).map { i ->
            val e = FloatArray(1024) { rnd.nextGaussian().toFloat() }.also { com.localmediatools.vision.core.FaceEngine.normalize(it) }
            val g = FloatArray(256) { rnd.nextGaussian().toFloat() / 16 }
            PhotoInfo(i, 1000, 4000, 3000, rnd.nextLong(), e, g, 20f, 1.0, if (i % 5 == 0) null else i * 30_000L, null)
        }
        val t = System.nanoTime()
        val groups = Duplicates.group(items)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("20,000 photos compared in $ms ms")
        assertTrue(groups.isEmpty())
        assertTrue("took $ms ms", ms < 20_000)
    }
}
