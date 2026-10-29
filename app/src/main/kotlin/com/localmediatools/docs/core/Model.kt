package com.localmediatools.docs.core

/**
 * What the readers make of a file, for the reader screens to show: a [FlowDocument] (text that
 * reflows to the screen: Word, OpenDocument text, RTF, e-books, plain text), a [Workbook] (sheets of
 * cells: Excel, OpenDocument spreadsheets, CSV) or a [Deck] (slides: PowerPoint, OpenDocument
 * presentations). Sizes are in points (1/72 inch); colours are 0xAARRGGBB, 0 meaning "not set".
 */
sealed class Opened : java.io.Closeable {
    abstract val title: String?
    /** Things the reader left out or couldn't show, in the user's words ("2 charts aren't shown"). */
    abstract val notes: List<String>
    /** What to close when the file is no longer shown (its pictures are read from it as they are shown). */
    var closer: java.io.Closeable? = null

    override fun close() { try { closer?.close() } catch (_: Exception) {}; closer = null }
}

/** Images inside the file, read when shown. */
fun interface Media { fun bytes(ref: String): ByteArray? }

// ====================================================================== flowing text
enum class Align { START, CENTER, END, JUSTIFY }

data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** 0: the paragraph's default size. */
    val sizePt: Float = 0f,
    val color: Int = 0,
    val highlight: Int = 0,
    val monospace: Boolean = false,
    /** 1 superscript, -1 subscript. */
    val vertical: Int = 0,
    val font: String? = null,
) {
    companion object { val PLAIN = RunStyle() }
}

/** A piece of text with one style; [link] is where it points (shown, never fetched). */
data class Span(val text: String, val style: RunStyle = RunStyle.PLAIN, val link: String? = null)

sealed class Block

class Paragraph(
    val spans: List<Span>,
    /** 1–6 for headings, 0 for body text. */
    val heading: Int = 0,
    val align: Align = Align.START,
    val indentPt: Float = 0f,
    val firstLinePt: Float = 0f,
    val spaceBeforePt: Float = 0f,
    val spaceAfterPt: Float = 0f,
    /** The list mark shown before it ("•", "3.", "b)"), and how deep the list is (0 = top). */
    val listLabel: String? = null,
    val listLevel: Int = 0,
    val shading: Int = 0,
    val quote: Boolean = false,
    /** Preformatted: monospace, spaces and line breaks kept. */
    val code: Boolean = false,
) : Block() {
    val text: String get() = spans.joinToString("") { it.text }
    fun isEmpty() = spans.all { it.text.isBlank() } && listLabel == null
}

class TableCell(
    val blocks: List<Block>,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val shading: Int = 0,
    val widthPt: Float = 0f,
)

class TableRow(val cells: List<TableCell>, val header: Boolean = false)

/** [columnsPt]: the width of each grid column when the file gives them (empty when it doesn't). */
class Table(val rows: List<TableRow>, val borders: Boolean = true, val columnsPt: List<Float> = emptyList()) : Block() {
    val columns: Int get() = rows.maxOfOrNull { r -> r.cells.sumOf { it.colSpan } } ?: 0
}

/** A picture: [ref] is a key into the document's [Media]. Size 0 = the picture's own. */
class Picture(val ref: String, val widthPt: Float = 0f, val heightPt: Float = 0f, val alt: String? = null) : Block()

/** A page or section break (shown as a gap with a line). */
object PageBreak : Block()

/** A horizontal line. */
object Rule : Block()

/** Something the reader can't draw, kept as a labelled placeholder ("Chart", "Equation"). */
class Placeholder(val label: String) : Block()

class FlowDocument(
    override val title: String?,
    val blocks: List<Block>,
    val media: Media = Media { null },
    override val notes: List<String> = emptyList(),
    /** Chapters or headings to jump to: label and index into [blocks]. */
    val outline: List<Pair<String, Int>> = emptyList(),
    /** The document's normal text size (pt). */
    val baseSizePt: Float = 11f,
) : Opened()

// ====================================================================== spreadsheets
enum class HAlign { GENERAL, LEFT, CENTER, RIGHT, JUSTIFY, FILL }
enum class VAlign { TOP, CENTER, BOTTOM }

data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float = 0f,
    val color: Int = 0,
    val fill: Int = 0,
    val hAlign: HAlign = HAlign.GENERAL,
    val vAlign: VAlign = VAlign.BOTTOM,
    val wrap: Boolean = false,
    /** Border colours (0: none) top, right, bottom, left. */
    val borderTop: Int = 0,
    val borderRight: Int = 0,
    val borderBottom: Int = 0,
    val borderLeft: Int = 0,
    val indent: Int = 0,
) {
    companion object { val DEFAULT = CellStyle() }
}

