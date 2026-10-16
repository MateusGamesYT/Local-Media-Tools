package com.localmediatools.gallery

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.localmediatools.gallery.core.VideoSampling
import com.localmediatools.image.ImageSource
import com.localmediatools.vision.VisionModels
import com.localmediatools.vision.VisionOps
import com.localmediatools.vision.core.FaceEngine
import kotlin.math.max

/** Which engine recognises things or faces on this phone. */
enum class EngineMode { AI, BASIC, OFF }

/** What indexing learned about one photo or video. */
class Analysis(val tags: Map<String, Float>, val faces: List<FoundFace>)

/**
 * Looks at one photo or video: objects and scenes (on-device AI, or the basic fallback) and faces.
 * Engines are picked once by a self-test; a device that can't run the AI models gets the classical
 * fallbacks instead of failing.
 */
class GalleryAnalyzer private constructor(
    private val ctx: Context,
    private val faces: FaceEngine?,
    private val basicFaces: BasicFaces?,
    private val objects: ObjectTagger?,
    private val basicObjects: BasicTagger,
) {
    val faceMode get() = if (faces != null) EngineMode.AI else if (basicFaces != null) EngineMode.BASIC else EngineMode.OFF
    val objectMode get() = if (objects != null) EngineMode.AI else EngineMode.BASIC

    /** [keepFrames]: for a video, the moments (ms) where faces were found before (looked at again). */
    fun analyze(m: GMedia, keepFrames: Collection<Long> = emptyList()): Analysis = if (m.video) video(m, true, keepFrames) else photo(m, true)

    /** Only the faces (the tags of an earlier analysis stay); [Analysis.tags] is empty. */
    fun analyzeFaces(m: GMedia, keepFrames: Collection<Long> = emptyList()): Analysis = if (m.video) video(m, false, keepFrames) else photo(m, false)

    private fun photo(m: GMedia, withTags: Boolean): Analysis {
        ImageSource.open(ctx, m.uri, m.name).use { src ->
            val work = src.preview(GalleryFaces.DETECT_SIDE)
            try {
                val found = findFaces(src, work)
                val tags = if (withTags) tagsFor(work, found) else emptyMap()
                return Analysis(tags, found)
            } finally { work.recycle() }
        }
    }

    /**
     * A video: the moments [VideoSampling] picks (different keyframes spread over it, plus where faces
     * were found before), each looked at like a photo; tags are the strongest over the frames, the
     * same person seen in several frames is kept once. Fails (to be tried again later) when no frame
     * at all can be decoded, rather than recording that the video holds nothing.
     */
    private fun video(m: GMedia, withTags: Boolean, keepFrames: Collection<Long>): Analysis {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, m.uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: m.durationMs
            val times = keyframes(m) { sync -> VideoSampling.moments(dur, keepFrames, sync) }
            val tags = HashMap<String, Float>()
            val all = ArrayList<FoundFace>()
            var decoded = 0
            for (t in times) {
                val frame = try {
                    mmr.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, GalleryFaces.DETECT_SIDE, GalleryFaces.DETECT_SIDE)
                } catch (e: Exception) { null } ?: continue
                decoded++
                try {
                    val f = findFaces(null, frame)
                    // Remember the frame, so the face's picture can be cut from that same frame later.
                    all.addAll(f.map { it.atFrame(t) })
                    if (withTags) for ((k, v) in tagsFor(frame, f)) tags[k] = max(tags[k] ?: 0f, v)
                } finally { frame.recycle() }
            }
            if (decoded == 0) throw IllegalStateException("no frame of ${m.name} could be decoded")
            return Analysis(tags, dedupe(all))
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }
    }

    /** Runs [pick] with a function giving the keyframe (ms) each moment decodes from (null: unknown). */
    private fun <T> keyframes(m: GMedia, pick: ((Long) -> Long?) -> T): T {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, m.uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return pick { null }
            ex.selectTrack(track)
            return pick { t ->
                try {
                    ex.seekTo(t * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    ex.sampleTime.takeIf { it >= 0 }?.div(1000)
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            return pick { null }
        } finally { ex.release() }
    }

    /** The same person in several frames of a video is kept once (the best view). */
    private fun dedupe(list: List<FoundFace>): List<FoundFace> {
        val out = ArrayList<FoundFace>()
        for (f in list.sortedByDescending { it.quality }) {
            val e = f.emb
            val same = com.localmediatools.gallery.core.ClusterParams.of(f.kind).sameVideoFace
            if (e != null && out.any { o -> o.emb != null && o.kind == f.kind && FaceEngine.cosine(o.emb, e) >= same }) continue
            if (e == null && out.size >= 8) continue
            out.add(f)
        }
        return out
    }

    // Errors here fail the item (it is tried again on a later run) rather than being saved as
    // "nothing found", which would be final.
    private fun findFaces(src: ImageSource?, work: Bitmap): List<FoundFace> {
        faces?.let { return GalleryFaces.analyze(it, src, work) }
        basicFaces?.let { return it.analyze(work) }
        return emptyList()
    }

    private fun tagsFor(work: Bitmap, found: List<FoundFace>): Map<String, Float> {
        val o = objects ?: return basicObjects.tag(found)
        return o.tag(work)
    }

    companion object {
        /** Builds the analyzer, choosing AI engines that pass their self-test and falling back otherwise. */
        fun create(ctx: Context): GalleryAnalyzer {
            val app = ctx.applicationContext
            val faces = try {
                VisionModels.galleryFaces(app).takeIf { SelfTest.faces(it) }.also { if (it == null) VisionModels.releaseGalleryFaces() }
            } catch (t: Throwable) { android.util.Log.w("LMT", "face AI unavailable", t); VisionModels.releaseGalleryFaces(); null }
            val basic = if (faces == null) try { BasicFaces() } catch (_: Throwable) { null } else null
            val objects = try {
                ObjectTagger.create(app).takeIf { SelfTest.objects(it) }
            } catch (t: Throwable) { android.util.Log.w("LMT", "object AI unavailable", t); null }
            return GalleryAnalyzer(app, faces, basic, objects, BasicTagger)
        }
    }

    /** Quick checks that the AI engines load and give sane answers on this phone. */
    object SelfTest {
        fun faces(engine: FaceEngine): Boolean {
            // A blank picture has no faces; the call must simply work. The recogniser must describe a
            // fixed pattern as it did when it was converted.
            val bmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            return try {
                bmp.eraseColor(0xFF808080.toInt())
                val m = VisionOps.bgr(bmp)
                try { engine.detect(m) } finally { m.release() }
                engine.recognizerWorks().also { if (!it) android.util.Log.w("LMT", "face recognition self-test failed") }
            } catch (t: Throwable) { android.util.Log.w("LMT", "face self-test failed", t); false } finally { bmp.recycle() }
        }

        fun objects(t: ObjectTagger): Boolean = try { t.selfTest() } catch (e: Throwable) { android.util.Log.w("LMT", "object self-test failed", e); false }
    }
}
