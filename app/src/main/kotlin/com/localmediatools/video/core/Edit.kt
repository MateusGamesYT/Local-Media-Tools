package com.localmediatools.video.core

import com.localmediatools.highlight.core.CaptionLayer
import com.localmediatools.highlight.core.CompClip
import com.localmediatools.highlight.core.Composition
import com.localmediatools.highlight.core.Frame
import com.localmediatools.highlight.core.FrameDesc
import com.localmediatools.highlight.core.Layer
import com.localmediatools.highlight.core.ShotKind
import com.localmediatools.highlight.core.Timeline
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A file in a video project: its displayed size (rotation applied), length and whether it has sound. */
data class EditSource(val uri: String, val name: String, val kind: ShotKind, val width: Int, val height: Int,
                      val durationMs: Long = 0, val hasAudio: Boolean = false)

/** How a clip fills the video's shape. */
enum class Framing {
    /** The whole picture, over a blurred copy of itself where the shapes differ. */
    FIT,
    /** Fills the video, cropped; the part shown can be moved ([EditClip.panX]) and zoomed ([EditClip.scale]). */
    FILL,
    /** Fills the video and follows its subject ([Reframe]). */
    AUTO,
}

/** How a clip starts after the one before it. */
enum class TransitionKind { CUT, CROSSFADE, FADE_BLACK }

/** Ready-made looks; [Look]'s own adjustments are added on top. */
enum class LookPreset(val brightness: Float, val contrast: Float, val saturation: Float, val warmth: Float, val mono: Boolean = false) {
    NONE(0f, 0f, 0f, 0f), VIVID(0.02f, 0.15f, 0.35f, 0.05f), WARM(0.03f, 0.05f, 0.1f, 0.45f), COOL(0f, 0.05f, 0f, -0.45f),
    MONO(0f, 0.15f, -1f, 0f, mono = true), FADED(0.08f, -0.25f, -0.25f, 0.1f), DRAMA(-0.04f, 0.35f, -0.1f, -0.05f),
}

/**
 * A clip's colour: a [preset] plus [brightness], [contrast], [saturation] and [warmth] (each −1…1, 0
 * = unchanged), as one colour matrix for the renderer ([matrix]).
 */
data class Look(val preset: LookPreset = LookPreset.NONE, val brightness: Float = 0f, val contrast: Float = 0f,
                val saturation: Float = 0f, val warmth: Float = 0f) {
    val isNone get() = preset == LookPreset.NONE && brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f

    /** Column-major 4×4 on (r, g, b, 1); null when it changes nothing. */
    fun matrix(): FloatArray? {
        if (isNone) return null
        val b = (preset.brightness + brightness).coerceIn(-1f, 1f) * 0.3f
        val c = 1f + (preset.contrast + contrast).coerceIn(-1f, 1f) * if (preset.contrast + contrast >= 0) 0.8f else 0.6f
        val s = (1f + (preset.saturation + saturation).coerceIn(-1f, 1f)).let { if (preset.mono) 0f else it }
        val w = (preset.warmth + warmth).coerceIn(-1f, 1f) * 0.12f
        // Saturation around the luma (Rec. 709), then contrast around mid-grey, then brightness, then warmth.
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val sat = arrayOf(
            floatArrayOf(lr * (1 - s) + s, lg * (1 - s), lb * (1 - s)),
            floatArrayOf(lr * (1 - s), lg * (1 - s) + s, lb * (1 - s)),
            floatArrayOf(lr * (1 - s), lg * (1 - s), lb * (1 - s) + s),
        )
        val gain = floatArrayOf(c * (1 + w), c, c * (1 - w))
        val offset = 0.5f * (1 - c) + b
        val m = FloatArray(16)
        for (row in 0 until 3) {
            for (col in 0 until 3) m[col * 4 + row] = gain[row] * sat[row][col]
            m[12 + row] = offset * (if (row == 0) 1 + w else if (row == 2) 1 - w else 1f)
        }
        m[15] = 1f
        return m
    }
}

