// Runs the app's auto-enhance (vision/core/AutoEnhance.kt + codec/edit/ColorPipeline.kt) on photo pairs
// from MIT-Adobe FiveK (the as-shot photo and expert C's retouch, made by fivek_extract.py) and scores
// how close the result comes to the expert, as the mean CIEDE2000 colour difference per pixel.
//
//   run.sh eval  <pairs dir> <name prefixes, comma-separated> <yunet.onnx>      app vs. unchanged photo
//   run.sh fit   <pairs dir> <prefixes> <yunet.onnx> <out.csv>                   per photo: what the app
//                measures, and the slider values that come closest to the expert (for fitting the model)
//   run.sh finished <pairs dir> <prefixes> <yunet.onnx> <out.csv>                the experts' versions as
//                photos that need no change (all targets 0), so the model learns to leave good photos be
import com.localmediatools.codec.edit.AdjustKey
import com.localmediatools.codec.edit.Adjustments
import com.localmediatools.codec.edit.ColorPipeline
import com.localmediatools.codec.edit.FilterSpec
import com.localmediatools.vision.core.AutoEnhance
import com.localmediatools.vision.core.FaceBox
import com.localmediatools.vision.core.FaceEngine
import com.localmediatools.vision.core.SceneKind
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class Img(val w: Int, val h: Int, val px: IntArray)

fun load(f: File): Img {
    val b = ImageIO.read(f)
    val px = IntArray(b.width * b.height)
    b.getRGB(0, 0, b.width, b.height, px, 0, b.width)
    return Img(b.width, b.height, px)
}

/** Area-average downscale so the long side is at most [side]. */
fun shrink(im: Img, side: Int): Img {
    val s = maxOf(im.w, im.h).toDouble() / side
    if (s <= 1.0) return im
    val w = (im.w / s).toInt().coerceAtLeast(1); val h = (im.h / s).toInt().coerceAtLeast(1)
    val out = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val x0 = (x * im.w / w); val x1 = maxOf(x0 + 1, (x + 1) * im.w / w)
        val y0 = (y * im.h / h); val y1 = maxOf(y0 + 1, (y + 1) * im.h / h)
        var r = 0; var g = 0; var b = 0; var n = 0
        for (yy in y0 until y1) for (xx in x0 until x1) { val c = im.px[yy * im.w + xx]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ }
        out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }
    return Img(w, h, out)
}

// ------------------------------------------------------------------ CIEDE2000
private val lin = DoubleArray(256) { ColorPipeline.srgbToLinear(it / 255.0) }
private fun f(t: Double) = if (t > 216.0 / 24389) Math.cbrt(t) else (24389.0 / 27 * t + 16) / 116

fun lab(c: Int, out: DoubleArray, o: Int) {
    val r = lin[(c shr 16) and 255]; val g = lin[(c shr 8) and 255]; val b = lin[c and 255]
    val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
    val fx = f(x); val fy = f(y); val fz = f(z)
    out[o] = 116 * fy - 16; out[o + 1] = 500 * (fx - fy); out[o + 2] = 200 * (fy - fz)
}

fun labs(px: IntArray): DoubleArray { val o = DoubleArray(px.size * 3); for (i in px.indices) lab(px[i], o, i * 3); return o }

