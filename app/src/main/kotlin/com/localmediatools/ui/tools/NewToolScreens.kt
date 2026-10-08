package com.localmediatools.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.export.ExportJob
import com.localmediatools.tools.ExtractPagesJob
import com.localmediatools.tools.PageMode
import com.localmediatools.tools.PageRanges
import com.localmediatools.tools.RemoveMetadataJob
import com.localmediatools.tools.ToolId
import com.localmediatools.tools.TrimVideoJob
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextField
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.WRAP
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import com.localmediatools.video.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// =========================================================================== Remove metadata
class RemoveMetadataScreen(a: MainActivity) : ToolScreen(a, ToolId.REMOVE_METADATA) {
    override fun buildOptions(container: LinearLayout) {
        container.addView(UI.text(ctx, "Removed", TextStyle.SUBTITLE), lp().apply { topMargin = ctx.dp(4) })
        container.addView(UI.text(ctx, "GPS location · camera make, model and serial number · dates · software · author · comments · XMP/IPTC · hidden thumbnails and extra images (depth, motion, HDR data)", TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(4) })
        container.addView(UI.text(ctx, "Kept", TextStyle.SUBTITLE), lp().apply { topMargin = ctx.dp(14) })
        container.addView(UI.text(ctx, "The image data byte for byte, orientation (so photos stay upright), colour profile and GIF animation. Videos: the video and audio streams, bit-for-bit, and their rotation.", TextStyle.BODY_2), lp().apply { topMargin = ctx.dp(4) })
        container.addView(UI.note(ctx, "Works with JPEG, PNG, WebP, GIF and MP4/MOV/WebM videos. HEIC, AVIF and RAW can't be cleaned without re-encoding; convert them with Convert images instead (converted copies carry no metadata).", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })
    }

    override fun outputNaming() = "name_clean.jpg / .png / .webp / .gif / .mp4 — photos in Pictures, videos in Movies"
    override fun createJob(items: List<MediaItem>): ExportJob = RemoveMetadataJob(items)
}

// =========================================================================== Extract PDF pages
object PagesPrefs {
    var spec = "1"
    var mode = PageMode.ONE_FILE
}

class ExtractPagesScreen(a: MainActivity) : ToolScreen(a, ToolId.EXTRACT_PDF_PAGES) {
    override fun buildOptions(container: LinearLayout) {
        container.addView(TextField(ctx, "Pages", "e.g. 1-3, 5, 8-", PagesPrefs.spec) { PagesPrefs.spec = it; refreshValidation() }, lp().apply { topMargin = ctx.dp(4) })
        container.addView(UI.text(ctx, "Use commas between pages; ranges like 4-9; \"8-\" means page 8 to the end. Pages are saved in the order you write them.", TextStyle.CAPTION), lp().apply { topMargin = ctx.dp(6) })
        section(container, "Save as")
        container.addView(ChoiceGroup(ctx, PageMode.entries, { it.label }, PagesPrefs.mode) { PagesPrefs.mode = it }, lp().apply { topMargin = ctx.dp(10) })
    }

    override fun validate(): String? {
        super.validate()?.let { return it }
        return PageRanges.validateSyntax(PagesPrefs.spec)
    }

    override fun outputNaming() = if (PagesPrefs.mode == PageMode.ONE_FILE) "name_pages.pdf" else "name_p3.pdf, name_p4.pdf …"
    override fun createJob(items: List<MediaItem>): ExportJob = ExtractPagesJob(items, PagesPrefs.spec, PagesPrefs.mode)
}

// =========================================================================== Trim & rotate video
class TrimScreen(a: MainActivity) : ToolScreen(a, ToolId.TRIM_VIDEO) {
    private var durationUs = 0L
    private var loadedKey: String? = null
    private var startF = 0.0
    private var endF = 1.0
    private var turns = 0
    private lateinit var info: TextView
    private lateinit var range: RangeSlider
    private lateinit var startImg: ImageView
    private lateinit var endImg: ImageView
    private lateinit var startLbl: TextView
    private lateinit var endLbl: TextView
    private var frameJob: Job? = null

