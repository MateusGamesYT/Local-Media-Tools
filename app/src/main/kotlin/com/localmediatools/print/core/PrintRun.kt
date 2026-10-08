package com.localmediatools.print.core

import java.io.File
import java.io.OutputStream

/** Draws the sheets being printed. */
interface SheetRenderer {
    /** Rows [y0, y0 + rows) of [sheet] at [width]×[height] pixels into [out] (ARGB, [width] per row). */
    fun render(sheet: Int, width: Int, height: Int, y0: Int, rows: Int, out: IntArray)

    /** The whole sheet as a JPEG, for printers that take no raster. */
    fun jpeg(sheet: Int, width: Int, height: Int): ByteArray = throw UnsupportedOperationException("JPEG isn't available")

    /** All [sheets] as one PDF at [dpi], for printers that take neither raster nor JPEG. */
    fun pdf(sheets: List<Int>, paper: MediaSize, dpi: Int, out: OutputStream): Unit = throw UnsupportedOperationException("PDF isn't available")
}

class PrintSettings(
    val paper: MediaSize,
    val mediaType: String? = null,
    val borderless: Boolean = false,
    val color: Boolean = true,
    /** 3 draft, 4 normal, 5 best. */
    val quality: Int = 4,
    val copies: Int = 1,
    val twoSided: Boolean = false,
    /** Two-sided, turning on the short edge. */
    val shortEdge: Boolean = false,
    /** Mostly photos (the printer may tune its colour for them). */
    val photo: Boolean = false,
    val jobName: String = "Local Media Tools",
)

enum class PrintStage { CHECKING, SENDING, PRINTING, DONE }

fun interface PrintListener { fun update(stage: PrintStage, fraction: Double, text: String) }

class PrintResult(val ok: Boolean, val message: String, val jobIds: List<Int>, val sheets: Int)

class PrintCancelled : RuntimeException("Cancelled")

/**
 * Prints [sheets] sheets drawn by [renderer]: asks the printer to check the job, streams it in the
 * best format the printer takes (PWG or Apple raster, else JPEG per sheet, else PDF), then follows
 * the job until it is printed, telling what holds it up (no paper, jam…).
 */
