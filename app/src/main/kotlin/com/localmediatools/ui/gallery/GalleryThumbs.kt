package com.localmediatools.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import com.localmediatools.gallery.GFace
import com.localmediatools.gallery.GMedia
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.image.ImageSource
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Gallery thumbnails from the system's thumbnail cache (fast, also for videos), plus face crops
 * cut from the original photo so even small faces look sharp. Requests for views that scrolled
 * away are cancelled.
 */
object GalleryThumbs {
    private val cache = object : LruCache<String, Bitmap>(maxOf(32 * 1024, (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY + 4); r.run() }, "lmt-gthumbs").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val running = HashMap<ImageView, Pair<Future<*>, CancellationSignal>>()

    fun cached(key: String): Bitmap? = cache.get(key)

    /** Stands in for the system thumbnailer and face crops where there are no real files (JVM tests). */
    @Volatile var override: ((GMedia?, GFace?, Int) -> Bitmap?)? = null

    private fun thumb(ctx: Context, m: GMedia, size: Int, signal: CancellationSignal?): Bitmap? {
        override?.let { return it(m, null, size) }
        return ctx.contentResolver.loadThumbnail(m.uri, Size(size, size), signal)
    }

    /** Shows a square-ish thumbnail of [m] in [target] (about [sizePx] on its longer side). */
    fun load(ctx: Context, m: GMedia, sizePx: Int, target: ImageView) {
        val bucket = if (sizePx <= 256) 256 else if (sizePx <= 512) 512 else 1024
        val key = "${m.id}:${m.modified}@$bucket"
        cancel(target)
        target.tag = key
        cache.get(key)?.let { target.setImageBitmap(it); return }
        target.setImageDrawable(null)
        val app = ctx.applicationContext
        val signal = CancellationSignal()
        val fut = pool.submit {
            if (signal.isCanceled) return@submit
            val bmp = try { thumb(app, m, bucket, signal) } catch (_: Throwable) { null }
            if (bmp != null) cache.put(key, bmp)
            main.post {
                synchronized(running) { if (running[target]?.second === signal) running.remove(target) }
                if (target.tag == key && bmp != null) target.setImageBitmap(bmp)
            }
        }
        synchronized(running) { running[target] = fut to signal }
    }

    fun cancel(target: ImageView) {
        synchronized(running) { running.remove(target) }?.let { (f, s) -> s.cancel(); f.cancel(false) }
    }

    /** Loads off the main thread, then calls [cb] on it. */
    fun get(ctx: Context, m: GMedia, sizePx: Int, cb: (Bitmap?) -> Unit) {
        val key = "${m.id}:${m.modified}@$sizePx"
        cache.get(key)?.let { cb(it); return }
        val app = ctx.applicationContext
        pool.submit {
            val bmp = try { thumb(app, m, sizePx, null) } catch (_: Throwable) { null }
            if (bmp != null) cache.put(key, bmp)
            main.post { cb(bmp) }
        }
    }

    /** A square crop around a face, [sizePx] px, cached on disk (face crops are cheap to keep). */
    fun face(ctx: Context, f: GFace, sizePx: Int, target: ImageView) {
        val key = "face:${f.id}@$sizePx"
        cancel(target)
        target.tag = key
        cache.get(key)?.let { target.setImageBitmap(it); return }
        target.setImageDrawable(null)
        val app = ctx.applicationContext
        val signal = CancellationSignal()
        val fut = pool.submit {
            if (signal.isCanceled) return@submit
            val bmp = try { faceCrop(app, f, sizePx) } catch (_: Throwable) { null }
            if (bmp != null) cache.put(key, bmp)
            main.post {
                synchronized(running) { if (running[target]?.second === signal) running.remove(target) }
                if (target.tag == key && bmp != null) target.setImageBitmap(bmp)
            }
        }
        synchronized(running) { running[target] = fut to signal }
    }

    private fun faceDir(ctx: Context) = File(ctx.cacheDir, "faces").apply { mkdirs() }

    fun faceCrop(ctx: Context, f: GFace, sizePx: Int): Bitmap? {
        override?.let { return it(null, f, sizePx) }
        val file = File(faceDir(ctx), "${f.id}-$sizePx.jpg")
        if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.path)?.let { return it }
        val m = GalleryDb.get(ctx).mediaById(f.mediaId) ?: return null
        val src: Bitmap
        val crop: Rect
        if (m.video) {
            // Video faces come from frames: cut from the very frame the face was found in.
            val mmr = android.media.MediaMetadataRetriever()
            src = try {
                mmr.setDataSource(ctx, m.uri)
                mmr.getScaledFrameAtTime(f.frameMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1024, 1024)
            } finally { try { mmr.release() } catch (_: Exception) { } } ?: return null
            crop = square(f, src.width, src.height)
        } else {
            val s = ImageSource.open(ctx, m.uri, m.name)
            try {
                val r = square(f, s.width, s.height)
                // Decode just the face area at a resolution close to what is shown.
                var sample = 1
                while (r.width() / (sample * 2) >= sizePx && sample < 32) sample *= 2
                src = s.decodeRegion(r, sample, budgetBytes = 96L shl 20)
                crop = Rect(0, 0, src.width, src.height)
            } finally { s.close() }
        }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(0xFF20242C.toInt())
            drawBitmap(src, crop, Rect(0, 0, sizePx, sizePx), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        src.recycle()
        try { file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) } } catch (_: Exception) { }
        return out
    }

    /** The face with some margin, as a square in a picture of [w]×[h] px. */
    private fun square(f: GFace, w: Int, h: Int): Rect {
        val cx = (f.x + f.w / 2) * w; val cy = (f.y + f.h / 2) * h
        val half = max(f.w * w, f.h * h) * 0.85f
        val side = (half * 2).roundToInt().coerceAtMost(minOf(w, h)).coerceAtLeast(2)
        val l = (cx - side / 2f).roundToInt().coerceIn(0, w - side)
        val t = (cy - side / 2f).roundToInt().coerceIn(0, h - side)
        return Rect(l, t, l + side, t + side)
    }

    /** Forgets cached face crops (after re-indexing). */
    fun clearFaces(ctx: Context) { faceDir(ctx).listFiles()?.forEach { it.delete() } }
}
