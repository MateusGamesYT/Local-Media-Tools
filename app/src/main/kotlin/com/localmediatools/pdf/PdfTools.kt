package com.localmediatools.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.localmediatools.core.UserFacingException
import java.io.File
import java.io.InputStream

/** Opens PDFs with Android's renderer, copying to a private temp file when the provider isn't seekable. */
object PdfFiles {
    fun openDescriptor(ctx: Context, uri: Uri, tempDir: File): Pair<ParcelFileDescriptor, File?> {
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null && pfd.statSize > 0) return pfd to null
            pfd?.close()
        } catch (_: Exception) {
        }
        val tmp = File.createTempFile("pdf", ".pdf", tempDir)
        ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            ?: throw UserFacingException("The PDF could not be opened.")
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY) to tmp
    }

    fun renderer(pfd: ParcelFileDescriptor): PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: SecurityException) {
        throw UserFacingException("This PDF is password-protected and can't be opened.")
    } catch (e: Exception) {
        throw UserFacingException("This file is not a valid PDF or is damaged.", e)
    }

    /** Reads "%PDF" from the first bytes. */
    fun looksLikePdf(input: InputStream): Boolean {
        val b = ByteArray(1024)
        val n = input.read(b)
        if (n < 5) return false
        val s = String(b, 0, n, Charsets.ISO_8859_1)
        return s.contains("%PDF-")
    }
}

/**
 * Renders a PDF page in horizontal strips, so pages of any size at up to 400 % scale can be
 * rasterised with bounded memory. Each strip is delivered as ARGB rows on a white background.
 */
class PdfPageRasterizer(private val page: PdfRenderer.Page, val scale: Double) {
    val width: Int = Math.max(1, Math.round(page.width * scale).toInt())
    val height: Int = Math.max(1, Math.round(page.height * scale).toInt())

    fun render(stripRows: Int, throttle: () -> Unit, sink: (y: Int, rows: Int, argb: IntArray) -> Unit) {
        val rowsPer = stripRows.coerceIn(1, height)
        val strip = Bitmap.createBitmap(width, rowsPer, Bitmap.Config.ARGB_8888)
        val px = IntArray(width * rowsPer)
        try {
            var y = 0
            while (y < height) {
                throttle()
                val rows = minOf(rowsPer, height - y)
                strip.eraseColor(Color.WHITE)
                val m = Matrix()
                m.setScale(scale.toFloat(), scale.toFloat())
                m.postTranslate(0f, -y.toFloat())
                // The clip is the bitmap itself; content outside it is skipped by the renderer.
                page.render(strip, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                strip.getPixels(px, 0, width, 0, 0, width, rows)
                sink(y, rows, px)
                y += rows
            }
        } finally {
            strip.recycle()
        }
    }
}
