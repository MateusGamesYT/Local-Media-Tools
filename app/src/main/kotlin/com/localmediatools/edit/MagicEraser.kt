package com.localmediatools.edit

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import com.localmediatools.codec.edit.MaskOps
import com.localmediatools.codec.edit.MosaicOps
import com.localmediatools.core.UserFacingException
import com.localmediatools.stitch.OpenCvLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.photo.Photo
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fills masked pixels of a [MaskOps.MODEL_SIZE]² ARGB image. */
interface Inpainter : Closeable {
    val label: String
    val isAi: Boolean
    fun inpaint(image: IntArray, hole: BooleanArray): IntArray
}

/**
 * MI-GAN (Picsart AI Research, MIT licence) converted to TensorFlow Lite. Runs fully on the phone:
 * input is the photo with the hole blanked plus the mask, output the completed 512×512 picture.
 */
class MiganInpainter private constructor(private val interpreter: Interpreter) : Inpainter {
    override val label = "AI · MI-GAN on-device"
    override val isAi = true
    private val n = MaskOps.MODEL_SIZE * MaskOps.MODEL_SIZE
    private val input = ByteBuffer.allocateDirect(n * 4 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())

    override fun inpaint(image: IntArray, hole: BooleanArray): IntArray = synchronized(this) {
        input.rewind()
        val fb = input.asFloatBuffer()
        for (i in 0 until n) {
            val c = image[i]
            if (hole[i]) {
                fb.put(-0.5f); fb.put(0f); fb.put(0f); fb.put(0f)
            } else {
                fb.put(0.5f)
                fb.put(((c shr 16) and 255) / 127.5f - 1f)
                fb.put(((c shr 8) and 255) / 127.5f - 1f)
                fb.put((c and 255) / 127.5f - 1f)
            }
        }
        input.rewind(); output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val ob = output.asFloatBuffer()
        IntArray(n) { i ->
            val r = (ob.get() * 127.5f + 127.5f).roundToInt().coerceIn(0, 255)
            val g = (ob.get() * 127.5f + 127.5f).roundToInt().coerceIn(0, 255)
            val b = (ob.get() * 127.5f + 127.5f).roundToInt().coerceIn(0, 255)
            if (hole[i]) (0xFF shl 24) or (r shl 16) or (g shl 8) or b else image[i]
        }
    }

    override fun close() = interpreter.close()

    companion object {
        const val ASSET = "models/migan_512_fp16.tflite"

        fun create(ctx: Context, threads: Int): MiganInpainter {
            val afd = ctx.assets.openFd(ASSET)
            val buf = FileInputStream(afd.fileDescriptor).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength) }
            afd.close()
            val it = Interpreter(buf, Interpreter.Options().setNumThreads(threads.coerceIn(1, 4)))
            val inShape = it.getInputTensor(0).shape()
            val outShape = it.getOutputTensor(0).shape()
            if (!inShape.contentEquals(intArrayOf(1, 512, 512, 4)) || !outShape.contentEquals(intArrayOf(1, 512, 512, 3))) {
                it.close()
                throw IllegalStateException("Unexpected eraser model shape ${inShape.toList()} → ${outShape.toList()}")
            }
            return MiganInpainter(it)
        }
    }
}

/** Classic diffusion-based inpainting (OpenCV Telea): fine for thin or small blemishes. */
class TeleaInpainter : Inpainter {
    override val label = "Basic fill (AI unavailable)"
    override val isAi = false

    override fun inpaint(image: IntArray, hole: BooleanArray): IntArray {
        OpenCvLoader.ensure()
        val s = MaskOps.MODEL_SIZE
        val rgb = ByteArray(s * s * 3)
        val m = ByteArray(s * s)
        for (i in 0 until s * s) {
            val c = image[i]
            rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte()
            m[i] = if (hole[i]) -1 else 0
        }
        val src = Mat(s, s, CvType.CV_8UC3); src.put(0, 0, rgb)
        val mask = Mat(s, s, CvType.CV_8UC1); mask.put(0, 0, m)
        val dst = Mat()
        try {
            Photo.inpaint(src, mask, dst, 9.0, Photo.INPAINT_TELEA)
            dst.get(0, 0, rgb)
        } finally { src.release(); mask.release(); dst.release() }
        return IntArray(s * s) { i ->
            if (!hole[i]) image[i] else (0xFF shl 24) or ((rgb[i * 3].toInt() and 255) shl 16) or ((rgb[i * 3 + 1].toInt() and 255) shl 8) or (rgb[i * 3 + 2].toInt() and 255)
        }
    }

