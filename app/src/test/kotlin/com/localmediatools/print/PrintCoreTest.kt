package com.localmediatools.print

import com.localmediatools.print.core.Box
import com.localmediatools.print.core.DnsRecord
import com.localmediatools.print.core.Fit
import com.localmediatools.print.core.IppClient
import com.localmediatools.print.core.IppGroup
import com.localmediatools.print.core.IppMessage
import com.localmediatools.print.core.IppOp
import com.localmediatools.print.core.IppOutOfBand
import com.localmediatools.print.core.IppResolution
import com.localmediatools.print.core.IppTag
import com.localmediatools.print.core.LayoutOptions
import com.localmediatools.print.core.LocalNetwork
import com.localmediatools.print.core.Margins
import com.localmediatools.print.core.Mdns
import com.localmediatools.print.core.MediaSize
import com.localmediatools.print.core.NotLocalException
import com.localmediatools.print.core.PageRef
import com.localmediatools.print.core.PinnedTrust
import com.localmediatools.print.core.PrintCancelled
import com.localmediatools.print.core.PrintRun
import com.localmediatools.print.core.PrintSettings
import com.localmediatools.print.core.PrintStage
import com.localmediatools.print.core.PrinterAddress
import com.localmediatools.print.core.RasterFormat
import com.localmediatools.print.core.RasterPage
import com.localmediatools.print.core.RasterWriter
import com.localmediatools.print.core.Sheet
import com.localmediatools.print.core.SheetLayout
import com.localmediatools.print.core.SheetRenderer
import com.localmediatools.print.core.collection
import com.localmediatools.print.core.matrix
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.util.Random
import javax.imageio.ImageIO

/**
 * Printing without a driver: IPP messages, the local-network rule, finding printers, PWG/Apple raster,
 * sheet layout, and whole jobs of real photos sent to a simulated Epson L3250 (and, when available,
 * to CUPS's reference IPP Everywhere printer, see buildtools/print/cups_check.sh).
 */
class PrintCoreTest {
    // ------------------------------------------------------------------ IPP
    @Test fun ippMessagesSurviveARoundTrip() {
        val m = IppMessage.request(IppOp.PRINT_JOB, 7, "ipp://192.168.1.20:631/ipp/print") {
            name("job-name", "Fotos de família")
            mime("document-format", "image/pwg-raster")
        }
        val job = IppGroup(IppTag.JOB).integer("copies", 2).keyword("sides", "one-sided")
            .add("printer-resolution", IppTag.RESOLUTION, IppResolution(360, 360)).add("copies-supported", IppTag.RANGE, 1..99)
            .collection("media-col", collection {
                collection("media-size", collection { integer("x-dimension", 21000); integer("y-dimension", 29700) })
                keyword("media-type", "photographic-glossy")
                integer("media-top-margin", 0)
            }, collection { keyword("media-type", "stationery") })
            .add("job-hold-until", IppTag.NO_VALUE, IppOutOfBand(IppTag.NO_VALUE))
            .keyword("finishings-names", "none", "staple")
        val msg = IppMessage(m.code, m.requestId, m.groups + job)
        val back = IppMessage.decode(msg.encode() + byteArrayOf(1, 2, 3))
        assertEquals(IppOp.PRINT_JOB, back.code); assertEquals(7, back.requestId)
        assertEquals(msg.encode().size, IppMessage.decodedLength(msg.encode() + byteArrayOf(1, 2, 3)))
        assertEquals("Fotos de família", back["job-name"]?.string)
        val j = back.group(IppTag.JOB)!!
        assertEquals(2, j["copies"]?.int)
        assertEquals(IppResolution(360, 360), j["printer-resolution"]?.values?.single())
        assertEquals(1..99, j["copies-supported"]?.values?.single())
        val cols = j["media-col"]!!.collections
        assertEquals(2, cols.size)
        assertEquals(29700, cols[0].collection("media-size")?.int("y-dimension"))
        assertEquals("photographic-glossy", cols[0].string("media-type"))
        assertEquals(0, cols[0].int("media-top-margin"))
        assertEquals("stationery", cols[1].string("media-type"))
        assertEquals(listOf("none", "staple"), j["finishings-names"]?.strings)
        assertTrue(j["job-hold-until"]?.values?.single() is IppOutOfBand)
        // Byte for byte, the encoding is stable.
        assertArrayEquals(msg.encode(), back.encode())
    }

