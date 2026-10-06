package com.localmediatools.export

import android.content.Context
import android.os.Process
import com.localmediatools.core.Errors
import com.localmediatools.core.ExportCancelledException
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MemoryBudget
import com.localmediatools.core.SkipItemException
import com.localmediatools.core.Workload
import com.localmediatools.core.WorkloadProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything a running job needs: cancellation, progress reporting, results, workload-aware
 * throttling and a worker dispatcher whose threads follow the workload's priority.
 */
class JobContext(
    val app: Context,
    val job: ExportJob,
    private val listener: Listener,
) {
    interface Listener {
        fun onProgress(ctx: JobContext)
        fun onResult(ctx: JobContext, result: ItemResult)
    }

    @Volatile var cancelled = false
    @Volatile var statusText: String = "Starting…"; private set
    @Volatile var currentName: String? = null; private set
    private val partial = ConcurrentHashMap<Int, Double>()
    private val doneUnits = AtomicInteger(0)
    val results: MutableList<ItemResult> = java.util.Collections.synchronizedList(ArrayList<ItemResult>())

    val workload: WorkloadProfile get() = Workload.profile()

    val unitsDone: Int get() = doneUnits.get()

    val fraction: Double
        get() {
            val total = job.unitCount.coerceAtLeast(1)
            val p = doneUnits.get() + partial.values.sum()
            return (p / total).coerceIn(0.0, 1.0)
        }

    fun checkCancelled() {
        if (cancelled) throw ExportCancelledException()
    }

    fun status(text: String, name: String? = currentName) {
        statusText = text
        currentName = name
        listener.onProgress(this)
    }

    /** Progress inside unit [index] (0..1). */
    fun unitProgress(index: Int, f: Double) {
        partial[index] = f.coerceIn(0.0, 1.0)
        listener.onProgress(this)
    }

    fun unitDone(index: Int) {
        partial.remove(index)
        doneUnits.incrementAndGet()
        listener.onProgress(this)
    }

    fun addResult(r: ItemResult) {
        results.add(r)
        listener.onResult(this, r)
    }

    /** Test hook: fixed memory budget instead of one derived from free RAM. */
    @Volatile var budgetOverride: Long? = null

    fun memoryBudget(sharedBy: Int = 1): Long = budgetOverride?.let { it / sharedBy.coerceAtLeast(1) } ?: MemoryBudget.bitmapBytes(app, workload, sharedBy)

    // ------------------------------------------------------------------ throttling
    private class ThrottleState { var workStart = System.nanoTime() }
    private val throttleState = ThreadLocal.withInitial { ThrottleState() }

    /**
     * Cooperative duty cycling, called frequently from processing loops. At lower workloads it
     * inserts short sleeps proportional to the work just done. Also checks for cancellation.
     */
    fun throttle() {
        checkCancelled()
        val st = throttleState.get()!!
        val now = System.nanoTime()
        val workedMs = (now - st.workStart) / 1_000_000
        if (workedMs < 120) return
        val duty = workload.dutyCycle
        if (duty < 0.99) {
            val sleepMs = (workedMs * (1 - duty) / duty).toLong().coerceAtMost(1500)
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { }
            }
        }
        try { Process.setThreadPriority(workload.threadPriority) } catch (_: Exception) { }
        st.workStart = System.nanoTime()
        checkCancelled()
    }

    // ------------------------------------------------------------------ item runners
    /**
     * Runs [block] for each item, sequentially or in parallel (bounded by the live workload).
     * Converts exceptions into explicit per-item results; cancellation stops everything.
     */
    suspend fun forEachItem(
        items: List<MediaItem>,
        parallel: Boolean,
        block: suspend (index: Int, item: MediaItem) -> ItemResult,
    ) {
        val next = AtomicInteger(0)
        val active = AtomicInteger(0)
        val maxWorkers = if (parallel) Runtime.getRuntime().availableProcessors().coerceIn(1, 8) else 1
        coroutineScope {
            (0 until maxWorkers).map { w ->
                async(dispatcher) {
                    while (true) {
                        if (cancelled) break
                        // Respect the current workload's parallelism (it may change mid-export).
                        while (parallel && w >= workload.parallelism) {
                            if (cancelled || next.get() >= items.size) break
                            delay(200)
                        }
                        val i = next.getAndIncrement()
                        if (i >= items.size || cancelled) break
                        active.incrementAndGet()
                        val item = items[i]
                        status(if (items.size > 1) "File ${i + 1} of ${items.size}" else "Processing", item.name)
                        val result = runItem(item) { block(i, item) }
                        active.decrementAndGet()
                        if (result != null) addResult(result)
                        unitDone(i)
                    }
                }
            }.awaitAll()
        }
        checkCancelled()
    }

    /** Runs one item's work, mapping failures to results. Returns null only when cancelled. */
    suspend fun runItem(item: MediaItem, block: suspend () -> ItemResult): ItemResult? {
        item.readError?.let { return ItemResult(item.name, ItemOutcome.FAILED, it) }
        return try {
            withContext(dispatcher) {
                try { Process.setThreadPriority(workload.threadPriority) } catch (_: Exception) { }
                block()
            }
        } catch (e: ExportCancelledException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SkipItemException) {
            ItemResult(item.name, ItemOutcome.SKIPPED, e.message)
        } catch (e: OutOfMemoryError) {
            System.gc()
            ItemResult(item.name, ItemOutcome.FAILED, Errors.describe(e))
        } catch (e: Throwable) {
            android.util.Log.w("LMT", "item failed: ${item.name}", e)
            ItemResult(item.name, ItemOutcome.FAILED, Errors.describe(e))
        }
    }

    companion object {
        private val threadCount = AtomicInteger(0)
        /** Shared worker pool for all exports; priorities are adjusted per task. */
        val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
            ThreadFactory { r ->
                Thread({
                    try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Exception) { }
                    r.run()
                }, "lmt-worker-${threadCount.incrementAndGet()}").apply { isDaemon = true }
            }
        ).asCoroutineDispatcher()
    }
}
