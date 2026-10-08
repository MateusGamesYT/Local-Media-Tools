package com.localmediatools.gallery.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln

/**
 * The scene classifier's output layer: all 21,843 ImageNet-21k classes, so the softmax is exact.
 * Weights are stored in 4 bits with a scale per group of [group] inputs (on labelled photos this
 * changed category scores by less than 0.005 for 99 % of pictures). Linear probes (sigmoid)
 * cover scenes the classifier has no class for.
 *
 * File layout (little-endian): "LMTH", version 2, rows R, dim D, group G, probes P, then R×(D/G)
 * half-float scales, R float biases, R×D/2 bytes of packed signed 4-bit weights (low nibble first),
 * P×D float probe weights and P float probe biases.
 */
class SceneHead(
    val rows: Int,
    val dim: Int,
    private val group: Int,
    private val scales: FloatArray,
    private val bias: FloatArray,
    private val packed: ByteArray,
    private val probeW: FloatArray,
    private val probeB: FloatArray,
) {
    val probeCount get() = probeB.size

    /** Softmax probabilities of all classes for one feature vector. */
    fun probabilities(f: FloatArray): FloatArray {
        val groups = dim / group
        val out = FloatArray(rows)
        var mx = Float.NEGATIVE_INFINITY
        var p = 0
        for (r in 0 until rows) {
            var acc = bias[r]
            var d = 0
            for (g in 0 until groups) {
                var s = 0f
                var k = 0
                while (k < group) {
                    val b = packed[p++].toInt()
                    // Sign-extend the two nibbles.
                    s += ((b shl 28) shr 28) * f[d + k] + ((b shl 24) shr 28) * f[d + k + 1]
                    k += 2
                }
                acc += s * scales[r * groups + g]
                d += group
            }
            out[r] = acc
            if (acc > mx) mx = acc
        }
        var sum = 0.0
        for (r in 0 until rows) sum += exp((out[r] - mx).toDouble())
        val lse = mx + ln(sum).toFloat()
        for (r in 0 until rows) out[r] = exp(out[r] - lse)
        return out
    }

    /** Probe outputs (0..1) for one feature vector. */
    fun probes(f: FloatArray): FloatArray = FloatArray(probeB.size) { q ->
        var s = probeB[q]
        val o = q * dim
        for (d in 0 until dim) s += probeW[o + d] * f[d]
        (1.0 / (1.0 + exp(-s.toDouble()))).toFloat()
    }

    companion object {
        fun read(bytes: ByteArray): SceneHead {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); b.get(magic)
            require(String(magic, Charsets.US_ASCII) == "LMTH") { "Not a scene head file" }
            require(b.int == 2) { "Unsupported scene head version" }
            val rows = b.int; val dim = b.int; val group = b.int; val probes = b.int
            require(dim % group == 0 && group % 2 == 0) { "Bad scene head layout" }
            val scales = FloatArray(rows * (dim / group)) { half(b.short) }
            val bias = FloatArray(rows) { b.float }
            val packed = ByteArray(rows * dim / 2); b.get(packed)
            val pw = FloatArray(probes * dim) { b.float }
            val pb = FloatArray(probes) { b.float }
            return SceneHead(rows, dim, group, scales, bias, packed, pw, pb)
        }

        /** IEEE 754 half precision to float. */
        fun half(h: Short): Float {
            val v = h.toInt() and 0xFFFF
            val sign = if (v and 0x8000 != 0) -1f else 1f
            val e = (v shr 10) and 0x1F
            val m = v and 0x3FF
            return sign * when (e) {
                0 -> m / 1024f * 6.1035156e-5f
                31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + m / 1024f) * Math.scalb(1f, e - 15)
            }
        }
    }
}
