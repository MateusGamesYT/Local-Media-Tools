package com.localmediatools.ui.tools

import android.app.AlertDialog
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.export.ExportJob
import com.localmediatools.highlight.HighlightAnalyzer
import com.localmediatools.highlight.HighlightAudio
import com.localmediatools.highlight.ShotInfo
import com.localmediatools.highlight.core.Aspect
import com.localmediatools.highlight.core.Plan
import com.localmediatools.highlight.core.Planner
import com.localmediatools.music.core.Mood
import com.localmediatools.tools.HighlightJob
import com.localmediatools.tools.HighlightSettings
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.Palette
import com.localmediatools.ui.ProgressBarView
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextField
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.Thumbs
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** The highlight video's settings, kept while the app runs; choices about shots belong to one selection. */
object HighlightPrefs {
    var mood: Mood? = Mood.UPBEAT
    var seconds: Double? = null
    var aspect: Aspect? = null
    var captions = true
    var clipSound = true
    var seed = 1L
    val include = HashSet<String>()
    val exclude = HashSet<String>()
    var title: String? = null
    val names = HashMap<Int, String>()
    /** The selection the shot choices were made for. */
    var forKeys: List<String> = emptyList()

    fun settings() = HighlightSettings(mood, seconds, aspect, captions, clipSound, include.toSet(), exclude.toSet(), title, names.toMap(), seed)

    fun reset() {
        mood = Mood.UPBEAT; seconds = null; aspect = null; captions = true; clipSound = true; seed = 1L
        include.clear(); exclude.clear(); title = null; names.clear(); forKeys = emptyList()
    }
}

/**
 * The highlight video: pick photos and videos, and the app looks at them, finds the moments and
 * drafts the edit. The timeline shows each moment with its shots — the ones in the video marked —
 * and every choice can be changed: the music, length, shape, captions, the title, a moment's name,
 * and any shot (tap: always in, tap again: left out).
 */
class HighlightScreen(a: MainActivity) : ToolScreen(a, ToolId.HIGHLIGHT_VIDEO) {
    private lateinit var status: TextView
    private lateinit var bar: ProgressBarView
    private lateinit var summary: TextView
    private lateinit var moments: LinearLayout
    private lateinit var shapes: ChoiceGroup<Aspect?>
    private lateinit var titleField: TextField
    private lateinit var playButton: ButtonView
    private var analysis: Job? = null
    private var analysedKeys: List<String>? = null
    private var items: List<MediaItem> = emptyList()
    private var infos: List<ShotInfo> = emptyList()
    private var plan: Plan? = null
    private val cells = HashMap<String, ShotCell>()
    private val headers = HashMap<Int, Pair<TextView, TextView>>()
    private var player: AudioTrack? = null
    private var playJob: Job? = null
    /** Stereo frames of music handed to the speaker by the last "Listen". */
    var previewFrames = 0; private set

