package com.localmediatools.highlight.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

enum class ShotKind { PHOTO, VIDEO }

/** A face in a picture, in fractions of the displayed picture. */
class FaceBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val area get() = w * h
    /** The part inside the picture (detectors can place a box partly outside it). */
    fun clipped(): FaceBox {
        val x0 = x.coerceIn(0f, 1f); val y0 = y.coerceIn(0f, 1f)
        return FaceBox(x0, y0, ((x + w).coerceIn(0f, 1f) - x0), ((y + h).coerceIn(0f, 1f) - y0))
    }
}

/** A stretch of a video, measured on frames taken from it. Scores 0..1 unless noted. */
class VideoWindow(
    val startMs: Long, val endMs: Long,
    /** How much changes between frames (people moving, a wave breaking); 1 = a lot. */
    val motion: Float,
    /** Picture sharpness, relative like [Shot.sharpness]. */
    val sharpness: Double,
    val faces: Int,
    /** Largest face, as a fraction of the frame. */
    val faceArea: Float,
    /** How loud the sound is (0 silent, 1 loud). */
    val loudness: Float,
    /** Camera shake (1 = very shaky). */
    val shake: Float = 0f,
)

/** What the planner knows about one photo or video of the event. */
class Shot(
    val id: Int,
    val kind: ShotKind,
    /** Capture time, UTC ms ([CaptureTime]). */
    val takenMs: Long,
    /** Displayed size (rotation applied). */
    val width: Int, val height: Int,
    val durationMs: Long = 0,
    /** Sharpness (variance of the Laplacian on a small copy); compared within the event. */
    val sharpness: Double = 0.0,
    /** 1 = well exposed, 0 = black, blown out or murky. */
    val exposure: Float = 1f,
    val faces: List<FaceBox> = emptyList(),
    /** Gallery category scores (0.5 = at the category's calibrated threshold). */
    val tags: Map<String, Float> = emptyMap(),
    /** Scene embedding (unit length), for scene changes and bursts. */
    val features: FloatArray? = null,
    val favorite: Boolean = false,
    val windows: List<VideoWindow> = emptyList(),
    val hasAudio: Boolean = false,
) {
    val aspect get() = width.toFloat() / max(1, height)
    fun tagged(key: String) = (tags[key] ?: 0f) >= 0.5f
}

/** A part of the event: shots close together in time and scene, with a name ("Lunch", "At the beach"). */
class Moment(val index: Int, val shots: List<Shot>, val label: String, val startMs: Long, val endMs: Long)

enum class Aspect(val label: String, val width: Int, val height: Int) {
    LANDSCAPE("Landscape 16:9", 1920, 1080), PORTRAIT("Portrait 9:16", 1080, 1920), SQUARE("Square", 1080, 1080);
    val ratio get() = width.toFloat() / height
}

class PlanOptions(
    /** Target length; null picks one from how much there is. */
    val seconds: Double? = null,
    /** Seconds per beat of the music (cuts fall on beats); 0.5 without music. */
    val beatSeconds: Double = 0.5,
    /** The event's title and each moment's name on screen. */
    val captions: Boolean = true,
    /** Videos keep their own sound (the music steps back meanwhile). */
    val clipSound: Boolean = true,
    val aspect: Aspect = Aspect.LANDSCAPE,
    /** Time zone for times of day and dates (the phone's). */
    val zone: TimeZone = TimeZone.getDefault(),
    /** Shots the user said must be in (ids), and must not. */
    val include: Set<Int> = emptySet(),
    val exclude: Set<Int> = emptySet(),
    /** The user's own title, and names for moments (by index), instead of the planner's. */
    val title: String? = null,
    val names: Map<Int, String> = emptyMap(),
)

/** A part of the source picture, in fractions of its displayed width and height. */
data class Frame(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(f: FaceBox) = f.x >= x - 1e-4f && f.y >= y - 1e-4f && f.x + f.w <= x + w + 1e-4f && f.y + f.h <= y + h + 1e-4f
}

