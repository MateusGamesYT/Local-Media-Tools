package com.localmediatools.export

import com.localmediatools.core.MediaItem
import com.localmediatools.core.OutputFile
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
abstract class ExportJob(val tool: ToolId, val inputs: List<MediaItem>) {
    val id: Long = ids.incrementAndGet()

    /** Progress units (usually the number of input files). */
    open val unitCount: Int get() = inputs.size

    /** E.g. "Compressing 12 videos". */
    abstract val title: String

    abstract suspend fun run(ctx: JobContext)

    companion object {
        private val ids = AtomicLong(System.currentTimeMillis())
        fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
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
        JobStatus.QUEUED -> "Waiting to start"
        JobStatus.RUNNING -> statusText
        JobStatus.CANCELLED -> "Cancelled" + if (outputs.isNotEmpty()) " · ${outputs.size} saved before cancelling" else ""
        else -> buildString {
            val saved = outputs.size
            append(if (saved == 1) "1 file saved" else "$saved files saved")
            if (failed > 0) append(" · $failed failed")
            if (skipped > 0) append(" · $skipped skipped")
            if (errorMessage != null && saved == 0) { clear(); append(errorMessage) }
        }
    }
}
