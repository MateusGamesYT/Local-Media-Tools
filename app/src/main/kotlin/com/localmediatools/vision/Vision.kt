package com.localmediatools.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.localmediatools.gallery.ObjectTagger
import com.localmediatools.i18n.tr
import com.localmediatools.stitch.OpenCvLoader
import com.localmediatools.vision.core.AutoEnhance
import com.localmediatools.vision.core.EnhanceResult
import com.localmediatools.vision.core.Face
import com.localmediatools.vision.core.FaceBox
import com.localmediatools.vision.core.FaceEngine
import com.localmediatools.vision.core.FaceModel
import com.localmediatools.vision.core.FaceSample
import com.localmediatools.vision.core.Matting
import com.localmediatools.vision.core.SceneKind
import com.localmediatools.vision.core.SceneMapper
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * The on-device vision models: face detection (YuNet) and recognition (ResNet-50 for the Gallery,
 * MobileFaceNet for face blur) and subject cut-out (U²-Net-p); scenes are recognised by the gallery's
 * [ObjectTagger]. They are loaded when first needed; everything runs on this phone.
 */
object VisionModels {
    const val YUNET = "face_detection_yunet_2023mar.onnx"
    const val U2NET = "u2netp.onnx"

    private var faces: FaceEngine? = null
    private var galleryFaces: FaceEngine? = null
    private var matting: Matting? = null

    /**
     * OpenCV reads models from files, so assets are copied to app storage once per app version
     * (older copies are removed).
     */
    @Synchronized fun file(ctx: Context, name: String): File {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        val stamp = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
        val f = File(dir, "$stamp-$name")
        if (f.exists() && f.length() > 0) return f
        dir.listFiles()?.filter { it.name.endsWith("-$name") }?.forEach { it.delete() }
        val tmp = File(dir, "$name.tmp")
        ctx.assets.open("models/$name").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        if (!tmp.renameTo(f)) throw java.io.IOException(tr("Could not prepare the on-device model {0}", name))
        return f
    }

    /** Faces for face blur and the editor: MobileFaceNet, fast enough for every face of every sampled video frame. Kept while the app runs. */
    @Synchronized fun faces(ctx: Context): FaceEngine {
        faces?.let { return it }
        OpenCvLoader.ensure()
        return FaceEngine(file(ctx, YUNET).path, file(ctx, FaceModel.MBF.file).path, FaceModel.MBF).also { faces = it }
    }

    /**
     * Faces for the Gallery's people: ResNet-50 (8-bit, TensorFlow Lite, about 90 MB while loaded),
     * used while indexing and released when it ends ([releaseGalleryFaces]).
     */
    @Synchronized fun galleryFaces(ctx: Context): FaceEngine {
        galleryFaces?.let { return it }
        OpenCvLoader.ensure()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val embedder = TfliteFaceEmbedder(mapAsset(ctx, "models/" + FaceModel.R50.file), threads)
        return FaceEngine(file(ctx, YUNET).path, embedder, FaceModel.R50).also { galleryFaces = it }
    }

    @Synchronized fun releaseGalleryFaces() {
        galleryFaces?.close(); galleryFaces = null
    }

    /** An asset stored uncompressed in the APK (TensorFlow Lite models), mapped into memory. */
    fun mapAsset(ctx: Context, asset: String): java.nio.MappedByteBuffer {
        val afd = ctx.assets.openFd(asset)
        return afd.use { java.io.FileInputStream(it.fileDescriptor).channel.use { ch -> ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength) } }
    }

    @Synchronized fun matting(ctx: Context): Matting {
        matting?.let { return it }
        OpenCvLoader.ensure()
        return Matting(file(ctx, U2NET).path).also { matting = it }
    }
}

object VisionOps {
    /** Stands in for the cut-out model where native code can't run (JVM tests); returns alpha for the bitmap. */
    @Volatile var maskOverride: ((Bitmap) -> FloatArray)? = null
    /** An ARGB bitmap as a BGR Mat (what the face models expect). */
    fun bgr(bmp: Bitmap): Mat {
        OpenCvLoader.ensure()
        val rgba = Mat()
        Utils.bitmapToMat(if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false), rgba)
        val out = Mat()
        Imgproc.cvtColor(rgba, out, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return out
    }

    fun rgb(bmp: Bitmap): Mat {
        OpenCvLoader.ensure()
        val rgba = Mat()
        Utils.bitmapToMat(if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false), rgba)
        val out = Mat()
        Imgproc.cvtColor(rgba, out, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return out
    }

    /** A copy no larger than [maxSide] on its longer side (the same bitmap if already small enough). */
    fun fit(bmp: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (s >= 1f) return bmp
        return Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
    }

    /**
     * Faces in a photo, as fractions of the picture, with identity embeddings. Detection runs on a
     * copy of at most [detectSide] px, which finds faces down to about 1% of the picture's size.
     */
    fun photoFaces(ctx: Context, bmp: Bitmap, detectSide: Int = 1600, embed: Boolean = true): List<FaceSample> {
        val engine = VisionModels.faces(ctx)
        val small = fit(bmp, detectSide)
        val m = bgr(small)
        try {
            val w = small.width.toFloat(); val h = small.height.toFloat()
            return engine.detect(m).map { f: Face ->
                FaceSample(0, f.x / w, f.y / h, f.w / w, f.h / h, f.score, if (embed) engine.embed(m, f) else null)
            }
        } finally {
            m.release()
            if (small !== bmp) small.recycle()
        }
    }

    /** A square thumbnail of a face (with some margin), [size] px. */
    fun faceThumb(bmp: Bitmap, s: FaceSample, size: Int = 160): Bitmap {
        val cx = (s.x + s.w / 2) * bmp.width; val cy = (s.y + s.h / 2) * bmp.height
        val half = max(s.w * bmp.width, s.h * bmp.height) * 0.8f
        val src = Rect((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF20242C.toInt())
        c.drawBitmap(bmp, src, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * One-tap enhancement settings for a photo: the faces and the scene found by the on-device
     * models steer [AutoEnhance]. [bmp] can be any size; a small copy is analysed.
     */
    fun enhance(ctx: Context, bmp: Bitmap, strength: Float): EnhanceResult {
        val faces = try { photoFaces(ctx, bmp, 800, embed = false).map { FaceBox(it.x, it.y, it.w, it.h) } } catch (_: Throwable) { emptyList() }
        val scene = try { SceneMapper.fromCategories(ObjectTagger.create(ctx).tag(bmp)).first } catch (_: Throwable) { SceneKind.GENERAL }
        val small = fit(bmp, 384)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val r = AutoEnhance.analyze(px, small.width, small.height, faces, scene, strength)
        if (small !== bmp) small.recycle()
        return r
    }

    /**
     * Subject mask for a photo at up to [workSide] px ([Matting.cutout]: the model's looks at the
     * photo, refined to follow its real edges). Returns the alpha (0..1) and its size.
     */
    fun subjectMask(ctx: Context, bmp: Bitmap, workSide: Int = 1024): Triple<FloatArray, Int, Int> {
        maskOverride?.let { f ->
            val work = fit(bmp, workSide)
            return Triple(f(work), work.width, work.height).also { if (work !== bmp) work.recycle() }
        }
        val matting = VisionModels.matting(ctx)
        val work = fit(bmp, workSide)
        val w = work.width; val h = work.height
        val px = IntArray(w * h)
        work.getPixels(px, 0, w, 0, 0, w, h)
        if (work !== bmp) work.recycle()
        return Triple(matting.cutout(px, w, h), w, h)
    }
}
