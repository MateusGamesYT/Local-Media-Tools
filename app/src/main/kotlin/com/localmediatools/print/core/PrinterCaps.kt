package com.localmediatools.print.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** A paper size in hundredths of a millimetre (IPP units), portrait. */
data class MediaSize(val name: String, val width: Int, val height: Int) {
    val widthMm get() = width / 100f
    val heightMm get() = height / 100f
    val label: String get() = MediaNames.size(this)
    fun same(o: MediaSize) = abs(width - o.width) <= 100 && abs(height - o.height) <= 100

    companion object {
        /** From a PWG self-describing name such as iso_a4_210x297mm or na_index-4x6_4x6in. */
        fun fromName(name: String): MediaSize? {
            if (name.startsWith("custom_")) return null
            val dims = name.substringAfterLast('_')
            val unit = when { dims.endsWith("mm") -> 100f; dims.endsWith("in") -> 2540f; else -> return null }
            val wh = dims.dropLast(2).split('x')
            if (wh.size != 2) return null
            val w = wh[0].toFloatOrNull() ?: return null; val h = wh[1].toFloatOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            return MediaSize(name, (minOf(w, h) * unit).roundToInt(), (maxOf(w, h) * unit).roundToInt())
        }

        val A4 = MediaSize("iso_a4_210x297mm", 21000, 29700)
        val LETTER = MediaSize("na_letter_8.5x11in", 21590, 27940)
    }
}

/** Margins in hundredths of a millimetre. */
data class Margins(val top: Int, val bottom: Int, val left: Int, val right: Int) {
    val none get() = top == 0 && bottom == 0 && left == 0 && right == 0
    companion object { val NONE = Margins(0, 0, 0, 0) }
}

/** An ink or toner supply as the printer reports it. [level] is a percentage, or negative when not known. */
data class Marker(val name: String, val color: String?, val level: Int, val lowLevel: Int, val type: String?) {
    val known get() = level in 0..100
    val low get() = known && level <= maxOf(lowLevel, 0)
}

enum class PrinterState { IDLE, PROCESSING, STOPPED, UNKNOWN }

/** What a printer said about itself (Get-Printer-Attributes). */
class PrinterCaps(val attrs: IppMessage) {
    private fun a(name: String) = attrs.group(IppTag.PRINTER)?.get(name) ?: attrs[name]
    private fun strings(name: String) = a(name)?.strings.orEmpty()
    private fun ints(name: String) = a(name)?.ints.orEmpty()

    val name: String get() = a("printer-info")?.string?.takeIf { it.isNotBlank() } ?: a("printer-make-and-model")?.string ?: a("printer-name")?.string ?: "Printer"
    val makeAndModel: String? get() = a("printer-make-and-model")?.string
    val location: String? get() = a("printer-location")?.string?.takeIf { it.isNotBlank() }
    val uuid: String? get() = a("printer-uuid")?.string?.lowercase()?.removePrefix("urn:uuid:")
    val adminUrl: String? get() = a("printer-more-info")?.string

    val state: PrinterState get() = when (a("printer-state")?.int) { 3 -> PrinterState.IDLE; 4 -> PrinterState.PROCESSING; 5 -> PrinterState.STOPPED; else -> PrinterState.UNKNOWN }
    /** printer-state-reasons without "none". */
    val reasons: List<String> get() = strings("printer-state-reasons").filter { it != "none" }
    val stateMessage: String? get() = a("printer-state-message")?.string?.takeIf { it.isNotBlank() }
    val acceptingJobs: Boolean get() = a("printer-is-accepting-jobs")?.bool ?: true

    val formats: List<String> get() = strings("document-format-supported").map { it.lowercase() }
    /** The raster format to send, if the printer takes one (PWG preferred). */
    val rasterFormat: RasterFormat? get() = when {
        "image/pwg-raster" in formats -> RasterFormat.PWG
        "image/urf" in formats -> RasterFormat.URF
        else -> null
    }
    val jpeg: Boolean get() = "image/jpeg" in formats
    val pdf: Boolean get() = "application/pdf" in formats

