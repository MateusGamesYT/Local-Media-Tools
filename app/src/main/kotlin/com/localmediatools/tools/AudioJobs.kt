package com.localmediatools.tools

import com.localmediatools.audio.AudioAnalysis
import com.localmediatools.audio.AudioEngine
import com.localmediatools.audio.AudioInfo
import com.localmediatools.audio.AudioOutput
import com.localmediatools.audio.AudioProbe
import com.localmediatools.audio.AudioStream
import com.localmediatools.audio.core.AudioPlan
import com.localmediatools.audio.core.FadeCurve
import com.localmediatools.audio.core.FadeShaper
import com.localmediatools.audio.core.Joiner
import com.localmediatools.audio.core.Limiter
import com.localmediatools.audio.core.LoudnessMeter
import com.localmediatools.audio.core.Pcm
import com.localmediatools.audio.core.Span
import com.localmediatools.audio.core.SpanFilter
import com.localmediatools.audio.core.SpeedPitch
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.OutputFile
import com.localmediatools.core.OutputStore
import com.localmediatools.core.SkipItemException
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Shared steps of the audio tools. */
internal object AudioJobs {
    fun probe(ctx: JobContext, item: MediaItem): AudioInfo = AudioProbe.probe(ctx.app, item.uri)

    /** [out], or the original's kind of file when null. */
    fun output(out: AudioOutput?, info: AudioInfo): AudioOutput = out ?: AudioOutput.sameAs(info)

    fun db(v: Double) = String.format(Locale.ROOT, "%+.1f dB", v)
    fun lufs(v: Double) = if (v.isInfinite()) "−∞ LUFS" else String.format(Locale.ROOT, "%.1f LUFS", v)
    fun len(frames: Long, rate: Int) = Format.duration(frames * 1000 / rate.coerceAtLeast(1))

    /** Streams [item]'s sound (from [startUs] to [endUs]) at [rate] and [ch] into [emit], reporting progress for item [index]. */
    fun pump(ctx: JobContext, item: MediaItem, info: AudioInfo, rate: Int, ch: Int, index: Int, from: Double, to: Double,
             startUs: Long = 0, endUs: Long = Long.MAX_VALUE, emit: (FloatArray) -> Unit) {
        val span = ((if (endUs == Long.MAX_VALUE) info.durationUs else endUs) - startUs).coerceAtLeast(1)
        AudioStream(ctx.app, item.uri, rate, ch, startUs, endUs).use { s ->
            var n = 0
            while (true) {
                val x = s.next() ?: break
                if (x.isNotEmpty()) emit(x)
                if (++n % 8 == 0) { ctx.throttle(); ctx.unitProgress(index, from + (to - from) * ((s.positionUs - startUs).toDouble() / span).coerceIn(0.0, 1.0)) }
            }
        }
    }

    fun details(out: AudioOutput, frames: Long, rate: Int, file: OutputFile): String = "${len(frames, rate)} · ${out.label} · ${Format.bytes(file.size)}"

    /** A gain (dB) as a linear factor. */
    fun lin(db: Double) = 10.0.pow(db / 20).toFloat()
}

// =========================================================================== Trim
/**
 * Keeps the part of each file from [startF] to [endF] (fractions of its length) — or, with
 * [removeMiddle], everything but that part — with fades at the ends.
 */
