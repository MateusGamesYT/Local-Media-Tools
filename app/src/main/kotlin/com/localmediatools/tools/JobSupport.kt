package com.localmediatools.tools

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import com.localmediatools.core.Format
import com.localmediatools.core.OutputArea
import com.localmediatools.core.OutputFile
import com.localmediatools.core.OutputStore
import com.localmediatools.core.PendingOutput
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.JobContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Helpers shared by all tools for creating, verifying and publishing outputs. */
object Outputs {
    /**
     * Creates a hidden output, lets [block] write and verify it, then publishes it. Any failure
     * deletes the hidden entry, so no partial or invalid file ever appears in the gallery.
     */
    inline fun <T> produce(ctx: JobContext, area: OutputArea, name: String, mime: String, block: (PendingOutput) -> T): Pair<OutputFile, T> {
        ctx.checkCancelled()
        val pending = ctx.outputFactory?.invoke(area, name, mime) ?: OutputStore.create(ctx.app, area, name, mime)
        try {
            val r = block(pending)
            ctx.checkCancelled()
            return pending.commit() to r
        } catch (t: Throwable) {
            pending.abort()
            throw t
        }
    }

    fun copy(input: InputStream, out: OutputStream, total: Long, ctx: JobContext, progress: (Double) -> Unit = {}) {
        val buf = ByteArray(1 shl 18)
        var done = 0L
        var n: Int
        var lastReport = 0L
        while (input.read(buf).also { n = it } > 0) {
            out.write(buf, 0, n)
            done += n
            if (done - lastReport > (8 shl 20)) {
                lastReport = done
                ctx.throttle()
                if (total > 0) progress(done.toDouble() / total)
            }
        }
    }

    fun tempDir(ctx: JobContext): File = File(ctx.app.cacheDir, "export-${ctx.job.id}").apply { mkdirs() }

    fun cleanTemp(ctx: JobContext) {
        try { File(ctx.app.cacheDir, "export-${ctx.job.id}").deleteRecursively() } catch (_: Exception) { }
    }

    /** Opens a written video/audio file and checks it has the expected tracks and a duration. */
    fun verifyMedia(ctx: Context, uri: Uri, expectVideo: Boolean, expectAudio: Boolean) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var v = false; var a = false
            var dur = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) v = true
                if (mime.startsWith("audio/")) a = true
                if (f.containsKey(android.media.MediaFormat.KEY_DURATION)) dur = maxOf(dur, f.getLong(android.media.MediaFormat.KEY_DURATION))
            }
            if ((expectVideo && !v) || (expectAudio && !a)) throw UserFacingException("The written file failed verification (missing track), so it was discarded.")
            if (dur <= 0) {
                ex.selectTrack(0)
                if (ex.sampleTime < 0) throw UserFacingException("The written file failed verification (no samples), so it was discarded.")
            }
        } catch (e: UserFacingException) {
            throw e
        } catch (e: Exception) {
            throw UserFacingException("The written file could not be read back, so it was discarded.", e)
        } finally {
            ex.release()
        }
    }

    fun sizeChange(before: Long, after: Long): String {
        if (before <= 0 || after < 0) return Format.bytes(after)
        val pct = ((after - before) * 100.0 / before)
        val sign = if (pct > 0) "+" else ""
        return "${Format.bytes(before)} → ${Format.bytes(after)} ($sign${String.format(java.util.Locale.US, "%.0f", pct)}%)"
    }
}
