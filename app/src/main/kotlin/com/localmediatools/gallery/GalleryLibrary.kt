package com.localmediatools.gallery

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/** Reads the phone's photo and video library (MediaStore) into the gallery index. */
object GalleryLibrary {
    private val FILES: Uri get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun uriOf(id: Long, video: Boolean): Uri =
        ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    fun isScreenshot(path: String, bucket: String, name: String): Boolean {
        val p = path.lowercase(); val b = bucket.lowercase(); val n = name.lowercase()
        return "screenshot" in p || "screenshot" in b || n.startsWith("screenshot") || "screen_recording" in p || "screenrecord" in p
    }

    /** Stands in for MediaStore where there is none (JVM tests). */
    @Volatile var source: ((Context) -> List<GMedia>)? = null

    /** Everything the app may see right now (with "selected photos" access, only those). */
    fun query(ctx: Context): List<GMedia> {
        source?.let { return it(ctx) }
        val out = ArrayList<GMedia>()
        val cols = arrayListOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.DATE_ADDED,
        )
        if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.MediaColumns.IS_FAVORITE)
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ${MediaStore.MediaColumns.SIZE} > 0"
        ctx.contentResolver.query(FILES, cols.toTypedArray(), sel, null, null)?.use { c ->
            while (c.moveToNext()) {
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val mime = c.getString(2) ?: if (video) "video/*" else "image/*"
                val modified = c.getLong(8)
                val taken = if (!c.isNull(7) && c.getLong(7) > 0) c.getLong(7)
                    else if (modified > 0) modified * 1000 else c.getLong(14) * 1000
                val rot = if (c.isNull(12)) 0 else c.getInt(12)
                var w = c.getInt(10); var h = c.getInt(11)
                if (rot % 180 != 0) { val t = w; w = h; h = t }
                out.add(GMedia(
                    id = c.getLong(0), video = video, mime = mime, name = c.getString(3) ?: "",
                    bucketId = c.getString(4) ?: "", bucket = c.getString(5) ?: "Other", path = c.getString(6) ?: "",
                    taken = taken, modified = modified, size = c.getLong(9), width = w, height = h, orientation = rot,
                    durationMs = if (c.isNull(13)) 0 else c.getLong(13),
                    favorite = Build.VERSION.SDK_INT >= 30 && c.getInt(15) == 1,
                ))
            }
        }
        return out
    }

    /** Brings the index up to date. Returns (added or changed, removed). */
    fun sync(ctx: Context, db: GalleryDb): Pair<Int, Int> {
        val now = query(ctx)
        val known = db.mediaStamps()
        val changed = HashSet<Long>()
        for (m in now) {
            val stamp = m.modified * 31 + m.size
            val old = known[m.id]
            if (old == null || old != stamp) changed.add(m.id)
        }
        val present = now.mapTo(HashSet()) { it.id }
        val gone = known.keys.filter { it !in present }
        db.upsert(now, changed)
        db.remove(gone)
        return changed.size to gone.size
    }

    private var observer: ContentObserver? = null

    /** Calls [onChange] (debounced) whenever photos or videos are added, changed or removed. */
    fun watch(ctx: Context, onChange: () -> Unit) {
        if (observer != null) return
        val h = Handler(Looper.getMainLooper())
        val fire = Runnable { onChange() }
        observer = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) {
                h.removeCallbacks(fire); h.postDelayed(fire, 1500)
            }
        }
        try { ctx.applicationContext.contentResolver.registerContentObserver(FILES, true, observer!!) } catch (_: Exception) { observer = null }
    }
}
