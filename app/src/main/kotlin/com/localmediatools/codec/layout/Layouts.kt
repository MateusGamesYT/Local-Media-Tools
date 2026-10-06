package com.localmediatools.codec.layout

/** Integer rectangle, [x, x + w) × [y, y + h). */
data class IRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
    fun intersects(o: IRect) = x < o.right && o.x < right && y < o.bottom && o.y < bottom
    fun contains(o: IRect) = o.x >= x && o.y >= y && o.right <= right && o.bottom <= bottom
}

data class PackResult(val width: Int, val height: Int, val positions: List<IRect>)

enum class Align { START, CENTER, END }

/** Layouts for merging images without scaling or cropping any of them. */
object MergeLayouts {

    fun vertical(sizes: List<Pair<Int, Int>>, spacing: Int, align: Align): PackResult {
        val width = sizes.maxOf { it.first }
        var y = 0
        val pos = ArrayList<IRect>()
        for ((w, h) in sizes) {
            val x = when (align) { Align.START -> 0; Align.CENTER -> (width - w) / 2; Align.END -> width - w }
            pos.add(IRect(x, y, w, h))
            y += h + spacing
        }
        return PackResult(width, y - spacing, pos)
    }

    fun horizontal(sizes: List<Pair<Int, Int>>, spacing: Int, align: Align): PackResult {
        val height = sizes.maxOf { it.second }
        var x = 0
        val pos = ArrayList<IRect>()
        for ((w, h) in sizes) {
            val y = when (align) { Align.START -> 0; Align.CENTER -> (height - h) / 2; Align.END -> height - h }
            pos.add(IRect(x, y, w, h))
            x += w + spacing
        }
        return PackResult(x - spacing, height, pos)
    }

    /**
     * Packs rectangles into a compact canvas (MaxRects, best-short-side-fit) trying many canvas
     * widths and several insertion orders, keeping the layout with the smallest bounding area
     * (ties broken towards a squarer canvas). Rectangles are never rotated.
     */
    fun smartPack(sizes: List<Pair<Int, Int>>, spacing: Int): PackResult {
        require(sizes.isNotEmpty())
        if (sizes.size == 1) return PackResult(sizes[0].first, sizes[0].second, listOf(IRect(0, 0, sizes[0].first, sizes[0].second)))
        val s = spacing.coerceAtLeast(0)
        val padded = sizes.map { (w, h) -> (w + s).toLong() to (h + s).toLong() }
        val maxW = padded.maxOf { it.first }
        val sumW = padded.sumOf { it.first }
        val area = padded.sumOf { it.first * it.second }
        val root = Math.sqrt(area.toDouble())
        val candidates = sortedSetOf<Long>()
        candidates.add(maxW)
        candidates.add(sumW)
        var f = 0.5
        while (f <= 2.5) {
            candidates.add(maxOf(maxW, minOf(sumW, (root * f).toLong())))
            f += 0.04
        }
        // Widths formed by rows of the widest items are often optimal for similar-sized images.
        val byWidth = padded.map { it.first }.sortedDescending()
        var acc = 0L
        for (w in byWidth) { acc += w; if (acc in maxW..sumW) candidates.add(acc) }

        val orders: List<List<Int>> = listOf(
            sizes.indices.sortedWith(compareByDescending<Int> { padded[it].second }.thenByDescending { padded[it].first }),
            sizes.indices.sortedByDescending { padded[it].first * padded[it].second },
            sizes.indices.sortedByDescending { maxOf(padded[it].first, padded[it].second) },
            sizes.indices.sortedWith(compareByDescending<Int> { padded[it].first }.thenByDescending { padded[it].second }),
        )
        var best: PackResult? = null
        var bestScore = Double.MAX_VALUE
        // MaxRects is quadratic in the number of free rectangles; large batches use shelf packing.
        val large = sizes.size > 80
        val widths = if (candidates.size > 70) candidates.filterIndexed { i, _ -> i % (candidates.size / 70 + 1) == 0 } else candidates.toList()
        for (binW in widths) {
            if (binW > Int.MAX_VALUE / 2) continue
            for (order in if (large) orders.take(1) else orders) {
                val r = (if (large) shelf(padded, order, binW.toInt()) else maxRects(padded, order, binW.toInt())) ?: continue
                val w = r.width - s; val h = r.height - s
                val aspect = Math.abs(Math.log(w.toDouble() / h))
                val score = w.toDouble() * h * (1 + 0.04 * aspect)
                if (score < bestScore) {
                    bestScore = score
                    best = PackResult(w, h, r.positions.mapIndexed { i, p -> IRect(p.x, p.y, sizes[i].first, sizes[i].second) })
                }
            }
        }
        return best ?: vertical(sizes, s, Align.START)
    }

