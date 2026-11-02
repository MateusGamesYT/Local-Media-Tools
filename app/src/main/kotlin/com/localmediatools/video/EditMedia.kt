package com.localmediatools.video

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.localmediatools.core.MediaKind
import com.localmediatools.core.MediaProbe
import com.localmediatools.highlight.SongChoice
import com.localmediatools.highlight.core.ShotKind
import com.localmediatools.image.ImageSource
import com.localmediatools.music.core.Mood
import com.localmediatools.video.core.Box
import com.localmediatools.video.core.EditClip
import com.localmediatools.video.core.EditOps
import com.localmediatools.video.core.EditProject
import com.localmediatools.video.core.EditSource
import com.localmediatools.video.core.EditText
import com.localmediatools.video.core.Framing
import com.localmediatools.video.core.Key
import com.localmediatools.video.core.Look
import com.localmediatools.video.core.LookPreset
import com.localmediatools.video.core.OutShape
import com.localmediatools.video.core.TextPlace
import com.localmediatools.video.core.TransitionKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/** The video editor's access to files: what a picked file is, a frame of it for the preview. */
object EditMedia {
    /** Stand-ins where media can't be decoded (Robolectric tests). */
    @Volatile var describeOverride: ((Uri) -> EditSource?)? = null
    @Volatile var frameOverride: ((EditSource, Long, Int) -> Bitmap?)? = null

    /** A picked file as a project source; null when it is neither a video nor a photo that can be read. */
    fun describe(ctx: Context, uri: Uri): EditSource? {
        describeOverride?.let { return it(uri) }
        return try {
            val item = MediaProbe.describe(ctx, uri)
            if (item.readError != null) return null
            // Kept readable for drafts reopened later (where the provider allows it).
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            when (item.kind) {
                MediaKind.VIDEO -> {
                    val info = VideoProbe.probe(ctx, uri)
                    val v = info.video ?: return null
                    val turned = info.rotation % 180 != 0
                    // Some files don't say how long they are in their header: ask the system's reader then.
                    val ms = if (info.durationUs > 0) info.durationUs / 1000 else retrievedDuration(ctx, uri)
                    EditSource(uri.toString(), item.name, ShotKind.VIDEO, if (turned) v.height else v.width, if (turned) v.width else v.height,
                        max(0L, ms), info.audio.isNotEmpty())
                }
                MediaKind.IMAGE -> ImageSource.open(ctx, item).use { s -> EditSource(uri.toString(), item.name, ShotKind.PHOTO, s.width, s.height) }
                else -> null
            }
        } catch (e: Exception) { android.util.Log.w("LMT", "editor: can't use $uri", e); null }
    }

    private fun retrievedDuration(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { try { r.release() } catch (_: Exception) { } }
    }

    /** Whether the file can still be read (a draft's files may have been moved or deleted since). */
    fun readable(ctx: Context, uri: Uri): Boolean {
        describeOverride?.let { return it(uri) != null }
        return try { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false } catch (_: Exception) { false }
    }

    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** The picture of [s] at [ms] (videos: the nearest frame), at most [maxSide] px; null when it can't be read. */
    fun frame(ctx: Context, s: EditSource, ms: Long, maxSide: Int): Bitmap? {
        val key = "${s.uri}@${if (s.kind == ShotKind.PHOTO) 0 else ms / 40}@$maxSide"
        cache.get(key)?.let { return it }
        val b = frameOverride?.invoke(s, ms, maxSide) ?: try {
            if (s.kind == ShotKind.PHOTO) ImageSource.open(ctx, Uri.parse(s.uri), s.name).use { it.preview(maxSide) }
            else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, Uri.parse(s.uri))
                    val sc = maxSide.toFloat() / max(1, max(s.width, s.height))
                    val f = r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, max(2, (s.width * sc).toInt()), max(2, (s.height * sc).toInt()))
                    // Some Android versions give the frame as stored, not turned as shown: turn it.
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (f != null && rot % 180 != 0 && s.width != s.height && (f.width > f.height) != (s.width > s.height)) {
                        val m = android.graphics.Matrix().apply { setRotate(rot.toFloat()) }
                        Bitmap.createBitmap(f, 0, 0, f.width, f.height, m, true).also { if (it !== f) f.recycle() }
                    } else f
                } finally { try { r.release() } catch (_: Exception) { } }
            }
        } catch (e: Exception) { null }
        if (b != null) cache.put(key, b)
        return b
    }

    fun clearCache() = cache.evictAll()
}

/**
 * The project being edited, kept between visits: saved as JSON in the app's own storage after each
 * change, with the music chosen. Files no longer readable when it is opened again are left out.
 */
object EditDraft {
    class Saved(val project: EditProject, val mood: Mood?, val song: SongChoice?, val seed: Long)

    private fun file(ctx: Context) = File(ctx.filesDir, "editor/draft.json")

