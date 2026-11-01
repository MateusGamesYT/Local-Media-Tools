package com.localmediatools.video

import com.localmediatools.video.core.Box
import com.localmediatools.video.core.Found
import com.localmediatools.video.core.Reframe
import com.localmediatools.video.core.ReframeSample
import com.localmediatools.video.core.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** Auto reframe's planning (the parts that don't need a phone); measured on real videos in buildtools/video. */
class ReframeTest {
    private val landscape = 16f / 9f
    private val portrait = Reframe.Options(9f / 16f)

    private fun person(x: Float, y: Float = 0.25f, w: Float = 0.12f, h: Float = 0.6f, score: Float = 0.9f) = Found(SubjectKind.PERSON, Box(x, y, w, h), score)

    private fun plan(samples: List<ReframeSample>, o: Reframe.Options = portrait) =
        Reframe.path(samples, Reframe.subjects(samples, landscape, o.outAspect), landscape, o)

    @Test fun bandedSolverMatchesADenseOne() {
        val rnd = Random(4)
        for (n in listOf(1, 2, 3, 5, 17, 60)) {
            val d = DoubleArray(n) { rnd.nextDouble() }
            val w = DoubleArray(n) { if (rnd.nextInt(4) == 0) 0.0 else rnd.nextDouble() * 3 }
            val dt = DoubleArray(n) { if (it == 0) 0.2 else 0.1 + rnd.nextDouble() * 0.3 }
            val p = Reframe.Params(speed = 0.7, accel = 0.3)
            val x = Reframe.solve(d, w, dt, p)
            // The same system written out densely and solved by Gaussian elimination with pivoting.
            val a = Array(n) { DoubleArray(n) }; val r = DoubleArray(n)
            for (i in 0 until n) { a[i][i] += w[i] + 1e-6; r[i] += w[i] * d[i] + 0.5e-6 }
            for (i in 0 until n - 1) { val c = p.speed / dt[i + 1]; a[i][i] += c; a[i + 1][i + 1] += c; a[i][i + 1] -= c; a[i + 1][i] -= c }
            for (i in 1 until n - 1) {
                val h = (dt[i] + dt[i + 1]) / 2; val c = p.accel / (h * h * h)
                val v = doubleArrayOf(1.0, -2.0, 1.0)
                for (j in 0..2) for (k in 0..2) a[i - 1 + j][i - 1 + k] += c * v[j] * v[k]
            }
            for (col in 0 until n) {
                val piv = (col until n).maxBy { abs(a[it][col]) }
                val t = a[piv]; a[piv] = a[col]; a[col] = t; val tr = r[piv]; r[piv] = r[col]; r[col] = tr
                for (row in col + 1 until n) { val f = a[row][col] / a[col][col]; for (k in col until n) a[row][k] -= f * a[col][k]; r[row] -= f * r[col] }
            }
            val y = DoubleArray(n)
            for (i in n - 1 downTo 0) { var s = r[i]; for (k in i + 1 until n) s -= a[i][k] * y[k]; y[i] = s / a[i][i] }
            for (i in 0 until n) assertEquals("n=$n i=$i", y[i], x[i], 1e-9)
        }
    }

    @Test fun aStillSubjectGetsAStillCameraOnIt() {
        val s = (0 until 50).map { ReframeSample(it * 200L, listOf(person(0.7f + 0.005f * (it % 3)))) }
        val keys = plan(s)
        assertTrue(keys.all { abs(it.crop.x - keys[0].crop.x) < 1e-6f })
        val c = keys[0].crop
        assertEquals(9f / 16f / landscape, c.w, 1e-4f); assertEquals(1f, c.h, 1e-4f)
        // The person is inside.
        assertTrue(c.x <= 0.7f && c.x + c.w >= 0.83f)
    }

    @Test fun aWalkingSubjectStaysInFrameAndTheCameraMovesSmoothly() {
        // Someone walking from left to right over 10 seconds, sampled 5 times a second, with detector jitter.
        val rnd = Random(1)
        val s = (0 until 50).map { i -> ReframeSample(i * 200L, listOf(person(0.05f + 0.8f * i / 49f + (rnd.nextFloat() - 0.5f) * 0.02f))) }
        val keys = plan(s)
        val subj = Reframe.subjects(s, landscape, portrait.outAspect)
        for ((k, b) in keys.zip(subj)) assertTrue("${k.ms}", k.crop.x <= b!!.x + 0.002f && k.crop.x + k.crop.w >= b.x1 - 0.002f)
        // No reversals: the camera only ever moves right (the jitter is smoothed away).
        for (i in 1 until keys.size) assertTrue(keys[i].crop.x >= keys[i - 1].crop.x - 1e-4f)
    }

