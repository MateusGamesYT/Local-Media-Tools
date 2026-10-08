package com.localmediatools.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.localmediatools.codec.edit.Affine
import com.localmediatools.codec.edit.GeometryPlan
import com.localmediatools.core.MediaItem
import com.localmediatools.core.MemoryBudget
import com.localmediatools.core.Workload
import com.localmediatools.edit.EditRenderer
import com.localmediatools.edit.EditState
import com.localmediatools.edit.Inpainter
import com.localmediatools.edit.Inpainters
import com.localmediatools.edit.PatchKind
import com.localmediatools.edit.PatchStore
import com.localmediatools.edit.RetouchPatch
import com.localmediatools.edit.Retouch
import com.localmediatools.edit.RetouchedSource
import com.localmediatools.edit.Stroke
import com.localmediatools.image.BitmapOps
import com.localmediatools.image.ImageSource
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * One photo being edited: a reduced working copy for previews, the full-resolution source for
 * retouching, and the undo/redo history. Every bitmap operation runs on [worker], one at a time.
 */
class EditorSession(val app: Context, val item: MediaItem) : Closeable {
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lmt-editor").apply { isDaemon = true } }.asCoroutineDispatcher()
    val store = PatchStore.newSession(app)
    lateinit var source: ImageSource; private set
    var srcW = 0; private set
    var srcH = 0; private set
    /** Working-copy pixels per full-resolution pixel. */
    var proxyScale = 1.0; private set
    private lateinit var proxyBase: Bitmap
    lateinit var proxy: Bitmap; private set
    var hasAlpha = false; private set
    var saved = false

    private val history = arrayListOf(EditState())
    @Volatile private var index = 0
    val state: EditState get() = history[index]
    /** Uncommitted state while a slider or crop handle is being dragged. */
    @Volatile var live: EditState? = null
    val current: EditState get() = live ?: state
    val canUndo get() = index > 0
    val canRedo get() = index < history.size - 1
    val changed get() = index > 0 || live != null

    private var inpainter: Inpainter? = null
    val inpainterLabel: String? get() = inpainter?.label

    fun budget() = MemoryBudget.bitmapBytes(app, Workload.profile(), 1)

    /** Opens the photo and decodes the working copy (longest side up to [maxSide]). */
    fun load(maxSide: Int = 2048) {
        source = ImageSource.open(app, item)
        srcW = source.width; srcH = source.height
        var sample = 1
        while (max(srcW, srcH) / sample > maxSide) sample *= 2
        val raw = BitmapOps.ensureArgb8888(source.decode(sample, budget()))
        proxyBase = if (raw.isMutable) raw else raw.copy(Bitmap.Config.ARGB_8888, true).also { raw.recycle() }
        proxyScale = proxyBase.width.toDouble() / srcW
        hasAlpha = BitmapOps.hasTransparency(proxyBase)
        proxy = proxyBase.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun commit(s: EditState) {
        val before = state
        live = null
        while (history.size > index + 1) history.removeAt(history.size - 1)
        history.add(s); index = history.size - 1
        if (history.size > 80) { history.removeAt(0); index-- }
        if (before.patches != s.patches) rebuildProxy()
    }

    fun undo(): Boolean {
        if (!canUndo) return false
        val before = state; live = null; index--
        if (before.patches != state.patches) rebuildProxy()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        val before = state; live = null; index++
        if (before.patches != state.patches) rebuildProxy()
        return true
    }

    private fun drawPatch(c: Canvas, p: RetouchPatch, paint: Paint) {
        val s = proxyScale.toFloat()
        c.drawBitmap(store.load(p), null, RectF(p.left * s, p.top * s, p.right * s, p.bottom * s), paint)
    }

    private fun rebuildProxy() {
        val fresh = proxyBase.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(fresh); val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (p in state.patches) drawPatch(c, p, paint)
        proxy = fresh
    }

    fun plan(s: EditState = current) = GeometryPlan(srcW, srcH, s.geometry)

    /** Final look at preview size; returns the bitmap and its pixels per output pixel. */
    fun renderOutput(s: EditState, maxSide: Int): Pair<Bitmap, Double> {
        val plan = plan(s)
        val scale = minOf(1.0, maxSide.toDouble() / max(plan.outW, plan.outH))
        return EditRenderer.preview(proxy, proxyScale, plan.forward, plan.outW, plan.outH, scale, s) to scale
    }

    /** The straightened frame without the crop (for the crop overlay). */
    fun renderFrame(s: EditState, maxSide: Int): Pair<Bitmap, Double> {
        val plan = plan(s)
        val fw = kotlin.math.ceil(plan.frameW).toInt(); val fh = kotlin.math.ceil(plan.frameH).toInt()
        val scale = minOf(1.0, maxSide.toDouble() / max(fw, fh))
        return EditRenderer.preview(proxy, proxyScale, plan.toFrame, fw, fh, scale, s) to scale
    }

    fun renderOriginal(maxSide: Int): Bitmap {
        val scale = minOf(1.0, maxSide.toDouble() / max(srcW, srcH))
        return EditRenderer.preview(proxyBase, proxyScale, Affine.IDENTITY, srcW, srcH, scale, EditState(), colors = false)
    }

    /** Small renders of each look for the filter strip (geometry and adjustments applied). */
    fun renderSmall(s: EditState, maxSide: Int): Bitmap {
        val plan = plan(s)
        val scale = minOf(1.0, maxSide.toDouble() / max(plan.outW, plan.outH))
        return EditRenderer.preview(proxy, proxyScale, plan.forward, plan.outW, plan.outH, scale, s, colors = false)
    }

    fun ensureInpainter(): Inpainter = inpainter ?: Inpainters.create(app).also { inpainter = it }

    /** Runs a retouch at full resolution and records it as a new history step. */
    fun retouch(kind: PatchKind, strokes: List<Stroke>, strength: Float): RetouchPatch {
        val rs = RetouchedSource(source, store, state.patches)
        val patch = if (kind == PatchKind.ERASE) Retouch.erase(rs, strokes, ensureInpainter(), store, budget())
        else Retouch.obscure(rs, strokes, kind, strength, store, budget())
        commit(state.copy(patches = state.patches + patch))
        return patch
    }

    override fun close() {
        try { inpainter?.close() } catch (_: Exception) { }
        try { if (::source.isInitialized) source.close() } catch (_: Exception) { }
        if (!saved) store.deleteAll()
        worker.close()
    }
}
