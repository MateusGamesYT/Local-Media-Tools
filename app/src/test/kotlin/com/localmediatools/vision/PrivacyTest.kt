package com.localmediatools.vision

import com.localmediatools.vision.core.CodeDetector
import com.localmediatools.vision.core.Detail
import com.localmediatools.vision.core.Face
import com.localmediatools.vision.core.FaceEngine
import com.localmediatools.vision.core.FaceMerge
import com.localmediatools.vision.core.FindKind
import com.localmediatools.vision.core.Found
import com.localmediatools.vision.core.LineMemory
import com.localmediatools.vision.core.PlateDetector
import com.localmediatools.vision.core.PrivacyFinder
import com.localmediatools.vision.core.PrivacyOptions
import com.localmediatools.vision.core.PrivacyShape
import com.localmediatools.vision.core.PrivateText
import com.localmediatools.vision.core.PrivateTracker
import com.localmediatools.vision.core.Quad
import com.localmediatools.vision.core.TextDetector
import com.localmediatools.vision.core.TextReader
import com.localmediatools.vision.core.TextVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * What "Blur faces & private info" finds besides faces, on real photos (resources/privacy: Open
 * Images, CC BY 2.0, credits in CREDITS.tsv): number plates people marked, phone numbers and e-mail
 * addresses in adverts, a QR code and a barcode; faces on the real-people set. Plus the text
 * classifier, video tracking and the shapes that are blurred.
 */
