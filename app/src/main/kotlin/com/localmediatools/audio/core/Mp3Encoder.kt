package com.localmediatools.audio.core

import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An MP3 encoder: MPEG-1 Layer III at a constant bit rate (32–320 kbps), 32, 44.1 or 48 kHz, mono
 * or joint stereo (mid/side when the channels are alike).
 *
 * The signal goes through the standard's polyphase filter bank and MDCT. Long blocks (18 lines per
 * subband) carry steady sound; at a sudden attack the encoder switches to three short blocks, with
 * start and stop windows around them (it looks one granule ahead to place them), so quantisation
 * noise isn't smeared into the quiet before the attack ("pre-echo"). A psychoacoustic model after
 * the standard's model 2 (how predictable each frequency is, spreading between bands, the threshold
 * of hearing) sets the noise each band can hide, and the quantisation loops spend the bits so the
 * noise stays under it, borrowing from the bit reservoir for the hard parts. The file starts with an
 * "Info" frame giving the number of frames, so players show the exact length.
 *
 * Feed interleaved float samples (−1…1) with [add], then call [finish].
 */
class Mp3Encoder(private val out: OutputStream, val rate: Int, val channels: Int, val kbps: Int) {
    private val sfIndex = when (rate) { 44100 -> 0; 48000 -> 1; 32000 -> 2; else -> throw IllegalArgumentException("MP3 needs 32, 44.1 or 48 kHz") }
    private val brIndex = BITRATES.indexOf(kbps).also { require(it > 0) { "No MP3 bit rate $kbps" } }
    init { require(channels == 1 || channels == 2) }

    private val longWidths = when (rate) { 44100 -> Mp3Tables.BANDS_44100; 48000 -> Mp3Tables.BANDS_48000; else -> Mp3Tables.BANDS_32000 }
    private val shortWidths = when (rate) { 44100 -> SHORT_44100; 48000 -> SHORT_48000; else -> SHORT_32000 }
    /** Start of each long scalefactor band (23 values, the last is 576). */
    private val bandStart = IntArray(23).also { for (i in 0 until 22) it[i + 1] = it[i] + longWidths[i] }
    /** Start of each short band within one window (14 values, the last is 192). */
    private val shortStart = IntArray(14).also { for (i in 0 until 13) it[i + 1] = it[i] + shortWidths[i] }
    /** Short blocks: where window w's frequency line f goes in the granule (band by band, each band window by window). */
    private val shortPos = Array(3) { w ->
        IntArray(192) { f -> var b = 0; while (shortStart[b + 1] <= f) b++; 3 * shortStart[b] + w * shortWidths[b] + (f - shortStart[b]) }
    }
    /** And back: the frequency line (within its window) at each position of a short-block granule. */
    private val shortFreq = IntArray(576).also { for (w in 0 until 3) for (f in 0 until 192) it[shortPos[w][f]] = f }
    private val sideInfoBytes = if (channels == 1) 17 else 32

    /**
     * The bands quantised together, as positions in the granule: long blocks have 22 (21 with a
     * scalefactor), short blocks 13 per window (12 with scalefactors). [freqBand] is the long band
     * with the same frequencies (the psychoacoustic model works in long bands).
     */
    private class Layout(val start: IntArray, val end: IntArray, val sfIndex: IntArray, val maxSf: IntArray, val freqBand: IntArray) {
        val size get() = start.size
    }
    private val longLayout = Layout(IntArray(22) { bandStart[it] }, IntArray(22) { bandStart[it + 1] }, IntArray(22) { if (it < 21) it else -1 },
        IntArray(22) { if (it < 11) 15 else if (it < 21) 7 else 0 }, IntArray(22) { it })
    private val shortLayout = run {
        val s = IntArray(39); val e = IntArray(39); val sfi = IntArray(39); val mx = IntArray(39); val fb = IntArray(39)
        for (b in 0 until 13) for (win in 0 until 3) {
            val k = b * 3 + win
            s[k] = 3 * shortStart[b] + win * shortWidths[b]; e[k] = s[k] + shortWidths[b]
            sfi[k] = if (b < 12) k else -1
            mx[k] = if (b < 6) 15 else if (b < 12) 7 else 0
            val f = 1.5 * (shortStart[b] + shortStart[b + 1])      // centre, in long-block lines
            var lb = 0; while (lb < 21 && bandStart[lb + 1] <= f) lb++
            fb[k] = lb
        }
        Layout(s, e, sfi, mx, fb)
    }

    // ------------------------------------------------------------------ state
    private val window = DoubleArray(512) { Mp3Tables.WINDOW_D[it] / 65536.0 / 32.0 }
    private val fifo = Array(channels) { DoubleArray(512) }
    private val pending = Array(channels) { FloatArray(576) }
    private var pendingN = 0
    /** Recent input per channel, for the psychoacoustic model's FFT and the attack detector. */
    private val history = Array(channels) { FloatArray(2048) }
    /** Subband samples of the last granule transformed (32 bands × 18), for the MDCT's overlap. */
    private val prevSub = Array(channels) { Array(32) { DoubleArray(18) } }
    private var prevType = LONG
    private var prevLateAttack = false
    private val attackEnergy = DoubleArray(3)
    private var framesWritten = 0L
    private var samplesIn = 0L
    private var granulesOut = 0L
    private var padRest = 0L
    /** Granules coded with short blocks (for measurements). */
    internal var shortGranules = 0L
        private set
    /** Bytes of padding where the bit reservoir overflowed (for measurements). */
    internal var stuffedBytes = 0L
        private set
    /** Testing: every granule in short blocks. */
    internal var allShort = false

    /** A granule through the filter bank, waiting for the next one to settle its block type. */
    private class SubGranule(val sub: Array<Array<DoubleArray>>, val attacks: Int, val ratios: Array<DoubleArray>)
    private val subQueue = ArrayDeque<SubGranule>()
    /** A transformed granule: spectrum per channel, block type and masking ratios (left, right[, mid, side]). */
    private class Spectrum(val xr: Array<DoubleArray>, val type: Int, val ratios: Array<DoubleArray>)
    private val ready = ArrayList<Spectrum>(2)

    // Main data: one continuous byte stream; each frame owns a slot of it.
    private val stream = ByteBuf()
    private var streamEnd = 0L                 // absolute position of stream's next byte
    private var slotStart = 0L                 // where the next frame's slot begins
    private var dataStart = 0L                 // where the next frame's main data begins (≤ slotStart)
    private class PendingFrame(val head: ByteArray, val slotStart: Long, val slotLen: Int)
    private val queue = ArrayDeque<PendingFrame>()
    private var writtenFrameBytes = 0L

