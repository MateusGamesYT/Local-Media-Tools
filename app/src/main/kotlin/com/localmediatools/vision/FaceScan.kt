package com.localmediatools.vision

import android.content.Context
import android.graphics.Bitmap
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.i18n.tr
import com.localmediatools.image.ImageSource
import com.localmediatools.video.FrameGrabber
import com.localmediatools.video.VideoProbe
import com.localmediatools.vision.core.FaceEngine
import com.localmediatools.vision.core.FaceIdentity
import com.localmediatools.vision.core.FaceSample
import com.localmediatools.vision.core.FaceTrack
import com.localmediatools.vision.core.FaceTracker
import com.localmediatools.vision.core.Found
import com.localmediatools.vision.core.LineMemory
import com.localmediatools.vision.core.PrivacyOptions
import com.localmediatools.vision.core.PrivateSample
import com.localmediatools.vision.core.PrivateTrack
import com.localmediatools.vision.core.PrivateTracker
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Faces found in a set of photos and videos, grouped into people, and the private things found
 * besides ([privates]: plates, text, codes). Track sources are item indexes.
 */
class FaceScanResult(
    val items: List<MediaItem>,
    val people: List<FaceIdentity>,
    private val thumbs: Map<FaceTrack, Bitmap>,
    /** Files that couldn't be scanned, with the reason. */
    val problems: List<Pair<String, String>>,
    val privates: List<PrivateTrack> = emptyList(),
    private val privateThumbs: Map<PrivateTrack, Bitmap> = emptyMap(),
) {
    /** A face picture for a person (their clearest appearance). */
    fun thumb(p: FaceIdentity): Bitmap? = p.tracks.maxByOrNull { t -> t.samples.maxOf { it.area * it.score } }?.let { thumbs[it] }

    /** A picture of a private thing. */
    fun thumb(t: PrivateTrack): Bitmap? = privateThumbs[t]

    /** Which files a person appears in. */
    fun itemsOf(p: FaceIdentity): List<MediaItem> = p.tracks.map { it.source }.distinct().map { items[it] }

    /** Tracks to hide per item key for the chosen people. */
    fun plan(chosen: Collection<FaceIdentity>): Map<String, List<FaceTrack>> =
        chosen.flatMap { it.tracks }.groupBy { items[it.source].key }

    /** Private things to hide per item key. */
    fun planPrivate(chosen: Collection<PrivateTrack>): Map<String, List<PrivateTrack>> = chosen.groupBy { items[it.source].key }

    fun release() {
        thumbs.values.forEach { if (!it.isRecycled) it.recycle() }
        privateThumbs.values.forEach { if (!it.isRecycled) it.recycle() }
    }
}

/**
 * Looks for faces in photos (one detection pass) and videos (sampled several times per second), and,
 * with [PrivacyOptions], for licence plates, private text and codes as well.
 */
object FaceScanner {
    class Cancelled : RuntimeException()

    /** Stands in for the models where native code can't run (JVM tests). */
    @Volatile var override: ((List<MediaItem>, PrivacyOptions) -> FaceScanResult)? = null

    fun scan(ctx: Context, items: List<MediaItem>, cancelled: () -> Boolean, privacy: PrivacyOptions = PrivacyOptions(), progress: (Double, String) -> Unit): FaceScanResult {
        override?.let { return it(items, privacy) }
        val tracks = ArrayList<FaceTrack>()
        val thumbs = IdentityHashMap<FaceTrack, Bitmap>()
        val privates = ArrayList<PrivateTrack>()
        val privateThumbs = IdentityHashMap<PrivateTrack, Bitmap>()
        val problems = ArrayList<Pair<String, String>>()
        VisionModels.faces(ctx) // load the models before the first file
        if (privacy.any) PrivacyModels.finder(ctx)
        for ((k, item) in items.withIndex()) {
            if (cancelled()) throw Cancelled()
            val base = k.toDouble() / items.size
            val span = 1.0 / items.size
            try {
                if (item.kind == MediaKind.VIDEO) {
                    tracks.addAll(scanVideo(ctx, k, item, thumbs, privacy, privates, privateThumbs, cancelled) { f -> progress(base + span * f, tr("Watching {0}", item.name)) })
                } else {
                    progress(base, tr("Looking at {0}", item.name))
                    ImageSource.open(ctx, item).use { src ->
                        val bmp = src.preview(1600)
                        try {
                            for ((i, s) in VisionOps.photoFaces(ctx, bmp, toHide = true).withIndex()) {
                                val t = FaceTrack(i, k, still = true)
                                t.samples.add(s)
                                tracks.add(t)
                                thumbs[t] = VisionOps.faceThumb(bmp, s)
                            }
                            if (privacy.any) {
                                if (cancelled()) throw Cancelled()
                                progress(base + span * 0.4, tr("Looking for private details in {0}", item.name))
                                for (f in findPrivate(ctx, bmp, privacy, null, cancelled)) {
                                    val t = PrivateTrack(privates.size, k, f.kind, f.details, f.text, still = true)
                                    val q = normalized(f, bmp.width, bmp.height)
                                    t.samples.add(PrivateSample(0, q))
                                    privates.add(t)
                                    privateThumbs[t] = PrivacyModels.thumb(bmp, q)
                                }
                            }
                        } finally { bmp.recycle() }
                    }
                }
            } catch (e: Cancelled) {
                throw e
            } catch (e: Throwable) {
                problems.add(item.name to (e.message ?: tr("couldn't be read")))
            }
        }
        if (cancelled()) throw Cancelled()
        progress(1.0, tr("Grouping faces into people"))
        val people = FaceTracker.cluster(tracks)
        // Thumbnails of dropped (noise) tracks are no longer needed.
        val kept = people.flatMap { it.tracks }.toHashSet()
        thumbs.keys.filter { it !in kept }.forEach { thumbs.remove(it)?.recycle() }
        return FaceScanResult(items, people, thumbs, problems, privates, privateThumbs)
    }