class TrimAudioJob(
    inputs: List<MediaItem>, private val startF: Double, private val endF: Double, private val removeMiddle: Boolean,
    private val fadeInMs: Int, private val fadeOutMs: Int, private val out: AudioOutput?,
) : ExportJob(ToolId.TRIM_AUDIO, inputs) {
    override val title = trn(inputs.size, "Trimming {0} sound file", "Trimming {0} sound files")

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = AudioJobs.probe(ctx, item)
            if (info.durationUs <= 0) throw UserFacingException(tr("The length of this file couldn't be read."))
            val o = AudioJobs.output(out, info)
            val rate = o.rateFor(info.rate); val ch = o.channelsFor(info.channels)
            val s = (startF.coerceIn(0.0, 1.0) * info.durationUs).roundToLong(); val e = (endF.coerceIn(0.0, 1.0) * info.durationUs).roundToLong()
            if (e - s < 50_000 && !removeMiddle) throw UserFacingException(tr("The part to keep is too short."))
            val ranges = if (!removeMiddle) listOf(s to e) else listOf(0L to s, e to Long.MAX_VALUE).filter { it.second - it.first > 1000 }
            if (ranges.isEmpty()) throw UserFacingException(tr("Nothing would be left."))
            OutputStore.ensureSpace(item.size)
            val shaper = FadeShaper(ch, fadeInMs * rate / 1000L, (fadeOutMs * rate / 1000L).toInt(), FadeCurve.SMOOTH)
            var frames = 0L
            val file = AudioEngine.write(ctx, ToolId.TRIM_AUDIO.area, "${item.baseName}_trimmed", o, rate, ch, info.tags, info.rate) { sink ->
                fun put(x: FloatArray) { if (x.isNotEmpty()) { sink.write(x); frames += x.size / ch } }
                if (ranges.size == 1) {
                    AudioJobs.pump(ctx, item, info, rate, ch, index, 0.0, 1.0, ranges[0].first, ranges[0].second) { put(shaper.process(it)) }
                } else {
                    // The two outer parts, joined with a short crossfade so the cut doesn't click.
                    val join = Joiner(ch, rate / 100)
                    AudioJobs.pump(ctx, item, info, rate, ch, index, 0.0, 0.5, ranges[0].first, ranges[0].second) { put(shaper.process(join.add(it))) }
                    put(shaper.process(join.next(rate / 100, 0)))
                    AudioJobs.pump(ctx, item, info, rate, ch, index, 0.5, 1.0, ranges[1].first, ranges[1].second) { put(shaper.process(join.add(it))) }
                    put(shaper.process(join.finish()))
                }
                put(shaper.finish())
            }
            ItemResult(item.name, ItemOutcome.SUCCESS, null, listOf(file), AudioJobs.details(o, frames, rate, file))
        }
    }
}

// =========================================================================== Merge
/**
 * Joins the files in order into one, with [gapMs] of silence between them or overlapping by
 * [crossfadeMs]; [evenOut] brings them all to the same loudness first.
 */
