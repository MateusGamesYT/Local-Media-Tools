package com.localmediatools.docs.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text encodings: byte order marks, then UTF-8 if it decodes cleanly, UTF-16 by its zero bytes, else Windows-1252. */
object TextDecode {
    fun charsetOf(b: ByteArray, n: Int = b.size): Pair<Charset, Int> {
        if (n >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return Charsets.UTF_8 to 3
        if (n >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return Charsets.UTF_16LE to 2
        if (n >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return Charsets.UTF_16BE to 2
        val k = minOf(n, 4096)
        var evenZero = 0; var oddZero = 0
        for (i in 0 until k) if (b[i] == 0.toByte()) { if (i % 2 == 0) evenZero++ else oddZero++ }
        if (k >= 8 && oddZero > k / 4 && evenZero < k / 64) return Charsets.UTF_16LE to 0
        if (k >= 8 && evenZero > k / 4 && oddZero < k / 64) return Charsets.UTF_16BE to 0
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            // A sequence cut at the end of the sample is fine.
            var end = n
            while (end > 0 && end > n - 4 && (b[end - 1].toInt() and 0xC0) == 0x80) end--
            if (end > 0 && (b[end - 1].toInt() and 0xC0) == 0xC0) end--
            dec.decode(ByteBuffer.wrap(b, 0, end)); Charsets.UTF_8 to 0
        } catch (_: CharacterCodingException) { Charset.forName("windows-1252") to 0 }
    }

    fun decode(b: ByteArray): String {
        val (cs, skip) = charsetOf(b)
        return String(b, skip, b.size - skip, cs)
    }

    /** True for the first bytes of a text file: no zero bytes (unless UTF-16) and almost no control characters. */
    fun looksLikeText(b: ByteArray, n: Int): Boolean {
        if (n == 0) return true
        val (cs, _) = charsetOf(b, n)
        if (cs == Charsets.UTF_16LE || cs == Charsets.UTF_16BE) return true
        var bad = 0
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) return false
            if (c < 0x20 && c != 9 && c != 10 && c != 13 && c != 12 && c != 27) bad++
        }
        return bad * 100 <= n
    }
}

