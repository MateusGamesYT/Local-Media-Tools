package com.localmediatools.tools

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.localmediatools.core.Errors
import com.localmediatools.core.ExportCancelledException
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MediaKind
import com.localmediatools.core.MediaProbe
import com.localmediatools.core.OutputArea
import com.localmediatools.core.OutputFile
import com.localmediatools.core.PendingOutput
import com.localmediatools.export.ExportJob
import com.localmediatools.export.ItemOutcome
import com.localmediatools.export.ItemResult
import com.localmediatools.export.JobContext
import com.localmediatools.ui.PickKind
import com.localmediatools.ui.ToolRules
import java.io.File
import java.io.FileOutputStream

/** What kind of file flows between the steps of a tool stack. */
enum class FlowKind(val noun: String) {
    IMAGE("photos"), GIF("GIFs"), VIDEO("videos"), PDF("PDFs"), AUDIO("audio files");

    companion object {
        fun of(item: MediaItem): FlowKind? = when (item.kind) {
            MediaKind.IMAGE -> IMAGE
            MediaKind.GIF -> GIF
            MediaKind.VIDEO -> VIDEO
            MediaKind.PDF -> PDF
            MediaKind.AUDIO -> AUDIO
            MediaKind.OTHER -> null
        }

        fun describe(kinds: Collection<FlowKind>): String = when (kinds.size) {
            0 -> "nothing"
            1 -> kinds.first().noun
            else -> kinds.sortedBy { it.ordinal }.let { k -> k.dropLast(1).joinToString(", ") { it.noun } + " and " + k.last().noun }
        }
    }
}

/** Which tools can be stacked, what they accept and what they make. */
object StackRules {
    /** Tools that work on one photo interactively, scan the library or use the camera. */
    private val notStackable = setOf(ToolId.PHOTO_EDITOR, ToolId.MAGIC_ERASER, ToolId.BLUR_REDACT, ToolId.DUPLICATES, ToolId.PDF_SCANNER, ToolId.TOOL_STACK, ToolId.PRINT)

    fun stackable(t: ToolId) = t !in notStackable

    fun whyNot(t: ToolId): String = when (t) {
        ToolId.PHOTO_EDITOR, ToolId.MAGIC_ERASER, ToolId.BLUR_REDACT -> "Edits one photo by hand, so it can't run in a stack"
        ToolId.DUPLICATES -> "Looks through your whole photo library, so it can't run in a stack"
        ToolId.PDF_SCANNER -> "Uses the camera; to make a PDF from files in a stack use Images → PDF"
        ToolId.PRINT -> "Prints instead of saving files, so it can't be a step of a stack"
        else -> "Can't be part of a stack"
    }

    fun accepts(t: ToolId): Set<FlowKind> = when (ToolRules.pickKind(t)) {
        PickKind.IMAGES -> if (t == ToolId.OPTIMIZE_IMAGES) setOf(FlowKind.IMAGE) else setOf(FlowKind.IMAGE, FlowKind.GIF)
        PickKind.VIDEOS -> setOf(FlowKind.VIDEO)
        PickKind.GIFS -> setOf(FlowKind.GIF)
        PickKind.PDFS -> setOf(FlowKind.PDF)
        PickKind.MEDIA -> if (t == ToolId.FACE_BLUR) setOf(FlowKind.IMAGE, FlowKind.VIDEO) else setOf(FlowKind.IMAGE, FlowKind.GIF, FlowKind.VIDEO)
        PickKind.ANY -> FlowKind.entries.toSet() - FlowKind.AUDIO
        PickKind.PRINTABLE -> setOf(FlowKind.IMAGE, FlowKind.GIF, FlowKind.PDF)
    }

    /** What a tool makes from a file of kind [k]. */
    fun output(t: ToolId, k: FlowKind): FlowKind = when (t) {
        ToolId.VIDEO_TO_GIF -> FlowKind.GIF
        ToolId.EXTRACT_AUDIO -> FlowKind.AUDIO
        ToolId.IMAGES_TO_PDF -> FlowKind.PDF
        ToolId.PDF_TO_IMAGES -> FlowKind.IMAGE
        ToolId.REMOVE_METADATA, ToolId.FACE_BLUR, ToolId.COMPRESS_GIF, ToolId.OPTIMIZE_GIF -> k
        // Photo tools turn a GIF into a still picture.
        else -> if (k == FlowKind.GIF) FlowKind.IMAGE else k
    }