    /** Raster resolutions (dpi) for [format]. */
    fun resolutions(format: RasterFormat): List<Int> = when (format) {
        RasterFormat.PWG -> a("pwg-raster-document-resolution-supported")?.values?.filterIsInstance<IppResolution>()?.map { it.dpiX }.orEmpty()
        RasterFormat.URF -> urf.firstOrNull { it.startsWith("RS") }?.removePrefix("RS")?.split('-')?.mapNotNull { it.toIntOrNull() }.orEmpty()
    }.ifEmpty { a("printer-resolution-supported")?.values?.filterIsInstance<IppResolution>()?.map { it.dpiX }.orEmpty() }
        .ifEmpty { listOf(300) }.distinct().sorted()

    private val urf: List<String> get() = strings("urf-supported").map { it.uppercase() }

    /** Whether [format] can carry colour pages / grayscale pages. */
    fun rasterColor(format: RasterFormat): Boolean = when (format) {
        RasterFormat.PWG -> strings("pwg-raster-document-type-supported").let { t -> t.isEmpty() || t.any { it.startsWith("srgb") || it.startsWith("rgb") } }
        RasterFormat.URF -> urf.isEmpty() || urf.any { it.startsWith("SRGB") }
    }
    fun rasterGray(format: RasterFormat): Boolean = when (format) {
        RasterFormat.PWG -> strings("pwg-raster-document-type-supported").let { t -> t.isEmpty() || t.any { it.startsWith("sgray") || it.startsWith("black") } }
        RasterFormat.URF -> urf.isEmpty() || urf.any { it.startsWith("W8") }
    }
    /** How the back of a two-sided sheet must be turned in raster (normal, flipped, rotated, manual-tumble). */
    val sheetBack: String get() = a("pwg-raster-document-sheet-back")?.string ?: if (urf.any { it == "DM1" }) "normal" else if (urf.any { it == "DM2" }) "flipped" else if (urf.any { it == "DM3" }) "rotated" else if (urf.any { it == "DM4" }) "manual-tumble" else "normal"

    /** Paper sizes the printer offers (fixed sizes only), in its order. */
    val sizes: List<MediaSize> get() {
        val out = ArrayList<MediaSize>()
        fun add(m: MediaSize) { if (out.none { it.same(m) }) out.add(m) }
        strings("media-supported").mapNotNull { MediaSize.fromName(it) }.forEach { add(it) }
        for (c in a("media-col-database")?.collections.orEmpty()) {
            val s = c.collection("media-size") ?: continue
            val w = s.int("x-dimension") ?: continue; val h = s.int("y-dimension") ?: continue
            add(MediaSize(MediaNames.nameFor(minOf(w, h), maxOf(w, h)), minOf(w, h), maxOf(w, h)))
        }
        return out.ifEmpty { listOf(MediaSize.A4, MediaSize.LETTER) }
    }

    /** The printer's default size (what is loaded, if it says). */
    val defaultSize: MediaSize? get() {
        val ready = a("media-col-ready")?.collections?.firstNotNullOfOrNull { sizeOf(it) }
            ?: strings("media-ready").firstNotNullOfOrNull { MediaSize.fromName(it) }
        val dflt = a("media-col-default")?.collections?.firstNotNullOfOrNull { sizeOf(it) } ?: a("media-default")?.string?.let { MediaSize.fromName(it) }
        val pick = ready ?: dflt ?: return null
        return sizes.firstOrNull { it.same(pick) } ?: pick
    }

    private fun sizeOf(c: IppCollection): MediaSize? {
        val s = c.collection("media-size") ?: return null
        val w = s.int("x-dimension") ?: return null; val h = s.int("y-dimension") ?: return null
        return MediaSize(MediaNames.nameFor(minOf(w, h), maxOf(w, h)), minOf(w, h), maxOf(w, h))
    }

    val mediaTypes: List<String> get() = strings("media-type-supported").ifEmpty {
        a("media-col-database")?.collections.orEmpty().mapNotNull { it.string("media-type") }.distinct()
    }
    val defaultMediaType: String? get() = a("media-col-default")?.collections?.firstOrNull()?.string("media-type") ?: a("media-type-default")?.string

