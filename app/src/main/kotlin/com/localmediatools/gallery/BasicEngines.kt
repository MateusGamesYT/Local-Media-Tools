package com.localmediatools.gallery

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import com.localmediatools.gallery.core.FaceKind
import com.localmediatools.gallery.core.Lbp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Classical face finder for phones where the face AI can't run: Android's built-in face detector
 * (frontal faces) plus an LBP texture descriptor. On labelled test data LBP can't tell different
 * people apart reliably, so in this mode faces are only grouped when they are near-identical
 * (see [com.localmediatools.gallery.core.ClusterParams.BASIC]); people are named by hand.
 */
class BasicFaces {
    fun analyze(work: Bitmap): List<FoundFace> {
        // The detector wants RGB_565 with an even width; 800 px keeps it quick.
        val s = minOf(1f, 800f / max(work.width, work.height))
        var w = max(2, (work.width * s).roundToInt()); if (w % 2 == 1) w -= 1
        val h = max(2, (work.height * s).roundToInt())
        val small = Bitmap.createScaledBitmap(work, w, h, true)
        val rgb565 = small.copy(Bitmap.Config.RGB_565, false)
        if (small !== work) small.recycle()
        try {
            val faces = arrayOfNulls<FaceDetector.Face>(16)
            val n = FaceDetector(w, h, faces.size).findFaces(rgb565, faces)
            if (n == 0) return emptyList()
            val px = IntArray(w * h); rgb565.getPixels(px, 0, w, 0, 0, w, h)
            val gray = FloatArray(w * h) { val c = px[it]; 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255) }
            val out = ArrayList<FoundFace>()
            val mid = PointF()
            for (i in 0 until n) {
                val f = faces[i] ?: continue
                if (f.confidence() < MIN_CONFIDENCE) continue
                f.getMidPoint(mid)
                val ed = f.eyesDistance()
                if (ed < 8f) continue
                // Face box from the eye distance (typical proportions).
                val bw = ed * 2.2f; val bh = ed * 2.6f
                val x0 = (mid.x - bw / 2) / w; val y0 = (mid.y - ed * 1.1f) / h
                val emb = Lbp.describe(gray, w, h, mid.x, mid.y, ed)
                out.add(FoundFace(x0.coerceIn(0f, 1f), y0.coerceIn(0f, 1f), (bw / w).coerceAtMost(1f), (bh / h).coerceAtMost(1f),
                    f.confidence(), ed, 0f, emb, kind = FaceKind.LBP))
            }
            return out
        } finally { rgb565.recycle() }
    }

    companion object {
        /** Android's detector reports 0.3–0.6 for most real faces; below 0.4 false hits get common. */
        const val MIN_CONFIDENCE = 0.4f
    }
}

/**
 * Object and scene tags when the AI can't run: only what can be known for sure without it
 * (people from clearly found faces, screenshots from where the file was saved).
 */
object BasicTagger {
    fun tag(faces: List<FoundFace>): Map<String, Float> {
        val out = HashMap<String, Float>()
        if (faces.any { it.score >= 0.5f && it.w * it.h >= 0.004f }) out["people"] = 0.75f
        return out
    }
}
