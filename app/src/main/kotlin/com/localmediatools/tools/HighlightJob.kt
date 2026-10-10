package com.localmediatools.tools

import android.os.ParcelFileDescriptor
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.OutputStore
import com.localmediatools.core.UserFacingException
import com.localmediatools.codec.mp4.Mp4FastStart
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.highlight.HighlightAnalyzer
import com.localmediatools.highlight.HighlightAudio
import com.localmediatools.highlight.HighlightRenderer
import com.localmediatools.highlight.ShotInfo
import com.localmediatools.highlight.core.Aspect
import com.localmediatools.highlight.core.Plan
import com.localmediatools.highlight.core.PlanOptions
import com.localmediatools.highlight.core.Planner
import com.localmediatools.highlight.core.Shot
import com.localmediatools.music.core.Mood
import com.localmediatools.video.VideoTranscoder
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the user chose for a highlight video (shots by [MediaItem.key]). */
data class HighlightSettings(
    /** Null: no music. */
    val mood: Mood? = Mood.UPBEAT,
    /** Null: as long as the moments need. */
    val seconds: Double? = null,
    /** Null: the shape most pictures have. */
    val aspect: Aspect? = null,
    val captions: Boolean = true,
    val clipSound: Boolean = true,
    val include: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val title: String? = null,
    val names: Map<Int, String> = emptyMap(),
    /** Which tune: a new seed writes a different piece. */
    val seed: Long = 1,
) {
    /** The plan for [items], all looked at already ([infos] in the same order). */
    fun plan(items: List<MediaItem>, infos: List<ShotInfo>): Plan {
        val shots: List<Shot> = infos.mapIndexed { i, s -> s.shot(i) }
        val ids = items.withIndex().associate { (i, it) -> it.key to i }
        val beat = mood?.let { 60.0 / it.bpm } ?: 0.5
        return Planner.plan(shots, PlanOptions(seconds = seconds, beatSeconds = beat, captions = captions, clipSound = clipSound,
            aspect = aspect ?: Planner.aspectFor(shots), zone = TimeZone.getDefault(),
            include = include.mapNotNull { ids[it] }.toSet(), exclude = exclude.mapNotNull { ids[it] }.toSet(), title = title, names = names))
    }
}

/**
 * Makes the highlight video: looks at every picked photo and video (unless the timeline already
 * did), plans it, writes and mixes the sound, draws it on the GPU and saves one MP4.
 */
class HighlightJob(inputs: List<MediaItem>, private val settings: HighlightSettings) : ExportJob(ToolId.HIGHLIGHT_VIDEO, inputs) {
    override val title = "Making a highlight video from ${plural(inputs.size, "photo or video", "photos and videos")}"
    override val unitCount: Int get() = 1

    override suspend fun run(ctx: JobContext) {
        val label = "Highlight video"
        try {
            val r = ctx.runItem(inputs.first().copy(name = label, readError = null)) { make(ctx, label) }
            if (r != null) ctx.addResult(r)
            ctx.unitDone(0)
        } finally {
            Outputs.cleanTemp(ctx)
        }
    }

    private fun make(ctx: JobContext, label: String): ItemResult {
        val usable = inputs.filter { it.readError == null }
        if (usable.size < 2) throw UserFacingException("Pick at least two photos or videos.")
        val notes = ArrayList<String>()
        // 1. Look at everything (the timeline usually has already).
        val infos = ArrayList<ShotInfo>()
        val items = ArrayList<MediaItem>()
        for ((k, item) in usable.withIndex()) {
            ctx.checkCancelled()
            ctx.status("Looking at the photos and videos (${k + 1} of ${usable.size})", item.name)
            try {
                infos.add(HighlightAnalyzer.analyze(ctx.app, item) { ctx.cancelled })
                items.add(item)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || e is com.localmediatools.core.ExportCancelledException) throw e
                notes.add("\"${item.name}\" couldn't be read and was left out.")
            }
            ctx.unitProgress(0, 0.15 * (k + 1) / usable.size)
            ctx.throttle()
        }
        HighlightAnalyzer.releaseModels()
        if (items.size < 2) throw UserFacingException("Too few of the files could be read to make a video.")
        // 2. The plan.
        val plan = settings.plan(items, infos)
        // 3. The sound.
        ctx.status(if (settings.mood != null) "Writing the music" else "Preparing the sound")
        val audio = HighlightAudio.build(ctx.app, plan, settings.mood, settings.seed, { items[it].uri }, { f -> ctx.unitProgress(0, 0.15 + 0.1 * f) }) { ctx.cancelled }
        ctx.checkCancelled()
        // 4. The picture.
        val (w, h) = VideoTranscoder.outputSize(plan.aspect.width, plan.aspect.height, 0, 30.0)
        val bitrate = VideoTranscoder.bitrateFor(85, w, h, 30.0, 0)
        OutputStore.ensureSpace((bitrate.toLong() + 192_000) * plan.durationMs / 8_000 * 2)
        val tmp = File(Outputs.tempDir(ctx), "highlight.mp4")
        ctx.status("Making the video")
        try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { pfd ->
                HighlightRenderer(ctx.app, plan, { id -> items[id].uri to items[id].name }, audio)
                    .run(pfd.fileDescriptor, w, h, bitrate, { ctx.throttle() }, { ctx.cancelled }) { f -> ctx.unitProgress(0, 0.25 + 0.72 * f) }
            }
            ctx.status("Saving")
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(plan.moments.first().startMs))
            val out = Outputs.produce(ctx, tool.area, "highlight_$day.mp4", "video/mp4") { pending ->
                RandomAccessFile(tmp, "r").use { raf -> pending.openStream().buffered(1 shl 20).use { os -> Mp4FastStart.process(raf, os) } }
                Outputs.verifyMedia(ctx.app, pending.uri, true, audio != null)
            }.first
            if (inputs.size > usable.size) notes.add("${inputs.size - usable.size} of the files couldn't be read.")
            val music = settings.mood?.let { "${it.label} music" } ?: "no music"
            return ItemResult(label, ItemOutcome.SUCCESS, notes.joinToString(" ").ifBlank { null }, listOf(out),
                "${Format.duration(plan.durationMs)} · ${plural(plan.clips.size, "clip")} · ${plural(plan.moments.count { m -> plan.clips.any { it.moment == m.index } }, "moment")} · $music · ${w}×$h · ${Format.bytes(out.size)}")
        } finally {
            tmp.delete()
        }
    }
}
