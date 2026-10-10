package com.localmediatools.music.core

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/** Four bars of music from the model: per 16th-note step, a melody, a bass and a drum event. */
class Trio(val melody: IntArray, val bass: IntArray, val drums: IntArray) {
    companion object {
        const val STEPS = 64
        /** Melody and bass events: 0 = hold, 1 = note off, 2.. = MIDI pitch 21.. (21 + event - 2). */
        const val HOLD = 0
        const val OFF = 1
        const val MIN_PITCH = 21
        fun pitchOf(event: Int) = event - 2 + MIN_PITCH
        /** Drum event bits: kick, snare, closed hi-hat, open hi-hat, low, mid and high tom, crash, ride. */
        val DRUM_PITCH = intArrayOf(36, 38, 42, 46, 45, 48, 50, 49, 51)
    }
}

/**
 * The decoder of Magenta's MusicVAE "trio_4bar" model (Roberts et al. 2018; weights from the
 * Magenta project, Apache-2.0), in plain Kotlin: a two-layer LSTM "conductor" turns a 256-number
 * latent code into one embedding per bar; for each bar, three two-layer LSTMs (melody, bass, drums)
 * write 16 steps, each step fed the previous step's event. It reproduces magenta.js's decoding
 * (checked against it, see MusicTest) and samples with a temperature like magenta.js's sample().
 *
 * The model writes notes, never sound: whatever it produces is played by the app's own instruments
 * ([Synth]), so the music is always instrumental.
 */
class TrioModel private constructor(private val w: Map<String, Tensor>) {
    class Tensor(val shape: IntArray, val data: FloatArray) { val rows get() = shape[0]; val cols get() = shape.last() }

    private class Lstm(val kernel: Tensor, val bias: Tensor) { val units = bias.data.size / 4 }

    private class Core(val cells: List<Lstm>, val zInit: Tensor, val zInitBias: Tensor, val out: Tensor, val outBias: Tensor) {
        val outDims = outBias.data.size
    }

    private val conductor = listOf(lstm("decoder/hierarchical_level_0/cell_0/lstm_cell/"), lstm("decoder/hierarchical_level_0/cell_1/lstm_cell/"))
    private val conductorInit = w.getValue("decoder/hierarchical_level_0/initial_state/kernel")
    private val conductorInitBias = w.getValue("decoder/hierarchical_level_0/initial_state/bias")
    private val cores = (0 until 3).map { i ->
        val p = "core_decoder/core_decoder_$i/decoder/"
        Core(listOf(lstm(p + "multi_rnn_cell/cell_0/lstm_cell/"), lstm(p + "multi_rnn_cell/cell_1/lstm_cell/")),
            w.getValue(p + "z_to_initial_state/kernel"), w.getValue(p + "z_to_initial_state/bias"),
            w.getValue(p + "output_projection/kernel"), w.getValue(p + "output_projection/bias"))
    }
    val zDims = conductorInit.rows

    private fun lstm(prefix: String) = Lstm(w.getValue(prefix + "kernel"), w.getValue(prefix + "bias"))

    /**
     * Decodes latent code [z]. With [temperature] null the most likely event is taken at every step
     * (magenta.js decode with no temperature); otherwise events are drawn from the softmax of
     * logits / temperature with [random] (a number in [0, 1) per draw).
     */
    fun decode(z: FloatArray, temperature: Float? = null, random: () -> Double = { 0.5 }): Trio {
        require(z.size == zDims)
        val out = Array(3) { IntArray(Trio.STEPS) }
        // Conductor state from z.
        val init = tanhDense(z, conductorInit, conductorInitBias)
        val units = conductor[0].units
        val c = Array(2) { l -> init.copyOfRange(2 * l * units, (2 * l + 1) * units) }
        val h = Array(2) { l -> init.copyOfRange((2 * l + 1) * units, (2 * l + 2) * units) }
        val prev = IntArray(3) { -1 }  // previous bar's last event per track (none before the first bar)
        val stepsPerBar = Trio.STEPS / 4
        for (bar in 0 until 4) {
            // One conductor step with a zero input (its single input number contributes nothing).
            var input = FloatArray(1)
            for (l in 0 until 2) { step(conductor[l], input, c[l], h[l]); input = h[l] }
            val e = h[1].copyOf()
            for ((t, core) in cores.withIndex()) {
                val events = decodeBar(core, e, stepsPerBar, prev[t], temperature, random)
                System.arraycopy(events, 0, out[t], bar * stepsPerBar, stepsPerBar)
                prev[t] = events.last()
            }
        }
        return Trio(out[0], out[1], out[2])
    }