    override fun buildOptions(container: LinearLayout) {
        info = UI.text(ctx, "Select a video to choose the part to keep.", TextStyle.BODY_2)
        container.addView(info, lp().apply { topMargin = ctx.dp(4) })
        val frames = UI.horizontal(ctx, Gravity.TOP)
        fun frameCol(): Triple<LinearLayout, ImageView, TextView> {
            val col = UI.vertical(ctx)
            val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Shapes.rounded(ctx, Palette.SURFACE_2, 12f); clipToOutline = true }
            col.addView(iv, LinearLayout.LayoutParams(-1, ctx.dp(96)))
            val t = UI.text(ctx, "", TextStyle.CAPTION).apply { setPadding(0, ctx.dp(6), 0, 0) }
            col.addView(t)
            return Triple(col, iv, t)
        }
        val (c1, i1, l1) = frameCol(); val (c2, i2, l2) = frameCol()
        startImg = i1; endImg = i2; startLbl = l1; endLbl = l2
        frames.addView(c1, lp(0, WRAP, 1f).apply { rightMargin = ctx.dp(6) })
        frames.addView(c2, lp(0, WRAP, 1f).apply { leftMargin = ctx.dp(6) })
        container.addView(frames, lp().apply { topMargin = ctx.dp(12) })
        range = RangeSlider(ctx) { s, e, done ->
            startF = s; endF = e
            updateLabels()
            if (done) loadFrames()
        }
        container.addView(range, lp().apply { topMargin = ctx.dp(8) })
        section(container, "Rotate", "Fix videos recorded sideways")
        val rot = listOf(0 to "Keep", 1 to "90° right", 2 to "180°", 3 to "90° left")
        container.addView(ChoiceGroup(ctx, rot, { it.second }, rot.first { it.first == turns }) { turns = it.first; refreshValidation() }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.note(ctx, "Lossless: streams are copied, not re-encoded. The start moves to the nearest earlier keyframe (usually less than a second or two); the end is exact.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
        onSelectionChanged()
    }

    override fun onSelectionChanged() {
        if (!::info.isInitialized) return
        val item = selection.usable.firstOrNull()
        if (item == null) { durationUs = 0; loadedKey = null; info.text = "Select a video to choose the part to keep."; startImg.setImageDrawable(null); endImg.setImageDrawable(null); updateLabels(); return }
        if (item.key == loadedKey) return
        loadedKey = item.key
        info.text = "Reading ${item.name}…"
        scope.launch {
            val d = withContext(Dispatchers.IO) { try { VideoProbe.probe(ctx, item.uri).durationUs } catch (_: Exception) { -1L } }
            if (loadedKey != item.key) return@launch
            durationUs = d.coerceAtLeast(0)
            startF = 0.0; endF = 1.0; range.set(0.0, 1.0)
            info.text = if (d > 0) "${item.name} · ${Format.duration(d / 1000)}" else "${item.name}: the duration couldn't be read."
            updateLabels(); loadFrames(); refreshValidation()
        }
    }

    private fun updateLabels() {
        if (!::startLbl.isInitialized) return
        val s = (startF * durationUs).toLong(); val e = (endF * durationUs).toLong()
        startLbl.text = "Start ${Format.duration(s / 1000)}"
        endLbl.text = "End ${Format.duration(e / 1000)} · keeps ${Format.duration((e - s).coerceAtLeast(0) / 1000)}"
        refreshValidation()
    }

    private fun loadFrames() {
        val item = selection.usable.firstOrNull() ?: return
        if (durationUs <= 0) return
        frameJob?.cancel()
        frameJob = scope.launch {
            delay(120)
            val s = (startF * durationUs).toLong(); val e = (endF * durationUs).toLong().coerceAtMost(durationUs - 1)
            val (a, b) = withContext(Dispatchers.IO) {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(ctx, item.uri)
                    // The start snaps to a keyframe, so show the keyframe the trim will really start at.
                    mmr.getScaledFrameAtTime(s, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, 360, 360) to
                        mmr.getScaledFrameAtTime(e, MediaMetadataRetriever.OPTION_CLOSEST, 360, 360)
                } catch (_: Exception) { null to null } finally { try { mmr.release() } catch (_: Exception) { } }
            }
            startImg.setImageBitmap(a); endImg.setImageBitmap(b)
        }
    }