    /** Kinds after a step: what it can work on is converted, everything else passes through unchanged. */
    fun after(t: ToolId, kinds: Set<FlowKind>): Set<FlowKind> = kinds.map { if (it in accepts(t)) output(t, it) else it }.toSet()
}

/**
 * One configured step of a tool stack: the tool, a short description of its settings and a job
 * that holds those settings (its files are set each time the stack runs).
 */
class StackStep(val tool: ToolId, val summary: String, val job: ExportJob)

/**
 * Runs tools one after another: every step works on the results of the step before. Results of
 * in-between steps are private temporary files (unless [keepInBetween]); only the last step's
 * results are saved. A file a step can't work on, or has nothing to do for, continues unchanged,
 * so nothing is lost on the way.
 */
class StackJob(inputs: List<MediaItem>, val steps: List<StackStep>, private val keepInBetween: Boolean) : ExportJob(ToolId.TOOL_STACK, inputs) {
    override val title = "Stack: " + steps.joinToString(" → ") { it.tool.title }
    override val unitCount: Int get() = steps.size

    /** A file on its way through the stack. */
    private class Flow(
        val item: MediaItem,
        /** Name of the original file(s), used in the results. */
        val origin: String,
        /** Set when the file is a private result of an earlier step (to be saved if nothing else changes it). */
        val temp: File?,
        val area: OutputArea?,
        val mime: String?,
        val notes: List<String>,
        val changed: Boolean,
    ) {
        fun note(n: String) = Flow(item, origin, temp, area, mime, notes + n, changed)
    }

