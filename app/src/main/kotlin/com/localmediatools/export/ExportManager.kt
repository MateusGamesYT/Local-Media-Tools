package com.localmediatools.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.localmediatools.core.Errors
import com.localmediatools.core.ExportCancelledException
import com.localmediatools.core.OutputArea
import com.localmediatools.core.OutputFile
import com.localmediatools.core.Workload
import com.localmediatools.tools.ToolId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs exports one at a time in the app process (outside any screen), keeps a queue and a history,
 * and starts the foreground [ExportService] so Android keeps the process alive while the user is
 * in other apps. Closing a screen never cancels an export; only an explicit Cancel does.
 */
object ExportManager {
    data class State(
        val active: JobSnapshot? = null,
        val queued: List<JobSnapshot> = emptyList(),
        val history: List<JobSnapshot> = emptyList(),
    ) {
        val busy get() = active != null || queued.isNotEmpty()
        fun latestFor(tool: ToolId): JobSnapshot? =
            active?.takeIf { it.tool == tool } ?: queued.firstOrNull { it.tool == tool } ?: history.firstOrNull { it.tool == tool }
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val queue = ArrayDeque<ExportJob>()
    private var running: Pair<ExportJob, JobContext>? = null
    private var runner: kotlinx.coroutines.Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state
    private var lastPublish = 0L
    private var startedAt = 0L

    fun init(ctx: Context) {
        app = ctx.applicationContext
        _state.value = State(history = loadHistory())
    }

    fun enqueue(job: ExportJob) {
        synchronized(lock) {
            queue.addLast(job)
            if (runner == null) runner = scope.launch { runLoop() }
        }
        publish(force = true)
        startService()
    }

    fun cancel(jobId: Long) {
        synchronized(lock) {
            val r = running
            if (r != null && r.first.id == jobId) r.second.cancelled = true
            val q = queue.firstOrNull { it.id == jobId }
            if (q != null) {
                queue.remove(q)
                addHistory(snapshot(q, null, JobStatus.CANCELLED, 0L, SystemClock.elapsedRealtime()))
            }
        }
        publish(force = true)
    }

    fun cancelAll() {
        synchronized(lock) {
            for (q in queue) addHistory(snapshot(q, null, JobStatus.CANCELLED, 0, 0))
            queue.clear()
            running?.second?.cancelled = true
        }
        publish(force = true)
    }

    fun clearHistory() {
        _state.value = _state.value.copy(history = emptyList())
        saveHistory(emptyList())
    }

    private suspend fun runLoop() {
        while (true) {
            // Taking the next job and retiring the runner happen under one lock, so a job enqueued
            // concurrently is never stranded.
            val job = synchronized(lock) {
                val j = queue.removeFirstOrNull()
                if (j == null) runner = null
                j
            } ?: break
            val ctx = JobContext(app, job, listener)
            synchronized(lock) { running = job to ctx }
            startedAt = System.currentTimeMillis()
            publish(force = true)
            var status: JobStatus
            var error: String? = null
            try {
                job.run(ctx)
                status = when {
                    ctx.results.isEmpty() -> JobStatus.FAILED
                    ctx.results.all { it.outcome == ItemOutcome.SUCCESS } -> JobStatus.SUCCEEDED
                    ctx.results.any { it.outcome == ItemOutcome.SUCCESS } -> JobStatus.PARTIAL
                    ctx.results.all { it.outcome == ItemOutcome.SKIPPED } -> JobStatus.PARTIAL
                    else -> JobStatus.FAILED
                }
                if (ctx.results.isEmpty()) error = "Nothing was produced."
            } catch (e: ExportCancelledException) {
                status = JobStatus.CANCELLED
            } catch (e: kotlinx.coroutines.CancellationException) {
                status = JobStatus.CANCELLED
            } catch (e: Throwable) {
                android.util.Log.e("LMT", "job failed", e)
                status = JobStatus.FAILED
                error = Errors.describe(e)
            }
            if (ctx.cancelled) status = JobStatus.CANCELLED
            val snap = snapshot(job, ctx, status, startedAt, System.currentTimeMillis(), error)
            synchronized(lock) {
                running = null
                addHistory(snap)
            }
            publish(force = true)
            ExportNotifications.showFinished(app, snap)
        }
        publish(force = true)
    }

    private val listener = object : JobContext.Listener {
        override fun onProgress(ctx: JobContext) = publish(force = false)
        override fun onResult(ctx: JobContext, result: ItemResult) = publish(force = true)
    }

    private fun snapshot(job: ExportJob, ctx: JobContext?, status: JobStatus, start: Long, end: Long, error: String? = null) = JobSnapshot(
        id = job.id, tool = job.tool, title = job.title, status = status,
        unitCount = job.unitCount, unitsDone = ctx?.unitsDone ?: 0,
        fraction = if (status == JobStatus.SUCCEEDED || status == JobStatus.PARTIAL) 1.0 else ctx?.fraction ?: 0.0,
        currentName = ctx?.currentName, statusText = ctx?.statusText ?: "Waiting",
        results = ctx?.results?.toList() ?: emptyList(), workloadPercent = Workload.percent.value,
        startedAt = start, finishedAt = end, errorMessage = error,
    )

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublish < 150) return
        lastPublish = now
        val s = synchronized(lock) {
            val r = running
            State(
                active = r?.let { snapshot(it.first, it.second, JobStatus.RUNNING, startedAt, 0) },
                queued = queue.map { snapshot(it, null, JobStatus.QUEUED, 0, 0) },
                history = _state.value.history,
            )
        }
        _state.value = s
    }

    private fun addHistory(s: JobSnapshot) {
        val h = (listOf(s) + _state.value.history).take(40)
        _state.value = _state.value.copy(history = h)
        saveHistory(h)
    }

    private fun startService() {
        val intent = Intent(app, ExportService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        } catch (e: Exception) {
            // Background-start restrictions: the export still runs while the app is visible.
            android.util.Log.w("LMT", "could not start export service", e)
        }
    }

    // ------------------------------------------------------------------ history persistence
    private fun historyFile() = File(app.filesDir, "export_history.json")

    private fun saveHistory(list: List<JobSnapshot>) {
        try {
            val arr = JSONArray()
            for (s in list) {
                val o = JSONObject()
                o.put("id", s.id); o.put("tool", s.tool.name); o.put("title", s.title); o.put("status", s.status.name)
                o.put("units", s.unitCount); o.put("start", s.startedAt); o.put("end", s.finishedAt)
                o.put("workload", s.workloadPercent); o.put("error", s.errorMessage ?: JSONObject.NULL)
                val res = JSONArray()
                for (r in s.results) {
                    val ro = JSONObject()
                    ro.put("name", r.inputName); ro.put("outcome", r.outcome.name); ro.put("msg", r.message ?: JSONObject.NULL)
                    ro.put("details", r.details ?: JSONObject.NULL)
                    val outs = JSONArray()
                    for (f in r.outputs) {
                        outs.put(JSONObject().put("uri", f.uri.toString()).put("name", f.displayName).put("mime", f.mime)
                            .put("size", f.size).put("area", f.area.name))
                    }
                    ro.put("outputs", outs)
                    res.put(ro)
                }
                o.put("results", res)
                arr.put(o)
            }
            historyFile().writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadHistory(): List<JobSnapshot> = try {
        val f = historyFile()
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val tool = ToolId.entries.firstOrNull { it.name == o.getString("tool") } ?: return@mapNotNull null
                val res = o.getJSONArray("results")
                val results = (0 until res.length()).map { j ->
                    val r = res.getJSONObject(j)
                    val outs = r.getJSONArray("outputs")
                    ItemResult(
                        r.getString("name"), ItemOutcome.valueOf(r.getString("outcome")),
                        r.optString("msg").takeIf { !r.isNull("msg") },
                        (0 until outs.length()).map { k ->
                            val fo = outs.getJSONObject(k)
                            OutputFile(Uri.parse(fo.getString("uri")), fo.getString("name"), fo.getString("mime"), fo.getLong("size"),
                                OutputArea.valueOf(fo.getString("area")))
                        },
                        r.optString("details").takeIf { !r.isNull("details") },
                    )
                }
                JobSnapshot(o.getLong("id"), tool, o.getString("title"), JobStatus.valueOf(o.getString("status")),
                    o.getInt("units"), o.getInt("units"), 1.0, null, "", results, o.getInt("workload"),
                    o.getLong("start"), o.getLong("end"), o.optString("error").takeIf { !o.isNull("error") })
            }
        }
    } catch (_: Exception) { emptyList() }
}
