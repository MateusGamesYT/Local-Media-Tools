package com.localmediatools.vision

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import com.localmediatools.stitch.OpenCvLoader
import com.localmediatools.stitch.TfliteEmbedder
import com.localmediatools.vision.core.AutoEnhance
import com.localmediatools.vision.core.DupKind
import com.localmediatools.vision.core.Duplicates
import com.localmediatools.vision.core.PhotoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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
)

class DupResultGroup(val kind: DupKind, val photos: List<GalleryPhoto>, val best: GalleryPhoto) {
    /** Bytes freed by removing everything but the best photo. */
    val extraBytes get() = photos.filter { it !== best }.sumOf { it.size }
}

sealed class DupScanState {
    object Idle : DupScanState()
    class Running(val done: Int, val total: Int, val phase: String) : DupScanState()
    class Done(val groups: List<DupResultGroup>, val scanned: Int, val aiUsed: Boolean) : DupScanState()
    class Failed(val message: String) : DupScanState()
}

/**
 * Scans the photo library for duplicates in the background (it keeps running while the user moves
 * around the app). What it learns about each photo — a difference hash, a sharpness score and an
 * image embedding from the on-device model — is cached, so later scans only look at new photos.
 */
object DuplicateScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<DupScanState>(DupScanState.Idle)
    val state: StateFlow<DupScanState> = _state

    private class Entry(val modified: Long, val size: Long, val dhash: Long, val sharpness: Double, val scale: Float, val emb: ByteArray?)

    fun start(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        _state.value = DupScanState.Running(0, 0, "Reading your photo library")
        job = scope.launch {
            try {
                _state.value = scan(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = DupScanState.Idle
            } catch (e: Throwable) {
                android.util.Log.w("LMT", "duplicate scan failed", e)
                _state.value = DupScanState.Failed(e.message ?: "The scan stopped unexpectedly.")
            }
        }
    }

    fun cancel() { job?.cancel(); _state.value = DupScanState.Idle }

    /** Shows a given state (JVM tests and previews, where the photo library can't be scanned). */
    fun show(s: DupScanState) { job?.cancel(); _state.value = s }

    /** Updates the results after photos were moved to the trash. */
    fun removed(uris: Set<Uri>) {
        val s = _state.value as? DupScanState.Done ?: return
        val groups = s.groups.mapNotNull { g ->
            val left = g.photos.filter { it.uri !in uris }
            if (left.size < 2) null else DupResultGroup(g.kind, left, if (g.best.uri in uris) left.maxByOrNull { it.width.toLong() * it.height }!! else g.best)
        }
        _state.value = DupScanState.Done(groups, s.scanned - uris.size, s.aiUsed)
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "duplicate-cache-v1.bin")

    private fun loadCache(ctx: Context): HashMap<Long, Entry> {
        val map = HashMap<Long, Entry>()
        try {
            DataInputStream(cacheFile(ctx).inputStream().buffered(1 shl 16)).use { d ->
                val n = d.readInt()
                repeat(n) {
                    val id = d.readLong(); val mod = d.readLong(); val size = d.readLong(); val h = d.readLong(); val sh = d.readDouble()
                    val scale = d.readFloat(); val len = d.readInt()
                    val emb = if (len > 0) ByteArray(len).also { d.readFully(it) } else null
                    map[id] = Entry(mod, size, h, sh, scale, emb)
                }
            }
        } catch (_: Exception) { }
        return map
    }

    private fun saveCache(ctx: Context, map: Map<Long, Entry>) {
        try {
            val tmp = File(ctx.filesDir, "duplicate-cache-v1.tmp")
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { d ->
                d.writeInt(map.size)
                for ((id, e) in map) {
                    d.writeLong(id); d.writeLong(e.modified); d.writeLong(e.size); d.writeLong(e.dhash); d.writeDouble(e.sharpness)
                    d.writeFloat(e.scale); d.writeInt(e.emb?.size ?: 0); e.emb?.let { d.write(it) }
                }
            }
            tmp.renameTo(cacheFile(ctx))
        } catch (_: Exception) { }
    }

    private fun query(ctx: Context): List<GalleryPhoto> {
        val list = ArrayList<GalleryPhoto>()
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.ORIENTATION, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE)
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
                    if (c.isNull(5)) null else c.getLong(5).takeIf { it > 0 }, c.getLong(6), c.getString(8)))
            }
        }
        return list
    }

    private suspend fun scan(ctx: Context): DupScanState {
        val photos = query(ctx)
        if (photos.size < 2) return DupScanState.Done(emptyList(), photos.size, false)
        val cache = loadCache(ctx)
        val (aiOk, _) = TfliteEmbedder.availability(ctx)
        val embedder = if (aiOk) try { OpenCvLoader.ensure(); TfliteEmbedder.create(ctx, 2) } catch (_: Throwable) { null } else null
        val infos = ArrayList<PhotoInfo>(photos.size)
        val fresh = HashMap<Long, Entry>()
        try {
            for ((i, p) in photos.withIndex()) {
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                if (i % 8 == 0) _state.value = DupScanState.Running(i, photos.size, "Looking at your photos")
                var e = cache[p.id]?.takeIf { it.modified == p.modified && it.size == p.size && (it.emb != null || embedder == null) }
                if (e == null) e = try { analyse(ctx, p, embedder) } catch (_: Throwable) { null }
                if (e == null) continue
                fresh[p.id] = e
                val emb = e.emb?.let { b -> FloatArray(b.size) { b[it] * e.scale } }
                if (emb != null) com.localmediatools.vision.core.FaceEngine.normalize(emb)
                infos.add(PhotoInfo(i, p.size, max(1, p.width), max(1, p.height), e.dhash, emb, e.sharpness, p.takenMs, null))
            }
        } finally {
            embedder?.close()
        }
        saveCache(ctx, fresh)
        // Identical files: only photos with the same size are read and compared.
        _state.value = DupScanState.Running(photos.size, photos.size, "Comparing")
        val bySize = infos.groupBy { it.bytes }.filter { it.value.size > 1 }
        val digests = HashMap<Int, String>()
        for (g in bySize.values) for (inf in g) digest(ctx, photos[inf.index].uri)?.let { digests[inf.index] = it }
        val withDigest = infos.map { if (digests.containsKey(it.index)) PhotoInfo(it.index, it.bytes, it.width, it.height, it.dhash, it.embedding, it.sharpness, it.takenMs, digests[it.index]) else it }
        val job = coroutineContext[Job]
        val groups = Duplicates.group(withDigest) { job?.isActive == false }
        val result = groups.map { g -> DupResultGroup(g.kind, g.members.map { photos[it] }, photos[g.best]) }
        return DupScanState.Done(result, photos.size, embedder != null)
    }

    private fun analyse(ctx: Context, p: GalleryPhoto, embedder: TfliteEmbedder?): Entry {
        val thumb: Bitmap = ctx.contentResolver.loadThumbnail(p.uri, Size(256, 256), null)
        try {
            val bmp = if (thumb.config == Bitmap.Config.ARGB_8888) thumb else thumb.copy(Bitmap.Config.ARGB_8888, false)
            // Difference hash from a 9×8 grey version.
            val tiny = Bitmap.createScaledBitmap(bmp, 9, 8, true)
            val px = IntArray(72); tiny.getPixels(px, 0, 9, 0, 0, 9, 8); tiny.recycle()
            val gray = IntArray(72) { val c = px[it]; (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000 }
            val dhash = Duplicates.dhash(gray)
            val s = 128f / max(bmp.width, bmp.height)
            val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, max(3, (bmp.width * s).roundToInt()), max(3, (bmp.height * s).roundToInt()), true) else bmp
            val spx = IntArray(small.width * small.height); small.getPixels(spx, 0, small.width, 0, 0, small.width, small.height)
            val sharp = AutoEnhance.sharpness(spx, small.width, small.height)
            if (small !== bmp) small.recycle()
            var scale = 0f; var emb: ByteArray? = null
            if (embedder != null) {
                val m = Mat()
                Utils.bitmapToMat(bmp, m)
                val v = try { embedder.embed(m) } finally { m.release() }
                val maxAbs = v.maxOf { abs(it) }.coerceAtLeast(1e-6f)
                scale = maxAbs / 127f
                emb = ByteArray(v.size) { (v[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }
            }
            if (bmp !== thumb) bmp.recycle()
            return Entry(p.modified, p.size, dhash, sharp, scale, emb)
        } finally {
            thumb.recycle()
        }
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
