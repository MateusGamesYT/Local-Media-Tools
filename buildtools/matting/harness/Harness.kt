// Runs the app's subject cut-out (vision/core/Matting.kt: U²-Net-p, then the edge refinement) on AIM-500
// (natural photos with exact alpha mattes, made by aim_extract.py) and scores each variant of the
// pipeline: SAD (sum of absolute alpha errors / 1000) and MSE (× 1000), at the app's working size.
//
//   run.sh <aim dir> <u2netp.onnx> <variants, comma-separated> [even|odd|all] [at most this many photos]
import com.localmediatools.vision.core.Matting
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Sample(val name: String, val category: String, val kind: String, val w: Int, val h: Int, val px: IntArray, val alpha: FloatArray)

fun load(dir: File, name: String, category: String, kind: String): Sample {
    val im = ImageIO.read(File(dir, "$name.jpg")); val al = ImageIO.read(File(dir, "${name}_a.png"))
    val w = im.width; val h = im.height
    val px = IntArray(w * h); im.getRGB(0, 0, w, h, px, 0, w)
    val a = FloatArray(w * h) { i -> (al.raster.getSample(i % w, i / w, 0)) / 255f }
    return Sample(name, category, kind, w, h, px, a)
}

fun rgbMat(px: IntArray, w: Int, h: Int, flip: Boolean = false): Mat {
    val b = ByteArray(w * h * 3)
    for (y in 0 until h) for (x in 0 until w) {
        val c = px[y * w + (if (flip) w - 1 - x else x)]; val o = (y * w + x) * 3
        b[o] = (c shr 16).toByte(); b[o + 1] = (c shr 8).toByte(); b[o + 2] = c.toByte()
    }
    return Mat(h, w, CvType.CV_8UC3).also { it.put(0, 0, b) }
}

fun crop(px: IntArray, w: Int, l: Int, t: Int, cw: Int, ch: Int) = IntArray(cw * ch) { i -> px[(t + i / cw) * w + l + i % cw] }

/** The model's prediction at [Matting.SIZE]², optionally averaged with the mirrored photo's. */
fun predict(m: Matting, px: IntArray, w: Int, h: Int, flip: Boolean): FloatArray {
    val a = rgbMat(px, w, h).let { mat -> m.predict(mat).also { mat.release() } }
    if (!flip) return a
    val b = rgbMat(px, w, h, flip = true).let { mat -> m.predict(mat).also { mat.release() } }
    val s = Matting.SIZE
    return FloatArray(s * s) { i -> (a[i] + b[(i / s) * s + (s - 1 - i % s)]) / 2 }
}

fun luma(px: IntArray) = FloatArray(px.size) { i -> val c = px[i]; (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f }

/** The coarse mask at the photo's size; with [zoom], the subject is looked at again, cropped and larger. */
fun coarse(m: Matting, s: Sample, flip: Boolean, zoom: Boolean): FloatArray {
    val first = Matting.resize(predict(m, s.px, s.w, s.h, flip), Matting.SIZE, Matting.SIZE, s.w, s.h)
    if (!zoom) return first
    val box = Matting.bounds(first, s.w, s.h, 0.25f) ?: return first
    val bw = box[2] - box[0]; val bh = box[3] - box[1]
    val mx = (bw * 0.12f).roundToInt() + 8; val my = (bh * 0.12f).roundToInt() + 8
    val l = max(0, box[0] - mx); val t = max(0, box[1] - my); val r = min(s.w, box[2] + mx); val b = min(s.h, box[3] + my)
    val cw = r - l; val ch = b - t
    // Only worth it when the subject is clearly smaller than the photo.
    if (cw * ch > 0.7 * s.w * s.h || cw < 16 || ch < 16) return first
    val second = Matting.resize(predict(m, crop(s.px, s.w, l, t, cw, ch), cw, ch, flip), Matting.SIZE, Matting.SIZE, cw, ch)
    val out = first.copyOf()
    // Inside the crop the closer look wins, blended over a band at the crop's inner edges.
    val band = max(4, min(cw, ch) / 20)
    for (y in 0 until ch) for (x in 0 until cw) {
        val d = min(min(x, cw - 1 - x), min(y, ch - 1 - y))
        val edgeOfPhoto = (x == 0 && l == 0) || (y == 0 && t == 0) || (x == cw - 1 && r == s.w) || (y == ch - 1 && b == s.h)
        val k = if (edgeOfPhoto) 1f else min(1f, d.toFloat() / band)
        val i = (t + y) * s.w + l + x
        out[i] = first[i] * (1 - k) + second[y * cw + x] * k
    }
    return out
}

// ------------------------------------------------------------------ colour guided filter (He et al.)
fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(w * h); val out = FloatArray(w * h); val n = (2 * r + 1).toFloat()
    for (y in 0 until h) { var s = 0f; for (i in -r..r) s += src[y * w + i.coerceIn(0, w - 1)]
        for (x in 0 until w) { tmp[y * w + x] = s / n; s += src[y * w + (x + r + 1).coerceAtMost(w - 1)] - src[y * w + (x - r).coerceAtLeast(0)] } }
    for (x in 0 until w) { var s = 0f; for (i in -r..r) s += tmp[i.coerceIn(0, h - 1) * w + x]
        for (y in 0 until h) { out[y * w + x] = s / n; s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x] } }
    return out
}

