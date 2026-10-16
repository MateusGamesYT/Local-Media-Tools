package com.localmediatools.denoise

import com.localmediatools.codec.raw.ArraySource
import com.localmediatools.codec.raw.RawFiles
import com.localmediatools.denoise.core.NoiseEstimator
import com.localmediatools.denoise.core.PhotoDenoiser
import com.localmediatools.denoise.core.RawDenoiser
import com.localmediatools.denoise.core.VideoDenoise
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The denoisers' arithmetic (the settings themselves are measured on real noise and real photos,
 * buildtools/denoise/README.md): noise is found where it is, fine detail isn't taken for noise,
 * pieces give the whole picture's result, RAW data keeps its levels, video averages still parts and
 * follows moving ones.
 */
class DenoiseCoreTest {
    companion object {
        @BeforeClass @JvmStatic fun load() { nu.pattern.OpenCV.loadLocally() }
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** A smooth, shaded picture (no detail) with Gaussian noise of [sigma] in each channel. */
    private fun shadedWithNoise(w: Int, h: Int, sigma: Double, seed: Long): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) {
            val x = it % w; val y = it / w
            val base = 60 + 120.0 * x / w + 40.0 * y / h
            fun ch(k: Double) = Math.round(base * k + rnd.nextGaussian() * sigma).toInt()
            argb(ch(1.0), ch(0.9), ch(0.8))
        }
    }

    @Test
    fun findsKnownNoiseUnderSmoothShading() {
        val w = 512; val h = 512
        for (sigma in listOf(2.0, 5.0, 10.0)) {
            val rnd = Random(3)
            val p = FloatArray(w * h) { (40 + 150.0 * (it % w) / w + rnd.nextGaussian() * sigma).toFloat() }
            val est = NoiseEstimator.sigma(p, w, h)
            assertEquals("σ $sigma", sigma, est, sigma * 0.08)
        }
        val clean = FloatArray(w * h) { (40 + 150.0 * (it % w) / w).toFloat() }
        assertTrue(NoiseEstimator.sigma(clean, w, h) < 0.2)
    }

    @Test
    fun trustsNoiseWhereThePictureIsQuiet() {
        val n = PhotoDenoiser.measure(shadedWithNoise(512, 384, 6.0, 5), 512, 384)
        assertTrue("spread ${n.spread}", n.spread in 1.25..1.6)
        assertEquals(1.0, PhotoDenoiser.confidence(n), 1e-9)
        assertTrue("strength ${PhotoDenoiser.strengthFor(n).luma}", PhotoDenoiser.strengthFor(n).luma > 10f)
        // A clean picture gets (almost) nothing.
        val c = PhotoDenoiser.measure(shadedWithNoise(512, 384, 0.0, 5), 512, 384)
        assertTrue("strength ${PhotoDenoiser.strengthFor(c).luma}", PhotoDenoiser.strengthFor(c).luma < 1f)
    }

    /**
     * Fine detail everywhere and no noise (thin strokes of every contrast, like grass): the quietest
     * blocks are detail, not noise, so the photo must get a gentle setting, far below what their
     * level alone would give.
     */
    @Test
    fun takesFineDetailForDetailNotNoise() {
        val w = 512; val h = 512
        val m = Mat(h, w, CvType.CV_8UC3, Scalar(70.0, 110.0, 90.0))
        val rnd = Random(9)
        repeat(9000) {
            val x = rnd.nextInt(w).toDouble(); val y = rnd.nextInt(h).toDouble()
            val a = (rnd.nextDouble() - 0.5) * 1.2; val len = 6 + rnd.nextInt(30)
            val c = Math.pow(rnd.nextDouble(), 2.0) * 90 * (if (rnd.nextBoolean()) 1 else -1)
            Imgproc.line(m, Point(x, y), Point(x + len * sin(a), y - len * cos(a)), Scalar(70 + c, 110 + c, 90 + c * 0.8), 1, Imgproc.LINE_AA)
        }
        val b = ByteArray(w * h * 3); m.get(0, 0, b); m.release()
        val px = IntArray(w * h) { argb(b[3 * it + 2].toInt() and 255, b[3 * it + 1].toInt() and 255, b[3 * it].toInt() and 255) }
        val n = PhotoDenoiser.measure(px, w, h)
        val levelAlone = PhotoDenoiser.K * (n.luma + 2 * n.color) / 2
        val s = PhotoDenoiser.strengthFor(n).luma
        assertTrue("level alone $levelAlone", levelAlone > 6)
        assertTrue("spread ${n.spread}, strength $s", s < 3f)
    }

    @Test
    fun stripsGiveTheWholePicturesResult() {
        val img = ImageIO.read(DenoiseCoreTest::class.java.getResourceAsStream("/people/02e3d0550a82ef46.jpg"))
        val w = minOf(img.width, 320); val h = minOf(img.height, 240)
        val rnd = Random(2)
        val px = IntArray(w * h)
        img.getRGB(0, 0, w, h, px, 0, w)
        for (i in px.indices) {
            val c = px[i]; val g = rnd.nextGaussian() * 8
            px[i] = argb(((c shr 16) and 255) + g.toInt(), ((c shr 8) and 255) + g.toInt(), (c and 255) + g.toInt())
        }
        val s = PhotoDenoiser.strengthFor(PhotoDenoiser.measure(px, w, h))
        assertTrue(s.luma > 3f)
        val whole = IntArray(w * h)
        PhotoDenoiser.denoiseRows(px, w, h, 0, h, s, whole)
        val strips = IntArray(w * h)
        val m = PhotoDenoiser.MARGIN
        var y0 = 0
        while (y0 < h) {
            val y1 = minOf(h, y0 + 37)
            val a = maxOf(0, y0 - m); val b = minOf(h, y1 + m)
            val band = px.copyOfRange(a * w, b * w)
            val out = IntArray((y1 - y0) * w)
            PhotoDenoiser.denoiseRows(band, w, b - a, y0 - a, y1 - a, s, out)
            System.arraycopy(out, 0, strips, y0 * w, out.size)
            y0 = y1
        }
        assertArrayEquals(whole, strips)
        // And it did remove noise.
        var diff = 0L
        for (i in px.indices) diff += abs((px[i] and 255) - (whole[i] and 255))
        assertTrue(diff > px.size)
    }

    /** Real sensor data (a Pixel 4a's, CC0) with strong added shot and read noise. */
    @Test
    fun rawDenoiserRemovesNoiseAndKeepsLevels() {
        val codec = RawFiles.open(ArraySource(DenoiseCoreTest::class.java.getResourceAsStream("/raw/pixel4a_tiles.dng")!!.readBytes()))
        val ref = codec.decode()
        val w = ref.width; val h = ref.height
        val max = 16383
        val rnd = Random(4)
        val noisy = ShortArray(ref.data.size) {
            val v = (ref.data[it].toInt() and 0xFFFF).toDouble()
            val sd = sqrt(4.0 * maxOf(v - 1023, 0.0) + 60.0 * 60.0)
            Math.round(v + rnd.nextGaussian() * sd).toInt().coerceIn(0, max).toShort()
        }
        // A clipped highlight: those photosites must stay at the maximum.
        for (y in 100 until 140) for (x in 100 until 140) noisy[y * w + x] = max.toShort()
        val data = noisy.copyOf()
        val r = RawDenoiser.denoise(data, w, h, intArrayOf(0, 0, h, w), max)
        assertTrue(r.changed)
        fun rmse(a: ShortArray) = sqrt((0 until a.size).filter { val x = it % w; val y = it / w; !(y in 96 until 144 && x in 96 until 144) }
            .sumOf { val d = (a[it].toInt() and 0xFFFF) - (ref.data[it].toInt() and 0xFFFF); d.toDouble() * d } / (a.size - 48 * 48))
        // The reference carries the phone's own noise (σ ≈ 37 here), which the denoiser removes too, so
        // the error can't fall much below that: 67 → 44 at the measured setting (h 1.0: 60, h 2.0: 57).
        val before = rmse(noisy); val after = rmse(data)
        assertTrue("rmse $before -> $after", after < 0.75 * before)
        for (y in 100 until 140) for (x in 100 until 140) assertEquals(max, data[y * w + x].toInt() and 0xFFFF)
        // Levels: each colour-filter site keeps its mean (no darkening or brightening).
        for (k in 0 until 4) {
            var sa = 0.0; var sb = 0.0; var n = 0
            for (y in (k shr 1) until h step 2) for (x in (k and 1) until w step 2) {
                if (y in 96 until 144 && x in 96 until 144) continue
                sa += noisy[y * w + x].toInt() and 0xFFFF; sb += data[y * w + x].toInt() and 0xFFFF; n++
            }
            assertEquals("site $k", sa / n, sb / n, sa / n * 0.003)
        }
    }

    @Test
    fun videoAveragesStillPartsAndFollowsMovingOnes() {
        val w = 96; val h = 96; val sigma = 0.03f
        val rnd = Random(6)
        fun scene(f: Int, moving: Boolean) = FloatArray(w * h * 3) {
            val i = it / 3; val x = i % w; val y = i / w
            val tex = 0.4f + 0.15f * sin(x * 0.3f) * cos(y * 0.2f)
            val sq = moving && x in (5 + 4 * f) until (25 + 4 * f) && y in 30 until 60
            if (sq) 0.9f else tex
        }
        for (moving in listOf(false, true)) {
            var prev: FloatArray? = null
            var errLate = 0.0; var trail = 0.0; var trailN = 0
            for (f in 0 until 14) {
                val clean = scene(f, moving)
                val cur = FloatArray(clean.size) { (clean[it] + rnd.nextGaussian().toFloat() * sigma).coerceIn(0f, 1f) }
                val out = FloatArray(cur.size)
                VideoDenoise.step(cur, prev, w, h, sigma, VideoDenoise.defaults, out)
                prev = out
                if (f >= 10) {
                    var s = 0.0
                    for (i in out.indices) { val d = out[i] - clean[i]; s += d * d }
                    errLate += sqrt(s / out.size) / 4
                    if (moving) {
                        // Where the square was one frame ago and isn't now: what's left of it.
                        for (y in 30 until 60) for (x in (5 + 4 * (f - 1)) until (5 + 4 * f)) for (c in 0 until 3) {
                            trail += abs(out[(y * w + x) * 3 + c] - clean[(y * w + x) * 3 + c]); trailN++
                        }
                    }
                }
            }
            if (!moving) assertTrue("still: error $errLate vs noise $sigma", errLate < 0.6 * sigma)
            else assertTrue("trail ${trail / trailN}", trail / trailN < 2 * sigma)
        }
    }
}
