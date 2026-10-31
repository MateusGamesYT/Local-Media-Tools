package com.localmediatools.ui.tools

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaPlayer
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.R
import com.localmediatools.app.MainActivity
import com.localmediatools.audio.AudioAnalysis
import com.localmediatools.audio.AudioEngine
import com.localmediatools.audio.AudioFormatOut
import com.localmediatools.audio.AudioInfo
import com.localmediatools.audio.AudioOutput
import com.localmediatools.audio.AudioProbe
import com.localmediatools.audio.core.AudioPlan
import com.localmediatools.audio.core.Span
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportJob
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import com.localmediatools.tools.AudioSpeedJob
import com.localmediatools.tools.ConvertAudioJob
import com.localmediatools.tools.ExtractAudioJob
import com.localmediatools.tools.MergeAudioJob
import com.localmediatools.tools.RemoveSilenceJob
import com.localmediatools.tools.SplitAudioJob
import com.localmediatools.tools.SplitMode
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.TrimAudioJob
import com.localmediatools.tools.VolumeJob
import com.localmediatools.tools.VolumeMode
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.Palette
import com.localmediatools.ui.SliderField
import com.localmediatools.ui.TextField
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The audio tools' settings for the app session. */
object AudioPrefs {
    /** Null: the same kind of file as the original. */
    var format: AudioFormatOut? = null
    val kbps = HashMap<AudioFormatOut, Int>()
    var bits = 16
    var channels = 0
    var convertFormat = AudioFormatOut.MP3
    var convertRate = 0
    var trimRemove = false
    var fadeInMs = 0
    var fadeOutMs = 0
    var mergeMode = 0          // 0 back to back, 1 pause, 2 crossfade
    var gapMs = 1000
    var crossfadeMs = 3000
    var evenOut = false
    var volumeMode = VolumeMode.LOUDNESS
    var targetLufs = -14.0
    var peakDb = -1.0
    var gainDb = 3
    var limit = true
    var album = false
    var tempo = 1.25
    var semitones = 0
    var splitMode = SplitMode.LENGTH
    var splitMinutes = 10.0
    var splitParts = 3
    var splitSilenceMs = 2000
    var splitSnap = true
    var silEnds = true
    var silPauses = true
    var silKeepMs = 500
    var silSensitivity = AudioPlan.SENSITIVITY_PAUSES

    fun output(format: AudioFormatOut?): AudioOutput? = format?.let { f ->
        AudioOutput(f, kbps = kbps[f] ?: f.defaultKbps, bits = if (f == AudioFormatOut.FLAC) min(bits, 24) else bits, channels = channels)
    }
}

/** The waveform of a sound file with the part chosen (two handles to drag) and parts to be removed shaded. */
class WaveformView(ctx: Context, private val selectable: Boolean, private val onChange: (Double, Double, Boolean) -> Unit = { _, _, _ -> }) : View(ctx) {
    private var bars = FloatArray(0)
    var start = 0.0; private set
    var end = 1.0; private set
    /** Fractions of the length shown as removed. */
    var removed: List<Pair<Double, Double>> = emptyList(); set(v) { field = v; invalidate() }
    /** Playback position (fraction), or < 0. */
    var playhead = -1.0; set(v) { field = v; invalidate() }
    private var active = 0
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint().apply { color = Palette.withAlpha(Palette.WARNING, 70) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.WARNING; strokeWidth = ctx.dp(2).toFloat() }
    private val r = RectF()

    init { contentDescription = if (selectable) tr("Waveform: drag the handles to choose the part") else tr("Waveform"); minimumHeight = ctx.dp(120) }

    /** Peaks (0…1) to draw. */
    fun setPeaks(p: FloatArray) { bars = p; invalidate() }
    fun set(s: Double, e: Double) { start = s; end = e; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(120))
    private val pad get() = context.dp(10).toFloat()
    private fun x(f: Double) = (pad + f * (width - 2 * pad)).toFloat()

    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        barPaint.color = Palette.SURFACE_2
        c.drawRoundRect(r, context.dp(14).toFloat(), context.dp(14).toFloat(), barPaint)
        val n = bars.size
        val cy = height / 2f; val amp = height / 2f - context.dp(10)
        if (n > 0) {
            val w = (width - 2 * pad) / n
            for (i in 0 until n) {
                val f = (i + 0.5) / n
                val inside = !selectable || (f >= start && f <= end)
                barPaint.color = if (inside) Palette.ACCENT else Palette.TEXT_3
                val h = max(context.dp(1).toFloat(), bars[i] * amp)
                r.set(pad + i * w + w * 0.15f, cy - h, pad + (i + 1) * w - w * 0.15f, cy + h)
                c.drawRect(r, barPaint)
            }
        }
        for ((a, b) in removed) c.drawRect(x(a), 0f, x(b), height.toFloat(), shade)
        if (selectable) for (f in listOf(start, end)) {
            r.set(x(f) - context.dp(2), 0f, x(f) + context.dp(2), height.toFloat()); c.drawRect(r, handle)
            r.set(x(f) - context.dp(7), cy - context.dp(16), x(f) + context.dp(7), cy + context.dp(16))
            c.drawRoundRect(r, context.dp(5).toFloat(), context.dp(5).toFloat(), handle)
        }
        if (playhead >= 0) c.drawLine(x(playhead), 0f, x(playhead), height.toFloat(), head)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!selectable) return false
        val f = ((e.x - pad) / (width - 2 * pad)).toDouble().coerceIn(0.0, 1.0)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); active = if (abs(f - start) <= abs(f - end)) 1 else 2; move(f, false) }
            MotionEvent.ACTION_MOVE -> move(f, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { move(f, true); active = 0 }
        }
        return true
    }

    private fun move(f: Double, done: Boolean) {
        if (active == 1) start = f.coerceAtMost(end - 0.001) else if (active == 2) end = f.coerceAtLeast(start + 0.001)
        invalidate(); onChange(start, end, done)
    }

    companion object {
        /** About [count] bars from the 10 ms peaks of an analysis. */
        fun bars(a: AudioAnalysis, count: Int = 160): FloatArray {
            val p = a.peaks
            if (p.isEmpty()) return FloatArray(0)
            val n = min(count, p.size)
            return FloatArray(n) { i -> var m = 0f; for (k in i * p.size / n until max(i * p.size / n + 1, (i + 1) * p.size / n)) m = max(m, p[k]); m.coerceAtMost(1f) }
        }
    }
}