    override fun buildOptions(container: LinearLayout) {
        val head = UI.vertical(ctx)
        status = UI.text(ctx, "", TextStyle.BODY_2)
        bar = ProgressBarView(ctx)
        head.addView(status)
        head.addView(bar, lp().apply { topMargin = ctx.dp(8) })
        container.addView(head, lp().apply { topMargin = ctx.dp(4) })

        section(container, "Music", "Written on this phone for your video — always instrumental")
        container.addView(ChoiceGroup(ctx, listOf(Mood.UPBEAT, Mood.CHILL, Mood.CINEMATIC, null), { it?.label ?: "No music" }, HighlightPrefs.mood) {
            HighlightPrefs.mood = it; stopMusic(); refresh()
        }, lp().apply { topMargin = ctx.dp(8) })
        val musicRow = UI.horizontal(ctx)
        playButton = UI.secondaryButton(ctx, "Listen", R.drawable.ic_play) { toggleMusic() }
        musicRow.addView(playButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        musicRow.addView(UI.secondaryButton(ctx, "Another tune", R.drawable.ic_music) {
            HighlightPrefs.seed++
            val wasPlaying = player != null
            stopMusic(); refresh()
            if (wasPlaying) toggleMusic()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(10) })
        container.addView(musicRow, lp().apply { topMargin = ctx.dp(10) })

        section(container, "Length")
        container.addView(ChoiceGroup(ctx, listOf(null, 15.0, 30.0, 60.0, 90.0), { it?.let { s -> "${s.roundToInt()} s" } ?: "Auto" }, HighlightPrefs.seconds) {
            HighlightPrefs.seconds = it; refresh()
        }, lp().apply { topMargin = ctx.dp(8) })

        section(container, "Shape")
        shapes = ChoiceGroup(ctx, listOf(null, Aspect.LANDSCAPE, Aspect.PORTRAIT, Aspect.SQUARE), { shapeLabel(it) }, HighlightPrefs.aspect) {
            HighlightPrefs.aspect = it; refresh()
        }
        container.addView(shapes, lp().apply { topMargin = ctx.dp(8) })

        container.addView(ToggleRow(ctx, "Captions", "The title at the start and each moment's name", HighlightPrefs.captions) { HighlightPrefs.captions = it; refresh() }, lp().apply { topMargin = ctx.dp(14) })
        container.addView(ToggleRow(ctx, "Sound from your videos", "The music gets quieter while they play", HighlightPrefs.clipSound) { HighlightPrefs.clipSound = it; refresh() }, lp().apply { topMargin = ctx.dp(8) })
        titleField = TextField(ctx, "Title", "", HighlightPrefs.title ?: "") { HighlightPrefs.title = it.ifBlank { null }; refresh() }
        container.addView(titleField, lp().apply { topMargin = ctx.dp(14) })

        section(container, "Moments", "Tap a shot to always include it; tap again to leave it out")
        summary = UI.text(ctx, "", TextStyle.CAPTION)
        container.addView(summary, lp().apply { topMargin = ctx.dp(6) })
        moments = UI.vertical(ctx)
        container.addView(moments, lp().apply { topMargin = ctx.dp(6) })
        onSelectionChanged()
    }

    private fun shapeLabel(a: Aspect?): String = when (a) {
        null -> plan?.let { p -> "Auto (${shapeLabel(p.aspect).substringBefore(' ').lowercase(Locale.UK)})" } ?: "Auto"
        Aspect.LANDSCAPE -> "Landscape 16:9"
        Aspect.PORTRAIT -> "Portrait 9:16"
        Aspect.SQUARE -> "Square"
    }

    override fun onSelectionChanged() {
        if (!::status.isInitialized) return
        val list = selection.usable
        val keys = list.map { it.key }
        if (keys == analysedKeys) return
        analysedKeys = keys
        if (keys != HighlightPrefs.forKeys) {
            // New pictures: earlier choices about shots and moment names don't apply.
            HighlightPrefs.include.clear(); HighlightPrefs.exclude.clear(); HighlightPrefs.names.clear(); HighlightPrefs.title = null
            HighlightPrefs.forKeys = keys
            titleField.edit.setText("")
        }
        analysis?.cancel()
        plan = null; items = emptyList(); infos = emptyList()
        moments.removeAllViews(); cells.clear(); headers.clear()
        if (list.size < 2) {
            status.text = "Pick the photos and videos of an event (two or more). They're put in the order they were taken."
            bar.visibility = View.GONE; summary.text = ""
            return
        }
        bar.visibility = View.VISIBLE
        analysis = scope.launch {
            val found = ArrayList<MediaItem>(); val got = ArrayList<ShotInfo>()
            for ((k, item) in list.withIndex()) {
                status.text = "Looking at your ${noun(list)} with on-device AI… ${k + 1} of ${list.size}"
                bar.setProgress(k.toFloat() / list.size)
                val info = HighlightAnalyzer.cached(item) ?: withContext(Dispatchers.Default) {
                    try { HighlightAnalyzer.analyze(ctx, item) { !isActive } } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                }
                if (!isActive) return@launch
                if (info != null) { found.add(item); got.add(info) }
            }
            bar.visibility = View.GONE
            withContext(Dispatchers.Default) { HighlightAnalyzer.releaseModels() }
            if (found.size < 2) { status.text = "Too few of these files could be read."; return@launch }
            items = found; infos = got
            status.text = if (found.size < list.size) "${list.size - found.size} of the files couldn't be read and are left out." else ""
            status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
            buildMoments()
        }
    }

    private fun noun(list: List<MediaItem>): String {
        val v = list.count { it.kind == MediaKind.VIDEO }; val p = list.size - v
        return when { v == 0 -> "photos"; p == 0 -> "videos"; else -> "photos and videos" }
    }

    /** One row per moment, its shots in the order they were taken. */
    private fun buildMoments() {
        val p = makePlan() ?: return
        plan = p
        moments.removeAllViews(); cells.clear(); headers.clear()
        val byShot = items.indices.associateBy { it }
        for (m in p.moments) {
            val card = UI.card(ctx, 12, Palette.SURFACE_2)
            val top = UI.horizontal(ctx)
            val name = UI.text(ctx, m.label, TextStyle.SUBTITLE)
            val info = UI.text(ctx, "", TextStyle.CAPTION)
            val texts = UI.vertical(ctx).apply { addView(name); addView(info) }
            top.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(UI.secondaryButton(ctx, "Rename") { rename(m.index) })
            card.addView(top)
            headers[m.index] = name to info
            val strip = UI.horizontal(ctx)
            for (s in m.shots) {
                val i = byShot[s.id] ?: continue
                val cell = ShotCell(items[i], infos[i]) { cycle(items[i]) }
                cells[items[i].key] = cell
                strip.addView(cell, LinearLayout.LayoutParams(ctx.dp(88), ctx.dp(88)).apply { rightMargin = ctx.dp(8) })
            }
            card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, lp().apply { topMargin = ctx.dp(10) })
            moments.addView(card, lp().apply { topMargin = ctx.dp(10) })
        }
        update(p)
    }

