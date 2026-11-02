package com.localmediatools.ui.video

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.Format
import com.localmediatools.core.MediaProbe
import com.localmediatools.export.ExportManager
import com.localmediatools.highlight.HighlightAudio
import com.localmediatools.highlight.SongChoice
import com.localmediatools.highlight.core.ShotKind
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import com.localmediatools.music.core.Mood
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.VideoEditJob
import com.localmediatools.tools.VideoEditSettings
import com.localmediatools.ui.BipolarSlider
import com.localmediatools.ui.ButtonView
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.Palette
import com.localmediatools.ui.PickKind
import com.localmediatools.ui.ProgressBarView
import com.localmediatools.ui.Screen
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.Thumbs
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import com.localmediatools.video.EditDraft
import com.localmediatools.video.EditMedia
import com.localmediatools.video.ReframeAnalyzer
import com.localmediatools.video.core.EditClip
import com.localmediatools.video.core.EditOps
import com.localmediatools.video.core.EditProject
import com.localmediatools.video.core.EditText as EditTextItem
import com.localmediatools.video.core.EditTimeline
import com.localmediatools.video.core.Framing
import com.localmediatools.video.core.Look
import com.localmediatools.video.core.LookPreset
import com.localmediatools.video.core.OutShape
import com.localmediatools.video.core.TextPlace
import com.localmediatools.video.core.TransitionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The editor's tool tabs. */
enum class EditTab(val icon: Int) {
    CLIP(R.drawable.ic_tool_trim), FRAME(R.drawable.ic_crop), LOOK(R.drawable.ic_filter), TEXT(R.drawable.ic_text),
    SOUND(R.drawable.ic_music), SHAPE(R.drawable.ic_grid);

    val label: String get() = when (this) {
        CLIP -> tr("Clip"); FRAME -> tr("Frame"); LOOK -> tr("Look"); TEXT -> tr("Text"); SOUND -> tr("Sound"); SHAPE -> tr("Shape")
    }
}

/**
 * The video editor: videos and photos in order on a strip, a preview of any moment as the saved video
 * will look, and tools for the selected clip (trim, split, speed, volume, turn, framing with auto
 * reframe, looks) and the whole video (texts, music, shape). Every change can be undone; the project
 * is kept as a draft until it is saved or started over.
 */
class VideoEditorScreen(activity: MainActivity, private val initial: List<Uri> = emptyList()) : Screen(activity) {
    var project = EditProject(); private set
    var selected = 0; private set
    var tab = EditTab.CLIP; private set
    var mood: Mood? = null; private set
    var song: SongChoice? = null; private set
    private var seed = 1L
    private var nextId = 1L
    private val undo = ArrayList<EditProject>()
    private val redo = ArrayList<EditProject>()
    var playheadMs = 0L; private set

    private lateinit var preview: EditPreview
    private lateinit var stage: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var empty: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var playBtn: ImageView
    private lateinit var undoBtn: ImageView
    private lateinit var redoBtn: ImageView
    private lateinit var exportBtn: ButtonView
    private lateinit var busy: TextView
    private val player = Player()
    private var reframeJob: Job? = null

    val timeline get() = EditTimeline(project)

    /** Photos and videos are judged against a dark surround, in either theme. */
    override val alwaysDark: Boolean get() = true

    override fun createView(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        val col = UI.vertical(ctx)
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = UI.horizontal(ctx).apply { setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(10), ctx.dp(6)); gravity = Gravity.CENTER_VERTICAL }
        bar.addView(UI.iconButton(ctx, R.drawable.ic_close, tr("Close the editor")) { pop() })
        bar.addView(UI.text(ctx, ToolId.VIDEO_EDITOR.title, TextStyle.SUBTITLE).apply { maxLines = 1; isAccessibilityHeading = true }, lp(0, WRAP, 1f).apply { leftMargin = ctx.dp(2) })
        undoBtn = UI.iconButton(ctx, R.drawable.ic_undo, tr("Undo")) { undo() }
        redoBtn = UI.iconButton(ctx, R.drawable.ic_redo, tr("Redo")) { redo() }
        bar.addView(undoBtn); bar.addView(redoBtn)
        bar.addView(UI.iconButton(ctx, R.drawable.ic_more, tr("More")) { more() })
        exportBtn = UI.primaryButton(ctx, tr("Save")) { exportSheet() }
        exportBtn.minimumHeight = ctx.dp(40)
        exportBtn.setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        bar.addView(exportBtn, LinearLayout.LayoutParams(WRAP, ctx.dp(40)).apply { leftMargin = ctx.dp(6) })
        col.addView(bar)