    /** The margins the printer uses by default (its smallest non-zero ones). */
    val margins: Margins get() {
        fun pick(name: String): Int {
            val d = a("media-col-default")?.collections?.firstOrNull()?.int(name.removeSuffix("-supported"))
            if (d != null && d > 0) return d
            return ints(name).filter { it > 0 }.minOrNull() ?: 300
        }
        return Margins(pick("media-top-margin-supported"), pick("media-bottom-margin-supported"), pick("media-left-margin-supported"), pick("media-right-margin-supported"))
    }

    /** Whether [size] can be printed without margins. */
    fun borderless(size: MediaSize): Boolean {
        val db = a("media-col-database")?.collections.orEmpty()
        if (db.isNotEmpty()) return db.any { c ->
            val s = sizeOf(c) ?: return@any false
            s.same(size) && listOf("media-top-margin", "media-bottom-margin", "media-left-margin", "media-right-margin").all { c.int(it) == 0 }
        }
        return listOf("media-top-margin-supported", "media-bottom-margin-supported", "media-left-margin-supported", "media-right-margin-supported").all { 0 in ints(it) }
    }

    val colorModes: List<String> get() = strings("print-color-mode-supported")
    val canColor: Boolean get() = when {
        a("color-supported")?.bool == false -> false
        colorModes.isEmpty() -> true
        else -> "color" in colorModes || ("auto" in colorModes && a("color-supported")?.bool == true)
    }
    val canMonochrome: Boolean get() = colorModes.isEmpty() || colorModes.any { it == "monochrome" || it == "process-monochrome" }
    val sides: List<String> get() = strings("sides-supported").ifEmpty { listOf("one-sided") }
    val canTwoSided: Boolean get() = sides.any { it.startsWith("two-sided") }
    val qualities: List<Int> get() = ints("print-quality-supported").ifEmpty { listOf(4) }
    val maxCopies: Int get() = (a("copies-supported")?.values?.firstOrNull() as? IntRange)?.last ?: 1

    val markers: List<Marker> get() {
        val names = strings("marker-names")
        val colors = strings("marker-colors"); val levels = ints("marker-levels")
        val low = ints("marker-low-levels"); val types = strings("marker-types")
        return names.mapIndexed { i, n -> Marker(n, colors.getOrNull(i), levels.getOrElse(i) { -2 }, low.getOrElse(i) { 15 }, types.getOrNull(i)) }
    }

    companion object {
        /** Just the state, for watching a printer while it prints. */
        val STATUS = arrayOf("printer-state", "printer-state-reasons", "printer-state-message", "printer-is-accepting-jobs", "marker-names", "marker-levels", "marker-colors", "marker-low-levels", "marker-types")
    }
}

/** Readable names for paper sizes, paper types and printer conditions. */
object MediaNames {
    private val known = listOf(
        "iso_a4_210x297mm" to "A4", "na_letter_8.5x11in" to "Letter", "na_legal_8.5x14in" to "Legal",
        "iso_a5_148x210mm" to "A5", "iso_a6_105x148mm" to "A6", "iso_a3_297x420mm" to "A3", "iso_b5_176x250mm" to "B5",
        "jis_b5_182x257mm" to "B5 (JIS)", "na_index-4x6_4x6in" to "10 × 15 cm (4 × 6 in)", "na_5x7_5x7in" to "13 × 18 cm (5 × 7 in)",
        "oe_photo-l_3.5x5in" to "9 × 13 cm (L)", "na_govt-letter_8x10in" to "20 × 25 cm (8 × 10 in)", "om_card_54x86mm" to "Card (54 × 86 mm)",
        "iso_dl_110x220mm" to "Envelope DL", "na_number-10_4.125x9.5in" to "Envelope #10", "iso_c6_114x162mm" to "Envelope C6",
        "iso_c5_162x229mm" to "Envelope C5", "na_executive_7.25x10.5in" to "Executive", "om_hagaki_100x148mm" to "Hagaki (100 × 148 mm)",
        "jpn_hagaki_100x148mm" to "Hagaki (100 × 148 mm)", "om_square-photo_89x89mm" to "Square 89 × 89 mm", "na_index-3x5_3x5in" to "3 × 5 in",
        "na_index-5x8_5x8in" to "5 × 8 in", "om_16k_195x270mm" to "16K", "na_foolscap_8.5x13in" to "Folio (8.5 × 13 in)",
        "om_folio_210x330mm" to "Oficio (210 × 330 mm)", "na_oficio_8.5x13.4in" to "Oficio (8.5 × 13.4 in)",
    ).mapNotNull { (n, l) -> MediaSize.fromName(n)?.let { it to l } }

