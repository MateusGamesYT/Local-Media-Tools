package com.localmediatools.stitch.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small dense linear algebra used by the global alignment (row-major 3x3 matrices as DoubleArray(9)). */
object M3 {
    fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    fun transpose(a: DoubleArray) = doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    fun det(a: DoubleArray) =
        a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])

    fun inverse(a: DoubleArray): DoubleArray? {
        val d = det(a)
        if (abs(d) < 1e-15) return null
        val r = DoubleArray(9)
        r[0] = (a[4] * a[8] - a[5] * a[7]) / d
        r[1] = (a[2] * a[7] - a[1] * a[8]) / d
        r[2] = (a[1] * a[5] - a[2] * a[4]) / d
        r[3] = (a[5] * a[6] - a[3] * a[8]) / d
        r[4] = (a[0] * a[8] - a[2] * a[6]) / d
        r[5] = (a[2] * a[3] - a[0] * a[5]) / d
        r[6] = (a[3] * a[7] - a[4] * a[6]) / d
        r[7] = (a[1] * a[6] - a[0] * a[7]) / d
        r[8] = (a[0] * a[4] - a[1] * a[3]) / d
        return r
    }

    /** Applies a homography; returns false when the point maps to infinity. */
    fun project(h: DoubleArray, x: Double, y: Double, out: DoubleArray): Boolean {
        val w = h[6] * x + h[7] * y + h[8]
        if (abs(w) < 1e-12) return false
        out[0] = (h[0] * x + h[1] * y + h[2]) / w
        out[1] = (h[3] * x + h[4] * y + h[5]) / w
        return w > 0
    }

    fun normalize(h: DoubleArray): DoubleArray {
        val s = if (abs(h[8]) > 1e-12) h[8] else 1.0
        return DoubleArray(9) { h[it] / s }
    }

    /** Rodrigues vector → rotation matrix. */
    fun rodrigues(rx: Double, ry: Double, rz: Double): DoubleArray {
        val th = sqrt(rx * rx + ry * ry + rz * rz)
        if (th < 1e-12) return identity()
        val kx = rx / th; val ky = ry / th; val kz = rz / th
        val c = cos(th); val s = sin(th); val v = 1 - c
        return doubleArrayOf(
            kx * kx * v + c, kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, ky * ky * v + c, ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, kz * kz * v + c
        )
    }

    /** Rotation matrix → Rodrigues vector. */
    fun toRodrigues(r: DoubleArray): DoubleArray {
        val tr = (r[0] + r[4] + r[8] - 1) / 2
        val th = Math.acos(tr.coerceIn(-1.0, 1.0))
        if (th < 1e-9) return doubleArrayOf(0.0, 0.0, 0.0)
        if (Math.PI - th < 1e-6) {
            // 180°: axis from the diagonal.
            val x = sqrt(((r[0] + 1) / 2).coerceAtLeast(0.0))
            val y = sqrt(((r[4] + 1) / 2).coerceAtLeast(0.0)) * (if (r[1] >= 0) 1 else -1)
            val z = sqrt(((r[8] + 1) / 2).coerceAtLeast(0.0)) * (if (r[2] >= 0) 1 else -1)
            return doubleArrayOf(x * th, y * th, z * th)
        }
        val k = th / (2 * sin(th))
        return doubleArrayOf((r[7] - r[5]) * k, (r[2] - r[6]) * k, (r[3] - r[1]) * k)
    }

    /** Nearest rotation matrix (polar decomposition via Newton iterations). */
    fun orthonormalize(m: DoubleArray): DoubleArray {
        var x = m.copyOf()
        // Scale so the iteration converges.
        val s = Math.cbrt(abs(det(x))).takeIf { it > 1e-12 } ?: return identity()
        for (i in 0 until 9) x[i] /= s
        repeat(30) {
            val inv = inverse(x) ?: return identity()
            val it2 = transpose(inv)
            val next = DoubleArray(9) { (x[it] + it2[it]) / 2 }
            var diff = 0.0
            for (i in 0 until 9) diff += abs(next[i] - x[i])
            x = next
            if (diff < 1e-12) return@repeat
        }
        if (det(x) < 0) for (i in 0 until 9) x[i] = -x[i]
        return x
    }
}

