package com.localmediatools.stitch.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Maps between output-canvas pixels and full-resolution source pixels. */
interface Warp {
    /** Output (x, y) → image [i] full-res (u, v). False when the point isn't seen by image i. */
    fun outToFull(i: Int, x: Double, y: Double, out: DoubleArray): Boolean
    /** Image [i] full-res (u, v) → output (x, y). */
    fun fullToOut(i: Int, u: Double, v: Double, out: DoubleArray): Boolean
    /** Uniformly rescales the output and shifts its origin (used for bounds and size limits). */
    fun withOutputTransform(scale: Double, offX: Double, offY: Double): Warp
    val description: String
}

/** Planar model: image i work coords --H_i--> reference plane (ref work pixels). */
class PlanarWarp(
    private val h: Array<DoubleArray>,
    private val hInv: Array<DoubleArray>,
    private val workScale: DoubleArray,
    private val k: Double,       // output px per plane unit
    private val ox: Double,      // plane origin of output (0,0)
    private val oy: Double,
    override val description: String,
) : Warp {
    private val tmp = ThreadLocal.withInitial { DoubleArray(2) }

    override fun outToFull(i: Int, x: Double, y: Double, out: DoubleArray): Boolean {
        val px = x / k + ox; val py = y / k + oy
        if (!M3.project(hInv[i], px, py, out)) return false
        out[0] /= workScale[i]; out[1] /= workScale[i]
        return true
    }

    override fun fullToOut(i: Int, u: Double, v: Double, out: DoubleArray): Boolean {
        if (!M3.project(h[i], u * workScale[i], v * workScale[i], out)) return false
        out[0] = (out[0] - ox) * k; out[1] = (out[1] - oy) * k
        return true
    }

    override fun withOutputTransform(scale: Double, offX: Double, offY: Double): Warp =
        PlanarWarp(h, hInv, workScale, k * scale, ox + offX / k, oy + offY / k, description)
}

enum class Surface { PLANE, CYLINDER, SPHERE }

/** Rotating-camera model: each image has rotation R_i (world → camera) and shared focal length. */
class RotationWarp(
    private val r: Array<DoubleArray>,
    private val fFull: Double,          // focal length in full-res pixels
    private val centers: Array<DoubleArray>, // principal point (full-res) per image
    private val surface: Surface,
    private val scale: Double,          // output px per radian (or per unit on the plane)
    private val ox: Double,
    private val oy: Double,
) : Warp {
    override val description: String
        get() = when (surface) { Surface.PLANE -> "panorama (rectilinear)"; Surface.CYLINDER -> "panorama (cylindrical)"; Surface.SPHERE -> "panorama (spherical)" }

    override fun outToFull(i: Int, x: Double, y: Double, out: DoubleArray): Boolean {
        val a = x / scale + ox; val b = y / scale + oy
        val wx: Double; val wy: Double; val wz: Double
        when (surface) {
            Surface.PLANE -> { wx = a; wy = b; wz = 1.0 }
            Surface.CYLINDER -> { wx = sin(a); wy = b; wz = cos(a) }
            Surface.SPHERE -> { wx = sin(a) * cos(b); wy = sin(b); wz = cos(a) * cos(b) }
        }
        val m = r[i]
        val cx = m[0] * wx + m[1] * wy + m[2] * wz
        val cy = m[3] * wx + m[4] * wy + m[5] * wz
        val cz = m[6] * wx + m[7] * wy + m[8] * wz
        if (cz <= 1e-6) return false
        out[0] = fFull * cx / cz + centers[i][0]
        out[1] = fFull * cy / cz + centers[i][1]
        return true
    }

    override fun fullToOut(i: Int, u: Double, v: Double, out: DoubleArray): Boolean {
        val cx = (u - centers[i][0]) / fFull; val cy = (v - centers[i][1]) / fFull
        val m = r[i]
        // world = R^T * cam
        val wx = m[0] * cx + m[3] * cy + m[6]
        val wy = m[1] * cx + m[4] * cy + m[7]
        val wz = m[2] * cx + m[5] * cy + m[8]
        val a: Double; val b: Double
        when (surface) {
            Surface.PLANE -> { if (wz <= 1e-6) return false; a = wx / wz; b = wy / wz }
            Surface.CYLINDER -> { a = atan2(wx, wz); b = wy / sqrt(wx * wx + wz * wz) }
            Surface.SPHERE -> { val n = sqrt(wx * wx + wy * wy + wz * wz); a = atan2(wx, wz); b = asin((wy / n).coerceIn(-1.0, 1.0)) }
        }
        out[0] = (a - ox) * scale; out[1] = (b - oy) * scale
        return true
    }

    override fun withOutputTransform(scale: Double, offX: Double, offY: Double): Warp =
        RotationWarp(r, fFull, centers, surface, this.scale * scale, ox + offX / this.scale, oy + offY / this.scale)
}

