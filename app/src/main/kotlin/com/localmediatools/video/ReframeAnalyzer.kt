package com.localmediatools.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.localmediatools.gallery.ObjectTagger
import com.localmediatools.vision.VisionModels
import com.localmediatools.vision.VisionOps
import com.localmediatools.video.core.Box
import com.localmediatools.video.core.Cuts
import com.localmediatools.video.core.Found
import com.localmediatools.video.core.Key
import com.localmediatools.video.core.Reframe
import com.localmediatools.video.core.ReframeSample
import com.localmediatools.video.core.SubjectKind
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Auto reframe on the phone: reads a clip's frames once (at [SIDE] px, through the GPU like every
 * frame reader of the app), keeps a tiny grey copy of each for finding cuts ([Cuts]), and [RATE]
 * times a second looks for faces (YuNet, as face blur does) and people, animals and things
 * (EfficientDet-Lite2, as the Gallery does); [Reframe] then plans the camera. The same steps run in
 * buildtools/video (reframe_detect.py and the Kotlin harness), where they were measured.
 */
object ReframeAnalyzer {
    /** Frames are looked at this big (long side). */
    const val SIDE = 640
    /** Samples a second. */
    const val RATE = 5.0
    /** People, animals and things below this score are left out. */
    const val MIN_OBJECT = 0.35f
    /** COCO classes that are animals (EfficientDet's numbering). */
    private val ANIMALS = 15..24

    /** Stands in for the analysis where video decoding can't run (Robolectric tests). */
    @Volatile var override: ((Uri, Long, Long, Float, Float) -> List<Key>?)? = null

    /**
     * The camera path for the part [fromMs]…[toMs] of a video (times in the source), shown as
     * [outAspect] (width over height) after turning it [rotation] degrees, zoomed in by [zoom] (0–1).
     */
    fun analyze(ctx: Context, uri: Uri, fromMs: Long, toMs: Long, outAspect: Float, zoom: Float, rotation: Int = 0,
                cancelled: () -> Boolean = { false }, progress: (Double) -> Unit = {}): List<Key> {
        override?.let { f -> return f(uri, fromMs, toMs, outAspect, zoom) ?: emptyList() }
        val info = VideoProbe.probe(ctx, uri)
        val v = info.video ?: return emptyList()
        // Displayed size (the stored picture turned by its rotation), then the user's turn on top.
        val turned = (info.rotation + rotation) % 180 != 0
        val dw = if (info.rotation % 180 != 0) v.height else v.width
        val dh = if (info.rotation % 180 != 0) v.width else v.height
        val s = SIDE.toFloat() / max(dw, dh)
        val w = max(2, (dw * s).roundToInt() / 2 * 2); val h = max(2, (dh * s).roundToInt() / 2 * 2)
        val faces = VisionModels.faces(ctx)
        val tagger = ObjectTagger.create(ctx)
        val thumbs = ArrayList<ByteArray>()
        val times = ArrayList<Long>()
        val found = HashMap<Long, List<Found>>()
        var next = fromMs.toDouble()
        val period = 1000.0 / RATE
        val span = (toMs - fromMs).coerceAtLeast(1)
        FrameGrabber(ctx, uri, info, w, h).run(fromMs * 1000, toMs * 1000, null, { true }, { argb, ptsUs ->
            if (cancelled()) return@run false
            val ms = ptsUs / 1000
            if (ms < fromMs) return@run true
            val (pw, ph, px) = if (rotation % 360 == 0) Triple(w, h, argb) else turn(argb, w, h, rotation)
            thumbs.add(thumb(px, pw, ph)); times.add(ms)
            if (ms + 1e-6 >= next) {
                found[ms] = look(px, pw, ph, faces, tagger)
                next = ms + period
                progress(((ms - fromMs).toDouble() / span).coerceIn(0.0, 1.0))
            }
            ms < toMs
        }, {})
        if (times.isEmpty()) return emptyList()
        val samples = samples(found, Cuts.find(thumbs, times.toLongArray()).map { times[it] })
        val srcAspect = if (turned) dh.toFloat() / dw else dw.toFloat() / dh
        val opts = Reframe.Options(outAspect, zoom)
        return Reframe.path(samples, Reframe.subjects(samples, srcAspect, outAspect), srcAspect, opts)
    }

    /**
     * The samples for [Reframe]: one per frame looked at; the first one at or after a cut moves to the
     * cut's first frame and starts a new shot (what it found is from just after the cut).
     */
    fun samples(found: Map<Long, List<Found>>, cuts: List<Long>): List<ReframeSample> {
        val out = ArrayList<ReframeSample>()
        var ci = 0
        for (t in found.keys.sorted()) {
            var cut: Long? = null
            while (ci < cuts.size && cuts[ci] <= t) { cut = cuts[ci]; ci++ }
            // A cut before the first sample changes nothing.
            if (cut != null && out.isNotEmpty() && cut > out.last().ms) out.add(ReframeSample(cut, found[t] ?: emptyList(), cut = true))
            else out.add(ReframeSample(t, found[t] ?: emptyList()))
        }
        return out
    }

    /** What is in one frame: faces, people, animals and things (fractions of the frame). */
    private fun look(px: IntArray, w: Int, h: Int, faces: com.localmediatools.vision.core.FaceEngine, tagger: ObjectTagger): List<Found> {
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        try {
            val out = ArrayList<Found>()
            val m = VisionOps.bgr(bmp)
            try {
                for (f in faces.detect(m)) out.add(Found(SubjectKind.FACE, Box(f.x / w, f.y / h, f.w / w, f.h / h), f.score))
            } finally { m.release() }
            for (d in tagger.detect(bmp)) {
                if (d.score < MIN_OBJECT) continue
                val kind = when (d.cls) { 0 -> SubjectKind.PERSON; in ANIMALS -> SubjectKind.ANIMAL; else -> SubjectKind.THING }
                out.add(Found(kind, Box(d.x0, d.y0, d.x1 - d.x0, d.y1 - d.y0), d.score))
            }
            return out
        } finally { bmp.recycle() }
    }

    /** A [Cuts.W] × [Cuts.H] grey copy: the mean of each block (luma as OpenCV computes it). */
    fun thumb(px: IntArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(Cuts.W * Cuts.H)
        for (by in 0 until Cuts.H) for (bx in 0 until Cuts.W) {
            val x0 = bx * w / Cuts.W; val x1 = max(x0 + 1, (bx + 1) * w / Cuts.W)
            val y0 = by * h / Cuts.H; val y1 = max(y0 + 1, (by + 1) * h / Cuts.H)
            var s = 0L; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = px[y * w + x]
                s += (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255) + 500) / 1000
                n++
            }
            out[by * Cuts.W + bx] = ((s + n / 2) / n).toInt().toByte()
        }
        return out
    }

    /** The frame turned [rotation] degrees clockwise. */
    private fun turn(px: IntArray, w: Int, h: Int, rotation: Int): Triple<Int, Int, IntArray> {
        val r = ((rotation % 360) + 360) % 360
        return when (r) {
            90 -> Triple(h, w, IntArray(w * h) { i -> val x = i % h; val y = i / h; px[(h - 1 - x) * w + y] })
            180 -> Triple(w, h, IntArray(w * h) { i -> px[w * h - 1 - i] })
            270 -> Triple(h, w, IntArray(w * h) { i -> val x = i % h; val y = i / h; px[x * w + (w - 1 - y)] })
            else -> Triple(w, h, px)
        }
    }
}
