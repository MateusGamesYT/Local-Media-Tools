package com.localmediatools.ui.tools

import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportJob
import com.localmediatools.tools.MergeVideosJob
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.VideoSpeedJob
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.Palette
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import com.localmediatools.video.Concatenator
import com.localmediatools.video.VideoInfo
import com.localmediatools.video.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// =========================================================================== Merge videos
object MergePrefs { var keepSound = true }

class MergeVideosScreen(a: MainActivity) : ToolScreen(a, ToolId.MERGE_VIDEOS) {
    private lateinit var info: TextView
    private var checkJob: Job? = null
    private var checkedKeys: List<String>? = null

    override fun buildOptions(container: LinearLayout) {
        info = UI.text(ctx, "Select two or more videos. They are joined in the order shown above (tap Review to reorder).", TextStyle.BODY_2)
        container.addView(info, lp().apply { topMargin = ctx.dp(4) })
        container.addView(ToggleRow(ctx, "Keep sound", "Clips without sound get silence", MergePrefs.keepSound) { MergePrefs.keepSound = it; checkedKeys = null; onSelectionChanged() }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, "Clips filmed on the same phone with the same settings are joined without re-encoding, in seconds and with no quality loss. Otherwise they're converted to one size (black bars where shapes differ) and format first.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(10) })
        onSelectionChanged()
    }

    override fun onSelectionChanged() {
        if (!::info.isInitialized) return
        val items = selection.usable
        val keys = items.map { it.key }
        if (items.size < 2) { checkedKeys = null; info.text = "Select two or more videos. They are joined in the order shown above (tap Review to reorder)."; info.setTextColor(Palette.TEXT_2); return }
        if (keys == checkedKeys) return
        checkedKeys = keys
        info.text = "Checking the clips…"
        checkJob?.cancel()
        checkJob = scope.launch {
            val infos: List<VideoInfo>? = withContext(Dispatchers.IO) { try { items.map { VideoProbe.probe(ctx, it.uri) } } catch (_: Exception) { null } }
            if (checkedKeys != keys) return@launch
            if (infos == null || infos.any { it.video == null }) { info.text = "One of the files couldn't be read as a video."; info.setTextColor(Palette.WARNING); return@launch }
            val total = infos.sumOf { it.durationUs.coerceAtLeast(0) }
            val reason = Concatenator.incompatibility(infos, MergePrefs.keepSound && infos.any { it.hasAudio })
            info.text = "${items.size} clips · ${Format.duration(total / 1000)} in total · " + if (reason == null)
                "these clips can be joined without re-encoding (no quality loss)." else "$reason, so they'll be converted to a common format first (slower)."
            info.setTextColor(if (reason == null) Palette.SUCCESS else Palette.TEXT_2)
        }
    }

    override fun outputNaming() = "firstclip_merged.mp4"
    override fun createJob(items: List<MediaItem>): ExportJob = MergeVideosJob(items, MergePrefs.keepSound)
}

// =========================================================================== Speed & timelapse
object SpeedPrefs {
    var speed = 2.0
    var keepSound = true
}

class SpeedScreen(a: MainActivity) : ToolScreen(a, ToolId.VIDEO_SPEED) {
    private lateinit var info: TextView
    private lateinit var sound: ToggleRow
    private var durationUs = -1L
    private var fps = 0.0
    private var loadedKey: String? = null

    override fun buildOptions(container: LinearLayout) {
        val slow = listOf(0.25, 0.5, 0.75)
        val fast = listOf(1.5, 2.0, 3.0, 4.0)
        val lapse = listOf(8.0, 16.0, 30.0, 60.0)
        section(container, "Slow motion", "Best with high-frame-rate recordings", top = 4)
        val groups = ArrayList<ChoiceGroup<Double>>()
        fun group(list: List<Double>): ChoiceGroup<Double> = ChoiceGroup(ctx, list, { VideoSpeedJob.label(it) }, list.firstOrNull { it == SpeedPrefs.speed }) { v ->
            SpeedPrefs.speed = v
            groups.forEach { g -> if (g.selected != v) g.select(Double.NaN, false) }
            update()
        }.also { groups.add(it) }
        container.addView(group(slow), lp().apply { topMargin = ctx.dp(10) })
        section(container, "Faster")
        container.addView(group(fast), lp().apply { topMargin = ctx.dp(10) })
        section(container, "Timelapse", "Hours become minutes")
        container.addView(group(lapse), lp().apply { topMargin = ctx.dp(10) })
        sound = ToggleRow(ctx, "Keep sound", "Natural pitch, no chipmunk voices (up to 4×)", SpeedPrefs.keepSound) { SpeedPrefs.keepSound = it; update() }
        container.addView(sound, lp().apply { topMargin = ctx.dp(14) })
        info = UI.text(ctx, "", TextStyle.BODY_2)
        container.addView(info, lp().apply { topMargin = ctx.dp(8) })
        container.addView(UI.note(ctx, "Without sound and at up to 120 fps, the video is only re-timed: no re-encoding, no quality loss. Otherwise it's re-encoded as H.264 MP4; timelapses keep 30 frames per second.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
        onSelectionChanged()
    }

    override fun onSelectionChanged() {
        if (!::info.isInitialized) return
        val item = selection.usable.firstOrNull()
        if (item == null) { durationUs = -1; loadedKey = null; update(); return }
        if (item.key == loadedKey) { update(); return }
        loadedKey = item.key
        scope.launch {
            val i = withContext(Dispatchers.IO) { try { VideoProbe.probe(ctx, item.uri) } catch (_: Exception) { null } }
            if (loadedKey != item.key) return@launch
            durationUs = i?.durationUs ?: -1; fps = i?.frameRate ?: 0.0
            update()
        }
    }

    private fun update() {
        val s = SpeedPrefs.speed
        sound.setAvailable(s <= VideoSpeedJob.MAX_SOUND_SPEED)
        if (s <= VideoSpeedJob.MAX_SOUND_SPEED) sound.switch.isChecked = SpeedPrefs.keepSound
        val parts = ArrayList<String>()
        if (durationUs > 0) parts.add("${Format.duration(durationUs / 1000)} becomes ${Format.duration((durationUs / s / 1000).toLong())}")
        if (fps > 0) {
            val out = fps * s
            val withSound = SpeedPrefs.keepSound && s <= VideoSpeedJob.MAX_SOUND_SPEED
            parts.add(when {
                s < 1 && out < 20 -> "only ${out.toInt()} frames per second, so it will look choppy"
                !withSound && out <= 120.5 -> "${out.roundToInt()} fps · no re-encoding"
                else -> "${minOf(out, if (s >= 4) 30.0 else 60.0).roundToInt()} fps"
            })
        }
        info.text = if (parts.isEmpty()) "Speed ${VideoSpeedJob.label(s)}" else "${VideoSpeedJob.label(s)}: " + parts.joinToString(" · ")
        refreshValidation()
    }

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (SpeedPrefs.speed.isNaN()) return "Choose a speed"
        return null
    }

    override fun outputNaming() = "name_${VideoSpeedJob.label(SpeedPrefs.speed).replace("×", "x")}.mp4"
    override fun createJob(items: List<MediaItem>): ExportJob = VideoSpeedJob(items, SpeedPrefs.speed, SpeedPrefs.keepSound && SpeedPrefs.speed <= VideoSpeedJob.MAX_SOUND_SPEED)
}
