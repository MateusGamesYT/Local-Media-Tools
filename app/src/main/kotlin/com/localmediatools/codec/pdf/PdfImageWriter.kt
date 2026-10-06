package com.localmediatools.codec.pdf

import com.localmediatools.codec.image.JpegInfo
import com.localmediatools.codec.image.Orientation
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater

/**
 * Streaming PDF writer for documents made of one image per page (images → PDF, scanner).
 *
 * Pages are written as soon as they are added, so memory use does not grow with the page count.
 * JPEG sources are embedded byte-for-byte (DCTDecode) – no recompression – and their EXIF
 * orientation is applied through the page transform, so the page shows the image exactly as it is
 * displayed by a gallery. Other images are embedded losslessly (Flate with PNG predictors), with an
 * optional soft mask for transparency.
 */
class PdfImageWriter(output: OutputStream, private val producer: String = "Local Media Tools") {
    private val out = CountingStream(output)
    private val offsets = HashMap<Int, Long>()
    private var nextObj = 4 // 1 catalog, 2 pages, 3 info
    private val pageObjs = ArrayList<Int>()
    private var finished = false

    init {
        write("%PDF-1.4\n")
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    val pageCount: Int get() = pageObjs.size

    /** Source pixels for a lossless page image, delivered as ARGB rows (top to bottom). */
    interface RowSource {
        val width: Int
        val height: Int
        val hasAlpha: Boolean
        val grayscale: Boolean
        /** Fills [dst] with rows [y, y + count). */
        fun readRows(y: Int, count: Int, dst: IntArray)
    }

    /**
     * Adds a page showing a JPEG stream. [orientation] is the image's EXIF orientation; the image is
     * fitted into the page box minus [margin] (points) preserving its displayed aspect ratio.
     */
    fun addJpegPage(jpeg: () -> InputStream, info: JpegInfo, orientation: Orientation, page: PageBox, margin: Double) {
        require(info.embeddableInPdf) { "JPEG variant cannot be embedded directly" }
        val imageObj = allocate()
        val lengthObj = allocate()
        val decode = if (info.components == 4 && info.hasAdobeSegment) " /Decode [1 0 1 0 1 0 1 0]" else ""
        val cs = when (info.components) { 1 -> "/DeviceGray"; 4 -> "/DeviceCMYK"; else -> "/DeviceRGB" }
        beginObj(imageObj)
        write("<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} /ColorSpace $cs " +
            "/BitsPerComponent 8 /Filter /DCTDecode$decode /Length $lengthObj 0 R >>\nstream\n")
        val start = out.count
        jpeg().use { it.copyTo(out, 1 shl 16) }
        val len = out.count - start
        write("\nendstream\nendobj\n")
        beginObj(lengthObj); write("$len\nendobj\n")
        addPageFor(imageObj, info.width, info.height, orientation, page, margin)
    }

    /** Adds a page with losslessly embedded pixels (already in display orientation). */
    fun addRawPage(src: RowSource, page: PageBox, margin: Double, compressionLevel: Int = 6) {
        val w = src.width; val h = src.height
        val channels = if (src.grayscale) 1 else 3
        val imageObj = allocate()
        val lengthObj = allocate()
        val smaskObj = if (src.hasAlpha) allocate() else -1
        val smaskLenObj = if (src.hasAlpha) allocate() else -1
        val cs = if (channels == 1) "/DeviceGray" else "/DeviceRGB"
        beginObj(imageObj)
        write("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace $cs /BitsPerComponent 8 " +
            "/Filter /FlateDecode /DecodeParms << /Predictor 15 /Colors $channels /BitsPerComponent 8 /Columns $w >>" +
            (if (smaskObj > 0) " /SMask $smaskObj 0 R" else "") + " /Length $lengthObj 0 R >>\nstream\n")
        val start = out.count
        val colorDeflate = DeflateSink(out, compressionLevel)
        val alphaBuffer = if (src.hasAlpha) ByteArrayOutputStream() else null
        val alphaDeflate = alphaBuffer?.let { DeflateSink(it, compressionLevel) }
        val colorFilter = RowFilter(w * channels, channels)
        val alphaFilter = if (src.hasAlpha) RowFilter(w, 1) else null
        val strip = maxOf(1, minOf(h, (4 shl 20) / maxOf(1, w)))
        val px = IntArray(w * strip)
        var y = 0
        while (y < h) {
            val n = minOf(strip, h - y)
            src.readRows(y, n, px)
            for (r in 0 until n) {
                val row = colorFilter.current
                val base = r * w
                if (channels == 1) {
                    for (x in 0 until w) row[x] = px[base + x].toByte()
                } else {
                    var o = 0
                    for (x in 0 until w) {
                        val c = px[base + x]
                        row[o] = (c shr 16).toByte(); row[o + 1] = (c shr 8).toByte(); row[o + 2] = c.toByte(); o += 3
                    }
                }
                colorDeflate.write(colorFilter.filter())
                if (alphaFilter != null) {
                    val arow = alphaFilter.current
                    for (x in 0 until w) arow[x] = (px[base + x] ushr 24).toByte()
                    alphaDeflate!!.write(alphaFilter.filter())
                }
            }
            y += n
        }
        colorDeflate.finish()
        val len = out.count - start
        write("\nendstream\nendobj\n")
        beginObj(lengthObj); write("$len\nendobj\n")
        if (smaskObj > 0) {
            alphaDeflate!!.finish()
            val bytes = alphaBuffer!!.toByteArray()
            beginObj(smaskObj)
            write("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceGray /BitsPerComponent 8 " +
                "/Filter /FlateDecode /DecodeParms << /Predictor 15 /Colors 1 /BitsPerComponent 8 /Columns $w >> " +
                "/Length $smaskLenObj 0 R >>\nstream\n")
            out.write(bytes)
            write("\nendstream\nendobj\n")
            beginObj(smaskLenObj); write("${bytes.size}\nendobj\n")
        }
        addPageFor(imageObj, w, h, Orientation.NORMAL, page, margin)
    }

    private fun addPageFor(imageObj: Int, rawW: Int, rawH: Int, orientation: Orientation, page: PageBox, margin: Double) {
        val cm = placement(rawW, rawH, orientation, page, margin)
        val content = String.format(Locale.US, "q\n%s %s %s %s %s %s cm\n/Im0 Do\nQ\n",
            num(cm[0]), num(cm[1]), num(cm[2]), num(cm[3]), num(cm[4]), num(cm[5]))
        val contentObj = allocate()
        beginObj(contentObj)
        val bytes = content.toByteArray(Charsets.ISO_8859_1)
        write("<< /Length ${bytes.size} >>\nstream\n")
        out.write(bytes)
        write("\nendstream\nendobj\n")
        val pageObj = allocate()
        beginObj(pageObj)
        write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(page.width)} ${num(page.height)}] " +
            "/Resources << /XObject << /Im0 $imageObj 0 R >> /ProcSet [/PDF /ImageB /ImageC] >> /Contents $contentObj 0 R >>\nendobj\n")
        pageObjs.add(pageObj)
    }

    /** Writes the page tree, catalog, info dictionary and cross-reference table. */
    fun finish(title: String? = null, creationDate: String? = null) {
        check(!finished)
        check(pageObjs.isNotEmpty()) { "A PDF needs at least one page" }
        beginObj(2)
        write("<< /Type /Pages /Kids [${pageObjs.joinToString(" ") { "$it 0 R" }}] /Count ${pageObjs.size} >>\nendobj\n")
        beginObj(1)
        write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        beginObj(3)
        val sb = StringBuilder("<< /Producer ${pdfString(producer)}")
        if (title != null) sb.append(" /Title ${pdfString(title)}")
        if (creationDate != null) sb.append(" /CreationDate (D:$creationDate)")
        sb.append(" >>\nendobj\n")
        write(sb.toString())
        val xref = out.count
        val count = nextObj
        val x = StringBuilder()
        x.append("xref\n0 $count\n0000000000 65535 f \n")
        for (i in 1 until count) {
            val off = offsets[i] ?: error("object $i was never written")
            x.append(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        x.append("trailer\n<< /Size $count /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        write(x.toString())
        out.flush()
        finished = true
    }

    private fun allocate() = nextObj++

    private fun beginObj(n: Int) {
        offsets[n] = out.count
        write("$n 0 obj\n")
    }

    private fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

    data class PageBox(val width: Double, val height: Double) {
        companion object {
            val A4_PORTRAIT = PageBox(595.2756, 841.8898)
            val A4_LANDSCAPE = PageBox(841.8898, 595.2756)
        }
    }

    companion object {
        /**
         * PDF `cm` matrix placing a raw image (drawn into the unit square) so that its displayed
         * form is centred in [page] inside [margin] and scaled to fit without distortion.
         */
        fun placement(rawW: Int, rawH: Int, orientation: Orientation, page: PageBox, margin: Double): DoubleArray {
            val dw = orientation.displayWidth(rawW, rawH).toDouble()
            val dh = orientation.displayHeight(rawW, rawH).toDouble()
            val boxW = page.width - 2 * margin
            val boxH = page.height - 2 * margin
            val s = minOf(boxW / dw, boxH / dh)
            val drawW = dw * s; val drawH = dh * s
            val left = (page.width - drawW) / 2
            val bottom = (page.height - drawH) / 2
            val m = orientation.rawToDisplay(rawW, rawH)
            // unit (a, b) -> raw (u, v) = (a*W, (1-b)*H) -> display (X, Y) -> page (left + X*s, bottom + drawH - Y*s)
            fun map(a: Double, b: Double): DoubleArray {
                val u = a * rawW; val v = (1 - b) * rawH
                val X = m[0] * u + m[1] * v + m[4]
                val Y = m[2] * u + m[3] * v + m[5]
                return doubleArrayOf(left + X * s, bottom + drawH - Y * s)
            }
            val p0 = map(0.0, 0.0); val p1 = map(1.0, 0.0); val p2 = map(0.0, 1.0)
            return doubleArrayOf(p1[0] - p0[0], p1[1] - p0[1], p2[0] - p0[0], p2[1] - p0[1], p0[0], p0[1])
        }

        fun num(v: Double): String {
            val r = Math.round(v * 10000.0) / 10000.0
            if (r == Math.rint(r)) return r.toLong().toString()
            return String.format(Locale.US, "%.4f", r).trimEnd('0').trimEnd('.')
        }

        fun pdfString(s: String): String {
            val ascii = s.all { it.code in 32..126 }
            if (ascii) {
                return "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
            }
            // UTF-16BE with BOM as a hex string.
            val sb = StringBuilder("<FEFF")
            for (ch in s) sb.append(String.format(Locale.US, "%04X", ch.code))
            return sb.append(">").toString()
        }
    }

    private class CountingStream(private val base: OutputStream) : OutputStream() {
        var count = 0L; private set
        override fun write(b: Int) { base.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { base.write(b, off, len); count += len }
        override fun flush() = base.flush()
        override fun close() = base.close()
    }

    private class DeflateSink(private val out: OutputStream, level: Int) {
        private val d = Deflater(level.coerceIn(0, 9))
        private val buf = ByteArray(1 shl 16)
        fun write(b: ByteArray) {
            d.setInput(b, 0, b.size)
            while (!d.needsInput()) {
                val n = d.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        fun finish() {
            d.finish()
            while (!d.finished()) {
                val n = d.deflate(buf)
                out.write(buf, 0, n)
            }
            d.end()
        }
    }

    /** PNG-style adaptive row filter used for the Flate predictor. */
    private class RowFilter(private val rowBytes: Int, private val bpp: Int) {
        var current = ByteArray(rowBytes); private set
        private var prev = ByteArray(rowBytes)
        private val cand = Array(5) { ByteArray(rowBytes + 1) }

        fun filter(): ByteArray {
            val row = current; val up = prev
            val c0 = cand[0]; val c1 = cand[1]; val c2 = cand[2]; val c3 = cand[3]; val c4 = cand[4]
            c0[0] = 0; c1[0] = 1; c2[0] = 2; c3[0] = 3; c4[0] = 4
            var s0 = 0L; var s1 = 0L; var s2 = 0L; var s3 = 0L; var s4 = 0L
            for (i in 0 until rowBytes) {
                val x = row[i].toInt() and 0xFF
                val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val b = up[i].toInt() and 0xFF
                val c = if (i >= bpp) up[i - bpp].toInt() and 0xFF else 0
                val p = a + b - c
                val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                val v1 = (x - a) and 0xFF; val v2 = (x - b) and 0xFF
                val v3 = (x - ((a + b) ushr 1)) and 0xFF; val v4 = (x - pred) and 0xFF
                c0[i + 1] = x.toByte(); c1[i + 1] = v1.toByte(); c2[i + 1] = v2.toByte(); c3[i + 1] = v3.toByte(); c4[i + 1] = v4.toByte()
                s0 += if (x < 128) x else 256 - x
                s1 += if (v1 < 128) v1 else 256 - v1
                s2 += if (v2 < 128) v2 else 256 - v2
                s3 += if (v3 < 128) v3 else 256 - v3
                s4 += if (v4 < 128) v4 else 256 - v4
            }
            val sums = longArrayOf(s0, s1, s2, s3, s4)
            var best = 0
            for (t in 1 until 5) if (sums[t] < sums[best]) best = t
            val t = prev; prev = current; current = t
            return cand[best]
        }
    }
}
