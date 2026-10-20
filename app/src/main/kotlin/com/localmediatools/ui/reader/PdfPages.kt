package com.localmediatools.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.localmediatools.docs.core.UnreadableException
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** Thrown when a PDF needs a password ([wrong]: one was given and didn't open it). */
class PasswordNeeded(val wrong: Boolean) : Exception()

object PdfOpen {
    /**
     * Pages of the PDF [file], drawn by Android (fast, and the same engine as the system's viewer),
     * or by PDFBox when Android can't open it. A password-protected PDF is opened with [password]
     * and kept as an unlocked copy in [work] while it is shown (deleted on close).
     */
    fun open(ctx: Context, file: File, password: String?, work: File): Pair<PageSource, PageSearch> {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        var shown = file
        var temp: File? = null
        val android = try { AndroidPdfPages(file) } catch (e: SecurityException) { null } catch (e: Throwable) { null }
        if (android != null) return android to PdfText(file, null, null)
        // Encrypted (or unreadable for Android): PDFBox opens it and, if locked, saves an unlocked copy.
        val doc = try {
            PDDocument.load(file, password ?: "", MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw PasswordNeeded(wrong = password != null)
        } catch (e: java.io.IOException) {
            throw UnreadableException(tr("The file is damaged: its pages can't be read."))
        }
        try {
            if (doc.isEncrypted) {
                doc.isAllSecurityToBeRemoved = true
                temp = File(work, "unlocked-${System.nanoTime()}.pdf")
                doc.save(temp)
                doc.close()
                shown = temp
                val unlocked = try { AndroidPdfPages(temp) } catch (_: Throwable) { null }
                if (unlocked != null) return unlocked.also { it.temp = temp } to PdfText(temp, null, null)
                val d2 = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
                return BoxPdfPages(d2, temp) to PdfText(temp, null, null)
            }
            return BoxPdfPages(doc, null) to PdfText(shown, password, null)
        } catch (e: Throwable) {
            try { doc.close() } catch (_: Exception) { }
            temp?.delete()
            if (e is UnreadableException) throw e
            throw UnreadableException(tr("The file is damaged: its pages can't be read."))
        }
    }
}

/** PDF pages drawn by Android's PdfRenderer (one page open at a time, on the render thread). */
private class AndroidPdfPages(file: File) : PageSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(pfd) } catch (e: Throwable) { pfd.close(); throw e }
    private val sizes: FloatArray
    var temp: File? = null

    init {
        val n = renderer.pageCount
        sizes = FloatArray(n * 2)
        for (i in 0 until n) renderer.openPage(i).use { p -> sizes[2 * i] = p.width.toFloat().coerceAtLeast(1f); sizes[2 * i + 1] = p.height.toFloat().coerceAtLeast(1f) }
    }

    override val count get() = sizes.size / 2
    override fun widthPt(i: Int) = sizes[2 * i]
    override fun heightPt(i: Int) = sizes[2 * i + 1]

    override fun render(i: Int, bitmap: Bitmap, scale: Float, dx: Float, dy: Float) {
        renderer.openPage(i).use { p ->
            val m = Matrix().apply { setScale(scale, scale); postTranslate(-dx, -dy) }
            p.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() {
        try { renderer.close() } catch (_: Exception) { }
        try { pfd.close() } catch (_: Exception) { }
        temp?.delete()
    }
}

/** PDF pages drawn by PDFBox, for files Android's renderer refuses. Slower, but reads more damaged files. */
private class BoxPdfPages(private val doc: PDDocument, private val temp: File?) : PageSource {
    private val renderer = PDFRenderer(doc)
    private val sizes: FloatArray

    init {
        val n = doc.numberOfPages
        sizes = FloatArray(n * 2)
        for (i in 0 until n) {
            val p = doc.getPage(i)
            val box = p.cropBox
            val turned = (p.rotation % 180) != 0
            sizes[2 * i] = (if (turned) box.height else box.width).coerceAtLeast(1f)
            sizes[2 * i + 1] = (if (turned) box.width else box.height).coerceAtLeast(1f)
        }
    }

    override val count get() = sizes.size / 2
    override fun widthPt(i: Int) = sizes[2 * i]
    override fun heightPt(i: Int) = sizes[2 * i + 1]

    override fun render(i: Int, bitmap: Bitmap, scale: Float, dx: Float, dy: Float) {
        // The whole page as PDFBox draws it (on white), at most about 24 million pixels; sharper parts of
        // very large zooms are scaled up from that.
        val pixels = widthPt(i) * heightPt(i) * scale * scale
        val s = if (pixels > 24e6f) scale * Math.sqrt(24e6 / pixels).toFloat() else scale
        val page = renderer.renderImage(i, s, com.tom_roush.pdfbox.rendering.ImageType.RGB)
        val c = Canvas(bitmap)
        c.translate(-dx, -dy)
        c.scale(scale / s, scale / s)
        c.drawBitmap(page, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        page.recycle()
    }

    override fun close() {
        try { doc.close() } catch (_: Exception) { }
        temp?.delete()
    }
}

/**
 * The text of a PDF's pages with where each letter is, read by PDFBox the first time someone
 * searches (page by page, so the first results come quickly), plus its bookmarks.
 */
class PdfText(private val file: File, private val password: String?, private val ownTemp: File?) : PageSearch {
    private class PageText(val text: String, val boxes: FloatArray)
    private var doc: PDDocument? = null
    private val pages = HashMap<Int, PageText>()
    private var failed = false

    @Synchronized private fun document(): PDDocument? {
        if (failed) return null
        doc?.let { return it }
        return try {
            PDDocument.load(file, password ?: "", MemoryUsageSetting.setupTempFileOnly()).also { doc = it }
        } catch (_: Throwable) { failed = true; null }
    }

    private fun page(d: PDDocument, i: Int): PageText {
        synchronized(pages) { pages[i]?.let { return it } }
        val sb = StringBuilder()
        val boxes = ArrayList<Float>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                for (tp in positions) {
                    val u = tp.unicode ?: continue
                    val h = maxOf(tp.height, tp.fontSizeInPt * 0.75f, 2f)
                    for (ch in u) {
                        sb.append(ch)
                        boxes.add(tp.x); boxes.add(tp.y - h); boxes.add(tp.x + tp.width); boxes.add(tp.y + h * 0.2f)
                    }
                }
            }
            override fun writeWordSeparator() { sb.append(' '); repeat(4) { boxes.add(Float.NaN) } }
            override fun writeLineSeparator() { sb.append(' '); repeat(4) { boxes.add(Float.NaN) } }
            override fun writeParagraphSeparator() {}
        }
        stripper.sortByPosition = true
        stripper.startPage = i + 1; stripper.endPage = i + 1
        val t = try { stripper.getText(d); PageText(sb.toString(), boxes.toFloatArray()) } catch (_: Throwable) { PageText("", FloatArray(0)) }
        synchronized(pages) { pages[i] = t }
        return t
    }

    override fun find(query: String, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): List<PageHit> {
        val d = document() ?: return emptyList()
        val n = d.numberOfPages
        val out = ArrayList<PageHit>()
        val q = query.replace(Regex("\\s+"), " ").trim()
        for (i in 0 until n) {
            if (cancelled()) break
            val pt = synchronized(d) { page(d, i) }
            for (r in RichText.find(pt.text, q, I18n.locale)) {
                // One box per line the hit covers.
                val rects = ArrayList<RectF>()
                var cur: RectF? = null
                for (k in r) {
                    val b = k * 4
                    if (b + 3 >= pt.boxes.size || pt.boxes[b].isNaN()) continue
                    val l = pt.boxes[b]; val t = pt.boxes[b + 1]; val rr = pt.boxes[b + 2]; val bb = pt.boxes[b + 3]
                    val c = cur
                    if (c != null && Math.abs(c.top - t) < (bb - t) * 0.5f) c.union(l, t, rr, bb)
                    else RectF(l, t, rr, bb).also { cur = it; rects.add(it) }
                }
                out.add(PageHit(i, rects))
            }
            if (i % 5 == 4 || i == n - 1) progress(i + 1, n)
        }
        return out
    }

    override fun outline(): List<Pair<String, Int>> {
        val d = document() ?: return emptyList()
        return synchronized(d) {
            val out = ArrayList<Pair<String, Int>>()
            fun walk(node: PDOutlineNode, depth: Int) {
                var item = node.firstChild
                var guard = 0
                while (item != null && out.size < 3000 && guard++ < 5000) {
                    val page = try { item.findDestinationPage(d)?.let { p -> d.pages.indexOf(p) } ?: -1 } catch (_: Throwable) { -1 }
                    val title = item.title?.trim().orEmpty()
                    if (page >= 0 && title.isNotEmpty()) out.add(("  ".repeat(depth.coerceAtMost(4)) + title.take(120)) to page)
                    if (depth < 8) walk(item, depth + 1)
                    item = item.nextSibling
                }
            }
            try { d.documentCatalog.documentOutline?.let { walk(it, 0) } } catch (_: Throwable) { }
            out
        }
    }

    /** All the text of page [i] (for copying). */
    fun pageText(i: Int): String = document()?.let { d -> synchronized(d) { page(d, i).text } } ?: ""

    override fun close() {
        try { doc?.close() } catch (_: Exception) { }
        doc = null
        ownTemp?.let { if (it.exists() && !it.delete()) it.deleteOnExit() }
    }
}
