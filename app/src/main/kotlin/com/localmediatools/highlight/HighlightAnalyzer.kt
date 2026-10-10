package com.localmediatools.highlight

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.core.MediaProbe
import com.localmediatools.gallery.ObjectTagger
import com.localmediatools.gallery.core.Taxonomy
import com.localmediatools.highlight.core.CaptureTime
import com.localmediatools.highlight.core.FaceBox
import com.localmediatools.highlight.core.Shot
import com.localmediatools.highlight.core.ShotKind
import com.localmediatools.highlight.core.VideoWindow
import com.localmediatools.image.BitmapOps
import com.localmediatools.image.ImageSource
import com.localmediatools.vision.VisionModels
import com.localmediatools.vision.VisionOps
import com.localmediatools.vision.core.AutoEnhance
import com.localmediatools.vision.core.FaceEngine
import com.localmediatools.video.FrameGrabber
import com.localmediatools.video.VideoProbe
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** What the planner needs to know about one photo or video (a [Shot] without its place in the list). */
class ShotInfo(
    val kind: ShotKind, val takenMs: Long, val width: Int, val height: Int, val durationMs: Long,
    val sharpness: Double, val exposure: Float, val faces: List<FaceBox>, val tags: Map<String, Float>,
    val features: FloatArray?, val favorite: Boolean, val windows: List<VideoWindow>, val hasAudio: Boolean,
    /** Videos: clockwise rotation stored in the file. */
    val rotation: Int = 0,
) {
    fun shot(id: Int) = Shot(id, kind, takenMs, width, height, durationMs, sharpness, exposure, faces, tags, features, favorite, windows, hasAudio)
}

/**
 * Looks at the photos and videos picked for a highlight video, on the phone: when each was taken
 * (from the file, as [CaptureTime] reads it), how sharp and well exposed it is, its faces, what it
 * shows (the gallery's object and scene models) and, for videos, which stretches are lively — motion,
 * faces and sound, measured on frames twice a second. Results are kept while the app runs, so the
 * timeline and the export don't look twice.
 */
object HighlightAnalyzer {
    /** Pictures are looked at this size (long side). */
    private const val SIDE = 640
    private const val SHARP_SIDE = 384
    private val cache = LruCache<String, ShotInfo>(600)

    private fun key(item: MediaItem) = "${item.uri}|${item.size}"
    fun cached(item: MediaItem): ShotInfo? = cache.get(key(item))
    /** For tests and the timeline: puts a known result in place. */
    fun remember(item: MediaItem, info: ShotInfo) { cache.put(key(item), info) }

    /** Frees the object and scene models (tens of MB) once the looking is done, unless the gallery is using them. */
    fun releaseModels() { if (!com.localmediatools.gallery.GalleryIndex.busy) ObjectTagger.release() }

