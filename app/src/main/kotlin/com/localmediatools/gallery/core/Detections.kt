package com.localmediatools.gallery.core

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** An object found in a photo: COCO class index, confidence and box as fractions of the photo. */
class Detection(val cls: Int, val score: Float, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val area get() = max(0f, x1 - x0) * max(0f, y1 - y0)
}

/**
 * Post-processing for EfficientDet-Lite (MediaPipe export without built-in NMS): SSD anchors,
 * box decoding and per-class non-maximum suppression. The anchors reproduce the ones stored in
 * the model's metadata exactly (checked in tests).
 */
object EfficientDetDecoder {
    const val INPUT = 448
    const val CLASSES = 90

    /** Anchors as (x centre, y centre, width, height) per box, levels 3–7, 3 scales × 3 aspect ratios. */
    val anchors: FloatArray by lazy { anchors(INPUT) }

    fun anchors(input: Int): FloatArray {
        val out = ArrayList<Float>()
        for (level in 3..7) {
            val fm = ceil(input / 2.0.pow(level)).toInt()
            for (y in 0 until fm) for (x in 0 until fm) for (s in 0 until 3) {
                val size = (3.0 * 2.0.pow(s / 3.0) / fm)
                for (ar in doubleArrayOf(1.0, 2.0, 0.5)) {
                    out.add(((x + 0.5) / fm).toFloat()); out.add(((y + 0.5) / fm).toFloat())
                    out.add((size * sqrt(ar)).toFloat()); out.add((size / sqrt(ar)).toFloat())
                }
            }
        }
        return out.toFloatArray()
    }

    /**
     * Decodes raw outputs ([boxes]: n×4 as (dy, dx, dh, dw); [scores]: n×[CLASSES] probabilities)
     * into detections at or above [minScore]. The model saw the photo letterboxed into the top-left
     * corner: [contentW]/[contentH] are the fractions of the input the photo filled.
     */
    fun decode(boxes: FloatArray, scores: FloatArray, minScore: Float, contentW: Float = 1f, contentH: Float = 1f,
               iou: Float = 0.5f, maxPerClass: Int = 20): List<Detection> {
        val a = anchors
        val n = a.size / 4
        val perClass = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val o = i * CLASSES
            for (c in 0 until CLASSES) if (scores[o + c] >= minScore) perClass.getOrPut(c) { ArrayList() }.add(i)
        }
        val out = ArrayList<Detection>()
        for ((c, idx) in perClass) {
            val cand = idx.map { i ->
                val ax = a[i * 4]; val ay = a[i * 4 + 1]; val aw = a[i * 4 + 2]; val ah = a[i * 4 + 3]
                val yc = boxes[i * 4] * ah + ay
                val xc = boxes[i * 4 + 1] * aw + ax
                val h = exp(boxes[i * 4 + 2]) * ah
                val w = exp(boxes[i * 4 + 3]) * aw
                Detection(c, scores[i * CLASSES + c],
                    ((xc - w / 2) / contentW).coerceIn(0f, 1f), ((yc - h / 2) / contentH).coerceIn(0f, 1f),
                    ((xc + w / 2) / contentW).coerceIn(0f, 1f), ((yc + h / 2) / contentH).coerceIn(0f, 1f))
            }
            out.addAll(nms(cand, iou, maxPerClass))
        }
        out.sortByDescending { it.score }
        return out
    }

    fun nms(dets: List<Detection>, iou: Float, max: Int): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val keep = ArrayList<Detection>()
        for (d in sorted) {
            if (keep.size >= max) break
            if (keep.none { iou(it, d) > iou }) keep.add(d)
        }
        return keep
    }

    fun iou(a: Detection, b: Detection): Float {
        val ix = max(0f, min(a.x1, b.x1) - max(a.x0, b.x0))
        val iy = max(0f, min(a.y1, b.y1) - max(a.y0, b.y0))
        val inter = ix * iy
        val u = a.area + b.area - inter
        return if (u <= 0f) 0f else inter / u
    }

    /** COCO labels in the model's 90-slot order ("" for unused slots). */
    val LABELS = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light", "fire hydrant", "",
        "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "",
        "backpack", "umbrella", "", "", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
        "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "", "wine glass", "cup", "fork",
        "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "", "dining table", "", "", "toilet", "", "tv", "laptop", "mouse", "remote",
        "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "", "book", "clock", "vase", "scissors",
        "teddy bear", "hair drier", "toothbrush",
    )
}