    /** Highest frequency kept: lower bit rates leave out what they can't code well (as LAME does). */
    private val cutoffHz = run {
        val perCh = if (channels == 2) kbps / 2 else kbps
        when {
            perCh >= 128 -> 20000.0; perCh >= 96 -> 19500.0; perCh >= 80 -> 17500.0; perCh >= 64 -> 16000.0
            perCh >= 56 -> 14500.0; perCh >= 48 -> 12000.0; perCh >= 40 -> 10000.0; else -> 8000.0
        }
    }
    private val cutoffLine = min(576, (cutoffHz / (rate / 2.0) * 576).toInt())

    /** The Info frame's bit rate: this one, or the lowest whose frame holds the tags (low rates' frames are too small). */
    private val infoBrIndex = (brIndex until BITRATES.size).first { 144000 * BITRATES[it] / rate >= 4 + sideInfoBytes + 120 + 36 }
    private val infoFrameLen = 144000 * BITRATES[infoBrIndex] / rate

    init {
        // An "Info" frame first (silent): frame count, size and seek table are filled in by finish().
        out.write(ByteArray(infoFrameLen))
    }

    // ------------------------------------------------------------------ input
    fun add(samples: FloatArray, n: Int = samples.size) {
        var i = 0
        val frames = n / channels
        samplesIn += frames
        while (i < frames) {
            val take = min(frames - i, 576 - pendingN)
            for (k in 0 until take) for (c in 0 until channels) pending[c][pendingN + k] = samples[(i + k) * channels + c]
            pendingN += take; i += take
            if (pendingN == 576) { granule(); pendingN = 0 }
        }
    }

    /**
     * Encodes what's left, flushes the bit reservoir and writes the Info frame. [rewrite] puts bytes
     * at a position of the file (the Info frame at 0); when it is null the Info frame stays silent.
     */
    fun finish(rewrite: ((Long, ByteArray) -> Unit)? = null) {
        // Silence through the filter bank and the look-ahead until the last samples are coded.
        val frames = (samplesIn + DELAY + 1152 + 1151) / 1152
        while (granulesOut < 2 * frames) {
            for (c in 0 until channels) java.util.Arrays.fill(pending[c], pendingN, 576, 0f)
            pendingN = 0
            granule()
        }
        // Fill the remaining slots with zeros (ancillary data).
        val lastSlotEnd = queue.lastOrNull()?.let { it.slotStart + it.slotLen } ?: streamEnd
        while (streamEnd < lastSlotEnd) { stream.add(0); streamEnd++ }
        flushFrames()
        out.flush()
        rewrite?.invoke(0, infoFrame())
    }

    // ------------------------------------------------------------------ one granule: filter bank, attacks, masking
    private fun granule() {
        for (c in 0 until channels) {
            val h = history[c]
            System.arraycopy(h, 576, h, 0, 2048 - 576)
            System.arraycopy(pending[c], 0, h, 2048 - 576, 576)
        }
        val sub = Array(channels) { c ->
            Array(18) { t ->
                val s = DoubleArray(32)
                analysis(c, pending[c], t * 32, s)
                // The decoder inverts every other sample of the odd subbands; do the same here.
                if (t and 1 == 1) for (b in 1 until 32 step 2) s[b] = -s[b]
                s
            }
        }
        val attacks = detectAttacks()
        // Masking for each signal that may be coded: left and right, and mid and side for joint stereo.
        val ratios = Array(if (channels == 2) 4 else 1) { k ->
            val x = DoubleArray(1024) { i ->
                val p = 2048 - 576 - PSY_LAG + i - 224
                when (k) {
                    0, 1 -> history[k][p].toDouble()
                    2 -> (history[0][p] + history[1][p]) * SQRT_HALF
                    else -> (history[0][p] - history[1][p]) * SQRT_HALF
                }
            }
            maskingRatios(x, psyStates[k])
        }
        subQueue.addLast(SubGranule(sub, attacks, ratios))
        if (subQueue.size >= 2) {
            val cur = subQueue.removeFirst()
            transform(cur, subQueue.first().attacks)
        }
        if (ready.size == 2) { encodeFrame(ready[0], ready[1]); ready.clear() }
    }

    /**
     * Which thirds of the granule's subband samples (bit 0, 1, 2) start with a sudden rise of
     * high-frequency energy: more than [ATTACK_RATIO] times the three thirds before.
     */
    private fun detectAttacks(): Int {
        var mask = 0
        for (j in 0 until 3) {
            var e = 0.0
            for (c in 0 until channels) {
                val h = history[c]
                val from = 2048 - 576 - FILTER_DELAY + j * 192
                for (i in from until from + 192) { val d = (h[i] - h[i - 1]).toDouble(); e += d * d }
            }
            e /= channels * 192.0
            val before = max(max(attackEnergy[0], attackEnergy[1]), attackEnergy[2])
            if (e > ATTACK_RATIO * max(before, ATTACK_FLOOR)) mask = mask or (1 shl j)
            attackEnergy[0] = attackEnergy[1]; attackEnergy[1] = attackEnergy[2]; attackEnergy[2] = e
        }
        return mask
    }

    /**
     * Chooses the block type and transforms the granule. A granule's MDCT spans its own subband
     * samples and the previous granule's: an attack in the first two thirds of its own needs short
     * blocks now; one in the last third is outside a start window, so short blocks follow in the next
     * granule. Short blocks come after a start window or short blocks, and a stop window ends them.
     */
    private fun transform(g: SubGranule, nextAttacks: Int) {
        val late = g.attacks and 4 != 0
        val needNow = allShort || g.attacks and 3 != 0 || prevLateAttack
        val needNext = allShort || nextAttacks and 3 != 0 || late
        val type = when {
            prevType == START || needNow -> SHORT          // (without a start window only at the very beginning, after silence)
            needNext -> if (prevType == SHORT) SHORT else START
            prevType == SHORT -> STOP
            else -> LONG
        }
        prevType = type; prevLateAttack = late
        granulesOut++
        if (type == SHORT) shortGranules++
        val xr = Array(channels) { c -> DoubleArray(576).also { mdct(c, g.sub[c], type, it) } }
        ready.add(Spectrum(xr, type, g.ratios))
    }

    // ------------------------------------------------------------------ one frame
    private fun frameLength(padding: Boolean) = 144000 * kbps / rate + if (padding) 1 else 0

