package com.localmediatools.tools

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.localmediatools.codec.mp4.Mp4FastStart
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.OutputStore
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.highlight.SongChoice
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import com.localmediatools.music.core.Mood
import com.localmediatools.video.CaptionPainter
import com.localmediatools.video.CompositionRenderer
import com.localmediatools.video.EditAudio
import com.localmediatools.video.EditTextPainter
import com.localmediatools.video.ReframeAnalyzer
import com.localmediatools.video.VideoTranscoder
import com.localmediatools.video.core.EditProject
import com.localmediatools.video.core.EditTimeline
import com.localmediatools.video.core.Framing
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** How the edited video is saved, and its music. */
data class VideoEditSettings(
    /** The output's short side (720, 1080). */
    val shortSide: Int = 1080,
    val quality: Int = 85,
    val mood: Mood? = null,
    val song: SongChoice? = null,
    val seed: Long = 1L,
) {
    val musicLabel: String get() = song?.name ?: mood?.musicLabel ?: tr("no music")
}

/**
 * Saves a video project as one MP4: finds the camera path of clips set to auto reframe that don't have
 * one for this shape yet, makes the sound ([EditAudio]), draws the video on the GPU
 * ([CompositionRenderer] over [EditTimeline]) and saves it. The originals are never changed.
 */
class VideoEditJob(inputs: List<MediaItem>, private val project: EditProject, private val settings: VideoEditSettings) :
    ExportJob(ToolId.VIDEO_EDITOR, inputs) {
    override val title = trn(project.clips.size, "Saving an edited video of {0} clip", "Saving an edited video of {0} clips")
    override val unitCount: Int get() = 1

    override suspend fun run(ctx: JobContext) {
        val label = tool.title
        try {
            val r = ctx.runItem(inputs.first().copy(name = label, readError = null)) { make(ctx, label) }
            if (r != null) ctx.addResult(r)
            ctx.unitDone(0)
        } finally {
            Outputs.cleanTemp(ctx)
        }
    }

    private fun make(ctx: JobContext, label: String): ItemResult {
        if (project.clips.isEmpty()) throw UserFacingException(tr("Add at least one video or photo."))
        // 1. Auto reframe where it isn't planned for this shape yet.
        var p = project
        val aspect = p.outAspect
        val pending = p.clips.withIndex().filter { (_, c) -> c.framing == Framing.AUTO && !c.isPhoto && (c.reframe == null || abs(c.reframeFor - aspect) > 1e-3f) }
        for ((k, ic) in pending.withIndex()) {
            val (i, c) = ic
            ctx.checkCancelled()
            ctx.status(tr("Finding the subject ({0} of {1})", k + 1, pending.size), c.source.name)
            val keys = ReframeAnalyzer.analyze(ctx.app, Uri.parse(c.source.uri), c.inMs, c.outMs, aspect, c.zoom, c.rotation, { ctx.cancelled }) { f ->
                ctx.unitProgress(0, 0.2 * (k + f) / pending.size)
            }
            p = p.copy(clips = p.clips.toMutableList().apply { set(i, c.copy(reframe = keys, reframeFor = aspect)) })
            ctx.throttle()
        }
        val tl = EditTimeline(p)
        if (tl.durationMs <= 0) throw UserFacingException(tr("The video is empty."))
        // 2. The sound.
        ctx.status(when { settings.song != null -> tr("Fitting your song to the video"); settings.mood != null -> tr("Writing the music"); else -> tr("Preparing the sound") })
        val audio = EditAudio.build(ctx.app, tl, settings.mood, settings.seed, settings.song, { i -> Uri.parse(p.clips[i].source.uri) },
            { f -> ctx.unitProgress(0, 0.2 + 0.1 * f) }) { ctx.cancelled }
        ctx.checkCancelled()
        // 3. The picture.
        val (w, h) = outputSize(aspect, settings.shortSide)
        val bitrate = VideoTranscoder.bitrateFor(settings.quality, w, h, 30.0, 0)
        OutputStore.ensureSpace((bitrate.toLong() + 192_000) * tl.durationMs / 8_000 * 2)
        val tmp = File(Outputs.tempDir(ctx), "edited.mp4")
        ctx.status(tr("Making the video"))
        try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { pfd ->
                CompositionRenderer(ctx.app, tl, { i -> Uri.parse(p.clips[i].source.uri) to p.clips[i].source.name }, audio,
                    CaptionPainter { i, ow, oh -> EditTextPainter.paint(p.texts[i], ow, oh) })
                    .run(pfd.fileDescriptor, w, h, bitrate, { ctx.throttle() }, { ctx.cancelled }) { f -> ctx.unitProgress(0, 0.3 + 0.67 * f) }
            }
            ctx.status(tr("Saving"))
            val day = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val out = Outputs.produce(ctx, tool.area, "edited_$day.mp4", "video/mp4") { pendingOut ->
                RandomAccessFile(tmp, "r").use { raf -> pendingOut.openStream().buffered(1 shl 20).use { os -> Mp4FastStart.process(raf, os) } }
                Outputs.verifyMedia(ctx.app, pendingOut.uri, true, audio != null)
            }.first
            return ItemResult(label, ItemOutcome.SUCCESS, null, listOf(out),
                "${Format.duration(tl.durationMs)} · " + trn(p.clips.size, "{0} clip", "{0} clips") + " · ${settings.musicLabel} · ${w}×$h · ${Format.bytes(out.size)}")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        /** Even dimensions for [aspect] with the short side [short], the long side at most 16∕9 of it (1920 for 1080). */
        fun outputSize(aspect: Float, short: Int): Pair<Int, Int> {
            fun even(x: Float) = max(2, (x / 2).roundToInt() * 2)
            val longMax = short * 16f / 9f
            val (w, h) = if (aspect >= 1f) {
                val ww = minOf(short * aspect, longMax); ww to ww / aspect
            } else {
                val hh = minOf(short / aspect, longMax); hh * aspect to hh
            }
            return VideoTranscoder.outputSize(even(w), even(h), 0, 30.0)
        }
    }
}