/** The format, quality and channels choice shared by the audio tools. */
internal class AudioFormatPanel(private val screen: ToolScreen, private val ctx: Context, private val allowSame: Boolean, private val sameLabel: String = tr("Same as the original"),
                                private val get: () -> AudioFormatOut?, private val set: (AudioFormatOut?) -> Unit) {
    private lateinit var quality: LinearLayout
    private lateinit var note: TextView

    fun build(container: LinearLayout, section: (String, String?) -> Unit) {
        section(tr("Save as"), tr("The kind of file and its quality"))
        val options: List<AudioFormatOut?> = (if (allowSame) listOf<AudioFormatOut?>(null) else emptyList()) + AudioFormatOut.entries
        container.addView(ChoiceGroup(ctx, options, { it?.label ?: sameLabel }, get()) { set(it); renderQuality() }, lp().apply { topMargin = ctx.dp(10) })
        quality = UI.vertical(ctx)
        container.addView(quality, lp().apply { topMargin = ctx.dp(10) })
        val ch = listOf(0 to tr("Keep channels"), 1 to tr("Mono"), 2 to tr("Stereo"))
        container.addView(ChoiceGroup(ctx, ch, { it.second }, ch.first { it.first == AudioPrefs.channels }) { AudioPrefs.channels = it.first }, lp().apply { topMargin = ctx.dp(10) })
        note = UI.text(ctx, "", TextStyle.CAPTION)
        container.addView(note, lp().apply { topMargin = ctx.dp(8) })
        renderQuality()
    }

    private fun renderQuality() {
        quality.removeAllViews()
        val f = get()
        note.text = when {
            f == null -> tr("The same kind of file as each original, at a quality no lower than its own. MP3, M4A and Opus are compressed again, which loses a little; choose FLAC or WAV to lose nothing more.")
            f.lossless -> tr("Lossless: keeps every detail of the sound as it is now. Files are large (FLAC about half the size of WAV).")
            f == AudioFormatOut.OPUS -> tr("Opus sounds the best for its size, but older devices and car stereos may not play it.")
            else -> tr("Higher bit rates sound better and make larger files. 192 kbps and up is hard to tell from the original.")
        }
        if (f == null) return
        if (f.lossless) {
            val opts = if (f == AudioFormatOut.FLAC) listOf(16, 24) else listOf(16, 24, 32)
            val sel = opts.firstOrNull { it == AudioPrefs.bits } ?: 16
            AudioPrefs.bits = sel
            quality.addView(ChoiceGroup(ctx, opts, { if (it == 32) tr("32-bit float") else tr("{0}-bit", it) }, sel) { AudioPrefs.bits = it })
        } else {
            val sel = AudioPrefs.kbps[f] ?: f.defaultKbps
            quality.addView(ChoiceGroup(ctx, f.bitrates, { "$it kbps" }, sel) { AudioPrefs.kbps[f] = it })
        }
        quality.requestLayout()
    }
}

/** Reads a selected file's info and waveform in the background, for the screens that show them. */
internal class AudioLoader(private val screen: ToolScreen, private val onLoaded: (MediaItem, AudioInfo?, AudioAnalysis?, String?) -> Unit, private val onProgress: (String) -> Unit) {
    private var key: String? = null
    private var job: Job? = null

    fun load(item: MediaItem?, analyse: Boolean) {
        if (item == null) { key = null; job?.cancel(); return }
        val k = item.key + analyse
        if (k == key) return
        key = k
        job?.cancel()
        job = screen.scope.launch {
            onProgress(tr("Reading {0}…", item.name))
            var error: String? = null
            val info = withContext(Dispatchers.IO) { try { AudioProbe.probe(screen.ctx, item.uri) } catch (e: Exception) { error = e.message; null } }
            if (key != k) return@launch
            if (info == null || !analyse) { onLoaded(item, info, null, error); return@launch }
            onProgress(tr("Drawing the waveform…"))
            val a = withContext(Dispatchers.Default) {
                try { AudioEngine.analyze(screen.ctx, info, item.uri, check = { if (key != k) throw kotlinx.coroutines.CancellationException() }) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message; null }
            }
            if (key != k) return@launch
            onLoaded(item, info, a, error)
        }
    }
}