    private fun encodeFrame(s0: Spectrum, s1: Spectrum) {
        val specs = arrayOf(s0, s1)
        // Padding keeps the average frame size exact at 44.1 kHz.
        padRest += (144000L * kbps) % rate
        val padding = padRest >= rate
        if (padding) padRest -= rate
        val len = frameLength(padding)
        val slot = len - 4 - sideInfoBytes

        // Joint stereo: mid/side when the two channels are much alike.
        var ms = false
        if (channels == 2) {
            var es = 0.0; var em = 0.0
            for (s in specs) for (i in 0 until 576) { val l = s.xr[0][i]; val r = s.xr[1][i]; es += (l - r) * (l - r); em += (l + r) * (l + r) }
            ms = es < 0.35 * em
            if (ms) for (s in specs) for (i in 0 until 576) {
                val l = s.xr[0][i]; val r = s.xr[1][i]
                s.xr[0][i] = (l + r) * SQRT_HALF; s.xr[1][i] = (l - r) * SQRT_HALF
            }
        }
        // Lowpass.
        for (s in specs) for (c in 0 until channels) {
            val x = s.xr[c]
            if (s.type == SHORT) { for (i in 0 until 576) if (3 * shortFreq[i] >= cutoffLine) x[i] = 0.0 }
            else for (i in cutoffLine until 576) x[i] = 0.0
        }

        // Bits: this frame's slot plus what the reservoir holds; harder granules get more, easy ones save
        // for later only while the reservoir has room (a full one would throw the bits away).
        val reservoir = (slotStart - dataStart).toInt()
        val frameBits = (reservoir + slot) * 8
        val meanBits = slot * 8 / (2 * channels)
        val fill = reservoir / 511.0
        val gcs = ArrayList<Granule>(2 * channels)
        val live = BooleanArray(2 * channels) { k -> specs[k / channels].xr[k % channels].any { abs(it) > 1e-9 } }
        var used = 0
        var resLeft = reservoir * 8
        for (gr in 0 until 2) for (c in 0 until channels) {
            val s = specs[gr]
            val layout = if (s.type == SHORT) shortLayout else longLayout
            val ratio = s.ratios[if (channels == 2 && ms) 2 + c else c]
            val (xmin, pe) = allowed(s.xr[c], ratio, layout)
            val remainingGc = (2 - gr) * channels - c
            val fair = (frameBits - used) / remainingGc
            val want = (pe * BITS_PER_PE).toInt()
            // Short blocks (attacks) get at least half a share more, and may draw deeper on the reservoir.
            var target = if (s.type == SHORT) meanBits + min(max(want - meanBits, meanBits / 2), (resLeft * 0.8).toInt())
                else if (want > meanBits) meanBits + min(want - meanBits, (resLeft * 0.6).toInt())
                else (want + (meanBits - want) * max(fill, 0.25)).toInt()
            target = min(target, min(4095, fair + resLeft / 2))
            // Leave the granules still to come at least half their share.
            target = min(target, frameBits - used - (remainingGc - 1) * (meanBits / 2)).coerceAtLeast(0)
            // Bits the reservoir can't carry to the next frame are lost: spend them, shared among the
            // granules still to come that have sound (a silent channel takes none).
            val mustSpend = frameBits - used - MAX_CARRY
            if (mustSpend > 0 && live[gr * channels + c]) {
                var liveLeft = 0
                for (k in gr * channels + c until 2 * channels) if (live[k]) liveLeft++
                target = max(target, min(4095, mustSpend / liveLeft))
            }
            val g = quantize(s.xr[c], xmin, min(target, 4095), s.type, layout)
            gcs.add(g)
            used += g.part23
            resLeft = max(0, frameBits - used - (remainingGc - 1) * meanBits)
        }

        // Main data: scalefactors and Huffman codes of each granule and channel.
        val w = BitWriter()
        for (g in gcs) writeMain(w, g)
        w.align()
        val data = w.bytes()
        check(data.size <= reservoir + slot) { "main data overflow" }
        for (b in data) stream.add(b.toInt())
        streamEnd += data.size
        // Frame header and side information.
        val h = BitWriter()
        h.put(0x7FF, 11); h.put(3, 2); h.put(1, 2); h.put(1, 1)            // MPEG-1, Layer III, no CRC
        h.put(brIndex, 4); h.put(sfIndex, 2); h.put(if (padding) 1 else 0, 1); h.put(0, 1)
        h.put(if (channels == 1) 3 else 1, 2); h.put(if (ms) 2 else 0, 2)   // mono, or joint stereo (MS)
        h.put(0, 1); h.put(1, 1); h.put(0, 2)                               // not copyrighted, original, no emphasis
        h.put(reservoir, 9); h.put(0, if (channels == 1) 5 else 3)          // main_data_begin, private bits
        for (c in 0 until channels) h.put(0, 4)                             // no scalefactor sharing
        for (g in gcs) {
            h.put(g.part23, 12); h.put(g.bigValues, 9); h.put(g.globalGain, 8); h.put(g.sfCompress, 4)
            if (g.type == LONG) {
                h.put(0, 1)
                for (r in 0 until 3) h.put(g.tableSelect[r], 5)
                h.put(g.region0, 4); h.put(g.region1, 3)
            } else {
                h.put(1, 1); h.put(g.type, 2); h.put(0, 1)                  // window switching, block type, not mixed
                for (r in 0 until 2) h.put(g.tableSelect[r], 5)
                for (k in 0 until 3) h.put(0, 3)                            // no subblock gain
            }
            h.put(0, 1); h.put(g.sfScale, 1); h.put(g.count1Table, 1)       // no preemphasis
        }
        val head = h.bytes()
        check(head.size == 4 + sideInfoBytes)
        queue.addLast(PendingFrame(head, slotStart, slot))
        // The next frame's data starts after this one's, at most 511 bytes before its own slot.
        slotStart += slot
        val res = slotStart - streamEnd
        if (res > 511) { val pad = (res - 511).toInt(); repeat(pad) { stream.add(0) }; streamEnd += pad; stuffedBytes += pad }
        dataStart = streamEnd
        flushFrames()
    }

    /** Writes the frames whose slots are complete. */
    private fun flushFrames() {
        while (queue.isNotEmpty()) {
            val f = queue.first()
            if (f.slotStart + f.slotLen > streamEnd) break
            queue.removeFirst()
            out.write(f.head)
            out.write(stream.take(f.slotStart, f.slotLen))
            framesWritten++
            writtenFrameBytes += f.head.size + f.slotLen
        }
    }