/**
 * One clip of a project: the part [inMs]…[outMs] of a video (for a photo: shown for [outMs] − [inMs]),
 * played at [speed], its sound at [volume] (0: silent), turned [rotation] degrees, framed by [framing],
 * coloured by [look], and how it starts after the clip before it ([transition], [transitionMs]).
 */
data class EditClip(
    val id: Long,
    val source: EditSource,
    val inMs: Long, val outMs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val rotation: Int = 0,
    val framing: Framing = Framing.FIT,
    /** FILL: how far the part shown is moved within the free room (−1…1 each way), and zoomed (1…3). */
    val panX: Float = 0f, val panY: Float = 0f, val scale: Float = 1f,
    /** AUTO: 0 = the widest crop that follows the subject, 1 = zoomed in on it ([Reframe.Options.zoom]). */
    val zoom: Float = 0f,
    /** AUTO: the camera path found for this clip's shape and rotation ([Reframe.path]; times in the source). */
    val reframe: List<Key>? = null,
    val reframeFor: Float = 0f,
    val look: Look = Look(),
    val transition: TransitionKind = TransitionKind.CUT,
    val transitionMs: Long = 600,
) {
    val isPhoto get() = source.kind == ShotKind.PHOTO
    /** How long the clip lasts in the video. */
    val lengthMs: Long get() = if (isPhoto) outMs - inMs else ((outMs - inMs) / speed.toDouble()).roundToLong()
    /** The picture's shape as shown (after [rotation]). */
    val aspect: Float get() = if (rotation % 180 != 0) source.height.toFloat() / max(1, source.width) else source.width.toFloat() / max(1, source.height)
    val hasSound get() = !isPhoto && source.hasAudio && volume > 0f
}

/** Where a text sits. */
enum class TextPlace { TOP, MIDDLE, BOTTOM }

/** A text over the video from [startMs] to [endMs] (times in the video). */
data class EditText(val id: Long, val text: String, val startMs: Long, val endMs: Long, val place: TextPlace = TextPlace.BOTTOM,
                    val size: Float = 1f, val box: Boolean = false)

/** The video's shape. [ORIGINAL]: the first clip's. */
enum class OutShape(val width: Int, val height: Int) {
    ORIGINAL(0, 0), LANDSCAPE(1920, 1080), PORTRAIT(1080, 1920), SQUARE(1080, 1080), TALL(1080, 1350);
}

/**
 * A video project: clips in order, the video's shape, texts, and how it ends ([fadeOutMs]). The
 * music and sound settings live with the Android side ([musicVolume] is the music's level, 0–1).
 */
data class EditProject(
    val clips: List<EditClip> = emptyList(),
    val shape: OutShape = OutShape.ORIGINAL,
    val texts: List<EditText> = emptyList(),
    val fadeOutMs: Long = 0,
    val musicVolume: Float = 1f,
    /** The music steps back while clips play their own sound. */
    val duckMusic: Boolean = true,
) {
    /** The output's width over its height. */
    val outAspect: Float get() = if (shape == OutShape.ORIGINAL) clips.firstOrNull()?.aspect ?: (16f / 9f) else shape.width.toFloat() / shape.height
}

/** Changes to a project, each giving a new one (so every step can be undone). */
object EditOps {
    /** Splits clip [index] at [atMs] of its own time in the video; null when too close to an end (half a second). */
    fun split(p: EditProject, index: Int, atMs: Long, newId: Long): EditProject? {
        val c = p.clips.getOrNull(index) ?: return null
        if (atMs < MIN_PART_MS || atMs > c.lengthMs - MIN_PART_MS) return null
        val cut = if (c.isPhoto) c.inMs + atMs else c.inMs + (atMs * c.speed.toDouble()).roundToLong()
        val a = c.copy(outMs = cut)
        val b = c.copy(id = newId, inMs = cut, transition = TransitionKind.CUT)
        return p.copy(clips = p.clips.toMutableList().apply { set(index, a); add(index + 1, b) })
    }

    fun move(p: EditProject, from: Int, to: Int): EditProject {
        if (from == to || from !in p.clips.indices) return p
        val l = p.clips.toMutableList(); val c = l.removeAt(from); l.add(to.coerceIn(0, l.size), c)
        return p.copy(clips = l)
    }