/** "name_trimmed.mp3", or the original's type when the format is "same as the original". */
internal fun audioName(base: String, format: AudioFormatOut?): String = format?.let { "$base.${it.ext}" } ?: tr("{0} (the same type as the original)", base)

private fun fmtTime(us: Long): String {
    val ms = us / 1000
    val m = ms / 60000; val s = (ms % 60000) / 1000.0
    return String.format(Locale.ROOT, "%d:%04.1f", m, s)
}

/** "1:23.4", "83.4" or "1:02:03" → µs; null if not a time. */
internal fun parseTime(s: String): Long? {
    val parts = s.trim().replace(',', '.').split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    var sec = 0.0
    for (p in parts) { val v = p.toDoubleOrNull() ?: return null; if (v < 0) return null; sec = sec * 60 + v }
    return (sec * 1_000_000).toLong()
}

// =========================================================================== Trim
class TrimAudioScreen(a: MainActivity) : ToolScreen(a, ToolId.TRIM_AUDIO) {
    private lateinit var info: TextView
    private lateinit var wave: WaveformView
    private lateinit var times: TextView
    private lateinit var startField: TextField
    private lateinit var endField: TextField
    private lateinit var play: com.localmediatools.ui.ButtonView
    private var durationUs = 0L
    private var player: MediaPlayer? = null
    private var playJob: Job? = null
    private val loader = AudioLoader(this, { item, inf, an, err ->
        durationUs = inf?.durationUs ?: 0
        if (inf == null) { info.text = tr("{0} couldn't be read: {1}", item.name, err ?: ""); info.setTextColor(Palette.WARNING); return@AudioLoader }
        info.setTextColor(Palette.TEXT_2)
        info.text = "${item.name} · ${Format.duration(inf.durationUs / 1000)} · ${inf.codec}" + if (inf.bitrate > 0 && !inf.pcmFile) " ${inf.bitrate / 1000} kbps" else ""
        an?.let { wave.setPeaks(WaveformView.bars(it)) }
        wave.set(0.0, 1.0); updateTimes(true); refreshValidation()
    }, { info.text = it })