    override fun close() {}
}

object Inpainters {
    /** Whether the AI eraser can run comfortably here, and why not otherwise. */
    fun aiAvailability(ctx: Context): Pair<Boolean, String> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        if (am.isLowRamDevice) return false to "This phone is set up as a low-memory device."
        if (mi.totalMem in 1 until (2L shl 30)) return false to "Needs at least 2 GB of RAM."
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) return false to "Needs a 64-bit processor."
        val present = try { ctx.assets.openFd(MiganInpainter.ASSET).use { true } } catch (_: Exception) { false }
        if (!present) return false to "The on-device model is missing from this build."
        return true to "On-device AI"
    }

    /** The AI model when possible, otherwise the classic fill (always available). */
    var factory: ((Context) -> Inpainter)? = null

    fun create(ctx: Context): Inpainter {
        factory?.let { return it(ctx) }
        if (aiAvailability(ctx).first) {
            try { return MiganInpainter.create(ctx, Runtime.getRuntime().availableProcessors().coerceAtMost(4)) } catch (e: Throwable) {
                android.util.Log.w("LMT", "AI eraser unavailable", e)
            }
        }
        return TeleaInpainter()
    }
}

/** Turns brush strokes into retouch patches (full resolution, feathered edges). */
object Retouch {
    /** Downscale to [tw]×[th] by repeated halving (box filter), then a final bilinear step. */
    private fun shrink(src: Bitmap, tw: Int, th: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= tw && cur.height / 2 >= th && cur.width >= 2 && cur.height >= 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (cur.width == tw && cur.height == th) return if (cur === src) Bitmap.createBitmap(src) else cur
        val out = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== src) cur.recycle()
        return out
    }

    /** Rough memory need per pixel of a retouched area (bitmaps, masks and pixel arrays). */
    private const val BYTES_PER_PIXEL = 22L

    private fun clampRect(r: RectF, w: Int, h: Int) = Rect(
        floor(r.left).toInt().coerceIn(0, w - 1), floor(r.top).toInt().coerceIn(0, h - 1),
        ceil(r.right).toInt().coerceIn(1, w), ceil(r.bottom).toInt().coerceIn(1, h),
    ).also { if (it.right <= it.left) it.right = it.left + 1; if (it.bottom <= it.top) it.bottom = it.top + 1 }

    private fun alphaMask(strokes: List<Stroke>, area: Rect, extra: Float): ByteArray {
        val m = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ALPHA_8)
        Stroke.draw(Canvas(m), strokes, area.left.toFloat(), area.top.toFloat(), 1f, extra)
        val bytes = ByteArray(area.width() * area.height())
        m.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        m.recycle()
        return bytes
    }

    /**
     * Removes what the strokes cover. The model sees a window around the area (with context) at
     * 512 px; its result is scaled back to full resolution, blended with a feathered edge and given
     * grain that matches the photo.
     */
    fun erase(rs: RetouchedSource, strokes: List<Stroke>, inpainter: Inpainter, store: PatchStore, budget: Long): RetouchPatch {
        val w = rs.width; val h = rs.height
        val minSide = min(w, h).toFloat()
        val d = max(3f, minSide * 0.005f)          // grow the brush a little: halos and shadows
        val f = max(1f, d / 3f)                    // feather
        val holeExtra = d + 3 * f
        val area = clampRect(Stroke.bounds(strokes, holeExtra + 1), w, h)
        if (area.width().toLong() * area.height() * BYTES_PER_PIXEL > budget) {
            throw UserFacingException("That area is too large to erase in one go with the memory available. Erase it in smaller parts.")
        }
        val plan = MaskOps.contextFor(w, h, area.left, area.top, area.right, area.bottom)
        val ctxRect = Rect(plan.left, plan.top, plan.left + plan.width, plan.top + plan.height)
        var sample = 1
        while (max(plan.width, plan.height) / (sample * 2) >= MaskOps.MODEL_SIZE) sample *= 2
        val ctxBmp = rs.decode(ctxRect, sample, budget)
        val size = MaskOps.MODEL_SIZE
        val sw = plan.scaledW; val sh = plan.scaledH
        val model = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val mc = Canvas(model)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        mc.drawBitmap(ctxBmp, null, Rect(0, 0, sw, sh), paint)
        // Pad to a square by repeating the edge pixels (the padding is never part of the hole).
        if (sw < size) mc.drawBitmap(model, Rect(sw - 1, 0, sw, sh), Rect(sw, 0, size, sh), null)
        if (sh < size) mc.drawBitmap(model, Rect(0, sh - 1, size, sh), Rect(0, sh, size, size), null)
        ctxBmp.recycle()
        val img = IntArray(size * size)
        model.getPixels(img, 0, size, 0, 0, size, size)
        val holeBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        Stroke.draw(Canvas(holeBmp), strokes, plan.left.toFloat(), plan.top.toFloat(), plan.scale.toFloat(), holeExtra + 1.5f / plan.scale.toFloat())
        val hb = ByteArray(size * size)
        holeBmp.copyPixelsToBuffer(ByteBuffer.wrap(hb)); holeBmp.recycle()
        val hole = BooleanArray(size * size) { i -> (hb[i].toInt() and 255) > 40 && (i % size) < sw && (i / size) < sh }
        val filled = inpainter.inpaint(img, hole)
        model.setPixels(filled, 0, size, 0, 0, size, size)

        // Back to full resolution, only for the area around the strokes.
        val aw = area.width(); val ah = area.height()
        val original = rs.decode(area, 1, budget)
        val patch = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
        // Model pixel m ↔ photo pixel m / scale + window origin; draw straight into the patch.
        val s = plan.scale.toFloat()
        val toPatch = android.graphics.Matrix().apply {
            setScale(1 / s, 1 / s)
            postTranslate((plan.left - area.left).toFloat(), (plan.top - area.top).toFloat())
        }
        Canvas(patch).drawBitmap(model, toPatch, paint)
        model.recycle()
        val px = IntArray(aw * ah); patch.getPixels(px, 0, aw, 0, 0, aw, ah)
        val orig = IntArray(aw * ah); original.getPixels(orig, 0, aw, 0, 0, aw, ah); original.recycle()
        val holeFull = alphaMask(strokes, area, holeExtra)
        for (i in px.indices) if ((holeFull[i].toInt() and 255) < 128) px[i] = orig[i]
        val alpha = MaskOps.feather(alphaMask(strokes, area, d + f), aw, ah, f.roundToInt())
        // Upscaled model output is smoother than the photo: add matching grain.
        val grainScale = ((1.0 / plan.scale - 1.0)).coerceIn(0.0, 1.0)
        if (grainScale > 0.05) {
            val sigma = MaskOps.noiseSigma(orig, aw, ah, holeFull) * grainScale
            MaskOps.addGrain(px, alpha, sigma, area.left * 31L + area.top)
        }
        for (i in px.indices) {
            val a = alpha[i].toInt() and 255
            val c = px[i]
            // Premultiplied storage is handled by Bitmap.setPixels (it takes unpremultiplied ARGB).
            px[i] = (a shl 24) or (c and 0x00FFFFFF)
        }
        patch.setPixels(px, 0, aw, 0, 0, aw, ah)
        return store.save(PatchKind.ERASE, area.left, area.top, patch)
    }

    /**
     * Blurs ([PatchKind.BLUR]) or pixelates ([PatchKind.PIXELATE]) what the strokes cover, e.g. faces,
     * number plates or addresses. [strength] is 0..1. With [featurePx] (e.g. a face's size) the
     * effect scales with the feature instead of the photo, so large faces are hidden as well.
     */
    fun obscure(rs: RetouchedSource, strokes: List<Stroke>, kind: PatchKind, strength: Float, store: PatchStore, budget: Long, featurePx: Float = 0f): RetouchPatch {
        val w = rs.width; val h = rs.height
        val minSide = min(w, h).toFloat()
        val st = strength.coerceIn(0.05f, 1f)
        if (kind == PatchKind.PIXELATE) {
            val block = if (featurePx > 0) max(4, (featurePx / (16f - 10f * st)).roundToInt())
                else max(4, (minSide * (0.008f + 0.035f * st)).roundToInt())
            val b = Stroke.bounds(strokes, 1f)
            // Align to the block grid of the whole photo so neighbouring strokes match up.
            val area = Rect((floor(b.left / block) * block).toInt().coerceAtLeast(0), (floor(b.top / block) * block).toInt().coerceAtLeast(0),
                (ceil(b.right / block) * block).toInt().coerceAtMost(w), (ceil(b.bottom / block) * block).toInt().coerceAtMost(h))
            if (area.width().toLong() * area.height() * BYTES_PER_PIXEL > budget) throw UserFacingException("That area is too large for the memory available. Do it in smaller parts.")
            val aw = area.width(); val ah = area.height()
            val bmp = rs.decode(area, 1, budget)
            val px = IntArray(aw * ah); bmp.getPixels(px, 0, aw, 0, 0, aw, ah)
            val mask = alphaMask(strokes, area, 0f)
            val alpha = ByteArray(aw * ah)
            MosaicOps.pixelate(px, aw, ah, block, area.left % block, area.top % block, mask, alpha)
            bmp.setPixels(px.mapIndexed { i, c -> ((alpha[i].toInt() and 255) shl 24) or (c and 0x00FFFFFF) }.toIntArray(), 0, aw, 0, 0, aw, ah)
            return store.save(kind, area.left, area.top, bmp)
        }
        val radius = if (featurePx > 0) max(3f, featurePx / 2 * (0.15f + 0.3f * st)) else minSide * (0.006f + 0.03f * st)
        val f = max(1f, radius / 4f)
        val area = clampRect(Stroke.bounds(strokes, radius * 2 + f * 2), w, h)
        if (area.width().toLong() * area.height() * BYTES_PER_PIXEL > budget) throw UserFacingException("That area is too large for the memory available. Do it in smaller parts.")
        val aw = area.width(); val ah = area.height()
        val bmp = rs.decode(area, 1, budget)
        // Blur by shrinking and enlarging again (cheap and strong), smoothed by a second pass.
        // Shrinking halves the size step by step: each step averages 2×2 pixels, so fine detail
        // (stripes, text) can't alias into blotches.
        val k = max(1f, radius / 2f)
        val small = shrink(bmp, max(1, (aw / k).roundToInt()), max(1, (ah / k).roundToInt()))
        val smaller = shrink(small, max(1, small.width / 2), max(1, small.height / 2))
        val back = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
        Canvas(back).drawBitmap(smaller, null, Rect(0, 0, aw, ah), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle(); smaller.recycle(); bmp.recycle()
        val px = IntArray(aw * ah); back.getPixels(px, 0, aw, 0, 0, aw, ah)
        val sm = IntArray(aw * ah)
        val r = max(1, (radius / 3).roundToInt())
        MosaicOps.boxBlurArgb(px, sm, aw, ah, r)
        val alpha = MaskOps.feather(alphaMask(strokes, area, f), aw, ah, f.roundToInt())
        for (i in sm.indices) sm[i] = ((alpha[i].toInt() and 255) shl 24) or (sm[i] and 0x00FFFFFF)
        back.setPixels(sm, 0, aw, 0, 0, aw, ah)
        return store.save(kind, area.left, area.top, back)
    }
}
