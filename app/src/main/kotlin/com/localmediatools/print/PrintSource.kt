package com.localmediatools.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.image.ImageSource
import com.localmediatools.pdf.PdfFiles
import com.localmediatools.print.core.MediaSize
import com.localmediatools.print.core.PageRef
import com.localmediatools.print.core.Placement
import com.localmediatools.print.core.Sheet
import com.localmediatools.print.core.SheetRenderer
import com.localmediatools.print.core.matrix
import com.localmediatools.tools.PageRanges
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import kotlin.math.ceil

/**
 * The photos and PDF pages being printed, opened for drawing. Photos are decoded once at the size
 * their place on the sheet needs (the last few are kept); PDF pages are drawn by Android's PDF
 * renderer straight into each strip.
 */
class PrintSource(private val ctx: Context, val items: List<MediaItem>, private val tempDir: File, pageRange: String? = null,
                  /** Memory allowed for one decoded photo. */
                  private val photoBudget: Long = 96L shl 20) : Closeable {
    private class Pdf(val pfd: ParcelFileDescriptor, val tmp: File?, val renderer: PdfRenderer)
    private val pdfs = HashMap<Int, Pdf>()
    private val sources = HashMap<Int, ImageSource>()
    /** item → (sample size, decoded photo); the most recently used last. */
    private val decoded = LinkedHashMap<Int, Pair<Int, Bitmap>>(8, 0.75f, true)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    val pages: List<PageRef>

    init {
        val out = ArrayList<PageRef>()
        try {
            for ((i, item) in items.withIndex()) {
                if (item.kind == MediaKind.PDF) {
                    val (pfd, tmp) = PdfFiles.openDescriptor(ctx, item.uri, tempDir)
                    val r = try { PdfFiles.renderer(pfd) } catch (e: Exception) { pfd.close(); tmp?.delete(); throw e }
                    pdfs[i] = Pdf(pfd, tmp, r)
                    val wanted = pageRange?.takeIf { it.isNotBlank() }?.let { PageRanges.parse(it, r.pageCount) } ?: (0 until r.pageCount).toList()
                    for (p in wanted) {
                        val page = r.openPage(p)
                        out.add(PageRef(i, p, page.width.toFloat(), page.height.toFloat(), true))
                        page.close()
                    }
                } else {
                    val s = ImageSource.open(ctx, item)
                    sources[i] = s
                    out.add(PageRef(i, 0, s.width.toFloat(), s.height.toFloat(), false))
                }
            }
        } catch (e: Throwable) { close(); throw e }
        pages = out
    }

    /**
     * Draws [p] onto [band] — the strip of the sheet starting at row [y0], at [pxPerMm] — clipped
     * to its cell.
     */
    fun draw(band: Bitmap, canvas: Canvas, p: Placement, pxPerMm: Float, y0: Int) {
        val clip = Rect((p.clip.x * pxPerMm).toInt(), (p.clip.y * pxPerMm).toInt() - y0, ceil(p.clip.right * pxPerMm).toInt(), ceil(p.clip.bottom * pxPerMm).toInt() - y0)
        if (!clip.intersect(0, 0, band.width, band.height)) return
        val ref = p.ref
        val pdf = pdfs[ref.item]
        if (pdf != null) synchronized(pdf.renderer) {
            val page = pdf.renderer.openPage(ref.page)
            try {
                val m = p.matrix(page.width.toFloat(), page.height.toFloat(), pxPerMm)
                m[5] -= y0
                page.render(band, clip, Matrix().apply { setValues(floatArrayOf(m[0], m[1], m[2], m[3], m[4], m[5], 0f, 0f, 1f)) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            } finally { page.close() }
            return
        }
        val bmp = photo(ref.item, p, pxPerMm)
        val m = p.matrix(bmp.width.toFloat(), bmp.height.toFloat(), pxPerMm)
        m[5] -= y0
        canvas.save()
        canvas.clipRect(clip)
        canvas.drawBitmap(bmp, Matrix().apply { setValues(floatArrayOf(m[0], m[1], m[2], m[3], m[4], m[5], 0f, 0f, 1f)) }, paint)
        canvas.restore()
    }

    /** The photo decoded at least as large as its place on the sheet needs (never larger than the original). */
    private fun photo(item: Int, p: Placement, pxPerMm: Float): Bitmap = synchronized(decoded) {
        val src = sources.getValue(item)
        val needW = ((if (p.rotate) p.dest.h else p.dest.w) * pxPerMm / p.crop.w).toInt().coerceAtLeast(1)
        val needH = ((if (p.rotate) p.dest.w else p.dest.h) * pxPerMm / p.crop.h).toInt().coerceAtLeast(1)
        var sample = src.sampleSizeFor(needW, needH)
        while (src.bytesFor(sample) * 2 > photoBudget && sample < 64) sample *= 2
        decoded[item]?.let { (s, b) -> if (s <= sample && !b.isRecycled) return b }
        decoded.remove(item)?.second?.recycle()
        while (decoded.size >= 3) { val k = decoded.keys.first(); decoded.remove(k)?.second?.recycle() }
        val b = src.decode(sample)
        decoded[item] = sample to b
        return b
    }

    override fun close() {
        synchronized(decoded) { decoded.values.forEach { it.second.recycle() }; decoded.clear() }
        sources.values.forEach { try { it.close() } catch (_: Exception) { } }
        sources.clear()
        for (p in pdfs.values) { try { p.renderer.close() } catch (_: Exception) { }; try { p.pfd.close() } catch (_: Exception) { }; p.tmp?.delete() }
        pdfs.clear()
    }
}

/** Draws sheets with Android's canvas for printing, previews, JPEG and PDF. */
class AndroidSheetRenderer(private val source: PrintSource, private val sheets: List<Sheet>, private val paper: MediaSize) : SheetRenderer {
    private var band: Bitmap? = null

    override fun render(sheet: Int, width: Int, height: Int, y0: Int, rows: Int, out: IntArray) {
        val b = band?.takeIf { it.width == width && it.height >= rows } ?: Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888).also { band?.recycle(); band = it }
        drawSheet(sheet, b, width, y0)
        b.getPixels(out, 0, width, 0, 0, width, rows)
    }

    private fun drawSheet(sheet: Int, b: Bitmap, width: Int, y0: Int) {
        b.eraseColor(Color.WHITE)
        val c = Canvas(b)
        val k = width / paper.widthMm
        for (p in sheets[sheet].placements) source.draw(b, c, p, k, y0)
    }

    /** The whole sheet, [width] pixels wide (for previews). */
    fun image(sheet: Int, width: Int): Bitmap {
        val h = Math.round(width * paper.heightMm / paper.widthMm).coerceAtLeast(1)
        return Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { drawSheet(sheet, it, width, 0) }
    }

    override fun jpeg(sheet: Int, width: Int, height: Int): ByteArray {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            drawSheet(sheet, b, width, 0)
            return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally { b.recycle() }
    }

    override fun pdf(sheets: List<Int>, paper: MediaSize, dpi: Int, out: OutputStream) {
        val doc = PdfDocument()
        val wPt = Math.round(paper.widthMm / 25.4f * 72); val hPt = Math.round(paper.heightMm / 25.4f * 72)
        val px = Math.round(paper.widthMm / 25.4f * minOf(dpi, 200))
        try {
            for ((n, s) in sheets.withIndex()) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, n + 1).create())
                val img = image(s, px)
                page.canvas.drawBitmap(img, null, RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                img.recycle()
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally { doc.close() }
    }

    fun release() { band?.recycle(); band = null }
}