        stage = FrameLayout(ctx)
        preview = EditPreview(ctx, scope)
        stage.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))
        texture = TextureView(ctx).apply { visibility = View.GONE }
        stage.addView(texture, FrameLayout.LayoutParams(0, 0))
        empty = UI.vertical(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(24), 0, ctx.dp(24), 0)
            addView(UI.text(ctx, tr("Add videos and photos to start"), TextStyle.SUBTITLE, Color.WHITE).apply { gravity = Gravity.CENTER })
            addView(UI.text(ctx, tr("They go in the order you pick them; you can move them later."), TextStyle.BODY_2).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, ctx.dp(14)) })
            addView(ButtonView(ctx, tr("Add videos and photos"), ButtonView.Kind.PRIMARY, R.drawable.ic_add) { add() })
        }
        stage.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        busy = UI.text(ctx, "", TextStyle.CAPTION, Color.WHITE).apply {
            background = Shapes.rounded(ctx, 0xCC000000.toInt(), 12f); setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6)); visibility = View.GONE
        }
        stage.addView(busy, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.dp(8) })
        col.addView(stage, lp(MATCH, 0, 1f))

        // Play, time and the whole video's position.
        val transport = UI.horizontal(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(12), 0) }
        playBtn = UI.iconButton(ctx, R.drawable.ic_play, tr("Play")) { if (player.playing) player.stop() else player.start() }
        transport.addView(playBtn)
        time = UI.text(ctx, "", TextStyle.CAPTION, Palette.TEXT_2)
        transport.addView(time)
        seek = SeekBar(ctx).apply { max = 1000 }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { player.stop(); setPlayhead(timeline.durationMs * p / 1000, follow = true) } }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        transport.addView(seek, lp(0, WRAP, 1f))
        col.addView(transport)

        strip = UI.horizontal(ctx).apply { setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6)); gravity = Gravity.CENTER_VERTICAL }
        col.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) })

        val bottom = UI.vertical(ctx).apply { background = Shapes.rounded(ctx, Palette.BG_2, 0f) }
        panel = UI.vertical(ctx).apply { setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(4)) }
        bottom.addView(android.widget.ScrollView(ctx).apply { addView(panel) }, lp(MATCH, ctx.dp(196)))
        tabs = UI.horizontal(ctx).apply { setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(8)) }
        for (t in EditTab.entries) {
            tabs.addView(UI.vertical(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(8), 0, ctx.dp(6))
                isClickable = true; isFocusable = true
                tag = t
                contentDescription = t.label
                setOnClickListener { setTab(t) }
                addView(UI.iconView(ctx, t.icon, Palette.TEXT_3, 22))
                addView(UI.text(ctx, t.label, TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(4), 0, 0); textSize = 11.5f })
            }, lp(0, WRAP, 1f))
        }
        bottom.addView(tabs)
        col.addView(bottom)

        // A draft from before, unless files were handed over.
        if (initial.isEmpty()) EditDraft.load(ctx)?.let { d ->
            project = d.project; mood = d.mood; song = d.song; seed = d.seed
            nextId = (project.clips.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(project.texts.maxOfOrNull { it.id } ?: 0L) + 1
        } else addUris(initial, replace = true)
        refreshAll()
        return root
    }

    // ------------------------------------------------------------------ project changes
    /** Applies a change (one step to undo). */
    fun change(p: EditProject, keepPlayhead: Boolean = true) {
        if (p == project) return
        undo.add(project); if (undo.size > 100) undo.removeAt(0)
        redo.clear()
        project = p
        if (!keepPlayhead) playheadMs = 0
        afterChange()
    }

    private fun afterChange() {
        selected = selected.coerceIn(0, max(0, project.clips.size - 1))
        playheadMs = playheadMs.coerceIn(0, max(0, timeline.durationMs - 1))
        saveDraft()
        refreshAll()
    }

    fun undo() { if (undo.isEmpty()) return; redo.add(project); project = undo.removeAt(undo.size - 1); afterChange() }
    fun redo() { if (redo.isEmpty()) return; undo.add(project); project = redo.removeAt(redo.size - 1); afterChange() }

    private fun saveDraft() {
        val s = EditDraft.Saved(project, mood, song, seed)
        scope.launch(Dispatchers.IO) { try { EditDraft.save(ctx, s) } catch (_: Exception) { } }
    }

    private val clip: EditClip? get() = project.clips.getOrNull(selected)

    private fun updateClip(f: (EditClip) -> EditClip) { val c = clip ?: return; change(EditOps.replace(project, selected, f(c))) }

    // ------------------------------------------------------------------ adding
    fun add() = activity.pickMedia(PickKind.MEDIA, multiple = true) { uris -> addUris(uris, replace = false) }

    fun addUris(uris: List<Uri>, replace: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            showBusy(trn(uris.size, "Adding {0} file…", "Adding {0} files…"))
            val found = withContext(Dispatchers.IO) { uris.map { EditMedia.describe(ctx, it) } }
            showBusy(null)
            val ok = found.filterNotNull()
            if (ok.size < uris.size) Toast.makeText(ctx, trn(uris.size - ok.size, "{0} file isn't a video or photo that can be read.", "{0} files aren't videos or photos that can be read."), Toast.LENGTH_LONG).show()
            if (ok.isEmpty()) return@launch
            val clips = ok.map { EditDraft.clipFor(it, nextId++) }
            val base = if (replace) EditProject() else project
            change(base.copy(clips = base.clips + clips))
            if (!replace) selected = project.clips.size - clips.size
            refreshAll()
        }
    }

    // ------------------------------------------------------------------ playhead
    fun setPlayhead(ms: Long, follow: Boolean) {
        playheadMs = ms.coerceIn(0, max(0, timeline.durationMs - 1))
        if (follow) timeline.clipAt(playheadMs)?.let { (i, _) -> if (i != selected) { selected = i; refreshStrip(); if (tab != EditTab.TEXT && tab != EditTab.SOUND && tab != EditTab.SHAPE) buildPanel() } }
        preview.timeMs = playheadMs
        refreshTime()
    }

    private fun refreshTime() {
        val d = timeline.durationMs
        time.text = "${Format.clock(playheadMs)} / ${Format.clock(d)}"
        seek.progress = if (d > 0) (playheadMs * 1000 / d).toInt() else 0
    }

    // ------------------------------------------------------------------ view refresh
    private fun refreshAll() {
        val has = project.clips.isNotEmpty()
        empty.visibility = if (has) View.GONE else View.VISIBLE
        preview.timeline = if (has) timeline else null
        preview.timeMs = playheadMs
        undoBtn.isEnabled = undo.isNotEmpty(); undoBtn.alpha = if (undo.isNotEmpty()) 1f else 0.35f
        redoBtn.isEnabled = redo.isNotEmpty(); redoBtn.alpha = if (redo.isNotEmpty()) 1f else 0.35f
        exportBtn.isEnabled = has
        refreshStrip(); refreshTabs(); buildPanel(); refreshTime()
    }

    private fun refreshStrip() {
        strip.removeAllViews()
        val tl = timeline
        for ((i, c) in project.clips.withIndex()) {
            if (i > 0) strip.addView(transitionChip(i), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(2); rightMargin = ctx.dp(2) })
            strip.addView(clipTile(i, c), LinearLayout.LayoutParams(ctx.dp((56 + (tl.placed.getOrNull(i)?.clip?.lengthMs ?: 0L) / 250).toInt().coerceIn(60, 132)), ctx.dp(56)))
        }
        strip.addView(UI.vertical(ctx).apply {
            gravity = Gravity.CENTER
            background = Shapes.clickable(ctx, Palette.SURFACE_2, 12f, Palette.STROKE_2)
            isClickable = true; isFocusable = true
            contentDescription = tr("Add videos and photos")
            setOnClickListener { add() }
            addView(UI.iconView(ctx, R.drawable.ic_add, Palette.TEXT_2, 22))
        }, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)).apply { leftMargin = ctx.dp(8) })
    }

    private fun clipTile(i: Int, c: EditClip): View = FrameLayout(ctx).apply {
        val on = i == selected
        background = Shapes.rounded(ctx, Palette.SURFACE_2, 12f)
        clipToOutline = true
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // With the clip's look, as it will be in the video.
            c.look.matrix()?.let { colorFilter = android.graphics.ColorMatrixColorFilter(EditPreview.colorMatrix(it)) }
        }
        addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        scope.launch {
            val b = withContext(Dispatchers.IO) { EditMedia.frame(ctx, c.source, c.inMs, 160) }
            if (b != null) img.setImageBitmap(b)
        }
        val len = UI.text(ctx, Format.clock(c.lengthMs), TextStyle.CAPTION, Color.WHITE).apply {
            textSize = 10.5f; setPadding(ctx.dp(5), ctx.dp(1), ctx.dp(5), ctx.dp(1)); background = Shapes.rounded(ctx, 0x99000000.toInt(), 8f)
        }
        addView(len, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(ctx.dp(4), 0, 0, ctx.dp(4)) })
        if (c.isPhoto) addView(UI.iconView(ctx, R.drawable.ic_image, Color.WHITE, 14), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, ctx.dp(4), ctx.dp(4), 0) })
        if (on) foreground = Shapes.rounded(ctx, Color.TRANSPARENT, 12f, Palette.ACCENT, 3f)
        isClickable = true; isFocusable = true
        contentDescription = tr("Clip {0}: {1}, {2}", i + 1, c.source.name, Format.clock(c.lengthMs)) + if (on) tr(", selected") else ""
        setOnClickListener {
            player.stop()
            selected = i
            setPlayhead(timeline.placed[i].startMs + 1, follow = false)
            refreshStrip(); buildPanel()
        }
    }

    private fun transitionChip(i: Int): View {
        val c = project.clips[i]
        val label = when (c.transition) { TransitionKind.CUT -> "|"; TransitionKind.CROSSFADE -> "⨯"; TransitionKind.FADE_BLACK -> "●" }
        return UI.text(ctx, label, TextStyle.CAPTION, Palette.TEXT).apply {
            gravity = Gravity.CENTER
            minWidth = ctx.dp(28); minHeight = ctx.dp(28)
            background = Shapes.clickable(ctx, Palette.SURFACE_2, 100f, Palette.STROKE_2)
            isClickable = true; isFocusable = true
            contentDescription = tr("Transition into clip {0}: {1}", i + 1, transitionLabel(c.transition))
            setOnClickListener {
                val next = TransitionKind.entries[(c.transition.ordinal + 1) % TransitionKind.entries.size]
                change(EditOps.replace(project, i, c.copy(transition = next)))
                Toast.makeText(ctx, transitionLabel(next), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun transitionLabel(t: TransitionKind) = when (t) { TransitionKind.CUT -> tr("Cut"); TransitionKind.CROSSFADE -> tr("Cross-fade"); TransitionKind.FADE_BLACK -> tr("Fade through black") }

    fun setTab(t: EditTab) { tab = t; refreshTabs(); buildPanel() }

    private fun refreshTabs() {
        for (i in 0 until tabs.childCount) {
            val v = tabs.getChildAt(i) as LinearLayout
            val on = v.tag == tab
            (v.getChildAt(0) as ImageView).setColorFilter(if (on) Palette.ACCENT else Palette.TEXT_3)
            (v.getChildAt(1) as TextView).setTextColor(if (on) Palette.TEXT else Palette.TEXT_3)
            v.contentDescription = (v.tag as EditTab).label + if (on) tr(", selected") else ""
        }
    }

    // ------------------------------------------------------------------ panels
    fun buildPanel() {
        panel.removeAllViews()
        val c = clip
        if (c == null && tab != EditTab.SHAPE && tab != EditTab.SOUND) { panel.addView(UI.text(ctx, tr("Add videos and photos to start"), TextStyle.BODY_2)); return }
        when (tab) {
            EditTab.CLIP -> clipPanel(c!!)
            EditTab.FRAME -> framePanel(c!!)
            EditTab.LOOK -> lookPanel(c!!)
            EditTab.TEXT -> textPanel()
            EditTab.SOUND -> soundPanel()
            EditTab.SHAPE -> shapePanel()
        }
    }

    private fun row(vararg views: View): LinearLayout = UI.horizontal(ctx).apply {
        for ((k, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (k > 0) leftMargin = ctx.dp(8) })
    }

    private fun label(text: String, top: Int = 10) = UI.text(ctx, text, TextStyle.CAPTION, Palette.TEXT_2).apply { setPadding(0, ctx.dp(top), 0, ctx.dp(4)) }

    private fun clipPanel(c: EditClip) {
        val i = selected
        val p = timeline.placed[i]
        panel.addView(UI.text(ctx, "${c.source.name} · ${Format.clock(c.lengthMs)}", TextStyle.CAPTION, Palette.TEXT_2).apply { maxLines = 1 })
        if (!c.isPhoto) {
            // Trim: where the clip starts and ends in its file.
            panel.addView(label(tr("Start {0} · end {1} of {2}", Format.clock(c.inMs), Format.clock(c.outMs), Format.clock(c.source.durationMs))))
            val dur = max(1L, c.source.durationMs)
            val start = SeekBar(ctx).apply { max = 1000; progress = (c.inMs * 1000 / dur).toInt(); contentDescription = tr("Start of the clip") }
            val end = SeekBar(ctx).apply { max = 1000; progress = (c.outMs * 1000 / dur).toInt(); contentDescription = tr("End of the clip") }
            fun hook(sb: SeekBar, isStart: Boolean) = sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) { player.stop() }
                override fun onStopTrackingTouch(s: SeekBar) {
                    val ms = dur * s.progress / 1000
                    change(if (isStart) EditOps.trim(project, i, ms, c.outMs) else EditOps.trim(project, i, c.inMs, ms))
                    preview.forget()
                }
            })
            hook(start, true); hook(end, false)
            panel.addView(row(start, end))
        } else {
            panel.addView(label(tr("Shown for {0}", Format.clock(c.lengthMs))))
            panel.addView(ChoiceGroup(ctx, listOf(1500L, 3000L, 5000L, 8000L), { tr("{0} s", it / 1000.0) }, c.lengthMs) { ms -> change(EditOps.trim(project, i, 0, ms)) })
        }
        panel.addView(row(
            UI.secondaryButton(ctx, tr("Split here")) {
                val at = playheadMs - p.startMs
                val q = EditOps.split(project, i, at, nextId++)
                if (q == null) Toast.makeText(ctx, tr("Move the playhead inside the clip, at least half a second from its ends."), Toast.LENGTH_LONG).show() else change(q)
            },
            UI.secondaryButton(ctx, tr("Duplicate")) { change(EditOps.duplicate(project, i, nextId++)) },
            UI.secondaryButton(ctx, tr("Delete")) { change(EditOps.remove(project, i)) },
        ).apply { setPadding(0, ctx.dp(10), 0, 0) })
        panel.addView(row(
            UI.secondaryButton(ctx, tr("Move left")) { if (i > 0) { change(EditOps.move(project, i, i - 1)); selected = i - 1; refreshAll() } },
            UI.secondaryButton(ctx, tr("Move right")) { if (i < project.clips.size - 1) { change(EditOps.move(project, i, i + 1)); selected = i + 1; refreshAll() } },
            UI.secondaryButton(ctx, tr("Turn")) { updateClip { it.copy(rotation = (it.rotation + 90) % 360, reframe = null) }; preview.forget() },
        ).apply { setPadding(0, ctx.dp(8), 0, 0) })
        if (!c.isPhoto) {
            panel.addView(label(tr("Speed")))
            panel.addView(ChoiceGroup(ctx, EditOps.SPEEDS.toList(), { s -> if (s == 1f) tr("Normal") else "${Format.decimal(s.toDouble())}×" }, c.speed) { s -> updateClip { it.copy(speed = s) } })
            panel.addView(label(if (c.source.hasAudio) tr("Volume {0}%", (c.volume * 100).roundToInt()) else tr("This video has no sound")))
            if (c.source.hasAudio) panel.addView(BipolarSlider(ctx, 200f) { v, done -> if (done) updateClip { it.copy(volume = (v / 100f).coerceIn(0f, 2f)) } }.apply { bipolar = false; set(c.volume * 100f) })
        }
    }

    private fun framePanel(c: EditClip) {
        panel.addView(label(tr("How the clip fills the video's shape"), 0))
        panel.addView(ChoiceGroup(ctx, Framing.entries, { framingLabel(it) }, c.framing) { f ->
            updateClip { it.copy(framing = f) }
            if (f == Framing.AUTO) reframe()
        })
        when (c.framing) {
            Framing.FIT -> panel.addView(UI.text(ctx, tr("The whole picture, over a blurred copy of itself where the shapes differ."), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(8), 0, 0) })
            Framing.FILL -> {
                panel.addView(label(tr("Zoom")))
                panel.addView(BipolarSlider(ctx, 200f) { v, done -> if (done) updateClip { it.copy(scale = 1f + v / 100f) } }.apply { bipolar = false; set((c.scale - 1f) * 100f) })
                panel.addView(label(tr("Left – right")))
                panel.addView(BipolarSlider(ctx, 100f) { v, done -> if (done) updateClip { it.copy(panX = v / 100f) } }.apply { set(c.panX * 100f) })
                panel.addView(label(tr("Up – down")))
                panel.addView(BipolarSlider(ctx, 100f) { v, done -> if (done) updateClip { it.copy(panY = v / 100f) } }.apply { set(c.panY * 100f) })
            }
            Framing.AUTO -> {
                val ready = c.reframe != null && abs(c.reframeFor - project.outAspect) < 1e-3f
                panel.addView(UI.text(ctx, when {
                    c.isPhoto -> tr("Photos are filled from the middle; auto reframe follows people and things in videos.")
                    ready -> tr("Follows the person or thing the clip is about, found by on-device AI.")
                    else -> tr("Finding the subject when the clip is ready…")
                }, TextStyle.CAPTION).apply { setPadding(0, ctx.dp(8), 0, 0) })
                if (!c.isPhoto) {
                    panel.addView(label(tr("Zoom in on the subject")))
                    panel.addView(BipolarSlider(ctx, 100f) { v, done -> if (done) { updateClip { it.copy(zoom = v / 100f, reframe = null) }; reframe() } }.apply { bipolar = false; set(c.zoom * 100f) })
                }
            }
        }
    }

    private fun framingLabel(f: Framing) = when (f) { Framing.FIT -> tr("Whole picture"); Framing.FILL -> tr("Fill"); Framing.AUTO -> tr("Auto reframe") }

    /** Plans the selected clip's camera (auto reframe) in the background, then shows it. */
    fun reframe() {
        val c = clip ?: return
        if (c.framing != Framing.AUTO || c.isPhoto) return
        if (c.reframe != null && abs(c.reframeFor - project.outAspect) < 1e-3f) return
        reframeJob?.cancel()
        val id = c.id; val aspect = project.outAspect
        reframeJob = scope.launch {
            showBusy(tr("Finding the subject…"))
            val keys = try {
                withContext(Dispatchers.Default) { ReframeAnalyzer.analyze(ctx, Uri.parse(c.source.uri), c.inMs, c.outMs, aspect, c.zoom, c.rotation) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(ctx, tr("The subject couldn't be found in this clip; it is filled from the middle."), Toast.LENGTH_LONG).show(); null
            }
            showBusy(null)
            val i = project.clips.indexOfFirst { it.id == id }
            if (i < 0 || keys == null) return@launch
            // Not a step of its own: it belongs to choosing auto reframe.
            project = EditOps.replace(project, i, project.clips[i].copy(reframe = keys, reframeFor = aspect))
            saveDraft(); refreshAll()
        }
    }

    private fun lookPanel(c: EditClip) {
        panel.addView(ChoiceGroup(ctx, LookPreset.entries, { lookLabel(it) }, c.look.preset) { p -> updateClip { it.copy(look = it.look.copy(preset = p)) } })
        fun slider(name: String, get: (Look) -> Float, set: (Look, Float) -> Look) {
            panel.addView(label(name))
            panel.addView(BipolarSlider(ctx, 100f) { v, done -> if (done) updateClip { it.copy(look = set(it.look, v / 100f)) } }.apply { set(get(c.look) * 100f) })
        }
        slider(tr("Brightness"), { it.brightness }) { l, v -> l.copy(brightness = v) }
        slider(tr("Contrast"), { it.contrast }) { l, v -> l.copy(contrast = v) }
        slider(tr("Saturation"), { it.saturation }) { l, v -> l.copy(saturation = v) }
        slider(tr("Warmth"), { it.warmth }) { l, v -> l.copy(warmth = v) }
        panel.addView(UI.secondaryButton(ctx, tr("Use on every clip")) { change(project.copy(clips = project.clips.map { it.copy(look = c.look) })) }.apply { }, lp().apply { topMargin = ctx.dp(10) })
    }

    private fun lookLabel(p: LookPreset) = when (p) {
        LookPreset.NONE -> tr("Original"); LookPreset.VIVID -> tr("Vivid"); LookPreset.WARM -> tr("Warm"); LookPreset.COOL -> tr("Cool")
        LookPreset.MONO -> tr("Black & white"); LookPreset.FADED -> tr("Faded"); LookPreset.DRAMA -> tr("Drama")
    }

    private fun textPanel() {
        panel.addView(ButtonView(ctx, tr("Add text here"), ButtonView.Kind.PRIMARY, R.drawable.ic_add) { editText(null) })
        if (project.texts.isEmpty()) panel.addView(UI.text(ctx, tr("Titles and captions appear over the video from the playhead on, for 3 seconds; change that after adding."), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(8), 0, 0) })
        for (t in project.texts.sortedBy { it.startMs }) {
            panel.addView(UI.horizontal(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ctx.dp(8), 0, 0)
                addView(UI.text(ctx, "${Format.clock(t.startMs)}–${Format.clock(t.endMs)}  ${t.text}", TextStyle.BODY).apply { maxLines = 1 }, lp(0, WRAP, 1f))
                addView(UI.secondaryButton(ctx, tr("Edit")) { setPlayhead(shownAt(t.startMs, t.endMs), follow = true); editText(t) })
                addView(UI.iconButton(ctx, R.drawable.ic_trash, tr("Delete \"{0}\"", t.text)) { change(project.copy(texts = project.texts.filter { it.id != t.id })) })
            })
        }
    }

    /** A moment where a text from [start] to [end] is fully shown (past its fade-in). */
    private fun shownAt(start: Long, end: Long): Long = min(start + EditTimeline.TEXT_FADE_MS.toLong() + 50, (start + end) / 2)

    /** Adds a text at the playhead ([t] null) or changes one. */
    fun editText(t: EditTextItem?) {
        val box = UI.vertical(ctx).apply { setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0) }
        val field = EditText(ctx).apply { setText(t?.text ?: ""); hint = tr("Your text"); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        box.addView(field)
        var place = t?.place ?: TextPlace.BOTTOM
        var seconds = ((t?.let { it.endMs - it.startMs } ?: 3000L) / 1000.0)
        var big = (t?.size ?: 1f) > 1.2f
        var withBox = t?.box ?: false
        box.addView(label(tr("Position")))
        box.addView(ChoiceGroup(ctx, TextPlace.entries, { when (it) { TextPlace.TOP -> tr("Top"); TextPlace.MIDDLE -> tr("Middle"); TextPlace.BOTTOM -> tr("Bottom") } }, place) { place = it })
        box.addView(label(tr("How long")))
        box.addView(ChoiceGroup(ctx, listOf(2.0, 3.0, 5.0, 8.0), { tr("{0} s", it) }, seconds.takeIf { it in listOf(2.0, 3.0, 5.0, 8.0) }) { seconds = it })
        box.addView(ToggleRow(ctx, tr("Large"), null, big) { big = it })
        box.addView(ToggleRow(ctx, tr("On a dark box"), null, withBox) { withBox = it })
        AlertDialog.Builder(activity).setTitle(if (t == null) tr("Add text") else tr("Edit text")).setView(box)
            .setPositiveButton(tr("OK")) { _, _ ->
                val text = field.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val start = t?.startMs ?: playheadMs
                val end = (start + (seconds * 1000).toLong()).coerceAtMost(max(start + 500, timeline.durationMs))
                val item = EditTextItem(t?.id ?: nextId++, text, start, end, place, if (big) 1.5f else 1f, withBox)
                change(project.copy(texts = project.texts.filter { it.id != item.id } + item))
                // Shown in full in the preview (at its very start it is still fading in).
                setPlayhead(shownAt(start, end), follow = false)
            }
            .setNegativeButton(tr("Cancel"), null).show()
    }

    private fun soundPanel() {
        panel.addView(label(tr("Music"), 0))
        val options = listOf<Any?>(null, Mood.UPBEAT, Mood.CHILL, Mood.CINEMATIC, OwnSong)
        panel.addView(ChoiceGroup(ctx, options, { when (it) { is Mood -> it.label; OwnSong -> song?.name ?: tr("My song"); else -> tr("No music") } },
            if (song != null) OwnSong else mood) { choice ->
            when (choice) {
                OwnSong -> activity.pickAudio { uri ->
                    if (uri == null) { buildPanel(); return@pickAudio }
                    scope.launch {
                        showBusy(tr("Listening to your song…"))
                        val name = withContext(Dispatchers.IO) { MediaProbe.describe(ctx, uri).name }
                        val beat = try { withContext(Dispatchers.Default) { HighlightAudio.songBeat(ctx, uri) } } catch (e: Exception) {
                            showBusy(null); Toast.makeText(ctx, e.message ?: tr("Your song couldn't be read."), Toast.LENGTH_LONG).show(); buildPanel(); return@launch
                        }
                        showBusy(null)
                        song = SongChoice(uri, name, beat?.bpm); mood = null; saveDraft(); buildPanel()
                    }
                }
                else -> { song = null; mood = choice as Mood?; saveDraft(); buildPanel() }
            }
        })
        if (mood != null) panel.addView(UI.secondaryButton(ctx, tr("Another tune")) { seed++; saveDraft(); Toast.makeText(ctx, tr("A new tune will be written when the video is saved."), Toast.LENGTH_SHORT).show() }, lp().apply { topMargin = ctx.dp(8) })
        if (mood != null || song != null) {
            panel.addView(label(tr("Music volume {0}%", (project.musicVolume * 100).roundToInt())))
            panel.addView(BipolarSlider(ctx, 100f) { v, done -> if (done) change(project.copy(musicVolume = v / 100f)) }.apply { bipolar = false; set(project.musicVolume * 100f) })
            panel.addView(ToggleRow(ctx, tr("Quieter while clips play their sound"), null, project.duckMusic) { change(project.copy(duckMusic = it)) }, lp().apply { topMargin = ctx.dp(6) })
        }
        panel.addView(UI.text(ctx, tr("Each clip's own volume is under Clip. The preview plays the clips' own sound; the music is mixed in when the video is saved."), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(10), 0, 0) })
    }

    private object OwnSong

    private fun shapePanel() {
        panel.addView(label(tr("The video's shape"), 0))
        panel.addView(ChoiceGroup(ctx, OutShape.entries, { shapeLabel(it) }, project.shape) { s ->
            change(project.copy(shape = s))
            // Auto reframe is planned for one shape: plan the selected clip again.
            reframe()
        })
        panel.addView(ToggleRow(ctx, tr("Fade out at the end"), null, project.fadeOutMs > 0) { change(project.copy(fadeOutMs = if (it) 1500 else 0)) }, lp().apply { topMargin = ctx.dp(10) })
        panel.addView(UI.text(ctx, tr("For portrait (9:16) and square videos from landscape clips, choose Auto reframe under Frame: the picture follows the person or thing the clip is about."), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(10), 0, 0) })
    }

    private fun shapeLabel(s: OutShape) = when (s) {
        OutShape.ORIGINAL -> tr("As the first clip"); OutShape.LANDSCAPE -> tr("Landscape 16:9"); OutShape.PORTRAIT -> tr("Portrait 9:16")
        OutShape.SQUARE -> tr("Square"); OutShape.TALL -> tr("Portrait 4:5")
    }

    // ------------------------------------------------------------------ saving
    fun exportSheet() {
        if (project.clips.isEmpty()) return
        player.stop()
        var short = 1080
        val box = UI.vertical(ctx).apply { setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0) }
        box.addView(label(tr("Size"), 0))
        box.addView(ChoiceGroup(ctx, listOf(720, 1080), { if (it == 720) tr("720p (smaller)") else tr("1080p") }, short) { short = it })
        val tl = timeline
        box.addView(UI.text(ctx, "${Format.clock(tl.durationMs)} · " + trn(project.clips.size, "{0} clip", "{0} clips"), TextStyle.CAPTION).apply { setPadding(0, ctx.dp(10), 0, 0) })
        AlertDialog.Builder(activity).setTitle(tr("Save the video")).setView(box)
            .setPositiveButton(tr("Save")) { _, _ -> startExport(short) }
            .setNegativeButton(tr("Cancel"), null).show()
    }

    fun startExport(short: Int) {
        val items = project.clips.map { MediaProbe.describe(ctx, Uri.parse(it.source.uri)) }.distinctBy { it.key }
        val job = VideoEditJob(items, project, VideoEditSettings(shortSide = short, mood = mood, song = song, seed = seed))
        activity.ensureNotificationPermission {
            val wasBusy = ExportManager.state.value.busy
            ExportManager.enqueue(job)
            Toast.makeText(ctx, if (wasBusy) tr("Queued — it starts when the current export finishes.") else tr("Export started. You can leave the app; progress is shown in the notification."), Toast.LENGTH_LONG).show()
        }
    }

    private fun more() {
        AlertDialog.Builder(activity).setItems(arrayOf(tr("Start over"))) { _, _ ->
            AlertDialog.Builder(activity).setMessage(tr("Remove every clip, text and setting of this video? The files themselves stay."))
                .setPositiveButton(tr("Start over")) { _, _ -> player.stop(); change(EditProject(), keepPlayhead = false); mood = null; song = null; EditDraft.clear(ctx) }
                .setNegativeButton(tr("Cancel"), null).show()
        }.show()
    }

    private fun showBusy(text: String?) { busy.text = text ?: ""; busy.visibility = if (text == null) View.GONE else View.VISIBLE }

    override fun onBack(): Boolean { player.stop(); return false }
    override fun onHide() { player.stop() }
    override fun onDestroy() { player.stop(); reframeJob?.cancel(); super.onDestroy() }

    // ------------------------------------------------------------------ playing
    /**
     * Plays the project from the playhead: each video with the system's player (at its speed and
     * volume, framed and coloured as it will be saved), photos for their time, texts on top; cuts
     * between clips (fades and music are in the saved video).
     */
    inner class Player : Choreographer.FrameCallback {
        var playing = false; private set
        private var mp: MediaPlayer? = null
        private var index = -1
        private var photoStart = 0L
        private var photoFrom = 0L
        private var prepared = false

        fun start() {
            if (project.clips.isEmpty()) return
            if (playheadMs >= timeline.durationMs - 50) setPlayhead(0, follow = true)
            playing = true
            playBtn.setImageResource(R.drawable.ic_pause); playBtn.contentDescription = tr("Pause")
            startClip(playheadMs)
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun startClip(t: Long) {
            release()
            val (i, ms) = timeline.clipAt(t) ?: return stop()
            index = i
            val c = project.clips[i]
            if (c.isPhoto) { photoStart = System.nanoTime(); photoFrom = t; texture.visibility = View.GONE; preview.textsOnly = false; return }
            val st = texture.surfaceTexture
            if (st == null) { texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { if (playing) startClip(playheadMs) }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }; layoutTexture(); texture.visibility = View.VISIBLE; return }
            layoutTexture()
            try {
                val p = MediaPlayer()
                mp = p; prepared = false
                p.setDataSource(ctx, Uri.parse(c.source.uri))
                p.setSurface(Surface(st))
                p.setOnPreparedListener {
                    if (mp !== p) return@setOnPreparedListener
                    p.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
                    try { p.playbackParams = PlaybackParams().setSpeed(c.speed) } catch (_: Exception) { }
                    val v = c.volume.coerceIn(0f, 1f); p.setVolume(v, v)
                    p.start(); prepared = true
                    texture.visibility = View.VISIBLE; preview.textsOnly = true
                }
                p.setOnErrorListener { _, _, _ -> stop(); true }
                p.prepareAsync()
            } catch (e: Exception) { stop() }
        }

        /** The TextureView over the preview's box, showing the clip as the saved video will. */
        private fun layoutTexture() {
            val b = preview.box
            if (b.isEmpty) return
            texture.layoutParams = FrameLayout.LayoutParams(b.width().toInt(), b.height().toInt()).apply { leftMargin = b.left.toInt(); topMargin = b.top.toInt() }
            applyFrame()
        }

        private fun applyFrame() {
            val c = project.clips.getOrNull(index) ?: return
            val b = preview.box
            val tl = timeline
            val l = tl.at(playheadMs.toDouble()).layers.lastOrNull { it.clip == index } ?: return
            texture.setTransform(EditPreview.textureMatrix(b.width(), b.height(), c.rotation, l.src, l.dst))
            val paint = android.graphics.Paint().apply { l.color?.let { colorFilter = android.graphics.ColorMatrixColorFilter(EditPreview.colorMatrix(it)) } }
            texture.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            val tl = timeline
            val c = project.clips.getOrNull(index) ?: return stop()
            val p = tl.placed[index]
            val t = if (c.isPhoto) photoFrom + (System.nanoTime() - photoStart) / 1_000_000
                else { val m = mp; if (m == null || !prepared) playheadMs else p.startMs + ((m.currentPosition - c.inMs) / c.speed).toLong() }
            if (t >= p.endMs - 20) {
                if (index + 1 >= project.clips.size) { setPlayhead(tl.durationMs - 1, follow = false); stop(); return }
                setPlayhead(p.endMs, follow = true); startClip(p.endMs)
            } else {
                setPlayhead(max(t, p.startMs), follow = false)
                if (c.framing == Framing.AUTO) applyFrame()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun release() { mp?.let { m -> mp = null; try { m.stop() } catch (_: Exception) { }; m.release() } }

        fun stop() {
            if (!playing && mp == null) return
            playing = false
            Choreographer.getInstance().removeFrameCallback(this)
            release()
            if (::texture.isInitialized) texture.visibility = View.GONE
            if (::preview.isInitialized) { preview.textsOnly = false; preview.timeMs = playheadMs }
            if (::playBtn.isInitialized) { playBtn.setImageResource(R.drawable.ic_play); playBtn.contentDescription = tr("Play") }
        }
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