    fun remove(p: EditProject, index: Int) = p.copy(clips = p.clips.filterIndexed { i, _ -> i != index })

    fun duplicate(p: EditProject, index: Int, newId: Long): EditProject {
        val c = p.clips.getOrNull(index) ?: return p
        return p.copy(clips = p.clips.toMutableList().apply { add(index + 1, c.copy(id = newId, transition = TransitionKind.CUT)) })
    }

    fun replace(p: EditProject, index: Int, c: EditClip) = p.copy(clips = p.clips.toMutableList().apply { set(index, c) })

    /** Trims clip [index] to [inMs]…[outMs] of its source (kept at least [MIN_PART_MS] long, inside the file). */
    fun trim(p: EditProject, index: Int, inMs: Long, outMs: Long): EditProject {
        val c = p.clips.getOrNull(index) ?: return p
        if (c.isPhoto) return replace(p, index, c.copy(inMs = 0, outMs = max(MIN_PART_MS, outMs - inMs)))
        val end = if (c.source.durationMs > 0) c.source.durationMs else Long.MAX_VALUE
        val a = inMs.coerceIn(0, max(0, end - MIN_PART_MS))
        val b = outMs.coerceIn(a + MIN_PART_MS, end)
        return replace(p, index, c.copy(inMs = a, outMs = b))
    }

    const val MIN_PART_MS = 500L
    /** A photo's length when added. */
    const val PHOTO_MS = 3000L
    /** Speeds offered. */
    val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
}

/**
 * When each clip of a project is on screen and how it is drawn ([Composition], for the renderer):
 * a cross-fade overlaps a clip with the one before by its [EditClip.transitionMs]; a fade through black
 * ends the one before and starts this one on black, half each; texts fade in and out.
 */
class EditTimeline(val project: EditProject) : Composition {
    class Placed(val clip: EditClip, val startMs: Long, val endMs: Long, val fadeInMs: Long, val fadeOutMs: Long, val overlapIn: Boolean)

    val placed: List<Placed>
    override val durationMs: Long
    override val outAspect: Float = project.outAspect
    override val captionCount: Int get() = project.texts.size

    init {
        val out = ArrayList<Placed>()
        var t = 0L
        val cs = project.clips
        for ((i, c) in cs.withIndex()) {
            val prev = cs.getOrNull(i - 1)
            val d = if (prev == null) min(c.transitionMs, c.lengthMs / 2) else min(c.transitionMs, min(prev.lengthMs, c.lengthMs) / 2)
            var start = t
            var fadeIn = 0L; var overlap = false
            when (if (prev == null && c.transition == TransitionKind.CROSSFADE) TransitionKind.FADE_BLACK else c.transition) {
                TransitionKind.CUT -> {}
                TransitionKind.CROSSFADE -> { start = t - d; fadeIn = d; overlap = true }
                TransitionKind.FADE_BLACK -> fadeIn = if (prev == null) d else d / 2
            }
            out.add(Placed(c, start, start + c.lengthMs, fadeIn, 0, overlap))
            t = start + c.lengthMs
        }
        // Fades to black at the end of the clip before a fade through black.
        for (i in 1 until out.size) {
            val c = out[i].clip
            if (c.transition == TransitionKind.FADE_BLACK) {
                val p = out[i - 1]
                out[i - 1] = Placed(p.clip, p.startMs, p.endMs, p.fadeInMs, out[i].fadeInMs, p.overlapIn)
            }
        }
        placed = out
        durationMs = max(0L, t)
    }

    override val clips: List<CompClip> = placed.mapIndexed { i, p ->
        val c = p.clip
        val (shown, dstW) = when (c.framing) {
            Framing.FIT -> 1f to (if (sameShape(c)) 1f else Timeline.fitRect(c.aspect, outAspect).w)
            Framing.FILL -> cover(c.aspect).w / c.scale to 1f
            Framing.AUTO -> (c.reframe?.minOfOrNull { it.crop.w } ?: cover(c.aspect).w) to 1f
        }
        CompClip(c.source.kind, i, c.source.width, c.source.height, p.startMs, p.endMs, c.inMs, c.outMs,
            fit = c.framing == Framing.FIT && !sameShape(c), minShownW = shown, dstW = dstW, rotation = c.rotation)
    }