class MergeAudioJob(
    inputs: List<MediaItem>, private val gapMs: Int, private val crossfadeMs: Int, private val evenOut: Boolean, private val out: AudioOutput?,
) : ExportJob(ToolId.MERGE_AUDIO, inputs) {
    override val title = trn(inputs.size, "Merging {0} sound file", "Merging {0} sound files")
    override val unitCount: Int get() = 1

    override suspend fun run(ctx: JobContext) {
        val label = trn(inputs.size, "{0} sound file", "{0} sound files")
        val r = ctx.runItem(inputs.first().copy(name = label, readError = null)) { merge(ctx) }
        if (r != null) ctx.addResult(r)
        ctx.unitDone(0)
    }

    private fun merge(ctx: JobContext): ItemResult {
        for (i in inputs) i.readError?.let { throw UserFacingException("\"${i.name}\": $it") }
        if (inputs.size < 2) throw UserFacingException(tr("Select at least two sound files to merge."))
        ctx.status(tr("Reading the files"))
        val infos = inputs.map { AudioJobs.probe(ctx, it) }
        val o = AudioJobs.output(out, infos[0])
        val rate = o.rateFor(infos[0].rate)
        val ch = if (o.channels in 1..2) o.channels else infos.maxOf { it.channels.coerceIn(1, 2) }
        OutputStore.ensureSpace(inputs.sumOf { it.size.coerceAtLeast(0) })
        // Even out: every file to the median loudness (the limiter catches the peaks that pushes up).
        val gains = if (!evenOut) List(inputs.size) { 1f } else {
            val l = inputs.mapIndexed { k, it ->
                ctx.status(tr("Measuring loudness"), it.name)
                AudioEngine.analyze(ctx.app, infos[k], it.uri, { f -> ctx.unitProgress(0, 0.3 * (k + f) / inputs.size) }, { ctx.checkCancelled() }, channels = ch).loudness.integratedLufs
            }
            val finite = l.filter { !it.isInfinite() }.sorted()
            val target = if (finite.isEmpty()) 0.0 else finite[finite.size / 2]
            l.map { if (it.isInfinite()) 1f else AudioJobs.lin(target - it) }
        }
        val lengths = infos.map { it.durationUs * rate / 1_000_000 }
        val crossfade = crossfadeMs.toLong() * rate / 1000
        val gap = (gapMs.toLong() * rate / 1000).toInt()
        val join = Joiner(ch, crossfade.toInt().coerceAtLeast(0))
        val limiter = if (evenOut) Limiter(rate, ch) else null
        var frames = 0L
        val base = if (evenOut) 0.3 else 0.0
        val tags = infos[0].tags.copy(title = null, track = null)
        val file = AudioEngine.write(ctx, ToolId.MERGE_AUDIO.area, "${inputs[0].baseName}_merged", o, rate, ch, tags, infos[0].rate) { sink ->
            fun put(x: FloatArray) { val y = limiter?.process(x) ?: x; if (y.isNotEmpty()) { sink.write(y); frames += y.size / ch } }
            for ((k, item) in inputs.withIndex()) {
                ctx.status(tr("Joining"), item.name)
                if (k > 0) put(join.next(AudioPlan.overlap(lengths, k, crossfade).toInt(), gap))
                val g = gains[k]
                AudioJobs.pump(ctx, item, infos[k], rate, ch, 0, base + (1 - base) * k / inputs.size, base + (1 - base) * (k + 1) / inputs.size) { x ->
                    Pcm.gain(x, x.size, g); put(join.add(x))
                }
            }
            put(join.finish())
            limiter?.let { val y = it.flush(); if (y.isNotEmpty()) { sink.write(y); frames += y.size / ch } }
        }
        val how = when {
            crossfadeMs > 0 -> tr("crossfaded over {0}", Format.seconds(crossfadeMs / 1000.0))
            gapMs > 0 -> tr("{0} of silence between", Format.seconds(gapMs / 1000.0))
            else -> tr("one after another")
        }
        return ItemResult(inputs.first().name, ItemOutcome.SUCCESS, if (evenOut) tr("Volume evened out to the same loudness.") else null, listOf(file),
            AudioJobs.details(o, frames, rate, file) + " · " + how)
    }
}

// =========================================================================== Convert
/** Converts each file to [out] (format, quality, channels, sample rate), keeping its tags and cover. */
class ConvertAudioJob(inputs: List<MediaItem>, private val out: AudioOutput) : ExportJob(ToolId.CONVERT_AUDIO, inputs) {
    override val title = trn(inputs.size, "Converting {0} sound file to {1}", "Converting {0} sound files to {1}", out.format.label)

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = AudioJobs.probe(ctx, item)
            val rate = out.rateFor(info.rate); val ch = out.channelsFor(info.channels)
            OutputStore.ensureSpace(if (out.format.lossless) info.durationUs / 1_000_000 * rate * ch * 3 else item.size)
            var frames = 0L
            val file = AudioEngine.write(ctx, ToolId.CONVERT_AUDIO.area, item.baseName, out, rate, ch, info.tags, info.rate) { sink ->
                AudioJobs.pump(ctx, item, info, rate, ch, index, 0.0, 1.0) { sink.write(it); frames += it.size / ch }
            }
            val note = if (out.format.lossless && info.mime != "audio/flac" && info.mime != "audio/wav" && info.mime != "audio/raw")
                tr("A lossless copy of {0} sound keeps exactly what it had, but can't bring back what {0} left out.", info.codec) else null
            ItemResult(item.name, ItemOutcome.SUCCESS, note, listOf(file), "${info.codec} → " + AudioJobs.details(out, frames, rate, file))
        }
    }
}

// =========================================================================== Volume
enum class VolumeMode { LOUDNESS, PEAK, GAIN }

/**
 * Changes the volume: to a loudness ([targetLufs], EBU R128 / BS.1770), to a peak level
 * ([peakDb], true peak), or by a fixed [gainDb]. [limit] keeps true peaks under −1 dBTP with the
 * limiter; [album] measures all files together and gives them one gain, keeping their differences.
 */