class PrivacyTest {
    companion object {
        private val assets = File("app/src/main/assets")
        lateinit var classifier: PrivateText
        lateinit var finder: PrivacyFinder
        /** labels.tsv: file → (kind, detail, coordinates as fractions). */
        val labels = HashMap<String, MutableList<Triple<String, String, FloatArray>>>()

        @BeforeClass @JvmStatic fun load() {
            nu.pattern.OpenCV.loadLocally()
            fun lines(n: String) = File(assets, "privacy/$n").readLines().filter { it.isNotBlank() }
            classifier = PrivateText(lines("first_names.txt").toHashSet(), lines("surnames.txt").toHashSet())
            finder = PrivacyFinder(
                PlateDetector(File(assets, "models/license_plate_lpd_yunet_2023mar_fp16.onnx").path),
                TextDetector(File(assets, "models/text_detection_ppocrv3_en_fp16.onnx").path),
                TextReader(File(assets, "models/text_recognition_ppocrv5_mobile_fp16.onnx").path, File(assets, "privacy/ppocrv5_dict.txt").readLines()),
                classifier, CodeDetector())
            for (row in text("privacy/labels.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }) {
                val c = row.split('\t')
                labels.getOrPut(c[0]) { ArrayList() }.add(Triple(c[1], c[2], c[3].split(' ').map { it.toFloat() }.toFloatArray()))
            }
        }

        fun text(path: String) = PrivacyTest::class.java.getResourceAsStream("/$path")!!.readBytes().toString(Charsets.UTF_8)

        fun photo(path: String): Mat {
            val bytes = PrivacyTest::class.java.getResourceAsStream("/$path")!!.readBytes()
            return Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
        }

        /** Share of [target] (a polygon in pixels) under the found things, grown as the app blurs them. */
        fun covered(target: FloatArray, found: List<Found>, w: Int, h: Int): Double {
            fun poly(p: FloatArray) = MatOfPoint(*Array(p.size / 2) { Point(p[it * 2].toDouble(), p[it * 2 + 1].toDouble()) })
            val t = Mat.zeros(h, w, CvType.CV_8U); val d = Mat.zeros(h, w, CvType.CV_8U)
            try {
                Imgproc.fillPoly(t, listOf(poly(target)), Scalar(255.0))
                for (f in found) Imgproc.fillPoly(d, listOf(poly(PrivacyShape.grown(f.quad).p)), Scalar(255.0))
                val both = Mat(); org.opencv.core.Core.bitwise_and(t, d, both)
                val n = org.opencv.core.Core.countNonZero(t)
                return if (n == 0) 0.0 else org.opencv.core.Core.countNonZero(both).toDouble() / n
            } finally { t.release(); d.release() }
        }

        fun box(c: FloatArray, w: Int, h: Int) = floatArrayOf(c[0] * w, c[1] * h, c[2] * w, c[1] * h, c[2] * w, c[3] * h, c[0] * w, c[3] * h)
    }

    // ------------------------------------------------------------------ the text classifier

    private fun details(s: String): Set<Detail> = classifier.classify(s).details

    @Test fun recognisesPrivateDetails() {
        val cases = listOf(
            "Password: hunter22" to Detail.PASSWORD, "Senha: Casa2024" to Detail.PASSWORD, "WiFi key: 8812bluesky" to Detail.PASSWORD,
            "Tel: (11) 98765-4321" to Detail.PHONE, "Call 555-123-4567 today" to Detail.PHONE, "+55 11 98765-4321" to Detail.PHONE,
            "1-888-867-2757" to Detail.PHONE, "jane.doe@example.com" to Detail.EMAIL, "E-mail: contato@loja.com.br" to Detail.EMAIL,
            "4111 1111 1111 1111" to Detail.CARD, "GB82 WEST 1234 5698 7654 32" to Detail.BANK, "Name: Ernani" to Detail.NAME,
            "Maria da Silva" to Detail.NAME, "John Smith" to Detail.NAME, "Rua Augusta, 1200" to Detail.ADDRESS,
            "CEP 01310-100" to Detail.ADDRESS, "221B Baker Street" to Detail.ADDRESS, "Date of birth: 12/05/1990" to Detail.BIRTH_DATE,
            "@some_handle" to Detail.USERNAME, "Order 123456789" to Detail.NUMBER, "123-45-6789" to Detail.ID_NUMBER,
        )
        for ((s, d) in cases) assertTrue("$s → ${details(s)}", d in details(s))
    }

    @Test fun leavesOrdinaryTextAlone() {
        for (s in listOf("Grand Hotel", "Will Power", "Rose Garden", "SALE 50% OFF", "R$ 1.299,00", "12/05/2024", "Open 9:30 - 18:00",
                "2012/02/19 18:05", "26022015", "1768-1794", "1972 1975", "Mobile Bar", "Coca-Cola", "Since 1970", "London Bridge", "Star King"))
            assertTrue("$s → ${details(s)}", details(s).isEmpty())
    }

    @Test fun labelsAloneMarkTheirValue() {
        val v: TextVerdict = classifier.classify("Senha:")
        assertTrue(v.label); assertTrue(v.labelOnly); assertTrue(Detail.PASSWORD in v.details)
        assertFalse(classifier.classify("Password: hunter22").labelOnly)
    }

    @Test fun checksNumbers() {
        assertTrue(PrivateText.luhn("4111111111111111")); assertFalse(PrivateText.luhn("4111111111111112"))
        assertTrue(PrivateText.iban("GB82WEST12345698765432")); assertFalse(PrivateText.iban("GB82WEST12345698765433"))
        assertTrue(PrivateText.compactDate("26022015")); assertTrue(PrivateText.compactDate("20150226")); assertFalse(PrivateText.compactDate("98765432"))
        // Letters read for digits are put right inside numbers only.
        assertEquals("Tel 98765-4321", PrivateText.fixDigits("Tel 98765-432l"))
        assertEquals("(SUN)", PrivateText.fixDigits("(SUN)"))
    }

    // ------------------------------------------------------------------ real photos

    @Test fun hidesNumberPlates() {
        var plates = 0
        for ((file, rows) in labels) {
            val want = rows.filter { it.first == "plate" }
            if (want.isEmpty()) continue
            val img = photo("privacy/$file"); val w = img.cols(); val h = img.rows()
            val vehicles = rows.filter { it.first == "vehicle" }.map { floatArrayOf(it.third[0] * w, it.third[1] * h, it.third[2] * w, it.third[3] * h) }
            val found = finder.find(img, PrivacyOptions(plates = true), vehicles)
            assertTrue(found.all { it.kind == FindKind.PLATE })
            for (p in want) { plates++; assertTrue("plate in $file", covered(box(p.third, w, h), found, w, h) >= 0.8) }
            // Nothing found by the plate detector is larger than half the photo across.
            for (f in found) assertTrue("plate-sized in $file", maxOf(f.quad.width, f.quad.height) <= 0.5f * maxOf(w, h) + 1)
            img.release()
        }
        assertEquals(5, plates)
    }

    @Test fun hidesPhoneNumbersAndEmailAddresses() {
        var n = 0; var hidden = 0
        for ((file, rows) in labels) {
            val want = rows.filter { it.first == "private" }
            if (want.isEmpty()) continue
            val img = photo("privacy/$file"); val w = img.cols(); val h = img.rows()
            val found = finder.find(img, PrivacyOptions(privateText = true))
            assertTrue(found.all { it.kind == FindKind.TEXT && it.details.isNotEmpty() })
            for (p in want) {
                n++
                if (covered(FloatArray(8) { if (it % 2 == 0) p.third[it] * w else p.third[it] * h }, found, w, h) >= 0.8) hidden++
            }
            assertTrue("e-mail kind in $file", want.none { it.second == "email" } || found.any { Detail.EMAIL in it.details })
            img.release()
        }
        assertEquals(8, n)
        assertEquals(8, hidden)
    }

    @Test fun allTextHidesMoreThanPrivateDetails() {
        val img = photo("privacy/223514026649d937.jpg")
        val all = finder.find(img, PrivacyOptions(allText = true))
        val private = finder.find(img, PrivacyOptions(privateText = true))
        assertTrue("${all.size} > ${private.size}", all.size > private.size * 3)
        img.release()
    }

    @Test fun hidesCodes() {
        for ((file, rows) in labels) {
            val want = rows.filter { it.first == "code" }
            if (want.isEmpty()) continue
            val img = photo("privacy/$file"); val w = img.cols(); val h = img.rows()
            val found = finder.find(img, PrivacyOptions(codes = true))
            for (c in want) assertTrue("${c.second} in $file", covered(box(c.third, w, h), found, w, h) >= 0.8)
            img.release()
        }
    }

    @Test fun findsNothingWhenNothingIsAskedFor() {
        val img = photo("privacy/e6299b9e04680adb.jpg")
        assertTrue(finder.find(img, PrivacyOptions()).isEmpty())
        img.release()
    }

    /**
     * On the real-people set, looking at smaller copies too keeps every face the single look finds
     * (under the blur) and merges the copies' boxes, so no face is listed twice.
     */
    @Test fun facesToHideIncludeEveryFaceFoundBefore() {
        val engine = FaceEngine(File(assets, "models/face_detection_yunet_2023mar.onnx").path, null as String?)
        val files = text("people/faces.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t')[0] }.distinct()
        var before = 0; var after = 0
        for (f in files) {
            val img = photo("people/$f")
            val one = engine.detect(img)
            val all = engine.detectToHide(img, FaceEngine.PHOTO_SIDES)
            before += one.size; after += all.size
            for (a in one) assertTrue("face in $f", all.any { overlap(a, it) > 0.6f })
            for (i in all.indices) for (j in i + 1 until all.size) assertFalse("same face twice in $f", FaceMerge.same(all[i], all[j]))
            img.release()
        }
        assertTrue("$after ≥ $before", after >= before)
    }

    private fun overlap(a: Face, b: Face): Float {
        val ix = maxOf(0f, minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)); val iy = maxOf(0f, minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y))
        return ix * iy / (a.w * a.h)
    }

