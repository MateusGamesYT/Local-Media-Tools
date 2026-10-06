package com.localmediatools.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import com.localmediatools.codec.jpeg.JpegWriter
import com.localmediatools.codec.png.PngColorAnalyzer
import com.localmediatools.codec.png.PngColorMode
import com.localmediatools.codec.png.PngWriter
import com.localmediatools.core.PendingOutput
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.JobContext
import java.io.FilterOutputStream
import java.io.OutputStream

enum class ImageOutFormat(val ext: String, val mime: String, val lossless: Boolean, val label: String) {
    JPEG("jpg", "image/jpeg", false, "JPEG"),
    PNG("png", "image/png", true, "PNG"),
    WEBP("webp", "image/webp", false, "WebP"),
    WEBP_LOSSLESS("webp", "image/webp", true, "WebP lossless");

    val supportsAlpha get() = this != JPEG
    /** WebP cannot exceed 16383 px per side. */
    val maxDimension get() = if (this == WEBP || this == WEBP_LOSSLESS) 16383 else if (this == JPEG) 65535 else Int.MAX_VALUE
    val streamable get() = this == JPEG || this == PNG
}

data class EncodeSpec(
    val format: ImageOutFormat,
    val quality: Int = 90,
    /** Pick the smallest exact PNG colour type (palette / grey / RGB / RGBA) and compress harder. */
    val optimizePng: Boolean = false,
    val background: Int = Color.WHITE,
)

/** Draws on top of the image while it is rendered. Coordinates are in output pixels. */
fun interface Overlay {
    /** [canvas] covers output rows [top, top + canvas height). */
    fun draw(canvas: Canvas, top: Int, outW: Int, outH: Int)
}

class CountingStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L; private set
    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
}

/**
 * Renders a source image (display orientation, optional scaling and overlay) into an encoded file.
 * Chooses between a whole-bitmap path (fast, uses the platform encoders) and a streaming strip
 * path (bounded memory, for very large images) based on the free-memory budget.
 */
object ImagePipeline {

    class Result(val bytes: Long, val width: Int, val height: Int, val usedStreaming: Boolean, val flattenedTransparency: Boolean)

    fun render(
        ctx: JobContext,
        src: ImageSource,
        outW: Int,
        outH: Int,
        spec: EncodeSpec,
        pending: PendingOutput,
        overlay: Overlay? = null,
        exactPixels: Boolean = false,
        onProgress: (Double) -> Unit = {},
    ): Result {
        if (outW > spec.format.maxDimension || outH > spec.format.maxDimension) {
            throw UserFacingException("${spec.format.label} supports at most ${spec.format.maxDimension} px per side; this image would be ${outW}×$outH. Choose a smaller width or another format.")
        }
        val budget = ctx.memoryBudget(ctx.workload.parallelism)
        val scale = src.width.toDouble() / outW
        val sample = sampleFor(scale)
        val decodeBytes = src.bytesFor(sample) * (if (src.orientation.isIdentity) 1 else 2)
        val outBytes = outW.toLong() * outH * 4
        // Full path needs the decode plus the scaled/flattened copy.
        val fullNeed = decodeBytes + (if (sample > 1 || scale != 1.0 || overlay != null || spec.format == ImageOutFormat.JPEG) outBytes else 0)
        val counting = CountingStream(pending.openStream().buffered(1 shl 16))
        try {
            val streaming = fullNeed > budget
            var flattened = false
            if (!streaming) {
                flattened = renderFull(ctx, src, outW, outH, sample, spec, overlay, exactPixels, counting)
            } else {
                if (!spec.format.streamable) {
                    throw UserFacingException("This image is too large to encode as ${spec.format.label} with the memory available (${src.width}×${src.height}). Choose PNG or JPEG, or set a smaller width.")
                }
                if (!src.canServeRegions(budget)) {
                    throw UserFacingException("This ${src.format.label} image is too large to process in pieces with the memory available.")
                }
                if (exactPixels) renderStripsExact(ctx, src, spec, budget, counting, onProgress)
                else renderStrips(ctx, src, outW, outH, spec, overlay, budget, counting, onProgress)
            }
            counting.flush()
            counting.close()
            return Result(counting.count, outW, outH, streaming, flattened)
        } catch (t: Throwable) {
            try { counting.close() } catch (_: Exception) { }
            throw t
        }
    }

    fun sampleFor(scale: Double): Int {
        var s = 1
        while (s * 2 <= scale) s *= 2
        return s
    }