fun colourGuided(px: IntArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
    val n = w * h
    val I = Array(3) { c -> FloatArray(n) { i -> ((px[i] shr (16 - 8 * c)) and 255) / 255f } }
    val mI = Array(3) { box(I[it], w, h, r) }
    val mp = box(p, w, h, r)
    val mIp = Array(3) { c -> box(FloatArray(n) { I[c][it] * p[it] }, w, h, r) }
    fun cov(a: Int, b: Int) = box(FloatArray(n) { I[a][it] * I[b][it] }, w, h, r).also { m -> for (i in 0 until n) m[i] -= mI[a][i] * mI[b][i] }
    val vrr = cov(0, 0); val vrg = cov(0, 1); val vrb = cov(0, 2); val vgg = cov(1, 1); val vgb = cov(1, 2); val vbb = cov(2, 2)
    val ar = FloatArray(n); val ag = FloatArray(n); val ab = FloatArray(n); val bb = FloatArray(n)
    for (i in 0 until n) {
        val cr = mIp[0][i] - mI[0][i] * mp[i]; val cg = mIp[1][i] - mI[1][i] * mp[i]; val cb = mIp[2][i] - mI[2][i] * mp[i]
        val a11 = vrr[i] + eps; val a12 = vrg[i]; val a13 = vrb[i]; val a22 = vgg[i] + eps; val a23 = vgb[i]; val a33 = vbb[i] + eps
        // Inverse of the symmetric 3×3 covariance.
        val c11 = a22 * a33 - a23 * a23; val c12 = a13 * a23 - a12 * a33; val c13 = a12 * a23 - a13 * a22
        val c22 = a11 * a33 - a13 * a13; val c23 = a12 * a13 - a11 * a23; val c33 = a11 * a22 - a12 * a12
        val det = a11 * c11 + a12 * c12 + a13 * c13
        ar[i] = (c11 * cr + c12 * cg + c13 * cb) / det; ag[i] = (c12 * cr + c22 * cg + c23 * cb) / det; ab[i] = (c13 * cr + c23 * cg + c33 * cb) / det
        bb[i] = mp[i] - ar[i] * mI[0][i] - ag[i] * mI[1][i] - ab[i] * mI[2][i]
    }
    val mar = box(ar, w, h, r); val mag = box(ag, w, h, r); val mab = box(ab, w, h, r); val mbb = box(bb, w, h, r)
    return FloatArray(n) { i -> mar[i] * I[0][i] + mag[i] * I[1][i] + mab[i] * I[2][i] + mbb[i] }
}

