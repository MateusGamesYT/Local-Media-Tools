package com.localmediatools.export

import com.localmediatools.core.MediaItem
import com.localmediatools.core.OutputFile
import com.localmediatools.i18n.I18n
import com.localmediatools.i18n.tr
import com.localmediatools.i18n.trn
import com.localmediatools.tools.ToolId
import java.util.concurrent.atomic.AtomicLong

enum class JobStatus { QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED, CANCELLED;
    val finished get() = this == SUCCEEDED || this == PARTIAL || this == FAILED || this == CANCELLED
}

enum class ItemOutcome { SUCCESS, SKIPPED, FAILED }

data class ItemResult(
    val inputName: String,
    val outcome: ItemOutcome,
    val message: String?,
    val outputs: List<OutputFile> = emptyList(),
    /** Extra facts shown in results, e.g. "4.2 MB → 1.1 MB". */
    val details: String? = null,
)

/**
 * One export: a tool, its inputs and its options. Subclasses implement [run], reporting progress and
 * per-item results through the [JobContext].
 */
abstract class ExportJob(val tool: ToolId, inputs: List<MediaItem>) {
    val id: Long = ids.incrementAndGet()

    /** The files to process. A tool stack sets them again for each run of a step (see StackJob). */
    var inputs: List<MediaItem> = inputs
        internal set

    /** Progress units (usually the number of input files). */
    open val unitCount: Int get() = inputs.size

    /** E.g. "Compressing 12 videos". */
    abstract val title: String

    abstract suspend fun run(ctx: JobContext)

    companion object {
        private val ids = AtomicLong(System.currentTimeMillis())
    }
}

/** Immutable view of a job's progress for UI and notifications. */
data class JobSnapshot(
    val id: Long,
    val tool: ToolId,
    val title: String,
    val status: JobStatus,
    val unitCount: Int,
    val unitsDone: Int,
    val fraction: Double,
    val currentName: String?,
    val statusText: String,
    val results: List<ItemResult>,
    val workloadPercent: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val errorMessage: String? = null,
) {
    val succeeded get() = results.count { it.outcome == ItemOutcome.SUCCESS }
    val failed get() = results.count { it.outcome == ItemOutcome.FAILED }
    val skipped get() = results.count { it.outcome == ItemOutcome.SKIPPED }
    val outputs get() = results.flatMap { it.outputs }

    fun summary(): String = when (status) {
        JobStatus.QUEUED -> tr("Waiting to start")
        JobStatus.RUNNING -> statusText
        JobStatus.CANCELLED -> if (outputs.isNotEmpty()) trn(outputs.size, "Cancelled · {0} saved before cancelling", "Cancelled · {0} saved before cancelling") else tr("Cancelled")
        // Printing saves nothing: its result says what happened.
        else -> if (tool == ToolId.PRINT) results.firstOrNull()?.message ?: errorMessage ?: tr("Not printed") else saved()
    }

    private fun saved(): String = buildString {
        val saved = outputs.size
        append(trn(saved, "{0} file saved", "{0} files saved"))
        if (failed > 0) append(" · ").append(trn(failed, "{0} failed", "{0} failed"))
        if (skipped > 0) append(" · ").append(trn(skipped, "{0} skipped", "{0} skipped"))
        if (errorMessage != null && saved == 0) { clear(); append(errorMessage) }
    }
}

/** Where an export's files went (a tool stack saves into the folders of the tools that made them). */
val JobSnapshot.savedTo: String
    get() = results.flatMap { r -> r.outputs.map { it.area.displayPath } }.distinct().let { I18n.andList(it) }.ifEmpty { I18n.andList(tool.outputPath.split("\n")) }
