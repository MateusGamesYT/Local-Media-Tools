package com.localmediatools.gallery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.localmediatools.core.Workload
import com.localmediatools.gallery.core.ClusterParams
import com.localmediatools.gallery.core.FaceClustering
import com.localmediatools.gallery.core.FaceKind
import com.localmediatools.gallery.core.FaceRec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * Keeps the gallery index up to date: reads the library, then looks at new photos and videos one
 * by one (newest first) in the background, and regroups faces into people as they come in.
 * Runs at low priority, eases off when the phone is hot and pauses when the battery is low.
 */
object GalleryIndex {
    /** Bump when what the analyzer stores changes, so older results are redone. */
    const val ANALYZER_VERSION = 1

    enum class Phase { IDLE, READING, ANALYZING, GROUPING, PAUSED, DONE }

    data class State(
        val phase: Phase = Phase.IDLE,
        val done: Int = 0,
        val total: Int = 0,
        /** Why indexing is paused, when it is. */
        val pausedWhy: String? = null,
        /** Paused from the app or the notification (not for battery or heat). */
        val pausedByUser: Boolean = false,
        val faceMode: EngineMode? = null,
        val objectMode: EngineMode? = null,
    ) {
        val working get() = phase == Phase.READING || phase == Phase.ANALYZING || phase == Phase.GROUPING
        val fraction get() = if (total <= 0) 0f else done.toFloat() / total
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "lmt-gallery").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var job: Job? = null
    private var analyzer: GalleryAnalyzer? = null
    @Volatile private var userPaused = false
    @Volatile private var again = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Increases whenever the index changed (new items, tags, faces or people), for screens to refresh. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    /** Stands in for the analyzer where models can't run (JVM tests). */
    @Volatile var analyzerOverride: ((GMedia) -> Analysis)? = null

    fun notifyChanged() { _changes.value = _changes.value + 1 }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    fun isPausedByUser(ctx: Context) = prefs(ctx).getBoolean("paused", false)

    fun setPaused(ctx: Context, paused: Boolean) {
        prefs(ctx).edit().putBoolean("paused", paused).apply()
        userPaused = paused
        if (paused) { job?.cancel(); _state.value = _state.value.copy(phase = Phase.PAUSED, pausedWhy = "Paused", pausedByUser = true) }
        else { _state.value = _state.value.copy(pausedByUser = false); start(ctx) }
    }