    override fun validate(): String? {
        super.validate()?.let { return it }
        if (stepMode && selection.usable.isEmpty()) return "Put a video in the stack to choose the part to keep"
        if (!stepMode && selection.usable.size > 1) return "Select one video (trimming is set per video)"
        if (durationUs <= 0) return "Reading the video…"
        val len = (endF - startF) * durationUs
        if (len < 300_000) return "Keep at least a fraction of a second"
        if (startF <= 0.0005 && endF >= 0.9995 && turns == 0) return "Move the handles or pick a rotation"
        return null
    }

    override fun outputNaming() = "name_trim.mp4 (WebM stays WebM)"

    /** In a stack the same part (as a share of the length) is kept from every video that arrives. */
    override fun createStepJob(): ExportJob = TrimVideoJob(selection.usable.take(1), 0, 0, turns, startF to endF)

    override fun stackSummary(): String {
        val s = (startF * durationUs).toLong(); val e = (endF * durationUs).toLong()
        val range = if (durationUs > 0) "Keep ${Format.duration(s / 1000)}–${Format.duration(e / 1000)} of ${Format.duration(durationUs / 1000)} (${(startF * 100).toInt()}–${(endF * 100).toInt()}% of each video)"
            else "Keep ${(startF * 100).toInt()}–${(endF * 100).toInt()}% of each video"
        return range + if (turns % 4 != 0) " · turn ${(turns % 4) * 90}° clockwise" else ""
    }
    override fun createJob(items: List<MediaItem>): ExportJob =
        TrimVideoJob(items.first(), (startF * durationUs).toLong(), if (endF >= 0.9995) Long.MAX_VALUE else (endF * durationUs).toLong(), turns)
}

/** Two-handle slider for a time range (fractions 0..1). */
class RangeSlider(ctx: Context, private val onChange: (Double, Double, Boolean) -> Unit) : View(ctx) {
    private var a = 0.0
    private var b = 1.0
    private var active = 0
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SURFACE_3 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val r = RectF()

    init { contentDescription = "Part of the video to keep"; minimumHeight = ctx.dp(48) }

    fun set(s: Double, e: Double) { a = s; b = e; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(48))
    private val pad get() = context.dp(14).toFloat()
    private fun x(f: Double) = (pad + f * (width - 2 * pad)).toFloat()

    override fun onDraw(c: Canvas) {
        val cy = height / 2f; val th = context.dp(6).toFloat()
        r.set(pad, cy - th / 2, width - pad, cy + th / 2); c.drawRoundRect(r, th, th, track)
        r.set(x(a), cy - th / 2, x(b), cy + th / 2); c.drawRoundRect(r, th, th, fill)
        for (f in listOf(a, b)) {
            r.set(x(f) - context.dp(7), cy - context.dp(14), x(f) + context.dp(7), cy + context.dp(14))
            c.drawRoundRect(r, context.dp(5).toFloat(), context.dp(5).toFloat(), knob)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = ((e.x - pad) / (width - 2 * pad)).toDouble().coerceIn(0.0, 1.0)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); active = if (abs(f - a) <= abs(f - b)) 1 else 2; move(f, false) }
            MotionEvent.ACTION_MOVE -> move(f, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { move(f, true); active = 0 }
        }
        return true
    }

    private fun move(f: Double, done: Boolean) {
        val gap = 0.002
        if (active == 1) a = f.coerceAtMost(b - gap) else if (active == 2) b = f.coerceAtLeast(a + gap)
        invalidate()
        onChange(a, b, done)
    }
}
