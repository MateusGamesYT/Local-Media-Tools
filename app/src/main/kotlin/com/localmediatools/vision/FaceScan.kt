package com.localmediatools.vision

import android.content.Context
import android.graphics.Bitmap
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.image.ImageSource
import com.localmediatools.video.FrameGrabber
import com.localmediatools.video.VideoProbe
import com.localmediatools.vision.core.FaceIdentity
import com.localmediatools.vision.core.FaceSample
import com.localmediatools.vision.core.FaceTrack
import com.localmediatools.vision.core.FaceTracker
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Faces found in a set of photos and videos, grouped into people. Track sources are item indexes. */
class FaceScanResult(
    val items: List<MediaItem>,
    val people: List<FaceIdentity>,
    private val thumbs: Map<FaceTrack, Bitmap>,
    /** Files that couldn't be scanned, with the reason. */
    val problems: List<Pair<String, String>>,
) {
    /** A face picture for a person (their clearest appearance). */
    fun thumb(p: FaceIdentity): Bitmap? = p.tracks.maxByOrNull { t -> t.samples.maxOf { it.area * it.score } }?.let { thumbs[it] }

    /** Which files a person appears in. */
    fun itemsOf(p: FaceIdentity): List<MediaItem> = p.tracks.map { it.source }.distinct().map { items[it] }

    /** Tracks to hide per item key for the chosen people. */
    fun plan(chosen: Collection<FaceIdentity>): Map<String, List<FaceTrack>> =
        chosen.flatMap { it.tracks }.groupBy { items[it.source].key }

    fun release() = thumbs.values.forEach { if (!it.isRecycled) it.recycle() }
}

/** Looks for faces in photos (one detection pass) and videos (sampled several times per second). */
object FaceScanner {
    class Cancelled : RuntimeException()

    /** Stands in for the face models where native code can't run (JVM tests). */
    @Volatile var override: ((List<MediaItem>) -> FaceScanResult)? = null

    fun scan(ctx: Context, items: List<MediaItem>, cancelled: () -> Boolean, progress: (Double, String) -> Unit): FaceScanResult {
        override?.let { return it(items) }
        val tracks = ArrayList<FaceTrack>()
        val thumbs = IdentityHashMap<FaceTrack, Bitmap>()
        val problems = ArrayList<Pair<String, String>>()
        VisionModels.faces(ctx) // load the models before the first file
        for ((k, item) in items.withIndex()) {
            if (cancelled()) throw Cancelled()
            val base = k.toDouble() / items.size
            val span = 1.0 / items.size
            try {
                if (item.kind == MediaKind.VIDEO) {
                    tracks.addAll(scanVideo(ctx, k, item, thumbs, cancelled) { f -> progress(base + span * f, "Watching ${item.name}") })
                } else {
                    progress(base, "Looking at ${item.name}")
                    ImageSource.open(ctx, item).use { src ->
                        val bmp = src.preview(1600)
                        try {
                            for ((i, s) in VisionOps.photoFaces(ctx, bmp).withIndex()) {
                                val t = FaceTrack(i, k, still = true)
                                t.samples.add(s)
                                tracks.add(t)
                                thumbs[t] = VisionOps.faceThumb(bmp, s)
                            }
                        } finally { bmp.recycle() }
                    }
                }
            } catch (e: Cancelled) {
                throw e
            } catch (e: Throwable) {
                problems.add(item.name to (e.message ?: "couldn't be read"))
            }
        }
        if (cancelled()) throw Cancelled()
        progress(1.0, "Grouping faces into people")
        val people = FaceTracker.cluster(tracks)
        // Thumbnails of dropped (noise) tracks are no longer needed.
        val kept = people.flatMap { it.tracks }.toHashSet()
        thumbs.keys.filter { it !in kept }.forEach { thumbs.remove(it)?.recycle() }
        return FaceScanResult(items, people, thumbs, problems)
    }

    private fun scanVideo(ctx: Context, source: Int, item: MediaItem, thumbs: MutableMap<FaceTrack, Bitmap>, cancelled: () -> Boolean, progress: (Double) -> Unit): List<FaceTrack> {
        val info = VideoProbe.probe(ctx, item.uri)
        val dur = info.durationUs.coerceAtLeast(1)
        // Several looks per second; long videos are sampled less often (at most ~1200 frames).
        val interval = when {
            dur <= 60_000_000 -> 166_000L
            dur <= 300_000_000 -> 250_000L
            else -> max(250_000L, dur / 1200)
        }
        val tracker = FaceTracker(maxGapUs = max(800_000L, interval * 3), source = source)
        val dw = info.displayWidth.coerceAtLeast(2); val dh = info.displayHeight.coerceAtLeast(2)
        val s = minOf(1.0, 960.0 / max(dw, dh))
        val w = ((dw * s / 2).roundToInt() * 2).coerceAtLeast(2); val h = ((dh * s / 2).roundToInt() * 2).coerceAtLeast(2)
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bestScore = IdentityHashMap<FaceTrack, Float>()
        val engine = VisionModels.faces(ctx)
        var next = 0L
        try {
            FrameGrabber(ctx, item.uri, info, w, h).run(0, dur, null, { pts -> if (pts >= next) { next = pts + interval; true } else false }, { argb, pts ->
                if (cancelled()) throw Cancelled()
                frame.setPixels(argb, 0, w, 0, 0, w, h)
                val m = VisionOps.bgr(frame)
                val samples = try {
                    engine.detect(m).map { f -> FaceSample(pts, f.x / w, f.y / h, f.w / w, f.h / h, f.score, engine.embed(m, f)) }
                } finally { m.release() }
                tracker.add(pts, samples)
                for (smp in samples) {
                    val t = tracker.tracks.lastOrNull { it.last === smp } ?: continue
                    val q = smp.area * smp.score
                    if (q > (bestScore[t] ?: -1f)) {
                        bestScore[t] = q
                        thumbs.put(t, VisionOps.faceThumb(frame, smp))?.recycle()
                    }
                }
                progress((pts.toDouble() / dur).coerceIn(0.0, 1.0))
                true
            }, { if (cancelled()) throw Cancelled() })
        } finally {
            frame.recycle()
        }
        return tracker.tracks
    }
}