/** Plain text, Markdown and JSON as flowing text. */
object TextReaders {
    private val CODE = setOf("xml", "json", "log", "kt", "java", "js", "ts", "py", "c", "h", "cpp", "cs", "go", "rs", "sh", "bat", "ps1", "sql", "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "css", "gradle", "swift", "rb", "php", "pl", "lua", "r", "m", "diff", "patch", "svg", "plist", "gpx", "kml")

    /** Lines kept as written; paragraphs split at blank lines (and every 60 lines, so long files scroll smoothly). */
    fun plain(text: String, name: String): FlowDocument {
        val ext = name.substringAfterLast('.', "").lowercase()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val aligned = lines.count { it.contains("   ") || it.contains('\t') }
        val mono = ext in CODE || aligned * 5 > lines.size.coerceAtLeast(1)
        val style = RunStyle(monospace = mono)
        val blocks = ArrayList<Block>()
        val cur = StringBuilder(); var count = 0
        fun flush() { if (cur.isNotEmpty()) { blocks.add(Paragraph(listOf(Span(cur.toString().trimEnd('\n'), style)), code = mono, spaceAfterPt = 6f)); cur.setLength(0); count = 0 } }
        for (l in lines) {
            if (l.isBlank() && !mono) { flush(); continue }
            cur.append(l).append('\n'); count++
            if (count >= 60) flush()
        }
        flush()
        return FlowDocument(null, blocks)
    }

    /** JSON pretty-printed when written on few lines; as written otherwise. */
    fun json(text: String): FlowDocument {
        val nl = text.count { it == '\n' }
        val shown = if (text.length > 200 && nl * 80 < text.length) prettyJson(text) ?: text else text
        return plain(shown, "x.json")
    }

    private fun prettyJson(s: String): String? {
        val sb = StringBuilder(s.length + s.length / 4)
        var indent = 0; var inString = false; var escape = false
        fun nl() { sb.append('\n'); repeat(indent) { sb.append("  ") } }
        for (c in s) {
            if (inString) { sb.append(c); if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') inString = false; continue }
            when (c) {
                '"' -> { inString = true; sb.append(c) }
                '{', '[' -> { sb.append(c); indent++; nl() }
                '}', ']' -> { indent = (indent - 1).coerceAtLeast(0); nl(); sb.append(c) }
                ',' -> { sb.append(c); nl() }
                ':' -> sb.append(": ")
                ' ', '\t', '\n', '\r' -> Unit
                else -> sb.append(c)
            }
            if (sb.length > 64 shl 20) return null
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ Markdown
    /** Markdown's usual parts: headings, emphasis, code, lists, quotes, rules, links, pipe tables. */
    fun markdown(text: String): FlowDocument {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Block>()
        val para = StringBuilder()
        var i = 0
        fun flushPara() { if (para.isNotBlank()) blocks.add(Paragraph(inlineMd(para.toString().trim()), spaceAfterPt = 6f)); para.setLength(0) }
        val olCounter = HashMap<Int, Int>()
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            val indent = (line.length - t.length) / 2
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flushPara()
                    val fence = t.take(3)
                    val sb = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) { sb.append(lines[i]).append('\n'); i++ }
                    blocks.add(Paragraph(listOf(Span(sb.toString().trimEnd('\n'), RunStyle(monospace = true))), code = true, shading = 0xFFF2F2F2.toInt(), spaceAfterPt = 6f))
                }
                Regex("""^#{1,6}\s""").containsMatchIn(t) -> {
                    flushPara()
                    val level = t.takeWhile { it == '#' }.length
                    blocks.add(Paragraph(inlineMd(t.drop(level).trim().trimEnd('#').trim(), RunStyle(bold = true)), heading = level, spaceBeforePt = 8f, spaceAfterPt = 4f))
                }
                Regex("""^(\*\s*\*\s*\*[\s*]*|-\s*-\s*-[\s-]*|_\s*_\s*_[\s_]*)$""").matches(t) -> { flushPara(); blocks.add(Rule) }
                t.startsWith(">") -> {
                    flushPara()
                    val sb = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) { sb.append(lines[i].trimStart().removePrefix(">").trim()).append(' '); i++ }
                    blocks.add(Paragraph(inlineMd(sb.toString().trim()), quote = true, spaceAfterPt = 6f))
                    continue
                }
                Regex("""^[-*+]\s+""").containsMatchIn(t) -> {
                    flushPara()
                    var body = t.replaceFirst(Regex("""^[-*+]\s+"""), "")
                    val label = when { body.startsWith("[ ] ") -> { body = body.drop(4); "☐" }; body.startsWith("[x] ", true) -> { body = body.drop(4); "☑" }; else -> "•" }
                    blocks.add(Paragraph(inlineMd(body), listLabel = label, listLevel = indent, indentPt = 18f * (indent + 1)))
                }
                Regex("""^\d{1,9}[.)]\s+""").containsMatchIn(t) -> {
                    flushPara()
                    val m = Regex("""^(\d{1,9})([.)])\s+""").find(t)!!
                    val n = m.groupValues[1].toInt()
                    olCounter[indent] = n
                    blocks.add(Paragraph(inlineMd(t.substring(m.range.last + 1)), listLabel = "$n${m.groupValues[2]}", listLevel = indent, indentPt = 18f * (indent + 1)))
                }
                t.startsWith("|") && i + 1 < lines.size && Regex("""^\|?\s*:?-{2,}""").containsMatchIn(lines[i + 1].trim()) -> {
                    flushPara()
                    val rows = ArrayList<TableRow>()
                    fun cells(l: String) = l.trim().removePrefix("|").removeSuffix("|").split('|').map { TableCell(listOf(Paragraph(inlineMd(it.trim())))) }
                    rows.add(TableRow(cells(t), header = true))
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows.add(TableRow(cells(lines[i]))); i++ }
                    blocks.add(Table(rows))
                    continue
                }
                t.isEmpty() -> flushPara()
                line.startsWith("    ") && para.isEmpty() -> {
                    val sb = StringBuilder()
                    while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) { sb.append(lines[i].removePrefix("    ")).append('\n'); i++ }
                    blocks.add(Paragraph(listOf(Span(sb.toString().trimEnd(), RunStyle(monospace = true))), code = true, shading = 0xFFF2F2F2.toInt()))
                    continue
                }
                i + 1 < lines.size && para.isEmpty() && Regex("""^=+\s*$""").matches(lines[i + 1]) -> { blocks.add(Paragraph(inlineMd(t, RunStyle(bold = true)), heading = 1)); i++ }
                i + 1 < lines.size && para.isEmpty() && Regex("""^-+\s*$""").matches(lines[i + 1]) && t.isNotEmpty() -> { blocks.add(Paragraph(inlineMd(t, RunStyle(bold = true)), heading = 2)); i++ }
                else -> { if (line.endsWith("  ")) para.append(t.trimEnd()).append('\n') else para.append(t).append(' ') }
            }
            i++
        }
        flushPara()
        val outline = blocks.withIndex().mapNotNull { (k, b) -> (b as? Paragraph)?.takeIf { it.heading in 1..3 }?.let { it.text to k } }
        val title = (blocks.firstOrNull { it is Paragraph && it.heading == 1 } as? Paragraph)?.text
        return FlowDocument(title, blocks, outline = outline, baseSizePt = 11f)
    }

    /** **bold**, *italic*, ~~strike~~, `code`, [text](link), ![alt](image) (shown as its alt text). */
    fun inlineMd(s: String, base: RunStyle = RunStyle.PLAIN): List<Span> {
        val out = ArrayList<Span>()
        var bold = base.bold; var italic = base.italic; var strike = false
        val sb = StringBuilder()
        fun style() = base.copy(bold = bold, italic = italic, strike = strike)
        fun flush(link: String? = null) { if (sb.isNotEmpty()) { out.add(Span(sb.toString(), style(), link)); sb.setLength(0) } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { sb.append(s[i + 1]); i += 2; continue }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) { flush(); out.add(Span(s.substring(i + 1, end), style().copy(monospace = true))); i = end + 1; continue }
                }
                (c == '*' || c == '_') && i + 1 < s.length && s[i + 1] == c -> { flush(); bold = !bold; i += 2; continue }
                (c == '*' || c == '_') && (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()) -> { flush(); italic = !italic; i++; continue }
                c == '~' && i + 1 < s.length && s[i + 1] == '~' -> { flush(); strike = !strike; i += 2; continue }
                c == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    val m = Regex("""^!\[([^\]]*)]\(([^)]*)\)""").find(s.substring(i))
                    if (m != null) { flush(); out.add(Span("[" + m.groupValues[1].ifBlank { "image" } + "]", style().copy(italic = true))); i += m.value.length; continue }
                }
                c == '[' -> {
                    val m = Regex("""^\[([^\]]*)]\(([^)\s]*)[^)]*\)""").find(s.substring(i))
                    if (m != null) { flush(); sb.append(m.groupValues[1]); flush(m.groupValues[2]); i += m.value.length; continue }
                }
                c == '<' && s.startsWith("http", i + 1) -> {
                    val end = s.indexOf('>', i)
                    if (end > i) { flush(); sb.append(s.substring(i + 1, end)); flush(s.substring(i + 1, end)); i = end + 1; continue }
                }
            }
            sb.append(c); i++
        }
        flush()
        return out
    }
}

