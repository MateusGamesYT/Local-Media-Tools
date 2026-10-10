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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * Keeps the gallery index up to date: reads the library, then looks at new photos and videos one
 * by one (newest first) in the background, and regroups faces into people as they come in.
 * Runs at low priority, slows down when the phone is warm and pauses when it is very hot or the
 * battery is low.
 */
object GalleryIndex {
    /**
     * Bump when what the analyzer stores changes, so older results are redone. 3 (1.8.0): faces are
     * described with MobileFaceNet; items analysed by version [FACES_ONLY_FROM] only have their faces
     * looked at again (their tags stay, and what the user said about each face stays with it).
     */
    const val ANALYZER_VERSION = 3
    const val FACES_ONLY_FROM = 2

    /**
     * How hot the phone may get (PowerManager's thermal status): from [EASE_AT] indexing goes on at
     * about half speed (after each item, a pause as long as the item took), from [PAUSE_AT] it waits
     * until the phone is below it again. Until 1.8.0 it paused from SEVERE, which phones reach easily
     * during a long scan (Android already slows the processor down by then).
     */
    private const val EASE_AT = PowerManager.THERMAL_STATUS_SEVERE
    private const val PAUSE_AT = PowerManager.THERMAL_STATUS_CRITICAL

    /**
     * What an analysis is stored as: the version plus how capable the engines were, so photos looked
     * at with the basic fallback are looked at again once the AI engines work (e.g. after a
     * temporary failure), while a phone that only has the fallback doesn't redo them forever.
     */
    fun analyzedValue(faces: EngineMode, objects: EngineMode): Int {
        val f = when (faces) { EngineMode.AI -> 2; EngineMode.BASIC -> 1; EngineMode.OFF -> 0 }
        val o = if (objects == EngineMode.AI) 1 else 0
        return ANALYZER_VERSION * 10 + f * 2 + o
    }

    /** What the current engines would store; anything below is (re)analysed. */
    @Volatile var target = analyzedValue(EngineMode.AI, EngineMode.AI); private set

    enum class Phase { IDLE, READING, ANALYZING, GROUPING, PAUSED, DONE }