fun de2000(l1: Double, a1: Double, b1: Double, l2: Double, a2: Double, b2: Double): Double {
    val c1 = sqrt(a1 * a1 + b1 * b1); val c2 = sqrt(a2 * a2 + b2 * b2)
    val cm = (c1 + c2) / 2; val cm7 = cm.pow(7)
    val g = 0.5 * (1 - sqrt(cm7 / (cm7 + 25.0.pow(7))))
    val ap1 = a1 * (1 + g); val ap2 = a2 * (1 + g)
    val cp1 = sqrt(ap1 * ap1 + b1 * b1); val cp2 = sqrt(ap2 * ap2 + b2 * b2)
    fun hue(b: Double, a: Double): Double { if (a == 0.0 && b == 0.0) return 0.0; val h = Math.toDegrees(atan2(b, a)); return if (h < 0) h + 360 else h }
    val hp1 = hue(b1, ap1); val hp2 = hue(b2, ap2)
    val dl = l2 - l1; val dc = cp2 - cp1
    var dh = if (cp1 * cp2 == 0.0) 0.0 else hp2 - hp1
    if (dh > 180) dh -= 360 else if (dh < -180) dh += 360
    val dH = 2 * sqrt(cp1 * cp2) * sin(Math.toRadians(dh / 2))
    val lm = (l1 + l2) / 2; val cpm = (cp1 + cp2) / 2
    var hm = hp1 + hp2
    if (cp1 * cp2 != 0.0) { hm = if (abs(hp1 - hp2) > 180) (if (hp1 + hp2 < 360) (hp1 + hp2 + 360) / 2 else (hp1 + hp2 - 360) / 2) else (hp1 + hp2) / 2 }
    val t = 1 - 0.17 * cos(Math.toRadians(hm - 30)) + 0.24 * cos(Math.toRadians(2 * hm)) + 0.32 * cos(Math.toRadians(3 * hm + 6)) - 0.20 * cos(Math.toRadians(4 * hm - 63))
    val dTheta = 30 * exp(-((hm - 275) / 25).pow(2))
    val cpm7 = cpm.pow(7)
    val rc = 2 * sqrt(cpm7 / (cpm7 + 25.0.pow(7)))
    val sl = 1 + 0.015 * (lm - 50).pow(2) / sqrt(20 + (lm - 50).pow(2))
    val sc = 1 + 0.045 * cpm; val sh = 1 + 0.015 * cpm * t
    val rt = -sin(Math.toRadians(2 * dTheta)) * rc
    return sqrt((dl / sl).pow(2) + (dc / sc).pow(2) + (dH / sh).pow(2) + rt * (dc / sc) * (dH / sh))
}

fun meanDe(px: IntArray, ref: DoubleArray): Double {
    val t = DoubleArray(3); var s = 0.0
    for (i in px.indices) { lab(px[i], t, 0); s += de2000(t[0], t[1], t[2], ref[i * 3], ref[i * 3 + 1], ref[i * 3 + 2]) }
    return s / px.size
}

fun render(im: Img, a: Adjustments): IntArray {
    val out = im.px.copyOf()
    // Colour only: sharpening is a matter of detail, not of the colours compared here.
    ColorPipeline(a.with(AdjustKey.SHARPNESS, 0f), FilterSpec()).apply(out, 0, im.w, im.h, 0, im.w, im.h)
    return out
}

// ------------------------------------------------------------------ inputs
class Pair(val name: String, val input: Img, val expert: Img, val faces: List<FaceBox>, val scene: SceneKind, val meta: Map<String, String>)

fun metaRows(dir: File, prefix: String): List<Map<String, String>> {
    // A tiny reader for fivek_extract.py's list of flat objects.
    val text = File(dir, prefix + "meta.json").readText()
    return Regex("\\{([^{}]*)\\}").findAll(text).map { m ->
        Regex("\"(\\w+)\":\\s*(\"[^\"]*\"|[-\\d.]+)").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
    }.toList()
}

/** What the app's scene classifier would mostly say, from FiveK's labels (subject 2 = nature, 4 = animals, 1 = man-made; time 3 = night). */
fun sceneOf(m: Map<String, String>): SceneKind = when {
    m["time"] == "3" -> SceneKind.NIGHT
    m["subject"] == "2" -> SceneKind.LANDSCAPE
    m["subject"] == "4" -> SceneKind.ANIMAL
    m["subject"] == "1" && m["location"] == "0" -> SceneKind.CITY
    else -> SceneKind.GENERAL
}