    // ------------------------------------------------------------------ local network only
    @Test fun onlyLocalNetworkAddressesAreAllowed() {
        for (ok in listOf("192.168.1.37", "10.0.0.5", "172.16.4.2", "172.31.255.1", "169.254.10.1", "127.0.0.1", "fe80::1", "fd12:3456::7", "::1"))
            assertTrue(ok, LocalNetwork.isLocal(LocalNetwork.parseLiteral(ok)!!))
        for (bad in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.64.0.1", "192.169.0.1", "2001:4860:4860::8888", "203.0.113.9"))
            assertFalse(bad, LocalNetwork.isLocal(LocalNetwork.parseLiteral(bad)!!))
        // Names are never looked up (a DNS lookup could reach servers outside the network).
        for (name in listOf("printer.local", "example.com", "localhost", "EPSON1A2B3C")) assertNull(name, LocalNetwork.parseLiteral(name))
        try { PrinterAddress.parse("ipp://example.com/ipp/print"); throw AssertionError("accepted a name") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("IP address")) }
        // Connecting to an internet address fails before any packet is sent.
        val outside = IppClient(PrinterAddress(false, InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)), 631, "/ipp/print"))
        try { outside.send(IppMessage.request(IppOp.GET_PRINTER_ATTRIBUTES, 1, outside.address.uri)); throw AssertionError("sent outside") } catch (_: NotLocalException) { }
        val a = PrinterAddress.parse("ipps://[fe80::1:2]:443/ipp/print")
        assertTrue(a.tls); assertEquals(443, a.port); assertEquals(InetAddress.getByName("fe80::1:2"), a.host); assertTrue(a.uri.startsWith("ipps://[fe80:"))
        assertEquals("ipp://192.168.1.37:631/ipp/print", PrinterAddress.parse("192.168.1.37").uri)
    }

    // ------------------------------------------------------------------ finding printers
    /** A DNS response as an Epson printer sends it: ipp and ipps, TXT, SRV, A (with name compression). */
    private fun announcement(): ByteArray {
        val b = ByteArrayOutputStream(); val o = DataOutputStream(b)
        val offsets = HashMap<String, Int>()
        fun name(n: String) {
            var rest = n
            while (rest.isNotEmpty()) {
                offsets[rest]?.let { o.writeShort(0xC000 or it); return }
                offsets[rest] = b.size()
                val label = rest.substringBefore('.'); val bytes = label.toByteArray()
                o.writeByte(bytes.size); o.write(bytes)
                rest = rest.substringAfter('.', "")
            }
            o.writeByte(0)
        }
        fun rr(n: String, type: Int, data: () -> Unit) {
            name(n); o.writeShort(type); o.writeShort(0x8001); o.writeInt(4500)
            val lenAt = b.size(); o.writeShort(0)
            val start = b.size(); data(); val len = b.size() - start
            val arr = b.toByteArray(); arr[lenAt] = (len shr 8).toByte(); arr[lenAt + 1] = len.toByte()
            b.reset(); b.write(arr)
        }
        o.writeShort(0); o.writeShort(0x8400); o.writeShort(0); o.writeShort(2); o.writeShort(0); o.writeShort(4)
        val inst = "EPSON L3250 Series._ipp._tcp.local"; val instS = "EPSON L3250 Series._ipps._tcp.local"
        rr("_ipp._tcp.local", Mdns.TYPE_PTR) { name(inst) }
        rr("_ipps._tcp.local", Mdns.TYPE_PTR) { name(instS) }
        val txt = listOf("txtvers=1", "ty=EPSON L3250 Series", "rp=ipp/print", "pdl=application/octet-stream,image/pwg-raster,image/urf,image/jpeg",
            "UUID=cfe92100-67c4-11d4-a45f-e0bb9e1a2b3c", "Color=T", "Duplex=F", "URF=CP1,IS1-7-16,MT1-3-7-8-10-11-12,OB10,PQ3-4-5,RS360-720,SRGB24,W8,V1.4")
        rr(inst, Mdns.TYPE_TXT) { for (t in txt) { o.writeByte(t.length); o.write(t.toByteArray()) } }
        rr(inst, Mdns.TYPE_SRV) { o.writeShort(0); o.writeShort(0); o.writeShort(631); name("EPSON1A2B3C.local") }
        rr(instS, Mdns.TYPE_SRV) { o.writeShort(0); o.writeShort(0); o.writeShort(631); name("EPSON1A2B3C.local") }
        rr("EPSON1A2B3C.local", Mdns.TYPE_A) { o.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 37)) }
        return b.toByteArray()
    }

    @Test fun printersAreFoundFromTheirAnnouncements() {
        val q = Mdns.query(listOf(Mdns.IPP, Mdns.IPPS))
        assertEquals(2, ((q[4].toInt() and 0xFF) shl 8) or (q[5].toInt() and 0xFF))
        assertEquals(0x80, q[q.size - 2].toInt() and 0xFF) // asks for a direct (unicast) answer
        val records = Mdns.parse(announcement())
        assertEquals(6, records.size)
        assertEquals("EPSON L3250 Series", (records[2] as DnsRecord.Txt).entries["ty"])
        val found = Mdns.merge(Mdns.printers(records))
        assertEquals(1, found.size)
        val p = found.single()
        assertEquals("EPSON L3250 Series", p.name)
        assertEquals("ipps://192.168.1.37:631/ipp/print", p.address.uri)
        assertEquals("ipp://192.168.1.37:631/ipp/print", p.fallback?.uri)
        assertEquals("cfe92100-67c4-11d4-a45f-e0bb9e1a2b3c", p.uuid)
        // An announcement pointing outside the local network is ignored.
        val bad = announcement(); bad[bad.size - 4] = 8; bad[bad.size - 3] = 8; bad[bad.size - 2] = 8; bad[bad.size - 1] = 8
        assertTrue(Mdns.printers(Mdns.parse(bad)).isEmpty())
        // Garbage doesn't crash the parser.
        val rnd = Random(3)
        repeat(200) { Mdns.parse(ByteArray(rnd.nextInt(80)).also { rnd.nextBytes(it); if (it.size > 3) it[2] = 0x84.toByte() }) }
    }

    // ------------------------------------------------------------------ raster
    private fun pattern(w: Int, h: Int, seed: Int): IntArray {
        val r = Random(seed.toLong())
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                y % 17 < 5 -> 0xFFFFFFFF.toInt()                                     // white bands (repeated rows)
                x < w / 3 -> 0xFF000000.toInt() or (r.nextInt() and 0xFFFFFF)       // noise (literals)
                x < 2 * w / 3 -> 0xFF000000.toInt() or ((x / 7 * 9) shl 16) or (y shl 8 and 0xFF00) // runs
                y % 2 == 0 -> 0x80FF0000.toInt()                                     // half-transparent red on white
                else -> 0xFF102030.toInt()
            }
        }
    }

    @Test fun rasterPagesDecodeToTheSamePixels() {
        for (format in RasterFormat.entries) for (color in listOf(true, false)) for (w in listOf(1, 2, 127, 128, 129, 257, 300)) {
            val h = 41
            val px = pattern(w, h, w)
            val out = ByteArrayOutputStream()
            val wr = RasterWriter(out, format, 2)
            for (page in 0 until 2) {
                wr.startPage(RasterPage(w, h, 360, color, 595, 842, "iso_a4_210x297mm", "stationery", 4))
                for (y in 0 until h) wr.writeRow(px, y * w)
                wr.endPage()
            }
            wr.finish()
            val pages = RasterReader.read(out.toByteArray())
            assertEquals(2, pages.size)
            for (p in pages) {
                assertEquals(w, p.width); assertEquals(h, p.height); assertEquals(360, p.dpi); assertEquals(color, p.color); assertEquals(4, p.quality)
                if (format == RasterFormat.PWG) { assertEquals("iso_a4_210x297mm", p.sizeName); assertEquals(595, p.widthPt) }
                for (i in px.indices) {
                    val c = px[i]; val a = c ushr 24
                    fun ch(v: Int) = if (a == 255) v else (v * a + 255 * (255 - a) + 127) / 255
                    val r = ch((c shr 16) and 255); val g = ch((c shr 8) and 255); val b = ch(c and 255)
                    val expected = if (color) (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        else ((((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114 + 500) / 1000).let { y -> ch(y) }.let { y -> (0xFF shl 24) or (y shl 16) or (y shl 8) or y }
                    if (expected != p.pixels[i]) throw AssertionError("$format color=$color w=$w pixel $i: %08x vs %08x".format(expected, p.pixels[i]))
                }
            }
        }
    }

    @Test fun blankPagesAndLongRunsStayCompact() {
        val out = ByteArrayOutputStream()
        val w = RasterWriter(out, RasterFormat.PWG, 1)
        w.startPage(RasterPage(2976, 4209, 360, true, 595, 842)); w.endPage(); w.finish()
        // A blank A4 page at 360 dpi (37 MB of pixels) takes a few kilobytes.
        assertTrue("${out.size()} bytes", out.size() < 4 + 1796 + 17 * (4209 / 256 + 1) * 30)
        assertTrue(RasterReader.read(out.toByteArray()).single().pixels.all { it == -1 })
    }

    // ------------------------------------------------------------------ layout
    @Test fun photosAndPagesAreLaidOutOnTheSheet() {
        val a4 = MediaSize.A4
        val m = Margins(300, 300, 300, 300)
        // A landscape photo on portrait A4 is turned and fills the printable width.
        val land = PageRef(0, 0, 4000f, 3000f, false)
        val one = SheetLayout.plan(listOf(land), a4, m, LayoutOptions()).single().placements.single()
        assertTrue(one.rotate)
        assertEquals(204f, one.dest.w, 0.01f)                       // 210 − 2×3 mm
        assertEquals(204f * 4 / 3, one.dest.h, 0.01f)
        assertEquals(148.5f, one.dest.y + one.dest.h / 2, 0.01f)    // centred
        // Fill on 10×15 cm borderless: the whole sheet, the photo's middle kept.
        val postcard = MediaSize.fromName("na_index-4x6_4x6in")!!
        val square = PageRef(1, 0, 3000f, 3000f, false)
        val f = SheetLayout.plan(listOf(square), postcard, m, LayoutOptions(photos = Fit.FILL, borderless = true)).single().placements.single()
        assertEquals(Box(0f, 0f, 101.6f, 152.4f), f.dest)
        assertEquals(101.6f / 152.4f, f.crop.w, 0.001f); assertEquals(1f, f.crop.h, 0.001f); assertEquals((1 - 101.6f / 152.4f) / 2, f.crop.x, 0.001f)
        // Four per sheet: a 2 × 2 grid with gaps, each photo inside its cell.
        val four = SheetLayout.plan(List(5) { PageRef(it, 0, 3000f, 4000f, false) }, a4, m, LayoutOptions(perSheet = 4))
        assertEquals(2, four.size); assertEquals(4, four[0].placements.size); assertEquals(1, four[1].placements.size)
        for (p in four[0].placements) { assertTrue(p.dest.x >= p.clip.x - 0.01f && p.dest.right <= p.clip.right + 0.01f && p.dest.bottom <= p.clip.bottom + 0.01f); assertFalse(p.rotate) }
        assertEquals(4f, four[0].placements[1].clip.x - four[0].placements[0].clip.right, 0.01f)
        // A document page at actual size: 210 × 297 mm stays 210 × 297 mm (clipped by the margins).
        val page = PageRef(2, 0, 595.28f, 841.89f, true)
        val actual = SheetLayout.plan(listOf(page), a4, m, LayoutOptions(actualSize = true)).single().placements.single()
        assertEquals(210f, actual.dest.w, 0.05f); assertEquals(297f, actual.dest.h, 0.05f)
        assertEquals(Box(3f, 3f, 204f, 291f), actual.clip)
    }

    @Test fun placementTransformsPutTheCornersInTheirPlace() {
        val k = 360 / 25.4f
        fun map(m: FloatArray, x: Float, y: Float) = (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
        // Not turned, cropped: the crop's corners land on the destination's corners.
        val p = com.localmediatools.print.core.Placement(PageRef(0, 0, 400f, 300f, false), Box(10f, 20f, 100f, 50f), Box(0.25f, 0f, 0.5f, 1f), false, Box(0f, 0f, 210f, 297f))
        val m = p.matrix(400f, 300f, k)
        map(m, 100f, 0f).let { assertEquals(10 * k, it.first, 0.01f); assertEquals(20 * k, it.second, 0.01f) }
        map(m, 300f, 300f).let { assertEquals(110 * k, it.first, 0.01f); assertEquals(70 * k, it.second, 0.01f) }
        // Turned a quarter clockwise: the source's top-left corner ends up top-right.
        val r = p.copy(crop = Box.UNIT, rotate = true, dest = Box(0f, 0f, 30f, 40f)).matrix(400f, 300f, k)
        map(r, 0f, 0f).let { assertEquals(30 * k, it.first, 0.01f); assertEquals(0f, it.second, 0.01f) }
        map(r, 400f, 300f).let { assertEquals(0f, it.first, 0.01f); assertEquals(40 * k, it.second, 0.01f) }
        map(r, 400f, 0f).let { assertEquals(30 * k, it.first, 0.01f); assertEquals(40 * k, it.second, 0.01f) }
    }

    // ------------------------------------------------------------------ whole jobs
    /** Draws sheets with Java2D using the same transforms as the app; remembers every row it handed out. */
    class AwtRenderer(val sheets: List<Sheet>, val images: List<BufferedImage>, val paper: MediaSize) : SheetRenderer {
        val given = HashMap<Int, IntArray>()
        override fun render(sheet: Int, width: Int, height: Int, y0: Int, rows: Int, out: IntArray) {
            val img = BufferedImage(width, rows, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.color = java.awt.Color.WHITE; g.fillRect(0, 0, width, rows)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val k = width / paper.widthMm
            for (p in sheets[sheet].placements) {
                val src = images[p.ref.item]
                val m = p.matrix(src.width.toFloat(), src.height.toFloat(), k)
                g.clip = java.awt.Rectangle((p.clip.x * k).toInt(), (p.clip.y * k).toInt() - y0, (p.clip.w * k).toInt(), (p.clip.h * k).toInt())
                g.transform = AffineTransform(m[0].toDouble(), m[3].toDouble(), m[1].toDouble(), m[4].toDouble(), m[2].toDouble(), (m[5] - y0).toDouble())
                g.drawImage(src, 0, 0, null)
                g.transform = AffineTransform(); g.clip = null
            }
            g.dispose()
            img.getRGB(0, 0, width, rows, out, 0, width)
            val whole = given.getOrPut(sheet) { IntArray(width * height) }
            System.arraycopy(out, 0, whole, y0 * width, rows * width)
        }
        override fun jpeg(sheet: Int, width: Int, height: Int): ByteArray {
            val px = IntArray(width * height); render(sheet, width, height, 0, height, px)
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); img.setRGB(0, 0, width, height, px, 0, width)
            return ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
        }
    }

    private fun photo(name: String): BufferedImage = ImageIO.read(PrintCoreTest::class.java.getResourceAsStream("/people/$name")!!)

    /** Real photos of people (test resources /people): portrait and landscape, with hats and make-up. */
    private val photos by lazy { listOf("02e3d0550a82ef46.jpg", "6c8e99aa5fc6d9b1.jpg", "0aed7fafa7ade4c7.jpg").map { photo(it) } }

    private fun job(printer: FakePrinter, settings: PrintSettings, layout: LayoutOptions, stages: MutableList<String> = ArrayList(), cancelAt: String? = null, spool: File? = null): Pair<AwtRenderer, Result<com.localmediatools.print.core.PrintResult>> {
        val client = IppClient(PrinterAddress.parse(printer.uri))
        val caps = PrintRun.capabilities(client)
        val refs = photos.mapIndexed { i, p -> PageRef(i, 0, p.width.toFloat(), p.height.toFloat(), false) }
        val sheets = SheetLayout.plan(refs, settings.paper, if (settings.borderless) Margins.NONE else caps.margins, layout)
        val renderer = AwtRenderer(sheets, photos, settings.paper)
        var cancel = false
        val run = PrintRun(client, caps, settings, sheets.size, renderer, { _, _, text -> stages.add(text); if (cancelAt != null && text.startsWith(cancelAt)) cancel = true },
            cancelled = { cancel }, spoolDir = spool, pollMs = 10)
        return renderer to runCatching { run.run() }
    }

    @Test fun realPhotosPrintOnTheSimulatedEpson() {
        FakePrinter().use { printer ->
            val stages = ArrayList<String>()
            val (renderer, result) = job(printer, PrintSettings(MediaSize.A4, "stationery", quality = 4, copies = 2, photo = true, jobName = "Holiday"), LayoutOptions(perSheet = 2), stages)
            val r = result.getOrThrow()
            assertTrue(r.message, r.ok); assertEquals("Printed 4 sheets (2 copies)", r.message)
            val job = printer.jobs.single()
            assertEquals("image/pwg-raster", job.format); assertTrue(job.chunked)
            val attrs = job.attributes!!
            assertEquals(2, attrs["copies"]?.int)
            assertEquals(21000, attrs["media-col"]!!.collections.single().collection("media-size")?.int("x-dimension"))
            assertEquals("stationery", attrs["media-col"]!!.collections.single().string("media-type"))
            assertEquals("color", attrs["print-color-mode"]?.string); assertEquals(4, attrs["print-quality"]?.int)
            assertEquals("Holiday", job.request["job-name"]?.string)
            // Validate-Job came first.
            assertEquals(IppOp.VALIDATE_JOB, printer.requests.first { it.code != IppOp.GET_PRINTER_ATTRIBUTES }.code)
            // Two A4 sheets at 360 dpi, exactly the pixels that were drawn.
            val pages = RasterReader.read(job.document)
            assertEquals(2, pages.size)
            for ((i, p) in pages.withIndex()) {
                assertEquals(2976, p.width); assertEquals(4209, p.height); assertEquals(360, p.dpi); assertTrue(p.color)
                assertEquals("iso_a4_210x297mm", p.sizeName)
                assertArrayEquals(renderer.given[i]!!.map { it or (0xFF shl 24) }.toIntArray(), p.pixels)
            }
            // The photos really are on the page: the middle of the first photo's place isn't white.
            val first = SheetLayout.plan(photos.mapIndexed { i, p -> PageRef(i, 0, p.width.toFloat(), p.height.toFloat(), false) }, MediaSize.A4, Margins(300, 300, 300, 300), LayoutOptions(perSheet = 2))[0].placements[0]
            val k = 360 / 25.4f
            assertTrue(pages[0].at(((first.dest.x + first.dest.w / 2) * k).toInt(), ((first.dest.y + first.dest.h / 2) * k).toInt()) != -1)
            assertEquals(-1, pages[0].at(5, 5))                                   // the margin stays white
            assertTrue(stages.any { it.startsWith("Sending sheet 1 of 2") }); assertEquals("Printed", stages.last())
        }
    }

    @Test fun grayscaleBorderlessAndQualityReachThePrinter() {
        FakePrinter().use { printer ->
            val postcard = MediaSize.fromName("na_index-4x6_4x6in")!!
            val (_, result) = job(printer, PrintSettings(postcard, "photographic-glossy", borderless = true, color = false, quality = 5), LayoutOptions(photos = Fit.FILL, borderless = true))
            assertTrue(result.getOrThrow().ok)
            val job = printer.jobs.single()
            val col = job.attributes!!["media-col"]!!.collections.single()
            assertEquals(0, col.int("media-top-margin")); assertEquals(0, col.int("media-left-margin"))
            assertEquals("photographic-glossy", col.string("media-type"))
            assertEquals("monochrome", job.attributes!!["print-color-mode"]?.string); assertEquals(5, job.attributes!!["print-quality"]?.int)
            val pages = RasterReader.read(job.document)
            assertEquals(3, pages.size)
            for (p in pages) {
                assertFalse(p.color); assertEquals(720, p.dpi)                    // best quality: the printer's 720 dpi
                assertEquals(Math.round(101.6f / 25.4f * 720), p.width)
                // Borderless fill: no white edge.
                assertTrue(p.at(0, 0) != -1 || p.at(1, p.height / 2) != -1)
            }
        }
    }

    @Test fun printersThatCantTakeStreamsOrRasterStillPrint() {
        // Refuses documents of unknown length: written to a file first.
        FakePrinter().use { printer ->
            printer.refuseChunked = true
            val dir = createTempDir()
            val (_, result) = job(printer, PrintSettings(MediaSize.A4), LayoutOptions(perSheet = 4), spool = dir)
            assertTrue(result.getOrThrow().ok)
            assertFalse(printer.jobs.single().chunked)
            assertEquals(1, RasterReader.read(printer.jobs.single().document).size)
            assertTrue(dir.listFiles()!!.isEmpty())
            dir.deleteRecursively()
        }
        // AirPrint-only: Apple raster.
        FakePrinter(listOf("image/urf", "image/jpeg")).use { printer ->
            assertTrue(job(printer, PrintSettings(MediaSize.A4), LayoutOptions()).second.getOrThrow().ok)
            assertEquals("image/urf", printer.jobs.single().format)
            assertEquals(3, RasterReader.read(printer.jobs.single().document).size)
        }
        // JPEG only: one job per sheet, scaled to fill.
        FakePrinter(listOf("image/jpeg")).use { printer ->
            assertTrue(job(printer, PrintSettings(MediaSize.A4), LayoutOptions(perSheet = 2)).second.getOrThrow().ok)
            assertEquals(2, printer.jobs.size)
            assertTrue(printer.jobs.all { it.format == "image/jpeg" && it.attributes!!["print-scaling"]?.string == "fill" })
            assertNotNull(ImageIO.read(printer.jobs[0].document.inputStream()))
        }
    }

    @Test fun paperProblemsAreExplainedAndJobsCanBeCancelled() {
        FakePrinter().use { printer ->
            printer.stuckPolls = 2
            printer.reasons = listOf("media-empty-error")
            val stages = ArrayList<String>()
            val (_, result) = job(printer, PrintSettings(MediaSize.A4), LayoutOptions(perSheet = 4), stages)
            assertTrue(result.getOrThrow().ok)
            assertTrue(stages.toString(), stages.any { it.startsWith("Add paper") })
        }
        FakePrinter().use { printer ->
            printer.processingPolls = 1000
            val (_, result) = job(printer, PrintSettings(MediaSize.A4), LayoutOptions(perSheet = 4), cancelAt = "Printing")
            assertTrue(result.exceptionOrNull() is PrintCancelled)
            assertEquals(7, printer.jobs.single().state)
            assertTrue(printer.requests.any { it.code == IppOp.CANCEL_JOB })
        }
        FakePrinter().use { printer ->
            printer.acceptingJobs = false
            val e = job(printer, PrintSettings(MediaSize.A4), LayoutOptions()).second.exceptionOrNull()
            assertTrue("$e", e?.message?.contains("isn't accepting jobs") == true)
        }
    }

    @Test fun printerCapabilitiesReadLikeAnEpson() {
        FakePrinter().use { printer ->
            val caps = PrintRun.capabilities(IppClient(PrinterAddress.parse(printer.uri)))
            assertEquals("EPSON L3250 Series", caps.name)
            assertEquals(RasterFormat.PWG, caps.rasterFormat)
            assertEquals(listOf(360, 720), caps.resolutions(RasterFormat.PWG))
            assertEquals(listOf(360, 720), caps.resolutions(RasterFormat.URF))
            val labels = caps.sizes.map { it.label }
            assertTrue(labels.toString(), labels.containsAll(listOf("A4", "Letter", "10 × 15 cm (4 × 6 in)", "13 × 18 cm (5 × 7 in)", "9 × 13 cm (L)", "Envelope DL")))
            assertEquals("A4", caps.defaultSize?.label)
            assertTrue(caps.borderless(MediaSize.fromName("na_index-4x6_4x6in")!!)); assertFalse(caps.borderless(MediaSize.fromName("iso_a5_148x210mm")!!))
            assertEquals(Margins(300, 300, 300, 300), caps.margins)
            assertEquals(listOf("Black", "Cyan", "Magenta", "Yellow"), caps.markers.map { it.name })
            assertEquals(35, caps.markers[2].level); assertEquals("#FF00FF", caps.markers[2].color)
            assertTrue(caps.canColor); assertTrue(caps.canMonochrome); assertFalse(caps.canTwoSided)
            assertEquals(99, caps.maxCopies)
            assertEquals("Glossy photo paper", com.localmediatools.print.core.MediaNames.mediaType("photographic-glossy"))
        }
    }

    // ------------------------------------------------------------------ CUPS's reference printer
    /**
     * Prints to CUPS's ippeveprinter (an independent IPP Everywhere implementation) when
     * buildtools/print/cups_check.sh started one: plain and TLS, then checks the files it kept.
     */
    @Test fun printsToCupsReferencePrinter() {
        val uri = System.getProperty("lmt.ippeve")
        Assume.assumeTrue("set by buildtools/print/cups_check.sh", uri != null)
        val spool = File(System.getProperty("lmt.ippeve.spool")!!)
        for (tls in listOf(false, true)) {
            val address = PrinterAddress.parse(if (tls) uri!!.replace("ipp://", "ipps://") else uri!!)
            val trust = PinnedTrust(null)
            val client = IppClient(address, trust)
            val caps = PrintRun.capabilities(client)
            if (tls) assertNotNull("the certificate is pinned on first use", trust.pin)
            val before = spool.listFiles()!!.toSet()
            val refs = photos.mapIndexed { i, p -> PageRef(i, 0, p.width.toFloat(), p.height.toFloat(), false) }
            val paper = caps.sizes.first { it.label == "A4" }
            val sheets = SheetLayout.plan(refs, paper, caps.margins, LayoutOptions(perSheet = 2))
            val renderer = AwtRenderer(sheets, photos, paper)
            val result = PrintRun(client, caps, PrintSettings(paper, quality = 3), sheets.size, renderer, { _, _, _ -> }, pollMs = 200).run()
            assertTrue(result.message, result.ok)
            val kept = (spool.listFiles()!!.toSet() - before).single { it.length() > 0 }
            val pages = RasterReader.read(kept.readBytes())
            assertEquals(2, pages.size)
            assertArrayEquals(renderer.given[0]!!.map { it or (0xFF shl 24) }.toIntArray(), pages[0].pixels)
            println("ippeveprinter (${if (tls) "ipps" else "ipp"}): ${caps.rasterFormat} ${pages[0].width}×${pages[0].height} at ${pages[0].dpi} dpi → ${kept.name} (${kept.length()} bytes)")
        }
        // A changed certificate is refused.
        val client = IppClient(PrinterAddress.parse(uri!!.replace("ipp://", "ipps://")), PinnedTrust("00".repeat(32)))
        val e = runCatching { PrintRun.capabilities(client) }.exceptionOrNull()
        assertTrue("$e", e?.message?.contains("identity changed") == true || e?.cause?.message?.contains("identity changed") == true)
    }

    private fun createTempDir(): File = File.createTempFile("spool", "").let { it.delete(); it.mkdirs(); it }
}
