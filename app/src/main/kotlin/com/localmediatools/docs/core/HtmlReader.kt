package com.localmediatools.docs.core

import com.localmediatools.docs.core.XmlReader.Event
import com.localmediatools.i18n.tr

/**
 * HTML and XHTML (web pages saved as files, e-book chapters) as flowing text: headings, paragraphs,
 * bold, italic, underline, strike, code, links, lists, quotes, preformatted text, tables, rules and
 * pictures. Pictures come only from inside the file ([resolveImage]); anything on the internet is
 * never fetched and shows as its description. Scripts, styles and form controls are left out.
 */
class HtmlReader private constructor(private val resolveImage: (String) -> String?) {
    companion object {
        fun read(bytes: ByteArray, name: String): FlowDocument {
            val r = HtmlReader { null }
            val blocks = r.convert(XmlReader.of(bytes, html = true))
            val notes = if (r.remoteImages > 0) listOf(com.localmediatools.i18n.trn(r.remoteImages, "{0} picture from the internet isn't shown.", "{0} pictures from the internet aren't shown.")) else emptyList()
            return FlowDocument(r.title ?: name, blocks, notes = notes, outline = outlineOf(blocks))
        }

        /** Blocks of one XHTML part of a package (an e-book chapter); [resolveImage] maps a src to a part name. */
        fun blocks(x: XmlReader, resolveImage: (String) -> String?): Pair<List<Block>, String?> {
            val r = HtmlReader(resolveImage)
            return r.convert(x) to r.title
        }

        fun outlineOf(blocks: List<Block>) = blocks.withIndex().mapNotNull { (i, b) -> (b as? Paragraph)?.takeIf { it.heading in 1..3 && it.text.isNotBlank() }?.let { it.text.trim().take(80) to i } }
    }

    var title: String? = null; private set
    var remoteImages = 0; private set

    private class Ctx(
        val style: RunStyle = RunStyle.PLAIN,
        val link: String? = null,
        val pre: Boolean = false,
    )

    private class ParaB {
        val spans = ArrayList<Span>()
        var heading = 0
        var align = Align.START
        var quote = false
        var code = false
        var listLabel: String? = null
        var listLevel = 0
        var indent = 0f
    }

    private val out = ArrayList<Block>()
    private var cur = ParaB()
    private var pendingLabel: String? = null
    private var quoteDepth = 0
    private var listDepth = 0
    private var headingLevel = 0
    private var align = Align.START

    private fun convert(x: XmlReader): List<Block> {
        while (x.next() != Event.EOF) {
            if (x.event == Event.START) element(x, Ctx(), out)
            else if (x.event == Event.TEXT) text(x.text, Ctx())
        }
        flush(out)
        return out.filter { it !is Paragraph || !it.isEmpty() || it.code }
    }

