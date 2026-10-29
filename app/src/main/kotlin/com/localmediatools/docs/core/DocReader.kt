package com.localmediatools.docs.core

import com.localmediatools.i18n.tr

/**
 * Word 97–2003 documents (.doc): the text in reading order, rebuilt from the file's piece table
 * (fields show their result), with paragraphs, tables, page breaks and footnotes; headings come from
 * the paragraph styles, and bold, italic, underline, size and colour from the character formatting.
 * Pictures placed in the text (PNG, JPEG, bitmaps) are shown; text in text boxes and shapes follows
 * the main text. Tables get their column widths and merged cells from each row's definition. Floating
 * drawings aren't drawn. Word 6 and 95 files and encrypted ones say so.
 */
class DocReader private constructor(private val ole: Ole2) {
    companion object {
        fun read(ole: Ole2): FlowDocument = DocReader(ole).read()
        private val ICO = intArrayOf(0, 0xFF000000.toInt(), 0xFF0000FF.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt(),
            0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt(), 0xFF000080.toInt(), 0xFF008080.toInt(), 0xFF008000.toInt(), 0xFF800080.toInt(), 0xFF800000.toInt(),
            0xFF808000.toInt(), 0xFF808080.toInt(), 0xFFC0C0C0.toInt())
    }

    private val wd = ole.stream("WordDocument") ?: throw UnreadableException(tr("This Word file is damaged: its text is missing."))
    private lateinit var table: ByteArray
    private class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean)
    private val pieces = ArrayList<Piece>()
    /** Character runs by file offset: (fcStart, fcEnd, style). */
    private val chpx = ArrayList<Triple<Int, Int, RunStyle>>()
    /** Paragraph style index by file offset of the paragraph's end. */
    private val papx = ArrayList<Triple<Int, Int, Int>>()
    private val styleNames = HashMap<Int, String>()
    private var notes = ArrayList<String>()
    /** Inline pictures: (fcStart, fcEnd, offset of the picture in the Data stream). */
    private val pictureRuns = ArrayList<Triple<Int, Int, Int>>()
    private val dataStream: ByteArray? by lazy { try { ole.stream("Data") } catch (_: Exception) { null } }
    private var missedPictures = 0

    /** A table row as the file defines it (sprmTDefTable): cell boundaries in twips, merge flags, header row. */
    private class RowDef(val centers: IntArray, val horz: IntArray, val vert: IntArray, var header: Boolean)
    /** Row definitions by the file offsets of the paragraph that ends the row. */
    private val rowDefs = ArrayList<Triple<Int, Int, RowDef>>()
    /** File offsets of the paragraphs that end table rows (sprmPFTtp), when the file marks them. */
    private val rowEnds = ArrayList<IntArray>()
    /** File offsets of the paragraphs in table cells (sprmPFInTable). */
    private val inTables = ArrayList<IntArray>()

    private fun read(): FlowDocument {
        if (le16(wd, 0) == 0xA5DC) throw UnreadableException(tr("This file was made by Word 6 or Word 95, which this app can't read yet."))
        if (le16(wd, 0) != 0xA5EC) throw UnreadableException(tr("This Word file is damaged: its text is missing."))
        val nFib = le16(wd, 2)
        if (nFib < 193) throw UnreadableException(tr("This file was made by Word 6 or Word 95, which this app can't read yet."))
        val flags = le16(wd, 0x0A)
        if (flags and 0x0100 != 0) throw UnreadableException(tr("This file is protected with a password, which this app can't open."))
        table = ole.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw UnreadableException(tr("This Word file is damaged: its text is missing."))
        val csw = le16(wd, 32)
        val lwBase = 32 + 2 + csw * 2 + 2
        val ccpText = le32(wd, lwBase + 3 * 4)
        val ccpFtn = le32(wd, lwBase + 4 * 4)
        val ccpHdd = le32(wd, lwBase + 5 * 4); val ccpMcr = le32(wd, lwBase + 6 * 4)
        val ccpAtn = le32(wd, lwBase + 7 * 4); val ccpEdn = le32(wd, lwBase + 8 * 4); val ccpTxbx = le32(wd, lwBase + 9 * 4)
        val cslw = le16(wd, 32 + 2 + csw * 2)
        val pairs = 32 + 2 + csw * 2 + 2 + cslw * 4 + 2
        fun fc(i: Int) = le32(wd, pairs + i * 8)
        fun lcb(i: Int) = le32(wd, pairs + i * 8 + 4)
        readPieces(fc(33), lcb(33))
        if (pieces.isEmpty()) throw UnreadableException(tr("This Word file is damaged: its text is missing."))
        try { readStyles(fc(1), lcb(1)) } catch (_: Exception) {}
        try { readChpx(fc(12), lcb(12)) } catch (_: Exception) { chpx.clear() }
        try { readPapx(fc(13), lcb(13)) } catch (_: Exception) { papx.clear() }
        val blocks = build(0, ccpText)
        fun valid(n: Int) = n in 1..(Int.MAX_VALUE / 8)
        // The other stories follow the main text in this order: footnotes, headers, (macros), comments, endnotes, text boxes.
        val ednStart = ccpText + maxOf(0, ccpFtn) + maxOf(0, ccpHdd) + maxOf(0, ccpMcr) + maxOf(0, ccpAtn)
        val boxStart = ednStart + maxOf(0, ccpEdn)
        if (valid(ccpTxbx)) {
            val boxes = build(boxStart, boxStart + ccpTxbx).filter { it !is Paragraph || !it.isEmpty() }
            if (boxes.isNotEmpty()) {
                blocks.add(Rule)
                blocks.add(Paragraph(listOf(Span(tr("Text in boxes and shapes"), RunStyle(bold = true))), heading = 2))
                blocks.addAll(boxes)
            }
        }
        val foot = ArrayList<Block>()
        if (valid(ccpFtn)) foot.addAll(build(ccpText, ccpText + ccpFtn).filter { it !is Paragraph || !it.isEmpty() })
        if (valid(ccpEdn)) foot.addAll(build(ednStart, ednStart + ccpEdn).filter { it !is Paragraph || !it.isEmpty() })
        if (foot.isNotEmpty()) {
            blocks.add(PageBreak)
            blocks.add(Paragraph(listOf(Span(tr("Notes"), RunStyle(bold = true))), heading = 2))
            blocks.addAll(foot)
        }
        if (missedPictures > 0 || hasDrawings) notes.add(tr("Some pictures and drawings in this document aren't shown."))
        val title = (blocks.firstOrNull { it is Paragraph && it.heading == 1 && it.text.isNotBlank() } as? Paragraph)?.text?.trim()
        return FlowDocument(title, blocks, Media { ref -> picture(ref.removePrefix("pic:").toIntOrNull() ?: -1) }, notes, outline = HtmlReader.outlineOf(blocks), baseSizePt = 12f)
    }

    private var hasDrawings = false

    private fun pictureAt(fc: Int): Int {
        var lo = 0; var hi = pictureRuns.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val (s, e, loc) = pictureRuns[mid]
            if (fc < s) hi = mid - 1 else if (fc >= e) lo = mid + 1 else return loc
        }
        return -1
    }

    /**
     * The picture stored at [off] in the Data stream (a PICF header, then Office drawing records
     * holding the image): its PNG or JPEG bytes, or a bitmap made into a BMP file; null for metafiles.
     */
    private fun picture(off: Int): ByteArray? {
        val d = dataStream ?: return null
        if (off < 0 || off + 8 > d.size) return null
        val lcb = le32(d, off)
        val end = if (lcb in 8..(d.size - off)) off + lcb else d.size
        var i = off + (le16(d, off + 4).coerceIn(0, 2048))
        while (i + 8 <= end) {
            if (OfficeArt.isBlip(d, i)) return OfficeArt.blip(d, i)
            i++
        }
        return null
    }

    private fun readPieces(fcClx: Int, lcbClx: Int) {
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) return
        var i = fcClx
        val end = fcClx + lcbClx
        while (i < end) {
            when (table[i].toInt()) {
                1 -> { i += 3 + le16(table, i + 1) }
                2 -> {
                    val lcb = le32(table, i + 1)
                    val start = i + 5
                    val n = (lcb - 4) / 12
                    if (n <= 0 || start + lcb > table.size) return
                    for (k in 0 until n) {
                        val cp0 = le32(table, start + k * 4); val cp1 = le32(table, start + (k + 1) * 4)
                        val pcd = start + (n + 1) * 4 + k * 8
                        val raw = le32(table, pcd + 2)
                        val compressed = raw and 0x40000000 != 0
                        val fc = if (compressed) (raw and 0x3FFFFFFF) / 2 else raw
                        pieces.add(Piece(cp0, cp1, fc, compressed))
                    }
                    return
                }
                else -> return
            }
        }
    }

    /** Characters [cp0, cp1) with the file offset of each. */
    private fun chars(cp0: Int, cp1: Int, out: StringBuilder, fcs: IntArrayList) {
        for (p in pieces) {
            val a = maxOf(cp0, p.cpStart); val b = minOf(cp1, p.cpEnd)
            if (a >= b) continue
            for (cp in a until b) {
                val k = cp - p.cpStart
                if (p.compressed) {
                    val off = p.fc + k
                    if (off >= wd.size) break
                    val v = wd[off].toInt() and 0xFF
                    out.append(CP1252.getOrNull(v) ?: v.toChar()); fcs.add(off)
                } else {
                    val off = p.fc + k * 2
                    if (off + 1 >= wd.size) break
                    out.append(le16(wd, off).toChar()); fcs.add(off)
                }
            }
        }
    }

    private class IntArrayList { var a = IntArray(1024); var n = 0; fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }; operator fun get(i: Int) = a[i] }

    private fun styleAt(fc: Int): RunStyle {
        // Binary search over sorted runs.
        var lo = 0; var hi = chpx.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val (s, e, st) = chpx[mid]
            if (fc < s) hi = mid - 1 else if (fc >= e) lo = mid + 1 else return st
        }
        return RunStyle(sizePt = 12f)
    }

    private fun istdAt(fc: Int): Int {
        var lo = 0; var hi = papx.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val (s, e, st) = papx[mid]
            if (fc < s) hi = mid - 1 else if (fc >= e) lo = mid + 1 else return st
        }
        return 0
    }

    private fun headingOf(istd: Int): Int {
        val n = styleNames[istd]?.lowercase() ?: return 0
        if (n == "title") return 1
        Regex("""^heading\s*([1-6])$""").find(n)?.let { return it.groupValues[1].toInt() }
        return 0
    }

    private fun build(cp0: Int, cp1: Int): MutableList<Block> {
        val text = StringBuilder(); val fcs = IntArrayList()
        chars(cp0, cp1, text, fcs)
        val out = ArrayList<Block>()
        val spans = ArrayList<Span>()
        var field = 0; val inResult = BooleanArray(32)
        val cellParas = ArrayList<Block>()
        val cells = ArrayList<TableCell>()
        val rows = ArrayList<TableRow>()
        val defs = ArrayList<RowDef?>()
        var lastWasCellEnd = false
        var footnote = 0
        fun add(s: String, fc: Int) {
            if (field > 0 && !inResult[field - 1]) return
            val st = styleAt(fc)
            val last = spans.lastOrNull()
            if (last != null && last.style == st) spans[spans.size - 1] = Span(last.text + s, st) else spans.add(Span(s, st))
        }
        val pics = ArrayList<Block>()
        fun paragraph(fc: Int): Paragraph = Paragraph(spans.toList(), headingOf(istdAt(fc))).also { spans.clear() }
        // Cells still waiting for their row's end (damaged files) end the row.
        fun flushTable() {
            if (cells.isNotEmpty()) { rows.add(TableRow(cells.toList())); defs.add(null); cells.clear() }
            if (rows.isNotEmpty()) { out.add(table(rows, defs)); rows.clear(); defs.clear() }
        }
        for (i in text.indices) {
            val c = text[i]; val fc = fcs[i]
            when (c) {
                '\r' -> {
                    val p = paragraph(fc)
                    // With the file's table marks, a paragraph is in a cell exactly when it is marked so
                    // (a cell can start with several paragraphs); without them, once a cell of the row has ended.
                    val flagged = rowEnds.isNotEmpty()
                    val inCell = if (flagged) isInTable(fc) else cells.isNotEmpty()
                    if (!inCell && (rows.isNotEmpty() || cells.isNotEmpty())) flushTable()
                    cellParas.add(p)
                    // Pictures in the paragraph come right after its text.
                    if (pics.isNotEmpty()) { if (p.isEmpty()) cellParas.removeAt(cellParas.size - 1); cellParas.addAll(pics); pics.clear() }
                    lastWasCellEnd = false
                    if (flagged) { if (!inCell) { out.addAll(cellParas); cellParas.clear() } }
                    // Unmarked: paragraphs not followed by a cell end are ordinary text, so only the last one waits.
                    else if (cells.isEmpty() && cellParas.size > 1) { out.addAll(cellParas.subList(0, cellParas.size - 1)); val keep = cellParas.last(); cellParas.clear(); cellParas.add(keep) }
                }
                '\u0007' -> {
                    val p = paragraph(fc)
                    // A row ends at the mark of a paragraph flagged as the row's end; files without the
                    // flags are read by their shape (an empty "cell" right after a cell's end).
                    val rowEnd = if (rowEnds.isNotEmpty()) isRowEnd(fc) else lastWasCellEnd && p.isEmpty() && cellParas.isEmpty()
                    if (rowEnd) {
                        // (Text before a row's end mark, in damaged files, is kept as one more cell.)
                        if (!p.isEmpty() || cellParas.isNotEmpty()) { if (!p.isEmpty()) cellParas.add(p); cells.add(TableCell(cellParas.toList())); cellParas.clear() }
                        if (cells.isNotEmpty()) { rows.add(TableRow(cells.toList())); defs.add(rowDefAt(fc)) }
                        cells.clear()
                        lastWasCellEnd = false
                    } else {
                        if (!p.isEmpty() || cellParas.isEmpty()) cellParas.add(p)
                        cells.add(TableCell(cellParas.toList()))
                        cellParas.clear()
                        lastWasCellEnd = true
                    }
                }
                '\u000B' -> add("\n", fc)
                '\u000C' -> {
                    val p = paragraph(fc)
                    if (cells.isNotEmpty()) {
                        // A page break inside a row (damaged files): what is waiting is the row's last cell.
                        if (!p.isEmpty()) cellParas.add(p)
                        if (cellParas.isNotEmpty()) cells.add(TableCell(cellParas.toList()))
                        cellParas.clear()
                        flushTable()
                    } else {
                        flushTable()
                        out.addAll(cellParas); cellParas.clear()
                        if (!p.isEmpty()) out.add(p)
                    }
                    out.add(PageBreak)
                }
                '\u000E' -> add("\n", fc)
                '\u0013' -> { if (field < 32) { inResult[field] = false; field++ } }
                '\u0014' -> { if (field > 0) inResult[field - 1] = true }
                '\u0015' -> { if (field > 0) field-- }
                '\u0001' -> if (field == 0 || inResult[field - 1]) {
                    val loc = pictureAt(fc)
                    if (loc >= 0 && picture(loc) != null) pics.add(Picture("pic:$loc")) else missedPictures++
                }
                '\u0008' -> hasDrawings = true
                '\u0002' -> { footnote++; if (field == 0 || inResult[field - 1]) spans.add(Span(footnote.toString(), styleAt(fc).copy(vertical = 1))) }
                '\u0003', '\u0004', '\u0005' -> Unit
                '\u001E' -> add("-", fc)
                '\u001F' -> Unit
                '\t' -> add("\t", fc)
                else -> if (c >= ' ' || c == '\n') add(c.toString(), fc)
            }
        }
        if (spans.isNotEmpty()) cellParas.add(paragraph(fcs.let { if (it.n > 0) it[it.n - 1] else 0 }))
        cellParas.addAll(pics)
        flushTable()
        out.addAll(cellParas)
        return out
    }

    // ------------------------------------------------------------------ tables
    private fun isRowEnd(fc: Int) = within(rowEnds, fc)
    private fun isInTable(fc: Int) = within(inTables, fc)

    private fun within(ranges: List<IntArray>, fc: Int): Boolean {
        var lo = 0; var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = ranges[mid]
            if (fc < r[0]) hi = mid - 1 else if (fc >= r[1]) lo = mid + 1 else return true
        }
        return false
    }

    private fun rowDefAt(fc: Int): RowDef? {
        var lo = 0; var hi = rowDefs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val (s, e, d) = rowDefs[mid]
            if (fc < s) hi = mid - 1 else if (fc >= e) lo = mid + 1 else return d
        }
        return null
    }

    /**
     * A table from its rows of cells and each row's definition: the columns are every cell boundary
     * of every row (Word rows needn't line up), so each cell spans the columns between its edges;
     * horizontally merged cells join the cell before them, and a cell continuing a vertical merge
     * lengthens the cell above it. Without a definition for every row, cells just follow each other.
     */
    private fun table(rows: List<TableRow>, defs: List<RowDef?>): Table {
        if (rows.indices.any { r -> defs[r].let { d -> d == null || d.centers.size - 1 != rows[r].cells.size } }) return Table(rows.toList())
        // The grid: boundaries from all rows, ones less than a point apart taken as one.
        val all = defs.flatMap { it!!.centers.toList() }.sorted()
        val grid = ArrayList<Int>()
        for (x in all) if (grid.isEmpty() || x - grid.last() > 20) grid.add(x)
        if (grid.size < 2) return Table(rows.toList())
        fun col(x: Int): Int { var best = 0; for (k in grid.indices) if (kotlin.math.abs(grid[k] - x) < kotlin.math.abs(grid[best] - x)) best = k; return best }
        class C(val blocks: MutableList<Block>, val c0: Int, var c1: Int, val vert: Int) { var rowSpan = 1 }
        // Text in a cell merged into another is kept, after that cell's own.
        fun hasText(b: List<Block>) = b.any { it !is Paragraph || !it.isEmpty() }
        val placed = rows.mapIndexed { r, row ->
            val d = defs[r]!!
            val list = ArrayList<C>()
            for ((k, cell) in row.cells.withIndex()) {
                val c0 = col(d.centers[k]); val c1 = col(d.centers[k + 1])
                // Merged with the cell before it (horzMerge 2 or 3): that cell grows.
                if (d.horz[k] >= 2 && list.isNotEmpty()) {
                    list.last().c1 = maxOf(list.last().c1, c1)
                    if (hasText(cell.blocks)) list.last().blocks.addAll(cell.blocks)
                    continue
                }
                list.add(C(cell.blocks.toMutableList(), c0, maxOf(c1, c0 + 1), d.vert[k]))
            }
            list
        }
        // Vertical merges: a continuing cell (vertMerge 1) lengthens the cell above that starts at the same column.
        val open = HashMap<Int, C>()
        val kept = placed.map { row ->
            row.filter { c ->
                val above = open[c.c0]
                if (c.vert == 1 && above != null && above.c1 == c.c1) {
                    above.rowSpan++
                    if (hasText(c.blocks)) above.blocks.addAll(c.blocks)
                    false
                } else { open[c.c0] = c; true }
            }
        }
        // Rows that start later than column 0, or leave gaps between cells, get empty cells there.
        val busy = HashMap<Int, java.util.BitSet>()
        val out = kept.mapIndexed { r, row ->
            val cellsOut = ArrayList<TableCell>()
            val taken = busy.getOrPut(r) { java.util.BitSet() }
            var at = 0
            for (c in row) {
                while (at < c.c0) {
                    if (taken[at]) { at++; continue }
                    var e = at; while (e < c.c0 && !taken[e]) e++
                    cellsOut.add(TableCell(emptyList(), e - at)); at = e
                }
                for (rr in r until (r + c.rowSpan).coerceAtMost(kept.size)) busy.getOrPut(rr) { java.util.BitSet() }.set(c.c0, c.c1)
                cellsOut.add(TableCell(c.blocks, c.c1 - c.c0, c.rowSpan, widthPt = (grid[c.c1] - grid[c.c0]) / 20f))
                at = c.c1
            }
            TableRow(cellsOut, defs[r]!!.header)
        }
        return Table(out, columnsPt = (0 until grid.size - 1).map { (grid[it + 1] - grid[it]) / 20f })
    }

    /**
     * The table row definition in a paragraph's properties ([b] from [start], [len] bytes of sprms),
     * following sprmPHugePapx into the Data stream when the properties are too large for their page.
     */
    /** Whether the last properties read by [rowDef] marked the end of a table row, or a paragraph in a table. */
    private var lastTtp = false
    private var lastInTable = false

    private fun rowDef(b: ByteArray, start: Int, len: Int, depth: Int = 0): RowDef? {
        var i = start
        val end = minOf(start + len, b.size)
        var def: RowDef? = null
        var header = false
        if (depth == 0) { lastTtp = false; lastInTable = false }
        while (i + 2 <= end) {
            val sprm = le16(b, i); i += 2
            when (sprm) {
                // sprmTDefTable: a two-byte size (one more than the bytes that follow it).
                0xD608, 0xD606 -> {
                    val cb = le16(b, i)
                    if (cb < 1) return def
                    if (sprm == 0xD608) parseDefTable(b, i + 2, minOf(i + 2 + cb - 1, end))?.let { def = it }
                    i += 2 + cb - 1
                    continue
                }
                0x6646 -> if (depth == 0) dataStream?.let { d ->
                    val at = le32(b, i)
                    if (at >= 0 && at + 2 <= d.size) rowDef(d, at + 2, le16(d, at), 1)?.let { def = it; header = header || it.header }
                }
                0x3404 -> header = (b.getOrNull(i)?.toInt() ?: 0) != 0
                // sprmPFTtp and sprmPFInnerTtp: this paragraph ends a row (of a table, or of a nested one).
                0x2417, 0x244C -> if ((b.getOrNull(i)?.toInt() ?: 0) != 0) lastTtp = true
                0x2416 -> if ((b.getOrNull(i)?.toInt() ?: 0) != 0) lastInTable = true
            }
            val spra = sprm ushr 13
            val size = when {
                // sprmPChgTabs with its long form.
                sprm == 0xC615 && (b.getOrNull(i)?.toInt()?.and(0xFF) ?: 0) == 255 -> {
                    val del = b.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: 0
                    val add = b.getOrNull(i + 2 + del * 4)?.toInt()?.and(0xFF) ?: 0
                    1 + 1 + del * 4 + 1 + add * 3
                }
                spra == 0 || spra == 1 -> 1
                spra == 2 || spra == 4 || spra == 5 -> 2
                spra == 7 -> 3
                spra == 3 -> 4
                spra == 6 -> (b.getOrNull(i)?.toInt()?.and(0xFF) ?: 0) + 1
                else -> 1
            }
            i += size
        }
        def?.let { it.header = it.header || header }
        return def
    }

    /** TDefTableOperand after its size: the number of cells, their boundaries, then a TC80 for each cell. */
    private fun parseDefTable(b: ByteArray, op: Int, opEnd: Int): RowDef? {
        if (op >= opEnd) return null
        val n = b[op].toInt() and 0xFF
        if (n == 0 || n > 63 || op + 1 + 2 * (n + 1) > opEnd) return null
        val centers = IntArray(n + 1) { k -> le16(b, op + 1 + 2 * k).toShort().toInt() }
        for (k in 0 until n) if (centers[k + 1] < centers[k]) return null
        val tc = op + 1 + 2 * (n + 1)
        val horz = IntArray(n); val vert = IntArray(n)
        for (k in 0 until n) {
            val t = tc + 20 * k
            if (t + 2 > opEnd) break
            val g = le16(b, t)
            horz[k] = g and 3
            vert[k] = (g shr 5) and 3
        }
        return RowDef(centers, horz, vert, false)
    }

    // ------------------------------------------------------------------ styles and formatting
    private fun readStyles(fc: Int, lcb: Int) {
        if (fc <= 0 || lcb <= 0 || fc + lcb > table.size) return
        val cbStshi = le16(table, fc)
        val stshi = fc + 2
        val cstd = le16(table, stshi)
        val cbSTDBaseInFile = le16(table, stshi + 2)
        var p = stshi + cbStshi
        for (istd in 0 until cstd) {
            if (p + 2 > fc + lcb) break
            val cbStd = le16(table, p)
            if (cbStd > 0) {
                val base = p + 2
                val nameAt = base + cbSTDBaseInFile
                val len = le16(table, nameAt)
                if (len in 1..255 && nameAt + 2 + len * 2 <= table.size) styleNames[istd] = String(table, nameAt + 2, len * 2, Charsets.UTF_16LE)
            }
            p += 2 + cbStd
        }
    }

    /** The character runs of the document, from the CHPX bin table and its formatted disk pages. */
    private fun readChpx(fc: Int, lcb: Int) {
        if (fc <= 0 || lcb < 8 || fc + lcb > table.size) return
        val n = (lcb - 4) / 8
        for (k in 0 until n) {
            val pn = le32(table, fc + (n + 1) * 4 + k * 4) and 0x3FFFFF
            val page = pn * 512
            if (page + 512 > wd.size) continue
            val crun = wd[page + 511].toInt() and 0xFF
            for (r in 0 until crun) {
                val s = le32(wd, page + r * 4); val e = le32(wd, page + (r + 1) * 4)
                val off = (wd[page + (crun + 1) * 4 + r].toInt() and 0xFF) * 2
                var style = RunStyle(sizePt = 12f)
                if (off > 0) {
                    val cb = wd[page + off].toInt() and 0xFF
                    style = sprmsRun(wd, page + off + 1, cb, style)
                    pictureLocation(wd, page + off + 1, cb).let { if (it >= 0) pictureRuns.add(Triple(s, e, it)) }
                }
                chpx.add(Triple(s, e, style))
            }
        }
        chpx.sortBy { it.first }
        pictureRuns.sortBy { it.first }
    }

    private fun readPapx(fc: Int, lcb: Int) {
        if (fc <= 0 || lcb < 8 || fc + lcb > table.size) return
        val n = (lcb - 4) / 8
        for (k in 0 until n) {
            val pn = le32(table, fc + (n + 1) * 4 + k * 4) and 0x3FFFFF
            val page = pn * 512
            if (page + 512 > wd.size) continue
            val crun = wd[page + 511].toInt() and 0xFF
            for (r in 0 until crun) {
                val s = le32(wd, page + r * 4); val e = le32(wd, page + (r + 1) * 4)
                // BX: 13 bytes per paragraph after the fc array; first byte = word offset of PAPX.
                val off = (wd[page + (crun + 1) * 4 + r * 13].toInt() and 0xFF) * 2
                var istd = 0
                if (off > 0) {
                    var cb = wd[page + off].toInt() and 0xFF
                    var at = page + off + 1
                    if (cb == 0) { cb = wd[at].toInt() and 0xFF; at++ ; cb *= 2 } else cb = cb * 2 - 1
                    istd = le16(wd, at)
                    if (cb > 2) {
                        rowDef(wd, at + 2, cb - 2)?.let { rowDefs.add(Triple(s, e, it)) }
                        if (lastTtp) rowEnds.add(intArrayOf(s, e))
                        if (lastInTable || lastTtp) inTables.add(intArrayOf(s, e))
                    }
                }
                papx.add(Triple(s, e, istd))
            }
        }
        papx.sortBy { it.first }
        rowDefs.sortBy { it.first }
        rowEnds.sortBy { it[0] }
        inTables.sortBy { it[0] }
    }

    /** Where a run's picture is in the Data stream (sprmCPicLocation), or -1. */
    private fun pictureLocation(b: ByteArray, start: Int, len: Int): Int {
        var i = start
        val end = (start + len).coerceAtMost(b.size)
        while (i + 2 <= end) {
            val sprm = le16(b, i); i += 2
            val size = when (sprm ushr 13) { 0, 1 -> 1; 2, 4, 5 -> 2; 7 -> 3; 3 -> 4; 6 -> (b.getOrNull(i)?.toInt()?.and(0xFF) ?: 0) + 1; else -> 1 }
            if (sprm == 0x6A03) return le32(b, i)
            i += size
        }
        return -1
    }

    private fun sprmsRun(b: ByteArray, start: Int, len: Int, base: RunStyle): RunStyle {
        var s = base
        var i = start
        val end = (start + len).coerceAtMost(b.size)
        while (i + 2 <= end) {
            val sprm = le16(b, i); i += 2
            val spra = sprm ushr 13
            val size = when (spra) { 0, 1 -> 1; 2, 4, 5 -> 2; 7 -> 3; 3 -> 4; 6 -> (b.getOrNull(i)?.toInt()?.and(0xFF) ?: 0) + 1; else -> 1 }
            val v1 = b.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
            fun toggle(cur: Boolean) = when (v1) { 0 -> false; 1 -> true; 0x80 -> cur; 0x81 -> !cur; else -> cur }
            when (sprm) {
                0x0835 -> s = s.copy(bold = toggle(s.bold))
                0x0836 -> s = s.copy(italic = toggle(s.italic))
                0x0837 -> s = s.copy(strike = toggle(s.strike))
                0x2A3E -> s = s.copy(underline = v1 != 0)
                0x4A43 -> s = s.copy(sizePt = le16(b, i) / 2f)
                0x2A42 -> s = s.copy(color = ICO.getOrElse(v1) { 0 })
                0x2A48 -> s = s.copy(vertical = when (v1) { 1 -> 1; 2 -> -1; else -> 0 })
                0x6870 -> { val c = le32(b, i); if (c ushr 24 == 0) s = s.copy(color = (0xFF shl 24) or ((c and 0xFF) shl 16) or (c and 0xFF00) or ((c shr 16) and 0xFF)) }
                0x083C -> if (toggle(false)) s = s.copy() // vanish: kept visible (rare)
            }
            i += size
        }
        return s
    }

    private val CP1252: CharArray = String(ByteArray(256) { it.toByte() }, charset("windows-1252")).toCharArray()
}
