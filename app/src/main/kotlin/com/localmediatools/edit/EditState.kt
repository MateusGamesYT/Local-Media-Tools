package com.localmediatools.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import com.localmediatools.codec.edit.Adjustments
import com.localmediatools.codec.edit.FilterSpec
import com.localmediatools.codec.edit.Geometry
import java.io.File
import java.util.UUID

enum class PatchKind(val label: String) { ERASE("Erase"), BLUR("Blur"), PIXELATE("Pixelate") }

/** A retouched area in upright-photo pixels. Its pixels (with feathered alpha) live in a PNG file. */
data class RetouchPatch(val id: String, val kind: PatchKind, val left: Int, val top: Int, val width: Int, val height: Int) {
    val right get() = left + width
    val bottom get() = top + height
    fun intersects(l: Int, t: Int, r: Int, b: Int) = left < r && right > l && top < b && bottom > t
}

/** Everything the user changed. Immutable: undo/redo keeps a list of states. */
data class EditState(
    val geometry: Geometry = Geometry(),
    val adjust: Adjustments = Adjustments(),
    val filter: FilterSpec = FilterSpec(),
    val patches: List<RetouchPatch> = emptyList(),
) {
    val isIdentity get() = geometry.isIdentity && adjust.isIdentity && filter.isIdentity && patches.isEmpty()
}

/** A brush stroke in upright-photo pixels. */
class Stroke(val points: FloatArray, val radius: Float) {
    fun bounds(extra: Float = 0f): RectF {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i < points.size) { l = minOf(l, points[i]); r = maxOf(r, points[i]); t = minOf(t, points[i + 1]); b = maxOf(b, points[i + 1]); i += 2 }
        val e = radius + extra
        return RectF(l - e, t - e, r + e, b + e)
    }

    companion object {
        fun bounds(strokes: List<Stroke>, extra: Float = 0f): RectF {
            val r = RectF(strokes.first().bounds(extra))
            for (s in strokes.drop(1)) r.union(s.bounds(extra))
            return r
        }

        /** Draws strokes (filled, round) mapped by x' = (x − ox)·scale, with radius grown by [extra] source px. */
        fun draw(canvas: Canvas, strokes: List<Stroke>, ox: Float, oy: Float, scale: Float, extra: Float, color: Int = -1) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
            for (s in strokes) {
                val r = (s.radius + extra) * scale
                p.strokeWidth = 2 * r
                val pts = s.points
                if (pts.size >= 4) {
                    val path = android.graphics.Path()
                    path.moveTo((pts[0] - ox) * scale, (pts[1] - oy) * scale)
                    var i = 2
                    while (i < pts.size) { path.lineTo((pts[i] - ox) * scale, (pts[i + 1] - oy) * scale); i += 2 }
                    canvas.drawPath(path, p)
                }
                canvas.drawCircle((pts[0] - ox) * scale, (pts[1] - oy) * scale, r, fill)
                canvas.drawCircle((pts[pts.size - 2] - ox) * scale, (pts[pts.size - 1] - oy) * scale, r, fill)
            }
        }
    }
}

/**
 * Patch pixels for one editing session, kept in app-private storage so a background export can
 * still read them after the editor closes. The export deletes the folder when it finishes.
 */
class PatchStore(val dir: File) {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    init { dir.mkdirs() }

    fun save(kind: PatchKind, left: Int, top: Int, bmp: Bitmap): RetouchPatch {
        val id = UUID.randomUUID().toString()
        File(dir, "$id.png").outputStream().buffered().use { if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) throw java.io.IOException("Could not store the retouched area") }
        cache.put(id, bmp)
        return RetouchPatch(id, kind, left, top, bmp.width, bmp.height)
    }

    fun load(p: RetouchPatch): Bitmap {
        cache.get(p.id)?.let { if (!it.isRecycled) return it }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = true }
        val bmp = BitmapFactory.decodeFile(File(dir, "${p.id}.png").absolutePath, opts)
            ?: throw java.io.IOException("A retouched area is missing; please redo it.")
        cache.put(p.id, bmp)
        return bmp
    }

    fun deleteAll() {
        cache.evictAll()
        dir.deleteRecursively()
    }

    companion object {
        private fun root(ctx: Context) = File(ctx.filesDir, "editor")
        fun newSession(ctx: Context) = PatchStore(File(root(ctx), UUID.randomUUID().toString()))
        /** Called at app start: exports don't survive process death, so no session is still in use. */
        fun cleanupAll(ctx: Context) { try { root(ctx).deleteRecursively() } catch (_: Exception) { } }
    }
}