    fun analyze(ctx: Context, item: MediaItem, cancelled: () -> Boolean = { false }): ShotInfo {
        cached(item)?.let { return it }
        val info = if (item.kind == MediaKind.VIDEO) video(ctx, item, cancelled) else photo(ctx, item)
        // A video looked at only in part (the user changed the pictures meanwhile) isn't kept.
        if (cancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
        cache.put(key(item), info)
        return info
    }

    // ------------------------------------------------------------------ photos
    private fun photo(ctx: Context, item: MediaItem): ShotInfo {
        ImageSource.open(ctx, item).use { src ->
            val work = src.preview(SIDE)
            try {
                val (sharp, exposure) = quality(work)
                val faces = faces(ctx, work)
                val (tags, features) = tags(ctx, work)
                val library = library(ctx, item.uri)
                val taken = CaptureTime.best(exifTime(ctx, item.uri), library.taken, library.modified ?: System.currentTimeMillis())
                return ShotInfo(ShotKind.PHOTO, taken, src.width, src.height, 0, sharp, exposure, faces, tags, features, library.favorite, emptyList(), false)
            } finally { work.recycle() }
        }
    }

    /** Sharpness (variance of the Laplacian at [SHARP_SIDE] px) and exposure (1 = fine, 0 = black, blown out or murky). */
    private fun quality(bmp: Bitmap): Pair<Double, Float> {
        val s = SHARP_SIDE.toDouble() / max(bmp.width, bmp.height)
        val small = if (s < 1) BitmapOps.scale(bmp, max(1, (bmp.width * s).toInt()), max(1, (bmp.height * s).toInt()), recycleSource = false) else bmp
        try {
            val px = IntArray(small.width * small.height)
            small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
            return AutoEnhance.sharpness(px, small.width, small.height) to exposure(px)
        } finally { if (small !== bmp) small.recycle() }
    }

    internal fun exposure(px: IntArray): Float {
        if (px.isEmpty()) return 1f
        var sum = 0.0; var dark = 0; var bright = 0
        for (c in px) {
            val y = (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000
            sum += y
            if (y < 8) dark++ else if (y > 248) bright++
        }
        val mean = sum / px.size / 255
        val clipped = (dark + bright).toDouble() / px.size
        val off = max(0.0, abs(mean - 0.5) - 0.25) * 3.0 + max(0.0, clipped - 0.2) * 2.0
        return (1 - off).coerceIn(0.0, 1.0).toFloat()
    }

    private fun faces(ctx: Context, bmp: Bitmap): List<FaceBox> = try {
        val engine: FaceEngine = VisionModels.faces(ctx)
        val m = VisionOps.bgr(bmp)
        try {
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            engine.detect(m, minSize = 12f).filter { it.score >= 0.6f }.map { FaceBox(it.x / w, it.y / h, it.w / w, it.h / h) }
        } finally { m.release() }
    } catch (t: Throwable) { emptyList() }

    private fun tags(ctx: Context, bmp: Bitmap): Pair<Map<String, Float>, FloatArray?> = try {
        val (tags, f) = ObjectTagger.create(ctx).tagWithFeatures(bmp)
        tags.filterValues { it >= Taxonomy.KEEP } to normalized(f)
    } catch (t: Throwable) { emptyMap<String, Float>() to null }

    private fun normalized(f: FloatArray): FloatArray? {
        var s = 0.0; for (v in f) s += v * v
        if (s <= 0 || s.isNaN()) return null
        val n = sqrt(s).toFloat()
        return FloatArray(f.size) { f[it] / n }
    }

    /** EXIF capture time with its sub-seconds and zone when the file has them. */
    private fun exifTime(ctx: Context, uri: Uri): Long? = try {
        MediaProbe.openInput(ctx.contentResolver, uri).use { input ->
            val e = android.media.ExifInterface(input)
            val dt = e.getAttribute("DateTimeOriginal") ?: e.getAttribute("DateTimeDigitized") ?: e.getAttribute("DateTime")
            CaptureTime.exif(dt, e.getAttribute("SubSecTimeOriginal"), e.getAttribute("OffsetTimeOriginal") ?: e.getAttribute("OffsetTime"))
        }
    } catch (_: Throwable) { null }

    private class Library(val taken: Long?, val modified: Long?, val favorite: Boolean)

    /** What the media library says (photos picked from the gallery have this; files from elsewhere may not). */
    private fun library(ctx: Context, uri: Uri): Library {
        var taken: Long? = null; var modified: Long? = null; var fav = false
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun long(col: String): Long? { val i = c.getColumnIndex(col); return if (i >= 0 && !c.isNull(i)) c.getLong(i) else null }
                    taken = long(MediaStore.MediaColumns.DATE_TAKEN)?.takeIf { it > 0 }
                    modified = long(MediaStore.MediaColumns.DATE_MODIFIED)?.let { it * 1000 } ?: long("last_modified")
                    fav = (long("is_favorite") ?: 0L) == 1L
                }
            }
        } catch (_: Throwable) { }
        return Library(taken, modified, fav)
    }

    // ------------------------------------------------------------------ videos
    private fun video(ctx: Context, item: MediaItem, cancelled: () -> Boolean): ShotInfo {
        val info = VideoProbe.probe(ctx, item.uri)
        val durMs = info.durationUs / 1000
        val mmr = MediaMetadataRetriever()
        var taken: Long? = null
        try { mmr.setDataSource(ctx, item.uri); taken = CaptureTime.video(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)) } catch (_: Throwable) { }
        val library = library(ctx, item.uri)
        val loud = try { if (info.hasAudio) loudness(ctx, item.uri, durMs, cancelled) else null } catch (_: Throwable) { null }
        val dw = max(2, info.displayWidth); val dh = max(2, info.displayHeight)
        val s = SHARP_SIDE.toDouble() / max(dw, dh)
        val gw = max(2, (dw * s).toInt() / 2 * 2); val gh = max(2, (dh * s).toInt() / 2 * 2)
        class Sample(val ms: Long, val sharp: Double, val exposure: Float, val faces: List<FaceBox>, val grey: ByteArray)
        val samples = ArrayList<Sample>()
        var middle: Bitmap? = null
        val midMs = durMs / 2
        fun take(argb: IntArray, ms: Long) {
            val bmp = Bitmap.createBitmap(argb, gw, gh, Bitmap.Config.ARGB_8888)
            val faces = if (samples.size % 2 == 0) faces(ctx, bmp) else samples.lastOrNull()?.faces ?: emptyList()
            val grey = ByteArray(argb.size) { (((argb[it] shr 16) and 255) * 299 + ((argb[it] shr 8) and 255) * 587 + (argb[it] and 255) * 114).div(1000).toByte() }
            samples.add(Sample(ms, AutoEnhance.sharpness(argb, gw, gh), exposure(argb), faces, grey))
            if (middle == null && ms >= midMs) middle = bmp else bmp.recycle()
        }
        try {
            if (durMs <= 180_000) {
                // Frames twice a second, decoded in one pass.
                var next = 0L
                FrameGrabber(ctx, item.uri, info, gw, gh).run(0, info.durationUs, null, { pts -> pts >= next }, { argb, pts ->
                    take(argb, pts / 1000); next = pts + 500_000
                    !cancelled()
                }) {}
            } else {
                // Long recordings: 60 frames spread over the whole video.
                for (k in 0 until 60) {
                    if (cancelled()) break
                    val ms = durMs * (2 * k + 1) / 120
                    val f = mmr.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, gw, gh) ?: continue
                    val sc = if (f.width != gw || f.height != gh) Bitmap.createScaledBitmap(f, gw, gh, true).also { if (it !== f) f.recycle() } else f
                    val px = IntArray(gw * gh); sc.getPixels(px, 0, gw, 0, 0, gw, gh); sc.recycle()
                    take(px, ms)
                }
            }
        } catch (t: Throwable) {
            if (samples.isEmpty()) android.util.Log.w("LMT", "highlight: frames unavailable", t)
        } finally { try { mmr.release() } catch (_: Exception) { } }
        // Windows between samples: motion from the change since the previous sample.
        val windows = ArrayList<VideoWindow>()
        for ((i, sm) in samples.withIndex()) {
            val start = if (i == 0) 0L else (samples[i - 1].ms + sm.ms) / 2
            val end = if (i == samples.size - 1) durMs else (sm.ms + samples[i + 1].ms) / 2
            if (end <= start) continue
            val motion = if (i == 0) samples.getOrNull(1)?.let { change(sm.grey, it.grey) } ?: 0f else change(samples[i - 1].grey, sm.grey)
            val biggest = sm.faces.maxOfOrNull { it.area } ?: 0f
            val l = loud?.let { lv -> window(lv, start, end) } ?: 0f
            windows.add(VideoWindow(start, end, motion, sm.sharp, sm.faces.size, biggest, l))
        }
        val mid = middle
        val (tags, features) = if (mid != null) try { tags(ctx, mid) } finally { mid.recycle() } else emptyMap<String, Float>() to null
        val midSample = samples.minByOrNull { abs(it.ms - midMs) }
        val sharp = samples.map { it.sharp }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        return ShotInfo(ShotKind.VIDEO, CaptureTime.best(taken, library.taken, library.modified ?: System.currentTimeMillis()), dw, dh, durMs,
            sharp, midSample?.exposure ?: 1f, midSample?.faces ?: emptyList(), tags, features, library.favorite, windows, info.hasAudio && loud?.any { it > 0.02f } != false, info.rotation)
    }

    /** How much the picture changed between two small grey frames (0 = still, 1 = completely different). */
    private fun change(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var sum = 0L
        for (i in a.indices) sum += abs((a[i].toInt() and 255) - (b[i].toInt() and 255))
        // A mean change of 40 grey levels is a lot (people moving across the frame, a pan).
        return (sum.toDouble() / a.size / 40.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun window(levels: FloatArray, startMs: Long, endMs: Long): Float {
        val a = (startMs / 500).toInt().coerceIn(0, levels.size); val b = ((endMs + 499) / 500).toInt().coerceIn(a, levels.size)
        if (b <= a) return 0f
        var m = 0f; for (k in a until b) m = max(m, levels[k])
        return m
    }

    /** Loudness per half second (0 silent … 1 loud), from the decoded sound track. */
    private fun loudness(ctx: Context, uri: Uri, durMs: Long, cancelled: () -> Boolean): FloatArray {
        val n = (durMs / 500 + 1).toInt()
        val sum = DoubleArray(n); val count = IntArray(n)
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return FloatArray(0)
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, null, null, 0); dec.start()
            val bi = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            var float = false
            var idle = 0
            while (!outDone && idle < 500 && !cancelled()) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val size = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (size < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(bi, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    idle = 0
                    if (bi.size > 0) {
                        val buf = dec.getOutputBuffer(o)!!
                        buf.position(bi.offset); buf.limit(bi.offset + bi.size)
                        val bb = buf.slice().order(ByteOrder.nativeOrder())
                        val k = (bi.presentationTimeUs / 500_000).toInt().coerceIn(0, n - 1)
                        var s = 0.0; var m = 0
                        if (float) { val fb = bb.asFloatBuffer(); while (fb.hasRemaining()) { val v = fb.get(); s += v * v; m++ } }
                        else { val sb = bb.asShortBuffer(); while (sb.hasRemaining()) { val v = sb.get() / 32768.0; s += v * v; m++ } }
                        sum[k] += s; count[k] += m
                    }
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    dec.releaseOutputBuffer(o, false)
                } else idle++
            }
        } finally {
            try { dec?.stop() } catch (_: Exception) { }
            try { dec?.release() } catch (_: Exception) { }
            ex.release()
        }
        // RMS 0.25 (a loud voice close to the phone) counts as 1.
        return FloatArray(n) { if (count[it] == 0) 0f else (sqrt(sum[it] / count[it]) / 0.25).coerceIn(0.0, 1.0).toFloat() }
    }
}