    /** Reads the library and analyses what is new. Safe to call often (e.g. every time the gallery opens). */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        // From now on, new, edited and deleted photos are picked up while the app runs.
        GalleryLibrary.watch(app) { start(app) }
        userPaused = isPausedByUser(app)
        if (job?.isActive == true) { again = true; return }
        job = scope.launch {
            do {
                again = false
                try { run(app) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
                    android.util.Log.w("LMT", "gallery indexing failed", t)
                }
            } while (again && isActive)
        }
    }

    fun stop() { job?.cancel() }

    /** Back to a clean state (tests). */
    fun resetForTests() {
        job?.cancel(); job = null; analyzer = null; again = false; userPaused = false
        _state.value = State()
    }

    private suspend fun run(ctx: Context) {
        val db = GalleryDb.get(ctx)
        _state.value = _state.value.copy(phase = Phase.READING, pausedWhy = null)
        val (changed, removed) = try { GalleryLibrary.sync(ctx, db) } catch (e: SecurityException) { 0 to 0 }
        if (changed > 0 || removed > 0) notifyChanged()
        if (removed > 0) regroup(ctx, db)
        var (done, total) = db.counts(ANALYZER_VERSION)
        if (userPaused) { _state.value = _state.value.copy(phase = Phase.PAUSED, done = done, total = total, pausedWhy = "Paused", pausedByUser = true); return }
        if (done >= total) { _state.value = _state.value.copy(phase = Phase.DONE, done = done, total = total); return }
        val a = if (analyzerOverride != null) null else analyzer ?: GalleryAnalyzer.create(ctx).also { analyzer = it }
        _state.value = _state.value.copy(phase = Phase.ANALYZING, done = done, total = total,
            faceMode = a?.faceMode ?: EngineMode.AI, objectMode = a?.objectMode ?: EngineMode.AI)
        GalleryIndexService.ensure(ctx)
        var newFaces = 0
        var lastNotify = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val batch = db.pending(ANALYZER_VERSION, 24)
            if (batch.isEmpty()) break
            for (m in batch) {
                if (!coroutineContext.isActive) return
                waitForGoodConditions(ctx, done, total)
                val t0 = System.nanoTime()
                val result = try {
                    analyzerOverride?.invoke(m) ?: a!!.analyze(m)
                } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
                if (result == null) { db.markFailed(m.id); done++; continue }
                db.saveAnalysis(m.id, ANALYZER_VERSION, result.tags, result.faces.map { f ->
                    GFace(0, m.id, f.x, f.y, f.w, f.h, f.score, f.eyePx, f.yaw, f.good, f.quality, 0, f.kind, f.emb, null, false)
                })
                newFaces += result.faces.count { it.emb != null }
                done++
                _state.value = _state.value.copy(done = done)
                val now = System.currentTimeMillis()
                if (now - lastNotify > 2500) { lastNotify = now; notifyChanged() }
                // People appear progressively: regroup every few hundred new faces.
                if (newFaces >= 300) { newFaces = 0; regroup(ctx, db); _state.value = _state.value.copy(phase = Phase.ANALYZING) }
                pace(System.nanoTime() - t0)
            }
            total = db.counts(ANALYZER_VERSION).second
            _state.value = _state.value.copy(total = total)
        }
        regroup(ctx, db)
        val (d, t) = db.counts(ANALYZER_VERSION)
        _state.value = _state.value.copy(phase = Phase.DONE, done = d, total = t)
        notifyChanged()
    }

    /** Duty cycle from the workload setting: lighter settings leave gaps between items. */
    private suspend fun pace(workNanos: Long) {
        val pct = Workload.percent.value
        val ratio = when {
            pct >= 90 -> 0.0
            pct >= 60 -> 0.25
            pct >= 35 -> 0.8
            else -> 1.6
        }
        val ms = (workNanos / 1_000_000 * ratio).toLong()
        if (ms > 0) delay(ms.coerceAtMost(3000))
    }

    /** Waits while the phone is hot or the battery is low (and not charging). */
    private suspend fun waitForGoodConditions(ctx: Context, done: Int, total: Int) {
        while (coroutineContext.isActive) {
            val why = holdReason(ctx) ?: break
            _state.value = _state.value.copy(phase = Phase.PAUSED, pausedWhy = why, done = done, total = total)
            delay(30_000)
        }
        if (_state.value.phase == Phase.PAUSED) _state.value = _state.value.copy(phase = Phase.ANALYZING, pausedWhy = null)
    }

    private fun holdReason(ctx: Context): String? {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return "Waiting for the phone to cool down"
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (!plugged && level >= 0 && level * 100 / scale < 15) return "Paused to save battery (below 15%)"
        }
        if (Build.VERSION.SDK_INT >= 29 && pm.isPowerSaveMode && !isCharging(ctx)) return "Paused while Battery Saver is on"
        return null
    }

    private fun isCharging(ctx: Context): Boolean {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** Regroups faces into people now (also after the user named, merged or corrected someone). */
    fun regroupNow(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { regroup(app, GalleryDb.get(app)); notifyChanged() }
    }

    private suspend fun regroup(ctx: Context, db: GalleryDb) {
        val prev = _state.value.phase
        _state.value = _state.value.copy(phase = Phase.GROUPING)
        try {
            val job = coroutineContext[Job]
            GalleryPeople.regroup(db) { job?.isActive == false }
        } finally {
            _state.value = _state.value.copy(phase = prev)
        }
        notifyChanged()
    }
}

/** Turns face groups into people rows, keeping names and stable ids for unnamed groups. */
object GalleryPeople {
    fun regroup(db: GalleryDb, cancelled: () -> Boolean) {
        val faces = db.faces("f.emb IS NOT NULL AND f.ignored = 0", withEmb = true, order = "f.id")
        if (faces.isEmpty()) { db.applyGrouping(emptyMap()); return }
        val rejected = db.notPeople()
        val assign = HashMap<Long, Long?>()
        val used = HashSet<Long>()
        val unnamed = db.people(includeHidden = true).filter { !it.named }.mapTo(HashSet()) { it.id }
        for (kind in FaceKind.entries) {
            val list = faces.filter { it.kind == kind }
            if (list.isEmpty()) continue
            val recs = list.map { f ->
                FaceRec(f.id, f.emb!!, f.quality, f.good, if (f.confirmed) f.personId else null, rejected[f.id] ?: LongArray(0))
            }
            val previous = list.associate { it.id to it.personId }
            val groups = FaceClustering.cluster(recs, ClusterParams.of(kind), cancelled)
            if (cancelled()) return
            // Bigger groups pick their old id first.
            for (g in groups.sortedByDescending { it.faces.size }) {
                val id: Long? = when {
                    g.person != null -> g.person
                    g.faces.size < 2 -> null
                    else -> {
                        val votes = HashMap<Long, Int>()
                        for (f in g.faces) previous[f]?.let { p -> if (p in unnamed && p !in used) votes[p] = (votes[p] ?: 0) + 1 }
                        votes.maxByOrNull { it.value }?.key ?: db.newPerson(null)
                    }
                }
                if (id != null) used.add(id)
                for (f in g.faces) assign[f] = id
            }
        }
        db.applyGrouping(assign)
    }
}