    @Test fun theSubjectIsFollowedNotWhoeverIsBiggest() {
        // Person A in the middle; person B, a bit bigger, walks in on the far left halfway through.
        val s = (0 until 40).map { i ->
            val f = arrayListOf(person(0.45f))
            if (i >= 20) f.add(person(0.02f, w = 0.14f, h = 0.7f, score = 0.9f))
            ReframeSample(i * 200L, f)
        }
        val subj = Reframe.subjects(s, landscape, portrait.outAspect)
        assertTrue(subj.drop(20).all { it!!.x in 0.4f..0.5f })
    }

    @Test fun cutsStartANewPathWithoutGliding() {
        val s = (0 until 20).map { i -> ReframeSample(i * 200L + if (i >= 10) 37 else 0, listOf(person(if (i < 10) 0.1f else 0.8f)), cut = i == 10) }
        val keys = plan(s)
        assertEquals(0, keys[9].shot); assertEquals(1, keys[10].shot)
        // Just before the new shot's first frame the old crop holds; from it, the new one.
        val before = Reframe.at(keys, keys[10].ms - 1); val after = Reframe.at(keys, keys[10].ms)
        assertEquals(keys[9].crop.x, before.x, 1e-6f); assertEquals(keys[10].crop.x, after.x, 1e-6f)
        assertTrue(after.x > 0.5f && before.x < 0.3f)
    }

    @Test fun zoomFillsThePictureWithTheSubjectWithinLimits() {
        // A small person far away in a landscape video kept landscape: zoomed in, at most 2×.
        val s = (0 until 20).map { ReframeSample(it * 200L, listOf(person(0.6f, y = 0.5f, w = 0.04f, h = 0.15f))) }
        val o = Reframe.Options(landscape, zoom = 1f)
        val keys = plan(s, o)
        assertEquals(1f / Reframe.MAX_ZOOM, keys[0].crop.w, 1e-4f)
        assertTrue(keys[0].crop.x <= 0.6f && keys[0].crop.x + keys[0].crop.w >= 0.64f)
        assertTrue(keys[0].crop.y <= 0.5f && keys[0].crop.y + keys[0].crop.h >= 0.65f)
        // Zoom off: the whole picture.
        assertEquals(1f, plan(s, Reframe.Options(landscape))[0].crop.w, 1e-4f)
    }

    @Test fun nothingFoundKeepsTheCentre() {
        val s = (0 until 10).map { ReframeSample(it * 200L, emptyList()) }
        assertTrue(plan(s).all { abs(it.crop.cx - 0.5f) < 1e-4f })
    }

    @Test fun beforeTheSubjectIsFoundTheCameraWaitsWhereItWillBe() {
        // Nothing found for the first 3 s, then someone right of the middle walking left.
        val s = (0 until 40).map { i -> ReframeSample(i * 200L, if (i < 15) emptyList() else listOf(person(0.7f - 0.01f * (i - 15)))) }
        val keys = plan(s)
        // From the start the crop is on them (not at an edge), and it never jumps.
        assertTrue("${keys[0].crop.x}", keys[0].crop.x <= 0.7f && keys[0].crop.x + keys[0].crop.w >= 0.82f)
        for (i in 1 until keys.size) assertTrue(abs(keys[i].crop.x - keys[i - 1].crop.x) < 0.05f)
    }

    /** A person too close to fit across a portrait picture: their face stays in, as near their middle as that allows. */
    @Test fun aPersonTooWideKeepsTheirHeadIn() {
        // Body 0.15–0.55 across (wider than the crop's 0.316), face at its right, 0.44–0.50 (as in the real clip in VideoEditorTest).
        val body = Found(SubjectKind.PERSON, Box(0.15f, 0.05f, 0.40f, 0.94f), 0.93f)
        val face = Found(SubjectKind.FACE, Box(0.44f, 0.18f, 0.06f, 0.19f), 0.85f)
        val s = (0 until 30).map { ReframeSample(it * 200L, if (it % 4 == 3) listOf(body) else listOf(body, face)) }
        val keys = plan(s)
        // The head: the face widened by 30 % of its width on each side (0.422–0.518), with the margin around it.
        val headX0 = 0.44f - 0.06f * 0.3f; val headX1 = headX0 + 0.06f * 1.6f; val room = 0.06f * 1.6f * Reframe.DEFAULT.margin
        for (k in keys) {
            assertTrue("${k.crop}", k.crop.x <= headX0 - room && k.crop.x1 >= headX1 + room - 1e-3f)
            // And no further right than the head needs: as much of the body as possible.
            assertTrue("${k.crop}", k.crop.x1 <= headX1 + room + 0.01f)
        }
        // Without the rule the crop sits on the body's middle and the face is at its very edge.
        val off = Reframe.Params(heads = false)
        val mid = Reframe.path(s, Reframe.subjects(s, landscape, 9f / 16f, off), landscape, portrait, off)
        assertEquals(0.35f, mid[0].crop.cx, 0.01f)
    }
}