enum class CellKind { TEXT, NUMBER, BOOLEAN, ERROR }

/** A cell as shown: [text] already formatted (dates, currency…); [formula] as Excel shows it ("=SUM(B2:B6)", "{=…}" for array formulas). */
class Cell(val text: String, val kind: CellKind = CellKind.TEXT, val style: Int = 0, val formula: String? = null, val color: Int = 0)

/** Cells by row and column (both from 0), stored per row for fast lookup of a visible range. */
class CellGrid {
    private val rows = java.util.TreeMap<Int, java.util.TreeMap<Int, Cell>>()
    var maxRow = -1; private set
    var maxCol = -1; private set
    var count = 0; private set

    fun put(r: Int, c: Int, cell: Cell) {
        val row = rows.getOrPut(r) { java.util.TreeMap() }
        if (row.put(c, cell) == null) count++
        if (r > maxRow) maxRow = r
        if (c > maxCol) maxCol = c
    }

    operator fun get(r: Int, c: Int): Cell? = rows[r]?.get(c)
    fun row(r: Int): java.util.SortedMap<Int, Cell>? = rows[r]
    /** Rows that hold cells, in order. */
    fun rowIndices(): Set<Int> = rows.keys
    fun rowsBetween(from: Int, to: Int): Map<Int, java.util.SortedMap<Int, Cell>> = rows.subMap(from, true, to, true)
}

class Merge(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

class Sheet(
    val name: String,
    val cells: CellGrid,
    /** Column widths and row heights in points, for those that differ from the defaults. */
    val colWidths: Map<Int, Float> = emptyMap(),
    val rowHeights: Map<Int, Float> = emptyMap(),
    val defaultColWidthPt: Float = 48f,
    val defaultRowHeightPt: Float = 15f,
    val hiddenCols: Set<Int> = emptySet(),
    val hiddenRows: Set<Int> = emptySet(),
    val merges: List<Merge> = emptyList(),
    val frozenRows: Int = 0,
    val frozenCols: Int = 0,
    val hidden: Boolean = false,
    val gridLines: Boolean = true,
    /** Images or charts on the sheet that aren't shown. */
    val drawings: Int = 0,
)

class Workbook(
    override val title: String?,
    val sheets: List<Sheet>,
    val styles: List<CellStyle> = listOf(CellStyle.DEFAULT),
    override val notes: List<String> = emptyList(),
    /** The sheet open first. */
    val activeSheet: Int = 0,
) : Opened() {
    fun style(i: Int): CellStyle = styles.getOrNull(i) ?: CellStyle.DEFAULT
}

// ====================================================================== slides
enum class Geometry { RECT, ROUND_RECT, ELLIPSE, LINE, TRIANGLE, RIGHT_ARROW, DIAMOND, OTHER }
enum class Anchor { TOP, MIDDLE, BOTTOM }

class TextBody(
    val paragraphs: List<Paragraph>,
    val insetLeftPt: Float = 7.2f,
    val insetTopPt: Float = 3.6f,
    val insetRightPt: Float = 7.2f,
    val insetBottomPt: Float = 3.6f,
    val anchor: Anchor = Anchor.TOP,
    /** Text shrunk to fit (PowerPoint's normAutofit fontScale), 1 = as written. */
    val fontScale: Float = 1f,
    val wrap: Boolean = true,
    /** The size used where runs give none (pt). */
    val defaultSizePt: Float = 18f,
    val defaultColor: Int = 0xFF000000.toInt(),
)

class Shape(
    val xPt: Float, val yPt: Float, val wPt: Float, val hPt: Float,
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val geometry: Geometry = Geometry.RECT,
    val fill: Int = 0,
    val lineColor: Int = 0,
    val lineWidthPt: Float = 0f,
    val text: TextBody? = null,
    /** A picture filling the shape (a key into the deck's [Media]), and how much is cropped off each side (left, top, right, bottom; fractions). */
    val image: String? = null,
    val crop: FloatArray? = null,
    val table: Table? = null,
    /** Text in a table's cells, styled like slide text. */
    val tableText: TextBody? = null,
    /** What it stands for when it can't be drawn ("Chart"). */
    val placeholder: String? = null,
)

class Slide(
    val shapes: List<Shape>,
    val background: Int = 0xFFFFFFFF.toInt(),
    val backgroundImage: String? = null,
    val notes: List<Paragraph> = emptyList(),
    val hidden: Boolean = false,
)

class Deck(
    override val title: String?,
    val slides: List<Slide>,
    val widthPt: Float = 720f,
    val heightPt: Float = 540f,
    val media: Media = Media { null },
    override val notes: List<String> = emptyList(),
) : Opened()

/** Thrown for files a reader recognises but can't open, with the reason in the user's words. */
class UnreadableException(message: String) : Exception(message)
