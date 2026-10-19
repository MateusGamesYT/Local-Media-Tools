package com.localmediatools.docs.core

import com.localmediatools.i18n.tr

/**
 * PowerPoint 97–2003 presentations (.ppt, .pps): each slide's text where it sits on the slide (titles
 * and bodies as the slide's outline holds them, text boxes from its drawing), with bullets, and the
 * speaker notes. The current version of each slide is found through the file's edit history, so text
 * left in the file from older edits isn't shown. Pictures, colours and shapes of these files aren't
 * drawn; encrypted presentations say so.
 */
class PptReader private constructor(private val doc: ByteArray, private val user: ByteArray?) {
    companion object {
        fun read(ole: Ole2): Deck {
            val doc = ole.stream("PowerPoint Document") ?: throw UnreadableException(tr("This PowerPoint file is damaged: its slides are missing."))
            return PptReader(doc, ole.stream("Current User")).read()
        }
    }

    private class Rec(val ver: Int, val instance: Int, val type: Int, val start: Int, val len: Int) { val end get() = start + len; val container get() = ver == 0xF }

    private fun rec(at: Int): Rec? {
        if (at < 0 || at + 8 > doc.size) return null
        val vi = le16(doc, at)
        val len = le32(doc, at + 4)
        if (len < 0 || at + 8 + len > doc.size) return null
        return Rec(vi and 0xF, vi ushr 4, le16(doc, at + 2), at + 8, len)
    }

    private fun children(r: Rec): List<Rec> {
        val out = ArrayList<Rec>()
        var p = r.start
        while (p + 8 <= r.end) { val c = rec(p) ?: break; out.add(c); p = c.end }
        return out
    }

