package com.localmediatools.ui.print

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.localmediatools.app.MainActivity
import com.localmediatools.app.R
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.print.AndroidSheetRenderer
import com.localmediatools.print.PrintJob
import com.localmediatools.print.PrintSource
import com.localmediatools.print.PrinterConnection
import com.localmediatools.print.Printers
import com.localmediatools.print.SavedPrinter
import com.localmediatools.print.core.FoundPrinter
import com.localmediatools.print.core.Fit
import com.localmediatools.print.core.LayoutOptions
import com.localmediatools.print.core.Margins
import com.localmediatools.print.core.MediaNames
import com.localmediatools.print.core.MediaSize
import com.localmediatools.print.core.PrintSettings
import com.localmediatools.print.core.PrinterCaps
import com.localmediatools.print.core.PrinterState
import com.localmediatools.print.core.SheetLayout
import com.localmediatools.tools.PageRanges
import com.localmediatools.tools.ToolId
import com.localmediatools.ui.ChoiceGroup
import com.localmediatools.ui.Palette
import com.localmediatools.ui.Shapes
import com.localmediatools.ui.SliderField
import com.localmediatools.ui.StepBadge
import com.localmediatools.ui.TextField
import com.localmediatools.ui.TextStyle
import com.localmediatools.ui.ToggleRow
import com.localmediatools.ui.ToolScreen
import com.localmediatools.ui.UI
import com.localmediatools.ui.WRAP
import com.localmediatools.ui.dp
import com.localmediatools.ui.listRow
import com.localmediatools.ui.lp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Print settings, kept while the app runs (the printer itself is remembered across runs). */
object PrintPrefs {
    var paper: String? = null
    var mediaType: String? = null
    var perSheet = 1
    var fit = Fit.FIT
    var borderless = false
    var color = true
    var quality = 4
    var copies = 1
    var twoSided = false
    var actualSize = false
    var pages = ""
}

/**
 * Printing photos and PDFs on a printer on the Wi-Fi: choose the printer (found on the network or
 * added by IP address), see its state and ink, set paper, layout, colour, quality and copies, and
 * check every sheet in the preview before printing.
 */
class PrintScreen(a: MainActivity) : ToolScreen(a, ToolId.PRINT) {
    private var printer: SavedPrinter? = null
    private var conn: PrinterConnection? = null
    private var problem: String? = null
    private var connecting = false
    private var connectJob: Job? = null
    private var previewJob: Job? = null
    private lateinit var printerBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var previewRow: LinearLayout
    private lateinit var previewLabel: TextView
    private val previews = ArrayList<Bitmap>()
    private val caps: PrinterCaps? get() = conn?.caps

    override val startLabel: String get() = "Print"
    override fun outputNaming() = ""
    override fun startedText(queued: Boolean) = if (queued) "Queued — it prints when the current job finishes." else "Sending to the printer. You can leave the app; progress is shown in the notification."

    override fun heroChips(): List<View> = listOf(
        chip(R.drawable.ic_shield, "Only your Wi-Fi", Palette.SUCCESS),
        chip(R.drawable.ic_tool_print, "No printer app", Palette.TEXT_2),
        chip(R.drawable.ic_clock, "Runs in background", Palette.TEXT_2),
    )