/**
 * Global alignment: chooses the motion model (flat similarity/affine, perspective homographies or
 * rotating-camera panorama) and solves all transforms jointly from the verified pairwise links.
 */
class GlobalAligner(
    private val images: List<WorkImage>,
    private val fullSizes: List<IntArray>,
    private val focal35: DoubleArray,
    private val monitor: StitchMonitor,
) {
    val notes = ArrayList<String>()

    class Graph(val component: List<Int>, val reference: Int, val parent: IntArray, val order: List<Int>, val links: List<PairLink>)

    /** Maximum spanning tree over link weights; keeps the largest connected component. */
    fun graph(links: List<PairLink>): Graph {
        val n = images.size
        val uf = IntArray(n) { it }
        fun find(x: Int): Int { var a = x; while (uf[a] != a) { uf[a] = uf[uf[a]]; a = uf[a] }; return a }
        val tree = ArrayList<PairLink>()
        for (l in links.sortedByDescending { it.inliers * it.confidence.coerceAtMost(3.0) }) {
            val a = find(l.i); val b = find(l.j)
            if (a != b) { uf[a] = b; tree.add(l) }
        }
        val groups = (0 until n).groupBy { find(it) }
        val comp = groups.values.maxByOrNull { it.size } ?: listOf(0)
        val compSet = comp.toSet()
        val compLinks = links.filter { it.i in compSet && it.j in compSet }
        // Reference: the image with the most total inliers (usually central).
        val weight = DoubleArray(n)
        for (l in compLinks) { weight[l.i] += l.inliers.toDouble(); weight[l.j] += l.inliers.toDouble() }
        val ref = comp.maxByOrNull { weight[it] } ?: comp[0]
        val parent = IntArray(n) { -1 }
        val order = ArrayList<Int>()
        val adj = HashMap<Int, MutableList<Int>>()
        for (l in tree) if (l.i in compSet) {
            adj.getOrPut(l.i) { ArrayList() }.add(l.j); adj.getOrPut(l.j) { ArrayList() }.add(l.i)
        }
        val queue = ArrayDeque<Int>()
        queue.add(ref); parent[ref] = ref
        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            order.add(x)
            for (y in adj[x] ?: emptyList()) if (parent[y] == -1) { parent[y] = x; queue.add(y) }
        }
        return Graph(order, ref, parent, order, compLinks)
    }

    private fun linkH(links: List<PairLink>, from: Int, to: Int): DoubleArray? {
        links.firstOrNull { it.i == from && it.j == to }?.let { return it.h }
        links.firstOrNull { it.i == to && it.j == from }?.let { return M3.inverse(it.h)?.let { h -> M3.normalize(h) } }
        return null
    }

    /** Chained homographies to the reference plane along the spanning tree. */
    fun chainHomographies(g: Graph): Array<DoubleArray> {
        val h = Array(images.size) { M3.identity() }
        for (x in g.order) {
            if (x == g.reference) continue
            val p = g.parent[x]
            val toParent = linkH(g.links, x, p) ?: error("missing link")
            h[x] = M3.normalize(M3.mul(h[p], toParent))
        }
        return h
    }

    private fun sampled(l: PairLink, max: Int): FloatArray {
        val n = l.inliers
        if (n <= max) return l.corr
        val out = FloatArray(max * 4)
        for (k in 0 until max) {
            val src = (k.toLong() * n / max).toInt()
            System.arraycopy(l.corr, src * 4, out, k * 4, 4)
        }
        return out
    }

    // ------------------------------------------------------------------ flat (similarity / affine)
    /** Linear least squares for similarity (4 dof) or affine (6 dof) transforms to the reference. */
    fun solveFlat(g: Graph, affine: Boolean): Pair<Array<DoubleArray>, Double>? {
        val nodes = g.component.filter { it != g.reference }
        val dof = if (affine) 6 else 4
        val idx = HashMap<Int, Int>()
        nodes.forEachIndexed { k, v -> idx[v] = k * dof }
        val n = nodes.size * dof
        if (n == 0) return arrayOf(M3.identity()) to 0.0
        val ata = Array(n) { DoubleArray(n) }
        val atb = DoubleArray(n)
        val row = DoubleArray(n)
        fun coeffs(img: Int, x: Double, y: Double, comp: Int, sign: Double, base: DoubleArray): Double {
            // Returns constant term contribution for the reference image (identity).
            if (img == g.reference) return sign * (if (comp == 0) x else y)
            val o = idx[img]!!
            if (affine) {
                if (comp == 0) { base[o] += sign * x; base[o + 1] += sign * y; base[o + 2] += sign }
                else { base[o + 3] += sign * x; base[o + 4] += sign * y; base[o + 5] += sign }
            } else {
                // [a -b tx; b a ty]
                if (comp == 0) { base[o] += sign * x; base[o + 1] += -sign * y; base[o + 2] += sign }
                else { base[o] += sign * y; base[o + 1] += sign * x; base[o + 3] += sign }
            }
            return 0.0
        }
        var eqs = 0
        for (l in g.links) {
            val c = sampled(l, 120)
            var k = 0
            while (k < c.size) {
                for (comp in 0..1) {
                    java.util.Arrays.fill(row, 0.0)
                    var rhs = 0.0
                    rhs -= coeffs(l.i, c[k].toDouble(), c[k + 1].toDouble(), comp, 1.0, row)
                    rhs -= coeffs(l.j, c[k + 2].toDouble(), c[k + 3].toDouble(), comp, -1.0, row)
                    for (a in 0 until n) if (row[a] != 0.0) {
                        atb[a] += row[a] * rhs
                        for (b in 0 until n) if (row[b] != 0.0) ata[a][b] += row[a] * row[b]
                    }
                    eqs++
                }
                k += 4
            }
        }
        for (a in 0 until n) ata[a][a] += 1e-9
        val x = Dense.solve(ata, atb) ?: return null
        val hs = Array(images.size) { M3.identity() }
        for (v in nodes) {
            val o = idx[v]!!
            hs[v] = if (affine) doubleArrayOf(x[o], x[o + 1], x[o + 2], x[o + 3], x[o + 4], x[o + 5], 0.0, 0.0, 1.0)
            else doubleArrayOf(x[o], -x[o + 1], x[o + 2], x[o + 1], x[o], x[o + 3], 0.0, 0.0, 1.0)
        }
        return hs to rmsPlanar(g, hs)
    }

    fun rmsPlanar(g: Graph, hs: Array<DoubleArray>): Double {
        var se = 0.0; var n = 0
        val p = DoubleArray(2); val q = DoubleArray(2)
        for (l in g.links) {
            val c = sampled(l, 200)
            var k = 0
            while (k < c.size) {
                if (M3.project(hs[l.i], c[k].toDouble(), c[k + 1].toDouble(), p) && M3.project(hs[l.j], c[k + 2].toDouble(), c[k + 3].toDouble(), q)) {
                    se += (p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]); n++
                } else { se += 1e6; n++ }
                k += 4
            }
        }
        return if (n == 0) 0.0 else sqrt(se / n)
    }

    // ------------------------------------------------------------------ perspective (homographies)
    fun refineHomographies(g: Graph, init: Array<DoubleArray>): Pair<Array<DoubleArray>, Double> {
        val nodes = g.component.filter { it != g.reference }
        if (nodes.isEmpty()) return init to 0.0
        val idx = HashMap<Int, Int>(); nodes.forEachIndexed { k, v -> idx[v] = k * 8 }
        val samples = g.links.map { sampled(it, 60) }
        val resCount = samples.sumOf { it.size / 4 } * 2
        fun hOf(p: DoubleArray, img: Int): DoubleArray {
            if (img == g.reference) return init[img]
            val o = idx[img]!!
            return doubleArrayOf(p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], p[o + 6], p[o + 7], 1.0)
        }
        val start = DoubleArray(nodes.size * 8)
        for (v in nodes) { val h = M3.normalize(init[v]); System.arraycopy(h, 0, start, idx[v]!!, 8) }
        val steps = DoubleArray(start.size) { k -> if (k % 8 == 6 || k % 8 == 7) 1e-7 else if (k % 8 == 2 || k % 8 == 5) 1e-2 else 1e-5 }
        val pa = DoubleArray(2); val pb = DoubleArray(2)
        val lm = LevenbergMarquardt({ p, out ->
            var r = 0
            for ((li, l) in g.links.withIndex()) {
                val hi = hOf(p, l.i); val hj = hOf(p, l.j)
                val c = samples[li]
                var k = 0
                while (k < c.size) {
                    val okA = M3.project(hi, c[k].toDouble(), c[k + 1].toDouble(), pa)
                    val okB = M3.project(hj, c[k + 2].toDouble(), c[k + 3].toDouble(), pb)
                    if (okA && okB) { out[r] = pa[0] - pb[0]; out[r + 1] = pa[1] - pb[1] } else { out[r] = 1e3; out[r + 1] = 1e3 }
                    r += 2; k += 4
                }
            }
        }, resCount, 40)
        val best = lm.optimize(start, steps) { monitor.checkpoint() }
        val hs = Array(images.size) { M3.identity() }
        for (v in g.component) hs[v] = hOf(best, v)
        return hs to rmsPlanar(g, hs)
    }

    // ------------------------------------------------------------------ rotation model
    class RotationSolution(val r: Array<DoubleArray>, val fFull: Double, val rms: Double)

    /** Principal points in work pixels, focal shared in full-res pixels. */
    fun solveRotation(g: Graph): RotationSolution? {
        val nodes = g.component
        if (nodes.size < 2) return null
        val ws = DoubleArray(images.size) { images[it].scale }
        val cxw = DoubleArray(images.size) { images[it].w / 2.0 }
        val cyw = DoubleArray(images.size) { images[it].h / 2.0 }
        // 1D search for the focal length that makes pairwise homographies most rotation-like.
        val maxDimFull = nodes.maxOf { max(fullSizes[it][0], fullSizes[it][1]) }.toDouble()
        fun kMat(img: Int, fFull: Double) = doubleArrayOf(fFull * ws[img], 0.0, cxw[img], 0.0, fFull * ws[img], cyw[img], 0.0, 0.0, 1.0)
        fun rotError(fFull: Double): Double {
            var e = 0.0
            for (l in g.links) {
                val ki = kMat(l.i, fFull); val kj = kMat(l.j, fFull)
                val kjInv = M3.inverse(kj) ?: return Double.MAX_VALUE
                val m = M3.mul(M3.mul(kjInv, l.h), ki)
                val d = M3.det(m)
                if (d <= 0) { e += 10.0; continue }
                val s = Math.cbrt(d)
                val rm = DoubleArray(9) { m[it] / s }
                val rrt = M3.mul(rm, M3.transpose(rm))
                for (a in 0 until 3) for (b in 0 until 3) { val t = rrt[a * 3 + b] - (if (a == b) 1.0 else 0.0); e += t * t }
            }
            return e
        }
        var bestF = maxDimFull * 0.8
        var bestE = Double.MAX_VALUE
        var f = maxDimFull * 0.3
        while (f < maxDimFull * 6) {
            val e = rotError(f)
            if (e < bestE) { bestE = e; bestF = f }
            f *= 1.05
        }
        val hint = nodes.map { focal35[it] }.filter { it > 0 }.average().takeIf { !it.isNaN() }
        if (hint != null) {
            val fh = hint / 36.0 * maxDimFull
            if (rotError(fh) <= bestE * 1.5) bestF = fh
        }
        // Initial rotations along the tree: R_j = R_ij R_i with R_ij = Kj^-1 H_ij Ki.
        val rot = Array(images.size) { M3.identity() }
        for (x in g.order) {
            if (x == g.reference) continue
            val p = g.parent[x]
            val hpx = linkH(g.links, p, x) ?: return null
            val m = M3.mul(M3.mul(M3.inverse(kMat(x, bestF))!!, hpx), kMat(p, bestF))
            rot[x] = M3.mul(M3.orthonormalize(m), rot[p])
        }
        // Bundle adjustment on rays.
        val others = nodes.filter { it != g.reference }
        val idx = HashMap<Int, Int>(); others.forEachIndexed { k, v -> idx[v] = 1 + k * 3 }
        val samples = g.links.map { sampled(it, 50) }
        val resCount = samples.sumOf { it.size / 4 } * 3
        val start = DoubleArray(1 + others.size * 3)
        start[0] = Math.log(bestF)
        for (v in others) { val rv = M3.toRodrigues(rot[v]); System.arraycopy(rv, 0, start, idx[v]!!, 3) }
        fun rOf(p: DoubleArray, img: Int): DoubleArray {
            if (img == g.reference) return M3.identity()
            val o = idx[img]!!
            return M3.rodrigues(p[o], p[o + 1], p[o + 2])
        }
        fun ray(rm: DoubleArray, fW: Double, x: Double, y: Double, cx: Double, cy: Double, out: DoubleArray) {
            val a = (x - cx) / fW; val b = (y - cy) / fW
            val wx = rm[0] * a + rm[3] * b + rm[6]; val wy = rm[1] * a + rm[4] * b + rm[7]; val wz = rm[2] * a + rm[5] * b + rm[8]
            val n = sqrt(wx * wx + wy * wy + wz * wz)
            out[0] = wx / n; out[1] = wy / n; out[2] = wz / n
        }
        val ra = DoubleArray(3); val rb = DoubleArray(3)
        val lm = LevenbergMarquardt({ p, out ->
            val fF = Math.exp(p[0])
            var r = 0
            for ((li, l) in g.links.withIndex()) {
                val ri = rOf(p, l.i); val rj = rOf(p, l.j)
                val fi = fF * ws[l.i]; val fj = fF * ws[l.j]
                val c = samples[li]
                var k = 0
                while (k < c.size) {
                    ray(ri, fi, c[k].toDouble(), c[k + 1].toDouble(), cxw[l.i], cyw[l.i], ra)
                    ray(rj, fj, c[k + 2].toDouble(), c[k + 3].toDouble(), cxw[l.j], cyw[l.j], rb)
                    val sc = fF * ws[l.j]
                    out[r] = (ra[0] - rb[0]) * sc; out[r + 1] = (ra[1] - rb[1]) * sc; out[r + 2] = (ra[2] - rb[2]) * sc
                    r += 3; k += 4
                }
            }
        }, resCount, 60)
        val steps = DoubleArray(start.size) { if (it == 0) 1e-4 else 1e-5 }
        val best = lm.optimize(start, steps) { monitor.checkpoint() }
        val fFull = Math.exp(best[0])
        if (fFull < maxDimFull * 0.15 || fFull > maxDimFull * 20) return null
        val rs = Array(images.size) { rOf(best, it) }
        // Reprojection RMS in work pixels.
        var se = 0.0; var n = 0
        for ((li, l) in g.links.withIndex()) {
            val c = samples[li]
            val ri = rs[l.i]; val rj = rs[l.j]
            val fi = fFull * ws[l.i]; val fj = fFull * ws[l.j]
            var k = 0
            while (k < c.size) {
                ray(ri, fi, c[k].toDouble(), c[k + 1].toDouble(), cxw[l.i], cyw[l.i], ra)
                // world -> camera j
                val cx = rj[0] * ra[0] + rj[1] * ra[1] + rj[2] * ra[2]
                val cy = rj[3] * ra[0] + rj[4] * ra[1] + rj[5] * ra[2]
                val cz = rj[6] * ra[0] + rj[7] * ra[1] + rj[8] * ra[2]
                if (cz > 1e-6) {
                    val u = fj * cx / cz + cxw[l.j]; val v = fj * cy / cz + cyw[l.j]
                    se += (u - c[k + 2]) * (u - c[k + 2]) + (v - c[k + 3]) * (v - c[k + 3])
                } else se += 1e6
                n++; k += 4
            }
        }
        val rms = if (n == 0) 0.0 else sqrt(se / n)
        waveCorrect(rs, nodes)
        return RotationSolution(rs, fFull, rms)
    }

    /** Straightens the horizon: rotates the world so camera "right" axes are as horizontal as possible. */
    private fun waveCorrect(rs: Array<DoubleArray>, nodes: List<Int>) {
        if (nodes.size < 2) return
        // Covariance of camera x axes (first rows of R).
        val cov = DoubleArray(9)
        val zSum = DoubleArray(3)
        for (i in nodes) {
            val r = rs[i]
            for (a in 0 until 3) for (b in 0 until 3) cov[a * 3 + b] += r[a] * r[b]
            zSum[0] += r[6]; zSum[1] += r[7]; zSum[2] += r[8]
        }
        val up = smallestEigenvector(cov)
        // Keep "up" pointing the same way as the cameras' average up (-y in camera coords).
        var avgY = 0.0
        for (i in nodes) avgY += rs[i][3] * up[0] + rs[i][4] * up[1] + rs[i][5] * up[2]
        if (avgY < 0) for (k in 0..2) up[k] = -up[k]
        // Forward = mean viewing direction made orthogonal to up.
        var fx = zSum[0]; var fy = zSum[1]; var fz = zSum[2]
        val d = fx * up[0] + fy * up[1] + fz * up[2]
        fx -= d * up[0]; fy -= d * up[1]; fz -= d * up[2]
        val fn = sqrt(fx * fx + fy * fy + fz * fz)
        if (fn < 1e-6) return
        fx /= fn; fy /= fn; fz /= fn
        // right = up x forward
        val rx = up[1] * fz - up[2] * fy; val ry = up[2] * fx - up[0] * fz; val rz = up[0] * fy - up[1] * fx
        // World' basis rows: right, up, forward. New R_i = R_i * B^T.
        val bT = doubleArrayOf(rx, up[0], fx, ry, up[1], fy, rz, up[2], fz)
        for (i in nodes) rs[i] = M3.mul(rs[i], bT)
    }

    private fun smallestEigenvector(m: DoubleArray): DoubleArray {
        // Inverse power iteration on (m + eps I).
        val a = DoubleArray(9) { m[it] + if (it % 4 == 0) 1e-6 else 0.0 }
        val inv = M3.inverse(a) ?: return doubleArrayOf(0.0, 1.0, 0.0)
        var v = doubleArrayOf(0.0, 1.0, 0.0)
        repeat(60) {
            val nv = doubleArrayOf(inv[0] * v[0] + inv[1] * v[1] + inv[2] * v[2], inv[3] * v[0] + inv[4] * v[1] + inv[5] * v[2], inv[6] * v[0] + inv[7] * v[1] + inv[8] * v[2])
            val n = sqrt(nv[0] * nv[0] + nv[1] * nv[1] + nv[2] * nv[2])
            v = doubleArrayOf(nv[0] / n, nv[1] / n, nv[2] / n)
        }
        return v
    }

    /** Builds the final warp for the chosen model. */
    fun planarWarp(hs: Array<DoubleArray>, g: Graph, desc: String): Warp {
        val inv = Array(hs.size) { M3.inverse(hs[it]) ?: M3.identity() }
        val k = 1.0 / images[g.reference].scale // plane units are reference work pixels
        return PlanarWarp(hs, inv, DoubleArray(images.size) { images[it].scale }, k, 0.0, 0.0, desc)
    }

    fun rotationWarp(sol: RotationSolution, g: Graph): Warp {
        // Angular extent of the cameras' viewing directions decides the projection surface.
        var minYaw = Double.MAX_VALUE; var maxYaw = -Double.MAX_VALUE; var minPitch = Double.MAX_VALUE; var maxPitch = -Double.MAX_VALUE
        for (i in g.component) {
            val r = sol.r[i]
            val zx = r[6]; val zy = r[7]; val zz = r[8]
            val yaw = atan2(zx, zz); val pitch = asin(zy.coerceIn(-1.0, 1.0))
            minYaw = min(minYaw, yaw); maxYaw = max(maxYaw, yaw); minPitch = min(minPitch, pitch); maxPitch = max(maxPitch, pitch)
        }
        val fovHalf = atan(fullSizes[g.reference].maxOrNull()!! / 2.0 / sol.fFull)
        val yawSpan = maxYaw - minYaw + 2 * fovHalf
        val pitchSpan = maxPitch - minPitch + 2 * fovHalf
        val surface = when {
            yawSpan < Math.toRadians(100.0) && pitchSpan < Math.toRadians(100.0) -> Surface.PLANE
            pitchSpan < Math.toRadians(70.0) -> Surface.CYLINDER
            else -> Surface.SPHERE
        }
        val centers = Array(images.size) { doubleArrayOf(fullSizes[it][0] / 2.0, fullSizes[it][1] / 2.0) }
        val scale = sol.fFull // output pixels per radian ≈ native resolution at the image centre
        return RotationWarp(sol.r, sol.fFull, centers, surface, scale, 0.0, 0.0)
    }

    /** Perspective strength of the links: how badly an affine model explains each homography. */
    fun maxAffineDeviation(links: List<PairLink>): Double {
        var worst = 0.0
        for (l in links) {
            val w = images[l.i].w.toDouble(); val h = images[l.i].h.toDouble()
            val p = DoubleArray(2)
            // Affine approximation from the homography at the image centre.
            for ((x, y) in listOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h)) {
                if (!M3.project(l.h, x, y, p)) return Double.MAX_VALUE
                val ax = l.h[0] * x + l.h[1] * y + l.h[2]; val ay = l.h[3] * x + l.h[4] * y + l.h[5]
                val d = sqrt((p[0] - ax) * (p[0] - ax) + (p[1] - ay) * (p[1] - ay)) / max(w, h)
                worst = max(worst, d)
            }
        }
        return worst
    }

    @Suppress("unused")
    private fun abs3(a: Double) = abs(a)
}