    override fun buildOptions(container: LinearLayout) {
        info = UI.text(ctx, tr("Select a sound file to choose the part to keep."), TextStyle.BODY_2)
        container.addView(info, lp().apply { topMargin = ctx.dp(4) })
        wave = WaveformView(ctx, true) { _, _, done -> updateTimes(done) }
        container.addView(wave, lp().apply { topMargin = ctx.dp(10) })
        times = UI.text(ctx, "", TextStyle.CAPTION)
        container.addView(times, lp().apply { topMargin = ctx.dp(6) })
        val row = UI.horizontal(ctx)
        startField = TextField(ctx, tr("Start"), "0:00.0", "0:00.0") { s -> parseTime(s)?.let { setFrom(it, null) } }
        endField = TextField(ctx, tr("End"), "1:00.0", "") { s -> parseTime(s)?.let { setFrom(null, it) } }
        row.addView(startField, lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ctx.dp(6) })
        row.addView(endField, lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(6) })
        container.addView(row, lp().apply { topMargin = ctx.dp(10) })
        play = UI.secondaryButton(ctx, tr("Listen to the part"), R.drawable.ic_play) { togglePlay() }
        container.addView(play, lp().apply { topMargin = ctx.dp(10) })
        section(container, tr("What to keep"))
        val modes = listOf(false to tr("Keep the part"), true to tr("Cut the part out"))
        container.addView(ChoiceGroup(ctx, modes, { it.second }, modes.first { it.first == AudioPrefs.trimRemove }) { AudioPrefs.trimRemove = it.first; updateTimes(false) }, lp().apply { topMargin = ctx.dp(10) })
        val fades = listOf(0, 500, 1000, 2000, 3000, 5000, 10000)
        fun fadeLabel(ms: Int) = if (ms == 0) tr("None") else Format.seconds(ms / 1000.0)
        section(container, tr("Fade in"), tr("From silence to full volume at the start"))
        container.addView(ChoiceGroup(ctx, fades, { fadeLabel(it) }, fades.firstOrNull { it == AudioPrefs.fadeInMs } ?: 0) { AudioPrefs.fadeInMs = it }, lp().apply { topMargin = ctx.dp(10) })
        section(container, tr("Fade out"), tr("Down to silence at the end"))
        container.addView(ChoiceGroup(ctx, fades, { fadeLabel(it) }, fades.firstOrNull { it == AudioPrefs.fadeOutMs } ?: 0) { AudioPrefs.fadeOutMs = it }, lp().apply { topMargin = ctx.dp(10) })
        AudioFormatPanel(this, ctx, true, get = { AudioPrefs.format }, set = { AudioPrefs.format = it }).build(container) { t, s -> section(container, t, s) }
        onSelectionChanged()
    }

    private fun setFrom(startUs: Long?, endUs: Long?) {
        if (durationUs <= 0) return
        val s = startUs?.let { (it.toDouble() / durationUs).coerceIn(0.0, 1.0) } ?: wave.start
        val e = endUs?.let { (it.toDouble() / durationUs).coerceIn(0.0, 1.0) } ?: wave.end
        if (e > s) { wave.set(s, e); updateTimes(false) }
    }

    override fun onSelectionChanged() {
        if (!::info.isInitialized) return
        stopPlay()
        val item = selection.usable.firstOrNull()
        if (item == null) { durationUs = 0; info.text = tr("Select a sound file to choose the part to keep."); wave.setPeaks(FloatArray(0)); updateTimes(true); loader.load(null, true); return }
        loader.load(item, true)
    }

    private fun updateTimes(fields: Boolean) {
        if (!::times.isInitialized) return
        val s = (wave.start * durationUs).toLong(); val e = (wave.end * durationUs).toLong()
        val kept = if (AudioPrefs.trimRemove) durationUs - (e - s) else e - s
        times.text = if (durationUs <= 0) "" else tr("Start {0} · End {1} · keeps {2}", fmtTime(s), fmtTime(e), com.localmediatools.tools.AudioJobs.len(kept.coerceAtLeast(0), 1_000_000))
        wave.removed = if (AudioPrefs.trimRemove) listOf(wave.start to wave.end) else listOf(0.0 to wave.start, wave.end to 1.0)
        if (fields && durationUs > 0) { startField.edit.setText(fmtTime(s)); endField.edit.setText(fmtTime(e)) }
        refreshValidation()
    }

    private fun togglePlay() {
        if (player != null) { stopPlay(); return }
        val item = selection.usable.firstOrNull() ?: return
        if (durationUs <= 0) return
        val s = (wave.start * durationUs / 1000).toInt(); val e = (wave.end * durationUs / 1000).toInt()
        val p = MediaPlayer()
        try {
            p.setDataSource(ctx, item.uri); p.prepare(); p.seekTo(s); p.start()
        } catch (_: Exception) { try { p.release() } catch (_: Exception) { }; android.widget.Toast.makeText(ctx, tr("This file can't be played here."), android.widget.Toast.LENGTH_SHORT).show(); return }
        player = p
        play.label = tr("Stop")
        playJob = scope.launch {
            while (true) {
                val pos = try { p.currentPosition } catch (_: Exception) { break }
                wave.playhead = pos * 1000.0 / durationUs
                if (pos >= e || !p.isPlaying) break
                delay(40)
            }
            stopPlay()
        }
    }

    private fun stopPlay() {
        playJob?.cancel(); playJob = null
        player?.let { try { it.stop() } catch (_: Exception) { }; try { it.release() } catch (_: Exception) { } }
        player = null
        if (::play.isInitialized) play.label = tr("Listen to the part")
        if (::wave.isInitialized) wave.playhead = -1.0
    }

    override fun onHide() { stopPlay(); super.onHide() }
    override fun onDestroy() { stopPlay(); super.onDestroy() }

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (durationUs <= 0) return null
        val len = (wave.end - wave.start) * durationUs
        if (!AudioPrefs.trimRemove && len < 50_000) return tr("The part to keep is too short")
        if (AudioPrefs.trimRemove && durationUs - len < 50_000) return tr("Nothing would be left")
        return null
    }

    override fun outputNaming() = audioName("name_trimmed", AudioPrefs.format)
    override fun createJob(items: List<MediaItem>): ExportJob =
        TrimAudioJob(items, wave.start, wave.end, AudioPrefs.trimRemove, AudioPrefs.fadeInMs, AudioPrefs.fadeOutMs, AudioPrefs.output(AudioPrefs.format))
}

// =========================================================================== Merge
class MergeAudioScreen(a: MainActivity) : ToolScreen(a, ToolId.MERGE_AUDIO) {
    private lateinit var info: TextView
    private lateinit var amount: LinearLayout
    private var checkJob: Job? = null
    private var checked: List<String>? = null

    override fun buildOptions(container: LinearLayout) {
        info = UI.text(ctx, tr("Select two or more sound files. They're joined in the order shown above (tap Review to reorder)."), TextStyle.BODY_2)
        container.addView(info, lp().apply { topMargin = ctx.dp(4) })
        section(container, tr("Between the files"))
        val modes = listOf(0 to tr("Back to back"), 1 to tr("A pause"), 2 to tr("Crossfade"))
        container.addView(ChoiceGroup(ctx, modes, { it.second }, modes.first { it.first == AudioPrefs.mergeMode }) { AudioPrefs.mergeMode = it.first; renderAmount() }, lp().apply { topMargin = ctx.dp(10) })
        amount = UI.vertical(ctx)
        container.addView(amount, lp().apply { topMargin = ctx.dp(10) })
        container.addView(ToggleRow(ctx, tr("Even out the volume"), tr("Brings every file to the same loudness first"), AudioPrefs.evenOut) { AudioPrefs.evenOut = it }, lp().apply { topMargin = ctx.dp(12) })
        AudioFormatPanel(this, ctx, true, tr("Like the first file"), get = { AudioPrefs.format }, set = { AudioPrefs.format = it }).build(container) { t, s -> section(container, t, s) }
        renderAmount()
        onSelectionChanged()
    }

