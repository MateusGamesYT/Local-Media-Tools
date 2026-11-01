package com.localmediatools.video

import com.localmediatools.highlight.core.ShotKind
import com.localmediatools.video.core.EditClip
import com.localmediatools.video.core.EditOps
import com.localmediatools.video.core.EditProject
import com.localmediatools.video.core.EditSound
import com.localmediatools.video.core.EditSource
import com.localmediatools.video.core.EditText
import com.localmediatools.video.core.EditTimeline
import com.localmediatools.video.core.Framing
import com.localmediatools.video.core.Look
import com.localmediatools.video.core.LookPreset
import com.localmediatools.video.core.OutShape
import com.localmediatools.video.core.TransitionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The video editor's timeline: where each clip is, which part of it plays, how it is framed and coloured. */
class EditTimelineTest {
    private val video = EditSource("v", "v.mp4", ShotKind.VIDEO, 1920, 1080, 20_000, true)
    private val photo = EditSource("p", "p.jpg", ShotKind.PHOTO, 3000, 4000)
    private fun clip(id: Long, s: EditSource = video, inMs: Long = 0, outMs: Long = 4000, t: TransitionKind = TransitionKind.CUT, speed: Float = 1f) =
        EditClip(id, s, inMs, outMs, speed = speed, transition = t, transitionMs = 600)

    @Test fun clipsFollowEachOtherWithTheirTransitions() {
        val p = EditProject(listOf(clip(1), clip(2, t = TransitionKind.CROSSFADE), clip(3, t = TransitionKind.FADE_BLACK), clip(4, s = photo, outMs = 3000)))
        val tl = EditTimeline(p)
        val s = tl.placed.map { it.startMs to it.endMs }
        // A cross-fade overlaps by 600 ms; a fade through black and a cut don't.
        assertEquals(listOf(0L to 4000L, 3400L to 7400L, 7400L to 11400L, 11400L to 14400L), s)
        assertEquals(14_400L, tl.durationMs)
        // In the cross-fade both clips are drawn, the new one fading in over the old.
        val mid = tl.at(3700.0)
        assertEquals(listOf(0, 1), mid.layers.map { it.clip })
        assertEquals(0.5f, mid.layers[1].alpha, 1e-3f); assertEquals(1f, mid.layers[0].alpha, 1e-6f)
        // Through black: the clip before fades out over its last 300 ms, the next in over its first 300 ms.
        assertEquals(0.5f, tl.at(7400.0 - 150).layers.single().alpha, 1e-3f)
        assertEquals(0.5f, tl.at(7400.0 + 150).layers.single().alpha, 1e-3f)
    }

    @Test fun speedPlaysMoreOrLessOfTheSource() {
        val p = EditProject(listOf(clip(1, inMs = 2000, outMs = 10_000, speed = 2f)))
        val tl = EditTimeline(p)
        assertEquals(4000L, tl.durationMs)
        assertEquals(2000L + 2000L, tl.at(1000.0).layers.single().sourceMs)
        assertEquals(listOf(0 to 2000L), listOf(tl.clipAt(0)!!.let { it.first to it.second }))
        val slow = EditTimeline(EditProject(listOf(clip(1, inMs = 0, outMs = 1000, speed = 0.25f))))
        assertEquals(4000L, slow.durationMs)
        assertEquals(250L, slow.at(1000.0).layers.single().sourceMs)
    }

    @Test fun splittingKeepsEveryMomentInItsPlace() {
        val p = EditProject(listOf(clip(1, inMs = 1000, outMs = 9000, speed = 2f)))
        val q = EditOps.split(p, 0, 1500, 2)!!
        assertEquals(2, q.clips.size)
        val a = EditTimeline(p); val b = EditTimeline(q)
        assertEquals(a.durationMs, b.durationMs)
        for (t in listOf(0.0, 1000.0, 1499.0, 1500.0, 3000.0, 3999.0)) assertEquals("$t", a.at(t).layers.single().sourceMs, b.at(t).layers.single().sourceMs)
        // Not too close to an end.
        assertNull(EditOps.split(p, 0, 200, 3))
        // Moving, removing and trimming.
        val r = EditOps.move(q, 1, 0)
        assertEquals(listOf(2L, 1L), r.clips.map { it.id })
        assertEquals(1, EditOps.remove(q, 0).clips.size)
        val t = EditOps.trim(p, 0, 19_900, 30_000).clips.single()
        assertEquals(19_500L, t.inMs); assertEquals(20_000L, t.outMs)
    }

