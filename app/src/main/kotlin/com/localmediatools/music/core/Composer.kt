package com.localmediatools.music.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The character of the background music. */
enum class Mood(val label: String, val explain: String, val bpm: Int, val temperature: Float) {
    UPBEAT("Upbeat", "Bright, with a beat", 120, 0.5f),
    CHILL("Chill", "Relaxed, soft electric piano", 92, 0.45f),
    CINEMATIC("Cinematic", "Slow and wide, piano and strings", 76, 0.4f),
}

/** A note on the 16th-note grid. */
class Note(val start: Int, val end: Int, val pitch: Int, val velocity: Float)
class DrumHit(val step: Int, val drum: Int, val velocity: Float)

/** What a part of the song plays. */
enum class Part { INTRO, VERSE, CHORUS, OUTRO }
class Section(val part: Part, val startStep: Int, val steps: Int)

/** A whole piece: notes per instrument on a grid of 16th notes at [bpm]. */
class Score(
    val mood: Mood, val bpm: Double, val totalSteps: Int,
    val melody: List<Note>, val bass: List<Note>, val pads: List<Note>, val drums: List<DrumHit>,
    val sections: List<Section>, val key: Key,
) {
    val stepSeconds get() = 60.0 / bpm / 4
    val seconds get() = totalSteps * stepSeconds
    /** Where beats fall (seconds), for cutting a video on the music. */
    fun beats(): DoubleArray = DoubleArray(totalSteps / 4) { it * 4 * stepSeconds }
}

/** A key: tonic pitch class (0 = C) and major or minor. */
data class Key(val tonic: Int, val minor: Boolean) {
    val scale: IntArray get() = (if (minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)).map { (it + tonic) % 12 }.toIntArray()
    override fun toString() = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")[tonic] + if (minor) " minor" else " major"
}

/**
 * Writes background music for a video of a given length: samples several 4-bar ideas from the
 * model (melody, bass and drums), keeps the two that sound most like music by simple measures
 * (notes in one key, a singable range, neither empty nor frantic, a steady beat, a bass line),
 * puts the second in the first one's key, and arranges intro, verses and variations around them,
 * with chords for a pad, to cover the length. The same seed always gives the same piece.
 */
object Composer {
    /** Krumhansl–Kessler key profiles. */
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    class Idea(val z: FloatArray, val trio: Trio, val score: Double, val key: Key)

    fun compose(model: TrioModel, mood: Mood, seconds: Double, seed: Long, candidates: Int = 8): Score {
        val rnd = java.util.Random(seed)
        val r = { rnd.nextDouble() }
        // Codes and the random numbers for each decode are drawn first, so decoding can run on
        // several cores and still give the same piece for the same seed.
        val zs = List(candidates) { FloatArray(model.zDims) { TrioModel.normal(r).toFloat() } }
        val seeds = LongArray(candidates) { rnd.nextLong() }
        val ideas = parallel(candidates) { k ->
            val rk = java.util.Random(seeds[k])
            val t = model.decode(zs[k], mood.temperature) { rk.nextDouble() }
            Idea(zs[k], t, rate(t, mood), keyOf(t))
        }.sortedByDescending { it.score }
        val a = ideas[0]
        // The second idea: good on its own and different enough from the first.
        val b = ideas.drop(1).maxBy { it.score - 2.0 * similarity(a.trio, it.trio) }
        val key = a.key
        val shiftB = transposition(b.key, key)
        // Variations: the same idea decoded from a nearby code.
        val nearZ = listOf(a, b).map { i -> FloatArray(i.z.size) { i.z[it] + 0.25f * TrioModel.normal(r).toFloat() } }
        val nearSeeds = LongArray(2) { rnd.nextLong() }
        val (a2, b2) = parallel(2) { k -> val rk = java.util.Random(nearSeeds[k]); model.decode(nearZ[k], mood.temperature) { rk.nextDouble() } }
        val shiftA2 = transposition(keyOf(a2), key); val shiftB2 = transposition(keyOf(b2), key)
        val bpm = mood.bpm.toDouble()
        val blockSec = Trio.STEPS * 60.0 / bpm / 4
        // Enough 4-bar blocks for the video plus a little tail for the fade-out.
        val blocks = max(1, ceil((seconds + 1.0) / blockSec).toInt())
        data class Block(val part: Part, val trio: Trio, val shift: Int)
        val plan = ArrayList<Block>()
        when {
            blocks == 1 -> plan.add(Block(Part.VERSE, a.trio, 0))
            blocks == 2 -> { plan.add(Block(Part.VERSE, a.trio, 0)); plan.add(Block(Part.OUTRO, a2, shiftA2)) }
            else -> {
                plan.add(Block(Part.INTRO, a.trio, 0))
                val body = listOf(Block(Part.VERSE, a.trio, 0), Block(Part.CHORUS, b.trio, shiftB), Block(Part.VERSE, a2, shiftA2), Block(Part.CHORUS, b2, shiftB2))
                var k = 0
                while (plan.size < blocks - 1) { plan.add(body[k % body.size]); k++ }
                plan.add(Block(Part.OUTRO, a.trio, 0))
            }
        }
        val melody = ArrayList<Note>(); val bass = ArrayList<Note>(); val drums = ArrayList<DrumHit>(); val pads = ArrayList<Note>()
        val scale = key.scale.toSet()
        val sections = ArrayList<Section>()
        for ((i, blk) in plan.withIndex()) {
            val off = i * Trio.STEPS
            sections.add(Section(blk.part, off, Trio.STEPS))
            val mel = notes(blk.trio.melody, off, blk.shift, 60, 84, scale)
            val bs = notes(blk.trio.bass, off, blk.shift, 36, 55, scale)
            val dr = drumHits(blk.trio.drums, off, mood, blk.part)
            if (blk.part != Part.INTRO) melody.addAll(mel)
            bass.addAll(bs)
            drums.addAll(dr)
            pads.addAll(chords(mel, bs, off, key))
        }
        return Score(mood, bpm, plan.size * Trio.STEPS, melody, bass, pads, drums, sections, key)
    }

