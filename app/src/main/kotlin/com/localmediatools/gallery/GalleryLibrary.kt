package com.localmediatools.gallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
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

    /** Whether the app may read photos or videos (all of them, or the ones the user selected). */
    fun hasAccess(ctx: Context): Boolean {
        fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            source != null -> true
            Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

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
        // No cursor means the media provider isn't answering, not that the library is empty.
        val cursor = ctx.contentResolver.query(FILES, cols.toTypedArray(), sel, null, null) ?: throw IllegalStateException("The photo library isn't available right now")
        cursor.use { c ->
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

    /**
     * Brings the index up to date. Returns (added or changed, removed).
     *
     * Removing is guarded, because a library that suddenly reads as empty or much smaller is more
     * often a glitch (a memory card out for a moment, the media provider restarting) than real, and
     * removing a photo also forgets its faces: an empty read removes nothing, and losing more than
     * half of a big library only takes effect if the next read, at least half an hour later, agrees.
     */
    fun sync(ctx: Context, db: GalleryDb): Pair<Int, Int> {
        val now = query(ctx)
        val known = db.mediaById()
        val write = ArrayList<GMedia>()
        val changed = HashSet<Long>()
        for (m in now) {
            val old = known[m.id]
            when {
                old == null || old.contentDiffers(m) -> { write.add(m); changed.add(m.id) }
                old.metaDiffers(m) -> write.add(m)
            }
        }
        val present = now.mapTo(HashSet()) { it.id }
        var gone: List<Long> = known.keys.filter { it !in present }
        val prefs = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)
        val bigDrop = gone.size >= 200 && gone.size > known.size / 2
        when {
            now.isEmpty() && known.isNotEmpty() -> gone = emptyList()
            bigDrop -> {
                val seen = prefs.getLong("big_drop_at", 0L)
                if (seen == 0L || System.currentTimeMillis() - seen < 30 * 60_000L) {
                    if (seen == 0L) prefs.edit().putLong("big_drop_at", System.currentTimeMillis()).apply()
                    gone = emptyList()
                } else prefs.edit().remove("big_drop_at").apply()
            }
            else -> if (prefs.contains("big_drop_at")) prefs.edit().remove("big_drop_at").apply()
        }
        if (write.isNotEmpty()) db.upsert(write, changed)
        db.remove(gone)
        return write.size to gone.size
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
        // Photos and videos only: other files changing (downloads, documents) aren't a reason to re-read.
        try {
            val r = ctx.applicationContext.contentResolver
            r.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer!!)
            r.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer!!)
        } catch (_: Exception) { observer = null }
    }
}