    // ------------------------------------------------------------------ shapes and video

    @Test fun capsuleCoversTheGrownQuad() {
        val q = Quad.box(100f, 50f, 300f, 70f)
        val c = PrivacyShape.capsule(q)
        // Along the line, through its middle, as thick as the line with its margin.
        assertEquals(60f, c.y0, 0.01f); assertEquals(60f, c.y1, 0.01f)
        assertEquals(10f + 20f * PrivacyShape.MARGIN, c.radius, 0.01f)
        assertTrue(c.x0 < 100f && c.x1 > 300f)
        assertEquals(PrivacyShape.FEATURE * 20f, c.featurePx, 0.01f)
        // Upright text: the capsule runs downwards.
        val up = PrivacyShape.capsule(Quad.box(10f, 10f, 30f, 210f))
        assertEquals(20f, up.x0, 0.01f); assertEquals(20f, up.x1, 0.01f)
    }

    @Test fun followsThingsThroughAVideo() {
        val t = PrivateTracker(maxGapUs = 1_000_000)
        fun at(x: Float) = Triple(FindKind.PLATE, emptySet<Detail>() to "", floatArrayOf(x, 0.5f, x + 0.1f, 0.5f, x + 0.1f, 0.55f, x, 0.55f))
        t.add(0, listOf(at(0.10f)))
        t.add(333_000, listOf(at(0.12f), Triple(FindKind.TEXT, setOf(Detail.PHONE) to "555-1234", floatArrayOf(0.7f, 0.1f, 0.9f, 0.1f, 0.9f, 0.13f, 0.7f, 0.13f))))
        t.add(666_000, listOf(at(0.14f)))
        assertEquals(2, t.tracks.size)
        assertEquals(3, t.tracks[0].samples.size)
        // Between two looks the box moves smoothly; before the first and after the last it is held.
        val mid = PrivateTracker.regionsAt(t.tracks.take(1), 500_000, 500_000, 16f / 9).single()
        assertEquals(0.18f, mid.cx, 0.002f)
        assertTrue(PrivateTracker.regionsAt(t.tracks.take(1), 1_100_000, 500_000, 16f / 9).isNotEmpty())
        assertTrue(PrivateTracker.regionsAt(t.tracks.take(1), 1_200_000, 500_000, 16f / 9).isEmpty())
        // The text was seen once: hidden around that moment only.
        assertEquals(1, PrivateTracker.regionsAt(t.tracks.drop(1), 0, 500_000, 16f / 9).size)
        assertTrue(PrivateTracker.regionsAt(t.tracks.drop(1), 900_000, 500_000, 16f / 9).isEmpty())
    }

    @Test fun readsEachLineOfAVideoOnce() {
        val m = LineMemory(1_000_000)
        val q = Quad.box(10f, 10f, 200f, 30f)
        val v = classifier.classify("Tel 555-123-4567")
        m.now = 0; assertEquals(null, m.recall(q)); m.remember(q, "Tel 555-123-4567", v)
        m.now = 333_000; assertEquals("Tel 555-123-4567", m.recall(Quad.box(14f, 11f, 204f, 31f))?.first)
        // Much larger now (closer): read again, as it can be read better.
        assertEquals(null, m.recall(Quad.box(10f, 10f, 400f, 50f)))
        m.now = 2_000_000; assertEquals(null, m.recall(q))
    }
}