class VolumeJob(
    inputs: List<MediaItem>, private val mode: VolumeMode, private val targetLufs: Double, private val peakDb: Double,
    private val gainDb: Double, private val limit: Boolean, private val album: Boolean, private val out: AudioOutput?,
) : ExportJob(ToolId.AUDIO_VOLUME, inputs) {
    override val title = trn(inputs.size, "Adjusting the volume of {0} sound file", "Adjusting the volume of {0} sound files")

    override suspend fun run(ctx: JobContext) {
        val infos = HashMap<Int, AudioInfo>()
        val analyses = HashMap<Int, AudioAnalysis>()
        var albumGain: Double? = null
        if (album && mode != VolumeMode.GAIN && inputs.size > 1) {
            // One gain for all: measured over all the files together.
            val blocks = ArrayList<DoubleArray>(); var peak = Double.NEGATIVE_INFINITY
            for ((k, item) in inputs.withIndex()) {
                if (item.readError != null) continue
                ctx.status(tr("Measuring loudness"), item.name)
                try {
                    val info = AudioJobs.probe(ctx, item); infos[k] = info
                    val a = AudioEngine.analyze(ctx.app, info, item.uri, { f -> ctx.unitProgress(k, 0.5 * f) }, { ctx.checkCancelled() }, channels = AudioJobs.output(out, info).channelsFor(info.channels))
                    analyses[k] = a; blocks.add(a.blocks); peak = maxOf(peak, a.loudness.truePeakDb)
                } catch (e: UserFacingException) { /* reported when the file itself is processed */ }
            }
            albumGain = when (mode) {
                VolumeMode.LOUDNESS -> AudioPlan.normalizeGain(LoudnessMeter.integrated(blocks), peak, targetLufs, -1.0, limit)
                else -> peakDb - peak
            }
        }
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = infos[index] ?: AudioJobs.probe(ctx, item)
            val o = AudioJobs.output(out, info)
            val rate = o.rateFor(info.rate); val ch = o.channelsFor(info.channels)
            val base = if (mode == VolumeMode.GAIN) 0.0 else 0.5
            val a = if (mode == VolumeMode.GAIN) null else analyses[index] ?: run {
                ctx.status(tr("Measuring loudness"), item.name)
                AudioEngine.analyze(ctx.app, info, item.uri, { f -> ctx.unitProgress(index, 0.5 * f) }, { ctx.checkCancelled() }, channels = ch)
            }
            val gain = albumGain ?: when (mode) {
                VolumeMode.LOUDNESS -> AudioPlan.normalizeGain(a!!.loudness.integratedLufs, a.loudness.truePeakDb, targetLufs, -1.0, limit)
                VolumeMode.PEAK -> peakDb - a!!.loudness.truePeakDb
                VolumeMode.GAIN -> gainDb
            }
            if (gain.isNaN() || gain.isInfinite()) throw SkipItemException(tr("This file is silent, so its volume can't be set."))
            if (abs(gain) < 0.05 && out == null) throw SkipItemException(tr("Already at that volume ({0}), so nothing was changed.", AudioJobs.db(gain)))
            val g = AudioJobs.lin(gain)
            // The limiter is needed only when the gain would push peaks over the ceiling.
            val peakAfter = (a?.loudness?.truePeakDb ?: 0.0) + gain
            val limiter = if (limit && (a == null || peakAfter > -1.0)) Limiter(rate, ch) else null
            OutputStore.ensureSpace(item.size)
            var frames = 0L
            val file = AudioEngine.write(ctx, ToolId.AUDIO_VOLUME.area, "${item.baseName}_volume", o, rate, ch, info.tags, info.rate) { sink ->
                AudioJobs.pump(ctx, item, info, rate, ch, index, base, 1.0) { x ->
                    Pcm.gain(x, x.size, g)
                    val y = limiter?.process(x) ?: x
                    if (y.isNotEmpty()) { sink.write(y); frames += y.size / ch }
                }
                limiter?.let { val y = it.flush(); if (y.isNotEmpty()) { sink.write(y); frames += y.size / ch } }
            }
            val what = when (mode) {
                VolumeMode.LOUDNESS -> "${AudioJobs.lufs(a!!.loudness.integratedLufs)} → ${AudioJobs.lufs(a.loudness.integratedLufs + gain)} (${AudioJobs.db(gain)})"
                VolumeMode.PEAK -> tr("peak {0} → {1}", AudioJobs.db(a!!.loudness.truePeakDb).removePrefix("+"), AudioJobs.db(a.loudness.truePeakDb + gain).removePrefix("+")) + " (${AudioJobs.db(gain)})"
                VolumeMode.GAIN -> AudioJobs.db(gain)
            }
            val note = when {
                limiter != null && limiter.minGain < 0.89 -> tr("Peaks were held under −1 dBTP by the limiter (by up to {0}).", AudioJobs.db(20 * kotlin.math.log10(limiter.minGain)))
                mode == VolumeMode.LOUDNESS && !limit && a != null && gain < targetLufs - a.loudness.integratedLufs - 0.05 ->
                    tr("Reached {0} instead: louder would have clipped its peaks. Turn on the limiter to go all the way.", AudioJobs.lufs(a.loudness.integratedLufs + gain))
                else -> null
            }
            ItemResult(item.name, ItemOutcome.SUCCESS, note, listOf(file), "$what · " + AudioJobs.details(o, frames, rate, file))
        }
    }
}

