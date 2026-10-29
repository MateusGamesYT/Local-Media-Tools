package com.localmediatools.print

import com.localmediatools.core.ExportCancelledException
import com.localmediatools.core.MediaItem
import com.localmediatools.core.UserFacingException
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import com.localmediatools.print.core.IppException
import com.localmediatools.print.core.LayoutOptions
import com.localmediatools.print.core.Margins
import com.localmediatools.print.core.PrintCancelled
import com.localmediatools.print.core.PrintRun
import com.localmediatools.print.core.PrintSettings
import com.localmediatools.print.core.SheetLayout
import com.localmediatools.tools.ToolId
import java.io.File
import kotlinx.coroutines.withContext

/** Prints photos, PDFs and documents on a network printer, in the background like any other job. */
class PrintJob(
    inputs: List<MediaItem>,
    val printer: SavedPrinter,
    private val settings: PrintSettings,
    private val layout: LayoutOptions,
    private val pageRange: String?,
) : ExportJob(ToolId.PRINT, inputs) {
    override val title = trn(inputs.size, "Printing {0} file on {1}", "Printing {0} files on {1}", printer.name)
    override val unitCount: Int get() = 1

    override suspend fun run(ctx: JobContext) = withContext(JobContext.dispatcher) {
        val names = if (inputs.size == 1) inputs[0].name else trn(inputs.size, "{0} file", "{0} files")
        val result = try {
            ctx.status(tr("Connecting to {0}", printer.name), null)
            Printers.onLocalNetwork(ctx.app) {
                val conn = Printers.connect(ctx.app, printer)
                val tmp = File(ctx.app.cacheDir, "print").apply { mkdirs() }
                ctx.status(tr("Preparing"), null)
                PrintSource(ctx.app, inputs, tmp, pageRange, photoBudget = ctx.memoryBudget(2), paper = settings.paper).use { src ->
                    if (src.pages.isEmpty()) throw UserFacingException(tr("There's nothing to print (no pages chosen)"))
                    val margins = if (layout.borderless) Margins.NONE else conn.caps.margins
                    val sheets = SheetLayout.plan(src.pages, settings.paper, margins, layout)
                    val renderer = AndroidSheetRenderer(src, sheets, settings.paper)
                    try {
                        val run = PrintRun(conn.client, conn.caps, settings, sheets.size, renderer,
                            { _, f, text -> ctx.status(text, null); ctx.unitProgress(0, f) },
                            cancelled = { ctx.cancelled }, spoolDir = tmp)
                        val r = run.run()
                        Printers.markUsed(ctx.app, conn.printer)
                        ItemResult(names, if (r.ok) ItemOutcome.SUCCESS else ItemOutcome.FAILED, r.message,
                            details = "${conn.caps.name} · ${settings.paper.label}" + (if (settings.copies > 1) " · " + trn(settings.copies, "{0} copy", "{0} copies") else ""))
                    } finally { renderer.release() }
                }
            }
        } catch (e: PrintCancelled) {
            throw ExportCancelledException()
        } catch (e: IppException) {
            ItemResult(names, ItemOutcome.FAILED, e.message ?: tr("The printer couldn't be reached"))
        } catch (e: UserFacingException) {
            ItemResult(names, ItemOutcome.FAILED, e.message)
        }
        ctx.addResult(result)
        ctx.unitDone(0)
    }
}
