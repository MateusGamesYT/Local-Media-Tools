package com.localmediatools.ui.tools

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.localmediatools.app.MainActivity
import com.localmediatools.core.Errors
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.denoise.core.PhotoDenoiser
import com.localmediatools.export.ExportJob
import com.localmediatools.i18n.tr
import com.localmediatools.image.ImageSource
import com.localmediatools.stitch.OpenCvLoader
import com.localmediatools.tools.DenoiseJob
import com.localmediatools.tools.DenoiseStrength
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.MATCH
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.WRAP
import com.localmediatools.ui.dp
import com.localmediatools.ui.lp
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DenoisePrefs { var strength = DenoiseStrength.NORMAL }

/**
 * Denoiser: photos, RAW files and videos in one batch. The preview shows a piece of the first photo
 * at full size (noise is invisible in a scaled-down picture), before and after, with the noise
 * measured on it.
 */
class DenoiseScreen(a: MainActivity) : ToolScreen(a, ToolId.DENOISE) {
    private lateinit var before: ImageView
    private lateinit var after: ImageView
    private lateinit var note: TextView
    private var key: String? = null
    private var job: Job? = null

    override fun buildOptions(container: LinearLayout) {
        container.addView(UI.text(ctx, tr("Preview at 100 %"), TextStyle.SUBTITLE), lp().apply { topMargin = ctx.dp(4) })
        val row = UI.horizontal(ctx, Gravity.TOP)
        fun col(label: String): Pair<LinearLayout, ImageView> {
            val c = UI.vertical(ctx)
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Shapes.rounded(ctx, Palette.SURFACE_2, 14f)
                clipToOutline = true
            }
            c.addView(iv, LinearLayout.LayoutParams(MATCH, ctx.dp(170)))
            c.addView(UI.text(ctx, label, TextStyle.CAPTION).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, 0) }, lp())
            return c to iv
        }
        val (c1, i1) = col(tr("Before")); val (c2, i2) = col(tr("After"))
        before = i1; after = i2
        row.addView(c1, lp(0, WRAP, 1f).apply { rightMargin = ctx.dp(6) })
        row.addView(c2, lp(0, WRAP, 1f).apply { leftMargin = ctx.dp(6) })
        container.addView(row, lp().apply { topMargin = ctx.dp(10) })
        note = UI.text(ctx, tr("Select photos or videos to see a piece of the first photo before and after."), TextStyle.CAPTION)
        container.addView(note, lp().apply { topMargin = ctx.dp(8) })
        section(container, tr("Strength"))
        container.addView(ChoiceGroup(ctx, DenoiseStrength.entries, { it.label }, DenoisePrefs.strength) { DenoisePrefs.strength = it; key = null; onSelectionChanged() }, lp().apply { topMargin = ctx.dp(10) })
        container.addView(UI.text(ctx, tr("Normal removes as much noise as each file has; Light keeps more texture, Strong smooths further."), TextStyle.CAPTION, Palette.TEXT_3), lp().apply { topMargin = ctx.dp(6) })
        container.addView(UI.note(ctx, tr("Each file keeps its format: JPEG stays JPEG, PNG stays PNG, WebP stays WebP, and RAW files (DNG, CR2, NEF, ARW) stay RAW — their sensor data is cleaned and everything else in the file is kept as it was. Videos are saved as MP4 (H.264) at the original's quality. HEIC, AVIF and other formats Android can't write are saved as JPEG."), UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(12) })
        container.addView(UI.note(ctx, tr("RAW files that can't be written back in their own format (CR3, RAF, ORF, RW2, Canon sRAW, lossy NEF) are cleaned as pictures and saved like the other files: a lossless PNG among PNGs, otherwise a JPEG at full quality. You're told before it starts."), UI.NoteKind.TIP), lp().apply { topMargin = ctx.dp(10) })
        onSelectionChanged()
    }

    override fun onSelectionChanged() {
        if (!::before.isInitialized) return
        val item = selection.usable.firstOrNull { it.kind == MediaKind.IMAGE && !it.format.isRaw }
        if (item == null) {
            key = null; before.setImageDrawable(null); after.setImageDrawable(null)
            note.text = if (selection.usable.any { it.format.isRaw || it.kind == MediaKind.VIDEO }) tr("No preview for RAW files and videos; they are cleaned when you start.")
                else tr("Select photos or videos to see a piece of the first photo before and after.")
            return
        }
        val k = item.key + DenoisePrefs.strength
        if (k == key) return
        key = k
        note.text = tr("Looking at {0}…", item.name)
        job?.cancel()
        job = scope.launch {
            val r = withContext(Dispatchers.Default) {
                try {
                    previewOverride?.let { return@withContext Result.success(it(item)) }
                    OpenCvLoader.ensure()
                    ImageSource.open(ctx, item).use { src -> Result.success(preview(src)) }
                } catch (e: Throwable) { Result.failure(e) }
            }
            if (key != k) return@launch
            r.onSuccess { (b, out, noise) ->
                before.setImageBitmap(b); after.setImageBitmap(out)
                val h = PhotoDenoiser.strengthFor(noise, DenoisePrefs.strength.value).luma
                note.text = if (h < 0.5f) tr("No noise to remove in this photo; it will be saved nearly unchanged.")
                    else tr("Noise measured: {0} in brightness, {1} in colour (of 255). Filter strength: {2}.", fmt(noise.luma), fmt(noise.color), fmt(h.toDouble())) +
                        (if (noise.spread > PhotoDenoiser.SPREAD * 1.05) " " + tr("The finest detail here looks like texture rather than noise, so the filter is gentler.") else "")
            }
            r.onFailure { e -> note.text = tr("Preview not available: {0}", Errors.describe(e)) }
        }
    }

    private fun fmt(v: Double) = String.format(com.localmediatools.i18n.I18n.locale, "%.1f", v)

    /** A 400 px piece from the middle of the photo, at full size, before and after (noise measured as the job does). */
    private fun preview(src: ImageSource): Triple<Bitmap, Bitmap, PhotoDenoiser.Noise> {
        val noise = DenoiseJob.measure(src)
        val s = min(400, min(src.width, src.height))
        val x = (src.width - s) / 2; val y = (src.height - s) / 2
        val m = PhotoDenoiser.MARGIN
        val a = maxOf(0, y - m); val b = min(src.height, y + s + m)
        val bmp = src.decodeRegion(Rect(x, a, x + s, b), 1)
        try {
            val band = IntArray(s * (b - a)); bmp.getPixels(band, 0, s, 0, 0, s, b - a)
            val out = IntArray(s * s)
            PhotoDenoiser.denoiseRows(band, s, b - a, y - a, y - a + s, PhotoDenoiser.strengthFor(noise, DenoisePrefs.strength.value), out)
            val before = Bitmap.createBitmap(band, (y - a) * s, s, s, s, Bitmap.Config.ARGB_8888)
            val after = Bitmap.createBitmap(out, s, s, Bitmap.Config.ARGB_8888)
            return Triple(before, after, noise)
        } finally { bmp.recycle() }
    }

    override fun outputNaming() = tr("name_denoised, in the file's own format")

    companion object {
        /** Stands in for the preview's measuring and filtering where OpenCV can't run (screenshots). */
        @Volatile var previewOverride: ((MediaItem) -> Triple<Bitmap, Bitmap, PhotoDenoiser.Noise>)? = null
    }
    override fun createJob(items: List<MediaItem>): ExportJob = DenoiseJob(items, DenoisePrefs.strength)
}