    private fun sameShape(c: EditClip) = abs(c.aspect / outAspect - 1f) < 0.02f

    /** The centred part of a picture of shape [aspect] with the output's shape. */
    fun cover(aspect: Float): Frame = if (aspect > outAspect) { val w = outAspect / aspect; Frame((1 - w) / 2, 0f, w, 1f) } else { val h = aspect / outAspect; Frame(0f, (1 - h) / 2, 1f, h) }

    /** Where clip [c] is in its source at [t] ms of the video. */
    fun sourceMs(p: Placed, t: Double): Long {
        val c = p.clip
        if (c.isPhoto) return 0L
        return (c.inMs + (t - p.startMs) * c.speed).toLong().coerceIn(c.inMs, max(c.inMs, c.outMs - 1))
    }

    /** The part of clip [c]'s picture shown at source time [ms] (FILL with its pan and zoom, AUTO from its path). */
    fun src(c: EditClip, ms: Long): Frame = when (c.framing) {
        Framing.FIT -> Frame(0f, 0f, 1f, 1f)
        Framing.FILL -> {
            val base = cover(c.aspect)
            val w = base.w / c.scale; val h = base.h / c.scale
            val cx = 0.5f + c.panX.coerceIn(-1f, 1f) * (1 - w) / 2; val cy = 0.5f + c.panY.coerceIn(-1f, 1f) * (1 - h) / 2
            Frame(cx - w / 2, cy - h / 2, w, h)
        }
        Framing.AUTO -> {
            val keys = c.reframe
            if (keys.isNullOrEmpty() || abs(c.reframeFor - outAspect) > 1e-3f) cover(c.aspect)
            else Reframe.at(keys, ms).let { Frame(it.x, it.y, it.w, it.h) }
        }
    }

    override fun at(t: Double): FrameDesc {
        val layers = ArrayList<Layer>()
        for ((i, p) in placed.withIndex()) {
            if (t < p.startMs || t >= p.endMs) continue
            val c = p.clip
            var alpha = 1f
            if (p.fadeInMs > 0) alpha = min(alpha, ((t - p.startMs) / p.fadeInMs).toFloat().coerceIn(0f, 1f))
            if (p.fadeOutMs > 0) alpha = min(alpha, ((p.endMs - t) / p.fadeOutMs).toFloat().coerceIn(0f, 1f))
            val ms = sourceMs(p, t)
            val src = src(c, ms)
            val fit = c.framing == Framing.FIT && !sameShape(c)
            val dst = if (fit) Timeline.fitRect(c.aspect, outAspect) else Frame(0f, 0f, 1f, 1f)
            layers.add(Layer(i, src, dst, alpha, fit, cover(c.aspect), ms, c.look.matrix()))
        }
        val caps = ArrayList<CaptionLayer>()
        for ((i, x) in project.texts.withIndex()) {
            if (t < x.startMs || t >= x.endMs) continue
            val a = min(min(1.0, (t - x.startMs) / TEXT_FADE_MS), (x.endMs - t) / TEXT_FADE_MS).toFloat().coerceIn(0f, 1f)
            caps.add(CaptionLayer(i, a))
        }
        val fade = if (project.fadeOutMs > 0) ((t - (durationMs - project.fadeOutMs)) / project.fadeOutMs).toFloat().coerceIn(0f, 1f) else 0f
        return FrameDesc(layers, caps, fade)
    }

    /** The clip on screen at [t] (the newer one during a cross-fade) and its time in its source. */
    fun clipAt(t: Long): Pair<Int, Long>? {
        val i = placed.indexOfLast { t >= it.startMs && t < it.endMs }
        if (i < 0) return if (placed.isNotEmpty() && t >= durationMs) placed.size - 1 to (placed.last().clip.outMs - 1) else null
        return i to sourceMs(placed[i], t.toDouble())
    }

    companion object {
        const val TEXT_FADE_MS = 250.0
    }
}