    // ------------------------------------------------------------------ filter bank and MDCT
    /** Pushes 32 samples from [x] at [at] through the polyphase filter bank; the 32 subband samples go to [s]. */
    private fun analysis(c: Int, x: FloatArray, at: Int, s: DoubleArray) {
        val f = fifo[c]
        System.arraycopy(f, 0, f, 32, 480)
        for (i in 0 until 32) f[31 - i] = x[at + i].toDouble()
        val y = DoubleArray(64)
        for (i in 0 until 64) { var v = 0.0; var j = i; while (j < 512) { v += window[j] * f[j]; j += 64 }; y[i] = v }
        for (k in 0 until 32) { var v = 0.0; val m = MATRIX[k]; for (i in 0 until 64) v += m[i] * y[i]; s[k] = v }
    }

    /** 18 subband samples × 32 bands → 576 spectral lines, with the window of [type] over the previous granule's samples and these. */
    private fun mdct(c: Int, sub: Array<DoubleArray>, type: Int, xr: DoubleArray) {
        val prev = prevSub[c]
        val z = DoubleArray(36)
        for (b in 0 until 32) {
            for (n in 0 until 18) { z[n] = prev[b][n]; z[n + 18] = sub[n][b] }
            if (type == SHORT) {
                // Three 12-point MDCTs, at 6, 12 and 18.
                for (w in 0 until 3) for (k in 0 until 6) {
                    var v = 0.0; val cs = MDCT_SHORT_COS[k]
                    for (n in 0 until 12) v += z[6 + 6 * w + n] * WIN_SHORT[n] * cs[n]
                    xr[shortPos[w][b * 6 + k]] = v * MDCT_SHORT_SCALE
                }
            } else {
                val win = WINDOWS[type]
                for (k in 0 until 18) { var v = 0.0; val cs = MDCT_COS[k]; for (n in 0 until 36) v += z[n] * win[n] * cs[n]; xr[b * 18 + k] = v * MDCT_SCALE }
            }
            for (n in 0 until 18) prev[b][n] = sub[n][b]
        }
        if (type != SHORT) {
            // Alias reduction butterflies (the decoder undoes them; short blocks have none).
            for (b in 1 until 32) for (i in 0 until 8) {
                val u = xr[b * 18 - 1 - i]; val d = xr[b * 18 + i]
                xr[b * 18 - 1 - i] = u * CS[i] + d * CA[i]
                xr[b * 18 + i] = d * CS[i] - u * CA[i]
            }
        }
    }

    // ------------------------------------------------------------------ psychoacoustics
    private val bandBark = DoubleArray(22) { b -> bark((bandStart[b] + bandStart[b + 1]) / 2.0 * rate / 2.0 / 576) }
    /** Spreading between bands (Schroeder's function), as a power ratio, [masker][maskee]. */
    private val spread = Array(22) { j -> DoubleArray(22) { i ->
        val dz = bandBark[i] - bandBark[j]
        val db = 15.81 + 7.5 * (dz + 0.474) - 17.5 * sqrt(1 + (dz + 0.474) * (dz + 0.474))
        10.0.pow(db / 10)
    } }

    /**
     * The threshold of hearing per band of [layout], as noise energy allowed: its quietest point
     * (0 dB SPL taken as 100 dB below a full-scale sine, and never above −40 dB of it) over the
     * band's width in long-block lines. A short block's line spans three long ones, and its noise
     * energy per line is three times a long block's for the same sound level, so the sum is alike.
     */
    private fun athFor(layout: Layout): DoubleArray = DoubleArray(layout.size) { k ->
        val (lo, hi) = if (layout === shortLayout) {
            val f = shortFreq[layout.start[k]]
            3 * f to min(576, 3 * (f + layout.end[k] - layout.start[k]))
        } else layout.start[k] to layout.end[k]
        var minDb = Double.MAX_VALUE
        for (i in lo until hi) minDb = min(minDb, athDb((i + 0.5) * rate / 2.0 / 576))
        FULL_SCALE_ENERGY * 10.0.pow(min(minDb - 100.0, -40.0) / 10) * (hi - lo)
    }
    private val athLong = athFor(longLayout)
    private val athShort = athFor(shortLayout)

    /** A signal's last two spectra (as magnitude and unit phasor per line) and masking thresholds. */
    private class PsyState {
        val r1 = DoubleArray(513); val r2 = DoubleArray(513)
        val c1 = DoubleArray(513); val s1 = DoubleArray(513); val c2 = DoubleArray(513); val s2 = DoubleArray(513)
        val nbPrev = DoubleArray(22) { -1.0 }
    }
    private val psyStates = Array(4) { PsyState() }
    /** FFT line → long band (by frequency). */
    private val lineBand = IntArray(513) { k ->
        val mdctLine = k * 576 / 512
        var b = 0
        while (b < 21 && bandStart[b + 1] <= mdctLine) b++
        b
    }

    /**
     * The psychoacoustic model (after ISO 11172-3's model 2): a 1024-point FFT per granule, how
     * predictable each line is from the two granules before (a steady tone is; noise isn't) giving
     * each band's tonality, energy spread to neighbouring bands, and a masking threshold 29 dB below
     * for tones and 6 dB for noise. Returns, per long band, the noise allowed as a share of its energy.
     * [st] holds the previous granules' spectra of this signal (left, right, mid or side).
     */
    private fun maskingRatios(x: DoubleArray, st: PsyState): DoubleArray {
        val re = DoubleArray(1024) { x[it] * HANN[it] }; val im = DoubleArray(1024)
        fft(re, im)
        val e = DoubleArray(22); val cw = DoubleArray(22)
        for (k in 0..512) {
            val r = sqrt(re[k] * re[k] + im[k] * im[k])
            val cs = if (r > 0) re[k] / r else 1.0; val sn = if (r > 0) im[k] / r else 0.0
            // Unpredictability: distance to the line extrapolated from the two before (magnitude
            // 2r₁ − r₂, phase 2φ₁ − φ₂, i.e. phasor u₁²·ū₂), over the sum of their sizes.
            val rp = 2 * st.r1[k] - st.r2[k]
            val ac = st.c1[k] * st.c1[k] - st.s1[k] * st.s1[k]; val asn = 2 * st.c1[k] * st.s1[k]
            val pc = ac * st.c2[k] + asn * st.s2[k]; val ps = asn * st.c2[k] - ac * st.s2[k]
            val dx = r * cs - rp * pc; val dy = r * sn - rp * ps
            val c = sqrt(dx * dx + dy * dy) / (r + abs(rp) + 1e-12)
            st.r2[k] = st.r1[k]; st.r1[k] = r
            st.c2[k] = st.c1[k]; st.s2[k] = st.s1[k]; st.c1[k] = cs; st.s1[k] = sn
            val b = lineBand[k]
            e[b] += r * r; cw[b] += r * r * c
        }
        val ratio = DoubleArray(22)
        for (i in 0 until 22) {
            var ecb = 0.0; var ct = 0.0; var norm = 0.0
            for (j in 0 until 22) { ecb += e[j] * spread[j][i]; ct += cw[j] * spread[j][i]; norm += spread[j][i] }
            val cb = if (ecb > 0) ct / ecb else 1.0
            val tb = min(1.0, max(0.0, -0.299 - 0.43 * ln(max(cb, 1e-9))))
            val offsetDb = tb * 29.0 + (1 - tb) * 6.0
            var nb = ecb / norm * 10.0.pow(-offsetDb / 10)
            // Pre-echo control: the threshold can't jump far above the previous granule's.
            if (st.nbPrev[i] >= 0) nb = min(nb, 2 * st.nbPrev[i])
            st.nbPrev[i] = nb
            ratio[i] = if (e[i] > 0) nb / e[i] else 1.0
        }
        return ratio
    }