    private fun flush(target: MutableList<Block>) {
        val b = cur
        cur = ParaB()
        // Collapse whitespace at the edges.
        val spans = b.spans
        if (!b.code) {
            while (spans.isNotEmpty() && spans.first().text.isBlank()) spans.removeAt(0)
            while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.size - 1)
            if (spans.isNotEmpty()) spans[0] = spans[0].let { Span(it.text.trimStart(), it.style, it.link) }
            if (spans.isNotEmpty()) spans[spans.size - 1] = spans.last().let { Span(it.text.trimEnd(), it.style, it.link) }
        }
        val label = b.listLabel ?: pendingLabel
        if (spans.isEmpty() && label == null) return
        pendingLabel = null
        target.add(Paragraph(spans.toList(), b.heading, b.align, b.indent, 0f, if (b.heading > 0) 8f else 0f, 6f, label, b.listLevel, if (b.code) 0xFFF2F2F2.toInt() else 0, b.quote, b.code))
    }

    private fun startBlock(target: MutableList<Block>) {
        flush(target)
        cur.heading = headingLevel
        cur.align = align
        cur.quote = quoteDepth > 0
        cur.indent = 18f * (listDepth + quoteDepth)
        cur.listLevel = (listDepth - 1).coerceAtLeast(0)
    }

    private fun text(t: String, ctx: Ctx) {
        if (t.isEmpty()) return
        val s = if (ctx.pre) t else t.replace(Regex("\\s+"), " ")
        if (!ctx.pre && s == " " && (cur.spans.isEmpty() || cur.spans.last().text.endsWith(" "))) return
        val last = cur.spans.lastOrNull()
        val add = if (!ctx.pre && last != null && last.text.endsWith(" ") && s.startsWith(" ")) s.substring(1) else s
        if (add.isEmpty()) return
        if (last != null && last.style == ctx.style && last.link == ctx.link) cur.spans[cur.spans.size - 1] = Span(last.text + add, ctx.style, ctx.link)
        else cur.spans.add(Span(add, ctx.style, ctx.link))
    }

    private fun inlineStyle(x: XmlReader, base: RunStyle): RunStyle {
        var s = base
        val css = x.attr("style")?.lowercase() ?: ""
        if (css.isNotEmpty()) {
            if (Regex("""font-weight\s*:\s*(bold|[6-9]00)""").containsMatchIn(css)) s = s.copy(bold = true)
            if (Regex("""font-style\s*:\s*italic""").containsMatchIn(css)) s = s.copy(italic = true)
            if (css.contains("underline")) s = s.copy(underline = true)
            if (css.contains("line-through")) s = s.copy(strike = true)
            Regex("""(?<![-\w])color\s*:\s*(#[0-9a-f]{3,6})""").find(css)?.let { m -> Units.hexColor(m.groupValues[1])?.let { s = s.copy(color = it) } }
            Regex("""background(?:-color)?\s*:\s*(#[0-9a-f]{3,6})""").find(css)?.let { m -> Units.hexColor(m.groupValues[1])?.let { if (it != 0xFFFFFFFF.toInt()) s = s.copy(highlight = it) } }
            if (css.contains("monospace")) s = s.copy(monospace = true)
        }
        return s
    }

    private fun blockAlign(x: XmlReader): Align? {
        val a = (x.attr("align") ?: Regex("""text-align\s*:\s*(\w+)""").find(x.attr("style")?.lowercase() ?: "")?.groupValues?.get(1))?.lowercase()
        return when (a) { "center" -> Align.CENTER; "right" -> Align.END; "justify" -> Align.JUSTIFY; "left" -> Align.START; else -> null }
    }

    private fun children(x: XmlReader, ctx: Ctx, target: MutableList<Block>) {
        x.children { c ->
            when (c.event) {
                Event.START -> element(c, ctx, target)
                Event.TEXT -> text(c.text, ctx)
                else -> Unit
            }
        }
    }

    private var level = 0

    private fun element(x: XmlReader, ctx: Ctx, target: MutableList<Block>) {
        // Nesting beyond reason (generated or crafted pages): the rest is read as plain text.
        if (level > 160) { text(x.readText(), ctx); return }
        level++
        try { elementBody(x, ctx, target) } finally { level-- }
    }

    private fun elementBody(x: XmlReader, ctx: Ctx, target: MutableList<Block>) {
        val name = x.local.lowercase()
        when (name) {
            "head" -> { x.children { c -> if (c.event == Event.START && c.local.equals("title", true)) title = c.readText().trim().ifEmpty { null } else if (c.event == Event.START) c.skip() } }
            "script", "style", "noscript", "template", "iframe", "object", "embed", "input", "select", "textarea", "button", "svg", "math", "canvas", "video", "audio", "nav" -> x.skip()
            "br" -> { text("\n", ctx.let { Ctx(it.style, it.link, true) }); x.skip() }
            "hr" -> { flush(target); target.add(Rule); x.skip() }
            "img", "image" -> {
                val src = x.attr("src") ?: x.attr(Ns.XLINK, "href") ?: x.attr("href")
                val alt = x.attr("alt")?.takeIf { it.isNotBlank() }
                val ref = src?.let { if (it.startsWith("http:") || it.startsWith("https:") || it.startsWith("//")) null else resolveImage(it) }
                if (src != null && ref == null && (src.startsWith("http") || src.startsWith("//"))) remoteImages++
                val w = x.attr("width")?.toFloatOrNull()?.times(0.75f) ?: 0f
                val h = x.attr("height")?.toFloatOrNull()?.times(0.75f) ?: 0f
                x.skip()
                if (ref != null) { flush(target); target.add(Picture(ref, w, h, alt)) }
                else if (alt != null) text("[$alt]", Ctx(ctx.style.copy(italic = true), ctx.link, ctx.pre))
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val saved = headingLevel; val savedAlign = align
                headingLevel = name[1] - '0'
                blockAlign(x)?.let { align = it }
                startBlock(target)
                children(x, Ctx(ctx.style.copy(bold = true), ctx.link, ctx.pre), target)
                flush(target)
                headingLevel = saved; align = savedAlign
            }
            "p", "div", "section", "article", "header", "footer", "main", "aside", "figure", "figcaption", "address", "center", "dt", "dd", "caption", "summary", "details", "body", "html" -> {
                val savedAlign = align
                (if (name == "center") Align.CENTER else blockAlign(x))?.let { align = it }
                startBlock(target)
                val style = when (name) { "dt" -> ctx.style.copy(bold = true); "figcaption", "caption" -> ctx.style.copy(italic = true); else -> ctx.style }
                if (name == "dd") cur.indent += 18f
                children(x, Ctx(inlineStyle(x, style), ctx.link, ctx.pre), target)
                flush(target)
                align = savedAlign
            }
            "blockquote" -> { quoteDepth++; startBlock(target); children(x, ctx, target); flush(target); quoteDepth-- }
            "pre" -> {
                startBlock(target)
                cur.code = true
                children(x, Ctx(ctx.style.copy(monospace = true), ctx.link, true), target)
                flush(target)
            }
            "ul", "ol", "menu", "dir" -> {
                flush(target)
                listDepth++
                var n = x.attr("start")?.toIntOrNull() ?: 1
                val type = x.attr("type")
                x.children { c ->
                    when (c.event) {
                        Event.START -> if (c.local.equals("li", true)) {
                            val label = if (name == "ol") Units.formatNumber(n++, type ?: "1") + "." else if (listDepth % 2 == 0) "◦" else "•"
                            startBlock(target)
                            cur.listLabel = label
                            children(c, ctx, target)
                            flush(target)
                        } else element(c, ctx, target)
                        Event.TEXT -> text(c.text, ctx)
                        else -> Unit
                    }
                }
                listDepth--
            }
            "li" -> { startBlock(target); cur.listLabel = "•"; children(x, ctx, target); flush(target) }
            "table" -> { flush(target); target.add(table(x, ctx)) }
            "a" -> {
                val href = x.attr("href")?.takeIf { it.isNotBlank() && !it.startsWith("javascript:") }
                children(x, Ctx(inlineStyle(x, if (href != null && !href.startsWith("#")) ctx.style.copy(underline = true, color = 0xFF1A5FB4.toInt()) else ctx.style), href ?: ctx.link, ctx.pre), target)
            }
            "b", "strong" -> children(x, Ctx(inlineStyle(x, ctx.style.copy(bold = true)), ctx.link, ctx.pre), target)
            "i", "em", "cite", "dfn", "var" -> children(x, Ctx(inlineStyle(x, ctx.style.copy(italic = true)), ctx.link, ctx.pre), target)
            "u", "ins" -> children(x, Ctx(inlineStyle(x, ctx.style.copy(underline = true)), ctx.link, ctx.pre), target)
            "s", "strike", "del" -> children(x, Ctx(inlineStyle(x, ctx.style.copy(strike = true)), ctx.link, ctx.pre), target)
            "code", "kbd", "tt", "samp" -> children(x, Ctx(inlineStyle(x, ctx.style.copy(monospace = true)), ctx.link, ctx.pre), target)
            "sup" -> children(x, Ctx(ctx.style.copy(vertical = 1), ctx.link, ctx.pre), target)
            "sub" -> children(x, Ctx(ctx.style.copy(vertical = -1), ctx.link, ctx.pre), target)
            "mark" -> children(x, Ctx(ctx.style.copy(highlight = 0xFFFFFF00.toInt()), ctx.link, ctx.pre), target)
            "small" -> children(x, Ctx(ctx.style.copy(sizePt = 9f), ctx.link, ctx.pre), target)
            "font" -> {
                var s = ctx.style
                Units.hexColor(x.attr("color"))?.let { s = s.copy(color = it) }
                children(x, Ctx(inlineStyle(x, s), ctx.link, ctx.pre), target)
            }
            else -> children(x, Ctx(inlineStyle(x, ctx.style), ctx.link, ctx.pre), target)
        }
    }

    private fun table(x: XmlReader, ctx: Ctx): Block {
        val rows = ArrayList<TableRow>()
        var caption: String? = null
        fun row(r: XmlReader, headerSection: Boolean) {
            val cells = ArrayList<TableCell>()
            var header = headerSection
            r.children { c ->
                if (c.event != Event.START) return@children
                val n = c.local.lowercase()
                if (n == "td" || n == "th") {
                    if (n == "th") header = true
                    val inner = ArrayList<Block>()
                    val savedCur = cur; val savedLabel = pendingLabel; val savedList = listDepth; val savedQuote = quoteDepth
                    cur = ParaB(); listDepth = 0; quoteDepth = 0
                    cur.align = blockAlign(c) ?: Align.START
                    children(c, Ctx(if (n == "th") ctx.style.copy(bold = true) else ctx.style, ctx.link, ctx.pre), inner)
                    flush(inner)
                    cur = savedCur; pendingLabel = savedLabel; listDepth = savedList; quoteDepth = savedQuote
                    val span = c.attr("colspan")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                    val rspan = c.attr("rowspan")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
                    val bg = Units.hexColor(c.attr("bgcolor")) ?: 0
                    cells.add(TableCell(inner.filter { it !is Paragraph || !it.isEmpty() }, span, rspan, bg))
                } else c.skip()
            }
            if (cells.isNotEmpty()) rows.add(TableRow(cells, header))
        }
        x.children { c ->
            if (c.event != Event.START) return@children
            when (c.local.lowercase()) {
                "tr" -> row(c, false)
                "thead" -> c.children { r -> if (r.event == Event.START && r.local.equals("tr", true)) row(r, true) else if (r.event == Event.START) r.skip() }
                "tbody", "tfoot" -> c.children { r -> if (r.event == Event.START && r.local.equals("tr", true)) row(r, false) else if (r.event == Event.START) r.skip() }
                "caption" -> caption = c.readText().trim()
                else -> c.skip()
            }
        }
        val border = x.attr("border")?.let { it != "0" } ?: true
        if (rows.isEmpty()) return Paragraph(listOf(Span(caption ?: "")))
        return Table(rows, border)
    }
}