    private fun makePlan(): Plan? = if (items.size < 2) null else try { HighlightPrefs.settings().plan(items, infos) } catch (_: IllegalArgumentException) { null }

    /** After any change: the plan again, and every mark and count with it. */
    private fun refresh() {
        if (items.size < 2) return
        val p = makePlan()
        if (p == null) { summary.text = "Everything is left out — tap a shot to bring it back."; cells.values.forEach { it.show(false, false, true, 0) }; return }
        plan = p
        update(p)
    }

    private fun update(p: Plan) {
        val used = HashMap<Int, Long>()
        for (c in p.clips) used[c.shot.id] = c.durationMs
        for ((i, item) in items.withIndex()) cells[item.key]?.show(i in used, item.key in HighlightPrefs.include, item.key in HighlightPrefs.exclude, used[i] ?: 0)
        for (m in p.moments) {
            val (name, info) = headers[m.index] ?: continue
            name.text = m.label
            val n = p.clips.count { it.moment == m.index }
            info.text = "${clock(m.startMs)}–${clock(m.endMs)} · ${if (n == 0) "not in the video" else "$n of ${m.shots.size} in the video"}"
        }
        val music = HighlightPrefs.mood?.let { "${it.label} music" } ?: "no music"
        summary.text = "${p.moments.size} ${if (p.moments.size == 1) "moment" else "moments"} · ${p.clips.size} clips · ${Format.duration(p.durationMs)} · $music · ${shapeLabel(p.aspect)}"
        shapes.relabel()
        titleField.edit.hint = Planner.title(infos.mapIndexed { i, s -> s.shot(i) }, java.util.TimeZone.getDefault())
    }

    private fun clock(ms: Long): String = java.text.SimpleDateFormat("H:mm", Locale.UK).format(java.util.Date(ms))

    /** Auto → always in → left out → auto. */
    private fun cycle(item: MediaItem) {
        val k = item.key
        when {
            k in HighlightPrefs.include -> { HighlightPrefs.include.remove(k); HighlightPrefs.exclude.add(k) }
            k in HighlightPrefs.exclude -> HighlightPrefs.exclude.remove(k)
            else -> HighlightPrefs.include.add(k)
        }
        refresh()
    }