class PrintRun(
    private val client: IppClient,
    private val caps: PrinterCaps,
    private val settings: PrintSettings,
    private val sheets: Int,
    private val renderer: SheetRenderer,
    private val listener: PrintListener,
    private val cancelled: () -> Boolean = { false },
    /** Where to write the job first for printers that can't take it streamed. */
    private val spoolDir: File? = null,
    private val pollMs: Long = 2000,
    private val watchLimitMs: Long = 30 * 60_000L,
) {
    val format: String = caps.rasterFormat?.mime ?: when {
        caps.jpeg -> "image/jpeg"
        caps.pdf -> "application/pdf"
        else -> throw IppException("This printer doesn't take any kind of file this app can send (it takes ${caps.formats.joinToString().ifEmpty { "nothing it names" }})")
    }
    private val raster: RasterFormat? = caps.rasterFormat

    val dpi: Int = run {
        val list = raster?.let { caps.resolutions(it) } ?: listOf(200, 300, 360)
        when (settings.quality) {
            3 -> list.first()
            5 -> list.filter { it <= 720 }.maxOrNull() ?: list.first()
            else -> list.firstOrNull { it >= 300 } ?: list.last()
        }
    }

    val widthPx: Int = Math.round(settings.paper.widthMm / 25.4f * dpi)
    val heightPx: Int = Math.round(settings.paper.heightMm / 25.4f * dpi)

    /** Copies the printer makes itself, or that are repeated in the job. */
    private val printerCopies = settings.copies > 1 && caps.maxCopies >= settings.copies
    private val streamCopies = if (printerCopies) 1 else settings.copies
    private var dropped = HashSet<String>()

    fun run(): PrintResult {
        listener.update(PrintStage.CHECKING, 0.0, "Checking with the printer")
        check()
        validate()
        val ids = if (format == "image/jpeg") sendJpegs() else listOf(sendOne())
        return watch(ids)
    }

    private fun check() {
        if (cancelled()) throw PrintCancelled()
        if (!caps.acceptingJobs) throw IppException("The printer isn't accepting jobs right now")
    }

    // ------------------------------------------------------------------ requests
    private fun request(op: Int, documentFormat: String = format): IppMessage {
        val m = IppMessage.request(op, client.nextId(), client.address.uri) {
            name("job-name", settings.jobName)
            mime("document-format", documentFormat)
        }
        val job = IppGroup(IppTag.JOB)
        if (printerCopies && "copies" !in dropped) job.integer("copies", settings.copies)
        if ("media-col" !in dropped) job.collection("media-col", collection {
            collection("media-size", collection {
                integer("x-dimension", settings.paper.width); integer("y-dimension", settings.paper.height)
            })
            settings.mediaType?.takeIf { it in caps.mediaTypes }?.let { keyword("media-type", it) }
            if (settings.borderless) { integer("media-bottom-margin", 0); integer("media-left-margin", 0); integer("media-right-margin", 0); integer("media-top-margin", 0) }
        })
        val mode = if (settings.color) "color" else "monochrome"
        if (mode in caps.colorModes && "print-color-mode" !in dropped) job.keyword("print-color-mode", mode)
        if (settings.quality in caps.qualities && "print-quality" !in dropped) job.enum("print-quality", settings.quality)
        if (settings.twoSided && caps.canTwoSided && "sides" !in dropped) job.keyword("sides", if (settings.shortEdge) "two-sided-short-edge" else "two-sided-long-edge")
        if (raster == null && "print-scaling" !in dropped && "fill" in caps.attrs["print-scaling-supported"]?.strings.orEmpty()) job.keyword("print-scaling", "fill")
        if (settings.photo && "photo" in caps.attrs["print-content-optimize-supported"]?.strings.orEmpty() && "print-content-optimize" !in dropped) job.keyword("print-content-optimize", "photo")
        return if (job.attributes.isEmpty()) m else IppMessage(m.code, m.requestId, m.groups + job, m.major, m.minor)
    }

    /** Validate-Job: settings the printer can't do are left out (it would ignore them anyway). */
    private fun validate() {
        repeat(2) {
            val r = try { client.send(request(IppOp.VALIDATE_JOB)) } catch (e: IppException) { if (e.status in 400..599) return else throw e }
            if (r.code == 0x0501 || r.code == 0x0502) return // Validate-Job not implemented
            r.group(IppTag.UNSUPPORTED_GROUP)?.attributes?.forEach { dropped.add(it.name) }
            if (IppStatus.successful(r.code)) return
            if (r.code != IppStatus.CLIENT_ERROR_ATTRIBUTES_OR_VALUES && r.code != IppStatus.OK_IGNORED_OR_SUBSTITUTED) throw IppException(message(r), r.code)
        }
    }

    private fun message(r: IppMessage): String {
        val printerSays = r["status-message"]?.string?.takeIf { it.isNotBlank() }
        return "The printer says " + (printerSays?.let { "\"$it\"" } ?: IppStatus.describe(r.code))
    }

    private fun jobId(r: IppMessage): Int {
        if (!IppStatus.successful(r.code)) throw IppException(message(r), r.code)
        return r.group(IppTag.JOB)?.get("job-id")?.int ?: r["job-id"]?.int ?: throw IppException("The printer accepted the job but gave it no number")
    }

    // ------------------------------------------------------------------ sending
    /** Sheet sides in the document sent. */
    private val total get() = sheets * streamCopies + if (settings.twoSided && sheets % 2 == 1 && streamCopies > 1) streamCopies else 0
    /** Sides the printer will print, copies included. */
    private val printed get() = total * (if (printerCopies) settings.copies else 1)

    private fun sendOne(): Int {
        val doc = object : IppDocument {
            override val length: Long? = null
            override fun writeTo(out: OutputStream) = writeDocument(out)
        }
        val r = try {
            client.send(request(IppOp.PRINT_JOB), doc, readTimeoutMs = 180_000)
        } catch (e: IppException) {
            // Some printers can't take a document of unknown length (they refuse it, or just hang up):
            // write it first, then send it with its length.
            if ((e.status !in setOf(400, 411, 413, 501) && !e.lostWhileSending) || spoolDir == null) throw e
            val f = File.createTempFile("print", ".job", spoolDir)
            try {
                f.outputStream().buffered().use { writeDocument(it) }
                val fileDoc = object : IppDocument {
                    override val length: Long = f.length()
                    override fun writeTo(out: OutputStream) { f.inputStream().use { it.copyTo(out, 64 * 1024) } }
                }
                listener.update(PrintStage.SENDING, 0.9, "Sending to the printer")
                client.send(request(IppOp.PRINT_JOB), fileDoc, readTimeoutMs = 180_000)
            } finally { f.delete() }
        }
        return jobId(r)
    }

    private fun writeDocument(out: OutputStream) {
        if (raster == null) {
            renderer.pdf((0 until sheets).toList().let { s -> (1..streamCopies).flatMap { s } }, settings.paper, dpi, out)
            return
        }
        val w = RasterWriter(out, raster, total)
        val gray = !settings.color
        val color = !gray || !caps.rasterGray(raster)
        val band = (4_000_000 / widthPx).coerceIn(16, heightPx)
        val buf = IntArray(widthPx * band)
        val rowBuf = IntArray(widthPx)
        var side = 0
        val pages = ArrayList<Int>()
        repeat(streamCopies) {
            for (s in 0 until sheets) pages.add(s)
            if (settings.twoSided && sheets % 2 == 1 && streamCopies > 1) pages.add(-1) // blank back, so copies start on a new sheet
        }
        for ((n, s) in pages.withIndex()) {
            if (cancelled()) throw PrintCancelled()
            val back = settings.twoSided && side % 2 == 1
            side++
            val (cross, feed) = if (back) backTransform() else 1 to 1
            w.startPage(RasterPage(widthPx, heightPx, dpi, color, Math.round(settings.paper.widthMm / 25.4f * 72), Math.round(settings.paper.heightMm / 25.4f * 72),
                settings.paper.name, settings.mediaType.orEmpty(), settings.quality, if (settings.photo) "photo" else "",
                settings.twoSided, settings.shortEdge, cross, feed))
            if (s < 0) { w.endPage(); continue }
            val order = if (feed == 1) (0 until heightPx step band).toList() else (0 until heightPx step band).toList().reversed()
            for ((bi, y0) in order.withIndex()) {
                if (cancelled()) throw PrintCancelled()
                val rows = minOf(band, heightPx - y0)
                renderer.render(s, widthPx, heightPx, y0, rows, buf)
                for (k in 0 until rows) {
                    val r = if (feed == 1) k else rows - 1 - k
                    val src = r * widthPx
                    if (cross == 1 && !gray) { w.writeRow(buf, src); continue }
                    for (x in 0 until widthPx) {
                        val c = buf[src + if (cross == 1) x else widthPx - 1 - x]
                        rowBuf[x] = if (gray) grayOf(c) else c
                    }
                    w.writeRow(rowBuf)
                }
                listener.update(PrintStage.SENDING, (n + (bi + 1).toDouble() / order.size) / pages.size * 0.9,
                    if (pages.size > 1) "Sending sheet ${n + 1} of ${pages.size}" else "Sending to the printer")
            }
            w.endPage()
        }
        w.finish()
    }

    /** Back-side turning the printer asks for (CUPS conventions): cross-feed and feed transforms. */
    private fun backTransform(): Pair<Int, Int> = when (caps.sheetBack) {
        "flipped" -> if (settings.shortEdge) -1 to 1 else 1 to -1
        "rotated" -> if (settings.shortEdge) 1 to 1 else -1 to -1
        "manual-tumble" -> if (settings.shortEdge) -1 to -1 else 1 to 1
        else -> 1 to 1
    }

    private fun grayOf(c: Int): Int {
        val y = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114 + 500) / 1000
        return (c and -0x1000000) or (y shl 16) or (y shl 8) or y
    }

    private fun sendJpegs(): List<Int> {
        val ids = ArrayList<Int>()
        val list = (1..streamCopies).flatMap { 0 until sheets }
        for ((n, s) in list.withIndex()) {
            if (cancelled()) throw PrintCancelled()
            listener.update(PrintStage.SENDING, n.toDouble() / list.size * 0.9, if (list.size > 1) "Sending sheet ${n + 1} of ${list.size}" else "Sending to the printer")
            val bytes = renderer.jpeg(s, widthPx, heightPx)
            val doc = object : IppDocument {
                override val length: Long = bytes.size.toLong()
                override fun writeTo(out: OutputStream) = out.write(bytes)
            }
            ids.add(jobId(client.send(request(IppOp.PRINT_JOB), doc, readTimeoutMs = 180_000)))
        }
        return ids
    }

    // ------------------------------------------------------------------ following the job
    private fun watch(ids: List<Int>): PrintResult {
        val start = System.currentTimeMillis()
        val sheetsTotal = printed
        for ((k, id) in ids.withIndex()) {
            while (true) {
                if (cancelled()) { cancel(ids.drop(k)); throw PrintCancelled() }
                if (System.currentTimeMillis() - start > watchLimitMs) return PrintResult(true, "Sent to the printer; it is still working on it", ids, sheetsTotal)
                val r = try {
                    client.send(IppMessage.request(IppOp.GET_JOB_ATTRIBUTES, client.nextId(), client.address.uri) {
                        integer("job-id", id)
                        keyword("requested-attributes", "job-state", "job-state-reasons", "job-state-message", "job-impressions-completed", "job-media-sheets-completed")
                    }, readTimeoutMs = 20_000)
                } catch (e: IppException) {
                    // A printer busy printing may stop answering for a while.
                    Thread.sleep(pollMs); continue
                }
                if (r.code == 0x0406) break // finished and forgotten
                val job = r.group(IppTag.JOB)
                val state = job?.get("job-state")?.int ?: break
                val reasons = job["job-state-reasons"]?.strings.orEmpty()
                when (state) {
                    9 -> break
                    7 -> return PrintResult(false, "The job was cancelled at the printer", ids, sheetsTotal)
                    8 -> return PrintResult(false, "The printer gave up on the job" + (problem()?.let { ": $it" } ?: reasons.firstNotNullOfOrNull { MediaNames.reason(it) }?.let { ": $it" } ?: ""), ids, sheetsTotal)
                }
                val done = job["job-media-sheets-completed"]?.int ?: job["job-impressions-completed"]?.int
                val issue = problem()
                val text = when {
                    issue != null -> "$issue — printing continues once it's sorted"
                    state == 3 || state == 4 -> "Waiting for the printer"
                    done != null && sheetsTotal > 1 -> "Printing sheet ${(done + 1).coerceAtMost(sheetsTotal)} of $sheetsTotal"
                    else -> "Printing"
                }
                val f = 0.9 + 0.1 * ((k + ((done ?: 0).toDouble() / sheetsTotal.coerceAtLeast(1)).coerceAtMost(1.0)) / ids.size)
                listener.update(PrintStage.PRINTING, f, text)
                Thread.sleep(pollMs)
            }
        }
        listener.update(PrintStage.DONE, 1.0, "Printed")
        val copies = if (settings.copies > 1) " (${settings.copies} copies)" else ""
        return PrintResult(true, (if (sheetsTotal == 1) "Printed 1 sheet" else "Printed $sheetsTotal sheets") + copies, ids, sheetsTotal)
    }

    /** What the printer reports as holding things up, in plain words. */
    private fun problem(): String? = try {
        val r = client.send(IppMessage.request(IppOp.GET_PRINTER_ATTRIBUTES, client.nextId(), client.address.uri) {
            keyword("requested-attributes", *PrinterCaps.STATUS)
        }, readTimeoutMs = 20_000)
        PrinterCaps(r).reasons.filter { MediaNames.blocking(it) }.firstNotNullOfOrNull { MediaNames.reason(it) }
    } catch (_: Exception) { null }

    private fun cancel(ids: List<Int>) {
        for (id in ids) try {
            client.send(IppMessage.request(IppOp.CANCEL_JOB, client.nextId(), client.address.uri) { integer("job-id", id) }, readTimeoutMs = 15_000)
        } catch (_: Exception) { }
    }

    /** Cancels jobs already handed to the printer (after [run] was cancelled while following them). */
    fun cancelJobs(ids: List<Int>) = cancel(ids)

    companion object {
        /** Asks a printer what it can do. */
        fun capabilities(client: IppClient): PrinterCaps {
            val r = client.send(IppMessage.request(IppOp.GET_PRINTER_ATTRIBUTES, client.nextId(), client.address.uri) {
                keyword("requested-attributes", "all", "media-col-database")
            }, readTimeoutMs = 20_000)
            if (!IppStatus.successful(r.code)) throw IppException("The printer didn't describe itself (${IppStatus.describe(r.code)})", r.code)
            return PrinterCaps(r)
        }
    }
}