    fun size(m: MediaSize): String = known.firstOrNull { it.first.name == m.name }?.second
        ?: known.firstOrNull { it.first.same(m) }?.second
        ?: "%s × %s mm".format(trim(m.widthMm), trim(m.heightMm))

    /** A PWG name for a size given in hundredths of a millimetre. */
    fun nameFor(w: Int, h: Int): String = known.firstOrNull { abs(it.first.width - w) <= 100 && abs(it.first.height - h) <= 100 }?.first?.name
        ?: "custom_%sx%smm".format(trim(w / 100f), trim(h / 100f))

    private fun trim(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)

    fun mediaType(t: String): String = when (t) {
        "stationery" -> "Plain paper"
        "stationery-heavyweight" -> "Heavy paper"
        "stationery-lightweight" -> "Light paper"
        "stationery-letterhead" -> "Letterhead"
        "stationery-inkjet" -> "Inkjet paper"
        "photographic" -> "Photo paper"
        "photographic-glossy" -> "Glossy photo paper"
        "photographic-high-gloss" -> "High-gloss photo paper"
        "photographic-semi-gloss" -> "Semi-gloss photo paper"
        "photographic-satin" -> "Satin photo paper"
        "photographic-matte" -> "Matte photo paper"
        "photographic-film" -> "Photo film"
        "envelope" -> "Envelope"
        "cardstock" -> "Card"
        "labels" -> "Labels"
        "transparency" -> "Transparency"
        "auto" -> "Automatic"
        else -> t.substringAfter("com.").substringAfter('-', t.substringAfter("com.")).replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun isPhotoPaper(t: String?) = t != null && (t.startsWith("photographic") || t.contains("photo") || t.contains("gloss"))

    /** A printer-state-reason in plain words (null for ones not worth showing). */
    fun reason(r: String): String? {
        val k = r.removeSuffix("-error").removeSuffix("-warning").removeSuffix("-report")
        return when {
            k == "none" || k == "other" || k == "moving-to-paused" -> null
            k == "media-empty" || k == "media-needed" -> "Add paper"
            k == "media-low" -> "Paper is running low"
            k == "media-jam" -> "Paper jam: open the printer and remove the stuck paper"
            k == "media-feed-failure" || k == "input-tray-missing" -> "The paper didn't feed: check the paper tray"
            k == "marker-supply-empty" || k == "toner-empty" -> "Out of ink"
            k == "marker-supply-low" || k == "toner-low" -> "Ink is low"
            k == "marker-waste-almost-full" -> "The ink pad (maintenance box) is almost full"
            k == "marker-waste-full" -> "The ink pad (maintenance box) is full: service needed"
            k == "cover-open" || k == "door-open" || k == "interlock-open" -> "A cover is open"
            k == "offline" || k == "shutdown" -> "The printer is offline"
            k == "paused" -> "The printer is paused"
            k == "spool-area-full" -> "The printer's memory is full"
            k == "printer-restarted" || k == "printer-shutdown" -> null
            k.startsWith("connecting-to-device") -> "Connecting…"
            else -> null
        }
    }

    /** Is the reason a problem that stops printing (rather than a warning)? */
    fun blocking(r: String): Boolean {
        if (r.endsWith("-warning") || r.endsWith("-report") || reason(r) == null) return false
        val k = r.removeSuffix("-error")
        return k !in setOf("media-low", "marker-supply-low", "toner-low", "marker-waste-almost-full") && !k.startsWith("connecting-to-device")
    }
}