    private fun persistMap(): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        var editOffset = user?.let { u -> if (u.size >= 20) le32(u, 16) else null } ?: findLastUserEdit()
        val seen = HashSet<Int>()
        while (editOffset > 0 && seen.add(editOffset)) {
            val ue = rec(editOffset) ?: break
            if (ue.type != 0x0FF5) break
            if (docPersist == 0) docPersist = le32(doc, ue.start + 16)
            val dirOff = le32(doc, ue.start + 12)
            rec(dirOff)?.takeIf { it.type == 0x1772 }?.let { dir ->
                var p = dir.start
                while (p + 4 <= dir.end) {
                    val v = le32(doc, p); p += 4
                    val id = v and 0xFFFFF; val n = v ushr 20
                    for (k in 0 until n) { if (p + 4 > dir.end) break; map.putIfAbsent(id + k, le32(doc, p)); p += 4 }
                }
            }
            editOffset = le32(doc, ue.start + 8)
        }
        return map
    }

    private var docPersist = 0

    private fun findLastUserEdit(): Int {
        var p = 0; var last = -1
        while (true) { val r = rec(p) ?: break; if (r.type == 0x0FF5) last = p; p = r.end }
        return last
    }

    private class TextItem(val type: Int, val text: String)
    private class Placed(val x: Float, val y: Float, val w: Float, val h: Float, val text: String, val type: Int, val outlineRef: Int?)

    private fun read(): Deck {
        if (user != null && user.size >= 16 && le32(user, 12) == 0xF3D1C4DF.toInt()) {
            throw UnreadableException(tr("This file is protected with a password, which this app can't open."))
        }
        val persist = persistMap()
        val docRec = persist[docPersist]?.let { rec(it) }?.takeIf { it.type == 0x03E8 } ?: run {
            var p = 0; var found: Rec? = null
            while (true) { val r = rec(p) ?: break; if (r.type == 0x03E8) found = r; p = r.end }
            found
        } ?: throw UnreadableException(tr("This PowerPoint file is damaged: its slides are missing."))
        var w = 720f; var h = 540f
        val slideTexts = ArrayList<Pair<Int, MutableList<TextItem>>>() // persist id → outline texts
        val notesBySlideId = HashMap<Int, Int>() // notes slide id → its persist id
        for (c in children(docRec)) {
            when {
                c.type == 0x03E9 -> { w = le32(doc, c.start) / 8f; h = le32(doc, c.start + 4) / 8f }
                c.type == 0x0FF0 && (c.instance == 0 || c.instance == 2) -> {
                    var current: MutableList<TextItem>? = null
                    var type = 4
                    for (k in children(c)) when (k.type) {
                        0x03F3 -> {
                            val id = le32(doc, k.start); current = ArrayList()
                            if (c.instance == 0) slideTexts.add(id to current!!) else notesBySlideId[le32(doc, k.start + 12)] = id
                        }
                        0x0F9F -> type = le32(doc, k.start)
                        0x0FA0 -> current?.add(TextItem(type, String(doc, k.start, k.len, Charsets.UTF_16LE)))
                        0x0FA8 -> current?.add(TextItem(type, String(doc, k.start, k.len, Charsets.ISO_8859_1)))
                    }
                }
            }
        }
        if (w <= 0 || h <= 0) { w = 720f; h = 540f }
        val slides = ArrayList<Slide>()
        for ((id, outline) in slideTexts) {
            val sr = persist[id]?.let { rec(it) }?.takeIf { it.type == 0x03EE }
            val placed = ArrayList<Placed>()
            var notesId: Int? = null
            if (sr != null) {
                for (c in children(sr)) {
                    if (c.type == 0x03EF) notesId = le32(doc, c.start + 16).takeIf { it != 0 }?.let { notesBySlideId[it] }
                    if (c.type == 0x040C) drawing(c, placed)
                }
            }
            val shapes = ArrayList<Shape>()
            val usedOutline = HashSet<Int>()
            for (p in placed) {
                val t = if (p.outlineRef != null) outline.getOrNull(p.outlineRef)?.also { usedOutline.add(p.outlineRef) }?.text else p.text
                val type = if (p.outlineRef != null) outline.getOrNull(p.outlineRef)?.type ?: p.type else p.type
                if (t.isNullOrBlank()) continue
                shapes.add(textShape(p.x, p.y, p.w, p.h, t, type))
            }
            // Outline text with no shape of its own: title on top, the rest below.
            var y = 30f
            for ((k, item) in outline.withIndex()) {
                if (k in usedOutline || item.text.isBlank()) continue
                val title = item.type == 0 || item.type == 6
                val hh = if (title) h * 0.18f else h * 0.6f
                shapes.add(textShape(w * 0.06f, y, w * 0.88f, hh, item.text, item.type))
                y += hh + 10f
            }
            val notes = notesId?.let { nid -> persist[nid]?.let { rec(it) } }?.let { nr -> notesText(nr) } ?: emptyList()
            slides.add(Slide(shapes, 0xFFFFFFFF.toInt(), null, notes))
        }
        if (slides.isEmpty()) throw UnreadableException(tr("The slides of this presentation can't be read."))
        return Deck(null, slides, w, h, notes = listOf(tr("Pictures and colours in PowerPoint 97–2003 files aren't shown.")))
    }

    private fun notesText(r: Rec): List<Paragraph> {
        val placed = ArrayList<Placed>()
        for (c in children(r)) if (c.type == 0x040C) drawing(c, placed)
        return placed.filter { it.type == 2 || it.type == 4 || it.type == 1 }.flatMap { para(it.text, 4) }.filter { !it.isEmpty() }
    }

    /** Shapes of a slide's drawing: their place, their own text, or which outline text they show. */
    private fun drawing(r: Rec, out: MutableList<Placed>) {
        fun walk(c: Rec) {
            if (c.type == 0xF004) { shape(c)?.let { out.add(it) }; return }
            if (c.container) for (k in children(c)) walk(k)
        }
        for (c in children(r)) walk(c)
    }

    private fun shape(sp: Rec): Placed? {
        var x = 0f; var y = 0f; var x2 = 0f; var y2 = 0f; var text = StringBuilder(); var type = 4; var ref: Int? = null
        var anchored = false
        fun walk(c: Rec) {
            when (c.type) {
                0xF010 -> if (c.len >= 16) { y = le32(doc, c.start) / 8f; x = le32(doc, c.start + 4) / 8f; x2 = le32(doc, c.start + 8) / 8f; y2 = le32(doc, c.start + 12) / 8f; anchored = true }
                    else if (c.len >= 8) { y = le16s(c.start) / 8f; x = le16s(c.start + 2) / 8f; x2 = le16s(c.start + 4) / 8f; y2 = le16s(c.start + 6) / 8f; anchored = true }
                0x0F9F -> type = le32(doc, c.start)
                0x0FA0 -> text.append(String(doc, c.start, c.len, Charsets.UTF_16LE))
                0x0FA8 -> text.append(String(doc, c.start, c.len, Charsets.ISO_8859_1))
                0x0F9E -> ref = le32(doc, c.start)
                else -> if (c.container || c.type == 0xF00D || c.type == 0xF011) for (k in children(c)) walk(k)
            }
        }
        for (c in children(sp)) walk(c)
        if (!anchored) return null
        return Placed(x, y, (x2 - x).coerceAtLeast(10f), (y2 - y).coerceAtLeast(10f), text.toString(), type, ref)
    }

    private fun le16s(o: Int): Int = le16(doc, o).toShort().toInt()

    private fun para(text: String, type: Int): List<Paragraph> {
        val title = type == 0 || type == 6
        val size = when (type) { 0 -> 36f; 6 -> 40f; 5 -> 28f; 1 -> 24f; else -> 18f }
        return text.split('\r', '\u000B').map { line ->
            val bullet = type == 1 && line.isNotBlank()
            Paragraph(listOf(Span(line.replace('\t', ' '), RunStyle(bold = title, sizePt = size, color = 0xFF000000.toInt()))), align = if (title || type == 5 || type == 6) Align.CENTER else Align.START,
                listLabel = if (bullet) "•" else null, indentPt = if (bullet) 20f else 0f)
        }
    }

    private fun textShape(x: Float, y: Float, w: Float, h: Float, text: String, type: Int): Shape {
        val paras = para(text, type)
        val title = type == 0 || type == 6
        return Shape(x, y, w, h, text = TextBody(paras, anchor = if (title) Anchor.MIDDLE else Anchor.TOP, defaultSizePt = paras.firstOrNull()?.spans?.firstOrNull()?.style?.sizePt ?: 18f))
    }
}