    @Test fun framingFitsFillsOrFollows() {
        // A landscape video in a portrait one.
        val fit = EditTimeline(EditProject(listOf(clip(1)), shape = OutShape.PORTRAIT))
        val l = fit.at(100.0).layers.single()
        assertTrue(l.backdrop)
        assertEquals(1f, l.dst.w, 1e-4f); assertEquals(9f / 16f * 9f / 16f, l.dst.h, 1e-3f)
        val fill = EditTimeline(EditProject(listOf(clip(1).copy(framing = Framing.FILL, panX = 1f)), shape = OutShape.PORTRAIT))
        val f = fill.at(100.0).layers.single()
        assertTrue(!f.backdrop)
        // Panned all the way right: the right edge of the picture.
        assertEquals(1f, f.src.x + f.src.w, 1e-4f)
        assertEquals((9f / 16f) / (16f / 9f), f.src.w, 1e-4f)
        // Zoomed twice: half as wide.
        val z = EditTimeline(EditProject(listOf(clip(1).copy(framing = Framing.FILL, scale = 2f)), shape = OutShape.PORTRAIT)).at(0.0).layers.single()
        assertEquals((9f / 16f) / (16f / 9f) / 2, z.src.w, 1e-4f)
        // The original shape needs no backdrop; a turned clip changes its shape.
        assertTrue(!EditTimeline(EditProject(listOf(clip(1)))).at(0.0).layers.single().backdrop)
        val turned = clip(1).copy(rotation = 90)
        assertEquals(9f / 16f, turned.aspect, 1e-4f)
        assertEquals(9f / 16f, EditProject(listOf(turned)).outAspect, 1e-4f)
    }

    @Test fun looksAreOneColourMatrix() {
        assertNull(Look().matrix())
        fun apply(m: FloatArray, r: Float, g: Float, b: Float) = FloatArray(3) { row -> m[row] * r + m[4 + row] * g + m[8 + row] * b + m[12 + row] }
        val mono = Look(LookPreset.MONO).matrix()!!
        val c = apply(mono, 0.8f, 0.3f, 0.1f)
        assertEquals(c[0], c[1], 1e-5f); assertEquals(c[1], c[2], 1e-5f)
        val bright = Look(brightness = 0.5f).matrix()!!
        assertTrue(apply(bright, 0.5f, 0.5f, 0.5f).all { it > 0.6f })
        val warm = Look(warmth = 1f).matrix()!!
        val w = apply(warm, 0.5f, 0.5f, 0.5f)
        assertTrue(w[0] > w[1] && w[1] > w[2])
        // Mid-grey stays mid-grey under contrast.
        val con = apply(Look(contrast = 0.8f).matrix()!!, 0.5f, 0.5f, 0.5f)
        assertTrue(con.all { kotlin.math.abs(it - 0.5f) < 1e-5f })
    }

    @Test fun textsFadeInAndOut() {
        val p = EditProject(listOf(clip(1)), texts = listOf(EditText(1, "Hello", 1000, 3000)))
        val tl = EditTimeline(p)
        assertTrue(tl.at(500.0).captions.isEmpty())
        assertEquals(0.5f, tl.at(1125.0).captions.single().alpha, 1e-3f)
        assertEquals(1f, tl.at(2000.0).captions.single().alpha, 1e-6f)
        assertNotNull(tl.at(2900.0).captions.singleOrNull())
        assertEquals(1, tl.captionCount)
    }

    @Test fun soundFollowsThePictures() {
        val silent = video.copy(hasAudio = false)
        val p = EditProject(listOf(clip(1), clip(2, t = TransitionKind.CROSSFADE).copy(volume = 0.5f, speed = 2f, outMs = 8000),
            clip(3, s = silent), clip(4, t = TransitionKind.FADE_BLACK).copy(volume = 0f), clip(5, s = photo, outMs = 2000)))
        val tl = EditTimeline(p)
        val parts = EditSound.parts(tl)
        // Silent files, muted clips and photos make no sound.
        assertEquals(listOf(0, 1), parts.map { it.clip })
        // The first fades out under the cross-fade as the second fades in; the second plays twice as fast.
        assertEquals(600L, parts[0].fadeOutMs); assertEquals(600L, parts[1].fadeInMs)
        assertEquals(2f, parts[1].speed, 0f); assertEquals(0.5f, parts[1].gain, 0f)
        assertEquals(3400L, parts[1].startMs)
        // The music steps back while they play (unless asked not to).
        assertEquals(2, EditSound.intervals(tl).size)
        assertTrue(EditSound.intervals(EditTimeline(p.copy(duckMusic = false))).isEmpty())
    }
}
