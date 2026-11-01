package com.localmediatools.vision.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.dnn.TextDetectionModel_DB
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.BarcodeDetector
import org.opencv.objdetect.QRCodeDetector
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Four corners in pixels, in order around the shape (text: top-left, top-right, bottom-right, bottom-left). */
class Quad(val p: FloatArray) {
    init { require(p.size == 8) }
    val x0 get() = min(min(p[0], p[2]), min(p[4], p[6]))
    val y0 get() = min(min(p[1], p[3]), min(p[5], p[7]))
    val x1 get() = max(max(p[0], p[2]), max(p[4], p[6]))
    val y1 get() = max(max(p[1], p[3]), max(p[5], p[7]))
    val cx get() = (p[0] + p[2] + p[4] + p[6]) / 4
    val cy get() = (p[1] + p[3] + p[5] + p[7]) / 4
    /** Length of the first side and its neighbour (for text: along the line, and its height). */
    val width get() = max(hypot(p[2] - p[0], p[3] - p[1]), hypot(p[4] - p[6], p[5] - p[7]))
    val height get() = max(hypot(p[6] - p[0], p[7] - p[1]), hypot(p[4] - p[2], p[5] - p[3]))
    /** The shorter side: how tall text is whichever way it runs. */
    val thickness get() = min(width, height)

    fun map(sx: Float, sy: Float, dx: Float = 0f, dy: Float = 0f) = Quad(FloatArray(8) { if (it % 2 == 0) p[it] * sx + dx else p[it] * sy + dy })

    /** Bounding-box overlap (intersection over union). */
    fun iou(o: Quad): Float {
        val ix = max(0f, min(x1, o.x1) - max(x0, o.x0)); val iy = max(0f, min(y1, o.y1) - max(y0, o.y0))
        val i = ix * iy; val u = (x1 - x0) * (y1 - y0) + (o.x1 - o.x0) * (o.y1 - o.y0) - i
        return if (u <= 0f) 0f else i / u
    }

    /** Corners from the top-left, clockwise, the longer side first (text read along it). */
    fun ordered(): Quad {
        val cx = cx; val cy = cy
        val idx = (0 until 4).sortedBy { atan2(p[it * 2 + 1] - cy, p[it * 2] - cx) }
        val tl = idx.minByOrNull { p[it * 2] + p[it * 2 + 1] }!!
        val start = idx.indexOf(tl)
        var q = Quad(FloatArray(8) { k -> val c = idx[(start + k / 2) % 4]; p[c * 2 + k % 2] })
        // Upright text taller than wide: the long side runs downwards.
        if (q.height > q.width * 1.5f) q = Quad(FloatArray(8) { k -> q.p[(k + 6) % 8] })
        return q
    }

