package com.localmediatools.vision

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.localmediatools.gallery.GalleryDb
import com.localmediatools.i18n.tr
import com.localmediatools.stitch.OpenCvLoader
import com.localmediatools.stitch.TfliteEmbedder
import com.localmediatools.vision.core.AutoEnhance
import com.localmediatools.vision.core.DupKind
import com.localmediatools.vision.core.Duplicates
import com.localmediatools.vision.core.FeatureCheck
import com.localmediatools.vision.core.KeepCandidate
import com.localmediatools.vision.core.PhotoInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** A photo in the device's library. */
class GalleryPhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val takenMs: Long?,
    val modified: Long,
    val album: String?,
    val mime: String = "image/jpeg",
    val favorite: Boolean = false,
) {
    /** A camera RAW file (DNG, CR2, NEF, ARW…). */
    val raw: Boolean get() = isRaw(mime, name)

    companion object {
        private val RAW_EXT = setOf("dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef", "srw", "x3f", "3fr", "iiq", "erf", "kdc", "mef", "mos", "rwl")
        fun isRaw(mime: String, name: String): Boolean =
            mime == "image/x-adobe-dng" || mime == "image/dng" || (mime.startsWith("image/x-") && RAW_EXT.any { mime.endsWith(it) }) ||
                name.substringAfterLast('.', "").lowercase() in RAW_EXT
    }
}

/**
 * A group found by the scan. [pictures] splits [photos] into distinct pictures (copies of each
 * other); [sharpness] is what the scan measured (for choosing which copy to keep).
 */
class DupResultGroup(
    val kind: DupKind,
    val photos: List<GalleryPhoto>,
    val best: GalleryPhoto,
    val pictures: List<List<GalleryPhoto>> = photos.map { listOf(it) },
    val sharpness: Map<Uri, Double> = emptyMap(),
) {
    /** Bytes freed by removing everything but the best photo. */
    val extraBytes get() = photos.filter { it !== best }.sumOf { it.size }
    val newest get() = photos.maxOf { it.takenMs ?: it.modified * 1000 }

    fun candidate(p: GalleryPhoto, people: Set<Long>) =
        KeepCandidate(p.size, p.width.toLong() * p.height, sharpness[p.uri] ?: 0.0, p.modified, p.raw, p.favorite, people)
}

sealed class DupScanState {
    object Idle : DupScanState()
    /**
     * [fraction]: share of the whole scan done (looking at the photos is the first 80 %, comparing them
     * the rest), null while it can't be told; [done] of [total] photos looked at; [leftMs]: about how
     * long looking at the rest takes, when it can be told.
     */
    class Running(val phase: String, val fraction: Float?, val done: Int = 0, val total: Int = 0, val leftMs: Long? = null) : DupScanState()
    /** [unreadable]: photos whose picture couldn't be read (still checked for identical copies). */
    class Done(val groups: List<DupResultGroup>, val scanned: Int, val aiUsed: Boolean, val unreadable: Int = 0) : DupScanState()
    class Failed(val message: String) : DupScanState()
}

/**
 * Scans the photo library for duplicates in the background (it keeps running while the user moves
 * around the app). What it learns about each photo — a difference hash, the picture's layout, a
 * sharpness score and an image embedding from the on-device model — is cached, so later scans only
 * look at new photos. Photos are read a few at a time in parallel; the model runs on its own threads.
 */
object DuplicateScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<DupScanState>(DupScanState.Idle)
    val state: StateFlow<DupScanState> = _state

    /** Stand-ins for tests (no MediaStore, thumbnails or AI model in Robolectric): the library, a photo's thumbnail, its embedding. */
    @Volatile var source: ((Context) -> List<GalleryPhoto>)? = null
    @Volatile var thumbnails: ((Context, GalleryPhoto) -> Bitmap)? = null
    @Volatile var embeddings: ((GalleryPhoto) -> FloatArray?)? = null

    private class Entry(
        val modified: Long, val size: Long, val dhash: Long, val sharpness: Double,
        val gridScale: Float, val grid: ByteArray, val contrast: Float,
        val scale: Float, val emb: ByteArray?,
        /** The thumbnail's size: the picture's shape as shown (upright), whatever MediaStore says. */
        val thumbW: Int, val thumbH: Int,
    )

    fun start(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        _state.value = DupScanState.Running(tr("Reading your photo library"), null)
        job = scope.launch {
            try {
                _state.value = scan(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = DupScanState.Idle
            } catch (e: Throwable) {
                android.util.Log.w("LMT", "duplicate scan failed", e)
                _state.value = DupScanState.Failed(e.message ?: tr("The scan stopped unexpectedly."))
            }
        }
    }

    fun cancel() { job?.cancel(); _state.value = DupScanState.Idle }

    /** Shows a given state (tests and previews, where the photo library can't be scanned). */
    fun show(s: DupScanState) { job?.cancel(); _state.value = s }

    /** Updates the results after photos were moved to the trash. */
    fun removed(uris: Set<Uri>) {
        val s = _state.value as? DupScanState.Done ?: return
        val groups = s.groups.mapNotNull { g ->
            val left = g.photos.filter { it.uri !in uris }
            if (left.size < 2) null else {
                val pics = g.pictures.map { p -> p.filter { it.uri !in uris } }.filter { it.isNotEmpty() }
                DupResultGroup(g.kind, left, if (g.best.uri in uris) left.maxByOrNull { it.width.toLong() * it.height }!! else g.best, pics, g.sharpness)
            }
        }
        _state.value = DupScanState.Done(groups, s.scanned - uris.size, s.aiUsed, s.unreadable)
    }

    /** Gallery people in each photo (by MediaStore id), for the "keep photos of…" rule. */
    fun peopleIn(ctx: Context, ids: Collection<Long>): Map<Long, Set<Long>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableSet<Long>>()
        try {
            val db = GalleryDb.get(ctx).readableDatabase
            for (chunk in ids.chunked(500)) {
                db.rawQuery("SELECT media_id, person_id FROM faces WHERE ignored = 0 AND person_id IS NOT NULL AND media_id IN (${chunk.joinToString(",")})", null).use { c ->
                    while (c.moveToNext()) out.getOrPut(c.getLong(0)) { HashSet() }.add(c.getLong(1))
                }
            }
        } catch (e: Exception) { android.util.Log.w("LMT", "people lookup failed", e) }
        return out
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "duplicate-cache-v3.bin")

    private fun loadCache(ctx: Context): HashMap<Long, Entry> {
        File(ctx.filesDir, "duplicate-cache-v1.bin").delete()  // 1.6.0's, without the layout
        File(ctx.filesDir, "duplicate-cache-v2.bin").delete()  // 1.7–1.9's, without the thumbnail's shape
        val map = HashMap<Long, Entry>()
        try {
            DataInputStream(cacheFile(ctx).inputStream().buffered(1 shl 16)).use { d ->
                val n = d.readInt()
                repeat(n) {
                    val id = d.readLong(); val mod = d.readLong(); val size = d.readLong(); val h = d.readLong(); val sh = d.readDouble()
                    val gs = d.readFloat(); val grid = ByteArray(256).also { d.readFully(it) }; val contrast = d.readFloat()
                    val scale = d.readFloat(); val len = d.readInt()
                    val emb = if (len > 0) ByteArray(len).also { d.readFully(it) } else null
                    val tw = d.readInt(); val th = d.readInt()
                    map[id] = Entry(mod, size, h, sh, gs, grid, contrast, scale, emb, tw, th)
                }
            }
        } catch (_: Exception) { }
        return map
    }

    private fun saveCache(ctx: Context, map: Map<Long, Entry>) {
        try {
            val tmp = File(ctx.filesDir, "duplicate-cache-v2.tmp")
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { d ->
                d.writeInt(map.size)
                for ((id, e) in map) {
                    d.writeLong(id); d.writeLong(e.modified); d.writeLong(e.size); d.writeLong(e.dhash); d.writeDouble(e.sharpness)
                    d.writeFloat(e.gridScale); d.write(e.grid); d.writeFloat(e.contrast)
                    d.writeFloat(e.scale); d.writeInt(e.emb?.size ?: 0); e.emb?.let { d.write(it) }
                    d.writeInt(e.thumbW); d.writeInt(e.thumbH)
                }
            }
            tmp.renameTo(cacheFile(ctx))
        } catch (_: Exception) { }
    }

    internal fun query(ctx: Context): List<GalleryPhoto> {
        val list = ArrayList<GalleryPhoto>()
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val fav = Build.VERSION.SDK_INT >= 30
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.ORIENTATION, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE) + (if (fav) arrayOf(MediaStore.MediaColumns.IS_FAVORITE) else emptyArray())
        ctx.contentResolver.query(col, proj, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(9) ?: ""
                if (mime == "image/gif") continue
                val size = c.getLong(2)
                if (size <= 0) continue
                val id = c.getLong(0)
                val rot = c.getInt(7)
                var w = c.getInt(3); var h = c.getInt(4)
                if (rot % 180 != 0) { val t = w; w = h; h = t }
                list.add(GalleryPhoto(id, ContentUris.withAppendedId(col, id), c.getString(1) ?: "photo", size, w, h,
                    if (c.isNull(5)) null else c.getLong(5).takeIf { it > 0 }, c.getLong(6), c.getString(8), mime,
                    fav && c.getInt(10) != 0))
            }
        }
        return list
    }

    private suspend fun scan(ctx: Context): DupScanState {
        val photos = source?.invoke(ctx) ?: query(ctx)
        if (photos.size < 2) return DupScanState.Done(emptyList(), photos.size, false)
        val cache = loadCache(ctx)
        val (aiOk, _) = TfliteEmbedder.availability(ctx)
        val cores = Runtime.getRuntime().availableProcessors()
        val embedder = if (aiOk) try { OpenCvLoader.ensure(); TfliteEmbedder.create(ctx, (cores / 2).coerceIn(2, 4)) } catch (e: Throwable) {
            android.util.Log.w("LMT", "duplicate finder: image model unavailable", e); null
        } else null
        val stand = embeddings
        val ai = embedder != null || stand != null
        val entries = arrayOfNulls<Entry>(photos.size)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val firstError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val fresh = HashMap<Long, Entry>()
        // Photos measured before (and unchanged) take no time: the time left counts the others.
        val known = BooleanArray(photos.size) { i -> cache[photos[i].id]?.let { it.modified == photos[i].modified && it.size == photos[i].size && (it.emb != null || !ai) } == true }
        val toRead = known.count { !it }.coerceAtLeast(1)
        val looked = java.util.concurrent.atomic.AtomicInteger()
        val read = java.util.concurrent.atomic.AtomicInteger()
        val lastReport = java.util.concurrent.atomic.AtomicLong(0)
        val left = com.localmediatools.vision.core.TimeLeft()
        val looking = tr("Looking at your photos")
        // A few times a second at most; one at a time, so the count never steps back.
        fun report(force: Boolean = false) {
            if (!force && System.currentTimeMillis() - lastReport.get() < 200) return
            synchronized(lastReport) {
                val now = System.currentTimeMillis()
                if (!force && now - lastReport.get() < 200) return
                lastReport.set(now)
                val d = looked.get()
                _state.value = DupScanState.Running(looking, (0.8 * d / photos.size).toFloat(), d, photos.size, left.at(read.get().toDouble() / toRead))
            }
        }
        report(force = true)
        try {
            // Thumbnails are read and measured a few at a time; the model itself is used by one at a time.
            val parallel = (cores - 1).coerceIn(1, 4)
            for (start in photos.indices step 32) {
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                val batch = (start until minOf(photos.size, start + 32)).toList()
                coroutineScope {
                    batch.chunked((batch.size + parallel - 1) / parallel).map { part ->
                        async(Dispatchers.Default) {
                            for (i in part) {
                                val p = photos[i]
                                var e = if (known[i]) cache[p.id] else null
                                if (e == null) {
                                    e = try { analyse(ctx, p, embedder) } catch (t: Throwable) {
                                        if (failures.incrementAndGet() == 1) { firstError.set(t); android.util.Log.w("LMT", "duplicate finder: can't read ${p.name}", t) }
                                        null
                                    }
                                    read.incrementAndGet()
                                }
                                entries[i] = e
                                looked.incrementAndGet()
                                report()
                            }
                        }
                    }.awaitAll()
                }
            }
            report(force = true)
        } finally {
            embedder?.close()
        }
        val unreadable = failures.get()
        if (unreadable == photos.size) {
            val why = firstError.get()?.let { com.localmediatools.core.Errors.describe(it) } ?: tr("unknown error")
            return DupScanState.Failed(tr("none of your {0} photos could be read ({1}).", photos.size, why))
        }
        val infos = ArrayList<PhotoInfo>(photos.size)
        for ((i, p) in photos.withIndex()) {
            val e = entries[i]
            if (e == null) {
                // Its picture couldn't be read: it can still be an identical copy of another file.
                infos.add(PhotoInfo(i, p.size, max(1, p.width), max(1, p.height), 0L, null, null, 0f, 0.0, p.takenMs, null))
                continue
            }
            fresh[p.id] = e
            val emb = e.emb?.let { b -> FloatArray(b.size) { b[it] * e.scale } }
            if (emb != null) com.localmediatools.vision.core.FaceEngine.normalize(emb)
            val grid = FloatArray(256) { e.grid[it] * e.gridScale }
            val (w, h) = shape(p, e)
            infos.add(PhotoInfo(i, p.size, w, h, e.dhash, emb, grid, e.contrast, e.sharpness, p.takenMs, null))
        }
        saveCache(ctx, fresh)
        // Identical files: only photos with the same size are read and compared.
        val comparing = tr("Comparing")
        var lastCompare = 0L
        fun compared(f: Float) {
            val now = System.currentTimeMillis()
            if (now - lastCompare < 200 && f < 1f) return
            lastCompare = now
            _state.value = DupScanState.Running(comparing, (0.8 + 0.2 * f.coerceIn(0f, 1f)).toFloat(), photos.size, photos.size)
        }
        compared(0f)
        val bySize = infos.groupBy { it.bytes }.filter { it.value.size > 1 }
        val digests = HashMap<Int, String>()
        val sameSize = bySize.values.sumOf { it.size }.coerceAtLeast(1)
        var hashed = 0
        for (g in bySize.values) for (inf in g) {
            digest(ctx, photos[inf.index].uri)?.let { digests[inf.index] = it }
            compared(0.1f * ++hashed / sameSize)
        }
        val withDigest = infos.map { if (digests.containsKey(it.index)) PhotoInfo(it.index, it.bytes, it.width, it.height, it.dhash, it.embedding, it.grid, it.contrast, it.sharpness, it.takenMs, digests[it.index]) else it }
        val job = coroutineContext[Job]
        val features = if (embedder != null) FeatureMatcher(ctx, photos, withDigest) else null
        val groups = try {
            Duplicates.group(withDigest, verify = features?.let { f -> { a: Int, b: Int -> f.check(a, b) } }, progress = { compared(0.1f + 0.9f * it) }) { job?.isActive == false }
        } finally { features?.release() }
        if (job?.isActive == false) throw kotlinx.coroutines.CancellationException()
        val sharp = withDigest.associate { photos[it.index].uri to it.sharpness }
        val result = groups.map { g ->
            val members = g.members.map { photos[it] }
            DupResultGroup(g.kind, members, photos[g.best], g.pictures.map { p -> p.map { photos[it] } }, members.associate { it.uri to (sharp[it.uri] ?: 0.0) })
        }
        return DupScanState.Done(result, photos.size, ai, unreadable)
    }

    /** Compares features of two photos' thumbnails, keeping the features of recently used ones. */
    private class FeatureMatcher(private val ctx: Context, private val photos: List<GalleryPhoto>, private val items: List<PhotoInfo>) {
        private val check = FeatureCheck()
        private val cache = object : LruCache<Int, FeatureCheck.Features>(400) {
            override fun entryRemoved(evicted: Boolean, key: Int, oldValue: FeatureCheck.Features, newValue: FeatureCheck.Features?) { oldValue.release() }
        }

        private fun features(pos: Int): FeatureCheck.Features? {
            cache.get(pos)?.let { return it }
            val uri = photos[items[pos].index].uri
            val thumb = try { ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null) } catch (_: Exception) { return null }
            val rgba = Mat(); val gray = Mat()
            try {
                val bmp = if (thumb.config == Bitmap.Config.ARGB_8888) thumb else thumb.copy(Bitmap.Config.ARGB_8888, false)
                Utils.bitmapToMat(bmp, rgba)
                if (bmp !== thumb) bmp.recycle()
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                return check.features(gray).also { cache.put(pos, it) }
            } finally { thumb.recycle(); rgba.release(); gray.release() }
        }

        fun check(a: Int, b: Int): Duplicates.Check? {
            val fa = features(a) ?: return null
            val fb = features(b) ?: return null
            return check.compare(fa, fb)
        }

        fun release() { cache.evictAll() }
    }

    private fun analyse(ctx: Context, p: GalleryPhoto, embedder: TfliteEmbedder?): Entry {
        val thumb: Bitmap = thumbnails?.invoke(ctx, p) ?: ctx.contentResolver.loadThumbnail(p.uri, Size(256, 256), null)
        try {
            val bmp = if (thumb.config == Bitmap.Config.ARGB_8888) thumb else thumb.copy(Bitmap.Config.ARGB_8888, false)
            // Difference hash from a 9×8 grey version.
            val tiny = Bitmap.createScaledBitmap(bmp, 9, 8, true)
            val px = IntArray(72); tiny.getPixels(px, 0, 9, 0, 0, 9, 8); tiny.recycle()
            val gray = IntArray(72) { val c = px[it]; (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000 }
            val dhash = Duplicates.dhash(gray)
            // The picture's layout, from every pixel of the thumbnail.
            val all = IntArray(bmp.width * bmp.height); bmp.getPixels(all, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val (grid, contrast) = Duplicates.grid(all, bmp.width, bmp.height)
            val gMax = grid.maxOf { abs(it) }.coerceAtLeast(1e-6f)
            val gScale = gMax / 127f
            val gq = ByteArray(256) { (grid[it] / gScale).roundToInt().coerceIn(-127, 127).toByte() }
            val s = 128f / max(bmp.width, bmp.height)
            val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, max(3, (bmp.width * s).roundToInt()), max(3, (bmp.height * s).roundToInt()), true) else bmp
            val spx = IntArray(small.width * small.height); small.getPixels(spx, 0, small.width, 0, 0, small.width, small.height)
            val sharp = AutoEnhance.sharpness(spx, small.width, small.height)
            if (small !== bmp) small.recycle()
            var scale = 0f; var emb: ByteArray? = null
            val stand = embeddings
            if (embedder != null || stand != null) {
                val v = if (embedder != null) {
                    val m = Mat()
                    Utils.bitmapToMat(bmp, m)
                    try { embedder.embed(m) } finally { m.release() }
                } else stand!!(p) ?: throw IllegalStateException("no embedding")
                val maxAbs = v.maxOf { abs(it) }.coerceAtLeast(1e-6f)
                scale = maxAbs / 127f
                emb = ByteArray(v.size) { (v[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }
            }
            if (bmp !== thumb) bmp.recycle()
            return Entry(p.modified, p.size, dhash, sharp, gScale, gq, contrast, scale, emb, thumb.width, thumb.height)
        } finally {
            thumb.recycle()
        }
    }

    /**
     * Width and height for comparing shapes and resolutions: the thumbnail's shape (upright as
     * shown; MediaStore's sizes and orientation are wrong on some phones, which kept copies of
     * turned photos apart) at MediaStore's pixel count, or the thumbnail's own size without one.
     */
    private fun shape(p: GalleryPhoto, e: Entry): Pair<Int, Int> {
        if (e.thumbW <= 0 || e.thumbH <= 0) return max(1, p.width) to max(1, p.height)
        val px = p.width.toLong() * p.height
        if (px <= 0) return e.thumbW to e.thumbH
        val aspect = e.thumbW.toDouble() / e.thumbH
        val w = Math.sqrt(px * aspect).roundToInt().coerceAtLeast(1)
        return w to (px / w).toInt().coerceAtLeast(1)
    }

    private fun digest(ctx: Context, uri: Uri): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }
}