    /** [f] for 0 until [n], on up to four cores (the model's weights are only read). */
    private fun <T> parallel(n: Int, f: (Int) -> T): List<T> {
        val threads = min(n, Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        if (threads <= 1) return List(n) { f(it) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val futures = List(n) { k -> pool.submit(java.util.concurrent.Callable { f(k) }) }
            return futures.map { it.get() }
        } finally { pool.shutdown() }
    }

    /**
     * Notes from melody or bass events, transposed by [shift] and folded by octaves into [lo, hi];
     * with a [scale] (pitch classes) a note outside it moves a semitone to the nearest one (down
     * when both are), so the lines always fit the pad's chords.
     */
    internal fun notes(events: IntArray, offset: Int, shift: Int, lo: Int, hi: Int, scale: Set<Int>? = null): List<Note> {
        val out = ArrayList<Note>()
        var start = -1; var pitch = 0
        fun close(at: Int) {
            if (start >= 0) {
                var p = pitch + shift
                if (scale != null && p % 12 !in scale) p += if ((p + 11) % 12 in scale) -1 else 1
                while (p < lo) p += 12
                while (p > hi) p -= 12
                // Downbeats a little louder.
                val vel = if (start % 4 == 0) 0.9f else 0.75f
                out.add(Note(offset + start, offset + at, p, vel))
            }
            start = -1
        }
        for ((s, e) in events.withIndex()) {
            when {
                e == Trio.HOLD -> {}
                e == Trio.OFF -> close(s)
                else -> { close(s); start = s; pitch = Trio.pitchOf(e) }
            }
        }
        close(events.size)
        return out
    }

    private fun drumHits(events: IntArray, offset: Int, mood: Mood, part: Part): List<DrumHit> {
        val out = ArrayList<DrumHit>()
        for ((s, e) in events.withIndex()) {
            for (d in 0 until 9) if ((e shr d) and 1 == 1) {
                // Chill: no crashes, soft snare; cinematic: only kick, toms and cymbal swells, sparse.
                val keep = when (mood) {
                    Mood.UPBEAT -> true
                    Mood.CHILL -> d != 7
                    Mood.CINEMATIC -> d == 0 && s % 8 == 0 || d in 4..6 || (d == 7 && s % 16 == 0)
                }
                if (!keep) continue
                if (part == Part.INTRO && d !in setOf(0, 2, 8)) continue
                val accent = if (s % 4 == 0) 1f else if (s % 2 == 0) 0.8f else 0.65f
                val vel = accent * when (d) { 0 -> 0.75f; 1 -> if (mood == Mood.CHILL) 0.55f else 0.9f; 2, 3 -> 0.5f; 7 -> 0.6f; 8 -> 0.45f; else -> 0.7f }
                out.add(DrumHit(offset + s, d, vel))
            }
        }
        return out
    }

    /** One pad chord per bar: the triad of the key built on the bar's main bass note. */
    private fun chords(mel: List<Note>, bass: List<Note>, offset: Int, key: Key): List<Note> {
        val out = ArrayList<Note>()
        val scale = key.scale
        for (bar in 0 until 4) {
            val b0 = offset + bar * 16; val b1 = b0 + 16
            val weight = DoubleArray(12)
            for (n in bass) { val d = overlap(n, b0, b1); if (d > 0) weight[n.pitch % 12] += d * 2.0 }
            for (n in mel) { val d = overlap(n, b0, b1); if (d > 0) weight[n.pitch % 12] += d * 0.5 }
            if (weight.all { it == 0.0 }) continue
            // The scale degree with the most weight (its triad's notes count too).
            val degree = (0 until 7).maxBy { k -> weight[scale[k]] * 1.0 + weight[scale[(k + 2) % 7]] * 0.5 + weight[scale[(k + 4) % 7]] * 0.5 }
            val root = scale[degree]; val third = scale[(degree + 2) % 7]; val fifth = scale[(degree + 4) % 7]
            for (pc in intArrayOf(root, third, fifth)) {
                var p = 48 + pc
                if (p < 52) p += 12
                out.add(Note(b0, b1, p, 0.5f))
            }
        }
        return out
    }

    private fun overlap(n: Note, a: Int, b: Int) = max(0, min(n.end, b) - max(n.start, a))

    /** The best-fitting key of the notes (Krumhansl–Schmuckler on durations). */
    fun keyOf(t: Trio): Key {
        val w = DoubleArray(12)
        for (n in notes(t.melody, 0, 0, 0, 127) + notes(t.bass, 0, 0, 0, 127)) w[n.pitch % 12] += (n.end - n.start).toDouble()
        var best = Key(0, false); var bestR = -2.0
        for (tonic in 0 until 12) for (minor in listOf(false, true)) {
            val prof = if (minor) MINOR else MAJOR
            val r = correlation(w) { prof[(it - tonic + 12) % 12] }
            if (r > bestR) { bestR = r; best = Key(tonic, minor) }
        }
        return best
    }

    private fun correlation(w: DoubleArray, p: (Int) -> Double): Double {
        val mw = w.average(); val mp = (0 until 12).sumOf { p(it) } / 12
        var num = 0.0; var dw = 0.0; var dp = 0.0
        for (i in 0 until 12) { val a = w[i] - mw; val b = p(i) - mp; num += a * b; dw += a * a; dp += b * b }
        return if (dw == 0.0) -1.0 else num / sqrt(dw * dp)
    }

    /** Semitones (−5..6) moving [from]'s tonic to [to]'s (relative major/minor counted as the same key). */
    fun transposition(from: Key, to: Key): Int {
        val f = if (from.minor) (from.tonic + 3) % 12 else from.tonic
        val t = if (to.minor) (to.tonic + 3) % 12 else to.tonic
        var d = (t - f + 12) % 12
        if (d > 6) d -= 12
        return d
    }

    /**
     * How much an idea sounds like usable background music (higher is better): notes in one key,
     * a melody neither empty nor frantic within a singable range, a bass line, a steady beat when
     * the mood has drums, and some repetition between bars.
     */
    fun rate(t: Trio, mood: Mood): Double {
        val mel = notes(t.melody, 0, 0, 0, 127); val bass = notes(t.bass, 0, 0, 0, 127)
        var s = 0.0
        // Melody density: 8–32 notes in 4 bars.
        s += when { mel.size < 4 -> -3.0; mel.size < 8 -> -1.0; mel.size <= 32 -> 1.0; else -> -1.5 }
        if (mel.isNotEmpty()) {
            val range = mel.maxOf { it.pitch } - mel.minOf { it.pitch }
            s += if (range <= 19) 0.5 else -1.0
            // Silence longer than a bar and a half hurts.
            var gap = 0; var last = 0
            for (n in mel.sortedBy { it.start }) { gap = max(gap, n.start - last); last = max(last, n.end) }
            gap = max(gap, Trio.STEPS - last)
            if (gap > 24) s -= 1.0
        }
        // In key.
        val key = keyOf(t)
        val scale = key.scale.toSet()
        val all = mel + bass
        val dur = all.sumOf { it.end - it.start }.coerceAtLeast(1)
        val inKey = all.filter { it.pitch % 12 in scale }.sumOf { it.end - it.start }.toDouble() / dur
        s += 4.0 * (inKey - 0.85)
        if (mood != Mood.CINEMATIC && !key.minor) s += 0.3
        // Bass.
        s += if (bass.size >= 4) 0.7 else -1.0
        // Drums: a kick and a snare or hats for moods with a beat, not every 16th busy.
        val kicks = t.drums.count { it and 1 == 1 }; val snares = t.drums.count { (it shr 1) and 1 == 1 }
        val busy = t.drums.count { it != 0 }
        if (mood == Mood.UPBEAT) s += if (kicks >= 4 && snares >= 2) 1.0 else -1.0
        if (mood == Mood.CHILL) s += if (kicks >= 2) 0.5 else -0.5
        if (busy > 56) s -= 0.5
        // Bars echoing each other (1 and 3, 2 and 4) give the loop a shape.
        s += 0.5 * (barEcho(t.melody) + barEcho(t.drums))
        return s
    }

    private fun barEcho(e: IntArray): Double {
        var same = 0
        for (k in 0 until 16) { if (e[k] == e[32 + k]) same++; if (e[16 + k] == e[48 + k]) same++ }
        return same / 32.0
    }

    /** How alike two ideas are (share of identical events), to keep the second idea different. */
    fun similarity(a: Trio, b: Trio): Double {
        var same = 0
        for (i in 0 until Trio.STEPS) { if (a.melody[i] == b.melody[i]) same++; if (a.drums[i] == b.drums[i]) same++ }
        return same / (2.0 * Trio.STEPS)
    }
}