fun curve(q: Float, lo: Float, hi: Float): Float { val t = ((q.coerceIn(0f, 1f) - lo) / (hi - lo)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

/** One variant of the pipeline, by name: "base" (1.8), "app", or flip, zoom, colour, curve "c<lo>-<hi>", radius "r<per mille>", eps "e<value>" joined by "+". */
fun run(m: Matting, s: Sample, variant: String): FloatArray {
    // "app": Matting.cutout, exactly as the app runs it (1.9: flip+zoom+r36).
    if (variant == "app") return m.cutout(s.px, s.w, s.h)
    val parts = variant.split('+')
    val c = coarse(m, s, "flip" in parts, "zoom" in parts)
    val rPm = parts.firstOrNull { it.startsWith("r") && it.drop(1).toIntOrNull() != null }?.drop(1)?.toInt() ?: 12
    val r = max(4, (min(s.w, s.h) * rPm / 1000f).roundToInt())
    val eps = parts.firstOrNull { it.startsWith("e") && it.drop(1).toFloatOrNull() != null }?.drop(1)?.toFloat() ?: 1e-3f
    val cv = parts.firstOrNull { it.startsWith("c") && it.contains('-') && it.drop(1).split('-').all { x -> x.toFloatOrNull() != null } }?.drop(1)?.split('-')?.map { it.toFloat() }
    if ("colour" !in parts && cv == null && rPm == 12 && eps == 1e-3f) return Matting.refine(luma(s.px), c, s.w, s.h, r)
    val q = if ("colour" in parts) colourGuided(s.px, c, s.w, s.h, r, eps) else {
        // The app's luma filter, without its curve.
        val g = luma(s.px); val n = s.w * s.h
        val mI = box(g, s.w, s.h, r); val mp = box(c, s.w, s.h, r)
        val mIp = box(FloatArray(n) { g[it] * c[it] }, s.w, s.h, r); val mII = box(FloatArray(n) { g[it] * g[it] }, s.w, s.h, r)
        val a = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) { val cov = mIp[i] - mI[i] * mp[i]; val v = mII[i] - mI[i] * mI[i]; a[i] = cov / (v + eps); b[i] = mp[i] - a[i] * mI[i] }
        val ma = box(a, s.w, s.h, r); val mb = box(b, s.w, s.h, r)
        FloatArray(n) { i -> ma[i] * g[i] + mb[i] }
    }
    val lo = cv?.get(0) ?: 0.2f; val hi = cv?.get(1) ?: 0.8f
    return FloatArray(q.size) { curve(q[it], lo, hi) }
}

fun main(args: Array<String>) {
    nu.pattern.OpenCV.loadLocally()
    val dir = File(args[0]); val m = Matting(args[1]); val variants = args[2].split(',')
    val which = args.getOrNull(3) ?: "all"
    val limit = args.getOrNull(4)?.toInt() ?: Int.MAX_VALUE
    val list = File(dir, "list.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        .filter { which == "all" || (it[0].toInt() % 2 == 0) == (which == "even") }.take(limit)
    val nanos = HashMap<String, Long>()
    val sad = HashMap<String, Double>(); val mse = HashMap<String, Double>()
    val byCat = HashMap<String, HashMap<String, DoubleArray>>()
    for ((k, row) in list.withIndex()) {
        val s = load(dir, row[1], row[2], row[3])
        for (v in variants) {
            val t0 = System.nanoTime()
            val a = run(m, s, v)
            nanos[v] = (nanos[v] ?: 0L) + (System.nanoTime() - t0)
            var e = 0.0; var e2 = 0.0
            for (i in a.indices) { val d = abs(a[i] - s.alpha[i]).toDouble(); e += d; e2 += d * d }
            sad[v] = (sad[v] ?: 0.0) + e / 1000; mse[v] = (mse[v] ?: 0.0) + e2 / a.size * 1000
            byCat.getOrPut(v) { HashMap() }.getOrPut(s.category) { DoubleArray(2) }.let { it[0] += e / 1000; it[1]++ }
        }
        if (k % 50 == 49) System.err.println("${k + 1}/${list.size}")
    }
    val n = list.size
    for (v in variants) {
        println("${v.padEnd(28)} ${nanos.getValue(v) / n / 1_000_000} ms   SAD ${"%.2f".format(sad.getValue(v) / n)}   MSE ${"%.2f".format(mse.getValue(v) / n)}   " +
            byCat.getValue(v).entries.sortedBy { it.key }.joinToString("  ") { "${it.key} ${"%.1f".format(it.value[0] / it.value[1])}" })
    }
}
