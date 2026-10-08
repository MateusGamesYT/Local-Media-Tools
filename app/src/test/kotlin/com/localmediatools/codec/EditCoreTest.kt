package com.localmediatools.codec

import com.localmediatools.codec.edit.AdjustKey
import com.localmediatools.codec.edit.Adjustments
import com.localmediatools.codec.edit.ColorPipeline
import com.localmediatools.codec.edit.CropRect
import com.localmediatools.codec.edit.FilterPreset
import com.localmediatools.codec.edit.FilterSpec
import com.localmediatools.codec.edit.Geometry
import com.localmediatools.codec.edit.GeometryPlan
import com.localmediatools.codec.edit.MaskOps
import com.localmediatools.codec.edit.MosaicOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EditCoreTest {
    private fun near(a: Double, b: Double, eps: Double = 1e-6) = abs(a - b) < eps

    private fun mapCorner(p: GeometryPlan, x: Double, y: Double) = p.forward.mapX(x, y) to p.forward.mapY(x, y)

    @Test fun identityGeometryKeepsSizeAndPixels() {
        val p = GeometryPlan(400, 300, Geometry())
        assertEquals(400, p.outW); assertEquals(300, p.outH)
        val (x, y) = mapCorner(p, 12.5, 7.0)
        assertTrue(near(x, 12.5) && near(y, 7.0))
    }

    @Test fun quarterTurnsMoveCornersClockwise() {
        // Source top-left corner (0,0) ends at the top-right after one clockwise turn.
        val one = GeometryPlan(400, 300, Geometry(quarterTurns = 1))
        assertEquals(300, one.outW); assertEquals(400, one.outH)
        val (x1, y1) = mapCorner(one, 0.0, 0.0); assertTrue(near(x1, 300.0) && near(y1, 0.0))
        val two = GeometryPlan(400, 300, Geometry(quarterTurns = 2))
        val (x2, y2) = mapCorner(two, 0.0, 0.0); assertTrue(near(x2, 400.0) && near(y2, 300.0))
        val three = GeometryPlan(400, 300, Geometry(quarterTurns = 3))
        val (x3, y3) = mapCorner(three, 0.0, 0.0); assertTrue(near(x3, 0.0) && near(y3, 400.0))
    }

    @Test fun flipMirrorsHorizontally() {
        val p = GeometryPlan(400, 300, Geometry(flipH = true))
        val (x, y) = mapCorner(p, 10.0, 20.0)
        assertTrue(near(x, 390.0) && near(y, 20.0))
    }

    @Test fun straightenZoomsInSoNoCornerIsEmpty() {
        val p = GeometryPlan(400, 300, Geometry(straighten = 10.0))
        assertTrue(p.outW < 400 && p.outH < 300)
        assertEquals(400.0 / 300, p.outW.toDouble() / p.outH, 0.02)
        // Every output corner maps back inside the source.
        for ((x, y) in listOf(0.0 to 0.0, p.outW.toDouble() to 0.0, 0.0 to p.outH.toDouble(), p.outW.toDouble() to p.outH.toDouble())) {
            val sx = p.inverse.mapX(x, y); val sy = p.inverse.mapY(x, y)
            assertTrue("corner ($x,$y) → ($sx,$sy)", sx >= -0.01 && sx <= 400.01 && sy >= -0.01 && sy <= 300.01)
        }
    }

    @Test fun cropSelectsTheRequestedPart() {
        val p = GeometryPlan(400, 300, Geometry(crop = CropRect(0.25, 0.5, 0.75, 1.0)))
        assertEquals(200, p.outW); assertEquals(150, p.outH)
        val (x, y) = mapCorner(p, 100.0, 150.0); assertTrue(near(x, 0.0) && near(y, 0.0))
        val b = p.sourceBounds(0.0, 0.0, p.outW.toDouble(), p.outH.toDouble(), pad = 0)
        assertArrayEquals(intArrayOf(100, 150, 300, 300), b)
    }

    @Test fun rotatingKeepsTheVisualCrop() {
        val g = Geometry(crop = CropRect(0.1, 0.2, 0.5, 0.6))
        val p0 = GeometryPlan(400, 300, g)
        val p1 = GeometryPlan(400, 300, g.rotatedClockwise())
        // Same source pixels, rotated: output sizes swap.
        assertEquals(p0.outW, p1.outH); assertEquals(p0.outH, p1.outW)
        val c0 = p0.sourceBounds(0.0, 0.0, p0.outW.toDouble(), p0.outH.toDouble(), 0)
        val c1 = p1.sourceBounds(0.0, 0.0, p1.outW.toDouble(), p1.outH.toDouble(), 0)
        assertArrayEquals(c0, c1)
        // Four turns and two flips bring everything back.
        var g2 = g
        repeat(4) { g2 = g2.rotatedClockwise() }
        assertEquals(0, g2.turns)
        assertEquals(g.crop.l, g2.crop.l, 1e-12); assertEquals(g.crop.t, g2.crop.t, 1e-12)
        assertEquals(g.crop.r, g2.crop.r, 1e-12); assertEquals(g.crop.b, g2.crop.b, 1e-12)
        val flipped = g.flippedHorizontally().flippedHorizontally()
        assertEquals(g.flipH, flipped.flipH); assertEquals(g.straighten, flipped.straighten, 1e-12)
        assertEquals(g.crop.l, flipped.crop.l, 1e-12); assertEquals(g.crop.r, flipped.crop.r, 1e-12)
        // Flip then rotate: still the same source region.
        val p2 = GeometryPlan(400, 300, g.flippedHorizontally().rotatedClockwise())
        assertArrayEquals(c0, p2.sourceBounds(0.0, 0.0, p2.outW.toDouble(), p2.outH.toDouble(), 0))
    }

    @Test fun centeredCropHasTheAspect() {
        val c = GeometryPlan.centeredCrop(400.0, 300.0, 1.0)
        assertEquals(1.0, c.width * 400 / (c.height * 300), 1e-9)
        assertEquals(0.5, (c.l + c.r) / 2, 1e-9)
    }

    @Test fun neutralColourPipelineChangesNothing() {
        val px = IntArray(256) { (0xFF shl 24) or (it shl 16) or ((255 - it) shl 8) or ((it * 7) and 255) }
        val copy = px.copyOf()
        ColorPipeline(Adjustments(), FilterSpec()).apply(px, 0, 16, 16, 0, 16, 16)
        assertArrayEquals(copy, px)
    }

    @Test fun adjustmentsMoveTheRightWay() {
        fun luma(c: Int) = ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)
        val grey = (0xFF shl 24) or (0x70 shl 16) or (0x70 shl 8) or 0x70
        fun run(a: Adjustments, f: FilterSpec = FilterSpec(), c: Int = grey): Int { val px = intArrayOf(c); ColorPipeline(a, f).apply(px, 0, 1, 1, 0, 1, 1); return px[0] }
        assertTrue(luma(run(Adjustments().with(AdjustKey.EXPOSURE, 0.5f))) > luma(grey))
        assertTrue(luma(run(Adjustments().with(AdjustKey.BRIGHTNESS, -0.5f))) < luma(grey))
        val warm = run(Adjustments().with(AdjustKey.WARMTH, 0.8f))
        assertTrue(((warm shr 16) and 255) > (warm and 255))
        val red = (0xFF shl 24) or (200 shl 16) or (60 shl 8) or 50
        val mono = run(Adjustments(), FilterSpec(FilterPreset.MONO), red)
        assertTrue(abs(((mono shr 16) and 255) - (mono and 255)) <= 2)
        val desat = run(Adjustments().with(AdjustKey.SATURATION, -1f), c = red)
        assertTrue(abs(((desat shr 16) and 255) - ((desat shr 8) and 255)) <= 1)
        // Alpha is preserved.
        assertEquals(0x80, run(Adjustments().with(AdjustKey.CONTRAST, 0.7f), c = 0x80406080.toInt()) ushr 24)
        // Exposure tone curve is monotonic.
        val pipe = ColorPipeline(Adjustments().with(AdjustKey.EXPOSURE, 0.4f).with(AdjustKey.CONTRAST, 0.5f).with(AdjustKey.SHADOWS, 0.6f).with(AdjustKey.HIGHLIGHTS, -0.6f), FilterSpec())
        val ramp = IntArray(256) { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }
        pipe.apply(ramp, 0, 256, 1, 0, 256, 1)
        for (i in 1 until 256) assertTrue("non-monotonic at $i", (ramp[i] and 255) >= (ramp[i - 1] and 255))
    }

    @Test fun vignetteDarkensCornersNotTheCentre() {
        val w = 64; val h = 48
        val px = IntArray(w * h) { (0xFF shl 24) or 0x808080 }
        ColorPipeline(Adjustments().with(AdjustKey.VIGNETTE, 1f), FilterSpec()).apply(px, 0, w, h, 0, w, h)
        assertTrue((px[0] and 255) < 0x60)
        assertEquals(0x80, px[(h / 2) * w + w / 2] and 255)
    }

    @Test fun sharpenIncreasesEdgeContrastAndSoftenReducesIt() {
        val w = 16; val rows = 8; val pad = ColorPipeline.SHARPEN_PAD
        val src = IntArray(w * (rows + 2 * pad)) { i -> if (i % w < 8) (0xFF shl 24) or 0x404040 else (0xFF shl 24) or 0xC0C0C0 }
        val sharp = IntArray(w * rows); ColorPipeline.sharpenRows(src, w, rows + 2 * pad, pad, 1f, sharp, 0)
        assertTrue((sharp[7] and 255) < 0x40 && (sharp[8] and 255) > 0xC0)
        val soft = IntArray(w * rows); ColorPipeline.sharpenRows(src, w, rows + 2 * pad, pad, -1f, soft, 0)
        assertTrue((soft[7] and 255) > 0x40 && (soft[8] and 255) < 0xC0)
        assertEquals(0x40, soft[0] and 255)
    }

    @Test fun featherKeepsTheInsideOpaqueAndFadesOut() {
        val w = 40; val h = 40
        val m = ByteArray(w * h) { i -> if (i % w in 10..29 && i / w in 10..29) -1 else 0 }
        val f = MaskOps.feather(m, w, h, 2)
        assertEquals(255, f[20 * w + 20].toInt() and 255)
        assertEquals(0, f[2 * w + 2].toInt() and 255)
        val edge = f[20 * w + 10].toInt() and 255
        assertTrue(edge in 60..200)
    }

    @Test fun contextWindowCoversTheHoleAndStaysInside() {
        val c = MaskOps.contextFor(4000, 3000, 3900, 2900, 3990, 2990)
        assertTrue(c.left >= 0 && c.top >= 0 && c.left + c.width <= 4000 && c.top + c.height <= 3000)
        assertTrue(c.left <= 3900 && c.top <= 2900 && c.left + c.width >= 3990 && c.top + c.height >= 2990)
        assertTrue(c.width >= 512 && c.height >= 512)
        val big = MaskOps.contextFor(4000, 3000, 1000, 1000, 2600, 1800)
        assertEquals(512.0 / maxOf(big.width, big.height), big.scale, 1e-9)
        val small = MaskOps.contextFor(300, 200, 100, 50, 140, 90)
        assertEquals(300, small.width); assertEquals(200, small.height)
    }

    @Test fun noiseEstimateFindsGrain() {
        val w = 120; val h = 90
        val rnd = java.util.Random(3)
        val flat = IntArray(w * h) { (0xFF shl 24) or 0x808080 }
        assertEquals(0.0, MaskOps.noiseSigma(flat, w, h, null), 0.01)
        val noisy = IntArray(w * h) { val v = (128 + rnd.nextGaussian() * 6).toInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val s = MaskOps.noiseSigma(noisy, w, h, null)
        assertTrue("sigma $s", s in 4.0..8.0)
    }

    @Test fun pixelateAveragesGridCellsInsideTheMask() {
        val w = 8; val h = 4
        val px = IntArray(w * h) { i -> (0xFF shl 24) or (if (i % 2 == 0) 0x000000 else 0x0000FF) }
        val mask = ByteArray(w * h) { i -> if (i % w < 4) -1 else 0 }
        val alpha = ByteArray(w * h)
        MosaicOps.pixelate(px, w, h, 4, 0, 0, mask, alpha)
        assertEquals((0xFF shl 24) or 0x00007F, px[0])
        assertEquals(px[0], px[3 * w + 3])
        assertEquals(-1, alpha[0].toInt())
        assertEquals(0, alpha[5].toInt())
        assertEquals((0xFF shl 24) or 0x0000FF, px[5])
    }
}