/** Dense symmetric positive (semi-)definite solve with Cholesky; falls back to Gaussian elimination. */
object Dense {
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val l = Array(n) { DoubleArray(n) }
        var ok = true
        outer@ for (i in 0 until n) {
            for (j in 0..i) {
                var s = a[i][j]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                if (i == j) {
                    if (s <= 1e-15) { ok = false; break@outer }
                    l[i][i] = sqrt(s)
                } else l[i][j] = s / l[j][j]
            }
        }
        if (ok) {
            val y = DoubleArray(n)
            for (i in 0 until n) { var s = b[i]; for (k in 0 until i) s -= l[i][k] * y[k]; y[i] = s / l[i][i] }
            val x = DoubleArray(n)
            for (i in n - 1 downTo 0) { var s = y[i]; for (k in i + 1 until n) s -= l[k][i] * x[k]; x[i] = s / l[i][i] }
            return x
        }
        return gauss(a, b)
    }

    fun gauss(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }
        val b = b0.copyOf()
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-14) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            val tb = b[c]; b[c] = b[p]; b[p] = tb
            for (r in c + 1 until n) {
                val f = a[r][c] / a[c][c]
                if (f == 0.0) continue
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (k in i + 1 until n) s -= a[i][k] * x[k]
            x[i] = s / a[i][i]
        }
        return x
    }
}

/**
 * Generic Levenberg–Marquardt with a numeric Jacobian. Residual functions are small (a few
 * thousand residuals, under ~200 parameters), so a dense approach is simple and fast enough.
 */
class LevenbergMarquardt(
    private val residuals: (params: DoubleArray, out: DoubleArray) -> Unit,
    private val residualCount: Int,
    /** Parameter indices touched by each residual block would speed things up; dense is fine here. */
    private val maxIterations: Int = 60,
) {
    var finalRms = Double.NaN; private set

    fun optimize(start: DoubleArray, steps: DoubleArray, cancel: () -> Unit = {}): DoubleArray {
        val n = start.size
        var p = start.copyOf()
        val r = DoubleArray(residualCount)
        val rTmp = DoubleArray(residualCount)
        residuals(p, r)
        var cost = sumSq(r)
        var lambda = 1e-3
        val jac = Array(n) { DoubleArray(residualCount) }
        for (iter in 0 until maxIterations) {
            cancel()
            // Numeric Jacobian (forward differences).
            for (k in 0 until n) {
                val old = p[k]
                p[k] = old + steps[k]
                residuals(p, rTmp)
                p[k] = old
                val inv = 1.0 / steps[k]
                val col = jac[k]
                for (i in 0 until residualCount) col[i] = (rTmp[i] - r[i]) * inv
            }
            val jtj = Array(n) { DoubleArray(n) }
            val jtr = DoubleArray(n)
            for (a in 0 until n) {
                val ca = jac[a]
                var s = 0.0
                for (i in 0 until residualCount) s += ca[i] * r[i]
                jtr[a] = -s
                for (b in 0..a) {
                    val cb = jac[b]
                    var t = 0.0
                    for (i in 0 until residualCount) t += ca[i] * cb[i]
                    jtj[a][b] = t; jtj[b][a] = t
                }
            }
            var improved = false
            for (attempt in 0 until 10) {
                val aug = Array(n) { i -> DoubleArray(n) { j -> if (i == j) jtj[i][j] * (1 + lambda) + 1e-12 else jtj[i][j] } }
                val delta = Dense.solve(aug, jtr) ?: break
                val cand = DoubleArray(n) { p[it] + delta[it] }
                residuals(cand, rTmp)
                val c = sumSq(rTmp)
                if (c < cost) {
                    val rel = (cost - c) / cost.coerceAtLeast(1e-30)
                    p = cand; cost = c
                    System.arraycopy(rTmp, 0, r, 0, residualCount)
                    lambda = (lambda / 3).coerceAtLeast(1e-9)
                    improved = true
                    if (rel < 1e-7) { finalRms = sqrt(cost / residualCount); return p }
                    break
                }
                lambda *= 4
            }
            if (!improved) break
        }
        finalRms = sqrt(cost / residualCount)
        return p
    }

    private fun sumSq(r: DoubleArray): Double { var s = 0.0; for (v in r) s += v * v; return s }
}