fun pairs(dir: File, prefixes: List<String>, yunet: String): List<Pair> {
    nu.pattern.OpenCV.loadLocally()
    val engine = FaceEngine(yunet, null as String?)
    val out = ArrayList<Pair>()
    for (p in prefixes) for (m in metaRows(dir, p)) {
        val name = m.getValue("name")
        val input = load(File(dir, name + "_in.png")); val expert = load(File(dir, name + "_ex.png"))
        if (input.w != expert.w || input.h != expert.h) continue
        val bgr = ByteArray(input.w * input.h * 3)
        for (i in input.px.indices) { val c = input.px[i]; bgr[i * 3] = c.toByte(); bgr[i * 3 + 1] = (c shr 8).toByte(); bgr[i * 3 + 2] = (c shr 16).toByte() }
        val mat = Mat(input.h, input.w, CvType.CV_8UC3); mat.put(0, 0, bgr)
        val faces = engine.detect(mat).map { FaceBox(it.x / input.w, it.y / input.h, it.w / input.w, it.h / input.h) }
        mat.release()
        out.add(Pair(name, input, expert, faces, sceneOf(m), m))
    }
    return out
}

fun analyze(p: Pair, strength: Float = 1f) = shrink(p.input, 384).let { s -> AutoEnhance.analyze(s.px, s.w, s.h, p.faces, p.scene, strength) }

// ------------------------------------------------------------------ fitting the sliders to the expert
val FIT_KEYS = listOf(AdjustKey.EXPOSURE, AdjustKey.BRIGHTNESS, AdjustKey.CONTRAST, AdjustKey.HIGHLIGHTS, AdjustKey.SHADOWS,
    AdjustKey.WARMTH, AdjustKey.TINT, AdjustKey.VIBRANCE, AdjustKey.SATURATION)

/** Coordinate descent on the sliders: the settings whose result is closest to the expert's (mean ΔE00). */
fun bestSliders(input: Img, ref: DoubleArray): Pair2 {
    var cur = Adjustments()
    var best = meanDe(render(input, cur), ref)
    var step = 0.25f
    while (step > 0.01f) {
        var improved = true
        while (improved) {
            improved = false
            for (k in FIT_KEYS) for (dir in floatArrayOf(1f, -1f)) {
                val v = (cur[k] + dir * step).coerceIn(-1f, 1f)
                if (v == cur[k]) continue
                val cand = cur.with(k, v)
                val d = meanDe(render(input, cand), ref)
                if (d < best - 1e-4) { best = d; cur = cand; improved = true }
            }
        }
        step /= 2
    }
    return Pair2(cur, best)
}
class Pair2(val adjust: Adjustments, val de: Double)