    /** Returns true when transparent pixels had to be flattened onto the background (JPEG). */
    private fun renderFull(
        ctx: JobContext, src: ImageSource, outW: Int, outH: Int, sample: Int, spec: EncodeSpec,
        overlay: Overlay?, exactPixels: Boolean, out: OutputStream,
    ): Boolean {
        ctx.throttle()
        var bmp = src.decode(sample, unpremultiplied = exactPixels, keepColorSpace = exactPixels)
        ctx.throttle()
        if (bmp.width != outW || bmp.height != outH) {
            if (exactPixels) throw IllegalStateException("exact pixel path cannot scale")
            bmp = BitmapOps.scale(bmp, outW, outH, recycleSource = true)
        }
        if (overlay != null) {
            if (!bmp.isMutable) {
                val m = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: throw OutOfMemoryError()
                bmp.recycle(); bmp = m
            }
            overlay.draw(Canvas(bmp), 0, outW, outH)
        }
        ctx.throttle()
        val flattened = spec.format == ImageOutFormat.JPEG && BitmapOps.hasTransparency(bmp)
        try {
            encodeBitmap(bmp, spec, out)
        } finally {
            bmp.recycle()
        }
        ctx.throttle()
        return flattened
    }

    /** Encodes a finished bitmap with the platform encoders (or the exact PNG writer). */
    fun encodeBitmap(bmp0: Bitmap, spec: EncodeSpec, out: OutputStream) {
        var bmp = bmp0
        when (spec.format) {
            ImageOutFormat.JPEG -> {
                val flat = BitmapOps.flatten(bmp, spec.background, recycleSource = false)
                try {
                    if (!flat.compress(Bitmap.CompressFormat.JPEG, spec.quality.coerceIn(1, 100), out)) throw UserFacingException("The JPEG encoder failed.")
                } finally { if (flat !== bmp) flat.recycle() }
            }
            ImageOutFormat.WEBP -> {
                @Suppress("DEPRECATION")
                val f = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                // On Android 10 quality 100 would silently switch to lossless; cap it.
                val q = if (Build.VERSION.SDK_INT >= 30) spec.quality.coerceIn(1, 100) else spec.quality.coerceIn(1, 99)
                if (!bmp.compress(f, q, out)) throw UserFacingException("The WebP encoder failed.")
            }
            ImageOutFormat.WEBP_LOSSLESS -> {
                @Suppress("DEPRECATION")
                val ok = if (Build.VERSION.SDK_INT >= 30) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                else bmp.compress(Bitmap.CompressFormat.WEBP, 100, out)
                if (!ok) throw UserFacingException("The lossless WebP encoder failed.")
            }
            ImageOutFormat.PNG -> {
                val cs = bmp.colorSpace
                val srgb = cs == null || cs == android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                if (!srgb) {
                    // The platform encoder embeds the colour profile; keep colours exact that way.
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw UserFacingException("The PNG encoder failed.")
                } else {
                    writePng(bmp, spec, out)
                }
            }
        }
    }

    private fun writePng(bmp: Bitmap, spec: EncodeSpec, out: OutputStream) {
        val w = bmp.width; val h = bmp.height
        val band = maxOf(1, (2 shl 20) / w)
        val px = IntArray(w * band)
        var mode = if (bmp.hasAlpha()) PngColorMode.RGBA else PngColorMode.RGB
        var palette: IntArray? = null
        if (spec.optimizePng) {
            val an = PngColorAnalyzer()
            var y = 0
            while (y < h) {
                val n = minOf(band, h - y)
                bmp.getPixels(px, 0, w, 0, y, w, n)
                an.feed(px, 0, w, w, n)
                y += n
            }
            val c = an.choose(); mode = c.first; palette = c.second
        } else if (bmp.hasAlpha() && !BitmapOps.hasTransparency(bmp)) {
            mode = PngColorMode.RGB
        }
        val writer = PngWriter(out, w, h, mode, palette, if (spec.optimizePng) 9 else 6)
        var y = 0
        while (y < h) {
            val n = minOf(band, h - y)
            bmp.getPixels(px, 0, w, 0, y, w, n)
            writer.writeRows(px, 0, w, n)
            y += n
        }
        writer.finish()
    }

    /** Streaming encoders share this tiny interface. */
    interface StripEncoder {
        fun write(argb: IntArray, offset: Int, stride: Int, rows: Int)
        fun finish()
    }

    fun stripEncoder(spec: EncodeSpec, w: Int, h: Int, hasAlpha: Boolean, out: OutputStream): StripEncoder = when (spec.format) {
        ImageOutFormat.PNG -> {
            val pw = PngWriter(out, w, h, if (hasAlpha) PngColorMode.RGBA else PngColorMode.RGB, null, if (spec.optimizePng) 9 else 6)
            object : StripEncoder {
                override fun write(argb: IntArray, offset: Int, stride: Int, rows: Int) = pw.writeRows(argb, offset, stride, rows)
                override fun finish() = pw.finish()
            }
        }
        ImageOutFormat.JPEG -> {
            val jw = JpegWriter(out, w, h, spec.quality, background = spec.background)
            object : StripEncoder {
                override fun write(argb: IntArray, offset: Int, stride: Int, rows: Int) = jw.writeRows(argb, offset, stride, rows)
                override fun finish() = jw.finish()
            }
        }
        else -> throw UserFacingException("${spec.format.label} cannot be written in pieces.")
    }