    private fun normalized(f: Found, w: Int, h: Int) = FloatArray(8) { if (it % 2 == 0) f.quad.p[it] / w else f.quad.p[it] / h }

    /** Plates, private text and codes in [bmp] (in its pixels). */
    private fun findPrivate(ctx: Context, bmp: Bitmap, privacy: PrivacyOptions, memory: LineMemory?, cancelled: () -> Boolean): List<Found> {
        val vehicles = if (privacy.plates) PrivacyModels.vehicles(ctx, bmp) else emptyList()
        val m = VisionOps.bgr(bmp)
        try {
            return PrivacyModels.finder(ctx).find(m, privacy, vehicles, memory) { if (cancelled()) throw Cancelled() }
        } finally { m.release() }
    }

    /** Several looks per second at a video; long videos are sampled less often (at most ~1200 frames). */
    private fun intervalUs(durationUs: Long) = when {
        durationUs <= 60_000_000 -> 166_000L
        durationUs <= 300_000_000 -> 250_000L
        else -> max(250_000L, durationUs / 1200)
    }

    /** Plates, text and codes move less and cost more to find: every other look. */
    private fun privacyIntervalUs(durationUs: Long) = intervalUs(durationUs) * 2

    /**
     * How long a private thing in a video of [durationUs] stays hidden before it is first seen and
     * after it is last seen (one and a half looks: it may have been missed in the next look).
     */
    fun privacyHoldUs(durationUs: Long): Long = privacyIntervalUs(durationUs.coerceAtLeast(1)) * 3 / 2

    private fun scanVideo(
        ctx: Context, source: Int, item: MediaItem, thumbs: MutableMap<FaceTrack, Bitmap>,
        privacy: PrivacyOptions, privates: MutableList<PrivateTrack>, privateThumbs: MutableMap<PrivateTrack, Bitmap>,
        cancelled: () -> Boolean, progress: (Double) -> Unit,
    ): List<FaceTrack> {
        val info = VideoProbe.probe(ctx, item.uri)
        val dur = info.durationUs.coerceAtLeast(1)
        val interval = intervalUs(dur)
        val tracker = FaceTracker(maxGapUs = max(800_000L, interval * 3), source = source)
        val privateInterval = privacyIntervalUs(dur)
        val privateTracker = PrivateTracker(maxGapUs = privateInterval * 3, source = source)
        val memory = LineMemory(privateInterval * 3)
        val dw = info.displayWidth.coerceAtLeast(2); val dh = info.displayHeight.coerceAtLeast(2)
        val s = minOf(1.0, 960.0 / max(dw, dh))
        val w = ((dw * s / 2).roundToInt() * 2).coerceAtLeast(2); val h = ((dh * s / 2).roundToInt() * 2).coerceAtLeast(2)
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bestScore = IdentityHashMap<FaceTrack, Float>()
        val bestPrivate = IdentityHashMap<PrivateTrack, Float>()
        val engine = VisionModels.faces(ctx)
        var next = 0L
        var nextPrivate = 0L
        try {
            FrameGrabber(ctx, item.uri, info, w, h).run(0, dur, null, { pts -> if (pts >= next) { next = pts + interval; true } else false }, { argb, pts ->
                if (cancelled()) throw Cancelled()
                frame.setPixels(argb, 0, w, 0, 0, w, h)
                val m = VisionOps.bgr(frame)
                val samples = try {
                    engine.detectToHide(m, FaceEngine.VIDEO_SIDES).map { f -> FaceSample(pts, f.x / w, f.y / h, f.w / w, f.h / h, f.score, engine.embed(m, f)) }
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
                if (privacy.any && pts >= nextPrivate) {
                    nextPrivate = pts + privateInterval
                    memory.now = pts
                    val found = findPrivate(ctx, frame, privacy, memory, cancelled)
                    privateTracker.add(pts, found.map { Triple(it.kind, it.details to it.text, normalized(it, w, h)) })
                    for (t in privateTracker.tracks) {
                        val last = t.samples.last()
                        if (last.tUs != pts) continue
                        val area = (last.x1 - last.x0) * (last.y1 - last.y0)
                        if (area > (bestPrivate[t] ?: -1f)) {
                            bestPrivate[t] = area
                            privateThumbs.put(t, PrivacyModels.thumb(frame, last.quad))?.recycle()
                        }
                    }
                }
                progress((pts.toDouble() / dur).coerceIn(0.0, 1.0))
                true
            }, { if (cancelled()) throw Cancelled() })
        } finally {
            frame.recycle()
        }
        // Numbered after the tracks of the files before, so every track of the scan has its own id.
        for (t in privateTracker.tracks) {
            val copy = PrivateTrack(privates.size, source, t.kind, t.details, t.text)
            copy.samples.addAll(t.samples)
            privateThumbs.remove(t)?.let { privateThumbs[copy] = it }
            privates.add(copy)
        }
        return tracker.tracks
    }
}