    /** Saves and clears run one at a time, in the order asked (a later save never lands before an earlier one). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val io = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    fun save(ctx: Context, s: Saved) {
        val f = file(ctx); f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "draft.json.tmp")
        tmp.writeText(toJson(s).toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun clear(ctx: Context) { file(ctx).delete() }

    fun load(ctx: Context): Saved? = try { file(ctx).takeIf { it.exists() }?.readText()?.let { fromJson(JSONObject(it)) } } catch (e: Exception) { null }

    fun toJson(s: Saved): JSONObject = JSONObject().apply {
        put("version", 1)
        val p = s.project
        put("shape", p.shape.name); put("fadeOut", p.fadeOutMs); put("musicVolume", p.musicVolume.toDouble()); put("duck", p.duckMusic)
        put("clips", JSONArray().apply { for (c in p.clips) put(clip(c)) })
        put("texts", JSONArray().apply { for (t in p.texts) put(JSONObject().apply {
            put("id", t.id); put("text", t.text); put("start", t.startMs); put("end", t.endMs); put("place", t.place.name); put("size", t.size.toDouble()); put("box", t.box)
        }) })
        s.mood?.let { put("mood", it.name) }
        s.song?.let { put("song", JSONObject().apply { put("uri", it.uri.toString()); put("name", it.name); it.bpm?.let { b -> put("bpm", b) } }) }
        put("seed", s.seed)
    }

    private fun clip(c: EditClip) = JSONObject().apply {
        put("id", c.id)
        put("source", JSONObject().apply {
            put("uri", c.source.uri); put("name", c.source.name); put("kind", c.source.kind.name); put("w", c.source.width); put("h", c.source.height)
            put("duration", c.source.durationMs); put("audio", c.source.hasAudio)
        })
        put("in", c.inMs); put("out", c.outMs); put("speed", c.speed.toDouble()); put("volume", c.volume.toDouble()); put("rotation", c.rotation)
        put("framing", c.framing.name); put("panX", c.panX.toDouble()); put("panY", c.panY.toDouble()); put("scale", c.scale.toDouble()); put("zoom", c.zoom.toDouble())
        c.reframe?.let { keys ->
            put("reframeFor", c.reframeFor.toDouble())
            put("reframe", JSONArray().apply { for (k in keys) put(JSONArray().apply { put(k.ms); put(k.crop.x.toDouble()); put(k.crop.y.toDouble()); put(k.crop.w.toDouble()); put(k.crop.h.toDouble()); put(k.shot) }) })
        }
        put("look", JSONObject().apply {
            put("preset", c.look.preset.name); put("b", c.look.brightness.toDouble()); put("c", c.look.contrast.toDouble()); put("s", c.look.saturation.toDouble()); put("w", c.look.warmth.toDouble())
        })
        put("transition", c.transition.name); put("transitionMs", c.transitionMs)
    }

    fun fromJson(j: JSONObject): Saved {
        val clips = ArrayList<EditClip>()
        val ca = j.optJSONArray("clips") ?: JSONArray()
        for (i in 0 until ca.length()) {
            val o = ca.getJSONObject(i); val s = o.getJSONObject("source")
            val src = EditSource(s.getString("uri"), s.getString("name"), ShotKind.valueOf(s.getString("kind")), s.getInt("w"), s.getInt("h"), s.optLong("duration"), s.optBoolean("audio"))
            val keys = o.optJSONArray("reframe")?.let { a -> (0 until a.length()).map { k -> val r = a.getJSONArray(k); Key(r.getLong(0), Box(r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat(), r.getDouble(4).toFloat()), r.getInt(5)) } }
            val l = o.optJSONObject("look")
            val look = if (l == null) Look() else Look(LookPreset.valueOf(l.optString("preset", "NONE")), l.optDouble("b", 0.0).toFloat(), l.optDouble("c", 0.0).toFloat(), l.optDouble("s", 0.0).toFloat(), l.optDouble("w", 0.0).toFloat())
            clips.add(EditClip(o.getLong("id"), src, o.getLong("in"), o.getLong("out"), o.optDouble("speed", 1.0).toFloat(), o.optDouble("volume", 1.0).toFloat(),
                o.optInt("rotation"), Framing.valueOf(o.optString("framing", "FIT")), o.optDouble("panX", 0.0).toFloat(), o.optDouble("panY", 0.0).toFloat(),
                o.optDouble("scale", 1.0).toFloat(), o.optDouble("zoom", 0.0).toFloat(), keys, o.optDouble("reframeFor", 0.0).toFloat(), look,
                TransitionKind.valueOf(o.optString("transition", "CUT")), o.optLong("transitionMs", 600)))
        }
        val texts = ArrayList<EditText>()
        val ta = j.optJSONArray("texts") ?: JSONArray()
        for (i in 0 until ta.length()) {
            val o = ta.getJSONObject(i)
            texts.add(EditText(o.getLong("id"), o.getString("text"), o.getLong("start"), o.getLong("end"), TextPlace.valueOf(o.optString("place", "BOTTOM")), o.optDouble("size", 1.0).toFloat(), o.optBoolean("box")))
        }
        val p = EditProject(clips, OutShape.valueOf(j.optString("shape", "ORIGINAL")), texts, j.optLong("fadeOut"), j.optDouble("musicVolume", 1.0).toFloat(), j.optBoolean("duck", true))
        val mood = j.optString("mood").takeIf { it.isNotEmpty() }?.let { runCatching { Mood.valueOf(it) }.getOrNull() }
        val song = j.optJSONObject("song")?.let { SongChoice(Uri.parse(it.getString("uri")), it.getString("name"), if (it.has("bpm")) it.getDouble("bpm") else null) }
        return Saved(p, mood, song, j.optLong("seed", 1))
    }

    /** A new clip for [s]: a whole video, or a photo for [EditOps.PHOTO_MS]. */
    fun clipFor(s: EditSource, id: Long): EditClip =
        EditClip(id, s, 0, if (s.kind == ShotKind.PHOTO) EditOps.PHOTO_MS else max(EditOps.MIN_PART_MS, s.durationMs))
}
