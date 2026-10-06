package com.localmediatools.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.localmediatools.codec.image.SoftDecodeException
import com.localmediatools.codec.image.SoftImageDecoders
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.SniffedFormat
import com.localmediatools.core.UserFacingException

/** Bridges the pure-Kotlin decoders (TIFF, PSD, QOI, PNM, TGA) to Android bitmaps. */
object SoftDecoders {
    private const val MAX_FILE = 512L * 1024 * 1024

    fun handles(f: SniffedFormat) = kindOf(f) != null

    private fun kindOf(f: SniffedFormat) = when (f) {
        SniffedFormat.TIFF -> SoftImageDecoders.Kind.TIFF
        SniffedFormat.PSD -> SoftImageDecoders.Kind.PSD
        SniffedFormat.QOI -> SoftImageDecoders.Kind.QOI
        SniffedFormat.PNM -> SoftImageDecoders.Kind.PNM
        SniffedFormat.TGA -> SoftImageDecoders.Kind.TGA
        else -> null
    }

    private fun bytes(ctx: Context, uri: Uri): ByteArray =
        MediaProbe.openInput(ctx.contentResolver, uri).use { SoftImageDecoders.readAll(it, MAX_FILE) }

    fun readSize(ctx: Context, uri: Uri, f: SniffedFormat): Pair<Int, Int>? = try {
        SoftImageDecoders.size(kindOf(f)!!, bytes(ctx, uri))
    } catch (e: SoftDecodeException) {
        throw UserFacingException(e.message ?: "Unsupported ${f.label} file")
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    fun decode(ctx: Context, uri: Uri, f: SniffedFormat, sampleSize: Int, unpremultiplied: Boolean = false): Bitmap {
        val kind = kindOf(f) ?: throw UserFacingException("Unsupported format")
        val data = bytes(ctx, uri)
        val (w, h) = SoftImageDecoders.size(kind, data)
        val s = sampleSize.coerceAtLeast(1)
        val ow = (w + s - 1) / s; val oh = (h + s - 1) / s
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        // setPixels() takes non-premultiplied colours and premultiplies them only if the bitmap is
        // premultiplied, so an unpremultiplied target keeps the exact source values.
        if (unpremultiplied) bmp.isPremultiplied = false
        val row = IntArray(ow)
        try {
            SoftImageDecoders.decode(kind, data) { y0, count, argb, stride ->
                for (r in 0 until count) {
                    val y = y0 + r
                    if (y % s != 0) continue
                    val base = r * stride
                    for (x in 0 until ow) row[x] = argb[base + x * s]
                    bmp.setPixels(row, 0, ow, 0, y / s, ow, 1)
                }
            }
        } catch (e: SoftDecodeException) {
            bmp.recycle()
            throw UserFacingException(e.message ?: "The ${f.label} file could not be decoded")
        } catch (e: IndexOutOfBoundsException) {
            bmp.recycle()
            throw UserFacingException("The ${f.label} file is damaged.")
        }
        return bmp
    }
}
