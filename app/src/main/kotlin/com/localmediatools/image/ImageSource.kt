package com.localmediatools.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.localmediatools.codec.image.ExifOrientationReader
import com.localmediatools.codec.image.Orientation
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.SniffedFormat
import com.localmediatools.core.UserFacingException
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream

class ImageTooLargeException(message: String) : UserFacingException(message)

/**
 * An image file opened for orientation-correct decoding.
 *
 * Everything this class returns is in *display* orientation: EXIF / HEIF rotation and mirroring are
 * resolved once here (and cross-checked against Android's own oriented decoder), so no tool can
 * accidentally show or save a rotated image. Pixels are decoded raw and transformed exactly, which
 * keeps the transform pixel-perfect and consistent between previews, full decodes and region decodes.
 */
class ImageSource private constructor(
    private val ctx: Context,
    val uri: Uri,
    val name: String,
    val format: SniffedFormat,
    val rawWidth: Int,
    val rawHeight: Int,
    val orientation: Orientation,
    private val mode: Mode,
) : Closeable {

    private enum class Mode { PLATFORM, IMAGE_DECODER, SOFTWARE }

    val width: Int get() = orientation.displayWidth(rawWidth, rawHeight)
    val height: Int get() = orientation.displayHeight(rawWidth, rawHeight)
    val pixelCount: Long get() = rawWidth.toLong() * rawHeight

    private var regionPfd: ParcelFileDescriptor? = null
    private var region: BitmapRegionDecoder? = null
    private var regionTried = false
    private var cachedFull: Bitmap? = null
    private val lock = Any()

    /** Bytes needed for a full ARGB_8888 decode at [sampleSize]. */
    fun bytesFor(sampleSize: Int): Long {
        val s = sampleSize.coerceAtLeast(1)
        return ((rawWidth + s - 1) / s).toLong() * ((rawHeight + s - 1) / s) * 4
    }

    /** Largest power-of-two sample size whose decode is still at least [minW] x [minH] (display). */
    fun sampleSizeFor(minW: Int, minH: Int): Int {
        var s = 1
        while (width / (s * 2) >= minW && height / (s * 2) >= minH && s < 64) s *= 2
        return s
    }

    /**
     * Decodes the whole image, oriented. Throws [ImageTooLargeException] when the decode would need
     * more than [budgetBytes] (an orientation change needs room for two copies briefly).
     */
    fun decode(sampleSize: Int = 1, budgetBytes: Long = Long.MAX_VALUE, unpremultiplied: Boolean = false, keepColorSpace: Boolean = false): Bitmap {
        val need = bytesFor(sampleSize) * (if (orientation.isIdentity) 1 else 2)
        if (need > budgetBytes) {
            throw ImageTooLargeException("This image (${width}×$height) needs about ${need / (1024 * 1024)} MB of memory, more than is free right now.")
        }
        val raw = when (mode) {
            Mode.PLATFORM -> decodePlatform(sampleSize, unpremultiplied, keepColorSpace)
            Mode.IMAGE_DECODER -> return decodeWithImageDecoder(sampleSize, unpremultiplied, keepColorSpace)
            Mode.SOFTWARE -> SoftDecoders.decode(ctx, uri, format, sampleSize, unpremultiplied)
        }
        return BitmapOps.orient(raw, orientation, recycleSource = true)
    }

    private fun decodePlatform(sampleSize: Int, unpremultiplied: Boolean, keepColorSpace: Boolean): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = !unpremultiplied
            if (!keepColorSpace) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bmp = open().use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw UserFacingException("The image data could not be decoded. The file may be damaged or use an unsupported variant of ${format.label}.")
        return BitmapOps.ensureArgb8888(bmp)
    }

    private fun decodeWithImageDecoder(sampleSize: Int, unpremultiplied: Boolean, keepColorSpace: Boolean): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isUnpremultipliedRequired = unpremultiplied
            if (sampleSize > 1) decoder.setTargetSampleSize(sampleSize)
            if (!keepColorSpace) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setOnPartialImageListener { false }
        }
        return BitmapOps.ensureArgb8888(bmp)
    }

    val supportsRegions: Boolean
        get() = synchronized(lock) { ensureRegion() != null }

    private fun ensureRegion(): BitmapRegionDecoder? {
        if (regionTried) return region
        regionTried = true
        if (mode != Mode.PLATFORM) return null
        if (format !in setOf(SniffedFormat.JPEG, SniffedFormat.PNG, SniffedFormat.WEBP, SniffedFormat.HEIF)) return null
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            regionPfd = pfd
            region = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(pfd)
            else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
            val r = region
            if (r != null && (r.width != rawWidth || r.height != rawHeight)) {
                // Never use a decoder that disagrees with the decoded image size.
                r.recycle(); region = null
            }
        } catch (e: Exception) {
            region = null
        }
        if (region == null) { try { regionPfd?.close() } catch (_: Exception) { }; regionPfd = null }
        return region
    }

    /**
     * Decodes the display-space rectangle [displayRect] at [sampleSize], oriented.
     * Uses region decoding where supported, otherwise a cached full decode (if it fits [budgetBytes]).
     */
    fun decodeRegion(displayRect: Rect, sampleSize: Int = 1, budgetBytes: Long = Long.MAX_VALUE, unpremultiplied: Boolean = false, keepColorSpace: Boolean = false): Bitmap {
        val r = orientation.displayRectToRaw(displayRect.left, displayRect.top, displayRect.right, displayRect.bottom, rawWidth, rawHeight)
        val rawRect = Rect(r[0].coerceAtLeast(0), r[1].coerceAtLeast(0), r[2].coerceAtMost(rawWidth), r[3].coerceAtMost(rawHeight))
        require(!rawRect.isEmpty) { "Empty region" }
        val decoder = synchronized(lock) { ensureRegion() }
        if (decoder != null) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = !unpremultiplied
                if (!keepColorSpace) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val part = decoder.decodeRegion(rawRect, opts)
                ?: throw UserFacingException("Part of the image could not be decoded; the file may be damaged.")
            return BitmapOps.orient(BitmapOps.ensureArgb8888(part), orientation, recycleSource = true)
        }
        // Fallback: crop from a cached full-resolution decode.
        val full = synchronized(lock) {
            cachedFull ?: run {
                val bmp = decode(1, budgetBytes, unpremultiplied, keepColorSpace)
                cachedFull = bmp
                bmp
            }
        }
        val cut = Bitmap.createBitmap(full, displayRect.left, displayRect.top, displayRect.width(), displayRect.height())
        return if (sampleSize > 1) BitmapOps.scale(cut, maxOf(1, cut.width / sampleSize), maxOf(1, cut.height / sampleSize), recycleSource = cut !== full) else
            (if (cut === full) cut.copy(Bitmap.Config.ARGB_8888, false) else cut)
    }

    /** True when region decoding or an in-budget full decode makes [decodeRegion] usable. */
    fun canServeRegions(budgetBytes: Long) = supportsRegions || bytesFor(1) * (if (orientation.isIdentity) 1 else 2) <= budgetBytes

    /** Oriented preview whose longer side is at most [maxSide]. */
    fun preview(maxSide: Int): Bitmap {
        val s = sampleSizeFor(maxSide / 2, maxSide / 2).coerceAtLeast(1)
        var bmp = decode(s)
        val scale = maxSide.toDouble() / maxOf(bmp.width, bmp.height)
        if (scale < 1.0) bmp = BitmapOps.scale(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), recycleSource = true)
        return bmp
    }

    fun open(): InputStream = BufferedInputStream(MediaProbe.openInput(ctx.contentResolver, uri), 1 shl 16)

    override fun close() {
        synchronized(lock) {
            try { region?.recycle() } catch (_: Exception) { }
            region = null
            try { regionPfd?.close() } catch (_: Exception) { }
            regionPfd = null
            cachedFull?.recycle()
            cachedFull = null
        }
    }

    companion object {
        private const val HEAD = 256 * 1024

        fun open(ctx: Context, item: MediaItem): ImageSource = open(ctx, item.uri, item.name, item.format)

        fun open(ctx: Context, uri: Uri, name: String, knownFormat: SniffedFormat? = null): ImageSource {
            val resolver = ctx.contentResolver
            val head = ByteArray(HEAD)
            var n = 0
            MediaProbe.openInput(resolver, uri).use { s ->
                while (n < head.size) {
                    val k = s.read(head, n, head.size - n)
                    if (k < 0) break
                    n += k
                }
            }
            if (n == 0) throw UserFacingException("The file is empty.")
            val format = knownFormat?.takeIf { it != SniffedFormat.UNKNOWN } ?: SniffedFormat.sniff(head, n, name, null)

            if (SoftDecoders.handles(format)) {
                val dims = SoftDecoders.readSize(ctx, uri, format)
                    ?: throw UserFacingException("This ${format.label} file is damaged or uses an unsupported variant.")
                return ImageSource(ctx, uri, name, format, dims.first, dims.second, Orientation.NORMAL, Mode.SOFTWARE)
            }

            // Raw (unoriented) size from the platform decoder.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                BufferedInputStream(MediaProbe.openInput(resolver, uri), 1 shl 16).use { BitmapFactory.decodeStream(it, null, bounds) }
            } catch (_: Exception) {
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                // Some formats (e.g. AVIF on certain versions) only work through ImageDecoder, which
                // already applies orientation.
                val size = imageDecoderSize(ctx, uri)
                    ?: throw UserFacingException(unsupportedMessage(format))
                return ImageSource(ctx, uri, name, format, size.first, size.second, Orientation.NORMAL, Mode.IMAGE_DECODER)
            }
            val rw = bounds.outWidth
            val rh = bounds.outHeight
            checkComplete(ctx, uri, format, head, n)
            val orientation = resolveOrientation(ctx, uri, format, head, n, rw, rh)
            return ImageSource(ctx, uri, name, format, rw, rh, orientation, Mode.PLATFORM)
        }

        /**
         * Android's decoders fill missing data of a truncated JPEG/PNG/WebP with grey or blank
         * pixels without reporting it. Detect truncation up front so it is reported, not baked in.
         */
        private fun checkComplete(ctx: Context, uri: Uri, format: SniffedFormat, head: ByteArray, n: Int) {
            if (format != SniffedFormat.JPEG && format != SniffedFormat.PNG && format != SniffedFormat.WEBP) return
            val size: Long
            val tail: ByteArray
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return
                pfd.use {
                    size = it.statSize
                    if (size <= 0) return
                    val len = minOf(size, 65536L).toInt()
                    tail = ByteArray(len)
                    java.io.FileInputStream(it.fileDescriptor).use { fis ->
                        fis.channel.position(size - len)
                        var off = 0
                        while (off < len) { val k = fis.read(tail, off, len - off); if (k < 0) break; off += k }
                    }
                }
            } catch (_: Exception) {
                return // provider without seekable descriptors: skip the check
            }
            val truncated = when (format) {
                SniffedFormat.JPEG -> {
                    var found = false
                    for (i in tail.size - 2 downTo 0) if (tail[i] == 0xFF.toByte() && tail[i + 1] == 0xD9.toByte()) { found = true; break }
                    !found
                }
                SniffedFormat.PNG -> {
                    val s = String(tail, Charsets.ISO_8859_1)
                    !s.contains("IEND")
                }
                else -> {
                    if (n < 8) false else {
                        val riff = (head[4].toLong() and 0xFF) or ((head[5].toLong() and 0xFF) shl 8) or
                            ((head[6].toLong() and 0xFF) shl 16) or ((head[7].toLong() and 0xFF) shl 24)
                        riff + 8 > size
                    }
                }
            }
            if (truncated) throw UserFacingException("This ${format.label} file is incomplete (truncated). Processing it would produce grey or missing areas, so it was not used.")
        }

        private fun unsupportedMessage(f: SniffedFormat) = when (f) {
            SniffedFormat.AVIF -> "AVIF images can't be decoded on this Android version."
            SniffedFormat.HEIF -> "This HEIC/HEIF image can't be decoded on this device."
            SniffedFormat.RAW_CAMERA -> "This camera RAW format isn't supported by Android's decoder."
            SniffedFormat.UNKNOWN -> "This file is not an image format that can be decoded."
            else -> "This ${f.label} image could not be decoded. It may be damaged."
        }

        private fun resolveOrientation(ctx: Context, uri: Uri, format: SniffedFormat, head: ByteArray, n: Int, rw: Int, rh: Int): Orientation {
            val parsed = ExifOrientationReader.read(if (n == head.size) head else head.copyOf(n))
            return when (format) {
                SniffedFormat.JPEG, SniffedFormat.PNG, SniffedFormat.WEBP -> parsed ?: Orientation.NORMAL
                SniffedFormat.HEIF, SniffedFormat.AVIF -> {
                    val rot = try {
                        MediaMetadataRetriever().run {
                            try {
                                setDataSource(ctx, uri)
                                extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_ROTATION)?.toIntOrNull()
                            } finally { release() }
                        }
                    } catch (_: Exception) { null }
                    val candidate = if (rot != null) Orientation.fromRotation(rot) else exifInterfaceOrientation(ctx, uri) ?: Orientation.NORMAL
                    verifyAgainstPlatform(ctx, uri, candidate, rw, rh)
                }
                SniffedFormat.DNG, SniffedFormat.RAW_CAMERA, SniffedFormat.TIFF -> {
                    val candidate = exifInterfaceOrientation(ctx, uri) ?: parsed ?: Orientation.NORMAL
                    verifyAgainstPlatform(ctx, uri, candidate, rw, rh)
                }
                else -> Orientation.NORMAL
            }
        }

        private fun exifInterfaceOrientation(ctx: Context, uri: Uri): Orientation? = try {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val v = ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
                if (v in 1..8) Orientation.fromExif(v) else null
            }
        } catch (_: Exception) { null }

        /** Oriented size reported by ImageDecoder (header only), or null. */
        fun imageDecoderSize(ctx: Context, uri: Uri): Pair<Int, Int>? = try {
            var size: Pair<Int, Int>? = null
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { _, info, _ ->
                    size = info.size.width to info.size.height
                    throw HeaderOnly()
                }
            } catch (_: HeaderOnly) {
            }
            size
        } catch (_: Exception) { null }

        private class HeaderOnly : RuntimeException()

        /**
         * Cross-checks a metadata-derived orientation with Android's oriented decoder. If the two
         * disagree about which way the image stands, compare tiny decodes to find the transform the
         * platform applies, so outputs always match what the gallery shows.
         */
        private fun verifyAgainstPlatform(ctx: Context, uri: Uri, candidate: Orientation, rw: Int, rh: Int): Orientation {
            val platform = imageDecoderSize(ctx, uri) ?: return candidate
            val expectW = candidate.displayWidth(rw, rh); val expectH = candidate.displayHeight(rw, rh)
            if (platform.first == expectW && platform.second == expectH) return candidate
            val swapped = platform.first == rh && platform.second == rw && rw != rh
            val sameAsRaw = platform.first == rw && platform.second == rh
            if (!swapped && !sameAsRaw) return candidate // different sizes (e.g. embedded preview): keep metadata
            return try {
                empiricalOrientation(ctx, uri, rw, rh, swapped) ?: if (swapped) Orientation.ROTATE_90 else Orientation.NORMAL
            } catch (_: Throwable) { candidate }
        }

        private fun empiricalOrientation(ctx: Context, uri: Uri, rw: Int, rh: Int, swapped: Boolean): Orientation? {
            val target = 48
            val oriented = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = maxOf(1, maxOf(info.size.width, info.size.height) / target)
                d.setTargetSampleSize(s)
            }
            var sample = 1
            while (maxOf(rw, rh) / (sample * 2) >= target) sample *= 2
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val candidates = Orientation.entries.filter { it.swapsDimensions == swapped }
            var best: Orientation? = null
            var bestScore = Double.MAX_VALUE
            val ow = oriented.width; val oh = oriented.height
            val ref = IntArray(ow * oh)
            BitmapOps.ensureArgb8888(oriented).getPixels(ref, 0, ow, 0, 0, ow, oh)
            for (o in candidates) {
                val t = BitmapOps.orient(raw.copy(Bitmap.Config.ARGB_8888, false), o, recycleSource = true)
                val scaled = Bitmap.createScaledBitmap(t, ow, oh, true)
                val px = IntArray(ow * oh)
                scaled.getPixels(px, 0, ow, 0, 0, ow, oh)
                var diff = 0.0
                for (i in px.indices) {
                    val a = ref[i]; val b = px[i]
                    diff += Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
                        Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + Math.abs((a and 0xFF) - (b and 0xFF))
                }
                if (diff < bestScore) { bestScore = diff; best = o }
                if (scaled !== t) scaled.recycle()
                t.recycle()
            }
            raw.recycle(); oriented.recycle()
            return best
        }
    }
}