    private fun renderAmount() {
        amount.removeAllViews()
        when (AudioPrefs.mergeMode) {
            1 -> { val o = listOf(500, 1000, 2000, 3000, 5000); amount.addView(ChoiceGroup(ctx, o, { Format.seconds(it / 1000.0) }, o.firstOrNull { it == AudioPrefs.gapMs } ?: 1000) { AudioPrefs.gapMs = it }) }
            2 -> { val o = listOf(1000, 2000, 3000, 5000, 8000); amount.addView(ChoiceGroup(ctx, o, { Format.seconds(it / 1000.0) }, o.firstOrNull { it == AudioPrefs.crossfadeMs } ?: 3000) { AudioPrefs.crossfadeMs = it }) }
        }
    }

    override fun onSelectionChanged() {
        if (!::info.isInitialized) return
        val items = selection.usable
        val keys = items.map { it.key }
        if (items.size < 2) { checked = null; info.text = tr("Select two or more sound files. They're joined in the order shown above (tap Review to reorder)."); info.setTextColor(Palette.TEXT_2); return }
        if (keys == checked) return
        checked = keys
        checkJob?.cancel()
        checkJob = scope.launch {
            info.text = tr("Checking the files…")
            val infos = withContext(Dispatchers.IO) { try { items.map { AudioProbe.probe(ctx, it.uri) } } catch (_: Exception) { null } }
            if (checked != keys) return@launch
            if (infos == null) { info.text = tr("One of the files couldn't be read as sound."); info.setTextColor(Palette.WARNING); return@launch }
            info.setTextColor(Palette.TEXT_2)
            info.text = trn(items.size, "{0} file · {1} in total", "{0} files · {1} in total", Format.duration(infos.sumOf { it.durationUs } / 1000))
        }
    }

    override fun outputNaming() = audioName("firstfile_merged", AudioPrefs.format)
    override fun createJob(items: List<MediaItem>): ExportJob = MergeAudioJob(items,
        if (AudioPrefs.mergeMode == 1) AudioPrefs.gapMs else 0, if (AudioPrefs.mergeMode == 2) AudioPrefs.crossfadeMs else 0, AudioPrefs.evenOut, AudioPrefs.output(AudioPrefs.format))
}

// =========================================================================== Convert
class ConvertAudioScreen(a: MainActivity) : ToolScreen(a, ToolId.CONVERT_AUDIO) {
    override fun buildOptions(container: LinearLayout) {
        AudioFormatPanel(this, ctx, false, get = { AudioPrefs.convertFormat }, set = { AudioPrefs.convertFormat = it ?: AudioFormatOut.MP3 }).build(container) { t, s -> section(container, t, s, top = 4) }
        section(container, tr("Sample rate"), tr("Opus is always 48 kHz; MP3 and M4A use the nearest rate they support"))
        val rates = listOf(0 to tr("Keep"), 44100 to "44.1 kHz", 48000 to "48 kHz")
        container.addView(ChoiceGroup(ctx, rates, { it.second }, rates.firstOrNull { it.first == AudioPrefs.convertRate } ?: rates[0]) { AudioPrefs.convertRate = it.first }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, tr("Videos can be chosen too: their sound is converted. Title, artist, album and cover are kept (M4A files keep the sound only)."), UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })
    }

    override fun outputNaming() = "name.${AudioPrefs.convertFormat.ext}"
    override fun createJob(items: List<MediaItem>): ExportJob = ConvertAudioJob(items, AudioPrefs.output(AudioPrefs.convertFormat)!!.copy(rate = AudioPrefs.convertRate))
}

// =========================================================================== Volume
class VolumeScreen(a: MainActivity) : ToolScreen(a, ToolId.AUDIO_VOLUME) {
    private lateinit var detail: LinearLayout
    private lateinit var albumRow: ToggleRow

    override fun buildOptions(container: LinearLayout) {
        section(container, tr("How"), top = 4)
        val modes = listOf(VolumeMode.LOUDNESS to tr("Same loudness"), VolumeMode.PEAK to tr("Peak level"), VolumeMode.GAIN to tr("Louder or quieter"))
        container.addView(ChoiceGroup(ctx, modes, { it.second }, modes.first { it.first == AudioPrefs.volumeMode }) { AudioPrefs.volumeMode = it.first; renderDetail() }, lp().apply { topMargin = ctx.dp(10) })
        detail = UI.vertical(ctx)
        container.addView(detail, lp().apply { topMargin = ctx.dp(10) })
        container.addView(ToggleRow(ctx, tr("Limit peaks to −1 dB"), tr("A limiter keeps louder parts from clipping"), AudioPrefs.limit) { AudioPrefs.limit = it }, lp().apply { topMargin = ctx.dp(12) })
        albumRow = ToggleRow(ctx, tr("Album mode"), tr("One change for all the files, measured together: the songs of an album keep their differences"), AudioPrefs.album) { AudioPrefs.album = it }
        container.addView(albumRow, lp().apply { topMargin = ctx.dp(8) })
        AudioFormatPanel(this, ctx, true, get = { AudioPrefs.format }, set = { AudioPrefs.format = it }).build(container) { t, s -> section(container, t, s) }
        renderDetail()
    }