    /** One bar of one track: 16 steps, each fed [previous event (one-hot), bar embedding]. */
    private fun decodeBar(core: Core, e: FloatArray, steps: Int, first: Int, temperature: Float?, random: () -> Double): IntArray {
        val init = tanhDense(e, core.zInit, core.zInitBias)
        val units = core.cells[0].units
        val c = Array(2) { l -> init.copyOfRange(2 * l * units, (2 * l + 1) * units) }
        val h = Array(2) { l -> init.copyOfRange((2 * l + 1) * units, (2 * l + 2) * units) }
        // The embedding's share of the first layer's gates is the same at every step of the bar.
        val k0 = core.cells[0].kernel
        val cols = k0.cols
        val ePart = FloatArray(cols)
        for (i in e.indices) { val x = e[i]; if (x != 0f) { val row = (core.outDims + i) * cols; for (j in 0 until cols) ePart[j] += x * k0.data[row + j] } }
        val events = IntArray(steps)
        var last = first
        val logits = FloatArray(core.outDims)
        for (s in 0 until steps) {
            // Layer 0: one-hot row of the previous event + embedding part + recurrent part.
            val gates = core.cells[0].bias.data.copyOf()
            for (j in 0 until cols) gates[j] += ePart[j]
            if (last >= 0) { val row = last * cols; for (j in 0 until cols) gates[j] += k0.data[row + j] }
            addMatVec(gates, h[0], k0, core.outDims + e.size)
            cell(gates, c[0], h[0])
            // Layer 1.
            val g1 = core.cells[1].bias.data.copyOf()
            addMatVec(g1, h[0], core.cells[1].kernel, 0)
            addMatVec(g1, h[1], core.cells[1].kernel, h[0].size)
            cell(g1, c[1], h[1])
            // Output projection and the event.
            System.arraycopy(core.outBias.data, 0, logits, 0, logits.size)
            addMatVec(logits, h[1], core.out, 0)
            last = pick(logits, temperature, random)
            events[s] = last
        }
        return events
    }

    /** A full LSTM step with an explicit input (the conductor). */
    private fun step(l: Lstm, input: FloatArray, c: FloatArray, h: FloatArray) {
        val gates = l.bias.data.copyOf()
        addMatVec(gates, input, l.kernel, 0)
        addMatVec(gates, h, l.kernel, input.size)
        cell(gates, c, h)
    }

    /** TF's LSTM cell (gates i, j, f, o; forget bias 1), updating [c] and [h] in place. */
    private fun cell(g: FloatArray, c: FloatArray, h: FloatArray) {
        val n = c.size
        for (k in 0 until n) {
            val i = sigmoid(g[k]); val j = tanh(g[n + k]); val f = sigmoid(g[2 * n + k] + 1f); val o = sigmoid(g[3 * n + k])
            c[k] = c[k] * f + i * j
            h[k] = tanh(c[k]) * o
        }
    }

    private fun sigmoid(x: Float) = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
    private fun tanh(x: Float) = kotlin.math.tanh(x.toDouble()).toFloat()

    /** out += x · kernel[rowOffset until rowOffset + x.size]. */
    private fun addMatVec(out: FloatArray, x: FloatArray, k: Tensor, rowOffset: Int) {
        val cols = k.cols
        val d = k.data
        for (i in x.indices) {
            val v = x[i]
            if (v == 0f) continue
            var p = (rowOffset + i) * cols
            for (j in 0 until cols) { out[j] += v * d[p]; p++ }
        }
    }

    private fun tanhDense(x: FloatArray, k: Tensor, b: Tensor): FloatArray {
        val out = b.data.copyOf()
        addMatVec(out, x, k, 0)
        for (i in out.indices) out[i] = tanh(out[i])
        return out
    }

    private fun pick(logits: FloatArray, temperature: Float?, random: () -> Double): Int {
        var best = 0
        for (i in logits.indices) if (logits[i] > logits[best]) best = i
        if (temperature == null || temperature <= 0f) return best
        // Softmax of logits / temperature, then one draw.
        val p = DoubleArray(logits.size)
        var sum = 0.0
        val m = logits[best] / temperature
        for (i in logits.indices) { p[i] = exp(logits[i] / temperature - m.toDouble()); sum += p[i] }
        var r = random() * sum
        for (i in p.indices) { r -= p[i]; if (r <= 0) return i }
        return best
    }

    companion object {
        /** Reads the packed decoder (buildtools/music/convert_trio.py). */
        fun load(stream: InputStream): TrioModel {
            val w = HashMap<String, Tensor>()
            DataInputStream(stream.buffered(1 shl 16)).use { d ->
                val magic = ByteArray(4).also { d.readFully(it) }
                require(String(magic) == "LMTV") { "not a music model" }
                require(d.readInt() == 1) { "unknown music model version" }
                repeat(d.readInt()) {
                    val name = String(ByteArray(d.readUnsignedShort()).also { d.readFully(it) })
                    val shape = IntArray(d.readByte().toInt()) { d.readInt() }
                    val min = d.readDouble(); val scale = d.readDouble()
                    val n = shape.fold(1) { a, b -> a * b }
                    val q = ByteArray(n).also { d.readFully(it) }
                    w[name] = Tensor(shape, FloatArray(n) { ((q[it].toInt() and 0xFF) * scale + min).toFloat() })
                }
            }
            return TrioModel(w)
        }

        /** Standard normal numbers from [random] (Box–Muller), for latent codes. */
        fun normal(random: () -> Double): Double {
            val u = random().coerceAtLeast(1e-12); val v = random()
            return kotlin.math.sqrt(-2 * ln(u)) * kotlin.math.cos(2 * Math.PI * v)
        }
    }
}