    private fun renderStrips(
        ctx: JobContext, src: ImageSource, outW: Int, outH: Int, spec: EncodeSpec, overlay: Overlay?,
        budget: Long, out: OutputStream, onProgress: (Double) -> Unit,
    ) {
        val scale = src.width.toDouble() / outW
        val sample = sampleFor(scale)
        // Strip height: decoded region (source width x strip*scale rows) must stay well within budget.
        val bytesPerOutRow = (src.width.toLong() / sample + 1) * 4 * (scale / sample + 1) * 2 + outW * 8L
        val stripRows = (budget / 3 / bytesPerOutRow).toInt().coerceIn(16, 2048).coerceAtMost(outH)
        val enc = stripEncoder(spec, outW, outH, spec.format.supportsAlpha, out)
        val strip = Bitmap.createBitmap(outW, stripRows, Bitmap.Config.ARGB_8888)
        val px = IntArray(outW * stripRows)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            var oy = 0
            while (oy < outH) {
                ctx.throttle()
                val rows = minOf(stripRows, outH - oy)
                strip.eraseColor(Color.TRANSPARENT)
                val c = Canvas(strip)
                // Source display rows needed (with a margin so filtering has neighbours).
                val pad = (2 * scale).toInt() + 2
                val top = (Math.floor(oy * scale).toInt() - pad).coerceAtLeast(0)
                val bottom = (Math.ceil((oy + rows) * scale).toInt() + pad).coerceAtMost(src.height)
                val region = src.decodeRegion(Rect(0, top, src.width, bottom), sample, budget)
                val m = Matrix()
                val sx = src.width.toDouble() / region.width / scale   // region px -> output px
                val sy = (bottom - top).toDouble() / region.height / scale
                m.setScale(sx.toFloat(), sy.toFloat())
                m.postTranslate(0f, (top / scale - oy).toFloat())
                c.drawBitmap(region, m, paint)
                region.recycle()
                overlay?.let {
                    c.save(); c.translate(0f, -oy.toFloat())
                    it.draw(c, oy, outW, outH)
                    c.restore()
                }
                strip.getPixels(px, 0, outW, 0, 0, outW, rows)
                enc.write(px, 0, outW, rows)
                oy += rows
                onProgress(oy.toDouble() / outH)
            }
            enc.finish()
        } finally {
            strip.recycle()
        }
    }

    /** Pixel-exact streaming (no scaling, no blending): used by the lossless optimizer. */
    private fun renderStripsExact(ctx: JobContext, src: ImageSource, spec: EncodeSpec, budget: Long, out: OutputStream, onProgress: (Double) -> Unit) {
        val w = src.width; val h = src.height
        val stripRows = (budget / 4 / (w.toLong() * 4 * 3)).toInt().coerceIn(8, 2048).coerceAtMost(h)
        val enc = stripEncoder(spec, w, h, true, out)
        val px = IntArray(w * stripRows)
        var y = 0
        while (y < h) {
            ctx.throttle()
            val rows = minOf(stripRows, h - y)
            val region = src.decodeRegion(Rect(0, y, w, y + rows), 1, budget, unpremultiplied = true, keepColorSpace = true)
            try {
                if (region.width != w || region.height != rows) throw IllegalStateException("region size mismatch")
                val cs = region.colorSpace
                if (cs != null && cs != android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)) {
                    throw UserFacingException("This very large image uses the ${cs.name} colour profile, which can't be preserved exactly when processing it in pieces.")
                }
                region.getPixels(px, 0, w, 0, 0, w, rows)
            } finally { region.recycle() }
            enc.write(px, 0, w, rows)
            y += rows
            onProgress(y.toDouble() / h)
        }
        enc.finish()
    }

    /** Confirms the written file decodes to the expected size before it is published. */
    fun verify(ctx: android.content.Context, pending: PendingOutput, expectW: Int, expectH: Int) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(pending.uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth != expectW || o.outHeight != expectH) {
            throw UserFacingException("The written file failed verification (${o.outWidth}×${o.outHeight}, expected ${expectW}×$expectH), so it was discarded.")
        }
    }

    /** Output size for a maximum width (0 = keep), never upscaling, preserving aspect ratio. */
    fun fitWidth(w: Int, h: Int, maxWidth: Int): Pair<Int, Int> {
        if (maxWidth <= 0 || w <= maxWidth) return w to h
        val nh = Math.round(h.toDouble() * maxWidth / w).toInt().coerceAtLeast(1)
        return maxWidth to nh
    }
}
