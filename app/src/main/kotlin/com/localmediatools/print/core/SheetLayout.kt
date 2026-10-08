package com.localmediatools.print.core

import kotlin.math.abs
import kotlin.math.min

/** A rectangle in millimetres on the sheet (or fractions, for crops). */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
    companion object { val UNIT = Box(0f, 0f, 1f, 1f) }
}

/** One thing to print: a photo or one page of a document, with its size (any unit; only the shape matters, except for actual-size pages in points). */
data class PageRef(val item: Int, val page: Int, val width: Float, val height: Float, val document: Boolean)

/**
 * Where one photo or page goes on a sheet: [crop] (fractions of the source, before turning) is
 * drawn into [dest] (mm), turned a quarter clockwise when [rotate]; nothing is drawn outside [clip].
 */
data class Placement(val ref: PageRef, val dest: Box, val crop: Box, val rotate: Boolean, val clip: Box)

class Sheet(val placements: List<Placement>)

enum class Fit {
    /** All of it, as large as fits. */
    FIT,
    /** Fill the space, trimming what sticks out. */
    FILL,
}

class LayoutOptions(
    /** Photos or pages per sheet: 1, 2, 4, 6 or 9. */
    val perSheet: Int = 1,
    val photos: Fit = Fit.FIT,
    /** Document pages at their real size instead of fitted. */
    val actualSize: Boolean = false,
    val borderless: Boolean = false,
    /** Space between photos when several share a sheet. */
    val gapMm: Float = 4f,
)

/** Lays out photos and pages on sheets of paper. */
object SheetLayout {
    val PER_SHEET = listOf(1, 2, 4, 6, 9)

    /** Columns × rows for [n] per sheet on a portrait area. */
    fun grid(n: Int): Pair<Int, Int> = when (n) { 1 -> 1 to 1; 2 -> 1 to 2; 4 -> 2 to 2; 6 -> 2 to 3; 9 -> 3 to 3; else -> throw IllegalArgumentException("$n per sheet") }

    /** The area that can be printed on a [w]×[h] mm sheet. */
    fun printable(w: Float, h: Float, m: Margins, borderless: Boolean): Box =
        if (borderless) Box(0f, 0f, w, h) else Box(m.left / 100f, m.top / 100f, w - (m.left + m.right) / 100f, h - (m.top + m.bottom) / 100f)

    fun plan(pages: List<PageRef>, paper: MediaSize, margins: Margins, o: LayoutOptions): List<Sheet> {
        val area = printable(paper.widthMm, paper.heightMm, margins, o.borderless)
        val n = o.perSheet
        var (cols, rows) = grid(n)
        if (area.w > area.h) { val t = cols; cols = rows; rows = t }
        val gap = if (n > 1) o.gapMm else 0f
        val cw = (area.w - gap * (cols - 1)) / cols
        val ch = (area.h - gap * (rows - 1)) / rows
        return pages.chunked(n).map { group ->
            Sheet(group.mapIndexed { k, ref ->
                val cell = Box(area.x + (k % cols) * (cw + gap), area.y + (k / cols) * (ch + gap), cw, ch)
                place(ref, cell, o)
            })
        }
    }

    fun place(ref: PageRef, cell: Box, o: LayoutOptions): Placement {
        val landscape = ref.width > ref.height
        val cellLandscape = cell.w > cell.h
        // Turn the photo or page when its shape fits the space better that way (not for square ones).
        val rotate = landscape != cellLandscape && abs(ref.width - ref.height) > 0.02f * maxOf(ref.width, ref.height) && abs(cell.w - cell.h) > 0.02f * maxOf(cell.w, cell.h)
        val w = if (rotate) ref.height else ref.width
        val h = if (rotate) ref.width else ref.height
        if (ref.document && o.actualSize) {
            val wmm = w / 72f * 25.4f; val hmm = h / 72f * 25.4f
            return Placement(ref, Box(cell.x + (cell.w - wmm) / 2, cell.y + (cell.h - hmm) / 2, wmm, hmm), Box.UNIT, rotate, cell)
        }
        val fill = !ref.document && o.photos == Fit.FILL
        if (!fill) {
            val s = min(cell.w / w, cell.h / h)
            val dw = w * s; val dh = h * s
            return Placement(ref, Box(cell.x + (cell.w - dw) / 2, cell.y + (cell.h - dh) / 2, dw, dh), Box.UNIT, rotate, cell)
        }
        // Fill: keep the middle of the photo, trimmed to the cell's shape.
        val cellAspect = cell.w / cell.h
        val aspect = w / h
        var cx = 0f; var cy = 0f; var cwf = 1f; var chf = 1f // crop in turned coordinates
        if (aspect > cellAspect) { cwf = cellAspect / aspect; cx = (1 - cwf) / 2 } else { chf = aspect / cellAspect; cy = (1 - chf) / 2 }
        // Back to the source's own orientation (a quarter turn clockwise maps source (u, v) to (1 - v, u)).
        val crop = if (!rotate) Box(cx, cy, cwf, chf) else Box(cy, 1 - cx - cwf, chf, cwf)
        return Placement(ref, cell, crop, rotate, cell)
    }
}

/**
 * The affine transform drawing a [srcW]×[srcH] source (pixels, or points for a PDF page) as this
 * placement on a sheet rendered at [pxPerMm]: x' = a·x + b·y + c, y' = d·x + e·y + f, returned as
 * (a, b, c, d, e, f) — the order of android.graphics.Matrix values.
 */
fun Placement.matrix(srcW: Float, srcH: Float, pxPerMm: Float): FloatArray {
    val k = pxPerMm
    val cl = crop.x * srcW; val ct = crop.y * srcH
    val cw = crop.w * srcW; val ch = crop.h * srcH
    return if (!rotate) floatArrayOf(
        k * dest.w / cw, 0f, k * (dest.x - cl * dest.w / cw),
        0f, k * dest.h / ch, k * (dest.y - ct * dest.h / ch),
    ) else floatArrayOf(
        // A quarter turn clockwise: the source's top edge ends up on the right.
        0f, -k * dest.w / ch, k * (dest.x + dest.w + ct * dest.w / ch),
        k * dest.h / cw, 0f, k * (dest.y - cl * dest.h / cw),
    )
}
