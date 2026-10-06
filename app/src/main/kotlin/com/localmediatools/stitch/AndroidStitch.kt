package com.localmediatools.stitch

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import com.localmediatools.core.MediaItem
import com.localmediatools.image.BitmapOps
import com.localmediatools.image.ImageSource
import com.localmediatools.stitch.core.AlignmentAssist
import com.localmediatools.stitch.core.StitchImages
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object OpenCvLoader {
    @Volatile private var loaded = false
    fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("opencv_java4")
                loaded = true
            }
        }
    }
}

/**
 * Stitcher input backed by [ImageSource]s, so every photo is used in its displayed orientation.
 * Full-resolution regions come from region decoding, or from a cache of full decodes when they all
 * fit in the memory budget (much faster for typical phone photos).
 */
class SourceStitchImages(
    private val ctx: Context,
    private val items: List<MediaItem>,
    private val budget: Long,
) : StitchImages, AutoCloseable {
    private val sources = items.map { ImageSource.open(ctx, it) }
    private val cache = HashMap<Int, Mat>()
    private val cacheAll: Boolean = sources.sumOf { it.bytesFor(1) } <= budget / 2
    private val focal = DoubleArray(items.size) { -1.0 }

    override val count get() = items.size
    override fun name(i: Int) = items[i].name
    override fun fullSize(i: Int) = intArrayOf(sources[i].width, sources[i].height)

    override fun workImage(i: Int, maxSide: Int): Mat {
        val src = sources[i]
        val bmp = src.preview(maxSide)
        val m = Mat()
        Utils.bitmapToMat(BitmapOps.ensureArgb8888(bmp), m, false)
        bmp.recycle()
        return m
    }

    override fun region(i: Int, x: Int, y: Int, w: Int, h: Int, sample: Int): Mat {
        val src = sources[i]
        if (cacheAll) {
            val full = synchronized(cache) {
                cache.getOrPut(i) {
                    val b = src.decode(1)
                    val m = Mat(); Utils.bitmapToMat(b, m, false); b.recycle(); m
                }
            }
            val sub = full.submat(org.opencv.core.Rect(x, y, w, h))
            if (sample <= 1) return sub.clone()
            val out = Mat()
            Imgproc.resize(sub, out, Size((w / sample).coerceAtLeast(1).toDouble(), (h / sample).coerceAtLeast(1).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            return out
        }
        val bmp = src.decodeRegion(Rect(x, y, x + w, y + h), sample, budget)
        val m = Mat()
        Utils.bitmapToMat(BitmapOps.ensureArgb8888(bmp), m, false)
        bmp.recycle()
        return m
    }

    override fun focal35mm(i: Int): Double {
        if (focal[i] >= 0) return focal[i]
        focal[i] = try {
            ctx.contentResolver.openInputStream(items[i].uri)?.use { s ->
                ExifInterface(s).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).toDouble()
            } ?: 0.0
        } catch (_: Exception) { 0.0 }
        return focal[i]
    }

    override fun close() {
        synchronized(cache) { cache.values.forEach { it.release() }; cache.clear() }
        sources.forEach { it.close() }
    }
}

/**
 * On-device AI assistance: a MobileNet-V3 image embedder (TensorFlow Lite, bundled in the app).
 * Images never leave the device; the model only scores whether two image patches show the same
 * content, which the stitcher uses to settle ambiguous alignments.
 */
class TfliteEmbedder private constructor(private val interpreter: Interpreter) : AlignmentAssist, AutoCloseable {
    private val input = ByteBuffer.allocateDirect(224 * 224 * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(outSize(interpreter)) }
    private val rgba = ByteArray(224 * 224 * 4)
    override val name = "MobileNet-V3 embedder (on-device)"

    override fun embed(rgba: Mat): FloatArray = synchronized(this) {
        val small = Mat()
        Imgproc.resize(rgba, small, Size(224.0, 224.0), 0.0, 0.0, Imgproc.INTER_AREA)
        small.get(0, 0, this.rgba)
        small.release()
        input.rewind()
        for (i in 0 until 224 * 224) {
            input.putFloat((this.rgba[i * 4].toInt() and 0xFF) / 255f)
            input.putFloat((this.rgba[i * 4 + 1].toInt() and 0xFF) / 255f)
            input.putFloat((this.rgba[i * 4 + 2].toInt() and 0xFF) / 255f)
        }
        input.rewind()
        interpreter.run(input, output)
        val v = output[0].copyOf()
        var n = 0.0
        for (x in v) n += x * x
        val s = Math.sqrt(n).toFloat().coerceAtLeast(1e-6f)
        for (k in v.indices) v[k] /= s
        v
    }

    override fun close() = interpreter.close()

    companion object {
        private const val ASSET = "models/image_embedder.tflite"

        private fun outSize(i: Interpreter) = i.getOutputTensor(0).shape().last()

        /** Whether the device can run AI-assisted alignment comfortably, with a reason if not. */
        fun availability(ctx: Context): Pair<Boolean, String> {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            val gb = mi.totalMem / (1024.0 * 1024 * 1024)
            if (am.isLowRamDevice) return false to "Not available: this phone is configured as a low-memory device."
            if (gb < 3.5) return false to "Not available: needs at least 4 GB of RAM (this phone has ${String.format("%.1f", gb)} GB)."
            if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) return false to "Not available: needs a 64-bit processor."
            if (Runtime.getRuntime().availableProcessors() < 4) return false to "Not available: needs at least 4 CPU cores."
            val present = try { ctx.assets.openFd(ASSET).use { true } } catch (_: Exception) { false }
            if (!present) return false to "Not available: the on-device model is missing from this build."
            return true to "Available on this device · runs fully offline"
        }

        fun create(ctx: Context, threads: Int): TfliteEmbedder {
            val afd = ctx.assets.openFd(ASSET)
            val buf: MappedByteBuffer = FileInputStream(afd.fileDescriptor).channel.use { ch ->
                ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
            afd.close()
            val opts = Interpreter.Options().setNumThreads(threads.coerceIn(1, 4))
            return TfliteEmbedder(Interpreter(buf, opts))
        }
    }
}

/** Converts a stitched RGBA row callback into Bitmap-free ARGB rows (already ARGB). */
@Suppress("unused")
private fun bitmapFromMat(m: Mat): Bitmap {
    val b = Bitmap.createBitmap(m.cols(), m.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(m, b)
    return b
}