    private fun rename(index: Int) {
        val current = plan?.moments?.getOrNull(index)?.label ?: return
        val input = EditText(ctx).apply {
            setText(HighlightPrefs.names[index] ?: current); setSelectAllOnFocus(true); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
        }
        AlertDialog.Builder(activity).setTitle("Name this moment").setView(input)
            .setPositiveButton("Save") { _, _ -> val t = input.text.toString().trim(); if (t.isEmpty()) HighlightPrefs.names.remove(index) else HighlightPrefs.names[index] = t; refresh() }
            .setNeutralButton("Automatic") { _, _ -> HighlightPrefs.names.remove(index); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ listening to the music
    private fun toggleMusic() {
        if (player != null || playJob?.isActive == true) { stopMusic(); return }
        val mood = HighlightPrefs.mood ?: run { android.widget.Toast.makeText(ctx, "Pick a kind of music first.", android.widget.Toast.LENGTH_SHORT).show(); return }
        playButton.label = "Writing…"
        val seconds = (plan?.durationMs?.div(1000.0) ?: 20.0).coerceIn(10.0, 30.0)
        playJob = scope.launch {
            val pcm = withContext(Dispatchers.Default) { try { HighlightAudio.music(ctx, mood, seconds, HighlightPrefs.seed) } catch (e: Exception) { null } }
            if (pcm == null || !isActive) { playButton.label = "Listen"; return@launch }
            // Streamed in small pieces: phones limit how much one static buffer may hold.
            val track = try {
                val min = AudioTrack.getMinBufferSize(HighlightAudio.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(HighlightAudio.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(min, HighlightAudio.RATE * 8 / 4)).build()
                    .also { it.play() }
            } catch (e: Exception) { null }
            if (track == null) { playButton.label = "Listen"; android.widget.Toast.makeText(ctx, "The music can't be played on this phone right now.", android.widget.Toast.LENGTH_SHORT).show(); return@launch }
            player = track
            playButton.label = "Stop"
            previewFrames = 0
            withContext(Dispatchers.IO) {
                val buf = java.nio.ByteBuffer.allocateDirect(8192 * 4).order(java.nio.ByteOrder.nativeOrder())
                var i = 0
                while (isActive && player === track && i < pcm.size) {
                    val n = minOf(8192, pcm.size - i)
                    buf.clear(); buf.asFloatBuffer().put(pcm, i, n); buf.limit(n * 4)
                    val w = try { track.write(buf, n * 4, AudioTrack.WRITE_BLOCKING) } catch (_: Exception) { -1 }
                    if (w <= 0) break
                    i += w / 4
                    previewFrames = i / 2
                }
            }
            if (player === track) stopMusic()
        }
    }

    private fun stopMusic() {
        playJob?.cancel(); playJob = null
        player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        player = null
        if (::playButton.isInitialized) playButton.label = "Listen"
    }

    override fun onHide() { stopMusic(); super.onHide() }
    override fun onDestroy() { stopMusic(); analysis?.cancel(); super.onDestroy() }

    override fun outputNaming() = "highlight_<date>.mp4"
    override fun createJob(items: List<MediaItem>): ExportJob = HighlightJob(items, HighlightPrefs.settings())

    // ------------------------------------------------------------------ one shot on the timeline
    private inner class ShotCell(val item: MediaItem, info: ShotInfo, onTap: () -> Unit) : FrameLayout(ctx) {
        private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Shapes.rounded(ctx, Palette.SURFACE_3, 12f); clipToOutline = true }
        private val badge = UI.text(ctx, "", TextStyle.LABEL, Color.WHITE).apply { textSize = 10f; setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2)) }
        private val corner = UI.text(ctx, "", TextStyle.LABEL, Color.WHITE).apply { textSize = 10f; setPadding(ctx.dp(5), ctx.dp(1), ctx.dp(5), ctx.dp(1)); background = Shapes.rounded(ctx, 0x99000000.toInt(), 8f) }

        init {
            setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(ctx.dp(5), 0, 0, ctx.dp(5)) })
            if (info.durationMs > 0) {
                corner.text = "▶ ${Format.duration(info.durationMs)}"
                addView(corner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, ctx.dp(5), ctx.dp(5), 0) })
            }
            Thumbs.load(ctx, item, ctx.dp(84), image)
            isClickable = true; isFocusable = true
            setOnClickListener { onTap() }
        }

        fun show(used: Boolean, must: Boolean, out: Boolean, ms: Long) {
            val color = when { out -> Palette.STROKE; must -> Palette.ACCENT; used -> Palette.SUCCESS; else -> Palette.STROKE }
            background = Shapes.rounded(ctx, Palette.SURFACE_2, 14f, color, if (used || must) 2.5f else 1f)
            image.alpha = if (out) 0.3f else if (used) 1f else 0.7f
            val text = when { out -> "Left out"; must && used -> "★ ${seconds(ms)}"; used -> seconds(ms); else -> null }
            badge.text = text ?: ""
            badge.visibility = if (text != null) VISIBLE else GONE
            badge.background = Shapes.rounded(ctx, if (out) 0xCC333333.toInt() else if (must) Palette.ACCENT else Palette.SUCCESS, 100f)
            contentDescription = "${item.name}, " + when { out -> "left out"; must -> "always in"; used -> "in the video"; else -> "not used" }
        }

        private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)
    }
}