    companion object {
        fun box(x0: Float, y0: Float, x1: Float, y1: Float) = Quad(floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    }
}

enum class FindKind { PLATE, TEXT, CODE }

/** Something to hide, in pixels of the picture it was found in. [text] is what was read (private text only). */
class Found(val kind: FindKind, val quad: Quad, val score: Float, val text: String = "", val details: Set<Detail> = emptySet())

/** What to look for besides faces. */
data class PrivacyOptions(val plates: Boolean = false, val privateText: Boolean = false, val allText: Boolean = false, val codes: Boolean = false) {
    val any get() = plates || privateText || allText || codes
    val text get() = privateText || allText
}

/** Shrinks [bgr] to at most [side] px on its longer side (a new Mat, or [bgr] itself), and the factor. */
internal fun fitMat(bgr: Mat, side: Int): Pair<Mat, Float> {
    val long = max(bgr.cols(), bgr.rows())
    if (long <= side) return bgr to 1f
    val s = side.toFloat() / long
    val out = Mat()
    Imgproc.resize(bgr, out, Size(max(1, (bgr.cols() * s).roundToInt()).toDouble(), max(1, (bgr.rows() * s).roundToInt()).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
    return out to s
}

/** All values of a (continuous) Mat as floats. */
internal fun floats(m: Mat): FloatArray {
    val f = Mat(); m.convertTo(f, CvType.CV_32F)
    try {
        val flat = f.reshape(1, 1)
        val out = FloatArray(flat.cols()); flat.get(0, 0, out)
        return out
    } finally { f.release() }
}

/**
 * Licence plates with LPD-YuNet (OpenCV Zoo, Apache 2.0; trained on Chinese plates, so other
 * countries' plates are found less often: see [PrivacyFinder]). Boxes are four corners.
 */
class PlateDetector(path: String) {
    private val net: Net = Dnn.readNet(path)
    private var priorsFor = 0 to 0
    private var priors = FloatArray(0)

    fun detect(bgr: Mat, minScore: Float): List<Found> = synchronized(this) {
        val w = bgr.cols(); val h = bgr.rows()
        if (w < 32 || h < 32) return emptyList()
        if (priorsFor != w to h) { priors = priors(w, h); priorsFor = w to h }
        val blob = Dnn.blobFromImage(bgr)
        val outs = ArrayList<Mat>()
        try {
            net.setInput(blob)
            net.forward(outs, listOf("loc", "conf", "iou"))
            val loc = floats(outs[0]); val conf = floats(outs[1]); val iou = floats(outs[2])
            val n = priors.size / 4
            if (loc.size != n * 14 || conf.size != n * 2 || iou.size != n) return emptyList()
            val cand = ArrayList<Found>()
            for (i in 0 until n) {
                val s = sqrt(conf[i * 2 + 1].coerceIn(0f, 1f) * iou[i].coerceIn(0f, 1f))
                if (s < minScore) continue
                val px = priors[i * 4]; val py = priors[i * 4 + 1]; val sx = priors[i * 4 + 2]; val sy = priors[i * 4 + 3]
                val q = FloatArray(8)
                for ((k, o) in intArrayOf(4, 6, 10, 12).withIndex()) {
                    q[k * 2] = (px + loc[i * 14 + o] * 0.1f * sx) * w
                    q[k * 2 + 1] = (py + loc[i * 14 + o + 1] * 0.1f * sy) * h
                }
                cand.add(Found(FindKind.PLATE, Quad(q), s))
            }
            return nms(cand, 0.3f)
        } finally { blob.release(); outs.forEach { it.release() } }
    }

    private fun priors(w: Int, h: Int): FloatArray {
        val minSizes = arrayOf(intArrayOf(10, 16, 24), intArrayOf(32, 48), intArrayOf(64, 96), intArrayOf(128, 192, 256))
        val steps = intArrayOf(8, 16, 32, 64)
        var fh = ((h + 1) / 2) / 2; var fw = ((w + 1) / 2) / 2
        val out = ArrayList<Float>()
        for (k in 0 until 4) {
            fh /= 2; fw /= 2
            for (i in 0 until fh) for (j in 0 until fw) for (m in minSizes[k]) {
                out.add((j + 0.5f) * steps[k] / w); out.add((i + 0.5f) * steps[k] / h)
                out.add(m.toFloat() / w); out.add(m.toFloat() / h)
            }
        }
        return out.toFloatArray()
    }
}

/** Best first; drops boxes overlapping a kept one by more than [iou]. */
internal fun nms(found: List<Found>, iou: Float): List<Found> {
    val keep = ArrayList<Found>()
    for (f in found.sortedByDescending { it.score }) if (keep.none { it.quad.iou(f.quad) > iou }) keep.add(f)
    return keep
}

/** Text lines with PP-OCRv3's detector (OpenCV Zoo, Apache 2.0): four corners each. */
class TextDetector(path: String) {
    private val model = TextDetectionModel_DB(Dnn.readNet(path)).apply {
        setBinaryThreshold(0.3f); setPolygonThreshold(0.5f); setUnclipRatio(2.0); setMaxCandidates(400)
        setInputMean(Scalar(123.675, 116.28, 103.53))
        setInputScale(Scalar(1.0 / 255 / 0.229, 1.0 / 255 / 0.224, 1.0 / 255 / 0.225))
    }

    fun detect(bgr: Mat): List<Found> = synchronized(this) {
        val w = bgr.cols(); val h = bgr.rows()
        if (w < 16 || h < 16) return emptyList()
        val pw = (w + 31) / 32 * 32; val ph = (h + 31) / 32 * 32
        val pad = Mat()
        Core.copyMakeBorder(bgr, pad, 0, ph - h, 0, pw - w, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
        val quads = ArrayList<MatOfPoint>(); val conf = MatOfFloat()
        try {
            model.setInputSize(pw, ph)
            model.detect(pad, quads, conf)
            val c = if (conf.total() > 0) conf.toArray() else FloatArray(0)
            return quads.mapIndexedNotNull { i, m ->
                val pts = m.toArray()
                if (pts.size != 4) null
                else Found(FindKind.TEXT, Quad(FloatArray(8) { k -> (if (k % 2 == 0) pts[k / 2].x else pts[k / 2].y).toFloat() }), c.getOrElse(i) { 1f })
            }
        } finally { pad.release(); conf.release(); quads.forEach { it.release() } }
    }
}

/**
 * Reads a line of text with PP-OCRv5's mobile recogniser (PaddleOCR, Apache 2.0; Chinese, Japanese,
 * English and other Latin-script languages): the line is straightened to 48 px high and read in
 * pieces of up to 320 px, cut between words.
 */
class TextReader(path: String, dictionary: List<String>) {
    private val net: Net = Dnn.readNet(path)
    private val chars = listOf("") + dictionary + listOf(" ")

    fun read(bgr: Mat, q: Quad): String = synchronized(this) {
        val o = q.ordered()
        val wq = o.width; val hq = o.height
        if (hq < 4f || wq < 4f) return ""
        val tw = max(1, (H * wq / hq).roundToInt()).coerceAtMost(W * 12)
        val src = MatOfPoint2f(Point(o.p[0].toDouble(), o.p[1].toDouble()), Point(o.p[2].toDouble(), o.p[3].toDouble()),
            Point(o.p[4].toDouble(), o.p[5].toDouble()), Point(o.p[6].toDouble(), o.p[7].toDouble()))
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(tw - 1.0, 0.0), Point(tw - 1.0, H - 1.0), Point(0.0, H - 1.0))
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val crop = Mat()
        try {
            Imgproc.warpPerspective(bgr, crop, m, Size(tw.toDouble(), H.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            return pieces(crop).joinToString("") { piece -> try { readLine(piece) } finally { if (piece !== crop) piece.release() } }
        } finally { src.release(); dst.release(); m.release(); crop.release() }
    }

    /** Long lines in pieces of at most [W] px, cut where there is least ink (between words). */
    private fun pieces(crop: Mat): List<Mat> {
        val tw = crop.cols()
        if (tw <= W) return listOf(crop)
        val g = Mat(); val b = Mat()
        val ink: FloatArray
        try {
            Imgproc.cvtColor(crop, g, Imgproc.COLOR_BGR2GRAY)
            Imgproc.threshold(g, b, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val px = ByteArray(tw * H); b.get(0, 0, px)
            val on = px.count { it.toInt() != 0 }
            val inkIsOn = on * 2 <= px.size   // ink is the minority
            val col = FloatArray(tw)
            for (y in 0 until H) for (x in 0 until tw) if ((px[y * tw + x].toInt() != 0) == inkIsOn) col[x] += 1f
            ink = FloatArray(tw) { x -> var s = 0f; for (k in -2..2) { val i = x + k; if (i in 0 until tw) s += col[i] }; s / 5 }
        } finally { g.release(); b.release() }
        val out = ArrayList<Mat>()
        var start = 0
        while (tw - start > W) {
            val lo = start + W * 5 / 8; val hi = start + W
            var cut = lo
            for (x in lo until hi) if (ink[x] < ink[cut]) cut = x
            out.add(crop.colRange(start, cut)); start = cut
        }
        out.add(crop.colRange(start, tw))
        return out
    }

    private fun readLine(piece: Mat): String {
        var c = piece
        if (c.cols() > W) { c = Mat(); Imgproc.resize(piece, c, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA) }
        val tw = c.cols()
        val px = ByteArray(tw * H * 3)
        val cont = if (c.isContinuous) c else c.clone()
        cont.get(0, 0, px)
        if (cont !== c) cont.release()
        if (c !== piece) c.release()
        // RGB scaled to -1..1, padded with zeros (mid grey) to the full width, as PaddleOCR does.
        val input = FloatArray(3 * H * W)
        for (y in 0 until H) for (x in 0 until tw) for (ch in 0 until 3) {
            val v = px[(y * tw + x) * 3 + (2 - ch)].toInt() and 255
            input[ch * H * W + y * W + x] = (v / 255f - 0.5f) / 0.5f
        }
        val blob = Mat(intArrayOf(1, 3, H, W), CvType.CV_32F)
        try {
            blob.put(intArrayOf(0, 0, 0, 0), input)
            net.setInput(blob)
            val out = net.forward()
            try {
                val logits = floats(out)
                val n = chars.size
                if (logits.size % n != 0) return ""
                val steps = logits.size / n
                val s = StringBuilder()
                var prev = -1
                for (t in 0 until steps) {
                    var best = 0; var bv = Float.NEGATIVE_INFINITY
                    for (k in 0 until n) { val v = logits[t * n + k]; if (v > bv) { bv = v; best = k } }
                    if (best != 0 && best != prev && best < chars.size) s.append(chars[best])
                    prev = best
                }
                return s.toString()
            } finally { out.release() }
        } finally { blob.release() }
    }

    companion object { const val H = 48; const val W = 320 }
}

/** QR codes and barcodes (OpenCV's detectors). */
class CodeDetector {
    private val qr = QRCodeDetector()
    private val bar = BarcodeDetector()

    fun detect(bgr: Mat): List<Found> = synchronized(this) {
        val out = ArrayList<Found>()
        for (d in listOf(qr, bar)) {
            val pts = Mat()
            try {
                if (d.detectMulti(bgr, pts) && !pts.empty()) {
                    val f = floats(pts)
                    for (i in 0 until f.size / 8) out.add(Found(FindKind.CODE, Quad(f.copyOfRange(i * 8, i * 8 + 8)), 1f))
                }
            } catch (_: Exception) {
                // A detector that fails on a picture finds nothing in it.
            } finally { pts.release() }
        }
        return out
    }
}

/**
 * Finds what to hide besides faces, in a photo or video frame worked on at up to 1600 px:
 *  - licence plates: [PlateDetector] on the whole picture (at up to 1024 px, score 0.5), then again
 *    inside each vehicle (found by the caller, [vehicles] in pixels) enlarged to 960 px (score 0.4),
 *    where any text found counts too (badges, numbers on vans);
 *  - text: lines found by [TextDetector] (at up to 1280 px), all of them or, read by [TextReader], the
 *    ones [PrivateText] says hold private details, and the value next to a label such as "Password:";
 *  - QR codes and barcodes.
 */
class PrivacyFinder(
    private val plates: PlateDetector?,
    private val textDetector: TextDetector?,
    private val reader: TextReader?,
    private val classifier: PrivateText?,
    private val codes: CodeDetector?,
) {
    /**
     * [vehicles]: boxes (x0, y0, x1, y1 in [bgr]'s pixels) of cars, motorcycles, buses and trucks,
     * largest first. [memory]: lines read in earlier frames of the same video.
     */
    fun find(bgr: Mat, opts: PrivacyOptions, vehicles: List<FloatArray> = emptyList(), memory: LineMemory? = null, check: () -> Unit = {}): List<Found> {
        val out = ArrayList<Found>()
        if (opts.plates && plates != null) { out.addAll(platesIn(bgr, vehicles, check)); check() }
        if (opts.text && textDetector != null) {
            val (small, s) = fitMat(bgr, TEXT_SIDE)
            val lines = try { textDetector.detect(small) } finally { if (small !== bgr) small.release() }
            val inPx = lines.map { Found(FindKind.TEXT, it.quad.map(1 / s, 1 / s), it.score) }
            check()
            if (opts.allText) out.addAll(inPx)
            else if (reader != null && classifier != null) out.addAll(privateLines(bgr, inPx, memory, check))
        }
        if (opts.codes && codes != null) out.addAll(codes.detect(bgr))
        return out
    }

    private fun platesIn(bgr: Mat, vehicles: List<FloatArray>, check: () -> Unit): List<Found> {
        val det = plates!!
        val out = ArrayList<Found>()
        val (small, s) = fitMat(bgr, PLATE_SIDE)
        try { det.detect(small, PLATE_SCORE).forEach { out.add(Found(FindKind.PLATE, it.quad.map(1 / s, 1 / s), it.score)) } }
        finally { if (small !== bgr) small.release() }
        val W = bgr.cols(); val H = bgr.rows()
        for (v in vehicles.take(MAX_VEHICLES)) {
            check()
            val gx = (v[2] - v[0]) * 0.08f; val gy = (v[3] - v[1]) * 0.08f
            val x0 = max(0, (v[0] - gx).toInt()); val y0 = max(0, (v[1] - gy).toInt())
            val x1 = min(W, (v[2] + gx).toInt()); val y1 = min(H, (v[3] + gy).toInt())
            if (x1 - x0 < 16 || y1 - y0 < 16) continue
            val cs = min(4f, VEHICLE_SIDE.toFloat() / max(x1 - x0, y1 - y0))
            // A big vehicle at about its own size: the whole-picture pass has looked at it.
            if (cs <= 1.05f && max(x1 - x0, y1 - y0) > 0.6f * max(W, H)) continue
            val crop = bgr.submat(y0, y1, x0, x1)
            val big = Mat()
            try {
                Imgproc.resize(crop, big, Size(max(1, ((x1 - x0) * cs).roundToInt()).toDouble(), max(1, ((y1 - y0) * cs).roundToInt()).toDouble()),
                    0.0, 0.0, if (cs > 1) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA)
                val found = det.detect(big, VEHICLE_PLATE_SCORE) + (textDetector?.detect(big) ?: emptyList())
                found.forEach { out.add(Found(FindKind.PLATE, it.quad.map(1 / cs, 1 / cs, x0.toFloat(), y0.toFloat()), it.score)) }
            } finally { crop.release(); big.release() }
        }
        return out
    }

    /** Lines that hold private details, and the values next to labels ("Password:" → what follows). */
    private fun privateLines(bgr: Mat, lines: List<Found>, memory: LineMemory?, check: () -> Unit): List<Found> {
        val verdicts = ArrayList<Pair<Found, TextVerdict>>()
        val read = ArrayList<String>()
        for (l in lines) {
            check()
            val known = memory?.recall(l.quad)
            val t = known?.first ?: if (l.quad.thickness >= MIN_READ_PX) reader!!.read(bgr, l.quad) else ""
            val v = known?.second ?: classifier!!.classify(t).also { memory?.remember(l.quad, t, it) }
            read.add(t)
            verdicts.add(l to v)
        }
        val hide = HashMap<Int, Set<Detail>>()
        for ((i, v) in verdicts.withIndex()) if (v.second.isPrivate) hide[i] = v.second.details
        for ((i, v) in verdicts.withIndex()) {
            val (l, verdict) = v
            if (!verdict.label) continue
            val kinds = verdict.details.ifEmpty { setOf(Detail.NUMBER) }
            val h = l.quad.thickness
            // The value on the same row, to the right.
            lines.indices.filter { j -> j != i && sameRow(l.quad, lines[j].quad) && lines[j].quad.x0 >= l.quad.cx && lines[j].quad.x0 - l.quad.x1 <= 3 * h }
                .minByOrNull { lines[it].quad.x0 }?.let { hide[it] = (hide[it] ?: emptySet()) + kinds }
            // A label on its own: the value may be under it.
            if (verdict.labelOnly) lines.indices.filter { j ->
                val q = lines[j].quad
                j != i && q.y0 >= l.quad.cy && q.y0 - l.quad.y1 <= 1.5f * h && q.x0 < l.quad.x1 + 2 * h && q.x1 > l.quad.x0 - 2 * h
            }.minByOrNull { lines[it].quad.y0 }?.let { hide[it] = (hide[it] ?: emptySet()) + kinds }
        }
        return hide.entries.sortedBy { it.key }.map { (i, d) -> Found(FindKind.TEXT, lines[i].quad, lines[i].score, read[i], d) }
    }

    private fun sameRow(a: Quad, b: Quad): Boolean {
        val ov = min(a.y1, b.y1) - max(a.y0, b.y0)
        return ov >= 0.5f * min(a.y1 - a.y0, b.y1 - b.y0)
    }

    companion object {
        const val PLATE_SIDE = 1024
        const val PLATE_SCORE = 0.5f
        const val VEHICLE_SIDE = 960
        const val VEHICLE_PLATE_SCORE = 0.4f
        const val MAX_VEHICLES = 8
        const val TEXT_SIDE = 1280
        /** Text thinner than this (in the picture as worked on) is too small to read, or to matter. */
        const val MIN_READ_PX = 6f
        /** COCO classes the vehicle pass looks in: car, motorcycle, bus, truck. */
        val VEHICLE_CLASSES = setOf(2, 3, 5, 7)
        const val VEHICLE_SCORE = 0.3f
    }
}

/**
 * Lines of text read in earlier frames of a video (in the frames' pixels): a line found again at
 * about the same place, no larger, within [holdUs] is not read again.
 */
class LineMemory(private val holdUs: Long) {
    private class Seen(var quad: Quad, val text: String, val verdict: TextVerdict, var tUs: Long)
    private val seen = ArrayList<Seen>()
    /** The time of the frame being looked at. */
    var now = 0L

    fun recall(q: Quad): Pair<String, TextVerdict>? {
        seen.removeAll { now - it.tUs > holdUs }
        val s = seen.filter { it.quad.iou(q) > 0.4f && q.thickness <= it.quad.thickness * 1.3f }.maxByOrNull { it.quad.iou(q) } ?: return null
        s.quad = q; s.tUs = now
        return s.text to s.verdict
    }

    fun remember(q: Quad, text: String, verdict: TextVerdict) { seen.add(Seen(q, text, verdict, now)) }
}

/** A line from (x0, y0) to (x1, y1) with round ends of [radius], and how large the effect should be. */
class Capsule(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val radius: Float, val featurePx: Float)

/** How much of a found thing to hide around it, and how strongly (for text: the letters' height). */
object PrivacyShape {
    /** Margin around found text and plates, as a share of their thickness. */
    const val MARGIN = 0.35f

    /**
     * Blur and pixel size scale with this many times the thickness (photos: [Capsule.featurePx]):
     * blocks of 0.7 to 1.3 times the letters' height, so text can't be read even at Light.
     */
    const val FEATURE = 8f

    /** A capsule over the grown quad: along its longer side, as thick as the shorter one. */
    fun capsule(q: Quad): Capsule {
        val g = grown(q)
        val p = g.p
        val along = g.width >= g.height
        // Middles of the two short sides (corners: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left).
        val (a, b, c, d) = if (along) listOf(0, 3, 1, 2) else listOf(0, 1, 3, 2)
        return Capsule((p[a * 2] + p[b * 2]) / 2, (p[a * 2 + 1] + p[b * 2 + 1]) / 2, (p[c * 2] + p[d * 2]) / 2, (p[c * 2 + 1] + p[d * 2 + 1]) / 2,
            min(g.width, g.height) / 2, FEATURE * q.thickness)
    }

    /** The quad grown by [MARGIN] of its thickness on every side. */
    fun grown(q: Quad): Quad {
        val o = q.ordered()
        val m = o.thickness * MARGIN
        val ux = (o.p[2] - o.p[0]); val uy = (o.p[3] - o.p[1]); val ul = hypot(ux, uy).coerceAtLeast(1e-3f)
        val vx = (o.p[6] - o.p[0]); val vy = (o.p[7] - o.p[1]); val vl = hypot(vx, vy).coerceAtLeast(1e-3f)
        val ax = ux / ul * m; val ay = uy / ul * m; val bx = vx / vl * m; val by = vy / vl * m
        return Quad(floatArrayOf(
            o.p[0] - ax - bx, o.p[1] - ay - by, o.p[2] + ax - bx, o.p[3] + ay - by,
            o.p[4] + ax + bx, o.p[5] + ay + by, o.p[6] - ax + bx, o.p[7] - ay + by))
    }
}
