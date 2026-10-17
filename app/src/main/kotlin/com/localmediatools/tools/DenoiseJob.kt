package com.localmediatools.tools

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import com.localmediatools.codec.mp4.Mp4FastStart
import com.localmediatools.codec.raw.ByteSource
import com.localmediatools.codec.raw.ChannelSource
import com.localmediatools.codec.raw.FileSource
import com.localmediatools.codec.raw.Mosaic
import com.localmediatools.codec.raw.RawFiles
import com.localmediatools.codec.raw.RawFormatException
import com.localmediatools.codec.raw.RawUnsupportedException
import com.localmediatools.core.Format
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.core.OutputStore
import com.localmediatools.core.SkipItemException
import com.localmediatools.core.SniffedFormat
import com.localmediatools.core.UserFacingException
import com.localmediatools.denoise.core.PhotoDenoiser
import com.localmediatools.denoise.core.RawDenoiser
import com.localmediatools.denoise.core.VideoDenoise
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trc
import com.localmediatools.i18n.trn
import com.localmediatools.image.CountingStream
import com.localmediatools.image.EncodeSpec
import com.localmediatools.image.ImageOutFormat
import com.localmediatools.image.ImagePipeline
import com.localmediatools.image.ImageSource
import com.localmediatools.stitch.OpenCvLoader
import com.localmediatools.ui.ToolRules
import com.localmediatools.video.FrameGrabber
import com.localmediatools.video.VideoProbe
import com.localmediatools.video.VideoTranscoder
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

enum class DenoiseStrength(val value: Float) {
    LIGHT(0.7f), NORMAL(1f), STRONG(1.4f);

    val label: String get() = when (this) {
        LIGHT -> trc("strength", "Light"); NORMAL -> trc("strength", "Normal"); STRONG -> trc("strength", "Strong")
    }
}

/**
 * Removes noise from photos, camera RAW files and videos, each in its own format: JPEG stays JPEG,
 * PNG PNG, WebP WebP; RAW files stay RAW (the sensor data is cleaned and written back into the same
 * file structure, everything else byte for byte); videos become H.264 MP4 at the original's
 * quality. Formats Android can't write (HEIC, AVIF, TIFF…) are saved as JPEG (PNG with transparency).
 * RAW files this app can't write back are cleaned as pictures and take the other files' kind at
 * full quality ([RawFallback]).
 */
class DenoiseJob(inputs: List<MediaItem>, private val strength: DenoiseStrength) : ExportJob(ToolId.DENOISE, inputs) {
    override val title = trn(inputs.size, "Denoising {0} file", "Denoising {0} files")

    override suspend fun run(ctx: JobContext) {
        val rawFormat = RawFallback.format(tool, inputs)
        ctx.forEachItem(inputs, parallel = false) { index, item ->
            when {
                item.kind == MediaKind.VIDEO -> video(ctx, index, item)
                item.format.isRaw -> raw(ctx, index, item, rawFormat)
                else -> photo(ctx, index, item)
            }
        }
        Outputs.cleanTemp(ctx)
    }

    // ------------------------------------------------------------------ photos
    private fun noiseLabel(sigma: Double) = when {
        sigma < 1.5 -> tr("little noise")
        sigma < 4.0 -> tr("some noise")
        else -> tr("strong noise")
    }

