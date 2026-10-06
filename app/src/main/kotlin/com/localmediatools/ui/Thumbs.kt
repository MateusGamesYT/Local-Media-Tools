package com.localmediatools.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.widget.ImageView
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.image.ImageSource
import java.util.concurrent.Executors

/**
 * Thumbnail loader. Image thumbnails come from the same orientation-aware decoder the tools use,
 * so previews always match the exported results.
 */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val failed = HashSet<String>()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "lmt-thumbs").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun load(ctx: Context, item: MediaItem, sizePx: Int, target: ImageView, onLoaded: ((Bitmap?) -> Unit)? = null) {
        val key = "${item.key}@$sizePx"
        target.tag = key
        cache.get(key)?.let { target.setImageBitmap(it); onLoaded?.invoke(it); return }
        target.setImageDrawable(null)
        if (synchronized(failed) { key in failed }) { onLoaded?.invoke(null); return }
        val app = ctx.applicationContext
        executor.execute {
            val bmp = try { generate(app, item, sizePx) } catch (_: Throwable) { null }
            if (bmp != null) cache.put(key, bmp) else synchronized(failed) { failed.add(key) }
            main.post {
                if (target.tag == key) {
                    if (bmp != null) target.setImageBitmap(bmp)
                    onLoaded?.invoke(bmp)
                }
            }
        }
    }

    /** Loads off the main thread and returns through [cb] (for previews that draw several images). */
    fun get(ctx: Context, item: MediaItem, sizePx: Int, cb: (Bitmap?) -> Unit) {
        val key = "${item.key}@$sizePx"
        cache.get(key)?.let { cb(it); return }
        val app = ctx.applicationContext
        executor.execute {
            val bmp = try { generate(app, item, sizePx) } catch (_: Throwable) { null }
            if (bmp != null) cache.put(key, bmp)
            main.post { cb(bmp) }
        }
    }

    fun generate(ctx: Context, item: MediaItem, size: Int): Bitmap? = when (item.kind) {
        MediaKind.IMAGE, MediaKind.GIF -> ImageSource.open(ctx, item).use { it.preview(size) }
        MediaKind.VIDEO -> videoFrame(ctx, item, size)
        MediaKind.PDF -> pdfPage(ctx, item, size)
        else -> null
    }

    private fun videoFrame(ctx: Context, item: MediaItem, size: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, item.uri)
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val t = if (dur > 2000) 1_000_000L else 0L
            var bmp = mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size) ?: return null
            // Some devices return frames in coded orientation; rotate if the aspect doesn't match.
            if ((rot == 90 || rot == 270) && vw > 0 && vh > 0) {
                val codedLandscape = vw > vh
                val bmpLandscape = bmp.width > bmp.height
                if (codedLandscape == bmpLandscape && vw != vh) {
                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                    val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    if (r !== bmp) bmp.recycle()
                    bmp = r
                }
            }
            return bmp
        } catch (_: Exception) {
            return null
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }
    }

    private fun pdfPage(ctx: Context, item: MediaItem, size: Int): Bitmap? {
        val pfd = ctx.contentResolver.openFileDescriptor(item.uri, "r") ?: return null
        return pfd.use {
            PdfRenderer(it).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { p ->
                    val s = size.toFloat() / maxOf(p.width, p.height)
                    val b = Bitmap.createBitmap(maxOf(1, (p.width * s).toInt()), maxOf(1, (p.height * s).toInt()), Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            }
        }
    }
}
