package com.localmediatools.ui.tools

import android.widget.LinearLayout
import com.localmediatools.app.MainActivity
import com.localmediatools.codec.gif.Dithering
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportJob
import com.localmediatools.tools.CompressVideoJob
import com.localmediatools.tools.ExtractAudioJob
import com.localmediatools.tools.OptimizeVideoJob
import com.localmediatools.tools.RemoveAudioJob
import com.localmediatools.tools.SplitVideoJob
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.VideoToGifJob
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.SliderField
import com.localmediatools.ui.TextField
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import com.localmediatools.video.VideoTranscoder
import java.util.Locale

/** Persistent per-tool option values for the app session. */
object ToolPrefs {
    var splitSeconds = 30.0
    var compressQuality = 72
    var compressShortSide = 0
    var gifSourceMode = false
    var gifFps = 12
    var gifWidth = 480
    var gifStart = 0.0
    var gifLength = 0.0
    var gifDither = Dithering.ORDERED
}

// =========================================================================== Split
class SplitScreen(a: MainActivity) : ToolScreen(a, ToolId.SPLIT_VIDEO) {
    private lateinit var field: TextField
    private var presets: ChoiceGroup<Pair<String, Double>>? = null

    override fun buildOptions(container: LinearLayout) {
        val choices = listOf("15 s" to 15.0, "30 s" to 30.0, "1 min" to 60.0, "5 min" to 300.0, "10 min" to 600.0)
        section(container, "Segment length", "Each part will be about this long", top = 4)
        presets = ChoiceGroup(ctx, choices, { it.first }, choices.firstOrNull { it.second == ToolPrefs.splitSeconds }) { p ->
            ToolPrefs.splitSeconds = p.second
            field.edit.setText(fmt(p.second))
            refreshValidation()
        }
        container.addView(presets, lp().apply { topMargin = ctx.dp(10) })
        field = TextField(ctx, "Custom length (seconds)", "e.g. 45", fmt(ToolPrefs.splitSeconds), numeric = true) { s ->
            s.replace(',', '.').toDoubleOrNull()?.let { v ->
                ToolPrefs.splitSeconds = v
                val match = choices.firstOrNull { it.second == v }
                if (match != null) presets?.select(match, notify = false)
            }
            refreshValidation()
        }
        container.addView(field, lp().apply { topMargin = ctx.dp(14) })
        container.addView(UI.note(ctx, "No re-encoding: parts keep the original quality. Cuts happen at the nearest keyframe (sync point) at or before each boundary, so parts may be slightly shorter than the length you set. Videos with very few keyframes can give some longer parts.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    override fun validate(): String? {
        super.validate()?.let { return it }
        val s = ToolPrefs.splitSeconds
        return if (s < 1 || s > 86400) "Enter a segment length between 1 second and 24 hours" else null
    }

    override fun outputNaming() = "One file per part: name_part01.mp4, name_part02.mp4 … (WebM sources stay WebM)"
    override fun createJob(items: List<MediaItem>): ExportJob = SplitVideoJob(items, ToolPrefs.splitSeconds)
}

// =========================================================================== Optimize
class OptimizeVideoScreen(a: MainActivity) : ToolScreen(a, ToolId.OPTIMIZE_VIDEO) {
    override fun buildOptions(container: LinearLayout) {
        container.addView(UI.note(ctx, "Lossless: video and audio streams are copied bit-for-bit, so quality is exactly the same. This is not compression — the size changes only slightly. Use Video compressor to make files much smaller.", UI.NoteKind.INFO))
        container.addView(UI.note(ctx, "What it improves: the index (moov) is moved to the start so playback and uploads begin immediately, and padding, subtitle and data tracks are removed. Location metadata isn't copied.", UI.NoteKind.TIP), lp().apply { topMargin = ctx.dp(10) })
    }
    override fun outputNaming() = "name_optimized.mp4"
    override fun createJob(items: List<MediaItem>): ExportJob = OptimizeVideoJob(items)
}

// =========================================================================== Compress
class CompressVideoScreen(a: MainActivity) : ToolScreen(a, ToolId.COMPRESS_VIDEO) {
    private lateinit var estimate: android.widget.TextView

    override fun buildOptions(container: LinearLayout) {
        container.addView(SliderField(ctx, "Quality", 20, 100, ToolPrefs.compressQuality, subtitle = "Higher quality = larger file") { v ->
            ToolPrefs.compressQuality = v; updateEstimate()
        })
        estimate = UI.text(ctx, "", TextStyle.CAPTION)
        container.addView(estimate, lp().apply { topMargin = ctx.dp(4) })
        section(container, "Maximum resolution", "Shorter side; never upscales")
        val res = listOf("Original" to 0, "1080p" to 1080, "720p" to 720, "480p" to 480)
        container.addView(ChoiceGroup(ctx, res, { it.first }, res.firstOrNull { it.second == ToolPrefs.compressShortSide }) {
            ToolPrefs.compressShortSide = it.second; updateEstimate()
        }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, "Output is H.264 video with AAC audio in MP4, which plays on virtually every device. Audio is kept (converted to AAC if needed). Orientation is preserved. If a result would be larger than the original, it is not saved.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })
        updateEstimate()
    }

    private fun updateEstimate() {
        if (!::estimate.isInitialized) return
        val side = ToolPrefs.compressShortSide.takeIf { it > 0 } ?: 1080
        val w = side * 16 / 9; val br = VideoTranscoder.bitrateFor(ToolPrefs.compressQuality, w, side, 30.0, 0)
        val mbMin = (br + 160_000) * 60.0 / 8 / 1_000_000
        estimate.text = String.format(Locale.US, "≈ %.1f Mbps for %dp at 30 fps · about %.0f MB per minute (lower if the source bitrate is lower)", br / 1e6, side, mbMin)
    }

    override fun outputNaming() = "name_compressed.mp4"
    override fun createJob(items: List<MediaItem>): ExportJob = CompressVideoJob(items, ToolPrefs.compressQuality, ToolPrefs.compressShortSide)
}

// =========================================================================== Remove audio
class RemoveAudioScreen(a: MainActivity) : ToolScreen(a, ToolId.REMOVE_AUDIO) {
    override fun buildOptions(container: LinearLayout) {
        container.addView(UI.note(ctx, "The video stream is copied unchanged (no quality loss); only the sound track is left out. Useful for silent clips and to save a little space. Videos that already have no audio are skipped.", UI.NoteKind.INFO))
    }
    override fun outputNaming() = "name_noaudio.mp4"
    override fun createJob(items: List<MediaItem>): ExportJob = RemoveAudioJob(items)
}

// =========================================================================== Video → GIF
class VideoToGifScreen(a: MainActivity) : ToolScreen(a, ToolId.VIDEO_TO_GIF) {
    private lateinit var fps: SliderField
    private lateinit var width: SliderField
    private lateinit var widthChoices: ChoiceGroup<Int>
    private val widthPresets = listOf(240, 320, 480, 640, 800)

    override fun buildOptions(container: LinearLayout) {
        container.addView(ToggleRow(ctx, "Keep source frame rate and size", "Uses the video's own frame rate (up to 50 fps) and displayed resolution", ToolPrefs.gifSourceMode) { on ->
            ToolPrefs.gifSourceMode = on
            setManualEnabled(!on)
        })
        fps = SliderField(ctx, "Frame rate", 2, 50, ToolPrefs.gifFps, " fps", "Fewer frames = much smaller GIF") { ToolPrefs.gifFps = it }
        container.addView(fps, lp().apply { topMargin = ctx.dp(10) })
        width = SliderField(ctx, "Width", 120, 1280, ToolPrefs.gifWidth, " px", "Height follows the video's shape") { ToolPrefs.gifWidth = it }
        container.addView(width, lp().apply { topMargin = ctx.dp(14) })
        widthChoices = ChoiceGroup(ctx, widthPresets, { "$it px" }, widthPresets.firstOrNull { it == ToolPrefs.gifWidth }) { width.setValue(it, fromField = false) }
        container.addView(widthChoices, lp().apply { topMargin = ctx.dp(6) })
        section(container, "Part of the video", "Leave length at 0 to convert to the end")
        val row = UI.horizontal(ctx)
        row.addView(TextField(ctx, "Start (s)", "0", fmt(ToolPrefs.gifStart), numeric = true) { s -> ToolPrefs.gifStart = s.replace(',', '.').toDoubleOrNull() ?: 0.0; refreshValidation() }, lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ctx.dp(6) })
        row.addView(TextField(ctx, "Length (s)", "0 = to the end", fmt(ToolPrefs.gifLength), numeric = true) { s -> ToolPrefs.gifLength = s.replace(',', '.').toDoubleOrNull() ?: 0.0; refreshValidation() }, lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(6) })
        container.addView(row, lp().apply { topMargin = ctx.dp(10) })
        section(container, "Colour dithering", "How the 256-colour palette blends gradients")
        container.addView(ChoiceGroup(ctx, Dithering.entries, { when (it) { Dithering.ORDERED -> "Pattern (stable)"; Dithering.NONE -> "None (sharpest)"; Dithering.DIFFUSION -> "Diffusion (smoothest)" } }, ToolPrefs.gifDither) { ToolPrefs.gifDither = it }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, "GIF can only show 256 colours per frame, so file size grows quickly with frame rate and resolution. 10–15 fps at 320–480 px is a good start. Colours adapt to each scene; timing follows the video exactly.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })
        setManualEnabled(!ToolPrefs.gifSourceMode)
    }

    private fun setManualEnabled(on: Boolean) {
        for (v in listOf(fps, width, widthChoices)) { v.alpha = if (on) 1f else 0.4f; setEnabledDeep(v, on) }
    }

    private fun setEnabledDeep(v: android.view.View, on: Boolean) {
        v.isEnabled = on
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (ToolPrefs.gifStart < 0 || ToolPrefs.gifLength < 0) return "Start and length can't be negative"
        return null
    }

    override fun outputNaming() = "name.gif"
    override fun createJob(items: List<MediaItem>): ExportJob = VideoToGifJob(items,
        if (ToolPrefs.gifSourceMode) null else ToolPrefs.gifFps.toDouble(),
        if (ToolPrefs.gifSourceMode) null else ToolPrefs.gifWidth,
        ToolPrefs.gifStart, ToolPrefs.gifLength, ToolPrefs.gifDither)
}

// =========================================================================== Extract audio
class ExtractAudioScreen(a: MainActivity) : ToolScreen(a, ToolId.EXTRACT_AUDIO) {
    override fun buildOptions(container: LinearLayout) {
        container.addView(UI.note(ctx, "The AAC sound track is copied into an .m4a file exactly as it is — no re-encoding, no quality loss. Videos whose audio isn't AAC (for example Opus or AC-3) or that have no sound are listed clearly in the results.", UI.NoteKind.INFO))
    }
    override fun outputNaming() = "name_audio.m4a"
    override fun createJob(items: List<MediaItem>): ExportJob = ExtractAudioJob(items)
}

@Suppress("unused")
private fun bytes(b: Long) = Format.bytes(b)