    override suspend fun run(ctx: JobContext) {
        val root = File(ctx.app.cacheDir, "stack-$id").apply { deleteRecursively(); mkdirs() }
        try {
            var flow = inputs.map { Flow(it, it.name, null, null, it.mime, emptyList(), false) }
            for ((k, step) in steps.withIndex()) {
                ctx.checkCancelled()
                flow = runStep(ctx, k, step, flow, root)
                // Delete private files nothing refers to any more.
                val live = flow.mapNotNull { it.temp?.absolutePath }.toHashSet()
                root.walkBottomUp().filter { it.isFile && it.absolutePath !in live }.forEach { it.delete() }
                ctx.unitDone(k)
            }
            // Files the last step didn't change.
            for (f in flow) {
                ctx.checkCancelled()
                val notes = f.notes.joinToString(" ").ifBlank { null }
                when {
                    f.temp != null && f.area != null -> {
                        val out = Outputs.produce(ctx, f.area, f.item.name, f.mime ?: f.item.mime ?: "application/octet-stream") { pending ->
                            f.temp.inputStream().use { input -> pending.openStream().use { os -> Outputs.copy(input, os, f.temp.length(), ctx) } }
                        }.first
                        ctx.addResult(ItemResult(f.origin, ItemOutcome.SUCCESS, notes, listOf(out), "Saved as it came out of an earlier step"))
                    }
                    f.changed -> ctx.addResult(ItemResult(f.origin, ItemOutcome.SKIPPED, notes))
                    else -> ctx.addResult(ItemResult(f.origin, ItemOutcome.SKIPPED, "No step changed this file. " + (notes ?: "")))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun runStep(ctx: JobContext, k: Int, step: StackStep, flow: List<Flow>, root: File): List<Flow> {
        val last = k == steps.lastIndex
        val label = "Step ${k + 1} (${step.tool.title})"
        val accepted = flow.filter { ToolRules.issue(step.tool, it.item) == null }
        val passing = flow.filter { f -> accepted.none { it === f } }.map { it.note("$label doesn't work on ${FlowKind.of(it.item)?.noun ?: "this kind of file"}, so it was skipped.") }
        val min = ToolRules.minItems(step.tool)
        if (accepted.isEmpty()) return passing
        if (accepted.size < min) return passing + accepted.map { it.note("$label needs at least $min files, so it was skipped.") }

        ctx.status("Step ${k + 1} of ${steps.size}: ${step.tool.title}", null)
        val job = step.job
        job.inputs = accepted.map { it.item }
        val stepDir = File(root, "step${k + 1}").apply { mkdirs() }
        val child = JobContext(ctx.app, job, object : JobContext.Listener {
            override fun onProgress(ctx2: JobContext) {
                ctx.unitProgress(k, ctx2.fraction)
                ctx.status("Step ${k + 1} of ${steps.size} · ${step.tool.title}: ${ctx2.statusText}", ctx2.currentName)
            }
            override fun onResult(ctx2: JobContext, result: ItemResult) {}
        }, parent = ctx)
        child.budgetOverride = ctx.budgetOverride
        if (!last && !keepInBetween) child.outputFactory = { area, name, mime -> TempOutput(stepDir, area, name, mime) }
        var stepError: String? = null
        try {
            job.run(child)
        } catch (e: ExportCancelledException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("LMT", "stack step failed", e)
            stepError = Errors.describe(e)
        }
        ctx.checkCancelled()

        val unmatched = accepted.toMutableList()
        val next = ArrayList<Flow>()
        for (r in child.results) {
            val src = unmatched.firstOrNull { it.item.name == r.inputName }?.also { unmatched.remove(it) }
            // Tools that combine files (merging) report one result for all of them.
            val group = if (src != null) listOf(src) else unmatched.toList().also { unmatched.clear() }
            if (group.isEmpty()) continue
            val origin = if (group.size == 1) group[0].origin else group.joinToString(" + ") { it.origin }.let { if (it.length > 60) "${group.size} files" else it }
            val notes = group.flatMap { it.notes }.distinct()
            when {
                r.outcome == ItemOutcome.SUCCESS && r.outputs.isNotEmpty() -> {
                    if (last) {
                        ctx.addResult(ItemResult(origin, ItemOutcome.SUCCESS, (notes + listOfNotNull(r.message)).joinToString(" ").ifBlank { null }, r.outputs, r.details))
                    } else {
                        if (keepInBetween) ctx.addResult(ItemResult("$origin · after step ${k + 1}", ItemOutcome.SUCCESS, r.message, r.outputs, r.details))
                        for (o in r.outputs) {
                            val item = MediaProbe.describe(ctx.app, o.uri)
                            val temp = if (o.uri.scheme == "file") File(o.uri.path!!) else null
                            next.add(Flow(item, origin, if (keepInBetween) null else temp, o.area, o.mime, notes + listOfNotNull(r.message?.let { "$label: $it" }), true))
                        }
                    }
                }
                r.outcome == ItemOutcome.FAILED -> for (g in group) {
                    ctx.addResult(ItemResult(g.origin, ItemOutcome.FAILED, "$label failed: ${r.message ?: "unknown error"}"))
                }
                // Skipped (nothing to do): the file continues unchanged.
                else -> for (g in group) next.add(g.note("$label left it unchanged: ${r.message ?: "nothing to do"}"))
            }
        }
        for (g in unmatched) ctx.addResult(ItemResult(g.origin, ItemOutcome.FAILED, "$label failed: ${stepError ?: "it produced no result"}"))
        return next + passing
    }
}

/** A step's result kept as a private temporary file (it becomes the next step's input). */
private class TempOutput(dir: File, override val area: OutputArea, override val requestedName: String, override val mime: String) : PendingOutput() {
    private val file: File = run {
        var f = File(dir, requestedName)
        var n = 1
        while (f.exists()) { f = File(dir, requestedName.substringBeforeLast('.') + "-$n." + requestedName.substringAfterLast('.', "bin")); n++ }
        f.createNewFile()
        f
    }
    override val uri: Uri = Uri.fromFile(file)
    override fun openStream() = FileOutputStream(file)
    override fun openFd(mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    override fun commit() = OutputFile(uri, file.name, mime, file.length(), area)
    override fun abort() { file.delete() }
}