    /** Rows ("shelves") filled left to right in the given order. */
    private fun shelf(sizes: List<Pair<Long, Long>>, order: List<Int>, binW: Int): PackResult? {
        val pos = arrayOfNulls<IRect>(sizes.size)
        var x = 0; var y = 0; var rowH = 0; var usedW = 0
        for (i in order) {
            val w = sizes[i].first.toInt(); val h = sizes[i].second.toInt()
            if (w > binW) return null
            if (x + w > binW) { y += rowH; x = 0; rowH = 0 }
            pos[i] = IRect(x, y, w, h)
            x += w; rowH = maxOf(rowH, h); usedW = maxOf(usedW, x)
        }
        return PackResult(usedW, y + rowH, pos.map { it!! })
    }

    private fun maxRects(sizes: List<Pair<Long, Long>>, order: List<Int>, binW: Int): PackResult? {
        val binH = sizes.sumOf { it.second }.coerceAtMost(Int.MAX_VALUE.toLong() / 2).toInt()
        val free = ArrayList<IRect>()
        free.add(IRect(0, 0, binW, binH))
        val pos = arrayOfNulls<IRect>(sizes.size)
        var usedW = 0; var usedH = 0
        for (i in order) {
            val w = sizes[i].first.toInt(); val h = sizes[i].second.toInt()
            var bestRect: IRect? = null
            var bestY = Int.MAX_VALUE; var bestShort = Int.MAX_VALUE
            for (fr in free) {
                if (fr.w >= w && fr.h >= h) {
                    // Prefer low placements (keeps the canvas short), then best short-side fit.
                    val shortSide = minOf(fr.w - w, fr.h - h)
                    val y = fr.y
                    if (y < bestY || (y == bestY && shortSide < bestShort) ||
                        (y == bestY && shortSide == bestShort && fr.x < (bestRect?.x ?: Int.MAX_VALUE))) {
                        bestRect = IRect(fr.x, fr.y, w, h); bestY = y; bestShort = shortSide
                    }
                }
            }
            val placed = bestRect ?: return null
            pos[i] = placed
            usedW = maxOf(usedW, placed.right); usedH = maxOf(usedH, placed.bottom)
            // Split free rectangles intersecting the placed one.
            val newFree = ArrayList<IRect>()
            val it = free.iterator()
            while (it.hasNext()) {
                val fr = it.next()
                if (!fr.intersects(placed)) continue
                it.remove()
                if (placed.x > fr.x) newFree.add(IRect(fr.x, fr.y, placed.x - fr.x, fr.h))
                if (placed.right < fr.right) newFree.add(IRect(placed.right, fr.y, fr.right - placed.right, fr.h))
                if (placed.y > fr.y) newFree.add(IRect(fr.x, fr.y, fr.w, placed.y - fr.y))
                if (placed.bottom < fr.bottom) newFree.add(IRect(fr.x, placed.bottom, fr.w, fr.bottom - placed.bottom))
            }
            free.addAll(newFree)
            // Prune rectangles contained in others.
            var a = 0
            while (a < free.size) {
                var removed = false
                var b = 0
                while (b < free.size) {
                    if (a != b && free[b].contains(free[a])) { free.removeAt(a); removed = true; break }
                    b++
                }
                if (!removed) a++
            }
        }
        return PackResult(usedW, usedH, pos.map { it!! })
    }
}

/** Largest axis-aligned rectangle of `true` cells in a row-major boolean mask. */
object LargestRectangle {
    fun find(mask: BooleanArray, width: Int, height: Int): IRect? {
        val heights = IntArray(width)
        var best: IRect? = null
        var bestArea = 0L
        val stack = IntArray(width + 1)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) heights[x] = if (mask[row + x]) heights[x] + 1 else 0
            var top = 0
            var x = 0
            while (x <= width) {
                val h = if (x == width) 0 else heights[x]
                if (top == 0 || h >= heights[stack[top - 1]]) {
                    stack[top++] = x; x++
                } else {
                    val idx = stack[--top]
                    val hh = heights[idx]
                    val left = if (top == 0) 0 else stack[top - 1] + 1
                    val w = x - left
                    val area = hh.toLong() * w
                    if (area > bestArea) { bestArea = area; best = IRect(left, y - hh + 1, w, hh) }
                }
            }
        }
        return best
    }
}