/** One shot on the output timeline. */
class Clip(
    val shot: Shot,
    val moment: Int,
    val startMs: Long, val endMs: Long,
    /** For videos: the part of the source shown. */
    val sourceStartMs: Long, val sourceEndMs: Long,
    /** Shown part of the picture at the start and at the end (slow zoom and pan for photos). */
    val from: Frame, val to: Frame,
    /** Cross-fade from the previous clip (they overlap this long), or a fade from black for the first. */
    val fadeInMs: Long,
    val withSound: Boolean,
    /**
     * The picture's shape is far from the video's (a portrait photo in a landscape video): it is
     * shown whole, over a blurred copy of itself, and [from]/[to] have the picture's own shape.
     */
    val fit: Boolean = false,
) {
    val durationMs get() = endMs - startMs
}

class Caption(val text: String, val sub: String?, val startMs: Long, val endMs: Long, val title: Boolean)

class Plan(
    val moments: List<Moment>, val clips: List<Clip>, val captions: List<Caption>, val durationMs: Long,
    val title: String, val subtitle: String, val fadeOutMs: Long, val aspect: Aspect = Aspect.LANDSCAPE,
) {
    /** When clips play their own sound (for stepping the music back). */
    fun soundIntervals(): List<LongArray> = clips.filter { it.withSound }.map { longArrayOf(it.startMs + it.fadeInMs / 2, it.endMs) }
}

/**
 * Plans a highlight video from an event's photos and videos: sorts them by capture time, splits
 * them into moments (by gaps in time and changes of scene) and names each moment from what's in it
 * and the time of day, scores every shot (sharpness, exposure, faces, what it shows, favourites;
 * bursts count once), gives each moment a share of the length, picks its best shots and the best
 * stretch of each video, and lays them out chronologically on the music's beat — cuts within a
 * moment, cross-fades between moments, a slow zoom on photos that keeps faces in view.
 */
object Planner {
    // ------------------------------------------------------------------ moments
    fun moments(shots: List<Shot>, zone: TimeZone = TimeZone.getDefault()): List<Moment> {
        val s = shots.sortedBy { it.takenMs }
        if (s.isEmpty()) return emptyList()
        val gaps = (0 until s.size - 1).map { s[it + 1].takenMs - s[it].takenMs }
        val median = gaps.sorted().getOrNull(gaps.size / 2) ?: 0L
        // A pause much longer than usual for this event ends a moment (10 min to 2 h) — unless the
        // pictures on both sides show the same scene (an afternoon at the beach, photos now and
        // then), which only a really long break splits.
        val pause = (median * 8).coerceIn(10 * 60_000L, 2 * 3_600_000L)
        val longPause = max(pause * 4, 3_600_000L)
        val cuts = ArrayList<Int>()  // index of the first shot of each new moment
        for (i in gaps.indices) {
            val g = gaps[i]
            val newDay = day(s[i].takenMs, zone) != day(s[i + 1].takenMs, zone) && g > 3_600_000L
            val d = sceneDistance(s, i)
            val sceneChange = g > 4 * 60_000L && d > 0.5
            val longBreak = g > pause && (d < 0 || d > 0.25 || g > longPause)
            if (longBreak || newDay || sceneChange) cuts.add(i + 1)
        }
        val groups = ArrayList<MutableList<Shot>>()
        var start = 0
        for (c in cuts + s.size) { groups.add(s.subList(start, c).toMutableList()); start = c }
        // A lone shot joins the closer neighbour when it isn't far off.
        var k = 0
        while (k < groups.size && groups.size > 1) {
            val g = groups[k]
            if (g.size == 1) {
                val prevGap = if (k > 0) g[0].takenMs - groups[k - 1].last().takenMs else Long.MAX_VALUE
                val nextGap = if (k < groups.size - 1) groups[k + 1][0].takenMs - g[0].takenMs else Long.MAX_VALUE
                val target = if (prevGap <= nextGap) k - 1 else k + 1
                if (min(prevGap, nextGap) < pause * 3) {
                    if (target < k) groups[target].addAll(g) else groups[target].addAll(0, g)
                    groups.removeAt(k); continue
                }
            }
            k++
        }
        val multiDay = day(s.first().takenMs, zone) != day(s.last().takenMs, zone)
        val firstDay = day(s.first().takenMs, zone)
        val out = ArrayList<Moment>()
        var previous: String? = null
        for ((i, g) in groups.withIndex()) {
            var label = label(g, i == 0, zone)
            if (label == previous) label = "More ${label.replaceFirstChar { it.lowercase() }}"
            previous = label
            val d = dayIndex(g[0].takenMs, firstDay, zone)
            if (multiDay && (i == 0 || dayIndex(groups[i - 1][0].takenMs, firstDay, zone) != d)) label = "Day ${d + 1} · $label"
            out.add(Moment(i, g, label, g.first().takenMs, g.last().takenMs))
        }
        return out
    }

