package com.localmediatools.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.localmediatools.gallery.core.Category
import com.localmediatools.gallery.core.Detection
import com.localmediatools.gallery.core.EfficientDetDecoder
import com.localmediatools.gallery.core.SceneHead
import com.localmediatools.gallery.core.Taxonomy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Recognises what is in a photo with two on-device models (TensorFlow Lite, CPU):
 *  - EfficientDet-Lite2 (COCO, int8): finds people, animals, vehicles, food and objects with boxes;
 *  - EfficientNetV2-B3 pre-trained on ImageNet-21k: recognises scenes and finer kinds of things,
 *    summed over WordNet groups (all dog breeds → "Dogs"), plus small linear probes for scenes the
 *    classifier has no class for (sunsets, night, waterfalls, fireworks…).
 * Their scores are fused per category with thresholds calibrated on human-verified labels.
 */
class ObjectTagger private constructor(
    private val detector: Interpreter,
    private val scene: Interpreter,
    private val head: SceneHead,
    private val categories: List<Category>,
) {
    private val detIn = ByteBuffer.allocateDirect(DET * DET * 3).order(ByteOrder.nativeOrder())
    private val detBoxes = ByteBuffer.allocateDirect(ANCHORS * 4 * 4).order(ByteOrder.nativeOrder())
    private val detScores = ByteBuffer.allocateDirect(ANCHORS * EfficientDetDecoder.CLASSES * 4).order(ByteOrder.nativeOrder())
    private val boxes = FloatArray(ANCHORS * 4)
    private val scores = FloatArray(ANCHORS * EfficientDetDecoder.CLASSES)
    private val sceneIn = ByteBuffer.allocateDirect(SCENE * SCENE * 3 * 4).order(ByteOrder.nativeOrder())
    private val sceneOut = Array(1) { FloatArray(head.dim) }
    private val boxesIdx: Int
    private val scoresIdx: Int

    init {
        // Output order differs between converter versions: pick by shape.
        var b = 0; var s = 1
        for (i in 0 until detector.outputTensorCount) {
            val shape = detector.getOutputTensor(i).shape()
            if (shape.last() == 4) b = i else if (shape.last() == EfficientDetDecoder.CLASSES) s = i
        }
        boxesIdx = b; scoresIdx = s
    }

    /** Objects found in [bmp] (any size; it is letterboxed to the model's input). */
    fun detect(bmp: Bitmap): List<Detection> = synchronized(this) {
        val s = DET.toFloat() / max(bmp.width, bmp.height)
        val w = max(1, (bmp.width * s).roundToInt()); val h = max(1, (bmp.height * s).roundToInt())
        val small = Resample.scale(bmp, w, h)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        detIn.rewind()
        for (y in 0 until DET) for (x in 0 until DET) {
            if (x < w && y < h) {
                val c = px[y * w + x]
                detIn.put(((c shr 16) and 255).toByte()); detIn.put(((c shr 8) and 255).toByte()); detIn.put((c and 255).toByte())
            } else { detIn.put(0); detIn.put(0); detIn.put(0) }
        }
        detIn.rewind()
        detBoxes.rewind(); detScores.rewind()
        val outs = HashMap<Int, Any>()
        outs[boxesIdx] = detBoxes; outs[scoresIdx] = detScores
        detector.runForMultipleInputsOutputs(arrayOf(detIn), outs)
        detBoxes.rewind(); detScores.rewind()
        detBoxes.asFloatBuffer().get(boxes)
        detScores.asFloatBuffer().get(scores)
        EfficientDetDecoder.decode(boxes, scores, MIN_DET, w.toFloat() / DET, h.toFloat() / DET)
    }

    /** The classifier's 1536 image features (centre crop, as in training). */
    fun features(bmp: Bitmap): FloatArray = synchronized(this) {
        val side = (minOf(bmp.width, bmp.height) * SCENE / (SCENE + 32f)).roundToInt().coerceAtLeast(1)
        val crop = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(crop).drawBitmap(bmp, Rect((bmp.width - side) / 2, (bmp.height - side) / 2, (bmp.width + side) / 2, (bmp.height + side) / 2),
            Rect(0, 0, side, side), null)
        val small = Resample.scale(crop, SCENE, SCENE)
        if (small !== crop) crop.recycle()
        val px = IntArray(SCENE * SCENE); small.getPixels(px, 0, SCENE, 0, 0, SCENE, SCENE)
        small.recycle()
        sceneIn.rewind()
        for (c in px) { sceneIn.putFloat(((c shr 16) and 255).toFloat()); sceneIn.putFloat(((c shr 8) and 255).toFloat()); sceneIn.putFloat((c and 255).toFloat()) }
        sceneIn.rewind()
        scene.run(sceneIn, sceneOut)
        sceneOut[0].copyOf()
    }

    /** Category → fused score (only scores worth keeping). */
    fun tag(bmp: Bitmap): Map<String, Float> {
        val dets = detect(bmp)
        val f = features(bmp)
        val probs = head.probabilities(f)
        val probes = head.probes(f)
        return Taxonomy.scores(categories, dets, probs, probes)
    }

    /** Runs both models on a test picture and checks the outputs are sane. */
    fun selfTest(): Boolean {
        val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            // A smooth gradient: nothing should be found with confidence, and the numbers must be finite.
            val c = Canvas(bmp)
            val p = Paint()
            for (y in 0 until 240) { p.color = 0xFF000000.toInt() or ((y * 255 / 239) shl 8) or (255 - y * 255 / 239); c.drawLine(0f, y.toFloat(), 320f, y.toFloat(), p) }
            val d = detect(bmp)
            val f = features(bmp)
            if (f.any { it.isNaN() || it.isInfinite() }) return false
            val probs = head.probabilities(f)
            val sum = probs.sum()
            return d.none { it.score > 0.9f } && sum.isFinite() && sum <= 1.0001f
        } finally { bmp.recycle() }
    }

    companion object {
        const val DET = EfficientDetDecoder.INPUT
        const val SCENE = 224
        private const val ANCHORS = 37629
        private const val MIN_DET = 0.15f
        private const val DET_MODEL = "models/efficientdet_lite2_int8.tflite"
        private const val SCENE_MODEL = "models/scene_effnetv2_b3_21k.tflite"
        private const val HEAD = "gallery/scene_head.bin"

        @Volatile private var shared: ObjectTagger? = null

        fun create(ctx: Context): ObjectTagger = shared ?: synchronized(this) {
            shared ?: run {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val det = Interpreter(map(ctx, DET_MODEL), Interpreter.Options().setNumThreads(threads))
                val sc = Interpreter(map(ctx, SCENE_MODEL), Interpreter.Options().setNumThreads(threads))
                val head = SceneHead.read(ctx.assets.open(HEAD).use { it.readBytes() })
                ObjectTagger(det, sc, head, GalleryRepo.categories(ctx)).also { shared = it }
            }
        }

        private fun map(ctx: Context, asset: String): MappedByteBuffer {
            val afd = ctx.assets.openFd(asset)
            return afd.use { FileInputStream(it.fileDescriptor).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength) } }
        }
    }
}

/** Downscaling that averages pixels (halving steps, then a final bilinear step), like the calibration did. */
object Resample {
    fun scale(src: Bitmap, w: Int, h: Int): Bitmap {
        var cur = src
        while (cur.width >= w * 2 && cur.height >= h * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        if (cur.width == w && cur.height == h) return cur
        val out = Bitmap.createScaledBitmap(cur, w, h, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }
}