    /** [raw]: for a RAW file that can't be written back, the format it takes and the note saying so ([RawFallback]). */
    private fun photo(ctx: JobContext, index: Int, item: MediaItem, raw: Pair<ImageOutFormat, String>? = null): ItemResult {
        ImageSource.open(ctx.app, item).use { src ->
            OpenCvLoader.ensure()
            val w = src.width; val h = src.height
            // Read in bands of rows (or cut from one full decode, for formats that can't be read in pieces).
            if (!src.canServeRegions(ctx.memoryBudget())) throw UserFacingException(tr("This {0} image is too large to process in pieces with the memory available.", src.format.label))
            ctx.status(tr("Measuring the noise"), item.name)
            val noise = measure(src)
            val s = PhotoDenoiser.strengthFor(noise, strength.value)
            val alpha = src.preview(256).let { b -> try { com.localmediatools.image.BitmapOps.hasTransparency(b) } finally { b.recycle() } }
            val (format, note) = raw ?: outputFormat(ctx, item, src, alpha)
            ctx.unitProgress(index, 0.05)
            ctx.status(tr("Removing noise"), item.name)
            val (out, _) = Outputs.produce(ctx, ToolId.DENOISE.area, "${item.baseName}_denoised.${format.ext}", format.mime) { pending ->
                CountingStream(pending.openStream().buffered(1 shl 16)).use { os ->
                    render(ctx, src, w, h, s, format, alpha, if (raw != null) RawFallback.JPEG_QUALITY else 95, os) { f -> ctx.unitProgress(index, 0.05 + 0.95 * f) }
                }
                ImagePipeline.verify(ctx.app, pending, w, h)
            }
            return ItemResult(item.name, ItemOutcome.SUCCESS, note, listOf(out), "${noiseLabel(PhotoDenoiser.strengthFor(noise).luma / PhotoDenoiser.K)} · ${w}×$h · ${format.label}")
        }
    }

    companion object {
        /** Noise of a photo, measured at full resolution on up to nine 512 px tiles spread over it (pooled). */
        fun measure(src: ImageSource): PhotoDenoiser.Noise {
            val w = src.width; val h = src.height
            val t = 512
            val tw = min(t, w); val th = min(t, h)
            val xs = if (w <= t) listOf(0) else listOf(0, (w - t) / 2, w - t)
            val ys = if (h <= t) listOf(0) else listOf(0, (h - t) / 2, h - t)
            val meter = PhotoDenoiser.Meter()
            val px = IntArray(tw * th)
            for (y in ys) for (x in xs) {
                val bmp = src.decodeRegion(Rect(x, y, x + tw, y + th), 1)
                try { bmp.getPixels(px, 0, tw, 0, 0, tw, th) } finally { bmp.recycle() }
                meter.add(px, tw, th)
            }
            return meter.result()
        }
    }

    /** The photo's own format when Android can write it; JPEG (PNG with transparency) otherwise. */
    private fun outputFormat(ctx: JobContext, item: MediaItem, src: ImageSource, alpha: Boolean): Pair<ImageOutFormat, String?> {
        return when (item.format) {
            SniffedFormat.JPEG -> ImageOutFormat.JPEG to null
            SniffedFormat.PNG -> ImageOutFormat.PNG to null
            SniffedFormat.WEBP -> {
                val lossless = try { ImageSource.isLosslessWebp(ctx.app, item.uri) } catch (_: Exception) { false }
                val f = if (lossless) ImageOutFormat.WEBP_LOSSLESS else ImageOutFormat.WEBP
                if (src.width.toLong() * src.height * 4 * 2 > ctx.memoryBudget()) ImageOutFormat.PNG to tr("Saved as PNG: this WebP is too large to write in one piece with the memory available.")
                else f to null
            }
            else -> {
                val f = if (alpha) ImageOutFormat.PNG else ImageOutFormat.JPEG
                f to tr("Saved as {0}: Android can't write {1} files.", f.label, item.format.label)
            }
        }
    }

    private fun render(ctx: JobContext, src: ImageSource, w: Int, h: Int, s: PhotoDenoiser.Strength, format: ImageOutFormat, alpha: Boolean, quality: Int, os: java.io.OutputStream, progress: (Double) -> Unit) {
        val m = PhotoDenoiser.MARGIN
        val rows = max(16, min(256, (ctx.memoryBudget() / 4 / max(1, w * 16)).toInt()))
        val hasAlpha = format.supportsAlpha && alpha
        val enc = if (format.streamable) ImagePipeline.stripEncoder(EncodeSpec(format, quality), w, h, hasAlpha, os) else null
        val target = if (enc == null) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
        try {
            val out = IntArray(w * rows)
            var y0 = 0
            while (y0 < h) {
                ctx.throttle()
                val y1 = min(h, y0 + rows)
                val a = max(0, y0 - m); val b = min(h, y1 + m)
                val band = IntArray(w * (b - a))
                src.decodeRegion(Rect(0, a, w, b), 1).let { bmp -> try { bmp.getPixels(band, 0, w, 0, 0, w, b - a) } finally { bmp.recycle() } }
                PhotoDenoiser.denoiseRows(band, w, b - a, y0 - a, y1 - a, s, out)
                if (enc != null) enc.write(out, 0, w, y1 - y0) else target!!.setPixels(out, 0, w, 0, y0, w, y1 - y0)
                progress(y1.toDouble() / h)
                y0 = y1
            }
            if (enc != null) enc.finish() else ImagePipeline.encodeBitmap(target!!, EncodeSpec(format, quality), os)
        } finally { target?.recycle() }
    }