    /** 1 − cosine between the scenes of up to three shots before and after the gap after [i]; −1 when unknown. */
    private fun sceneDistance(s: List<Shot>, i: Int): Double {
        fun mean(range: IntRange): FloatArray? {
            val fs = range.mapNotNull { s.getOrNull(it)?.features }
            if (fs.isEmpty()) return null
            val m = FloatArray(fs[0].size)
            for (f in fs) for (k in m.indices) m[k] += f[k]
            return m
        }
        val a = mean(max(0, i - 2)..i) ?: return -1.0
        val b = mean(i + 1..min(s.size - 1, i + 3)) ?: return -1.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (k in a.indices) { dot += a[k] * b[k]; na += a[k] * a[k]; nb += b[k] * b[k] }
        return 1 - dot / sqrt(na * nb).coerceAtLeast(1e-9)
    }

    private fun cal(ms: Long, zone: TimeZone) = Calendar.getInstance(zone).apply { timeInMillis = ms }
    private fun day(ms: Long, zone: TimeZone) = cal(ms, zone).let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
    private fun dayIndex(ms: Long, firstDay: Int, zone: TimeZone): Int {
        val c = cal(ms, zone)
        val f = Calendar.getInstance(zone).apply { clear(); set(Calendar.YEAR, firstDay / 1000); set(Calendar.DAY_OF_YEAR, firstDay % 1000) }
        return ((c.timeInMillis - f.timeInMillis) / 86_400_000L).toInt().coerceAtLeast(0)
    }
    private fun hour(ms: Long, zone: TimeZone) = cal(ms, zone).let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60.0 }

    private class Theme(val name: String, val keys: Set<String>, val label: (Double, Boolean) -> String)

    private val MEAL = setOf("food", "pizza", "cake", "burger", "sushi", "icecream", "fruit", "salad", "dessert", "coffee", "drink", "wine", "beer", "cocktail")
    private val TRAVEL = setOf("airplane", "train", "bus", "car", "helicopter")
    private val THEMES = listOf(
        Theme("wedding", setOf("wedding")) { _, _ -> "The wedding" },
        Theme("concert", setOf("concert")) { _, _ -> "At the concert" },
        Theme("beach", setOf("beach", "sea", "surfing")) { _, _ -> "At the beach" },
        Theme("pool", setOf("pool")) { _, _ -> "By the pool" },
        Theme("snow", setOf("snow", "skiing")) { _, _ -> "In the snow" },
        Theme("waterfall", setOf("waterfall")) { _, _ -> "At the waterfall" },
        Theme("mountain", setOf("mountain", "rock")) { _, _ -> "In the mountains" },
        Theme("water", setOf("lake", "river", "boat")) { _, _ -> "By the water" },
        Theme("forest", setOf("forest")) { _, _ -> "In the woods" },
        Theme("sunset", setOf("sunset")) { _, _ -> "Sunset" },
        Theme("castle", setOf("castle")) { _, _ -> "At the castle" },
        Theme("city", setOf("city", "street", "tower", "bridge", "fountain")) { h, _ -> if (h >= 19) "The city at night" else "In the city" },
        Theme("meal", MEAL) { h, _ ->
            when {
                h < 5 -> "Late bite"; h < 10.75 -> "Breakfast"; h < 15.5 -> "Lunch"; h < 17.75 -> "Afternoon snack"; else -> "Dinner"
            }
        },
        Theme("travel", TRAVEL) { _, first -> if (first) "Setting off" else "On the way" },
        Theme("sports", setOf("soccer", "tennis")) { _, _ -> "Game time" },
        Theme("garden", setOf("flower", "grass")) { _, _ -> "Outdoors" },
        Theme("animals", setOf("dog", "cat", "horse", "bird", "animal", "cow", "sheep", "goat", "elephant", "zebra", "giraffe", "monkey", "lion", "tiger")) { _, _ -> "With the animals" },
        Theme("night", setOf("night")) { _, _ -> "Night out" },
    )

    /** A moment's name: its main theme (if at least a third of its shots show it) or the time of day. */
    fun label(shots: List<Shot>, first: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
        val mid = shots[shots.size / 2].takenMs
        val h = hour(mid, zone)
        var best: Theme? = null; var bestShare = 0.0
        for (t in THEMES) {
            val share = shots.count { s -> t.keys.any { s.tagged(it) } }.toDouble() / shots.size
            // Drinks at night are a night out, not a meal.
            if (t.name == "meal" && h >= 20 && shots.none { s -> setOf("food", "pizza", "burger", "sushi", "salad", "dessert", "cake").any { s.tagged(it) } }) continue
            if (share > bestShare + 1e-9) { best = t; bestShare = share }
        }
        if (best != null && bestShare >= 1.0 / 3) return best.label(h, first)
        if (first && shots.any { s -> TRAVEL.any { s.tagged(it) } }) return "Setting off"
        val people = shots.count { s -> s.faces.size >= 3 }.toDouble() / shots.size
        if (people >= 0.5) return "Together"
        return when { h < 5 -> "Late night"; h < 11 -> "Morning"; h < 14 -> "Midday"; h < 18 -> "Afternoon"; h < 21 -> "Evening"; else -> "Night" }
    }

    // ------------------------------------------------------------------ scores
    /**
     * How much a shot deserves a place: sharp (compared with the event's other shots), well
     * exposed, with faces (more and bigger count more), showing something, a favourite; badly
     * blurred shots drop below everything else.
     */
    fun scores(shots: List<Shot>): Map<Int, Double> {
        val sharp = shots.map { it.sharpness }.sorted()
        val median = sharp[sharp.size / 2]
        fun rank(v: Double): Double { if (sharp.size < 2) return 0.5; var lo = 0; while (lo < sharp.size && sharp[lo] < v) lo++; return lo.toDouble() / (sharp.size - 1) }
        return shots.associate { s ->
            val r = rank(s.sharpness)
            var v = 1.0 + 1.2 * r + 0.6 * s.exposure
            v += 0.25 * min(s.faces.size, 4) + 2.0 * min(0.3f, s.faces.maxOfOrNull { it.area } ?: 0f)
            v += 0.5 * (s.tags.values.maxOrNull()?.let { (it - 0.5f).coerceAtLeast(0f) * 2 } ?: 0f)
            if (s.favorite) v += 1.0
            if (s.kind == ShotKind.VIDEO) v += 0.4
            if (s.kind == ShotKind.PHOTO && r < 0.12 && sharp.size >= 6) v -= 1.0
            // Badly blurred (a fifth of the event's usual sharpness): only if there's nothing else.
            if (s.kind == ShotKind.PHOTO && sharp.size >= 4 && s.sharpness < 0.2 * median) v -= 3.0
            if (s.exposure < 0.25f) v -= 0.8
            s.id to v
        }
    }

    /** Shots of a burst (taken within 90 s and nearly the same scene) — only the best of each counts. */
    fun bursts(shots: List<Shot>): List<List<Shot>> {
        val s = shots.sortedBy { it.takenMs }
        val out = ArrayList<MutableList<Shot>>()
        for (x in s) {
            val g = out.lastOrNull()
            val y = g?.last()
            val same = y != null && x.kind == ShotKind.PHOTO && y.kind == ShotKind.PHOTO && x.takenMs - y.takenMs <= 90_000 && run {
                val a = x.features; val b = y.features
                if (a != null && b != null) cos(a, b) >= 0.92 else x.takenMs - y.takenMs <= 4_000
            }
            if (same) g!!.add(x) else out.add(mutableListOf(x))
        }
        return out
    }

    private fun cos(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (k in a.indices) { d += a[k] * b[k]; na += a[k] * a[k]; nb += b[k] * b[k] }
        return d / sqrt(na * nb).coerceAtLeast(1e-9)
    }

    /** The best stretch of a video, [lengthMs] long (scored per measured window), as (start, end). */
    fun bestStretch(v: Shot, lengthMs: Long, withSound: Boolean): Pair<Long, Long> {
        val dur = max(v.durationMs, 1)
        val len = min(lengthMs, dur)
        // Skip the first and last moments (pressing the button shakes the phone).
        val margin = if (dur > len + 1200) 400L else 0L
        if (v.windows.isEmpty()) {
            val s = ((dur - len) / 2).coerceAtLeast(0)
            return s to s + len
        }
        val sharp = v.windows.map { it.sharpness }.sorted()
        fun windowScore(w: VideoWindow): Double {
            val r = sharp.indexOfFirst { it >= w.sharpness }.coerceAtLeast(0).toDouble() / max(1, sharp.size - 1)
            return 0.5 + 0.8 * w.motion + 0.15 * min(w.faces, 3) + 2.0 * min(0.3f, w.faceArea) + 0.4 * r +
                (if (withSound) 0.3 * w.loudness else 0.0) - 0.8 * w.shake
        }
        var best = margin; var bestScore = Double.NEGATIVE_INFINITY
        val stepMs = 250L
        var s = margin
        while (s + len <= dur - margin || s == margin) {
            val e = s + len
            var sum = 0.0; var weight = 0.0
            for (w in v.windows) {
                val o = min(e, w.endMs) - max(s, w.startMs)
                if (o > 0) { sum += windowScore(w) * o; weight += o.toDouble() }
            }
            val sc = if (weight > 0) sum / weight else 0.0
            if (sc > bestScore + 1e-9) { bestScore = sc; best = s }
            if (s + len > dur - margin) break
            s += stepMs
        }
        val start = best.coerceIn(0, max(0, dur - len))
        return start to start + len
    }

    // ------------------------------------------------------------------ the plan
    fun plan(all: List<Shot>, o: PlanOptions): Plan {
        val shots = all.filter { it.id !in o.exclude }.sortedBy { it.takenMs }
        require(shots.isNotEmpty()) { "nothing to put in the video" }
        // Moments come from everything picked, so leaving a shot out doesn't reshuffle them.
        val found = moments(all, o.zone)
        val moments = found.map { m -> o.names[m.index]?.trim()?.takeIf { it.isNotEmpty() }?.let { Moment(m.index, m.shots, it, m.startMs, m.endMs) } ?: m }
        val score = scores(shots)
        val beatMs = (o.beatSeconds * 1000).roundToLong().coerceIn(250, 1500)
        fun beats(sec: Double) = max(2, (sec * 1000 / beatMs).roundToInt())
        val photoBeats = beats(2.2)
        val videoMin = beats(2.5); val videoMax = max(videoMin, beats(6.0))
        // What each moment offers once bursts count once (the rest of a burst ranks far below).
        class Pick(val shot: Shot, val score: Double) { val must = shot.id in o.include }
        val order = compareByDescending<Pick> { it.must }.thenByDescending { it.score }
        val offer = moments.map { m ->
            val picks = ArrayList<Pick>()
            for (b in bursts(m.shots.filter { it.id !in o.exclude })) {
                val ranked = b.map { Pick(it, score.getValue(it.id)) }.sortedWith(order)
                picks.add(ranked[0])
                for (x in ranked.drop(1)) picks.add(Pick(x.shot, x.score - 2.5))
            }
            picks.sortedWith(order)
        }
        fun lengthBeats(s: Shot) = if (s.kind == ShotKind.VIDEO) (s.durationMs / beatMs).toInt().coerceIn(videoMin, videoMax).coerceAtLeast(1) else photoBeats
        val autoSeconds = (offer.sumOf { list -> list.take(4).sumOf { lengthBeats(it.shot) } } * beatMs / 1000.0).coerceIn(15.0, 90.0)
        val target = (o.seconds ?: autoSeconds).coerceAtLeast(beatMs * 2 / 1000.0)
        val targetBeats = max(2, (target * 1000 / beatMs).roundToInt())
        // Each moment's share: more (and better) shots, more time; everyone gets at least one shot.
        val weights = offer.map { list -> if (list.isEmpty()) 0.0 else sqrt(list.size.toDouble()) * (0.6 + list.map { it.score }.sortedDescending().take(3).average().coerceAtLeast(0.0) / 4) }
        val wsum = weights.sum()
        val chosen = ArrayList<List<Pick>>()
        var spare = 0
        for ((i, list) in offer.withIndex()) {
            val share = (targetBeats * weights[i] / wsum).roundToInt() + spare
            val take = ArrayList<Pick>()
            var used = 0
            for (p in list) {
                val l = lengthBeats(p.shot)
                if (p.must) { take.add(p); used += l; continue }
                if (take.isNotEmpty() && used + l > share + l / 2) break
                if (p.score < 0 && take.isNotEmpty()) break
                take.add(p); used += l
            }
            spare = (share - used).coerceAtLeast(0)
            chosen.add(take.sortedBy { it.shot.takenMs })
        }
        // Lay the clips out on the beat.
        val clips = ArrayList<Clip>()
        val crossBeats = if (beatMs <= 600) 1.0 else 0.5
        val crossMs = (beatMs * crossBeats).roundToLong()
        var t = 0L
        var photoIndex = 0
        for ((mi, list) in chosen.withIndex()) {
            for ((k, p) in list.withIndex()) {
                val s = p.shot
                val first = clips.isEmpty()
                val fade = if (first) 400L else if (k == 0) crossMs else 0L
                val start = if (first) 0L else t - fade
                val lenMs = lengthBeats(s) * beatMs + (if (k == 0 && !first) fade else 0L)
                val end = start + lenMs
                val fit = mismatch(s.aspect, o.aspect.ratio) > FIT_ABOVE
                if (s.kind == ShotKind.VIDEO) {
                    val sound = o.clipSound && s.hasAudio && (s.windows.isEmpty() || s.windows.any { it.loudness > 0.05f })
                    val (a, b) = bestStretch(s, min(lenMs, max(s.durationMs, 1)), sound)
                    val f = if (fit) Frame(0f, 0f, 1f, 1f) else cover(s, o.aspect.ratio, 1f, faceFocus(s))
                    val shown = b - a
                    clips.add(Clip(s, mi, start, start + shown, a, b, f, f, fade, sound, fit))
                    t = start + shown
                } else {
                    val (from, to) = kenBurns(s, if (fit) s.aspect else o.aspect.ratio, photoIndex++)
                    clips.add(Clip(s, mi, start, end, 0, 0, from, to, fade, false, fit))
                    t = end
                }
            }
        }
        val fadeOut = 1000L
        val duration = t
        val title = o.title?.trim()?.takeIf { it.isNotEmpty() } ?: title(shots, o.zone)
        val themes = moments.map { it.label.substringAfter(" · ") }.distinct()
        val subtitle = themes.take(3).joinToString(" · ")
        val captions = ArrayList<Caption>()
        if (o.captions) {
            captions.add(Caption(title, subtitle, 300, min(3300, duration - 300).coerceAtLeast(600), true))
            for ((mi, m) in moments.withIndex()) {
                if (mi == 0) continue
                val c = clips.firstOrNull { it.moment == mi } ?: continue
                val at = c.startMs + c.fadeInMs
                captions.add(Caption(m.label, time(m.startMs, o.zone), at, min(at + 1900, c.endMs), false))
            }
        }
        return Plan(moments, clips, captions, duration, title, subtitle, fadeOut, o.aspect)
    }

    /** Pictures this much wider or narrower than the video are shown whole (see [Clip.fit]). */
    const val FIT_ABOVE = 1.45f

    private fun mismatch(a: Float, b: Float) = max(a / b, b / a)

    /** The shape that suits most of the pictures. */
    fun aspectFor(shots: List<Shot>): Aspect {
        val portrait = shots.count { it.aspect < 0.9f }; val landscape = shots.count { it.aspect > 1.1f }
        return when {
            portrait > landscape -> Aspect.PORTRAIT
            landscape >= portrait && landscape > 0 -> Aspect.LANDSCAPE
            else -> Aspect.SQUARE
        }
    }

    /** "Saturday, 14 June 2026", "14–16 June 2026", "28 June – 3 July 2026". */
    fun title(shots: List<Shot>, zone: TimeZone): String {
        val a = cal(shots.minOf { it.takenMs }, zone); val b = cal(shots.maxOf { it.takenMs }, zone)
        fun month(c: Calendar) = c.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.UK)!!
        fun dd(c: Calendar) = c.get(Calendar.DAY_OF_MONTH)
        return when {
            day(a.timeInMillis, zone) == day(b.timeInMillis, zone) -> "${a.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.UK)}, ${dd(a)} ${month(a)} ${a.get(Calendar.YEAR)}"
            a.get(Calendar.MONTH) == b.get(Calendar.MONTH) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR) -> "${dd(a)}–${dd(b)} ${month(a)} ${a.get(Calendar.YEAR)}"
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) -> "${dd(a)} ${month(a)} – ${dd(b)} ${month(b)} ${b.get(Calendar.YEAR)}"
            else -> "${dd(a)} ${month(a)} ${a.get(Calendar.YEAR)} – ${dd(b)} ${month(b)} ${b.get(Calendar.YEAR)}"
        }
    }

    private fun time(ms: Long, zone: TimeZone): String {
        val c = cal(ms, zone)
        val h = c.get(Calendar.HOUR); val m = c.get(Calendar.MINUTE)
        return String.format(Locale.US, "%d:%02d %s", if (h == 0) 12 else h, m, if (c.get(Calendar.AM_PM) == Calendar.AM) "am" else "pm")
    }

    // ------------------------------------------------------------------ framing
    /** Where to look in a picture: the faces (bigger ones count more), else a little above the middle. */
    fun faceFocus(s: Shot): Pair<Float, Float>? {
        val faces = s.faces.map { it.clipped() }.filter { it.area > 0f }
        if (faces.isEmpty()) return null
        var wx = 0f; var wy = 0f; var w = 0f
        for (f in faces) { val a = sqrt(f.area); wx += f.cx * a; wy += f.cy * a; w += a }
        return wx / w to wy / w
    }

    /**
     * The largest part of the picture with the output's shape, [zoom]× closer, centred on [focus]
     * (or slightly above the middle) and kept inside the picture.
     */
    fun cover(s: Shot, outRatio: Float, zoom: Float, focus: Pair<Float, Float>?): Frame {
        val a = s.aspect
        var w: Float; var h: Float
        if (a > outRatio) { w = outRatio / a; h = 1f } else { w = 1f; h = a / outRatio }
        w /= zoom; h /= zoom
        val (fx, fy) = focus ?: (0.5f to 0.45f)
        val x = (fx - w / 2).coerceIn(0f, 1f - w)
        val y = (fy - h / 2).coerceIn(0f, 1f - h)
        return Frame(x, y, w, h)
    }

    /**
     * A slow zoom for a photo: alternately in and out by up to 12 % toward the faces, less when that
     * would cut a face off; very wide pictures pan across instead.
     */
    fun kenBurns(s: Shot, outRatio: Float, n: Int): Pair<Frame, Frame> {
        val focus = faceFocus(s)
        val wide = s.aspect > outRatio * 1.9f
        if (wide && s.faces.isEmpty()) {
            val left = cover(s, outRatio, 1f, 0f to 0.5f); val right = cover(s, outRatio, 1f, 1f to 0.5f)
            return if (n % 2 == 0) left to right else right to left
        }
        val faces = s.faces.map { it.clipped() }
        var zoom = 1.12f
        val full = cover(s, outRatio, 1f, focus)
        while (zoom > 1.0f) {
            val close = cover(s, outRatio, zoom, focus)
            if (faces.all { f -> !full.contains(f) || close.contains(f) }) break
            zoom -= 0.02f
        }
        val close = cover(s, outRatio, max(1f, zoom), focus)
        return if (n % 2 == 0) full to close else close to full
    }
}