// =========================================================================== Speed & pitch
/** Plays each file at [tempo] (1 = unchanged) and moves its pitch by [semitones], independently. */
class AudioSpeedJob(inputs: List<MediaItem>, private val tempo: Double, private val semitones: Double, private val out: AudioOutput?) : ExportJob(ToolId.AUDIO_SPEED, inputs) {
    override val title = trn(inputs.size, "Changing the speed and pitch of {0} sound file", "Changing the speed and pitch of {0} sound files")

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            if (abs(tempo - 1) < 1e-6 && abs(semitones) < 1e-6) throw SkipItemException(tr("Speed and pitch are unchanged, so there is nothing to do."))
            val info = AudioJobs.probe(ctx, item)
            val o = AudioJobs.output(out, info)
            val rate = o.rateFor(info.rate); val ch = o.channelsFor(info.channels)
            OutputStore.ensureSpace((item.size / tempo).toLong())
            val sp = SpeedPitch(rate, ch, tempo, semitones)
            var frames = 0L
            val file = AudioEngine.write(ctx, ToolId.AUDIO_SPEED.area, "${item.baseName}_${label(tempo, semitones)}", o, rate, ch, info.tags, info.rate) { sink ->
                AudioJobs.pump(ctx, item, info, rate, ch, index, 0.0, 1.0) { x -> val y = sp.process(x); if (y.isNotEmpty()) { sink.write(y); frames += y.size / ch } }
                val y = sp.flush(); if (y.isNotEmpty()) { sink.write(y); frames += y.size / ch }
            }
            ItemResult(item.name, ItemOutcome.SUCCESS, null, listOf(file),
                "${AudioJobs.len(info.frames, info.rate)} → " + AudioJobs.details(o, frames, rate, file))
        }
    }

    companion object {
        /** "1.25x", "-3st", "0.8x+2st" (for file names). */
        fun label(tempo: Double, semitones: Double): String {
            val parts = ArrayList<String>()
            if (abs(tempo - 1) > 1e-6) parts.add(String.format(Locale.ROOT, "%sx", trimZeros(tempo)))
            if (abs(semitones) > 1e-6) parts.add(String.format(Locale.ROOT, "%s%sst", if (semitones > 0) "+" else "", trimZeros(semitones)))
            return parts.joinToString("").ifEmpty { "1x" }
        }
        private fun trimZeros(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}

// =========================================================================== Split
enum class SplitMode { LENGTH, PARTS, SILENCE }

/**
 * Splits each file: into parts of [seconds], into [parts] equal parts, or at silences of at least
 * [silenceMs] (like the tracks of a recorded album). With [snap], cuts move into the nearest quiet
 * moment within a few seconds.
 */
class SplitAudioJob(
    inputs: List<MediaItem>, private val mode: SplitMode, private val seconds: Double, private val parts: Int,
    private val silenceMs: Int, private val snap: Boolean, private val out: AudioOutput?,
) : ExportJob(ToolId.SPLIT_AUDIO, inputs) {
    override val title = trn(inputs.size, "Splitting {0} sound file", "Splitting {0} sound files")

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = AudioJobs.probe(ctx, item)
            val o = AudioJobs.output(out, info)
            val rate = o.rateFor(info.rate); val ch = o.channelsFor(info.channels)
            val needLevels = snap || mode == SplitMode.SILENCE
            val a = if (needLevels) { ctx.status(tr("Finding the quiet moments"), item.name); AudioEngine.analyze(ctx.app, info, item.uri, { f -> ctx.unitProgress(index, 0.3 * f) }, { ctx.checkCancelled() }) } else null
            val total = a?.frames ?: info.frames
            val srcRate = a?.rate ?: info.rate
            val spans: List<Span> = when (mode) {
                SplitMode.SILENCE -> {
                    val th = AudioPlan.silenceThreshold(a!!.levelsDb, AudioPlan.SENSITIVITY_SILENCE + 4)
                    val sil = AudioPlan.silences(a.levelsDb, a.window, total, th, silenceMs.toLong() * srcRate / 1000)
                    AudioPlan.keep(sil, total, ends = true, pauses = true, keepPause = srcRate / 5L).filter { it.length > srcRate / 2 }
                }
                else -> {
                    val sil = a?.let { AudioPlan.silences(it.levelsDb, it.window, total, AudioPlan.silenceThreshold(it.levelsDb), srcRate / 10L) } ?: emptyList()
                    AudioPlan.split(total, if (mode == SplitMode.LENGTH) (seconds * srcRate).roundToLong() else 0, parts, sil, if (snap) 3L * srcRate else 0)
                }
            }
            if (spans.size < 2) throw SkipItemException(when (mode) {
                SplitMode.SILENCE -> tr("No silence long enough to split at was found.")
                else -> tr("{0} long — already within one part, nothing to split.", AudioJobs.len(total, srcRate))
            })
            OutputStore.ensureSpace(item.size * 2)
            val files = ArrayList<OutputFile>()
            val digits = spans.size.toString().length.coerceAtLeast(2)
            val base = if (needLevels) 0.3 else 0.0
            for ((k, sp) in spans.withIndex()) {
                val n = (k + 1).toString().padStart(digits, '0')
                val tags = info.tags.copy(title = info.tags.title?.let { "$it (${k + 1}/${spans.size})" }, track = "${k + 1}/${spans.size}")
                val startUs = sp.start * 1_000_000 / srcRate; val endUs = if (sp.end >= total) Long.MAX_VALUE else sp.end * 1_000_000 / srcRate
                val fades = FadeShaper(ch, if (k > 0) rate / 200L else 0, if (k < spans.size - 1) rate / 200 else 0)
                files.add(AudioEngine.write(ctx, ToolId.SPLIT_AUDIO.area, "${item.baseName}_part$n", o, rate, ch, tags, info.rate) { sink ->
                    AudioJobs.pump(ctx, item, info, rate, ch, index, base + (1 - base) * k / spans.size, base + (1 - base) * (k + 1) / spans.size, startUs, endUs) { x ->
                        val y = fades.process(x); if (y.isNotEmpty()) sink.write(y)
                    }
                    val y = fades.finish(); if (y.isNotEmpty()) sink.write(y)
                })
            }
            ItemResult(item.name, ItemOutcome.SUCCESS, null, files,
                trn(files.size, "{0} part", "{0} parts") + " · " + o.label + " · " + Format.bytes(files.sumOf { it.size }))
        }
    }
}