    // ------------------------------------------------------------------ RAW
    /** A RAW file cleaned in its own format; one this app can't write back is cleaned as a picture and saved as [rawFormat]. */
    private fun raw(ctx: JobContext, index: Int, item: MediaItem, rawFormat: ImageOutFormat): ItemResult {
        val ext = item.name.substringAfterLast('.', "").ifBlank { item.format.label.lowercase() }
        val label = ToolRules.rawLabel(item)
        val pfd = ctx.app.contentResolver.openFileDescriptor(item.uri, "r") ?: throw UserFacingException(tr("The file can't be opened."))
        pfd.use {
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val src: ByteSource = ChannelSource(ch)
                val codec = try { RawFiles.open(src) } catch (e: RawUnsupportedException) {
                    return photo(ctx, index, item, rawFormat to RawFallback.note(rawFormat, item, e.shown))
                } catch (e: RawFormatException) {
                    throw UserFacingException(damaged(label))
                }
                val info = codec.info
                // The sensor data, the file's compressed data and the encoder's copy must fit.
                val need = info.width.toLong() * info.height * 2 * 3 + src.size
                val rt = Runtime.getRuntime()
                val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
                if (need > free) throw SkipItemException(tr("This {0} file needs about {1} of memory, more than is free right now. Close other apps and try again.", label, Format.bytes(need)))
                ctx.status(tr("Reading the sensor data"), item.name)
                val m = try { codec.decode() } catch (e: RawUnsupportedException) {
                    return photo(ctx, index, item, rawFormat to RawFallback.note(rawFormat, item, e.shown))
                } catch (e: RawFormatException) {
                    throw UserFacingException(damaged(label))
                }
                ctx.unitProgress(index, 0.15)
                ctx.status(tr("Removing noise"), item.name)
                OpenCvLoader.ensure()
                val r = RawDenoiser.denoise(m.data, info.width, info.height, info.active, info.maxValue, strength.value) { f -> ctx.throttle(); ctx.unitProgress(index, 0.15 + 0.65 * f) }
                if (!r.changed) throw SkipItemException(tr("The noise in this file couldn't be measured, so it was left as it is."))
                ctx.status(tr("Writing the {0} file", label), item.name)
                val patches = try { codec.encode(m) } catch (e: RawUnsupportedException) {
                    return photo(ctx, index, item, rawFormat to RawFallback.note(rawFormat, item, e.shown))
                }
                // Written to a private file first and read back: the cleaned sensor data must come out as written.
                val tmp = File(Outputs.tempDir(ctx), "raw-$index.$ext")
                try {
                    tmp.outputStream().buffered(1 shl 20).use { RawFiles.write(src, patches, it) }
                    ctx.unitProgress(index, 0.9)
                    RandomAccessFile(tmp, "r").use { f ->
                        val back = try { RawFiles.open(FileSource(f)).decode() } catch (e: RawFormatException) { null }
                        if (back == null || !sameEnough(back, m, info.format == "ARW" && info.variant == "compressed", info.maxValue)) {
                            throw UserFacingException(tr("The written {0} file failed verification, so it was discarded.", label))
                        }
                    }
                    OutputStore.ensureSpace(tmp.length())
                    val (out, _) = Outputs.produce(ctx, ToolId.DENOISE.area, "${item.baseName}_denoised.$ext", rawMime(item, ext)) { pending ->
                        tmp.inputStream().use { input -> pending.openStream().use { os -> Outputs.copy(input, os, tmp.length(), ctx) } }
                    }
                    return ItemResult(item.name, ItemOutcome.SUCCESS, null, listOf(out),
                        tr("{0} sensor data cleaned", label) + " · ${info.width}×${info.height} · ${Format.bytes(out.size)}")
                } finally { tmp.delete() }
            }
        }
    }

    private fun damaged(label: String) = tr("This {0} file looks damaged, so it was left as it is.", label)

    /** Exactly what was written (lossless formats), or within the format's own rounding (Sony's compressed ARW). */
    private fun sameEnough(a: Mosaic, b: Mosaic, lossy: Boolean, max: Int): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        if (!lossy) return a.data.contentEquals(b.data)
        var sum = 0L
        for (i in a.data.indices) sum += Math.abs((a.data[i].toInt() and 0xFFFF) - (b.data[i].toInt() and 0xFFFF))
        return sum.toDouble() / a.data.size < max / 512.0
    }

    private fun rawMime(item: MediaItem, ext: String): String {
        item.mime?.takeIf { it.startsWith("image/") && it != "image/*" }?.let { return it }
        return when (ext.lowercase()) {
            "dng" -> "image/x-adobe-dng"
            "cr2" -> "image/x-canon-cr2"
            "nef" -> "image/x-nikon-nef"
            "arw" -> "image/x-sony-arw"
            else -> "image/x-$ext"
        }
    }

    // ------------------------------------------------------------------ videos
    private fun video(ctx: JobContext, index: Int, item: MediaItem): ItemResult {
        val info = VideoProbe.probe(ctx.app, item.uri)
        val v = info.video ?: throw SkipItemException(tr("This file has no video track."))
        ctx.status(tr("Measuring the noise"), item.name)
        val sigma = videoNoise(ctx, item, info)
        val (w, h) = VideoTranscoder.outputSize(info.codedWidth, info.codedHeight, 0, info.frameRate)
        val bitrate = keepQualityBitrate(info, item.size, w, h)
        OutputStore.ensureSpace((bitrate.toLong() + 192_000) * info.durationUs / 8_000_000 * 2)
        val tmp = File(Outputs.tempDir(ctx), "denoise-$index.mp4")
        try {
            ctx.status(tr("Removing noise frame by frame"), item.name)
            val dn = VideoTranscoder.Denoise((sigma * strength.value).toFloat(), VideoDenoise.defaults)
            val result = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { pfd ->
                VideoTranscoder(ctx.app, item.uri, info).run(pfd.fileDescriptor, VideoTranscoder.Params(w, h, bitrate, denoise = dn),
                    ctx.workload, { ctx.throttle() }) { f -> ctx.unitProgress(index, 0.05 + f * 0.9) }
            }
            val out = Outputs.produce(ctx, ToolId.DENOISE.videoArea!!, "${item.baseName}_denoised.mp4", "video/mp4") { pending ->
                RandomAccessFile(tmp, "r").use { raf -> pending.openStream().buffered(1 shl 20).use { os -> Mp4FastStart.process(raf, os) } }
                Outputs.verifyMedia(ctx.app, pending.uri, true, false)
            }.first
            return ItemResult(item.name, ItemOutcome.SUCCESS, result.notes.joinToString(" ").ifBlank { null }, listOf(out),
                "${noiseLabel(sigma * 255)} · ${info.displayWidth}×${info.displayHeight} · " + tr("H.264 from {0}", v.codecLabel) + " · ${Format.bytes(out.size)}")
        } finally { tmp.delete() }
    }

    /** The video's noise (share of full brightness), from three frames measured together ([VideoDenoise.sigma]). */
    private fun videoNoise(ctx: JobContext, item: MediaItem, info: com.localmediatools.video.VideoInfo): Double {
        val w = info.displayWidth; val h = info.displayHeight
        val times = listOf(0.2, 0.5, 0.8).map { (info.durationUs * it).toLong() }
        val meter = PhotoDenoiser.Meter()
        var k = 0
        try {
            FrameGrabber(ctx.app, item.uri, info, w, h).run(0, info.durationUs, ctx.workload.codecOperatingRate, { pts -> k < times.size && pts >= times[k] }, { argb, _ ->
                meter.add(argb, w, h)
                k++
                k < times.size
            }) { ctx.throttle() }
        } catch (e: Exception) {
            if (k == 0) throw e
        }
        if (k == 0) throw UserFacingException(tr("No video frames could be decoded from this file."))
        return VideoDenoise.sigma(meter.result()).toDouble()
    }
}