/** E-books (EPUB 2 and 3): the chapters in reading order, with their pictures and the table of contents. */
object EpubReader {
    fun read(parts: Parts): FlowDocument {
        val opf = opfPath(parts) ?: throw UnreadableException(tr("This e-book is damaged: its list of chapters is missing."))
        val x = parts.xml(opf) ?: throw UnreadableException(tr("This e-book is damaged: its list of chapters is missing."))
        val manifest = HashMap<String, Pair<String, String>>() // id → (part, media type)
        val spine = ArrayList<String>()
        var title: String? = null
        var encrypted = parts.has("META-INF/encryption.xml")
        while (x.next() != Event.EOF) {
            if (x.event != Event.START) continue
            when (x.local) {
                "title" -> if (title == null && x.ns == Ns.DC) title = x.readText().trim().ifEmpty { null }
                "item" -> {
                    val id = x.attr("id"); val href = x.attr("href")
                    if (id != null && href != null) manifest[id] = Parts.resolve(opf, Parts.decodePercent(href.substringBefore('#'))) to (x.attr("media-type") ?: "")
                }
                "itemref" -> x.attr("idref")?.let { if (x.attr("linear") != "no") spine.add(it) }
            }
        }
        if (encrypted) {
            // Fonts are often "encrypted" (obfuscated) in ordinary e-books; DRM'd books encrypt the chapters.
            val enc = parts.bytes("META-INF/encryption.xml")?.let { String(it, Charsets.UTF_8) } ?: ""
            encrypted = spine.any { id -> manifest[id]?.first?.let { p -> enc.contains(p.substringAfterLast('/')) } == true }
            if (encrypted) throw UnreadableException(tr("This e-book is protected with DRM, so it can only be read in the app it was bought for."))
        }
        val blocks = ArrayList<Block>()
        val outline = ArrayList<Pair<String, Int>>()
        for ((k, id) in spine.withIndex()) {
            val (part, type) = manifest[id] ?: continue
            if (!(type.contains("html") || type.contains("xml") || part.endsWith(".html") || part.endsWith(".xhtml") || part.endsWith(".htm"))) continue
            val cx = parts.xml(part, html = true) ?: continue
            val (chapter, chapterTitle) = HtmlReader.blocks(cx) { src -> Parts.resolve(part, Parts.decodePercent(src.substringBefore('#'))).takeIf { parts.has(it) } }
            if (chapter.isEmpty()) continue
            if (k > 0 && blocks.isNotEmpty()) blocks.add(PageBreak)
            val label = (chapter.firstOrNull { it is Paragraph && it.heading in 1..3 } as? Paragraph)?.text?.trim()?.take(80) ?: chapterTitle
            if (label != null) outline.add(label to blocks.size)
            blocks.addAll(chapter)
        }
        if (blocks.isEmpty()) throw UnreadableException(tr("This e-book has no chapters that can be shown."))
        return FlowDocument(title, blocks, Media { ref -> parts.bytes(ref, 32L shl 20) }, outline = outline, baseSizePt = 11f)
    }

    private fun opfPath(parts: Parts): String? {
        parts.xml("META-INF/container.xml")?.let { x ->
            while (x.next() != Event.EOF) if (x.event == Event.START && x.local == "rootfile") x.attr("full-path")?.let { return Parts.normalise(Parts.decodePercent(it)) }
        }
        return parts.names.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }
}
