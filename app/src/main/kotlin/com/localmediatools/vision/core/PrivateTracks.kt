package com.localmediatools.vision.core

import kotlin.math.max
import kotlin.math.min

/** Where a private thing was at [tUs]: its four corners as fractions of the picture (x, y pairs). */
class PrivateSample(val tUs: Long, val quad: FloatArray) {
    val x0 get() = min(min(quad[0], quad[2]), min(quad[4], quad[6]))
    val y0 get() = min(min(quad[1], quad[3]), min(quad[5], quad[7]))
    val x1 get() = max(max(quad[0], quad[2]), max(quad[4], quad[6]))
    val y1 get() = max(max(quad[1], quad[3]), max(quad[5], quad[7]))

    fun iou(o: PrivateSample): Float {
        val ix = max(0f, min(x1, o.x1) - max(x0, o.x0)); val iy = max(0f, min(y1, o.y1) - max(y0, o.y0))
        val i = ix * iy; val u = (x1 - x0) * (y1 - y0) + (o.x1 - o.x0) * (o.y1 - o.y0) - i
        return if (u <= 0f) 0f else i / u
    }
}

/**
 * A licence plate, a piece of private text or a code, in one photo ([still]) or followed through a
 * video. [source] is the file's index in the scan; [details] what kind of private text it is.
 */
class PrivateTrack(val id: Int, val source: Int, val kind: FindKind, details: Set<Detail>, text: String, val still: Boolean = false) {
    val samples = ArrayList<PrivateSample>()
    var details: Set<Detail> = details; internal set
    var text: String = text; internal set
    val start get() = samples.first().tUs
    val end get() = samples.last().tUs
}

/** A rectangle to hide at a moment: centre and half sizes as fractions of the picture. */
class HideRect(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/**
 * Follows things found in sampled video frames: a find overlapping one of the same kind seen in the
 * last [maxGapUs] (IoU above 0.3, best match first) continues it, anything else starts a new track.
 */
class PrivateTracker(private val maxGapUs: Long, private val source: Int = 0) {
    val tracks = ArrayList<PrivateTrack>()
    private var next = 0

    /** [finds]: kind, details, text read and corners as fractions of the frame. */
    fun add(tUs: Long, finds: List<Triple<FindKind, Pair<Set<Detail>, String>, FloatArray>>) {
        val open = tracks.filter { tUs - it.end <= maxGapUs && it.end < tUs }.toMutableList()
        val pairs = ArrayList<Triple<Float, Int, PrivateTrack>>()
        val samples = finds.map { PrivateSample(tUs, it.third) }
        for ((i, s) in samples.withIndex()) for (t in open) {
            if (t.kind != finds[i].first) continue
            val o = s.iou(t.samples.last())
            if (o > 0.3f) pairs.add(Triple(o, i, t))
        }
        val usedFind = HashSet<Int>(); val usedTrack = HashSet<PrivateTrack>()
        for ((_, i, t) in pairs.sortedByDescending { it.first }) {
            if (i in usedFind || t in usedTrack) continue
            usedFind.add(i); usedTrack.add(t)
            t.samples.add(samples[i])
            t.details = t.details + finds[i].second.first
            if (t.text.isEmpty()) t.text = finds[i].second.second
        }
        for ((i, f) in finds.withIndex()) {
            if (i in usedFind) continue
            tracks.add(PrivateTrack(next++, source, f.first, f.second.first, f.second.second).also { it.samples.add(samples[i]) })
        }
    }

    companion object {
        /**
         * Rectangles to hide at [tUs]: each track's box (grown by [PrivacyShape.MARGIN] of its
         * thickness, more when it moves fast), moved smoothly between samples and kept [holdUs]
         * before its first and after its last.
         */
        fun regionsAt(tracks: List<PrivateTrack>, tUs: Long, holdUs: Long, aspect: Float): List<HideRect> {
            val out = ArrayList<HideRect>()
            for (t in tracks) {
                if (t.samples.isEmpty() || tUs < t.start - holdUs || tUs > t.end + holdUs) continue
                val s = t.samples
                val k = s.indexOfFirst { it.tUs >= tUs }
                val box: FloatArray
                var speed = 0f
                if (k <= 0 || s.size == 1) {
                    val one = if (k < 0) s.last() else s[k.coerceAtLeast(0)]
                    box = floatArrayOf(one.x0, one.y0, one.x1, one.y1)
                } else {
                    val a = s[k - 1]; val b = s[k]
                    val f = ((tUs - a.tUs).toFloat() / (b.tUs - a.tUs).coerceAtLeast(1)).coerceIn(0f, 1f)
                    box = floatArrayOf(a.x0 + (b.x0 - a.x0) * f, a.y0 + (b.y0 - a.y0) * f, a.x1 + (b.x1 - a.x1) * f, a.y1 + (b.y1 - a.y1) * f)
                    speed = kotlin.math.abs((b.x0 + b.x1) - (a.x0 + a.x1)) / 2 + kotlin.math.abs((b.y0 + b.y1) - (a.y0 + a.y1)) / 2
                }
                // The thickness in the same units both ways ([aspect] = width / height of the picture).
                val w = box[2] - box[0]; val h = box[3] - box[1]
                val thick = min(w * aspect, h)
                val m = thick * PrivacyShape.MARGIN
                val mx = m / aspect + speed * 0.5f; val my = m + speed * 0.5f
                out.add(HideRect((box[0] + box[2]) / 2, (box[1] + box[3]) / 2, w / 2 + mx, h / 2 + my))
            }
            return out
        }
    }
}