    private fun renderDetail() {
        detail.removeAllViews()
        when (AudioPrefs.volumeMode) {
            VolumeMode.LOUDNESS -> {
                val o = listOf(-14.0 to tr("−14 LUFS · streaming (Spotify, YouTube)"), -16.0 to tr("−16 LUFS · podcasts, Apple Music"), -18.0 to tr("−18 LUFS · quieter"), -23.0 to tr("−23 LUFS · TV and radio (EBU R128)"))
                detail.addView(ChoiceGroup(ctx, o, { it.second }, o.firstOrNull { it.first == AudioPrefs.targetLufs } ?: o[0]) { AudioPrefs.targetLufs = it.first })
                detail.addView(UI.note(ctx, tr("Loudness is measured as people hear it (ITU-R BS.1770), so files sound equally loud however their peaks look."), UI.NoteKind.TIP), lp().apply { topMargin = ctx.dp(8) })
            }
            VolumeMode.PEAK -> {
                val o = listOf(-0.1, -1.0, -3.0, -6.0)
                detail.addView(ChoiceGroup(ctx, o, { String.format(Locale.ROOT, "%.1f dB", it).replace("-", "−") }, o.firstOrNull { it == AudioPrefs.peakDb } ?: -1.0) { AudioPrefs.peakDb = it })
                detail.addView(UI.note(ctx, tr("The loudest moment (including peaks between samples) is set to this level."), UI.NoteKind.TIP), lp().apply { topMargin = ctx.dp(8) })
            }
            VolumeMode.GAIN -> detail.addView(SliderField(ctx, tr("Change"), -20, 20, AudioPrefs.gainDb, " dB", tr("+6 dB is about twice as loud in level, −6 dB half")) { AudioPrefs.gainDb = it })
        }
        if (::albumRow.isInitialized) albumRow.setAvailable(AudioPrefs.volumeMode != VolumeMode.GAIN)
    }

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (AudioPrefs.volumeMode == VolumeMode.GAIN && AudioPrefs.gainDb == 0) return tr("Choose a change other than 0 dB")
        return null
    }

    override fun outputNaming() = audioName("name_volume", AudioPrefs.format)
    override fun createJob(items: List<MediaItem>): ExportJob = VolumeJob(items, AudioPrefs.volumeMode, AudioPrefs.targetLufs, AudioPrefs.peakDb,
        AudioPrefs.gainDb.toDouble(), AudioPrefs.limit, AudioPrefs.album && items.size > 1, AudioPrefs.output(AudioPrefs.format))
}

// =========================================================================== Speed & pitch
class AudioSpeedScreen(a: MainActivity) : ToolScreen(a, ToolId.AUDIO_SPEED) {
    private lateinit var info: TextView
    private lateinit var tempo: SliderField
    private var durationUs = 0L
    private val loader = AudioLoader(this, { _, inf, _, _ -> durationUs = inf?.durationUs ?: 0; update() }, { })

