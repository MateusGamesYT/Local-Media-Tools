package com.localmediatools.gallery

import android.graphics.Bitmap
import android.graphics.Rect
import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceKind
import com.localmediatools.image.ImageSource
import com.localmediatools.vision.VisionOps
import com.localmediatools.vision.core.Face
import com.localmediatools.vision.core.FaceEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A face found while indexing, in fractions of the displayed photo. */
class FoundFace(
    val x: Float, val y: Float, val w: Float, val h: Float,
    val score: Float,
    /** Eye distance (px) in the picture the embedding was computed from. */
    val eyePx: Float,
    val yaw: Float,
    val emb: FloatArray?,
    val kind: FaceKind = FaceKind.SFACE,
    /** For videos: the frame (ms) the face was seen in. */
    val frameMs: Long = 0,
) {
    fun atFrame(ms: Long) = FoundFace(x, y, w, h, score, eyePx, yaw, emb, kind, ms)

    private val params get() = ClusterParams.of(kind)
    val good get() = emb != null && params.isGood(score, yaw, eyePx)
    val quality get() = params.quality(score, yaw, eyePx)
}

/**
 * Face pipeline for the gallery: YuNet on a copy of at most [DETECT_SIDE] px, then SFace identity
 * embeddings averaged with the mirrored face. Small faces are detected again on a sharper crop
 * decoded from the full-resolution original, so people far from the camera are still recognised.
 */
object GalleryFaces {
    const val DETECT_SIDE = 1600
    /** Faces whose eyes are closer than this (px, in the detection copy) are refined from the original. */
    private const val REFINE_BELOW_EYE = 40f
    /** Target eye distance for refined crops. */
    private const val REFINE_EYE = 64f

    fun analyze(engine: FaceEngine, src: ImageSource?, work: Bitmap): List<FoundFace> {
        val m = VisionOps.bgr(work)
        try {
            val ww = work.width.toFloat(); val wh = work.height.toFloat()
            val found = engine.detect(m, minSize = 12f)
            val out = ArrayList<FoundFace>()
            // Ratio of the original's display size to the working copy.
            val up = if (src != null) src.width / ww else 1f
            for (f in found) {
                val (eye, yaw) = FaceEngine.eyesAndYaw(f.row)
                var result: FoundFace? = null
                if (src != null && eye < REFINE_BELOW_EYE && up > 1.25f) result = refine(engine, src, f, up)
                if (result == null) {
                    val emb = try { engine.embedTta(m, f) } catch (_: Throwable) { null }
                    result = FoundFace(f.x / ww, f.y / wh, f.w / ww, f.h / wh, f.score, eye, yaw, emb)
                }
                out.add(result)
            }
            return out
        } finally { m.release() }
    }

    /** Detects the face again in a full-resolution crop around it; null when that doesn't work out. */
    private fun refine(engine: FaceEngine, src: ImageSource, f: Face, up: Float): FoundFace? {
        val (eye, _) = FaceEngine.eyesAndYaw(f.row)
        val fullEye = eye * up
        // Sample size keeping the refined eye distance near REFINE_EYE (never upscaling).
        var sample = 1
        while (fullEye / (sample * 2) >= REFINE_EYE && sample < 16) sample *= 2
        val cx = (f.x + f.w / 2) * up; val cy = (f.y + f.h / 2) * up
        val half = max(f.w, f.h) * up * 1.4f
        val rect = Rect(max(0, (cx - half).roundToInt()), max(0, (cy - half).roundToInt()),
            min(src.width, (cx + half).roundToInt()), min(src.height, (cy + half).roundToInt()))
        if (rect.width() < 16 || rect.height() < 16) return null
        val crop = try { src.decodeRegion(rect, sample, budgetBytes = 160L shl 20) } catch (_: Throwable) { return null }
        val cm = VisionOps.bgr(crop)
        try {
            val cs = rect.width().toFloat() / crop.width
            val faces = engine.detect(cm, minSize = 12f)
            // The face nearest the crop centre, of a similar size.
            val ccx = crop.width / 2f; val ccy = crop.height / 2f
            val g = faces.minByOrNull { (it.cx - ccx) * (it.cx - ccx) + (it.cy - ccy) * (it.cy - ccy) } ?: return null
            val dist = kotlin.math.hypot(g.cx - ccx, g.cy - ccy)
            val expected = f.w * up / cs
            if (dist > expected * 0.5f || g.w < expected * 0.5f || g.w > expected * 2f) return null
            val (e2, yaw2) = FaceEngine.eyesAndYaw(g.row)
            val emb = engine.embedTta(cm, g) ?: return null
            val W = src.width.toFloat(); val H = src.height.toFloat()
            return FoundFace((rect.left + g.x * cs) / W, (rect.top + g.y * cs) / H, g.w * cs / W, g.h * cs / H,
                g.score, e2, yaw2, emb)
        } catch (_: Throwable) {
            return null
        } finally {
            cm.release(); crop.recycle()
        }
    }
}