    override fun buildOptions(container: LinearLayout) {
        section(container, "Printer", null, top = 4)
        printerBox = UI.vertical(ctx)
        container.addView(printerBox, lp().apply { topMargin = ctx.dp(8) })
        section(container, "Preview")
        previewLabel = UI.text(ctx, "", TextStyle.CAPTION)
        container.addView(previewLabel, lp().apply { topMargin = ctx.dp(4) })
        previewRow = UI.horizontal(ctx, Gravity.TOP)
        container.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(previewRow) }, lp().apply { topMargin = ctx.dp(10) })
        settingsBox = UI.vertical(ctx)
        container.addView(settingsBox, lp())
        renderPrinter(); renderSettings(); schedulePreview()
        Printers.last(ctx)?.let { choose(it) }
    }

    // ------------------------------------------------------------------ printer
    private fun choose(p: SavedPrinter) {
        Printers.save(ctx, p)
        printer = p; conn = null; problem = null; connecting = true
        renderPrinter(); refreshValidation()
        connectJob?.cancel()
        connectJob = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Printers.connect(ctx, p) } }
            connecting = false
            r.onSuccess { c -> conn = c; printer = c.printer }.onFailure { e -> problem = e.message ?: "Can't reach the printer" }
            renderPrinter(); renderSettings(); schedulePreview(); refreshValidation()
        }
    }

    private fun renderPrinter() {
        if (!::printerBox.isInitialized) return
        printerBox.removeAllViews()
        val p = printer
        if (p == null) {
            printerBox.addView(UI.text(ctx, "Choose your printer. It must be on the same Wi-Fi as this phone (or its own Wi-Fi Direct network).", TextStyle.BODY_2))
            printerBox.addView(UI.primaryButton(ctx, "Find printers") { pick() }, lp().apply { topMargin = ctx.dp(10) })
            return
        }
        val c = caps
        val (line, color) = when {
            connecting -> "Connecting…" to Palette.TEXT_2
            problem != null -> problem!! to Palette.WARNING
            c == null -> "" to Palette.TEXT_2
            else -> {
                val issue = c.reasons.filter { MediaNames.blocking(it) }.firstNotNullOfOrNull { MediaNames.reason(it) }
                val note = c.reasons.firstNotNullOfOrNull { MediaNames.reason(it) }
                when {
                    issue != null -> issue to Palette.WARNING
                    c.state == PrinterState.PROCESSING -> "Printing" to Palette.ACCENT
                    note != null -> "Ready · $note" to Palette.WARNING
                    else -> "Ready" to Palette.SUCCESS
                }
            }
        }
        val row = UI.horizontal(ctx)
        row.addView(UI.iconTile(ctx, R.drawable.ic_tool_print, accent, 44, 24))
        val texts = UI.vertical(ctx).apply { setPadding(ctx.dp(12), 0, ctx.dp(8), 0) }
        texts.addView(UI.text(ctx, c?.name ?: p.name, TextStyle.SUBTITLE))
        texts.addView(UI.text(ctx, line, TextStyle.CAPTION, color).apply { setPadding(0, ctx.dp(2), 0, 0) })
        texts.addView(UI.text(ctx, p.host + if (p.uri.startsWith("ipps")) " · encrypted" else "", TextStyle.CAPTION, Palette.TEXT_3).apply { setPadding(0, ctx.dp(2), 0, 0) })
        row.addView(texts, lp(0, WRAP, 1f))
        row.addView(UI.ghostButton(ctx, "Change") { pick() })
        printerBox.addView(row)
        if (problem != null) printerBox.addView(UI.secondaryButton(ctx, "Try again", R.drawable.ic_redo) { choose(p) }, lp().apply { topMargin = ctx.dp(10) })
        val markers = c?.markers.orEmpty()
        if (markers.isNotEmpty()) {
            val inks = UI.horizontal(ctx)
            for (m in markers) {
                val dot = View(ctx).apply { background = Shapes.circle(parseColor(m.color)) }
                inks.addView(dot, LinearLayout.LayoutParams(ctx.dp(10), ctx.dp(10)).apply { rightMargin = ctx.dp(5) })
                inks.addView(UI.text(ctx, (if (m.known) "${m.level}%" else "?"), TextStyle.CAPTION, if (m.low) Palette.WARNING else Palette.TEXT_2), lp(WRAP, WRAP).apply { rightMargin = ctx.dp(14) })
                inks.contentDescription = markers.joinToString { "${it.name} ${if (it.known) "${it.level}%" else "unknown"}" }
            }
            printerBox.addView(UI.text(ctx, "Ink, as the printer estimates it", TextStyle.LABEL), lp().apply { topMargin = ctx.dp(12) })
            printerBox.addView(inks, lp().apply { topMargin = ctx.dp(6) })
            if (markers.any { it.low }) printerBox.addView(UI.note(ctx, "Some ink is low. If your printer has refillable tanks, check them: the printer can only estimate.", UI.NoteKind.WARN), lp().apply { topMargin = ctx.dp(8) })
        }
    }

    /** A marker colour as the printer gives it ("#00FFFF"); grey when it gives none. */
    private fun parseColor(c: String?): Int = c?.takeIf { it.startsWith("#") && it.length == 7 }?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: Palette.TEXT_3

    /** The printer list: remembered printers, the ones found on the Wi-Fi now, and adding one by IP address. */
    private fun pick() {
        val col = UI.vertical(ctx, 20, 16)
        col.addView(UI.text(ctx, "Choose a printer", TextStyle.TITLE))
        val dialog = AlertDialog.Builder(activity).setView(ScrollView(ctx).apply { addView(col) }).setNegativeButton("Close", null).create()
        fun use(p: SavedPrinter) { dialog.dismiss(); choose(p) }
        val saved = Printers.all(ctx)
        if (saved.isNotEmpty()) {
            col.addView(UI.label(ctx, "Saved"), lp().apply { topMargin = ctx.dp(16) })
            for (p in saved) {
                val remove = UI.iconButton(ctx, R.drawable.ic_trash, "Forget ${p.name}", Palette.TEXT_3) {
                    Printers.remove(ctx, p.id)
                    if (printer?.id == p.id) { printer = null; conn = null; renderPrinter(); refreshValidation() }
                    dialog.dismiss(); pick()
                }
                col.addView(listRow(ctx, R.drawable.ic_tool_print, accent, p.name, p.host, remove) { use(p) }, lp().apply { topMargin = ctx.dp(6) })
            }
        }
        col.addView(UI.label(ctx, "On this Wi-Fi"), lp().apply { topMargin = ctx.dp(16) })
        val foundBox = UI.vertical(ctx)
        val status = UI.text(ctx, "Looking for printers…", TextStyle.CAPTION)
        col.addView(status, lp().apply { topMargin = ctx.dp(6) })
        col.addView(foundBox)
        col.addView(listRow(ctx, R.drawable.ic_add, Palette.ACCENT, "Add by IP address", "If your printer isn't found (its IP address is on its network status page)") {
            val input = EditText(ctx).apply { hint = "e.g. 192.168.1.37"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
            AlertDialog.Builder(activity).setTitle("Printer's IP address").setView(input.apply { setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12)) })
                .setPositiveButton("Add") { _, _ ->
                    try { use(SavedPrinter.manual(input.text.toString())) } catch (e: UserFacingException) { Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show() }
                }.setNegativeButton("Cancel", null).show()
        }, lp().apply { topMargin = ctx.dp(16) })
        var shown = emptyList<FoundPrinter>()
        fun show(list: List<FoundPrinter>) {
            if (list == shown) return
            shown = list
            foundBox.removeAllViews()
            for (f in list) {
                val known = saved.firstOrNull { it.id == f.identity }
                foundBox.addView(listRow(ctx, R.drawable.ic_tool_print, accent, f.name, (f.model.takeIf { it != f.name }?.let { "$it · " } ?: "") + f.address.hostText) {
                    use(SavedPrinter.from(f, known))
                }, lp().apply { topMargin = ctx.dp(6) })
            }
        }
        val search = scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Printers.discover(ctx, 6000) { list -> scope.launch { show(list) } } }
            }
            r.onSuccess { list ->
                show(list)
                status.text = if (list.isEmpty()) "No printers answered. Check that the printer is on and connected to this Wi-Fi, or add it by IP address." else "Found ${list.size}"
            }.onFailure { e -> status.text = e.message ?: "Couldn't look for printers"; status.setTextColor(Palette.WARNING) }
        }
        dialog.setOnDismissListener { search.cancel() }
        dialog.show()
    }

    // ------------------------------------------------------------------ settings
    private fun paperChoices(): List<MediaSize> {
        val list = caps?.sizes ?: listOf(MediaSize.A4, MediaSize.LETTER, MediaSize.fromName("na_index-4x6_4x6in")!!)
        val order = listOf("iso_a4_210x297mm", "na_letter_8.5x11in", "na_index-4x6_4x6in", "na_5x7_5x7in", "oe_photo-l_3.5x5in", "iso_a5_148x210mm", "iso_a6_105x148mm", "na_govt-letter_8x10in", "na_legal_8.5x14in")
        return list.sortedBy { m -> order.indexOfFirst { MediaSize.fromName(it)?.same(m) == true }.let { if (it < 0) 99 else it } }
    }

    private fun paper(): MediaSize {
        val choices = paperChoices()
        return choices.firstOrNull { it.name == PrintPrefs.paper } ?: caps?.defaultSize?.let { d -> choices.firstOrNull { it.same(d) } } ?: choices.first()
    }

    private fun mediaType(): String? {
        val types = caps?.mediaTypes.orEmpty()
        return PrintPrefs.mediaType?.takeIf { it in types } ?: caps?.defaultMediaType?.takeIf { it in types } ?: types.firstOrNull { it == "stationery" }
    }

    private fun borderless() = PrintPrefs.borderless && caps?.borderless(paper()) == true

    private fun hasPdf() = selection.usable.any { it.kind == MediaKind.PDF }

    private fun renderSettings() {
        if (!::settingsBox.isInitialized) return
        val box = settingsBox
        box.removeAllViews()
        val c = caps
        if (c == null) box.addView(UI.note(ctx, "The paper sizes, paper types and other settings shown are the printer's own once it's connected.", UI.NoteKind.INFO), lp().apply { topMargin = ctx.dp(14) })

        section(box, "Paper size")
        val sizes = paperChoices()
        box.addView(ChoiceGroup(ctx, sizes, { it.label }, paper()) { PrintPrefs.paper = it.name; renderSettings(); schedulePreview() }, lp().apply { topMargin = ctx.dp(10) })

        val types = c?.mediaTypes.orEmpty()
        if (types.isNotEmpty()) {
            section(box, "Paper type", "Tell the printer what's loaded: it lays down the right amount of ink")
            box.addView(ChoiceGroup(ctx, types, { MediaNames.mediaType(it) }, mediaType()) {
                PrintPrefs.mediaType = it
                if (MediaNames.isPhotoPaper(it) && PrintPrefs.quality < 5 && 5 in c!!.qualities) { PrintPrefs.quality = 5; renderSettings() }
            }, lp().apply { topMargin = ctx.dp(10) })
        }

        section(box, "Layout")
        box.addView(ChoiceGroup(ctx, SheetLayout.PER_SHEET, { if (it == 1) "1 per sheet" else "$it per sheet" }, PrintPrefs.perSheet) { PrintPrefs.perSheet = it; schedulePreview() }, lp().apply { topMargin = ctx.dp(10) })
        box.addView(ChoiceGroup(ctx, Fit.entries, { if (it == Fit.FIT) "Whole photo" else "Fill (trims edges)" }, PrintPrefs.fit) { PrintPrefs.fit = it; schedulePreview() }, lp().apply { topMargin = ctx.dp(8) })
        val canBorderless = c?.borderless(paper()) == true
        box.addView(ToggleRow(ctx, "Borderless", if (c == null) "Available once the printer is connected" else if (canBorderless) "Print to the edges of the paper" else "Not available for ${paper().label} on this printer", borderless()) {
            PrintPrefs.borderless = it; schedulePreview()
        }.apply { setAvailable(canBorderless) }, lp().apply { topMargin = ctx.dp(4) })
        if (hasPdf()) {
            box.addView(ToggleRow(ctx, "Actual size", "PDF pages at their real size instead of fitted to the paper", PrintPrefs.actualSize) { PrintPrefs.actualSize = it; schedulePreview() }, lp())
            box.addView(TextField(ctx, "Pages", "All, or e.g. 1-3, 5, 8-", PrintPrefs.pages) { PrintPrefs.pages = it; refreshValidation(); schedulePreview() }, lp().apply { topMargin = ctx.dp(8) })
        }

        section(box, "Colour")
        val colors = buildList { if (c?.canColor != false) add(true); if (c?.canMonochrome != false) add(false) }
        if (PrintPrefs.color && true !in colors) PrintPrefs.color = false
        box.addView(ChoiceGroup(ctx, colors, { if (it) "Colour" else "Black & white" }, PrintPrefs.color) { PrintPrefs.color = it; schedulePreview() }, lp().apply { topMargin = ctx.dp(10) })

        section(box, "Quality")
        val qualities = (c?.qualities ?: listOf(3, 4, 5)).filter { it in 3..5 }.ifEmpty { listOf(4) }
        if (PrintPrefs.quality !in qualities) PrintPrefs.quality = qualities.firstOrNull { it == 4 } ?: qualities.first()
        box.addView(ChoiceGroup(ctx, qualities, { when (it) { 3 -> "Draft"; 5 -> "Best"; else -> "Normal" } }, PrintPrefs.quality,
            badgeOf = { if (it == 5) "photos" else null }) { PrintPrefs.quality = it }, lp().apply { topMargin = ctx.dp(10) })

        if (c?.canTwoSided == true) box.addView(ToggleRow(ctx, "Two-sided", "Print on both sides of the paper", PrintPrefs.twoSided) { PrintPrefs.twoSided = it }, lp().apply { topMargin = ctx.dp(4) })
        box.addView(SliderField(ctx, "Copies", 1, 50, PrintPrefs.copies) { PrintPrefs.copies = it; refreshValidation() }, lp().apply { topMargin = ctx.dp(12) })
    }

    private fun layoutOptions() = LayoutOptions(PrintPrefs.perSheet, PrintPrefs.fit, PrintPrefs.actualSize, borderless())

    private fun settings(items: List<MediaItem>): PrintSettings {
        val type = mediaType()
        return PrintSettings(paper(), type, borderless(), PrintPrefs.color, PrintPrefs.quality, PrintPrefs.copies,
            PrintPrefs.twoSided && caps?.canTwoSided == true, photo = items.none { it.kind == MediaKind.PDF } || MediaNames.isPhotoPaper(type),
            jobName = items.firstOrNull()?.baseName?.let { if (items.size > 1) "$it + ${items.size - 1}" else it } ?: "Local Media Tools")
    }

    // ------------------------------------------------------------------ preview
    private fun schedulePreview() {
        if (!::previewRow.isInitialized) return
        previewJob?.cancel()
        val items = selection.usable
        if (items.isEmpty()) { showPreview(emptyList(), 0, "Select photos or PDFs to see the sheets"); return }
        val paper = paper()
        val margins = if (borderless()) Margins.NONE else caps?.margins ?: Margins(300, 300, 300, 300)
        val layout = layoutOptions()
        val pages = PrintPrefs.pages.takeIf { hasPdf() }
        val gray = !PrintPrefs.color
        previewJob = scope.launch {
            delay(200)
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    PrintSource(ctx, items, File(ctx.cacheDir, "print").apply { mkdirs() }, pages).use { src ->
                        val sheets = SheetLayout.plan(src.pages, paper, margins, layout)
                        val renderer = AndroidSheetRenderer(src, sheets, paper)
                        sheets.size to (0 until minOf(sheets.size, 12)).map { i -> renderer.image(i, 220).let { if (gray) grayscale(it) else it } }
                    }
                }
            }
            r.onSuccess { (n, imgs) -> showPreview(imgs, n, null) }.onFailure { e -> showPreview(emptyList(), 0, e.message ?: "Couldn't prepare the preview") }
        }
    }

    private fun grayscale(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(b, 0f, 0f, android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }) })
        b.recycle()
        return out
    }

    private fun showPreview(imgs: List<Bitmap>, sheets: Int, message: String?) {
        previewRow.removeAllViews()
        previews.forEach { it.recycle() }; previews.clear(); previews.addAll(imgs)
        previewLabel.text = message ?: when (sheets) {
            1 -> "1 sheet" + copiesText()
            else -> "$sheets sheets" + copiesText() + if (sheets > imgs.size) " · first ${imgs.size} shown" else ""
        }
        previewLabel.setTextColor(if (message != null && selection.usable.isNotEmpty()) Palette.WARNING else Palette.TEXT_2)
        for ((i, b) in imgs.withIndex()) {
            val w = ctx.dp(116); val h = w * b.height / b.width
            val frame = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            frame.addView(ImageView(ctx).apply {
                setImageBitmap(b); scaleType = ImageView.ScaleType.FIT_XY
                background = Shapes.rounded(ctx, Color.WHITE, 2f, Palette.STROKE_2)
                elevation = ctx.dp(2).toFloat()
                contentDescription = "Sheet ${i + 1}"
            }, LinearLayout.LayoutParams(w, h))
            frame.addView(UI.text(ctx, "${i + 1}", TextStyle.CAPTION).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = ctx.dp(4) })
            previewRow.addView(frame, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = ctx.dp(10); bottomMargin = ctx.dp(4) })
        }
        refreshValidation()
    }

    private fun copiesText() = if (PrintPrefs.copies > 1) " × ${PrintPrefs.copies} copies" else ""

    override fun onSelectionChanged() { renderSettings(); schedulePreview() }

    // ------------------------------------------------------------------ printing
    override fun validate(): String? {
        super.validate()?.let { return it }
        if (printer == null) return "Choose a printer"
        if (conn == null) return if (connecting) "Connecting to the printer…" else problem ?: "Can't reach the printer"
        if (hasPdf() && PrintPrefs.pages.isNotBlank()) PageRanges.validateSyntax(PrintPrefs.pages)?.let { return it }
        return null
    }

    override fun createJob(items: List<MediaItem>): ExportJob {
        val c = conn ?: throw IllegalArgumentException("Choose a printer first")
        return PrintJob(items, c.printer, settings(items), layoutOptions(), PrintPrefs.pages.takeIf { hasPdf() && it.isNotBlank() })
    }

    override fun outputCard(): View {
        val c = UI.card(ctx)
        val head = UI.horizontal(ctx)
        head.addView(StepBadge(ctx, 3))
        head.addView(UI.text(ctx, "Printing", TextStyle.SUBTITLE).apply { setPadding(ctx.dp(10), 0, 0, 0) })
        c.addView(head)
        c.addView(UI.text(ctx, "The sheets are drawn on this phone and sent straight to the printer. Progress shows in the notification and in Activity, where you can cancel; problems such as no paper are explained there.", TextStyle.BODY_2).apply { setPadding(0, ctx.dp(10), 0, 0) })
        c.addView(UI.note(ctx, "Your files go only to the printer, over your own network. The app never connects to servers on the internet.", UI.NoteKind.PRIVACY), lp().apply { topMargin = ctx.dp(12) })
        return c
    }

    override fun onDestroy() {
        connectJob?.cancel(); previewJob?.cancel()
        previews.forEach { it.recycle() }; previews.clear()
        super.onDestroy()
    }
}