    override fun buildOptions(container: LinearLayout) {
        section(container, tr("Speed"), tr("Pitch stays the same"), top = 4)
        val presets = listOf(0.5, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 2.0)
        tempo = SliderField(ctx, tr("Speed"), 50, 200, (AudioPrefs.tempo * 100).roundToInt(), "%") { AudioPrefs.tempo = it / 100.0; update() }
        container.addView(ChoiceGroup(ctx, presets, { trimNum(it) + "×" }, presets.firstOrNull { it == AudioPrefs.tempo }) { AudioPrefs.tempo = it; tempo.setValue((it * 100).roundToInt(), fromField = false); update() }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(tempo, lp().apply { topMargin = ctx.dp(10) })
        section(container, tr("Pitch"), tr("In semitones; speed stays the same (12 = an octave)"))
        container.addView(SliderField(ctx, tr("Pitch"), -12, 12, AudioPrefs.semitones, "", tr("0 = unchanged")) { AudioPrefs.semitones = it; update() }, lp().apply { topMargin = ctx.dp(10) })
        info = UI.text(ctx, "", TextStyle.BODY_2)
        container.addView(info, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, tr("Voices stay natural from about 0.75× to 1.5×; further than that, and more than a few semitones, the sound starts to show the change. For videos, use Speed & timelapse."), UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
        AudioFormatPanel(this, ctx, true, get = { AudioPrefs.format }, set = { AudioPrefs.format = it }).build(container) { t, s -> section(container, t, s) }
        onSelectionChanged()
    }

    private fun trimNum(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    override fun onSelectionChanged() { if (::info.isInitialized) { loader.load(selection.usable.firstOrNull(), false); update() } }

    private fun update() {
        if (!::info.isInitialized) return
        val t = AudioPrefs.tempo; val p = AudioPrefs.semitones
        val parts = ArrayList<String>()
        if (durationUs > 0 && abs(t - 1) > 1e-6) parts.add(tr("{0} becomes {1}", Format.duration(durationUs / 1000), Format.duration((durationUs / t / 1000).toLong())))
        if (p != 0) parts.add(trn(abs(p), "pitch {1} {0} semitone", "pitch {1} {0} semitones", if (p > 0) tr("up") else tr("down")))
        info.text = if (parts.isEmpty()) tr("Unchanged") else parts.joinToString(" · ")
        refreshValidation()
    }

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (abs(AudioPrefs.tempo - 1) < 1e-6 && AudioPrefs.semitones == 0) return tr("Change the speed or the pitch")
        return null
    }

    override fun outputNaming() = audioName("name_${AudioSpeedJob.label(AudioPrefs.tempo, AudioPrefs.semitones.toDouble())}", AudioPrefs.format)
    override fun createJob(items: List<MediaItem>): ExportJob = AudioSpeedJob(items, AudioPrefs.tempo, AudioPrefs.semitones.toDouble(), AudioPrefs.output(AudioPrefs.format))
}

// =========================================================================== Split
class SplitAudioScreen(a: MainActivity) : ToolScreen(a, ToolId.SPLIT_AUDIO) {
    private lateinit var detail: LinearLayout

    override fun buildOptions(container: LinearLayout) {
        section(container, tr("Split"), top = 4)
        val modes = listOf(SplitMode.LENGTH to tr("Every…"), SplitMode.PARTS to tr("Into equal parts"), SplitMode.SILENCE to tr("At silences"))
        container.addView(ChoiceGroup(ctx, modes, { it.second }, modes.first { it.first == AudioPrefs.splitMode }) { AudioPrefs.splitMode = it.first; renderDetail(); refreshValidation() }, lp().apply { topMargin = ctx.dp(10) })
        detail = UI.vertical(ctx)
        container.addView(detail, lp().apply { topMargin = ctx.dp(10) })
        AudioFormatPanel(this, ctx, true, get = { AudioPrefs.format }, set = { AudioPrefs.format = it }).build(container) { t, s -> section(container, t, s) }
        renderDetail()
    }

    private fun renderDetail() {
        detail.removeAllViews()
        when (AudioPrefs.splitMode) {
            SplitMode.LENGTH -> {
                val o = listOf(1.0, 5.0, 10.0, 15.0, 30.0, 60.0)
                detail.addView(ChoiceGroup(ctx, o, { Format.seconds(it * 60) }, o.firstOrNull { it == AudioPrefs.splitMinutes }) { AudioPrefs.splitMinutes = it; refreshValidation() })
                detail.addView(TextField(ctx, tr("Custom length (minutes)"), tr("e.g. 2.5"), trimNum(AudioPrefs.splitMinutes), numeric = true) { s -> s.replace(',', '.').toDoubleOrNull()?.let { AudioPrefs.splitMinutes = it }; refreshValidation() }, lp().apply { topMargin = ctx.dp(10) })
            }
            SplitMode.PARTS -> {
                val o = listOf(2, 3, 4, 5, 6, 8, 10)
                detail.addView(ChoiceGroup(ctx, o, { trn(it, "{0} part", "{0} parts") }, o.firstOrNull { it == AudioPrefs.splitParts } ?: 3) { AudioPrefs.splitParts = it })
            }
            SplitMode.SILENCE -> {
                val o = listOf(1000, 2000, 3000, 5000)
                detail.addView(UI.text(ctx, tr("Silences at least this long separate the parts:"), TextStyle.CAPTION))
                detail.addView(ChoiceGroup(ctx, o, { Format.seconds(it / 1000.0) }, o.firstOrNull { it == AudioPrefs.splitSilenceMs } ?: 2000) { AudioPrefs.splitSilenceMs = it }, lp().apply { topMargin = ctx.dp(6) })
                detail.addView(UI.note(ctx, tr("For recordings of several songs or tracks: each part starts where the sound comes back, and the silence between is left out."), UI.NoteKind.TIP), lp().apply { topMargin = ctx.dp(8) })
            }
        }
        if (AudioPrefs.splitMode != SplitMode.SILENCE)
            detail.addView(ToggleRow(ctx, tr("Cut at quiet moments"), tr("Moves each cut up to 3 seconds to a pause, so words and notes aren't cut in half"), AudioPrefs.splitSnap) { AudioPrefs.splitSnap = it }, lp().apply { topMargin = ctx.dp(10) })
    }

    private fun trimNum(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (AudioPrefs.splitMode == SplitMode.LENGTH && (AudioPrefs.splitMinutes < 0.1 || AudioPrefs.splitMinutes > 600)) return tr("Enter a length between 0.1 and 600 minutes")
        return null
    }

    override fun outputNaming() = tr("One file per part: {0}, {1} …", audioName("name_part01", AudioPrefs.format), "name_part02")
    override fun createJob(items: List<MediaItem>): ExportJob = SplitAudioJob(items, AudioPrefs.splitMode, AudioPrefs.splitMinutes * 60, AudioPrefs.splitParts,
        AudioPrefs.splitSilenceMs, AudioPrefs.splitSnap, AudioPrefs.output(AudioPrefs.format))
}

// =========================================================================== Remove silence
class RemoveSilenceScreen(a: MainActivity) : ToolScreen(a, ToolId.REMOVE_SILENCE) {
    private lateinit var info: TextView
    private lateinit var wave: WaveformView
    private var analysis: AudioAnalysis? = null
    private val loader = AudioLoader(this, { item, _, an, err ->
        analysis = an
        if (an == null) { info.text = tr("{0} couldn't be read: {1}", item.name, err ?: ""); return@AudioLoader }
        wave.setPeaks(WaveformView.bars(an)); preview()
    }, { if (::info.isInitialized) info.text = it })

    override fun buildOptions(container: LinearLayout) {
        container.addView(ToggleRow(ctx, tr("At the start and end"), tr("Removes the silence before the sound starts and after it ends"), AudioPrefs.silEnds) { AudioPrefs.silEnds = it; preview() }, lp().apply { topMargin = ctx.dp(4) })
        container.addView(ToggleRow(ctx, tr("Shorten pauses"), tr("Long pauses inside are cut down to the length below"), AudioPrefs.silPauses) { AudioPrefs.silPauses = it; preview() }, lp().apply { topMargin = ctx.dp(8) })
        val keep = listOf(200, 300, 500, 1000, 2000)
        container.addView(ChoiceGroup(ctx, keep, { tr("keep {0}", Format.seconds(it / 1000.0)) }, keep.firstOrNull { it == AudioPrefs.silKeepMs } ?: 500) { AudioPrefs.silKeepMs = it; preview() }, lp().apply { topMargin = ctx.dp(8) })
        section(container, tr("What counts as silence"))
        val sens = listOf(AudioPlan.SENSITIVITY_SILENCE to tr("Only silence"), AudioPlan.SENSITIVITY_PAUSES to tr("Pauses in speech"), AudioPlan.SENSITIVITY_SOFT to tr("Also very soft parts"))
        container.addView(ChoiceGroup(ctx, sens, { it.second }, sens.firstOrNull { it.first == AudioPrefs.silSensitivity } ?: sens[1]) { AudioPrefs.silSensitivity = it.first; preview() }, lp().apply { topMargin = ctx.dp(10) })
        wave = WaveformView(ctx, false)
        container.addView(wave, lp().apply { topMargin = ctx.dp(12) })
        info = UI.text(ctx, tr("Select a sound file to see what would be removed."), TextStyle.BODY_2)
        container.addView(info, lp().apply { topMargin = ctx.dp(6) })
        container.addView(UI.note(ctx, tr("Silence is judged against each recording's own background noise, so pauses with room noise or breathing are found too. The shaded parts above will be removed (shown for the first file)."), UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(10) })
        AudioFormatPanel(this, ctx, true, get = { AudioPrefs.format }, set = { AudioPrefs.format = it }).build(container) { t, s -> section(container, t, s) }
        onSelectionChanged()
    }

    override fun onSelectionChanged() {
        if (!::info.isInitialized) return
        val item = selection.usable.firstOrNull()
        if (item == null) { analysis = null; wave.setPeaks(FloatArray(0)); wave.removed = emptyList(); info.text = tr("Select a sound file to see what would be removed."); loader.load(null, true); return }
        loader.load(item, true)
    }

    private fun preview() {
        val a = analysis ?: return
        if (!AudioPrefs.silEnds && !AudioPrefs.silPauses) { wave.removed = emptyList(); info.text = tr("Choose what to remove"); refreshValidation(); return }
        val keep = RemoveSilenceJob.plan(a, AudioPrefs.silEnds, AudioPrefs.silPauses, AudioPrefs.silKeepMs, AudioPrefs.silKeepMs + 200, AudioPrefs.silSensitivity)
        val total = a.frames.toDouble()
        val removed = ArrayList<Pair<Double, Double>>()
        var pos = 0L
        for (s in keep) { if (s.start > pos) removed.add(pos / total to s.start / total); pos = s.end }
        if (pos < a.frames) removed.add(pos / total to 1.0)
        wave.removed = removed
        val kept = keep.sumOf(Span::length)
        info.text = if (a.frames - kept < a.rate / 10) tr("No silence to remove at this setting.") else tr("Removes {0}: {1} becomes {2}",
            com.localmediatools.tools.AudioJobs.len(a.frames - kept, a.rate), com.localmediatools.tools.AudioJobs.len(a.frames, a.rate), com.localmediatools.tools.AudioJobs.len(kept, a.rate))
        refreshValidation()
    }

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (!AudioPrefs.silEnds && !AudioPrefs.silPauses) return tr("Choose what to remove")
        return null
    }

    override fun outputNaming() = audioName("name_nosilence", AudioPrefs.format)
    override fun createJob(items: List<MediaItem>): ExportJob = RemoveSilenceJob(items, AudioPrefs.silEnds, AudioPrefs.silPauses, AudioPrefs.silKeepMs,
        AudioPrefs.silKeepMs + 200, AudioPrefs.silSensitivity, AudioPrefs.output(AudioPrefs.format))
}

// =========================================================================== Extract audio
object ExtractPrefs { var format: AudioFormatOut? = null }

class ExtractAudioScreen(a: MainActivity) : ToolScreen(a, ToolId.EXTRACT_AUDIO) {
    override fun buildOptions(container: LinearLayout) {
        AudioFormatPanel(this, ctx, true, tr("Original (no re-encoding)"), get = { ExtractPrefs.format }, set = { ExtractPrefs.format = it }).build(container) { t, s -> section(container, t, s, top = 4) }
        container.addView(UI.note(ctx, tr("Original: AAC sound (most phone videos) is copied into an .m4a file exactly as it is — no quality loss. Other sound (Opus, AC-3…) is converted to M4A."), UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })
    }
    override fun outputNaming() = "name_audio.${(ExtractPrefs.format ?: AudioFormatOut.AAC).ext}"
    override fun createJob(items: List<MediaItem>): ExportJob = ExtractAudioJob(items, AudioPrefs.output(ExtractPrefs.format))
}