// =========================================================================== Remove silence
/**
 * Removes silence: at the start and end ([ends]) and/or shortens pauses inside to [keepPauseMs]
 * ([pauses], only those longer than [minPauseMs]). [sensitivity] (dB over the background, see
 * [AudioPlan.silenceThreshold]) sets what counts as silence.
 */
class RemoveSilenceJob(
    inputs: List<MediaItem>, private val ends: Boolean, private val pauses: Boolean, private val keepPauseMs: Int,
    private val minPauseMs: Int, private val sensitivity: Double, private val out: AudioOutput?,
) : ExportJob(ToolId.REMOVE_SILENCE, inputs) {
    override val title = trn(inputs.size, "Removing silence from {0} sound file", "Removing silence from {0} sound files")

    override suspend fun run(ctx: JobContext) {
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            val info = AudioJobs.probe(ctx, item)
            val o = AudioJobs.output(out, info)
            ctx.status(tr("Finding the silence"), item.name)
            val a = AudioEngine.analyze(ctx.app, info, item.uri, { f -> ctx.unitProgress(index, 0.4 * f) }, { ctx.checkCancelled() })
            val keep = plan(a, ends, pauses, keepPauseMs, minPauseMs, sensitivity)
            val removed = a.frames - keep.sumOf { it.length }
            if (keep.isEmpty()) throw SkipItemException(tr("The whole file is silent at this sensitivity, so nothing was saved."))
            if (removed < a.rate / 10) throw SkipItemException(tr("No silence to remove was found."))
            // The plan is in the file's own rate; the output may be at another.
            val rate = o.rateFor(a.rate); val ch = o.channelsFor(info.channels)
            val scaled = keep.map { Span(it.start * rate / a.rate, it.end * rate / a.rate) }
            val filter = SpanFilter(ch, scaled, a.frames * rate / a.rate, rate / 200)
            OutputStore.ensureSpace(item.size)
            val file = AudioEngine.write(ctx, ToolId.REMOVE_SILENCE.area, "${item.baseName}_nosilence", o, rate, ch, info.tags, info.rate) { sink ->
                AudioJobs.pump(ctx, item, info, rate, ch, index, 0.4, 1.0) { x -> val y = filter.process(x); if (y.isNotEmpty()) sink.write(y) }
            }
            ItemResult(item.name, ItemOutcome.SUCCESS, null, listOf(file),
                tr("{0} of silence removed ({1} → {2})", AudioJobs.len(removed, a.rate), AudioJobs.len(a.frames, a.rate), AudioJobs.len(filter.kept, rate)) + " · " + o.label + " · " + Format.bytes(file.size))
        }
    }

    companion object {
        /** What stays of a file with analysis [a] (also shown on the tool's screen before saving). */
        fun plan(a: AudioAnalysis, ends: Boolean, pauses: Boolean, keepPauseMs: Int, minPauseMs: Int, sensitivity: Double): List<Span> {
            val th = AudioPlan.silenceThreshold(a.levelsDb, sensitivity)
            // Silence at the start and end goes from a tenth of a second; pauses inside only when long enough.
            val minPause = maxOf(minPauseMs, keepPauseMs + 50).toLong() * a.rate / 1000
            val sil = AudioPlan.silences(a.levelsDb, a.window, a.frames, th, a.rate / 10L)
                .filter { it.start <= 0 || it.end >= a.frames || it.length >= minPause }
            return AudioPlan.keep(sil, a.frames, ends, pauses, keepPauseMs.toLong() * a.rate / 1000)
        }
    }
}

// =========================================================================== Extract audio (videos)
/**
 * Saves the sound of videos (and sound files): with [out] null, AAC sound is copied as it is into an
 * .m4a (no re-encoding) and other sound converted to M4A; otherwise converted to [out].
 */
internal fun extractAudioConverted(ctx: JobContext, index: Int, item: MediaItem, out: AudioOutput, note: String?): ItemResult {
    val info = AudioJobs.probe(ctx, item)
    val rate = out.rateFor(info.rate); val ch = out.channelsFor(info.channels)
    var frames = 0L
    val file = AudioEngine.write(ctx, ToolId.EXTRACT_AUDIO.area, "${item.baseName}_audio", out, rate, ch, info.tags, info.rate) { sink ->
        AudioJobs.pump(ctx, item, info, rate, ch, index, 0.0, 1.0) { sink.write(it); frames += it.size / ch }
    }
    return ItemResult(item.name, ItemOutcome.SUCCESS, note, listOf(file), "${info.codec} → " + AudioJobs.details(out, frames, rate, file))
}