    /**
     * Allowed noise energy per band of [layout] (the masking ratio of its frequencies × its energy, at
     * least the threshold of hearing), and the perceptual entropy. In short blocks each window's band
     * has its own energy, so the quiet windows before an attack get little noise.
     */
    private fun allowed(xr: DoubleArray, ratio: DoubleArray, layout: Layout): Pair<DoubleArray, Double> {
        val short = layout === shortLayout
        val ath = if (short) athShort else athLong
        val xmin = DoubleArray(layout.size)
        val en = DoubleArray(layout.size)
        for (k in 0 until layout.size) {
            var e = 0.0
            for (i in layout.start[k] until layout.end[k]) e += xr[i] * xr[i]
            en[k] = e
            xmin[k] = e * ratio[layout.freqBand[k]]
        }
        for (k in 0 until layout.size) xmin[k] = max(xmin[k], ath[k])
        var pe = 0.0
        for (k in 0 until layout.size) if (en[k] > xmin[k]) pe += (layout.end[k] - layout.start[k]) * ln(en[k] / xmin[k]) / ln(2.0) * 0.5
        return xmin to pe
    }

    // ------------------------------------------------------------------ quantisation
    private class Granule(val type: Int, val layout: Layout) {
        val ix = IntArray(576)
        val sign = BooleanArray(576)
        val sf = IntArray(39)
        var sfScale = 0
        var sfCompress = 0
        var globalGain = 210
        var part23 = 0
        var part2 = 0
        var huffBits = 0
        var bigValues = 0
        val tableSelect = IntArray(3)
        var region0 = 0
        var region1 = 0
        var count1Table = 0
        var count1 = 0
    }

    /** Quantises one granule's spectrum into [maxBits]: the outer loop shapes the noise under [xmin], the inner one fits the bits. */
    private fun quantize(xr: DoubleArray, xmin: DoubleArray, maxBits: Int, type: Int, layout: Layout): Granule {
        val g = Granule(type, layout)
        val xr34 = DoubleArray(576) { abs(xr[it]).pow(0.75) }
        for (i in 0 until 576) g.sign[i] = xr[i] < 0
        if (xr34.all { it < 1e-12 }) { g.globalGain = 0; return g }
        val sf = IntArray(39)
        var sfScale = 0
        // The best result so far: its values, scalefactors and gain.
        val bestIx = IntArray(576); val bestSf = IntArray(39)
        var bestScale = 0; var bestGain = -1; var bestPart2 = 0
        var bestScore = Double.MAX_VALUE
        var stall = 0
        var gain = -1
        val nmr = DoubleArray(layout.size)
        for (iter in 0 until 30) {
            // Inner loop: the smallest gain whose bits fit (more gain, coarser steps, fewer bits).
            val part2 = scalefactorBits(sf, type)?.first ?: break
            gain = innerLoop(xr34, sf, sfScale, maxBits - part2, g, gain) ?: break
            noiseToMask(xr, xmin, g.ix, gain, sf, sfScale, layout, nmr)
            var over = 0; var overSum = 0.0; var worst = 0.0
            for (k in 0 until layout.size) {
                worst = max(worst, nmr[k])
                if (nmr[k] > 1.0) { over++; overSum += 10 * log10(nmr[k]) }
            }
            // Best: the lowest worst-band noise-to-mask ratio, then the least noise over the masks in all.
            val score = 1000 * log10(max(worst, 1e-9)) + overSum
            if (score < bestScore) {
                bestScore = score; stall = 0
                System.arraycopy(g.ix, 0, bestIx, 0, 576); System.arraycopy(sf, 0, bestSf, 0, 39)
                bestScale = sfScale; bestGain = gain; bestPart2 = part2
            } else if (++stall >= STALL) break
            if (over == 0) break
            // Outer loop: amplify the noisiest bands (their steps get finer): those within half (in dB)
            // of the worst, so that when the bits can't cover everything the noise still follows the
            // masking. (Amplifying every band over its mask would mostly just shift the global gain.)
            val trigger = sqrt(worst)
            var changed = false
            for (k in 0 until layout.size) if (nmr[k] > 1.0 && nmr[k] >= trigger) {
                val idx = layout.sfIndex[k]
                if (idx >= 0 && sf[idx] < layout.maxSf[k]) { sf[idx]++; changed = true }
            }
            if (!changed) {
                if (sfScale == 0) { sfScale = 1; for (k in sf.indices) sf[k] = (sf[k] + 1) / 2; gain = -1; continue }
                break
            }
        }
        if (bestGain < 0) return Granule(type, layout).also { it.globalGain = 0 }   // nothing fitted (not expected): silence
        System.arraycopy(bestIx, 0, g.ix, 0, 576); System.arraycopy(bestSf, 0, g.sf, 0, 39)
        g.sfScale = bestScale; g.globalGain = bestGain; g.part2 = bestPart2
        g.sfCompress = scalefactorBits(bestSf, type)!!.second
        // Final Huffman layout with the best region split (never more bits than the usual one).
        layout(g, exact = true)
        g.part23 = g.part2 + g.huffBits
        return g
    }

    /** Each band's quantisation noise over what [xmin] allows, into [nmr]. */
    private fun noiseToMask(xr: DoubleArray, xmin: DoubleArray, ix: IntArray, gain: Int, sf: IntArray, sfScale: Int, layout: Layout, nmr: DoubleArray) {
        val mult = if (sfScale == 0) 0.5 else 1.0
        for (k in 0 until layout.size) {
            var noise = 0.0
            val idx = layout.sfIndex[k]
            val step = 2.0.pow((gain - 210) / 4.0 - mult * (if (idx >= 0) sf[idx] else 0))
            for (i in layout.start[k] until layout.end[k]) {
                val q = if (ix[i] == 0) 0.0 else POW43[min(ix[i], POW43.size - 1)] * step
                val d = abs(xr[i]) - q
                noise += d * d
            }
            nmr[k] = noise / max(xmin[k], 1e-20)
        }
    }

