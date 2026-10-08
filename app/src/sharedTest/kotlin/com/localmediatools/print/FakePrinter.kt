package com.localmediatools.print

import com.localmediatools.print.core.IppGroup
import com.localmediatools.print.core.IppMessage
import com.localmediatools.print.core.IppOp
import com.localmediatools.print.core.IppResolution
import com.localmediatools.print.core.IppTag
import com.localmediatools.print.core.collection
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A simulated network printer speaking IPP over HTTP on 127.0.0.1, describing itself the way an
 * Epson EcoTank L3250 does over IPP (Mopria/AirPrint): PWG and Apple raster at 360 and 720 dpi,
 * JPEG, A4/Letter/photo sizes, borderless photo sizes, plain and photo paper types, four inks.
 * Jobs are kept for checking; job state, paper problems and protocol quirks can be set.
 */
class FakePrinter(
    var formats: List<String> = listOf("application/octet-stream", "image/pwg-raster", "image/urf", "image/jpeg"),
) : AutoCloseable {
    class Job(val id: Int, val request: IppMessage, val format: String, val document: ByteArray, val chunked: Boolean) {
        @Volatile var state = 5
        @Volatile var polls = 0
        val attributes get() = request.group(IppTag.JOB)
    }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val uri: String get() = "ipp://127.0.0.1:$port/ipp/print"

    val jobs = CopyOnWriteArrayList<Job>()
    /** Every request in order: operation code → request. */
    val requests = CopyOnWriteArrayList<IppMessage>()
    @Volatile var reasons: List<String> = listOf("none")
    @Volatile var markerLevels = listOf(62, 48, 35, 80)
    /** Answer "411 Length Required" to streamed (chunked) documents, like some printers do. */
    @Volatile var refuseChunked = false
    /** Polls a job stays "processing" before it completes. */
    @Volatile var processingPolls = 1
    /** Polls a job stays held up (as with no paper) before it continues. */
    @Volatile var stuckPolls = 0
    @Volatile var acceptingJobs = true

    private var nextJob = 100
    @Volatile private var closed = false

    init {
        thread(isDaemon = true, name = "fake-printer") {
            while (!closed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { serve(s) } catch (_: Exception) { } finally { s.close() } }
            }
        }
    }

    override fun close() { closed = true; server.close() }

    private fun serve(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        // No encryption here: like printers without it, hang up on a TLS handshake.
        input.mark(1); if (input.read() == 0x16) return; input.reset()
        val request = line(input) ?: return
        val headers = HashMap<String, String>()
        while (true) { val l = line(input) ?: return; if (l.isEmpty()) break; headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
        require(request.startsWith("POST ")) { "Only POST" }
        val chunked = headers["transfer-encoding"]?.contains("chunked") == true
        if (chunked && refuseChunked) { respond(s, 411, ByteArray(0)); return }
        val body = if (chunked) dechunk(input) else input.readNBytes(headers["content-length"]!!.toInt())
        val msg = IppMessage.decode(body)
        val doc = body.copyOfRange(IppMessage.decodedLength(body), body.size)
        requests.add(msg)
        respond(s, 200, answer(msg, doc, chunked).encode())
    }

    private fun answer(r: IppMessage, doc: ByteArray, chunked: Boolean): IppMessage {
        val op = r.group(IppTag.OPERATION)!!
        fun reply(status: Int, vararg groups: IppGroup) = IppMessage(status, r.requestId, listOf(IppGroup(IppTag.OPERATION).charset("attributes-charset", "utf-8").language("attributes-natural-language", "en")) + groups, 2, 0)
        return when (r.code) {
            IppOp.GET_PRINTER_ATTRIBUTES -> reply(0, printer())
            IppOp.VALIDATE_JOB, IppOp.PRINT_JOB -> {
                val format = op["document-format"]?.string ?: "application/octet-stream"
                if (format !in formats) return reply(0x040A)
                if (!acceptingJobs) return reply(0x0506)
                val unsupported = IppGroup(IppTag.UNSUPPORTED_GROUP)
                r.group(IppTag.JOB)?.attributes?.forEach { if (it.name == "sides" && it.string != "one-sided") unsupported.attributes.add(it) }
                if (r.code == IppOp.VALIDATE_JOB) return if (unsupported.attributes.isEmpty()) reply(0) else reply(1, unsupported)
                val job = Job(nextJob++, r, format, doc, chunked)
                jobs.add(job)
                reply(0, IppGroup(IppTag.JOB).integer("job-id", job.id).uri("job-uri", "$uri/${job.id}").enum("job-state", 3).keyword("job-state-reasons", "none"))
            }
            IppOp.GET_JOB_ATTRIBUTES -> {
                val id = op["job-id"]?.int
                val job = jobs.firstOrNull { it.id == id } ?: return reply(0x0406)
                job.polls++
                if (job.state != 7) job.state = when {
                    job.polls <= stuckPolls -> 5
                    job.polls <= stuckPolls + processingPolls -> 5
                    else -> 9
                }
                if (job.polls > stuckPolls && reasons.any { it.startsWith("media-empty") }) reasons = listOf("none")
                reply(0, IppGroup(IppTag.JOB).integer("job-id", job.id).enum("job-state", job.state)
                    .keyword("job-state-reasons", if (job.state == 9) "job-completed-successfully" else "job-printing")
                    .integer("job-impressions-completed", if (job.state == 9) 1 else 0))
            }
            IppOp.CANCEL_JOB -> {
                jobs.firstOrNull { it.id == op["job-id"]?.int }?.state = 7
                reply(0)
            }
            else -> reply(0x0501)
        }
    }

    private fun printer(): IppGroup {
        val g = IppGroup(IppTag.PRINTER)
        g.uri("printer-uri-supported", uri).keyword("uri-security-supported", "none")
        g.text("printer-info", "EPSON L3250 Series").text("printer-make-and-model", "EPSON L3250 Series").name("printer-name", "EPSON1A2B3C")
        g.text("printer-location", "").uri("printer-uuid", "urn:uuid:cfe92100-67c4-11d4-a45f-e0bb9e1a2b3c")
        g.uri("printer-more-info", "http://127.0.0.1/PRESENTATION/BONJOUR")
        g.enum("printer-state", if (reasons.any { it.endsWith("-error") || it == "media-empty" }) 5 else 3).keyword("printer-state-reasons", *reasons.toTypedArray())
        g.bool("printer-is-accepting-jobs", acceptingJobs)
        g.keyword("ipp-versions-supported", "1.0", "1.1", "2.0")
        g.enum("operations-supported", 0x02, 0x04, 0x08, 0x09, 0x0A, 0x0B)
        g.add("document-format-supported", IppTag.MIME_TYPE, *formats.toTypedArray()).mime("document-format-default", "application/octet-stream")
        g.add("pwg-raster-document-resolution-supported", IppTag.RESOLUTION, IppResolution(360, 360), IppResolution(720, 720))
        g.keyword("pwg-raster-document-type-supported", "sgray_8", "srgb_8").keyword("pwg-raster-document-sheet-back", "normal")
        g.keyword("urf-supported", "CP1", "IS1-7-16", "MT1-3-7-8-10-11-12", "OB10", "PQ3-4-5", "RS360-720", "SRGB24", "W8", "V1.4")
        g.add("printer-resolution-supported", IppTag.RESOLUTION, IppResolution(360, 360), IppResolution(720, 720))
        val sizes = listOf("iso_a4_210x297mm" to (21000 to 29700), "na_letter_8.5x11in" to (21590 to 27940), "na_legal_8.5x14in" to (21590 to 35560),
            "iso_a5_148x210mm" to (14800 to 21000), "iso_a6_105x148mm" to (10500 to 14800), "jis_b5_182x257mm" to (18200 to 25700),
            "na_index-4x6_4x6in" to (10160 to 15240), "na_5x7_5x7in" to (12700 to 17780), "oe_photo-l_3.5x5in" to (8890 to 12700),
            "na_govt-letter_8x10in" to (20320 to 25400), "iso_c6_114x162mm" to (11400 to 16200), "iso_dl_110x220mm" to (11000 to 22000),
            "na_number-10_4.125x9.5in" to (10478 to 24130))
        val borderless = setOf("iso_a4_210x297mm", "na_letter_8.5x11in", "na_index-4x6_4x6in", "na_5x7_5x7in", "oe_photo-l_3.5x5in", "na_govt-letter_8x10in")
        g.keyword("media-supported", *sizes.map { it.first }.toTypedArray()).keyword("media-default", "iso_a4_210x297mm").keyword("media-ready", "iso_a4_210x297mm")
        g.keyword("media-type-supported", "stationery", "photographic-high-gloss", "photographic-glossy", "photographic-semi-gloss", "photographic-matte", "photographic", "envelope")
        g.keyword("media-source-supported", "main")
        fun col(w: Int, h: Int, m: Int, type: String) = collection {
            collection("media-size", collection { integer("x-dimension", w); integer("y-dimension", h) })
            integer("media-bottom-margin", m); integer("media-left-margin", m); integer("media-right-margin", m); integer("media-top-margin", m)
            keyword("media-source", "main"); keyword("media-type", type)
        }
        val db = ArrayList<com.localmediatools.print.core.IppCollection>()
        for ((name, wh) in sizes) {
            db.add(col(wh.first, wh.second, 300, "stationery"))
            if (name in borderless) db.add(col(wh.first, wh.second, 0, "photographic-glossy"))
        }
        g.collection("media-col-database", *db.toTypedArray())
        g.collection("media-col-default", col(21000, 29700, 300, "stationery"))
        g.collection("media-col-ready", col(21000, 29700, 300, "stationery"))
        for (side in listOf("bottom", "left", "right", "top")) g.integer("media-$side-margin-supported", 0, 300)
        g.keyword("print-color-mode-supported", "auto", "color", "monochrome").keyword("print-color-mode-default", "auto")
        g.bool("color-supported", true)
        g.enum("print-quality-supported", 3, 4, 5).enum("print-quality-default", 4)
        g.keyword("sides-supported", "one-sided").keyword("sides-default", "one-sided")
        g.add("copies-supported", IppTag.RANGE, 1..99).integer("copies-default", 1)
        g.keyword("print-scaling-supported", "auto", "auto-fit", "fill", "fit", "none")
        g.keyword("print-content-optimize-supported", "auto", "photo", "graphics", "text", "text-and-graphics")
        g.add("marker-names", IppTag.NAME, "Black", "Cyan", "Magenta", "Yellow")
        g.add("marker-colors", IppTag.NAME, "#000000", "#00FFFF", "#FF00FF", "#FFFF00")
        g.integer("marker-levels", *markerLevels.toIntArray()).integer("marker-low-levels", 10, 10, 10, 10).integer("marker-high-levels", 100, 100, 100, 100)
        g.keyword("marker-types", "ink", "ink", "ink", "ink")
        return g
    }

    private fun respond(s: Socket, status: Int, body: ByteArray) {
        val out = s.getOutputStream()
        out.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: application/ipp\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    private fun line(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) { val c = input.read(); if (c < 0) return if (b.size() == 0) null else b.toString(); if (c == '\n'.code) return b.toString().trimEnd('\r'); b.write(c) }
    }

    private fun dechunk(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val n = line(input)!!.substringBefore(';').trim().toInt(16)
            if (n == 0) { line(input); return out.toByteArray() }
            out.write(input.readNBytes(n)); line(input)
        }
    }
}
