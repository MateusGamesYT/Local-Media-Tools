package com.localmediatools.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.localmediatools.gallery.ObjectTagger
import com.localmediatools.gallery.core.EfficientDetDecoder
import com.localmediatools.stitch.OpenCvLoader
import com.localmediatools.vision.core.CodeDetector
import com.localmediatools.vision.core.PlateDetector
import com.localmediatools.vision.core.PrivacyFinder
import com.localmediatools.vision.core.PrivateText
import com.localmediatools.vision.core.TextDetector
import com.localmediatools.vision.core.TextReader
import kotlin.math.max
import kotlin.math.min

/**
 * The on-device models that find private things besides faces: licence plates (LPD-YuNet), text
 * (PP-OCRv3 detection, PP-OCRv5 recognition) and QR codes and barcodes (OpenCV), with the
 * gallery's object detector for vehicles. Loaded when first needed and kept while the app runs.
 */
object PrivacyModels {
    const val PLATES = "license_plate_lpd_yunet_2023mar_fp16.onnx"
    const val TEXT_DETECTION = "text_detection_ppocrv3_en_fp16.onnx"
    const val TEXT_RECOGNITION = "text_recognition_ppocrv5_mobile_fp16.onnx"

    private var finder: PrivacyFinder? = null

    @Synchronized fun finder(ctx: Context): PrivacyFinder {
        finder?.let { return it }
        OpenCvLoader.ensure()
        fun lines(name: String) = ctx.assets.open("privacy/$name").bufferedReader().use { r -> r.readLines().filter { it.isNotEmpty() } }
        val classifier = PrivateText(lines("first_names.txt").toHashSet(), lines("surnames.txt").toHashSet())
        val dict = ctx.assets.open("privacy/ppocrv5_dict.txt").bufferedReader().use { it.readLines() }
        return PrivacyFinder(
            PlateDetector(VisionModels.file(ctx, PLATES).path),
            TextDetector(VisionModels.file(ctx, TEXT_DETECTION).path),
            TextReader(VisionModels.file(ctx, TEXT_RECOGNITION).path, dict),
            classifier, CodeDetector(),
        ).also { finder = it }
    }

    /**
     * Cars, motorcycles, buses and trucks in [bmp] (score 0.3 or more, overlapping boxes of any of
     * these kinds merged), largest first, as x0, y0, x1, y1 in [bmp]'s pixels.
     */
    fun vehicles(ctx: Context, bmp: Bitmap): List<FloatArray> {
        val found = ObjectTagger.create(ctx).detect(bmp)
            .filter { it.cls in PrivacyFinder.VEHICLE_CLASSES && it.score >= PrivacyFinder.VEHICLE_SCORE }
        val kept = EfficientDetDecoder.nms(found, 0.5f, 50)
        return kept.sortedByDescending { it.area }.take(PrivacyFinder.MAX_VEHICLES)
            .map { floatArrayOf(it.x0 * bmp.width, it.y0 * bmp.height, it.x1 * bmp.width, it.y1 * bmp.height) }
    }

    /** A thumbnail of a found thing: the box around [quad] (fractions of [bmp]) with some margin, fitted into [w]×[h]. */
    fun thumb(bmp: Bitmap, quad: FloatArray, w: Int = 240, h: Int = 120): Bitmap {
        val xs = floatArrayOf(quad[0], quad[2], quad[4], quad[6]); val ys = floatArrayOf(quad[1], quad[3], quad[5], quad[7])
        val x0 = xs.min() * bmp.width; val x1 = xs.max() * bmp.width; val y0 = ys.min() * bmp.height; val y1 = ys.max() * bmp.height
        val m = 0.25f * min(x1 - x0, y1 - y0) + 4f
        val src = Rect((x0 - m).toInt().coerceAtLeast(0), (y0 - m).toInt().coerceAtLeast(0),
            (x1 + m).toInt().coerceAtMost(bmp.width), (y1 + m).toInt().coerceAtMost(bmp.height))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF20242C.toInt())
        if (src.width() > 0 && src.height() > 0) {
            val s = min(w.toFloat() / src.width(), h.toFloat() / src.height())
            val dw = src.width() * s; val dh = src.height() * s
            val l = (w - dw) / 2; val t = (h - dh) / 2
            c.drawBitmap(bmp, src, Rect(l.toInt(), t.toInt(), max(l.toInt() + 1, (l + dw).toInt()), max(t.toInt() + 1, (t + dh).toInt())), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }
}