/** CSV and TSV files as one sheet: the delimiter is found from the lines (comma, semicolon, tab or bar). */
object CsvReader {
    fun read(file: File, delimiter: Char?, maxCells: Int = 3_000_000): Workbook {
        val limit = 64 shl 20
        val text = TextDecode.decode(headOf(file, limit))
        val d = delimiter ?: guess(text)
        val grid = CellGrid()
        val widths = HashMap<Int, Int>()
        var r = 0; var c = 0
        val field = StringBuilder()
        var quoted = false; var i = 0; var fieldWasQuoted = false
        var cells = 0
        fun endField() {
            val v = if (field.length > 32767) field.substring(0, 32767) else field.toString()
            if (v.isNotEmpty()) {
                val num = !fieldWasQuoted && Regex("""^[-+]?(\d{1,3}(,\d{3})+|\d+)?(\.\d+)?([eE][-+]?\d+)?%?$""").matches(v.trim()) && v.trim().any { it.isDigit() }
                grid.put(r, c, Cell(v, if (num) CellKind.NUMBER else CellKind.TEXT))
                if (r < 300) widths[c] = maxOf(widths[c] ?: 0, v.length.coerceAtMost(60))
                cells++
            }
            field.setLength(0); fieldWasQuoted = false; c++
        }
        while (i < text.length && cells < maxCells) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false } else field.append(ch)
            } else when (ch) {
                '"' -> if (field.isEmpty()) { quoted = true; fieldWasQuoted = true } else field.append(ch)
                d -> endField()
                '\r' -> Unit
                '\n' -> { endField(); r++; c = 0 }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || c > 0) endField()
        val colWidths = widths.mapValues { (_, n) -> (n * 5.6f + 8f).coerceIn(30f, 320f) }
        val notes = if (cells >= maxCells || file.length() > limit) listOf(com.localmediatools.i18n.tr("This file is very large: only its first {0} cells are shown.", cells)) else emptyList()
        return Workbook(file.name, listOf(Sheet(file.name.substringBeforeLast('.'), grid, colWidths = colWidths)), notes = notes)
    }

    /** The delimiter that splits the first lines into the same number of fields (more than one). */
    fun guess(text: String): Char {
        val sample = text.lineSequence().take(50).filter { it.isNotBlank() }.toList()
        var best = ','; var bestScore = -1.0
        for (d in charArrayOf(',', ';', '\t', '|')) {
            val counts = sample.map { line -> var n = 0; var q = false; for (ch in line) { if (ch == '"') q = !q else if (ch == d && !q) n++ }; n }
            if (counts.isEmpty() || counts.all { it == 0 }) continue
            val mode = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
            val score = mode.value.toDouble() / counts.size * (if (mode.key > 0) 1.0 else 0.0) + mode.key * 0.001
            if (score > bestScore) { bestScore = score; best = d }
        }
        return best
    }
}