    data class State(
        val phase: Phase = Phase.IDLE,
        val done: Int = 0,
        val total: Int = 0,
        /** Why indexing is paused, when it is. */
        val pausedWhy: String? = null,
        /** Paused from the app or the notification (not for battery or heat). */
        val pausedByUser: Boolean = false,
        /** Faces found before are being described again by a newer face model (names are kept). */
        val updatingFaces: Boolean = false,
        /** Going slower because the phone is warm ([EASE_AT]). */
        val warm: Boolean = false,
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
    @Volatile private var lastStart = 0L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Increases whenever the index changed (new items, tags, faces or people), for screens to refresh. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    /** Stands in for the analyzer where models can't run (JVM tests). */
    @Volatile var analyzerOverride: ((GMedia) -> Analysis)? = null

    fun notifyChanged() { _changes.update { it + 1 } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    fun isPausedByUser(ctx: Context) = prefs(ctx).getBoolean("paused", false)

    fun setPaused(ctx: Context, paused: Boolean) {
        prefs(ctx).edit().putBoolean("paused", paused).apply()
        userPaused = paused
        if (paused) { job?.cancel(); _state.update { it.copy(phase = Phase.PAUSED, pausedWhy = "Paused", pausedByUser = true) } }
        else { _state.update { it.copy(pausedByUser = false) }; start(ctx) }
    }

    /**
     * Like [start] for screens that call it often: nothing if a pass is running (it already picks up
     * changes) or the library was read moments ago.
     */
    fun refresh(ctx: Context) {
        if (job?.isActive == true || System.currentTimeMillis() - lastStart < 120_000) return
        start(ctx)
    }

    /** Reads the library and analyses what is new. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        // Without access the library reads as (almost) empty: never sync then, or the index would be wiped.
        if (!GalleryLibrary.hasAccess(app)) return
        // From now on, new, edited and deleted photos are picked up while the app runs.
        GalleryLibrary.watch(app) { start(app) }
        userPaused = isPausedByUser(app)
        lastStart = System.currentTimeMillis()
        if (job?.isActive == true) { again = true; return }
        job = scope.launch {
            try {
                do {
                    again = false
                    try { run(app) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
                        android.util.Log.w("LMT", "gallery indexing failed", t)
                    }
                } while (again && isActive)
            } finally {
                // However it ended (error, cancel, stop), never leave the state saying it is working.
                _state.update { s ->
                    when {
                        userPaused -> s.copy(phase = Phase.PAUSED, pausedWhy = "Paused", pausedByUser = true)
                        s.working || (s.phase == Phase.PAUSED && !s.pausedByUser) -> s.copy(phase = Phase.IDLE, pausedWhy = null)
                        else -> s
                    }
                }
                analyzer = null
                ObjectTagger.release()
            }
        }
    }

    /** Stops indexing (e.g. when Android ends the background time); it resumes on the next start. */
    fun stop() { job?.cancel() }

    /** Whether a pass is running (tests wait on it). */
    val busy get() = job?.isActive == true

    /** Waits until the work queued on the indexing thread so far (e.g. a regrouping) has finished (tests). */
    fun drainForTests() = kotlinx.coroutines.runBlocking { scope.launch { }.join() }

    /** Back to a clean state (tests). */
    fun resetForTests() {
        job?.cancel(); job = null; analyzer = null; again = false; userPaused = false; lastStart = 0L
        _state.value = State()
    }

    private suspend fun run(ctx: Context) {
        val db = GalleryDb.get(ctx)
        _state.update { it.copy(phase = Phase.READING, pausedWhy = null) }
        val (changed, removed) = try { GalleryLibrary.sync(ctx, db) } catch (e: SecurityException) { 0 to 0 }
        // Removed photos took their faces with them; people left without faces go (no full regroup needed).
        if (removed > 0) db.pruneEmptyPeople()
        if (changed > 0 || removed > 0) notifyChanged()
        if (userPaused) {
            val (d, t) = db.counts(target)
            _state.update { it.copy(phase = Phase.PAUSED, done = d, total = t, pausedWhy = "Paused", pausedByUser = true) }
            return
        }
        val a = if (analyzerOverride != null) null else analyzer ?: GalleryAnalyzer.create(ctx).also { analyzer = it }
        target = if (a == null) analyzedValue(EngineMode.AI, EngineMode.AI) else analyzedValue(a.faceMode, a.objectMode)
        // Items without faces need nothing new from a face-model update; where the face AI doesn't work,
        // faces described before stay as they are (still grouped) instead of becoming basic ones.
        db.promoteUnchanged(FACES_ONLY_FROM, target, keepFaces = a != null && a.faceMode != EngineMode.AI)
        var (done, total) = db.counts(target)
        if (done >= total) {
            // Nothing new to look at, but people found under older grouping rules are regrouped once.
            if (!GalleryPeople.current(db)) regroup(ctx, db)
            _state.update { it.copy(phase = Phase.DONE, done = done, total = total) }
            return
        }
        _state.update { it.copy(phase = Phase.ANALYZING, done = done, total = total,
            faceMode = a?.faceMode ?: EngineMode.AI, objectMode = a?.objectMode ?: EngineMode.AI) }
        GalleryIndexService.ensure(ctx)
        var newFaces = 0
        var groupedFaces = db.faceCount()
        var lastNotify = System.currentTimeMillis()
        // Items that failed in this run aren't tried again until the next one.
        val attempted = HashSet<Long>()
        while (coroutineContext.isActive) {
            val batch = db.pending(target, 24, attempted)
            if (batch.isEmpty()) break
            for (m in batch) {
                if (!coroutineContext.isActive) return
                waitForGoodConditions(ctx, done, total)
                attempted.add(m.id)
                // Counted before looking, so a file that crashes the app is skipped after two tries.
                db.markAttempt(m.id)
                val t0 = System.nanoTime()
                // Analysed before with engines at least as capable for tags: only the faces are new.
                val prev = db.analyzedValue(m.id)
                val facesOnly = prev / 10 == FACES_ONLY_FROM && prev % 2 >= target % 2
                if (facesOnly != _state.value.updatingFaces) _state.update { it.copy(updatingFaces = facesOnly) }
                val result = try {
                    analyzerOverride?.invoke(m) ?: if (facesOnly) a!!.analyzeFaces(m) else a!!.analyze(m)
                } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
                if (result != null) {
                    val faces = result.faces.map { f ->
                        GFace(0, m.id, f.x, f.y, f.w, f.h, f.score, f.eyePx, f.yaw, f.good, f.quality, f.frameMs, f.kind, f.emb, null, false)
                    }
                    if (facesOnly) db.saveFaces(m.id, target, faces) else db.saveAnalysis(m.id, target, result.tags, faces)
                    // Faces described again keep their people until the regrouping at the end, so nobody
                    // shows up twice (old and new descriptions) on the way.
                    if (!facesOnly) newFaces += result.faces.count { it.emb != null }
                    done++
                    _state.update { it.copy(done = done) }
                }
                val now = System.currentTimeMillis()
                if (now - lastNotify > 10_000) { lastNotify = now; notifyChanged() }
                // People appear progressively; regrouping costs more as the library grows, so it
                // happens after a growing number of new faces (a few dozen times for 30,000 faces).
                if (newFaces >= maxOf(300, groupedFaces / 3)) {
                    groupedFaces += newFaces; newFaces = 0
                    regroup(ctx, db)
                }
                val warm = thermalStatus(ctx) >= EASE_AT
                if (warm != _state.value.warm) _state.update { it.copy(warm = warm) }
                pace(System.nanoTime() - t0, warm)
                // Let waiting work (naming, merging) in between items.
                yield()
            }
            val c = db.counts(target); done = c.first; total = c.second
            _state.update { it.copy(done = done, total = total) }
        }
        regroup(ctx, db)
        val (d, t) = db.counts(target)
        _state.update { it.copy(phase = Phase.DONE, done = d, total = t, updatingFaces = false, warm = false) }
        notifyChanged()
    }

    /** Duty cycle from the workload setting (lighter settings leave gaps between items), at most half while [warm]. */
    private suspend fun pace(workNanos: Long, warm: Boolean) {
        val pct = Workload.percent.value
        val ratio = maxOf(if (warm) 1.0 else 0.0, when {
            pct >= 90 -> 0.0
            pct >= 60 -> 0.25
            pct >= 35 -> 0.8
            else -> 1.6
        })
        val ms = (workNanos / 1_000_000 * ratio).toLong()
        if (ms > 0) delay(ms.coerceAtMost(3000))
    }

    /** Waits while the phone is very hot or the battery is low (and not charging). */
    private suspend fun waitForGoodConditions(ctx: Context, done: Int, total: Int) {
        while (coroutineContext.isActive) {
            val why = holdReason(ctx) ?: break
            _state.update { it.copy(phase = Phase.PAUSED, pausedWhy = why, done = done, total = total) }
            delay(30_000)
        }
        _state.update { if (it.phase == Phase.PAUSED && !it.pausedByUser) it.copy(phase = Phase.ANALYZING, pausedWhy = null) else it }
    }

    private fun thermalStatus(ctx: Context) = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus

    private fun holdReason(ctx: Context): String? {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.currentThermalStatus >= PAUSE_AT) return "Waiting for the phone to cool down"
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

    fun isCharging(ctx: Context): Boolean {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** Regroups faces into people now (also after the user named, merged or corrected someone). */
    fun regroupNow(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            try { regroup(app, GalleryDb.get(app)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
                android.util.Log.w("LMT", "gallery regrouping failed", t)
            }
            notifyChanged()
        }
    }

    private suspend fun regroup(ctx: Context, db: GalleryDb) {
        val prev = _state.value.phase
        _state.update { if (it.phase == Phase.PAUSED && it.pausedByUser) it else it.copy(phase = Phase.GROUPING) }
        try {
            val job = coroutineContext[Job]
            GalleryPeople.regroup(db) { job?.isActive == false }
        } finally {
            // Only undo our own change: a pause (or anything else) set meanwhile stays.
            _state.update { if (it.phase == Phase.GROUPING) it.copy(phase = if (prev == Phase.GROUPING) Phase.IDLE else prev) else it }
        }
        notifyChanged()
    }
}

/** Turns face groups into people rows, keeping names and stable ids for unnamed groups. */
object GalleryPeople {
    /**
     * The basic (LBP) descriptor is large (3,776 numbers, ~15 KB a face) and only groups
     * near-identical faces, so at most this many of the best are grouped; the others stay on their own.
     */
    private const val MAX_BASIC = 3000

    /** Bumped when the grouping rules change, so libraries grouped before are regrouped (1.5.0: mean-face grouping). */
    const val VERSION = 2
    private const val VERSION_KEY = "grouping_version"

    /** Whether the people were last grouped with the current rules. */
    fun current(db: GalleryDb) = db.meta(VERSION_KEY) == VERSION.toString()

    fun regroup(db: GalleryDb, cancelled: () -> Boolean) {
        val rejected = db.notPeople()
        val assign = HashMap<Long, Long?>()
        val used = HashSet<Long>()
        val unnamed = db.people(includeHidden = true).filter { !it.named }.mapTo(HashSet()) { it.id }
        for (kind in FaceKind.entries) {
            val basic = kind == FaceKind.LBP
            val list = db.faces("f.emb IS NOT NULL AND f.ignored = 0 AND f.kind = ?", arrayOf(kind.code.toString()), withEmb = true,
                order = if (basic) "f.confirmed DESC, f.quality DESC, f.id" else "f.id", limit = if (basic) MAX_BASIC else null)
            if (list.isEmpty()) continue
            val params = ClusterParams.of(kind)
            val recs = list.map { f ->
                FaceRec(f.id, f.emb!!, f.quality, f.good, if (f.confirmed) f.personId else null, rejected[f.id] ?: LongArray(0), params.isUsable(f.score))
            }
            val previous = list.associate { it.id to it.personId }
            val groups = FaceClustering.cluster(recs, params, cancelled)
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
        db.setMeta(VERSION_KEY, VERSION.toString())
    }
}