    /**
     * The smallest global gain at which the Huffman bits fit in [budget]; null when even the coarsest
     * doesn't. Starts from [hint] (the last gain, as it changes little between outer loop rounds) and
     * widens the steps until it brackets the answer, then halves them.
     */
    private fun innerLoop(xr34: DoubleArray, sf: IntArray, sfScale: Int, budget: Int, g: Granule, hint: Int): Int? {
        if (budget < 0) return null
        fun fits(gain: Int) = quantizeAt(xr34, sf, sfScale, gain, g.ix, g.layout) && run { layout(g, exact = false); g.huffBits <= budget }
        var lo: Int; var hi: Int                      // lo doesn't fit (or −1), hi fits
        if (hint < 0) { lo = -1; hi = 255; if (!fits(255)) return null }
        else if (fits(hint)) {
            hi = hint; var step = 1
            while (true) { val t = hi - step; if (t < 0) { lo = -1; break }; if (fits(t)) { hi = t; step *= 2 } else { lo = t; break } }
        } else {
            lo = hint; var step = 1
            while (true) { val t = min(255, lo + step); if (fits(t)) { hi = t; break }; if (t == 255) return null; lo = t; step *= 2 }
        }
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (fits(mid)) hi = mid else lo = mid }
        fits(hi)
        return hi
    }

    /** ix = round((|xr|·2^(−(gain−210)/4 + m·sf))^(3/4)); false when a value is too big for the tables. */
    private fun quantizeAt(xr34: DoubleArray, sf: IntArray, sfScale: Int, gain: Int, ix: IntArray, layout: Layout): Boolean {
        val mult = if (sfScale == 0) 0.5 else 1.0
        for (k in 0 until layout.size) {
            val idx = layout.sfIndex[k]
            val f = 2.0.pow(-0.1875 * (gain - 210) + 0.75 * mult * (if (idx >= 0) sf[idx] else 0))
            for (i in layout.start[k] until layout.end[k]) {
                val v = (xr34[i] * f + 0.4054).toInt()
                if (v > 8206) return false
                ix[i] = v
            }
        }
        return true
    }

    /**
     * Scalefactor bits and the scalefac_compress index; null when none fits. Long blocks: bands 0–10
     * take slen1 bits each and 11–20 slen2; short blocks: bands 0–5 slen1 and 6–11 slen2, three windows each.
     */
    private fun scalefactorBits(sf: IntArray, type: Int): Pair<Int, Int>? {
        var m1 = 0; var m2 = 0
        val n1: Int; val n2: Int
        if (type == SHORT) {
            for (k in 0 until 18) m1 = max(m1, sf[k])
            for (k in 18 until 36) m2 = max(m2, sf[k])
            n1 = 18; n2 = 18
        } else {
            for (b in 0 until 11) m1 = max(m1, sf[b])
            for (b in 11 until 21) m2 = max(m2, sf[b])
            n1 = 11; n2 = 10
        }
        var bestBits = Int.MAX_VALUE; var bestIdx = -1
        for (k in 0 until 16) {
            val s1 = SLEN[k][0]; val s2 = SLEN[k][1]
            if (m1 < (1 shl s1) && m2 < (1 shl s2)) {
                val bits = n1 * s1 + n2 * s2
                if (bits < bestBits) { bestBits = bits; bestIdx = k }
            }
        }
        return if (bestIdx < 0) null else bestBits to bestIdx
    }

    // ------------------------------------------------------------------ Huffman layout and bit counts
    /**
     * Splits [g]'s values into big values (pairs), count1 (quadruples of 0/±1) and zeros, picks the
     * regions and tables and counts the bits. Long blocks: [exact] tries every region split (the final
     * pass), otherwise the usual split. Start, short and stop blocks have fixed regions (36 values, then the rest).
     */
    private fun layout(g: Granule, exact: Boolean) {
        val ix = g.ix
        var i = 576
        while (i > 1 && ix[i - 1] == 0 && ix[i - 2] == 0) i -= 2
        var count1 = 0
        while (i > 3 && ix[i - 1] <= 1 && ix[i - 2] <= 1 && ix[i - 3] <= 1 && ix[i - 4] <= 1) { count1++; i -= 4 }
        val big = i / 2
        g.bigValues = big; g.count1 = count1
        // Count1 region: table A or B, whichever is shorter.
        var a = 0; var b = 0
        var p = big * 2
        for (q in 0 until count1) {
            val v = ix[p] * 8 + ix[p + 1] * 4 + ix[p + 2] * 2 + ix[p + 3]
            val signs = ix[p] + ix[p + 1] + ix[p + 2] + ix[p + 3]
            a += Mp3Tables.QUAD_LEN[0][v] + signs; b += 4 + signs
            p += 4
        }
        g.count1Table = if (b < a) 1 else 0
        var bits = min(a, b)
        val end = big * 2
        g.tableSelect.fill(0)
        if (g.type != LONG) {
            val e0 = min(end, 36)
            val (t0, c0) = bestTable(ix, 0, e0)
            val (t1, c1) = bestTable(ix, e0, end)
            g.tableSelect[0] = t0; g.tableSelect[1] = t1
            g.huffBits = bits + c0 + c1
            return
        }
        if (end == 0) { g.region0 = 0; g.region1 = 0; g.huffBits = bits; return }
        // The usual split: about a third and a half of the big values' bands.
        var bands = 0
        while (bands < 22 && bandStart[bands + 1] <= end) bands++
        val u0 = min(15, max(0, (bands + 1) / 3 - 1))
        val u1 = min(7, max(0, (bands + 1) / 2 - u0 - 2))
        if (!exact) {
            bits += regions(g, end, u0, u1)
        } else {
            var bestBits = regions(g, end, u0, u1); var b0 = u0; var b1 = u1
            for (r0 in 0 until 16) {
                if (bandStart[min(22, r0 + 1)] > end && r0 > 0) break
                for (r1 in 0 until 8) {
                    if (r0 + r1 + 2 > 22) break
                    val c = regions(g, end, r0, r1)
                    if (c < bestBits) { bestBits = c; b0 = r0; b1 = r1 }
                    if (bandStart[min(22, r0 + r1 + 2)] >= end) break
                }
            }
            bits += regions(g, end, b0, b1)
        }
        g.huffBits = bits
    }

    /** Bits of a long block's big values with region 0 of [r0]+1 bands and region 1 of [r1]+1; sets the tables. */
    private fun regions(g: Granule, end: Int, r0: Int, r1: Int): Int {
        g.region0 = r0; g.region1 = r1
        val e0 = min(end, bandStart[min(22, r0 + 1)])
        val e1 = min(end, bandStart[min(22, r0 + r1 + 2)])
        var bits = 0
        val bounds = intArrayOf(0, e0, e1, end)
        for (r in 0 until 3) {
            val (t, c) = bestTable(g.ix, bounds[r], bounds[r + 1])
            g.tableSelect[r] = t
            bits += c
        }
        return bits
    }

    /** The table that codes ix[from until to] (pairs) in the fewest bits, and those bits. */
    private fun bestTable(ix: IntArray, from: Int, to: Int): Pair<Int, Int> {
        if (to <= from) return 0 to 0
        var m = 0
        for (i in from until to) m = max(m, ix[i])
        if (m == 0) return 0 to 0
        val family = when { m <= 1 -> 0; m <= 2 -> 1; m <= 3 -> 2; m <= 5 -> 3; m <= 7 -> 4; m <= 15 -> 5; else -> 6 }
        // One pass adds up every candidate's code lengths at once (packed side by side in a Long).
        val packed = PACKED[family]
        var sum = 0L; var signs = 0; var escapes = 0
        var i = from
        while (i < to) {
            val x = ix[i]; val y = ix[i + 1]
            if (x != 0) signs++
            if (y != 0) signs++
            if (x >= 15) escapes++
            if (y >= 15) escapes++
            sum += packed[min(x, 15) * 16 + min(y, 15)]
            i += 2
        }
        val tables = FAMILIES[family]
        var bestT = 0; var bestBits = Int.MAX_VALUE
        for (k in tables.indices) {
            var t = tables[k]
            var bits = ((sum ushr (21 * k)) and 0x1FFFFF).toInt() + signs
            if (family == 6) {
                // Escapes: the smallest table of this code whose extra bits hold the largest value.
                t = if (k == 0) 16 else 24
                while ((1 shl Mp3Tables.LINBITS[t]) <= m - 15) t++
                bits += escapes * Mp3Tables.LINBITS[t]
            }
            if (bits < bestBits) { bestBits = bits; bestT = t }
        }
        return bestT to bestBits
    }

    private fun writeMain(w: BitWriter, g: Granule) {
        val before = w.bitCount
        val s1 = SLEN[g.sfCompress][0]; val s2 = SLEN[g.sfCompress][1]
        if (g.type == SHORT) {
            // Bands 0–5 (slen1 bits) and 6–11 (slen2), each window by window.
            for (k in 0 until 36) { val bits = if (k < 18) s1 else s2; if (bits > 0) w.put(g.sf[k], bits) }
        } else {
            // Bands 0–10 (slen1 bits) and 11–20 (slen2).
            for (b in 0 until 11) if (s1 > 0) w.put(g.sf[b], s1)
            for (b in 11 until 21) if (s2 > 0) w.put(g.sf[b], s2)
        }
        val end = g.bigValues * 2
        val bounds = if (g.type == LONG) {
            val e0 = min(end, bandStart[min(22, g.region0 + 1)])
            val e1 = min(end, bandStart[min(22, g.region0 + g.region1 + 2)])
            intArrayOf(0, e0, e1, end)
        } else intArrayOf(0, min(end, 36), end, end)
        for (r in 0 until 3) {
            val t = g.tableSelect[r]
            if (t == 0) continue
            val h = Mp3Tables.TABLE[t]
            val xlen = Mp3Tables.XLEN[h]; val code = Mp3Tables.CODES[h]; val len = Mp3Tables.LENS[h]; val lin = Mp3Tables.LINBITS[t]
            var i = bounds[r]
            while (i < bounds[r + 1]) {
                val x = g.ix[i]; val y = g.ix[i + 1]
                val xx = if (lin > 0 && x >= 15) 15 else x
                val yy = if (lin > 0 && y >= 15) 15 else y
                w.put(code[xx * xlen + yy], len[xx * xlen + yy])
                if (lin > 0 && x >= 15) w.put(x - 15, lin)
                if (x != 0) w.put(if (g.sign[i]) 1 else 0, 1)
                if (lin > 0 && y >= 15) w.put(y - 15, lin)
                if (y != 0) w.put(if (g.sign[i + 1]) 1 else 0, 1)
                i += 2
            }
        }
        var p = end
        val qt = g.count1Table
        for (q in 0 until g.count1) {
            val v = g.ix[p] * 8 + g.ix[p + 1] * 4 + g.ix[p + 2] * 2 + g.ix[p + 3]
            w.put(Mp3Tables.QUAD_CODE[qt][v], Mp3Tables.QUAD_LEN[qt][v])
            for (k in 0 until 4) if (g.ix[p + k] != 0) w.put(if (g.sign[p + k]) 1 else 0, 1)
            p += 4
        }
        check(w.bitCount - before == g.part23.toLong()) { "bit count ${w.bitCount - before} ≠ ${g.part23}" }
    }

    // ------------------------------------------------------------------ the Info frame
    private fun infoFrame(): ByteArray {
        val f = ByteArray(infoFrameLen)
        val h = BitWriter()
        h.put(0x7FF, 11); h.put(3, 2); h.put(1, 2); h.put(1, 1); h.put(infoBrIndex, 4); h.put(sfIndex, 2); h.put(0, 1); h.put(0, 1)
        h.put(if (channels == 1) 3 else 1, 2); h.put(0, 2); h.put(0, 1); h.put(1, 1); h.put(0, 2)
        System.arraycopy(h.bytes(), 0, f, 0, 4)
        var p = 4 + sideInfoBytes
        fun be32(v: Long) { f[p] = (v ushr 24).toByte(); f[p + 1] = (v ushr 16).toByte(); f[p + 2] = (v ushr 8).toByte(); f[p + 3] = v.toByte(); p += 4 }
        "Info".toByteArray().copyInto(f, p); p += 4
        be32(0x0FL)                                         // frames, bytes, seek table, quality
        be32(framesWritten + 1)
        be32(infoFrameLen + writtenFrameBytes)
        // Seek table: CBR, so evenly spaced.
        for (k in 0 until 100) f[p++] = (k * 256 / 100).toByte()
        be32(100L)
        // A LAME-format tag with this encoder's own name: the delay and padding for gapless playback.
        "LMT1.13  ".toByteArray().copyInto(f, p); p += 9
        p += 12                                              // revision/VBR, lowpass, peak, gains, flags, bitrate
        val delay = DELAY - DECODER_DELAY
        val padding = (framesWritten * 1152 - samplesIn - delay).coerceIn(0, 4095).toInt()
        f[p] = (delay shr 4).toByte(); f[p + 1] = (((delay and 15) shl 4) or (padding shr 8)).toByte(); f[p + 2] = padding.toByte()
        return f
    }

    companion object {
        val BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private const val LONG = 0
        private const val START = 1
        private const val SHORT = 2
        private const val STOP = 3
        /** Short-block band widths per window (ISO 11172-3 table B.8). */
        private val SHORT_44100 = intArrayOf(4, 4, 4, 4, 6, 8, 10, 12, 14, 18, 22, 30, 56)
        private val SHORT_48000 = intArrayOf(4, 4, 4, 4, 6, 6, 10, 12, 14, 16, 20, 26, 66)
        private val SHORT_32000 = intArrayOf(4, 4, 4, 4, 6, 8, 12, 16, 20, 26, 34, 42, 12)
        private val SLEN = arrayOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(0, 3), intArrayOf(3, 0), intArrayOf(1, 1), intArrayOf(1, 2),
            intArrayOf(1, 3), intArrayOf(2, 1), intArrayOf(2, 2), intArrayOf(2, 3), intArrayOf(3, 1), intArrayOf(3, 2), intArrayOf(3, 3), intArrayOf(4, 2), intArrayOf(4, 3))
        private val SQRT_HALF = sqrt(0.5)
        /** Samples between input and decoded output: the filter bank and MDCT (measured), and the part decoders add. */
        const val DELAY = 1057
        const val DECODER_DELAY = 529
        /** The most main data bits a frame can leave for the next (main_data_begin ≤ 511 bytes). */
        private const val MAX_CARRY = 511 * 8
        /** How far the subband samples lag the input (the analysis filter's centre). */
        private const val FILTER_DELAY = 225
        /** How far the spectral lines lag the input (the filter bank and MDCT), so the FFT looks at the same sound. */
        private const val PSY_LAG = 480
        /** An attack: a third of a granule with this many times the high-frequency energy of the three before… */
        private const val ATTACK_RATIO = 8.0
        /** …and above this (mean squared difference of samples; about −57 dB). */
        private const val ATTACK_FLOOR = 2e-6
        /** Candidate tables for the largest value in a region (≤1, ≤2, ≤3, ≤5, ≤7, ≤15, more: escape codes 16… and 24…). */
        private val FAMILIES = arrayOf(intArrayOf(1, 2, 3), intArrayOf(2, 3), intArrayOf(5, 6), intArrayOf(7, 8, 9), intArrayOf(10, 11, 12), intArrayOf(13, 15), intArrayOf(16, 24))
        /** Per family and value pair (x, y ≤ 15): the candidates' code lengths, 21 bits each. */
        private val PACKED = Array(FAMILIES.size) { f ->
            LongArray(256) { xy ->
                val x = xy / 16; val y = xy % 16
                var v = 0L
                for ((k, t) in FAMILIES[f].withIndex()) {
                    val h = Mp3Tables.TABLE[t]; val xl = Mp3Tables.XLEN[h]
                    if (x < xl && y < xl) v = v or (Mp3Tables.LENS[h][x * xl + y].toLong() shl (21 * k))
                }
                v
            }
        }
        /** The outer loop stops after this many rounds without a better result. */
        private const val STALL = 6
        /** Bits a granule asks for per bit of perceptual entropy. */
        private const val BITS_PER_PE = 3.1
        /** Calibrated so decoded levels equal the input's. */
        private const val MDCT_SCALE = 1.0 / 9
        private const val MDCT_SHORT_SCALE = 1.0 / 3
        /** MDCT energy of a full-scale sine in its strongest line (measured: 1.2–1.6), for the threshold of hearing. */
        private const val FULL_SCALE_ENERGY = 1.4

        private val MATRIX = Array(32) { k -> DoubleArray(64) { i -> cos((2 * k + 1) * (i - 16) * PI / 64) } }
        /** The 36-point windows of long, start, (short: unused) and stop blocks. */
        private val WINDOWS = Array(4) { type ->
            DoubleArray(36) { i ->
                val long = sin(PI / 36 * (i + 0.5))
                when (type) {
                    START -> when { i < 18 -> long; i < 24 -> 1.0; i < 30 -> sin(PI / 12 * (i - 18 + 0.5)); else -> 0.0 }
                    STOP -> when { i < 6 -> 0.0; i < 12 -> sin(PI / 12 * (i - 6 + 0.5)); i < 18 -> 1.0; else -> long }
                    else -> long
                }
            }
        }
        private val WIN_SHORT = DoubleArray(12) { sin(PI / 12 * (it + 0.5)) }
        private val MDCT_COS = Array(18) { k -> DoubleArray(36) { n -> cos(PI / 72 * (2 * n + 19) * (2 * k + 1)) } }
        private val MDCT_SHORT_COS = Array(6) { k -> DoubleArray(12) { n -> cos(PI / 24 * (2 * n + 7) * (2 * k + 1)) } }
        private val C = doubleArrayOf(-0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037)
        private val CS = DoubleArray(8) { 1 / sqrt(1 + C[it] * C[it]) }
        private val CA = DoubleArray(8) { C[it] / sqrt(1 + C[it] * C[it]) }
        private val POW43 = DoubleArray(8207) { it.toDouble().pow(4.0 / 3) }
        private val HANN = DoubleArray(1024) { 0.5 - 0.5 * cos(2 * PI * (it + 0.5) / 1024) }

        private fun bark(f: Double) = 13 * atan(0.00076 * f) + 3.5 * atan((f / 7500) * (f / 7500))

        /** Terhardt's threshold of hearing, dB SPL. */
        private fun athDb(f: Double): Double {
            val k = max(f, 20.0) / 1000
            return 3.64 * k.pow(-0.8) - 6.5 * exp(-0.6 * (k - 3.3) * (k - 3.3)) + 0.001 * k.pow(4)
        }

        /** In-place radix-2 complex FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                        re[i + k] += ar; im[i + k] += ai
                        val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}

/** A growable byte buffer that can hand out ranges by absolute position and forget what was taken. */
internal class ByteBuf {
    private var buf = ByteArray(1 shl 16)
    private var base = 0L           // absolute position of buf[0]
    private var n = 0

    fun add(b: Int) {
        if (n == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[n++] = b.toByte()
    }

    /** Bytes [at, at+len) (absolute); drops everything before at+len. */
    fun take(at: Long, len: Int): ByteArray {
        val from = (at - base).toInt()
        val r = buf.copyOfRange(from, from + len)
        val drop = from + len
        System.arraycopy(buf, drop, buf, 0, n - drop)
        n -= drop; base += drop
        return r
    }
}