fun main(args: Array<String>) {
    val dir = File(args[1]); val prefixes = args[2].split(','); val yunet = args[3]
    val ps = pairs(dir, prefixes, yunet)
    when (args[0]) {
        "eval" -> {
            // The unchanged photo, 1.8's hand-written rules and the learned model, against the expert.
            val sum = DoubleArray(3); val idem = DoubleArray(2); var better = 0; var worse = 0
            val byScene = HashMap<SceneKind, DoubleArray>()
            for (p in ps) {
                val ref = labs(p.expert.px)
                val none = meanDe(p.input.px, ref)
                AutoEnhance.learned = false
                val rules = meanDe(render(p.input, analyze(p).adjust), ref)
                AutoEnhance.learned = true
                val r = analyze(p)
                val model = meanDe(render(p.input, r.adjust), ref)
                sum[0] += none; sum[1] += rules; sum[2] += model
                // Already finished: the expert's own version should be left (nearly) as it is.
                val done = Pair(p.name, p.expert, p.expert, p.faces, p.scene, p.meta)
                AutoEnhance.learned = false
                idem[0] += meanDe(render(p.expert, analyze(done).adjust), ref)
                AutoEnhance.learned = true
                idem[1] += meanDe(render(p.expert, analyze(done).adjust), ref)
                if (model < rules - 0.25) better++ else if (model > rules + 0.25) worse++
                byScene.getOrPut(r.scene) { DoubleArray(4) }.let { it[0] += none; it[1] += rules; it[2] += model; it[3]++ }
                if (args.size > 4) {
                    fun meanL(px: IntArray): Double { val t = DoubleArray(3); var s = 0.0; for (c in px) { lab(c, t, 0); s += t[0] }; return s / px.size }
                    println("${p.name}\t${r.scene}\t${"%.2f".format(none)}\t${"%.2f".format(rules)}\t${"%.2f".format(model)}\tL ${"%.1f".format(meanL(p.input.px))} ${"%.1f".format(meanL(render(p.input, r.adjust)))} ${"%.1f".format(meanL(p.expert.px))}\t${r.adjust.values.entries.joinToString(" ") { "${it.key.name.lowercase()}=${"%.2f".format(it.value)}" }}")
                }
            }
            val n = ps.size
            println("photos $n   ΔE00 to the expert: unchanged ${"%.3f".format(sum[0] / n)}   rules (1.8) ${"%.3f".format(sum[1] / n)}   model ${"%.3f".format(sum[2] / n)}" +
                "   model closer by >0.25: $better, further: $worse")
            println("expert's versions enhanced again (change, ΔE00): rules ${"%.3f".format(idem[0] / n)}   model ${"%.3f".format(idem[1] / n)}")
            for ((k, v) in byScene.entries.sortedBy { it.key.ordinal }) println("  ${k.name.padEnd(9)} ${v[3].toInt().toString().padStart(4)}   unchanged ${"%.2f".format(v[0] / v[3])}   rules ${"%.2f".format(v[1] / v[3])}   model ${"%.2f".format(v[2] / v[3])}")
        }
        "restat" -> {
            // Rows of an earlier fit/finished CSV ([args] 4) with what AutoEnhance.measure sees now (after it measures more).
            val byName = ps.associateBy { it.name }
            val lines = File(args[4]).readLines()
            val head = lines.first().split(',')
            val keep = head.indexOf("p1")
            File(args[5]).printWriter().use { w ->
                w.println(head.take(keep).joinToString(",") + "," + AutoEnhance.Stats.HEADER)
                for (line in lines.drop(1)) {
                    val cols = line.split(',')
                    val name = cols[0].removeSuffix("_ex")
                    val p = byName[name] ?: continue
                    val im = shrink(if (cols[0].endsWith("_ex")) p.expert else p.input, 384)
                    w.println(cols.take(keep).joinToString(",") + "," + AutoEnhance.measure(im.px, im.w, im.h, p.faces).csv())
                }
            }
        }
        "finished" -> {
            // The experts' versions as photos that need nothing: every slider's target is 0.
            File(args[4]).printWriter().use { w ->
                w.println("name,scene,faces,de_none,de_app,de_best," + FIT_KEYS.joinToString(",") { "best_" + it.name.lowercase() } + "," + AutoEnhance.Stats.HEADER)
                for (p in ps) {
                    val e384 = shrink(p.expert, 384)
                    val se = AutoEnhance.measure(e384.px, e384.w, e384.h, p.faces)
                    w.println("${p.name}_ex,${p.scene},${p.faces.size},0,0,0," + FIT_KEYS.joinToString(",") { "0" } + "," + se.csv())
                }
            }
        }
        "fit" -> {
            File(args[4]).printWriter().use { w ->
                w.println("name,scene,faces,de_none,de_app,de_best," + FIT_KEYS.joinToString(",") { "best_" + it.name.lowercase() } + "," + AutoEnhance.Stats.HEADER)
                for (p in ps) {
                    val small = shrink(p.input, 160); val ref = labs(shrink(p.expert, 160).px)
                    val b = bestSliders(small, ref)
                    val s384 = shrink(p.input, 384)
                    val st = AutoEnhance.measure(s384.px, s384.w, s384.h, p.faces)
                    val app = meanDe(render(small, analyze(p).adjust), ref)
                    w.println("${p.name},${p.scene},${p.faces.size},${"%.3f".format(meanDe(small.px, ref))},${"%.3f".format(app)},${"%.3f".format(b.de)}," +
                        FIT_KEYS.joinToString(",") { "%.3f".format(b.adjust[it]) } + "," + st.csv())
                    System.err.print(".")
                }
            }
            System.err.println()
        }
    }
}
